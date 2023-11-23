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

"""
Test runner for Roboleaf mode.

This runner is used to run the tests that have been fully converted to Bazel.
"""

import enum
import shlex
import os
import logging
import json
import subprocess

from typing import Any, Dict, List, Set

from atest import atest_utils
from atest import constants
from atest import bazel_mode
from atest import result_reporter

from atest.atest_enum import ExitCode
from atest.test_finders.test_info import TestInfo
from atest.test_runners import test_runner_base
from atest.tools.singleton import Singleton

# Roboleaf maintains allow lists that identify which modules have been
# fully converted to bazel.  Users of atest can use
# --roboleaf-mode=[PROD/STAGING/DEV] to filter by these allow lists.
# PROD (default) is the only mode expected to be fully converted and passing.
_ALLOW_LIST_PROD_PATH = ('/soong/soong_injection/allowlists/'
                    'mixed_build_prod_allowlist.txt')
_ALLOW_LIST_STAGING_PATH = ('/soong/soong_injection/allowlists/'
                       'mixed_build_staging_allowlist.txt')
_ROBOLEAF_MODULE_MAP_PATH = ('/soong/soong_injection/metrics/'
                             'converted_modules_path_map.json')
_ROBOLEAF_BUILD_CMD = 'build/soong/soong_ui.bash'


@enum.unique
class BazelBuildMode(enum.Enum):
    "Represents different bp2build allow lists to use whening running bazel (b)"
    OFF = 'off'
    DEV = 'dev'
    STAGING = 'staging'
    PROD = 'prod'


class RoboleafModuleMap(metaclass=Singleton):
    """Roboleaf Module Map Singleton class."""

    def __init__(self,
                 module_map_location: str = ''):
        self._module_map = _generate_map(module_map_location)
        self.modules_prod = _read_allow_list(_ALLOW_LIST_PROD_PATH)
        self.modules_staging = _read_allow_list(_ALLOW_LIST_STAGING_PATH)

    def get_map(self) -> Dict[str, str]:
        """Return converted module map.

        Returns:
            A dictionary of test names that bazel paths for eligible tests,
            for example { "test_a": "//platform/test_a" }.
        """
        return self._module_map

def _generate_map(module_map_location: str = '') -> Dict[str, str]:
    """Generate converted module map.

    Args:
        module_map_location: Path of the module_map_location to check.

    Returns:
        A dictionary of test names that bazel paths for eligible tests,
        for example { "test_a": "//platform/test_a" }.
    """
    if not module_map_location:
        module_map_location = (
            atest_utils.get_build_out_dir() + _ROBOLEAF_MODULE_MAP_PATH)

    # TODO(b/274161649): It is possible it could be stale on first run.
    # Invoking m or b test will check/recreate this file.  Bug here is
    # to determine if we can check staleness without a large time penalty.
    if not os.path.exists(module_map_location):
        logging.warning('The roboleaf converted modules file: %s was not '
                        'found.', module_map_location)
        # Attempt to generate converted modules file.
        try:
            cmd = _generate_bp2build_command()
            env_vars = os.environ.copy()
            logging.info(
                'Running `bp2build` to generate converted modules file.'
                '\n%s', ' '.join(cmd))
            subprocess.check_call(cmd,  env=env_vars)
        except subprocess.CalledProcessError as e:
            logging.error(e)
            return {}

    with open(module_map_location, 'r', encoding='utf8') as robo_map:
        return json.load(robo_map)

def _read_allow_list(allow_list_location: str = '') -> List[str]:
    """Generate a list of modules based on an allow list file.
    The expected file format is a text file that has a module name on each line.
    Lines that start with '#' or '//' are considered comments and skipped.

    Args:
        location: Path of the allow_list file to parse.

    Returns:
        A list of module names.
    """

    allow_list_location = (
            atest_utils.get_build_out_dir() + allow_list_location)

    if not os.path.exists(allow_list_location):
        logging.error('The roboleaf allow list file: %s was not '
                        'found.', allow_list_location)
        return []
    with open(allow_list_location,  encoding='utf-8') as f:
        allowed = []
        for module_name in f.read().splitlines():
            if module_name.startswith('#') or module_name.startswith('//'):
                continue
            allowed.append(module_name)
        return allowed

def _generate_bp2build_command() -> List[str]:
    """Build command to run bp2build.

    Returns:
        A list of commands to run bp2build.
    """
    soong_ui = (
        f'{os.environ.get(constants.ANDROID_BUILD_TOP, os.getcwd())}/'
        f'{_ROBOLEAF_BUILD_CMD}')
    return [soong_ui, '--make-mode', 'bp2build']


class AbortRunException(Exception):
    """Roboleaf Abort Run Exception Class."""


class RoboleafTestRunner(test_runner_base.TestRunnerBase):
    """Roboleaf Test Runner class."""
    NAME = 'RoboleafTestRunner'
    EXECUTABLE = 'b'

    # pylint: disable=unused-argument
    def generate_run_commands(self,
                              test_infos: Set[Any],
                              extra_args: Dict[str, Any],
                              port: int = None) -> List[str]:
        """Generate a list of run commands from TestInfos.

        Args:
            test_infos: A set of TestInfo instances.
            extra_args: A Dict of extra args to append.
            port: Optional. An int of the port number to send events to.

        Returns:
            A list of run commands to run the tests.
        """
        target_patterns = ' '.join(
            self.test_info_target_label(i) for i in test_infos)
        bazel_args = bazel_mode.parse_args(test_infos, extra_args, None)
        bazel_args.append('--config=android')
        bazel_args.append(
            '--//build/bazel/rules/tradefed:runmode=host_driven_test'
        )
        bazel_args_str = ' '.join(shlex.quote(arg) for arg in bazel_args)
        command = f'{self.EXECUTABLE} test {target_patterns} {bazel_args_str}'
        results = [command]
        logging.info("Roboleaf test runner command:\n"
                     "\n".join(results))
        return results

    def test_info_target_label(self, test: TestInfo) -> str:
        """ Get bazel path of test

        Args:
            test: An object of TestInfo.

        Returns:
            The bazel path of the test.
        """
        module_map = RoboleafModuleMap().get_map()
        return f'{module_map[test.test_name]}:{test.test_name}'

    def run_tests(self,
                  test_infos: List[TestInfo],
                  extra_args: Dict[str, Any],
                  reporter: result_reporter.ResultReporter) -> int:
        """Run the list of test_infos.

        Args:
            test_infos: List of TestInfo.
            extra_args: Dict of extra args to add to test run.
            reporter: An instance of result_reporter.ResultReporter.
        """
        reporter.register_unsupported_runner(self.NAME)
        ret_code = ExitCode.SUCCESS
        try:
            run_cmds = self.generate_run_commands(test_infos, extra_args)
        except AbortRunException as e:
            atest_utils.colorful_print(f'Stop running test(s): {e}',
                                       constants.RED)
            return ExitCode.ERROR
        for run_cmd in run_cmds:
            subproc = self.run(run_cmd, output_to_stdout=True)
            ret_code |= self.wait_for_subprocess(subproc)
        return ret_code

    def get_test_runner_build_reqs(
        self,
        test_infos: List[TestInfo]) -> Set[str]:
        return set()

    def host_env_check(self) -> None:
        """Check that host env has everything we need.

        We actually can assume the host env is fine because we have the same
        requirements that atest has. Update this to check for android env vars
        if that changes.
        """

    def roboleaf_eligible_tests(
        self,
        mode: BazelBuildMode,
        module_names: List[str]) -> Dict[str, TestInfo]:
        """Filter the given module_names to only ones that are currently
        fully converted with roboleaf (b test) and then filter further by the
        given allow list specified in BazelBuildMode.

        Args:
            mode: A BazelBuildMode value to filter by allow list.
            module_names: A list of module names to check for roboleaf support.

        Returns:
            A dictionary keyed by test name and value of Roboleaf TestInfo.
        """
        if not module_names:
            return {}

        mod_map = RoboleafModuleMap()
        supported_modules = set(filter(
            lambda m: m in mod_map.get_map(), module_names))


        if mode == BazelBuildMode.PROD:
            supported_modules = set(filter(
            lambda m: m in supported_modules, mod_map.modules_prod))
        elif mode == BazelBuildMode.STAGING:
            supported_modules = set(filter(
            lambda m: m in supported_modules, mod_map.modules_staging))

        return {
            module: TestInfo(module, RoboleafTestRunner.NAME, set())
            for module in supported_modules
        }
