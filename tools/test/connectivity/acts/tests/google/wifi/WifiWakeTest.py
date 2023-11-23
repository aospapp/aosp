#!/usr/bin/env python3.4
#
#   Copyright 2018 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

import time
import queue

from acts import asserts
from acts.controllers.android_device import SL4A_APK_NAME
from acts.test_decorators import test_tracker_info
from acts.test_utils.wifi.WifiBaseTest import WifiBaseTest
import acts.test_utils.wifi.wifi_test_utils as wutils
import acts.utils

CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT = 5
LAST_DISCONNECT_TIMEOUT_MILLIS = 5000
LAST_DISCONNECT_TIMEOUT_SEC = LAST_DISCONNECT_TIMEOUT_MILLIS / 1000
PRESCAN_DELAY_SEC = 5


class WifiWakeTest(WifiBaseTest):
    """
    Tests Wifi Wake.

    Test Bed Requirements:
    * One Android Device
    * Two APs that can be turned on and off
    """

    def __init__(self, controllers):
        super().__init__(controllers)

    def setup_class(self):
        self.dut = self.android_devices[0]
        wutils.wifi_test_device_init(self.dut)
        # turn location back on
        acts.utils.set_location_service(self.dut, True)
        self.dut.droid.wifiScannerToggleAlwaysAvailable(True)

        self.unpack_userparams(req_param_names=[],
                               opt_param_names=["reference_networks"])

        if "AccessPoint" in self.user_params:
            self.legacy_configure_ap_and_start(mirror_ap=False, ap_count=2)

        # use 2G since Wifi Wake does not work if an AP is on a 5G DFS channel
        self.ap_a = self.reference_networks[0]["2g"]
        self.ap_b = self.reference_networks[1]["2g"]

        self.ap_a_atten = self.attenuators[0]
        self.ap_b_atten = self.attenuators[2]

    # TODO(b/119040540): this method of disabling/re-enabling Wifi on APs is
    # hacky, switch to using public methods when they are implemented
    def ap_a_off(self):
        ap_a_hostapd = self.access_points[0]._aps['wlan0'].hostapd
        if ap_a_hostapd.is_alive():
            ap_a_hostapd.stop()
            self.log.info('Turned AP A off')

    def ap_a_on(self):
        ap_a_hostapd = self.access_points[0]._aps['wlan0'].hostapd
        if not ap_a_hostapd.is_alive():
            ap_a_hostapd.start(ap_a_hostapd.config)
            self.log.info('Turned AP A on')

    def ap_b_off(self):
        ap_b_hostapd = self.access_points[1]._aps['wlan0'].hostapd
        if ap_b_hostapd.is_alive():
            ap_b_hostapd.stop()
            self.log.info('Turned AP B off')

    def ap_b_on(self):
        ap_b_hostapd = self.access_points[1]._aps['wlan0'].hostapd
        if not ap_b_hostapd.is_alive():
            ap_b_hostapd.start(ap_b_hostapd.config)
            self.log.info('Turned AP B on')

    def setup_test(self):
        self.dut.droid.wakeLockAcquireBright()
        self.dut.droid.wakeUpNow()
        self.ap_a_on()
        self.ap_b_on()
        self.ap_a_atten.set_atten(0)
        self.ap_b_atten.set_atten(0)
        wutils.reset_wifi(self.dut)
        wutils.wifi_toggle_state(self.dut, new_state=True)
        # clear events from event dispatcher
        self.dut.droid.wifiStartTrackingStateChange()
        self.dut.droid.wifiStopTrackingStateChange()
        self.dut.ed.clear_all_events()

    def teardown_test(self):
        self.dut.droid.wakeLockRelease()
        self.dut.droid.goToSleepNow()

    def on_fail(self, test_name, begin_time):
        self.dut.take_bug_report(test_name, begin_time)
        self.dut.cat_adb_log(test_name, begin_time)

    def do_location_scan(self, num_times=1):
        scan_settings = {
            "band": wutils.WifiEnums.WIFI_BAND_BOTH,
            "periodInMs": 0,
            "reportEvents": wutils.WifiEnums.REPORT_EVENT_AFTER_EACH_SCAN
        }

        wifi_chs = wutils.WifiChannelUS(self.dut.model)
        stime_channel = 47  # dwell time plus 2ms
        leeway = 10

        for i in range(num_times):
            self.log.info("Scan count: {}".format(i))
            data = wutils.start_wifi_single_scan(self.dut, scan_settings)
            idx = data["Index"]
            scan_rt = data["ScanElapsedRealtime"]
            self.log.debug(
                "Wifi single shot scan started index: %s at real time: %s", idx,
                scan_rt)
            # generating event wait time from scan setting plus leeway
            scan_time, scan_channels = wutils.get_scan_time_and_channels(
                wifi_chs, scan_settings, stime_channel)
            wait_time = int(scan_time / 1000) + leeway
            # track number of result received
            result_received = 0
            try:
                for _ in range(1, 3):
                    event_name = "{}{}onResults".format("WifiScannerScan", idx)
                    self.log.debug("Waiting for event: %s for time %s",
                                   event_name, wait_time)
                    event = self.dut.ed.pop_event(event_name, wait_time)
                    self.log.debug("Event received: %s", event)
                    result_received += 1
            except queue.Empty as error:
                asserts.assert_true(
                    result_received >= 1,
                    "Event did not triggered for single shot {}".format(error))
            finally:
                self.dut.droid.wifiScannerStopScan(idx)
                # For single shot number of result received and length of result
                # should be one
                asserts.assert_true(
                    result_received == 1,
                    "Test fail because received result {}".format(
                        result_received))

    @test_tracker_info(uuid="372b9b74-4241-46ce-8f18-e6a97d3a3452")
    def test_no_reconnect_manual_disable_wifi(self):
        """
        Tests that Wifi Wake does not reconnect to a network if the user turned
        off Wifi while connected to that network and the user has not moved
        (i.e. moved out of range of the AP then came back).
        """
        wutils.wifi_connect(self.dut, self.ap_a, num_of_tries=5)
        wutils.wifi_toggle_state(self.dut, new_state=False)
        time.sleep(PRESCAN_DELAY_SEC)
        self.do_location_scan(
            2 * CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT + 2)
        asserts.assert_false(
            self.dut.droid.wifiCheckState(),
            "Expect Wifi Wake to not enable Wifi, but Wifi was enabled.")

    @test_tracker_info(uuid="ec7a54a5-f293-43f5-a1dd-d41679aa1825")
    def test_reconnect_wifi_saved_network(self):
        """Tests that Wifi Wake re-enables Wifi for a saved network."""
        wutils.wifi_connect(self.dut, self.ap_a, num_of_tries=5)
        wutils.wifi_connect(self.dut, self.ap_b, num_of_tries=5)
        self.dut.ed.clear_all_events()
        self.ap_a_off()
        self.ap_b_off()
        wutils.wait_for_disconnect(self.dut)
        self.log.info("Wifi Disconnected")
        time.sleep(LAST_DISCONNECT_TIMEOUT_SEC * 1.2)
        wutils.wifi_toggle_state(self.dut, new_state=False)
        time.sleep(PRESCAN_DELAY_SEC)
        self.do_location_scan(CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT + 2)

        self.ap_a_on()
        self.do_location_scan()
        asserts.assert_true(
            self.dut.droid.wifiCheckState(),
            "Expect Wifi Wake to enable Wifi, but Wifi is disabled.")

    @test_tracker_info(uuid="")
    def test_reconnect_wifi_network_suggestion(self):
        """Tests that Wifi Wake re-enables Wifi for app provided suggestion."""
        self.dut.log.info("Adding network suggestions");
        asserts.assert_true(
            self.dut.droid.wifiAddNetworkSuggestions([self.ap_a]),
            "Failed to add suggestions")
        asserts.assert_true(
            self.dut.droid.wifiAddNetworkSuggestions([self.ap_b]),
            "Failed to add suggestions")
        # Enable suggestions by the app.
        self.dut.log.debug("Enabling suggestions from test");
        self.dut.adb.shell("cmd wifi network-suggestions-set-user-approved"
                           + " " + SL4A_APK_NAME + " yes")
        # Ensure network is seen in scan results & auto-connected to.
        self.do_location_scan(2)
        wutils.wait_for_connect(self.dut)
        self.dut.ed.clear_all_events()
        self.ap_a_off()
        self.ap_b_off()
        wutils.wait_for_disconnect(self.dut)
        self.log.info("Wifi Disconnected")
        time.sleep(LAST_DISCONNECT_TIMEOUT_SEC * 1.2)
        wutils.wifi_toggle_state(self.dut, new_state=False)
        time.sleep(PRESCAN_DELAY_SEC)
        self.do_location_scan(CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT + 2)

        self.ap_a_on()
        self.do_location_scan()
        asserts.assert_true(
            self.dut.droid.wifiCheckState(),
            "Expect Wifi Wake to enable Wifi, but Wifi is disabled.")

    @test_tracker_info(uuid="6c77ca9b-ff34-4bc7-895f-cc7340e0e645")
    def test_reconnect_wifi_move_back_in_range(self):
        """
        Tests that Wifi Wake re-enables Wifi if the device moves out of range of
        the AP then came back.
        """
        wutils.wifi_connect(self.dut, self.ap_a, num_of_tries=5)
        wutils.wifi_toggle_state(self.dut, new_state=False)
        time.sleep(PRESCAN_DELAY_SEC)
        # init Wakeup Lock with AP A
        self.do_location_scan(CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT + 2)
        self.ap_a_off()
        # evict AP A from Wakeup Lock
        self.do_location_scan(CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT + 2)
        self.ap_a_on()
        self.do_location_scan()
        asserts.assert_true(
            self.dut.droid.wifiCheckState(),
            "Expect Wifi Wake to enable Wifi, but Wifi is disabled.")

    @test_tracker_info(uuid="08e8284a-a523-48f3-b9ea-9c6bf27d711e")
    def test_no_reconnect_to_flaky_ap(self):
        """
        Tests that Wifi Wake does not reconnect to flaky networks.
        If a network sporadically connects and disconnects, and the user turns
        off Wifi even during the disconnected phase, Wifi Wake should not
        re-enable Wifi for that network.
        """
        wutils.wifi_connect(self.dut, self.ap_a, num_of_tries=5)
        self.ap_a_off()
        time.sleep(LAST_DISCONNECT_TIMEOUT_SEC * 0.4)
        wutils.wifi_toggle_state(self.dut, new_state=False)
        time.sleep(PRESCAN_DELAY_SEC)
        self.do_location_scan(CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT + 2)
        self.ap_a_on()
        self.do_location_scan(
            2 * CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT + 2)
        asserts.assert_false(
            self.dut.droid.wifiCheckState(),
            "Expect Wifi Wake to not enable Wifi, but Wifi was enabled.")

    @test_tracker_info(uuid="b990a8f7-e3a0-4774-89cf-2067ccd64903")
    def test_reconnect_wifi_disabled_after_disconnecting(self):
        """
        Tests that Wifi Wake reconnects to a network if Wifi was disabled long
        after disconnecting from a network.
        """
        wutils.wifi_connect(self.dut, self.ap_a, num_of_tries=5)
        self.dut.ed.clear_all_events()
        self.ap_a_off()
        wutils.wait_for_disconnect(self.dut)
        self.log.info("Wifi Disconnected")
        time.sleep(LAST_DISCONNECT_TIMEOUT_SEC * 1.2)
        wutils.wifi_toggle_state(self.dut, new_state=False)
        time.sleep(PRESCAN_DELAY_SEC)
        self.do_location_scan(CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT + 2)
        self.ap_a_on()
        self.do_location_scan()
        asserts.assert_true(
            self.dut.droid.wifiCheckState(),
            "Expect Wifi Wake to enable Wifi, but Wifi is disabled.")

    @test_tracker_info(uuid="bb217794-d3ee-4fb9-87ff-7a594d0223b0")
    def test_no_reconnect_if_exists_ap_in_wakeup_lock(self):
        """
        2 APs in Wakeup Lock, user moves out of range of one AP but stays in
        range of the other, should not reconnect when user moves back in range
        of both.
        """
        wutils.wifi_connect(self.dut, self.ap_a, num_of_tries=5)
        wutils.wifi_connect(self.dut, self.ap_b, num_of_tries=5)
        wutils.wifi_toggle_state(self.dut, new_state=False)
        time.sleep(PRESCAN_DELAY_SEC)
        self.do_location_scan(CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT + 2)
        self.ap_b_off()
        self.do_location_scan(CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT + 2)
        self.ap_b_on()
        self.do_location_scan(CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT + 2)
        asserts.assert_false(
            self.dut.droid.wifiCheckState(),
            "Expect Wifi Wake to not enable Wifi, but Wifi was enabled.")

    @test_tracker_info(uuid="567a0663-4ce0-488d-8fe2-db79a3ebf068")
    def test_reconnect_if_both_ap_evicted_from_wakeup_lock(self):
        """
        2 APs in Wakeup Lock, user moves out of range of both APs, should
        reconnect when user moves back in range of either AP.
        """
        wutils.wifi_connect(self.dut, self.ap_a, num_of_tries=5)
        wutils.wifi_connect(self.dut, self.ap_b, num_of_tries=5)
        wutils.wifi_toggle_state(self.dut, new_state=False)
        time.sleep(PRESCAN_DELAY_SEC)
        self.do_location_scan(CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT + 2)
        self.ap_a_off()
        self.ap_b_off()
        self.do_location_scan(CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT + 2)
        self.ap_a_on()
        self.do_location_scan()
        asserts.assert_true(
            self.dut.droid.wifiCheckState(),
            "Expect Wifi Wake to enable Wifi, but Wifi is disabled.")

    @test_tracker_info(uuid="d67657c8-3de3-46a6-a103-428cdab89423")
    def test_reconnect_to_better_saved_network(self):
        """
        2 saved APs, one attenuated, one unattenuated, Wifi Wake should connect
        to the unattenuated AP
        """
        wutils.wifi_connect(self.dut, self.ap_a, num_of_tries=5)
        wutils.wifi_connect(self.dut, self.ap_b, num_of_tries=5)
        self.dut.ed.clear_all_events()
        self.ap_a_off()
        self.ap_b_off()
        wutils.wait_for_disconnect(self.dut)
        self.log.info("Wifi Disconnected")
        time.sleep(LAST_DISCONNECT_TIMEOUT_SEC * 1.2)
        wutils.wifi_toggle_state(self.dut, new_state=False)
        time.sleep(PRESCAN_DELAY_SEC)
        self.do_location_scan(CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT + 2)

        self.ap_a_on()
        self.ap_b_on()
        self.ap_a_atten.set_atten(30)
        self.ap_b_atten.set_atten(0)

        self.do_location_scan()
        asserts.assert_true(
            self.dut.droid.wifiCheckState(),
            "Expect Wifi Wake to enable Wifi, but Wifi is disabled.")
        expected_ssid = self.ap_b[wutils.WifiEnums.SSID_KEY]
        actual_ssid = self.dut.droid.wifiGetConnectionInfo()[
            wutils.WifiEnums.SSID_KEY]
        asserts.assert_equal(
            expected_ssid, actual_ssid,
            ("Expected to connect to SSID '{}', but actually connected to "
             "'{}' instead.").format(expected_ssid, actual_ssid))
