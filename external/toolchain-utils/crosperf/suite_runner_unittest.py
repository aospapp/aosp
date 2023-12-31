#!/usr/bin/env python3
# -*- coding: utf-8 -*-
#
# Copyright 2014 The ChromiumOS Authors
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Unittest for suite_runner."""


import contextlib
import json
import unittest
import unittest.mock as mock

from benchmark import Benchmark
from cros_utils import command_executer
from cros_utils import logger
import label
from machine_manager import MockCrosMachine
import suite_runner


class SuiteRunnerTest(unittest.TestCase):
    """Class of SuiteRunner test."""

    mock_json = mock.Mock(spec=json)
    mock_cmd_exec = mock.Mock(spec=command_executer.CommandExecuter)
    mock_cmd_term = mock.Mock(spec=command_executer.CommandTerminator)
    mock_logger = mock.Mock(spec=logger.Logger)
    mock_label = label.MockLabel(
        "lumpy",
        "build",
        "lumpy_chromeos_image",
        "",
        "",
        "/tmp/chromeos",
        "lumpy",
        ["lumpy1.cros", "lumpy.cros2"],
        "",
        "",
        False,
        "average",
        "gcc",
        False,
        "",
    )
    telemetry_crosperf_bench = Benchmark(
        "b1_test",  # name
        "octane",  # test_name
        "",  # test_args
        3,  # iterations
        False,  # rm_chroot_tmp
        "record -e cycles",  # perf_args
        "telemetry_Crosperf",  # suite
        True,
    )  # show_all_results

    crosperf_wrapper_bench = Benchmark(
        "b2_test",  # name
        "webgl",  # test_name
        "",  # test_args
        3,  # iterations
        False,  # rm_chroot_tmp
        "",  # perf_args
        "crosperf_Wrapper",
    )  # suite

    tast_bench = Benchmark(
        "b3_test",  # name
        "platform.ReportDiskUsage",  # test_name
        "",  # test_args
        1,  # iterations
        False,  # rm_chroot_tmp
        "",  # perf_args
        "tast",
    )  # suite

    def __init__(self, *args, **kwargs):
        super(SuiteRunnerTest, self).__init__(*args, **kwargs)
        self.crosfleet_run_args = []
        self.test_that_args = []
        self.tast_args = []
        self.call_crosfleet_run = False
        self.call_test_that_run = False
        self.call_tast_run = False

    def setUp(self):
        self.runner = suite_runner.SuiteRunner(
            {},
            self.mock_logger,
            "verbose",
            self.mock_cmd_exec,
            self.mock_cmd_term,
        )

    def test_get_profiler_args(self):
        input_str = (
            "--profiler=custom_perf --profiler_args='perf_options"
            '="record -a -e cycles,instructions"\''
        )
        output_str = (
            "profiler=custom_perf profiler_args='record -a -e "
            "cycles,instructions'"
        )
        res = suite_runner.GetProfilerArgs(input_str)
        self.assertEqual(res, output_str)

    def test_get_dut_config_args(self):
        dut_config = {"enable_aslr": False, "top_interval": 1.0}
        output_str = (
            "dut_config="
            "'"
            '{"enable_aslr": '
            'false, "top_interval": 1.0}'
            "'"
            ""
        )
        res = suite_runner.GetDutConfigArgs(dut_config)
        self.assertEqual(res, output_str)

    @mock.patch("suite_runner.ssh_tunnel")
    def test_run(self, ssh_tunnel):
        @contextlib.contextmanager
        def mock_ssh_tunnel(_watcher, _host):
            yield "fakelocalhost:1234"

        ssh_tunnel.side_effect = mock_ssh_tunnel

        def reset():
            self.test_that_args = []
            self.crosfleet_run_args = []
            self.tast_args = []
            self.call_test_that_run = False
            self.call_crosfleet_run = False
            self.call_tast_run = False

        def FakeCrosfleetRun(test_label, benchmark, test_args, profiler_args):
            self.crosfleet_run_args = [
                test_label,
                benchmark,
                test_args,
                profiler_args,
            ]
            self.call_crosfleet_run = True
            return "Ran FakeCrosfleetRun"

        def FakeTestThatRun(
            machine, test_label, benchmark, test_args, profiler_args
        ):
            self.test_that_args = [
                machine,
                test_label,
                benchmark,
                test_args,
                profiler_args,
            ]
            self.call_test_that_run = True
            return "Ran FakeTestThatRun"

        def FakeTastRun(machine, test_label, benchmark):
            self.tast_args = [machine, test_label, benchmark]
            self.call_tast_run = True
            return "Ran FakeTastRun"

        self.runner.Crosfleet_Run = FakeCrosfleetRun
        self.runner.Test_That_Run = FakeTestThatRun
        self.runner.Tast_Run = FakeTastRun

        self.runner.dut_config["enable_aslr"] = False
        self.runner.dut_config["cooldown_time"] = 0
        self.runner.dut_config["governor"] = "fake_governor"
        self.runner.dut_config["cpu_freq_pct"] = 65
        self.runner.dut_config["intel_pstate"] = "no_hwp"
        machine = "fake_machine"
        cros_machine = MockCrosMachine(
            machine, self.mock_label.chromeos_root, self.mock_logger
        )
        test_args = ""
        profiler_args = ""

        # Test crosfleet run for telemetry_Crosperf and crosperf_Wrapper benchmarks.
        self.mock_label.crosfleet = True
        reset()
        self.runner.Run(
            cros_machine,
            self.mock_label,
            self.crosperf_wrapper_bench,
            test_args,
            profiler_args,
        )
        self.assertTrue(self.call_crosfleet_run)
        self.assertFalse(self.call_test_that_run)
        self.assertEqual(
            self.crosfleet_run_args,
            [self.mock_label, self.crosperf_wrapper_bench, "", ""],
        )

        reset()
        self.runner.Run(
            cros_machine,
            self.mock_label,
            self.telemetry_crosperf_bench,
            test_args,
            profiler_args,
        )
        self.assertTrue(self.call_crosfleet_run)
        self.assertFalse(self.call_test_that_run)
        self.assertEqual(
            self.crosfleet_run_args,
            [self.mock_label, self.telemetry_crosperf_bench, "", ""],
        )

        # Test test_that run for telemetry_Crosperf and crosperf_Wrapper benchmarks.
        self.mock_label.crosfleet = False
        reset()
        self.runner.Run(
            cros_machine,
            self.mock_label,
            self.crosperf_wrapper_bench,
            test_args,
            profiler_args,
        )
        self.assertTrue(self.call_test_that_run)
        self.assertFalse(self.call_crosfleet_run)
        self.assertEqual(
            self.test_that_args,
            [
                "fake_machine",
                self.mock_label,
                self.crosperf_wrapper_bench,
                "",
                "",
            ],
        )

        reset()
        self.runner.Run(
            cros_machine,
            self.mock_label,
            self.telemetry_crosperf_bench,
            test_args,
            profiler_args,
        )
        self.assertTrue(self.call_test_that_run)
        self.assertFalse(self.call_crosfleet_run)
        self.assertEqual(
            self.test_that_args,
            [
                "fake_machine",
                self.mock_label,
                self.telemetry_crosperf_bench,
                "",
                "",
            ],
        )

        # Test tast run for tast benchmarks.
        reset()
        self.runner.Run(cros_machine, self.mock_label, self.tast_bench, "", "")
        self.assertTrue(self.call_tast_run)
        self.assertFalse(self.call_test_that_run)
        self.assertFalse(self.call_crosfleet_run)
        self.assertEqual(
            self.tast_args,
            ["fakelocalhost:1234", self.mock_label, self.tast_bench],
        )

    def test_gen_test_args(self):
        test_args = "--iterations=2"
        perf_args = "record -a -e cycles"

        # Test crosperf_Wrapper benchmarks arg list generation
        args_list = [
            "test_args='--iterations=2'",
            "dut_config='{}'",
            "test=webgl",
        ]
        res = self.runner.GenTestArgs(
            self.crosperf_wrapper_bench, test_args, ""
        )
        self.assertCountEqual(res, args_list)

        # Test telemetry_Crosperf benchmarks arg list generation
        args_list = [
            "test_args='--iterations=2'",
            "dut_config='{}'",
            "test=octane",
            "run_local=False",
        ]
        args_list.append(suite_runner.GetProfilerArgs(perf_args))
        res = self.runner.GenTestArgs(
            self.telemetry_crosperf_bench, test_args, perf_args
        )
        self.assertCountEqual(res, args_list)

    @mock.patch.object(command_executer.CommandExecuter, "CrosRunCommand")
    @mock.patch.object(
        command_executer.CommandExecuter, "ChrootRunCommandWOutput"
    )
    def test_tast_run(self, mock_chroot_runcmd, mock_cros_runcmd):
        mock_chroot_runcmd.return_value = 0
        self.mock_cmd_exec.ChrootRunCommandWOutput = mock_chroot_runcmd
        self.mock_cmd_exec.CrosRunCommand = mock_cros_runcmd
        res = self.runner.Tast_Run(
            "lumpy1.cros", self.mock_label, self.tast_bench
        )
        self.assertEqual(mock_cros_runcmd.call_count, 1)
        self.assertEqual(mock_chroot_runcmd.call_count, 1)
        self.assertEqual(res, 0)
        self.assertEqual(
            mock_cros_runcmd.call_args_list[0][0],
            ("rm -rf /usr/local/autotest/results/*",),
        )
        args_list = mock_chroot_runcmd.call_args_list[0][0]
        args_dict = mock_chroot_runcmd.call_args_list[0][1]
        self.assertEqual(len(args_list), 2)
        self.assertEqual(args_dict["command_terminator"], self.mock_cmd_term)

    @mock.patch.object(command_executer.CommandExecuter, "CrosRunCommand")
    @mock.patch.object(
        command_executer.CommandExecuter, "ChrootRunCommandWOutput"
    )
    @mock.patch.object(logger.Logger, "LogFatal")
    def test_test_that_run(
        self, mock_log_fatal, mock_chroot_runcmd, mock_cros_runcmd
    ):
        mock_log_fatal.side_effect = SystemExit()
        self.runner.logger.LogFatal = mock_log_fatal
        # Test crosperf_Wrapper benchmarks cannot take perf_args
        raised_exception = False
        try:
            self.runner.Test_That_Run(
                "lumpy1.cros",
                self.mock_label,
                self.crosperf_wrapper_bench,
                "",
                "record -a -e cycles",
            )
        except SystemExit:
            raised_exception = True
        self.assertTrue(raised_exception)

        mock_chroot_runcmd.return_value = 0
        self.mock_cmd_exec.ChrootRunCommandWOutput = mock_chroot_runcmd
        self.mock_cmd_exec.CrosRunCommand = mock_cros_runcmd
        res = self.runner.Test_That_Run(
            "lumpy1.cros",
            self.mock_label,
            self.crosperf_wrapper_bench,
            "--iterations=2",
            "",
        )
        self.assertEqual(mock_cros_runcmd.call_count, 1)
        self.assertEqual(mock_chroot_runcmd.call_count, 1)
        self.assertEqual(res, 0)
        self.assertEqual(
            mock_cros_runcmd.call_args_list[0][0],
            ("rm -rf /usr/local/autotest/results/*",),
        )
        args_list = mock_chroot_runcmd.call_args_list[0][0]
        args_dict = mock_chroot_runcmd.call_args_list[0][1]
        self.assertEqual(len(args_list), 2)
        self.assertEqual(args_dict["command_terminator"], self.mock_cmd_term)

    @mock.patch.object(command_executer.CommandExecuter, "RunCommandWOutput")
    @mock.patch.object(json, "loads")
    def test_crosfleet_run_client(self, mock_json_loads, mock_runcmd):
        def FakeDownloadResult(l, task_id):
            if l and task_id:
                self.assertEqual(task_id, "12345")
                return 0

        mock_runcmd.return_value = (
            0,
            "Created Swarming task https://swarming/task/b12345",
            "",
        )
        self.mock_cmd_exec.RunCommandWOutput = mock_runcmd

        mock_json_loads.return_value = {
            "child-results": [
                {
                    "success": True,
                    "task-run-url": "https://swarming/task?id=12345",
                }
            ]
        }
        self.mock_json.loads = mock_json_loads

        self.mock_label.crosfleet = True
        self.runner.DownloadResult = FakeDownloadResult
        res = self.runner.Crosfleet_Run(
            self.mock_label, self.crosperf_wrapper_bench, "", ""
        )
        ret_tup = (0, "\nResults placed in tmp/swarming-12345\n", "")
        self.assertEqual(res, ret_tup)
        self.assertEqual(mock_runcmd.call_count, 2)

        args_list = mock_runcmd.call_args_list[0][0]
        args_dict = mock_runcmd.call_args_list[0][1]
        self.assertEqual(len(args_list), 1)
        self.assertEqual(args_dict["command_terminator"], self.mock_cmd_term)

        args_list = mock_runcmd.call_args_list[1][0]
        self.assertEqual(args_list[0], ("crosfleet wait-task 12345"))
        self.assertEqual(args_dict["command_terminator"], self.mock_cmd_term)


if __name__ == "__main__":
    unittest.main()
