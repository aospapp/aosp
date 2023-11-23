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
"""Tests for acloud.setup.gcp_setup_runner."""

import unittest
import os
import mock

# pylint: disable=no-name-in-module,import-error
from acloud.internal.proto import user_config_pb2
from acloud.public import config
from acloud.setup import gcp_setup_runner

_GCP_USER_CONFIG = """
[compute]
region = new_region
zone = new_zone
[core]
account = new@google.com
disable_usage_reporting = False
project = new_project
"""


def _CreateCfgFile():
    """A helper method that creates a mock configuration object."""
    default_cfg = """
project: "fake_project"
zone: "fake_zone"
storage_bucket_name: "fake_bucket"
client_id: "fake_client_id"
client_secret: "fake_client_secret"
"""
    return default_cfg


# pylint: disable=protected-access
class AcloudGCPSetupTest(unittest.TestCase):
    """Test GCP Setup steps."""

    def setUp(self):
        """Create config and gcp_env_runner."""
        self.cfg_path = "acloud_unittest.config"
        file_write = open(self.cfg_path, 'w')
        file_write.write(_CreateCfgFile().strip())
        file_write.close()
        self.gcp_env_runner = gcp_setup_runner.GcpTaskRunner(self.cfg_path)
        self.gcloud_runner = gcp_setup_runner.GoogleSDKBins("")

    def tearDown(self):
        """Remove temp file."""
        if os.path.isfile(self.cfg_path):
            os.remove(self.cfg_path)

    def testUpdateConfigFile(self):
        """Test update config file."""
        # Test update project field.
        gcp_setup_runner.UpdateConfigFile(self.cfg_path, "project",
                                          "test_project")
        cfg = config.AcloudConfigManager.LoadConfigFromProtocolBuffer(
            open(self.cfg_path, "r"), user_config_pb2.UserConfig)
        self.assertEqual(cfg.project, "test_project")
        self.assertEqual(cfg.ssh_private_key_path, "")
        # Test add ssh key path in config
        gcp_setup_runner.UpdateConfigFile(self.cfg_path,
                                          "ssh_private_key_path", "test_path")
        cfg = config.AcloudConfigManager.LoadConfigFromProtocolBuffer(
            open(self.cfg_path, "r"), user_config_pb2.UserConfig)
        self.assertEqual(cfg.project, "test_project")
        self.assertEqual(cfg.ssh_private_key_path, "test_path")

    @mock.patch.object(gcp_setup_runner.GcpTaskRunner, "_CreateBucket")
    @mock.patch.object(gcp_setup_runner.GcpTaskRunner, "_BucketExists")
    @mock.patch.object(gcp_setup_runner.GcpTaskRunner, "_BucketInDefaultRegion")
    def testCreateDefaultBucket(self, mock_valid, mock_exist, mock_create):
        """Test default bucket name.

        Default bucket name is "acloud-{project}".
        If default bucket exist but region is not in default region,
        bucket name changes to "acloud-{project}-us".
        """
        self.gcp_env_runner.project = "fake_project"
        mock_exist.return_value = False
        mock_valid.return_value = False
        mock_create.return_value = True
        self.assertEqual(
            "acloud-fake_project",
            self.gcp_env_runner._CreateDefaultBucket(self.gcloud_runner))
        mock_exist.return_value = True
        mock_valid.return_value = False
        self.assertEqual(
            "acloud-fake_project-%s" %
            gcp_setup_runner._DEFAULT_BUCKET_REGION.lower(),
            self.gcp_env_runner._CreateDefaultBucket(self.gcloud_runner))

    @mock.patch("os.path.dirname", return_value="")
    @mock.patch("subprocess.check_output")
    def testSeupProjectZone(self, mock_runner, mock_path):
        """Test setup project and zone."""
        gcloud_runner = gcp_setup_runner.GoogleSDKBins(mock_path)
        self.gcp_env_runner.project = "fake_project"
        self.gcp_env_runner.zone = "fake_zone"
        mock_runner.side_effect = [0, _GCP_USER_CONFIG]
        self.gcp_env_runner._UpdateProject(gcloud_runner)
        self.assertEqual(self.gcp_env_runner.project, "new_project")
        self.assertEqual(self.gcp_env_runner.zone, "new_zone")

    @mock.patch("__builtin__.raw_input")
    def testSetupClientIDSecret(self, mock_id):
        """Test setup client ID and client secret."""
        self.gcp_env_runner.client_id = "fake_client_id"
        self.gcp_env_runner.client_secret = "fake_client_secret"
        mock_id.side_effect = ["new_id", "new_secret"]
        self.gcp_env_runner._SetupClientIDSecret()
        self.assertEqual(self.gcp_env_runner.client_id, "new_id")
        self.assertEqual(self.gcp_env_runner.client_secret, "new_secret")

    def testGenerateBucketName(self):
        """Test generate default bucket name."""
        # Filter out organization name for project name.
        bucket_name = self.gcp_env_runner._GenerateBucketName(
            "AOSP.com:fake_project")
        self.assertEqual(bucket_name, "acloud-fake_project")

        # A bucket name can contain lowercase alphanumeric characters,
        # hyphens and underscores.
        bucket_name = self.gcp_env_runner._GenerateBucketName(
            "@.fake_*Project.#")
        self.assertEqual(bucket_name, "acloud-fake_project")

        # Bucket names must limit to 63 characters.
        bucket_name = self.gcp_env_runner._GenerateBucketName(
            "fake_project-fake_project-fake_project-fake_project-fake_project")
        self.assertEqual(bucket_name,
                         "acloud-fake_project-fake_project-fake_project-fake_project-fake")

        # Rule 3: Bucket names must end with a number or letter.
        bucket_name = self.gcp_env_runner._GenerateBucketName("fake_project__--")
        self.assertEqual(bucket_name, "acloud-fake_project")

    @mock.patch.object(gcp_setup_runner.GoogleSDKBins, "RunGsutil")
    def testBucketExists(self, mock_bucket_name):
        """Test bucket name exist or not."""
        mock_bucket_name.return_value = "gs://acloud-fake_project/"
        self.assertTrue(
            self.gcp_env_runner._BucketExists("acloud-fake_project",
                                              self.gcloud_runner))
        self.assertFalse(
            self.gcp_env_runner._BucketExists("wrong_project",
                                              self.gcloud_runner))

    @mock.patch.object(gcp_setup_runner.GoogleSDKBins, "RunGsutil")
    def testBucketNotInDefaultRegion(self, mock_region):
        """Test bucket region is in default region or not."""
        mock_region.return_value = "Location constraint:ASIA"
        self.assertFalse(
            self.gcp_env_runner._BucketInDefaultRegion("test-bucket",
                                                       self.gcloud_runner))
        mock_region.return_value = "Location constraint:US"
        self.assertTrue(
            self.gcp_env_runner._BucketInDefaultRegion("test-bucket",
                                                       self.gcloud_runner))


if __name__ == "__main__":
    unittest.main()
