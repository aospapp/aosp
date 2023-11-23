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

import tempfile
import unittest

from acts.controllers.sl4a_lib import rpc_client
from acts import base_test
from acts import signals
from acts import test_decorators
from acts import test_runner


TEST_TRACKER_UUID = '12345'
UUID_KEY = 'test_tracker_uuid'


def return_true():
    return True


def return_false():
    return False


def raise_pass():
    raise signals.TestPass('')


def raise_failure():
    raise signals.TestFailure('')


def raise_sl4a():
    raise rpc_client.Sl4aException('')


def raise_generic():
    raise Exception('')


class TestDecoratorUnitTests(unittest.TestCase):

    def _verify_test_tracker_info(self, func):
        try:
            test_decorators.test_tracker_info(uuid=TEST_TRACKER_UUID)(func)()
            self.assertTrue(False, 'Expected decorator to raise exception.')
        except Exception as e:
            self.assertTrue(hasattr(e, 'extras'), 'Expected thrown exception to have extras.')
            self.assertTrue(UUID_KEY in e.extras, 'Expected extras to have %s.' % UUID_KEY)
            self.assertEqual(e.extras[UUID_KEY], TEST_TRACKER_UUID)

    def test_test_tracker_info_on_return_true(self):
        self._verify_test_tracker_info(return_true)

    def test_test_tracker_info_on_return_false(self):
        self._verify_test_tracker_info(return_false)

    def test_test_tracker_info_on_raise_pass(self):
        self._verify_test_tracker_info(raise_pass)

    def test_test_tracker_info_on_raise_failure(self):
        self._verify_test_tracker_info(raise_failure)

    def test_test_tracker_info_on_raise_sl4a(self):
        self._verify_test_tracker_info(raise_sl4a)

    def test_test_tracker_info_on_raise_generic(self):
        self._verify_test_tracker_info(raise_generic)


class MockTest(base_test.BaseTestClass):
    TEST_CASE_LIST = 'test_run_mock_test'
    TEST_LOGIC_ATTR = 'test_logic'

    @test_decorators.test_tracker_info(uuid=TEST_TRACKER_UUID)
    def test_run_mock_test(self):
        getattr(MockTest, MockTest.TEST_LOGIC_ATTR, None)()


class TestDecoratorIntegrationTests(unittest.TestCase):

    MOCK_CONFIG = {
        "testbed": {
            "name": "SampleTestBed",
        },
        "logpath": tempfile.mkdtemp(),
        "cli_args": None,
        "testpaths": ["./"],
    }

    MOCK_TEST_RUN_LIST = [(MockTest.__name__, [MockTest.TEST_CASE_LIST])]

    def setUp(self):
        pass

    def _run_with_test_logic(self, func):
        if hasattr(MockTest, MockTest.TEST_LOGIC_ATTR):
            delattr(MockTest, MockTest.TEST_LOGIC_ATTR)
        setattr(MockTest, MockTest.TEST_LOGIC_ATTR, func)
        self.test_runner = test_runner.TestRunner(TestDecoratorIntegrationTests.MOCK_CONFIG,
                                                  TestDecoratorIntegrationTests.MOCK_TEST_RUN_LIST)
        self.test_runner.run(MockTest)

    def _validate_results_has_extra(self, result, extra_key, extra_value):
        results = self.test_runner.results
        self.assertGreaterEqual(len(results.executed), 1, 'Expected at least one executed test.')
        record = results.executed[0]
        self.assertIsNotNone(record.extras, 'Expected the test record to have extras.')
        self.assertEqual(record.extras[extra_key], extra_value)

    def test_mock_test_with_raise_pass(self):
        self._run_with_test_logic(raise_pass)
        self._validate_results_has_extra(self.test_runner.results, UUID_KEY, TEST_TRACKER_UUID)

    def test_mock_test_with_raise_generic(self):
        self._run_with_test_logic(raise_generic)
        self._validate_results_has_extra(self.test_runner.results, UUID_KEY, TEST_TRACKER_UUID)


if __name__ == "__main__":
    unittest.main()