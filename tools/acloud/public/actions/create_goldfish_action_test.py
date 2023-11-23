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
"""Tests for acloud.public.actions.create_goldfish_actions."""
import uuid
import unittest
import mock

from acloud.internal import constants
from acloud.internal.lib import android_build_client
from acloud.internal.lib import android_compute_client
from acloud.internal.lib import auth
from acloud.internal.lib import driver_test_lib
from acloud.internal.lib import gcompute_client
from acloud.internal.lib import goldfish_compute_client
from acloud.public.actions import create_goldfish_action


class CreateGoldfishActionTest(driver_test_lib.BaseDriverTest):
    """Tests create_goldfish_action."""

    IP = gcompute_client.IP(external="127.0.0.1", internal="10.0.0.1")
    INSTANCE = "fake-instance"
    IMAGE = "fake-image"
    BUILD_TARGET = "fake-build-target"
    EMULATOR_TARGET = "emu-fake-target"
    BUILD_ID = "12345"
    EMULATOR_BUILD_ID = "1234567"
    GPU = "nvidia-tesla-k80"
    BRANCH = "fake-branch"
    EMULATOR_BRANCH = "emu-fake-branch"
    GOLDFISH_HOST_IMAGE_NAME = "fake-stable-host-image-name"
    GOLDFISH_HOST_IMAGE_PROJECT = "fake-stable-host-image-project"
    EXTRA_DATA_DISK_GB = 4
    EXTRA_SCOPES = None

    def setUp(self):
        """Sets up the test."""
        super(CreateGoldfishActionTest, self).setUp()
        self.build_client = mock.MagicMock()
        self.Patch(
            android_build_client,
            "AndroidBuildClient",
            return_value=self.build_client)
        self.compute_client = mock.MagicMock()
        self.Patch(
            goldfish_compute_client,
            "GoldfishComputeClient",
            return_value=self.compute_client)
        self.Patch(
            android_compute_client,
            "AndroidComputeClient",
            return_value=self.compute_client)
        self.Patch(auth, "CreateCredentials", return_value=mock.MagicMock())
        #Initialize new avd_spec
        self.avd_spec = mock.MagicMock()
        self.avd_spec.cfg = self._CreateCfg()
        self.avd_spec.remote_image = {constants.BUILD_ID: self.BUILD_ID,
                                      constants.BUILD_BRANCH: self.BRANCH,
                                      constants.BUILD_TARGET: self.BUILD_TARGET}
        self.avd_spec.emulator_build_id = self.EMULATOR_BUILD_ID
        self.avd_spec.gpu = self.GPU
        self.avd_spec.serial_log_file = None
        self.avd_spec.autoconnect = False

    def _CreateCfg(self):
        """A helper method that creates a mock configuration object."""
        cfg = mock.MagicMock()
        cfg.service_account_name = "fake@service.com"
        cfg.service_account_private_key_path = "/fake/path/to/key"
        cfg.zone = "fake_zone"
        cfg.ssh_private_key_path = ""
        cfg.ssh_public_key_path = ""
        cfg.stable_goldfish_host_image_name = self.GOLDFISH_HOST_IMAGE_NAME
        cfg.stable_goldfish_host_image_project = self.GOLDFISH_HOST_IMAGE_PROJECT
        cfg.emulator_build_target = self.EMULATOR_TARGET
        cfg.extra_data_disk_size_gb = self.EXTRA_DATA_DISK_GB
        cfg.extra_scopes = self.EXTRA_SCOPES
        return cfg

    def testCreateDevices(self):
        """Tests CreateDevices."""
        cfg = self._CreateCfg()

        # Mock uuid
        fake_uuid = mock.MagicMock(hex="1234")
        self.Patch(uuid, "uuid4", return_value=fake_uuid)

        # Mock compute client methods
        self.compute_client.GetInstanceIP.return_value = self.IP
        self.compute_client.GenerateImageName.return_value = self.IMAGE
        self.compute_client.GenerateInstanceName.return_value = self.INSTANCE

        # Mock build client method
        self.build_client.GetBranch.side_effect = [
            self.BRANCH, self.EMULATOR_BRANCH
        ]

        none_avd_spec = None

        # Call CreateDevices with avd_spec is None
        report = create_goldfish_action.CreateDevices(
            none_avd_spec, cfg, self.BUILD_TARGET, self.BUILD_ID,
            self.EMULATOR_BUILD_ID, self.GPU)

        # Verify
        self.compute_client.CreateInstance.assert_called_with(
            instance=self.INSTANCE,
            blank_data_disk_size_gb=self.EXTRA_DATA_DISK_GB,
            image_name=self.GOLDFISH_HOST_IMAGE_NAME,
            image_project=self.GOLDFISH_HOST_IMAGE_PROJECT,
            build_target=self.BUILD_TARGET,
            branch=self.BRANCH,
            build_id=self.BUILD_ID,
            emulator_branch=self.EMULATOR_BRANCH,
            emulator_build_id=self.EMULATOR_BUILD_ID,
            gpu=self.GPU,
            avd_spec=none_avd_spec,
            extra_scopes=self.EXTRA_SCOPES)

        self.assertEquals(report.data, {
            "devices": [
                {
                    "instance_name": self.INSTANCE,
                    "ip": self.IP.external,
                    "branch": self.BRANCH,
                    "build_id": self.BUILD_ID,
                    "build_target": self.BUILD_TARGET,
                    "emulator_branch": self.EMULATOR_BRANCH,
                    "emulator_build_id": self.EMULATOR_BUILD_ID,
                    "emulator_build_target": self.EMULATOR_TARGET,
                },
            ],
        })
        self.assertEquals(report.command, "create_gf")
        self.assertEquals(report.status, "SUCCESS")

        # Call CreateDevices with avd_spec
        self.build_client.GetBranch.side_effect = [
            self.BRANCH, self.EMULATOR_BRANCH
        ]
        report = create_goldfish_action.CreateDevices(avd_spec=self.avd_spec)
        # Verify
        self.compute_client.CreateInstance.assert_called_with(
            instance=self.INSTANCE,
            blank_data_disk_size_gb=self.EXTRA_DATA_DISK_GB,
            image_name=self.GOLDFISH_HOST_IMAGE_NAME,
            image_project=self.GOLDFISH_HOST_IMAGE_PROJECT,
            build_target=self.BUILD_TARGET,
            branch=self.BRANCH,
            build_id=self.BUILD_ID,
            emulator_branch=self.EMULATOR_BRANCH,
            emulator_build_id=self.EMULATOR_BUILD_ID,
            gpu=self.GPU,
            avd_spec=self.avd_spec,
            extra_scopes=self.EXTRA_SCOPES)

    def testCreateDevicesWithoutBuildId(self):
        """Test CreateDevices when emulator sys image build id is not provided."""
        cfg = self._CreateCfg()

        # Mock uuid
        fake_uuid = mock.MagicMock(hex="1234")
        self.Patch(uuid, "uuid4", return_value=fake_uuid)

        # Mock compute client methods
        self.compute_client.GetInstanceIP.return_value = self.IP
        self.compute_client.GenerateImageName.return_value = self.IMAGE
        self.compute_client.GenerateInstanceName.return_value = self.INSTANCE

        # Mock build client method
        self.build_client.GetBranch.side_effect = [
            self.BRANCH, self.EMULATOR_BRANCH
        ]

        # Mock _FetchBuildIdFromFile method
        self.Patch(
            create_goldfish_action,
            "_FetchBuildIdFromFile",
            return_value=self.BUILD_ID)

        none_avd_spec = None
        # Call CreateDevices with no avd_spec
        report = create_goldfish_action.CreateDevices(
            none_avd_spec,
            cfg,
            self.BUILD_TARGET,
            None,
            self.EMULATOR_BUILD_ID,
            self.GPU,
            branch=self.BRANCH)

        # Verify
        self.compute_client.CreateInstance.assert_called_with(
            instance=self.INSTANCE,
            blank_data_disk_size_gb=self.EXTRA_DATA_DISK_GB,
            image_name=self.GOLDFISH_HOST_IMAGE_NAME,
            image_project=self.GOLDFISH_HOST_IMAGE_PROJECT,
            build_target=self.BUILD_TARGET,
            branch=self.BRANCH,
            build_id=self.BUILD_ID,
            emulator_branch=self.EMULATOR_BRANCH,
            emulator_build_id=self.EMULATOR_BUILD_ID,
            gpu=self.GPU,
            avd_spec=none_avd_spec,
            extra_scopes=self.EXTRA_SCOPES)

        self.assertEquals(report.data, {
            "devices": [{
                "instance_name": self.INSTANCE,
                "ip": self.IP.external,
                "branch": self.BRANCH,
                "build_id": self.BUILD_ID,
                "build_target": self.BUILD_TARGET,
                "emulator_branch": self.EMULATOR_BRANCH,
                "emulator_build_id": self.EMULATOR_BUILD_ID,
                "emulator_build_target": self.EMULATOR_TARGET,
            },],
        })
        self.assertEquals(report.command, "create_gf")
        self.assertEquals(report.status, "SUCCESS")

        # Call CreateDevices with avd_spec
        self.build_client.GetBranch.side_effect = [
            self.BRANCH, self.EMULATOR_BRANCH
        ]
        report = create_goldfish_action.CreateDevices(avd_spec=self.avd_spec)
        # Verify
        self.compute_client.CreateInstance.assert_called_with(
            instance=self.INSTANCE,
            blank_data_disk_size_gb=self.EXTRA_DATA_DISK_GB,
            image_name=self.GOLDFISH_HOST_IMAGE_NAME,
            image_project=self.GOLDFISH_HOST_IMAGE_PROJECT,
            build_target=self.BUILD_TARGET,
            branch=self.BRANCH,
            build_id=self.BUILD_ID,
            emulator_branch=self.EMULATOR_BRANCH,
            emulator_build_id=self.EMULATOR_BUILD_ID,
            gpu=self.GPU,
            avd_spec=self.avd_spec,
            extra_scopes=self.EXTRA_SCOPES)

    #pylint: disable=invalid-name
    def testCreateDevicesWithoutEmulatorBuildId(self):
        """Test CreateDevices when emulator build id is not provided."""
        cfg = self._CreateCfg()

        # Mock uuid
        fake_uuid = mock.MagicMock(hex="1234")
        self.Patch(uuid, "uuid4", return_value=fake_uuid)

        # Mock compute client methods
        self.compute_client.GetInstanceIP.return_value = self.IP
        self.compute_client.GenerateImageName.return_value = self.IMAGE
        self.compute_client.GenerateInstanceName.return_value = self.INSTANCE

        # Mock build client method
        self.build_client.GetBranch.side_effect = [
            self.BRANCH, self.EMULATOR_BRANCH
        ]

        # Mock _FetchBuildIdFromFile method
        self.Patch(
            create_goldfish_action,
            "_FetchBuildIdFromFile",
            return_value=self.EMULATOR_BUILD_ID)

        none_avd_spec = None
        # Call CreateDevices
        report = create_goldfish_action.CreateDevices(
            none_avd_spec, cfg, self.BUILD_TARGET, self.BUILD_ID, None,
            self.GPU)

        # Verify
        self.compute_client.CreateInstance.assert_called_with(
            instance=self.INSTANCE,
            blank_data_disk_size_gb=self.EXTRA_DATA_DISK_GB,
            image_name=self.GOLDFISH_HOST_IMAGE_NAME,
            image_project=self.GOLDFISH_HOST_IMAGE_PROJECT,
            build_target=self.BUILD_TARGET,
            branch=self.BRANCH,
            build_id=self.BUILD_ID,
            emulator_branch=self.EMULATOR_BRANCH,
            emulator_build_id=self.EMULATOR_BUILD_ID,
            gpu=self.GPU,
            avd_spec=none_avd_spec,
            extra_scopes=self.EXTRA_SCOPES)

        self.assertEquals(report.data, {
            "devices": [{
                "instance_name": self.INSTANCE,
                "ip": self.IP.external,
                "branch": self.BRANCH,
                "build_id": self.BUILD_ID,
                "build_target": self.BUILD_TARGET,
                "emulator_branch": self.EMULATOR_BRANCH,
                "emulator_build_id": self.EMULATOR_BUILD_ID,
                "emulator_build_target": self.EMULATOR_TARGET,
            },],
        })
        self.assertEquals(report.command, "create_gf")
        self.assertEquals(report.status, "SUCCESS")

        # Call CreateDevices with avd_spec
        self.build_client.GetBranch.side_effect = [
            self.BRANCH, self.EMULATOR_BRANCH
        ]
        report = create_goldfish_action.CreateDevices(avd_spec=self.avd_spec)
        # Verify
        self.compute_client.CreateInstance.assert_called_with(
            instance=self.INSTANCE,
            blank_data_disk_size_gb=self.EXTRA_DATA_DISK_GB,
            image_name=self.GOLDFISH_HOST_IMAGE_NAME,
            image_project=self.GOLDFISH_HOST_IMAGE_PROJECT,
            build_target=self.BUILD_TARGET,
            branch=self.BRANCH,
            build_id=self.BUILD_ID,
            emulator_branch=self.EMULATOR_BRANCH,
            emulator_build_id=self.EMULATOR_BUILD_ID,
            gpu=self.GPU,
            avd_spec=self.avd_spec,
            extra_scopes=self.EXTRA_SCOPES)


if __name__ == "__main__":
    unittest.main()
