#!/usr/bin/env python3
#
# Copyright 2018 - The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Functional test for aidegen project files."""

from __future__ import absolute_import

import argparse
import itertools
import json
import os
import sys
import xml.etree.ElementTree
import xml.parsers.expat

import aidegen.lib.errors

from aidegen import aidegen_main
from aidegen.lib.common_util import get_related_paths
from aidegen.lib.common_util import time_logged
from atest import constants
from atest import module_info
from atest import atest_utils

_ANDROID_ROOT_PATH = os.environ.get(constants.ANDROID_BUILD_TOP)
_ROOT_DIR = os.path.join(_ANDROID_ROOT_PATH,
                         'tools/asuite/aidegen_functional_test')
_TEST_DATA_PATH = os.path.join(_ROOT_DIR, 'test_data')
_ANDROID_SINGLE_PROJECT_JSON = os.path.join(_TEST_DATA_PATH,
                                            'single_module.json')
_VERIFY_COMMANDS_JSON = os.path.join(_TEST_DATA_PATH, 'verify_commands.json')
_PRODUCT_DIR = '$PROJECT_DIR$'
_ANDROID_COMMON = 'android_common'
_LINUX_GLIBC_COMMON = 'linux_glibc_common'
_SRCS = 'srcs'
_JARS = 'jars'
_URL = 'url'
_TEST_ERROR = ('AIDEGen functional test error: %s-%s is different.')
_MSG_NOT_IN_PROJECT_FILE = ('%s is expected, but not found in the created '
                            'project file: %s')
_MSG_NOT_IN_SAMPLE_DATA = ('%s is unexpected, but found in the created project '
                           'file: %s')
_TEST_IML_DICT = {
    'SystemUI': ['SystemUI.iml', 'dependencies-SystemUI.iml'],
    'tradefed': ['core.iml', 'dependencies-core.iml']
}
_ALL_PASS = 'All tests passed!'


def _parse_args(args):
    """Parse command line arguments.

    Args:
        args: A list of arguments.

    Returns:
        An argparse.Namespace class instance holding parsed args.
    """
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
        usage='aidegen_functional_test [-c | -v]')
    group = parser.add_mutually_exclusive_group()
    parser.required = False
    group.add_argument(
        '-c',
        '--create-sample',
        action='store_true',
        dest='create_sample',
        help=('Create aidegen project files and write data to sample json file '
              'for aidegen_functional_test to compare.'))
    group.add_argument(
        '-v',
        '--verify',
        action='store_true',
        dest='verify_aidegen',
        help='Verify various use cases of executing aidegen.')
    return parser.parse_args(args)


def _import_project_file_xml_etree(filename):
    """Import iml project file and load data into a dictionary.

    Args:
        filename: The input project file name.
    """
    data = {}
    try:
        tree = xml.etree.ElementTree.parse(filename)
        data[_SRCS] = []
        root = tree.getroot()
        for element in root.iter('sourceFolder'):
            src = element.get(_URL).replace(_ANDROID_ROOT_PATH, _PRODUCT_DIR)
            data[_SRCS].append(src)
        data[_JARS] = []
        for element in root.iter('root'):
            jar = element.get(_URL).replace(_ANDROID_ROOT_PATH, _PRODUCT_DIR)
            data[_JARS].append(jar)
    except (EnvironmentError, ValueError, LookupError,
            xml.parsers.expat.ExpatError) as err:
        print("{0}: import error: {1}".format(os.path.basename(filename), err))
        raise
    return data


def _generate_sample_json():
    """Generate sample iml data and write into a json file."""
    atest_module_info = module_info.ModuleInfo()
    data = {}
    for target, filelist in _TEST_IML_DICT.items():
        aidegen_main.main([target, '-n'])
        _, abs_path = get_related_paths(atest_module_info, target)
        for filename in filelist:
            real_iml_file = os.path.join(abs_path, filename)
            item_name = os.path.basename(real_iml_file)
            data[item_name] = _import_project_file_xml_etree(real_iml_file)
    return data


def _create_sample_json_file():
    """Write samples' iml data into a json file.

    linked_function: _generate_sample_json()
    """
    data = _generate_sample_json()
    with open(_ANDROID_SINGLE_PROJECT_JSON, 'w') as outfile:
        json.dump(data, outfile, indent=4, sort_keys=False)


def test_some_sample_iml():
    """Compare sample iml data to assure project iml file contents is right."""
    test_successful = True
    with open(_ANDROID_SINGLE_PROJECT_JSON, 'r') as outfile:
        data_sample = json.load(outfile)
    data_real = _generate_sample_json()
    for name in data_real:
        for item in [_SRCS, _JARS]:
            s_items = data_sample[name][item]
            r_items = data_real[name][item]
            if set(s_items) != set(r_items):
                diff_iter = _compare_content(name, item, s_items, r_items)
                if diff_iter:
                    print('\n%s\n%s' % (atest_utils.colorize(
                        'Test error...', constants.RED), _TEST_ERROR %
                                        (name, item)))
                    print('%s %s contents are different:' % (name, item))
                    for diff in diff_iter:
                        print(diff)
                    test_successful = False
    if test_successful:
        print(atest_utils.colorize(_ALL_PASS, constants.GREEN))


def _compare_content(module_name, item_type, s_items, r_items):
    """Compare src or jar files' data of two dictionaries.

    Args:
        module_name: the test module name.
        item_type: the type is src or jar.
        s_items: sample jars' items.
        r_items: real jars' items.

    Returns:
        An iterator of not equal sentences after comparison.
    """
    if item_type == _SRCS:
        cmp_iter1 = _compare_srcs_content(module_name, s_items, r_items,
                                          _MSG_NOT_IN_PROJECT_FILE)
        cmp_iter2 = _compare_srcs_content(module_name, r_items, s_items,
                                          _MSG_NOT_IN_SAMPLE_DATA)
    else:
        cmp_iter1 = _compare_jars_content(module_name, s_items, r_items,
                                          _MSG_NOT_IN_PROJECT_FILE)
        cmp_iter2 = _compare_jars_content(module_name, r_items, s_items,
                                          _MSG_NOT_IN_SAMPLE_DATA)
    return itertools.chain(cmp_iter1, cmp_iter2)


def _compare_srcs_content(module_name, s_items, r_items, msg):
    """Compare src or jar files' data of two dictionaries.

    Args:
        module_name: the test module name.
        s_items: sample jars' items.
        r_items: real jars' items.
        msg: the message will be written into log file.

    Returns:
        An iterator of not equal sentences after comparison.
    """
    for sample in s_items:
        if not sample in r_items:
            yield msg % (sample, module_name)


def _compare_jars_content(module_name, s_items, r_items, msg):
    """Compare src or jar files' data of two dictionaries.

    Args:
        module_name: the test module name.
        s_items: sample jars' items.
        r_items: real jars' items.
        msg: the message will be written into log file.

    Returns:
        An iterator of not equal sentences after comparison.
    """
    for sample in s_items:
        if not sample in r_items:
            lnew = sample
            if _LINUX_GLIBC_COMMON in sample:
                lnew = sample.replace(_LINUX_GLIBC_COMMON, _ANDROID_COMMON)
            else:
                lnew = sample.replace(_ANDROID_COMMON, _LINUX_GLIBC_COMMON)
            if not lnew in r_items:
                yield msg % (sample, module_name)


# pylint: disable=broad-except
# pylint: disable=eval-used
@time_logged
def _verify_aidegen():
    """Verify various use cases of executing aidegen."""
    with open(_VERIFY_COMMANDS_JSON, 'r') as jsfile:
        data = json.load(jsfile)
    for use_case in data:
        for cmd in data[use_case]:
            try:
                eval(cmd)
            except (aidegen.lib.errors.ProjectOutsideAndroidRootError,
                    aidegen.lib.errors.ProjectPathNotExistError,
                    aidegen.lib.errors.NoModuleDefinedInModuleInfoError,
                    aidegen.lib.errors.IDENotExistError) as err:
                print('{} command has raise error: {}.'.format(use_case, err))
            except Exception as exp:
                print('{}.{} command {}.'.format(
                    use_case, cmd,
                    atest_utils.colorize('executes failed', constants.RED)))
                raise Exception(
                    'Unexpected command {} exception {}.'.format(use_case, exp))
        print('{} command {}!'.format(
            use_case, atest_utils.colorize('test passed', constants.GREEN)))
    print(atest_utils.colorize(_ALL_PASS, constants.GREEN))


def main(argv):
    """Main entry.

    Compare iml project files to the data recorded in single_module.json.

    Args:
        argv: A list of system arguments.
    """
    args = _parse_args(argv)
    if args.create_sample:
        _create_sample_json_file()
    elif args.verify_aidegen:
        _verify_aidegen()
    else:
        test_some_sample_iml()


if __name__ == '__main__':
    main(sys.argv[1:])
