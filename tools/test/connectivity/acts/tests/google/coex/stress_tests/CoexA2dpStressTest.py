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
"""
Test suite to perform Stress test on A2DP with Wlan.

Test Setup:

One Android device.
One A2DP Headset connected to Relay.
"""
import time

from acts.test_utils.bt import BtEnum
from acts.test_utils.bt.bt_test_utils import clear_bonded_devices
from acts.test_utils.coex.audio_test_utils import SshAudioCapture
from acts.test_utils.coex.CoexBaseTest import CoexBaseTest
from acts.test_utils.coex.coex_test_utils import connect_dev_to_headset
from acts.test_utils.coex.coex_test_utils import disconnect_headset_from_dev
from acts.test_utils.coex.coex_test_utils import multithread_func
from acts.test_utils.coex.coex_test_utils import music_play_and_check
from acts.test_utils.coex.coex_test_utils import pair_and_connect_headset
from acts.test_utils.coex.coex_test_utils import push_music_to_android_device


class CoexA2dpStressTest(CoexBaseTest):

    def __init__(self, controllers):
        super().__init__(controllers)

    def setup_class(self):
        super().setup_class()
        req_params = ["iterations", "audio_params", "headset_mac_address"]
        self.unpack_userparams(req_params)
        if hasattr(self, "audio_params"):
            if self.audio_params["music_file"]:
                self.music_file_to_play = push_music_to_android_device(
                    self.pri_ad, self.audio_params)
                if not self.music_file_to_play:
                    self.log.error("Music file push failed.")
                    return False
        else:
            self.log.warning("No Music files pushed to play.")

    def setup_test(self):
        super().setup_test()
        if "a2dp_streaming" in self.current_test_name:
            self.audio = SshAudioCapture(self.audio_params, self.log_path)
        if hasattr(self, "RelayDevice"):
            self.audio_receiver.pairing_mode()
        time.sleep(5)  #Wait time until headset goes into pairing mode.
        if not pair_and_connect_headset(
                self.pri_ad, self.headset_mac_address,
                set([BtEnum.BluetoothProfile.A2DP.value])):
            self.log.error("Failed to pair and connect to headset")
            return False

    def teardown_test(self):
        clear_bonded_devices(self.pri_ad)
        if hasattr(self, "RelayDevice"):
            self.audio_receiver.clean_up()
        if "a2dp_streaming" in self.current_test_name:
            analysis_path = self.audio.audio_quality_analysis(self.log_path)
            with open(analysis_path) as f:
                self.result["audio_artifacts"] = f.readline()
            self.audio.terminate_and_store_audio_results()
        super().teardown_test()

    def connect_disconnect_headset(self):
        """Initiates connection to paired headset and disconnects headset.

        Returns:
            True if successful False otherwise.
        """
        for i in range(self.iterations):
            self.log.info("Headset connect/disconnect iteration={}".format(i))
            self.pri_ad.droid.bluetoothConnectBonded(
                self.headset_mac_address)
            time.sleep(2)  #Wait time until device gets connected.
            self.pri_ad.droid.bluetoothDisconnectConnected(
                self.headset_mac_address)
        return True

    def connect_disconnect_a2dp_headset(self):
        """Connect and disconnect a2dp profile on headset for multiple
         iterations.

        Steps:
        1.Connect a2dp profile on headset.
        2.Disconnect a2dp profile on headset.
        3.Repeat step 1 and 2 for N iterations.

        Returns:
            True if successful, False otherwise.
        """
        for i in range(self.iterations):
            if not connect_dev_to_headset(self.pri_ad,
                                          self.headset_mac_address,
                                          {BtEnum.BluetoothProfile.A2DP.value}):
                self.log.error("Failure to connect A2dp headset.")
                return False

            if not disconnect_headset_from_dev(
                    self.pri_ad, self.headset_mac_address,
                [BtEnum.BluetoothProfile.A2DP.value]):
                self.log.error("Could not disconnect {}".format(
                    self.headset_mac_address))
                return False
        return True

    def connect_disconnect_headset_with_iperf(self):
        """Wrapper function to start iperf traffic and connect/disconnect
        to headset for N iterations.
        """
        self.run_iperf_and_get_result()
        if not self.connect_disconnect_headset():
            return False
        return self.teardown_result()

    def connect_disconnect_a2dp_headset_with_iperf(self):
        """Wrapper function to start iperf traffic and connect/disconnect
        to a2dp headset for N iterations.
        """
        self.run_iperf_and_get_result()
        if not self.connect_disconnect_a2dp_headset():
            return False
        return self.teardown_result()

    def music_streaming_with_iperf(self):
        """Wrapper function to start iperf traffic and music streaming."""
        tasks = [(self.audio.capture_audio, ()),
                 (music_play_and_check,
                  (self.pri_ad, self.headset_mac_address,
                   self.music_file_to_play,
                   self.audio_params["music_play_time"])),
                 (self.run_iperf_and_get_result, ())]
        if not multithread_func(self.log, tasks):
            return False
        return self.teardown_result()

    def test_stress_connect_disconnect_headset_with_tcp_ul(self):
        """Stress test for connect/disconnect headset.

        This test is to start TCP-uplink traffic between host machine and
        android device and test the integrity of connection and disconnection
        to headset.

        Steps:
        1. Run TCP-uplink traffic.
        2. Connect and disconnect headset.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_013
        """
        if not self.connect_disconnect_headset_with_iperf():
            return False
        return True

    def test_stress_connect_disconnect_headset_with_tcp_dl(self):
        """Stress test for connect/disconnect headset.

        This test is to start TCP-downlink traffic between host machine and
        android device and test the integrity of connection and disconnection
        to headset.

        Steps:
        1. Run TCP-downlink traffic.
        2. Connect and disconnect headset.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_014
        """
        if not self.connect_disconnect_headset_with_iperf():
            return False
        return True

    def test_stress_connect_disconnect_headset_with_udp_ul(self):
        """Stress test for connect/disconnect headset.

        This test is to start UDP-uplink traffic between host machine and
        android device and test the integrity of connection and disconnection
        to headset.

        Steps:
        1. Run UDP-uplink traffic.
        2. Connect and disconnect headset.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_015
        """
        if not self.connect_disconnect_headset_with_iperf():
            return False
        return True

    def test_stress_connect_disconnect_headset_with_udp_dl(self):
        """Stress test for connect/disconnect headset.

        This test is to start UDP-downlink traffic between host machine and
        android device and test the integrity of connection and disconnection
        to headset.

        Steps:
        1. Run UDP-downlink traffic.
        2. Connect and disconnect headset.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_016
        """
        if not self.connect_disconnect_headset_with_iperf():
            return False
        return True

    def test_stress_a2dp_streaming_long_duration_with_tcp_ul(self):
        """Stress test to stream music to headset continuously for 12 hours.

        This test is to start TCP-uplink traffic between host machine and
        android device and test the integrity of audio streaming for 12 hours.

        Steps:
        1. Start TCP uplink traffic.
        2. Start music streaming to headset.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_017
        """
        if not self.music_streaming_with_iperf():
            return False
        return True

    def test_stress_a2dp_streaming_long_duration_with_tcp_dl(self):
        """Stress test to stream music to headset continuously for 12 hours.

        This test is to start TCP-downlink traffic between host machine and
        android device and test the integrity of audio streaming for 12 hours.

        Steps:
        1. Start TCP downlink traffic.
        2. Start music streaming to headset.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_018
        """
        if not self.music_streaming_with_iperf():
            return False
        return True

    def test_stress_a2dp_streaming_long_duration_with_udp_ul(self):
        """Stress test to stream music to headset continuously for 12 hours.

        This test is to start UDP-uplink traffic between host machine and
        android device and test the integrity of audio streaming for 12 hours.

        Steps:
        1. Start UDP uplink traffic.
        2. Start music streaming to headset.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_019
        """
        if not self.music_streaming_with_iperf():
            return False
        return True

    def test_stress_a2dp_streaming_long_duration_with_udp_dl(self):
        """Stress test to stream music to headset continuously for 12 hours.

        This test is to start UDP-downlink traffic between host machine and
        android device and test the integrity of audio streaming for 12 hours.

        Steps:
        1. Start UDP downlink traffic.
        2. Start music streaming to headset.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_020
        """
        if not self.music_streaming_with_iperf():
            return False
        return True

    def test_stress_connect_disconnect_a2dp_profile_with_tcp_ul(self):
        """Stress test for connect/disconnect a2dp headset.

        This test is to start TCP-uplink traffic between host machine and
        android device and test the integrity of connection and disconnection
        to headset with a2dp profile.

        Steps:
        1. Run TCP-uplink traffic.
        2. Connect and disconnect headset with a2dp profile.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_029
        """
        if not self.connect_disconnect_a2dp_headset_with_iperf():
            return False
        return True

    def test_stress_connect_disconnect_a2dp_profile_with_tcp_dl(self):
        """Stress test for connect/disconnect a2dp headset.

        This test is to start TCP-downlink traffic between host machine and
        android device and test the integrity of connection and disconnection
        to headset with a2dp profile.

        Steps:
        1. Run TCP-downlink traffic.
        2. Connect and disconnect headset with a2dp profile.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_030
        """
        if not self.connect_disconnect_a2dp_headset_with_iperf():
            return False
        return True

    def test_stress_connect_disconnect_a2dp_profile_with_udp_ul(self):
        """Stress test for connect/disconnect a2dp headset.

        This test is to start UDP-uplink traffic between host machine and
        android device and test the integrity of connection and disconnection
        to headset with a2dp profile.

        Steps:
        1. Run UDP-uplink traffic.
        2. Connect and disconnect headset with a2dp profile.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_031
        """
        if not self.connect_disconnect_a2dp_headset_with_iperf():
            return False
        return True

    def test_stress_connect_disconnect_a2dp_profile_with_udp_dl(self):
        """Stress test for connect/disconnect a2dp headset.

        This test is to start UDP-downlink traffic between host machine and
        android device and test the integrity of connection and disconnection
        to headset with a2dp profile.

        Steps:
        1. Run UDP-downlink traffic.
        2. Connect and disconnect headset with a2dp profile.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_032
        """
        if not self.connect_disconnect_a2dp_headset_with_iperf():
            return False
        return True

    def test_stress_connect_disconnect_headset_with_tcp_bidirectional(self):
        """Stress test for connect/disconnect headset.

        This test starts TCP-bidirectional traffic between host machine and
        android device and test the integrity of connection and disconnection
        to headset.

        Steps:
        1. Run TCP-bidirectional traffic.
        2. Connect and disconnect headset.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_057
        """
        if not self.connect_disconnect_headset_with_iperf():
            return False
        return True

    def test_stress_connect_disconnect_headset_with_udp_bidirectional(self):
        """Stress test for connect/disconnect headset.

        This test starts UDP-bidirectional traffic between host machin and
        android device and test the integrity of connection and disconnection
        to headset.

        Steps:
        1. Run UDP-bidirectional traffic.
        2. Connect and disconnect headset.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_058
        """
        if not self.connect_disconnect_headset_with_iperf():
            return False
        return True

    def test_stress_a2dp_streaming_long_duration_with_tcp_bidirectional(self):
        """Stress test to stream music to headset continuously for 12 hours.

        This test starts TCP-bidirectional traffic between host machin and
        android device and test the integrity of audio streaming for 12 hours.

        Steps:
        1. Start TCP bidirectional traffic.
        2. Start music streaming to headset.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_065
        """
        if not self.music_streaming_with_iperf():
            return False
        return True

    def test_stress_a2dp_streaming_long_duration_with_udp_bidirectional(self):
        """Stress test to stream music to headset continuously for 12 hours.

        This test starts UDP-bidirectional traffic between host machin and
        android device and test the integrity of audio streaming for 12 hours.

        Steps:
        1. Start UDP bidirectional traffic.
        2. Start music streaming to headset.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Stress_066
        """
        if not self.music_streaming_with_iperf():
            return False
        return True
