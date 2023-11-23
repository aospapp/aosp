#!/usr/bin/env python3
#
# Copyright (C) 2023 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import argparse
from datetime import datetime
from threading import Thread
import os
import re
import sys
import time
import traceback

# May import this package in the workstation with:
# pip install paramiko
from paramiko import SSHClient
from paramiko import AutoAddPolicy

from prepare_tracing import adb_run

# Usage:
# ./calculate_time_offset.py --host_username root --host_ip 10.42.0.247
# --guest_serial 10.42.0.247 --clock_name CLOCK_REALTIME
# or
# ./calculate_time_offset.py --host_username root --host_ip 10.42.0.247
# --guest_serial 10.42.0.247 --clock_name CLOCK_REALTIME --mode trace

class Device:
    # Get the machine time
    def __init__(self, clock_name, mode):
        if clock_name != None:
            self.time_cmd += f' {clock_name}'
        if mode == "trace":
            if clock_name == None:
                raise SystemExit("Error: with trace mode, clock_name must be specified")
            self.time_cmd = f'{self.time_cmd} --trace'
    def GetTime(self):
        pass

    def ParseTime(self, time_str):
        pattern = r'\d+'
        match = re.search(pattern, time_str)
        if match is None:
            raise Exception(f'Error: ParseTime no match time string: {time_str}')
        return int(match.group())

    # Here is an example of time_util with --trace flag enable and given a clockname
    # will give a snapshot of the CPU counter and clock timestamp.
    # time_util  CLOCK_REALTIME --trace
    # 6750504532818         CPU tick value
    # 1686355159395639260   CLOCK_REATIME
    # 0.0192                CPU tick per nanosecond
    #
    # The example's output is ts_str
    def TraceTime(self, ts_str):
        lines = ts_str.split("\n")
        if len(lines) < 3:
            raise Exception(f'Error: TraceTime input is wrong {ts_str}.'
                            'Expecting three lines of input: '
                            'cpu_tick_value, CLOCK value, and CPU cycles per nanoseconds')

        self.cpu_ts = int(lines[0])
        self.clock_ts = int(lines[1])
        self.cpu_cycles = float(lines[2])

class QnxDevice(Device):
    def __init__(self, host_username, host_ip, clock_name, mode):
        self.sshclient = SSHClient()
        self.sshclient.load_system_host_keys()
        self.sshclient.set_missing_host_key_policy(AutoAddPolicy())
        self.sshclient.connect(host_ip, username=host_username)
        self.time_cmd = "/bin/QnxClocktime"
        super().__init__(clock_name, mode)

    def GetTime(self):
        (stdin, stdout, stderr) = self.sshclient.exec_command(self.time_cmd)
        return stdout

    def ParseTime(self, time_str):
        time_decoded_str = time_str.read().decode()
        return super().ParseTime(time_decoded_str)

    def TraceTime(self):
        result_str = self.GetTime()
        ts_str = result_str.read().decode()
        super().TraceTime(ts_str)

class AndroidDevice(Device):
    def __init__(self, guest_serial, clock_name, mode):
        adb_run(guest_serial,  ['connect'])
        self.time_cmd =  "/vendor/bin/android.automotive.time_util"
        self.serial = guest_serial
        super().__init__(clock_name, mode)

    def GetTime(self):
        ts = adb_run(self.serial, ['shell', self.time_cmd])
        return ts

    def TraceTime(self):
        super().TraceTime(self.GetTime())

# measure the time offset between device1 and device2 with ptp,
# return the average value over cnt times.
def Ptp(device1, device2):
    # set up max delay as 100 milliseconds
    max_delay_ms = 100000000
    # set up max offset as 2 milliseconds
    max_offset_ms = 2000000
    max_retry = 20
    for i in range(max_retry):
        time1_d1_str = device1.GetTime()
        time1_d2_str = device2.GetTime()
        time2_d2_str = device2.GetTime()
        time2_d1_str = device1.GetTime()

        time1_d1 = device1.ParseTime(time1_d1_str)
        time2_d1 = device1.ParseTime(time2_d1_str)
        time1_d2 = device2.ParseTime(time1_d2_str)
        time2_d2 = device2.ParseTime(time2_d2_str)

        offset = (time1_d2 + time2_d2 - time1_d1 - time2_d1)/2
        if time2_d1 - time1_d1 > max_delay_ms or time2_d2 - time2_d2 > max_delay_ms or abs(offset) > max_offset_ms:
            print(f'Network delay is too big, ignore this measure {offset}')
        else:
            return int(offset)
    raise SystemExit(f"Network delay is still too big after {max_retry} retries")

# It assumes device1 and device2 have access to the same CPU counter and uses the cpu counter
# as the time source to calculate the time offset between device1 and device2.
def TraceTimeOffset(device1, device2):
    offset = device2.clock_ts - device1.clock_ts - ((device2.cpu_ts - device1.cpu_ts)/device2.cpu_cycles)
    return int(offset)

def CalculateTimeOffset(host_username, hostip, guest_serial, clock_name, mode):
    qnx = QnxDevice(host_username, hostip, clock_name, mode)
    android = AndroidDevice(guest_serial, clock_name, mode)
    if mode == "trace":
        return TraceTimeOffset(qnx, android)
    else:
        return Ptp(qnx, android)


def ParseArguments():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host_ip', required=True,
                             help = 'host IP address')
    parser.add_argument('--host_username', required=True,
                             help = 'host username')
    parser.add_argument('--guest_serial', required=True,
                        help = 'guest VM serial number')
    parser.add_argument('--clock_name', required=False, choices =['CLOCK_REALTIME','CLOCK_MONOTONIC'],
                        help = 'clock that will be used for the measument. By default CPU counter is used.')
    parser.add_argument('--mode', choices=['ptp', 'trace'], default='ptp',
                        help='select the mode of operation. If the two devices have access of the same CPU counter, '
                        'use trace option. Otherwise use ptp option.')
    return parser.parse_args()

def main():
    args = ParseArguments()
    time_offset = CalculateTimeOffset(args.host_username, args.host_ip, args.guest_serial, args.clock_name, args.mode)
    print(f'Time offset between host and guest is {time_offset} nanoseconds')
if __name__ == "__main__":
    main()
