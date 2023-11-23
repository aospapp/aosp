#!/usr/bin/env python3
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

from acts.libs.metrics.bindings import metric_binding
from acts.libs.metrics.metric import Metric
from acts import signals


class MockMetric(Metric):
    def setup(self, test, testcase, binding_name):
        self.test = test
        self.testcase = testcase
        self.binding_name = binding_name


class MetricBindingTest(unittest.TestCase):

    def test_binding(self):

        metric = MockMetric()

        class TestTest(object):
            @metric_binding(metric, 'binding', bind_to_arg=False)
            def test(self):
                pass

        test = TestTest()

        try:
            test.test()
        except signals.TestPass:
            pass

        self.assertEqual(metric.test.__class__.__name__, TestTest.__name__)
        self.assertEqual(metric.testcase.__name__, TestTest.test.__name__)
        self.assertEqual(metric.binding_name, 'binding')

    def test_arg_binding(self):

        metric = MockMetric()

        class TestTest(object):
            def __init__(self):
                self.grabbed_metric = None

            @metric_binding(metric, 'binding', bind_to_arg=True)
            def test(self, binding):
                self.grabbed_metric = binding

        test = TestTest()

        try:
            test.test()
        except signals.TestPass:
            pass

        self.assertEqual(test.grabbed_metric, metric)


if __name__ == "__main__":
    unittest.main()
