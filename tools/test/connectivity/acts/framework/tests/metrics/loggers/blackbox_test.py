#!/usr/bin/env python3
#
#   Copyright 2016 - The Android Open Source Project
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

from mock import Mock
from mock import patch
import tempfile
import unittest
from unittest import TestCase
from acts.base_test import BaseTestClass
from acts.metrics.loggers.blackbox import BlackboxMetricLogger
from acts.test_runner import TestRunner

COMPILE_IMPORT_PROTO = 'acts.metrics.logger.compile_import_proto'
GET_CONTEXT_FOR_EVENT = 'acts.metrics.logger.get_context_for_event'
PROTO_METRIC_PUBLISHER = 'acts.metrics.logger.ProtoMetricPublisher'


class BlackboxMetricLoggerTest(TestCase):
    """Unit tests for BlackboxMetricLogger."""

    TEST_METRIC_NAME = "metric_name"
    TEST_FILE_NAME = "blackbox_metric_name"

    def setUp(self):
        self.proto_module = Mock()
        self.event = Mock()
        self.context = Mock()
        self.publisher = Mock()
        self._get_blackbox_identifier = lambda: str(id(self.context))

    @patch(COMPILE_IMPORT_PROTO)
    def test_default_init_attributes(self, compile_import_proto):
        metric_name = Mock()
        compile_import_proto.return_value = self.proto_module

        logger = BlackboxMetricLogger(metric_name)

        self.assertEqual(logger.metric_name, metric_name)
        self.assertEqual(logger.proto_module, self.proto_module)
        self.assertEqual(logger.result_attr, 'result')
        self.assertIsNone(logger.metric_key)

    @patch(COMPILE_IMPORT_PROTO)
    def test_init_with_params(self, compile_import_proto):
        metric_name = Mock()
        result_attr = Mock()
        metric_key = Mock()

        logger = BlackboxMetricLogger(metric_name,
                                      result_attr=result_attr,
                                      metric_key=metric_key)

        self.assertEqual(logger.result_attr, result_attr)
        self.assertEqual(logger.metric_key, metric_key)

    @patch(PROTO_METRIC_PUBLISHER)
    @patch(GET_CONTEXT_FOR_EVENT)
    @patch(COMPILE_IMPORT_PROTO)
    def test_init_with_event(self,
                             compile_import_proto,
                             get_context,
                             publisher_cls):
        metric_name = Mock()

        logger = BlackboxMetricLogger(metric_name, event=self.event)

        self.assertIsNotNone(logger.context)
        self.assertIsNotNone(logger.publisher)

    @patch(COMPILE_IMPORT_PROTO)
    def test_end_populates_result(self, compile_import_proto):
        result = Mock()
        compile_import_proto.return_value = self.proto_module
        self.proto_module.ActsBlackboxMetricResult.return_value = result

        logger = BlackboxMetricLogger(self.TEST_METRIC_NAME)
        logger.context = self.context
        logger.publisher = self.publisher
        logger.context.identifier = 'Class.test'

        logger.end(self.event)

        self.assertEqual(result.test_identifier, 'Class#test')
        self.assertEqual(result.metric_key, '%s.%s' % ('Class#test',
                                                       self.TEST_METRIC_NAME))
        self.assertEqual(result.metric_value, self.context.test_class.result)

    @patch(COMPILE_IMPORT_PROTO)
    def test_end_uses_custom_result_attr(self, compile_import_proto):
        result = Mock()
        compile_import_proto.return_value = self.proto_module
        self.proto_module.ActsBlackboxMetricResult.return_value = result
        result_attr = 'result_attr'

        logger = BlackboxMetricLogger(self.TEST_METRIC_NAME,
                                      result_attr=result_attr)
        logger.context = self.context
        logger.publisher = self.publisher
        logger._get_blackbox_identifier = self._get_blackbox_identifier

        logger.end(self.event)

        self.assertEqual(result.metric_value,
                         getattr(self.context.test_class, result_attr))

    @patch(COMPILE_IMPORT_PROTO)
    def test_end_uses_metric_value_on_result_attr_none(self,
                                                       compile_import_proto):
        result = Mock()
        expected_result = Mock()
        compile_import_proto.return_value = self.proto_module
        self.proto_module.ActsBlackboxMetricResult.return_value = result
        result_attr = None

        logger = BlackboxMetricLogger(self.TEST_METRIC_NAME,
                                      result_attr=result_attr)
        logger.context = self.context
        logger.publisher = self.publisher
        logger._get_blackbox_identifier = self._get_blackbox_identifier
        logger.metric_value = expected_result
        logger.end(self.event)

        self.assertEqual(result.metric_value, expected_result)

    @patch(COMPILE_IMPORT_PROTO)
    def test_end_uses_metric_value_on_metric_value_not_none(
            self, compile_import_proto):
        result = Mock()
        expected_result = Mock()
        compile_import_proto.return_value = self.proto_module
        self.proto_module.ActsBlackboxMetricResult.return_value = result
        result_attr = 'result_attr'

        logger = BlackboxMetricLogger(self.TEST_METRIC_NAME,
                                      result_attr=result_attr)
        logger.context = self.context
        logger.context.identifier = 'Class.test'
        logger.publisher = self.publisher
        logger.metric_value = expected_result
        logger.end(self.event)

        self.assertEqual(result.metric_value, expected_result)

    @patch(COMPILE_IMPORT_PROTO)
    def test_end_uses_custom_metric_key(self, compile_import_proto):
        result = Mock()
        compile_import_proto.return_value = self.proto_module
        self.proto_module.ActsBlackboxMetricResult.return_value = result
        metric_key = 'metric_key'

        logger = BlackboxMetricLogger(self.TEST_METRIC_NAME,
                                      metric_key=metric_key)
        logger.context = self.context
        logger.publisher = self.publisher
        logger._get_blackbox_identifier = self._get_blackbox_identifier

        logger.end(self.event)

        expected_metric_key = '%s.%s' % (metric_key, self.TEST_METRIC_NAME)
        self.assertEqual(result.metric_key, expected_metric_key)

    @patch('acts.metrics.loggers.blackbox.ProtoMetric')
    @patch(COMPILE_IMPORT_PROTO)
    def test_end_does_publish(self, compile_import_proto, proto_metric_cls):
        result = Mock()
        compile_import_proto.return_value = self.proto_module
        self.proto_module.ActsBlackboxMetricResult.return_value = result
        metric_key = 'metric_key'

        logger = BlackboxMetricLogger(self.TEST_METRIC_NAME,
                                      metric_key=metric_key)
        logger.context = self.context
        logger.publisher = self.publisher
        logger._get_blackbox_identifier = self._get_blackbox_identifier

        logger.end(self.event)

        proto_metric_cls.assert_called_once_with(name=self.TEST_FILE_NAME,
                                                 data=result)
        self.publisher.publish.assert_called_once_with(
            proto_metric_cls.return_value)


class BlackboxMetricLoggerIntegrationTest(TestCase):
    """Integration tests for BlackboxMetricLogger."""

    @patch('acts.test_runner.sys')
    @patch('acts.test_runner.utils')
    @patch('acts.test_runner.importlib')
    def run_acts_test(self, test_class, importlib, utils, sys):
        config = {
            "testbed": {
                "name": "SampleTestBed",
            },
            "logpath": tempfile.mkdtemp(),
            "cli_args": None,
            "testpaths": ["./"],
        }
        mockModule = Mock()
        setattr(mockModule, test_class.__name__, test_class)
        utils.find_files.return_value = [(None, None, None)]
        importlib.import_module.return_value = mockModule
        runner = TestRunner(config, [(test_class.__name__, None,)])

        runner.run()
        runner.stop()
        return runner

    @patch('acts.metrics.logger.ProtoMetricPublisher')
    def test_test_case_metric(self, publisher_cls):
        result = 5.0

        class MyTest(BaseTestClass):
            def __init__(self, controllers):
                BaseTestClass.__init__(self, controllers)
                self.tests = ('test_case',)
                BlackboxMetricLogger.for_test_case('my_metric')

            def test_case(self):
                self.result = result

        self.run_acts_test(MyTest)

        args_list = publisher_cls().publish.call_args_list
        self.assertEqual(len(args_list), 1)
        metric = self.__get_only_arg(args_list[0])
        self.assertEqual(metric.name, 'blackbox_my_metric')
        self.assertEqual(metric.data.test_identifier, 'MyTest#test_case')
        self.assertEqual(metric.data.metric_key, 'MyTest#test_case.my_metric')
        self.assertEqual(metric.data.metric_value, result)

    @patch('acts.metrics.logger.ProtoMetricPublisher')
    def test_multiple_test_case_metrics(self, publisher_cls):
        result = 5.0

        class MyTest(BaseTestClass):
            def __init__(self, controllers):
                BaseTestClass.__init__(self, controllers)
                self.tests = ('test_case',)
                BlackboxMetricLogger.for_test_case('my_metric_1')
                BlackboxMetricLogger.for_test_case('my_metric_2')

            def test_case(self):
                self.result = result

        self.run_acts_test(MyTest)

        args_list = publisher_cls().publish.call_args_list
        self.assertEqual(len(args_list), 2)
        metrics = [self.__get_only_arg(args) for args in args_list]
        self.assertEqual(
            {metric.name for metric in metrics},
            {'blackbox_my_metric_1', 'blackbox_my_metric_2'})
        self.assertEqual(
            {metric.data.test_identifier for metric in metrics},
            {'MyTest#test_case'})
        self.assertEqual(
            {metric.data.metric_key for metric in metrics},
            {'MyTest#test_case.my_metric_1', 'MyTest#test_case.my_metric_2'})
        self.assertEqual(
            {metric.data.metric_value for metric in metrics},
            {result})

    @patch('acts.metrics.logger.ProtoMetricPublisher')
    def test_test_case_metric_with_custom_key(self, publisher_cls):
        result = 5.0

        class MyTest(BaseTestClass):
            def __init__(self, controllers):
                BaseTestClass.__init__(self, controllers)
                self.tests = ('test_case',)
                BlackboxMetricLogger.for_test_case('my_metric',
                                                   metric_key='my_metric_key')

            def test_case(self):
                self.result = result

        self.run_acts_test(MyTest)

        args_list = publisher_cls().publish.call_args_list
        self.assertEqual(len(args_list), 1)
        metric = self.__get_only_arg(args_list[0])
        self.assertEqual(metric.data.metric_key, 'my_metric_key.my_metric')

    @patch('acts.metrics.logger.ProtoMetricPublisher')
    def test_test_case_metric_with_custom_result_attr(self, publisher_cls):
        true_result = 5.0
        other_result = 10.0

        class MyTest(BaseTestClass):
            def __init__(self, controllers):
                BaseTestClass.__init__(self, controllers)
                self.tests = ('test_case',)
                BlackboxMetricLogger.for_test_case('my_metric',
                                                   result_attr='true_result')

            def test_case(self):
                self.true_result = true_result
                self.result = other_result

        self.run_acts_test(MyTest)

        args_list = publisher_cls().publish.call_args_list
        self.assertEqual(len(args_list), 1)
        metric = self.__get_only_arg(args_list[0])
        self.assertEqual(metric.data.metric_value, true_result)

    @patch('acts.metrics.logger.ProtoMetricPublisher')
    def test_test_class_metric(self, publisher_cls):
        publisher_cls().publish = Mock()
        result_1 = 5.0
        result_2 = 8.0

        class MyTest(BaseTestClass):
            def __init__(self, controllers):
                BaseTestClass.__init__(self, controllers)
                self.tests = ('test_case_1', 'test_case_2',)
                BlackboxMetricLogger.for_test_class('my_metric')
                self.result = 0

            def test_case_1(self):
                self.result += result_1

            def test_case_2(self):
                self.result += result_2

        self.run_acts_test(MyTest)

        args_list = publisher_cls().publish.call_args_list
        self.assertEqual(len(args_list), 1)
        metric = self.__get_only_arg(args_list[0])
        self.assertEqual(metric.data.metric_value, result_1 + result_2)
        self.assertEqual(metric.data.test_identifier, MyTest.__name__)

    def __get_only_arg(self, call_args):
        self.assertEqual(len(call_args[0]) + len(call_args[1]), 1)
        if len(call_args[0]) == 1:
            return call_args[0][0]
        return next(iter(call_args[1].values()))


if __name__ == '__main__':
    unittest.main()
