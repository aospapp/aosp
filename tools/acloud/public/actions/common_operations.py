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
"""Common operations between managing GCE and Cuttlefish devices.

This module provides the common operations between managing GCE (device_driver)
and Cuttlefish (create_cuttlefish_action) devices. Should not be called
directly.
"""

from __future__ import print_function
import getpass
import logging
import os
import subprocess

from acloud import errors
from acloud.public import avd
from acloud.public import report
from acloud.internal import constants
from acloud.internal.lib import utils

logger = logging.getLogger(__name__)


def CreateSshKeyPairIfNecessary(cfg):
    """Create ssh key pair if necessary.

    Args:
        cfg: An Acloudconfig instance.

    Raises:
        error.DriverError: If it falls into an unexpected condition.
    """
    if not cfg.ssh_public_key_path:
        logger.warning(
            "ssh_public_key_path is not specified in acloud config. "
            "Project-wide public key will "
            "be used when creating AVD instances. "
            "Please ensure you have the correct private half of "
            "a project-wide public key if you want to ssh into the "
            "instances after creation.")
    elif cfg.ssh_public_key_path and not cfg.ssh_private_key_path:
        logger.warning(
            "Only ssh_public_key_path is specified in acloud config, "
            "but ssh_private_key_path is missing. "
            "Please ensure you have the correct private half "
            "if you want to ssh into the instances after creation.")
    elif cfg.ssh_public_key_path and cfg.ssh_private_key_path:
        utils.CreateSshKeyPairIfNotExist(cfg.ssh_private_key_path,
                                         cfg.ssh_public_key_path)
    else:
        # Should never reach here.
        raise errors.DriverError(
            "Unexpected error in CreateSshKeyPairIfNecessary")


class DevicePool(object):
    """A class that manages a pool of virtual devices.

    Attributes:
        devices: A list of devices in the pool.
    """

    def __init__(self, device_factory, devices=None):
        """Constructs a new DevicePool.

        Args:
            device_factory: A device factory capable of producing a goldfish or
                cuttlefish device. The device factory must expose an attribute with
                the credentials that can be used to retrieve information from the
                constructed device.
            devices: List of devices managed by this pool.
        """
        self._devices = devices or []
        self._device_factory = device_factory
        self._compute_client = device_factory.GetComputeClient()

    def _CollectAdbLogcats(self, output_dir):
        """Collect Adb logcats.

        Args:
            output_dir: String, the output file directory to store adb logcats.

        Returns:
            The file information dictionary with file path and file name.
        """
        file_dict = {}
        for device in self._devices:
            if not device.adb_port:
                # If device adb tunnel is not established, do not do adb logcat
                continue
            file_name = "%s_adb_logcat.log" % device.instance_name
            full_file_path = os.path.join(output_dir, file_name)
            logger.info("Get adb %s:%s logcat for instance %s",
                        constants.LOCALHOST, device.adb_port,
                        device.instance_name)
            try:
                subprocess.check_call(
                    ["adb -s %s:%s logcat -b all -d > %s" % (
                        constants.LOCALHOST, device.adb_port, full_file_path)],
                    shell=True)
                file_dict[full_file_path] = file_name
            except subprocess.CalledProcessError:
                logging.error("Failed to get adb logcat for %s for instance %s",
                              device.serial_number, device.instance_name)
        return file_dict

    def CreateDevices(self, num):
        """Creates |num| devices for given build_target and build_id.

        Args:
            num: Number of devices to create.
        """
        # Create host instances for cuttlefish/goldfish device.
        # Currently one instance supports only 1 device.
        for _ in range(num):
            instance = self._device_factory.CreateInstance()
            ip = self._compute_client.GetInstanceIP(instance)
            self.devices.append(
                avd.AndroidVirtualDevice(ip=ip, instance_name=instance))

    @utils.TimeExecute(function_description="Waiting for AVD(s) to boot up",
                       result_evaluator=utils.BootEvaluator)
    def WaitForBoot(self):
        """Waits for all devices to boot up.

        Returns:
            A dictionary that contains all the failures.
            The key is the name of the instance that fails to boot,
            and the value is an errors.DeviceBootError object.
        """
        failures = {}
        for device in self._devices:
            try:
                self._compute_client.WaitForBoot(device.instance_name)
            except errors.DeviceBootError as e:
                failures[device.instance_name] = e
        return failures

    def PullLogs(self, source_files, output_dir, user=None, ssh_rsa_path=None):
        """Tar logs from GCE instance into output_dir.

        Args:
            source_files: List of file names to be pulled.
            output_dir: String. The output file dirtory
            user: String, the ssh username to access GCE
            ssh_rsa_path: String, the ssh rsa key path to access GCE

        Returns:
            The file dictionary with file_path and file_name
        """

        file_dict = {}
        for device in self._devices:
            if isinstance(source_files, basestring):
                source_files = [source_files]
            for source_file in source_files:
                file_name = "%s_%s" % (device.instance_name,
                                       os.path.basename(source_file))
                dst_file = os.path.join(output_dir, file_name)
                logger.info("Pull %s for instance %s with user %s to %s",
                            source_file, device.instance_name, user, dst_file)
                try:
                    utils.ScpPullFile(source_file, dst_file, device.ip,
                                      user_name=user, rsa_key_file=ssh_rsa_path)
                    file_dict[dst_file] = file_name
                except errors.DeviceConnectionError as e:
                    logger.warning("Failed to pull %s from instance %s: %s",
                                   source_file, device.instance_name, e)
        return file_dict

    def CollectSerialPortLogs(self, output_file,
                              port=constants.DEFAULT_SERIAL_PORT):
        """Tar the instance serial logs into specified output_file.

        Args:
            output_file: String, the output tar file path
            port: The serial port number to be collected
        """
        # For emulator, the serial log is the virtual host serial log.
        # For GCE AVD device, the serial log is the AVD device serial log.
        with utils.TempDir() as tempdir:
            src_dict = {}
            for device in self._devices:
                logger.info("Store instance %s serial port %s output to %s",
                            device.instance_name, port, output_file)
                serial_log = self._compute_client.GetSerialPortOutput(
                    instance=device.instance_name, port=port)
                file_name = "%s_serial_%s.log" % (device.instance_name, port)
                file_path = os.path.join(tempdir, file_name)
                src_dict[file_path] = file_name
                with open(file_path, "w") as f:
                    f.write(serial_log.encode("utf-8"))
            utils.MakeTarFile(src_dict, output_file)

    def CollectLogcats(self, output_file, ssh_user, ssh_rsa_path):
        """Tar the instances' logcat and other logs into specified output_file.

        Args:
            output_file: String, the output tar file path
            ssh_user: The ssh user name
            ssh_rsa_path: The ssh rsa key path
        """
        with utils.TempDir() as tempdir:
            file_dict = {}
            if getattr(self._device_factory, "LOG_FILES", None):
                file_dict = self.PullLogs(
                    self._device_factory.LOG_FILES, tempdir, user=ssh_user,
                    ssh_rsa_path=ssh_rsa_path)
            # If the device is auto-connected, get adb logcat
            for file_path, file_name in self._CollectAdbLogcats(
                    tempdir).items():
                file_dict[file_path] = file_name
            utils.MakeTarFile(file_dict, output_file)

    @property
    def devices(self):
        """Returns a list of devices in the pool.

        Returns:
            A list of devices in the pool.
        """
        return self._devices

# TODO: Delete unused-argument when b/119614469 is resolved.
# pylint: disable=unused-argument
# pylint: disable=too-many-locals
def CreateDevices(command, cfg, device_factory, num, avd_type,
                  report_internal_ip=False, autoconnect=False,
                  serial_log_file=None, logcat_file=None):
    """Create a set of devices using the given factory.

    Main jobs in create devices.
        1. Create GCE instance: Launch instance in GCP(Google Cloud Platform).
        2. Starting up AVD: Wait device boot up.

    Args:
        command: The name of the command, used for reporting.
        cfg: An AcloudConfig instance.
        device_factory: A factory capable of producing a single device.
        num: The number of devices to create.
        avd_type: String, the AVD type(cuttlefish, goldfish...).
        report_internal_ip: Boolean to report the internal ip instead of
                            external ip.
        serial_log_file: String, the file path to tar the serial logs.
        logcat_file: String, the file path to tar the logcats.
        autoconnect: Boolean, whether to auto connect to device.

    Raises:
        errors: Create instance fail.

    Returns:
        A Report instance.
    """
    reporter = report.Report(command=command)
    try:
        CreateSshKeyPairIfNecessary(cfg)
        device_pool = DevicePool(device_factory)
        device_pool.CreateDevices(num)
        failures = device_pool.WaitForBoot()
        if failures:
            reporter.SetStatus(report.Status.BOOT_FAIL)
        else:
            reporter.SetStatus(report.Status.SUCCESS)

        # Collect logs
        if serial_log_file:
            device_pool.CollectSerialPortLogs(
                serial_log_file, port=constants.DEFAULT_SERIAL_PORT)
        # TODO(b/119614469): Refactor CollectLogcats into a utils lib and
        #                    turn it on inside the reporting loop.
        # if logcat_file:
        #     device_pool.CollectLogcats(logcat_file, ssh_user, ssh_rsa_path)

        # Write result to report.
        for device in device_pool.devices:
            ip = (device.ip.internal if report_internal_ip
                  else device.ip.external)
            device_dict = {
                "ip": ip,
                "instance_name": device.instance_name
            }
            for attr in ("branch", "build_target", "build_id", "kernel_branch",
                         "kernel_build_target", "kernel_build_id",
                         "emulator_branch", "emulator_build_target",
                         "emulator_build_id"):
                if getattr(device_factory, "_%s" % attr, None):
                    device_dict[attr] = getattr(device_factory, "_%s" % attr)
            if autoconnect:
                forwarded_ports = utils.AutoConnect(
                    ip, cfg.ssh_private_key_path,
                    utils.AVD_PORT_DICT[avd_type].vnc_port,
                    utils.AVD_PORT_DICT[avd_type].adb_port,
                    getpass.getuser())
                device_dict[constants.VNC_PORT] = forwarded_ports.vnc_port
                device_dict[constants.ADB_PORT] = forwarded_ports.adb_port
            if device.instance_name in failures:
                reporter.AddData(key="devices_failing_boot", value=device_dict)
                reporter.AddError(str(failures[device.instance_name]))
            else:
                reporter.AddData(key="devices", value=device_dict)
    except errors.DriverError as e:
        reporter.AddError(str(e))
        reporter.SetStatus(report.Status.FAIL)
    return reporter
