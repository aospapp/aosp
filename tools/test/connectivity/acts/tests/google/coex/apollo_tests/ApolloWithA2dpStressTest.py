# /usr/bin/env python3
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


from acts.controllers.buds_lib.apollo_utils import get_serial_object
from acts.test_utils.bt import BtEnum
from acts.test_utils.bt.bt_test_utils import clear_bonded_devices
from acts.test_utils.coex.audio_test_utils import SshAudioCapture
from acts.test_utils.coex.CoexBaseTest import CoexBaseTest
from acts.test_utils.coex.coex_test_utils import connect_disconnect_a2dp_headset
from acts.test_utils.coex.coex_test_utils import connect_disconnect_headset
from acts.test_utils.coex.coex_test_utils import multithread_func
from acts.test_utils.coex.coex_test_utils import music_play_and_check
from acts.test_utils.coex.coex_test_utils import pair_and_connect_headset
from acts.test_utils.coex.coex_test_utils import push_music_to_android_device


class ApolloWithA2dpStressTest(CoexBaseTest):

    def setup_class(self):
        super().setup_class()
        req_params = ["serial_device", "iterations", "audio_params"]
        self.unpack_userparams(req_params)
        self.buds_device = get_serial_object(self.pri_ad, self.serial_device)
        self.headset_mac_address = self.buds_device.bluetooth_address
        self.music_file_to_play = push_music_to_android_device(
            self.pri_ad, self.audio_params)

    def setup_test(self):
        super().setup_test()
        if "a2dp_streaming" in self.current_test_name:
            self.audio = SshAudioCapture(self.audio_params, self.log_path)
        self.buds_device.send("ResetPair\n")
        self.buds_device.set_pairing_mode()
        if not pair_and_connect_headset(
                self.pri_ad, self.headset_mac_address,
                set([BtEnum.BluetoothProfile.A2DP.value])):
            self.log.error("Failed to pair and connect to headset")
            return False
        self.buds_device.set_stay_connected(1)

    def teardown_test(self):
        if "a2dp_streaming" in self.current_test_name:
            analysis_path = self.audio.audio_quality_analysis(self.log_path)
            if analysis_path:
                with open(analysis_path) as f:
                    self.result['audio_artifacts'] = f.readline()
            self.audio.terminate_and_store_audio_results()
        clear_bonded_devices(self.pri_ad)
        super().teardown_test()

    def connect_disconnect_headset_with_iperf(self):
        """Wrapper function to start iperf traffic and connect/disconnect
        to headset for N iterations.
        """
        self.run_iperf_and_get_result()
        if not connect_disconnect_headset(self.pri_ad,
                        self.headset_mac_address, iterations=self.iterations):
            return False
        return self.teardown_result()

    def connect_disconnect_a2dp_headset_with_iperf(self):
        """Wrapper function to start iperf traffic and connect/disconnect
        to headset for N iterations.
        """
        self.run_iperf_and_get_result()
        if not connect_disconnect_a2dp_headset(self.pri_ad,
                        self.headset_mac_address, iterations=self.iterations):
            return False
        return self.teardown_result()

    def music_streaming_with_iperf(self):
        """Wrapper function to start iperf traffic and music streaming."""
        tasks = [(self.audio.capture_audio, ()),
                 (music_play_and_check, (self.pri_ad,
                                         self.headset_mac_address,
                                         self.music_file_to_play,
                                         self.audio_params[
                                             'music_play_time'])),
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
        """
        return self.connect_disconnect_headset_with_iperf()

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
        """
        return self.connect_disconnect_headset_with_iperf()

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
        """
        return self.connect_disconnect_headset_with_iperf()

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
        """
        return self.connect_disconnect_headset_with_iperf()

    def test_stress_a2dp_streaming_long_duration_with_tcp_ul(self):
        """Stress test to stream music to headset continuously for 12 hours.

        This test is to start TCP-uplink traffic between host machine and
        android device and test the integrity of audio streaming for 12 hours.

        Steps:
        1. Start TCP uplink traffic.
        2. Start music streaming to headset.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_with_iperf()

    def test_stress_a2dp_streaming_long_duration_with_tcp_dl(self):
        """Stress test to stream music to headset continuously for 12 hours.

        This test is to start TCP-downlink traffic between host machine and
        android device and test the integrity of audio streaming for 12 hours.

        Steps:
        1. Start TCP downlink traffic.
        2. Start music streaming to headset.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_with_iperf()

    def test_stress_a2dp_streaming_long_duration_with_udp_ul(self):
        """Stress test to stream music to headset continuously for 12 hours.

        This test is to start UDP-uplink traffic between host machine and
        android device and test the integrity of audio streaming for 12 hours.

        Steps:
        1. Start UDP uplink traffic.
        2. Start music streaming to headset.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_with_iperf()

    def test_stress_a2dp_streaming_long_duration_with_udp_dl(self):
        """Stress test to stream music to headset continuously for 12 hours.

        This test is to start UDP-downlink traffic between host machine and
        android device and test the integrity of audio streaming for 12 hours.

        Steps:
        1. Start UDP downlink traffic.
        2. Start music streaming to headset.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_with_iperf()

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
        """
        return self.connect_disconnect_a2dp_headset_with_iperf()

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
        """
        return self.connect_disconnect_a2dp_headset_with_iperf()

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
        """
        return self.connect_disconnect_a2dp_headset_with_iperf()

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
        """
        return self.connect_disconnect_a2dp_headset_with_iperf()

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
        """
        return self.connect_disconnect_headset_with_iperf()

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
        """
        return self.connect_disconnect_headset_with_iperf()

    def test_stress_a2dp_streaming_long_duration_with_tcp_bidirectional(self):
        """Stress test to stream music to headset continuously for 12 hours.

        This test starts TCP-bidirectional traffic between host machin and
        android device and test the integrity of audio streaming for 12 hours.

        Steps:
        1. Start TCP bidirectional traffic.
        2. Start music streaming to headset.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_with_iperf()

    def test_stress_a2dp_streaming_long_duration_with_udp_bidirectional(self):
        """Stress test to stream music to headset continuously for 12 hours.

        This test starts UDP-bidirectional traffic between host machin and
        android device and test the integrity of audio streaming for 12 hours.

        Steps:
        1. Start UDP bidirectional traffic.
        2. Start music streaming to headset.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_with_iperf()
