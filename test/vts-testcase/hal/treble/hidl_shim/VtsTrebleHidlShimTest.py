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

from vts.runners.host import asserts
from vts.runners.host import base_test
from vts.runners.host import test_runner
from vts.utils.python.common import vintf_utils
from vts.utils.python.controllers import android_device
from vts.utils.python.file import target_file_utils
from vts.utils.python.os import path_utils


class VtsTrebleHidlShimTest(base_test.BaseTestClass):
    """Ensures existence of at least one HIDL shim library (32 or 64-bit)."""

    VENDOR_MANIFEST_FILE_PATH = '/vendor/manifest.xml'
    SHIM_DIRS = [
        '/system/lib/', '/system/lib64/', '/vendor/lib/', '/vendor/lib64/'
    ]

    def setUpClass(self):
        self.dut = self.registerController(android_device)[0]
        self.dut.shell.InvokeTerminal(
            'VtsTrebleHidlShim')  # creates a remote shell instance.
        self.shell = self.dut.shell.VtsTrebleHidlShim

    def missingHidlShims(self, hal_dictionary):
        """Finds HALs listed in hal_dictionary that are missing shims on device.

        Args:
            hal_dictionary:
                {key: hal_name@version, value: vintf_utils.HalDescription object}
        Returns:
            a list of the hal_keys (hal_name@version) for which no HIDL shim was
            found on device
        """
        missing_hidl_shims = []
        for hal_key, hal_info in hal_dictionary.iteritems():
            for shim_dir in self.SHIM_DIRS:
                hidl_shim_path = path_utils.JoinTargetPath(
                    shim_dir, hal_key + '.so')
                found = target_file_utils.Exists(hidl_shim_path, self.shell)
                if found:
                    break
            if not found:
                missing_hidl_shims.append(hal_key)
        return missing_hidl_shims

    def testHidlShimExists(self):
        """Checks that at least one HIDL shim (32 or 64-bit) exists on device.

        For all HALs registered in /vendor/manifest.xml, at least one HIDL shim
        must exist on device under one of the dirs listed in self.SHIM_DIRS.
        """
        vendor_manifest_xml = target_file_utils.ReadFileContent(
            self.VENDOR_MANIFEST_FILE_PATH, self.shell)
        manifest_hwbinder_hals, manifest_passthrough_hals = vintf_utils.GetHalDescriptions(
            vendor_manifest_xml)

        missing_hidl_shims_hwbinder = self.missingHidlShims(
            manifest_hwbinder_hals)
        missing_hidl_shims_passthrough = self.missingHidlShims(
            manifest_passthrough_hals)

        asserts.assertTrue(
            len(missing_hidl_shims_hwbinder) == 0 and
            len(missing_hidl_shims_passthrough) == 0,
            ('No HIDL shim library (neither 32 or 64-bit) was found on device for the following HALs: %s, %s'
             ) % (missing_hidl_shims_hwbinder, missing_hidl_shims_passthrough))


if __name__ == "__main__":
    test_runner.main()
