#
# Copyright (C) 2017 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import logging
import os

from vts.runners.host import const
from vts.runners.host import keys
from vts.runners.host import test_runner
from vts.testcases.template.binary_test import binary_test
from vts.testcases.template.hal_hidl_replay_test import hal_hidl_replay_test_case
from vts.utils.python.common import vintf_utils

from vts.utils.python.os import path_utils


class HalHidlReplayTest(binary_test.BinaryTest):
    """Base class to run a HAL HIDL replay test on a target device.

    Attributes:
        _dut: AndroidDevice, the device under test as config
        DEVICE_TMP_DIR: string, target device's tmp directory path.
    """

    DEVICE_TMP_DIR = "/data/local/tmp"

    def setUpClass(self):
        """Prepares class and initializes a target device."""
        super(HalHidlReplayTest, self).setUpClass()

        if self._skip_all_testcases:
            return

        # TODO(zhuoyao): consider to push trace just before each test case.
        for trace_path in self.trace_paths:
            trace_path = str(trace_path)
            trace_file_name = str(os.path.basename(trace_path))
            self._dut.adb.push(
                path_utils.JoinTargetPath(self.data_file_path,
                                          "hal-hidl-trace", trace_path),
                path_utils.JoinTargetPath(self.DEVICE_TMP_DIR,
                                          "vts_replay_trace", trace_file_name))
        if self.coverage.enabled:
            # enable passthrough mode to measure code coverage.
            self.shell.Execute('setprop vts.hidl.get_stub true')

    def getServiceName(self):
        """Get service name(s) for the given hal."""
        cmd = "lshal --neat -i | grep -oP %s::[a-zA-Z]+/.+ | sort -u" % \
              self.hal_hidl_package_name
        out = str(self._dut.adb.shell(cmd)).split()
        service_names = map(lambda x: x[x.find('/') + 1:], out)
        logging.info("registered service: %s with name: %s" %
                     (self.hal_hidl_package_name, ' '.join(service_names)))
        return service_names

    # @Override
    def CreateTestCases(self):
        """Create a list of HalHidlReplayTestCase objects."""
        required_params = [
            keys.ConfigKeys.IKEY_HAL_HIDL_REPLAY_TEST_TRACE_PATHS,
            keys.ConfigKeys.IKEY_HAL_HIDL_PACKAGE_NAME,
            keys.ConfigKeys.IKEY_ABI_BITNESS
        ]
        opt_params = [keys.ConfigKeys.IKEY_BINARY_TEST_DISABLE_FRAMEWORK]
        self.getUserParams(
            req_param_names=required_params, opt_param_names=opt_params)
        self.hal_hidl_package_name = str(self.hal_hidl_package_name)
        self.abi_bitness = str(self.abi_bitness)
        self.trace_paths = map(str, self.hal_hidl_replay_test_trace_paths)

        target_package, target_version = self.hal_hidl_package_name.split("@")
        custom_ld_library_path = path_utils.JoinTargetPath(
            self.DEVICE_TMP_DIR, self.abi_bitness)
        driver_binary_path = path_utils.JoinTargetPath(
            self.DEVICE_TMP_DIR, self.abi_bitness,
            "vts_hal_replayer%s" % self.abi_bitness)

        if not self._skip_all_testcases:
            service_names = self.getServiceName()
        else:
            service_names = [""]

        test_suite = ''
        for trace_path in self.trace_paths:
            trace_file_name = str(os.path.basename(trace_path))
            trace_path = path_utils.JoinTargetPath(
                self.DEVICE_TMP_DIR, "vts_replay_trace", trace_file_name)
            for service_name in service_names:
                test_name = "replay_test_" + trace_file_name
                if service_name:
                    test_name += "_" + service_name
                test_case = hal_hidl_replay_test_case.HalHidlReplayTestCase(
                    trace_path,
                    service_name,
                    test_suite,
                    test_name,
                    driver_binary_path,
                    ld_library_path=custom_ld_library_path)
                self.testcases.append(test_case)

    def tearDownClass(self):
        """Performs clean-up tasks."""
        # Delete the pushed file.
        if not self._skip_all_testcases:
            for trace_path in self.trace_paths:
                trace_file_name = str(os.path.basename(trace_path))
                target_trace_path = path_utils.JoinTargetPath(
                    self.DEVICE_TMP_DIR, "vts_replay_trace", trace_file_name)
                cmd_results = self.shell.Execute(
                    "rm -f %s" % target_trace_path)
                if not cmd_results or any(cmd_results[const.EXIT_CODE]):
                    logging.warning("Failed to remove: %s", cmd_results)

        if self.coverage.enabled:
            # disable passthrough mode.
            self.shell.Execute('setprop vts.hidl.get_stub false')
        super(HalHidlReplayTest, self).tearDownClass()

    def setUp(self):
        """Setup for code coverage for each test case."""
        super(HalHidlReplayTest, self).setUp()
        if self.coverage.enabled and not self.coverage.global_coverage:
            # enable passthrough mode to measure code coverage.
            self.shell.Execute('setprop vts.hidl.get_stub true')
            self.coverage.LoadArtifacts()
            self.coverage.InitializeDeviceCoverage(self._dut)

    def tearDown(self):
        """Generate the coverage data for each test case."""
        if self.coverage.enabled and not self.coverage.global_coverage:
            self.coverage.SetCoverageData(dut=self._dut, isGlobal=False)
            # disable passthrough mode.
            self.shell.Execute('setprop vts.hidl.get_stub false')
        super(HalHidlReplayTest, self).tearDown()


if __name__ == "__main__":
    test_runner.main()
