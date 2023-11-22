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

"""Create cuttlefish instances.

TODO: This module now just contains the skeleton but not the actual logic.
      Need to fill in the actuall logic.
"""

import logging

from acloud.public.actions import common_operations
from acloud.public.actions import base_device_factory
from acloud.internal import constants
from acloud.internal.lib import android_build_client
from acloud.internal.lib import auth
from acloud.internal.lib import cvd_compute_client


logger = logging.getLogger(__name__)


class CuttlefishDeviceFactory(base_device_factory.BaseDeviceFactory):
    """A class that can produce a cuttlefish device.

    Attributes:
        cfg: An AcloudConfig instance.
        build_target: String,Target name.
        build_id: String, Build id, e.g. "2263051", "P2804227"
        kernel_build_id: String, Kernel build id.
        avd_spec: An AVDSpec instance.

    """

    RELEASE_BRANCH_SUFFIX = "-release"
    RELEASE_BRANCH_PATH_GLOB_PATTERN = "*-%s"
    LOG_FILES = ["/home/vsoc-01/cuttlefish_runtime/kernel.log",
                 "/home/vsoc-01/cuttlefish_runtime/logcat",
                 "/home/vsoc-01/cuttlefish_runtime/cuttlefish_config.json"]

    def __init__(self, cfg, build_target, build_id, kernel_build_id=None,
                 avd_spec=None, kernel_branch=None):

        self.credentials = auth.CreateCredentials(cfg)

        compute_client = cvd_compute_client.CvdComputeClient(
            cfg, self.credentials)
        super(CuttlefishDeviceFactory, self).__init__(compute_client)

        # Private creation parameters
        self._cfg = cfg
        self._build_target = build_target
        self._build_id = build_id
        self._kernel_build_id = kernel_build_id
        self._blank_data_disk_size_gb = cfg.extra_data_disk_size_gb
        self._avd_spec = avd_spec
        self._kernel_branch = kernel_branch
        self._kernel_build_target = cfg.kernel_build_target
        self._extra_scopes = cfg.extra_scopes

        # Configure clients for interaction with GCE/Build servers
        self._build_client = android_build_client.AndroidBuildClient(
            self.credentials)

        # Discover branches
        self._branch = self._build_client.GetBranch(build_target, build_id)
        self._kernel_branch = None
        if kernel_branch:
            # If no kernel_build_id is given or kernel_build_id is latest,
            # use the last green build id in the given kernel_branch
            if not kernel_build_id or kernel_build_id == self.LATEST:
                self._kernel_build_id = (
                    self._build_client.GetLKGB(self._kernel_build_target,
                                               kernel_branch))
        elif kernel_build_id:
            self._kernel_branch = self._build_client.GetBranch(
                self._kernel_build_target, kernel_build_id)
        else:
            self._kernel_build_target = None

    def CreateInstance(self):
        """Creates singe configured cuttlefish device.

        Override method from parent class.

        Returns:
            A string, representing instance name.
        """

        # Create host instances for cuttlefish device. Currently one host instance
        # has one cuttlefish device. In the future, these logics should be modified
        # to support multiple cuttlefish devices per host instance.
        instance = self._compute_client.GenerateInstanceName(
            build_id=self._build_id, build_target=self._build_target)

        # Create an instance from Stable Host Image
        if self.RELEASE_BRANCH_SUFFIX in self._branch:
            # Workaround for release branch builds.
            bid = self.RELEASE_BRANCH_PATH_GLOB_PATTERN % self._build_id
        else:
            bid = self._build_id
        self._compute_client.CreateInstance(
            instance=instance,
            image_name=self._cfg.stable_host_image_name,
            image_project=self._cfg.stable_host_image_project,
            build_target=self._build_target,
            branch=self._branch,
            build_id=bid,
            kernel_branch=self._kernel_branch,
            kernel_build_id=self._kernel_build_id,
            blank_data_disk_size_gb=self._blank_data_disk_size_gb,
            avd_spec=self._avd_spec,
            extra_scopes=self._extra_scopes)

        return instance


def CreateDevices(avd_spec=None,
                  cfg=None,
                  build_target=None,
                  build_id=None,
                  kernel_build_id=None,
                  kernel_branch=None,
                  num=1,
                  serial_log_file=None,
                  logcat_file=None,
                  autoconnect=False,
                  report_internal_ip=False):
    """Create one or multiple Cuttlefish devices.

    Args:
        avd_spec: An AVDSpec instance.
        cfg: An AcloudConfig instance.
        build_target: String, Target name.
        build_id: String, Build id, e.g. "2263051", "P2804227"
        kernel_build_id: String, Kernel build id.
        kernel_branch: String, Kernel branch name.
        num: Integer, Number of devices to create.
        serial_log_file: String, A path to a tar file where serial output should
                         be saved to.
        logcat_file: String, A path to a file where logcat logs should be saved.
        autoconnect: Boolean, Create ssh tunnel(s) and adb connect after device creation.
        report_internal_ip: Boolean to report the internal ip instead of
                            external ip.

    Returns:
        A Report instance.
    """
    if avd_spec:
        cfg = avd_spec.cfg
        build_target = avd_spec.remote_image[constants.BUILD_TARGET]
        build_id = avd_spec.remote_image[constants.BUILD_ID]
        kernel_build_id = avd_spec.kernel_build_id
        num = avd_spec.num
        autoconnect = avd_spec.autoconnect
        report_internal_ip = avd_spec.report_internal_ip
        serial_log_file = avd_spec.serial_log_file
        logcat_file = avd_spec.logcat_file
    logger.info(
        "Creating a cuttlefish device in project %s, build_target: %s, "
        "build_id: %s, num: %s, serial_log_file: %s, logcat_file: %s, "
        "autoconnect: %s, report_internal_ip: %s", cfg.project, build_target,
        build_id, num, serial_log_file, logcat_file, autoconnect,
        report_internal_ip)
    device_factory = CuttlefishDeviceFactory(cfg, build_target, build_id,
                                             avd_spec=avd_spec,
                                             kernel_build_id=kernel_build_id,
                                             kernel_branch=kernel_branch)
    return common_operations.CreateDevices("create_cf", cfg, device_factory,
                                           num, constants.TYPE_CF,
                                           report_internal_ip, autoconnect,
                                           serial_log_file, logcat_file)
