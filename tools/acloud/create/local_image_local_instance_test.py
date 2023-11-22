#!/usr/bin/env python
#
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
"""Tests for LocalImageLocalInstance."""

import unittest
import mock

from acloud.create import local_image_local_instance
from acloud.internal import constants
from acloud.internal.lib import utils


class LocalImageLocalInstanceTest(unittest.TestCase):
    """Test LocalImageLocalInstance method."""

    LAUNCH_CVD_CMD = """sg group1 <<EOF
sg group2
launch_cvd --daemon --cpus fake --x_res fake --y_res fake --dpi fake --memory_mb fake --blank_data_image_mb fake --data_policy always_create --system_image_dir fake_image_dir --vnc_server_port 6444
EOF"""

    def setUp(self):
        """Initialize new LocalImageLocalInstance."""
        self.local_image_local_instance = local_image_local_instance.LocalImageLocalInstance()

    # pylint: disable=protected-access
    @mock.patch.object(utils, "CheckUserInGroups")
    def testPrepareLaunchCVDCmd(self, mock_usergroups):
        """test PrepareLaunchCVDCmd."""
        mock_usergroups.return_value = False
        hw_property = {"cpu": "fake", "x_res": "fake", "y_res": "fake",
                       "dpi":"fake", "memory": "fake", "disk": "fake"}
        constants.LIST_CF_USER_GROUPS = ["group1", "group2"]

        launch_cmd = self.local_image_local_instance.PrepareLaunchCVDCmd(
            constants.CMD_LAUNCH_CVD, hw_property, "fake_image_dir")

        self.assertEqual(launch_cmd, self.LAUNCH_CVD_CMD)


if __name__ == "__main__":
    unittest.main()
