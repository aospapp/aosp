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
import re

from vts.runners.host import asserts
from vts.runners.host import base_test
from vts.runners.host import test_runner
from vts.utils.python.controllers import android_device


class VtsHalOemHookV1_0HostTest(base_test.BaseTestClass):
    """Verify deprecated IOemHook HAL is unused."""

    def setUpClass(self):
        """Get the VINTF"""

        dut = self.android_devices[0]
        logging.debug("Get the VINTF")
        self._vintf_xml = dut.getVintfXml(use_lshal=True)

    def apiNameInVintf(self, api_name):
        """Helper to check if a API is present in the VINTF

        Args:
            api_name: A string for the API to be searched.

        Returns:
            True if the given api_name is present in the VINTF and false
            otherwise.

        Raises:
            TypeError if VINTF is invalid.
        """

        return (re.search(r"\b({0})\b".format(api_name), self._vintf_xml)
                is not None)

    def testOemHook(self):
        """Test to ensure the deprecated IOemHook HAL is not present"""

        logging.debug("Check if API is present in VINTF")
        try:
            asserts.assertFalse(self.apiNameInVintf("IOemHook"),
                    "IOemHook cannot be present")
        except TypeError as e:
            asserts.fail(str(e))


if __name__ == "__main__":
    test_runner.main()
