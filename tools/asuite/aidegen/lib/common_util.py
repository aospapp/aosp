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

"""common_util

This module has a collection of functions that provide helper functions to
other modules.
"""

import logging
import os
import time

from functools import partial
from functools import wraps

from aidegen import constant
from aidegen.lib.errors import FakeModuleError
from aidegen.lib.errors import NoModuleDefinedInModuleInfoError
from aidegen.lib.errors import ProjectOutsideAndroidRootError
from aidegen.lib.errors import ProjectPathNotExistError
from atest import constants
from atest import module_info
from atest.atest_utils import colorize

COLORED_INFO = partial(colorize, color=constants.MAGENTA, highlight=False)
COLORED_PASS = partial(colorize, color=constants.GREEN, highlight=False)
COLORED_FAIL = partial(colorize, color=constants.RED, highlight=False)
FAKE_MODULE_ERROR = '{} is a fake module.'
OUTSIDE_ROOT_ERROR = '{} is outside android root.'
PATH_NOT_EXISTS_ERROR = 'The path {} doesn\'t exist.'
NO_MODULE_DEFINED_ERROR = 'No modules defined at {}.'
# Java related classes.
JAVA_TARGET_CLASSES = ['APPS', 'JAVA_LIBRARIES', 'ROBOLECTRIC']
# C, C++ related classes.
NATIVE_TARGET_CLASSES = [
    'HEADER_LIBRARIES', 'NATIVE_TESTS', 'STATIC_LIBRARIES', 'SHARED_LIBRARIES'
]
TARGET_CLASSES = JAVA_TARGET_CLASSES
TARGET_CLASSES.extend(NATIVE_TARGET_CLASSES)
_REBUILD_MODULE_INFO = '%s We should rebuild module-info.json file for it.'


def time_logged(func=None, *, message='', maximum=1):
    """Decorate a function to find out how much time it spends.

    Args:
        func: a function is to be calculated its spending time.
        message: the message the decorated function wants to show.
        maximum: a interger, minutes. If time exceeds the maximum time show
                 message, otherwise doesn't.

    Returns:
        The wrapper function.
    """
    if func is None:
        return partial(time_logged, message=message, maximum=maximum)

    @wraps(func)
    def wrapper(*args, **kwargs):
        """A wrapper function."""

        start = time.time()
        try:
            return func(*args, **kwargs)
        finally:
            timestamp = time.time() - start
            logging.debug('{}.{} takes: {:.2f}s'.format(
                func.__module__, func.__name__, timestamp))
            if message and timestamp > maximum * 60:
                print(message)

    return wrapper


def get_related_paths(atest_module_info, target=None):
    """Get the relative and absolute paths of target from module-info.

    Args:
        atest_module_info: A ModuleInfo instance.
        target: A string user input from command line. It could be several cases
                such as:
                1. Module name, e.g. Settings
                2. Module path, e.g. packages/apps/Settings
                3. Relative path, e.g. ../../packages/apps/Settings
                4. Current directory, e.g. . or no argument

    Return:
        rel_path: The relative path of a module, return None if no matching
                  module found.
        abs_path: The absolute path of a module, return None if no matching
                  module found.
    """
    rel_path = None
    abs_path = None
    if target:
        # User inputs a module name.
        if atest_module_info.is_module(target):
            paths = atest_module_info.get_paths(target)
            if paths:
                rel_path = paths[0]
                abs_path = os.path.join(constant.ANDROID_ROOT_PATH, rel_path)
        # User inputs a module path or a relative path of android root folder.
        elif (atest_module_info.get_module_names(target) or os.path.isdir(
                os.path.join(constant.ANDROID_ROOT_PATH, target))):
            rel_path = target.strip(os.sep)
            abs_path = os.path.join(constant.ANDROID_ROOT_PATH, rel_path)
        # User inputs a relative path of current directory.
        else:
            abs_path = os.path.abspath(os.path.join(os.getcwd(), target))
            rel_path = os.path.relpath(abs_path, constant.ANDROID_ROOT_PATH)
    else:
        # User doesn't input.
        abs_path = os.getcwd()
        if is_android_root(abs_path):
            rel_path = ''
        else:
            rel_path = os.path.relpath(abs_path, constant.ANDROID_ROOT_PATH)
    return rel_path, abs_path


def is_target_android_root(atest_module_info, targets):
    """Check if any target is the Android root path.

    Args:
        atest_module_info: A ModuleInfo instance contains data of
                           module-info.json.
        targets: A list of target modules or project paths from user input.

    Returns:
        True if target is Android root, otherwise False.
    """
    for target in targets:
        _, abs_path = get_related_paths(atest_module_info, target)
        if is_android_root(abs_path):
            return True
    return False


def is_android_root(abs_path):
    """Check if an absolute path is the Android root path.

    Args:
        abs_path: The absolute path of a module.

    Returns:
        True if abs_path is Android root, otherwise False.
    """
    return abs_path == constant.ANDROID_ROOT_PATH


def has_build_target(atest_module_info, rel_path):
    """Determine if a relative path contains buildable module.

    Args:
        atest_module_info: A ModuleInfo instance contains data of
                           module-info.json.
        rel_path: The module path relative to android root.

    Returns:
        True if the relative path contains a build target, otherwise false.
    """
    return any(
        mod_path.startswith(rel_path)
        for mod_path in atest_module_info.path_to_module_info)


def _check_modules(atest_module_info, targets, raise_on_lost_module=True):
    """Check if all targets are valid build targets.

    Args:
        atest_module_info: A ModuleInfo instance contains data of
                           module-info.json.
        targets: A list of target modules or project paths from user input.
                When locating the path of the target, given a matched module
                name has priority over path. Below is the priority of locating a
                target:
                1. Module name, e.g. Settings
                2. Module path, e.g. packages/apps/Settings
                3. Relative path, e.g. ../../packages/apps/Settings
                4. Current directory, e.g. . or no argument
        raise_on_lost_module: A boolean, pass to _check_module to determine if
                ProjectPathNotExistError or NoModuleDefinedInModuleInfoError
                should be raised.

    Returns:
        True if any _check_module return flip the True/False.
    """
    for target in targets:
        if not _check_module(atest_module_info, target, raise_on_lost_module):
            return False
    return True


def _check_module(atest_module_info, target, raise_on_lost_module=True):
    """Check if a target is valid or it's a path containing build target.

    Args:
        atest_module_info: A ModuleInfo instance contains the data of
                module-info.json.
        target: A target module or project path from user input.
                When locating the path of the target, given a matched module
                name has priority over path. Below is the priority of locating a
                target:
                1. Module name, e.g. Settings
                2. Module path, e.g. packages/apps/Settings
                3. Relative path, e.g. ../../packages/apps/Settings
                4. Current directory, e.g. . or no argument
        raise_on_lost_module: A boolean, handles if ProjectPathNotExistError or
                NoModuleDefinedInModuleInfoError should be raised.

    Returns:
        1. If there is no error _check_module always return True.
        2. If there is a error,
            a. When raise_on_lost_module is False, _check_module will raise the
               error.
            b. When raise_on_lost_module is True, _check_module will return
               False if module's error is ProjectPathNotExistError or
               NoModuleDefinedInModuleInfoError else raise the error.

    Raises:
        Raise ProjectPathNotExistError and NoModuleDefinedInModuleInfoError only
        when raise_on_lost_module is True, others don't subject to the limit.
        The rules for raising exceptions:
        1. Absolute path of a module is None -> FakeModuleError
        2. Module doesn't exist in repo root -> ProjectOutsideAndroidRootError
        3. The given absolute path is not a dir -> ProjectPathNotExistError
        4. If the given abs path doesn't contain any target and not repo root
           -> NoModuleDefinedInModuleInfoError
    """
    rel_path, abs_path = get_related_paths(atest_module_info, target)
    if not abs_path:
        err = FAKE_MODULE_ERROR.format(target)
        logging.error(err)
        raise FakeModuleError(err)
    if not abs_path.startswith(constant.ANDROID_ROOT_PATH):
        err = OUTSIDE_ROOT_ERROR.format(abs_path)
        logging.error(err)
        raise ProjectOutsideAndroidRootError(err)
    if not os.path.isdir(abs_path):
        err = PATH_NOT_EXISTS_ERROR.format(rel_path)
        if raise_on_lost_module:
            logging.error(err)
            raise ProjectPathNotExistError(err)
        logging.debug(_REBUILD_MODULE_INFO, err)
        return False
    if (not has_build_target(atest_module_info, rel_path)
            and not is_android_root(abs_path)):
        err = NO_MODULE_DEFINED_ERROR.format(rel_path)
        if raise_on_lost_module:
            logging.error(err)
            raise NoModuleDefinedInModuleInfoError(err)
        logging.debug(_REBUILD_MODULE_INFO, err)
        return False
    return True


def get_abs_path(rel_path):
    """Get absolute path from a relative path.

    Args:
        rel_path: A string, a relative path to constant.ANDROID_ROOT_PATH.

    Returns:
        abs_path: A string, an absolute path starts with
                  constant.ANDROID_ROOT_PATH.
    """
    if not rel_path:
        return constant.ANDROID_ROOT_PATH
    if rel_path.startswith(constant.ANDROID_ROOT_PATH):
        return rel_path
    return os.path.join(constant.ANDROID_ROOT_PATH, rel_path)


def is_project_path_relative_module(data, project_relative_path):
    """Determine if the given project path is relative to the module.

    The rules:
       1. If project_relative_path is empty, it's under Android root, return
          True.
       2. If module's path equals or starts with project_relative_path return
          True, otherwise return False.

    Args:
        data: the module-info dictionary of the checked module.
        project_relative_path: project's relative path

    Returns:
        True if it's the given project path is relative to the module, otherwise
        False.
    """
    if 'path' not in data:
        return False
    path = data['path'][0]
    if project_relative_path == '':
        return True
    if ('class' in data
            and (path == project_relative_path
                 or path.startswith(project_relative_path + os.sep))):
        return True
    return False


def is_target(src_file, src_file_extensions):
    """Check if src_file is a type of src_file_extensions.

    Args:
        src_file: A string of the file path to be checked.
        src_file_extensions: A list of file types to be checked

    Returns:
        True if src_file is one of the types of src_file_extensions, otherwise
        False.
    """
    return any(src_file.endswith(x) for x in src_file_extensions)


def get_atest_module_info(targets):
    """Get the right version of atest ModuleInfo instance.

    The rules:
        Check if the targets don't exist in ModuleInfo, we'll regain a new atest
        ModleInfo instance by setting force_build=True and call _check_modules
        again. If targets still don't exist, raise exceptions.

    Args:
        targets: A list of targets to be built.

    Returns:
        An atest ModuleInfo instance.
    """
    amodule_info = module_info.ModuleInfo()
    if not _check_modules(amodule_info, targets, raise_on_lost_module=False):
        amodule_info = module_info.ModuleInfo(force_build=True)
        _check_modules(amodule_info, targets)
    return amodule_info
