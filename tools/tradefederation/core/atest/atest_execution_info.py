# Copyright 2019, The Android Open Source Project
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
ATest execution info generator.
"""

from __future__ import print_function

import logging
import json
import os

_ARGS_KEY = 'args'
_STATUS_PASSED_KEY = 'PASSED'
_STATUS_FAILED_KEY = 'FAILED'
_STATUS_IGNORED_KEY = 'IGNORED'
_SUMMARY_KEY = 'summary'
_TOTAL_SUMMARY_KEY = 'total_summary'
_TEST_RUNNER_KEY = 'test_runner'
_TEST_NAME_KEY = 'test_name'
_TEST_TIME_KEY = 'test_time'
_TEST_DETAILS_KEY = 'details'
_TEST_RESULT_NAME = 'test_result'

_SUMMARY_MAP_TEMPLATE = {_STATUS_PASSED_KEY : 0,
                         _STATUS_FAILED_KEY : 0,
                         _STATUS_IGNORED_KEY : 0,}


class AtestExecutionInfo(object):
    """Class that stores the whole test progress information in JSON format.

    ----
    For example, running command
        atest hello_world_test HelloWorldTest

    will result in storing the execution detail in JSON:
    {
      "args": "hello_world_test HelloWorldTest",
      "test_runner": {
          "AtestTradefedTestRunner": {
              "hello_world_test": {
                  "FAILED": [
                      {"test_time": "(5ms)",
                       "details": "Hello, Wor...",
                       "test_name": "HelloWorldTest#PrintHelloWorld"}
                      ],
                  "summary": {"FAILED": 1, "PASSED": 0, "IGNORED": 0}
              },
              "HelloWorldTests": {
                  "PASSED": [
                      {"test_time": "(27ms)",
                       "details": null,
                       "test_name": "...HelloWorldTest#testHalloWelt"},
                      {"test_time": "(1ms)",
                       "details": null,
                       "test_name": "....HelloWorldTest#testHelloWorld"}
                      ],
                  "summary": {"FAILED": 0, "PASSED": 2, "IGNORED": 0}
              }
          }
      },
      "total_summary": {"FAILED": 1, "PASSED": 2, "IGNORED": 0}
    }
    """

    result_reporters = []

    def __init__(self, args, work_dir):
        """Initialise an AtestExecutionInfo instance.

        Args:
            args: Command line parameters.
            work_dir : The directory for saving information.

        Returns:
               A json format string.
        """
        self.args = args
        self.work_dir = work_dir
        self.result_file = None

    def __enter__(self):
        """Create and return information file object."""
        full_file_name = os.path.join(self.work_dir, _TEST_RESULT_NAME)
        try:
            self.result_file = open(full_file_name, 'w')
        except IOError:
            logging.error('Cannot open file %s', full_file_name)
        return self.result_file

    def __exit__(self, exit_type, value, traceback):
        """Write execution information and close information file."""
        if self.result_file:
            self.result_file.write(AtestExecutionInfo.
                                   _generate_execution_detail(self.args))
            self.result_file.close()

    @staticmethod
    def _generate_execution_detail(args):
        """Generate execution detail.

        Args:
            args: Command line parameters that you want to save.

        Returns:
            A json format string.
        """
        info_dict = {_ARGS_KEY: ' '.join(args)}
        try:
            AtestExecutionInfo._arrange_test_result(
                info_dict,
                AtestExecutionInfo.result_reporters)
            return json.dumps(info_dict)
        except ValueError as err:
            logging.warn('Parsing test result failed due to : %s', err)

    @staticmethod
    def _arrange_test_result(info_dict, reporters):
        """Append test result information in given dict.

        Arrange test information to below
        "test_runner": {
            "test runner name": {
                "test name": {
                    "FAILED": [
                        {"test time": "",
                         "details": "",
                         "test name": ""}
                    ],
                "summary": {"FAILED": 0, "PASSED": 0, "IGNORED": 0}
                },
            },
        "total_summary": {"FAILED": 0, "PASSED": 0, "IGNORED": 0}

        Args:
            info_dict: A dict you want to add result information in.
            reporters: A list of result_reporter.

        Returns:
            A dict contains test result information data.
        """
        info_dict[_TEST_RUNNER_KEY] = {}
        for reporter in reporters:
            for test in reporter.all_test_results:
                runner = info_dict[_TEST_RUNNER_KEY].setdefault(test.runner_name, {})
                group = runner.setdefault(test.group_name, {})
                result_dict = {_TEST_NAME_KEY : test.test_name,
                               _TEST_TIME_KEY : test.test_time,
                               _TEST_DETAILS_KEY : test.details}
                group.setdefault(test.status, []).append(result_dict)

        total_test_group_summary = _SUMMARY_MAP_TEMPLATE.copy()
        for runner in info_dict[_TEST_RUNNER_KEY]:
            for group in info_dict[_TEST_RUNNER_KEY][runner]:
                group_summary = _SUMMARY_MAP_TEMPLATE.copy()
                for status in info_dict[_TEST_RUNNER_KEY][runner][group]:
                    count = len(info_dict[_TEST_RUNNER_KEY][runner][group][status])
                    if _SUMMARY_MAP_TEMPLATE.has_key(status):
                        group_summary[status] = count
                        total_test_group_summary[status] += count
                info_dict[_TEST_RUNNER_KEY][runner][group][_SUMMARY_KEY] = group_summary
        info_dict[_TOTAL_SUMMARY_KEY] = total_test_group_summary
        return info_dict
