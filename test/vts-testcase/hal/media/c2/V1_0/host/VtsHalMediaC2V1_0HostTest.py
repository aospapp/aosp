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

import copy
import logging
import time

from vts.runners.host import asserts
from vts.runners.host import keys
from vts.runners.host import test_runner
from vts.testcases.template.hal_hidl_gtest import hal_hidl_gtest
from vts.utils.python.hal import hal_service_name_utils

class VtsHalMediaC2V1_0Host(hal_hidl_gtest.HidlHalGTest):
    """Host test class to run the Media_C2 HAL."""

    COMPONENT_TEST = "Codec2Component"
    AUDIO_ENC_TEST = "Codec2AudioEnc"
    AUDIO_DEC_TEST = "Codec2AudioDec"
    VIDEO_ENC_TEST = "Codec2VideoEnc"
    VIDEO_DEC_TEST = "Codec2VideoDec"

    def CreateTestCases(self):
        """Get all registered test components and create test case objects."""
        # Retrieve all available IComponentStore instances
        testable, self.service_names = \
            hal_service_name_utils.GetHalServiceName(
                self.shell,
                "android.hardware.media.c2@1.0::IComponentStore",
                "64" if self._dut.is64Bit else "32")
        self.components = [];

        if testable:
            for service_name in self.service_names:
                self._dut.hal.InitHidlHal(
                    target_type="media_c2",
                    target_basepaths=self._dut.libPaths,
                    target_version=1.0,
                    target_package="android.hardware.media.c2",
                    target_component_name="IComponentStore",
                    hw_binder_service_name=service_name.encode("utf8"),
                    bits=64 if self._dut.is64Bit else 32)
                self.vtypes = self._dut.hal.media_c2.GetHidlTypeInterface("types")
                status, traitsList = self._dut.hal.media_c2.listComponents()
                asserts.assertEqual(self.vtypes.Status.OK, status)
                for traits in traitsList:
                    self.components.append({
                        'owner' : service_name,
                        'name' : traits['name'],
                        'domain' : traits['domain'],
                        'kind' : traits['kind'],
                        'mediaType' : traits['mediaType'],
                        'aliases' : traits['aliases']
                    })
        else:
            self.skipAllTests('There are no HAL services presenting ' + \
                              'android.hardware.media.c2@1.0::IComponentStore. ' + \
                              'Tests skipped.')

        super(VtsHalMediaC2V1_0Host, self).CreateTestCases()

    # @Override
    def CreateTestCase(self, path, tag=''):
        """Create a list of VtsHalMediaC2V1_0testCase objects.

        For each target side gtest test case, create a set of new test cases
        argumented with different component and role values.

        Args:
            path: string, absolute path of a gtest binary on device
            tag: string, a tag that will be appended to the end of test name

        Returns:
            A list of VtsHalMediaC2V1_0TestCase objects
        """
        gtest_cases = super(VtsHalMediaC2V1_0Host, self).CreateTestCase(path,
                                                                        tag)
        test_cases = []

        for gtest_case in gtest_cases:
            test_suite = gtest_case.full_name
            for component in self.components:
                if self.AUDIO_ENC_TEST in test_suite and \
                    (component['domain'] != 2 or component['kind'] != 2):
                    continue
                if self.AUDIO_DEC_TEST in test_suite and \
                    (component['domain'] != 2 or component['kind'] != 1):
                    continue
                if self.VIDEO_ENC_TEST in test_suite and \
                    (component['domain'] != 1 or component['kind'] != 2):
                    continue
                if self.VIDEO_DEC_TEST in test_suite and \
                    (component['domain'] != 1 or component['kind'] != 1):
                    continue

                test_case = copy.copy(gtest_case)
                test_case.args += " -I " + component['owner']
                test_case.args += " -C " + component['name']
                test_case.name_appendix = '_' + component['owner'] + \
                    '_' + component['name'] + test_case.name_appendix
                test_cases.append(test_case)

        logging.info("num of test_testcases: %s", len(test_cases))
        return test_cases

if __name__ == "__main__":
    test_runner.main()
