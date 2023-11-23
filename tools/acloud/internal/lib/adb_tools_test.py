# Copyright 2019 - The Android Open Source Project
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
"""Tests for AdbTools."""

import unittest
import subprocess

from acloud.internal.lib import adb_tools
from acloud.internal.lib import driver_test_lib


class AdbToolsTest(driver_test_lib.BaseDriverTest):
    """Test adb functions."""
    DEVICE_ALIVE = ("List of devices attached\n"
                    "127.0.0.1:48451 device")
    DEVICE_OFFLINE = ("List of devices attached\n"
                      "127.0.0.1:48451 offline")
    DEVICE_NONE = ("List of devices attached")

    # pylint: disable=no-member
    def testGetAdbConnectionStatus(self):
        """Test get adb connection status."""
        fake_adb_port = "48451"
        self.Patch(subprocess, "check_output", return_value=self.DEVICE_ALIVE)
        adb_cmd = adb_tools.AdbTools(fake_adb_port)
        self.assertEqual(adb_cmd.GetAdbConnectionStatus(), "device")

        self.Patch(subprocess, "check_output", return_value=self.DEVICE_OFFLINE)
        self.assertEqual(adb_cmd.GetAdbConnectionStatus(), "offline")

        self.Patch(subprocess, "check_output", return_value=self.DEVICE_NONE)
        self.assertEqual(adb_cmd.GetAdbConnectionStatus(), None)

    # pylint: disable=no-member,protected-access
    def testConnectAdb(self):
        """Test connect adb."""
        fake_adb_port = "48451"
        self.Patch(subprocess, "check_output", return_value=self.DEVICE_ALIVE)
        self.Patch(subprocess, "check_call", return_value=True)
        adb_cmd = adb_tools.AdbTools(fake_adb_port)
        adb_cmd.ConnectAdb()
        self.assertEqual(adb_cmd.IsAdbConnectionAlive(), True)
        subprocess.check_call.assert_not_called()

        self.Patch(subprocess, "check_output", return_value=self.DEVICE_OFFLINE)
        self.Patch(subprocess, "check_call", return_value=True)
        subprocess.check_call.call_count = 0
        adb_cmd = adb_tools.AdbTools(fake_adb_port)
        adb_cmd.ConnectAdb()
        self.assertEqual(adb_cmd.IsAdbConnectionAlive(), False)
        subprocess.check_call.assert_called_with([adb_cmd._adb_command,
                                                  adb_tools._ADB_CONNECT,
                                                  adb_cmd._device_serial])

    # pylint: disable=no-member,protected-access
    def testDisconnectAdb(self):
        """Test disconnect adb."""
        fake_adb_port = "48451"
        self.Patch(subprocess, "check_output", return_value=self.DEVICE_ALIVE)
        self.Patch(subprocess, "check_call", return_value=True)
        adb_cmd = adb_tools.AdbTools(fake_adb_port)

        self.assertEqual(adb_cmd.IsAdbConnected(), True)
        subprocess.check_call.assert_not_called()

        self.Patch(subprocess, "check_output", return_value=self.DEVICE_OFFLINE)
        self.Patch(subprocess, "check_call", return_value=True)
        subprocess.check_call.call_count = 0
        adb_cmd = adb_tools.AdbTools(fake_adb_port)
        adb_cmd.DisconnectAdb()
        self.assertEqual(adb_cmd.IsAdbConnected(), True)
        subprocess.check_call.assert_called_with([adb_cmd._adb_command,
                                                  adb_tools._ADB_DISCONNECT,
                                                  adb_cmd._device_serial])

        self.Patch(subprocess, "check_output", return_value=self.DEVICE_NONE)
        self.Patch(subprocess, "check_call", return_value=True)
        subprocess.check_call.call_count = 0
        adb_cmd = adb_tools.AdbTools(fake_adb_port)
        adb_cmd.DisconnectAdb()
        self.assertEqual(adb_cmd.IsAdbConnected(), False)
        subprocess.check_call.assert_not_called()


if __name__ == "__main__":
    unittest.main()
