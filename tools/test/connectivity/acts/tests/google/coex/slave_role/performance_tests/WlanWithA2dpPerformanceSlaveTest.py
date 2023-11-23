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
from acts.test_utils.coex.bluez_test_utils import avrcp_actions
from acts.test_utils.coex.bluez_test_utils import connect_bluetooth_device
from acts.test_utils.coex.CoexBluezBaseTest import CoexBluezBaseTest
from acts.test_utils.coex.coex_constants import A2DP_TEST
from acts.test_utils.coex.coex_constants import bluetooth_profiles
from acts.test_utils.coex.coex_test_utils import music_play_and_check
from acts.test_utils.coex.coex_test_utils import multithread_func


class WlanWithA2dpPerformanceSlaveTest(CoexBluezBaseTest):

    def __init__(self, controllers):
        super().__init__(controllers, A2DP_TEST)

    def setup_class(self):
        super().setup_class()
        if not self.bluez_flag:
            return False
        req_params = ["iterations", "music_file"]
        self.unpack_userparams(req_params)
        if hasattr(self, "music_file"):
            self.push_music_to_android_device(self.pri_ad)

    def setup_test(self):
        super().setup_test()
        if not self.device_interface or not self.paired:
            return False
        if not connect_bluetooth_device(self.device_interface, self.bus,
                                        bluetooth_profiles["A2DP_SRC"]):
            self.log.error("Connection Failed")
            return False

    def teardown_test(self):
        clear_bonded_devices(self.pri_ad)
        super().teardown_test()

    def teardown_class(self):
        super().teardown_class()

    def music_streaming_with_iperf(self):
        """Wrapper function to start iperf traffic, music streaming
        to headset and associate with access point.
        """
        self.run_iperf_and_get_result()
        if not music_play_and_check(
                self.pri_ad, self.adapter_mac_address, self.music_file_to_play,
                self.iperf["duration"]):
            return False
        return self.teardown_result()

    def music_streaming_avrcp_controls_with_iperf(self):
        """Wrapper function to start iperf traffic, music streaming and avrcp
        controls.
        """
        self.run_iperf_and_get_result()
        tasks = [(music_play_and_check,
                  (self.pri_ad, self.adapter_mac_address,
                   self.music_file_to_play, self.iperf["duration"])),
                 (avrcp_actions, (self.device_id, self.bus))]
        if not multithread_func(self.log, tasks):
            return False
        return self.teardown_result()

    def test_performance_a2dp_streaming_slave_role_with_tcp_ul(self):
        """Starts TCP-uplink traffic with music streaming to a2dp headset.

        This test is to start TCP-uplink traffic between host machine and
        android device and test the performance of a2dp music
        streaming and wifi throughput when device acts as a slave.

        Steps:
        1. Run TCP-uplink traffic.
        2. Start media streaming to a2dp headset.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_045
        """
        if not self.music_streaming_with_iperf():
            return False
        return True

    def test_performance_a2dp_streaming_slave_role_with_tcp_dl(self):
        """Starts TCP-downlink traffic with music streaming to a2dp headset.

        This test is to start TCP-downlink traffic between host machine and
        android device and test the performance of a2dp music
        streaming and wifi throughput when device acts as a slave.

        Steps:
        1. Run TCP-downlink traffic.
        2. Start media streaming to a2dp headset.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_046
        """
        if not self.music_streaming_with_iperf():
            return False
        return True

    def test_performance_a2dp_streaming_slave_role_with_udp_ul(self):
        """Starts UDP-uplink traffic with music streaming to a2dp headset.

        This test is to start UDP-uplink traffic between host machine and
        android device and test the performance of a2dp music
        streaming and wifi throughput when device acts as a slave.

        Steps:
        1. Run UDP-uplink traffic.
        2. Start media streaming to a2dp headset.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_047
        """
        if not self.music_streaming_with_iperf():
            return False
        return True

    def test_performance_a2dp_streaming_slave_role_with_udp_dl(self):
        """Starts UDP-downlink traffic with music streaming to a2dp headset.

        This test is to start UDP-downlink traffic between host machine and
        android device and test the performance of a2dp music
        streaming and wifi throughput when device acts as a slave.

        Steps:
        1. Run UDP-downlink traffic.
        2. Start media streaming to a2dp headset.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_048
        """
        if not self.music_streaming_with_iperf():
            return False
        return True

    def test_performance_a2dp_streaming_avrcp_controls_slave_role_tcp_ul(self):
        """Starts TCP-uplink traffic with music streaming and avrcp controls.

        This test is to start TCP-uplink traffic between host machine and
        android device and test the performance of a2dp music streaming and
        avrcp controls when device is in slave role.

        1. Run TCP-uplink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_049
        """
        if not self.music_streaming_avrcp_controls_with_iperf():
            return False
        return True

    def test_performance_a2dp_streaming_avrcp_controls_slave_role_tcp_dl(self):
        """Starts TCP-downlink traffic with music streaming and avrcp controls.

        This test is to start TCP-downlink traffic between host machine and
        android device and test the performance of a2dp music streaming and
        avrcp controls when device is in slave role.

        1. Run TCP-downlink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_050
        """
        if not self.music_streaming_avrcp_controls_with_iperf():
            return False
        return True

    def test_performance_a2dp_streaming_avrcp_controls_slave_role_udp_ul(self):
        """Starts UDP-uplink traffic with music streaming and avrcp controls.

        This test is to start UDP-uplink traffic between host machine and
        android device and test the performance of a2dp music streaming and
        avrcp controls when device is in slave role.

        1. Run UDP-uplink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_051
        """
        if not self.music_streaming_avrcp_controls_with_iperf():
            return False
        return True

    def test_performance_a2dp_streaming_avrcp_controls_slave_role_udp_dl(self):
        """Starts UDP-downlink traffic with music streaming and avrcp controls.

        This test is to start UDP-downlink traffic between host machine and
        android device and test the performance of a2dp music streaming and
        avrcp controls when device is in slave role.

        1. Run UDP-downlink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_052
        """
        if not self.music_streaming_avrcp_controls_with_iperf():
            return False
        return True
