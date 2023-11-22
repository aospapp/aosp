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
"""Tests for avd_spec."""

import glob
import os
import unittest
import mock


from acloud import errors
from acloud.create import avd_spec
from acloud.create import create_common
from acloud.internal import constants
from acloud.internal.lib import driver_test_lib
from acloud.internal.lib import utils


# pylint: disable=invalid-name,protected-access
class AvdSpecTest(driver_test_lib.BaseDriverTest):
    """Test avd_spec methods."""

    def setUp(self):
        """Initialize new avd_spec.AVDSpec."""
        super(AvdSpecTest, self).setUp()
        self.args = mock.MagicMock()
        self.args.local_image = ""
        self.args.config_file = ""
        self.args.build_target = "fake_build_target"
        self.AvdSpec = avd_spec.AVDSpec(self.args)

    # pylint: disable=protected-access
    def testProcessLocalImageArgs(self):
        """Test process args.local_image."""
        self.Patch(create_common, "ZipCFImageFiles",
                   return_value="/path/cf_x86_phone-img-eng.user.zip")
        self.Patch(glob, "glob", return_value=["fake.img"])
        expected_image_artifact = "/path/cf_x86_phone-img-eng.user.zip"
        expected_image_dir = "/path-to-image-dir"

        # Specified --local-image to a local zipped image file
        self.Patch(os.path, "isfile", return_value=True)
        self.args.local_image = "/path/cf_x86_phone-img-eng.user.zip"
        self.AvdSpec._avd_type = constants.TYPE_CF
        self.AvdSpec._instance_type = constants.INSTANCE_TYPE_REMOTE
        self.AvdSpec._ProcessLocalImageArgs(self.args)
        self.assertEqual(self.AvdSpec._local_image_artifact,
                         expected_image_artifact)

        # Specified --local-image to a dir contains images
        self.Patch(utils, "GetBuildEnvironmentVariable",
                   return_value="test_environ")
        self.Patch(os.path, "isfile", return_value=False)
        self.args.local_image = "/path-to-image-dir"
        self.AvdSpec._avd_type = constants.TYPE_CF
        self.AvdSpec._instance_type = constants.INSTANCE_TYPE_REMOTE
        self.AvdSpec._ProcessLocalImageArgs(self.args)
        self.assertEqual(self.AvdSpec._local_image_dir, expected_image_dir)

        # Specified local_image without arg
        self.args.local_image = None
        self.Patch(utils, "GetBuildEnvironmentVariable",
                   return_value="test_environ")
        self.AvdSpec._ProcessLocalImageArgs(self.args)
        self.assertEqual(self.AvdSpec._local_image_dir, "test_environ")
        self.assertEqual(self.AvdSpec.local_image_artifact, expected_image_artifact)

    def testProcessImageArgs(self):
        """Test process image source."""
        self.Patch(glob, "glob", return_value=["fake.img"])
        # No specified local_image, image source is from remote
        self.args.local_image = ""
        self.AvdSpec._ProcessImageArgs(self.args)
        self.assertEqual(self.AvdSpec._image_source, constants.IMAGE_SRC_REMOTE)
        self.assertEqual(self.AvdSpec._local_image_dir, None)

        # Specified local_image with an arg for cf type
        self.Patch(os.path, "isfile", return_value=True)
        self.args.local_image = "/test_path/cf_x86_phone-img-eng.user.zip"
        self.AvdSpec._avd_type = constants.TYPE_CF
        self.AvdSpec._instance_type = constants.INSTANCE_TYPE_REMOTE
        self.AvdSpec._ProcessImageArgs(self.args)
        self.assertEqual(self.AvdSpec._image_source, constants.IMAGE_SRC_LOCAL)
        self.assertEqual(self.AvdSpec._local_image_artifact,
                         "/test_path/cf_x86_phone-img-eng.user.zip")

        # Specified local_image with an arg for gce type
        self.Patch(os.path, "isfile", return_value=False)
        self.Patch(os.path, "exists", return_value=True)
        self.args.local_image = "/test_path_to_dir/"
        self.AvdSpec._avd_type = constants.TYPE_GCE
        self.AvdSpec._ProcessImageArgs(self.args)
        self.assertEqual(self.AvdSpec._image_source, constants.IMAGE_SRC_LOCAL)
        self.assertEqual(self.AvdSpec._local_image_artifact,
                         "/test_path_to_dir/avd-system.tar.gz")

    @mock.patch.object(avd_spec.AVDSpec, "_GetGitRemote")
    @mock.patch("subprocess.check_output")
    def testGetBranchFromRepo(self, mock_repo, mock_gitremote):
        """Test get branch name from repo info."""
        # Check aosp repo gets proper branch prefix.
        mock_gitremote.return_value = "aosp"
        mock_repo.return_value = "Manifest branch: master"
        self.assertEqual(self.AvdSpec._GetBranchFromRepo(), "aosp-master")

        # Check default repo gets default branch prefix.
        mock_gitremote.return_value = ""
        mock_repo.return_value = "Manifest branch: master"
        self.assertEqual(self.AvdSpec._GetBranchFromRepo(), "git_master")

        mock_repo.return_value = "Manifest branch:"
        with self.assertRaises(errors.GetBranchFromRepoInfoError):
            self.AvdSpec._GetBranchFromRepo()

    # pylint: disable=protected-access
    def testGetBuildTarget(self):
        """Test get build target name."""
        self.AvdSpec._remote_image[avd_spec._BUILD_BRANCH] = "git_branch"
        self.AvdSpec._flavor = constants.FLAVOR_IOT
        self.args.avd_type = constants.TYPE_GCE
        self.assertEqual(
            self.AvdSpec._GetBuildTarget(self.args),
            "gce_x86_iot-userdebug")

        self.AvdSpec._remote_image[avd_spec._BUILD_BRANCH] = "aosp-master"
        self.AvdSpec._flavor = constants.FLAVOR_PHONE
        self.args.avd_type = constants.TYPE_CF
        self.assertEqual(
            self.AvdSpec._GetBuildTarget(self.args),
            "aosp_cf_x86_phone-userdebug")

        self.AvdSpec._remote_image[avd_spec._BUILD_BRANCH] = "git_branch"
        self.AvdSpec._flavor = constants.FLAVOR_PHONE
        self.args.avd_type = constants.TYPE_CF
        self.assertEqual(
            self.AvdSpec._GetBuildTarget(self.args),
            "cf_x86_phone-userdebug")

    # pylint: disable=protected-access
    def testProcessHWPropertyWithInvalidArgs(self):
        """Test _ProcessHWPropertyArgs with invalid args."""
        # Checking wrong resolution.
        args = mock.MagicMock()
        args.hw_property = "cpu:3,resolution:1280"
        with self.assertRaises(errors.InvalidHWPropertyError):
            self.AvdSpec._ProcessHWPropertyArgs(args)

        # Checking property should be int.
        args = mock.MagicMock()
        args.hw_property = "cpu:3,dpi:fake"
        with self.assertRaises(errors.InvalidHWPropertyError):
            self.AvdSpec._ProcessHWPropertyArgs(args)

        # Checking disk property should be with 'g' suffix.
        args = mock.MagicMock()
        args.hw_property = "cpu:3,disk:2"
        with self.assertRaises(errors.InvalidHWPropertyError):
            self.AvdSpec._ProcessHWPropertyArgs(args)

        # Checking memory property should be with 'g' suffix.
        args = mock.MagicMock()
        args.hw_property = "cpu:3,memory:2"
        with self.assertRaises(errors.InvalidHWPropertyError):
            self.AvdSpec._ProcessHWPropertyArgs(args)

    # pylint: disable=protected-access
    def testParseHWPropertyStr(self):
        """Test _ParseHWPropertyStr."""
        expected_dict = {"cpu": "2", "x_res": "1080", "y_res": "1920",
                         "dpi": "240", "memory": "4096", "disk": "4096"}
        args_str = "cpu:2,resolution:1080x1920,dpi:240,memory:4g,disk:4g"
        result_dict = self.AvdSpec._ParseHWPropertyStr(args_str)
        self.assertTrue(expected_dict == result_dict)

    def testGetFlavorFromBuildTargetString(self):
        """Test _GetFlavorFromLocalImage."""
        img_path = "/fack_path/cf_x86_tv-img-eng.user.zip"
        self.assertEqual(self.AvdSpec._GetFlavorFromString(img_path),
                         "tv")

        build_target_str = "aosp_cf_x86_auto"
        self.assertEqual(self.AvdSpec._GetFlavorFromString(
            build_target_str), "auto")

        # Flavor is not supported.
        img_path = "/fack_path/cf_x86_error-img-eng.user.zip"
        self.assertEqual(self.AvdSpec._GetFlavorFromString(img_path),
                         None)

    # pylint: disable=protected-access
    def testProcessRemoteBuildArgs(self):
        """Test _ProcessRemoteBuildArgs."""
        self.args.branch = "git_master"
        self.args.build_id = "1234"

        # Verify auto-assigned avd_type if build_targe contains "_gce_".
        self.args.build_target = "aosp_gce_x86_phone-userdebug"
        self.AvdSpec._ProcessRemoteBuildArgs(self.args)
        self.assertTrue(self.AvdSpec.avd_type == "gce")

        # Verify auto-assigned avd_type if build_targe contains "gce_".
        self.args.build_target = "gce_x86_phone-userdebug"
        self.AvdSpec._ProcessRemoteBuildArgs(self.args)
        self.assertTrue(self.AvdSpec.avd_type == "gce")

        # Verify auto-assigned avd_type if build_targe contains "_cf_".
        self.args.build_target = "aosp_cf_x86_phone-userdebug"
        self.AvdSpec._ProcessRemoteBuildArgs(self.args)
        self.assertTrue(self.AvdSpec.avd_type == "cuttlefish")

        # Verify auto-assigned avd_type if build_targe contains "cf_".
        self.args.build_target = "cf_x86_phone-userdebug"
        self.AvdSpec._ProcessRemoteBuildArgs(self.args)
        self.assertTrue(self.AvdSpec.avd_type == "cuttlefish")

        # Verify auto-assigned avd_type if build_targe contains "sdk_".
        self.args.build_target = "sdk_phone_armv7-sdk"
        self.AvdSpec._ProcessRemoteBuildArgs(self.args)
        self.assertTrue(self.AvdSpec.avd_type == "goldfish")

        # Verify auto-assigned avd_type if build_targe contains "_sdk_".
        self.args.build_target = "aosp_sdk_phone_armv7-sdk"
        self.AvdSpec._ProcessRemoteBuildArgs(self.args)
        self.assertTrue(self.AvdSpec.avd_type == "goldfish")

        # Verify auto-assigned avd_type if no match, default as cuttlefish.
        self.args.build_target = "mini_emulator_arm64-userdebug"
        self.args.avd_type = "cuttlefish"
        # reset args.avd_type default value as cuttlefish.
        self.AvdSpec = avd_spec.AVDSpec(self.args)
        self.AvdSpec._ProcessRemoteBuildArgs(self.args)
        self.assertTrue(self.AvdSpec.avd_type == "cuttlefish")

    def testEscapeAnsi(self):
        """Test EscapeAnsi."""
        test_string = "\033[1;32;40m Manifest branch:"
        expected_result = " Manifest branch:"
        self.assertEqual(avd_spec.EscapeAnsi(test_string), expected_result)

    def testGetGceLocalImagePath(self):
        """Test get gce local image path."""
        self.Patch(os.path, "isfile", return_value=True)
        # Verify when specify --local-image ~/XXX.tar.gz.
        fake_image_path = "~/gce_local_image_dir/gce_image.tar.gz"
        self.Patch(os.path, "exists", return_value=True)
        self.assertEqual(self.AvdSpec._GetGceLocalImagePath(fake_image_path),
                         "~/gce_local_image_dir/gce_image.tar.gz")

        # Verify when specify --local-image ~/XXX.img.
        fake_image_path = "~/gce_local_image_dir/gce_image.img"
        self.assertEqual(self.AvdSpec._GetGceLocalImagePath(fake_image_path),
                         "~/gce_local_image_dir/gce_image.img")

        # Verify if exist argument --local-image as a directory.
        self.Patch(os.path, "isfile", return_value=False)
        self.Patch(os.path, "exists", return_value=True)
        fake_image_path = "~/gce_local_image_dir/"
        # Default to find */avd-system.tar.gz if exist then return the path.
        self.assertEqual(self.AvdSpec._GetGceLocalImagePath(fake_image_path),
                         "~/gce_local_image_dir/avd-system.tar.gz")

        # Otherwise choose raw file */android_system_disk_syslinux.img if
        # exist then return the path.
        self.Patch(os.path, "exists", side_effect=[False, True])
        self.assertEqual(self.AvdSpec._GetGceLocalImagePath(fake_image_path),
                         "~/gce_local_image_dir/android_system_disk_syslinux.img")

        # Both _GCE_LOCAL_IMAGE_CANDIDATE could not be found then raise error.
        self.Patch(os.path, "exists", side_effect=[False, False])
        self.assertRaises(errors.BootImgDoesNotExist,
                          self.AvdSpec._GetGceLocalImagePath, fake_image_path)


if __name__ == "__main__":
    unittest.main()
