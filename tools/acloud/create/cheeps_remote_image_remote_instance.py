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
r"""CheepsRemoteImageRemoteInstance class.

Create class that is responsible for creating a cheeps remote instance AVD with
a remote image.
"""
import logging

from acloud.create import base_avd_create
from acloud.internal import constants
from acloud.internal.lib import auth
from acloud.internal.lib import cheeps_compute_client
from acloud.internal.lib import utils
from acloud.public.actions import base_device_factory
from acloud.public.actions import common_operations

logger = logging.getLogger(__name__)

class CheepsRemoteImageRemoteInstance(base_avd_create.BaseAVDCreate):
    """Create class for a Cheeps remote image remote instance AVD."""

    @utils.TimeExecute(function_description="Total time: ",
                       print_before_call=False, print_status=False)
    def _CreateAVD(self, avd_spec, no_prompts):
        """Create the AVD.

        Args:
            avd_spec: AVDSpec object that tells us what we're going to create.
            no_prompts: Boolean, True to skip all prompts.

        Returns:
            A Report instance.
        """
        build_id = avd_spec.remote_image[constants.BUILD_ID]
        logger.info(
            "Creating a cheeps device in project %s, build_id: %s",
            avd_spec.cfg.project, build_id)

        device_factory = CheepsDeviceFactory(avd_spec.cfg, build_id, avd_spec)

        report = common_operations.CreateDevices(
            command="create_cheeps",
            cfg=avd_spec.cfg,
            device_factory=device_factory,
            num=avd_spec.num,
            report_internal_ip=avd_spec.report_internal_ip,
            autoconnect=avd_spec.autoconnect,
            avd_type=constants.TYPE_CHEEPS)

        # Launch vnc client if we're auto-connecting.
        if avd_spec.autoconnect:
            utils.LaunchVNCFromReport(report, avd_spec, no_prompts)

        return report


class CheepsDeviceFactory(base_device_factory.BaseDeviceFactory):
    """A class that can produce a cheeps device.

    Attributes:
        _cfg: An AcloudConfig instance.
        _build_id: String, Build id, e.g. "2263051", "P2804227"

    """
    LOG_FILES = []

    def __init__(self, cfg, build_id, avd_spec=None):
        """Initialize.

        Args:
            cfg: An AcloudConfig instance.
            build_id: String, Build id, e.g. "2263051", "P2804227"
            avd_spec: An AVDSpec instance.
        """
        self.credentials = auth.CreateCredentials(cfg)

        compute_client = cheeps_compute_client.CheepsComputeClient(
            cfg, self.credentials)
        super(CheepsDeviceFactory, self).__init__(compute_client)

        self._cfg = cfg
        self._build_id = build_id
        self._avd_spec = avd_spec

    def CreateInstance(self):
        """Creates single configured cheeps device.

        Returns:
            String, the name of created instance.
        """
        instance = self._compute_client.GenerateInstanceName(self._build_id)
        self._compute_client.CreateInstance(
            instance=instance,
            image_name=self._cfg.stable_cheeps_host_image_name,
            image_project=self._cfg.stable_cheeps_host_image_project,
            build_id=self._build_id,
            avd_spec=self._avd_spec)
        return instance
