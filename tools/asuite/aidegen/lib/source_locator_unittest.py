#!/usr/bin/env python3
#
# Copyright 2018, The Android Open Source Project
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

"""Unittests for source_locator."""

import os
import shutil
import tempfile
import unittest
from unittest import mock

from aidegen import constant
from aidegen import unittest_constants as uc
from aidegen.lib import source_locator

_MODULE_NAME = 'test'
_MODULE_PATH = 'packages/apps/test'
_MODULE_INFO = {
    'path': [_MODULE_PATH],
    'srcs': [
        'packages/apps/test/src/main/java/com/android/java.java',
        'packages/apps/test/tests/com/android/test.java',
        'packages/apps/test/tests/test.srcjar'
    ],
    'dependencies': [],
    'installed': []
}
_MODULE_DEPTH = 0


# pylint: disable=protected-access
# pylint: disable=invalid-name
class SourceLocatorUnittests(unittest.TestCase):
    """Unit tests for source_locator.py"""

    def test_collect_srcs_paths(self):
        """Test _collect_srcs_paths create the source path list."""
        result_source = set(['packages/apps/test/src/main/java'])
        result_test = set(['packages/apps/test/tests'])
        constant.ANDROID_ROOT_PATH = uc.TEST_DATA_PATH
        module_data = source_locator.ModuleData(_MODULE_NAME, _MODULE_INFO,
                                                _MODULE_DEPTH)
        module_data._collect_srcs_paths()
        self.assertEqual(module_data.src_dirs, result_source)
        self.assertEqual(module_data.test_dirs, result_test)

    def test_get_source_folder(self):
        """Test _get_source_folder process."""
        # Test for getting the source path by parse package name from a java.
        test_java = 'packages/apps/test/src/main/java/com/android/java.java'
        result_source = 'packages/apps/test/src/main/java'
        constant.ANDROID_ROOT_PATH = uc.TEST_DATA_PATH
        module_data = source_locator.ModuleData(_MODULE_NAME, _MODULE_INFO,
                                                _MODULE_DEPTH)
        src_path = module_data._get_source_folder(test_java)
        self.assertEqual(src_path, result_source)

        # Return path is None if the java file doesn't exist.
        test_java = 'file_not_exist.java'
        src_path = module_data._get_source_folder(test_java)
        self.assertEqual(src_path, None)

        # Return path is None on the java file without package name.
        test_java = ('packages/apps/test/src/main/java/com/android/'
                     'wrong_package.java')
        src_path = module_data._get_source_folder(test_java)
        self.assertEqual(src_path, None)

    def test_append_jar_file(self):
        """Test _append_jar_file process."""
        # Append an existing jar file path to module_data.jar_files.
        test_jar_file = os.path.join(_MODULE_PATH, 'test.jar')
        result_jar_list = set([test_jar_file])
        constant.ANDROID_ROOT_PATH = uc.TEST_DATA_PATH
        module_data = source_locator.ModuleData(_MODULE_NAME, _MODULE_INFO,
                                                _MODULE_DEPTH)
        module_data._append_jar_file(test_jar_file)
        self.assertEqual(module_data.jar_files, result_jar_list)

        # Skip if the jar file doesn't exist.
        test_jar_file = os.path.join(_MODULE_PATH, 'jar_not_exist.jar')
        module_data.jar_files = set()
        module_data._append_jar_file(test_jar_file)
        self.assertEqual(module_data.jar_files, set())

        # Skip if it's not a jar file.
        test_jar_file = os.path.join(_MODULE_PATH, 'test.java')
        module_data.jar_files = set()
        module_data._append_jar_file(test_jar_file)
        self.assertEqual(module_data.jar_files, set())

    def test_append_jar_from_installed(self):
        """Test _append_jar_from_installed handling."""
        # Test appends the first jar file of 'installed'.
        module_info = dict(_MODULE_INFO)
        module_info['installed'] = [
            os.path.join(_MODULE_PATH, 'test.aar'),
            os.path.join(_MODULE_PATH, 'test.jar'),
            os.path.join(_MODULE_PATH, 'tests/test_second.jar')
        ]
        result_jar_list = set([os.path.join(_MODULE_PATH, 'test.jar')])
        constant.ANDROID_ROOT_PATH = uc.TEST_DATA_PATH
        module_data = source_locator.ModuleData(_MODULE_NAME, module_info,
                                                _MODULE_DEPTH)
        module_data._append_jar_from_installed()
        self.assertEqual(module_data.jar_files, result_jar_list)

        # Test on the jar file path matches the path prefix.
        module_data.jar_files = set()
        result_jar_list = set(
            [os.path.join(_MODULE_PATH, 'tests/test_second.jar')])
        module_data._append_jar_from_installed(
            os.path.join(_MODULE_PATH, 'tests/'))
        self.assertEqual(module_data.jar_files, result_jar_list)

    def test_set_jars_jarfile(self):
        """Test _set_jars_jarfile handling."""
        # Combine the module path with jar file name in 'jars' and then append
        # it to module_data.jar_files.
        module_info = dict(_MODULE_INFO)
        module_info['jars'] = [
            'test.jar',
            'src/test.jar',  # This jar file doesn't exist.
            'tests/test_second.jar'
        ]
        result_jar_list = set([
            os.path.join(_MODULE_PATH, 'test.jar'),
            os.path.join(_MODULE_PATH, 'tests/test_second.jar')
        ])
        constant.ANDROID_ROOT_PATH = uc.TEST_DATA_PATH
        module_data = source_locator.ModuleData(_MODULE_NAME, module_info,
                                                _MODULE_DEPTH)
        module_data._set_jars_jarfile()
        self.assertEqual(module_data.jar_files, result_jar_list)

    def test_locate_sources_path(self):
        """Test locate_sources_path handling."""
        # Test collect source path.
        module_info = dict(_MODULE_INFO)
        result_src_list = set(['packages/apps/test/src/main/java',
                               'out/target/common/R'])
        result_test_list = set(['packages/apps/test/tests'])
        result_jar_list = set()
        constant.ANDROID_ROOT_PATH = uc.TEST_DATA_PATH
        module_data = source_locator.ModuleData(_MODULE_NAME, module_info,
                                                _MODULE_DEPTH)
        module_data.locate_sources_path()
        self.assertEqual(module_data.src_dirs, result_src_list)
        self.assertEqual(module_data.test_dirs, result_test_list)
        self.assertEqual(module_data.jar_files, result_jar_list)

        # Test find jar files.
        jar_file = ('out/soong/.intermediates/packages/apps/test/test/'
                    'android_common/test.jar')
        module_info['jarjar_rules'] = ['jarjar-rules.txt']
        module_info['installed'] = [jar_file]
        result_jar_list = set([jar_file])
        module_data = source_locator.ModuleData(_MODULE_NAME, module_info,
                                                _MODULE_DEPTH)
        module_data.locate_sources_path()
        self.assertEqual(module_data.jar_files, result_jar_list)

        # Test find jar by srcjar.
        module_info = dict(_MODULE_INFO)
        module_info['srcs'].extend(
            [('out/soong/.intermediates/packages/apps/test/test/android_common/'
              'gen/test.srcjar')])
        module_info['installed'] = [
            ('out/soong/.intermediates/packages/apps/test/test/android_common/'
             'test.jar')
        ]
        result_jar_list = set([
            jar_file,
            ('out/soong/.intermediates/packages/apps/test/test/'
             'android_common/test.jar')
        ])
        module_data = source_locator.ModuleData(_MODULE_NAME, module_info,
                                                _MODULE_DEPTH)
        module_data.locate_sources_path()
        self.assertEqual(module_data.jar_files, result_jar_list)

    def test_collect_jar_by_depth_value(self):
        """Test parameter --depth handling."""
        # Test find jar by module's depth greater than the --depth value from
        # command line.
        depth_by_source = 2
        module_info = dict(_MODULE_INFO)
        module_info['depth'] = 3
        module_info['installed'] = [
            ('out/soong/.intermediates/packages/apps/test/test/android_common/'
             'test.jar')
        ]
        result_src_list = set()
        result_jar_list = set(
            [('out/soong/.intermediates/packages/apps/test/test/'
              'android_common/test.jar')])
        constant.ANDROID_ROOT_PATH = uc.TEST_DATA_PATH
        module_data = source_locator.ModuleData(_MODULE_NAME, module_info,
                                                depth_by_source)
        module_data.locate_sources_path()
        self.assertEqual(module_data.src_dirs, result_src_list)
        self.assertEqual(module_data.jar_files, result_jar_list)

        # Test find source folder when module's depth equal to the --depth value
        # from command line.
        depth_by_source = 2
        module_info = dict(_MODULE_INFO)
        module_info['depth'] = 2
        result_src_list = set(['packages/apps/test/src/main/java',
                               'out/target/common/R'])
        result_test_list = set(['packages/apps/test/tests'])
        result_jar_list = set()
        module_data = source_locator.ModuleData(_MODULE_NAME, module_info,
                                                depth_by_source)
        module_data.locate_sources_path()
        self.assertEqual(module_data.src_dirs, result_src_list)
        self.assertEqual(module_data.test_dirs, result_test_list)
        self.assertEqual(module_data.jar_files, result_jar_list)

        # Test find source folder when module's depth smaller than the --depth
        # value from command line.
        depth_by_source = 3
        module_info = dict(_MODULE_INFO)
        module_info['depth'] = 2
        result_src_list = set(['packages/apps/test/src/main/java',
                               'out/target/common/R'])
        result_test_list = set(['packages/apps/test/tests'])
        result_jar_list = set()
        module_data = source_locator.ModuleData(_MODULE_NAME, module_info,
                                                depth_by_source)
        module_data.locate_sources_path()
        self.assertEqual(module_data.src_dirs, result_src_list)
        self.assertEqual(module_data.test_dirs, result_test_list)
        self.assertEqual(module_data.jar_files, result_jar_list)

    @mock.patch('aidegen.lib.project_info.ProjectInfo')
    @mock.patch('atest.atest_utils.build')
    def test_locate_source(self, mock_atest_utils_build, mock_project_info):
        """Test locate_source handling."""
        mock_atest_utils_build.build.return_value = True
        test_root_path = os.path.join(tempfile.mkdtemp(), 'test')
        shutil.copytree(uc.TEST_DATA_PATH, test_root_path)
        constant.ANDROID_ROOT_PATH = test_root_path
        generated_jar = ('out/soong/.intermediates/packages/apps/test/test/'
                         'android_common/generated.jar')
        module_info = dict(_MODULE_INFO)
        module_info['srcs'].extend(
            [('out/soong/.intermediates/packages/apps/test/test/android_common/'
              'gen/test.srcjar')])
        module_info['installed'] = [generated_jar]
        mock_project_info.dep_modules = {'test': module_info}
        mock_project_info.source_path = {
            'source_folder_path': set(),
            'test_folder_path': set(),
            'jar_path': set(),
            'jar_module_path': dict(),
        }
        # Show warning when the jar not exists after build the module.
        result_jar = set()
        source_locator.locate_source(mock_project_info, False, 0,
                                     constant.IDE_INTELLIJ, True)
        self.assertEqual(mock_project_info.source_path['jar_path'], result_jar)

        # Test on jar exists.
        jar_abspath = os.path.join(test_root_path, generated_jar)
        result_jar = set([generated_jar])
        result_jar_module_path = dict({generated_jar: module_info['path'][0]})
        try:
            open(jar_abspath, 'w').close()
            source_locator.locate_source(mock_project_info, False, 0,
                                         constant.IDE_INTELLIJ, False)
            self.assertEqual(mock_project_info.source_path['jar_path'],
                             result_jar)
            self.assertEqual(mock_project_info.source_path['jar_module_path'],
                             result_jar_module_path)
        finally:
            shutil.rmtree(test_root_path)

        # Test collects source and test folders.
        result_source = set(['packages/apps/test/src/main/java',
                             'out/target/common/R'])
        result_test = set(['packages/apps/test/tests'])
        self.assertEqual(mock_project_info.source_path['source_folder_path'],
                         result_source)
        self.assertEqual(mock_project_info.source_path['test_folder_path'],
                         result_test)

        # Test loading jar from dependencies parameter.
        default_jar = os.path.join(_MODULE_PATH, 'test.jar')
        module_info['dependencies'] = [default_jar]
        result_jar = set([generated_jar, default_jar])
        source_locator.locate_source(mock_project_info, False, 0,
                                     constant.IDE_INTELLIJ, False)
        self.assertEqual(mock_project_info.source_path['jar_path'], result_jar)


if __name__ == '__main__':
    unittest.main()
