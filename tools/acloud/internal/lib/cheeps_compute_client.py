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
"""A client that manages Cheeps Virtual Device on compute engine.

** CheepsComputeClient **

CheepsComputeClient derives from AndroidComputeClient. It manges a google
compute engine project that is setup for running Cheeps Virtual Devices.
It knows how to create a host instance from a Cheeps Stable Host Image, fetch
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
       cheeps_compute_client.CheepsComputeClient
"""

import getpass
import logging

from acloud import errors
from acloud.internal import constants
from acloud.internal.lib import android_compute_client
from acloud.internal.lib import gcompute_client

logger = logging.getLogger(__name__)

class CheepsComputeClient(android_compute_client.AndroidComputeClient):
    """Client that manages Cheeps based Android Virtual Device.

    Cheeps is a VM that run Chrome OS which runs on GCE.
    """
    # This is the timeout for betty to start.
    BOOT_TIMEOUT_SECS = 15*60
    # This is printed by betty.sh.
    BOOT_COMPLETED_MSG = "VM successfully started"
    # systemd prints this if betty.sh returns nonzero status code.
    BOOT_FAILED_MSG = "betty.service: Failed with result 'exit-code'"

    def CheckBootFailure(self, serial_out, instance):
        """Overrides superclass. Determines if there's a boot failure."""
        if self.BOOT_FAILED_MSG in serial_out:
            raise errors.DeviceBootError("Betty failed to start")

    # pylint: disable=too-many-locals,arguments-differ
    def CreateInstance(self, instance, image_name, image_project,
                       build_id=None, avd_spec=None):
        """ Creates a cheeps instance in GCE.

        Args:
            instance: name of the VM
            image_name: the GCE image to use
            image_project: project the GCE image is in
            build_id: (optional) the Android build id to use. To specify a
                different betty image you should use a different image_name
            avd_spec: An AVDSpec instance.
        """
        metadata = self._metadata.copy()
        metadata[constants.INS_KEY_AVD_TYPE] = constants.TYPE_CHEEPS
        if build_id:
            metadata['android_build_id'] = build_id

        # Update metadata by avd_spec
        if avd_spec:
            metadata["cvd_01_x_res"] = avd_spec.hw_property[constants.HW_X_RES]
            metadata["cvd_01_y_res"] = avd_spec.hw_property[constants.HW_Y_RES]
            metadata["cvd_01_dpi"] = avd_spec.hw_property[constants.HW_ALIAS_DPI]
            metadata[constants.INS_KEY_DISPLAY] = ("%sx%s (%s)" % (
                avd_spec.hw_property[constants.HW_X_RES],
                avd_spec.hw_property[constants.HW_Y_RES],
                avd_spec.hw_property[constants.HW_ALIAS_DPI]))

        # Add per-instance ssh key
        if self._ssh_public_key_path:
            rsa = self._LoadSshPublicKey(self._ssh_public_key_path)
            logger.info("ssh_public_key_path is specified in config: %s, "
                        "will add the key to the instance.",
                        self._ssh_public_key_path)
            metadata["sshKeys"] = "%s:%s" % (getpass.getuser(), rsa)
        else:
            logger.warning("ssh_public_key_path is not specified in config, "
                           "only project-wide key will be effective.")

        # Add labels for giving the instances ability to be filter for
        # acloud list/delete cmds.
        labels = {constants.LABEL_CREATE_BY: getpass.getuser()}

        gcompute_client.ComputeClient.CreateInstance(
            self,
            instance=instance,
            image_name=image_name,
            image_project=image_project,
            disk_args=None,
            metadata=metadata,
            machine_type=self._machine_type,
            network=self._network,
            zone=self._zone,
            labels=labels)
