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
"""Gcloud setup runner."""

from __future__ import print_function
import logging
import os
import re
import subprocess

from acloud import errors
from acloud.internal.lib import utils
from acloud.public import config
from acloud.setup import base_task_runner
from acloud.setup import google_sdk

# APIs that need to be enabled for GCP project.
_ANDROID_BUILD_SERVICE = "androidbuildinternal.googleapis.com"
_COMPUTE_ENGINE_SERVICE = "compute.googleapis.com"
_GOOGLE_CLOUD_STORAGE_SERVICE = "storage-component.googleapis.com"
_GOOGLE_APIS = [
    _GOOGLE_CLOUD_STORAGE_SERVICE, _ANDROID_BUILD_SERVICE,
    _COMPUTE_ENGINE_SERVICE
]
_BUILD_SERVICE_ACCOUNT = "android-build-prod@system.gserviceaccount.com"
_DEFAULT_SSH_FOLDER = os.path.expanduser("~/.ssh")
_DEFAULT_SSH_KEY = "acloud_rsa"
_DEFAULT_SSH_PRIVATE_KEY = os.path.join(_DEFAULT_SSH_FOLDER,
                                        _DEFAULT_SSH_KEY)
_DEFAULT_SSH_PUBLIC_KEY = os.path.join(_DEFAULT_SSH_FOLDER,
                                       _DEFAULT_SSH_KEY + ".pub")
# Bucket naming parameters
_BUCKET_HEADER = "gs://"
_BUCKET_LENGTH_LIMIT = 63
_DEFAULT_BUCKET_HEADER = "acloud"
_DEFAULT_BUCKET_REGION = "US"
_INVALID_BUCKET_NAME_END_CHARS = "_-"
_PROJECT_SEPARATOR = ":"
# Regular expression to get project/zone/bucket information.
_BUCKET_RE = re.compile(r"^gs://(?P<bucket>.+)/")
_BUCKET_REGION_RE = re.compile(r"^Location constraint:(?P<region>.+)")
_PROJECT_RE = re.compile(r"^project = (?P<project>.+)")
_ZONE_RE = re.compile(r"^zone = (?P<zone>.+)")

logger = logging.getLogger(__name__)


def UpdateConfigFile(config_path, item, value):
    """Update config data.

    Case A: config file contain this item.
        In config, "project = A_project". New value is B_project
        Set config "project = B_project".
    Case B: config file didn't contain this item.
        New value is B_project.
        Setup config as "project = B_project".

    Args:
        config_path: String, acloud config path.
        item: String, item name in config file. EX: project, zone
        value: String, value of item in config file.

    TODO(111574698): Refactor this to minimize writes to the config file.
    TODO(111574698): Use proto method to update config.
    """
    write_lines = []
    find_item = False
    write_line = item + ": \"" + value + "\"\n"
    if os.path.isfile(config_path):
        with open(config_path, "r") as cfg_file:
            for read_line in cfg_file.readlines():
                if read_line.startswith(item + ":"):
                    find_item = True
                    write_lines.append(write_line)
                else:
                    write_lines.append(read_line)
    if not find_item:
        write_lines.append(write_line)
    with open(config_path, "w") as cfg_file:
        cfg_file.writelines(write_lines)


def SetupSSHKeys(config_path, private_key_path, public_key_path):
    """Setup the pair of the ssh key for acloud.config.

    User can use the default path: "~/.ssh/acloud_rsa".

    Args:
        config_path: String, acloud config path.
        private_key_path: Path to the private key file.
                          e.g. ~/.ssh/acloud_rsa
        public_key_path: Path to the public key file.
                         e.g. ~/.ssh/acloud_rsa.pub
    """
    private_key_path = os.path.expanduser(private_key_path)
    if (private_key_path == "" or public_key_path == ""
            or private_key_path == _DEFAULT_SSH_PRIVATE_KEY):
        utils.CreateSshKeyPairIfNotExist(_DEFAULT_SSH_PRIVATE_KEY,
                                         _DEFAULT_SSH_PUBLIC_KEY)
        UpdateConfigFile(config_path, "ssh_private_key_path",
                         _DEFAULT_SSH_PRIVATE_KEY)
        UpdateConfigFile(config_path, "ssh_public_key_path",
                         _DEFAULT_SSH_PUBLIC_KEY)


def _InputIsEmpty(input_string):
    """Check input string is empty.

    Tool requests user to input client ID & client secret.
    This basic check can detect user input is empty.

    Args:
        input_string: String, user input string.

    Returns:
        Boolean: True if input is empty, False otherwise.
    """
    if input_string is None:
        return True
    if input_string == "":
        print("Please enter a non-empty value.")
        return True
    return False


class GoogleSDKBins(object):
    """Class to run tools in the Google SDK."""

    def __init__(self, google_sdk_folder):
        """GoogleSDKBins initialize.

        Args:
            google_sdk_folder: String, google sdk path.
        """
        self.gcloud_command_path = os.path.join(google_sdk_folder, "gcloud")
        self.gsutil_command_path = os.path.join(google_sdk_folder, "gsutil")

    def RunGcloud(self, cmd, **kwargs):
        """Run gcloud command.

        Args:
            cmd: String list, command strings.
                  Ex: [config], then this function call "gcloud config".
            **kwargs: dictionary of keyword based args to pass to func.

        Returns:
            String, return message after execute gcloud command.
        """
        return subprocess.check_output([self.gcloud_command_path] + cmd, **kwargs)

    def RunGsutil(self, cmd, **kwargs):
        """Run gsutil command.

        Args:
            cmd : String list, command strings.
                  Ex: [list], then this function call "gsutil list".
            **kwargs: dictionary of keyword based args to pass to func.

        Returns:
            String, return message after execute gsutil command.
        """
        return subprocess.check_output([self.gsutil_command_path] + cmd, **kwargs)


class GcpTaskRunner(base_task_runner.BaseTaskRunner):
    """Runner to setup google cloud user information."""

    WELCOME_MESSAGE_TITLE = "Setup google cloud user information"
    WELCOME_MESSAGE = (
        "This step will walk you through gcloud SDK installation."
        "Then configure gcloud user information."
        "Finally enable some gcloud API services.")

    def __init__(self, config_path):
        """Initialize parameters.

        Load config file to get current values.

        Args:
            config_path: String, acloud config path.
        """
        # pylint: disable=invalid-name
        config_mgr = config.AcloudConfigManager(config_path)
        cfg = config_mgr.Load()
        self.config_path = config_mgr.user_config_path
        self.project = cfg.project
        self.zone = cfg.zone
        self.storage_bucket_name = cfg.storage_bucket_name
        self.ssh_private_key_path = cfg.ssh_private_key_path
        self.ssh_public_key_path = cfg.ssh_public_key_path
        self.stable_host_image_name = cfg.stable_host_image_name
        self.client_id = cfg.client_id
        self.client_secret = cfg.client_secret
        self.service_account_name = cfg.service_account_name
        self.service_account_private_key_path = cfg.service_account_private_key_path
        self.service_account_json_private_key_path = cfg.service_account_json_private_key_path

    def ShouldRun(self):
        """Check if we actually need to run GCP setup.

        We'll only do the gcp setup if certain fields in the cfg are empty.

        Returns:
            True if reqired config fields are empty, False otherwise.
        """
        # We need to ensure the config has the proper auth-related fields set,
        # so config requires just 1 of the following:
        # 1. client id/secret
        # 2. service account name/private key path
        # 3. service account json private key path
        if ((not self.client_id or not self.client_secret)
                and (not self.service_account_name or not self.service_account_private_key_path)
                and not self.service_account_json_private_key_path):
            return True

        # If a project isn't set, then we need to run setup.
        return not self.project

    def _Run(self):
        """Run GCP setup task."""
        self._SetupGcloudInfo()
        SetupSSHKeys(self.config_path, self.ssh_private_key_path,
                     self.ssh_public_key_path)

    def _SetupGcloudInfo(self):
        """Setup Gcloud user information.
            1. Setup Gcloud SDK tools.
            2. Setup Gcloud project.
                a. Setup Gcloud project and zone.
                b. Setup Client ID and Client secret.
                c. Setup Google Cloud Storage bucket.
            3. Enable Gcloud API services.
        """
        google_sdk_init = google_sdk.GoogleSDK()
        try:
            google_sdk_runner = GoogleSDKBins(google_sdk_init.GetSDKBinPath())
            self._SetupProject(google_sdk_runner)
            self._EnableGcloudServices(google_sdk_runner)
            self._CreateStableHostImage()
        finally:
            google_sdk_init.CleanUp()

    def _CreateStableHostImage(self):
        """Create the stable host image."""
        # Write default stable_host_image_name with dummy value.
        # TODO(113091773): An additional step to create the host image.
        if not self.stable_host_image_name:
            UpdateConfigFile(self.config_path, "stable_host_image_name", "")


    def _NeedProjectSetup(self):
        """Confirm project setup should run or not.

        If the project settings (project name and zone) are blank (either one),
        we'll run the project setup flow. If they are set, we'll check with
        the user if they want to update them.

        Returns:
            Boolean: True if we need to setup the project, False otherwise.
        """
        user_question = (
            "Your default Project/Zone settings are:\n"
            "project:[%s]\n"
            "zone:[%s]\n"
            "Would you like to update them?[y/N]: \n") % (self.project, self.zone)

        if not self.project or not self.zone:
            logger.info("Project or zone is empty. Start to run setup process.")
            return True
        return utils.GetUserAnswerYes(user_question)

    def _NeedClientIDSetup(self, project_changed):
        """Confirm client setup should run or not.

        If project changed, client ID must also have to change.
        So tool will force to run setup function.
        If client ID or client secret is empty, tool force to run setup function.
        If project didn't change and config hold user client ID/secret, tool
        would skip client ID setup.

        Args:
            project_changed: Boolean, True for project changed.

        Returns:
            Boolean: True for run setup function.
        """
        if project_changed:
            logger.info("Your project changed. Start to run setup process.")
            return True
        elif not self.client_id or not self.client_secret:
            logger.info("Client ID or client secret is empty. Start to run setup process.")
            return True
        logger.info("Project was unchanged and client ID didn't need to changed.")
        return False

    def _SetupProject(self, gcloud_runner):
        """Setup gcloud project information.

        Setup project and zone.
        Setup client ID and client secret.
        Setup Google Cloud Storage bucket.

        Args:
            gcloud_runner: A GcloudRunner class to run "gcloud" command.
        """
        project_changed = False
        if self._NeedProjectSetup():
            project_changed = self._UpdateProject(gcloud_runner)
        if self._NeedClientIDSetup(project_changed):
            self._SetupClientIDSecret()
        self._SetupStorageBucket(gcloud_runner)

    def _UpdateProject(self, gcloud_runner):
        """Setup gcloud project name and zone name and check project changed.

        Run "gcloud init" to handle gcloud project setup.
        Then "gcloud list" to get user settings information include "project" & "zone".
        Record project_changed for next setup steps.

        Args:
            gcloud_runner: A GcloudRunner class to run "gcloud" command.

        Returns:
            project_changed: True for project settings changed.
        """
        project_changed = False
        gcloud_runner.RunGcloud(["init"])
        gcp_config_list_out = gcloud_runner.RunGcloud(["config", "list"])
        for line in gcp_config_list_out.splitlines():
            project_match = _PROJECT_RE.match(line)
            if project_match:
                project = project_match.group("project")
                project_changed = (self.project != project)
                self.project = project
                continue
            zone_match = _ZONE_RE.match(line)
            if zone_match:
                self.zone = zone_match.group("zone")
                continue
        UpdateConfigFile(self.config_path, "project", self.project)
        UpdateConfigFile(self.config_path, "zone", self.zone)
        return project_changed

    def _SetupClientIDSecret(self):
        """Setup Client ID / Client Secret in config file.

        User can use input new values for Client ID and Client Secret.
        """
        print("Please generate a new client ID/secret by following the instructions here:")
        print("https://support.google.com/cloud/answer/6158849?hl=en")
        # TODO: Create markdown readme instructions since the link isn't too helpful.
        self.client_id = None
        self.client_secret = None
        while _InputIsEmpty(self.client_id):
            self.client_id = str(raw_input("Enter Client ID: ").strip())
        while _InputIsEmpty(self.client_secret):
            self.client_secret = str(raw_input("Enter Client Secret: ").strip())
        UpdateConfigFile(self.config_path, "client_id", self.client_id)
        UpdateConfigFile(self.config_path, "client_secret", self.client_secret)

    def _SetupStorageBucket(self, gcloud_runner):
        """Setup storage_bucket_name in config file.

        We handle the following cases:
            1. Bucket set in the config && bucket is valid.
                - Configure the bucket.
            2. Bucket set in the config && bucket is invalid.
                - Create a default acloud bucket and configure it
            3. Bucket is not set in the config.
                - Create a default acloud bucket and configure it.

        Args:
            gcloud_runner: A GcloudRunner class to run "gsutil" command.
        """
        if (not self.storage_bucket_name
                or not self._BucketIsValid(self.storage_bucket_name, gcloud_runner)):
            self.storage_bucket_name = self._CreateDefaultBucket(gcloud_runner)
        self._ConfigureBucket(gcloud_runner)
        UpdateConfigFile(self.config_path, "storage_bucket_name",
                         self.storage_bucket_name)
        logger.info("Storage bucket name set to [%s]", self.storage_bucket_name)

    def _ConfigureBucket(self, gcloud_runner):
        """Setup write access right for Android Build service account.

        To avoid confuse user, we don't show messages for processing messages.
        e.g. "No changes to gs://acloud-bucket/"

        Args:
            gcloud_runner: A GcloudRunner class to run "gsutil" command.
        """
        gcloud_runner.RunGsutil([
            "acl", "ch", "-u",
            "%s:W" % (_BUILD_SERVICE_ACCOUNT),
            "%s" % (_BUCKET_HEADER + self.storage_bucket_name)
        ], stderr=subprocess.STDOUT)

    def _BucketIsValid(self, bucket_name, gcloud_runner):
        """Check bucket is valid or not.

        If bucket exists and region is in default region,
        then this bucket is valid.

        Args:
            bucket_name: String, name of storage bucket.
            gcloud_runner: A GcloudRunner class to run "gsutil" command.

        Returns:
            Boolean: True if bucket is valid, otherwise False.
        """
        return (self._BucketExists(bucket_name, gcloud_runner) and
                self._BucketInDefaultRegion(bucket_name, gcloud_runner))

    def _CreateDefaultBucket(self, gcloud_runner):
        """Setup bucket to default bucket name.

        Default bucket name is "acloud-{project}".
        If default bucket exist and its region is not "US",
        then default bucket name is changed as "acloud-{project}-us"
        If default bucket didn't exist, tool will create it.

        Args:
            gcloud_runner: A GcloudRunner class to run "gsutil" command.

        Returns:
            String: string of bucket name.
        """
        bucket_name = self._GenerateBucketName(self.project)
        if (self._BucketExists(bucket_name, gcloud_runner) and
                not self._BucketInDefaultRegion(bucket_name, gcloud_runner)):
            bucket_name += ("-" + _DEFAULT_BUCKET_REGION.lower())
        if not self._BucketExists(bucket_name, gcloud_runner):
            self._CreateBucket(bucket_name, gcloud_runner)
        return bucket_name

    @staticmethod
    def _GenerateBucketName(project_name):
        """Generate GCS bucket name that meets the naming guidelines.

        Naming guidelines: https://cloud.google.com/storage/docs/naming
        1. Filter out organization name.
        2. Filter out illegal characters.
        3. Length limit.
        4. Name must end with a number or letter.

        Args:
            project_name: String, name of project.

        Returns:
            String: GCS bucket name compliant with naming guidelines.
        """
        # Sanitize the project name by filtering out the org name (e.g.
        # AOSP:fake_project -> fake_project)
        if _PROJECT_SEPARATOR in project_name:
            _, project_name = project_name.split(_PROJECT_SEPARATOR)

        bucket_name = "%s-%s" % (_DEFAULT_BUCKET_HEADER, project_name)

        # Rule 1: A bucket name can contain lowercase alphanumeric characters,
        # hyphens, and underscores.
        bucket_name = re.sub("[^a-zA-Z_/-]+", "", bucket_name).lower()

        # Rule 2: Bucket names must limit to 63 characters.
        if len(bucket_name) > _BUCKET_LENGTH_LIMIT:
            bucket_name = bucket_name[:_BUCKET_LENGTH_LIMIT]

        # Rule 3: Bucket names must end with a letter, strip out any ending
        # "-" or "_" at the end of the name.
        bucket_name = bucket_name.rstrip(_INVALID_BUCKET_NAME_END_CHARS)

        return bucket_name

    @staticmethod
    def _BucketExists(bucket_name, gcloud_runner):
        """Confirm bucket exist in project or not.

        Args:
            bucket_name: String, name of storage bucket.
            gcloud_runner: A GcloudRunner class to run "gsutil" command.

        Returns:
            Boolean: True for bucket exist in project.
        """
        output = gcloud_runner.RunGsutil(["list"])
        for output_line in output.splitlines():
            match = _BUCKET_RE.match(output_line)
            if match.group("bucket") == bucket_name:
                return True
        return False

    @staticmethod
    def _BucketInDefaultRegion(bucket_name, gcloud_runner):
        """Confirm bucket region settings is "US" or not.

        Args:
            bucket_name: String, name of storage bucket.
            gcloud_runner: A GcloudRunner class to run "gsutil" command.

        Returns:
            Boolean: True for bucket region is in default region.

        Raises:
            errors.SetupError: For parsing bucket region information error.
        """
        output = gcloud_runner.RunGsutil(
            ["ls", "-L", "-b", "%s" % (_BUCKET_HEADER + bucket_name)])
        for region_line in output.splitlines():
            region_match = _BUCKET_REGION_RE.match(region_line.strip())
            if region_match:
                region = region_match.group("region").strip()
                logger.info("Bucket[%s] is in %s (checking for %s)", bucket_name,
                            region, _DEFAULT_BUCKET_REGION)
                if region == _DEFAULT_BUCKET_REGION:
                    return True
                return False
        raise errors.ParseBucketRegionError("Could not determine bucket region.")

    @staticmethod
    def _CreateBucket(bucket_name, gcloud_runner):
        """Create new storage bucket in project.

        Args:
            bucket_name: String, name of storage bucket.
            gcloud_runner: A GcloudRunner class to run "gsutil" command.
        """
        gcloud_runner.RunGsutil(["mb", "%s" % (_BUCKET_HEADER + bucket_name)])
        logger.info("Create bucket [%s].", bucket_name)

    @staticmethod
    def _EnableGcloudServices(gcloud_runner):
        """Enable 3 Gcloud API services.

        1. Android build service
        2. Compute engine service
        3. Google cloud storage service
        To avoid confuse user, we don't show messages for services processing
        messages. e.g. "Waiting for async operation operations ...."

        Args:
            gcloud_runner: A GcloudRunner class to run "gcloud" command.
        """
        for service in _GOOGLE_APIS:
            gcloud_runner.RunGcloud(["services", "enable", service],
                                    stderr=subprocess.STDOUT)
