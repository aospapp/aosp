# Copyright 2018 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""A test which verifies the function of hardware JPEG encode accelerator."""

import logging
import os
from autotest_lib.client.common_lib import file_utils
from autotest_lib.client.cros import chrome_binary_test
from autotest_lib.client.cros.video import device_capability
from autotest_lib.client.cros.video import helper_logger

DOWNLOAD_BASE = 'http://commondatastorage.googleapis.com/chromiumos-test-assets-public/'
TEST_IMAGE_PATH = 'jpeg_test/bali_640x360_P420.yuv'

class video_JpegEncodeAccelerator(chrome_binary_test.ChromeBinaryTest):
    """
    This test is a wrapper of the chrome unittest binary:
    jpeg_encode_accelerator_unittest.
    """

    version = 1
    binary = 'jpeg_encode_accelerator_unittest'

    @helper_logger.video_log_wrapper
    @chrome_binary_test.nuke_chrome
    def run_once(self, capability):
        """
        Runs jpeg_encode_accelerator_unittest on the device.

        @param capability: Capability required for executing this test.
        @param gtest_filter: test case filter.

        @raises: error.TestFail for jpeg_encode_accelerator_unittest failures.
        """
        device_capability.DeviceCapability().ensure_capability(capability)

        url = DOWNLOAD_BASE + TEST_IMAGE_PATH
        local_path = os.path.join(self.bindir, os.path.basename(TEST_IMAGE_PATH))
        file_utils.download_file(url, local_path)

        cmd_line = helper_logger.chrome_vmodule_flag()
        self.run_chrome_test_binary(self.binary, cmd_line)
