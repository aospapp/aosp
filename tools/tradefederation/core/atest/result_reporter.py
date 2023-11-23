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
"""
Result Reporter

The result reporter formats and prints test results.

----
Example Output for command to run following tests:
CtsAnimationTestCases:EvaluatorTest, HelloWorldTests, and WmTests

Running Tests ...

CtsAnimationTestCases (7 Tests)
------------------------------
[1/7] android.animation.cts.EvaluatorTest#testRectEvaluator: PASSED (153ms)
[2/7] android.animation.cts.EvaluatorTest#testIntArrayEvaluator: PASSED (0ms)
[3/7] android.animation.cts.EvaluatorTest#testIntEvaluator: PASSED (0ms)
[4/7] android.animation.cts.EvaluatorTest#testFloatArrayEvaluator: PASSED (1ms)
[5/7] android.animation.cts.EvaluatorTest#testPointFEvaluator: PASSED (1ms)
[6/7] android.animation.cts.EvaluatorTest#testArgbEvaluator: PASSED (0ms)
[7/7] android.animation.cts.EvaluatorTest#testFloatEvaluator: PASSED (1ms)

HelloWorldTests (2 Tests)
------------------------
[1/2] android.test.example.helloworld.HelloWorldTest#testHalloWelt: PASSED (0ms)
[2/2] android.test.example.helloworld.HelloWorldTest#testHelloWorld: PASSED (1ms)

WmTests (1 Test)
---------------
RUNNER ERROR: com.android.tradefed.targetprep.TargetSetupError:
Failed to install WmTests.apk on 127.0.0.1:54373. Reason:
    error message ...


Summary
-------
CtsAnimationTestCases: Passed: 7, Failed: 0
HelloWorldTests: Passed: 2, Failed: 0
WmTests: Passed: 0, Failed: 0 (Completed With ERRORS)

1 test failed
"""

from __future__ import print_function
from collections import OrderedDict

import constants
import atest_utils as au

from test_runners import test_runner_base

UNSUPPORTED_FLAG = 'UNSUPPORTED_RUNNER'
FAILURE_FLAG = 'RUNNER_FAILURE'


class RunStat(object):
    """Class for storing stats of a test run."""

    def __init__(self, passed=0, failed=0, ignored=0, run_errors=False,
                 assumption_failed=0):
        """Initialize a new instance of RunStat class.

        Args:
            passed: Count of passing tests.
            failed: Count of failed tests.
            ignored: Count of ignored tests.
            assumption_failed: Count of assumption failure tests.
            run_errors: A boolean if there were run errors
        """
        # TODO(b/109822985): Track group and run estimated totals for updating
        # summary line
        self.passed = passed
        self.failed = failed
        self.ignored = ignored
        self.assumption_failed = assumption_failed
        # Run errors are not for particular tests, they are runner errors.
        self.run_errors = run_errors

    @property
    def total(self):
        """Getter for total tests actually ran. Accessed via self.total"""
        return self.passed + self.failed


class ResultReporter(object):
    """Result Reporter class.

    As each test is run, the test runner will call self.process_test_result()
    with a TestResult namedtuple that contains the following information:
    - runner_name:   Name of the test runner
    - group_name:    Name of the test group if any.
                     In Tradefed that's the Module name.
    - test_name:     Name of the test.
                     In Tradefed that's qualified.class#Method
    - status:        The strings FAILED or PASSED.
    - stacktrace:    The stacktrace if the test failed.
    - group_total:   The total tests scheduled to be run for a group.
                     In Tradefed this is provided when the Module starts.
    - runner_total:  The total tests scheduled to be run for the runner.
                     In Tradefed this is not available so is None.

    The Result Reporter will print the results of this test and then update
    its stats state.

    Test stats are stored in the following structure:
    - self.run_stats: Is RunStat instance containing stats for the overall run.
                      This include pass/fail counts across ALL test runners.

    - self.runners:  Is of the form: {RunnerName: {GroupName: RunStat Instance}}
                     Where {} is an ordered dict.

                     The stats instance contains stats for each test group.
                     If the runner doesn't support groups, then the group
                     name will be None.

    For example this could be a state of ResultReporter:

    run_stats: RunStat(passed:10, failed:5)
    runners: {'AtestTradefedTestRunner':
                            {'Module1': RunStat(passed:1, failed:1),
                             'Module2': RunStat(passed:0, failed:4)},
              'RobolectricTestRunner': {None: RunStat(passed:5, failed:0)},
              'VtsTradefedTestRunner': {'Module1': RunStat(passed:4, failed:0)}}
    """

    def __init__(self):
        self.run_stats = RunStat()
        self.runners = OrderedDict()
        self.failed_tests = []
        self.all_test_results = []

    def process_test_result(self, test):
        """Given the results of a single test, update stats and print results.

        Args:
            test: A TestResult namedtuple.
        """
        if test.runner_name not in self.runners:
            self.runners[test.runner_name] = OrderedDict()
        assert self.runners[test.runner_name] != FAILURE_FLAG
        self.all_test_results.append(test)
        if test.group_name not in self.runners[test.runner_name]:
            self.runners[test.runner_name][test.group_name] = RunStat()
            self._print_group_title(test)
        self._update_stats(test,
                           self.runners[test.runner_name][test.group_name])
        self._print_result(test)

    def runner_failure(self, runner_name, failure_msg):
        """Report a runner failure.

        Use instead of process_test_result() when runner fails separate from
        any particular test, e.g. during setup of runner.

        Args:
            runner_name: A string of the name of the runner.
            failure_msg: A string of the failure message to pass to user.
        """
        self.runners[runner_name] = FAILURE_FLAG
        print('\n', runner_name, '\n', '-' * len(runner_name), sep='')
        print('Runner encountered a critical failure. Skipping.\n'
              'FAILURE: %s' % failure_msg)

    def register_unsupported_runner(self, runner_name):
        """Register an unsupported runner.

           Prints the following to the screen:

           RunnerName
           ----------
           This runner does not support normal results formatting.
           Below is the raw output of the test runner.

           RAW OUTPUT:
           <Raw Runner Output>

           Args:
              runner_name: A String of the test runner's name.
        """
        assert runner_name not in self.runners
        self.runners[runner_name] = UNSUPPORTED_FLAG
        print('\n', runner_name, '\n', '-' * len(runner_name), sep='')
        print('This runner does not support normal results formatting. Below '
              'is the raw output of the test runner.\n\nRAW OUTPUT:')

    def print_starting_text(self):
        """Print starting text for running tests."""
        print(au.colorize('\nRunning Tests...', constants.CYAN))

    def print_summary(self):
        """Print summary of all test runs.

        Returns:
            0 if all tests pass, non-zero otherwise.

        """
        tests_ret = constants.EXIT_CODE_SUCCESS
        if not self.runners:
            return tests_ret
        print('\n%s' % au.colorize('Summary', constants.CYAN))
        print('-------')
        failed_sum = len(self.failed_tests)
        for runner_name, groups in self.runners.items():
            if groups == UNSUPPORTED_FLAG:
                print(runner_name, 'Unsupported. See raw output above.')
                continue
            if groups == FAILURE_FLAG:
                tests_ret = constants.EXIT_CODE_TEST_FAILURE
                print(runner_name, 'Crashed. No results to report.')
                failed_sum += 1
                continue
            for group_name, stats in groups.items():
                name = group_name if group_name else runner_name
                summary = self.process_summary(name, stats)
                if stats.failed > 0:
                    tests_ret = constants.EXIT_CODE_TEST_FAILURE
                if stats.run_errors:
                    tests_ret = constants.EXIT_CODE_TEST_FAILURE
                    failed_sum += 1 if not stats.failed else 0
                print(summary)
        print()
        if tests_ret == constants.EXIT_CODE_SUCCESS:
            print(au.colorize('All tests passed!', constants.GREEN))
        else:
            message = '%d %s failed' % (failed_sum,
                                        'tests' if failed_sum > 1 else 'test')
            print(au.colorize(message, constants.RED))
            print('-'*len(message))
            self.print_failed_tests()
        return tests_ret

    def print_failed_tests(self):
        """Print the failed tests if existed."""
        if self.failed_tests:
            for test_name in self.failed_tests:
                print('%s' % test_name)

    def process_summary(self, name, stats):
        """Process the summary line.

        Strategy:
            Error status happens ->
                SomeTests: Passed: 2, Failed: 0 <red>(Completed With ERRORS)</red>
                SomeTests: Passed: 2, <red>Failed</red>: 2 <red>(Completed With ERRORS)</red>
            More than 1 test fails ->
                SomeTests: Passed: 2, <red>Failed</red>: 5
            No test fails ->
                SomeTests: <green>Passed</green>: 2, Failed: 0

        Args:
            name: A string of test name.
            stats: A RunStat instance for a test group.

        Returns:
            A summary of the test result.
        """
        passed_label = 'Passed'
        failed_label = 'Failed'
        ignored_label = 'Ignored'
        assumption_failed_label = 'Assumption Failed'
        error_label = ''
        if stats.failed > 0:
            failed_label = au.colorize(failed_label, constants.RED)
        if stats.run_errors:
            error_label = au.colorize('(Completed With ERRORS)', constants.RED)
        elif stats.failed == 0:
            passed_label = au.colorize(passed_label, constants.GREEN)
        summary = '%s: %s: %s, %s: %s, %s: %s, %s: %s %s' % (name,
                                                             passed_label,
                                                             stats.passed,
                                                             failed_label,
                                                             stats.failed,
                                                             ignored_label,
                                                             stats.ignored,
                                                             assumption_failed_label,
                                                             stats.assumption_failed,
                                                             error_label)
        return summary

    def _update_stats(self, test, group):
        """Given the results of a single test, update test run stats.

        Args:
            test: a TestResult namedtuple.
            group: a RunStat instance for a test group.
        """
        # TODO(109822985): Track group and run estimated totals for updating
        # summary line
        if test.status == test_runner_base.PASSED_STATUS:
            self.run_stats.passed += 1
            group.passed += 1
        elif test.status == test_runner_base.IGNORED_STATUS:
            self.run_stats.ignored += 1
            group.ignored += 1
        elif test.status == test_runner_base.ASSUMPTION_FAILED:
            self.run_stats.assumption_failed += 1
            group.assumption_failed += 1
        elif test.status == test_runner_base.FAILED_STATUS:
            self.run_stats.failed += 1
            self.failed_tests.append(test.test_name)
            group.failed += 1
        elif test.status == test_runner_base.ERROR_STATUS:
            self.run_stats.run_errors = True
            group.run_errors = True

    def _print_group_title(self, test):
        """Print the title line for a test group.

        Test Group/Runner Name (## Total)
        ---------------------------------

        Args:
            test: A TestResult namedtuple.
        """
        title = test.group_name or test.runner_name
        total = ''
        if test.group_total:
            if test.group_total > 1:
                total = '(%s Tests)' % test.group_total
            else:
                total = '(%s Test)' % test.group_total
        underline = '-' * (len(title) + len(total))
        print('\n%s %s\n%s' % (title, total, underline))

    def _print_result(self, test):
        """Print the results of a single test.

           Looks like:
           fully.qualified.class#TestMethod: PASSED/FAILED

        Args:
            test: a TestResult namedtuple.
        """
        if test.status == test_runner_base.ERROR_STATUS:
            print('RUNNER ERROR: %s\n' % test.details)
            return
        if test.test_name:
            if test.status == test_runner_base.PASSED_STATUS:
                # Example of output:
                # [78/92] test_name: PASSED (92ms)
                print('[%s/%s] %s: %s %s' % (test.test_count,
                                             test.group_total,
                                             test.test_name,
                                             au.colorize(
                                                 test.status,
                                                 constants.GREEN),
                                             test.test_time))
                if test.perf_info.keys():
                    print('\t%s: %s(ns) %s: %s(ns) %s: %s'
                          %(au.colorize('cpu_time', constants.BLUE),
                            test.perf_info['cpu_time'],
                            au.colorize('real_time', constants.BLUE),
                            test.perf_info['real_time'],
                            au.colorize('iterations', constants.BLUE),
                            test.perf_info['iterations']))
            elif test.status == test_runner_base.IGNORED_STATUS:
                # Example: [33/92] test_name: IGNORED (12ms)
                print('[%s/%s] %s: %s %s' % (test.test_count, test.group_total,
                                             test.test_name, au.colorize(
                                                 test.status, constants.MAGENTA),
                                             test.test_time))
            elif test.status == test_runner_base.ASSUMPTION_FAILED:
                # Example: [33/92] test_name: ASSUMPTION_FAILED (12ms)
                print('[%s/%s] %s: %s %s' % (test.test_count, test.group_total,
                                             test.test_name, au.colorize(
                                                 test.status, constants.MAGENTA),
                                             test.test_time))
            else:
                # Example: [26/92] test_name: FAILED (32ms)
                print('[%s/%s] %s: %s %s' % (test.test_count, test.group_total,
                                             test.test_name, au.colorize(
                                                 test.status, constants.RED),
                                             test.test_time))
        if test.status == test_runner_base.FAILED_STATUS:
            print('\nSTACKTRACE:\n%s' % test.details)
