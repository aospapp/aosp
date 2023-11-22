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
from acts.test_utils.wifi import wifi_test_utils as wutils
from acts.test_utils.wifi import wifi_power_test_utils as wputils

UNLOCK_SCREEN = 'input keyevent 82'


class PowerscanTest(base_test.BaseTestClass):
    def __init__(self, controllers):

        base_test.BaseTestClass.__init__(self, controllers)
        self.tests = ('test_single_shot_scan_2g_highRSSI',
                      'test_single_shot_scan_2g_lowRSSI',
                      'test_single_shot_scan_5g_highRSSI',
                      'test_single_shot_scan_5g_lowRSSI',
                      'test_background_scan'
                      'test_wifi_scan_2g', 'test_wifi_scan_5g',
                      'test_scan_wifidisconnected_turnonscreen',
                      'test_scan_wificonnected_turnonscreen',
                      'test_scan_screenoff_below_rssi_threshold',
                      'test_scan_screenoff_lost_wificonnection')

    def setup_class(self):

        self.log = logging.getLogger()
        self.dut = self.android_devices[0]
        self.access_point = self.access_points[0]
        req_params = ('main_network', 'scantest_params')
        self.unpack_userparams(req_params)
        self.unpack_testparams(self.scantest_params)
        self.mon_data_path = os.path.join(self.log_path, 'Monsoon')
        self.mon = self.monsoons[0]
        self.mon.set_max_current(8.0)
        self.mon.set_voltage(4.2)
        self.mon.attach_device(self.dut)
        self.mon_info = wputils.create_monsoon_info(self)
        self.num_atten = self.attenuators[0].instrument.num_atten

    def unpack_testparams(self, bulk_params):
        """Unpack all the test specific parameters.

        Args:
            bulk_params: dict with all test specific params in the config file
        """
        for key in bulk_params.keys():
            setattr(self, key, bulk_params[key])

    def setup_test(self):

        self.SINGLE_SHOT_SCAN = (
            'am instrument -w -r  -e min_scan_count \"700\"'
            ' -e WifiScanTest-testWifiSingleShotScan %d'
            ' -e class com.google.android.platform.powertests.'
            'WifiScanTest#testWifiSingleShotScan'
            ' com.google.android.platform.powertests/'
            'android.test.InstrumentationTestRunner > /dev/null &' %
            (self.mon_duration + self.mon_offset + 10))
        self.BACKGROUND_SCAN = (
            'am instrument -w -r -e min_scan_count \"1\" -e '
            'WifiScanTest-testWifiBackgroundScan %d -e class '
            'com.google.android.platform.powertests.WifiScan'
            'Test#testWifiBackgroundScan com.google.android.'
            'platform.powertests/android.test.Instrumentation'
            'TestRunner > /dev/null &' %
            (self.mon_duration + self.mon_offset + 10))
        self.WIFI_SCAN = (
            'am instrument -w -r -e min_scan_count \"1\" -e '
            'WifiScanTest-testWifiScan %d -e class '
            'com.google.android.platform.powertests.WifiScanTest#'
            'testWifiScan com.google.android.platform.powertests/'
            'android.test.InstrumentationTestRunner > /dev/null &' %
            (self.mon_duration + self.mon_offset + 10))

    def teardown_class(self):

        self.mon.usb('on')

    def powrapk_scan_test_func(self, scan_command):
        """Test function for power.apk triggered scans.
        Args:
            scan_command: the adb shell command to trigger scans

        """
        self.mon_info['offset'] == 0
        # Initialize the dut to rock-bottom state
        wputils.dut_rockbottom(self.dut)
        wutils.wifi_toggle_state(self.dut, True)
        self.log.info('Wait for {} seconds'.format(self.settle_wait_time))
        time.sleep(self.settle_wait_time)
        self.log.info('Running power apk command to trigger scans')
        self.dut.adb.shell_nb(scan_command)
        self.dut.droid.goToSleepNow()
        # Collect power data and plot
        file_path, avg_current = wputils.monsoon_data_collect_save(
            self.dut, self.mon_info, self.current_test_name, self.bug_report)
        wputils.monsoon_data_plot(self.mon_info, file_path)
        # Close AP controller
        self.access_point.close()
        # Path fail check
        wputils.pass_fail_check(self, avg_current)

    # Test cases
    @test_tracker_info(uuid='e5539b01-e208-43c6-bebf-6f1e73d8d8cb')
    def test_single_shot_scan_2g_highRSSI(self):

        network = self.main_network[hc.BAND_2G]
        wputils.ap_setup(self.access_point, network)
        self.log.info('Set attenuation to get high RSSI at 2g')
        [
            self.attenuators[i].set_atten(
                self.atten_level[self.current_test_name][i])
            for i in range(self.num_atten)
        ]
        self.powrapk_scan_test_func(self.SINGLE_SHOT_SCAN)

    @test_tracker_info(uuid='14c5a762-95bc-40ea-9fd4-27126df7d86c')
    def test_single_shot_scan_2g_lowRSSI(self):

        network = self.main_network[hc.BAND_2G]
        wputils.ap_setup(self.access_point, network)
        self.log.info('Set attenuation to get low RSSI at 2g')
        [
            self.attenuators[i].set_atten(
                self.atten_level[self.current_test_name][i])
            for i in range(self.num_atten)
        ]
        self.powrapk_scan_test_func(self.SINGLE_SHOT_SCAN)

    @test_tracker_info(uuid='a6506600-c567-43b5-9c25-86b505099b97')
    def test_single_shot_scan_2g_noAP(self):

        network = self.main_network[hc.BAND_2G]
        wputils.ap_setup(self.access_point, network)
        self.log.info('Set attenuation so all AP is out of reach ')
        [
            self.attenuators[i].set_atten(
                self.atten_level[self.current_test_name][i])
            for i in range(self.num_atten)
        ]
        self.powrapk_scan_test_func(self.SINGLE_SHOT_SCAN)

    @test_tracker_info(uuid='1a458248-1159-4c8e-a39f-92fc9e69c4dd')
    def test_single_shot_scan_5g_highRSSI(self):

        network = self.main_network[hc.BAND_5G]
        wputils.ap_setup(self.access_point, network)
        self.log.info('Set attenuation to get high RSSI at 5g')
        [
            self.attenuators[i].set_atten(
                self.atten_level[self.current_test_name][i])
            for i in range(self.num_atten)
        ]
        self.powrapk_scan_test_func(self.SINGLE_SHOT_SCAN)

    @test_tracker_info(uuid='bd4da426-a621-4131-9f89-6e5a77f321d2')
    def test_single_shot_scan_5g_lowRSSI(self):

        network = self.main_network[hc.BAND_5G]
        wputils.ap_setup(self.access_point, network)
        self.log.info('Set attenuation to get low RSSI at 5g')
        [
            self.attenuators[i].set_atten(
                self.atten_level[self.current_test_name][i])
            for i in range(self.num_atten)
        ]
        self.powrapk_scan_test_func(self.SINGLE_SHOT_SCAN)

    @test_tracker_info(uuid='288b3add-8925-4803-81c0-53debf157ffc')
    def test_single_shot_scan_5g_noAP(self):

        network = self.main_network[hc.BAND_5G]
        wputils.ap_setup(self.access_point, network)
        self.log.info('Set attenuation so all AP is out of reach ')
        [
            self.attenuators[i].set_atten(
                self.atten_level[self.current_test_name][i])
            for i in range(self.num_atten)
        ]
        self.powrapk_scan_test_func(self.SINGLE_SHOT_SCAN)

    @test_tracker_info(uuid='f401c66c-e515-4f51-8ef2-2a03470d8ff2')
    def test_background_scan(self):

        network = self.main_network[hc.BAND_2G]
        wputils.ap_setup(self.access_point, network)
        self.powrapk_scan_test_func(self.BACKGROUND_SCAN)

    @test_tracker_info(uuid='fe38c1c7-937c-42c0-9381-98356639df8f')
    def test_wifi_scan_2g(self):

        network = self.main_network[hc.BAND_2G]
        wputils.ap_setup(self.access_point, network)
        [
            self.attenuators[i].set_atten(
                self.atten_level[self.current_test_name][i])
            for i in range(self.num_atten)
        ]
        self.powrapk_scan_test_func(self.WIFI_SCAN)

    @test_tracker_info(uuid='8eedefd1-3a08-4ac2-ba55-5eb438def3d4')
    def test_wifi_scan_5g(self):

        network = self.main_network[hc.BAND_2G]
        wputils.ap_setup(self.access_point, network)
        [
            self.attenuators[i].set_atten(
                self.atten_level[self.current_test_name][i])
            for i in range(self.num_atten)
        ]
        self.powrapk_scan_test_func(self.WIFI_SCAN)

    @test_tracker_info(uuid='ff5ea952-ee31-4968-a190-82935ce7a8cb')
    def test_scan_wifidisconnected_turnonscreen(self):

        # Initialize the dut to rock-bottom state
        wputils.dut_rockbottom(self.dut)
        wutils.wifi_toggle_state(self.dut, True)
        self.dut.droid.goToSleepNow()
        self.log.info('Screen is OFF')
        time.sleep(5)
        self.dut.droid.wakeUpNow()
        self.log.info('Now turn on screen to trigger scans')
        self.dut.adb.shell(UNLOCK_SCREEN)
        file_path, avg_current = wputils.monsoon_data_collect_save(
            self.dut, self.mon_info, self.current_test_name, self.bug_report)
        wputils.monsoon_data_plot(self.mon_info, file_path)
        wputils.pass_fail_check(self, avg_current)

    @test_tracker_info(uuid='9a836e5b-8128-4dd2-8e96-e79177810bdd')
    def test_scan_wificonnected_turnonscreen(self):

        network = self.main_network[hc.BAND_2G]
        wputils.ap_setup(self.access_point, network)
        # Initialize the dut to rock-bottom state
        wputils.dut_rockbottom(self.dut)
        wutils.wifi_toggle_state(self.dut, True)
        # Set attenuators to connect main AP
        [
            self.attenuators[i].set_atten(
                self.atten_level[self.current_test_name][i])
            for i in range(self.num_atten)
        ]
        wutils.wifi_connect(self.dut, network)
        time.sleep(10)
        self.dut.droid.goToSleepNow()
        self.log.info('Screen is OFF')
        time.sleep(5)
        self.dut.droid.wakeUpNow()
        self.log.info('Now turn on screen to trigger scans')
        self.dut.adb.shell(UNLOCK_SCREEN)
        file_path, avg_current = wputils.monsoon_data_collect_save(
            self.dut, self.mon_info, self.current_test_name, self.bug_report)
        wputils.monsoon_data_plot(self.mon_info, file_path)
        # Close AP controller
        self.access_point.close()
        # Path fail check
        wputils.pass_fail_check(self, avg_current)

    @test_tracker_info(uuid='51e3c4f1-742b-45af-afd5-ae3552a03272')
    def test_scan_screenoff_below_rssi_threshold(self):

        network = self.main_network[hc.BAND_2G]
        wputils.ap_setup(self.access_point, network)
        # Initialize the dut to rock-bottom state
        wputils.dut_rockbottom(self.dut)
        wutils.wifi_toggle_state(self.dut, True)
        # Set attenuator and add main network to the phone
        self.log.info('Set attenuation so device connection has medium RSSI')
        [
            self.attenuators[i].set_atten(self.atten_level['zero_atten'][i])
            for i in range(self.num_atten)
        ]
        wutils.wifi_connect(self.dut, network)
        self.dut.droid.goToSleepNow()
        time.sleep(20)
        # Set attenuator to make RSSI below threshold
        self.log.info('Set attenuation to drop RSSI below threhold')
        [
            self.attenuators[i].set_atten(
                self.atten_level[self.current_test_name][i])
            for i in range(self.num_atten)
        ]
        file_path, avg_current = wputils.monsoon_data_collect_save(
            self.dut, self.mon_info, self.current_test_name, self.bug_report)
        wputils.monsoon_data_plot(self.mon_info, file_path)
        # Close AP controller
        self.access_point.close()
        # Path fail check
        wputils.pass_fail_check(self, avg_current)

    @test_tracker_info(uuid='a16ae337-326f-4d09-990f-42232c3c0dc4')
    def test_scan_screenoff_lost_wificonnection(self):

        network = self.main_network[hc.BAND_5G]
        wputils.ap_setup(self.access_point, network)
        # Initialize the dut to rock-bottom state
        wputils.dut_rockbottom(self.dut)
        wutils.wifi_toggle_state(self.dut, True)
        # Set attenuator and add main network to the phone
        self.log.info('Set attenuation so device connection has medium RSSI')
        [
            self.attenuators[i].set_atten(self.atten_level['zero_atten'][i])
            for i in range(self.num_atten)
        ]
        wutils.wifi_connect(self.dut, network)
        self.dut.droid.goToSleepNow()
        time.sleep(5)
        # Set attenuator to make RSSI below threshold
        self.log.info('Set attenuation so device loses connection')
        [
            self.attenuators[i].set_atten(
                self.atten_level[self.current_test_name][i])
            for i in range(self.num_atten)
        ]
        file_path, avg_current = wputils.monsoon_data_collect_save(
            self.dut, self.mon_info, self.current_test_name, self.bug_report)
        wputils.monsoon_data_plot(self.mon_info, file_path)
        # Close AP controller
        self.access_point.close()
        # Path fail check
        wputils.pass_fail_check(self, avg_current)
