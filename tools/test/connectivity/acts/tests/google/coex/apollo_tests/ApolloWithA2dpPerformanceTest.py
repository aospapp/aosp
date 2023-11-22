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

from acts.controllers.buds_lib.apollo_utils import avrcp_actions
from acts.controllers.buds_lib.apollo_utils import get_serial_object
from acts.test_utils.bt import BtEnum
from acts.test_utils.bt.bt_test_utils import clear_bonded_devices
from acts.test_utils.coex.audio_test_utils import SshAudioCapture
from acts.test_utils.coex.CoexPerformanceBaseTest import CoexPerformanceBaseTest
from acts.test_utils.coex.coex_test_utils import music_play_and_check
from acts.test_utils.coex.coex_test_utils import pair_and_connect_headset
from acts.test_utils.coex.coex_test_utils import perform_classic_discovery
from acts.test_utils.coex.coex_test_utils import push_music_to_android_device

AVRCP_WAIT_TIME = 5


class ApolloWithA2dpPerformanceTest(CoexPerformanceBaseTest):

    def setup_class(self):
        super().setup_class()
        req_params = ["serial_device", "audio_params"]
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
            self.audio.terminate_and_store_audio_results()
        clear_bonded_devices(self.pri_ad)
        super().teardown_test()

    def teardown_class(self):
        super().teardown_class()

    def initiate_music_streaming_to_headset_with_iperf(self):
        """Initiate music streaming to headset and start iperf traffic."""
        tasks = [(self.audio.capture_audio, ()),
                 (music_play_and_check, (self.pri_ad,
                                         self.headset_mac_address,
                                         self.music_file_to_play,
                                         self.audio_params[
                                             'music_play_time'])),
                 (self.run_iperf_and_get_result, ())]
        return self.set_attenuation_and_run_iperf(tasks)

    def perform_discovery_with_iperf(self):
        """Starts iperf traffic based on test and perform bluetooth classic
        discovery.
        """
        tasks = [(self.run_iperf_and_get_result, ()),
                 (perform_classic_discovery, (self.pri_ad,
                                              self.iperf['duration'],
                                              self.json_file,
                                              self.dev_list))]
        return self.set_attenuation_and_run_iperf(tasks)

    def music_streaming_and_avrcp_controls_with_iperf(self):
        """Starts iperf traffic based on test and initiate music streaming and
        check for avrcp controls.
        """
        tasks = [(self.audio.capture_audio, ()),
                 (music_play_and_check, (self.pri_ad,
                                         self.headset_mac_address,
                                         self.music_file_to_play,
                                         self.iperf['duration'])),
                 (self.run_iperf_and_get_result, ()),
                 (avrcp_actions, (self.pri_ad,
                                  self.buds_device))]
        return self.set_attenuation_and_run_iperf(tasks)

    def test_performance_a2dp_streaming_tcp_ul(self):
        """Performance test to check throughput when streaming music.
        This test is to start TCP-uplink traffic between host machine and
        android device and test the performance when music streamed to a2dp
        headset.

        Steps:
        1. Start TCP-uplink traffic.
        2. Start music streaming to a2dp headset.

        Returns:
            True if successful, False otherwise.
        """
        return self.initiate_music_streaming_to_headset_with_iperf()

    def test_performance_a2dp_streaming_tcp_dl(self):
        """Performance test to check throughput when streaming music.
        This test is to start TCP-downlink traffic between host machine and
        android device and test the performance when music streamed to a2dp
        headset.

        Steps:
        1. Start TCP-downlink traffic.
        2. Start music streaming to a2dp headset.

        Returns:
            True if successful, False otherwise.
        """
        return self.initiate_music_streaming_to_headset_with_iperf()

    def test_performance_a2dp_streaming_udp_ul(self):
        """Performance test to check throughput when streaming music.
        This test is to start UDP-uplink traffic between host machine and
        android device and test the performance when music streamed to a2dp
        headset.

        Steps:
        1. Start UDP-uplink traffic.
        2. Start music streaming to a2dp headset.

        Returns:
            True if successful, False otherwise.
        """
        return self.initiate_music_streaming_to_headset_with_iperf()

    def test_performance_a2dp_streaming_udp_dl(self):
        """Performance test to check throughput when streaming music.
        This test is to start UDP-downlink traffic between host machine and
        android device and test the performance when music streamed to a2dp
        headset.

        Steps:
        1. Start UDP-downlink traffic.
        2. Start music streaming to a2dp headset.

        Returns:
            True if successful, False otherwise.
        """
        return self.initiate_music_streaming_to_headset_with_iperf()

    def test_performance_a2dp_streaming_avrcp_controls_with_tcp_ul(self):
        """Performance test to check throughput when music streaming.
        This test is to start TCP-uplink traffic between host machine and
        android device and test the wlan throughput when perfroming a2dp music
        streaming and avrcp controls.

        Steps:
        1. Start TCP-uplink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_and_avrcp_controls_with_iperf()

    def test_performance_a2dp_streaming_avrcp_controls_with_tcp_dl(self):
        """Performance test to check throughput when music streaming.
        This test is to start TCP-downlink traffic between host machine and
        android device and test the wlan throughput when perfroming a2dp music
        streaming and avrcp controls.

        Steps:
        1. Start TCP-downlink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_and_avrcp_controls_with_iperf()

    def test_performance_a2dp_streaming_avrcp_controls_with_udp_ul(self):
        """Performance test to check throughput when music streaming.
        This test is to start UDP-uplink traffic between host machine and
        android device and test the wlan throughput when perfroming a2dp music
        streaming and avrcp controls.

        Steps:
        1. Start UDP-uplink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_and_avrcp_controls_with_iperf()

    def test_performance_a2dp_streaming_avrcp_controls_with_udp_dl(self):
        """Performance test to check throughput when music streaming.
        This test is to start UDP-uplink traffic between host machine and
        android device and test the wlan throughput when perfroming a2dp music
        streaming and avrcp controls.

        Steps:
        1. Start UDP-downlink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_and_avrcp_controls_with_iperf()

    def test_performance_inquiry_after_headset_connection_with_tcp_ul(self):
        """Performance test to check throughput when bluetooth discovery.

        This test is to start TCP-uplink traffic between host machine and
        android device and test the performance when bluetooth discovery is
        performed after connecting to headset.

        Steps:
        1. Run TCP-uplink traffic.
        2. Start bluetooth discovery when headset is connected.

        Returns:
            True if successful, False otherwise.
        """
        return self.perform_discovery_with_iperf()

    def test_performance_inquiry_after_headset_connection_with_tcp_dl(self):
        """Performance test to check throughput when bluetooth discovery.

        This test is to start TCP-downlink traffic between host machine and
        android device and test the performance when bluetooth discovery is
        performed after connecting to headset.

        Steps:
        1. Run TCP-downlink traffic.
        2. Start bluetooth discovery when headset is connected.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_030
        """
        return self.perform_discovery_with_iperf()

    def test_performance_inquiry_after_headset_connection_with_udp_ul(self):
        """Performance test to check throughput when bluetooth discovery.

        This test is to start UDP-uplink traffic between host machine and
        android device and test the performance when bluetooth discovery is
        performed after connecting to headset.

        Steps:
        1. Run UDP-uplink traffic.
        2. Start bluetooth discovery when headset is connected.

        Returns:
            True if successful, False otherwise.
        """
        return self.perform_discovery_with_iperf()

    def test_performance_inquiry_after_headset_connection_with_udp_dl(self):
        """Performance test to check throughput when bluetooth discovery.

        This test is to start UDP-downlink traffic between host machine and
        android device and test the performance when bluetooth discovery is
        performed after connecting to headset.

        Steps:
        1. Run UDP-downlink traffic.
        2. Start bluetooth discovery when headset is connected.

        Returns:
            True if successful, False otherwise.
        """
        return self.perform_discovery_with_iperf()
