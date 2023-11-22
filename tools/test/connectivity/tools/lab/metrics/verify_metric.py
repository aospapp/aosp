#!/usr/bin/env python
#
#   Copyright 2017 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

from metrics.metric import Metric


class VerifyMetric(Metric):
    """Gathers the information of connected devices via ADB"""
    COMMAND = r"adb devices | sed '1d;$d'"
    DEVICES = 'devices'

    def gather_metric(self):
        """ Gathers device info based on adb output.

        Returns:
            A dictionary with the field:
            devices: a dict with device serial number as key and device status as
            value.
        """
        device_dict = {}
        # Delete first and last line of output of adb.
        output = self._shell.run(self.COMMAND).stdout

        # Example Line, Device Serial Num TAB Phone Status
        # 00bd977c7f504caf	offline
        if output:
            for line in output.split('\n'):
                spl_line = line.split('\t')
                # spl_line[0] is serial, [1] is status. See example line.
                device_dict[spl_line[0]] = spl_line[1]

        return {self.DEVICES: device_dict}
