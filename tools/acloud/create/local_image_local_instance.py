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
r"""LocalImageLocalInstance class.

Create class that is responsible for creating a local instance AVD with a
local image.
"""

from __future__ import print_function
import logging
import os
import subprocess
import sys

from acloud import errors
from acloud.create import base_avd_create
from acloud.delete import delete
from acloud.internal import constants
from acloud.internal.lib import utils
from acloud.public import report

logger = logging.getLogger(__name__)

_CMD_LAUNCH_CVD_ARGS = (" --daemon --cpus %s --x_res %s --y_res %s --dpi %s "
                        "--memory_mb %s --blank_data_image_mb %s "
                        "--data_policy always_create "
                        "--system_image_dir %s "
                        "--vnc_server_port %s")
_CONFIRM_RELAUNCH = ("\nCuttlefish AVD is already running. \n"
                     "Enter 'y' to terminate current instance and launch a new "
                     "instance, enter anything else to exit out[y/N]: ")
_ENV_ANDROID_HOST_OUT = "ANDROID_HOST_OUT"
_LAUNCH_CVD_TIMEOUT_SECS = 60  # setup timeout as 60 seconds
_LAUNCH_CVD_TIMEOUT_ERROR = ("Cuttlefish AVD launch timeout, did not complete "
                             "within %d secs." % _LAUNCH_CVD_TIMEOUT_SECS)

class LocalImageLocalInstance(base_avd_create.BaseAVDCreate):
    """Create class for a local image local instance AVD."""

    @utils.TimeExecute(function_description="Total time: ",
                       print_before_call=False, print_status=False)
    def _CreateAVD(self, avd_spec, no_prompts):
        """Create the AVD.

        Args:
            avd_spec: AVDSpec object that tells us what we're going to create.
            no_prompts: Boolean, True to skip all prompts.
        """
        # Running instances on local is not supported on all OS.
        if not utils.IsSupportedPlatform(print_warning=True):
            result_report = report.Report(constants.LOCAL_INS_NAME)
            result_report.SetStatus(report.Status.FAIL)
            return result_report

        self.PrintDisclaimer()
        local_image_path, host_bins_path = self.GetImageArtifactsPath(avd_spec)

        launch_cvd_path = os.path.join(host_bins_path, "bin",
                                       constants.CMD_LAUNCH_CVD)
        cmd = self.PrepareLaunchCVDCmd(launch_cvd_path,
                                       avd_spec.hw_property,
                                       local_image_path)
        try:
            self.CheckLaunchCVD(cmd, host_bins_path, no_prompts)
        except errors.LaunchCVDFail as launch_error:
            raise launch_error

        result_report = report.Report(constants.LOCAL_INS_NAME)
        result_report.SetStatus(report.Status.SUCCESS)
        result_report.AddData(
            key="devices",
            value={constants.ADB_PORT: constants.CF_ADB_PORT,
                   constants.VNC_PORT: constants.CF_VNC_PORT})
        # Launch vnc client if we're auto-connecting.
        if avd_spec.autoconnect:
            utils.LaunchVNCFromReport(result_report, avd_spec, no_prompts)
        return result_report

    @staticmethod
    def GetImageArtifactsPath(avd_spec):
        """Get image artifacts path.

        This method will check if launch_cvd is exist and return the tuple path
        (image path and host bins path) where they are located respectively.
        For remote image, RemoteImageLocalInstance will override this method and
        return the artifacts path which is extracted and downloaded from remote.

        Args:
            avd_spec: AVDSpec object that tells us what we're going to create.

        Returns:
            Tuple of (local image file, host bins package) paths.
        """
        # Check if launch_cvd is exist.
        host_bins_path = os.environ.get(_ENV_ANDROID_HOST_OUT)
        launch_cvd_path = os.path.join(host_bins_path, "bin",
                                       constants.CMD_LAUNCH_CVD)
        if not os.path.exists(launch_cvd_path):
            raise errors.GetCvdLocalHostPackageError(
                "No launch_cvd found. Please run \"m launch_cvd\" first")

        return avd_spec.local_image_dir, host_bins_path

    @staticmethod
    def PrepareLaunchCVDCmd(launch_cvd_path, hw_property, system_image_dir):
        """Prepare launch_cvd command.

        Create the launch_cvd commands with all the required args and add
        in the user groups to it if necessary.

        Args:
            launch_cvd_path: String of launch_cvd path.
            hw_property: dict object of hw property.
            system_image_dir: String of local images path.

        Returns:
            String, launch_cvd cmd.
        """
        launch_cvd_w_args = launch_cvd_path + _CMD_LAUNCH_CVD_ARGS % (
            hw_property["cpu"], hw_property["x_res"], hw_property["y_res"],
            hw_property["dpi"], hw_property["memory"], hw_property["disk"],
            system_image_dir, constants.CF_VNC_PORT)

        launch_cmd = utils.AddUserGroupsToCmd(launch_cvd_w_args,
                                              constants.LIST_CF_USER_GROUPS)
        logger.debug("launch_cvd cmd:\n %s", launch_cmd)
        return launch_cmd

    def CheckLaunchCVD(self, cmd, host_bins_path, no_prompts=False):
        """Execute launch_cvd command and wait for boot up completed.

        Args:
            cmd: String, launch_cvd command.
            host_bins_path: String of host package directory.
            no_prompts: Boolean, True to skip all prompts.
        """
        # launch_cvd assumes host bins are in $ANDROID_HOST_OUT, let's overwrite
        # it to wherever we're running launch_cvd since they could be in a
        # different dir (e.g. downloaded image).
        os.environ[_ENV_ANDROID_HOST_OUT] = host_bins_path
        # Cuttlefish support launch single AVD at one time currently.
        if utils.IsCommandRunning(constants.CMD_LAUNCH_CVD):
            logger.info("Cuttlefish AVD is already running.")
            if no_prompts or utils.GetUserAnswerYes(_CONFIRM_RELAUNCH):
                stop_cvd_cmd = os.path.join(host_bins_path,
                                            "bin",
                                            constants.CMD_STOP_CVD)
                with open(os.devnull, "w") as dev_null:
                    subprocess.check_call(
                        utils.AddUserGroupsToCmd(
                            stop_cvd_cmd, constants.LIST_CF_USER_GROUPS),
                        stderr=dev_null, stdout=dev_null, shell=True)

                # Delete ssvnc viewer
                delete.CleanupSSVncviewer(constants.CF_VNC_PORT)

            else:
                sys.exit(constants.EXIT_BY_USER)
        self._LaunchCvd(cmd)

    @staticmethod
    @utils.TimeExecute(function_description="Waiting for AVD(s) to boot up")
    @utils.TimeoutException(_LAUNCH_CVD_TIMEOUT_SECS, _LAUNCH_CVD_TIMEOUT_ERROR)
    def _LaunchCvd(cmd):
        """Execute Launch CVD.

        Kick off the launch_cvd command and log the output.

        Args:
            cmd: String, launch_cvd command.

        Raises:
            errors.LaunchCVDFail when any CalledProcessError.
        """
        try:
            # Check the result of launch_cvd command.
            # An exit code of 0 is equivalent to VIRTUAL_DEVICE_BOOT_COMPLETED
            logger.debug(subprocess.check_output(cmd, shell=True,
                                                 stderr=subprocess.STDOUT))
        except subprocess.CalledProcessError as error:
            raise errors.LaunchCVDFail(
                "Can't launch cuttlefish AVD.%s. \nFor more detail: "
                "~/cuttlefish_runtime/launcher.log" % error.message)

    @staticmethod
    def PrintDisclaimer():
        """Print Disclaimer."""
        utils.PrintColorString(
            "(Disclaimer: Local cuttlefish instance is not a fully supported\n"
            "runtime configuration, fixing breakages is on a best effort SLO.)\n",
            utils.TextColors.WARNING)
