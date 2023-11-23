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

import collections
import json
import logging
import os
import statistics
import time
from acts import asserts
from acts import base_test
from acts import utils
from acts.controllers.utils_lib import ssh
from acts.metrics.loggers.blackbox import BlackboxMetricLogger
from acts.test_utils.wifi import wifi_performance_test_utils as wputils
from acts.test_utils.wifi import wifi_retail_ap as retail_ap
from acts.test_utils.wifi import wifi_test_utils as wutils


class WifiPingTest(base_test.BaseTestClass):
    """Class for ping-based Wifi performance tests.

    This class implements WiFi ping performance tests such as range and RTT.
    The class setups up the AP in the desired configurations, configures
    and connects the phone to the AP, and runs  For an example config file to
    run this test class see example_connectivity_performance_ap_sta.json.
    """

    TEST_TIMEOUT = 10
    RSSI_POLL_INTERVAL = 0.2
    SHORT_SLEEP = 1
    MED_SLEEP = 5
    MAX_CONSECUTIVE_ZEROS = 5
    DISCONNECTED_PING_RESULT = {
        "connected": 0,
        "rtt": [],
        "time_stamp": [],
        "ping_interarrivals": [],
        "packet_loss_percentage": 100
    }

    def __init__(self, controllers):
        base_test.BaseTestClass.__init__(self, controllers)
        self.ping_range_metric = BlackboxMetricLogger.for_test_case(
            metric_name='ping_range')
        self.ping_rtt_metric = BlackboxMetricLogger.for_test_case(
            metric_name='ping_rtt')
        self.tests = (
            "test_ping_range_ch1_VHT20", "test_fast_ping_rtt_ch1_VHT20",
            "test_slow_ping_rtt_ch1_VHT20", "test_ping_range_ch6_VHT20",
            "test_fast_ping_rtt_ch6_VHT20", "test_slow_ping_rtt_ch6_VHT20",
            "test_ping_range_ch11_VHT20", "test_fast_ping_rtt_ch11_VHT20",
            "test_slow_ping_rtt_ch11_VHT20", "test_ping_range_ch36_VHT20",
            "test_fast_ping_rtt_ch36_VHT20", "test_slow_ping_rtt_ch36_VHT20",
            "test_ping_range_ch36_VHT40", "test_fast_ping_rtt_ch36_VHT40",
            "test_slow_ping_rtt_ch36_VHT40", "test_ping_range_ch36_VHT80",
            "test_fast_ping_rtt_ch36_VHT80", "test_slow_ping_rtt_ch36_VHT80",
            "test_ping_range_ch40_VHT20", "test_ping_range_ch44_VHT20",
            "test_ping_range_ch44_VHT40", "test_ping_range_ch48_VHT20",
            "test_ping_range_ch149_VHT20", "test_fast_ping_rtt_ch149_VHT20",
            "test_slow_ping_rtt_ch149_VHT20", "test_ping_range_ch149_VHT40",
            "test_fast_ping_rtt_ch149_VHT40", "test_slow_ping_rtt_ch149_VHT40",
            "test_ping_range_ch149_VHT80", "test_fast_ping_rtt_ch149_VHT80",
            "test_slow_ping_rtt_ch149_VHT80", "test_ping_range_ch153_VHT20",
            "test_ping_range_ch157_VHT20", "test_ping_range_ch157_VHT40",
            "test_ping_range_ch161_VHT20")

    def setup_class(self):
        self.client_dut = self.android_devices[-1]
        req_params = [
            "ping_test_params", "testbed_params", "main_network",
            "RetailAccessPoints", "RemoteServer"
        ]
        opt_params = ["golden_files_list"]
        self.unpack_userparams(req_params, opt_params)
        self.testclass_params = self.ping_test_params
        self.num_atten = self.attenuators[0].instrument.num_atten
        self.ping_server = ssh.connection.SshConnection(
            ssh.settings.from_config(self.RemoteServer[0]["ssh_config"]))
        self.access_points = retail_ap.create(self.RetailAccessPoints)
        self.access_point = self.access_points[0]
        self.log.info("Access Point Configuration: {}".format(
            self.access_point.ap_settings))
        self.log_path = os.path.join(logging.log_path, "results")
        utils.create_dir(self.log_path)
        if not hasattr(self, "golden_files_list"):
            self.golden_files_list = [
                os.path.join(self.testbed_params["golden_results_path"], file)
                for file in os.listdir(
                    self.testbed_params["golden_results_path"])
            ]
        self.testclass_results = []

        # Turn WiFi ON
        for dev in self.android_devices:
            wutils.wifi_toggle_state(dev, True)

    def teardown_class(self):
        # Turn WiFi OFF
        for dev in self.android_devices:
            wutils.wifi_toggle_state(dev, False)
        self.process_testclass_results()

    def process_testclass_results(self):
        """Saves all test results to enable comparison."""
        testclass_summary = {}
        for test in self.testclass_results:
            if "range" in test["test_name"]:
                testclass_summary[test["test_name"]] = test["range"]
        # Save results
        results_file_path = "{}/testclass_summary.json".format(self.log_path)
        with open(results_file_path, 'w') as results_file:
            json.dump(testclass_summary, results_file, indent=4)

    def pass_fail_check_ping_rtt(self, ping_range_result):
        """Check the test result and decide if it passed or failed.

        The function computes RTT statistics and fails any tests in which the
        tail of the ping latency results exceeds the threshold defined in the
        configuration file.

        Args:
            ping_range_result: dict containing ping results and other meta data
        """
        ignored_fraction = (self.testclass_params["rtt_ignored_interval"] /
                            self.testclass_params["rtt_ping_duration"])
        sorted_rtt = [
            sorted(x["rtt"][round(ignored_fraction * len(x["rtt"])):])
            for x in ping_range_result["ping_results"]
        ]
        mean_rtt = [statistics.mean(x) for x in sorted_rtt]
        std_rtt = [statistics.stdev(x) for x in sorted_rtt]
        rtt_at_test_percentile = [
            x[int((1 - self.testclass_params["rtt_test_percentile"] / 100) *
                  len(x))] for x in sorted_rtt
        ]
        # Set blackbox metric
        self.ping_rtt_metric.metric_value = max(rtt_at_test_percentile)
        # Evaluate test pass/fail
        test_failed = False
        for idx, rtt in enumerate(rtt_at_test_percentile):
            if rtt > self.testclass_params["rtt_threshold"] * 1000:
                test_failed = True
                self.log.info(
                    "RTT Failed. Test %ile RTT = {}ms. Mean = {}ms. Stdev = {}"
                    .format(rtt, mean_rtt[idx], std_rtt[idx]))
        if test_failed:
            asserts.fail("RTT above threshold")
        else:
            asserts.explicit_pass(
                "Test Passed. RTTs at test percentile = {}".format(
                    rtt_at_test_percentile))

    def pass_fail_check_ping_range(self, ping_range_result):
        """Check the test result and decide if it passed or failed.

        Checks whether the attenuation at which ping packet losses begin to
        exceed the threshold matches the range derived from golden
        rate-vs-range result files. The test fails is ping range is
        range_gap_threshold worse than RvR range.

        Args:
            ping_range_result: dict containing ping results and meta data
        """
        # Get target range
        rvr_range = self.get_range_from_rvr()
        # Set Blackbox metric
        self.ping_range_metric.metric_value = ping_range_result["range"]
        # Evaluate test pass/fail
        if ping_range_result["range"] - rvr_range < -self.testclass_params[
                "range_gap_threshold"]:
            asserts.fail(
                "Attenuation at range is {}dB. Golden range is {}dB".format(
                    ping_range_result["range"], rvr_range))
        else:
            asserts.explicit_pass(
                "Attenuation at range is {}dB. Golden range is {}dB".format(
                    ping_range_result["range"], rvr_range))

    def process_ping_results(self, testcase_params, ping_range_result):
        """Saves and plots ping results.

        Args:
            ping_range_result: dict containing ping results and metadata
        """
        # Compute range
        ping_loss_over_att = [
            x["packet_loss_percentage"]
            for x in ping_range_result["ping_results"]
        ]
        ping_loss_above_threshold = [
            x > testcase_params["range_ping_loss_threshold"]
            for x in ping_loss_over_att
        ]
        for idx in range(len(ping_loss_above_threshold)):
            if all(ping_loss_above_threshold[idx:]):
                range_index = max(idx, 1) - 1
                break
        else:
            range_index = -1
        ping_range_result["atten_at_range"] = testcase_params["atten_range"][
            range_index]
        ping_range_result["peak_throughput"] = "{}%".format(
            100 - min(ping_loss_over_att))
        ping_range_result["range"] = (ping_range_result["atten_at_range"] +
                                      ping_range_result["fixed_attenuation"])

        # Save results
        results_file_path = "{}/{}.json".format(self.log_path,
                                                self.current_test_name)
        with open(results_file_path, 'w') as results_file:
            json.dump(ping_range_result, results_file, indent=4)

        # Plot results
        x_data = [
            list(range(len(x["rtt"])))
            for x in ping_range_result["ping_results"] if len(x["rtt"]) > 1
        ]
        rtt_data = [
            x["rtt"] for x in ping_range_result["ping_results"]
            if len(x["rtt"]) > 1
        ]
        legend = [
            "RTT @ {}dB".format(att)
            for att in ping_range_result["attenuation"]
        ]

        data_sets = [x_data, rtt_data]
        fig_property = {
            "title": self.current_test_name,
            "x_label": 'Sample Index',
            "y_label": 'Round Trip Time (ms)',
            "linewidth": 3,
            "markersize": 0
        }
        output_file_path = "{}/{}.html".format(self.log_path,
                                               self.current_test_name)
        wputils.bokeh_plot(
            data_sets,
            legend,
            fig_property,
            shaded_region=None,
            output_file_path=output_file_path)

    def get_range_from_rvr(self):
        """Function gets range from RvR golden results

        The function fetches the attenuation at which the RvR throughput goes
        to zero.

        Returns:
            range: range derived from looking at rvr curves
        """
        # Fetch the golden RvR results
        test_name = self.current_test_name
        rvr_golden_file_name = "test_rvr_TCP_DL_" + "_".join(
            test_name.split("_")[3:])
        golden_path = [
            file_name for file_name in self.golden_files_list
            if rvr_golden_file_name in file_name
        ]
        with open(golden_path[0], 'r') as golden_file:
            golden_results = json.load(golden_file)
        # Get 0 Mbps attenuation and backoff by low_rssi_backoff_from_range
        try:
            atten_idx = golden_results["throughput_receive"].index(0)
            rvr_range = (golden_results["attenuation"][atten_idx - 1] +
                         golden_results["fixed_attenuation"])
        except ValueError:
            rvr_range = float("nan")
        return rvr_range

    def run_ping_test(self, testcase_params):
        """Main function to test ping.

        The function sets up the AP in the correct channel and mode
        configuration and calls get_ping_stats while sweeping attenuation

        Args:
            testcase_params: dict containing all test parameters
        Returns:
            test_result: dict containing ping results and other meta data
        """
        # Prepare results dict
        test_result = collections.OrderedDict()
        test_result["test_name"] = self.current_test_name
        test_result["ap_config"] = self.access_point.ap_settings.copy()
        test_result["attenuation"] = testcase_params["atten_range"]
        test_result["fixed_attenuation"] = self.testbed_params[
            "fixed_attenuation"][str(testcase_params["channel"])]
        test_result["rssi_results"] = []
        test_result["ping_results"] = []
        # Run ping and sweep attenuation as needed
        zero_counter = 0
        for atten in testcase_params["atten_range"]:
            for attenuator in self.attenuators:
                attenuator.set_atten(atten, strict=False)
            rssi_future = wputils.get_connected_rssi_nb(
                self.client_dut,
                int(testcase_params["ping_duration"] / 2 /
                    self.RSSI_POLL_INTERVAL), self.RSSI_POLL_INTERVAL,
                testcase_params["ping_duration"] / 2)
            current_ping_stats = wputils.get_ping_stats(
                self.ping_server, self.dut_ip,
                testcase_params["ping_duration"],
                testcase_params["ping_interval"], testcase_params["ping_size"])
            current_rssi = rssi_future.result()["signal_poll_rssi"]["mean"]
            test_result["rssi_results"].append(current_rssi)
            if current_ping_stats["connected"]:
                self.log.info("Attenuation = {0}dB\tPacket Loss = {1}%\t"
                              "Avg RTT = {2:.2f}ms\tRSSI = {3}\t".format(
                                  atten,
                                  current_ping_stats["packet_loss_percentage"],
                                  statistics.mean(current_ping_stats["rtt"]),
                                  current_rssi))
                if current_ping_stats["packet_loss_percentage"] == 100:
                    zero_counter = zero_counter + 1
                else:
                    zero_counter = 0
            else:
                self.log.info(
                    "Attenuation = {}dB. Disconnected.".format(atten))
                zero_counter = zero_counter + 1
            test_result["ping_results"].append(current_ping_stats.as_dict())
            if zero_counter == self.MAX_CONSECUTIVE_ZEROS:
                self.log.info("Ping loss stable at 100%. Stopping test now.")
                for idx in range(
                        len(testcase_params["atten_range"]) -
                        len(test_result["ping_results"])):
                    test_result["ping_results"].append(
                        self.DISCONNECTED_PING_RESULT)
                break
        return test_result

    def setup_ap(self, testcase_params):
        """Sets up the access point in the configuration required by the test.

        Args:
            testcase_params: dict containing AP and other test params
        """
        band = self.access_point.band_lookup_by_channel(
            testcase_params["channel"])
        if "2G" in band:
            frequency = wutils.WifiEnums.channel_2G_to_freq[
                testcase_params["channel"]]
        else:
            frequency = wutils.WifiEnums.channel_5G_to_freq[
                testcase_params["channel"]]
        if frequency in wutils.WifiEnums.DFS_5G_FREQUENCIES:
            self.access_point.set_region(self.testbed_params["DFS_region"])
        else:
            self.access_point.set_region(self.testbed_params["default_region"])
        self.access_point.set_channel(band, testcase_params["channel"])
        self.access_point.set_bandwidth(band, testcase_params["mode"])
        self.log.info("Access Point Configuration: {}".format(
            self.access_point.ap_settings))

    def setup_dut(self, testcase_params):
        """Sets up the DUT in the configuration required by the test.

        Args:
            testcase_params: dict containing AP and other test params
        """
        band = self.access_point.band_lookup_by_channel(
            testcase_params["channel"])
        wutils.reset_wifi(self.client_dut)
        self.client_dut.droid.wifiSetCountryCode(
            self.testclass_params["country_code"])
        self.main_network[band]["channel"] = testcase_params["channel"]
        wutils.wifi_connect(
            self.client_dut,
            self.main_network[band],
            num_of_tries=5,
            check_connectivity=False)
        self.dut_ip = self.client_dut.droid.connectivityGetIPv4Addresses(
            'wlan0')[0]
        time.sleep(self.MED_SLEEP)

    def setup_ping_test(self, testcase_params):
        """Function that gets devices ready for the test.

        Args:
            testcase_params: dict containing test-specific parameters
        """
        # Configure AP
        self.setup_ap(testcase_params)
        # Set attenuator to 0 dB
        for attenuator in self.attenuators:
            attenuator.set_atten(0, strict=False)
        # Reset, configure, and connect DUT
        self.setup_dut(testcase_params)

    def parse_test_params(self, test_name):
        test_name_params = test_name.split("_")
        testcase_params = collections.OrderedDict()
        if "range" in test_name:
            testcase_params["channel"] = int(test_name_params[3][2:])
            testcase_params["mode"] = test_name_params[4]
            num_atten_steps = int((self.testclass_params["range_atten_stop"] -
                                   self.testclass_params["range_atten_start"])
                                  / self.testclass_params["range_atten_step"])
            testcase_params["atten_range"] = [
                self.testclass_params["range_atten_start"] +
                x * self.testclass_params["range_atten_step"]
                for x in range(0, num_atten_steps)
            ]
            testcase_params["ping_duration"] = self.testclass_params[
                "range_ping_duration"]
            testcase_params["ping_interval"] = self.testclass_params[
                "range_ping_interval"]
            testcase_params["ping_size"] = self.testclass_params["ping_size"]
        else:
            testcase_params["channel"] = int(test_name_params[4][2:])
            testcase_params["mode"] = test_name_params[5]
            testcase_params["atten_range"] = self.testclass_params[
                "rtt_test_attenuation"]
            testcase_params["ping_duration"] = self.testclass_params[
                "rtt_ping_duration"]
            testcase_params["ping_interval"] = self.testclass_params[
                "rtt_ping_interval"][test_name_params[1]]
            testcase_params["ping_size"] = self.testclass_params["ping_size"]
        return testcase_params

    def _test_ping_rtt(self):
        """ Function that gets called for each RTT test case

        The function gets called in each RTT test case. The function customizes
        the RTT test based on the test name of the test that called it
        """
        # Compile test parameters from config and test name
        testcase_params = self.parse_test_params(self.current_test_name)
        testcase_params.update(self.testclass_params)
        # Run ping test
        self.setup_ping_test(testcase_params)
        ping_result = self.run_ping_test(testcase_params)
        # Postprocess results
        self.process_ping_results(testcase_params, ping_result)
        self.pass_fail_check_ping_rtt(ping_result)

    def _test_ping_range(self):
        """ Function that gets called for each range test case

        The function gets called in each range test case. It customizes the
        range test based on the test name of the test that called it
        """
        # Compile test parameters from config and test name
        testcase_params = self.parse_test_params(self.current_test_name)
        testcase_params.update(self.testclass_params)
        # Run ping test
        self.setup_ping_test(testcase_params)
        ping_result = self.run_ping_test(testcase_params)
        # Postprocess results
        self.testclass_results.append(ping_result)
        self.process_ping_results(testcase_params, ping_result)
        self.pass_fail_check_ping_range(ping_result)

    def test_ping_range_ch1_VHT20(self):
        self._test_ping_range()

    def test_ping_range_ch6_VHT20(self):
        self._test_ping_range()

    def test_ping_range_ch11_VHT20(self):
        self._test_ping_range()

    def test_ping_range_ch36_VHT20(self):
        self._test_ping_range()

    def test_ping_range_ch36_VHT40(self):
        self._test_ping_range()

    def test_ping_range_ch36_VHT80(self):
        self._test_ping_range()

    def test_ping_range_ch40_VHT20(self):
        self._test_ping_range()

    def test_ping_range_ch44_VHT20(self):
        self._test_ping_range()

    def test_ping_range_ch44_VHT40(self):
        self._test_ping_range()

    def test_ping_range_ch48_VHT20(self):
        self._test_ping_range()

    def test_ping_range_ch149_VHT20(self):
        self._test_ping_range()

    def test_ping_range_ch149_VHT40(self):
        self._test_ping_range()

    def test_ping_range_ch149_VHT80(self):
        self._test_ping_range()

    def test_ping_range_ch153_VHT20(self):
        self._test_ping_range()

    def test_ping_range_ch157_VHT20(self):
        self._test_ping_range()

    def test_ping_range_ch157_VHT40(self):
        self._test_ping_range()

    def test_ping_range_ch161_VHT20(self):
        self._test_ping_range()

    def test_fast_ping_rtt_ch1_VHT20(self):
        self._test_ping_rtt()

    def test_slow_ping_rtt_ch1_VHT20(self):
        self._test_ping_rtt()

    def test_fast_ping_rtt_ch6_VHT20(self):
        self._test_ping_rtt()

    def test_slow_ping_rtt_ch6_VHT20(self):
        self._test_ping_rtt()

    def test_fast_ping_rtt_ch11_VHT20(self):
        self._test_ping_rtt()

    def test_slow_ping_rtt_ch11_VHT20(self):
        self._test_ping_rtt()

    def test_fast_ping_rtt_ch36_VHT20(self):
        self._test_ping_rtt()

    def test_slow_ping_rtt_ch36_VHT20(self):
        self._test_ping_rtt()

    def test_fast_ping_rtt_ch36_VHT40(self):
        self._test_ping_rtt()

    def test_slow_ping_rtt_ch36_VHT40(self):
        self._test_ping_rtt()

    def test_fast_ping_rtt_ch36_VHT80(self):
        self._test_ping_rtt()

    def test_slow_ping_rtt_ch36_VHT80(self):
        self._test_ping_rtt()

    def test_fast_ping_rtt_ch149_VHT20(self):
        self._test_ping_rtt()

    def test_slow_ping_rtt_ch149_VHT20(self):
        self._test_ping_rtt()

    def test_fast_ping_rtt_ch149_VHT40(self):
        self._test_ping_rtt()

    def test_slow_ping_rtt_ch149_VHT40(self):
        self._test_ping_rtt()

    def test_fast_ping_rtt_ch149_VHT80(self):
        self._test_ping_rtt()

    def test_slow_ping_rtt_ch149_VHT80(self):
        self._test_ping_rtt()
