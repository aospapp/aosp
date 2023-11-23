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

import random

from acts import asserts
from acts.controllers.openwrt_ap import MOBLY_CONTROLLER_CONFIG_NAME as OPENWRT
from acts_contrib.test_utils.wifi import wifi_test_utils as wutils
from acts_contrib.test_utils.wifi.WifiBaseTest import WifiBaseTest
from scapy.all import rdpcap, DNSRR, DNSQR, IP, IPv6


WLAN = "wlan0"
PING_ADDR = "google.com"


class DNSTest(WifiBaseTest):
    """DNS related test for Android."""

    def setup_class(self):
        self.dut = self.android_devices[0]
        wutils.wifi_test_device_init(self.dut)

        req_params = []
        opt_param = ["wifi_network", "configure_OpenWrt"]
        self.unpack_userparams(
            req_param_names=req_params, opt_param_names=opt_param)

        asserts.assert_true(OPENWRT in self.user_params,
                            "OpenWrtAP is not in testbed.")
        self.openwrt = self.access_points[0]
        if hasattr(self, "configure_OpenWrt") and self.configure_OpenWrt == "skip":
            self.dut.log.info("Skip configure Wifi interface due to config setup.")
        else:
            self.configure_openwrt_ap_and_start(wpa_network=True)
            self.wifi_network = self.openwrt.get_wifi_network()

        asserts.assert_true(self.openwrt.verify_wifi_status(),
                            "OpenWrt Wifi interface is not ready.")

    def teardown_class(self):
        """Reset wifi to make sure VPN tears down cleanly."""
        wutils.reset_wifi(self.dut)

    def teardown_test(self):
        """Reset wifi to make sure VPN tears down cleanly."""
        wutils.reset_wifi(self.dut)

    def ping(self, addr, ignore_status=True, timeout=60):
        """Start a ping from DUT and return ping result.

        Args:
            addr: Address to ping.
            ignore_status: ignore non zero return.
            timeout: cmd timeout.
        Returns:
            Boolean for ping result.
        """
        return "100%" not in self.dut.adb.shell("ping -c 1 %s" % addr,
                                                ignore_status=ignore_status,
                                                timeout=timeout)

    def generate_query_qname(self):
        """Return a random query name."""
        return "%s-ds.metric.gstatic.com" % random.randint(0, 99999999)

    def test_dns_query(self):
        # Setup environment
        wutils.connect_to_wifi_network(self.dut, self.wifi_network)
        # Start tcpdump on OpenWrt
        remote_pcap_path = self.openwrt.network_setting.start_tcpdump(self.test_name)
        # Generate query name
        test_qname = self.generate_query_qname()
        self.dut.log.info("Test query name = %s" % test_qname)
        # Start send a query
        ping_result = self.ping(test_qname)
        local_pcap_path = self.openwrt.network_setting.stop_tcpdump(remote_pcap_path,
                                                                    self.dut.device_log_path)
        # Check DNSRR.rrname in tcpdump to verify DNS response
        packets = rdpcap(local_pcap_path)
        self.dut.log.info("pcap file path : %s" % local_pcap_path)
        pkt_count = 0
        for pkt in packets:
            if pkt.haslayer(DNSRR) and pkt[DNSRR].rrname.decode().strip(".") == test_qname:
                pkt_count = pkt_count + 1
        self.dut.log.info("DNS query response count : %s" % pkt_count)
        if not ping_result:
            asserts.assert_true(pkt_count > 0,
                                "Did not find match standard query response in tcpdump.")
        asserts.assert_true(ping_result, "Device ping fail.")

    def test_dns_query_retransmit(self):
        # Setup environment
        wutils.connect_to_wifi_network(self.dut, self.wifi_network)
        self.openwrt.network_setting.block_dns_response()
        # Start tcpdump on OpenWrt
        remote_pcap_path = self.openwrt.network_setting.start_tcpdump(self.test_name)
        # Generate query name
        test_qname = self.generate_query_qname()
        self.dut.log.info("Test query name = %s" % test_qname)
        # Start send a query
        self.ping(test_qname)
        local_pcap_path = self.openwrt.network_setting.stop_tcpdump(remote_pcap_path,
                                                                    self.dut.device_log_path)
        # Check DNSQR.qname in tcpdump to verify device retransmit the query
        packets = rdpcap(local_pcap_path)
        self.dut.log.info("pcap file path : %s" % local_pcap_path)
        pkt_count = 0
        pkt6_count = 0
        for pkt in packets:
            if pkt.haslayer(DNSQR) and pkt[DNSQR].qname.decode().strip(".") == test_qname:
                if pkt.haslayer(IP):
                    pkt_count = pkt_count + 1
                if pkt.haslayer(IPv6):
                    pkt6_count = pkt6_count + 1
        self.dut.log.info("IPv4 DNS query count : %s" % pkt_count)
        self.dut.log.info("IPv6 DNS query count : %s" % pkt6_count)
        self.openwrt.network_setting.unblock_dns_response()
        asserts.assert_true(pkt_count >= 2 or pkt6_count >= 2,
                            "Did not find match standard query in tcpdump.")
