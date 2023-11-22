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

import unittest

from vts.testcases.template.hal_hidl_gtest import hal_hidl_gtest

class HidlHalGTestUnitTest(unittest.TestCase):
    """Tests for hal hidl gtest template"""

    def testGetServiceInstancesCombinations(self):
        """Test the function to get service instance combinations"""

        comb1 = hal_hidl_gtest.HidlHalGTest._GetServiceInstancesCombinations(
            [], {})
        self.assertEquals(0, len(comb1))
        comb2 = hal_hidl_gtest.HidlHalGTest._GetServiceInstancesCombinations(
            ["s1"], {})
        self.assertEquals(0, len(comb2))
        comb3 = hal_hidl_gtest.HidlHalGTest._GetServiceInstancesCombinations(
            ["s1"], {"s1": ["n1"]})
        self.assertEqual([["s1/n1"]], comb3)
        comb4 = hal_hidl_gtest.HidlHalGTest._GetServiceInstancesCombinations(
            ["s1"], {"s1": ["n1", "n2"]})
        self.assertEqual([["s1/n1"], ["s1/n2"]], comb4)
        comb5 = hal_hidl_gtest.HidlHalGTest._GetServiceInstancesCombinations(
            ["s1", "s2"], {"s1": ["n1", "n2"]})
        self.assertEqual([["s1/n1"], ["s1/n2"]], comb5)
        comb6 = hal_hidl_gtest.HidlHalGTest._GetServiceInstancesCombinations(
            ["s1", "s2"], {"s1": ["n1", "n2"],
                           "s2": ["n3"]})
        self.assertEqual([["s2/n3", "s1/n1"], ["s2/n3", "s1/n2"]], comb6)
        comb7 = hal_hidl_gtest.HidlHalGTest._GetServiceInstancesCombinations(
            ["s1", "s2"], {"s1": ["n1", "n2"],
                           "s2": ["n3", "n4"]})
        self.assertEqual([["s2/n3", "s1/n1"], ["s2/n3", "s1/n2"],
                          ["s2/n4", "s1/n1"], ["s2/n4", "s1/n2"]], comb7)


if __name__ == '__main__':
    unittest.main()
