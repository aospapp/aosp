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

"""module_info_util

This module receives a module path which is relative to its root directory and
makes a command to generate two json files, one for mk files and one for bp
files. Then it will load these two json files into two json dictionaries,
merge them into one dictionary and return the merged dictionary to its caller.

Example usage:
merged_dict = generate_module_info_json(atest_module_info, project, verbose)
"""

import glob
import json
import logging
import os
import subprocess
import sys

from aidegen import constant
from aidegen.lib.common_util import COLORED_INFO
from aidegen.lib.common_util import time_logged
from aidegen.lib.common_util import get_related_paths
from aidegen.lib import errors

_BLUEPRINT_JSONFILE_NAME = 'module_bp_java_deps.json'
_KEY_CLS = 'class'
_KEY_PATH = 'path'
_KEY_INS = 'installed'
_KEY_DEP = 'dependencies'
_KEY_SRCS = 'srcs'
_MERGE_NEEDED_ITEMS = [_KEY_CLS, _KEY_PATH, _KEY_INS, _KEY_DEP, _KEY_SRCS]
_INTELLIJ_PROJECT_FILE_EXT = '*.iml'
_LAUNCH_PROJECT_QUERY = (
    'There exists an IntelliJ project file: %s. Do you want '
    'to launch it (yes/No)?')
_GENERATE_JSON_COMMAND = ('SOONG_COLLECT_JAVA_DEPS=false make nothing;'
                          'SOONG_COLLECT_JAVA_DEPS=true make nothing')


@time_logged
def generate_module_info_json(module_info, projects, verbose, skip_build=False):
    """Generate a merged json dictionary.

    Change directory to ANDROID_ROOT_PATH before making _GENERATE_JSON_COMMAND
    to avoid command error: "make: *** No rule to make target 'nothing'.  Stop."
    and change back to current directory after command completed.

    Linked functions:
        _build_target(project, verbose)
        _get_soong_build_json_dict()
        _merge_json(mk_dict, bp_dict)

    Args:
        module_info: A ModuleInfo instance contains data of module-info.json.
        projects: A list of project names.
        verbose: A boolean, if true displays full build output.
        skip_build: A boolean, if true skip building _BLUEPRINT_JSONFILE_NAME if
                    it exists, otherwise build it.

    Returns:
        A tuple of Atest module info instance and a merged json dictionary.
    """
    cwd = os.getcwd()
    os.chdir(constant.ANDROID_ROOT_PATH)
    _build_target([_GENERATE_JSON_COMMAND], projects[0], module_info, verbose,
                  skip_build)
    os.chdir(cwd)
    bp_dict = _get_soong_build_json_dict()
    return _merge_json(module_info.name_to_module_info, bp_dict)


def _build_target(cmd, main_project, module_info, verbose, skip_build=False):
    """Make nothing to generate module_bp_java_deps.json.

    We build without environment setting SOONG_COLLECT_JAVA_DEPS and then build
    with environment setting SOONG_COLLECT_JAVA_DEPS. In this way we can trigger
    the process of collecting dependencies and generating
    module_bp_java_deps.json.

    Args:
        cmd: A string list, build command.
        main_project: The main project name.
        module_info: A ModuleInfo instance contains data of module-info.json.
        verbose: A boolean, if true displays full build output.
        skip_build: A boolean, if true skip building _BLUEPRINT_JSONFILE_NAME if
                    it exists, otherwise build it.

    Build results:
        1. Build successfully return.
        2. Build failed:
           1) There's no project file, raise BuildFailureError.
           2) There exists a project file, ask users if they want to
              launch IDE with the old project file.
              a) If the answer is yes, return.
              b) If the answer is not yes, sys.exit(1)
    """
    json_path = _get_blueprint_json_path()
    original_json_mtime = None
    if os.path.isfile(json_path):
        if skip_build:
            logging.info('%s file exists, skipping build.',
                         _BLUEPRINT_JSONFILE_NAME)
            return
        original_json_mtime = os.path.getmtime(json_path)
    try:
        if verbose:
            full_env_vars = os.environ.copy()
            subprocess.check_call(
                cmd, stderr=subprocess.STDOUT, env=full_env_vars, shell=True)
        else:
            subprocess.check_call(cmd, shell=True)
        logging.info('Build successfully: %s.', cmd)
    except subprocess.CalledProcessError:
        if not _is_new_json_file_generated(json_path, original_json_mtime):
            if os.path.isfile(json_path):
                message = ('Generate new {0} failed, AIDEGen will proceed and '
                           'reuse the old {0}.'.format(json_path))
                print('\n{} {}\n'.format(COLORED_INFO('Warning:'), message))
        else:
            _, main_project_path = get_related_paths(module_info, main_project)
            _build_failed_handle(main_project_path)


def _is_new_json_file_generated(json_path, original_file_mtime):
    """Check the new file is generated or not.

    Args:
        json_path: The path of the json file being to check.
        original_file_mtime: the original file modified time.
    """
    if not original_file_mtime:
        return os.path.isfile(json_path)
    return original_file_mtime != os.path.getmtime(json_path)


def _build_failed_handle(main_project_path):
    """Handle build failures.

    Args:
        main_project_path: The main project directory.

    Handle results:
        1) There's no project file, raise BuildFailureError.
        2) There exists a project file, ask users if they want to
           launch IDE with the old project file.
           a) If the answer is yes, return.
           b) If the answer is not yes, sys.exit(1)
    """
    project_file = glob.glob(
        os.path.join(main_project_path, _INTELLIJ_PROJECT_FILE_EXT))
    if project_file:
        query = (_LAUNCH_PROJECT_QUERY) % project_file[0]
        input_data = input(query)
        if not input_data.lower() in ['yes', 'y']:
            sys.exit(1)
    else:
        raise errors.BuildFailureError(
            'Failed to generate %s.' % _get_blueprint_json_path())


def _get_soong_build_json_dict():
    """Load a json file from path and convert it into a json dictionary.

    Returns:
        A json dictionary.
    """
    json_path = _get_blueprint_json_path()
    try:
        with open(json_path) as jfile:
            json_dict = json.load(jfile)
            return json_dict
    except IOError as err:
        raise errors.JsonFileNotExistError(
            '%s does not exist, error: %s.' % (json_path, err))


def _get_blueprint_json_path():
    """Assemble the path of blueprint json file.

    Returns:
        Blueprint json path.
    """
    return os.path.join(constant.SOONG_OUT_DIR_PATH, _BLUEPRINT_JSONFILE_NAME)


def _merge_module_keys(m_dict, b_dict):
    """Merge a module's json dictionary into another module's json dictionary.

    Args:
        m_dict: The module dictionary is going to merge b_dict into.
        b_dict: Soong build system module dictionary.
    """
    for key, b_modules in b_dict.items():
        m_dict[key] = sorted(list(set(m_dict.get(key, []) + b_modules)))


def _copy_needed_items_from(mk_dict):
    """Shallow copy needed items from Make build system part json dictionary.

    Args:
        mk_dict: Make build system json dictionary is going to be copyed.

    Returns:
        A merged json dictionary.
    """
    merged_dict = dict()
    for module in mk_dict.keys():
        merged_dict[module] = dict()
        for key in mk_dict[module].keys():
            if key in _MERGE_NEEDED_ITEMS and mk_dict[module][key] != []:
                merged_dict[module][key] = mk_dict[module][key]
    return merged_dict


def _merge_json(mk_dict, bp_dict):
    """Merge two json dictionaries.

    Linked function:
        _merge_module_keys(m_dict, b_dict)

    Args:
        mk_dict: Make build system part json dictionary.
        bp_dict: Soong build system part json dictionary.

    Returns:
        A merged json dictionary.
    """
    merged_dict = _copy_needed_items_from(mk_dict)
    for module in bp_dict.keys():
        if not module in merged_dict.keys():
            merged_dict[module] = dict()
        _merge_module_keys(merged_dict[module], bp_dict[module])
    return merged_dict
