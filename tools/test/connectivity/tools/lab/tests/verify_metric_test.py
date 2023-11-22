#!/usr/bin/env python
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

import unittest

from metrics import verify_metric
from tests import fake


class VerifyMetricTest(unittest.TestCase):
    def test_gather_device_empty(self):
        mock_output = ''
        FAKE_RESULT = fake.FakeResult(stdout=mock_output)
        fake_shell = fake.MockShellCommand(fake_result=FAKE_RESULT)
        metric_obj = verify_metric.VerifyMetric(shell=fake_shell)

        expected_result = {verify_metric.VerifyMetric.DEVICES: {}}
        self.assertEquals(metric_obj.gather_metric(), expected_result)

    def test_gather_device_two(self):
        mock_output = '00serial01\toffline\n' \
                      '01serial00\tdevice'
        FAKE_RESULT = fake.FakeResult(stdout=mock_output)
        fake_shell = fake.MockShellCommand(fake_result=FAKE_RESULT)
        metric_obj = verify_metric.VerifyMetric(shell=fake_shell)

        expected_result = {
            verify_metric.VerifyMetric.DEVICES: {
                '00serial01': 'offline',
                '01serial00': 'device',
            }
        }
        self.assertEquals(metric_obj.gather_metric(), expected_result)


if __name__ == '__main__':
    unittest.main()
