#!/usr/bin/env python
#
# Copyright 2018, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Unittests for atest_tf_test_runner."""

import os
import sys
import tempfile
import unittest
import json
import socket
import mock

# pylint: disable=import-error
import constants
import unittest_constants as uc
import unittest_utils
import atest_tf_test_runner as atf_tr
import event_handler
from test_finders import test_info

if sys.version_info[0] == 2:
    from StringIO import StringIO
else:
    from io import StringIO

#pylint: disable=protected-access
#pylint: disable=invalid-name
TEST_INFO_DIR = '/tmp/atest_run_1510085893_pi_Nbi'
METRICS_DIR = '%s/baseline-metrics' % TEST_INFO_DIR
METRICS_DIR_ARG = '--metrics-folder %s ' % METRICS_DIR
RUN_CMD_ARGS = '{metrics}--log-level WARN{serial}'
RUN_CMD = atf_tr.AtestTradefedTestRunner._RUN_CMD.format(
    exe=atf_tr.AtestTradefedTestRunner.EXECUTABLE,
    template=atf_tr.AtestTradefedTestRunner._TF_TEMPLATE,
    args=RUN_CMD_ARGS)
FULL_CLASS2_NAME = 'android.jank.cts.ui.SomeOtherClass'
CLASS2_FILTER = test_info.TestFilter(FULL_CLASS2_NAME, frozenset())
METHOD2_FILTER = test_info.TestFilter(uc.FULL_CLASS_NAME, frozenset([uc.METHOD2_NAME]))
MODULE_ARG1 = [(constants.TF_INCLUDE_FILTER_OPTION, "A"),
               (constants.TF_INCLUDE_FILTER_OPTION, "B")]
MODULE_ARG2 = []
CLASS2_METHOD_FILTER = test_info.TestFilter(FULL_CLASS2_NAME,
                                            frozenset([uc.METHOD_NAME, uc.METHOD2_NAME]))
MODULE2_INFO = test_info.TestInfo(uc.MODULE2_NAME,
                                  atf_tr.AtestTradefedTestRunner.NAME,
                                  set(),
                                  data={constants.TI_REL_CONFIG: uc.CONFIG2_FILE,
                                        constants.TI_FILTER: frozenset()})
CLASS1_BUILD_TARGETS = {'class_1_build_target'}
CLASS1_INFO = test_info.TestInfo(uc.MODULE_NAME,
                                 atf_tr.AtestTradefedTestRunner.NAME,
                                 CLASS1_BUILD_TARGETS,
                                 data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
                                       constants.TI_FILTER: frozenset([uc.CLASS_FILTER])})
CLASS2_BUILD_TARGETS = {'class_2_build_target'}
CLASS2_INFO = test_info.TestInfo(uc.MODULE_NAME,
                                 atf_tr.AtestTradefedTestRunner.NAME,
                                 CLASS2_BUILD_TARGETS,
                                 data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
                                       constants.TI_FILTER: frozenset([CLASS2_FILTER])})
CLASS3_BUILD_TARGETS = {'class_3_build_target'}
CLASS3_INFO = test_info.TestInfo(uc.MODULE_NAME,
                                 atf_tr.AtestTradefedTestRunner.NAME,
                                 CLASS3_BUILD_TARGETS,
                                 data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
                                       constants.TI_FILTER: frozenset(),
                                       constants.TI_MODULE_ARG: MODULE_ARG1})
CLASS4_BUILD_TARGETS = {'class_4_build_target'}
CLASS4_INFO = test_info.TestInfo(uc.MODULE_NAME,
                                 atf_tr.AtestTradefedTestRunner.NAME,
                                 CLASS4_BUILD_TARGETS,
                                 data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
                                       constants.TI_FILTER: frozenset(),
                                       constants.TI_MODULE_ARG: MODULE_ARG2})
CLASS1_CLASS2_MODULE_INFO = test_info.TestInfo(
    uc.MODULE_NAME,
    atf_tr.AtestTradefedTestRunner.NAME,
    uc.MODULE_BUILD_TARGETS | CLASS1_BUILD_TARGETS | CLASS2_BUILD_TARGETS,
    uc.MODULE_DATA)
FLAT_CLASS_INFO = test_info.TestInfo(
    uc.MODULE_NAME,
    atf_tr.AtestTradefedTestRunner.NAME,
    CLASS1_BUILD_TARGETS | CLASS2_BUILD_TARGETS,
    data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
          constants.TI_FILTER: frozenset([uc.CLASS_FILTER, CLASS2_FILTER])})
FLAT2_CLASS_INFO = test_info.TestInfo(
    uc.MODULE_NAME,
    atf_tr.AtestTradefedTestRunner.NAME,
    CLASS3_BUILD_TARGETS | CLASS4_BUILD_TARGETS,
    data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
          constants.TI_FILTER: frozenset(),
          constants.TI_MODULE_ARG: MODULE_ARG1 + MODULE_ARG2})
GTF_INT_CONFIG = os.path.join(uc.GTF_INT_DIR, uc.GTF_INT_NAME + '.xml')
CLASS2_METHOD_INFO = test_info.TestInfo(
    uc.MODULE_NAME,
    atf_tr.AtestTradefedTestRunner.NAME,
    set(),
    data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
          constants.TI_FILTER:
              frozenset([test_info.TestFilter(
                  FULL_CLASS2_NAME, frozenset([uc.METHOD_NAME, uc.METHOD2_NAME]))])})
METHOD_AND_CLASS2_METHOD = test_info.TestInfo(
    uc.MODULE_NAME,
    atf_tr.AtestTradefedTestRunner.NAME,
    uc.MODULE_BUILD_TARGETS,
    data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
          constants.TI_FILTER: frozenset([uc.METHOD_FILTER, CLASS2_METHOD_FILTER])})
METHOD_METHOD2_AND_CLASS2_METHOD = test_info.TestInfo(
    uc.MODULE_NAME,
    atf_tr.AtestTradefedTestRunner.NAME,
    uc.MODULE_BUILD_TARGETS,
    data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
          constants.TI_FILTER: frozenset([uc.FLAT_METHOD_FILTER, CLASS2_METHOD_FILTER])})
METHOD2_INFO = test_info.TestInfo(
    uc.MODULE_NAME,
    atf_tr.AtestTradefedTestRunner.NAME,
    set(),
    data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
          constants.TI_FILTER: frozenset([METHOD2_FILTER])})

EVENTS_NORMAL = [
    ('TEST_MODULE_STARTED', {
        'moduleContextFileName':'serial-util1146216{974}2772610436.ser',
        'moduleName':'someTestModule'}),
    ('TEST_RUN_STARTED', {'testCount': 2}),
    ('TEST_STARTED', {'start_time':52, 'className':'someClassName',
                      'testName':'someTestName'}),
    ('TEST_ENDED', {'end_time':1048, 'className':'someClassName',
                    'testName':'someTestName'}),
    ('TEST_STARTED', {'start_time':48, 'className':'someClassName2',
                      'testName':'someTestName2'}),
    ('TEST_FAILED', {'className':'someClassName2', 'testName':'someTestName2',
                     'trace': 'someTrace'}),
    ('TEST_ENDED', {'end_time':9876450, 'className':'someClassName2',
                    'testName':'someTestName2'}),
    ('TEST_RUN_ENDED', {}),
    ('TEST_MODULE_ENDED', {'foo': 'bar'}),
]

class AtestTradefedTestRunnerUnittests(unittest.TestCase):
    """Unit tests for atest_tf_test_runner.py"""

    def setUp(self):
        self.tr = atf_tr.AtestTradefedTestRunner(results_dir=TEST_INFO_DIR)

    def tearDown(self):
        mock.patch.stopall()

    @mock.patch.object(atf_tr.AtestTradefedTestRunner,
                       '_start_socket_server')
    @mock.patch.object(atf_tr.AtestTradefedTestRunner,
                       'run')
    @mock.patch.object(atf_tr.AtestTradefedTestRunner,
                       '_exec_with_tf_polling')
    @mock.patch.object(atf_tr.AtestTradefedTestRunner,
                       '_create_test_args', return_value=['some_args'])
    @mock.patch.object(atf_tr.AtestTradefedTestRunner,
                       'generate_run_commands', return_value='some_cmd')
    @mock.patch.object(atf_tr.AtestTradefedTestRunner,
                       '_process_connection', return_value=None)
    @mock.patch('os.killpg', return_value=None)
    @mock.patch('os.getpgid', return_value=None)
    @mock.patch('signal.signal', return_value=None)
    def test_run_tests_pretty(self, _signal, _pgid, _killpg, _process,
                              _run_cmd, _test_args, mock_exec_w_poll,
                              mock_run, mock_start_socket_server):
        """Test _run_tests_pretty method."""
        mock_subproc = mock.Mock()
        mock_run.return_value = mock_subproc
        mock_subproc.returncode = 0
        mock_server = mock.Mock()
        mock_server.getsockname.return_value = ('', '')
        mock_start_socket_server.return_value = mock_server
        mock_reporter = mock.Mock()

        # Test no early TF exit
        mock_exec_w_poll.return_value = ('some_conn', 'some_addr')
        self.tr.run_tests_pretty([MODULE2_INFO], {}, mock_reporter)

        # Test early TF exit
        tmp_file = tempfile.NamedTemporaryFile()
        with open(tmp_file.name, 'w') as f:
            f.write("tf msg")
        self.tr.test_log_file = tmp_file
        mock_exec_w_poll.side_effect = atf_tr.TradeFedExitError()
        capture_output = StringIO()
        sys.stdout = capture_output
        self.assertRaises(atf_tr.TradeFedExitError, self.tr.run_tests_pretty,
                          [MODULE2_INFO], {}, mock_reporter)
        sys.stdout = sys.__stdout__
        self.assertTrue('tf msg' in capture_output.getvalue())

    def test_exec_with_tf_polling(self):
        """Test _exec_with_tf_polling method."""
        mock_socket_func = mock.Mock()
        mock_socket_func.side_effect = [socket.timeout, socket.timeout,
                                        socket.timeout]
        mock_tf_subproc = mock.Mock()
        exit_code = 7
        mock_tf_subproc.poll.side_effect = [None, None, exit_code]
        mock_tf_subproc.returncode.returns = exit_code
        # First call should raise, because TF exits before socket_func returns
        self.assertRaises(atf_tr.TradeFedExitError,
                          self.tr._exec_with_tf_polling,
                          mock_socket_func, mock_tf_subproc)
        # Second call succeeds because socket_func returns before TF exits
        mock_socket_func.side_effect = [socket.timeout, 'some_return_value']
        mock_tf_subproc.poll.side_effect = [None]
        self.tr._exec_with_tf_polling(mock_socket_func, mock_tf_subproc)

    def test_start_socket_server(self):
        """Test start_socket_server method."""
        server = self.tr._start_socket_server()
        host, port = server.getsockname()
        self.assertEquals(host, atf_tr.SOCKET_HOST)
        self.assertLessEqual(port, 65535)
        self.assertGreaterEqual(port, 1024)
        server.close()

    @mock.patch('os.path.exists')
    @mock.patch.dict('os.environ', {'APE_API_KEY':'/tmp/123.json'})
    def test_try_set_gts_authentication_key_is_set_by_user(self, mock_exist):
        """Test try_set_authentication_key_is_set_by_user method."""
        # Test key is set by user.
        self.tr._try_set_gts_authentication_key()
        mock_exist.assert_not_called()

    @mock.patch('constants.GTS_GOOGLE_SERVICE_ACCOUNT')
    @mock.patch('os.path.exists')
    def test_try_set_gts_authentication_key_not_set(self, mock_exist, mock_key):
        """Test try_set_authentication_key_not_set method."""
        # Test key neither exists nor set by user.
        mock_exist.return_value = False
        mock_key.return_value = ''
        self.tr._try_set_gts_authentication_key()
        self.assertEquals(os.environ.get('APE_API_KEY'), None)

    @mock.patch.object(event_handler.EventHandler, 'process_event')
    def test_process_connection(self, mock_pe):
        """Test _process_connection method."""
        mock_socket = mock.Mock()
        socket_data = ['%s %s' % (name, json.dumps(data))
                       for name, data in EVENTS_NORMAL]
        socket_data.append('')
        mock_socket.recv.side_effect = socket_data
        self.tr._process_connection(mock_socket, mock_pe)

        calls = [mock.call.process_event(name, data) for name, data in EVENTS_NORMAL]
        mock_pe.assert_has_calls(calls)

    @mock.patch.object(event_handler.EventHandler, 'process_event')
    def test_process_connection_multiple_lines_in_single_recv(self, mock_pe):
        """Test _process_connection when recv reads multiple lines in one go."""
        mock_socket = mock.Mock()
        squashed_events = '\n'.join(['%s %s' % (name, json.dumps(data))
                                     for name, data in EVENTS_NORMAL])
        socket_data = [squashed_events, '']
        mock_socket.recv.side_effect = socket_data
        self.tr._process_connection(mock_socket, mock_pe)
        calls = [mock.call.process_event(name, data) for name, data in EVENTS_NORMAL]
        mock_pe.assert_has_calls(calls)

    @mock.patch.object(event_handler.EventHandler, 'process_event')
    def test_process_connection_with_buffering(self, mock_pe):
        """Test _process_connection when events overflow socket buffer size"""
        mock_socket = mock.Mock()
        module_events = [EVENTS_NORMAL[0], EVENTS_NORMAL[-1]]
        socket_events = ['%s %s' % (name, json.dumps(data))
                         for name, data in module_events]
        # test try-block code by breaking apart first event after first }
        index = socket_events[0].index('}') + 1
        socket_data = [socket_events[0][:index], socket_events[0][index:]]
        # test non-try block buffering with second event
        socket_data.extend([socket_events[1][:-4], socket_events[1][-4:], ''])
        mock_socket.recv.side_effect = socket_data
        self.tr._process_connection(mock_socket, mock_pe)
        calls = [mock.call.process_event(name, data) for name, data in module_events]
        mock_pe.assert_has_calls(calls)

    @mock.patch('os.environ.get', return_value=None)
    @mock.patch.object(atf_tr.AtestTradefedTestRunner, '_generate_metrics_folder')
    @mock.patch('atest_utils.get_result_server_args')
    def test_generate_run_commands_without_serial_env(self, mock_resultargs, mock_mertrics, _):
        """Test generate_run_command method."""
        # Basic Run Cmd
        mock_resultargs.return_value = []
        mock_mertrics.return_value = ''
        unittest_utils.assert_strict_equal(
            self,
            self.tr.generate_run_commands([], {}),
            [RUN_CMD.format(metrics='', serial='')])
        mock_mertrics.return_value = METRICS_DIR
        unittest_utils.assert_strict_equal(
            self,
            self.tr.generate_run_commands([], {}),
            [RUN_CMD.format(metrics=METRICS_DIR_ARG, serial='')])
        # Run cmd with result server args.
        result_arg = '--result_arg'
        mock_resultargs.return_value = [result_arg]
        mock_mertrics.return_value = ''
        unittest_utils.assert_strict_equal(
            self,
            self.tr.generate_run_commands([], {}),
            [RUN_CMD.format(metrics='', serial='') + ' ' + result_arg])

    @mock.patch('os.environ.get')
    @mock.patch.object(atf_tr.AtestTradefedTestRunner, '_generate_metrics_folder')
    @mock.patch('atest_utils.get_result_server_args')
    def test_generate_run_commands_with_serial_env(self, mock_resultargs, mock_mertrics, mock_env):
        """Test generate_run_command method."""
        # Basic Run Cmd
        env_device_serial = 'env-device-0'
        mock_resultargs.return_value = []
        mock_mertrics.return_value = ''
        mock_env.return_value = env_device_serial
        env_serial_arg = ' --serial %s' % env_device_serial
        # Serial env be set and without --serial arg.
        unittest_utils.assert_strict_equal(
            self,
            self.tr.generate_run_commands([], {}),
            [RUN_CMD.format(metrics='', serial=env_serial_arg)])
        # Serial env be set but with --serial arg.
        arg_device_serial = 'arg-device-0'
        arg_serial_arg = ' --serial %s' % arg_device_serial
        unittest_utils.assert_strict_equal(
            self,
            self.tr.generate_run_commands([], {constants.SERIAL:arg_device_serial}),
            [RUN_CMD.format(metrics='', serial=arg_serial_arg)])
        # Serial env be set but with -n arg
        unittest_utils.assert_strict_equal(
            self,
            self.tr.generate_run_commands([], {constants.HOST}),
            [RUN_CMD.format(metrics='', serial='') +
             ' -n --prioritize-host-config --skip-host-arch-check'])


    def test_flatten_test_filters(self):
        """Test _flatten_test_filters method."""
        # No Flattening
        filters = self.tr._flatten_test_filters({uc.CLASS_FILTER})
        unittest_utils.assert_strict_equal(self, frozenset([uc.CLASS_FILTER]),
                                           filters)
        filters = self.tr._flatten_test_filters({CLASS2_FILTER})
        unittest_utils.assert_strict_equal(
            self, frozenset([CLASS2_FILTER]), filters)
        filters = self.tr._flatten_test_filters({uc.METHOD_FILTER})
        unittest_utils.assert_strict_equal(
            self, frozenset([uc.METHOD_FILTER]), filters)
        filters = self.tr._flatten_test_filters({uc.METHOD_FILTER,
                                                 CLASS2_METHOD_FILTER})
        unittest_utils.assert_strict_equal(
            self, frozenset([uc.METHOD_FILTER, CLASS2_METHOD_FILTER]), filters)
        # Flattening
        filters = self.tr._flatten_test_filters({uc.METHOD_FILTER,
                                                 METHOD2_FILTER})
        unittest_utils.assert_strict_equal(
            self, filters, frozenset([uc.FLAT_METHOD_FILTER]))
        filters = self.tr._flatten_test_filters({uc.METHOD_FILTER,
                                                 METHOD2_FILTER,
                                                 CLASS2_METHOD_FILTER,})
        unittest_utils.assert_strict_equal(
            self, filters, frozenset([uc.FLAT_METHOD_FILTER,
                                      CLASS2_METHOD_FILTER]))

    def test_flatten_test_infos(self):
        """Test _flatten_test_infos method."""
        # No Flattening
        test_infos = self.tr._flatten_test_infos({uc.MODULE_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {uc.MODULE_INFO})

        test_infos = self.tr._flatten_test_infos([uc.MODULE_INFO, MODULE2_INFO])
        unittest_utils.assert_equal_testinfo_sets(
            self, test_infos, {uc.MODULE_INFO, MODULE2_INFO})

        test_infos = self.tr._flatten_test_infos({CLASS1_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {CLASS1_INFO})

        test_infos = self.tr._flatten_test_infos({uc.INT_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {uc.INT_INFO})

        test_infos = self.tr._flatten_test_infos({uc.METHOD_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {uc.METHOD_INFO})

        # Flattening
        test_infos = self.tr._flatten_test_infos({CLASS1_INFO, CLASS2_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {FLAT_CLASS_INFO})

        test_infos = self.tr._flatten_test_infos({CLASS1_INFO, uc.INT_INFO,
                                                  CLASS2_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {uc.INT_INFO,
                                                   FLAT_CLASS_INFO})

        test_infos = self.tr._flatten_test_infos({CLASS1_INFO, uc.MODULE_INFO,
                                                  CLASS2_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {CLASS1_CLASS2_MODULE_INFO})

        test_infos = self.tr._flatten_test_infos({MODULE2_INFO, uc.INT_INFO,
                                                  CLASS1_INFO, CLASS2_INFO,
                                                  uc.GTF_INT_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {uc.INT_INFO, uc.GTF_INT_INFO,
                                                   FLAT_CLASS_INFO,
                                                   MODULE2_INFO})

        test_infos = self.tr._flatten_test_infos({uc.METHOD_INFO,
                                                  CLASS2_METHOD_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {METHOD_AND_CLASS2_METHOD})

        test_infos = self.tr._flatten_test_infos({uc.METHOD_INFO, METHOD2_INFO,
                                                  CLASS2_METHOD_INFO})
        unittest_utils.assert_equal_testinfo_sets(
            self, test_infos, {METHOD_METHOD2_AND_CLASS2_METHOD})
        test_infos = self.tr._flatten_test_infos({uc.METHOD_INFO, METHOD2_INFO,
                                                  CLASS2_METHOD_INFO,
                                                  MODULE2_INFO,
                                                  uc.INT_INFO})
        unittest_utils.assert_equal_testinfo_sets(
            self, test_infos, {uc.INT_INFO, MODULE2_INFO,
                               METHOD_METHOD2_AND_CLASS2_METHOD})

        test_infos = self.tr._flatten_test_infos({CLASS3_INFO, CLASS4_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {FLAT2_CLASS_INFO})


if __name__ == '__main__':
    unittest.main()
