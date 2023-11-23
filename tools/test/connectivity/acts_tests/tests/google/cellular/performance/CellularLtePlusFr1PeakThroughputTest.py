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
import csv
import itertools
import json
import re
import os
from acts import context
from acts import base_test
from acts.metrics.loggers.blackbox import BlackboxMappedMetricLogger
from acts_contrib.test_utils.cellular.performance import cellular_performance_test_utils as cputils
from acts_contrib.test_utils.cellular.performance.CellularThroughputBaseTest import CellularThroughputBaseTest
from acts_contrib.test_utils.wifi import wifi_performance_test_utils as wputils

from functools import partial

LONG_SLEEP = 10
MEDIUM_SLEEP = 2
IPERF_TIMEOUT = 10
SHORT_SLEEP = 1
SUBFRAME_LENGTH = 0.001
STOP_COUNTER_LIMIT = 3


class CellularLtePlusFr1PeakThroughputTest(CellularThroughputBaseTest):
    """Base class to test cellular LTE and FR1 throughput

    This class implements cellular LTE & FR1 throughput tests on a callbox setup.
    The class setups up the callbox in the desired configurations, configures
    and connects the phone, and runs traffic/iperf throughput.
    """

    def process_testcase_results(self):
        """Publish test case metrics and save results"""
        if self.current_test_name not in self.testclass_results:
            return
        testcase_data = self.testclass_results[self.current_test_name]
        results_file_path = os.path.join(
            context.get_current_context().get_full_output_path(),
            '{}.json'.format(self.current_test_name))
        with open(results_file_path, 'w') as results_file:
            json.dump(wputils.serialize_dict(testcase_data),
                      results_file,
                      indent=4)
        testcase_result = testcase_data['results'][0]
        metric_map = {
            'tcp_udp_tput': testcase_result.get('iperf_throughput',
                                                float('nan'))
        }
        if testcase_data['testcase_params']['endc_combo_config'][
                'nr_cell_count']:
            metric_map.update({
                'nr_min_dl_tput':
                testcase_result['nr_tput_result']['total']['DL']['min_tput'],
                'nr_max_dl_tput':
                testcase_result['nr_tput_result']['total']['DL']['max_tput'],
                'nr_avg_dl_tput':
                testcase_result['nr_tput_result']['total']['DL']
                ['average_tput'],
                'nr_theoretical_dl_tput':
                testcase_result['nr_tput_result']['total']['DL']
                ['theoretical_tput'],
                'nr_dl_bler':
                testcase_result['nr_bler_result']['total']['DL']['nack_ratio']
                * 100,
                'nr_min_dl_tput':
                testcase_result['nr_tput_result']['total']['UL']['min_tput'],
                'nr_max_dl_tput':
                testcase_result['nr_tput_result']['total']['UL']['max_tput'],
                'nr_avg_dl_tput':
                testcase_result['nr_tput_result']['total']['UL']
                ['average_tput'],
                'nr_theoretical_dl_tput':
                testcase_result['nr_tput_result']['total']['UL']
                ['theoretical_tput'],
                'nr_ul_bler':
                testcase_result['nr_bler_result']['total']['UL']['nack_ratio']
                * 100
            })
        if testcase_data['testcase_params']['endc_combo_config'][
                'lte_cell_count']:
            metric_map.update({
                'lte_min_dl_tput':
                testcase_result['lte_tput_result']['total']['DL']['min_tput'],
                'lte_max_dl_tput':
                testcase_result['lte_tput_result']['total']['DL']['max_tput'],
                'lte_avg_dl_tput':
                testcase_result['lte_tput_result']['total']['DL']
                ['average_tput'],
                'lte_theoretical_dl_tput':
                testcase_result['lte_tput_result']['total']['DL']
                ['theoretical_tput'],
                'lte_dl_bler':
                testcase_result['lte_bler_result']['total']['DL']['nack_ratio']
                * 100,
                'lte_min_dl_tput':
                testcase_result['lte_tput_result']['total']['UL']['min_tput'],
                'lte_max_dl_tput':
                testcase_result['lte_tput_result']['total']['UL']['max_tput'],
                'lte_avg_dl_tput':
                testcase_result['lte_tput_result']['total']['UL']
                ['average_tput'],
                'lte_theoretical_dl_tput':
                testcase_result['lte_tput_result']['total']['UL']
                ['theoretical_tput'],
                'lte_ul_bler':
                testcase_result['lte_bler_result']['total']['UL']['nack_ratio']
                * 100
            })
        if self.publish_testcase_metrics:
            for metric_name, metric_value in metric_map.items():
                self.testcase_metric_logger.add_metric(metric_name,
                                                       metric_value)

    def process_testclass_results(self):
        """Saves CSV with all test results to enable comparison."""
        results_file_path = os.path.join(
            context.get_current_context().get_full_output_path(),
            'results.csv')
        with open(results_file_path, 'w', newline='') as csvfile:
            field_names = [
                'Test Name', 'NR DL Min. Throughput', 'NR DL Max. Throughput',
                'NR DL Avg. Throughput', 'NR DL Theoretical Throughput',
                'NR UL Min. Throughput', 'NR UL Max. Throughput',
                'NR UL Avg. Throughput', 'NR UL Theoretical Throughput',
                'NR DL BLER (%)', 'NR UL BLER (%)', 'LTE DL Min. Throughput',
                'LTE DL Max. Throughput', 'LTE DL Avg. Throughput',
                'LTE DL Theoretical Throughput', 'LTE UL Min. Throughput',
                'LTE UL Max. Throughput', 'LTE UL Avg. Throughput',
                'LTE UL Theoretical Throughput', 'LTE DL BLER (%)',
                'LTE UL BLER (%)', 'TCP/UDP Throughput'
            ]
            writer = csv.DictWriter(csvfile, fieldnames=field_names)
            writer.writeheader()

            for testcase_name, testcase_results in self.testclass_results.items(
            ):
                for result in testcase_results['results']:
                    row_dict = {
                        'Test Name': testcase_name,
                        'TCP/UDP Throughput':
                        result.get('iperf_throughput', 0)
                    }
                    if testcase_results['testcase_params'][
                            'endc_combo_config']['nr_cell_count']:
                        row_dict.update({
                            'NR DL Min. Throughput':
                            result['nr_tput_result']['total']['DL']
                            ['min_tput'],
                            'NR DL Max. Throughput':
                            result['nr_tput_result']['total']['DL']
                            ['max_tput'],
                            'NR DL Avg. Throughput':
                            result['nr_tput_result']['total']['DL']
                            ['average_tput'],
                            'NR DL Theoretical Throughput':
                            result['nr_tput_result']['total']['DL']
                            ['theoretical_tput'],
                            'NR UL Min. Throughput':
                            result['nr_tput_result']['total']['UL']
                            ['min_tput'],
                            'NR UL Max. Throughput':
                            result['nr_tput_result']['total']['UL']
                            ['max_tput'],
                            'NR UL Avg. Throughput':
                            result['nr_tput_result']['total']['UL']
                            ['average_tput'],
                            'NR UL Theoretical Throughput':
                            result['nr_tput_result']['total']['UL']
                            ['theoretical_tput'],
                            'NR DL BLER (%)':
                            result['nr_bler_result']['total']['DL']
                            ['nack_ratio'] * 100,
                            'NR UL BLER (%)':
                            result['nr_bler_result']['total']['UL']
                            ['nack_ratio'] * 100
                        })
                    if testcase_results['testcase_params'][
                            'endc_combo_config']['lte_cell_count']:
                        row_dict.update({
                            'LTE DL Min. Throughput':
                            result['lte_tput_result']['total']['DL']
                            ['min_tput'],
                            'LTE DL Max. Throughput':
                            result['lte_tput_result']['total']['DL']
                            ['max_tput'],
                            'LTE DL Avg. Throughput':
                            result['lte_tput_result']['total']['DL']
                            ['average_tput'],
                            'LTE DL Theoretical Throughput':
                            result['lte_tput_result']['total']['DL']
                            ['theoretical_tput'],
                            'LTE UL Min. Throughput':
                            result['lte_tput_result']['total']['UL']
                            ['min_tput'],
                            'LTE UL Max. Throughput':
                            result['lte_tput_result']['total']['UL']
                            ['max_tput'],
                            'LTE UL Avg. Throughput':
                            result['lte_tput_result']['total']['UL']
                            ['average_tput'],
                            'LTE UL Theoretical Throughput':
                            result['lte_tput_result']['total']['UL']
                            ['theoretical_tput'],
                            'LTE DL BLER (%)':
                            result['lte_bler_result']['total']['DL']
                            ['nack_ratio'] * 100,
                            'LTE UL BLER (%)':
                            result['lte_bler_result']['total']['UL']
                            ['nack_ratio'] * 100
                        })
                    writer.writerow(row_dict)

    def get_per_cell_power_sweeps(self, testcase_params):
        """Function to get per cell power sweep lists

        Args:
            testcase_params: dict containing all test case params
        Returns:
            cell_power_sweeps: list of cell power sweeps for each cell under test
        """
        cell_power_sweeps = []
        for cell in testcase_params['endc_combo_config']['cell_list']:
            if cell['cell_type'] == 'LTE':
                sweep = [self.testclass_params['lte_cell_power']]
            else:
                sweep = [self.testclass_params['nr_cell_power']]
            cell_power_sweeps.append(sweep)
        return cell_power_sweeps


class CellularLteFr1EndcPeakThroughputTest(CellularLtePlusFr1PeakThroughputTest
                                           ):
    """Class to test cellular LTE/FR1 ENDC combo list"""

    def __init__(self, controllers):
        base_test.BaseTestClass.__init__(self, controllers)
        self.testcase_metric_logger = (
            BlackboxMappedMetricLogger.for_test_case())
        self.testclass_metric_logger = (
            BlackboxMappedMetricLogger.for_test_class())
        self.publish_testcase_metrics = True
        self.testclass_params = self.user_params['throughput_test_params']
        self.tests = self.generate_test_cases([(27, 4), (4, 27)],
                                              lte_dl_mcs_table='QAM256',
                                              lte_ul_mcs_table='QAM256',
                                              transform_precoding=0)

    def generate_endc_combo_config(self, endc_combo_str):
        """Function to generate ENDC combo config from combo string

        Args:
            endc_combo_str: ENDC combo descriptor (e.g. B48A[4];A[1]+N5A[2];A[1])
        Returns:
            endc_combo_config: dictionary with all ENDC combo settings
        """
        endc_combo_str = endc_combo_str.replace(' ', '')
        endc_combo_list = endc_combo_str.split('+')
        endc_combo_list = [combo.split(';') for combo in endc_combo_list]
        endc_combo_config = collections.OrderedDict()
        cell_config_list = list()
        lte_cell_count = 0
        nr_cell_count = 0
        lte_scc_list = []
        nr_dl_carriers = []
        nr_ul_carriers = []
        lte_carriers = []

        for cell in endc_combo_list:
            cell_config = {}
            dl_config_str = cell[0]
            dl_config_regex = re.compile(
                r'(?P<cell_type>[B,N])(?P<band>[0-9]+)(?P<bandwidth_class>[A-Z])\[(?P<mimo_config>[0-9])\]'
            )
            dl_config_match = re.match(dl_config_regex, dl_config_str)
            if dl_config_match.group('cell_type') == 'B':
                cell_config['cell_type'] = 'LTE'
                lte_cell_count = lte_cell_count + 1
                cell_config['cell_number'] = lte_cell_count
                if cell_config['cell_number'] == 1:
                    cell_config['pcc'] = 1
                    endc_combo_config['lte_pcc'] = cell_config['cell_number']
                else:
                    cell_config['pcc'] = 0
                    lte_scc_list.append(cell_config['cell_number'])
                cell_config['band'] = dl_config_match.group('band')
                cell_config['duplex_mode'] = 'FDD' if int(
                    cell_config['band']
                ) in cputils.DUPLEX_MODE_TO_BAND_MAPPING['LTE'][
                    'FDD'] else 'TDD'
                cell_config['dl_mimo_config'] = 'D{nss}U{nss}'.format(
                    nss=dl_config_match.group('mimo_config'))
                if int(dl_config_match.group('mimo_config')) == 1:
                    cell_config['transmission_mode'] = 'TM1'
                elif int(dl_config_match.group('mimo_config')) == 2:
                    cell_config['transmission_mode'] = 'TM2'
                else:
                    cell_config['transmission_mode'] = 'TM3'
                lte_carriers.append(cell_config['cell_number'])
            else:
                cell_config['cell_type'] = 'NR5G'
                nr_cell_count = nr_cell_count + 1
                cell_config['cell_number'] = nr_cell_count
                nr_dl_carriers.append(cell_config['cell_number'])
                cell_config['band'] = 'N' + dl_config_match.group('band')
                cell_config['duplex_mode'] = 'FDD' if cell_config[
                    'band'] in cputils.DUPLEX_MODE_TO_BAND_MAPPING['NR5G'][
                        'FDD'] else 'TDD'
                cell_config['subcarrier_spacing'] = 'MU0' if cell_config[
                    'duplex_mode'] == 'FDD' else 'MU1'
                cell_config['dl_mimo_config'] = 'N{nss}X{nss}'.format(
                    nss=dl_config_match.group('mimo_config'))

            cell_config['dl_bandwidth_class'] = dl_config_match.group(
                'bandwidth_class')
            cell_config['dl_bandwidth'] = 'BW20'
            cell_config['ul_enabled'] = len(cell) > 1
            if cell_config['ul_enabled']:
                ul_config_str = cell[1]
                ul_config_regex = re.compile(
                    r'(?P<bandwidth_class>[A-Z])\[(?P<mimo_config>[0-9])\]')
                ul_config_match = re.match(ul_config_regex, ul_config_str)
                cell_config['ul_bandwidth_class'] = ul_config_match.group(
                    'bandwidth_class')
                cell_config['ul_mimo_config'] = 'N{nss}X{nss}'.format(
                    nss=ul_config_match.group('mimo_config'))
                if cell_config['cell_type'] == 'NR5G':
                    nr_ul_carriers.append(cell_config['cell_number'])
            cell_config_list.append(cell_config)
        endc_combo_config['lte_cell_count'] = lte_cell_count
        endc_combo_config['nr_cell_count'] = nr_cell_count
        endc_combo_config['nr_dl_carriers'] = nr_dl_carriers
        endc_combo_config['nr_ul_carriers'] = nr_ul_carriers
        endc_combo_config['cell_list'] = cell_config_list
        endc_combo_config['lte_scc_list'] = lte_scc_list
        endc_combo_config['lte_carriers'] = lte_carriers
        return endc_combo_config

    def generate_test_cases(self, mcs_pair_list, **kwargs):
        test_cases = []

        with open(self.testclass_params['endc_combo_file'],
                  'r') as endc_combos:
            for endc_combo_str in endc_combos:
                if endc_combo_str[0] == '#':
                    continue
                endc_combo_config = self.generate_endc_combo_config(
                    endc_combo_str)
                special_chars = '+[];\n'
                for char in special_chars:
                    endc_combo_str = endc_combo_str.replace(char, '_')
                endc_combo_str = endc_combo_str.replace('__', '_')
                endc_combo_str = endc_combo_str.strip('_')
                for mcs_pair in mcs_pair_list:
                    test_name = 'test_lte_fr1_endc_{}_dl_mcs{}_ul_mcs{}'.format(
                        endc_combo_str, mcs_pair[0], mcs_pair[1])
                    test_params = collections.OrderedDict(
                        endc_combo_config=endc_combo_config,
                        nr_dl_mcs=mcs_pair[0],
                        nr_ul_mcs=mcs_pair[1],
                        lte_dl_mcs=mcs_pair[0],
                        lte_ul_mcs=mcs_pair[1],
                        **kwargs)
                    setattr(self, test_name,
                            partial(self._test_throughput_bler, test_params))
                    test_cases.append(test_name)
        return test_cases


class CellularSingleCellThroughputTest(CellularLtePlusFr1PeakThroughputTest):
    """Base Class to test single cell LTE or LTE/FR1"""

    def generate_endc_combo_config(self, test_config):
        """Function to generate ENDC combo config from CSV test config

        Args:
            test_config: dict containing ENDC combo config from CSV
        Returns:
            endc_combo_config: dictionary with all ENDC combo settings
        """
        endc_combo_config = collections.OrderedDict()
        lte_cell_count = 0
        nr_cell_count = 0
        lte_scc_list = []
        nr_dl_carriers = []
        nr_ul_carriers = []
        lte_carriers = []

        cell_config_list = []
        if test_config['lte_band']:
            lte_cell = {
                'cell_type':
                'LTE',
                'cell_number':
                1,
                'pcc':
                1,
                'band':
                test_config['lte_band'],
                'dl_bandwidth':
                test_config['lte_bandwidth'],
                'ul_enabled':
                1,
                'duplex_mode':
                test_config['lte_duplex_mode'],
                'dl_mimo_config':
                'D{nss}U{nss}'.format(nss=test_config['lte_dl_mimo_config']),
                'ul_mimo_config':
                'D{nss}U{nss}'.format(nss=test_config['lte_ul_mimo_config'])
            }
            if int(test_config['lte_dl_mimo_config']) == 1:
                lte_cell['transmission_mode'] = 'TM1'
            elif int(test_config['lte_dl_mimo_config']) == 2:
                lte_cell['transmission_mode'] = 'TM2'
            else:
                lte_cell['transmission_mode'] = 'TM3'
            cell_config_list.append(lte_cell)
            endc_combo_config['lte_pcc'] = 1
            lte_cell_count = 1
            lte_carriers = [1]

        if test_config['nr_band']:
            nr_cell = {
                'cell_type':
                'NR5G',
                'cell_number':
                1,
                'band':
                test_config['nr_band'],
                'duplex_mode':
                test_config['nr_duplex_mode'],
                'dl_mimo_config':
                'N{nss}X{nss}'.format(nss=test_config['nr_dl_mimo_config']),
                'dl_bandwidth_class':
                'A',
                'dl_bandwidth':
                test_config['nr_bandwidth'],
                'ul_enabled':
                1,
                'ul_bandwidth_class':
                'A',
                'ul_mimo_config':
                'N{nss}X{nss}'.format(nss=test_config['nr_ul_mimo_config']),
                'subcarrier_spacing':
                'MU0' if test_config['nr_scs'] == '15' else 'MU1'
            }
            cell_config_list.append(nr_cell)
            nr_cell_count = 1
            nr_dl_carriers = [1]
            nr_ul_carriers = [1]

        endc_combo_config['lte_cell_count'] = lte_cell_count
        endc_combo_config['nr_cell_count'] = nr_cell_count
        endc_combo_config['nr_dl_carriers'] = nr_dl_carriers
        endc_combo_config['nr_ul_carriers'] = nr_ul_carriers
        endc_combo_config['cell_list'] = cell_config_list
        endc_combo_config['lte_scc_list'] = lte_scc_list
        endc_combo_config['lte_carriers'] = lte_carriers
        return endc_combo_config


class CellularFr1SingleCellPeakThroughputTest(CellularSingleCellThroughputTest
                                              ):
    """Class to test single cell FR1 NSA mode"""

    def __init__(self, controllers):
        base_test.BaseTestClass.__init__(self, controllers)
        self.testcase_metric_logger = (
            BlackboxMappedMetricLogger.for_test_case())
        self.testclass_metric_logger = (
            BlackboxMappedMetricLogger.for_test_class())
        self.publish_testcase_metrics = True
        self.testclass_params = self.user_params['throughput_test_params']
        self.tests = self.generate_test_cases(
            nr_mcs_pair_list=[(27, 4), (4, 27)],
            nr_channel_list=['LOW', 'MID', 'HIGH'],
            transform_precoding=0,
            lte_dl_mcs=4,
            lte_dl_mcs_table='QAM256',
            lte_ul_mcs=4,
            lte_ul_mcs_table='QAM64')

    def generate_test_cases(self, nr_mcs_pair_list, nr_channel_list, **kwargs):

        test_cases = []
        with open(self.testclass_params['nr_single_cell_configs'],
                  'r') as csvfile:
            test_configs = csv.DictReader(csvfile)
            for test_config, nr_channel, nr_mcs_pair in itertools.product(
                    test_configs, nr_channel_list, nr_mcs_pair_list):
                if int(test_config['skip_test']):
                    continue
                endc_combo_config = self.generate_endc_combo_config(
                    test_config)
                endc_combo_config['cell_list'][1]['channel'] = nr_channel
                test_name = 'test_fr1_{}_{}_dl_mcs{}_ul_mcs{}'.format(
                    test_config['nr_band'], nr_channel.lower(), nr_mcs_pair[0],
                    nr_mcs_pair[1])
                test_params = collections.OrderedDict(
                    endc_combo_config=endc_combo_config,
                    nr_dl_mcs=nr_mcs_pair[0],
                    nr_ul_mcs=nr_mcs_pair[1],
                    **kwargs)
                setattr(self, test_name,
                        partial(self._test_throughput_bler, test_params))
                test_cases.append(test_name)
        return test_cases


class CellularLteSingleCellPeakThroughputTest(CellularSingleCellThroughputTest
                                              ):
    """Class to test single cell LTE"""

    def __init__(self, controllers):
        base_test.BaseTestClass.__init__(self, controllers)
        self.testcase_metric_logger = (
            BlackboxMappedMetricLogger.for_test_case())
        self.testclass_metric_logger = (
            BlackboxMappedMetricLogger.for_test_class())
        self.publish_testcase_metrics = True
        self.testclass_params = self.user_params['throughput_test_params']
        self.tests = self.generate_test_cases(lte_mcs_pair_list=[
            (('QAM256', 27), ('QAM256', 4)), (('QAM256', 4), ('QAM256', 27))
        ],
                                              transform_precoding=0)

    def generate_test_cases(self, lte_mcs_pair_list, **kwargs):
        test_cases = []
        with open(self.testclass_params['lte_single_cell_configs'],
                  'r') as csvfile:
            test_configs = csv.DictReader(csvfile)
            for test_config, lte_mcs_pair in itertools.product(
                    test_configs, lte_mcs_pair_list):
                if int(test_config['skip_test']):
                    continue
                endc_combo_config = self.generate_endc_combo_config(
                    test_config)
                test_name = 'test_lte_B{}_dl_{}_mcs{}_ul_{}_mcs{}'.format(
                    test_config['lte_band'], lte_mcs_pair[0][0],
                    lte_mcs_pair[0][1], lte_mcs_pair[1][0], lte_mcs_pair[1][1])
                test_params = collections.OrderedDict(
                    endc_combo_config=endc_combo_config,
                    lte_dl_mcs_table=lte_mcs_pair[0][0],
                    lte_dl_mcs=lte_mcs_pair[0][1],
                    lte_ul_mcs_table=lte_mcs_pair[1][0],
                    lte_ul_mcs=lte_mcs_pair[1][1],
                    **kwargs)
                setattr(self, test_name,
                        partial(self._test_throughput_bler, test_params))
                test_cases.append(test_name)
        return test_cases
