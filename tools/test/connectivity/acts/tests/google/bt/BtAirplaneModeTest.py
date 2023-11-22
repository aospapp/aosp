#/usr/bin/env python3.4
#
# Copyright (C) 2016 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.
"""
Test script to test various airplane mode scenarios and how it
affects Bluetooth state.
"""
from queue import Empty
import time
from acts.test_utils.bt.BluetoothBaseTest import BluetoothBaseTest
from acts.test_utils.bt.bt_test_utils import bluetooth_enabled_check


class BtAirplaneModeTest(BluetoothBaseTest):
    default_timeout = 10
    grace_timeout = 4

    def __init__(self, controllers):
        BluetoothBaseTest.__init__(self, controllers)
        self.dut = self.android_devices[0]

    @BluetoothBaseTest.bt_test_wrap
    def test_bt_on_toggle_airplane_mode_on(self):
        """Test that toggles airplane mode on while BT on

        Turning airplane mode on should toggle Bluetooth off
        successfully.

        Steps:
        1. Verify that Bluetooth state is on
        2. Turn airplane mode on
        3. Verify that Bluetooth state is off

        Expected Result:
        Bluetooth should toggle off successfully.

        Returns:
          Pass if True
          Fail if False

        TAGS: Bluetooth, Airplane
        Priority: 3
        """
        if not bluetooth_enabled_check(self.dut):
            self.log.error("Failed to set Bluetooth state to enabled")
            return False
        self.dut.droid.connectivityToggleAirplaneMode(True)
        # Since there is no callback for airplane mode toggling we need
        # to give the connectivity manger grace time to turn off the radios.
        time.sleep(self.grace_timeout)
        return not self.dut.droid.bluetoothCheckState()

    @BluetoothBaseTest.bt_test_wrap
    def test_bt_on_toggle_airplane_mode_on_bt_remains_off(self):
        """Test that verifies BT remains off after airplane mode toggles

        Turning airplane mode on should toggle Bluetooth off
        successfully and Bluetooth state should remain off. For
        this test we will use 60 seconds as a baseline.

        Steps:
        1. Verify that Bluetooth state is on
        2. Turn airplane mode on
        3. Verify that Bluetooth state is off
        3. Verify tat Bluetooth state remains off for 60 seconds

        Expected Result:
        Bluetooth should remain toggled off.

        Returns:
          Pass if True
          Fail if False

        TAGS: Bluetooth, Airplane
        Priority: 3
        """
        if not bluetooth_enabled_check(self.dut):
            self.log.error("Failed to set Bluetooth state to enabled")
            return False
        self.dut.droid.connectivityToggleAirplaneMode(True)
        toggle_timeout = 60
        self.log.info(
            "Waiting {} seconds until verifying Bluetooth state.".format(
                toggle_timeout))
        time.sleep(toggle_timeout)
        return not self.dut.droid.bluetoothCheckState()

    @BluetoothBaseTest.bt_test_wrap
    def test_bt_on_toggle_airplane_mode_on_then_off(self):
        """Test that toggles airplane mode both on and off

        Turning airplane mode on should toggle Bluetooth off
        successfully. Turning airplane mode off should toggle
        Bluetooth back on.

        Steps:
        1. Verify that Bluetooth state is on
        2. Turn airplane mode on
        3. Verify that Bluetooth state is off
        4. Turn airplane mode off
        5. Verify that Bluetooth state is on

        Expected Result:
        Bluetooth should toggle off successfully.

        Returns:
          Pass if True
          Fail if False

        TAGS: Bluetooth, Airplane
        Priority: 3
        """
        if not bluetooth_enabled_check(self.dut):
            self.log.error("Failed to set Bluetooth state to enabled")
            return False
        self.dut.droid.connectivityToggleAirplaneMode(True)
        self.dut.droid.connectivityToggleAirplaneMode(False)
        # Since there is no callback for airplane mode toggling off we need
        # to give the connectivity manger grace time to turn on the radios.
        time.sleep(self.grace_timeout)
        return self.dut.droid.bluetoothCheckState()
