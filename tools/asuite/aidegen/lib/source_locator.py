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

"""Collect the source paths from dependency information."""

from __future__ import absolute_import

import glob
import logging
import os
import re

from aidegen import constant
from aidegen.lib import errors
from aidegen.lib import common_util
from aidegen.lib.common_util import COLORED_INFO
from atest import atest_utils
from atest import constants

# Parse package name from the package declaration line of a java.
# Group matches "foo.bar" of line "package foo.bar;" or "package foo.bar"
_PACKAGE_RE = re.compile(r'\s*package\s+(?P<package>[^(;|\s)]+)\s*', re.I)

_ANDROID_SUPPORT_PATH_KEYWORD = 'prebuilts/sdk/current/'
_JAR = '.jar'
_TARGET_LIBS = [_JAR]
_JARJAR_RULES_FILE = 'jarjar-rules.txt'
_JAVA = '.java'
_KOTLIN = '.kt'
_TARGET_FILES = [_JAVA, _KOTLIN]
_KEY_INSTALLED = 'installed'
_KEY_JARJAR_RULES = 'jarjar_rules'
_KEY_JARS = 'jars'
_KEY_PATH = 'path'
_KEY_SRCS = 'srcs'
_KEY_TESTS = 'tests'
_SRCJAR = '.srcjar'
_AAPT2_DIR = 'out/target/common/obj/APPS/%s_intermediates/aapt2'
_AAPT2_SRCJAR = 'out/target/common/obj/APPS/%s_intermediates/aapt2.srcjar'
_IGNORE_DIRS = [
    # The java files under this directory have to be ignored because it will
    # cause duplicated classes by libcore/ojluni/src/main/java.
    'libcore/ojluni/src/lambda/java'
]
_DIS_ROBO_BUILD_ENV_VAR = {'DISABLE_ROBO_RUN_TESTS': 'true'}
_SKIP_BUILD_WARN = (
    'You choose "--skip-build". Skip building jar and module might increase '
    'the risk of the absence of some jar or R/AIDL/logtags java files and '
    'cause the red lines to appear in IDE tool.')


def multi_projects_locate_source(projects, verbose, depth, ide_name,
                                 skip_build=True):
    """Locate the paths of dependent source folders and jar files with projects.

    Args:
        projects: A list of ProjectInfo instances. Information of a project such
                  as project relative path, project real path, project
                  dependencies.
        verbose: A boolean, if true displays full build output.
        depth: An integer shows the depth of module dependency referenced by
               source. Zero means the max module depth.
        ide_name: A string stands for the IDE name, default is IntelliJ.
        skip_build: A boolean default to true, if true skip building jar and
                    srcjar files, otherwise build them.
    """
    if skip_build:
        print('\n{} {}\n'.format(COLORED_INFO('Warning:'), _SKIP_BUILD_WARN))
    for project in projects:
        locate_source(project, verbose, depth, ide_name, build=not skip_build)


def locate_source(project, verbose, depth, ide_name, build=True):
    """Locate the paths of dependent source folders and jar files.

    Try to reference source folder path as dependent module unless the
    dependent module should be referenced to a jar file, such as modules have
    jars and jarjar_rules parameter.
    For example:
        Module: asm-6.0
            java_import {
                name: 'asm-6.0',
                host_supported: true,
                jars: ['asm-6.0.jar'],
            }
        Module: bouncycastle
            java_library {
                name: 'bouncycastle',
                ...
                target: {
                    android: {
                        jarjar_rules: 'jarjar-rules.txt',
                    },
                },
            }

    Args:
        project: A ProjectInfo instance. Information of a project such as
                 project relative path, project real path, project dependencies.
        verbose: A boolean, if true displays full build output.
        depth: An integer shows the depth of module dependency referenced by
               source. Zero means the max module depth.
        ide_name: A string stands for the IDE name, default is IntelliJ.
        build: A boolean default to true, if true skip building jar and srcjar
               files, otherwise build them.

    Example usage:
        project.source_path = locate_source(project, verbose, False)
        E.g.
            project.source_path = {
                'source_folder_path': ['path/to/source/folder1',
                                       'path/to/source/folder2', ...],
                'test_folder_path': ['path/to/test/folder', ...],
                'jar_path': ['path/to/jar/file1', 'path/to/jar/file2', ...]
            }
    """
    if not hasattr(project, 'dep_modules') or not project.dep_modules:
        raise errors.EmptyModuleDependencyError(
            'Dependent modules dictionary is empty.')
    dependencies = project.source_path
    rebuild_targets = set()
    for module_name, module_data in project.dep_modules.items():
        module = _generate_moduledata(module_name, module_data, ide_name,
                                      project.project_relative_path, depth)
        module.locate_sources_path()
        dependencies['source_folder_path'].update(module.src_dirs)
        dependencies['test_folder_path'].update(module.test_dirs)
        _append_jars_as_dependencies(dependencies, module)
        if module.build_targets:
            rebuild_targets |= module.build_targets
    if rebuild_targets:
        if build:
            _build_dependencies(verbose, rebuild_targets)
            locate_source(project, verbose, depth, ide_name, build=False)
        else:
            logging.warning('Jar files or modules build failed:\n\t%s.',
                            '\n\t'.join(rebuild_targets))


def _build_dependencies(verbose, rebuild_targets):
    """Build the jar or srcjar files of the modules if it don't exist.

    Args:
        verbose: A boolean, if true displays full build output.
        rebuild_targets: A list of jar or srcjar files which do not exist.
    """
    logging.info(('Ready to build the jar or srcjar files.'))
    targets = ['-k']
    targets.extend(list(rebuild_targets))
    if not atest_utils.build(targets, verbose, _DIS_ROBO_BUILD_ENV_VAR):
        message = ('Build failed!\n{}\nAIDEGen will proceed but dependency '
                   'correctness is not guaranteed if not all targets being '
                   'built successfully.'.format('\n'.join(targets)))
        print('\n{} {}\n'.format(COLORED_INFO('Warning:'), message))


def _generate_moduledata(module_name, module_data, ide_name, project_relpath,
                         depth):
    """Generate a module class to collect dependencies in IntelliJ or Eclipse.

    Args:
        module_name: Name of the module.
        module_data: A dictionary holding a module information.
        ide_name: A string stands for the IDE name.
        project_relpath: A string stands for the project's relative path.
        depth: An integer shows the depth of module dependency referenced by
               source. Zero means the max module depth.

    Returns:
        A ModuleData class.
    """
    if ide_name == constant.IDE_ECLIPSE:
        return EclipseModuleData(module_name, module_data, project_relpath)
    return ModuleData(module_name, module_data, depth)


def _append_jars_as_dependencies(dependent_data, module):
    """Add given module's jar files into dependent_data as dependencies.

    Args:
        dependent_data: A dictionary contains the dependent source paths and
                        jar files.
        module: A ModuleData instance.
    """
    if module.jar_files:
        dependent_data['jar_path'].update(module.jar_files)
        for jar in list(module.jar_files):
            dependent_data['jar_module_path'].update({jar: module.module_path})
    # Collecting the jar files of default core modules as dependencies.
    if constant.KEY_DEP in module.module_data:
        dependent_data['jar_path'].update([
            x for x in module.module_data[constant.KEY_DEP]
            if common_util.is_target(x, _TARGET_LIBS)
        ])


class ModuleData():
    """ModuleData class.

    Attributes:
        All following relative paths stand for the path relative to the android
        repo root.

        module_path: A string of the relative path to the module.
        src_dirs: A set to keep the unique source folder relative paths.
        test_dirs: A set to keep the unique test folder relative paths.
        jar_files: A set to keep the unique jar file relative paths.
        referenced_by_jar: A boolean to check if the module is referenced by a
                           jar file.
        build_targets: A set to keep the unique build target jar or srcjar file
                       relative paths which are ready to be rebuld.
        missing_jars: A set to keep the jar file relative paths if it doesn't
                      exist.
        specific_soong_path: A string of the relative path to the module's
                             intermediates folder under out/.
    """

    def __init__(self, module_name, module_data, depth):
        """Initialize ModuleData.

        Args:
            module_name: Name of the module.
            module_data: A dictionary holding a module information.
            depth: An integer shows the depth of module dependency referenced by
                   source. Zero means the max module depth.
            For example:
                {
                    'class': ['APPS'],
                    'path': ['path/to/the/module'],
                    'depth': 0,
                    'dependencies': ['bouncycastle', 'ims-common'],
                    'srcs': [
                        'path/to/the/module/src/com/android/test.java',
                        'path/to/the/module/src/com/google/test.java',
                        'out/soong/.intermediates/path/to/the/module/test/src/
                         com/android/test.srcjar'
                    ],
                    'installed': ['out/target/product/generic_x86_64/
                                   system/framework/framework.jar'],
                    'jars': ['settings.jar'],
                    'jarjar_rules': ['jarjar-rules.txt']
                }
        """
        assert module_name, 'Module name can\'t be null.'
        assert module_data, 'Module data of %s can\'t be null.' % module_name
        self.module_name = module_name
        self.module_data = module_data
        self._init_module_path()
        self._init_module_depth(depth)
        self.src_dirs = set()
        self.test_dirs = set()
        self.jar_files = set()
        self.referenced_by_jar = False
        self.build_targets = set()
        self.missing_jars = set()
        self.specific_soong_path = os.path.join(
            'out/soong/.intermediates', self.module_path, self.module_name)

    def _is_app_module(self):
        """Check if the current module's class is APPS"""
        return self._check_key('class') and 'APPS' in self.module_data['class']

    def _is_target_module(self):
        """Check if the current module is a target module.

        A target module is the target project or a module under the
        target project and it's module depth is 0.
        For example: aidegen Settings framework
            The target projects are Settings and framework so they are also
            target modules. And the dependent module SettingsUnitTests's path
            is packages/apps/Settings/tests/unit so it also a target module.
        """
        return self.module_depth == 0

    def _is_module_in_apps(self):
        """Check if the current module is under packages/apps."""
        _apps_path = os.path.join('packages', 'apps')
        return self.module_path.startswith(_apps_path)

    def _collect_r_srcs_paths(self):
        """Collect the source folder of R.java.

        For modules under packages/apps, check if exists an intermediates
        directory which contains R.java. If it does not exist, build the
        aapt2.srcjar of the module to generate. Build system will finally copy
        the R.java from a intermediates directory to the central R directory
        after building successfully. So set the central R directory
        out/target/common/R as a default source folder in IntelliJ.
        """
        if (self._is_app_module() and self._is_target_module() and
                self._is_module_in_apps()):
            # The directory contains R.java for apps in packages/apps.
            r_src_dir = _AAPT2_DIR % self.module_name
            if not os.path.exists(common_util.get_abs_path(r_src_dir)):
                self.build_targets.add(_AAPT2_SRCJAR % self.module_name)
            # In case the central R folder been deleted, uses the intermediate
            # folder as the dependency to R.java.
            self.src_dirs.add(r_src_dir)
        # Add the central R as a default source folder.
        self.src_dirs.add('out/target/common/R')

    def _init_module_path(self):
        """Inintialize self.module_path."""
        self.module_path = (self.module_data[_KEY_PATH][0]
                            if _KEY_PATH in self.module_data
                            and self.module_data[_KEY_PATH] else '')

    def _init_module_depth(self, depth):
        """Inintialize module depth's settings.

        Set the module's depth from module info when user have -d parameter.
        Set the -d value from user input, default to 0.

        Args:
            depth: the depth to be set.
        """
        self.module_depth = (int(self.module_data[constant.KEY_DEPTH])
                             if depth else 0)
        self.depth_by_source = depth

    def _is_android_supported_module(self):
        """Determine if this is an Android supported module."""
        return self.module_path.startswith(_ANDROID_SUPPORT_PATH_KEYWORD)

    def _check_jarjar_rules_exist(self):
        """Check if jarjar rules exist."""
        return (_KEY_JARJAR_RULES in self.module_data and
                self.module_data[_KEY_JARJAR_RULES][0] == _JARJAR_RULES_FILE)

    def _check_jars_exist(self):
        """Check if jars exist."""
        return _KEY_JARS in self.module_data and self.module_data[_KEY_JARS]

    def _collect_srcs_paths(self):
        """Collect source folder paths in src_dirs from module_data['srcs']."""
        if self._check_key(_KEY_SRCS):
            scanned_dirs = set()
            for src_item in self.module_data[_KEY_SRCS]:
                src_dir = None
                src_item = os.path.relpath(src_item)
                if src_item.endswith(_SRCJAR):
                    self._append_jar_from_installed(self.specific_soong_path)
                elif common_util.is_target(src_item, _TARGET_FILES):
                    # Only scan one java file in each source directories.
                    src_item_dir = os.path.dirname(src_item)
                    if src_item_dir not in scanned_dirs:
                        scanned_dirs.add(src_item_dir)
                        src_dir = self._get_source_folder(src_item)
                else:
                    # To record what files except java and srcjar in the srcs.
                    logging.debug('%s is not in parsing scope.', src_item)
                if src_dir:
                    self._add_to_source_or_test_dirs(src_dir)

    def _check_key(self, key):
        """Check if key is in self.module_data and not empty.

        Args:
            key: the key to be checked.
        """
        return key in self.module_data and self.module_data[key]

    def _add_to_source_or_test_dirs(self, src_dir):
        """Add folder to source or test directories.

        Args:
            src_dir: the directory to be added.
        """
        if not any(path in src_dir for path in _IGNORE_DIRS):
            # Build the module if the source path not exists. The java is
            # normally generated for AIDL or logtags file.
            if not os.path.exists(common_util.get_abs_path(src_dir)):
                self.build_targets.add(self.module_name)
            if self._is_test_module(src_dir):
                self.test_dirs.add(src_dir)
            else:
                self.src_dirs.add(src_dir)

    @staticmethod
    def _is_test_module(src_dir):
        """Check if the module path is a test module path.

        Args:
            src_dir: the directory to be checked.

        Returns:
            True if module path is a test module path, otherwise False.
        """
        return _KEY_TESTS in src_dir.split(os.sep)

    # pylint: disable=inconsistent-return-statements
    @staticmethod
    def _get_source_folder(java_file):
        """Parsing a java to get the package name to filter out source path.

        There are 3 steps to get the source path from a java.
        1. Parsing a java to get package name.
           For example:
               The java_file is:path/to/the/module/src/main/java/com/android/
                                first.java
               The package name of java_file is com.android.
        2. Transfer package name to package path:
           For example:
               The package path of com.android is com/android.
        3. Remove the package path and file name from the java path.
           For example:
               The path after removing package path and file name is
               path/to/the/module/src/main/java.
        As a result, path/to/the/module/src/main/java is the source path parsed
        from path/to/the/module/src/main/java/com/android/first.java.

        Returns:
            source_folder: A string of path to source folder(e.g. src/main/java)
                           or none when it failed to get package name.
        """
        abs_java_path = common_util.get_abs_path(java_file)
        if os.path.exists(abs_java_path):
            with open(abs_java_path) as data:
                for line in data.read().splitlines():
                    match = _PACKAGE_RE.match(line)
                    if match:
                        package_name = match.group('package')
                        package_path = package_name.replace(os.extsep, os.sep)
                        source_folder, _, _ = java_file.rpartition(package_path)
                        return source_folder.strip(os.sep)

    def _append_jar_file(self, jar_path):
        """Append a path to the jar file into self.jar_files if it's exists.

        Args:
            jar_path: A path supposed to be a jar file.

        Returns:
            Boolean: True if jar_path is an existing jar file.
        """
        if common_util.is_target(jar_path, _TARGET_LIBS):
            self.referenced_by_jar = True
            if os.path.isfile(common_util.get_abs_path(jar_path)):
                self.jar_files.add(jar_path)
            else:
                self.missing_jars.add(jar_path)
            return True

    def _append_jar_from_installed(self, specific_dir=None):
        """Append a jar file's path to the list of jar_files with matching
        path_prefix.

        There might be more than one jar in "installed" parameter and only the
        first jar file is returned. If specific_dir is set, the jar file must be
        under the specific directory or its sub-directory.

        Args:
            specific_dir: A string of path.
        """
        if (_KEY_INSTALLED in self.module_data
                and self.module_data[_KEY_INSTALLED]):
            for jar in self.module_data[_KEY_INSTALLED]:
                if specific_dir and not jar.startswith(specific_dir):
                    continue
                if self._append_jar_file(jar):
                    break

    def _set_jars_jarfile(self):
        """Append prebuilt jars of module into self.jar_files.

        Some modules' sources are prebuilt jar files instead of source java
        files. The jar files can be imported into IntelliJ as a dependency
        directly. There is only jar file name in self.module_data['jars'], it
        has to be combined with self.module_data['path'] to append into
        self.jar_files.
        For example:
        'asm-6.0': {
            'jars': [
                'asm-6.0.jar'
            ],
            'path': [
                'prebuilts/misc/common/asm'
            ],
        },
        Path to the jar file is prebuilts/misc/common/asm/asm-6.0.jar.
        """
        if _KEY_JARS in self.module_data and self.module_data[_KEY_JARS]:
            for jar_name in self.module_data[_KEY_JARS]:
                if self._check_key(_KEY_INSTALLED):
                    self._append_jar_from_installed()
                else:
                    jar_path = os.path.join(self.module_path, jar_name)
                    jar_abs = common_util.get_abs_path(jar_path)
                    if not os.path.isfile(
                            jar_abs) and jar_name.endswith('prebuilt.jar'):
                        rel_path = self._get_jar_path_from_prebuilts(jar_name)
                        if rel_path:
                            jar_path = rel_path
                    self._append_jar_file(jar_path)

    @staticmethod
    def _get_jar_path_from_prebuilts(jar_name):
        """Get prebuilt jar file from prebuilts folder.

        If the prebuilt jar file we get from method _set_jars_jarfile() does not
        exist, we should search the prebuilt jar file in prebuilts folder.
        For example:
        'platformprotos': {
            'jars': [
                'platformprotos-prebuilt.jar'
            ],
            'path': [
                'frameworks/base'
            ],
        },
        We get an incorrect path: 'frameworks/base/platformprotos-prebuilt.jar'
        If the file does not exist, we should search the file name from
        prebuilts folder. If we can get the correct path from 'prebuilts', we
        can replace it with the incorrect path.

        Args:
            jar_name: The prebuilt jar file name.

        Return:
            A relative prebuilt jar file path if found, otherwise None.
        """
        rel_path = ''
        search = os.sep.join(
            [constant.ANDROID_ROOT_PATH, 'prebuilts/**', jar_name])
        results = glob.glob(search, recursive=True)
        if results:
            jar_abs = results[0]
            rel_path = os.path.relpath(
                jar_abs, os.environ.get(constants.ANDROID_BUILD_TOP, os.sep))
        return rel_path

    def locate_sources_path(self):
        """Locate source folders' paths or jar files."""
        if self.module_depth > self.depth_by_source:
            self._append_jar_from_installed(self.specific_soong_path)
        else:
            if self._is_android_supported_module():
                self._append_jar_from_installed()
            elif self._check_jarjar_rules_exist():
                self._append_jar_from_installed(self.specific_soong_path)
            elif self._check_jars_exist():
                self._set_jars_jarfile()
            self._collect_srcs_paths()
            # If there is no source/tests folder of the module, reference the
            # module by jar.
            if not self.src_dirs and not self.test_dirs:
                self._append_jar_from_installed()
            self._collect_r_srcs_paths()
        if self.referenced_by_jar and self.missing_jars:
            self.build_targets |= self.missing_jars


class EclipseModuleData(ModuleData):
    """Deal with modules data for Eclipse

    Only project target modules use source folder type and the other ones use
    jar as their source. We'll combine both to establish the whole project's
    dependencies. If the source folder used to build dependency jar file exists
    in Android, we should provide the jar file path as <linkedResource> item in
    source data.
    """

    def __init__(self, module_name, module_data, project_relpath):
        """Initialize EclipseModuleData.

        Only project target modules apply source folder type, so set the depth
        of module referenced by source to 0.

        Args:
            module_name: String type, name of the module.
            module_data: A dictionary contains a module information.
            project_relpath: A string stands for the project's relative path.
        """
        super().__init__(module_name, module_data, depth=0)
        self.is_project = common_util.is_project_path_relative_module(
            module_data, project_relpath)

    def locate_sources_path(self):
        """Locate source folders' paths or jar files.

        Only collect source folders for the project modules and collect jar
        files for the other dependent modules.
        """
        if self.is_project:
            self._locate_project_source_path()
        else:
            self._locate_jar_path()
        if self.referenced_by_jar and self.missing_jars:
            self.build_targets |= self.missing_jars

    def _locate_project_source_path(self):
        """Locate the source folder paths of the project module.

        A project module is the target modules or paths that users key in
        aidegen command. Collecting the source folders is necessary for
        developers to edit code. And also collect the central R folder for the
        dependency of resources.
        """
        self._collect_srcs_paths()
        self._collect_r_srcs_paths()

    def _locate_jar_path(self):
        """Locate the jar path of the module.

        Use jar files for dependency modules for Eclipse. Collect the jar file
        path with different cases.
        """
        if self._check_jarjar_rules_exist():
            self._append_jar_from_installed(self.specific_soong_path)
        elif self._check_jars_exist():
            self._set_jars_jarfile()
        else:
            self._append_jar_from_installed()
