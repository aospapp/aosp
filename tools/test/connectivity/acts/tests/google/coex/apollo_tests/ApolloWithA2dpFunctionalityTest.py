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
from acts.test_utils.coex.CoexBaseTest import CoexBaseTest
from acts.test_utils.coex.coex_test_utils import connect_disconnect_a2dp_headset
from acts.test_utils.coex.coex_test_utils import connect_ble
from acts.test_utils.coex.coex_test_utils import connect_disconnect_headset
from acts.test_utils.coex.coex_test_utils import multithread_func
from acts.test_utils.coex.coex_test_utils import music_play_and_check
from acts.test_utils.coex.coex_test_utils import pair_and_connect_headset
from acts.test_utils.coex.coex_test_utils import perform_classic_discovery
from acts.test_utils.coex.coex_test_utils import push_music_to_android_device
from acts.test_utils.coex.coex_test_utils import toggle_screen_state
from acts.test_utils.coex.coex_test_utils import start_fping

AVRCP_WAIT_TIME = 5


class ApolloWithA2dpFunctionalityTest(CoexBaseTest):

    def setup_class(self):
        super().setup_class()
        req_params = ["serial_device", "fping_params",
                      "audio_params", "iterations"]
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

    def perform_classic_discovery_with_iperf(self):
        """Wrapper function to start iperf traffic and classic discovery"""
        self.run_iperf_and_get_result()
        if not perform_classic_discovery(self.pri_ad,
                self.iperf['duration'], self.json_file, self.dev_list):
            return False
        return self.teardown_result()

    def connect_disconnect_a2dp_headset_with_iperf(self):
        """Wrapper function to start iperf traffic and connect/disconnect
        to headset for N iterations.
        """
        self.run_iperf_and_get_result()
        if not connect_disconnect_a2dp_headset(self.pri_ad,
                        self.headset_mac_address, self.iterations):
            return False
        return self.teardown_result()

    def music_streaming_bluetooth_discovery_with_iperf(self):
        """Wrapper function to start iperf traffic, music streaming and
        classic discovery.
        """
        tasks = [(self.audio.capture_audio, ()),
                 (music_play_and_check, (self.pri_ad,
                                         self.headset_mac_address,
                                         self.music_file_to_play,
                                         self.audio_params['music_play_time'])),
                 (perform_classic_discovery, (self.pri_ad,
                                              self.iperf['duration'],
                                              self.json_file,
                                              self.dev_list)),
                 (self.run_iperf_and_get_result, ())]
        if not multithread_func(self.log, tasks):
            return False
        return self.teardown_result()

    def music_streaming_with_iperf(self):
        """Wrapper function to start iperf traffic and music streaming."""
        tasks = [
            (self.audio.capture_audio, ()),
            (music_play_and_check, (self.pri_ad,
                                    self.headset_mac_address,
                                    self.music_file_to_play,
                                    self.audio_params[
                                        'music_play_time'])),
            (self.run_iperf_and_get_result, ())
        ]
        if not multithread_func(self.log, tasks):
            return False
        return self.teardown_result()

    def music_streaming_avrcp_controls_with_iperf(self):
        """Wrapper function to start iperf traffic, music streaming and avrcp
        controls.
        """
        tasks = [(self.audio.capture_audio, ()),
                 (music_play_and_check, (self.pri_ad,
                                         self.headset_mac_address,
                                         self.music_file_to_play,
                                         self.audio_params[
                                             'music_play_time'])),
                 (avrcp_actions, (self.pri_ad,
                                  self.buds_device)),
                 (self.run_iperf_and_get_result, ())]
        if not multithread_func(self.log, tasks):
            return False
        return self.teardown_result()

    def music_streaming_discovery_avrcp_controls_with_iperf(self):
        """Wrapper function to start iperf traffic, music streaming, bluetooth
        discovery and avrcp controls.
        """
        tasks = [(self.audio.capture_audio, ()),
                 (music_play_and_check, (self.pri_ad,
                                         self.headset_mac_address,
                                         self.music_file_to_play,
                                         self.audio_params[
                                             'music_play_time'])),
                 (perform_classic_discovery,(self.pri_ad,
                                             self.iperf['duration'],
                                             self.json_file,
                                             self.dev_list)),
                 (avrcp_actions, (self.pri_ad,
                                  self.buds_device)),
                 (self.run_iperf_and_get_result, ())]
        if not multithread_func(self.log, tasks):
            return False
        return self.teardown_result()

    def music_streaming_ble_connection_with_iperf(self):
        """Wrapper function to start iperf traffic, music streaming and ble
        connection.
        """
        tasks = [(self.audio.capture_audio, ()),
                 (music_play_and_check, (self.pri_ad,
                                         self.headset_mac_address,
                                         self.music_file_to_play,
                                         self.audio_params[
                                             'music_play_time'])),
                 (connect_ble, (self.pri_ad,
                                self.inquiry_devices[0])),
                 (self.run_iperf_and_get_result, ())]
        if not multithread_func(self.log, tasks):
            return False
        return self.teardown_result()

    def test_inquiry_after_headset_connection_with_tcp_ul(self):
        """Starts TCP-uplink traffic, start inquiry after bluetooth connection.

        This test is to start TCP-uplink traffic between host machine and
        android device and test functional behaviour of bluetooth discovery
        after connecting to headset.

        Steps:
        1. Run TCP-uplink traffic.
        2. Start bluetooth discovery when headset is connected.

        Returns:
            True if successful, False otherwise.
        """
        return self.perform_classic_discovery_with_iperf()

    def test_inquiry_after_headset_connection_with_tcp_dl(self):
        """Starts TCP-downlink traffic, start inquiry after bluetooth connection.

        This test is to start TCP-downlink traffic between host machine and
        android device and test functional behaviour of bluetooth discovery
        after connecting to headset.

        Steps:
        1. Run TCP-downlink traffic.
        2. Start bluetooth discovery when headset is connected.

        Returns:
            True if successful, False otherwise.
        """
        return self.perform_classic_discovery_with_iperf()

    def test_inquiry_after_headset_connection_with_udp_ul(self):
        """Starts UDP-uplink traffic, start inquiry after bluetooth connection.

        This test is to start UDP-uplink traffic between host machine and
        android device and test functional behaviour of bluetooth discovery
        after connecting to headset.

        Steps:
        1. Run UDP-uplink traffic.
        2. Start bluetooth discovery when headset is connected.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_011
        """
        return self.perform_classic_discovery_with_iperf()

    def test_inquiry_after_headset_connection_with_udp_dl(self):
        """Starts UDP-downlink traffic, start inquiry after bluetooth connection.

        This test is to start UDP-downlink traffic between host machine and
        android device and test functional behaviour of bluetooth discovery
        after connecting to headset.

        Steps:
        1. Run UDP-downlink traffic.
        2. Start bluetooth discovery when headset is connected.

        Returns:
            True if successful, False otherwise.
        """
        return self.perform_classic_discovery_with_iperf()

    def test_connect_disconnect_a2dp_headset_with_tcp_ul(self):
        """Starts TCP-uplink traffic and connect/disconnect a2dp headset.

        This test is to start TCP-uplink traffic between host machine and
        android device and test functional behaviour of connection and
        disconnection to a2dp headset.

        Steps:
        1. Run TCP-uplink traffic.
        2. Connect and disconnect A2DP headset.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.
        """
        return self.connect_disconnect_a2dp_headset_with_iperf()

    def test_connect_disconnect_a2dp_headset_with_tcp_dl(self):
        """Starts TCP-downlink traffic and connect/disconnect a2dp headset.

        This test is to start TCP-downlink traffic between host machine and
        android device and test functional behaviour of connection and
        disconnection to a2dp headset.

        Steps:
        1. Run TCP-downlink traffic.
        2. Connect and disconnect A2DP headset.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.
        """
        return self.connect_disconnect_a2dp_headset_with_iperf()

    def test_connect_disconnect_a2dp_headset_with_udp_ul(self):
        """Starts UDP-uplink traffic and connect/disconnect a2dp headset.

        This test is to start UDP-uplink traffic between host machine and
        android device and test functional behaviour of connection and
        disconnection to a2dp headset.

        Steps:
        1. Run UDP-uplink traffic.
        2. Connect and disconnect A2DP headset.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.
        """
        return self.connect_disconnect_a2dp_headset_with_iperf()

    def test_connect_disconnect_a2dp_headset_with_udp_dl(self):
        """Starts UDP-downlink traffic and connect/disconnect a2dp headset.

        This test is to start UDP-downlink traffic between host machine and
        android device and test functional behaviour of connection and
        disconnection to a2dp headset.

        Steps:
        1. Run UDP-downlink traffic.
        2. Connect and disconnect A2DP headset.
        3. Repeat step 2 for N iterations.

        Returns:
            True if successful, False otherwise.
        """
        return self.connect_disconnect_a2dp_headset_with_iperf()

    def test_a2dp_streaming_bluetooth_discovery_with_tcp_ul(self):
        """Starts TCP-uplink traffic, with music streaming to a2dp headset and
        bluetooth discovery.

        This test is to start TCP-uplink traffic between host machine and
        android device and test functional behaviour of a2dp music streaming
        and bluetooth discovery.

        Steps:
        1. Run TCP-uplink traffic.
        2. Start media streaming to a2dp headset.
        3. Start bluetooth discovery on android device.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_bluetooth_discovery_with_iperf()

    def test_a2dp_streaming_bluetooth_discovery_with_tcp_dl(self):
        """Starts TCP-downlink traffic, with music streaming to a2dp headset
        and bluetooth discovery.

        This test is to start TCP-downlink traffic between host machine and
        android device and test functional behaviour of a2dp music streaming
        and bluetooth discovery.

        Steps:
        1. Run TCP-downlink traffic.
        2. Start media streaming to a2dp headset.
        3. Start bluetooth discovery on android device.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_bluetooth_discovery_with_iperf()

    def test_a2dp_streaming_bluetooth_discovery_with_udp_ul(self):
        """Starts UDP-uplink traffic, with music streaming to a2dp headset and
        bluetooth discovery.

        This test is to start UDP-uplink traffic between host machine and
        android device and test functional behaviour of a2dp music streaming
        and bluetooth discovery.

        Steps:
        1. Run UDP-uplink traffic.
        2. Start media streaming to a2dp headset.
        3. Start bluetooth discovery on android device.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_bluetooth_discovery_with_iperf()

    def test_a2dp_streaming_bluetooth_discovery_with_udp_dl(self):
        """Starts UDP-downlink traffic, with music streaming to a2dp headset
        and bluetooth discovery.

        This test is to start UDP-downlink traffic between host machine and
        android device and test functional behaviour of a2dp music streaming
        and bluetooth discovery.

        Steps:
        1. Run UDP-downlink traffic.
        2. Start media streaming to a2dp headset.
        3. Start bluetooth discovery on android device.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_bluetooth_discovery_with_iperf()

    def test_a2dp_streaming_with_tcp_ul(self):
        """Starts TCP-uplink traffic with music streaming to a2dp headset.

        This test is to start TCP-uplink traffic between host machine and
        android device and test the functional behaviour of a2dp music
        streaming.

        Steps:
        1. Run TCP-uplink traffic.
        2. Start media streaming to a2dp headset.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_with_iperf()

    def test_a2dp_streaming_with_tcp_dl(self):
        """Starts TCP-downlink traffic with music streaming to a2dp headset.

        This test is to start TCP-downlink traffic between host machine and
        android device and test the functional behaviour of a2dp music
        streaming.

        Steps:
        1. Run TCP-downlink traffic.
        2. Start media streaming to a2dp headset.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_with_iperf()

    def test_a2dp_streaming_with_udp_ul(self):
        """Starts UDP-uplink traffic with music streaming to a2dp headset.

        This test is to start UDP-uplink traffic between host machine and
        android device and test the functional behaviour of a2dp music
        streaming.

        Steps:
        1. Run UDP-uplink traffic.
        2. Start media streaming to a2dp headset.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_with_iperf()

    def test_a2dp_streaming_with_udp_dl(self):
        """Starts UDP-downlink traffic with music streaming to a2dp headset.

        This test is to start UDP-downlink traffic between host machine and
        android device and test the functional behaviour of a2dp music
        streaming.

        Steps:
        1. Run UDP-downlink traffic.
        2. Start media streaming to a2dp headset.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_with_iperf()

    def test_a2dp_streaming_avrcp_controls_with_tcp_ul(self):
        """Starts TCP-uplink traffic with music streaming and avrcp controls.

        This test is to start TCP-uplink traffic between host machine and
        android device and test the functional behaviour of a2dp music
        streaming and avrcp controls.

        1. Run TCP-uplink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_avrcp_controls_with_iperf()

    def test_a2dp_streaming_avrcp_controls_with_tcp_dl(self):
        """Starts TCP-downlink traffic with music streaming and avrcp controls.

        This test is to start TCP-downlink traffic between host machine and
        android device and test the functional behaviour of a2dp music
        streaming and avrcp controls.

        1. Run TCP-downlink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_avrcp_controls_with_iperf()

    def test_a2dp_streaming_avrcp_controls_with_udp_ul(self):
        """Starts UDP-uplink traffic with music streaming and avrcp controls.

        This test is to start UDP-uplink traffic between host machine and
        android device and test the functional behaviour of a2dp music
        streaming and avrcp controls.

        1. Run UDP-uplink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_avrcp_controls_with_iperf()

    def test_a2dp_streaming_avrcp_controls_with_udp_dl(self):
        """Starts UDP-downlink traffic with music streaming and avrcp controls.

        This test is to start UDP-downlink traffic between host machine and
        android device and test the functional behaviour of a2dp music
        streaming and avrcp controls.

        1. Run UDP-downlink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_avrcp_controls_with_iperf()

    def test_a2dp_streaming_avrcp_controls_bluetooth_discovery_tcp_ul(self):
        """Starts TCP-uplink traffic with music streaming, avrcp controls and
        bluetooth discovery.

        This test is to start TCP-uplink traffic between host machine and
        android device and test the functional behaviour of a2dp music
        streaming, avrcp controls and bluetooth discovery.

        1. Run TCP-uplink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.
        4. Start bluetooth discovery.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_discovery_avrcp_controls_with_iperf()

    def test_a2dp_streaming_avrcp_controls_bluetooth_discovery_tcp_dl(self):
        """Starts TCP-downlink traffic with music streaming, avrcp controls and
        bluetooth discovery.

        This test is to start TCP-downlink traffic between host machine and
        android device and test the functional behaviour of a2dp music
        streaming, avrcp controls and bluetooth discovery.

        1. Run TCP-downlink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.
        4. Start bluetooth discovery.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_discovery_avrcp_controls_with_iperf()

    def test_a2dp_streaming_avrcp_controls_bluetooth_discovery_udp_ul(self):
        """Starts UDP-uplink traffic with music streaming, avrcp controls and
        bluetooth discovery.

        This test is to start UDP-uplink traffic between host machine and
        android device and test the functional behaviour of a2dp music
        streaming, avrcp controls and bluetooth discovery.

        1. Run UDP-uplink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.
        4. Start bluetooth discovery.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_discovery_avrcp_controls_with_iperf()

    def test_a2dp_streaming_avrcp_controls_bluetooth_discovery_udp_dl(self):
        """Starts UDP-downlink traffic with music streaming, avrcp controls and
        bluetooth discovery.

        This test is to start UDP-downlink traffic between host machine and
        android device and test the functional behaviour of a2dp music
        streaming, avrcp controls and bluetooth discovery.

        1. Run UDP-downlink traffic.
        2. Start media streaming to a2dp headset.
        3. Check all avrcp related controls.
        4. Start bluetooth discovery.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_discovery_avrcp_controls_with_iperf()

    def test_connect_disconnect_headset_with_fping(self):
        """Starts fping, along with connection and disconnection of headset.

        This test is to start fping between host machine and android device
        with connection and disconnection of paired headset.

        Steps:
        1. Start fping.
        2. Enable bluetooth
        3. Connect bluetooth headset.
        4. Disconnect bluetooth headset.

        Returns:
            True if successful, False otherwise.
        """
        tasks = [(start_fping, (self.pri_ad,
                                self.iperf['duration'],
                                self.fping_params)),
                 (connect_disconnect_headset, (self.pri_ad,
                                               self.headset_mac_address))]
        return multithread_func(self.log, tasks)

    def test_a2dp_streaming_with_fping(self):
        """Starts fping along with a2dp streaming.

        This test is to start fping between host machine and android device
        and test the functional behaviour of music streaming to a2dp headset.

        Steps:
        1. Start fping.
        1. Start media play on android device and check for music streaming.

        Returns:
            True if successful, False otherwise.
        """
        tasks = [(self.audio.capture_audio, ()),
                 (start_fping, (self.pri_ad,
                                self.iperf['duration'],
                                self.fping_params)),
                 (music_play_and_check, (self.pri_ad,
                                         self.headset_mac_address,
                                         self.music_file_to_play,
                                         self.iperf['duration']))]
        return multithread_func(self.log, tasks)

    def test_connect_disconnect_headset_toggle_screen_state_with_fping(self):
        """Starts fping along with connection and disconnection of the headset.

        This test is to start fping between host machine and android device
        and test the functional behaviour of connection and disconnection of
        the paired headset when screen is off and on.

        Steps:
        1. Start fping.
        2. Connect bluetooth headset.
        4. Disconnect bluetooth headset.
        5. Screen on/off.

        Returns:
            True if successful, False otherwise.
        """
        tasks = [(start_fping, (self.pri_ad,
                                self.iperf['duration'],
                                self.fping_params)),
                 (connect_disconnect_headset, (self.pri_ad,
                                               self.headset_mac_address)),
                 (toggle_screen_state, (self.pri_ad,
                                        self.iperf['duration']))]
        return multithread_func(self.log, tasks)

    def test_a2dp_streaming_toggle_screen_state_with_fping(self):
        """Starts fping along with a2dp streaming.

        This test is to start fping with traffic between host machine and
        android device and test the functional behaviour of a2dp streaming when
        screen turned on or off.

        Steps:
        1. Start fping.
        2. Start media play on android device and check for music streaming.
        3. Start screen on/off of android device multiple times.

        Returns:
            True if successful, False otherwise.
        """
        tasks = [(self.audio.capture_audio, ()),
                 (start_fping, (self.pri_ad,
                                self.iperf['duration'],
                                self.fping_params)),
                 (music_play_and_check, (self.pri_ad,
                                         self.headset_mac_address,
                                         self.music_file_to_play,
                                         self.iperf['duration'])),
                 (toggle_screen_state, (self.pri_ad,
                                        self.iperf['duration']))]
        return multithread_func(self.log, tasks)

    def test_a2dp_streaming_ble_connection_with_tcp_ul(self):
        """Starts TCP-uplink traffic with a2dp streaming and ble connection.

        This test is to start TCP-uplink traffic between host machine and
        android device and test the functional behaviour of  ble connection
        and a2dp streaming.

        Steps:
        1. Start TCP-uplink traffic.
        2. Start media play on android device and check for music streaming.
        3. Initiate ble connection to android device.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_ble_connection_with_iperf()

    def test_a2dp_streaming_ble_connection_with_tcp_dl(self):
        """Starts TCP-downlink traffic with a2dp streaming and ble connection.

        This test is to start TCP-downlink traffic between host machine and
        android device and test the functional behaviour of  ble connection
        and a2dp streaming.

        Steps:
        1. Start TCP-downlink traffic.
        2. Start media play on android device and check for music streaming.
        3. Initiate ble connection to android device.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_ble_connection_with_iperf()
