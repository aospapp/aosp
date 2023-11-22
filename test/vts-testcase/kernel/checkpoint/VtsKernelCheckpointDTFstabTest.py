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

"""A test to check if dynamic partitions is enabled for new devices in Q."""

import logging

from vts.runners.host import asserts
from vts.runners.host import base_test
from vts.runners.host import test_runner
from vts.utils.python.file import target_file_utils

class VtsKernelCheckpointDTFstabTest(base_test.BaseTestClass):
    """A test class to verify dynamic partitions are enabled."""

    def setUpClass(self):
        """Initializes device and shell."""
        self.dut = self.android_devices[0]
        self.shell = self.dut.shell

    def testMetadataEarlyMounted(self):
        """Checks metadata exists in device tree based fstab.

        Userdata checkpoint requires metadata to be early mounted.
        """
        fstab = "/proc/device-tree/firmware/android/fstab"
        if target_file_utils.Exists(fstab, self.shell):
            asserts.assertTrue(target_file_utils.Exists(fstab + "/metadata",
                                                        self.shell))
        else:
            logging.info("device is not using device tree fstab")

if __name__ == "__main__":
    test_runner.main()
