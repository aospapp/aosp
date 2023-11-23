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
r"""Instance class.

Define the instance class used to hold details about an AVD instance.

The instance class will hold details about AVD instances (remote/local) used to
enable users to understand what instances they've created. This will be leveraged
for the list, delete, and reconnect commands.

The details include:
- instance name (for remote instances)
- creation date/instance duration
- instance image details (branch/target/build id)
- and more!
"""

import datetime
import logging
import re
import subprocess

# pylint: disable=import-error
import dateutil.parser
import dateutil.tz

from acloud.internal import constants
from acloud.internal.lib import utils
from acloud.internal.lib.adb_tools import AdbTools

logger = logging.getLogger(__name__)

_MSG_UNABLE_TO_CALCULATE = "Unable to calculate"
_RE_GROUP_ADB = "local_adb_port"
_RE_GROUP_VNC = "local_vnc_port"
_RE_SSH_TUNNEL_PATTERN = (r"((.*\s*-L\s)(?P<%s>\d+):127.0.0.1:%s)"
                          r"((.*\s*-L\s)(?P<%s>\d+):127.0.0.1:%s)"
                          r"(.+%s)")
_RE_TIMEZONE = re.compile(r"^(?P<time>[0-9\-\.:T]*)(?P<timezone>[+-]\d+:\d+)$")

_COMMAND_PS_LAUNCH_CVD = ["ps", "-wweo", "lstart,cmd"]
_RE_LAUNCH_CVD = re.compile(r"(?P<date_str>^[^/]+)(.*launch_cvd --daemon )+"
                            r"((.*\s*-cpus\s)(?P<cpu>\d+))?"
                            r"((.*\s*-x_res\s)(?P<x_res>\d+))?"
                            r"((.*\s*-y_res\s)(?P<y_res>\d+))?"
                            r"((.*\s*-dpi\s)(?P<dpi>\d+))?"
                            r"((.*\s*-memory_mb\s)(?P<memory>\d+))?"
                            r"((.*\s*-blank_data_image_mb\s)(?P<disk>\d+))?")
_FULL_NAME_STRING = ("device serial: %(device_serial)s (%(instance_name)s) "
                     "elapsed time: %(elapsed_time)s")


def _GetElapsedTime(start_time):
    """Calculate the elapsed time from start_time till now.

    Args:
        start_time: String of instance created time.

    Returns:
        datetime.timedelta of elapsed time, _MSG_UNABLE_TO_CALCULATE for
        datetime can't parse cases.
    """
    match = _RE_TIMEZONE.match(start_time)
    try:
        # Check start_time has timezone or not. If timezone can't be found,
        # use local timezone to get elapsed time.
        if match:
            return datetime.datetime.now(
                dateutil.tz.tzlocal()) - dateutil.parser.parse(start_time)

        return datetime.datetime.now(
            dateutil.tz.tzlocal()) - dateutil.parser.parse(
                start_time).replace(tzinfo=dateutil.tz.tzlocal())
    except ValueError:
        logger.debug(("Can't parse datetime string(%s)."), start_time)
        return _MSG_UNABLE_TO_CALCULATE


class Instance(object):
    """Class to store data of instance."""

    def __init__(self):
        self._name = None
        self._fullname = None
        self._status = None
        self._display = None  # Resolution and dpi
        self._ip = None
        self._adb_port = None  # adb port which is forwarding to remote
        self._vnc_port = None  # vnc port which is forwarding to remote
        self._ssh_tunnel_is_connected = None  # True if ssh tunnel is still connected
        self._createtime = None
        self._elapsed_time = None
        self._avd_type = None
        self._avd_flavor = None
        self._is_local = None  # True if this is a local instance

    def __repr__(self):
        """Return full name property for print."""
        return self._fullname

    def Summary(self):
        """Let's make it easy to see what this class is holding."""
        indent = " " * 3
        representation = []
        representation.append(" name: %s" % self._name)
        representation.append("%s IP: %s" % (indent, self._ip))
        representation.append("%s create time: %s" % (indent, self._createtime))
        representation.append("%s elapse time: %s" % (indent, self._elapsed_time))
        representation.append("%s status: %s" % (indent, self._status))
        representation.append("%s avd type: %s" % (indent, self._avd_type))
        representation.append("%s display: %s" % (indent, self._display))
        representation.append("%s vnc: 127.0.0.1:%s" % (indent, self._vnc_port))

        if self._adb_port:
            representation.append("%s adb serial: 127.0.0.1:%s" %
                                  (indent, self._adb_port))
        else:
            representation.append("%s adb serial: disconnected" % indent)

        return "\n".join(representation)

    @property
    def name(self):
        """Return the instance name."""
        return self._name

    @property
    def fullname(self):
        """Return the instance full name."""
        return self._fullname

    @property
    def ip(self):
        """Return the ip."""
        return self._ip

    @property
    def status(self):
        """Return status."""
        return self._status

    @property
    def display(self):
        """Return display."""
        return self._display

    @property
    def forwarding_adb_port(self):
        """Return the adb port."""
        return self._adb_port

    @property
    def forwarding_vnc_port(self):
        """Return the vnc port."""
        return self._vnc_port

    @property
    def ssh_tunnel_is_connected(self):
        """Return the connect status."""
        return self._ssh_tunnel_is_connected

    @property
    def createtime(self):
        """Return create time."""
        return self._createtime

    @property
    def avd_type(self):
        """Return avd_type."""
        return self._avd_type

    @property
    def avd_flavor(self):
        """Return avd_flavor."""
        return self._avd_flavor

    @property
    def islocal(self):
        """Return if it is a local instance."""
        return self._is_local


class LocalInstance(Instance):
    """Class to store data of local instance."""

    # pylint: disable=protected-access
    def __new__(cls):
        """Initialize a localInstance object.

        Gather local instance information from launch_cvd process.

        returns:
            Instance object if launch_cvd process is found otherwise return None.
        """
        # Running instances on local is not supported on all OS.
        if not utils.IsSupportedPlatform():
            return None

        process_output = subprocess.check_output(_COMMAND_PS_LAUNCH_CVD)
        for line in process_output.splitlines():
            match = _RE_LAUNCH_CVD.match(line)
            if match:
                local_instance = Instance()
                x_res = match.group("x_res")
                y_res = match.group("y_res")
                dpi = match.group("dpi")
                date_str = match.group("date_str").strip()
                local_instance._name = constants.LOCAL_INS_NAME
                local_instance._createtime = date_str
                local_instance._elapsed_time = _GetElapsedTime(date_str)
                local_instance._fullname = (_FULL_NAME_STRING %
                                            {"device_serial": "127.0.0.1:%d" %
                                                              constants.CF_ADB_PORT,
                                             "instance_name": local_instance._name,
                                             "elapsed_time": local_instance._elapsed_time})
                local_instance._avd_type = constants.TYPE_CF
                local_instance._ip = "127.0.0.1"
                local_instance._status = constants.INS_STATUS_RUNNING
                local_instance._adb_port = constants.CF_ADB_PORT
                local_instance._vnc_port = constants.CF_VNC_PORT
                local_instance._display = ("%sx%s (%s)" % (x_res, y_res, dpi))
                local_instance._is_local = True
                local_instance._ssh_tunnel_is_connected = True
                return local_instance
        return None


class RemoteInstance(Instance):
    """Class to store data of remote instance."""

    def __init__(self, gce_instance):
        """Process the args into class vars.

        RemoteInstace initialized by gce dict object.
        Reference:
        https://cloud.google.com/compute/docs/reference/rest/v1/instances/get

        Args:
            gce_instance: dict object queried from gce.
        """
        super(RemoteInstance, self).__init__()
        self._ProcessGceInstance(gce_instance)
        self._is_local = False

    def _ProcessGceInstance(self, gce_instance):
        """Parse the required data from gce_instance to local variables.

        We also gather more details on client side including the forwarding adb
        port and vnc port which will be used to determine the status of ssh
        tunnel connection.

        The status of gce instance will be displayed in _fullname property:
        - Connected: If gce instance and ssh tunnel and adb connection are all
         active.
        - No connected: If ssh tunnel or adb connection is not found.
        - Terminated: If we can't retrieve the public ip from gce instance.

        Args:
           gce_instance: dict object queried from gce.
        """
        self._name = gce_instance.get(constants.INS_KEY_NAME)

        self._createtime = gce_instance.get(constants.INS_KEY_CREATETIME)
        self._elapsed_time = _GetElapsedTime(self._createtime)
        self._status = gce_instance.get(constants.INS_KEY_STATUS)

        ip = None
        for network_interface in gce_instance.get("networkInterfaces"):
            for access_config in network_interface.get("accessConfigs"):
                ip = access_config.get("natIP")

        # Get metadata
        for metadata in gce_instance.get("metadata", {}).get("items", []):
            key = metadata["key"]
            value = metadata["value"]
            if key == constants.INS_KEY_DISPLAY:
                self._display = value
            elif key == constants.INS_KEY_AVD_TYPE:
                self._avd_type = value
            elif key == constants.INS_KEY_AVD_FLAVOR:
                self._avd_flavor = value

        # Find ssl tunnel info.
        if ip:
            forwarded_ports = self.GetAdbVncPortFromSSHTunnel(ip,
                                                              self._avd_type)
            self._ip = ip
            self._adb_port = forwarded_ports.adb_port
            self._vnc_port = forwarded_ports.vnc_port
            self._ssh_tunnel_is_connected = self._adb_port is not None

            adb_device = AdbTools(self._adb_port)
            if adb_device.IsAdbConnected():
                self._fullname = (_FULL_NAME_STRING %
                                  {"device_serial": "127.0.0.1:%d" % self._adb_port,
                                   "instance_name": self._name,
                                   "elapsed_time": self._elapsed_time})
            else:
                self._fullname = (_FULL_NAME_STRING %
                                  {"device_serial": "not connected",
                                   "instance_name": self._name,
                                   "elapsed_time": self._elapsed_time})
        # If instance is terminated, its ip is None.
        else:
            self._ssh_tunnel_is_connected = False
            self._fullname = (_FULL_NAME_STRING %
                              {"device_serial": "terminated",
                               "instance_name": self._name,
                               "elapsed_time": self._elapsed_time})

    @staticmethod
    def GetAdbVncPortFromSSHTunnel(ip, avd_type):
        """Get forwarding adb and vnc port from ssh tunnel.

        Args:
            ip: String, ip address.
            avd_type: String, the AVD type.

        Returns:
            NamedTuple ForwardedPorts(vnc_port, adb_port) holding the ports
            used in the ssh forwarded call. Both fields are integers.
        """
        process_output = subprocess.check_output(constants.COMMAND_PS)
        default_vnc_port = utils.AVD_PORT_DICT[avd_type].vnc_port
        default_adb_port = utils.AVD_PORT_DICT[avd_type].adb_port
        re_pattern = re.compile(_RE_SSH_TUNNEL_PATTERN %
                                (_RE_GROUP_VNC, default_vnc_port,
                                 _RE_GROUP_ADB, default_adb_port, ip))

        adb_port = None
        vnc_port = None
        for line in process_output.splitlines():
            match = re_pattern.match(line)
            if match:
                adb_port = int(match.group(_RE_GROUP_ADB))
                vnc_port = int(match.group(_RE_GROUP_VNC))
                break

        logger.debug(("grathering detail for ssh tunnel. "
                      "IP:%s, forwarding (adb:%d, vnc:%d)"), ip, adb_port,
                     vnc_port)

        return utils.ForwardedPorts(vnc_port=vnc_port, adb_port=adb_port)
