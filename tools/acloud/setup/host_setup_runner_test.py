# Copyright 2018 - The Android Open Source Project
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
"""Tests for host_setup_runner."""
import platform
import unittest

from acloud.internal.lib import driver_test_lib
from acloud.internal.lib import utils
from acloud.setup import setup_common
from acloud.setup.host_setup_runner import CuttlefishHostSetup
from acloud.setup.host_setup_runner import AvdPkgInstaller


class CuttlefishHostSetupTest(driver_test_lib.BaseDriverTest):
    """Test CuttlsfishHostSetup."""

    LSMOD_OUTPUT = """nvidia_modeset        860160  6 nvidia_drm
module1                12312  1
module2                12312  1
ghash_clmulni_intel    16384  0
aesni_intel           167936  3
aes_x86_64             20480  1 aesni_intel
lrw                    16384  1 aesni_intel"""

    # pylint: disable=invalid-name
    def setUp(self):
        """Set up the test."""
        super(CuttlefishHostSetupTest, self).setUp()
        self.CuttlefishHostSetup = CuttlefishHostSetup()

    def testShouldRunFalse(self):
        """Test ShouldRun returns False."""
        self.Patch(utils, "CheckUserInGroups", return_value=True)
        self.Patch(CuttlefishHostSetup, "_CheckLoadedModules", return_value=True)
        self.assertFalse(self.CuttlefishHostSetup.ShouldRun())

    def testShouldRunTrue(self):
        """Test ShouldRun returns True."""
        # 1. Checking groups fails.
        self.Patch(
            utils, "CheckUserInGroups", return_value=False)
        self.Patch(CuttlefishHostSetup, "_CheckLoadedModules", return_value=True)
        self.assertTrue(self.CuttlefishHostSetup.ShouldRun())

        # 2. Checking modules fails.
        self.Patch(utils, "CheckUserInGroups", return_value=True)
        self.Patch(
            CuttlefishHostSetup, "_CheckLoadedModules", return_value=False)
        self.assertTrue(self.CuttlefishHostSetup.ShouldRun())

    # pylint: disable=protected-access
    def testCheckLoadedModules(self):
        """Test _CheckLoadedModules."""
        self.Patch(
            setup_common, "CheckCmdOutput", return_value=self.LSMOD_OUTPUT)

        # Required modules are all in lsmod should return True.
        self.assertTrue(
            self.CuttlefishHostSetup._CheckLoadedModules(["module1", "module2"]))
        # Required modules are not all in lsmod should return False.
        self.assertFalse(
            self.CuttlefishHostSetup._CheckLoadedModules(["module1", "module3"]))


class AvdPkgInstallerTest(driver_test_lib.BaseDriverTest):
    """Test AvdPkgInstallerTest."""

    # pylint: disable=invalid-name
    def setUp(self):
        """Set up the test."""
        super(AvdPkgInstallerTest, self).setUp()
        self.AvdPkgInstaller = AvdPkgInstaller()

    def testShouldNotRun(self):
        """Test ShoudRun should raise error in non-linux os."""
        self.Patch(platform, "system", return_value="Mac")

        self.assertFalse(self.AvdPkgInstaller.ShouldRun())


if __name__ == "__main__":
    unittest.main()
