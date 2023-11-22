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
import itertools
import json
import os
from acts import base_test
from acts import context
from acts.metrics.loggers.blackbox import BlackboxMetricLogger
from acts.test_utils.wifi import ota_chamber
from acts.test_utils.wifi import wifi_performance_test_utils as wputils
from WifiRvrTest import WifiRvrTest
from WifiPingTest import WifiPingTest


class WifiOtaRvrTest(WifiRvrTest):
    """Class to test over-the-air RvR

    This class implements measures WiFi RvR tests in an OTA chamber. It enables
    setting turntable orientation and other chamber parameters to study
    performance in varying channel conditions
    """

    def __init__(self, controllers):
        base_test.BaseTestClass.__init__(self, controllers)
        self.failure_count_metric = BlackboxMetricLogger.for_test_case(
            metric_name='failure_count')

    def setup_class(self):
        WifiRvrTest.setup_class(self)
        req_params = ['OTAChamber']
        self.unpack_userparams(req_params)
        self.ota_chambers = ota_chamber.create(self.OTAChamber)
        self.ota_chamber = self.ota_chambers[0]

    def teardown_class(self):
        WifiRvrTest.teardown_class(self)
        self.ota_chamber.set_orientation(0)

    def setup_rvr_test(self, testcase_params):
        """Function that gets devices ready for the test.

        Args:
            testcase_params: dict containing test-specific parameters
        """
        # Set turntable orientation
        self.ota_chamber.set_orientation(testcase_params['orientation'])
        # Continue test setup
        WifiRvrTest.setup_rvr_test(self, testcase_params)

    def parse_test_params(self, test_name):
        """Function that generates test params based on the test name."""
        # Call parent parsing function
        testcase_params = WifiRvrTest.parse_test_params(self, test_name)
        # Add orientation information
        test_name_params = test_name.split('_')
        testcase_params['orientation'] = int(test_name_params[6][0:-3])
        return testcase_params

    def generate_test_cases(self, channels, modes, angles, traffic_types,
                            directions):
        test_cases = []
        testcase_wrapper = self._test_rvr
        allowed_configs = {
            'VHT20': [
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 36, 40, 44, 48, 149, 153,
                157, 161
            ],
            'VHT40': [36, 44, 149, 157],
            'VHT80': [36, 149]
        }
        for channel, mode, angle, traffic_type, direction in itertools.product(
                channels, modes, angles, traffic_types, directions):
            if channel not in allowed_configs[mode]:
                continue
            testcase_name = 'test_rvr_{}_{}_ch{}_{}_{}deg'.format(
                traffic_type, direction, channel, mode, angle)
            setattr(self, testcase_name, testcase_wrapper)
            test_cases.append(testcase_name)
        return test_cases


class WifiOtaRvr_StandardOrientation_Test(WifiOtaRvrTest):
    def __init__(self, controllers):
        WifiOtaRvrTest.__init__(self, controllers)
        self.tests = self.generate_test_cases(
            [1, 6, 11, 36, 40, 44, 48, 149, 153, 157, 161],
            ['VHT20', 'VHT40', 'VHT80'], list(range(0, 360,
                                                    45)), ['TCP'], ['DL'])


class WifiOtaRvr_SampleChannel_Test(WifiOtaRvrTest):
    def __init__(self, controllers):
        WifiOtaRvrTest.__init__(self, controllers)
        self.tests = self.generate_test_cases([6, 36, 149], ['VHT20', 'VHT80'],
                                              list(range(0, 360, 45)), ['TCP'],
                                              ['DL'])


class WifiOtaRvr_SingleOrientation_Test(WifiOtaRvrTest):
    def __init__(self, controllers):
        WifiOtaRvrTest.__init__(self, controllers)
        self.tests = self.generate_test_cases(
            [6, 36, 40, 44, 48, 149, 153, 157, 161],
            ['VHT20', 'VHT40', 'VHT80'], [0], ['TCP'], ['DL', 'UL'])


# Ping Tests
class WifiOtaPingTest(WifiPingTest):
    """Class to test over-the-air ping

    This class tests WiFi ping performance in an OTA chamber. It enables
    setting turntable orientation and other chamber parameters to study
    performance in varying channel conditions
    """

    def __init__(self, controllers):
        base_test.BaseTestClass.__init__(self, controllers)
        self.ping_range_metric = BlackboxMetricLogger.for_test_case(
            metric_name='ping_range')
        self.ping_rtt_metric = BlackboxMetricLogger.for_test_case(
            metric_name='ping_rtt')

    def setup_class(self):
        WifiPingTest.setup_class(self)
        req_params = ['OTAChamber']
        self.unpack_userparams(req_params)
        self.ota_chambers = ota_chamber.create(self.OTAChamber)
        self.ota_chamber = self.ota_chambers[0]

    def teardown_class(self):
        self.process_testclass_results()
        self.ota_chamber.set_orientation(0)

    def process_testclass_results(self):
        """Saves all test results to enable comparison."""
        WifiPingTest.process_testclass_results(self)

        range_vs_angle = collections.OrderedDict()
        for test in self.testclass_results:
            curr_params = self.parse_test_params(test['test_name'])
            curr_config = curr_params['channel']
            if curr_config in range_vs_angle:
                range_vs_angle[curr_config]['orientation'].append(
                    curr_params['orientation'])
                range_vs_angle[curr_config]['range'].append(test['range'])
            else:
                range_vs_angle[curr_config] = {
                    'orientation': [curr_params['orientation']],
                    'range': [test['range']]
                }
        figure = wputils.BokehFigure(
            title='Range vs. Orientation',
            x_label='Angle (deg)',
            primary_y='Range (dB)',
        )
        for config, config_data in range_vs_angle.items():
            figure.add_line(config_data['orientation'], config_data['range'],
                            'Channel {}'.format(config))
        current_context = context.get_current_context().get_full_output_path()
        plot_file_path = os.path.join(current_context, 'results.html')
        figure.generate_figure(plot_file_path)

        # Save results
        results_file_path = os.path.join(current_context,
                                         'testclass_summary.json')
        with open(results_file_path, 'w') as results_file:
            json.dump(range_vs_angle, results_file, indent=4)

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
        # Setup turntable
        self.ota_chamber.set_orientation(testcase_params['orientation'])
        # Reset, configure, and connect DUT
        self.setup_dut(testcase_params)

    def parse_test_params(self, test_name):
        """Function that generates test params based on the test name."""
        # Call parent parsing function
        testcase_params = WifiPingTest.parse_test_params(self, test_name)
        # Add orientation information
        test_name_params = test_name.split('_')
        testcase_params['orientation'] = int(test_name_params[5][0:-3])
        return testcase_params

    def generate_test_cases(self, channels, modes, angles):
        test_cases = []
        testcase_wrapper = self._test_ping_range
        allowed_configs = {
            'VHT20': [
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 36, 40, 44, 48, 149, 153,
                157, 161
            ],
            'VHT40': [36, 44, 149, 157],
            'VHT80': [36, 149]
        }
        for channel, mode, angle in itertools.product(channels, modes, angles):
            if channel not in allowed_configs[mode]:
                continue
            testcase_name = 'test_ping_range_ch{}_{}_{}deg'.format(
                channel, mode, angle)
            setattr(self, testcase_name, testcase_wrapper)
            test_cases.append(testcase_name)
        return test_cases


class WifiOtaPing_TenDegree_Test(WifiOtaPingTest):
    def __init__(self, controllers):
        WifiOtaPingTest.__init__(self, controllers)
        self.tests = self.generate_test_cases(
            [1, 6, 11, 36, 40, 44, 48, 149, 153, 157, 161], ['VHT20'],
            list(range(0, 360, 10)))


class WifiOtaPing_45Degree_Test(WifiOtaPingTest):
    def __init__(self, controllers):
        WifiOtaPingTest.__init__(self, controllers)
        self.tests = self.generate_test_cases(
            [1, 6, 11, 36, 40, 44, 48, 149, 153, 157, 161], ['VHT20'],
            list(range(0, 360, 45)))
