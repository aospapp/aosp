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
"""A client that manages Cuttlefish Virtual Device on compute engine.

** CvdComputeClient **

CvdComputeClient derives from AndroidComputeClient. It manges a google
compute engine project that is setup for running Cuttlefish Virtual Devices.
It knows how to create a host instance from Cuttlefish Stable Host Image, fetch
Android build, and start Android within the host instance.

** Class hierarchy **

  base_cloud_client.BaseCloudApiClient
                ^
                |
       gcompute_client.ComputeClient
                ^
                |
       android_compute_client.AndroidComputeClient
                ^
                |
       cvd_compute_client.CvdComputeClient

"""

import getpass
import logging

from acloud.internal import constants
from acloud.internal.lib import android_compute_client
from acloud.internal.lib import gcompute_client
from acloud.internal.lib import utils

logger = logging.getLogger(__name__)


class CvdComputeClient(android_compute_client.AndroidComputeClient):
    """Client that manages Anadroid Virtual Device."""

    DATA_POLICY_CREATE_IF_MISSING = "create_if_missing"

    # TODO: refactor CreateInstance to take in an object that contains these
    # args, this method differs too and holds way too cf-specific args to put in
    # the parent method.
    # pylint: disable=arguments-differ,too-many-locals
    @utils.TimeExecute(function_description="Creating GCE instance")
    def CreateInstance(self, instance, image_name, image_project,
                       build_target=None, branch=None, build_id=None,
                       kernel_branch=None, kernel_build_id=None,
                       blank_data_disk_size_gb=None, avd_spec=None,
                       extra_scopes=None):
        """Create a cuttlefish instance given stable host image and build id.

        Args:
            instance: instance name.
            image_name: A string, the name of the GCE image.
            image_project: A string, name of the project where the image lives.
                           Assume the default project if None.
            build_target: Target name, e.g. "aosp_cf_x86_phone-userdebug"
            branch: Branch name, e.g. "aosp-master"
            build_id: Build id, a string, e.g. "2263051", "P2804227"
            kernel_branch: Kernel branch name, e.g. "kernel-common-android-4.14"
            kernel_build_id: Kernel build id, a string, e.g. "223051", "P280427"
            blank_data_disk_size_gb: Size of the blank data disk in GB.
            avd_spec: An AVDSpec instance.
            extra_scopes: A list of extra scopes to be passed to the instance.
        """
        self._CheckMachineSize()

        # A blank data disk would be created on the host. Make sure the size of
        # the boot disk is large enough to hold it.
        boot_disk_size_gb = (
            int(self.GetImage(image_name, image_project)["diskSizeGb"]) +
            blank_data_disk_size_gb)
        disk_args = self._GetDiskArgs(
            instance, image_name, image_project, boot_disk_size_gb)

        # Transitional metadata variable as outlined in go/cuttlefish-deployment
        # These metadata tell the host instance to fetch and launch one
        # cuttlefish device (cvd-01). Ideally we should use a separate tool to
        # manage CVD devices on the host instance and not through metadata.
        # TODO(b/77626419): Remove these metadata once the
        # cuttlefish-google.service is turned off on the host instance.
        metadata = self._metadata.copy()
        metadata["cvd_01_fetch_android_build_target"] = build_target
        metadata["cvd_01_fetch_android_bid"] = "{branch}/{build_id}".format(
            branch=branch, build_id=build_id)
        if kernel_branch and kernel_build_id:
            metadata["cvd_01_fetch_kernel_bid"] = "{branch}/{build_id}".format(
                branch=kernel_branch, build_id=kernel_build_id)
        metadata["cvd_01_launch"] = (self._launch_args
                                     if self._launch_args else "1")

        # The cuttlefish-google tools changed the usage of this cvd_01_launch
        # variable. For the local image, we remove the cvd_01_launch from
        # metadata to tell server not to launch cvd  while instance is booted
        # up.
        if avd_spec and avd_spec.image_source == constants.IMAGE_SRC_LOCAL:
            metadata.pop("cvd_01_launch", None)

        if blank_data_disk_size_gb > 0:
            # Policy 'create_if_missing' would create a blank userdata disk if
            # missing. If already exist, reuse the disk.
            metadata["cvd_01_data_policy"] = self.DATA_POLICY_CREATE_IF_MISSING
            metadata["cvd_01_blank_data_disk_size"] = str(
                blank_data_disk_size_gb * 1024)
        metadata["user"] = getpass.getuser()
        # Update metadata by avd_spec
        # for legacy create_cf cmd, we will keep using resolution.
        # And always use avd_spec for acloud create cmd.
        # TODO(b/118406018): deprecate resolution config and use hw_proprty for
        # all create cmds.
        if avd_spec:
            metadata[constants.INS_KEY_AVD_TYPE] = avd_spec.avd_type
            metadata[constants.INS_KEY_AVD_FLAVOR] = avd_spec.flavor
            metadata["cvd_01_x_res"] = avd_spec.hw_property[constants.HW_X_RES]
            metadata["cvd_01_y_res"] = avd_spec.hw_property[constants.HW_Y_RES]
            metadata["cvd_01_dpi"] = avd_spec.hw_property[constants.HW_ALIAS_DPI]
            metadata["cvd_01_blank_data_disk_size"] = avd_spec.hw_property[
                constants.HW_ALIAS_DISK]
            # Use another METADATA_DISPLAY to record resolution which will be
            # retrieved in acloud list cmd. We try not to use cvd_01_x_res
            # since cvd_01_xxx metadata is going to deprecated by cuttlefish.
            metadata[constants.INS_KEY_DISPLAY] = ("%sx%s (%s)" % (
                avd_spec.hw_property[constants.HW_X_RES],
                avd_spec.hw_property[constants.HW_Y_RES],
                avd_spec.hw_property[constants.HW_ALIAS_DPI]))
        else:
            resolution = self._resolution.split("x")
            metadata["cvd_01_dpi"] = resolution[3]
            metadata["cvd_01_x_res"] = resolution[0]
            metadata["cvd_01_y_res"] = resolution[1]

        # Add per-instance ssh key
        if self._ssh_public_key_path:
            rsa = self._LoadSshPublicKey(self._ssh_public_key_path)
            logger.info("ssh_public_key_path is specified in config: %s, "
                        "will add the key to the instance.",
                        self._ssh_public_key_path)
            metadata["sshKeys"] = "%s:%s" % (getpass.getuser(), rsa)
        else:
            logger.warning(
                "ssh_public_key_path is not specified in config, "
                "only project-wide key will be effective.")

        # Add labels for giving the instances ability to be filter for
        # acloud list/delete cmds.
        labels = {constants.LABEL_CREATE_BY: getpass.getuser()}

        gcompute_client.ComputeClient.CreateInstance(
            self,
            instance=instance,
            image_name=image_name,
            image_project=image_project,
            disk_args=disk_args,
            metadata=metadata,
            machine_type=self._machine_type,
            network=self._network,
            zone=self._zone,
            labels=labels,
            extra_scopes=extra_scopes)
