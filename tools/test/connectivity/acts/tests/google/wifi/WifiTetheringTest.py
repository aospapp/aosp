#
#   Copyright 2017 - The Android Open Source Project
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
import random
import socket
import time

from acts import asserts
from acts import base_test
from acts import test_runner
from acts.controllers import adb
from acts.test_decorators import test_tracker_info
from acts.test_utils.tel import tel_data_utils
from acts.test_utils.tel import tel_defines
from acts.test_utils.tel.tel_data_utils import toggle_airplane_mode
from acts.test_utils.tel.tel_data_utils import wait_for_cell_data_connection
from acts.test_utils.tel.tel_test_utils import get_operator_name
from acts.test_utils.tel.tel_test_utils import http_file_download_by_chrome
from acts.test_utils.tel.tel_test_utils import verify_http_connection
from acts.test_utils.tel.tel_test_utils import WIFI_CONFIG_APBAND_2G
from acts.test_utils.tel.tel_test_utils import WIFI_CONFIG_APBAND_5G
from acts.test_utils.wifi import wifi_test_utils as wutils

WAIT_TIME = 2

class WifiTetheringTest(base_test.BaseTestClass):
    """ Tests for Wifi Tethering """

    def setup_class(self):
        """ Setup devices for tethering and unpack params """

        self.convert_byte_to_mb = 1024.0 * 1024.0
        self.new_ssid = "wifi_tethering_test2"
        self.data_usage_error = 1

        self.hotspot_device = self.android_devices[0]
        self.tethered_devices = self.android_devices[1:]
        req_params = ("network", "url", "download_file", "file_size")
        self.unpack_userparams(req_params)
        self.file_size = int(self.file_size)

        wutils.wifi_toggle_state(self.hotspot_device, False)
        self.hotspot_device.droid.telephonyToggleDataConnection(True)
        wait_for_cell_data_connection(self.log, self.hotspot_device, True)
        asserts.assert_true(
            verify_http_connection(self.log, self.hotspot_device),
            "HTTP verification failed on cell data connection")
        asserts.assert_true(
            self.hotspot_device.droid.connectivityIsTetheringSupported(),
            "Tethering is not supported for the provider")
        for ad in self.tethered_devices:
            wutils.wifi_test_device_init(ad)

        # Set chrome browser start with no-first-run verification
        # Give permission to read from and write to storage
        commands = ["pm grant com.android.chrome "
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "pm grant com.android.chrome "
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "rm /data/local/chrome-command-line",
                    "am set-debug-app --persistent com.android.chrome",
                    'echo "chrome --no-default-browser-check --no-first-run '
                    '--disable-fre" > /data/local/tmp/chrome-command-line']
        for cmd in commands:
            for dut in self.tethered_devices:
                try:
                    dut.adb.shell(cmd)
                except adb.AdbError:
                    self.log.warn("adb command %s failed on %s"
                                  % (cmd, dut.serial))

    def teardown_class(self):
        """ Reset devices """
        wutils.wifi_toggle_state(self.hotspot_device, True)

    def on_fail(self, test_name, begin_time):
        """ Collect bug report on failure """
        self.hotspot_device.take_bug_report(test_name, begin_time)
        for ad in self.tethered_devices:
            ad.take_bug_report(test_name, begin_time)

    """ Helper functions """

    def _is_ipaddress_ipv6(self, ip_address):
        """ Verify if the given string is a valid IPv6 address

        Args:
            1. string which contains the IP address

        Returns:
            True: if valid ipv6 address
            False: if not
        """
        try:
            socket.inet_pton(socket.AF_INET6, ip_address)
            return True
        except socket.error:
            return False

    def _supports_ipv6_tethering(self, dut):
        """ Check if provider supports IPv6 tethering.
            Currently, only Verizon supports IPv6 tethering

        Returns:
            True: if provider supports IPv6 tethering
            False: if not
        """
        # Currently only Verizon support IPv6 tethering
        carrier_supports_tethering = ["vzw"]
        operator = get_operator_name(self.log, dut)
        return operator in carrier_supports_tethering

    def _carrier_supports_ipv6(self,dut):
        """ Verify if carrier supports ipv6
            Currently, only verizon and t-mobile supports IPv6

        Returns:
            True: if carrier supports ipv6
            False: if not
        """
        carrier_supports_ipv6 = ["vzw", "tmo"]
        operator = get_operator_name(self.log, dut)
        self.log.info("Carrier is %s" % operator)
        return operator in carrier_supports_ipv6

    def _find_ipv6_default_route(self, dut):
        """ Checks if IPv6 default route exists in the link properites

        Returns:
            True: if default route found
            False: if not
        """
        default_route_substr = "::/0 -> "
        link_properties = dut.droid.connectivityGetActiveLinkProperties()
        self.log.info("LINK PROPERTIES:\n%s\n" % link_properties)
        return link_properties and default_route_substr in link_properties

    def _verify_ipv6_tethering(self, dut):
        """ Verify IPv6 tethering """
        http_response = dut.droid.httpRequestString(self.url)
        link_properties = dut.droid.connectivityGetActiveLinkProperties()
        self.log.info("IP address %s " % http_response)
        if dut==self.hotspot_device and self._carrier_supports_ipv6(dut)\
            or self._supports_ipv6_tethering(self.hotspot_device):
            asserts.assert_true(self._is_ipaddress_ipv6(http_response),
                                "The http response did not return IPv6 address")
            asserts.assert_true(link_properties and http_response in link_properties,
                                "Could not find IPv6 address in link properties")
            asserts.assert_true(self._find_ipv6_default_route(dut),
                                "Could not find IPv6 default route in link properties")
        else:
            asserts.assert_true(not self._find_ipv6_default_route(dut),
                                "Found IPv6 default route in link properties")

    def _start_wifi_tethering(self, wifi_band=WIFI_CONFIG_APBAND_2G):
        """ Start wifi tethering on hotspot device

        Args:
            1. wifi_band: specifies the wifi band to start the hotspot
               on. The current options are 2G and 5G
        """
        wutils.start_wifi_tethering(self.hotspot_device,
                                    self.network[wutils.WifiEnums.SSID_KEY],
                                    self.network[wutils.WifiEnums.PWD_KEY],
                                    wifi_band)

    def _connect_disconnect_devices(self):
        """ Randomly connect and disconnect devices from the
            self.tethered_devices list to hotspot device
        """
        device_connected = [ False ] * len(self.tethered_devices)
        for _ in range(50):
            dut_id = random.randint(0, len(self.tethered_devices)-1)
            dut = self.tethered_devices[dut_id]
            # wait for 1 sec between connect & disconnect stress test
            time.sleep(1)
            if device_connected[dut_id]:
                wutils.wifi_forget_network(dut, self.network["SSID"])
            else:
                wutils.wifi_connect(dut, self.network)
            device_connected[dut_id] = not device_connected[dut_id]

    def _verify_ping(self, dut, ip, isIPv6=False):
        """ Verify ping works from the dut to IP/hostname

        Args:
            1. dut - ad object to check ping from
            2. ip - ip/hostname to ping (IPv4 and IPv6)

        Returns:
            True - if ping is successful
            False - if not
        """
        self.log.info("Pinging %s from dut %s" % (ip, dut.serial))
        if isIPv6 or self._is_ipaddress_ipv6(ip):
            return dut.droid.pingHost(ip, 5, "ping6")
        return dut.droid.pingHost(ip)

    def _return_ip_for_interface(self, dut, iface_name):
        """ Return list of IP addresses for an interface

        Args:
            1. dut - ad object
            2. iface_name - interface name

        Returns:
            List of IPv4 and IPv6 addresses
        """
        return dut.droid.connectivityGetIPv4Addresses(iface_name) + \
            dut.droid.connectivityGetIPv6Addresses(iface_name)

    def _test_traffic_between_two_tethered_devices(self, dut1, dut2):
        """ Verify pinging interfaces of one DUT from another

        Args:
            1. dut1 - tethered device 1
            2. dut2 - tethered device 2
        """
        wutils.wifi_connect(dut1, self.network)
        wutils.wifi_connect(dut2, self.network)

        dut1_ipaddrs = dut1.droid.connectivityGetIPv4Addresses("wlan0") + \
            dut1.droid.connectivityGetIPv6Addresses("wlan0")
        dut2_ipaddrs = dut2.droid.connectivityGetIPv4Addresses("wlan0") + \
            dut2.droid.connectivityGetIPv6Addresses("wlan0")

        for ip in dut1_ipaddrs:
            asserts.assert_true(self._verify_ping(dut2, ip), "%s " % ip)
        for ip in dut2_ipaddrs:
            asserts.assert_true(self._verify_ping(dut1, ip), "%s " % ip)

    def _ping_hotspot_interfaces_from_tethered_device(self, dut):
        """ Ping hotspot interfaces from tethered device

        Args:
            1. dut - tethered device

        Returns:
            True - if all IP addresses are pingable
            False - if not
        """
        ifaces = self.hotspot_device.droid.connectivityGetNetworkInterfaces()
        return_result = True
        for interface in ifaces:
            iface_name = interface.split()[0].split(':')[1]
            if iface_name == "lo":
                continue
            ip_list = self._return_ip_for_interface(
                self.hotspot_device, iface_name)
            for ip in ip_list:
                ping_result = self._verify_ping(dut, ip)
                self.log.info("Ping result: %s %s %s" %
                              (iface_name, ip, ping_result))
                return_result = return_result and ping_result

        return return_result

    """ Test Cases """

    @test_tracker_info(uuid="36d03295-bea3-446e-8342-b9f8f1962a32")
    def test_ipv6_tethering(self):
        """ IPv6 tethering test

        Steps:
            1. Start wifi tethering on hotspot device
            2. Verify IPv6 address on hotspot device (VZW & TMO only)
            3. Connect tethered device to hotspot device
            4. Verify IPv6 address on the client's link properties (VZW only)
            5. Verify ping on client using ping6 which should pass (VZW only)
            6. Disable mobile data on provider and verify that link properties
               does not have IPv6 address and default route (VZW only)
        """
        # Start wifi tethering on the hotspot device
        wutils.toggle_wifi_off_and_on(self.hotspot_device)
        self._start_wifi_tethering()

        # Verify link properties on hotspot device
        self.log.info("Check IPv6 properties on the hotspot device. "
                      "Verizon & T-mobile should have IPv6 in link properties")
        self._verify_ipv6_tethering(self.hotspot_device)

        # Connect the client to the SSID
        wutils.wifi_connect(self.tethered_devices[0], self.network)

        # Need to wait atleast 2 seconds for IPv6 address to
        # show up in the link properties
        time.sleep(WAIT_TIME)

        # Verify link properties on tethered device
        self.log.info("Check IPv6 properties on the tethered device. "
                      "Device should have IPv6 if carrier is Verizon")
        self._verify_ipv6_tethering(self.tethered_devices[0])

        # Verify ping6 on tethered device
        ping_result = self._verify_ping(self.tethered_devices[0],
                                        wutils.DEFAULT_PING_ADDR, True)
        if self._supports_ipv6_tethering(self.hotspot_device):
            asserts.assert_true(ping_result, "Ping6 failed on the client")
        else:
            asserts.assert_true(not ping_result, "Ping6 failed as expected")

        # Disable mobile data on hotspot device
        # and verify the link properties on tethered device
        self.log.info("Disabling mobile data to verify ipv6 default route")
        self.hotspot_device.droid.telephonyToggleDataConnection(False)
        asserts.assert_equal(
            self.hotspot_device.droid.telephonyGetDataConnectionState(),
            tel_defines.DATA_STATE_CONNECTED,
            "Could not disable cell data")

        time.sleep(WAIT_TIME) # wait until the IPv6 is removed from link properties

        result = self._find_ipv6_default_route(self.tethered_devices[0])
        self.hotspot_device.droid.telephonyToggleDataConnection(True)
        if result:
            asserts.fail("Found IPv6 default route in link properties:Data off")
        self.log.info("Did not find IPv6 address in link properties")

        # Disable wifi tethering
        wutils.stop_wifi_tethering(self.hotspot_device)

    @test_tracker_info(uuid="110b61d1-8af2-4589-8413-11beac7a3025")
    def wifi_tethering_2ghz_traffic_between_2tethered_devices(self):
        """ Steps:

            1. Start wifi hotspot with 2G band
            2. Connect 2 tethered devices to the hotspot device
            3. Ping interfaces between the tethered devices
        """
        wutils.toggle_wifi_off_and_on(self.hotspot_device)
        self._start_wifi_tethering(WIFI_CONFIG_APBAND_2G)
        self._test_traffic_between_two_tethered_devices(self.tethered_devices[0],
                                                        self.tethered_devices[1])
        wutils.stop_wifi_tethering(self.hotspot_device)

    @test_tracker_info(uuid="953f6e2e-27bd-4b73-85a6-d2eaa4e755d5")
    def wifi_tethering_5ghz_traffic_between_2tethered_devices(self):
        """ Steps:

            1. Start wifi hotspot with 5ghz band
            2. Connect 2 tethered devices to the hotspot device
            3. Send traffic between the tethered devices
        """
        wutils.toggle_wifi_off_and_on(self.hotspot_device)
        self._start_wifi_tethering(WIFI_CONFIG_APBAND_5G)
        self._test_traffic_between_two_tethered_devices(self.tethered_devices[0],
                                                        self.tethered_devices[1])
        wutils.stop_wifi_tethering(self.hotspot_device)

    @test_tracker_info(uuid="d7d5aa51-682d-4882-a334-61966d93b68c")
    def test_wifi_tethering_2ghz_connect_disconnect_devices(self):
        """ Steps:

            1. Start wifi hotspot with 2ghz band
            2. Connect and disconnect multiple devices randomly
            3. Verify the correct functionality
        """
        wutils.toggle_wifi_off_and_on(self.hotspot_device)
        self._start_wifi_tethering(WIFI_CONFIG_APBAND_2G)
        self._connect_disconnect_devices()
        wutils.stop_wifi_tethering(self.hotspot_device)

    @test_tracker_info(uuid="34abd6c9-c7f1-4d89-aa2b-a66aeabed9aa")
    def test_wifi_tethering_5ghz_connect_disconnect_devices(self):
        """ Steps:

            1. Start wifi hotspot with 5ghz band
            2. Connect and disconnect multiple devices randomly
            3. Verify the correct functionality
        """
        wutils.toggle_wifi_off_and_on(self.hotspot_device)
        self._start_wifi_tethering(WIFI_CONFIG_APBAND_5G)
        self._connect_disconnect_devices()
        wutils.stop_wifi_tethering(self.hotspot_device)

    @test_tracker_info(uuid="7edfb220-37f8-42ea-8d7c-39712fbe9be5")
    def test_wifi_tethering_2ghz_ping_hotspot_interfaces(self):
        """ Steps:

            1. Start wifi hotspot with 2ghz band
            2. Connect tethered device to hotspot device
            3. Ping 'wlan0' and 'rmnet_data' interface's IPv4
               and IPv6 interfaces on hotspot device from tethered device
        """
        wutils.toggle_wifi_off_and_on(self.hotspot_device)
        self._start_wifi_tethering(WIFI_CONFIG_APBAND_2G)
        wutils.wifi_connect(self.tethered_devices[0], self.network)
        result = self._ping_hotspot_interfaces_from_tethered_device(
            self.tethered_devices[0])
        wutils.stop_wifi_tethering(self.hotspot_device)
        return result

    @test_tracker_info(uuid="17e450f4-795f-4e67-adab-984940dffedc")
    def test_wifi_tethering_5ghz_ping_hotspot_interfaces(self):
        """ Steps:

            1. Start wifi hotspot with 5ghz band
            2. Connect tethered device to hotspot device
            3. Ping 'wlan0' and 'rmnet_data' interface's IPv4
               and IPv6 interfaces on hotspot device from tethered device
        """
        wutils.toggle_wifi_off_and_on(self.hotspot_device)
        self._start_wifi_tethering(WIFI_CONFIG_APBAND_5G)
        wutils.wifi_connect(self.tethered_devices[0], self.network)
        result = self._ping_hotspot_interfaces_from_tethered_device(
            self.tethered_devices[0])
        wutils.stop_wifi_tethering(self.hotspot_device)
        return result

    @test_tracker_info(uuid="d4e18031-0af0-4b29-a574-8707cd4029b7")
    def test_wifi_tethering_verify_received_bytes(self):
        """ Steps:

            1. Start wifi hotspot and connect tethered device to it
            2. Get the data usage on hotspot device
            3. Download data on tethered device
            4. Get the new data usage on hotspot device
            5. Verify that hotspot device's data usage
               increased by downloaded file size
        """
        wutils.toggle_wifi_off_and_on(self.hotspot_device)
        dut = self.hotspot_device
        self._start_wifi_tethering()
        wutils.wifi_connect(self.tethered_devices[0], self.network)
        subscriber_id = dut.droid.telephonyGetSubscriberId()

        # get data usage limit
        end_time = int(time.time() * 1000)
        bytes_before_download = dut.droid.connectivityGetRxBytesForDevice(
            subscriber_id, 0, end_time)
        self.log.info("Data usage before download: %s MB" %
                      (bytes_before_download/self.convert_byte_to_mb))

        # download file
        self.log.info("Download file of size %sMB" % self.file_size)
        http_file_download_by_chrome(self.tethered_devices[0],
                                     self.download_file)

        # get data usage limit after download
        end_time = int(time.time() * 1000)
        bytes_after_download = dut.droid.connectivityGetRxBytesForDevice(
            subscriber_id, 0, end_time)
        self.log.info("Data usage after download: %s MB" %
                      (bytes_after_download/self.convert_byte_to_mb))

        bytes_diff = bytes_after_download - bytes_before_download
        wutils.stop_wifi_tethering(self.hotspot_device)

        # verify data usage update is correct
        bytes_used = bytes_diff/self.convert_byte_to_mb
        self.log.info("Data usage on the device increased by %s" % bytes_used)
        return bytes_used > self.file_size \
            and bytes_used < self.file_size + self.data_usage_error

    @test_tracker_info(uuid="07a00c96-4770-44a1-a9db-b3d02d6a12b6")
    def test_wifi_tethering_data_usage_limit(self):
        """ Steps:

            1. Set the data usage limit to current data usage + 10MB
            2. Start wifi tethering and connect a dut to the SSID
            3. Download 20MB data on tethered device
               a. file download should stop
               b. tethered device will lose internet connectivity
               c. data usage limit reached message should be displayed
                  on the hotspot device
            4. Verify data usage limit
        """
        wutils.toggle_wifi_off_and_on(self.hotspot_device)
        dut = self.hotspot_device
        data_usage_inc = 10 * self.convert_byte_to_mb
        subscriber_id = dut.droid.telephonyGetSubscriberId()

        self._start_wifi_tethering()
        wutils.wifi_connect(self.tethered_devices[0], self.network)

        # get current data usage
        end_time = int(time.time() * 1000)
        old_data_usage = dut.droid.connectivityQuerySummaryForDevice(
            subscriber_id, 0, end_time)

        # set data usage limit to current usage limit + 10MB
        dut.droid.connectivitySetDataUsageLimit(
            subscriber_id, str(int(old_data_usage + data_usage_inc)))

        # download file - size 20MB
        http_file_download_by_chrome(self.tethered_devices[0],
                                     self.download_file,
                                     timeout=120)
        end_time = int(time.time() * 1000)
        new_data_usage = dut.droid.connectivityQuerySummaryForDevice(
            subscriber_id, 0, end_time)

        # test network connectivity on tethered device
        asserts.assert_true(
            not wutils.validate_connection(self.tethered_devices[0]),
            "Tethered device has internet connectivity after data usage"
            "limit is reached on hotspot device")
        dut.droid.connectivityFactoryResetNetworkPolicies(subscriber_id)
        wutils.stop_wifi_tethering(self.hotspot_device)

        old_data_usage = (old_data_usage+data_usage_inc)/self.convert_byte_to_mb
        new_data_usage = new_data_usage/self.convert_byte_to_mb
        self.log.info("Expected data usage: %s MB" % old_data_usage)
        self.log.info("Actual data usage: %s MB" % new_data_usage)

        return (new_data_usage-old_data_usage) < self.data_usage_error

    @test_tracker_info(uuid="2bc344cb-0277-4f06-b6cc-65b3972086ed")
    def test_change_wifi_hotspot_ssid_when_hotspot_enabled(self):
        """ Steps:

            1. Start wifi tethering
            2. Verify wifi Ap configuration
            3. Change the SSID of the wifi hotspot while hotspot is on
            4. Verify the new SSID in wifi ap configuration
            5. Restart tethering and verify that the tethered device is able
               to connect to the new SSID
        """
        wutils.toggle_wifi_off_and_on(self.hotspot_device)
        dut = self.hotspot_device

        # start tethering and verify the wifi ap configuration settings
        self._start_wifi_tethering()
        wifi_ap = dut.droid.wifiGetApConfiguration()
        asserts.assert_true(
            wifi_ap[wutils.WifiEnums.SSID_KEY] == \
                self.network[wutils.WifiEnums.SSID_KEY],
            "Configured wifi hotspot SSID did not match with the expected SSID")
        wutils.wifi_connect(self.tethered_devices[0], self.network)

        # update the wifi ap configuration with new ssid
        config = {wutils.WifiEnums.SSID_KEY: self.new_ssid}
        config[wutils.WifiEnums.PWD_KEY] = self.network[wutils.WifiEnums.PWD_KEY]
        config[wutils.WifiEnums.APBAND_KEY] = WIFI_CONFIG_APBAND_2G
        asserts.assert_true(
            dut.droid.wifiSetWifiApConfiguration(config),
            "Failed to update WifiAp Configuration")
        wifi_ap = dut.droid.wifiGetApConfiguration()
        asserts.assert_true(
            wifi_ap[wutils.WifiEnums.SSID_KEY] == self.new_ssid,
            "Configured wifi hotspot SSID does not match with the expected SSID")

        # start wifi tethering with new wifi ap configuration
        wutils.stop_wifi_tethering(dut)
        dut.droid.wifiStartTrackingTetherStateChange()
        dut.droid.connectivityStartTethering(tel_defines.TETHERING_WIFI, False)
        try:
            dut.ed.pop_event("ConnectivityManagerOnTetheringStarted")
            dut.ed.wait_for_event("TetherStateChanged",
                                  lambda x: x["data"]["ACTIVE_TETHER"], 30)
        except:
            asserts.fail("Didn't receive wifi tethering starting confirmation")
        dut.droid.wifiStopTrackingTetherStateChange()

        # verify dut can connect to new wifi ap configuration
        new_network = {wutils.WifiEnums.SSID_KEY: self.new_ssid,
                       wutils.WifiEnums.PWD_KEY: \
                       self.network[wutils.WifiEnums.PWD_KEY]}
        wutils.wifi_connect(self.tethered_devices[0], new_network)
        wutils.stop_wifi_tethering(self.hotspot_device)
