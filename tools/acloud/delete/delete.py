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
r"""Delete entry point.

Delete will handle all the logic related to deleting a local/remote instance
of an Android Virtual Device.
"""

from __future__ import print_function
from distutils.spawn import find_executable
import logging
import os
import re
import subprocess

from acloud import errors
from acloud.internal import constants
from acloud.internal.lib import utils
from acloud.list import list as list_instances
from acloud.list import instance as instance_class
from acloud.public import config
from acloud.public import device_driver
from acloud.public import report

logger = logging.getLogger(__name__)

_COMMAND_GET_PROCESS_ID = ["pgrep", "launch_cvd"]
_COMMAND_GET_PROCESS_COMMAND = ["ps", "-o", "command", "-p"]
_RE_LAUNCH_CVD = re.compile(r"^(?P<launch_cvd>.+launch_cvd)(.*--daemon ).+")
_SSVNC_VIEWER_PATTERN = "vnc://127.0.0.1:%(vnc_port)d"


def _GetStopCvd():
    """Get stop_cvd path.

    "stop_cvd" and "launch_cvd" are in the same folder(host package folder).
    Try to get directory of "launch_cvd" by "ps -o command -p <pid>." command.
    For example: "/tmp/bin/launch_cvd --daemon --cpus 2 ..."

    Raises:
        errors.NoExecuteCmd: Can't find stop_cvd.

    Returns:
        String of stop_cvd file path.
    """
    process_id = subprocess.check_output(_COMMAND_GET_PROCESS_ID)
    process_info = subprocess.check_output(
        _COMMAND_GET_PROCESS_COMMAND + process_id.splitlines())
    for process in process_info.splitlines():
        match = _RE_LAUNCH_CVD.match(process)
        if match:
            launch_cvd_path = match.group("launch_cvd")
            stop_cvd_cmd = os.path.join(os.path.dirname(launch_cvd_path),
                                        constants.CMD_STOP_CVD)
            if os.path.exists(stop_cvd_cmd):
                logger.debug("stop_cvd command: %s", stop_cvd_cmd)
                return stop_cvd_cmd

    default_stop_cvd = find_executable(constants.CMD_STOP_CVD)
    if default_stop_cvd:
        return default_stop_cvd

    raise errors.NoExecuteCmd("Cannot find stop_cvd binary.")


def CleanupSSVncviewer(vnc_port):
    """Cleanup the old disconnected ssvnc viewer.

    Args:
        vnc_port: Integer, port number of vnc.
    """
    ssvnc_viewer_pattern = _SSVNC_VIEWER_PATTERN % {"vnc_port":vnc_port}
    utils.CleanupProcess(ssvnc_viewer_pattern)


def DeleteInstances(cfg, instances_to_delete):
    """Delete instances according to instances_to_delete.

    Args:
        cfg: AcloudConfig object.
        instances_to_delete: List of list.Instance() object.

    Returns:
        Report instance if there are instances to delete, None otherwise.
    """
    if not instances_to_delete:
        print("No instances to delete")
        return None

    delete_report = None
    remote_instance_list = []
    for instance in instances_to_delete:
        if instance.islocal:
            delete_report = DeleteLocalInstance()
        else:
            remote_instance_list.append(instance.name)
        # Delete ssvnc viewer
        if instance.forwarding_vnc_port:
            CleanupSSVncviewer(instance.forwarding_vnc_port)

    if remote_instance_list:
        # TODO(119283708): We should move DeleteAndroidVirtualDevices into
        # delete.py after gce is deprecated.
        # Stop remote instances.
        return DeleteRemoteInstances(cfg, remote_instance_list, delete_report)

    return delete_report


@utils.TimeExecute(function_description="Deleting remote instances",
                   result_evaluator=utils.ReportEvaluator,
                   display_waiting_dots=False)
def DeleteRemoteInstances(cfg, instances_to_delete, delete_report=None):
    """Delete remote instances.

    Args:
        cfg: AcloudConfig object.
        instances_to_delete: List of instance names(string).
        delete_report: Report object.

    Returns:
        Report instance if there are instances to delete, None otherwise.
    """
    utils.PrintColorString("")
    for instance in instances_to_delete:
        utils.PrintColorString(" - %s" % instance, utils.TextColors.WARNING)
    utils.PrintColorString("")
    utils.PrintColorString("status: waiting...", end="")

    # TODO(119283708): We should move DeleteAndroidVirtualDevices into
    # delete.py after gce is deprecated.
    # Stop remote instances.
    delete_report = device_driver.DeleteAndroidVirtualDevices(
        cfg, instances_to_delete, delete_report)

    return delete_report


@utils.TimeExecute(function_description="Deleting local instances",
                   result_evaluator=utils.ReportEvaluator)
def DeleteLocalInstance():
    """Delete local instance.

    Delete local instance with stop_cvd command and write delete instance
    information to report.

    Returns:
        A Report instance.
    """
    delete_report = report.Report(command="delete")
    try:
        with open(os.devnull, "w") as dev_null:
            subprocess.check_call(
                utils.AddUserGroupsToCmd(_GetStopCvd(),
                                         constants.LIST_CF_USER_GROUPS),
                stderr=dev_null, stdout=dev_null, shell=True)
            delete_report.SetStatus(report.Status.SUCCESS)
            device_driver.AddDeletionResultToReport(
                delete_report, [constants.LOCAL_INS_NAME], failed=[],
                error_msgs=[],
                resource_name="instance")
    except subprocess.CalledProcessError as e:
        delete_report.AddError(str(e))
        delete_report.SetStatus(report.Status.FAIL)
    # Only CF supports local instances so assume it's a CF VNC port.
    CleanupSSVncviewer(constants.CF_VNC_PORT)
    return delete_report


def Run(args):
    """Run delete.

    After delete command executed, tool will return one Report instance.
    If there is no instance to delete, just reutrn empty Report.

    Args:
        args: Namespace object from argparse.parse_args.

    Returns:
        A Report instance.
    """
    cfg = config.GetAcloudConfig(args)
    remote_instances_to_delete = args.instance_names

    if remote_instances_to_delete:
        return DeleteRemoteInstances(cfg, remote_instances_to_delete)

    if args.local_instance:
        if instance_class.LocalInstance():
            return DeleteLocalInstance()
        print("There is no local instance AVD to delete.")
        return report.Report(command="delete")

    if args.adb_port:
        return DeleteInstances(
            cfg, list_instances.GetInstanceFromAdbPort(cfg, args.adb_port))

    # Provide instances list to user and let user choose what to delete if user
    # didn't specific instance name in args.
    return DeleteInstances(cfg, list_instances.ChooseInstances(cfg, args.all))
