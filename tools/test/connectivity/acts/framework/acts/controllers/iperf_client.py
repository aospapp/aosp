#!/usr/bin/env python3
#
#   Copyright 2018 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

import logging
import os
import subprocess
import threading

from acts import context
from acts import utils
from acts.controllers.android_device import AndroidDevice
from acts.controllers.utils_lib.ssh import connection
from acts.controllers.utils_lib.ssh import settings
from acts.event import event_bus
from acts.event.decorators import subscribe_static
from acts.event.event import TestClassBeginEvent
from acts.event.event import TestClassEndEvent
from acts.libs.proc import job

ACTS_CONTROLLER_CONFIG_NAME = 'IPerfClient'
ACTS_CONTROLLER_REFERENCE_NAME = 'iperf_clients'


def create(configs):
    """Factory method for iperf clients.

    The function creates iperf clients based on at least one config.
    If configs contain ssh settings or and AndroidDevice, remote iperf clients
    will be started on those devices, otherwise, a the client will run on the
    local machine.

    Args:
        configs: config parameters for the iperf server
    """
    results = []
    for c in configs:
        if type(c) is dict and 'AndroidDevice' in c:
            results.append(IPerfClientOverAdb(c['AndroidDevice']))
        elif type(c) is dict and 'ssh_config' in c:
            results.append(IPerfClientOverSsh(c['ssh_config']))
        else:
            results.append(IPerfClient())
    return results


def destroy(_):
    # No cleanup needed.
    pass


class IPerfClientBase(object):
    """The Base class for all IPerfClients.

    This base class is responsible for synchronizing the logging to prevent
    multiple IPerfClients from writing results to the same file, as well
    as providing the interface for IPerfClient objects.
    """
    # Keeps track of the number of IPerfClient logs to prevent file name
    # collisions.
    __log_file_counter = 0

    __log_file_lock = threading.Lock()

    @staticmethod
    def _get_full_file_path(tag=''):
        """Returns the full file path for the IPerfClient log file.

        Note: If the directory for the file path does not exist, it will be
        created.

        Args:
            tag: The tag passed in to the server run.
        """
        current_context = context.get_current_context()
        full_out_dir = os.path.join(current_context.get_full_output_path(),
                                    'iperf_client_files')

        with IPerfClientBase.__log_file_lock:
            utils.create_dir(full_out_dir)
            tags = ['IPerfClient', tag, IPerfClientBase.__log_file_counter]
            out_file_name = '%s.log' % (','.join(
                [str(x) for x in tags if x != '' and x is not None]))
            IPerfClientBase.__log_file_counter += 1

        return os.path.join(full_out_dir, out_file_name)

    def start(self, ip, iperf_args, tag, timeout=3600):
        """Starts iperf client, and waits for completion.

        Args:
            ip: iperf server ip address.
            iperf_args: A string representing arguments to start iperf
                client. Eg: iperf_args = "-t 10 -p 5001 -w 512k/-u -b 200M -J".
            tag: A string to further identify iperf results file
            timeout: the maximum amount of time the iperf client can run.

        Returns:
            full_out_path: iperf result path.
        """
        raise NotImplementedError('start() must be implemented.')


class IPerfClient(IPerfClientBase):
    """Class that handles iperf3 client operations."""

    def start(self, ip, iperf_args, tag, timeout=3600):
        """Starts iperf client, and waits for completion.

        Args:
            ip: iperf server ip address.
            iperf_args: A string representing arguments to start iperf
            client. Eg: iperf_args = "-t 10 -p 5001 -w 512k/-u -b 200M -J".
            tag: tag to further identify iperf results file
            timeout: unused.

        Returns:
            full_out_path: iperf result path.
        """
        iperf_cmd = ['iperf3', '-c', ip] + iperf_args.split(' ')
        full_out_path = self._get_full_file_path(tag)

        with open(full_out_path, 'w') as out_file:
            subprocess.call(iperf_cmd, stdout=out_file)

        return full_out_path


class IPerfClientOverSsh(IPerfClientBase):
    """Class that handles iperf3 client operations on remote machines."""

    def __init__(self, ssh_config):
        self._ssh_settings = settings.from_config(ssh_config)
        self._ssh_session = connection.SshConnection(self._ssh_settings)

    def start(self, ip, iperf_args, tag, timeout=3600):
        """Starts iperf client, and waits for completion.

        Args:
            ip: iperf server ip address.
            iperf_args: A string representing arguments to start iperf
            client. Eg: iperf_args = "-t 10 -p 5001 -w 512k/-u -b 200M -J".
            tag: tag to further identify iperf results file
            timeout: the maximum amount of time to allow the iperf client to run

        Returns:
            full_out_path: iperf result path.
        """
        iperf_cmd = 'iperf3 -c {} {}'.format(ip, iperf_args)
        full_out_path = self._get_full_file_path(tag)

        try:
            iperf_process = self._ssh_session.run(iperf_cmd, timeout=timeout)
            iperf_output = iperf_process.stdout
            with open(full_out_path, 'w') as out_file:
                out_file.write(iperf_output)
        except Exception:
            logging.exception('iperf run failed.')

        return full_out_path


# TODO(markdr): Remove this after automagic controller creation has been
# removed.
class _AndroidDeviceBridge(object):
    """A helper class that bridges the IPerfClientOverAdb to the AndroidDevices.

    Using this class, IPerfClientOverAdb can access the AndroidDevices on the
    test
    """
    android_devices = {}

    @staticmethod
    @subscribe_static(TestClassBeginEvent)
    def on_test_begin(event):
        for device in getattr(event.test_class, 'android_devices', []):
            _AndroidDeviceBridge.android_devices[device.serial] = device

    @staticmethod
    @subscribe_static(TestClassEndEvent)
    def on_test_end(_):
        _AndroidDeviceBridge.android_devices = {}


event_bus.register_subscription(_AndroidDeviceBridge.on_test_begin.subscription)
event_bus.register_subscription(_AndroidDeviceBridge.on_test_end.subscription)


class IPerfClientOverAdb(IPerfClientBase):
    """Class that handles iperf3 operations over ADB devices."""

    def __init__(self, android_device_or_serial):
        """Creates a new IPerfClientOverAdb object.

        Args:
            android_device_or_serial: Either an AndroidDevice object, or the
                serial that corresponds to the AndroidDevice. Note that the
                serial must be present in an AndroidDevice entry in the ACTS
                config.
        """
        self._android_device_or_serial = android_device_or_serial

    @property
    def _android_device(self):
        if isinstance(self._android_device_or_serial, AndroidDevice):
            return self._android_device_or_serial
        else:
            return _AndroidDeviceBridge.android_devices[
                self._android_device_or_serial]

    def start(self, ip, iperf_args, tag, timeout=3600):
        """Starts iperf client, and waits for completion.

        Args:
            ip: iperf server ip address.
            iperf_args: A string representing arguments to start iperf
            client. Eg: iperf_args = "-t 10 -p 5001 -w 512k/-u -b 200M -J".
            tag: tag to further identify iperf results file
            timeout: the maximum amount of time to allow the iperf client to run

        Returns:
            The iperf result file path.
        """
        iperf_output = ''
        try:
            iperf_status, iperf_output = self._android_device.run_iperf_client(
                ip, iperf_args, timeout=timeout)
        except job.TimeoutError:
            logging.warning('TimeoutError: Iperf measurement timed out.')

        full_out_path = self._get_full_file_path(tag)
        with open(full_out_path, 'w') as out_file:
            out_file.write('\n'.join(iperf_output))

        return full_out_path
