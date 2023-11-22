#
# Copyright (C) 2017 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

from vts.testcases.template.gtest_binary_test import gtest_test_case

class VtsHalWifiV1_0TestCase(gtest_test_case.GtestTestCase):
    """A class to represent a WiFi HAL VTS test case.

    Attributes:
        _nan: boolean, whether WiFi NAN feature is supported on the device.
    """

    def __init__(self, nan, *args, **kwargs):
        super(VtsHalWifiV1_0TestCase, self).__init__(*args, **kwargs)
        self._nan = nan

    # @Override
    def GetRunCommand(self):
        """Get the command to run the test. """
        orig_cmds = super(VtsHalWifiV1_0TestCase,
                          self).GetRunCommand(test_name=self.test_suite),
        new_cmd = [('{cmd}{nan_on}').format(
            cmd=orig_cmds[0][0], nan_on=" --nan_on" if self._nan else ""),
            orig_cmds[0][1]]
        return new_cmd
