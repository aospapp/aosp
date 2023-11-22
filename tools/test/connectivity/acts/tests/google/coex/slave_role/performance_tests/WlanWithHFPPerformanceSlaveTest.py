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
from acts.test_utils.coex.bluez_test_utils import answer_call
from acts.test_utils.coex.bluez_test_utils import connect_bluetooth_device
from acts.test_utils.coex.bluez_test_utils import (
    initiate_and_disconnect_call_from_bluez)
from acts.test_utils.coex.CoexBluezBaseTest import CoexBluezBaseTest
from acts.test_utils.coex.coex_constants import HFP_TEST
from acts.test_utils.coex.coex_constants import bluetooth_profiles
from acts.test_utils.coex.coex_constants import CALL_WAIT_TIME
from acts.test_utils.coex.coex_test_utils import setup_tel_config
from acts.test_utils.tel.tel_test_utils import initiate_call


class WlanWithHFPPerformanceSlaveTest(CoexBluezBaseTest):

    def __init__(self, controllers):
        super().__init__(controllers, HFP_TEST)

    def setup_class(self):
        super().setup_class()
        if not self.bluez_flag:
            return False
        req_params = ["sim_conf_file"]
        self.unpack_userparams(req_params)
        self.ag_phone_number, self.re_phone_number = setup_tel_config(
            self.pri_ad, self.sec_ad, self.sim_conf_file)

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

    def initiate_call_from_hf_with_iperf(self):
        """Wrapper function to start iperf and initiate call"""
        self.run_iperf_and_get_result()
        if not initiate_and_disconnect_call_from_bluez(
                self.re_phone_number, self.iperf["duration"], self.bus):
            return False
        return self.teardown_result()

    def initiate_call_avrcp_controls_with_iperf(self):
        """Wrapper function to start iperf, initiate call from hf and answer
        call from secondary device.
        """
        initiate_call(self.log, self.sec_ad, self.ag_phone_number)
        time.sleep(CALL_WAIT_TIME)
        self.run_iperf_and_get_result()
        answer_call(self.iperf["duration"], self.bus, avrcp_flag=True)
        return self.teardown_result()

    def test_performance_hfp_call_slave_role_tcp_ul(self):
        """Starts TCP-uplink traffic with hfp connection.

        This test is to start TCP-uplink traffic between host machine and
        android device and test the performance hfp connection
        call and wifi throughput when device is in slave role.

        Steps:.
        1. Start TCP-uplink traffic.
        2. Initiate call from HF and disconnect call from primary device.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_053
        """
        if not self.initiate_call_from_hf_with_iperf():
            return False
        return True

    def test_performance_hfp_call_slave_role_tcp_dl(self):
        """Starts TCP-downlink traffic with hfp connection.

        This test is to start TCP-downlink traffic between host machine and
        android device and test the performance hfp connection
        call and wifi throughput when device is in slave role.

        Steps:.
        1. Start TCP-downlink traffic.
        2. Initiate call from HF and disconnect call from primary device.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_054
        """
        if not self.initiate_call_from_hf_with_iperf():
            return False
        return True

    def test_performance_hfp_call_slave_role_udp_ul(self):
        """Starts UDP-uplink traffic with hfp connection.

        This test is to start UDP-uplink traffic between host machine and
        android device and test the performance hfp connection
        call and wifi throughput when device is in slave role.

        Steps:.
        1. Start UDP-uplink traffic.
        2. Initiate call from HF and disconnect call from primary device.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_055
        """
        if not self.initiate_call_from_hf_with_iperf():
            return False
        return True

    def test_performance_hfp_call_slave_role_udp_dl(self):
        """Starts UDP-downlink traffic with hfp connection.

        This test is to start UDP-downlink traffic between host machine and
        android device and test the performance hfp connection
        call and wifi throughput when device is in slave role.

        Steps:
        1. Start UDP-downlink traffic.
        2. Initiate call from HF and disconnect call from primary device.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_056
        """
        if not self.initiate_call_from_hf_with_iperf():
            return False
        return True

    def test_performance_hfp_call_avrcp_controls_slave_role_tcp_ul(self):
        """Starts TCP-uplink traffic with hfp connection and check volume.

        This test is to start TCP-uplink traffic between host machine and
        android device and test the performance hfp connection,
        call, volume change and wifi throughput when device is in slave role.

        Steps:.
        1. Start TCP-uplink traffic.
        2. Initiate call from HF and disconnect call from primary device.
        3. Change call volume when device is in call.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_057
        """
        if not self.initiate_call_avrcp_controls_with_iperf():
            return False
        return True

    def test_performance_hfp_call_avrcp_controls_slave_role_tcp_dl(self):
        """Starts TCP-downlink traffic with hfp connection and check volume.

        This test is to start TCP-downlink traffic between host machine and
        android device and test the performance hfp connection,
        call, volume change and wifi throughput when device is in slave role.

        Steps:.
        1. Start TCP-downlink traffic.
        2. Initiate call from HF and disconnect call from primary device.
        3. Change call volume when device is in call.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_058
        """
        if not self.initiate_call_avrcp_controls_with_iperf():
            return False
        return True

    def test_performance_hfp_call_avrcp_controls_slave_role_udp_ul(self):
        """Starts UDP-uplink traffic with hfp connection and check volume.

        This test is to start UDP-uplink traffic between host machine and
        android device and test the performance hfp connection,
        call, volume change and wifi throughput when device is in slave role.

        Steps:.
        1. Start UDP-uplink traffic.
        2. Initiate call from HF and disconnect call from primary device.
        3. Change call volume when device is in call.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_059
        """
        if not self.initiate_call_avrcp_controls_with_iperf():
            return False
        return True

    def test_performance_hfp_call_avrcp_controls_slave_role_udp_dl(self):
        """Starts UDP-downlink traffic with hfp connection and check volume.

        This test is to start UDP-downlink traffic between host machine and
        android device and test the performance hfp connection,
        call, volume change and wifi throughput when device is in slave role.

        Steps:.
        1. Start UDP-downlink traffic.
        2. Initiate call from HF and disconnect call from primary device.
        3. Change call volume when device is in call.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_060
        """
        if not self.initiate_call_avrcp_controls_with_iperf():
            return False
        return True
