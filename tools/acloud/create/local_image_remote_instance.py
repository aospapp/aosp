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

from distutils.spawn import find_executable
import getpass
import logging
import os
import subprocess

from acloud import errors
from acloud.create import base_avd_create
from acloud.create import create_common
from acloud.internal import constants
from acloud.internal.lib import auth
from acloud.internal.lib import cvd_compute_client
from acloud.internal.lib import utils
from acloud.public.actions import base_device_factory
from acloud.public.actions import common_operations

logger = logging.getLogger(__name__)

_CVD_HOST_PACKAGE = "cvd-host_package.tar.gz"
_CVD_USER = getpass.getuser()
_CMD_LAUNCH_CVD_ARGS = (" -cpus %s -x_res %s -y_res %s -dpi %s "
                        "-memory_mb %s -blank_data_image_mb %s "
                        "-data_policy always_create ")
#Output to Serial port 1 (console) group in the instance
_OUTPUT_CONSOLE_GROUPS = "tty"
SSH_BIN = "ssh"
_SSH_CMD = (" -i %(rsa_key_file)s "
            "-q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no "
            "-l %(login_user)s %(ip_addr)s ")
_SSH_CMD_MAX_RETRY = 2
_SSH_CMD_RETRY_SLEEP = 3
_USER_BUILD = "userbuild"

class RemoteInstanceDeviceFactory(base_device_factory.BaseDeviceFactory):
    """A class that can produce a cuttlefish device.

    Attributes:
        avd_spec: AVDSpec object that tells us what we're going to create.
        cfg: An AcloudConfig instance.
        image_path: A string, upload image artifact to instance.
        cvd_host_package: A string, upload host package artifact to instance.
        credentials: An oauth2client.OAuth2Credentials instance.
        compute_client: An object of cvd_compute_client.CvdComputeClient.
    """
    def __init__(self, avd_spec, local_image_artifact, cvd_host_package_artifact):
        """Constructs a new remote instance device factory."""
        self._avd_spec = avd_spec
        self._cfg = avd_spec.cfg
        self._local_image_artifact = local_image_artifact
        self._cvd_host_package_artifact = cvd_host_package_artifact
        self._report_internal_ip = avd_spec.report_internal_ip
        self.credentials = auth.CreateCredentials(avd_spec.cfg)
        compute_client = cvd_compute_client.CvdComputeClient(
            avd_spec.cfg, self.credentials)
        super(RemoteInstanceDeviceFactory, self).__init__(compute_client)
        # Private creation parameters
        self._ssh_cmd = None

    def CreateInstance(self):
        """Create a single configured cuttlefish device.

        1. Create gcp instance.
        2. setup the AVD env in the instance.
        3. upload the artifacts to instance.
        4. Launch CVD.

        Returns:
            A string, representing instance name.
        """
        instance = self._CreateGceInstance()
        self._SetAVDenv(_CVD_USER)
        self._UploadArtifacts(_CVD_USER,
                              self._local_image_artifact,
                              self._cvd_host_package_artifact)
        self._LaunchCvd(_CVD_USER, self._avd_spec.hw_property)
        return instance

    @staticmethod
    def _ShellCmdWithRetry(remote_cmd):
        """Runs a shell command on remote device.

        If the network is unstable and causes SSH connect fail, it will retry.
        When it retry in a short time, you may encounter unstable network. We
        will use the mechanism of RETRY_BACKOFF_FACTOR. The retry time for each
        failure is times * retries.

        Args:
            remote_cmd: A string, shell command to be run on remote.

        Raises:
            subprocess.CalledProcessError: For any non-zero return code of
                                           remote_cmd.

        Returns:
            Boolean, True if the command was successfully executed. False otherwise.
        """
        return utils.RetryExceptionType(
            exception_types=subprocess.CalledProcessError,
            max_retries=_SSH_CMD_MAX_RETRY,
            functor=lambda cmd: subprocess.check_call(cmd, shell=True),
            sleep_multiplier=_SSH_CMD_RETRY_SLEEP,
            retry_backoff_factor=utils.DEFAULT_RETRY_BACKOFF_FACTOR,
            cmd=remote_cmd)

    def _CreateGceInstance(self):
        """Create a single configured cuttlefish device.

        Override method from parent class.
        build_target: The format is like "aosp_cf_x86_phone". We only get info
                      from the user build image file name. If the file name is
                      not custom format (no "-"), We will use the original
                      flavor as our build_target.

        Returns:
            A string, representing instance name.
        """
        image_name = os.path.basename(self._local_image_artifact)
        build_target = self._avd_spec.flavor if "-" not in image_name else image_name.split(
            "-")[0]
        instance = self._compute_client.GenerateInstanceName(
            build_target=build_target, build_id=_USER_BUILD)
        # Create an instance from Stable Host Image
        self._compute_client.CreateInstance(
            instance=instance,
            image_name=self._cfg.stable_host_image_name,
            image_project=self._cfg.stable_host_image_project,
            blank_data_disk_size_gb=self._cfg.extra_data_disk_size_gb,
            avd_spec=self._avd_spec)
        ip = self._compute_client.GetInstanceIP(instance)
        self._ssh_cmd = find_executable(SSH_BIN) + _SSH_CMD % {
            "login_user": getpass.getuser(),
            "rsa_key_file": self._cfg.ssh_private_key_path,
            "ip_addr": (ip.internal if self._report_internal_ip
                        else ip.external)}
        return instance

    @utils.TimeExecute(function_description="Setting up GCE environment")
    def _SetAVDenv(self, cvd_user):
        """set the user to run AVD in the instance.

        Args:
            cvd_user: A string, user run the cvd in the instance.
        """
        avd_list_of_groups = []
        avd_list_of_groups.extend(constants.LIST_CF_USER_GROUPS)
        avd_list_of_groups.append(_OUTPUT_CONSOLE_GROUPS)
        remote_cmd = ""
        for group in avd_list_of_groups:
            remote_cmd += "\"sudo usermod -aG %s %s;\"" %(group, cvd_user)
        logger.debug("remote_cmd:\n %s", remote_cmd)
        self._ShellCmdWithRetry(self._ssh_cmd + remote_cmd)

    @utils.TimeExecute(function_description="Uploading local image")
    def _UploadArtifacts(self,
                         cvd_user,
                         local_image_artifact,
                         cvd_host_package_artifact):
        """Upload local image and avd local host package to instance.

        Args:
            cvd_user: A string, user upload the artifacts to instance.
            local_image_artifact: A string, path to local image.
            cvd_host_package_artifact: A string, path to cvd host package.
        """
        # TODO(b/129376163) Use lzop for fast sparse image upload
        remote_cmd = ("\"sudo su -c '/usr/bin/install_zip.sh .' - '%s'\" < %s" %
                      (cvd_user, local_image_artifact))
        logger.debug("remote_cmd:\n %s", remote_cmd)
        self._ShellCmdWithRetry(self._ssh_cmd + remote_cmd)

        # host_package
        remote_cmd = ("\"sudo su -c 'tar -x -z -f -' - '%s'\" < %s" %
                      (cvd_user, cvd_host_package_artifact))
        logger.debug("remote_cmd:\n %s", remote_cmd)
        self._ShellCmdWithRetry(self._ssh_cmd + remote_cmd)

    def _LaunchCvd(self, cvd_user, hw_property):
        """Launch CVD."""
        lunch_cvd_args = _CMD_LAUNCH_CVD_ARGS % (
            hw_property["cpu"],
            hw_property["x_res"],
            hw_property["y_res"],
            hw_property["dpi"],
            hw_property["memory"],
            hw_property["disk"])
        remote_cmd = ("\"sudo su -c 'bin/launch_cvd %s>&/dev/ttyS0&' - '%s'\"" %
                      (lunch_cvd_args, cvd_user))
        logger.debug("remote_cmd:\n %s", remote_cmd)
        subprocess.Popen(self._ssh_cmd + remote_cmd, shell=True)


class LocalImageRemoteInstance(base_avd_create.BaseAVDCreate):
    """Create class for a local image remote instance AVD.

    Attributes:
        local_image_artifact: A string, path to local image.
        cvd_host_package_artifact: A string, path to cvd host package.
    """

    def __init__(self):
        """LocalImageRemoteInstance initialize."""
        self.cvd_host_package_artifact = None

    def VerifyHostPackageArtifactsExist(self):
        """Verify the host package exists and return its path.

        Look for the host package in $ANDROID_HOST_OUT and dist dir.

        Return:
            A string, the path to the host package.
        """
        dirs_to_check = filter(None,
                               [os.environ.get(constants.ENV_ANDROID_HOST_OUT)])
        dist_dir = utils.GetDistDir()
        if dist_dir:
            dirs_to_check.append(dist_dir)

        cvd_host_package_artifact = self.GetCvdHostPackage(dirs_to_check)
        logger.debug("cvd host package: %s", cvd_host_package_artifact)
        return cvd_host_package_artifact

    @staticmethod
    def GetCvdHostPackage(paths):
        """Get cvd host package path.

        Args:
            paths: A list, holds the paths to check for the host package.

        Returns:
            String, full path of cvd host package.

        Raises:
            errors.GetCvdLocalHostPackageError: Can't find cvd host package.
        """
        for path in paths:
            cvd_host_package = os.path.join(path, _CVD_HOST_PACKAGE)
            if os.path.exists(cvd_host_package):
                return cvd_host_package
        raise errors.GetCvdLocalHostPackageError, (
            "Can't find the cvd host package (Try lunching a cuttlefish target"
            " like aosp_cf_x86_phone-userdebug and running 'm'): \n%s" %
            '\n'.join(paths))

    @utils.TimeExecute(function_description="Total time: ",
                       print_before_call=False, print_status=False)
    def _CreateAVD(self, avd_spec, no_prompts):
        """Create the AVD.

        Args:
            avd_spec: AVDSpec object that tells us what we're going to create.
            no_prompts: Boolean, True to skip all prompts.
        """
        self.cvd_host_package_artifact = self.VerifyHostPackageArtifactsExist()

        if avd_spec.local_image_artifact:
            local_image_artifact = avd_spec.local_image_artifact
        else:
            local_image_artifact = create_common.ZipCFImageFiles(
                avd_spec.local_image_dir)

        device_factory = RemoteInstanceDeviceFactory(
            avd_spec,
            local_image_artifact,
            self.cvd_host_package_artifact)
        report = common_operations.CreateDevices(
            "create_cf", avd_spec.cfg, device_factory, avd_spec.num,
            report_internal_ip=avd_spec.report_internal_ip,
            autoconnect=avd_spec.autoconnect,
            avd_type=constants.TYPE_CF)
        # Launch vnc client if we're auto-connecting.
        if avd_spec.autoconnect:
            utils.LaunchVNCFromReport(report, avd_spec, no_prompts)
        return report
