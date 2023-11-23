#
#   Copyright 2021 - The Android Open Source Project
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

from acts import asserts
from acts.controllers.openwrt_ap import MOBLY_CONTROLLER_CONFIG_NAME as OPENWRT
from acts_contrib.test_utils.wifi import wifi_test_utils as wutils
from acts_contrib.test_utils.wifi.WifiBaseTest import WifiBaseTest

WLAN = "wlan0"
PING_ADDR = "google.com"


class DhcpTest(WifiBaseTest):
    """DHCP related test for Android."""

    def setup_class(self):
        self.dut = self.android_devices[0]

        wutils.wifi_test_device_init(self.dut)
        asserts.assert_true(OPENWRT in self.user_params,
                            "OpenWrtAP is not in testbed.")
        self.openwrt = self.access_points[0]
        self.configure_openwrt_ap_and_start(wpa_network=True)
        self.wifi_network = self.openwrt.get_wifi_network()
        self.openwrt.network_setting.setup_ipv6_bridge()
        asserts.assert_true(self.openwrt.verify_wifi_status(),
                            "OpenWrt Wifi interface is not ready.")
    def teardown_class(self):
        """Reset wifi to make sure VPN tears down cleanly."""
        wutils.reset_wifi(self.dut)

    def teardown_test(self):
        """Reset wifi to make sure VPN tears down cleanly."""
        wutils.reset_wifi(self.dut)

    def _verify_ping(self, option="", dest=PING_ADDR):
        try:
            out = self.dut.adb.shell("ping%s -c1 %s" % (option, dest))
            return "100%" not in out
        except Exception as e:
            self.dut.log.debug(e)
            return False

    def _verify_device_address(self, ipv4=True, ipv6=True, timeout=15):
        """Verify device get assign address on wireless interface."""
        current_time = time.time()
        while time.time() < current_time + timeout:
            try:
                if ipv4:
                    ipv4_addr = self.dut.droid.connectivityGetIPv4Addresses(WLAN)[0]
                    self.dut.log.info("ipv4_address is %s" % ipv4_addr)
                if ipv6:
                    ipv6_addr = self.dut.droid.connectivityGetIPv6Addresses(WLAN)[0]
                    self.dut.log.info("ipv6_address is %s" % ipv6_addr)
                return True
            except:
                time.sleep(1)
        return False

    def test_ipv4_ipv6_network(self):
        """Verify device can get both ipv4 ipv6 address."""
        wutils.connect_to_wifi_network(self.dut, self.wifi_network)

        asserts.assert_true(self._verify_device_address(),
                            "Fail to get ipv4/ipv6 address.")
        asserts.assert_true(self._verify_ping(), "Fail to ping on ipv4.")
        asserts.assert_true(self._verify_ping("6"), "Fail to ping on ipv6.")

    def test_ipv6_only_prefer_option(self):
        """Verify DUT can only get ipv6 address and ping out."""
        self.openwrt.network_setting.add_ipv6_prefer_option()
        wutils.connect_to_wifi_network(self.dut, self.wifi_network)

        asserts.assert_true(self._verify_device_address(ipv4=False),
                            "Fail to get ipv6 address.")
        asserts.assert_false(self._verify_ping(),
                             "Should not ping on success on ipv4.")
        asserts.assert_true(self._verify_ping("6"),
                            "Fail to ping on ipv6.")
