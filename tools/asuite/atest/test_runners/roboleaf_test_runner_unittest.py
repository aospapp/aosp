#!/usr/bin/env python3
#
# Copyright 2023, The Android Open Source Project
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

"""Unittests for roboleaf_test_runner."""

import json
import unittest
import subprocess
import logging

from pathlib import Path
from unittest import mock
from pyfakefs import fake_filesystem_unittest

from atest import atest_utils
from atest import unittest_constants
from atest.test_finders.test_info import TestInfo
from atest.test_runners import roboleaf_test_runner
from atest.test_runners.roboleaf_test_runner import RoboleafTestRunner
from atest.test_runners.roboleaf_test_runner import RoboleafModuleMap

# TODO(b/274706697): Refactor to remove disable=protected-access
# pylint: disable=protected-access
class RoboleafTestRunnerUnittests(fake_filesystem_unittest.TestCase):
    """Unit tests for roboleaf_test_runner.py"""
    def setUp(self):
        self.test_runner = RoboleafTestRunner(results_dir='')
        self.setUpPyfakefs()
        out_dir = atest_utils.get_build_out_dir()
        self.fs.create_file(
            out_dir+roboleaf_test_runner._ROBOLEAF_MODULE_MAP_PATH,
            contents="{}")
        self.fs.create_file(
            out_dir+roboleaf_test_runner._ALLOW_LIST_PROD_PATH,
            contents="")
        self.fs.create_file(
            out_dir+roboleaf_test_runner._ALLOW_LIST_STAGING_PATH,
            contents="")

    def tearDown(self):
        RoboleafModuleMap()._module_map = {}
        mock.patch.stopall()

    def test_read_allow_list(self):
        """Test _read_allow_list method"""
        self.fs.create_file(
            atest_utils.get_build_out_dir()+"allow_list",
            contents="""test1\ntest2\n#comment1\n//comment2""")

        self.assertEqual(
            roboleaf_test_runner._read_allow_list("allow_list"),
            ['test1','test2'])

    def test_roboleaf_eligible_tests_filtering(self):
        """Test roboleaf_eligible_tests method when _module_map has entries"""
        RoboleafModuleMap._instances = {}

        self.setUpPyfakefs()
        out_dir = atest_utils.get_build_out_dir()
        self.fs.create_file(
            out_dir+roboleaf_test_runner._ROBOLEAF_MODULE_MAP_PATH,
            contents=json.dumps({
            'test1': "//a",
            'test2': "//a/b",
            'test3': "//a/b",
        }))
        self.fs.create_file(
            out_dir+roboleaf_test_runner._ALLOW_LIST_STAGING_PATH,
            contents="test1\ntest2")
        self.fs.create_file(
            out_dir+roboleaf_test_runner._ALLOW_LIST_PROD_PATH,
            contents="test1")
        module_names = [
            'test1',
            'test2',
            'test3',
            'test4',
        ]

        eligible_tests = self.test_runner.roboleaf_eligible_tests(
            roboleaf_test_runner.BazelBuildMode.DEV,
            module_names)

        self.assertEqual(len(eligible_tests), 3)
        self.assertEqual(eligible_tests["test1"].test_name, 'test1')
        self.assertEqual(eligible_tests["test1"].test_runner,
                         RoboleafTestRunner.NAME)
        self.assertEqual(eligible_tests["test2"].test_name, 'test2')
        self.assertEqual(eligible_tests["test2"].test_runner,
                         RoboleafTestRunner.NAME)
        self.assertEqual(eligible_tests["test3"].test_name, 'test3')
        self.assertEqual(eligible_tests["test3"].test_runner,
                         RoboleafTestRunner.NAME)

        eligible_tests = self.test_runner.roboleaf_eligible_tests(
            roboleaf_test_runner.BazelBuildMode.STAGING,
            module_names)

        self.assertEqual(len(eligible_tests), 2)
        self.assertEqual(eligible_tests["test1"].test_name, 'test1')
        self.assertEqual(eligible_tests["test1"].test_runner,
                         RoboleafTestRunner.NAME)
        self.assertEqual(eligible_tests["test2"].test_name, 'test2')
        self.assertEqual(eligible_tests["test2"].test_runner,
                         RoboleafTestRunner.NAME)

        eligible_tests = self.test_runner.roboleaf_eligible_tests(
            roboleaf_test_runner.BazelBuildMode.PROD,
            module_names)

        self.assertEqual(len(eligible_tests), 1)
        self.assertEqual(eligible_tests["test1"].test_name, 'test1')
        self.assertEqual(eligible_tests["test1"].test_runner,
                         RoboleafTestRunner.NAME)

    def test_roboleaf_eligible_tests_empty_map(self):
        """Test roboleaf_eligible_tests method when _module_map is empty"""
        module_names = [
            'test1',
            'test2',
        ]
        RoboleafModuleMap()._module_map = {}

        eligible_tests = self.test_runner.roboleaf_eligible_tests(
            roboleaf_test_runner.BazelBuildMode.DEV,
            module_names)
        self.assertEqual(eligible_tests, {})

    def test_generate_bp2build_command(self):
        """Test generate_bp2build method."""
        cmd = roboleaf_test_runner._generate_bp2build_command()

        self.assertTrue('build/soong/soong_ui.bash --make-mode bp2build' in
                        ' '.join(cmd))

    def test_get_map(self):
        """Test get_map method."""
        data = {
            "test1": "//platform/a",
            "test2": "//platform/b"
        }
        RoboleafModuleMap()._module_map = data

        self.assertEqual(RoboleafModuleMap().get_map(), data)

    @mock.patch.object(subprocess, "check_call")
    def test_generate_map(self, mock_subprocess):
        """Test test_generate_map method fomr file."""
        module_map_location = Path(unittest_constants.TEST_DATA_DIR).joinpath(
            "roboleaf_testing/converted_modules_path_map.json"
        )
        self.fs.create_file(
            module_map_location,
            contents=json.dumps({
            "test1": "//platform/a",
            "test2": "//platform/b"
        }))

        data = roboleaf_test_runner._generate_map(module_map_location)

        # Expected to not call a subprocess with the roboleaf bp2build
        # command since file already exists.
        self.assertEqual(mock_subprocess.called, False)
        self.assertEqual(data, {
            "test1": "//platform/a",
            "test2": "//platform/b"
        })

    @mock.patch('builtins.open', mock.mock_open(read_data=json.dumps(
        {"test3": "//a/b"})))
    @mock.patch.object(subprocess, "check_call")
    def test_generate_map_with_command(self, mock_subprocess):
        """Test that _generate_map runs the bp2build command"""
        module_map_location = Path(unittest_constants.TEST_DATA_DIR).joinpath(
            "roboleaf_testing/does_not_exist.json"
        )

        # Disable expected warning log message "converted modules file was not
        # found." to reduce noise during tests.
        logging.disable(logging.WARNING)
        data = roboleaf_test_runner._generate_map(module_map_location)
        logging.disable(logging.NOTSET)

        self.assertEqual(mock_subprocess.called, True)
        self.assertEqual(data, {"test3": "//a/b"})

    def test_info_target_label(self):
        """Test info_target_label method."""
        RoboleafModuleMap()._module_map = {
            "test1": "//a",
        }

        target_label = self.test_runner.test_info_target_label(
            TestInfo(
                "test1",
                RoboleafTestRunner.NAME,
                set()),
        )

        self.assertEqual(target_label, "//a:test1")

    def test_generate_run_commands(self):
        """Test generate_run_commands method."""
        RoboleafModuleMap()._module_map = {
            "test1": "//a",
            "test2": "//b",
        }
        test_infos = (
            TestInfo(
                "test1",
                RoboleafTestRunner.NAME,
                set()),
            TestInfo(
                "test2",
                RoboleafTestRunner.NAME,
                set()),
        )

        cmds = self.test_runner.generate_run_commands(test_infos, extra_args={})

        self.assertEqual(len(cmds), 1)
        self.assertTrue('b test //a:test1 //b:test2' in cmds[0])

    @mock.patch.object(RoboleafTestRunner, 'run')
    def test_run_tests(self, mock_run):
        """Test run_tests_raw method."""
        RoboleafModuleMap()._module_map = {
            "test1": "//a",
            "test2": "//b",
        }
        test_infos = (
            TestInfo(
                "test1",
                RoboleafTestRunner.NAME,
                set()),
            TestInfo(
                "test2",
                RoboleafTestRunner.NAME,
                set()),
        )
        extra_args = {}
        mock_subproc = mock.Mock()
        mock_run.return_value = mock_subproc
        mock_subproc.returncode = 0
        mock_reporter = mock.Mock()

        result = self.test_runner.run_tests(
            test_infos, extra_args, mock_reporter)

        self.assertEqual(result, 0)


if __name__ == '__main__':
    unittest.main()
