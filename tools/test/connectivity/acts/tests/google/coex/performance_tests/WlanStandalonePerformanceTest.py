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

from acts.test_utils.coex.CoexPerformanceBaseTest import CoexPerformanceBaseTest
from acts.test_utils.bt.bt_test_utils import disable_bluetooth


class WlanStandalonePerformanceTest(CoexPerformanceBaseTest):

    def __init__(self, controllers):
        super().__init__(controllers)

    def setup_class(self):
        super().setup_class()
        req_params = ["iterations"]
        self.unpack_userparams(req_params)

    def setup_test(self):
        super().setup_test()
        if not disable_bluetooth(self.pri_ad.droid):
            self.log.info("Failed to disable bluetooth")
            return False

    def test_performance_wlan_standalone_tcp_ul(self):
        """Check throughout for wlan standalone.

        This test is to start TCP-uplink traffic between host machine and
        android device for wlan-standalone.

        Steps:
        1. Start TCP-uplink traffic.

        Test Id: Bt_CoEx_kpi_001
        """
        self.set_attenuation_and_run_iperf()
        return self.teardown_result()

    def test_performance_wlan_standalone_tcp_dl(self):
        """Check throughout for wlan standalone.

        This test is to start TCP-downlink traffic between host machine and
        android device for wlan-standalone.

        Steps:
        1. Start TCP-downlink traffic.

        Test Id: Bt_CoEx_kpi_002
        """
        self.set_attenuation_and_run_iperf()
        return self.teardown_result()

    def test_performance_wlan_standalone_udp_ul(self):
        """Check throughout for wlan standalone.

        This test is to start UDP-uplink traffic between host machine and
        android device for wlan-standalone.

        Steps:
        1. Start UDP-uplink traffic.

        Test Id: Bt_CoEx_kpi_003
        """
        self.set_attenuation_and_run_iperf()
        return self.teardown_result()

    def test_performance_wlan_standalone_udp_dl(self):
        """Check throughout for wlan standalone.

        This test is to start UDP-downlink traffic between host machine and
        android device for wlan-standalone.

        Steps:
        1. Start UDP-downlink traffic.

        Test Id: Bt_CoEx_kpi_004
        """
        self.set_attenuation_and_run_iperf()
        return self.teardown_result()
