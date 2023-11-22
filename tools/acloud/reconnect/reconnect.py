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
r"""Reconnect entry point.

Reconnect will:
 - re-establish ssh tunnels for adb/vnc port forwarding for a remote instance
 - adb connect to forwarded ssh port for remote instance
 - restart vnc for remote/local instances
"""

from __future__ import print_function

import getpass
import re

from acloud.delete import delete
from acloud.internal.lib import auth
from acloud.internal.lib import android_compute_client
from acloud.internal.lib import utils
from acloud.internal.lib.adb_tools import AdbTools
from acloud.list import list as list_instance
from acloud.public import config

_RE_DISPLAY = re.compile(r"([\d]+)x([\d]+)\s.*")
_VNC_STARTED_PATTERN = "ssvnc vnc://127.0.0.1:%(vnc_port)d"


def StartVnc(vnc_port, display):
    """Start vnc connect to AVD.

    Confirm whether there is already a connection before VNC connection.
    If there is a connection, it will not be connected. If not, connect it.
    Before reconnecting, clear old disconnect ssvnc viewer.

    Args:
        vnc_port: Integer of vnc port number.
        display: String, vnc connection resolution. e.g., 1080x720 (240)
    """
    vnc_started_pattern = _VNC_STARTED_PATTERN % {"vnc_port": vnc_port}
    if not utils.IsCommandRunning(vnc_started_pattern):
        #clean old disconnect ssvnc viewer.
        delete.CleanupSSVncviewer(vnc_port)

        match = _RE_DISPLAY.match(display)
        if match:
            utils.LaunchVncClient(vnc_port, match.group(1), match.group(2))
        else:
            utils.LaunchVncClient(vnc_port)


def AddPublicSshRsaToInstance(cfg, user, instance_name):
    """Add the public rsa key to the instance's metadata.

    When the public key doesn't exist in the metadata, it will add it.

    Args:
        cfg: An AcloudConfig instance.
        user: String, the ssh username to access instance.
        instance_name: String, instance name.
    """
    credentials = auth.CreateCredentials(cfg)
    compute_client = android_compute_client.AndroidComputeClient(
        cfg, credentials)
    compute_client.AddSshRsaInstanceMetadata(
        cfg.zone,
        user,
        cfg.ssh_public_key_path,
        instance_name)


def ReconnectInstance(ssh_private_key_path, instance):
    """Reconnect adb/vnc/ssh to the specified instance.

    Args:
        ssh_private_key_path: Path to the private key file.
                              e.g. ~/.ssh/acloud_rsa
        instance: list.Instance() object.
    """
    adb_cmd = AdbTools(instance.forwarding_adb_port)
    vnc_port = instance.forwarding_vnc_port
    # ssh tunnel is up but device is disconnected on adb
    if instance.ssh_tunnel_is_connected and not adb_cmd.IsAdbConnectionAlive():
        adb_cmd.DisconnectAdb()
        adb_cmd.ConnectAdb()
    # ssh tunnel is down and it's a remote instance
    elif not instance.ssh_tunnel_is_connected and not instance.islocal:
        adb_cmd.DisconnectAdb()
        forwarded_ports = utils.AutoConnect(
            instance.ip,
            ssh_private_key_path,
            utils.AVD_PORT_DICT[instance.avd_type].vnc_port,
            utils.AVD_PORT_DICT[instance.avd_type].adb_port,
            getpass.getuser())
        vnc_port = forwarded_ports.vnc_port

    if vnc_port:
        StartVnc(vnc_port, instance.display)


def Run(args):
    """Run reconnect.

    Args:
        args: Namespace object from argparse.parse_args.
    """
    cfg = config.GetAcloudConfig(args)
    instances_to_reconnect = []
    if args.instance_names is not None:
        # user input instance name to get instance object.
        instances_to_reconnect = list_instance.GetInstancesFromInstanceNames(
            cfg, args.instance_names)
    if not instances_to_reconnect:
        instances_to_reconnect = list_instance.ChooseInstances(cfg, args.all)
    for instance in instances_to_reconnect:
        AddPublicSshRsaToInstance(cfg, getpass.getuser(), instance.name)
        ReconnectInstance(cfg.ssh_private_key_path, instance)
