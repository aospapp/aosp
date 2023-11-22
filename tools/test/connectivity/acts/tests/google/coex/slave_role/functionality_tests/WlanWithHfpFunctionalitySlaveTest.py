#!/usr/bin/env python3
#
# Copyright (C) 2018 The Android Open Source Project
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

import time

from acts.test_utils.bt.bt_test_utils import clear_bonded_devices
from acts.test_utils.coex.bluez_test_utils import connect_bluetooth_device
from acts.test_utils.coex.bluez_test_utils import disconnect_bluetooth_profile
from acts.test_utils.coex.CoexBluezBaseTest import CoexBluezBaseTest
from acts.test_utils.coex.coex_constants import bluetooth_profiles
from acts.test_utils.coex.coex_constants import HFP_TEST
from acts.test_utils.coex.coex_constants import WAIT_TIME


class WlanWithHfpFunctionalitySlaveTest(CoexBluezBaseTest):

    def __init__(self, controllers):
        super().__init__(controllers, HFP_TEST)

    def setup_class(self):
        super().setup_class()
        if not self.bluez_flag:
            return False
        req_params = ["iterations"]
        self.unpack_userparams(req_params)

    def setup_test(self):
        super().setup_test()
        if not self.device_interface or not self.paired:
            return False
        if not connect_bluetooth_device(self.device_interface, self.bus,
                                        bluetooth_profiles["HFP_AG"]):
            self.log.error("Connection Failed")
            return False

    def teardown_test(self):
        clear_bonded_devices(self.pri_ad)
        super().teardown_test()

    def teardown_class(self):
        super().teardown_class()

    def connect_disconnect_hfp_headset(self):
        """Connect and disconnect headset for multiple iterations."""
        for i in range(self.iterations):
            if not disconnect_bluetooth_profile(bluetooth_profiles["HFP_AG"],
                                                self.pri_ad,
                                                self.device_interface):
                self.log.error("Disconnection Failed")
                return False
            time.sleep(WAIT_TIME)
            if not connect_bluetooth_device(self.device_interface, self.bus,
                                            bluetooth_profiles["HFP_AG"]):
                self.log.error("Connection Failed")
                return False
            time.sleep(WAIT_TIME)
        return True

    def connect_disconnect_hfp_headset_with_iperf(self):
        """Wrapper function to start iperf traffic and connect/disconnect
        to headset for N iterations.
        """
        self.run_iperf_and_get_result()
        if not self.connect_disconnect_hfp_headset():
            return False
        return self.teardown_result()

    def test_connect_disconnect_hfp_headset_slave_role_with_tcp_ul(self):
        """Starts TCP-uplink traffic and connect/disconnect hfp headset.

        This test is to start TCP-uplink traffic between host machine and
        android device and test functional behaviour of connection and
        disconnection to hfp headset when android device as a slave.

        Steps:
        1. Run TCP-uplink traffic.
        2. Initiate connection from hfp headset(bluez).
        2. Connect and disconnect hfp headset.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_058
        """
        if not self.connect_disconnect_hfp_headset_with_iperf():
            return False
        return True

    def test_connect_disconnect_hfp_headset_slave_role_with_tcp_dl(self):
        """Starts TCP-downlink traffic and connect/disconnect hfp headset.

        This test is to start TCP-downlink traffic between host machine and
        android device and test functional behaviour of connection and
        disconnection to hfp headset when android device as a slave.

        Steps:
        1. Run TCP-downlink traffic.
        2. Initiate connection from hfp headset(bluez).
        2. Connect and disconnect hfp headset.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_059
        """
        if not self.connect_disconnect_hfp_headset_with_iperf():
            return False
        return True

    def test_connect_disconnect_hfp_headset_slave_role_with_udp_ul(self):
        """Starts UDP-uplink traffic and connect/disconnect hfp headset.

        This test is to start UDP-uplink traffic between host machine and
        android device and test functional behaviour of connection and
        disconnection to hfp headset when android device as a slave.

        Steps:
        1. Run UDP-uplink traffic.
        2. Initiate connection from hfp headset(bluez).
        2. Connect and disconnect hfp headset.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_060
        """
        if not self.connect_disconnect_hfp_headset_with_iperf():
            return False
        return True

    def test_connect_disconnect_hfp_headset_slave_role_with_udp_dl(self):
        """Starts UDP-downlink traffic and connect/disconnect hfp headset.

        This test is to start UDP-downlink traffic between host machine and
        android device and test functional behaviour of connection and
        disconnection to hfp headset when android device as a slave.

        Steps:
        1. Run UDP-downlink traffic.
        2. Initiate connection from hfp headset(bluez).
        2. Connect and disconnect hfp headset.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_061
        """
        if not self.connect_disconnect_hfp_headset_with_iperf():
            return False
        return True
