#!/usr/bin/env python3.4
#
#   Copyright 2022 - The Android Open Source Project
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

import collections
import itertools
import json
import numpy
import os
from functools import partial
from acts import asserts
from acts import context
from acts import base_test
from acts import utils
from acts.metrics.loggers.blackbox import BlackboxMappedMetricLogger
from acts.controllers.utils_lib import ssh
from acts_contrib.test_utils.cellular.keysight_5g_testapp import Keysight5GTestApp
from acts_contrib.test_utils.cellular.performance import cellular_performance_test_utils as cputils
from acts_contrib.test_utils.wifi.wifi_performance_test_utils.bokeh_figure import BokehFigure
from acts_contrib.test_utils.wifi import wifi_performance_test_utils as wputils
from Cellular5GFR2ThroughputTest import Cellular5GFR2ThroughputTest


class Cellular5GFR2SensitivityTest(Cellular5GFR2ThroughputTest):
    """Class to test cellular throughput

    This class implements cellular throughput tests on a lab/callbox setup.
    The class setups up the callbox in the desired configurations, configures
    and connects the phone, and runs traffic/iperf throughput.
    """

    def __init__(self, controllers):
        base_test.BaseTestClass.__init__(self, controllers)
        self.testcase_metric_logger = (
            BlackboxMappedMetricLogger.for_test_case())
        self.testclass_metric_logger = (
            BlackboxMappedMetricLogger.for_test_class())
        self.publish_testcase_metrics = True

    def setup_class(self):
        """Initializes common test hardware and parameters.

        This function initializes hardwares and compiles parameters that are
        common to all tests in this class.
        """
        self.dut = self.android_devices[-1]
        self.testclass_params = self.user_params['sensitivity_test_params']
        self.keysight_test_app = Keysight5GTestApp(
            self.user_params['Keysight5GTestApp'])
        self.testclass_results = collections.OrderedDict()
        self.iperf_server = self.iperf_servers[0]
        self.iperf_client = self.iperf_clients[0]
        self.remote_server = ssh.connection.SshConnection(
            ssh.settings.from_config(
                self.user_params['RemoteServer']['ssh_config']))
        if self.testclass_params.get('reload_scpi', 1):
            self.keysight_test_app.import_scpi_file(
                self.testclass_params['scpi_file'])
        # Configure test retries
        self.user_params['retry_tests'] = [self.__class__.__name__]

        # Turn Airplane mode on
        asserts.assert_true(utils.force_airplane_mode(self.dut, True),
                            'Can not turn on airplane mode.')

    def process_testcase_results(self):
        if self.current_test_name not in self.testclass_results:
            return
        testcase_results = self.testclass_results[self.current_test_name]
        cell_power_list = [
            result['cell_power'] for result in testcase_results['results']
        ]
        dl_bler_list = [
            result['bler_result']['total']['DL']['nack_ratio']
            for result in testcase_results['results']
        ]
        bler_above_threshold = [
            x > self.testclass_params['bler_threshold'] for x in dl_bler_list
        ]
        for idx in range(len(bler_above_threshold)):
            if all(bler_above_threshold[idx:]):
                sensitivity_index = max(idx, 1) - 1
                cell_power_at_sensitivity = cell_power_list[sensitivity_index]
                break
        else:
            sensitivity_index = -1
            cell_power_at_sensitivity = float('nan')
        if min(dl_bler_list) < 0.05:
            testcase_results['sensitivity'] = cell_power_at_sensitivity
        else:
            testcase_results['sensitivity'] = float('nan')

        testcase_results['cell_power_list'] = cell_power_list
        testcase_results['dl_bler_list'] = dl_bler_list

        results_file_path = os.path.join(
            context.get_current_context().get_full_output_path(),
            '{}.json'.format(self.current_test_name))
        with open(results_file_path, 'w') as results_file:
            json.dump(wputils.serialize_dict(testcase_results),
                      results_file,
                      indent=4)

        result_string = ('DL {}CC MCS {} Sensitivity = {}dBm.'.format(
            testcase_results['testcase_params']['num_dl_cells'],
            testcase_results['testcase_params']['dl_mcs'],
            testcase_results['sensitivity']))
        if min(dl_bler_list) < 0.05:
            self.log.info('Test Passed. {}'.format(result_string))
        else:
            self.log.info('Result unreliable. {}'.format(result_string))

    def process_testclass_results(self):
        Cellular5GFR2ThroughputTest.process_testclass_results(self)

        plots = collections.OrderedDict()
        id_fields = ['band', 'num_dl_cells']
        for testcase, testcase_data in self.testclass_results.items():
            testcase_params = testcase_data['testcase_params']
            plot_id = cputils.extract_test_id(testcase_params, id_fields)
            plot_id = tuple(plot_id.items())
            if plot_id not in plots:
                plots[plot_id] = BokehFigure(title='{} {}CC'.format(
                    testcase_params['band'], testcase_params['num_dl_cells']),
                                             x_label='Cell Power (dBm)',
                                             primary_y_label='BLER (%)')
            plots[plot_id].add_line(
                testcase_data['cell_power_list'],
                testcase_data['dl_bler_list'],
                'Channel {}, MCS {}'.format(testcase_params['channel'],
                                            testcase_params['dl_mcs']))
        figure_list = []
        for plot_id, plot in plots.items():
            plot.generate_figure()
            figure_list.append(plot)
        output_file_path = os.path.join(self.log_path, 'results.html')
        BokehFigure.save_figures(figure_list, output_file_path)

    def generate_test_cases(self, bands, channels, mcs_pair_list,
                            num_dl_cells_list, num_ul_cells_list, **kwargs):
        """Function that auto-generates test cases for a test class."""
        test_cases = []

        for band, channel, num_ul_cells, num_dl_cells, mcs_pair in itertools.product(
                bands, channels, num_ul_cells_list, num_dl_cells_list,
                mcs_pair_list):
            if num_ul_cells > num_dl_cells:
                continue
            test_name = 'test_nr_sensitivity_{}_{}_DL_{}CC_mcs{}'.format(
                band, channel, num_dl_cells, mcs_pair[0])
            test_params = collections.OrderedDict(
                band=band,
                channel=channel,
                dl_mcs=mcs_pair[0],
                ul_mcs=mcs_pair[1],
                num_dl_cells=num_dl_cells,
                num_ul_cells=num_ul_cells,
                dl_cell_list=list(range(1, num_dl_cells + 1)),
                ul_cell_list=list(range(1, num_ul_cells + 1)),
                **kwargs)
            setattr(self, test_name,
                    partial(self._test_nr_throughput_bler, test_params))
            test_cases.append(test_name)
        return test_cases


class Cellular5GFR2_AllBands_SensitivityTest(Cellular5GFR2SensitivityTest):

    def __init__(self, controllers):
        super().__init__(controllers)
        self.tests = self.generate_test_cases(['N257', 'N258', 'N260', 'N261'],
                                              ['low', 'mid', 'high'],
                                              [(16, 4), (27, 4)],
                                              list(range(1, 9)), [1],
                                              schedule_scenario="FULL_TPUT",
                                              traffic_direction='DL',
                                              transform_precoding=0)


class Cellular5GFR2_FrequencySweep_SensitivityTest(Cellular5GFR2SensitivityTest
                                                   ):

    def __init__(self, controllers):
        super().__init__(controllers)
        frequency_sweep_params = self.user_params['sensitivity_test_params'][
            'frequency_sweep']
        self.tests = self.generate_test_cases(frequency_sweep_params,
                                              [(16, 4), (27, 4)],
                                              schedule_scenario="FULL_TPUT",
                                              traffic_direction='DL',
                                              transform_precoding=0)

    def generate_test_cases(self, dl_frequency_sweep_params, mcs_pair_list,
                            **kwargs):
        """Function that auto-generates test cases for a test class."""
        test_cases = ['test_load_scpi']

        for band, band_config in dl_frequency_sweep_params.items():
            for num_dl_cells_str, sweep_config in band_config.items():
                num_dl_cells = int(num_dl_cells_str[0])
                num_ul_cells = 1
                freq_vector = numpy.arange(sweep_config[0], sweep_config[1],
                                           sweep_config[2])
                for freq in freq_vector:
                    for mcs_pair in mcs_pair_list:
                        test_name = 'test_nr_sensitivity_{}_{}_DL_{}CC_mcs{}'.format(
                            band, freq, num_dl_cells, mcs_pair[0])
                        test_params = collections.OrderedDict(
                            band=band,
                            channel=freq,
                            dl_mcs=mcs_pair[0],
                            ul_mcs=mcs_pair[1],
                            num_dl_cells=num_dl_cells,
                            num_ul_cells=num_ul_cells,
                            dl_cell_list=list(range(1, num_dl_cells + 1)),
                            ul_cell_list=list(range(1, num_ul_cells + 1)),
                            **kwargs)
                        setattr(
                            self, test_name,
                            partial(self._test_nr_throughput_bler,
                                    test_params))
                        test_cases.append(test_name)
        return test_cases
