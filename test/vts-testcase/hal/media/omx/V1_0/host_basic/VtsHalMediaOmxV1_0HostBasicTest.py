#!/usr/bin/env python
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
import time

from vts.runners.host import asserts
from vts.runners.host import base_test
from vts.runners.host import keys
from vts.runners.host import test_runner
from vts.utils.python.controllers import android_device
from vts.utils.python.precondition import precondition_utils

class VtsHalMediaOmxV1_0HostBasic(base_test.BaseTestClass):
    """Host test class to run the Media_Omx HAL."""

    def setUpClass(self):
        self.dut = self.registerController(android_device)[0]

        self.dut.shell.InvokeTerminal("one")
        self.dut.shell.one.Execute("setenforce 0")  # SELinux permissive mode
        if not precondition_utils.CanRunHidlHalTest(
            self, self.dut, self.dut.shell.one):
            self._skip_all_testcases = True
            return

        self.dut.hal.InitHidlHal(
            target_type="media_omx",
            target_basepaths=self.dut.libPaths,
            target_version=1.0,
            target_package="android.hardware.media.omx",
            target_component_name="IOmxStore",
            bits=int(self.abi_bitness))

        if self.coverage.enabled:
            self.coverage.LoadArtifacts()
            self.coverage.InitializeDeviceCoverage(self._dut)

    def testIOmxStoreBase(self):
        """A simple test case which just calls each registered function."""

        self.vtypes = self.dut.hal.media_omx.GetHidlTypeInterface("types")
        status, attributes = self.dut.hal.media_omx.listServiceAttributes()
        asserts.assertEqual(self.vtypes.Status.OK, status)

        prefix = self.dut.hal.media_omx.getNodePrefix()
        logging.info("getNodePrefix: %s", prefix)

        roles = self.dut.hal.media_omx.listRoles()
        logging.info("roles: %s", roles)

        omx = self.dut.hal.media_omx.getOmx("default")
        logging.info("omx: %s", omx)

if __name__ == "__main__":
    test_runner.main()
