#!/usr/bin/python3
#pylint: disable-msg=C0111
"""Unit Tests for autotest.client.common_lib.test"""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

__author__ = 'gps@google.com (Gregory P. Smith)'

import json
import tempfile
import unittest
import common
import mock as pymock
import os
import shutil
import six
from six.moves import range

from autotest_lib.client.common_lib import test
from autotest_lib.client.common_lib.test_utils import mock


class TestTestCase(unittest.TestCase):
    class _neutered_base_test(test.base_test):
        """A child class of base_test to avoid calling the constructor."""
        def __init__(self, *args, **kwargs):
            class MockJob(object):
                pass
            class MockProfilerManager(object):
                def active(self):
                    return False
                def present(self):
                    return True
            self.job = MockJob()
            self.job.default_profile_only = False
            self.job.profilers = MockProfilerManager()
            self.job.test_retry = 0
            self.job.fast = False
            self._new_keyval = False
            self.iteration = 0
            self.tagged_testname = 'neutered_base_test'
            self.before_iteration_hooks = []
            self.after_iteration_hooks = []

            self.crash_reporter_dir = tempfile.mkdtemp()
            # Make a temp dir for the test-in-prog file to be created.
            self.test_in_prog_file = os.path.join(self.crash_reporter_dir,
                                                  "test-in-prog")

    def setUp(self):
        self.god = mock.mock_god()
        self.test = self._neutered_base_test()


    def tearDown(self):
        self.god.unstub_all()
        shutil.rmtree(self.test.crash_reporter_dir)



class Test_base_test_execute(TestTestCase):
    # Test the various behaviors of the base_test.execute() method.
    def setUp(self):
        TestTestCase.setUp(self)
        self.god.stub_function(self.test, 'run_once_profiling')
        self.god.stub_function(self.test, 'postprocess')
        self.god.stub_function(self.test, 'process_failed_constraints')


    def test_call_run_once(self):
        # setup
        self.god.stub_function(self.test, 'drop_caches_between_iterations')
        self.god.stub_function(self.test, 'run_once')
        self.god.stub_function(self.test, 'postprocess_iteration')
        self.god.stub_function(self.test, 'analyze_perf_constraints')
        before_hook = self.god.create_mock_function('before_hook')
        after_hook = self.god.create_mock_function('after_hook')
        self.test.register_before_iteration_hook(before_hook)
        self.test.register_after_iteration_hook(after_hook)

        # tests the test._call_run_once implementation
        self.test.drop_caches_between_iterations.expect_call()
        before_hook.expect_call(self.test)
        self.test.run_once.expect_call(1, 2, arg='val')
        self.test.postprocess_iteration.expect_call()
        self.test.analyze_perf_constraints.expect_call([])
        after_hook.expect_call(self.test)
        self.test._call_run_once([], False, None, (1, 2), {'arg': 'val'})
        self.god.check_playback()


    def test_call_run_once_with_exception(self):
        # setup
        self.god.stub_function(self.test, 'drop_caches_between_iterations')
        self.god.stub_function(self.test, 'run_once')
        before_hook = self.god.create_mock_function('before_hook')
        after_hook = self.god.create_mock_function('after_hook')
        self.test.register_before_iteration_hook(before_hook)
        self.test.register_after_iteration_hook(after_hook)
        error = Exception('fail')

        # tests the test._call_run_once implementation
        self.test.drop_caches_between_iterations.expect_call()
        before_hook.expect_call(self.test)
        self.test.run_once.expect_call(1, 2, arg='val').and_raises(error)
        after_hook.expect_call(self.test)
        try:
            self.test._call_run_once([], False, None, (1, 2), {'arg': 'val'})
        except:
            pass
        self.god.check_playback()


    def _expect_call_run_once(self):
        self.test._call_run_once.expect_call((), False, None, (), {})


    def test_execute_test_length(self):
        # test that test_length overrides iterations and works.
        self.god.stub_function(self.test, '_call_run_once')

        self._expect_call_run_once()
        self._expect_call_run_once()
        self._expect_call_run_once()
        self.test.run_once_profiling.expect_call(None)
        self.test.postprocess.expect_call()
        self.test.process_failed_constraints.expect_call()

        if six.PY2:
            fake_time = iter(range(4)).next
        else:
            fake_time = iter(range(4)).__next__
        self.test.execute(iterations=1, test_length=3, _get_time=fake_time)
        self.god.check_playback()


    def test_execute_iterations(self):
        # test that iterations works.
        self.god.stub_function(self.test, '_call_run_once')

        iterations = 2
        for _ in range(iterations):
            self._expect_call_run_once()
        self.test.run_once_profiling.expect_call(None)
        self.test.postprocess.expect_call()
        self.test.process_failed_constraints.expect_call()

        self.test.execute(iterations=iterations)
        self.god.check_playback()


    def _mock_calls_for_execute_no_iterations(self):
        self.test.run_once_profiling.expect_call(None)
        self.test.postprocess.expect_call()
        self.test.process_failed_constraints.expect_call()


    def test_execute_iteration_zero(self):
        # test that iterations=0 works.
        self._mock_calls_for_execute_no_iterations()

        self.test.execute(iterations=0)
        self.god.check_playback()


    def test_execute_profile_only(self):
        # test that profile_only=True works.
        self.god.stub_function(self.test, 'drop_caches_between_iterations')
        self.test.drop_caches_between_iterations.expect_call()
        self.test.run_once_profiling.expect_call(None)
        self.test.drop_caches_between_iterations.expect_call()
        self.test.run_once_profiling.expect_call(None)
        self.test.postprocess.expect_call()
        self.test.process_failed_constraints.expect_call()
        self.test.execute(profile_only=True, iterations=2)
        self.god.check_playback()


    def test_execute_default_profile_only(self):
        # test that profile_only=True works.
        self.god.stub_function(self.test, 'drop_caches_between_iterations')
        for _ in range(3):
            self.test.drop_caches_between_iterations.expect_call()
            self.test.run_once_profiling.expect_call(None)
        self.test.postprocess.expect_call()
        self.test.process_failed_constraints.expect_call()
        self.test.job.default_profile_only = True
        self.test.execute(iterations=3)
        self.god.check_playback()


    def test_execute_postprocess_profiled_false(self):
        # test that postprocess_profiled_run=False works
        self.god.stub_function(self.test, '_call_run_once')

        self.test._call_run_once.expect_call((), False, False, (), {})
        self.test.run_once_profiling.expect_call(False)
        self.test.postprocess.expect_call()
        self.test.process_failed_constraints.expect_call()

        self.test.execute(postprocess_profiled_run=False, iterations=1)
        self.god.check_playback()


    def test_execute_postprocess_profiled_true(self):
        # test that postprocess_profiled_run=True works
        self.god.stub_function(self.test, '_call_run_once')

        self.test._call_run_once.expect_call((), False, True, (), {})
        self.test.run_once_profiling.expect_call(True)
        self.test.postprocess.expect_call()
        self.test.process_failed_constraints.expect_call()

        self.test.execute(postprocess_profiled_run=True, iterations=1)
        self.god.check_playback()


    def test_output_single_perf_value(self):
        self.test.resultsdir = tempfile.mkdtemp()

        self.test.output_perf_value("Test", 1, units="ms", higher_is_better=True)

        f = open(self.test.resultsdir + "/results-chart.json")
        expected_result = {"Test": {"summary": {"units": "ms", "type": "scalar",
                           "value": 1, "improvement_direction": "up"}}}
        self.assertDictEqual(expected_result, json.loads(f.read()))

    def test_output_perf_value_with_custom_resultsdir(self):
        self.test.resultsdir = tempfile.mkdtemp()

        resultsdir = self.test.resultsdir + "/tests/tmp"
        self.test.output_perf_value("Test", 1, units="ms",higher_is_better=True,
                                    resultsdir=resultsdir)

        f = open(self.test.resultsdir + "/tests/tmp/results-chart.json")
        expected_result = {"Test": {"summary": {"units": "ms", "type": "scalar",
                           "value": 1, "improvement_direction": "up"}}}
        self.assertDictEqual(expected_result, json.loads(f.read()))


    def test_output_single_perf_value_twice(self):
        self.test.resultsdir = tempfile.mkdtemp()

        self.test.output_perf_value("Test", 1, units="ms", higher_is_better=True)
        self.test.output_perf_value("Test", 2, units="ms", higher_is_better=True)

        f = open(self.test.resultsdir + "/results-chart.json")
        expected_result = {"Test": {"summary": {"units": "ms",
                           "type": "list_of_scalar_values", "values": [1, 2],
                           "improvement_direction": "up"}}}
        self.assertDictEqual(expected_result, json.loads(f.read()))


    def test_output_single_perf_value_three_times(self):
        self.test.resultsdir = tempfile.mkdtemp()

        self.test.output_perf_value("Test", 1, units="ms",
                                    higher_is_better=True)
        self.test.output_perf_value("Test", 2, units="ms", higher_is_better=True)
        self.test.output_perf_value("Test", 3, units="ms", higher_is_better=True)

        f = open(self.test.resultsdir + "/results-chart.json")
        expected_result = {"Test": {"summary": {"units": "ms",
                           "type": "list_of_scalar_values", "values": [1, 2, 3],
                           "improvement_direction": "up"}}}
        self.assertDictEqual(expected_result, json.loads(f.read()))


    def test_output_list_perf_value(self):
        self.test.resultsdir = tempfile.mkdtemp()

        self.test.output_perf_value("Test", [1, 2, 3], units="ms",
                                    higher_is_better=False)

        f = open(self.test.resultsdir + "/results-chart.json")
        expected_result = {"Test": {"summary": {"units": "ms",
                           "type": "list_of_scalar_values", "values": [1, 2, 3],
                           "improvement_direction": "down"}}}
        self.assertDictEqual(expected_result, json.loads(f.read()))


    def test_output_single_then_list_perf_value(self):
        self.test.resultsdir = tempfile.mkdtemp()
        self.test.output_perf_value("Test", 1, units="ms",
                                    higher_is_better=False)
        self.test.output_perf_value("Test", [4, 3, 2], units="ms",
                                    higher_is_better=False)
        f = open(self.test.resultsdir + "/results-chart.json")
        expected_result = {"Test": {"summary": {"units": "ms",
                           "type": "list_of_scalar_values",
                           "values": [1, 4, 3, 2],
                           "improvement_direction": "down"}}}
        self.assertDictEqual(expected_result, json.loads(f.read()))


    def test_output_list_then_list_perf_value(self):
        self.test.resultsdir = tempfile.mkdtemp()
        self.test.output_perf_value("Test", [1, 2, 3], units="ms",
                                    higher_is_better=False)
        self.test.output_perf_value("Test", [4, 3, 2], units="ms",
                                    higher_is_better=False)
        f = open(self.test.resultsdir + "/results-chart.json")
        expected_result = {"Test": {"summary": {"units": "ms",
                           "type": "list_of_scalar_values",
                           "values": [1, 2, 3, 4, 3, 2],
                           "improvement_direction": "down"}}}
        self.assertDictEqual(expected_result, json.loads(f.read()))


    def test_output_single_perf_value_input_string(self):
        self.test.resultsdir = tempfile.mkdtemp()

        self.test.output_perf_value("Test", u'-0.34', units="ms",
                                    higher_is_better=True)

        f = open(self.test.resultsdir + "/results-chart.json")
        expected_result = {"Test": {"summary": {"units": "ms", "type": "scalar",
                           "value": -0.34, "improvement_direction": "up"}}}
        self.assertDictEqual(expected_result, json.loads(f.read()))


    def test_output_single_perf_value_input_list_of_string(self):
        self.test.resultsdir = tempfile.mkdtemp()

        self.test.output_perf_value("Test", [0, u'-0.34', 1], units="ms",
                                    higher_is_better=True)

        f = open(self.test.resultsdir + "/results-chart.json")
        expected_result = {"Test": {"summary": {"units": "ms",
                           "type": "list_of_scalar_values",
                           "values": [0, -0.34, 1],
                           "improvement_direction": "up"}}}
        self.assertDictEqual(expected_result, json.loads(f.read()))

    def test_output_list_then_replace_list_perf_value(self):
        self.test.resultsdir = tempfile.mkdtemp()
        self.test.output_perf_value("Test", [1, 2, 3], units="ms",
                                    higher_is_better=False)
        self.test.output_perf_value("Test", [4, 5, 6], units="ms",
                                    higher_is_better=False,
                                    replace_existing_values=True)
        f = open(self.test.resultsdir + "/results-chart.json")
        expected_result = {"Test": {"summary": {"units": "ms",
                           "type": "list_of_scalar_values",
                           "values": [4, 5, 6],
                           "improvement_direction": "down"}}}
        self.assertDictEqual(expected_result, json.loads(f.read()))

    def test_output_single_then_replace_list_perf_value(self):
        self.test.resultsdir = tempfile.mkdtemp()
        self.test.output_perf_value("Test", 3, units="ms",
                                    higher_is_better=False)
        self.test.output_perf_value("Test", [4, 5, 6], units="ms",
                                    higher_is_better=False,
                                    replace_existing_values=True)
        f = open(self.test.resultsdir + "/results-chart.json")
        expected_result = {"Test": {"summary": {"units": "ms",
                           "type": "list_of_scalar_values",
                           "values": [4, 5, 6],
                           "improvement_direction": "down"}}}
        self.assertDictEqual(expected_result, json.loads(f.read()))

    def test_output_list_then_replace_single_perf_value(self):
        self.test.resultsdir = tempfile.mkdtemp()
        self.test.output_perf_value("Test", [1,2,3], units="ms",
                                    higher_is_better=False)
        self.test.output_perf_value("Test", 4, units="ms",
                                    higher_is_better=False,
                                    replace_existing_values=True)
        f = open(self.test.resultsdir + "/results-chart.json")
        expected_result = {"Test": {"summary": {"units": "ms",
                           "type": "scalar",
                           "value": 4,
                           "improvement_direction": "down"}}}
        self.assertDictEqual(expected_result, json.loads(f.read()))

    def test_output_single_then_replace_single_perf_value(self):
        self.test.resultsdir = tempfile.mkdtemp()
        self.test.output_perf_value("Test", 1, units="ms",
                                    higher_is_better=False)
        self.test.output_perf_value("Test", 2, units="ms",
                                    higher_is_better=False,
                                    replace_existing_values=True)
        f = open(self.test.resultsdir + "/results-chart.json")
        expected_result = {"Test": {"summary": {"units": "ms",
                           "type": "scalar",
                           "value": 2,
                           "improvement_direction": "down"}}}
        self.assertDictEqual(expected_result, json.loads(f.read()))

    def test_output_perf_then_replace_certain_perf_value(self):
        self.test.resultsdir = tempfile.mkdtemp()
        self.test.output_perf_value("Test1", 1, units="ms",
                                    higher_is_better=False)
        self.test.output_perf_value("Test2", 2, units="ms",
                                    higher_is_better=False)
        self.test.output_perf_value("Test3", 3, units="ms",
                                    higher_is_better=False)
        self.test.output_perf_value("Test2", -1, units="ms",
                                    higher_is_better=False,
                                    replace_existing_values=True)
        f = open(self.test.resultsdir + "/results-chart.json")
        expected_result = {"Test1": {"summary":
                                       {"units": "ms",
                                        "type": "scalar",
                                        "value": 1,
                                        "improvement_direction": "down"}},
                           "Test2": {"summary":
                                       {"units": "ms",
                                        "type": "scalar",
                                        "value": -1,
                                        "improvement_direction": "down"}},
                           "Test3": {"summary":
                                       {"units": "ms",
                                        "type": "scalar",
                                        "value": 3,
                                        "improvement_direction": "down"}}}
        self.assertDictEqual(expected_result, json.loads(f.read()))

    def test_chart_supplied(self):
        self.test.resultsdir = tempfile.mkdtemp()

        test_data = [("tcp_tx", "ch006_mode11B_none", "BT_connected_but_not_streaming", 0),
                     ("tcp_tx", "ch006_mode11B_none", "BT_streaming_audiofile", 5),
                     ("tcp_tx", "ch006_mode11B_none", "BT_disconnected_again", 0),
                     ("tcp_rx", "ch006_mode11B_none", "BT_connected_but_not_streaming", 0),
                     ("tcp_rx", "ch006_mode11B_none", "BT_streaming_audiofile", 8),
                     ("tcp_rx", "ch006_mode11B_none", "BT_disconnected_again", 0),
                     ("udp_tx", "ch006_mode11B_none", "BT_connected_but_not_streaming", 0),
                     ("udp_tx", "ch006_mode11B_none", "BT_streaming_audiofile", 6),
                     ("udp_tx", "ch006_mode11B_none", "BT_disconnected_again", 0),
                     ("udp_rx", "ch006_mode11B_none", "BT_connected_but_not_streaming", 0),
                     ("udp_rx", "ch006_mode11B_none", "BT_streaming_audiofile", 8),
                     ("udp_rx", "ch006_mode11B_none", "BT_streaming_audiofile", 9),
                     ("udp_rx", "ch006_mode11B_none", "BT_disconnected_again", 0)]


        for (config_tag, ap_config_tag, bt_tag, drop) in test_data:
            self.test.output_perf_value(config_tag + '_' + bt_tag + '_drop',
                                        drop,
                                        units='percent_drop',
                                        higher_is_better=False,
                                        graph=ap_config_tag + '_drop')
        f = open(self.test.resultsdir + "/results-chart.json")
        expected_result = {
          "ch006_mode11B_none_drop": {
            "udp_tx_BT_streaming_audiofile_drop": {
              "units": "percent_drop",
              "type": "scalar",
              "value": 6.0,
              "improvement_direction": "down"
            },
            "udp_rx_BT_disconnected_again_drop": {
              "units": "percent_drop",
              "type": "scalar",
              "value": 0.0,
              "improvement_direction": "down"
            },
            "tcp_tx_BT_disconnected_again_drop": {
              "units": "percent_drop",
              "type": "scalar",
              "value": 0.0,
              "improvement_direction": "down"
            },
            "tcp_rx_BT_streaming_audiofile_drop": {
              "units": "percent_drop",
              "type": "scalar",
              "value": 8.0,
              "improvement_direction": "down"
            },
            "udp_tx_BT_connected_but_not_streaming_drop": {
              "units": "percent_drop",
              "type": "scalar",
              "value": 0.0,
              "improvement_direction": "down"
            },
            "tcp_tx_BT_connected_but_not_streaming_drop": {
              "units": "percent_drop",
              "type": "scalar",
              "value": 0.0,
              "improvement_direction": "down"
            },
            "udp_tx_BT_disconnected_again_drop": {
              "units": "percent_drop",
              "type": "scalar",
              "value": 0.0,
              "improvement_direction": "down"
            },
            "tcp_tx_BT_streaming_audiofile_drop": {
              "units": "percent_drop",
              "type": "scalar",
              "value": 5.0,
              "improvement_direction": "down"
            },
            "tcp_rx_BT_connected_but_not_streaming_drop": {
              "units": "percent_drop",
              "type": "scalar",
              "value": 0.0,
              "improvement_direction": "down"
            },
            "udp_rx_BT_connected_but_not_streaming_drop": {
              "units": "percent_drop",
              "type": "scalar",
              "value": 0.0,
              "improvement_direction": "down"
            },
            "udp_rx_BT_streaming_audiofile_drop": {
              "units": "percent_drop",
              "type": "list_of_scalar_values",
              "values": [
                8.0,
                9.0
              ],
              "improvement_direction": "down"
            },
            "tcp_rx_BT_disconnected_again_drop": {
              "units": "percent_drop",
              "type": "scalar",
              "value": 0.0,
              "improvement_direction": "down"
            }
          }
        }
        self.maxDiff = None
        self.assertDictEqual(expected_result, json.loads(f.read()))

class Test_runtest(unittest.TestCase):
    _TEST_CONTENTS = """
from autotest_lib.client.common_lib import test

class mocktest(test.base_test):
    version = 1
    def initialize(self, host, arg1=None):
        self.job.initialize_mock(host, arg1)

    def warmup(self, host):
        self.job.warmup_mock(host)

    def run_once(self, arg2):
        self.job.run_once_mock(arg2)

    def cleanup(self, **kwargs):
        self.job.cleanup_mock(**kwargs)
    """
    def setUp(self):
        self.workdir = tempfile.mkdtemp()
        self.testname = 'mocktest'
        testdir = os.path.join(self.workdir, 'tests')
        resultdir = os.path.join(self.workdir, 'results')
        tmpdir = os.path.join(self.workdir, 'tmp')

        self.test_in_prog_file = os.path.join(tmpdir, "test-in-prog")

        os.makedirs(os.path.join(testdir, self.testname))
        os.makedirs(os.path.join(resultdir, self.testname))
        os.makedirs(tmpdir)

        self.job = pymock.MagicMock(testdir=testdir, resultdir=resultdir,
                                    tmpdir=tmpdir, site_testdir=None)

        with open(os.path.join(self.job.testdir, self.testname,
                  '{}.py'.format(self.testname)), 'w') as f:
            f.write(self._TEST_CONTENTS)

    def tearDown(self):
        shutil.rmtree(self.workdir)

    def test_runtest(self):
        all_args = {'host': 'hostvalue', 'arg1': 'value1', 'arg2': 'value2'}
        test.runtest(self.job,
                     self.testname,
                     '', (),
                     all_args,
                     override_test_in_prog_file=self.test_in_prog_file)
        self.job.initialize_mock.assert_called_with('hostvalue', 'value1')
        self.job.warmup_mock.assert_called_with('hostvalue')
        self.job.run_once_mock.assert_called_with('value2')
        self.job.cleanup_mock.assert_called_with(**all_args)

if __name__ == '__main__':
    unittest.main()
