#!/usr/bin/env python
#
# Copyright (C) 2019 The Android Open Source Project
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
import re

from time import sleep
from vts.runners.host import asserts
from vts.runners.host import const
from vts.runners.host import test_runner
from vts.testcases.template.hal_hidl_host_test import hal_hidl_host_test


class VtsHalNeuralnetworksV1_2Benchmark(hal_hidl_host_test.HalHidlHostTest):
    """A test case that runs the accuracy benchmark for every NN device driver."""

    TEST_HAL_SERVICES = {"android.hardware.neuralnetworks@1.2::IDevice"}

    def testAccuracy(self):
        """Test that the driver meets accuracy requirements."""
        hal_service_instance = self.cur_param[0]
        command = (
            "am instrument -e halServiceInstance '{}' " +
            "-w com.android.nn.benchmark.vts.v1_2/androidx.test.runner.AndroidJUnitRunner"
        ).format(hal_service_instance)
        logging.info("Executing command: %s", command)
        logging.info("Check logcat for more information")
        results = self.shell.Execute([command])
        stdout = results[const.STDOUT][0]
        stderr = results[const.STDERR][0]
        exit_code = results[const.EXIT_CODE][0]
        if stdout:
          logging.info("stdout: %s", stdout)
        if stderr:
          logging.error("stderr: %s", stderr)
        if exit_code:
          logging.error("exit_code: %s", exit_code)
        # The "am instrument" command exits with 0 even when there are failed tests.
        asserts.assertTrue(re.search(r"^OK \([0-9]+ tests?\)$", stdout, re.M),
                           "Benchmark failed")


if __name__ == "__main__":
    test_runner.main()
