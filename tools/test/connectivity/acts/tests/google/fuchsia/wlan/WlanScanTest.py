#!/usr/bin/env python3.4
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
#
"""
This test exercises basic scanning functionality to confirm expected behavior
related to wlan scanning
"""

from datetime import datetime

import pprint
import time

import acts.base_test
import acts.test_utils.wifi.wifi_test_utils as wutils

from acts import signals
from acts.test_utils.wifi.WifiBaseTest import WifiBaseTest

class WlanScanTest(WifiBaseTest):
    """wlan scan test class.

    Test Bed Requirement:
    * One Fuchsia device
    * Several Wi-Fi networks visible to the device, including an open Wi-Fi
      network.
    """
    def __init__(self, controllers):
      WifiBaseTest.__init__(self, controllers)

    def setup_class(self):
      self.dut = self.fuchsia_devices[0]

    def teardown_test(self):
      self.dut.wlan_lib.wlanDisconnect()

    """Helper Functions"""

    def check_connect_response(self, connection_response):
      if connection_response.get("error") is None:
        # the command did not get an error response - go ahead and check the
        # result
        connection_result = connection_response.get("result")
        if connection_result:
          self.log.info("connection to network successful")
        else:
          # ideally, we would have the actual error...  but logging here to
          # cover that error case
          raise signals.TestFailure("Connect call failed, aborting test")
      else:
        # the response indicates an error - log and raise failure
        raise signals.TestFailure("Aborting test - Connect call failed with error: %s"
                                  %connection_response.get("error"))


    """Tests"""
    def test_basic_scan_request(self):
      """Verify a general scan trigger returns at least one result"""
      start_time = datetime.now()

      scan_response = self.dut.wlan_lib.wlanStartScan()

      # first check if we received an error
      if scan_response.get("error") is None:
        # the scan command did not get an error response - go ahead and check
        # for scan results
        scan_results = scan_response["result"]
      else:
        # the response indicates an error - log and raise failure
        raise signals.TestFailure("Aborting test - scan failed with error: %s"
                                  %scan_response.get("error"))

      self.log.info("scan contained %d results", len(scan_results))

      total_time_ms = (datetime.now() - start_time).total_seconds() * 1000
      self.log.info("scan time: %d ms", total_time_ms)

      if len(scan_results) > 0:
          raise signals.TestPass(details="", extras={"Scan time":"%d" %total_time_ms})
      else:
          raise signals.TestFailure("Scan failed or did not find any networks")

    def test_scan_while_connected_open_network(self):
      """Verify a general scan trigger returns at least one result when wifi is connected"""

      "first check if we can read params"
      target_ssid = self.user_params.get("wlan_open_network_ssid").get("SSID")
      self.log.info("got the ssid! %s", target_ssid)

      connection_response = self.dut.wlan_lib.wlanConnectToNetwork(target_ssid)
      self.check_connect_response(connection_response)

      self.test_basic_scan_request()

    def test_scan_while_connected_wpa2_network(self):
      """Verify a general scan trigger returns at least one result when wifi is connected"""

      "first check if we can read params"
      target_ssid = self.user_params.get("wlan_wpa2_network_ssid").get("SSID")
      target_pwd = self.user_params.get("wlan_wpa2_network_pwd").get("password")
      self.log.info("got the ssid! %s", target_ssid)

      connection_response = self.dut.wlan_lib.wlanConnectToNetwork(target_ssid, target_pwd)
      self.check_connect_response(connection_response)

      self.test_basic_scan_request()

