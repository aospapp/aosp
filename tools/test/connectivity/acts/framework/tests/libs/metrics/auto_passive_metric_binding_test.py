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

from acts.libs.metrics.bindings import auto_passive_metric
from acts.libs.metrics.metric import PassiveMetric
from acts import signals


class MockMetric(PassiveMetric):
    def __init__(self):
        self.started = False
        self.stopped = False

    def setup(self, test, testcase, binding_name):
        self.test = test
        self.testcase = testcase
        self.binding_name = binding_name

    def start(self):
        self.started = True

    def stop(self):
        self.stopped = True

    def finish(self):
        pass


class AutoPassiveMetricBindingTest(unittest.TestCase):

    def test_binding(self):

        metric = MockMetric()

        class TestTest(object):
            @auto_passive_metric(metric, 'binding', bind_to_arg=False)
            def test(self):
                pass

        test = TestTest()

        try:
            test.test()
        except signals.TestPass:
            pass

        self.assertTrue(metric.started)
        self.assertTrue(metric.stopped)


if __name__ == "__main__":
    unittest.main()
