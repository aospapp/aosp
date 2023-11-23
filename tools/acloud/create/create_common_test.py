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
"""Tests for create_common."""

import os
import tempfile
import time
import unittest
import zipfile

from acloud import errors
from acloud.create import create_common
from acloud.internal import constants
from acloud.internal.lib import driver_test_lib



class FakeZipFile(object):
    """Fake implementation of ZipFile()"""

    # pylint: disable=invalid-name,unused-argument,no-self-use
    def write(self, filename, arcname=None, compress_type=None):
        """Fake write method."""
        return

    # pylint: disable=invalid-name,no-self-use
    def close(self):
        """Fake close method."""
        return


# pylint: disable=invalid-name,protected-access
class CreateCommonTest(driver_test_lib.BaseDriverTest):
    """Test create_common functions."""

    # pylint: disable=protected-access
    def testProcessHWPropertyWithInvalidArgs(self):
        """Test ParseHWPropertyArgs with invalid args."""
        # Checking wrong property value.
        args_str = "cpu:3,disk:"
        with self.assertRaises(errors.MalformedDictStringError):
            create_common.ParseHWPropertyArgs(args_str)

        # Checking wrong property format.
        args_str = "cpu:3,disk"
        with self.assertRaises(errors.MalformedDictStringError):
            create_common.ParseHWPropertyArgs(args_str)

    def testParseHWPropertyStr(self):
        """Test ParseHWPropertyArgs."""
        expected_dict = {"cpu": "2", "resolution": "1080x1920", "dpi": "240",
                         "memory": "4g", "disk": "4g"}
        args_str = "cpu:2,resolution:1080x1920,dpi:240,memory:4g,disk:4g"
        result_dict = create_common.ParseHWPropertyArgs(args_str)
        self.assertTrue(expected_dict == result_dict)

    def testZipCFImageFiles(self):
        """Test ZipCFImageFiles."""
        # Should raise error if zip file already exists
        fake_image_path = "/fake_image_dir/"
        self.Patch(os.path, "exists", return_value=True)
        self.Patch(os, "makedirs")
        self.assertRaises(errors.ZipImageError,
                          create_common.ZipCFImageFiles,
                          fake_image_path)

        # Test should get archive name by timestamp if zip file does not exist.
        self.Patch(zipfile, "ZipFile", return_value=FakeZipFile())
        self.Patch(os.path, "exists", return_value=False)
        self.Patch(os.environ, "get", return_value="fake_build_target")
        self.Patch(time, "time", return_value=12345)
        self.Patch(tempfile, "gettempdir", return_value="/fake_temp")
        self.assertEqual(create_common.ZipCFImageFiles(fake_image_path),
                         "/fake_temp/%s/fake_build_target-local-12345.zip" %
                         constants.TEMP_ARTIFACTS_FOLDER)


if __name__ == "__main__":
    unittest.main()
