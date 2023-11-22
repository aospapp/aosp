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

from acts.controllers.buds_lib.apollo_utils import get_serial_object
from acts.test_utils.bt import BtEnum
from acts.test_utils.coex.CoexPerformanceBaseTest import CoexPerformanceBaseTest
from acts.test_utils.coex.coex_test_utils import perform_classic_discovery
from acts.test_utils.coex.coex_test_utils import pair_and_connect_headset


class ApolloBasicPerformanceTest(CoexPerformanceBaseTest):
    """Test suite to check A2DP Functionality with Wlan.

        Test Setup:

            Two Android device.
            One apollo board.
    """
    def setup_class(self):
        super().setup_class()
        req_params = ["serial_device"]
        self.unpack_userparams(req_params)
        self.buds_device = get_serial_object(self.pri_ad, self.serial_device)
        self.headset_mac_address = self.buds_device.bluetooth_address

    def perform_classic_discovery_with_iperf(self):
        """Wrapper function to start iperf traffic and classic discovery.

        Returns:
            True if successful, False otherwise.
        """
        tasks = [(perform_classic_discovery, (self.pri_ad,
                                              self.iperf['duration'],
                                              self.json_file,
                                              self.dev_list)),
                 (self.run_iperf_and_get_result, ())]
        return self.set_attenuation_and_run_iperf(tasks)

    def connect_headset(self):
        """Connect to apollo headset.

        Returns:
            True if successful, False otherwise.
        """
        self.buds_device.send("ResetPair\n")
        self.buds_device.set_pairing_mode()
        if not pair_and_connect_headset(
                self.pri_ad, self.headset_mac_address,
                set([BtEnum.BluetoothProfile.A2DP.value])):
            self.log.error("Failed to pair and connect to headset")
            return False
        self.buds_device.set_stay_connected(1)

    def test_performance_with_bluetooth_discovery_tcp_ul(self):
        """Check throughput when bluetooth discovery is ongoing.

        This test is to start TCP-uplink traffic between host machine and
        android device and bluetooth discovery and checks throughput.

        Steps:
        1. Start TCP-uplink traffic and bluetooth discovery parallelly.

        Returns:
            True if successful, False otherwise.
        """
        return self.perform_classic_discovery_with_iperf()

    def test_performance_with_bluetooth_discovery_tcp_dl(self):
        """Check throughput when bluetooth discovery is ongoing.

        This test is to start TCP-downlink traffic between host machine and
        android device and bluetooth discovery and checks throughput.

        Steps:
        1. Start TCP-downlink traffic and bluetooth discovery parallelly.

        Returns:
            True if successful, False otherwise.
        """
        return self.perform_classic_discovery_with_iperf()

    def test_performance_with_bluetooth_discovery_udp_ul(self):
        """Check throughput when bluetooth discovery is ongoing.

        This test is to start UDP-uplink traffic between host machine and
        android device and bluetooth discovery and checks throughput.

        Steps:
        1. Start UDP-uplink traffic and bluetooth discovery parallelly.

        Returns:
            True if successful, False otherwise.
        """
        return self.perform_classic_discovery_with_iperf()

    def test_performance_with_bluetooth_discovery_udp_dl(self):
        """Check throughput when bluetooth discovery is ongoing.

        This test is to start UDP-downlink traffic between host machine and
        android device and bluetooth discovery and checks throughput.

        Steps:
        1. Start UDP-downlink traffic and bluetooth discovery parallelly.

        Returns:
            True if successful, False otherwise.
        """
        return self.perform_classic_discovery_with_iperf()

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
        self.connect_headset()
        return self.perform_classic_discovery_with_iperf()

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
        """
        self.connect_headset()
        return self.perform_classic_discovery_with_iperf()

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
        self.connect_headset()
        return self.perform_classic_discovery_with_iperf()

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
        self.connect_headset()
        return self.perform_classic_discovery_with_iperf()
