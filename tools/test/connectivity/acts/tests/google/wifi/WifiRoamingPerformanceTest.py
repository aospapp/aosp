#!/usr/bin/env python3.4
#
#   Copyright 2019 - The Android Open Source Project
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
import math
import os
import time
from acts import asserts
from acts import base_test
from acts import context
from acts import utils
from acts.controllers import iperf_server as ipf
from acts.controllers.utils_lib import ssh
from acts.test_utils.wifi import wifi_performance_test_utils as wputils
from acts.test_utils.wifi import wifi_retail_ap as retail_ap
from acts.test_utils.wifi import wifi_test_utils as wutils

SHORT_SLEEP = 1
MED_SLEEP = 5
TRAFFIC_GAP_THRESH = 0.5
IPERF_INTERVAL = 0.25


class WifiRoamingPerformanceTest(base_test.BaseTestClass):
    """Class for ping-based Wifi performance tests.

    This class implements WiFi ping performance tests such as range and RTT.
    The class setups up the AP in the desired configurations, configures
    and connects the phone to the AP, and runs  For an example config file to
    run this test class see example_connectivity_performance_ap_sta.json.
    """

    def setup_class(self):
        """Initializes common test hardware and parameters.

        This function initializes hardwares and compiles parameters that are
        common to all tests in this class.
        """
        self.client_dut = self.android_devices[-1]
        req_params = [
            'RetailAccessPoints', 'roaming_test_params', 'testbed_params'
        ]
        opt_params = ['main_network', 'RemoteServer']
        self.unpack_userparams(req_params, opt_params)
        self.testclass_params = self.roaming_test_params
        self.num_atten = self.attenuators[0].instrument.num_atten
        self.remote_server = ssh.connection.SshConnection(
            ssh.settings.from_config(self.RemoteServer[0]['ssh_config']))
        self.remote_server.setup_master_ssh()
        self.iperf_server = self.iperf_servers[0]
        self.iperf_client = self.iperf_clients[0]
        self.access_point = retail_ap.create(self.RetailAccessPoints)[0]
        self.log.info('Access Point Configuration: {}'.format(
            self.access_point.ap_settings))
        self.log_path = os.path.join(logging.log_path, 'results')
        utils.create_dir(self.log_path)

        #Turn WiFi ON
        for dev in self.android_devices:
            wutils.wifi_toggle_state(dev, True)

    def pass_fail_traffic_continuity(self, result):
        """Pass fail check for traffic continuity

        Currently, the function only reports test results and implicitly passes
        the test. A pass fail criterion is current being researched.

        Args:
            result: dict containing test results
        """
        self.log.info('Detected {} roam transitions:'.format(
            len(result['roam_transitions'])))
        for event in result['roam_transitions']:
            self.log.info('Roam: {} -> {})'.format(event[0], event[1]))
        self.log.info('Roam transition statistics: {}'.format(
            result['roam_counts']))

        formatted_traffic_gaps = [
            round(gap, 2) for gap in result['traffic_disruption']
        ]
        self.log.info('Detected {} traffic gaps of duration: {}'.format(
            len(result['traffic_disruption']), formatted_traffic_gaps))

        if (max(result['traffic_disruption']) >
                self.testclass_params['traffic_disruption thresold']):
            asserts.fail('Test failed. Max traffic discruption: {}s.'.format(
                max(result['traffic_disruption'])))
        asserts.explicit_pass(
            'Test passed. Max traffic discruption: {}s.'.format(
                max(result['traffic_disruption'])))

    def pass_fail_roaming_consistency(self, results_dict):
        """Function to evaluate roaming consistency results.

        The function looks for the roams recorded in multiple runs of the same
        attenuation waveform and checks that the DUT reliably roams to the
        same network

        Args:
            results_dict: dict containing consistency test results
        """
        test_fail = False
        for secondary_atten, roam_stats in results_dict['roam_stats'].items():
            total_roams = sum(list(roam_stats.values()))
            common_roam = max(roam_stats.keys(), key=(lambda k: roam_stats[k]))
            common_roam_frequency = roam_stats[common_roam] / total_roams
            self.log.info(
                '{}dB secondary atten. Most common roam: {}. Frequency: {}'.
                format(secondary_atten, common_roam, common_roam_frequency))
            if common_roam_frequency < self.testclass_params[
                    'consistency_threshold']:
                test_fail = True
                self.log.info('Unstable Roams at {}dB secondary att'.format(
                    secondary_atten))
        if test_fail:
            asserts.fail('Incosistent roaming detected.')
        else:
            asserts.explicit_pass('Consistent roaming at all levels.')

    def process_traffic_continuity_results(self, testcase_params, result):
        """Function to process traffic results.

        The function looks for traffic gaps during a roaming test

        Args:
            testcase_params: dict containing all test results and meta data
            results_dict: dict containing consistency test results
        """
        self.detect_roam_events(result)
        current_context = context.get_current_context().get_full_output_path()
        plot_file_path = os.path.join(current_context,
                                      self.current_test_name + '.html')

        if 'ping' in self.current_test_name:
            self.detect_ping_gaps(result)
            self.plot_ping_result(
                testcase_params, result, output_file_path=plot_file_path)
        elif 'iperf' in self.current_test_name:
            self.detect_iperf_gaps(result)
            self.plot_iperf_result(
                testcase_params, result, output_file_path=plot_file_path)

        results_file_path = os.path.join(current_context,
                                         self.current_test_name + '.json')
        with open(results_file_path, 'w') as results_file:
            json.dump(result, results_file, indent=4)

    def process_consistency_results(self, testcase_params, results_dict):
        """Function to process roaming consistency results.

        The function looks compiles the test of roams recorded in consistency
        tests and plots results for easy visualization.

        Args:
            testcase_params: dict containing all test results and meta data
            results_dict: dict containing consistency test results
        """
        # make figure placeholder and get relevant functions
        if 'ping' in self.current_test_name:
            detect_gaps = self.detect_ping_gaps
            plot_result = self.plot_ping_result
            primary_y_axis = 'RTT (ms)'
        elif 'iperf' in self.current_test_name:
            detect_gaps = self.detect_iperf_gaps
            plot_result = self.plot_iperf_result
            primary_y_axis = 'Throughput (Mbps)'
        # loop over results
        roam_stats = collections.OrderedDict()
        current_context = context.get_current_context().get_full_output_path()
        for secondary_atten, results_list in results_dict.items():
            figure = wputils.BokehFigure(
                title=self.current_test_name,
                x_label='Time (ms)',
                primary_y=primary_y_axis,
                secondary_y='RSSI (dBm)')
            roam_stats[secondary_atten] = collections.OrderedDict()
            for result in results_list:
                self.detect_roam_events(result)
                for roam_transition, count in result['roam_counts'].items():
                    roam_stats[secondary_atten][
                        roam_transition] = roam_stats[secondary_atten].get(
                            roam_transition, 0) + count
                detect_gaps(result)
                plot_result(testcase_params, result, figure=figure)
            # save plot
            plot_file_name = (
                self.current_test_name + '_' + secondary_atten + '.html')

            plot_file_path = os.path.join(current_context, plot_file_name)
            figure.save_figure(plot_file_path)
        results_dict['roam_stats'] = roam_stats

        results_file_path = os.path.join(current_context,
                                         self.current_test_name + '.json')
        with open(results_file_path, 'w') as results_file:
            json.dump(result, results_file, indent=4)

    def detect_roam_events(self, result):
        """Function to process roaming results.

        The function detects roams by looking at changes in BSSID and compiles
        meta data about each roam, e.g., RSSI before and after a roam. The
        function then calls the relevant method to process traffic results and
        report traffic disruptions.

        Args:
            testcase_params: dict containing AP and other test params
            result: dict containing test results
        """
        roam_events = [
            (idx, idx + 1)
            for idx in range(len(result['rssi_result']['bssid']) - 1)
            if result['rssi_result']['bssid'][idx] != result['rssi_result']
            ['bssid'][idx + 1]
        ]

        def ignore_entry(vals):
            for val in vals:
                if val in {0} or math.isnan(val):
                    return True
            return False

        for roam_idx, roam_event in enumerate(roam_events):
            # Find true roam start by scanning earlier samples for valid data
            while ignore_entry([
                    result['rssi_result']['frequency'][roam_event[0]],
                    result['rssi_result']['signal_poll_rssi']['data'][
                        roam_event[0]]
            ]):
                roam_event = (roam_event[0] - 1, roam_event[1])
                roam_events[roam_idx] = roam_event
            # Find true roam end by scanning later samples for valid data
            while ignore_entry([
                    result['rssi_result']['frequency'][roam_event[1]],
                    result['rssi_result']['signal_poll_rssi']['data'][
                        roam_event[1]]
            ]):
                roam_event = (roam_event[0], roam_event[1] + 1)
                roam_events[roam_idx] = roam_event

        roam_events = list(set(roam_events))
        roam_events.sort(key=lambda event_tuple: event_tuple[1])
        roam_transitions = []
        roam_counts = {}
        for event in roam_events:
            from_bssid = next(
                key for key, value in self.main_network.items()
                if value['BSSID'] == result['rssi_result']['bssid'][event[0]])
            to_bssid = next(
                key for key, value in self.main_network.items()
                if value['BSSID'] == result['rssi_result']['bssid'][event[1]])
            curr_bssid_transition = (from_bssid, to_bssid)
            curr_roam_transition = (
                (from_bssid,
                 result['rssi_result']['signal_poll_rssi']['data'][event[0]]),
                (to_bssid,
                 result['rssi_result']['signal_poll_rssi']['data'][event[1]]))
            roam_transitions.append(curr_roam_transition)
            roam_counts[curr_bssid_transition] = roam_counts.get(
                curr_bssid_transition, 0) + 1
        result['roam_events'] = roam_events
        result['roam_transitions'] = roam_transitions
        result['roam_counts'] = roam_counts

    def detect_ping_gaps(self, result):
        """Function to process ping results.

        The function looks for gaps in iperf traffic and reports them as
        disruptions due to roams.

        Args:
            result: dict containing test results
        """
        traffic_disruption = [
            x for x in result['ping_result']['ping_interarrivals']
            if x > TRAFFIC_GAP_THRESH
        ]
        result['traffic_disruption'] = traffic_disruption

    def detect_iperf_gaps(self, result):
        """Function to process iperf results.

        The function looks for gaps in iperf traffic and reports them as
        disruptions due to roams.

        Args:
            result: dict containing test results
        """
        tput_thresholding = [tput < 1 for tput in result['throughput']]
        window_size = int(TRAFFIC_GAP_THRESH / IPERF_INTERVAL)
        tput_thresholding = [
            any(tput_thresholding[max(0, idx - window_size):idx])
            for idx in range(1,
                             len(tput_thresholding) + 1)
        ]

        traffic_disruption = []
        current_disruption = 1 - window_size
        for tput_low in tput_thresholding:
            if tput_low:
                current_disruption += 1
            elif current_disruption > window_size:
                traffic_disruption.append(current_disruption * IPERF_INTERVAL)
                current_disruption = 1 - window_size
            else:
                current_disruption = 1 - window_size
        result['traffic_disruption'] = traffic_disruption

    def plot_ping_result(self,
                         testcase_params,
                         result,
                         figure=None,
                         output_file_path=None):
        """Function to plot ping results.

        The function plots ping RTTs along with RSSI over time during a roaming
        test.

        Args:
            testcase_params: dict containing all test params
            result: dict containing test results
            figure: optional bokeh figure object to add current plot to
            output_file_path: optional path to output file
        """
        if not figure:
            figure = wputils.BokehFigure(
                title=self.current_test_name,
                x_label='Time (ms)',
                primary_y='RTT (ms)',
                secondary_y='RSSI (dBm)')
        figure.add_line(
            result['ping_result']['time_stamp'],
            result['ping_result']['rtt'],
            'Ping RTT',
            width=1)
        figure.add_line(
            result['rssi_result']['time_stamp'],
            result['rssi_result']['signal_poll_rssi']['data'],
            'RSSI',
            y_axis='secondary')
        figure.generate_figure(output_file_path)

    def plot_iperf_result(self,
                          testcase_params,
                          result,
                          figure=None,
                          output_file_path=None):
        """Function to plot iperf results.

        The function plots iperf throughput and RSSI over time during a roaming
        test.

        Args:
            testcase_params: dict containing all test params
            result: dict containing test results
            figure: optional bokeh figure object to add current plot to
            output_file_path: optional path to output file
        """
        if not figure:
            figure = wputils.BokehFigure(
                title=self.current_test_name,
                x_label='Time (s)',
                primary_y='Throughput (Mbps)',
                secondary_y='RSSI (dBm)')
        iperf_time_stamps = [
            idx * IPERF_INTERVAL for idx in range(len(result['throughput']))
        ]
        figure.add_line(
            iperf_time_stamps, result['throughput'], 'Throughput', width=1)
        figure.add_line(
            result['rssi_result']['time_stamp'],
            result['rssi_result']['signal_poll_rssi']['data'],
            'RSSI',
            y_axis='secondary')

        figure.generate_figure(output_file_path)

    def setup_ap(self, testcase_params):
        """Sets up the AP and attenuator to the test configuration.

        Args:
            testcase_params: dict containing AP and other test params
        """
        (primary_net_id,
         primary_net_config) = next(net for net in self.main_network.items()
                                    if net[1]['roaming_label'] == 'primary')
        for atten in self.attenuators:
            if primary_net_id in atten.path:
                atten.set_atten(0)
            else:
                atten.set_atten(atten.instrument.max_atten)

    def setup_dut(self, testcase_params):
        """Sets up the DUT in the configuration required by the test.

        Args:
            testcase_params: dict containing AP and other test params
        """
        wutils.reset_wifi(self.client_dut)
        self.client_dut.droid.wifiSetCountryCode(
            self.testclass_params['country_code'])
        (primary_net_id,
         primary_net_config) = next(net for net in self.main_network.items()
                                    if net[1]['roaming_label'] == 'primary')
        network = primary_net_config.copy()
        network.pop('BSSID', None)
        self.client_dut.droid.wifiSetEnableAutoJoinWhenAssociated(1)
        wutils.wifi_connect(
            self.client_dut, network, num_of_tries=5, check_connectivity=False)
        self.client_dut.droid.wifiSetEnableAutoJoinWhenAssociated(1)
        self.dut_ip = self.client_dut.droid.connectivityGetIPv4Addresses(
            'wlan0')[0]
        if testcase_params['screen_on']:
            self.client_dut.wakeup_screen()
            self.client_dut.droid.wakeLockAcquireBright()
        time.sleep(MED_SLEEP)

    def setup_roaming_test(self, testcase_params):
        """Function to set up roaming test."""
        self.setup_ap(testcase_params)
        self.setup_dut(testcase_params)

    def run_ping_test(self, testcase_params):
        """Main function for ping roaming tests.

        Args:
            testcase_params: dict including all test params encoded in test
            name
        Returns:
            dict containing all test results and meta data
        """
        self.log.info('Starting ping test.')
        ping_future = wputils.get_ping_stats_nb(
            self.remote_server, self.dut_ip,
            testcase_params['atten_waveforms']['length'],
            testcase_params['ping_interval'], 64)
        rssi_future = wputils.get_connected_rssi_nb(
            self.client_dut,
            int(testcase_params['atten_waveforms']['length'] /
                testcase_params['rssi_polling_frequency']),
            testcase_params['rssi_polling_frequency'])
        self.run_attenuation_waveform(testcase_params)
        return {
            'ping_result': ping_future.result(),
            'rssi_result': rssi_future.result(),
            'ap_settings': self.access_point.ap_settings,
        }

    def run_iperf_test(self, testcase_params):
        """Main function for iperf roaming tests.

        Args:
            testcase_params: dict including all test params encoded in test
            name
        Returns:
            result: dict containing all test results and meta data
        """
        self.log.info('Starting iperf test.')
        self.iperf_server.start(extra_args='-i {}'.format(IPERF_INTERVAL))
        if isinstance(self.iperf_server, ipf.IPerfServerOverAdb):
            iperf_server_address = (
                self.client_dut.droid.connectivityGetIPv4Addresses('wlan0')[0])
            self.iperf_client._ssh_session.setup_master_ssh()
        else:
            iperf_server_address = self.testbed_params['iperf_server_address']
        iperf_args = '-i {} -t {} -J'.format(
            IPERF_INTERVAL, testcase_params['atten_waveforms']['length'])
        if not isinstance(self.iperf_server, ipf.IPerfServerOverAdb):
            iperf_args = iperf_args + ' -R'
        iperf_future = wputils.start_iperf_client_nb(
            self.iperf_client, iperf_server_address, iperf_args, 0,
            testcase_params['atten_waveforms']['length'] + MED_SLEEP)
        rssi_future = wputils.get_connected_rssi_nb(
            self.client_dut,
            int(testcase_params['atten_waveforms']['length'] /
                testcase_params['rssi_polling_frequency']),
            testcase_params['rssi_polling_frequency'])
        self.run_attenuation_waveform(testcase_params)
        client_output_path = iperf_future.result()
        server_output_path = self.iperf_server.stop()
        if isinstance(self.iperf_server, ipf.IPerfServerOverAdb):
            iperf_file = server_output_path
        else:
            iperf_file = client_output_path
        iperf_result = ipf.IPerfResult(iperf_file)
        return {
            'throughput': iperf_result.instantaneous_rates,
            'rssi_result': rssi_future.result(),
            'ap_settings': self.access_point.ap_settings,
        }

    def run_attenuation_waveform(self, testcase_params, step_duration=1):
        """Function that generates test params based on the test name.

        Args:
            testcase_params: dict including all test params encoded in test
            name
            step_duration: int representing number of seconds to dwell on each
            atten level
        """
        atten_waveforms = testcase_params['atten_waveforms']
        for atten_idx in range(atten_waveforms['length']):
            start_time = time.time()
            for network, atten_waveform in atten_waveforms.items():
                for atten in self.attenuators:
                    if network in atten.path:
                        atten.set_atten(atten_waveform[atten_idx])
            measure_time = time.time() - start_time
            time.sleep(step_duration - measure_time)

    def compile_atten_waveforms(self, waveform_params):
        """Function to compile all attenuation waveforms for roaming test.

        Args:
            waveform_params: list of dicts representing waveforms to generate
        """
        atten_waveforms = {}
        for network in list(waveform_params[0]):
            atten_waveforms[network] = []

        for waveform in waveform_params:
            for network, network_waveform in waveform.items():
                waveform_vector = self.gen_single_atten_waveform(
                    network_waveform)
                atten_waveforms[network] += waveform_vector

        waveform_lengths = {
            len(atten_waveforms[network])
            for network in atten_waveforms.keys()
        }
        if len(waveform_lengths) != 1:
            raise ValueError(
                'Attenuation waveform length should be equal for all networks.'
            )
        else:
            atten_waveforms['length'] = waveform_lengths.pop()
        return atten_waveforms

    def gen_single_atten_waveform(self, waveform_params):
        """Function to generate a single attenuation waveform for roaming test.

        Args:
            waveform_params: dict representing waveform to generate
        """
        waveform_vector = []
        for section in range(len(waveform_params['atten_levels']) - 1):
            section_limits = waveform_params['atten_levels'][section:section +
                                                             2]
            up_down = (1 - 2 * (section_limits[1] < section_limits[0]))
            temp_section = list(
                range(section_limits[0], section_limits[1] + up_down,
                      up_down * waveform_params['step_size']))
            temp_section = [
                val for val in temp_section
                for _ in range(waveform_params['step_duration'])
            ]
            waveform_vector += temp_section
        waveform_vector *= waveform_params['repetitions']
        return waveform_vector

    def parse_test_params(self, test_name):
        """Function that generates test params based on the test name.

        Args:
            test_name: current test name
        Returns:
            testcase_params: dict including all test params encoded in test
            name
        """
        test_name_params = test_name.split('_')
        testcase_params = collections.OrderedDict()
        if test_name_params[1] == 'smooth':
            testcase_params[
                'roaming_waveforms_params'] = self.testclass_params[
                    'smooth_roaming_waveforms']
        elif test_name_params[1] == 'failover':
            testcase_params[
                'roaming_waveforms_params'] = self.testclass_params[
                    'failover_roaming_waveforms']
        elif test_name_params[1] == 'consistency':
            testcase_params[
                'roaming_waveforms_params'] = self.testclass_params[
                    'consistency_waveforms']
        testcase_params['screen_on'] = test_name_params[4] == 'on'
        testcase_params['traffic_type'] = test_name_params[5]
        return testcase_params

    def _test_traffic_continuity(self):
        """Test function for traffic continuity"""
        # Compile test parameters from config and test name
        testcase_params = self.parse_test_params(self.current_test_name)
        testcase_params.update(self.testclass_params)
        testcase_params['atten_waveforms'] = self.compile_atten_waveforms(
            testcase_params['roaming_waveforms_params'])
        # Run traffic test
        self.setup_roaming_test(testcase_params)
        if testcase_params['traffic_type'] == 'iperf':
            result = self.run_iperf_test(testcase_params)
        elif testcase_params['traffic_type'] == 'ping':
            result = self.run_ping_test(testcase_params)
        # Postprocess results
        self.process_traffic_continuity_results(testcase_params, result)
        self.pass_fail_traffic_continuity(result)

    def _test_roam_consistency(self):
        """Test function for roaming consistency"""
        testcase_params = self.parse_test_params(self.current_test_name)
        testcase_params.update(self.testclass_params)
        # Run traffic test
        secondary_attens = range(
            self.testclass_params['consistency_waveforms']['secondary_loop']
            ['atten_levels'][0], self.testclass_params['consistency_waveforms']
            ['secondary_loop']['atten_levels'][1],
            self.testclass_params['consistency_waveforms']['secondary_loop']
            ['step_size'])
        results = collections.OrderedDict()
        for secondary_atten in secondary_attens:
            primary_waveform = self.gen_single_atten_waveform(
                testcase_params['roaming_waveforms_params']['primary_sweep'])
            secondary_waveform_params = {
                'atten_levels': [secondary_atten, secondary_atten],
                'step_size': 1,
                'step_duration': len(primary_waveform),
                'repetitions': 1
            }
            secondary_waveform = self.gen_single_atten_waveform(
                secondary_waveform_params)
            testcase_params['atten_waveforms'] = {
                'length': len(primary_waveform)
            }
            for network_key, network_info in self.main_network.items():
                if 'primary' in network_info['roaming_label']:
                    testcase_params['atten_waveforms'][
                        network_key] = primary_waveform
                else:
                    testcase_params['atten_waveforms'][
                        network_key] = secondary_waveform
            results[secondary_atten] = []
            for run in range(self.testclass_params['consistency_num_runs']):
                self.setup_roaming_test(testcase_params)
                results[secondary_atten].append(
                    self.run_ping_test(testcase_params))
        # Postprocess results
        self.process_consistency_results(testcase_params, results)
        self.pass_fail_roaming_consistency(results)

    def test_consistency_roaming_screen_on_ping(self):
        self._test_roam_consistency()

    def test_smooth_roaming_screen_on_ping_continuity(self):
        self._test_traffic_continuity()

    def test_smooth_roaming_screen_on_iperf_continuity(self):
        self._test_traffic_continuity()

    def test_failover_roaming_screen_on_ping_continuity(self):
        self._test_traffic_continuity()

    def test_failover_roaming_screen_on_iperf_continuity(self):
        self._test_traffic_continuity()

    def test_smooth_roaming_screen_off_ping_continuity(self):
        self._test_traffic_continuity()

    def test_smooth_roaming_screen_off_iperf_continuity(self):
        self._test_traffic_continuity()

    def test_failover_roaming_screen_off_ping_continuity(self):
        self._test_traffic_continuity()

    def test_failover_roaming_screen_off_iperf_continuity(self):
        self._test_traffic_continuity()
