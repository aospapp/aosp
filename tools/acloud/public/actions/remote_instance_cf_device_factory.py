# Copyright 2019 - The Android Open Source Project
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

"""RemoteInstanceDeviceFactory provides basic interface to create a cuttlefish
device factory."""

import logging
import os
import tempfile

from acloud.create import create_common
from acloud.internal import constants
from acloud.internal.lib import cvd_utils
from acloud.internal.lib import ota_tools
from acloud.internal.lib import utils
from acloud.public.actions import gce_device_factory
from acloud.pull import pull


logger = logging.getLogger(__name__)
_SCREEN_CONSOLE_COMMAND = "screen ~/cuttlefish_runtime/console"
_MIXED_SUPER_IMAGE_NAME = "mixed_super.img"


class RemoteInstanceDeviceFactory(gce_device_factory.GCEDeviceFactory):
    """A class that can produce a cuttlefish device.

    Attributes:
        avd_spec: AVDSpec object that tells us what we're going to create.
        cfg: An AcloudConfig instance.
        local_image_artifact: A string, path to local image.
        cvd_host_package_artifact: A string, path to cvd host package.
        report_internal_ip: Boolean, True for the internal ip is used when
                            connecting from another GCE instance.
        credentials: An oauth2client.OAuth2Credentials instance.
        compute_client: An object of cvd_compute_client.CvdComputeClient.
        ssh: An Ssh object.
    """
    def __init__(self, avd_spec, local_image_artifact=None,
                 cvd_host_package_artifact=None):
        super().__init__(avd_spec, local_image_artifact)
        self._all_logs = {}
        self._cvd_host_package_artifact = cvd_host_package_artifact

    # pylint: disable=broad-except
    def CreateInstance(self):
        """Create a single configured cuttlefish device.

        Returns:
            A string, representing instance name.
        """
        instance = self.CreateGceInstance()
        # If instance is failed, no need to go next step.
        if instance in self.GetFailures():
            return instance
        try:
            image_args = self._ProcessArtifacts()
            failures = self._compute_client.LaunchCvd(
                instance, self._avd_spec, cvd_utils.GCE_BASE_DIR, image_args)
            for failing_instance, error_msg in failures.items():
                self._SetFailures(failing_instance, error_msg)
        except Exception as e:
            self._SetFailures(instance, e)

        self._FindLogFiles(
            instance,
            instance in self.GetFailures() and not self._avd_spec.no_pull_log)
        return instance

    def _ProcessArtifacts(self):
        """Process artifacts.

        - If images source is local, tool will upload images from local site to
          remote instance.
        - If images source is remote, tool will download images from android
          build to remote instance. Before download images, we have to update
          fetch_cvd to remote instance.

        Returns:
            A list of strings, the launch_cvd arguments.
        """
        avd_spec = self._avd_spec
        if avd_spec.image_source == constants.IMAGE_SRC_LOCAL:
            cvd_utils.UploadArtifacts(
                self._ssh,
                cvd_utils.GCE_BASE_DIR,
                self._local_image_artifact or avd_spec.local_image_dir,
                self._cvd_host_package_artifact)
        elif avd_spec.image_source == constants.IMAGE_SRC_REMOTE:
            self._compute_client.UpdateFetchCvd(avd_spec.fetch_cvd_version)
            self._compute_client.FetchBuild(
                avd_spec.remote_image,
                avd_spec.system_build_info,
                avd_spec.kernel_build_info,
                avd_spec.boot_build_info,
                avd_spec.bootloader_build_info,
                avd_spec.ota_build_info)

        launch_cvd_args = []
        if avd_spec.local_system_image or avd_spec.local_vendor_image:
            with tempfile.TemporaryDirectory() as temp_dir:
                super_image_path = os.path.join(temp_dir,
                                                _MIXED_SUPER_IMAGE_NAME)
                self._CreateMixedSuperImage(
                    super_image_path, self._GetLocalTargetFilesDir(temp_dir))
                launch_cvd_args += cvd_utils.UploadSuperImage(
                    self._ssh, cvd_utils.GCE_BASE_DIR, super_image_path)

        if avd_spec.mkcert and avd_spec.connect_webrtc:
            self._compute_client.UpdateCertificate()

        if avd_spec.extra_files:
            self._compute_client.UploadExtraFiles(avd_spec.extra_files)

        launch_cvd_args += cvd_utils.UploadExtraImages(
            self._ssh, cvd_utils.GCE_BASE_DIR, avd_spec)
        return launch_cvd_args

    @utils.TimeExecute(function_description="Downloading target_files archive")
    def _DownloadTargetFiles(self, download_dir):
        avd_spec = self._avd_spec
        build_id = avd_spec.remote_image[constants.BUILD_ID]
        build_target = avd_spec.remote_image[constants.BUILD_TARGET]
        create_common.DownloadRemoteArtifact(
            avd_spec.cfg, build_target, build_id,
            cvd_utils.GetMixBuildTargetFilename(build_target, build_id),
            download_dir, decompress=True)

    def _GetLocalTargetFilesDir(self, temp_dir):
        """Return a directory of extracted target_files or local images.

        Args:
            temp_dir: Temporary directory to store downloaded build artifacts
                      and extracted target_files archive.
        """
        avd_spec = self._avd_spec
        if avd_spec.image_source == constants.IMAGE_SRC_LOCAL:
            if self._local_image_artifact:
                target_files_dir = os.path.join(temp_dir, "local_images")
                os.makedirs(target_files_dir, exist_ok=True)
                utils.Decompress(self._local_image_artifact, target_files_dir)
            else:
                target_files_dir = os.path.abspath(avd_spec.local_image_dir)
        else:  # must be IMAGE_SRC_REMOTE
            target_files_dir = os.path.join(temp_dir, "remote_images")
            os.makedirs(target_files_dir, exist_ok=True)
            self._DownloadTargetFiles(target_files_dir)
        return target_files_dir

    def _CreateMixedSuperImage(self, super_image_path, target_files_dir):
        """Create a mixed super image from device images and local system image.

        Args:
            super_image_path: Path to the output mixed super image.
            target_files_dir: Path to extracted target_files directory
                              containing device images and misc_info.txt.
        """
        avd_spec = self._avd_spec
        misc_info_path = cvd_utils.FindMiscInfo(target_files_dir)
        image_dir = cvd_utils.FindImageDir(target_files_dir)
        ota = ota_tools.FindOtaTools(
            avd_spec.local_tool_dirs +
                create_common.GetNonEmptyEnvVars(
                    constants.ENV_ANDROID_SOONG_HOST_OUT,
                    constants.ENV_ANDROID_HOST_OUT))

        system_image_path=None
        vendor_image_path=None
        vendor_dlkm_image_path=None
        odm_image_path=None
        odm_dlkm_image_path=None

        if avd_spec.local_system_image:
            system_image_path = create_common.FindSystemImage(
                avd_spec.local_system_image)

        if avd_spec.local_vendor_image:
            vendor_image_paths = cvd_utils.FindVendorImages(
                avd_spec.local_vendor_image)
            vendor_image_path = vendor_image_paths.vendor
            vendor_dlkm_image_path = vendor_image_paths.vendor_dlkm
            odm_image_path = vendor_image_paths.odm
            odm_dlkm_image_path = vendor_image_paths.odm_dlkm

        ota.MixSuperImage(super_image_path, misc_info_path, image_dir,
                          system_image=system_image_path,
                          vendor_image=vendor_image_path,
                          vendor_dlkm_image=vendor_dlkm_image_path,
                          odm_image=odm_image_path,
                          odm_dlkm_image=odm_dlkm_image_path)

    def _FindLogFiles(self, instance, download):
        """Find and pull all log files from instance.

        Args:
            instance: String, instance name.
            download: Whether to download the files to a temporary directory
                      and show messages to the user.
        """
        logs = [cvd_utils.HOST_KERNEL_LOG]
        if self._avd_spec.image_source == constants.IMAGE_SRC_REMOTE:
            logs.append(
                cvd_utils.GetRemoteFetcherConfigJson(cvd_utils.GCE_BASE_DIR))
        logs.extend(cvd_utils.FindRemoteLogs(
            self._ssh,
            cvd_utils.GCE_BASE_DIR,
            self._avd_spec.base_instance_num,
            self._avd_spec.num_avds_per_instance))
        self._all_logs[instance] = logs

        if download:
            # To avoid long download time, fetch from the first device only.
            log_files = pull.GetAllLogFilePaths(self._ssh,
                                                constants.REMOTE_LOG_FOLDER)
            error_log_folder = pull.PullLogs(self._ssh, log_files, instance)
            self._compute_client.ExtendReportData(constants.ERROR_LOG_FOLDER,
                                                  error_log_folder)

    def GetOpenWrtInfoDict(self):
        """Get openwrt info dictionary.

        Returns:
            A openwrt info dictionary. None for the case is not openwrt device.
        """
        if not self._avd_spec.openwrt:
            return None
        return {"ssh_command": self._compute_client.GetSshConnectCmd(),
                "screen_command": _SCREEN_CONSOLE_COMMAND}

    def GetAdbPorts(self):
        """Get ADB ports of the created devices.

        Returns:
            The port numbers as a list of integers.
        """
        return cvd_utils.GetAdbPorts(self._avd_spec.base_instance_num,
                                     self._avd_spec.num_avds_per_instance)

    def GetFastbootPorts(self):
        """Get Fastboot ports of the created devices.

        Returns:
            The port numbers as a list of integers.
        """
        return cvd_utils.GetFastbootPorts(self._avd_spec.base_instance_num,
                                          self._avd_spec.num_avds_per_instance)

    def GetVncPorts(self):
        """Get VNC ports of the created devices.

        Returns:
            The port numbers as a list of integers.
        """
        return cvd_utils.GetVncPorts(self._avd_spec.base_instance_num,
                                     self._avd_spec.num_avds_per_instance)

    def GetBuildInfoDict(self):
        """Get build info dictionary.

        Returns:
            A build info dictionary. None for local image case.
        """
        if self._avd_spec.image_source == constants.IMAGE_SRC_LOCAL:
            return None
        return cvd_utils.GetRemoteBuildInfoDict(self._avd_spec)

    def GetLogs(self):
        """Get all device logs.

        Returns:
            A dictionary that maps instance names to lists of report.LogFile.
        """
        return self._all_logs
