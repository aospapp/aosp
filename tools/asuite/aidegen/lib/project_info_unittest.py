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
import unittest
from unittest import mock

from aidegen import constant
from aidegen.lib import project_info
from aidegen.lib.project_info import ProjectInfo

import aidegen.unittest_constants as uc

_MODULE_INFO = {
    'm1': {'class': ['JAVA_LIBRARIES'], 'dependencies': ['m2', 'm6'],
           'path': ['m1']},
    'm2': {'class': ['JAVA_LIBRARIES'], 'dependencies': ['m3', 'm4']},
    'm3': {'class': ['JAVA_LIBRARIES'], 'dependencies': []},
    'm4': {'class': ['JAVA_LIBRARIES'], 'dependencies': ['m6']},
    'm5': {'class': ['JAVA_LIBRARIES'], 'dependencies': []},
    'm6': {'class': ['JAVA_LIBRARIES'], 'dependencies': ['m2']},
}
_EXPECT_DEPENDENT_MODULES = {
    'm1': {'class': ['JAVA_LIBRARIES'], 'dependencies': ['m2', 'm6'],
           'path': ['m1'], 'depth': 0},
    'm2': {'class': ['JAVA_LIBRARIES'], 'dependencies': ['m3', 'm4'],
           'depth': 1},
    'm3': {'class': ['JAVA_LIBRARIES'], 'dependencies': [], 'depth': 2},
    'm4': {'class': ['JAVA_LIBRARIES'], 'dependencies': ['m6'], 'depth': 2},
    'm6': {'class': ['JAVA_LIBRARIES'], 'dependencies': ['m2'], 'depth': 1},
}


# pylint: disable=protected-access
# pylint: disable=invalid-name
class ProjectInfoUnittests(unittest.TestCase):
    """Unit tests for project_info.py"""

    def setUp(self):
        """Initialize arguments for ProjectInfo."""
        self.args = mock.MagicMock()
        self.args.module_name = 'm1'
        self.args.project_path = ''

    @mock.patch('atest.module_info.ModuleInfo')
    def test_get_dep_modules(self, mock_module_info):
        """Test get_dep_modules recursively find dependent modules."""
        mock_module_info.is_module.return_value = True
        mock_module_info.get_paths.return_value = ['m1']
        mock_module_info.get_module_names.return_value = ['m1']
        proj_info = project_info.ProjectInfo(mock_module_info,
                                             self.args.module_name)
        proj_info.modules_info = _MODULE_INFO
        proj_info.dep_modules = proj_info.get_dep_modules()
        self.assertEqual(proj_info.dep_modules, _EXPECT_DEPENDENT_MODULES)

    def test_is_a_target_module(self):
        """Test _is_a_target_module with different conditions."""
        self.assertEqual(ProjectInfo._is_a_target_module({}), False)
        self.assertEqual(ProjectInfo._is_a_target_module({'path': ''}), False)
        self.assertEqual(ProjectInfo._is_a_target_module({'class': ''}), False)
        self.assertEqual(
            ProjectInfo._is_a_target_module({
                'class': ['APPS']
            }), True)
        self.assertEqual(
            ProjectInfo._is_a_target_module({
                'class': ['JAVA_LIBRARIES']
            }), True)
        self.assertEqual(
            ProjectInfo._is_a_target_module({
                'class': ['ROBOLECTRIC']
            }), True)

    def test_is_a_robolectric_module(self):
        """Test _is_a_robolectric_module with different conditions."""
        self.assertEqual(ProjectInfo._is_a_robolectric_module({}), False)
        self.assertEqual(
            ProjectInfo._is_a_robolectric_module({
                'path': [uc.TEST_PATH]
            }), False)
        self.assertEqual(
            ProjectInfo._is_a_robolectric_module({
                'path': ['path/robotests']
            }), True)
        self.assertEqual(
            ProjectInfo._is_a_robolectric_module({
                'path': ['path/robolectric']
            }), True)
        self.assertEqual(
            ProjectInfo._is_a_robolectric_module({
                'path': ['robotests/robolectric']
            }), True)

    def test_get_target_name(self):
        """Test _get_target_name with different conditions."""
        constant.ANDROID_ROOT_PATH = uc.TEST_DATA_PATH
        self.assertEqual(
            ProjectInfo._get_target_name(uc.TEST_MODULE, uc.TEST_DATA_PATH),
            os.path.basename(uc.TEST_DATA_PATH))
        self.assertEqual(
            ProjectInfo._get_target_name(uc.TEST_MODULE, uc.TEST_PATH),
            uc.TEST_MODULE)


if __name__ == '__main__':
    unittest.main()
