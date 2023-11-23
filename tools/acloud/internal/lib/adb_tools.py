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
"""A tool that help to run adb to check device status."""

from distutils.spawn import find_executable

import re
import subprocess

from acloud import errors
from acloud.internal import constants
from acloud.internal.lib import utils

_ADB_CONNECT = "connect"
_ADB_DEVICE = "devices"
_ADB_DISCONNECT = "disconnect"
_ADB_STATUS_DEVICE = "device"


class AdbTools(object):
    """Adb tools.

    Args:
        adb_port: String of adb port number.
    """
    def __init__(self, adb_port=None):
        self._adb_command = ""
        self._adb_port = adb_port
        self._device_serial = ""
        self._SetDeviceSerial()
        self._CheckAdb()

    def _SetDeviceSerial(self):
        """Set device serial."""
        if self._adb_port:
            self._device_serial = "127.0.0.1:%s" % self._adb_port

    def _CheckAdb(self):
        """Find adb bin path.

        Raises:
            errors.NoExecuteCmd: Can't find the execute adb bin.
        """
        self._adb_command = find_executable(constants.ADB_BIN)
        if not self._adb_command:
            raise errors.NoExecuteCmd("Can't find the adb command.")

    def GetAdbConnectionStatus(self):
        """Get Adb connect status.

        We determine adb connection in below manner:
        1. Check if self._adb_port is null (ssh tunnel is broken).
        2. Check adb devices command to get the connection status of the
        adb devices. When the attached field is device, then device is returned,
        if it is offline, then offline is returned. If no device is found,
        the None is returned.

        e.g.
            Case 1: return device
            List of devices attached
            127.0.0.1:48451 device

            Case 2: return offline
            List of devices attached
            127.0.0.1:48451 offline

            Case 3: return None
            List of devices attached

        Returns:
            String, the result of adb connection.
        """
        if not self._adb_port:
            return None

        adb_cmd = [self._adb_command, _ADB_DEVICE]
        device_info = subprocess.check_output(adb_cmd)
        for device in device_info.splitlines():
            match = re.match(r"%s\s(?P<adb_status>.+)" % self._device_serial, device)
            if match:
                return match.group("adb_status")
        return None

    def IsAdbConnectionAlive(self):
        """Check devices connect alive.

        Returns:
            Boolean, True if adb status is device. False otherwise.
        """
        return self.GetAdbConnectionStatus() == _ADB_STATUS_DEVICE

    def IsAdbConnected(self):
        """Check devices connected or not.

        If adb connected and the status is device or offline, return True.
        If there is no any connection, return False.

        Returns:
            Boolean, True if adb status not none. False otherwise.
        """
        return self.GetAdbConnectionStatus() is not None

    def DisconnectAdb(self):
        """Disconnect adb.

        Only disconnect if the devices shows up in adb devices.
        """
        try:
            if self.IsAdbConnected():
                adb_disconnect_args = [self._adb_command,
                                       _ADB_DISCONNECT,
                                       self._device_serial]
                subprocess.check_call(adb_disconnect_args)
        except subprocess.CalledProcessError:
            utils.PrintColorString("Failed to adb disconnect %s" %
                                   self._device_serial,
                                   utils.TextColors.FAIL)

    def ConnectAdb(self):
        """Connect adb.

        Only connect if adb connection is not alive.
        """
        try:
            if not self.IsAdbConnectionAlive():
                adb_connect_args = [self._adb_command,
                                    _ADB_CONNECT,
                                    self._device_serial]
                subprocess.check_call(adb_connect_args)
        except subprocess.CalledProcessError:
            utils.PrintColorString("Failed to adb connect %s" %
                                   self._device_serial,
                                   utils.TextColors.FAIL)
