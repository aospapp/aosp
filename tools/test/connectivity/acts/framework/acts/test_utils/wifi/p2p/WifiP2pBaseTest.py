#!/usr/bin/env python3
#
#   Copyright 2018 - Google
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

import acts.utils
import re
import time

from acts import asserts
from acts import utils
from acts.base_test import BaseTestClass
from acts.test_utils.wifi import wifi_test_utils as wutils
from acts.test_utils.wifi.p2p import wifi_p2p_const as p2pconsts

class WifiP2pBaseTest(BaseTestClass):
    def __init__(self, controllers):
        if not hasattr(self, 'android_devices'):
            super(WifiP2pBaseTest, self).__init__(controllers)

    def setup_class(self):
        self.dut1 = self.android_devices[0]
        self.dut2 = self.android_devices[1]
        self.dut1_mac = self.get_p2p_mac_address(self.dut1)
        self.dut2_mac = self.get_p2p_mac_address(self.dut2)

        #init location before init p2p
        acts.utils.set_location_service(self.dut1, True)
        acts.utils.set_location_service(self.dut2, True)

        wutils.wifi_test_device_init(self.dut1)
        utils.sync_device_time(self.dut1)
        self.dut1.droid.wifiP2pInitialize()
        time.sleep(p2pconsts.DEFAULT_FUNCTION_SWITCH_TIME)
        asserts.assert_true(self.dut1.droid.wifiP2pIsEnabled(),
                "DUT1's p2p should be initialized but it didn't")
        self.dut1.name = "Android_" + self.dut1.serial
        self.dut1.droid.wifiP2pSetDeviceName(self.dut1.name)
        wutils.wifi_test_device_init(self.dut2)
        utils.sync_device_time(self.dut2)
        self.dut2.droid.wifiP2pInitialize()
        time.sleep(p2pconsts.DEFAULT_FUNCTION_SWITCH_TIME)
        asserts.assert_true(self.dut2.droid.wifiP2pIsEnabled(),
                "DUT2's p2p should be initialized but it didn't")
        self.dut2.name = "Android_" + self.dut2.serial
        self.dut2.droid.wifiP2pSetDeviceName(self.dut2.name)

        if len(self.android_devices) > 2:
            self.dut3 = self.android_devices[2]
            wutils.wifi_test_device_init(self.dut3)
            utils.sync_device_time(self.dut3)
            self.dut3.droid.wifiP2pInitialize()
            time.sleep(p2pconsts.DEFAULT_FUNCTION_SWITCH_TIME)
            asserts.assert_true(self.dut3.droid.wifiP2pIsEnabled(),
                    "DUT1's p2p should be initialized but it didn't")
            self.dut3.name = "Android_" + self.dut3.serial
            self.dut3.droid.wifiP2pSetDeviceName(self.dut3.name)
            acts.utils.set_location_service(self.dut3, True)


    def teardown_class(self):
        self.dut1.droid.wifiP2pClose()
        self.dut2.droid.wifiP2pClose()
        acts.utils.set_location_service(self.dut1, False)
        acts.utils.set_location_service(self.dut2, False)

        if len(self.android_devices) > 2:
            self.dut3.droid.wifiP2pClose()
            acts.utils.set_location_service(self.dut3, False)

    def setup_test(self):
        for ad in self.android_devices:
            ad.droid.wakeLockAcquireBright()
            ad.droid.wakeUpNow()
            ad.ed.clear_all_events()

    def teardown_test(self):
        for ad in self.android_devices:
            # Clear p2p group info
            ad.droid.wifiP2pRequestPersistentGroupInfo()
            event = ad.ed.pop_event("WifiP2pOnPersistentGroupInfoAvailable",
                    p2pconsts.DEFAULT_TIMEOUT)
            for network in event['data']:
                ad.droid.wifiP2pDeletePersistentGroup(network['NetworkId'])
            # Clear p2p local service
            ad.droid.wifiP2pClearLocalServices()
            ad.droid.wakeLockRelease()
            ad.droid.goToSleepNow()

    def on_fail(self, test_name, begin_time):
        for ad in self.android_devices:
            ad.take_bug_report(test_name, begin_time)
            ad.cat_adb_log(test_name, begin_time)

    def get_p2p_mac_address(self, dut):
        """Gets the current MAC address being used for Wi-Fi Direct."""
        out = dut.adb.shell("ifconfig p2p0")
        return re.match(".* HWaddr (\S+).*", out, re.S).group(1)