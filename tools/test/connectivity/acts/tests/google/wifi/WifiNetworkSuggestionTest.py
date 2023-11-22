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

import itertools
import pprint
import queue
import time

import acts.base_test
import acts.signals as signals
import acts.test_utils.wifi.wifi_test_utils as wutils
import acts.utils

from acts import asserts
from acts.controllers.android_device import SL4A_APK_NAME
from acts.test_decorators import test_tracker_info
from acts.test_utils.wifi.WifiBaseTest import WifiBaseTest
from acts.test_utils.wifi import wifi_constants

WifiEnums = wutils.WifiEnums

# Default timeout used for reboot, toggle WiFi and Airplane mode,
# for the system to settle down after the operation.
DEFAULT_TIMEOUT = 10

class WifiNetworkSuggestionTest(WifiBaseTest):
    """Tests for WifiNetworkSuggestion API surface.

    Test Bed Requirement:
    * one Android device
    * Several Wi-Fi networks visible to the device, including an open Wi-Fi
      network.
    """

    def __init__(self, controllers):
        WifiBaseTest.__init__(self, controllers)

    def setup_class(self):
        self.dut = self.android_devices[0]
        wutils.wifi_test_device_init(self.dut)
        req_params = []
        opt_param = [
            "open_network", "reference_networks"
        ]
        self.unpack_userparams(
            req_param_names=req_params, opt_param_names=opt_param)

        if "AccessPoint" in self.user_params:
            self.legacy_configure_ap_and_start(wpa_network=True,
                                               wep_network=True)

        asserts.assert_true(
            len(self.reference_networks) > 0,
            "Need at least one reference network with psk.")
        self.wpa_psk_2g = self.reference_networks[0]["2g"]
        self.wpa_psk_5g = self.reference_networks[0]["5g"]
        self.open_2g = self.open_network[0]["2g"]
        self.open_5g = self.open_network[0]["5g"]
        self.dut.droid.wifiRemoveNetworkSuggestions([])

    def setup_test(self):
        self.dut.droid.wakeLockAcquireBright()
        self.dut.droid.wakeUpNow()
        self.clear_deleted_ephemeral_networks()
        wutils.wifi_toggle_state(self.dut, True)
        self.dut.ed.clear_all_events()

    def teardown_test(self):
        self.dut.droid.wakeLockRelease()
        self.dut.droid.goToSleepNow()
        self.dut.droid.wifiRemoveNetworkSuggestions([])
        self.dut.droid.wifiDisconnect()
        wutils.reset_wifi(self.dut)
        self.dut.ed.clear_all_events()

    def on_fail(self, test_name, begin_time):
        self.dut.take_bug_report(test_name, begin_time)
        self.dut.cat_adb_log(test_name, begin_time)

    def teardown_class(self):
        if "AccessPoint" in self.user_params:
            del self.user_params["reference_networks"]
            del self.user_params["open_network"]

    """Helper Functions"""
    def set_approved(self, approved):
        self.dut.log.debug("Setting suggestions from sl4a app "
                           + "approved" if approved else "not approved")
        self.dut.adb.shell("cmd wifi network-suggestions-set-user-approved"
                           + " " + SL4A_APK_NAME
                           + " " + ("yes" if approved else "no"))

    def is_approved(self):
        is_approved_str = self.dut.adb.shell(
            "cmd wifi network-suggestions-has-user-approved"
            + " " + SL4A_APK_NAME)
        return True if (is_approved_str == "yes") else False

    def clear_deleted_ephemeral_networks(self):
        self.dut.log.debug("Clearing deleted ephemeral networks")
        self.dut.adb.shell(
            "cmd wifi clear-deleted-ephemeral-networks")

    def add_suggestions_and_ensure_connection(self, network_suggestions,
                                              expected_ssid,
                                              expect_post_connection_broadcast):
        if expect_post_connection_broadcast is not None:
            self.dut.droid.wifiStartTrackingNetworkSuggestionStateChange()

        self.dut.log.info("Adding network suggestions");
        asserts.assert_true(
            self.dut.droid.wifiAddNetworkSuggestions(network_suggestions),
            "Failed to add suggestions")
        # Enable suggestions by the app.
        self.dut.log.debug("Enabling suggestions from test");
        self.set_approved(True)
        wutils.start_wifi_connection_scan_and_return_status(self.dut)
        wutils.wait_for_connect(self.dut, expected_ssid)

        if expect_post_connection_broadcast is None:
            return;

        # Check if we expected to get the broadcast.
        try:
            event = self.dut.ed.pop_event(
                wifi_constants.WIFI_NETWORK_SUGGESTION_POST_CONNECTION, 60)
        except queue.Empty:
            if expect_post_connection_broadcast:
                raise signals.TestFailure(
                    "Did not receive post connection broadcast")
        else:
            if not expect_post_connection_broadcast:
                raise signals.TestFailure(
                    "Received post connection broadcast")
        finally:
            self.dut.droid.wifiStopTrackingNetworkSuggestionStateChange()
        self.dut.ed.clear_all_events()


    @test_tracker_info(uuid="bda8ed20-4382-4380-831a-64cf77eca108")
    def test_connect_to_wpa_psk_2g(self):
        """ Adds a network suggestion and ensure that the device connected.

        Steps:
        1. Send a network suggestion to the device.
        2. Wait for the device to connect to it.
        3. Ensure that we did not receive the post connection broadcast
           (isAppInteractionRequired = False).
        4. Remove the suggestions and ensure the device does not connect back.
        """
        self.add_suggestions_and_ensure_connection(
            [self.wpa_psk_2g], self.wpa_psk_2g[WifiEnums.SSID_KEY],
            False)
        self.dut.log.info("Removing network suggestions");
        asserts.assert_true(
            self.dut.droid.wifiRemoveNetworkSuggestions([self.wpa_psk_2g]),
            "Failed to remove suggestions")
        # Ensure we did not disconnect
        wutils.ensure_no_disconnect(self.dut)

        # Trigger a disconnect and wait for the disconnect.
        self.dut.droid.wifiDisconnect()
        wutils.wait_for_disconnect(self.dut)
        self.dut.ed.clear_all_events()

        # Now ensure that we didn't connect back.
        asserts.assert_false(
            wutils.wait_for_connect(self.dut,
                                    self.wpa_psk_2g[WifiEnums.SSID_KEY],
                                    assert_on_fail=False),
            "Device should not connect back")


    @test_tracker_info(uuid="f54bc250-d9e9-4f00-8b5b-b866e8550b43")
    def test_connect_to_highest_priority(self):
        """
        Adds network suggestions and ensures that device connects to
        the suggestion with the highest priority.

        Steps:
        1. Send 2 network suggestions to the device (with different priorities).
        2. Wait for the device to connect to the network with the highest
           priority.
        3. Re-add the suggestions with the priorities reversed.
        4. Again wait for the device to connect to the network with the highest
           priority.
        """
        network_suggestion_2g = self.wpa_psk_2g
        network_suggestion_5g = self.wpa_psk_5g

        # Add suggestions & wait for the connection event.
        network_suggestion_2g[WifiEnums.PRIORITY] = 5
        network_suggestion_5g[WifiEnums.PRIORITY] = 2
        self.add_suggestions_and_ensure_connection(
            [network_suggestion_2g, network_suggestion_5g],
            self.wpa_psk_2g[WifiEnums.SSID_KEY],
            None)

        # Remove all suggestions
        self.dut.log.info("Removing network suggestions");
        asserts.assert_true(
            self.dut.droid.wifiRemoveNetworkSuggestions([]),
            "Failed to remove suggestions")
        # Trigger a disconnect and wait for the disconnect.
        self.dut.droid.wifiDisconnect()
        wutils.wait_for_disconnect(self.dut)
        self.dut.ed.clear_all_events()

        # Reverse the priority.
        # Add suggestions & wait for the connection event.
        network_suggestion_2g[WifiEnums.PRIORITY] = 2
        network_suggestion_5g[WifiEnums.PRIORITY] = 5
        self.add_suggestions_and_ensure_connection(
            [network_suggestion_2g, network_suggestion_5g],
            self.wpa_psk_5g[WifiEnums.SSID_KEY],
            None)


    @test_tracker_info(uuid="b1d27eea-23c8-4c4f-b944-ef118e4cc35f")
    def test_connect_to_wpa_psk_2g_with_post_connection_broadcast(self):
        """ Adds a network suggestion and ensure that the device connected.

        Steps:
        1. Send a network suggestion to the device with
           isAppInteractionRequired set.
        2. Wait for the device to connect to it.
        3. Ensure that we did receive the post connection broadcast
           (isAppInteractionRequired = True).
        4. Remove the suggestions and ensure the device does not connect back.
        """
        network_suggestion = self.wpa_psk_2g
        network_suggestion[WifiEnums.IS_APP_INTERACTION_REQUIRED] = True
        self.add_suggestions_and_ensure_connection(
            [network_suggestion], self.wpa_psk_2g[WifiEnums.SSID_KEY],
            True)
        self.dut.log.info("Removing network suggestions");
        asserts.assert_true(
            self.dut.droid.wifiRemoveNetworkSuggestions([network_suggestion]),
            "Failed to remove suggestions")
        # Ensure we did not disconnect
        wutils.ensure_no_disconnect(self.dut)

        # Trigger a disconnect and wait for the disconnect.
        self.dut.droid.wifiDisconnect()
        wutils.wait_for_disconnect(self.dut)
        self.dut.ed.clear_all_events()

        # Now ensure that we didn't connect back.
        asserts.assert_false(
            wutils.wait_for_connect(self.dut,
                                    self.wpa_psk_2g[WifiEnums.SSID_KEY],
                                    assert_on_fail=False),
            "Device should not connect back")


    @test_tracker_info(uuid="a036a24d-29c0-456d-ae6a-afdde34da710")
    def test_connect_to_wpa_psk_5g_reboot_config_store(self):
        """
        Adds a network suggestion and ensure that the device connects to it
        after reboot.

        Steps:
        1. Send a network suggestion to the device.
        2. Wait for the device to connect to it.
        3. Ensure that we did not receive the post connection broadcast
           (isAppInteractionRequired = False).
        4. Reboot the device.
        5. Wait for the device to connect to back to it.
        6. Remove the suggestions and ensure the device does not connect back.
        """
        self.add_suggestions_and_ensure_connection(
            [self.wpa_psk_5g], self.wpa_psk_5g[WifiEnums.SSID_KEY],
            None)

        # Reboot and wait for connection back to the same suggestion.
        self.dut.reboot()
        time.sleep(DEFAULT_TIMEOUT)

        wutils.wait_for_connect(self.dut, self.wpa_psk_5g[WifiEnums.SSID_KEY])

        self.dut.log.info("Removing network suggestions");
        asserts.assert_true(
            self.dut.droid.wifiRemoveNetworkSuggestions([self.wpa_psk_5g]),
            "Failed to remove suggestions")
        # Ensure we did not disconnect
        wutils.ensure_no_disconnect(self.dut)

        # Trigger a disconnect and wait for the disconnect.
        self.dut.droid.wifiDisconnect()
        wutils.wait_for_disconnect(self.dut)
        self.dut.ed.clear_all_events()

        # Now ensure that we didn't connect back.
        asserts.assert_false(
            wutils.wait_for_connect(self.dut,
                                    self.wpa_psk_5g[WifiEnums.SSID_KEY],
                                    assert_on_fail=False),
            "Device should not connect back")


    @test_tracker_info(uuid="554b5861-22d0-4922-a5f4-712b4cf564eb")
    def test_fail_to_connect_to_wpa_psk_5g_when_not_approved(self):
        """
        Adds a network suggestion and ensure that the device does not
        connect to it until we approve the app.

        Steps:
        1. Send a network suggestion to the device with the app not approved.
        2. Ensure the network is present in scan results, but we don't connect
           to it.
        3. Now approve the app.
        4. Wait for the device to connect to it.
        """
        self.dut.log.info("Adding network suggestions");
        asserts.assert_true(
            self.dut.droid.wifiAddNetworkSuggestions([self.wpa_psk_5g]),
            "Failed to add suggestions")

        # Disable suggestions by the app.
        self.set_approved(False)

        # Ensure the app is not approved.
        asserts.assert_false(
            self.is_approved(),
            "Suggestions should be disabled")

        # Start a new scan to trigger auto-join.
        wutils.start_wifi_connection_scan_and_ensure_network_found(
            self.dut, self.wpa_psk_5g[WifiEnums.SSID_KEY])

        # Ensure we don't connect to the network.
        asserts.assert_false(
            wutils.wait_for_connect(
                self.dut, self.wpa_psk_5g[WifiEnums.SSID_KEY], assert_on_fail=False),
            "Should not connect to network suggestions from unapproved app")

        self.dut.log.info("Enabling suggestions from test");
        # Now Enable suggestions by the app & ensure we connect to the network.
        self.set_approved(True)

        # Ensure the app is approved.
        asserts.assert_true(
            self.is_approved(),
            "Suggestions should be enabled")

        # Start a new scan to trigger auto-join.
        wutils.start_wifi_connection_scan_and_ensure_network_found(
            self.dut, self.wpa_psk_5g[WifiEnums.SSID_KEY])

        wutils.wait_for_connect(self.dut, self.wpa_psk_5g[WifiEnums.SSID_KEY])

    @test_tracker_info(uuid="98400dea-776e-4a0a-9024-18845b27331c")
    def test_fail_to_connect_to_wpa_psk_2g_after_user_forgot_network(self):
        """
        Adds a network suggestion and ensures that the device does not
        connect to it after the user forgot the network previously.

        Steps:
        1. Send a network suggestion to the device with
           isAppInteractionRequired set.
        2. Wait for the device to connect to it.
        3. Ensure that we did receive the post connection broadcast
           (isAppInteractionRequired = True).
        4. Simulate user forgetting the network and the device does not
           connecting back even though the suggestion is active from the app.
        """
        network_suggestion = self.wpa_psk_2g
        network_suggestion[WifiEnums.IS_APP_INTERACTION_REQUIRED] = True
        self.add_suggestions_and_ensure_connection(
            [network_suggestion], self.wpa_psk_2g[WifiEnums.SSID_KEY],
            True)

        # Simulate user forgeting the ephemeral network.
        self.dut.droid.wifiDisableEphemeralNetwork(
            self.wpa_psk_2g[WifiEnums.SSID_KEY])
        wutils.wait_for_disconnect(self.dut)
        self.dut.log.info("Disconnected from network %s", self.wpa_psk_2g)
        self.dut.ed.clear_all_events()

        # Now ensure that we don't connect back even though the suggestion
        # is still active.
        asserts.assert_false(
            wutils.wait_for_connect(self.dut,
                                    self.wpa_psk_2g[WifiEnums.SSID_KEY],
                                    assert_on_fail=False),
            "Device should not connect back")
