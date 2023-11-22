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
r"""LocalImageRemoteInstance class.

Create class that is responsible for creating a remote instance AVD with a
local image.
"""
import uuid

import glob
import os
import subprocess
import time
import unittest

import mock

from acloud import errors
from acloud.create import avd_spec
from acloud.create import create_common
from acloud.create import local_image_remote_instance
from acloud.internal import constants
from acloud.internal.lib import auth
from acloud.internal.lib import cvd_compute_client
from acloud.internal.lib import driver_test_lib
from acloud.internal.lib import utils


class LocalImageRemoteInstanceTest(driver_test_lib.BaseDriverTest):
    """Test LocalImageRemoteInstance method."""

    def setUp(self):
        """Initialize new LocalImageRemoteInstance."""
        super(LocalImageRemoteInstanceTest, self).setUp()
        self.local_image_remote_instance = local_image_remote_instance.LocalImageRemoteInstance()

    def testVerifyHostPackageArtifactsExist(self):
        """test verify host package artifacts exist."""
        # Can't find the cvd host package
        with mock.patch("os.path.exists") as exists:
            exists.return_value = False
            self.assertRaises(
                errors.GetCvdLocalHostPackageError,
                self.local_image_remote_instance.VerifyHostPackageArtifactsExist)

        self.Patch(os.environ, "get", return_value="/fake_dir2")
        self.Patch(utils, "GetDistDir", return_value="/fake_dir1")
        # First path is host out dir, 2nd path is dist dir.
        self.Patch(os.path, "exists",
                   side_effect=[False, True])

        # Find cvd host in dist dir.
        self.assertEqual(
            self.local_image_remote_instance.VerifyHostPackageArtifactsExist(),
            "/fake_dir1/cvd-host_package.tar.gz")

        # Find cvd host in host out dir.
        self.Patch(os.environ, "get", return_value="/fake_dir2")
        self.Patch(utils, "GetDistDir", return_value=None)
        with mock.patch("os.path.exists") as exists:
            exists.return_value = True
            self.assertEqual(
                self.local_image_remote_instance.VerifyHostPackageArtifactsExist(),
                "/fake_dir2/cvd-host_package.tar.gz")


class RemoteInstanceDeviceFactoryTest(driver_test_lib.BaseDriverTest):
    """Test RemoteInstanceDeviceFactory method."""

    def setUp(self):
        """Set up the test."""
        super(RemoteInstanceDeviceFactoryTest, self).setUp()
        self.Patch(auth, "CreateCredentials", return_value=mock.MagicMock())
        self.Patch(cvd_compute_client.CvdComputeClient, "InitResourceHandle")

    # pylint: disable=protected-access
    def testSSHExecuteWithRetry(self):
        """test SSHExecuteWithRetry method."""
        self.Patch(time, "sleep")
        factory = local_image_remote_instance.RemoteInstanceDeviceFactory
        self.Patch(subprocess, "check_call",
                   side_effect=subprocess.CalledProcessError(
                       None, "ssh command fail."))
        self.assertRaises(subprocess.CalledProcessError,
                          factory._ShellCmdWithRetry,
                          "fake cmd")
        self.assertEqual(subprocess.check_call.call_count, #pylint: disable=no-member
                         local_image_remote_instance._SSH_CMD_MAX_RETRY + 1)
        self.Patch(subprocess, "check_call", return_value=True)
        self.assertEqual(factory._ShellCmdWithRetry("fake cmd"), True)

    # pylint: disable=protected-access
    def testCreateGceInstanceName(self):
        """test create gce instance."""
        self.Patch(utils, "GetBuildEnvironmentVariable",
                   return_value="test_environ")
        self.Patch(glob, "glob", return_vale=["fake.img"])
        self.Patch(create_common, "ZipCFImageFiles",
                   return_value="/fake/aosp_cf_x86_phone-img-eng.username.zip")
        # Mock uuid
        args = mock.MagicMock()
        args.config_file = ""
        args.avd_type = constants.TYPE_CF
        args.flavor = "phone"
        args.local_image = None
        fake_avd_spec = avd_spec.AVDSpec(args)

        fake_uuid = mock.MagicMock(hex="1234")
        self.Patch(uuid, "uuid4", return_value=fake_uuid)
        self.Patch(cvd_compute_client.CvdComputeClient, "CreateInstance")
        fake_host_package_name = "/fake/host_package.tar.gz"
        fake_image_name = "/fake/aosp_cf_x86_phone-img-eng.username.zip"

        factory = local_image_remote_instance.RemoteInstanceDeviceFactory(
            fake_avd_spec,
            fake_image_name,
            fake_host_package_name)
        self.assertEqual(factory._CreateGceInstance(), "ins-1234-userbuild-aosp-cf-x86-phone")

        fake_image_name = "/fake/aosp_cf_x86_phone.username.zip"
        factory = local_image_remote_instance.RemoteInstanceDeviceFactory(
            fake_avd_spec,
            fake_image_name,
            fake_host_package_name)
        self.assertEqual(factory._CreateGceInstance(), "ins-1234-userbuild-phone")

if __name__ == "__main__":
    unittest.main()
