#!/usr/bin/env python3.4
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

import json
import logging
import math
import os
import time
from acts import asserts
from acts import base_test
from acts import utils
from acts.controllers import iperf_server as ipf
from acts.metrics.loggers.blackbox import BlackboxMetricLogger
from acts.test_utils.wifi import wifi_power_test_utils as wputils
from acts.test_utils.wifi import wifi_retail_ap as retail_ap
from acts.test_utils.wifi import wifi_test_utils as wutils

TEST_TIMEOUT = 10
SHORT_SLEEP = 1
MED_SLEEP = 6


class WifiThroughputStabilityTest(base_test.BaseTestClass):
    """Class to test WiFi throughput stability.

    This class tests throughput stability and identifies cases where throughput
    fluctuates over time. The class setups up the AP, configures and connects
    the phone, and runs iperf throughput test at several attenuations For an
    example config file to run this test class see
    example_connectivity_performance_ap_sta.json.
    """

    def __init__(self, controllers):
        base_test.BaseTestClass.__init__(self, controllers)
        # Define metrics to be uploaded to BlackBox
        self.min_throughput_metric = BlackboxMetricLogger.for_test_case(
            metric_name='min_throughput')
        self.avg_throughput_metric = BlackboxMetricLogger.for_test_case(
            metric_name='avg_throughput')
        self.std_dev_percent_metric = BlackboxMetricLogger.for_test_case(
            metric_name='std_dev_percent')

        # Generate test cases
        modes = [(6, "VHT20"), (36, "VHT20"), (36, "VHT40"), (36, "VHT80"),
                 (149, "VHT20"), (149, "VHT40"), (149, "VHT80")]
        traffic_types = [("TCP", "DL"), ("TCP", "UL"), ("UDP", "DL"), ("UDP",
                                                                       "UL")]
        signal_levels = ["high", "low"]
        self.generate_test_cases(modes, traffic_types, signal_levels)

    def setup_class(self):
        self.dut = self.android_devices[0]
        req_params = [
            "throughput_stability_test_params", "testbed_params",
            "main_network", "RetailAccessPoints"
        ]
        opt_params = ["golden_files_list"]
        self.unpack_userparams(req_params, opt_params)
        self.test_params = self.throughput_stability_test_params
        self.num_atten = self.attenuators[0].instrument.num_atten
        self.iperf_server = self.iperf_servers[0]
        self.iperf_client = self.iperf_clients[0]
        self.access_points = retail_ap.create(self.RetailAccessPoints)
        self.access_point = self.access_points[0]
        self.log_path = os.path.join(logging.log_path, "test_results")
        utils.create_dir(self.log_path)
        self.log.info("Access Point Configuration: {}".format(
            self.access_point.ap_settings))
        if not hasattr(self, "golden_files_list"):
            self.golden_files_list = [
                os.path.join(self.testbed_params["golden_results_path"],
                             file) for file in os.listdir(
                                 self.testbed_params["golden_results_path"])
            ]

    def teardown_test(self):
        self.iperf_server.stop()

    def pass_fail_check(self, test_result_dict):
        """Check the test result and decide if it passed or failed.

        Checks the throughput stability test's PASS/FAIL criteria based on
        minimum instantaneous throughput, and standard deviation.

        Args:
            test_result_dict: dict containing attenuation, throughput and other
            meta data
        """
        #TODO(@oelayach): Check throughput vs RvR golden file
        avg_throughput = test_result_dict["iperf_results"]["avg_throughput"]
        min_throughput = test_result_dict["iperf_results"]["min_throughput"]
        std_dev_percent = (
            test_result_dict["iperf_results"]["std_deviation"] /
            test_result_dict["iperf_results"]["avg_throughput"]) * 100
        # Set blackbox metrics
        self.avg_throughput_metric.metric_value = avg_throughput
        self.min_throughput_metric.metric_value = min_throughput
        self.std_dev_percent_metric.metric_value = std_dev_percent
        # Evaluate pass/fail
        min_throughput_check = (
            (min_throughput / avg_throughput) *
            100) > self.test_params["min_throughput_threshold"]
        std_deviation_check = std_dev_percent < self.test_params["std_deviation_threshold"]

        if min_throughput_check and std_deviation_check:
            asserts.explicit_pass(
                "Test Passed. Throughput at {0:.2f}dB attenuation is stable. "
                "Mean throughput is {1:.2f} Mbps with a standard deviation of "
                "{2:.2f}% and dips down to {3:.2f} Mbps.".format(
                    self.atten_level, avg_throughput, std_dev_percent,
                    min_throughput))
        asserts.fail(
            "Test Failed. Throughput at {0:.2f}dB attenuation is unstable. "
            "Mean throughput is {1:.2f} Mbps with a standard deviation of "
            "{2:.2f}% and dips down to {3:.2f} Mbps.".format(
                self.atten_level, avg_throughput, std_dev_percent,
                min_throughput))

    def post_process_results(self, test_result):
        """Extracts results and saves plots and JSON formatted results.

        Args:
            test_result: dict containing attenuation, iPerfResult object and
            other meta data
        Returns:
            test_result_dict: dict containing post-processed results including
            avg throughput, other metrics, and other meta data
        """
        # Save output as text file
        test_name = self.current_test_name
        results_file_path = "{}/{}.txt".format(self.log_path,
                                               self.current_test_name)
        test_result_dict = {}
        test_result_dict["ap_settings"] = test_result["ap_settings"].copy()
        test_result_dict["attenuation"] = self.atten_level
        if test_result["iperf_result"].instantaneous_rates:
            instantaneous_rates_Mbps = [
                rate * 8 * (1.024**2)
                for rate in test_result["iperf_result"].instantaneous_rates[
                    self.test_params["iperf_ignored_interval"]:-1]
            ]
        else:
            instantaneous_rates_Mbps = float("nan")
        test_result_dict["iperf_results"] = {
            "instantaneous_rates":
            instantaneous_rates_Mbps,
            "avg_throughput":
            math.fsum(instantaneous_rates_Mbps) /
            len(instantaneous_rates_Mbps),
            "std_deviation":
            test_result["iperf_result"].get_std_deviation(
                self.test_params["iperf_ignored_interval"]) * 8,
            "min_throughput":
            min(instantaneous_rates_Mbps)
        }
        with open(results_file_path, 'w') as results_file:
            json.dump(test_result_dict, results_file)
        # Plot and save
        legends = self.current_test_name
        x_label = 'Time (s)'
        y_label = 'Throughput (Mbps)'
        time_data = list(range(0, len(instantaneous_rates_Mbps)))
        data_sets = [[time_data], [instantaneous_rates_Mbps]]
        fig_property = {
            "title": test_name,
            "x_label": x_label,
            "y_label": y_label,
            "linewidth": 3,
            "markersize": 10
        }
        output_file_path = "{}/{}.html".format(self.log_path, test_name)
        wputils.bokeh_plot(
            data_sets,
            legends,
            fig_property,
            shaded_region=None,
            output_file_path=output_file_path)
        return test_result_dict

    def throughput_stability_test_func(self, channel, mode):
        """Main function to test throughput stability.

        The function sets up the AP in the correct channel and mode
        configuration and runs an iperf test to measure throughput.

        Args:
            channel: Specifies AP's channel
            mode: Specifies AP's bandwidth/mode (11g, VHT20, VHT40, VHT80)
        Returns:
            test_result: dict containing test result and meta data
        """
        #Initialize RvR test parameters
        test_result = {}
        # Configure AP
        band = self.access_point.band_lookup_by_channel(channel)
        if "2G" in band:
            frequency = wutils.WifiEnums.channel_2G_to_freq[channel]
        else:
            frequency = wutils.WifiEnums.channel_5G_to_freq[channel]
        if frequency in wutils.WifiEnums.DFS_5G_FREQUENCIES:
            self.access_point.set_region(self.testbed_params["DFS_region"])
        else:
            self.access_point.set_region(self.testbed_params["default_region"])
        self.access_point.set_channel(band, channel)
        self.access_point.set_bandwidth(band, mode)
        self.log.info("Access Point Configuration: {}".format(
            self.access_point.ap_settings))
        # Set attenuator to test level
        self.log.info("Setting attenuation to {} dB".format(self.atten_level))
        for attenuator in self.attenuators:
            attenuator.set_atten(self.atten_level)
        # Connect DUT to Network
        wutils.wifi_toggle_state(self.dut, True)
        wutils.reset_wifi(self.dut)
        self.main_network[band]["channel"] = channel
        self.dut.droid.wifiSetCountryCode(self.test_params["country_code"])
        wutils.wifi_connect(
            self.dut,
            self.main_network[band],
            num_of_tries=5,
            check_connectivity=False)
        time.sleep(MED_SLEEP)
        # Get iperf_server address
        if isinstance(self.iperf_server, ipf.IPerfServerOverAdb):
            iperf_server_address = self.dut.droid.connectivityGetIPv4Addresses(
                'wlan0')[0]
        else:
            iperf_server_address = self.testbed_params["iperf_server_address"]
        # Run test and log result
        # Start iperf session
        self.log.info("Starting iperf test.")
        self.iperf_server.start(tag=str(self.atten_level))
        client_output_path = self.iperf_client.start(
            iperf_server_address, self.iperf_args, str(self.atten_level),
            self.test_params["iperf_duration"] + TEST_TIMEOUT)
        server_output_path = self.iperf_server.stop()
        # Set attenuator to 0 dB
        for attenuator in self.attenuators:
            attenuator.set_atten(0)
        # Parse and log result
        if self.use_client_output:
            iperf_file = client_output_path
        else:
            iperf_file = server_output_path
        try:
            iperf_result = ipf.IPerfResult(iperf_file)
        except:
            asserts.fail("Cannot get iperf result.")
        test_result["ap_settings"] = self.access_point.ap_settings.copy()
        test_result["attenuation"] = self.atten_level
        test_result["iperf_result"] = iperf_result
        return test_result

    def get_target_atten_tput(self):
        """Function gets attenuation used for test

        The function fetches the attenuation at which the test should be
        performed, and the expected target average throughput.

        Returns:
            test_target: dict containing target test attenuation and expected
            throughput
        """
        # Fetch the golden RvR results
        test_name = self.current_test_name
        rvr_golden_file_name = "test_rvr_" + "_".join(test_name.split("_")[4:])
        golden_path = [
            file_name for file_name in self.golden_files_list
            if rvr_golden_file_name in file_name
        ]
        with open(golden_path[0], 'r') as golden_file:
            golden_results = json.load(golden_file)
        test_target = {}
        rssi_high_low = test_name.split("_")[3]
        if rssi_high_low == "low":
            # Get last test point where throughput is above self.test_params["low_rssi_backoff_from_range"]
            throughput_below_target = [
                x < self.test_params["low_rssi_backoff_from_range"]
                for x in golden_results["throughput_receive"]
            ]
            atten_idx = throughput_below_target.index(1) - 1
            test_target["target_attenuation"] = golden_results["attenuation"][
                atten_idx]
            test_target["target_throughput"] = golden_results[
                "throughput_receive"][atten_idx]
        if rssi_high_low == "high":
            # Test at lowest attenuation point
            test_target["target_attenuation"] = golden_results["attenuation"][
                0]
            test_target["target_throughput"] = golden_results[
                "throughput_receive"][0]
        return test_target

    def _test_throughput_stability(self):
        """ Function that gets called for each test case

        The function gets called in each test case. The function customizes
        the test based on the test name of the test that called it
        """
        test_params = self.current_test_name.split("_")
        channel = int(test_params[6][2:])
        mode = test_params[7]
        test_target = self.get_target_atten_tput()
        self.atten_level = test_target["target_attenuation"]
        self.iperf_args = '-i 1 -t {} -J'.format(
            self.test_params["iperf_duration"])
        if test_params[4] == "UDP":
            self.iperf_args = self.iperf_args + " -u -b {}".format(
                self.test_params["UDP_rates"][mode])
        if (test_params[5] == "DL"
                and not isinstance(self.iperf_server, ipf.IPerfServerOverAdb)
            ) or (test_params[5] == "UL"
                  and isinstance(self.iperf_server, ipf.IPerfServerOverAdb)):
            self.iperf_args = self.iperf_args + ' -R'
            self.use_client_output = True
        else:
            self.use_client_output = False
        test_result = self.throughput_stability_test_func(channel, mode)
        test_result_postprocessed = self.post_process_results(test_result)
        self.pass_fail_check(test_result_postprocessed)

    def generate_test_cases(self, modes, traffic_types, signal_levels):
        """Function that auto-generates test cases for a test class."""
        testcase_wrapper = self._test_throughput_stability
        for mode in modes:
            for traffic_type in traffic_types:
                for signal_level in signal_levels:
                    testcase_name = "test_tput_stability_{}_{}_{}_ch{}_{}".format(
                        signal_level, traffic_type[0], traffic_type[1],
                        mode[0], mode[1])
                    setattr(self, testcase_name, testcase_wrapper)
                    self.tests.append(testcase_name)
