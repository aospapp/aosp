#!/usr/bin/env python3.4
#
#   Copyright 2016 - The Android Open Source Project
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

import logging
import queue
import random
import time

from acts import asserts
from acts import utils
from acts.test_decorators import test_tracker_info
from acts.test_utils.net import arduino_test_utils as dutils
from acts.test_utils.net import socket_test_utils as sutils
from acts.test_utils.tel import tel_defines
from acts.test_utils.tel import tel_test_utils as tel_utils
from acts.test_utils.tel.tel_test_utils import WIFI_CONFIG_APBAND_2G
from acts.test_utils.tel.tel_test_utils import WIFI_CONFIG_APBAND_5G
from acts.test_utils.tel.tel_test_utils import WIFI_CONFIG_APBAND_AUTO
from acts.test_utils.wifi import wifi_test_utils as wutils
from acts.test_utils.wifi.WifiBaseTest import WifiBaseTest

class WifiSoftApTest(WifiBaseTest):

    def setup_class(self):
        """It will setup the required dependencies from config file and configure
           the devices for softap mode testing.

        Returns:
            True if successfully configured the requirements for testing.
        """
        self.dut = self.android_devices[0]
        self.dut_client = self.android_devices[1]
        req_params = ["dbs_supported_models"]
        opt_param = ["open_network"]
        self.unpack_userparams(
            req_param_names=req_params, opt_param_names=opt_param)
        if "AccessPoint" in self.user_params:
            self.legacy_configure_ap_and_start()
        self.open_network = self.open_network[0]["2g"]
        # Do a simple version of init - mainly just sync the time and enable
        # verbose logging.  This test will fail if the DUT has a sim and cell
        # data is disabled.  We would also like to test with phones in less
        # constrained states (or add variations where we specifically
        # constrain).
        utils.require_sl4a((self.dut, self.dut_client))
        utils.sync_device_time(self.dut)
        utils.sync_device_time(self.dut_client)
        # Set country code explicitly to "US".
        self.dut.droid.wifiSetCountryCode(wutils.WifiEnums.CountryCode.US)
        self.dut_client.droid.wifiSetCountryCode(wutils.WifiEnums.CountryCode.US)
        # Enable verbose logging on the duts
        self.dut.droid.wifiEnableVerboseLogging(1)
        asserts.assert_equal(self.dut.droid.wifiGetVerboseLoggingLevel(), 1,
            "Failed to enable WiFi verbose logging on the softap dut.")
        self.dut_client.droid.wifiEnableVerboseLogging(1)
        asserts.assert_equal(self.dut_client.droid.wifiGetVerboseLoggingLevel(), 1,
            "Failed to enable WiFi verbose logging on the client dut.")
        wutils.wifi_toggle_state(self.dut, True)
        wutils.wifi_toggle_state(self.dut_client, True)
        self.AP_IFACE = 'wlan0'
        if self.dut.model in self.dbs_supported_models:
            self.AP_IFACE = 'wlan1'
        if len(self.android_devices) > 2:
            utils.sync_device_time(self.android_devices[2])
            self.android_devices[2].droid.wifiSetCountryCode(wutils.WifiEnums.CountryCode.US)
            self.android_devices[2].droid.wifiEnableVerboseLogging(1)
            asserts.assert_equal(self.android_devices[2].droid.wifiGetVerboseLoggingLevel(), 1,
                "Failed to enable WiFi verbose logging on the client dut.")

    def teardown_class(self):
        wutils.stop_wifi_tethering(self.dut)
        wutils.reset_wifi(self.dut)
        wutils.reset_wifi(self.dut_client)
        if "AccessPoint" in self.user_params:
            del self.user_params["reference_networks"]
            del self.user_params["open_network"]

    def setup_test(self):
        # Set country code explicitly to "US".
        self.dut.droid.wifiSetCountryCode(wutils.WifiEnums.CountryCode.US)
        self.dut_client.droid.wifiSetCountryCode(wutils.WifiEnums.CountryCode.US)

    def teardown_test(self):
        self.dut.log.debug("Toggling Airplane mode OFF.")
        asserts.assert_true(utils.force_airplane_mode(self.dut, False),
                            "Can not turn off airplane mode: %s" % self.dut.serial)
        if self.dut.droid.wifiIsApEnabled():
            wutils.stop_wifi_tethering(self.dut)

    def on_fail(self, test_name, begin_time):
        self.dut.take_bug_report(test_name, begin_time)
        self.dut_client.take_bug_report(test_name, begin_time)

    """ Helper Functions """
    def create_softap_config(self):
        """Create a softap config with ssid and password."""
        ap_ssid = "softap_" + utils.rand_ascii_str(8)
        ap_password = utils.rand_ascii_str(8)
        self.dut.log.info("softap setup: %s %s", ap_ssid, ap_password)
        config = {wutils.WifiEnums.SSID_KEY: ap_ssid}
        config[wutils.WifiEnums.PWD_KEY] = ap_password
        return config

    def confirm_softap_in_scan_results(self, ap_ssid):
        """Confirm the ap started by wifi tethering is seen in scan results.

        Args:
            ap_ssid: SSID of the ap we are looking for.
        """
        wutils.start_wifi_connection_scan_and_ensure_network_found(
            self.dut_client, ap_ssid);

    def confirm_softap_not_in_scan_results(self, ap_ssid):
        """Confirm the ap started by wifi tethering is not seen in scan results.

        Args:
            ap_ssid: SSID of the ap we are looking for.
        """
        wutils.start_wifi_connection_scan_and_ensure_network_not_found(
            self.dut_client, ap_ssid);

    def validate_traffic_between_softap_clients(self, config):
        """Send traffic between softAp clients.

        Connect SoftAp clients to the wifi hotspot; one android
        device and the other arduino wifi controller. Send UDP traffic
        between the clients and verify that expected messages are received.

        Args:
            config: wifi network config with SSID, password
        """
        ad = self.dut_client
        wd = self.arduino_wifi_dongles[0]
        wutils.wifi_connect(ad, config, check_connectivity=False)
        dutils.connect_wifi(wd, config)
        local_ip = ad.droid.connectivityGetIPv4Addresses('wlan0')[0]
        remote_ip = wd.ip_address()
        port = random.randint(8000, 9000)
        self.log.info("IP addr on android device: %s" % local_ip)
        self.log.info("IP addr on arduino device: %s" % remote_ip)

        socket = sutils.open_datagram_socket(ad, local_ip, port)
        sutils.send_recv_data_datagram_sockets(
            ad, ad, socket, socket, remote_ip, port)
        sutils.close_datagram_socket(ad, socket)

    def check_cell_data_and_enable(self):
        """Make sure that cell data is enabled if there is a sim present.

        If a sim is active, cell data needs to be enabled to allow provisioning
        checks through (when applicable).  This is done to relax hardware
        requirements on DUTs - without this check, running this set of tests
        after other wifi tests may cause failures.
        """
        # We do have a sim.  Make sure data is enabled so we can tether.
        if not self.dut.droid.telephonyIsDataEnabled():
            self.dut.log.info("need to enable data")
            self.dut.droid.telephonyToggleDataConnection(True)
            asserts.assert_true(self.dut.droid.telephonyIsDataEnabled(),
                                "Failed to enable cell data for softap dut.")

    def validate_full_tether_startup(self, band=None, hidden=None,
                                     test_ping=False, test_clients=None):
        """Test full startup of wifi tethering

        1. Report current state.
        2. Switch to AP mode.
        3. verify SoftAP active.
        4. Shutdown wifi tethering.
        5. verify back to previous mode.
        """
        initial_wifi_state = self.dut.droid.wifiCheckState()
        initial_cell_state = tel_utils.is_sim_ready(self.log, self.dut)
        self.dut.log.info("current state: %s", initial_wifi_state)
        self.dut.log.info("is sim ready? %s", initial_cell_state)
        if initial_cell_state:
            self.check_cell_data_and_enable()
        config = self.create_softap_config()
        wutils.start_wifi_tethering(self.dut,
                                    config[wutils.WifiEnums.SSID_KEY],
                                    config[wutils.WifiEnums.PWD_KEY], band, hidden)
        if hidden:
            # First ensure it's not seen in scan results.
            self.confirm_softap_not_in_scan_results(
                config[wutils.WifiEnums.SSID_KEY])
            # If the network is hidden, it should be saved on the client to be
            # seen in scan results.
            config[wutils.WifiEnums.HIDDEN_KEY] = True
            ret = self.dut_client.droid.wifiAddNetwork(config)
            asserts.assert_true(ret != -1, "Add network %r failed" % config)
            self.dut_client.droid.wifiEnableNetwork(ret, 0)
        self.confirm_softap_in_scan_results(config[wutils.WifiEnums.SSID_KEY])
        if test_ping:
            self.validate_ping_between_softap_and_client(config)
        if test_clients:
            if hasattr(self, 'arduino_wifi_dongles'):
                self.validate_traffic_between_softap_clients(config)
            if len(self.android_devices) > 2:
                self.validate_ping_between_two_clients(config)
        wutils.stop_wifi_tethering(self.dut)
        asserts.assert_false(self.dut.droid.wifiIsApEnabled(),
                             "SoftAp is still reported as running")
        if initial_wifi_state:
            wutils.wait_for_wifi_state(self.dut, True)
        elif self.dut.droid.wifiCheckState():
            asserts.fail("Wifi was disabled before softap and now it is enabled")

    def validate_ping_between_softap_and_client(self, config):
        """Test ping between softap and its client.

        Connect one android device to the wifi hotspot.
        Verify they can ping each other.

        Args:
            config: wifi network config with SSID, password
        """
        wutils.wifi_connect(self.dut_client, config, check_connectivity=False)

        dut_ip = self.dut.droid.connectivityGetIPv4Addresses(self.AP_IFACE)[0]
        dut_client_ip = self.dut_client.droid.connectivityGetIPv4Addresses('wlan0')[0]

        self.dut.log.info("Try to ping %s" % dut_client_ip)
        asserts.assert_true(
            utils.adb_shell_ping(self.dut, count=10, dest_ip=dut_client_ip, timeout=20),
            "%s ping %s failed" % (self.dut.serial, dut_client_ip))

        self.dut_client.log.info("Try to ping %s" % dut_ip)
        asserts.assert_true(
            utils.adb_shell_ping(self.dut_client, count=10, dest_ip=dut_ip, timeout=20),
            "%s ping %s failed" % (self.dut_client.serial, dut_ip))

        wutils.stop_wifi_tethering(self.dut)

    def validate_ping_between_two_clients(self, config):
        """Test ping between softap's clients.

        Connect two android device to the wifi hotspot.
        Verify the clients can ping each other.

        Args:
            config: wifi network config with SSID, password
        """
        # Connect DUT to Network
        ad1 = self.dut_client
        ad2 = self.android_devices[2]

        wutils.wifi_connect(ad1, config, check_connectivity=False)
        wutils.wifi_connect(ad2, config, check_connectivity=False)
        ad1_ip = ad1.droid.connectivityGetIPv4Addresses('wlan0')[0]
        ad2_ip = ad2.droid.connectivityGetIPv4Addresses('wlan0')[0]

        # Ping each other
        ad1.log.info("Try to ping %s" % ad2_ip)
        asserts.assert_true(
            utils.adb_shell_ping(ad1, count=10, dest_ip=ad2_ip, timeout=20),
            "%s ping %s failed" % (ad1.serial, ad2_ip))

        ad2.log.info("Try to ping %s" % ad1_ip)
        asserts.assert_true(
            utils.adb_shell_ping(ad2, count=10, dest_ip=ad1_ip, timeout=20),
            "%s ping %s failed" % (ad2.serial, ad1_ip))

    """ Tests Begin """

    @test_tracker_info(uuid="495f1252-e440-461c-87a7-2c45f369e129")
    def test_check_wifi_tethering_supported(self):
        """Test check for wifi tethering support.

         1. Call method to check if wifi hotspot is supported
        """
        # TODO(silberst): wifiIsPortableHotspotSupported() is currently failing.
        # Remove the extra check and logging when b/30800811 is resolved
        hotspot_supported = self.dut.droid.wifiIsPortableHotspotSupported()
        tethering_supported = self.dut.droid.connectivityIsTetheringSupported()
        self.log.info(
            "IsPortableHotspotSupported: %s, IsTetheringSupported %s." % (
            hotspot_supported, tethering_supported))
        asserts.assert_true(hotspot_supported,
                            "DUT should support wifi tethering but is reporting false.")
        asserts.assert_true(tethering_supported,
                            "DUT should also support wifi tethering when called from ConnectivityManager")

    @test_tracker_info(uuid="09c19c35-c708-48a5-939b-ac2bbb403d54")
    def test_full_tether_startup(self):
        """Test full startup of wifi tethering in default band.

        1. Report current state.
        2. Switch to AP mode.
        3. verify SoftAP active.
        4. Shutdown wifi tethering.
        5. verify back to previous mode.
        """
        self.validate_full_tether_startup()

    @test_tracker_info(uuid="6437727d-7db1-4f69-963e-f26a7797e47f")
    def test_full_tether_startup_2G(self):
        """Test full startup of wifi tethering in 2G band.

        1. Report current state.
        2. Switch to AP mode.
        3. verify SoftAP active.
        4. Shutdown wifi tethering.
        5. verify back to previous mode.
        """
        self.validate_full_tether_startup(WIFI_CONFIG_APBAND_2G)

    @test_tracker_info(uuid="970272fa-1302-429b-b261-51efb4dad779")
    def test_full_tether_startup_5G(self):
        """Test full startup of wifi tethering in 5G band.

        1. Report current state.
        2. Switch to AP mode.
        3. verify SoftAP active.
        4. Shutdown wifi tethering.
        5. verify back to previous mode.
        """
        self.validate_full_tether_startup(WIFI_CONFIG_APBAND_5G)

    @test_tracker_info(uuid="f76ed37a-519a-48b4-b260-ee3fc5a9cae0")
    def test_full_tether_startup_auto(self):
        """Test full startup of wifi tethering in auto-band.

        1. Report current state.
        2. Switch to AP mode.
        3. verify SoftAP active.
        4. Shutdown wifi tethering.
        5. verify back to previous mode.
        """
        self.validate_full_tether_startup(WIFI_CONFIG_APBAND_AUTO)

    @test_tracker_info(uuid="d26ee4df-5dcb-4191-829f-05a10b1218a7")
    def test_full_tether_startup_2G_hidden(self):
        """Test full startup of wifi tethering in 2G band using hidden AP.

        1. Report current state.
        2. Switch to AP mode.
        3. verify SoftAP active.
        4. Shutdown wifi tethering.
        5. verify back to previous mode.
        """
        self.validate_full_tether_startup(WIFI_CONFIG_APBAND_2G, True)

    @test_tracker_info(uuid="229cd585-a789-4c9a-8948-89fa72de9dd5")
    def test_full_tether_startup_5G_hidden(self):
        """Test full startup of wifi tethering in 5G band using hidden AP.

        1. Report current state.
        2. Switch to AP mode.
        3. verify SoftAP active.
        4. Shutdown wifi tethering.
        5. verify back to previous mode.
        """
        self.validate_full_tether_startup(WIFI_CONFIG_APBAND_5G, True)

    @test_tracker_info(uuid="d546a143-6047-4ffd-b3c6-5ec81a38001f")
    def test_full_tether_startup_auto_hidden(self):
        """Test full startup of wifi tethering in auto-band using hidden AP.

        1. Report current state.
        2. Switch to AP mode.
        3. verify SoftAP active.
        4. Shutdown wifi tethering.
        5. verify back to previous mode.
        """
        self.validate_full_tether_startup(WIFI_CONFIG_APBAND_AUTO, True)

    @test_tracker_info(uuid="b2f75330-bf33-4cdd-851a-de390f891ef7")
    def test_tether_startup_while_connected_to_a_network(self):
        """Test full startup of wifi tethering in auto-band while the device
        is connected to a network.

        1. Connect to an open network.
        2. Turn on AP mode (in auto band).
        3. Verify SoftAP active.
        4. Make a client connect to the AP.
        5. Shutdown wifi tethering.
        6. Ensure that the client disconnected.
        """
        wutils.wifi_toggle_state(self.dut, True)
        wutils.wifi_connect(self.dut, self.open_network)
        config = self.create_softap_config()
        wutils.start_wifi_tethering(self.dut,
                                    config[wutils.WifiEnums.SSID_KEY],
                                    config[wutils.WifiEnums.PWD_KEY],
                                    WIFI_CONFIG_APBAND_AUTO)
        asserts.assert_true(self.dut.droid.wifiIsApEnabled(),
                             "SoftAp is not reported as running")
        # local hotspot may not have internet connectivity
        wutils.wifi_connect(self.dut_client, config, check_connectivity=False)
        wutils.stop_wifi_tethering(self.dut)
        wutils.wait_for_disconnect(self.dut_client)

    @test_tracker_info(uuid="f2cf56ad-b8b9-43b6-ab15-a47b1d96b92e")
    def test_full_tether_startup_2G_with_airplane_mode_on(self):
        """Test full startup of wifi tethering in 2G band with
        airplane mode on.

        1. Turn on airplane mode.
        2. Report current state.
        3. Switch to AP mode.
        4. verify SoftAP active.
        5. Shutdown wifi tethering.
        6. verify back to previous mode.
        7. Turn off airplane mode.
        """
        self.dut.log.debug("Toggling Airplane mode ON.")
        asserts.assert_true(utils.force_airplane_mode(self.dut, True),
                            "Can not turn on airplane mode: %s" % self.dut.serial)
        wutils.wifi_toggle_state(self.dut, True)
        self.validate_full_tether_startup(WIFI_CONFIG_APBAND_2G)

    @test_tracker_info(uuid="05c6f929-7754-477f-a9cd-f77e850b818b")
    def test_full_tether_startup_2G_multiple_clients(self):
        """Test full startup of wifi tethering in 2G band, connect clients
        to softAp and send traffic between them.

        1. Report current state.
        2. Switch to AP mode.
        3. verify SoftAP active.
        4. Connect clients to softAp.
        5. Send and recv UDP traffic between them.
        6. Shutdown wifi tethering.
        7. verify back to previous mode.
        """
        asserts.skip_if(not hasattr(self, 'arduino_wifi_dongles'),
                        "No wifi dongles connected. Skipping test")
        self.validate_full_tether_startup(WIFI_CONFIG_APBAND_2G,
                                          test_clients=True)

    @test_tracker_info(uuid="883dd5b1-50c6-4958-a50f-bb4bea77ccaf")
    def test_full_tether_startup_2G_one_client_ping_softap(self):
        """(AP) 1 Device can connect to 2G hotspot

        Steps:
        1. Turn on DUT's 2G softap
        2. Client connects to the softap
        3. Client and DUT ping each other
        """
        self.validate_full_tether_startup(WIFI_CONFIG_APBAND_2G, test_ping=True)

    @test_tracker_info(uuid="6604e848-99d6-422c-9fdc-2882642438b6")
    def test_full_tether_startup_5G_one_client_ping_softap(self):
        """(AP) 1 Device can connect to 5G hotspot

        Steps:
        1. Turn on DUT's 5G softap
        2. Client connects to the softap
        3. Client and DUT ping each other
        """
        self.validate_full_tether_startup(WIFI_CONFIG_APBAND_5G, test_ping=True)

    @test_tracker_info(uuid="17725ecd-f900-4cf7-8b2d-d7515b0a595c")
    def test_softap_2G_two_clients_ping_each_other(self):
        """Test for 2G hotspot with 2 clients

        1. Turn on 2G hotspot
        2. Two clients connect to the hotspot
        3. Two clients ping each other
        """
        asserts.skip_if(len(self.android_devices) < 3,
                        "No extra android devices. Skip test")
        self.validate_full_tether_startup(WIFI_CONFIG_APBAND_2G, test_clients=True)

    @test_tracker_info(uuid="98c09888-1021-4f79-9065-b3cf9b132146")
    def test_softap_5G_two_clients_ping_each_other(self):
        """Test for 5G hotspot with 2 clients

        1. Turn on 5G hotspot
        2. Two clients connect to the hotspot
        3. Two clients ping each other
        """
        asserts.skip_if(len(self.android_devices) < 3,
                        "No extra android devices. Skip test")
        self.validate_full_tether_startup(WIFI_CONFIG_APBAND_5G, test_clients=True)

    """ Tests End """


if __name__ == "__main__":
    pass
