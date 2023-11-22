#!/usr/bin/env python3.4
#
#   Copyright 2017 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the 'License');
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an 'AS IS' BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

import os
from acts import base_test
from acts.test_utils.wifi import wifi_test_utils as wutils
from acts.test_utils.wifi import wifi_power_test_utils as wputils
from acts.test_decorators import test_tracker_info


class PowerbaselineTest(base_test.BaseTestClass):
    """Power baseline tests for rockbottom state.
    Rockbottom for wifi on/off, screen on/off, everything else turned off

    """

    def __init__(self, controllers):

        base_test.BaseTestClass.__init__(self, controllers)
        self.tests = ('test_rockbottom_screenoff_wifidisabled',
                      'test_rockbottom_screenoff_wifidisconnected',
                      'test_rockbottom_screenon_wifidisabled',
                      'test_rockbottom_screenon_wifidisconnected')

    def setup_class(self):

        self.dut = self.android_devices[0]
        req_params = ['baselinetest_params']
        self.unpack_userparams(req_params)
        self.unpack_testparams(self.baselinetest_params)
        self.mon_data_path = os.path.join(self.log_path, 'Monsoon')
        self.mon = self.monsoons[0]
        self.mon.set_max_current(8.0)
        self.mon.set_voltage(4.2)
        self.mon.attach_device(self.dut)
        self.mon_info = wputils.create_monsoon_info(self)

    def teardown_class(self):

        self.mon.usb('on')

    def unpack_testparams(self, bulk_params):
        """Unpack all the test specific parameters.

        Args:
            bulk_params: dict with all test specific params in the config file
        """
        for key in bulk_params.keys():
            setattr(self, key, bulk_params[key])

    def rockbottom_test_func(self, screen_status, wifi_status):
        """Test function for baseline rockbottom tests.

        Args:
            screen_status: screen on or off
            wifi_status: wifi enable or disable, on/off, not connected even on
        """
        # Initialize the dut to rock-bottom state
        wputils.dut_rockbottom(self.dut)
        if wifi_status == 'ON':
            wutils.wifi_toggle_state(self.dut, True)
        if screen_status == 'OFF':
            self.dut.droid.goToSleepNow()
            self.dut.log.info('Screen is OFF')
        # Collecting current measurement data and plot
        file_path, avg_current = wputils.monsoon_data_collect_save(
            self.dut, self.mon_info, self.current_test_name, self.bug_report)
        wputils.monsoon_data_plot(self.mon_info, file_path)
        wputils.pass_fail_check(self, avg_current)

    # Test cases
    @test_tracker_info(uuid='e7ab71f4-1e14-40d2-baec-cde19a3ac859')
    def test_rockbottom_screenoff_wifidisabled(self):

        self.rockbottom_test_func('OFF', 'OFF')

    @test_tracker_info(uuid='167c847d-448f-4c7c-900f-82c552d7d9bb')
    def test_rockbottom_screenoff_wifidisconnected(self):

        self.rockbottom_test_func('OFF', 'ON')

    @test_tracker_info(uuid='2cd25820-8548-4e60-b0e3-63727b3c952c')
    def test_rockbottom_screenon_wifidisabled(self):

        self.rockbottom_test_func('ON', 'OFF')

    @test_tracker_info(uuid='d7d90a1b-231a-47c7-8181-23814c8ff9b6')
    def test_rockbottom_screenon_wifidisconnected(self):

        self.rockbottom_test_func('ON', 'ON')
