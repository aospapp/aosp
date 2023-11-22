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

"""Unittests for module_info_utils."""

import copy
import os
import subprocess
import unittest
from unittest import mock

import aidegen.unittest_constants as uc
from aidegen.lib import errors
from aidegen.lib import module_info_util
from atest import module_info


_TEST_CLASS_DICT = {'class': ['JAVA_LIBRARIES']}
_TEST_SRCS_BAR_DICT = {'srcs': ['Bar']}
_TEST_SRCS_BAZ_DICT = {'srcs': ['Baz']}
_TEST_DEP_FOO_DIST = {'dependencies': ['Foo']}
_TEST_DEP_SRC_DICT = {'dependencies': ['Foo'], 'srcs': ['Bar']}
_TEST_DEP_SRC_MORE_DICT = {'dependencies': ['Foo'], 'srcs': ['Baz', 'Bar']}
_TEST_MODULE_A_DICT = {
    'module_a': {
        'class': ['JAVA_LIBRARIES'],
        'path': ['path_a'],
        'installed': ['out/path_a/a.jar'],
        'dependencies': ['Foo'],
        'srcs': ['Bar']
    }
}
_TEST_MODULE_A_DICT_HAS_NONEED_ITEMS = {
    'module_a': {
        'class': ['JAVA_LIBRARIES'],
        'path': ['path_a'],
        'installed': ['out/path_a/a.jar'],
        'dependencies': ['Foo'],
        'srcs': ['Bar'],
        'compatibility_suites': ['null-suite'],
        'module_name': ['ltp_fstat03_64']
    }
}
_TEST_MODULE_A_JOIN_PATH_DICT = {
    'module_a': {
        'class': ['JAVA_LIBRARIES'],
        'path': ['path_a'],
        'installed': ['out/path_a/a.jar'],
        'dependencies': ['Foo'],
        'srcs': ['path_a/Bar']
    }
}


# pylint: disable=protected-access
# pylint: disable=invalid-name
class AidegenModuleInfoUtilUnittests(unittest.TestCase):
    """Unit tests for moduole_info_utils.py"""

    def test_merge_module_keys_with_empty_dict(self):
        """Test _merge_module_keys with an empty dictionary."""
        test_b_dict = {}
        test_m_dict = copy.deepcopy(_TEST_DEP_SRC_DICT)
        module_info_util._merge_module_keys(test_m_dict, test_b_dict)
        self.assertEqual(_TEST_DEP_SRC_DICT, test_m_dict)

    def test_merge_module_keys_with_key_not_in_orginial_dict(self):
        """Test _merge_module_keys with the key does not exist in the dictionary
        to be merged into.
        """
        test_b_dict = _TEST_SRCS_BAR_DICT
        test_m_dict = copy.deepcopy(_TEST_DEP_FOO_DIST)
        module_info_util._merge_module_keys(test_m_dict, test_b_dict)
        self.assertEqual(_TEST_DEP_SRC_DICT, test_m_dict)

    def test_merge_module_keys_with_key_in_orginial_dict(self):
        """Test _merge_module_keys with with the key exists in the dictionary
        to be merged into.
        """
        test_b_dict = _TEST_SRCS_BAZ_DICT
        test_m_dict = copy.deepcopy(_TEST_DEP_SRC_DICT)
        module_info_util._merge_module_keys(test_m_dict, test_b_dict)
        self.assertEqual(
            set(_TEST_DEP_SRC_MORE_DICT['srcs']), set(test_m_dict['srcs']))
        self.assertEqual(
            set(_TEST_DEP_SRC_MORE_DICT['dependencies']),
            set(test_m_dict['dependencies']))

    def test_merge_module_keys_with_duplicated_item_dict(self):
        """Test _merge_module_keys with with the key exists in the dictionary
        to be merged into.
        """
        test_b_dict = _TEST_CLASS_DICT
        test_m_dict = copy.deepcopy(_TEST_CLASS_DICT)
        module_info_util._merge_module_keys(test_m_dict, test_b_dict)
        self.assertEqual(_TEST_CLASS_DICT, test_m_dict)

    def test_copy_needed_items_from_empty_dict(self):
        """Test _copy_needed_items_from an empty dictionary."""
        test_mk_dict = {}
        want_dict = {}
        self.assertEqual(want_dict,
                         module_info_util._copy_needed_items_from(test_mk_dict))

    def test_copy_needed_items_from_all_needed_items_dict(self):
        """Test _copy_needed_items_from a dictionary with all needed items."""
        self.assertEqual(
            _TEST_MODULE_A_DICT,
            module_info_util._copy_needed_items_from(_TEST_MODULE_A_DICT))

    def test_copy_needed_items_from_some_needed_items_dict(self):
        """Test _copy_needed_items_from a dictionary with some needed items."""
        self.assertEqual(
            _TEST_MODULE_A_DICT,
            module_info_util._copy_needed_items_from(
                _TEST_MODULE_A_DICT_HAS_NONEED_ITEMS))

    @mock.patch('subprocess.check_call')
    @mock.patch('os.environ.copy')
    def test_build_target_normal(self, mock_copy, mock_check_call):
        """Test _build_target with verbose true and false."""
        mock_copy.return_value = ''
        amodule_info = module_info.ModuleInfo()
        cmd = [module_info_util._GENERATE_JSON_COMMAND]
        module_info_util._build_target(cmd, uc.TEST_MODULE, amodule_info, True)
        self.assertTrue(mock_copy.called)
        self.assertTrue(mock_check_call.called)
        mock_check_call.assert_called_with(
            cmd,
            stderr=subprocess.STDOUT,
            env=mock_copy.return_value,
            shell=True)
        module_info_util._build_target(cmd, uc.TEST_MODULE, amodule_info, False)
        self.assertTrue(mock_check_call.called)
        mock_check_call.assert_called_with(cmd, shell=True)

    @mock.patch('os.path.getmtime')
    @mock.patch('os.path.isfile')
    def test_is_new_json_file_generated(self, mock_isfile, mock_getmtime):
        """Test _is_new_json_file_generated with different situations."""
        jfile = 'path/test.json'
        mock_isfile.return_value = True
        self.assertEqual(
            mock_isfile.return_value,
            module_info_util._is_new_json_file_generated(jfile, None))
        mock_isfile.return_value = False
        self.assertEqual(
            mock_isfile.return_value,
            module_info_util._is_new_json_file_generated(jfile, None))
        original_file_mtime = 1000
        mock_getmtime.return_value = original_file_mtime
        self.assertEqual(
            False,
            module_info_util._is_new_json_file_generated(
                jfile, original_file_mtime))
        mock_getmtime.return_value = 1001
        self.assertEqual(
            True,
            module_info_util._is_new_json_file_generated(
                jfile, original_file_mtime))

    @mock.patch('builtins.input')
    @mock.patch('glob.glob')
    def test_build_failed_handle(self, mock_glob, mock_input):
        """Test _build_failed_handle with different situations."""
        mock_glob.return_value = ['project/file.iml']
        mock_input.return_value = 'N'
        with self.assertRaises(SystemExit) as cm:
            module_info_util._build_failed_handle(uc.TEST_MODULE)
        self.assertEqual(cm.exception.code, 1)
        mock_glob.return_value = []
        with self.assertRaises(errors.BuildFailureError):
            module_info_util._build_failed_handle(uc.TEST_MODULE)

    @mock.patch('builtins.open')
    def test_get_soong_build_json_dict_failed(self, mock_open):
        """Test _get_soong_build_json_dict failure and raise error."""
        mock_open.side_effect = IOError
        with self.assertRaises(errors.JsonFileNotExistError):
            module_info_util._get_soong_build_json_dict()

    @mock.patch('aidegen.lib.module_info_util._build_failed_handle')
    @mock.patch('aidegen.lib.module_info_util._is_new_json_file_generated')
    @mock.patch('subprocess.check_call')
    def test_build_target(self, mock_call, mock_new, mock_handle):
        """Test _build_target with different arguments."""
        cmd = [module_info_util._GENERATE_JSON_COMMAND]
        main_project = ''
        amodule_info = {}
        verbose = False
        module_info_util._build_target(cmd, main_project, amodule_info, verbose)
        mock_call.assert_called_with(cmd, shell=True)
        verbose = True
        full_env_vars = os.environ.copy()
        module_info_util._build_target(cmd, main_project, amodule_info, verbose)
        mock_call.assert_called_with(cmd, stderr=subprocess.STDOUT,
                                     env=full_env_vars, shell=True)
        mock_call.side_effect = subprocess.CalledProcessError(1, '')
        mock_new.return_value = False
        module_info_util._build_target(cmd, main_project, amodule_info, verbose)
        self.assertTrue(mock_new.called)
        self.assertFalse(mock_handle.called)
        mock_new.return_value = True
        module_info_util._build_target(cmd, main_project, amodule_info, verbose)
        self.assertTrue(mock_new.called)
        self.assertTrue(mock_handle.called)


if __name__ == '__main__':
    unittest.main()
