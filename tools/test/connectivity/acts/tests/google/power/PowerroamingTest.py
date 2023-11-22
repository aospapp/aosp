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
from acts.controllers.ap_lib import hostapd_constants as hc
from acts.test_decorators import test_tracker_info
from acts.test_utils.wifi import wifi_constants as wc
from acts.test_utils.wifi import wifi_test_utils as wutils
from acts.test_utils.wifi import wifi_power_test_utils as wputils


class PowerroamingTest(base_test.BaseTestClass):
    def __init__(self, controllers):

        base_test.BaseTestClass.__init__(self, controllers)
        self.tests = ('test_screenoff_roaming', 'test_screenoff_fastroaming',
                      'test_screenon_toggle_between_AP',
                      'test_screenoff_toggle_between_AP',
                      'test_screenoff_wifi_wedge')

    def setup_class(self):

        self.log = logging.getLogger()
        self.dut = self.android_devices[0]
        self.access_point_main = self.access_points[0]
        self.access_point_aux = self.access_points[1]
        req_params = ('main_network', 'aux_network', 'roamingtest_params')
        self.unpack_userparams(req_params)
        self.unpack_testparams(self.roamingtest_params)
        self.mon_data_path = os.path.join(self.log_path, 'Monsoon')
        self.mon = self.monsoons[0]
        self.mon.set_max_current(8.0)
        self.mon.set_voltage(4.2)
        self.mon_duration_all = self.mon_duration
        self.mon.attach_device(self.dut)
        self.mon_info = wputils.create_monsoon_info(self)
        self.num_atten = self.attenuators[0].instrument.num_atten

    def teardown_class(self):

        self.mon.usb('on')

    def unpack_testparams(self, bulk_params):
        """Unpack all the test specific parameters.

        Args:
            bulk_params: dict with all test specific params in the config file
        """
        for key in bulk_params.keys():
            setattr(self, key, bulk_params[key])

    def ap_close_all(self):
        """Close all the AP controller objects in roaming tests.

        """
        for ap in self.access_points:
            ap.close()

    # Test cases
    @test_tracker_info(uuid='392622d3-0c5c-4767-afa2-abfb2058b0b8')
    def test_screenoff_roaming(self):
        """Test roaming power consumption with screen off.
        Change the attenuation level to trigger roaming between two APs

        """
        # Setup both APs
        network_main = self.main_network[hc.BAND_2G]
        wputils.ap_setup(self.access_point_main, network_main)
        network_aux = self.aux_network[hc.BAND_2G]
        wputils.ap_setup(self.access_point_aux, network_aux)
        # Initialize the dut to rock-bottom state
        wputils.dut_rockbottom(self.dut)
        wutils.wifi_toggle_state(self.dut, True)
        # Set attenuator and add two networks to the phone
        self.log.info('Set attenuation to connect device to both APs')
        [
            self.attenuators[i].set_atten(self.atten_level['zero_atten'][i])
            for i in range(self.num_atten)
        ]
        wutils.wifi_connect(self.dut, network_aux)
        time.sleep(5)
        wutils.wifi_connect(self.dut, network_main)
        self.dut.droid.goToSleepNow()
        time.sleep(5)
        # Set attenuator to trigger roaming
        self.dut.log.info('Trigger roaming now')
        [
            self.attenuators[i].set_atten(
                self.atten_level[self.current_test_name][i])
            for i in range(self.num_atten)
        ]
        file_path, avg_current = wputils.monsoon_data_collect_save(
            self.dut, self.mon_info, self.current_test_name, self.bug_report)
        wputils.monsoon_data_plot(self.mon_info, file_path)
        # Close AP controller
        self.ap_close_all()
        # Path fail check
        wputils.pass_fail_check(self, avg_current)

    @test_tracker_info(uuid='2fec5208-043a-410a-8fd2-6784d70a3587')
    def test_screenoff_fastroaming(self):

        # Initialize the dut to rock-bottom state
        wputils.dut_rockbottom(self.dut)
        wutils.wifi_toggle_state(self.dut, True)
        # Setup the aux AP
        network_main = self.main_network[hc.BAND_2G]
        network_aux = self.aux_network[hc.BAND_2G]
        # Set the same SSID for the AUX AP for fastroaming purpose
        network_aux[wc.SSID] = network_main[wc.SSID]
        wputils.ap_setup(self.access_point_aux, network_aux)
        # Set attenuator and add two networks to the phone
        self.log.info('Set attenuation to connect device to the aux AP')
        [
            self.attenuators[i].set_atten(self.atten_level[wc.AP_MAIN][i])
            for i in range(self.num_atten)
        ]
        wutils.wifi_connect(self.dut, network_aux)
        time.sleep(5)
        # Setup the main AP
        wputils.ap_setup(self.access_point_main, network_main)
        # Set attenuator to connect the phone to main AP
        self.log.info('Set attenuation to connect device to the main AP')
        [
            self.attenuators[i].set_atten(self.atten_level[wc.AP_MAIN][i])
            for i in range(self.num_atten)
        ]
        wutils.wifi_connect(self.dut, network_main)
        time.sleep(5)
        self.dut.droid.goToSleepNow()
        # Trigger fastroaming
        self.dut.log.info('Trigger fastroaming now')
        [
            self.attenuators[i].set_atten(self.atten_level[wc.AP_MAIN][i])
            for i in range(self.num_atten)
        ]
        file_path, avg_current = wputils.monsoon_data_collect_save(
            self.dut, self.mon_info, self.current_test_name, self.bug_report)
        wputils.monsoon_data_plot(self.mon_info, file_path)
        # Close AP controller
        self.ap_close_all()
        # Path fail check
        wputils.pass_fail_check(self, avg_current)

    @test_tracker_info(uuid='a0459b7c-74ce-4adb-8e55-c5365bc625eb')
    def test_screenoff_toggle_between_AP(self):

        # Setup both APs
        network_main = self.main_network[hc.BAND_2G]
        wputils.ap_setup(self.access_point_main, network_main)
        network_aux = self.aux_network[hc.BAND_2G]
        wputils.ap_setup(self.access_point_aux, network_aux)
        # Initialize the dut to rock-bottom state
        wputils.dut_rockbottom(self.dut)
        wutils.wifi_toggle_state(self.dut, True)
        self.mon_info['duration'] = self.toggle_interval
        self.dut.droid.goToSleepNow()
        time.sleep(5)
        self.log.info('Set attenuation to connect device to both APs')
        [
            self.attenuators[i].set_atten(
                self.atten_level[self.current_test_name][i])
            for i in range(self.num_atten)
        ]
        # Toggle between two networks
        for i in range(self.toggle_times):
            self.dut.log.info('Connecting to %s' % network_main[wc.SSID])
            self.dut.droid.wifiConnect(network_main)
            file_path, avg_current = wputils.monsoon_data_collect_save(
                self.dut, self.mon_info, self.current_test_name, 0)
            self.dut.log.info('Connecting to %s' % network_aux[wc.SSID])
            self.dut.droid.wifiConnect(network_aux)
            file_path, avg_current = wputils.monsoon_data_collect_save(
                self.dut, self.mon_info, self.current_test_name, 0)
        wputils.monsoon_data_plot(self.mon_info, file_path)
        # Close AP controller
        self.ap_close_all()
        # Path fail check
        wputils.pass_fail_check(self, avg_current)

    @test_tracker_info(uuid='e5ff95c0-b17e-425c-a903-821ba555a9b9')
    def test_screenon_toggle_between_AP(self):

        # Setup both APs
        network_main = self.main_network[hc.BAND_5G]
        wputils.ap_setup(self.access_point_main, network_main)
        network_aux = self.aux_network[hc.BAND_5G]
        wputils.ap_setup(self.access_point_aux, network_aux)
        # Initialize the dut to rock-bottom state
        wputils.dut_rockbottom(self.dut)
        wutils.wifi_toggle_state(self.dut, True)
        self.mon_info['duration'] = self.toggle_interval
        self.log.info('Set attenuation to connect device to both APs')
        [
            self.attenuators[i].set_atten(
                self.atten_level[self.current_test_name][i])
            for i in range(self.num_atten)
        ]
        # Toggle between two networks
        for i in range(self.toggle_times):
            self.dut.log.info('Connecting to %s' % network_main[wc.SSID])
            self.dut.droid.wifiConnect(network_main)
            file_path, avg_current = wputils.monsoon_data_collect_save(
                self.dut, self.mon_info, self.current_test_name, 0)
            self.dut.log.info('Connecting to %s' % network_aux[wc.SSID])
            self.dut.droid.wifiConnect(network_aux)
            file_path, avg_current = wputils.monsoon_data_collect_save(
                self.dut, self.mon_info, self.current_test_name, 0)
        wputils.monsoon_data_plot(self.mon_info, file_path)
        # Close AP controller
        self.ap_close_all()
        # Path fail check
        wputils.pass_fail_check(self, avg_current)

    @test_tracker_info(uuid='a16ae337-326f-4d09-990f-42232c3c0dc4')
    def test_screenoff_wifi_wedge(self):

        # Setup both APs
        network_main = self.main_network[hc.BAND_2G]
        wputils.ap_setup(self.access_point_main, network_main)
        network_aux = self.aux_network[hc.BAND_2G]
        wputils.ap_setup(self.access_point_aux, network_aux)
        # Initialize the dut to rock-bottom state
        wputils.dut_rockbottom(self.dut)
        wutils.wifi_toggle_state(self.dut, True)
        # Set attenuator to connect phone to both networks
        self.log.info('Set attenuation to connect device to both APs')
        [
            self.attenuators[i].set_atten(self.atten_level['zero_atten'][i])
            for i in range(self.num_atten)
        ]
        wutils.wifi_connect(self.dut, network_main)
        wutils.wifi_connect(self.dut, network_aux)
        self.log.info('Forget network {}'.format(network_aux[wc.SSID]))
        wutils.wifi_forget_network(self.dut, network_aux[wc.SSID])
        self.log.info('Set attenuation to trigger wedge condition')
        [
            self.attenuators[i].set_atten(
                self.atten_level[self.current_test_name][i])
            for i in range(self.num_atten)
        ]
        self.dut.droid.goToSleepNow()
        file_path, avg_current = wputils.monsoon_data_collect_save(
            self.dut, self.mon_info, self.current_test_name, self.bug_report)
        wputils.monsoon_data_plot(self.mon_info, file_path)
        # Close AP controller
        self.ap_close_all()
        # Path fail check
        wputils.pass_fail_check(self, avg_current)
