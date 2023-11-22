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

"""AIDEgen

This CLI generates project files for using in IntelliJ, such as:
    - iml
    - .idea/compiler.xml
    - .idea/misc.xml
    - .idea/modules.xml
    - .idea/vcs.xml
    - .idea/.name
    - .idea/copyright/Apache_2.xml
    - .idea/copyright/progiles_settings.xml

- Sample usage:
    - Change directory to AOSP root first.
    $ cd /user/home/aosp/
    - Generating project files under packages/apps/Settings folder.
    $ aidegen packages/apps/Settings
    or
    $ aidegen Settings
    or
    $ cd packages/apps/Settings;aidegen
"""

from __future__ import absolute_import

import argparse
import logging
import os
import sys
import traceback

from aidegen import constant
from aidegen.lib.android_dev_os import AndroidDevOS
from aidegen.lib import common_util
from aidegen.lib.common_util import COLORED_INFO
from aidegen.lib.common_util import COLORED_PASS
from aidegen.lib.common_util import is_android_root
from aidegen.lib.common_util import time_logged
from aidegen.lib.errors import AIDEgenError
from aidegen.lib.errors import IDENotExistError
from aidegen.lib.ide_util import IdeUtil
from aidegen.lib.aidegen_metrics import starts_asuite_metrics
from aidegen.lib.aidegen_metrics import ends_asuite_metrics
from aidegen.lib.module_info_util import generate_module_info_json
from aidegen.lib.project_file_gen import generate_eclipse_project_files
from aidegen.lib.project_file_gen import generate_ide_project_files
from aidegen.lib.project_info import ProjectInfo
from aidegen.lib.source_locator import multi_projects_locate_source

AIDEGEN_REPORT_LINK = ('To report the AIDEGen tool problem, please use this '
                       'link: https://goto.google.com/aidegen-bug')
_NO_LAUNCH_IDE_CMD = """
Can not find IDE in path: {}, you can:
    - add IDE executable to your $PATH
or  - specify the exact IDE executable path by "aidegen -p"
or  - specify "aidegen -n" to generate project file only
"""

_CONGRATULATION = COLORED_PASS('CONGRATULATION:')
_LAUNCH_SUCCESS_MSG = (
    'IDE launched successfully. Please check your IDE window.')
_IDE_CACHE_REMINDER_MSG = (
    'To prevent the existed IDE cache from impacting your IDE dependency '
    'analysis, please consider to clear IDE caches if necessary. To do that, in'
    ' IntelliJ IDEA, go to [File > Invalidate Caches / Restart...].')

_SKIP_BUILD_INFO = ('If you are sure the related modules and dependencies have '
                    'been already built, please try to use command {} to skip '
                    'the building process.')
_MAX_TIME = 1
_SKIP_BUILD_INFO_FUTURE = ''.join([
    'AIDEGen build time exceeds {} minute(s).\n'.format(_MAX_TIME),
    _SKIP_BUILD_INFO.rstrip('.'), ' in the future.'
])
_SKIP_BUILD_CMD = 'aidegen {} -s'
_INFO = COLORED_INFO('INFO:')
_SKIP_MSG = _SKIP_BUILD_INFO_FUTURE.format(
    COLORED_INFO('aidegen [ module(s) ] -s'))
_TIME_EXCEED_MSG = '\n{} {}\n'.format(_INFO, _SKIP_MSG)
_LOG_FORMAT = '%(asctime)s %(filename)s:%(lineno)s:%(levelname)s: %(message)s'
_DATE_FORMAT = '%Y-%m-%d %H:%M:%S'


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
        usage=('aidegen [module_name1 module_name2... '
               'project_path1 project_path2...]'))
    parser.required = False
    parser.add_argument(
        'targets',
        type=str,
        nargs='*',
        default=[''],
        help=('Android module name or path.'
              'e.g. Settings or packages/apps/Settings'))
    parser.add_argument(
        '-d',
        '--depth',
        type=int,
        choices=range(10),
        default=0,
        help='The depth of module referenced by source.')
    parser.add_argument(
        '-v',
        '--verbose',
        action='store_true',
        help='Display DEBUG level logging.')
    parser.add_argument(
        '-i',
        '--ide',
        default=['j'],
        help='Launch IDE type, j: IntelliJ, s: Android Studio, e: Eclipse.')
    parser.add_argument(
        '-p',
        '--ide-path',
        dest='ide_installed_path',
        help='IDE installed path.')
    parser.add_argument(
        '-n', '--no_launch', action='store_true', help='Do not launch IDE.')
    parser.add_argument(
        '-r',
        '--config-reset',
        dest='config_reset',
        action='store_true',
        help='Reset all saved configurations, e.g., preferred IDE version.')
    parser.add_argument(
        '-s',
        '--skip-build',
        dest='skip_build',
        action='store_true',
        help=('Skip building jar or modules that create java files in build '
              'time, e.g. R/AIDL/Logtags.'))
    parser.add_argument(
        '-a',
        '--android-tree',
        dest='android_tree',
        action='store_true',
        help='Generate whole Android source tree project file for IDE.')
    return parser.parse_args(args)


def _configure_logging(verbose):
    """Configure the logger.

    Args:
        verbose: A boolean. If true, display DEBUG level logs.
    """
    log_format = _LOG_FORMAT
    datefmt = _DATE_FORMAT
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(level=level, format=log_format, datefmt=datefmt)


def _get_ide_util_instance(args):
    """Get an IdeUtil class instance for launching IDE.

    Args:
        args: A list of arguments.

    Returns:
        A IdeUtil class instance.
    """
    if args.no_launch:
        return None
    ide_util_obj = IdeUtil(args.ide_installed_path, args.ide[0],
                           args.config_reset,
                           AndroidDevOS.MAC == AndroidDevOS.get_os_type())
    if not ide_util_obj.is_ide_installed():
        ipath = args.ide_installed_path or ide_util_obj.get_default_path()
        err = _NO_LAUNCH_IDE_CMD.format(ipath)
        logging.error(err)
        raise IDENotExistError(err)
    return ide_util_obj


def _check_skip_build(args):
    """Check if users skip building target, display the warning message.

    Args:
        args: A list of arguments.
    """
    if not args.skip_build:
        msg = _SKIP_BUILD_INFO.format(
            COLORED_INFO(_SKIP_BUILD_CMD.format(' '.join(args.targets))))
        print('\n{} {}\n'.format(_INFO, msg))


def _generate_project_files(ide, projects):
    """Generate project files by IDE type.

    Args:
        ide: A character to represent IDE type.
        projects: A list of ProjectInfo instances.
    """
    if ide.lower() == 'e':
        generate_eclipse_project_files(projects)
    else:
        generate_ide_project_files(projects)


def _compile_targets_for_whole_android_tree(atest_module_info, targets, cwd):
    """Compile a list of targets to include whole Android tree in the project.

    Adding the whole Android tree to the project will do two things,
    1. If current working directory is not Android root, change the target to
       its relative path to root and change current working directory to root.
       If we don't change directory it's hard to deal with the whole Android
       tree together with the sub-project.
    2. If the whole Android tree target is not in the target list, insert it to
       the first one.

    Args:
        atest_module_info: A instance of atest module-info object.
        targets: A list of targets to be built.
        cwd: A path of current working directory.

    Returns:
        A list of targets after adjustment.
    """
    new_targets = []
    if is_android_root(cwd):
        new_targets = list(targets)
    else:
        for target in targets:
            _, abs_path = common_util.get_related_paths(atest_module_info,
                                                        target)
            rel_path = os.path.relpath(abs_path, constant.ANDROID_ROOT_PATH)
            new_targets.append(rel_path)
        os.chdir(constant.ANDROID_ROOT_PATH)

    if new_targets[0] != '':
        new_targets.insert(0, '')
    return new_targets


def _launch_ide(ide_util_obj, project_absolute_path):
    """Launch IDE through ide_util instance.

    To launch IDE,
    1. Set IDE config.
    2. For IntelliJ, use .idea as open target is better than .iml file,
       because open the latter is like to open a kind of normal file.
    3. Show _LAUNCH_SUCCESS_MSG to remind users IDE being launched.

    Args:
        ide_util_obj: An ide_util instance.
        project_absolute_path: A string of project absolute path.
    """
    ide_util_obj.config_ide()
    ide_util_obj.launch_ide(project_absolute_path)
    print('\n{} {}\n'.format(_CONGRATULATION, _LAUNCH_SUCCESS_MSG))


def _check_whole_android_tree(atest_module_info, targets, android_tree):
    """Check if it's a building project file for the whole Android tree.

    The rules:
    1. If users command aidegen under Android root, e.g.,
       root$ aidegen
       that implies users would like to launch the whole Android tree, AIDEGen
       should set the flag android_tree True.
    2. If android_tree is True, add whole Android tree to the project.

    Args:
        atest_module_info: A instance of atest module-info object.
        targets: A list of targets to be built.
        android_tree: A boolean, True if it's a whole Android tree case,
                      otherwise False.

    Returns:
        A list of targets to be built.
    """
    cwd = os.getcwd()
    if not android_tree and is_android_root(cwd) and targets == ['']:
        android_tree = True
    new_targets = targets
    if android_tree:
        new_targets = _compile_targets_for_whole_android_tree(
            atest_module_info, targets, cwd)
    return new_targets


@time_logged(message=_TIME_EXCEED_MSG, maximum=_MAX_TIME)
def main_with_message(args):
    """Main entry with skip build message.

    Args:
        args: A list of system arguments.
    """
    aidegen_main(args)


@time_logged
def main_without_message(args):
    """Main entry without skip build message.

    Args:
        args: A list of system arguments.
    """
    aidegen_main(args)


# pylint: disable=broad-except
def main(argv):
    """Main entry.

    Try to generates project files for using in IDE.

    Args:
        argv: A list of system arguments.
    """
    exit_code = constant.EXIT_CODE_NORMAL
    try:
        args = _parse_args(argv)
        _configure_logging(args.verbose)
        starts_asuite_metrics()
        if args.skip_build:
            main_without_message(args)
        else:
            main_with_message(args)
    except BaseException as err:
        exit_code = constant.EXIT_CODE_EXCEPTION
        _, exc_value, exc_traceback = sys.exc_info()
        if isinstance(err, AIDEgenError):
            exit_code = constant.EXIT_CODE_AIDEGEN_EXCEPTION
        # Filter out sys.Exit(0) case, which is not an exception case.
        if isinstance(err, SystemExit) and exc_value.code == 0:
            exit_code = constant.EXIT_CODE_NORMAL
    finally:
        if exit_code is not constant.EXIT_CODE_NORMAL:
            error_message = str(exc_value)
            traceback_list = traceback.format_tb(exc_traceback)
            traceback_list.append(error_message)
            traceback_str = ''.join(traceback_list)
            # print out the trackback message for developers to debug
            print(traceback_str)
            ends_asuite_metrics(exit_code, traceback_str, error_message)
        else:
            ends_asuite_metrics(exit_code)


def aidegen_main(args):
    """AIDEGen main entry.

    Try to generates project files for using in IDE.

    Args:
        args: A list of system arguments.
    """
    # Pre-check for IDE relevant case, then handle dependency graph job.
    ide_util_obj = _get_ide_util_instance(args)
    _check_skip_build(args)
    atest_module_info = common_util.get_atest_module_info(args.targets)
    targets = _check_whole_android_tree(atest_module_info, args.targets,
                                        args.android_tree)
    ProjectInfo.modules_info = generate_module_info_json(
        atest_module_info, targets, args.verbose, args.skip_build)
    projects = ProjectInfo.generate_projects(atest_module_info, targets)
    multi_projects_locate_source(projects, args.verbose, args.depth,
                                 constant.IDE_NAME_DICT[args.ide[0]],
                                 args.skip_build)
    _generate_project_files(args.ide[0], projects)
    if ide_util_obj:
        _launch_ide(ide_util_obj, projects[0].project_absolute_path)


if __name__ == '__main__':
    try:
        main(sys.argv[1:])
    finally:
        print('\n{0} {1}\n\n{0} {2}\n'.format(_INFO, AIDEGEN_REPORT_LINK,
                                              _IDE_CACHE_REMINDER_MSG))
