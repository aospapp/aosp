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


"""Unittests for ide_util."""

import os
import unittest
from unittest import mock
from unittest.mock import patch

from subprocess import CalledProcessError as cmd_err

from aidegen.lib.android_dev_os import AndroidDevOS
from aidegen.lib import ide_util
from aidegen.lib.ide_util import IdeBase
from aidegen.lib.ide_util import IdeIntelliJ
from aidegen.lib.ide_util import IdeLinuxEclipse
from aidegen.lib.ide_util import IdeLinuxIntelliJ
from aidegen.lib.ide_util import IdeLinuxStudio
from aidegen.lib.ide_util import IdeMacEclipse
from aidegen.lib.ide_util import IdeMacIntelliJ
from aidegen.lib.ide_util import IdeMacStudio
from aidegen.lib.ide_util import IdeUtil


import aidegen.unittest_constants as uc


#pylint: disable=protected-access
class IdeUtilUnittests(unittest.TestCase):
    """Unit tests for ide_util.py."""

    _TEST_PRJ_PATH1 = ''
    _TEST_PRJ_PATH2 = ''
    _TEST_PRJ_PATH3 = ''
    _TEST_PRJ_PATH4 = ''


    def setUp(self):
        """Prepare the testdata related path."""
        IdeUtilUnittests._TEST_PRJ_PATH1 = os.path.join(uc.TEST_DATA_PATH,
                                                        'android_facet.iml')
        IdeUtilUnittests._TEST_PRJ_PATH2 = os.path.join(uc.TEST_DATA_PATH,
                                                        'project/test.java')
        IdeUtilUnittests._TEST_PRJ_PATH3 = uc.TEST_DATA_PATH
        IdeUtilUnittests._TEST_PRJ_PATH4 = os.path.join(uc.TEST_DATA_PATH,
                                                        '.idea')


    def tearDown(self):
        """Clear the testdata related path."""
        IdeUtilUnittests._TEST_PRJ_PATH1 = ''
        IdeUtilUnittests._TEST_PRJ_PATH2 = ''
        IdeUtilUnittests._TEST_PRJ_PATH3 = ''
        IdeUtilUnittests._TEST_PRJ_PATH4 = ''

    def test_is_intellij_project(self):
        """Test _is_intellij_project."""
        self.assertFalse(
            ide_util._is_intellij_project(IdeUtilUnittests._TEST_PRJ_PATH2))
        self.assertTrue(
            ide_util._is_intellij_project(IdeUtilUnittests._TEST_PRJ_PATH1))
        self.assertTrue(
            ide_util._is_intellij_project(IdeUtilUnittests._TEST_PRJ_PATH3))
        self.assertFalse(
            ide_util._is_intellij_project(IdeUtilUnittests._TEST_PRJ_PATH4))

    @mock.patch('glob.glob', return_value=uc.IDEA_SH_FIND_NONE)
    def test_get_intellij_sh_none(self, mock_glob):
        """Test with the cmd return none, test result should be None."""
        mock_glob.return_value = uc.IDEA_SH_FIND_NONE
        self.assertEqual(
            None,
            ide_util._get_intellij_version_path(IdeLinuxIntelliJ()._ls_ce_path))
        self.assertEqual(
            None,
            ide_util._get_intellij_version_path(IdeLinuxIntelliJ()._ls_ue_path))

    @mock.patch('builtins.input')
    @mock.patch('glob.glob', return_value=uc.IDEA_SH_FIND)
    def test_ask_preference(self, mock_glob, mock_input):
        """Ask users' preference, the result should be equal to test data."""
        mock_glob.return_value = uc.IDEA_SH_FIND
        mock_input.return_value = '1'
        self.assertEqual(
            ide_util._ask_preference(uc.IDEA_SH_FIND), uc.IDEA_SH_FIND[0])
        mock_input.return_value = '2'
        self.assertEqual(
            ide_util._ask_preference(uc.IDEA_SH_FIND), uc.IDEA_SH_FIND[1])

    @unittest.skip('Skip to use real command to launch IDEA.')
    def test_run_intellij_sh_in_linux(self):
        """Follow the target behavior, with sh to show UI, else raise err."""
        sh_path = IdeLinuxIntelliJ()._get_script_from_system()
        if sh_path:
            ide_util_obj = IdeUtil()
            ide_util_obj.launch_ide(IdeUtilUnittests._TEST_PRJ_PATH1)
        else:
            self.assertRaises(cmd_err)

    @mock.patch.object(ide_util, '_get_linux_ide')
    @mock.patch.object(ide_util, '_get_mac_ide')
    def test_get_ide(self, mock_mac, mock_linux):
        """Test if _get_ide calls the correct respective functions."""
        ide_util._get_ide(None, 'j', False, is_mac=True)
        self.assertTrue(mock_mac.called)
        ide_util._get_ide(None, 'j', False, is_mac=False)
        self.assertTrue(mock_linux.called)

    def test_get_mac_and_linux_ide(self):
        """Test if _get_mac_ide and _get_linux_ide return correct IDE class."""
        self.assertIsInstance(ide_util._get_mac_ide(), IdeMacIntelliJ)
        self.assertIsInstance(ide_util._get_mac_ide(None, 's'), IdeMacStudio)
        self.assertIsInstance(ide_util._get_mac_ide(None, 'e'), IdeMacEclipse)
        self.assertIsInstance(ide_util._get_linux_ide(), IdeLinuxIntelliJ)
        self.assertIsInstance(
            ide_util._get_linux_ide(None, 's'), IdeLinuxStudio)
        self.assertIsInstance(
            ide_util._get_linux_ide(None, 'e'), IdeLinuxEclipse)

    @mock.patch.object(ide_util, '_get_script_from_input_path')
    @mock.patch.object(IdeIntelliJ, '_get_script_from_system')
    def test_init_ideintellij(self, mock_sys, mock_input):
        """Test IdeIntelliJ's __init__ method."""
        IdeLinuxIntelliJ()
        self.assertTrue(mock_sys.called)
        IdeMacIntelliJ()
        self.assertTrue(mock_sys.called)
        IdeLinuxIntelliJ('some_path')
        self.assertTrue(mock_input.called)
        IdeMacIntelliJ('some_path')
        self.assertTrue(mock_input.called)

    @mock.patch.object(IdeIntelliJ, '_get_config_root_paths')
    @mock.patch.object(IdeBase, 'apply_optional_config')
    def test_config_ide(self, mock_config, mock_paths):
        """Test IDEA, IdeUtil.config_ide won't call base none implement api."""
        util_obj = IdeUtil()
        util_obj.config_ide()
        self.assertFalse(mock_config.called)
        self.assertFalse(mock_paths.called)

    @patch.object(ide_util, '_get_script_from_input_path')
    @patch.object(ide_util, '_get_script_from_internal_path')
    def test_get_linux_config_1(self, mock_path, mock_path_2):
        """Test to get unique config path for linux IDEA case."""
        if not AndroidDevOS.MAC == AndroidDevOS.get_os_type():
            mock_path.return_value = '/opt/intelliJ-ce-2018.3/bin/idea.sh'
            mock_path_2.return_value = '/opt/intelliJ-ce-2018.3/bin/idea.sh'
            ide_obj = IdeLinuxIntelliJ()
            self.assertEqual(1, len(ide_obj._get_config_root_paths()))
        else:
            self.assertTrue(AndroidDevOS.MAC == AndroidDevOS.get_os_type())

    @patch('glob.glob')
    @patch.object(ide_util, '_get_script_from_input_path')
    @patch.object(ide_util, '_get_script_from_internal_path')
    def test_get_linux_config_2(self, mock_path, mock_path_2, mock_filter):
        """Test to get unique config path for linux IDEA case."""
        if not AndroidDevOS.MAC == AndroidDevOS.get_os_type():
            mock_path.return_value = '/opt/intelliJ-ce-2018.3/bin/idea.sh'
            mock_path_2.return_value = '/opt/intelliJ-ce-2018.3/bin/idea.sh'
            ide_obj = IdeLinuxIntelliJ()
            mock_filter.called = False
            ide_obj._get_config_root_paths()
            self.assertFalse(mock_filter.called)
        else:
            self.assertTrue(AndroidDevOS.MAC == AndroidDevOS.get_os_type())

    def test_get_mac_config_root_paths(self):
        """Return None if there's no install path."""
        if AndroidDevOS.MAC == AndroidDevOS.get_os_type():
            mac_ide = IdeMacIntelliJ()
            mac_ide._installed_path = None
            self.assertIsNone(mac_ide._get_config_root_paths())
        else:
            self.assertFalse(AndroidDevOS.MAC == AndroidDevOS.get_os_type())

    @patch('glob.glob')
    @patch.object(ide_util, '_get_script_from_input_path')
    @patch.object(ide_util, '_get_script_from_internal_path')
    def test_get_linux_config_root(self, mock_path_1, mock_path_2, mock_filter):
        """Test to go filter logic for self download case."""
        mock_path_1.return_value = '/usr/tester/IDEA/IC2018.3.3/bin'
        mock_path_2.return_value = '/usr/tester/IDEA/IC2018.3.3/bin'
        ide_obj = IdeLinuxIntelliJ()
        mock_filter.reset()
        ide_obj._get_config_root_paths()
        self.assertTrue(mock_filter.called)

    @patch('os.path.join')
    def test_get_code_style_config(self, mock_join_path):
        """Test return None, when no config source case existed."""
        mock_join_path.return_value = '/usr/tester/no_file.test'
        self.assertIsNone(ide_util.IdeIntelliJ._get_code_style_config())

    @patch('shutil.copy2')
    @patch.object(IdeIntelliJ, '_get_code_style_config')
    def test_apply_optional_config(self, mock_config_path, mock_copy):
        """Test copy logic should not work if there's no config source."""
        mock_config_path.return_value = None
        ide_obj = IdeIntelliJ()
        ide_obj.apply_optional_config()
        self.assertFalse(mock_copy.called)


if __name__ == '__main__':
    unittest.main()
