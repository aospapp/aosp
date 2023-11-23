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

"""Unittests for result_reporter."""

import unittest
import mock

import result_reporter
from test_runners import test_runner_base

RESULT_PASSED_TEST = test_runner_base.TestResult(
    runner_name='someTestRunner',
    group_name='someTestModule',
    test_name='someClassName#sostName',
    status=test_runner_base.PASSED_STATUS,
    details=None,
    test_count=1,
    test_time='(10ms)',
    runner_total=None,
    group_total=2,
    perf_info={}
)

RESULT_PASSED_TEST_MODULE_2 = test_runner_base.TestResult(
    runner_name='someTestRunner',
    group_name='someTestModule2',
    test_name='someClassName#sostName',
    status=test_runner_base.PASSED_STATUS,
    details=None,
    test_count=1,
    test_time='(10ms)',
    runner_total=None,
    group_total=2,
    perf_info={}
)

RESULT_PASSED_TEST_RUNNER_2_NO_MODULE = test_runner_base.TestResult(
    runner_name='someTestRunner2',
    group_name=None,
    test_name='someClassName#sostName',
    status=test_runner_base.PASSED_STATUS,
    details=None,
    test_count=1,
    test_time='(10ms)',
    runner_total=None,
    group_total=2,
    perf_info={}
)

RESULT_FAILED_TEST = test_runner_base.TestResult(
    runner_name='someTestRunner',
    group_name='someTestModule',
    test_name='someClassName2#sestName2',
    status=test_runner_base.FAILED_STATUS,
    details='someTrace',
    test_count=1,
    test_time='',
    runner_total=None,
    group_total=2,
    perf_info={}
)

RESULT_RUN_FAILURE = test_runner_base.TestResult(
    runner_name='someTestRunner',
    group_name='someTestModule',
    test_name='someClassName#sostName',
    status=test_runner_base.ERROR_STATUS,
    details='someRunFailureReason',
    test_count=1,
    test_time='',
    runner_total=None,
    group_total=2,
    perf_info={}
)

RESULT_INVOCATION_FAILURE = test_runner_base.TestResult(
    runner_name='someTestRunner',
    group_name=None,
    test_name=None,
    status=test_runner_base.ERROR_STATUS,
    details='someInvocationFailureReason',
    test_count=1,
    test_time='',
    runner_total=None,
    group_total=None,
    perf_info={}
)

RESULT_IGNORED_TEST = test_runner_base.TestResult(
    runner_name='someTestRunner',
    group_name='someTestModule',
    test_name='someClassName#sostName',
    status=test_runner_base.IGNORED_STATUS,
    details=None,
    test_count=1,
    test_time='(10ms)',
    runner_total=None,
    group_total=2,
    perf_info={}
)

RESULT_ASSUMPTION_FAILED_TEST = test_runner_base.TestResult(
    runner_name='someTestRunner',
    group_name='someTestModule',
    test_name='someClassName#sostName',
    status=test_runner_base.ASSUMPTION_FAILED,
    details=None,
    test_count=1,
    test_time='(10ms)',
    runner_total=None,
    group_total=2,
    perf_info={}
)

#pylint: disable=protected-access
#pylint: disable=invalid-name
class ResultReporterUnittests(unittest.TestCase):
    """Unit tests for result_reporter.py"""

    def setUp(self):
        self.rr = result_reporter.ResultReporter()

    def tearDown(self):
        mock.patch.stopall()

    @mock.patch.object(result_reporter.ResultReporter, '_print_group_title')
    @mock.patch.object(result_reporter.ResultReporter, '_update_stats')
    @mock.patch.object(result_reporter.ResultReporter, '_print_result')
    def test_process_test_result(self, mock_print, mock_update, mock_title):
        """Test process_test_result method."""
        # Passed Test
        self.assertTrue('someTestRunner' not in self.rr.runners)
        self.rr.process_test_result(RESULT_PASSED_TEST)
        self.assertTrue('someTestRunner' in self.rr.runners)
        group = self.rr.runners['someTestRunner'].get('someTestModule')
        self.assertIsNotNone(group)
        mock_title.assert_called_with(RESULT_PASSED_TEST)
        mock_update.assert_called_with(RESULT_PASSED_TEST, group)
        mock_print.assert_called_with(RESULT_PASSED_TEST)
        # Failed Test
        mock_title.reset_mock()
        self.rr.process_test_result(RESULT_FAILED_TEST)
        mock_title.assert_not_called()
        mock_update.assert_called_with(RESULT_FAILED_TEST, group)
        mock_print.assert_called_with(RESULT_FAILED_TEST)
        # Test with new Group
        mock_title.reset_mock()
        self.rr.process_test_result(RESULT_PASSED_TEST_MODULE_2)
        self.assertTrue('someTestModule2' in self.rr.runners['someTestRunner'])
        mock_title.assert_called_with(RESULT_PASSED_TEST_MODULE_2)
        # Test with new Runner
        mock_title.reset_mock()
        self.rr.process_test_result(RESULT_PASSED_TEST_RUNNER_2_NO_MODULE)
        self.assertTrue('someTestRunner2' in self.rr.runners)
        mock_title.assert_called_with(RESULT_PASSED_TEST_RUNNER_2_NO_MODULE)

    def test_register_unsupported_runner(self):
        """Test register_unsupported_runner method."""
        self.rr.register_unsupported_runner('NotSupported')
        runner = self.rr.runners['NotSupported']
        self.assertIsNotNone(runner)
        self.assertEquals(runner, result_reporter.UNSUPPORTED_FLAG)

    def test_update_stats_passed(self):
        """Test _update_stats method."""
        # Passed Test
        group = result_reporter.RunStat()
        self.rr._update_stats(RESULT_PASSED_TEST, group)
        self.assertEquals(self.rr.run_stats.passed, 1)
        self.assertEquals(self.rr.run_stats.failed, 0)
        self.assertEquals(self.rr.run_stats.run_errors, False)
        self.assertEquals(self.rr.failed_tests, [])
        self.assertEquals(group.passed, 1)
        self.assertEquals(group.failed, 0)
        self.assertEquals(group.ignored, 0)
        self.assertEquals(group.run_errors, False)
        # Passed Test New Group
        group2 = result_reporter.RunStat()
        self.rr._update_stats(RESULT_PASSED_TEST_MODULE_2, group2)
        self.assertEquals(self.rr.run_stats.passed, 2)
        self.assertEquals(self.rr.run_stats.failed, 0)
        self.assertEquals(self.rr.run_stats.run_errors, False)
        self.assertEquals(self.rr.failed_tests, [])
        self.assertEquals(group2.passed, 1)
        self.assertEquals(group2.failed, 0)
        self.assertEquals(group.ignored, 0)
        self.assertEquals(group2.run_errors, False)

    def test_update_stats_failed(self):
        """Test _update_stats method."""
        # Passed Test
        group = result_reporter.RunStat()
        self.rr._update_stats(RESULT_PASSED_TEST, group)
        # Passed Test New Group
        group2 = result_reporter.RunStat()
        self.rr._update_stats(RESULT_PASSED_TEST_MODULE_2, group2)
        # Failed Test Old Group
        self.rr._update_stats(RESULT_FAILED_TEST, group)
        self.assertEquals(self.rr.run_stats.passed, 2)
        self.assertEquals(self.rr.run_stats.failed, 1)
        self.assertEquals(self.rr.run_stats.run_errors, False)
        self.assertEquals(self.rr.failed_tests, [RESULT_FAILED_TEST.test_name])
        self.assertEquals(group.passed, 1)
        self.assertEquals(group.failed, 1)
        self.assertEquals(group.ignored, 0)
        self.assertEquals(group.total, 2)
        self.assertEquals(group2.total, 1)
        self.assertEquals(group.run_errors, False)
        # Test Run Failure
        self.rr._update_stats(RESULT_RUN_FAILURE, group)
        self.assertEquals(self.rr.run_stats.passed, 2)
        self.assertEquals(self.rr.run_stats.failed, 1)
        self.assertEquals(self.rr.run_stats.run_errors, True)
        self.assertEquals(self.rr.failed_tests, [RESULT_FAILED_TEST.test_name])
        self.assertEquals(group.passed, 1)
        self.assertEquals(group.failed, 1)
        self.assertEquals(group.ignored, 0)
        self.assertEquals(group.run_errors, True)
        self.assertEquals(group2.run_errors, False)
        # Invocation Failure
        self.rr._update_stats(RESULT_INVOCATION_FAILURE, group)
        self.assertEquals(self.rr.run_stats.passed, 2)
        self.assertEquals(self.rr.run_stats.failed, 1)
        self.assertEquals(self.rr.run_stats.run_errors, True)
        self.assertEquals(self.rr.failed_tests, [RESULT_FAILED_TEST.test_name])
        self.assertEquals(group.passed, 1)
        self.assertEquals(group.failed, 1)
        self.assertEquals(group.ignored, 0)
        self.assertEquals(group.run_errors, True)

    def test_update_stats_ignored_and_assumption_failure(self):
        """Test _update_stats method."""
        # Passed Test
        group = result_reporter.RunStat()
        self.rr._update_stats(RESULT_PASSED_TEST, group)
        # Passed Test New Group
        group2 = result_reporter.RunStat()
        self.rr._update_stats(RESULT_PASSED_TEST_MODULE_2, group2)
        # Failed Test Old Group
        self.rr._update_stats(RESULT_FAILED_TEST, group)
        # Test Run Failure
        self.rr._update_stats(RESULT_RUN_FAILURE, group)
        # Invocation Failure
        self.rr._update_stats(RESULT_INVOCATION_FAILURE, group)
        # Ignored Test
        self.rr._update_stats(RESULT_IGNORED_TEST, group)
        self.assertEquals(self.rr.run_stats.passed, 2)
        self.assertEquals(self.rr.run_stats.failed, 1)
        self.assertEquals(self.rr.run_stats.run_errors, True)
        self.assertEquals(self.rr.failed_tests, [RESULT_FAILED_TEST.test_name])
        self.assertEquals(group.passed, 1)
        self.assertEquals(group.failed, 1)
        self.assertEquals(group.ignored, 1)
        self.assertEquals(group.run_errors, True)
        # 2nd Ignored Test
        self.rr._update_stats(RESULT_IGNORED_TEST, group)
        self.assertEquals(self.rr.run_stats.passed, 2)
        self.assertEquals(self.rr.run_stats.failed, 1)
        self.assertEquals(self.rr.run_stats.run_errors, True)
        self.assertEquals(self.rr.failed_tests, [RESULT_FAILED_TEST.test_name])
        self.assertEquals(group.passed, 1)
        self.assertEquals(group.failed, 1)
        self.assertEquals(group.ignored, 2)
        self.assertEquals(group.run_errors, True)

        # Assumption_Failure test
        self.rr._update_stats(RESULT_ASSUMPTION_FAILED_TEST, group)
        self.assertEquals(group.assumption_failed, 1)
        # 2nd Assumption_Failure test
        self.rr._update_stats(RESULT_ASSUMPTION_FAILED_TEST, group)
        self.assertEquals(group.assumption_failed, 2)

    def test_print_summary_ret_val(self):
        """Test print_summary method's return value."""
        # PASS Case
        self.rr.process_test_result(RESULT_PASSED_TEST)
        self.assertEquals(0, self.rr.print_summary())
        # PASS Case + Fail Case
        self.rr.process_test_result(RESULT_FAILED_TEST)
        self.assertNotEqual(0, self.rr.print_summary())
        # PASS Case + Fail Case + PASS Case
        self.rr.process_test_result(RESULT_PASSED_TEST_MODULE_2)
        self.assertNotEqual(0, self.rr.print_summary())

    def test_print_summary_ret_val_err_stat(self):
        """Test print_summary method's return value."""
        # PASS Case
        self.rr.process_test_result(RESULT_PASSED_TEST)
        self.assertEquals(0, self.rr.print_summary())
        # PASS Case + Fail Case
        self.rr.process_test_result(RESULT_RUN_FAILURE)
        self.assertNotEqual(0, self.rr.print_summary())
        # PASS Case + Fail Case + PASS Case
        self.rr.process_test_result(RESULT_PASSED_TEST_MODULE_2)
        self.assertNotEqual(0, self.rr.print_summary())

if __name__ == '__main__':
    unittest.main()
