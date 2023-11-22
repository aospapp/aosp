#!/usr/bin/env python3.4
#
#   Copyright 2017 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the 'License');
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an 'AS IS' BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

import logging
import os
import time
from acts import base_test
from acts.controllers.ap_lib import bridge_interface as bi
from acts.controllers.ap_lib import hostapd_constants as hc
from acts.test_decorators import test_tracker_info
from acts.test_utils.wifi import wifi_test_utils as wutils
from acts.test_utils.wifi import wifi_power_test_utils as wputils
from acts.test_utils.wifi.WifiBaseTest import WifiBaseTest
import acts.controllers.iperf_server as ipf


class PowertrafficTest(base_test.BaseTestClass):
    def __init__(self, controllers):

        WifiBaseTest.__init__(self, controllers)
        self.tests = ('test_screenoff_iperf_2g_highrssi',
                      'test_screenoff_iperf_2g_mediumrssi',
                      'test_screenoff_iperf_2g_lowrssi',
                      'test_screenoff_iperf_5g_highrssi',
                      'test_screenoff_iperf_5g_mediumrssi',
                      'test_screenoff_iperf_5g_lowrssi')

    def setup_class(self):

        self.log = logging.getLogger()
        self.dut = self.android_devices[0]
        req_params = ['main_network', 'traffictest_params']
        self.unpack_userparams(req_params)
        self.unpack_testparams(self.traffictest_params)
        self.num_atten = self.attenuators[0].instrument.num_atten
        self.mon_data_path = os.path.join(self.log_path, 'Monsoon')
        self.mon_duration = self.iperf_duration - 10
        self.mon = self.monsoons[0]
        self.mon.set_max_current(8.0)
        self.mon.set_voltage(4.2)
        self.mon.attach_device(self.dut)
        self.mon_info = wputils.create_monsoon_info(self)
        self.iperf_server = self.iperf_servers[0]
        self.access_point = self.access_points[0]
        self.pkt_sender = self.packet_senders[0]

    def teardown_test(self):
        self.iperf_server.stop()
        self.access_point.close()

    def unpack_testparams(self, bulk_params):
        """Unpack all the test specific parameters.

        Args:
            bulk_params: dict with all test specific params in the config file
        """
        for key in bulk_params.keys():
            setattr(self, key, bulk_params[key])

    def iperf_power_test_func(self, screen_status, band):
        """Test function for iperf power measurement at different RSSI level.

        Args:
            screen_status: screen ON or OFF
            band: desired band for AP to operate on
        """
        wputils.dut_rockbottom(self.dut)
        wutils.wifi_toggle_state(self.dut, True)

        # Set up the AP
        network = self.main_network[band]
        channel = network['channel']
        configs = self.access_point.generate_bridge_configs(channel)
        brconfigs = bi.BridgeInterfaceConfigs(configs[0], configs[1],
                                              configs[2])
        self.access_point.bridge.startup(brconfigs)
        wputils.ap_setup(self.access_point, network)

        # Wait for DHCP on the ethernet port and get IP as Iperf server address
        # Time out in 60 seconds if not getting DHCP address
        iface_eth = self.pkt_sender.interface
        self.iperf_server_address = wputils.wait_for_dhcp(iface_eth)

        # Set attenuator to desired level
        self.log.info('Set attenuation to desired RSSI level')
        for i in range(self.num_atten):
            attenuation = self.atten_level[self.current_test_name][i]
            self.attenuators[i].set_atten(attenuation)

        # Connect the phone to the AP
        wutils.wifi_connect(self.dut, network)
        time.sleep(5)
        if screen_status == 'OFF':
            self.dut.droid.goToSleepNow()
        RSSI = wputils.get_wifi_rssi(self.dut)

        # Run IPERF session
        iperf_args = '-i 1 -t %d > /dev/null' % self.iperf_duration
        self.iperf_server.start()
        wputils.run_iperf_client_nonblocking(
            self.dut, self.iperf_server_address, iperf_args)

        # Collect power data and plot
        file_path, avg_current = wputils.monsoon_data_collect_save(
            self.dut, self.mon_info, self.current_test_name, self.bug_report)
        iperf_result = ipf.IPerfResult(self.iperf_server.log_files[-1])

        # Monsoon Power data plot with IPerf throughput information
        tag = '_RSSI_{0:d}dBm_Throughput_{1:.2f}Mbps'.format(
            RSSI, (iperf_result.avg_receive_rate * 8))
        wputils.monsoon_data_plot(self.mon_info, file_path, tag)

        # Bring down bridge interface
        self.access_point.bridge.teardown(brconfigs)

        # Bring down the AP object
        self.access_point.close()

        # Pass and fail check
        wputils.pass_fail_check(self, avg_current)

    # Test cases
    @test_tracker_info(uuid='43d9b146-3547-4a27-9d79-c9341c32ccda')
    def test_screenoff_iperf_2g_highrssi(self):

        self.iperf_power_test_func('OFF', hc.BAND_2G)

    @test_tracker_info(uuid='f00a868b-c8b1-4b36-8136-b39b5c2396a7')
    def test_screenoff_iperf_2g_mediumrssi(self):

        self.iperf_power_test_func('OFF', hc.BAND_2G)

    @test_tracker_info(uuid='cd0c37ac-23fe-4dd1-9130-ccb2dfa71020')
    def test_screenoff_iperf_2g_lowrssi(self):

        self.iperf_power_test_func('OFF', hc.BAND_2G)

    @test_tracker_info(uuid='f9173d39-b46d-4d80-a5a5-7966f5eed9de')
    def test_screenoff_iperf_5g_highrssi(self):

        self.iperf_power_test_func('OFF', hc.BAND_5G)

    @test_tracker_info(uuid='cf77e1dc-30bc-4df9-88be-408f1fddc24f')
    def test_screenoff_iperf_5g_mediumrssi(self):

        self.iperf_power_test_func('OFF', hc.BAND_5G)

    @test_tracker_info(uuid='48f91745-22dc-47c9-ace6-c2719df651d6')
    def test_screenoff_iperf_5g_lowrssi(self):

        self.iperf_power_test_func('OFF', hc.BAND_5G)
