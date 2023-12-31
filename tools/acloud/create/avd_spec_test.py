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
import subprocess
import unittest

from unittest import mock

from acloud import errors
from acloud.create import avd_spec
from acloud.internal import constants
from acloud.internal.lib import android_build_client
from acloud.internal.lib import auth
from acloud.internal.lib import driver_test_lib
from acloud.internal.lib import utils
from acloud.list import list as list_instances
from acloud.public import config


# pylint: disable=invalid-name,protected-access
class AvdSpecTest(driver_test_lib.BaseDriverTest):
    """Test avd_spec methods."""

    def setUp(self):
        """Initialize new avd_spec.AVDSpec."""
        super().setUp()
        self.args = mock.MagicMock()
        self.args.flavor = ""
        self.args.local_image = None
        self.args.local_kernel_image = None
        self.args.local_system_image = None
        self.args.config_file = ""
        self.args.build_target = "fake_build_target"
        self.args.adb_port = None
        self.args.fastboot_port = None
        self.args.launch_args = None
        self.Patch(list_instances, "ChooseOneRemoteInstance", return_value=mock.MagicMock())
        self.Patch(list_instances, "GetInstancesFromInstanceNames", return_value=mock.MagicMock())

        # Setup mock Acloud config for usage in tests.
        self.mock_config = mock.MagicMock()
        self.mock_config.launch_args = None
        self.Patch(config, 'GetAcloudConfig', return_value=self.mock_config)

        self.AvdSpec = avd_spec.AVDSpec(self.args)

    # pylint: disable=protected-access
    def testProcessLocalImageArgs(self):
        """Test process args.local_image."""
        self.Patch(glob, "glob", return_value=["fake.img"])
        expected_image_artifact = "/path/cf_x86_phone-img-eng.user.zip"
        expected_image_dir = "/path-to-image-dir"
        self.Patch(os.path, "exists",
                   side_effect=lambda path: path in (expected_image_artifact,
                                                     expected_image_dir))
        self.Patch(os.path, "isdir",
                   side_effect=lambda path: path == expected_image_dir)
        self.Patch(os.path, "isfile",
                   side_effect=lambda path: path == expected_image_artifact)

        # Specified --local-image to a local zipped image file
        self.args.local_image = "/path/cf_x86_phone-img-eng.user.zip"
        self.AvdSpec._avd_type = constants.TYPE_CF
        self.AvdSpec._instance_type = constants.INSTANCE_TYPE_REMOTE
        self.AvdSpec._ProcessLocalImageArgs(self.args)
        self.assertEqual(self.AvdSpec._local_image_artifact,
                         expected_image_artifact)

        # Specified --local-image to a dir contains images
        self.Patch(utils, "GetBuildEnvironmentVariable",
                   return_value="test_cf_x86")
        self.args.local_image = "/path-to-image-dir"
        self.AvdSpec._avd_type = constants.TYPE_CF
        self.AvdSpec._instance_type = constants.INSTANCE_TYPE_REMOTE
        self.AvdSpec._ProcessLocalImageArgs(self.args)
        self.assertEqual(self.AvdSpec._local_image_dir, expected_image_dir)

        # Specified local_image without arg
        self.args.local_image = constants.FIND_IN_BUILD_ENV
        self.Patch(utils, "GetBuildEnvironmentVariable",
                   side_effect=["cf_x86_auto", "test_environ", "test_environ"])
        self.AvdSpec._ProcessLocalImageArgs(self.args)
        self.assertEqual(self.AvdSpec._local_image_dir, "test_environ")
        self.assertEqual(self.AvdSpec.local_image_artifact, expected_image_artifact)

        # Specified --avd-type=goldfish --local-image with a dir
        self.Patch(utils, "GetBuildEnvironmentVariable",
                   return_value="test_environ")
        self.args.local_image = "/path-to-image-dir"
        self.AvdSpec._avd_type = constants.TYPE_GF
        self.AvdSpec._instance_type = constants.INSTANCE_TYPE_LOCAL
        self.AvdSpec._ProcessLocalImageArgs(self.args)
        self.assertEqual(self.AvdSpec._local_image_dir, expected_image_dir)

    def testProcessLocalMixedImageArgs(self):
        """Test process args.local_kernel_image and args.local_system_image."""
        expected_image_dir = "/path-to-image-dir"
        expected_image_file = "/path-to-image-file"
        self.Patch(os.path, "exists",
                   side_effect=lambda path: path in (expected_image_file,
                                                     expected_image_dir))
        self.Patch(os.path, "isdir",
                   side_effect=lambda path: path == expected_image_dir)
        self.Patch(os.path, "isfile",
                   side_effect=lambda path: path == expected_image_file)

        # Specified --local-*-image with dirs.
        self.args.local_kernel_image = expected_image_dir
        self.args.local_system_image = expected_image_dir
        self.args.local_vendor_image = expected_image_dir
        self.AvdSpec._ProcessImageArgs(self.args)
        self.assertEqual(self.AvdSpec.local_kernel_image, expected_image_dir)
        self.assertEqual(self.AvdSpec.local_system_image, expected_image_dir)
        self.assertEqual(self.AvdSpec.local_vendor_image, expected_image_dir)

        # Specified --local-*-image with files.
        self.args.local_kernel_image = expected_image_file
        self.args.local_system_image = expected_image_file
        self.AvdSpec._ProcessImageArgs(self.args)
        self.assertEqual(self.AvdSpec.local_kernel_image, expected_image_file)
        self.assertEqual(self.AvdSpec.local_system_image, expected_image_file)

        # Specified --local-*-image without args.
        self.args.local_kernel_image = constants.FIND_IN_BUILD_ENV
        self.args.local_system_image = constants.FIND_IN_BUILD_ENV
        self.args.local_vendor_image = constants.FIND_IN_BUILD_ENV
        with mock.patch("acloud.create.avd_spec.utils."
                        "GetBuildEnvironmentVariable",
                        return_value=expected_image_dir):
            self.AvdSpec._ProcessImageArgs(self.args)
        self.assertEqual(self.AvdSpec.local_kernel_image, expected_image_dir)
        self.assertEqual(self.AvdSpec.local_system_image, expected_image_dir)
        self.assertEqual(self.AvdSpec.local_vendor_image, expected_image_dir)

    def testProcessAutoconnect(self):
        """Test process autoconnect."""
        self.AvdSpec._autoconnect = False
        self.AvdSpec._ProcessAutoconnect()
        self.assertEqual(self.AvdSpec._autoconnect, False)

        self.AvdSpec._avd_type = constants.TYPE_CF
        self.AvdSpec._autoconnect = "webrtc"
        self.AvdSpec._ProcessAutoconnect()
        self.assertEqual(self.AvdSpec._autoconnect, "webrtc")

        self.AvdSpec._autoconnect = "vnc"
        self.AvdSpec._ProcessAutoconnect()
        self.assertEqual(self.AvdSpec._autoconnect, "vnc")

        self.AvdSpec._avd_type = constants.TYPE_GF
        self.AvdSpec._autoconnect = "webrtc"
        self.AvdSpec._ProcessAutoconnect()
        self.assertEqual(self.AvdSpec._autoconnect, "vnc")

    def testProcessImageArgs(self):
        """Test process image source."""
        self.Patch(glob, "glob", return_value=["fake.img"])
        # No specified local_image, image source is from remote
        self.AvdSpec._ProcessImageArgs(self.args)
        self.assertEqual(self.AvdSpec._image_source, constants.IMAGE_SRC_REMOTE)
        self.assertEqual(self.AvdSpec._local_image_dir, None)
        self.assertEqual(self.AvdSpec.local_kernel_image, None)
        self.assertEqual(self.AvdSpec.local_system_image, None)

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
    def testGetBranchFromRepo(self, mock_gitremote):
        """Test get branch name from repo info."""
        # Check aosp repo gets proper branch prefix.
        fake_subprocess = mock.MagicMock()
        fake_subprocess.stdout = mock.MagicMock()
        fake_subprocess.stdout.readline = mock.MagicMock(return_value='')
        fake_subprocess.poll = mock.MagicMock(return_value=0)
        fake_subprocess.returncode = 0
        return_value = "Manifest branch: fake_branch"
        fake_subprocess.communicate = mock.MagicMock(return_value=(return_value, ''))
        self.Patch(subprocess, "Popen", return_value=fake_subprocess)

        mock_gitremote.return_value = "aosp"
        self.assertEqual(self.AvdSpec._GetBranchFromRepo(), "aosp-fake_branch")

        # Check default repo gets default branch prefix.
        mock_gitremote.return_value = ""
        return_value = "Manifest branch: fake_branch"
        fake_subprocess.communicate = mock.MagicMock(return_value=(return_value, ''))
        self.Patch(subprocess, "Popen", return_value=fake_subprocess)
        self.assertEqual(self.AvdSpec._GetBranchFromRepo(), "git_fake_branch")

        # Can't get branch from repo info, set it as default branch.
        return_value = "Manifest branch:"
        fake_subprocess.communicate = mock.MagicMock(return_value=(return_value, ''))
        self.Patch(subprocess, "Popen", return_value=fake_subprocess)
        self.assertEqual(self.AvdSpec._GetBranchFromRepo(), "aosp-master")

    def testGetBuildBranch(self):
        """Test GetBuildBranch function"""
        # Test infer branch from build_id and build_target.
        build_client = mock.MagicMock()
        build_id = "fake_build_id"
        build_target = "fake_build_target"
        expected_branch = "fake_build_branch"
        self.Patch(android_build_client, "AndroidBuildClient",
                   return_value=build_client)
        self.Patch(auth, "CreateCredentials", return_value=mock.MagicMock())
        self.Patch(build_client, "GetBranch", return_value=expected_branch)
        self.assertEqual(self.AvdSpec._GetBuildBranch(build_id, build_target),
                         expected_branch)
        # Infer branch from "repo info" when build_id and build_target is None.
        self.Patch(self.AvdSpec, "_GetBranchFromRepo", return_value="repo_branch")
        build_id = None
        build_target = None
        expected_branch = "repo_branch"
        self.assertEqual(self.AvdSpec._GetBuildBranch(build_id, build_target),
                         expected_branch)

    # pylint: disable=protected-access
    def testGetBuildTarget(self):
        """Test get build target name."""
        self.AvdSpec._remote_image[constants.BUILD_BRANCH] = "git_branch"
        self.AvdSpec._flavor = constants.FLAVOR_IOT
        self.args.avd_type = constants.TYPE_GCE
        self.assertEqual(
            self.AvdSpec._GetBuildTarget(self.args),
            "gce_x86_64_iot-userdebug")

        self.AvdSpec._remote_image[constants.BUILD_BRANCH] = "aosp-master"
        self.AvdSpec._flavor = constants.FLAVOR_PHONE
        self.args.avd_type = constants.TYPE_CF
        self.assertEqual(
            self.AvdSpec._GetBuildTarget(self.args),
            "aosp_cf_x86_64_phone-userdebug")

        self.AvdSpec._remote_image[constants.BUILD_BRANCH] = "git_branch"
        self.AvdSpec._flavor = constants.FLAVOR_PHONE
        self.args.avd_type = constants.TYPE_CF
        self.assertEqual(
            self.AvdSpec._GetBuildTarget(self.args),
            "cf_x86_64_phone-userdebug")

    # pylint: disable=protected-access
    def testProcessHWPropertyWithInvalidArgs(self):
        """Test _ProcessHWPropertyArgs with invalid args."""
        # Checking wrong resolution.
        args = mock.MagicMock()
        args.hw_property = "cpu:3,resolution:1280"
        args.reuse_instance_name = None
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
    @mock.patch.object(utils, "PrintColorString")
    def testCheckCFBuildTarget(self, print_warning):
        """Test _CheckCFBuildTarget."""
        # patch correct env variable.
        self.Patch(utils, "GetBuildEnvironmentVariable",
                   return_value="cf_x86_phone-userdebug")
        self.AvdSpec._CheckCFBuildTarget(constants.INSTANCE_TYPE_REMOTE)
        self.AvdSpec._CheckCFBuildTarget(constants.INSTANCE_TYPE_LOCAL)

        self.Patch(utils, "GetBuildEnvironmentVariable",
                   return_value="aosp_cf_arm64_auto-userdebug")
        self.AvdSpec._CheckCFBuildTarget(constants.INSTANCE_TYPE_HOST)
        # patch wrong env variable.
        self.Patch(utils, "GetBuildEnvironmentVariable",
                   return_value="test_environ")
        self.AvdSpec._CheckCFBuildTarget(constants.INSTANCE_TYPE_REMOTE)

        print_warning.assert_called_once()

    # pylint: disable=protected-access
    def testParseHWPropertyStr(self):
        """Test _ParseHWPropertyStr."""
        expected_dict = {"cpu": "2", "x_res": "1080", "y_res": "1920",
                         "dpi": "240", "memory": "4096", "disk": "4096"}
        args_str = "cpu:2,resolution:1080x1920,dpi:240,memory:4g,disk:4g"
        result_dict = self.AvdSpec._ParseHWPropertyStr(args_str)
        self.assertTrue(expected_dict == result_dict)

        expected_dict = {"cpu": "2", "x_res": "1080", "y_res": "1920",
                         "dpi": "240", "memory": "512", "disk": "4096"}
        args_str = "cpu:2,resolution:1080x1920,dpi:240,memory:512m,disk:4g"
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

    # pylint: disable=protected-access,too-many-statements
    def testProcessRemoteBuildArgs(self):
        """Test _ProcessRemoteBuildArgs."""
        self.args.branch = "git_master"
        self.args.build_id = "1234"
        self.args.launch_args = None

        # Verify auto-assigned avd_type if build_targe contains "_gce_".
        self.args.build_target = "aosp_gce_x86_phone-userdebug"
        self.AvdSpec._ProcessRemoteBuildArgs(self.args)
        self.assertTrue(self.AvdSpec.avd_type == "gce")

        # Verify auto-assigned avd_type if build_targe contains "gce_".
        self.args.build_target = "gce_x86_phone-userdebug"
        self.AvdSpec._ProcessRemoteBuildArgs(self.args)
        self.assertTrue(self.AvdSpec.avd_type == "gce")

        # Verify auto-assigned avd_type if build_targe contains "_cf_".
        self.args.build_target = "aosp_cf_x86_64_phone-userdebug"
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

        # Verify extra build info.
        self.args.system_branch = "system_branch"
        self.args.system_build_target = "system_build_target"
        self.args.system_build_id = "system_build_id"
        self.args.ota_branch = "ota_branch"
        self.args.ota_build_target = "ota_build_target"
        self.args.ota_build_id = "ota_build_id"
        self.args.kernel_branch = "kernel_branch"
        self.args.kernel_build_target = "kernel_build_target"
        self.args.kernel_build_id = "kernel_build_id"
        self.args.boot_branch = "boot_branch"
        self.args.boot_build_target = "boot_build_target"
        self.args.boot_build_id = "boot_build_id"
        self.args.boot_artifact = "boot_artifact"
        self.AvdSpec._ProcessRemoteBuildArgs(self.args)
        self.assertEqual(
            {constants.BUILD_BRANCH: "system_branch",
             constants.BUILD_TARGET: "system_build_target",
             constants.BUILD_ID: "system_build_id"},
            self.AvdSpec.system_build_info)
        self.assertEqual(
            {constants.BUILD_BRANCH: "kernel_branch",
             constants.BUILD_TARGET: "kernel_build_target",
             constants.BUILD_ID: "kernel_build_id"},
            self.AvdSpec.kernel_build_info)
        self.assertEqual(
            {constants.BUILD_BRANCH: "boot_branch",
             constants.BUILD_TARGET: "boot_build_target",
             constants.BUILD_ID: "boot_build_id",
             constants.BUILD_ARTIFACT: "boot_artifact"},
            self.AvdSpec.boot_build_info)
        self.assertEqual(
            {constants.BUILD_BRANCH: "ota_branch",
             constants.BUILD_TARGET: "ota_build_target",
             constants.BUILD_ID: "ota_build_id"},
            self.AvdSpec.ota_build_info)

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
        self.assertRaises(errors.ImgDoesNotExist,
                          self.AvdSpec._GetGceLocalImagePath, fake_image_path)

    def testProcessMiscArgs(self):
        """Test process misc args."""
        self.args.remote_host = None
        self.args.local_instance = None
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertEqual(self.AvdSpec._instance_type, constants.INSTANCE_TYPE_REMOTE)

        self.args.remote_host = None
        self.args.local_instance = 0
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertEqual(self.AvdSpec._instance_type, constants.INSTANCE_TYPE_LOCAL)

        self.args.remote_host = "1.1.1.1"
        self.args.local_instance = None
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertEqual(self.AvdSpec._instance_type, constants.INSTANCE_TYPE_HOST)

        self.args.remote_host = "1.1.1.1"
        self.args.local_instance = 1
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertEqual(self.AvdSpec._instance_type, constants.INSTANCE_TYPE_HOST)

        self.args.oxygen = True
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertTrue(self.AvdSpec._oxygen)

        # Test avd_spec.autoconnect
        self.args.autoconnect = False
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertEqual(self.AvdSpec.autoconnect, False)
        self.assertEqual(self.AvdSpec.connect_adb, False)
        self.assertEqual(self.AvdSpec.connect_fastboot, False)
        self.assertEqual(self.AvdSpec.connect_vnc, False)
        self.assertEqual(self.AvdSpec.connect_webrtc, False)

        self.args.autoconnect = constants.INS_KEY_VNC
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertEqual(self.AvdSpec.autoconnect, True)
        self.assertEqual(self.AvdSpec.connect_adb, True)
        self.assertEqual(self.AvdSpec.connect_fastboot, True)
        self.assertEqual(self.AvdSpec.connect_vnc, True)
        self.assertEqual(self.AvdSpec.connect_webrtc, False)

        self.args.autoconnect = constants.INS_KEY_ADB
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertEqual(self.AvdSpec.autoconnect, True)
        self.assertEqual(self.AvdSpec.connect_adb, True)
        self.assertEqual(self.AvdSpec.connect_fastboot, True)
        self.assertEqual(self.AvdSpec.connect_vnc, False)
        self.assertEqual(self.AvdSpec.connect_webrtc, False)

        self.args.autoconnect = constants.INS_KEY_FASTBOOT
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertEqual(self.AvdSpec.autoconnect, True)
        self.assertEqual(self.AvdSpec.connect_adb, True)
        self.assertEqual(self.AvdSpec.connect_fastboot, True)
        self.assertEqual(self.AvdSpec.connect_vnc, False)
        self.assertEqual(self.AvdSpec.connect_webrtc, False)

        self.args.autoconnect = constants.INS_KEY_WEBRTC
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertEqual(self.AvdSpec.autoconnect, True)
        self.assertEqual(self.AvdSpec.connect_adb, True)
        self.assertEqual(self.AvdSpec.connect_fastboot, True)
        self.assertEqual(self.AvdSpec.connect_vnc, False)
        self.assertEqual(self.AvdSpec.connect_webrtc, True)

        # Test stable host image name.
        self.args.stable_host_image_name = "fake_host_image"
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertEqual(self.AvdSpec.stable_host_image_name, "fake_host_image")

        # Setup acloud config with betty_image spec
        self.mock_config.betty_image = 'from-config'
        # --betty-image from cmdline should override config
        self.args.cheeps_betty_image = 'from-cmdline'
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertEqual(self.AvdSpec.cheeps_betty_image, 'from-cmdline')
        # acloud config value is used otherwise
        self.args.cheeps_betty_image = None
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertEqual(self.AvdSpec.cheeps_betty_image, 'from-config')

        # Verify cheeps_features is assigned from args.
        self.args.cheeps_features = ['a', 'b', 'c']
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertEqual(self.args.cheeps_features, ['a', 'b', 'c'])

        # Verify connect_hostname
        self.mock_config.connect_hostname = True
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertTrue(self.AvdSpec.connect_hostname)
        self.args.connect_hostname = True
        self.mock_config.connect_hostname = False
        self.assertTrue(self.AvdSpec.connect_hostname)

        # Verify fetch_cvd_version
        self.args.fetch_cvd_build_id = None
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertEqual(self.AvdSpec.fetch_cvd_version, "LKGB")

        self.args.fetch_cvd_build_id = "23456"
        self.AvdSpec._ProcessMiscArgs(self.args)
        self.assertEqual(self.AvdSpec.fetch_cvd_version, "23456")


if __name__ == "__main__":
    unittest.main()
