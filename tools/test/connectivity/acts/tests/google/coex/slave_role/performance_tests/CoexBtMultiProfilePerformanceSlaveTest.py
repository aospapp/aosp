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

from acts.test_utils.bt.bt_test_utils import clear_bonded_devices
from acts.test_utils.coex.bluez_test_utils import connect_bluetooth_device
from acts.test_utils.coex.CoexBluezBaseTest import CoexBluezBaseTest
from acts.test_utils.coex.coex_constants import MULTIPROFILE_TEST
from acts.test_utils.coex.coex_constants import bluetooth_profiles
from acts.test_utils.coex.coex_test_utils import music_play_and_check_via_app
from acts.test_utils.coex.coex_test_utils import initiate_disconnect_call_dut
from acts.test_utils.coex.coex_test_utils import connect_wlan_profile
from acts.test_utils.coex.coex_test_utils import setup_tel_config


class CoexBtMultiProfilePerformanceSlaveTest(CoexBluezBaseTest):

    def __init__(self, controllers):
        super().__init__(controllers, MULTIPROFILE_TEST)

    def setup_class(self):
        super().setup_class()
        if not self.bluez_flag:
            return False
        req_params = ["sim_conf_file", "music_file"]
        self.unpack_userparams(req_params)
        self.ag_phone_number, self.re_phone_number = setup_tel_config(
            self.pri_ad, self.sec_ad, self.sim_conf_file)
        if hasattr(self, "music_file"):
            self.push_music_to_android_device(self.pri_ad)

    def setup_test(self):
        super().setup_test()
        if not self.device_interface or not self.paired:
            return False
        if not connect_bluetooth_device(self.device_interface, self.bus,
                bluetooth_profiles["A2DP_SRC"], bluetooth_profiles["HFP_AG"]):
            self.log.error("Connection Failed")
            return False

    def teardown_test(self):
        clear_bonded_devices(self.pri_ad)
        super().teardown_test()

    def teardown_class(self):
        super().teardown_class()

    def initiate_call_when_a2dp_streaming_on(self):
        """Initiates HFP call, then check for call is present or not.

        Returns:
            True if successful, False otherwise.
        """
        if not initiate_disconnect_call_dut(
                self.pri_ad, self.sec_ad, self.iperf["duration"],
                self.re_phone_number):
            return False
        return True

    def play_music_and_connect_wifi(self):
        """Perform A2DP music streaming and scan and connect to wifi
        network

        Returns:
            True if successful, False otherwise.
        """
        if not music_play_and_check_via_app(
                self.pri_ad, self.adapter_mac_address):
            self.log.error("Failed to stream music file")
            return False
        if not connect_wlan_profile(self.pri_ad, self.network):
            return False
        return True

    def initiate_call_when_a2dp_streaming_with_iperf(self):
        """Wrapper function to initiate call when a2dp streaming and starts
        iperf.
        """
        if not self.play_music_and_connect_wifi():
            return False
        self.run_iperf_and_get_result()
        if not self.initiate_call_when_a2dp_streaming_on():
            return False
        return self.teardown_result()

    def test_performance_a2dp_streaming_hfp_call_slave_tcp_ul(self):
        """Check performance when a2dp streaming and hfp call..

        This test is to check wifi performance when a2dp streaming and
        hfp call performed sequentially with TCP-uplink traffic. Android
        device is in slave role.

        Steps:
        1.Enable bluetooth.
        2.Start a2dp streaming.
        3.Run TCP-uplink traffic.
        4.Initiate hfp call.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Kpi_061
        """
        if not self.initiate_call_when_a2dp_streaming_with_iperf():
            return False
        return True

    def test_performance_a2dp_streaming_hfp_call_slave_tcp_dl(self):
        """Check performance when a2dp streaming and hfp call..

        This test is to check wifi performance when a2dp streaming and
        hfp call performed sequentially with TCP-downlink traffic. Android
        device is in slave role.

        Steps:
        1.Enable bluetooth.
        2.Start a2dp streaming.
        3.Run TCP-downlink traffic.
        4.Initiate hfp call.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Kpi_062
        """
        if not self.initiate_call_when_a2dp_streaming_with_iperf():
            return False
        return True

    def test_performance_a2dp_streaming_hfp_call_slave_udp_ul(self):
        """Check performance when a2dp streaming and hfp call..

        This test is to check wifi performance when a2dp streaming and
        hfp call performed sequentially with UDP-uplink traffic. Android
        device is in slave role.

        Steps:
        1.Enable bluetooth.
        2.Start a2dp streaming.
        3.Run UDP-uplink traffic.
        4.Initiate hfp call.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Kpi_063
        """
        if not self.initiate_call_when_a2dp_streaming_with_iperf():
            return False
        return True

    def test_performance_a2dp_streaming_hfp_call_slave_udp_dl(self):
        """Check performance when a2dp streaming and hfp call..

        This test is to check wifi performance when a2dp streaming and
        hfp call performed sequentially with UDP-downlink traffic. Android
        device is in slave role.

        Steps:
        1.Enable bluetooth.
        2.Start a2dp streaming.
        3.Run UDP-downlink traffic.
        4.Initiate hfp call.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Kpi_064
        """
        if not self.initiate_call_when_a2dp_streaming_with_iperf():
            return False
        return True
