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
#
import argparse
from datetime import datetime
from threading import Thread
import json
import os
import re
import sys
import subprocess
import time
import traceback

# May import this package in the workstation with:
# pip install paramiko
from paramiko import SSHClient
from paramiko import AutoAddPolicy

import calculate_time_offset
from remote_slay import slay_process
import update_trace

# This script works on Linux workstation.
# We haven't tested on Windows/macOS.

# Demonstration of tracing QNX host and get tracelogger output as a binary file.
#
# Prerequirements for run the script:
# Install  traceprinter utility in QNX Software and setup proper path for it.
# One can Install QNX Software Center from the following location:
# https://www.qnx.com/download/group.html?programid=29178
#
# Define an environment varialbe QNX_DEV_DIR, the script will read this environment variable.
# export QNX_DEV_DIR=/to/qns_dev_dir/
# Make symbolic link or copy traceprinter, qnx_perfetto.py under this directory.
#
# Install perfetto SDK from https://github.com/google/perfetto/releases.
# Define an environment varialbe PERFETTO_DEV_DIR, the script will read this environment variable.
# export PERFETTO_DEV_DIR=/to/perfetto_sdk_dir/
#
# Usage:
# python3 tracing_agent.py --guest_serial 10.42.0.235 --host_ip
# 10.42.0.235 --host_tracing_file_name qnx.trace --out_dir test_trace
# --duration 2 --host_username root
#

QNX_DEV_DIR_ENV_NAME = 'QNX_DEV_DIR'
qnx_dev_dir = os.environ.get(QNX_DEV_DIR_ENV_NAME)

PERFETTO_DEV_DIR_ENV_NAME = 'PERFETTO_DEV_DIR'
perfetto_dev_dir = os.environ.get(PERFETTO_DEV_DIR_ENV_NAME)

def parseArguments():
    parser = argparse.ArgumentParser(
        prog = 'vm_tracing_driver.py',
        description='VM Tracing Driver')
    parser.add_argument('--guest_serial', required=True,
                             help = 'guest VM serial number')
    parser.add_argument('--guest_config', required=True,
                             help = 'guest VM configuration file')
    parser.add_argument('--host_ip', required=True,
                             help = 'host IP address')
    #TODO(b/267675642): read user name from user ssh_config.
    parser.add_argument('--host_username', required=True,
                             help = 'host username')
    parser.add_argument('--host_tracing_file_name', required=True,
                             help = 'host trace file name')
    parser.add_argument('--out_dir', required=True,
                             help = 'directory to store output file')
    parser.add_argument('--duration', type=int, required=True,
                             help = 'tracing time')
    parser.add_argument('--verbose', action='store_true')
    return parser.parse_args()

def subprocessRun(cmd):
    print(f'Subprocess executing command {cmd}')
    return subprocess.run(cmd, stdout=subprocess.PIPE, check=True)

# This is the base class for tracing agent.
class TracingAgent:
    # child class should extend this function
    def __init__(self, name='TracingAgent'):
        self.name = name
        self.thread = Thread(target=self.run)
        self.error_msg = None

    # abstract method
    # Start tracing on the device.
    # Raise exception when there is an error.
    def startTracing(self):
        pass

    # abstract method
    # Copy tracing file from device to worksstation.
    # Raise exception when there is an error.
    def copyTracingFile(self):
        pass

    # abstract method
    # Parse tracing file to perfetto input format.
    # Raise exception when there is an error.
    def parseTracingFile(self):
        pass

    def verbose_print(self, str):
        if self.verbose:
            print(str)

    def run(self):
        try:
            print(f'**********start tracing on {self.name} vm')
            self.startTracing()

            print(f'**********copy tracing file from {self.name} vm')
            self.copyTracingFile()

            print(f'**********parse tracing file of {self.name} vm')
            self.parseTracingFile()
        except Exception as e:
            traceresult = traceback.format_exc()
            self.error_msg = f'Caught an exception: {traceback.format_exc()}'
            sys.exit()

    def start(self):
        self.thread.start()

    def join(self):
        self.thread.join()
        # Check if the thread exit cleanly or not.
        # If the thread doesn't exit cleanly, will throw an exception in the main process.
        if self.error_msg != None:
            sys.exit(self.error_msg)

        print(f'**********tracing done on {self.name}')

# HostTracingAgent for QNX
class QnxTracingAgent(TracingAgent):
    def __init__(self, args):
        self.verbose = args.verbose
        self.ip = args.host_ip
        super().__init__(f'qnx host at ssh://{self.ip}')
        self.username = args.host_username
        self.out_dir = args.out_dir
        self.duration = args.duration
        self.tracing_kev_file_path = os.path.join(args.out_dir, f'{args.host_tracing_file_name}.kev')
        self.tracing_printer_file_path = os.path.join(args.out_dir, f'{args.host_tracing_file_name}.printer')

        # setup a sshclient
        self.client = SSHClient()
        self.client.load_system_host_keys()
        self.client.set_missing_host_key_policy(AutoAddPolicy())
        self.client.connect(self.ip, username=self.username)

        # create directory at the host to store tracing config and tracing output
        if self.doesDirExist(self.out_dir) == False:
            mkdir_cmd = f'mkdir {self.out_dir}'
            self.clientExecuteCmd(mkdir_cmd)

        # TODO(b/267675642):
        # read the trace configuration file to get the tracing parameters

        if not slay_process(self.client, "memdump_tracelogger"):
            print("Warning: could not kill memdump_tracelogger on QNX."
                  "If there is a resource busy error reported by QNX, "
                  "execute slay memdump_tracelogger on QNX.")

    def clientExecuteCmd(self, cmd_str):
        self.verbose_print(f'sshclient executing command {cmd_str}')
        (stdin, stdout, stderr) = self.client.exec_command(cmd_str)
        if stdout.channel.recv_exit_status():
            raise Exception(stderr.read())
        elif stderr.channel.recv_exit_status():
            raise Exception(stderr.read())

    def doesDirExist(self, dirpath):
        cmd = f'ls -d {dirpath}'
        (stdin, stdout, stderr) = self.client.exec_command(cmd)
        error_str = stderr.read()
        if len(error_str) == 0:
            return True
        return False

    def startTracing(self):
        tracing_cmd = f'on -p15 tracelogger  -s {self.duration} -f {self.tracing_kev_file_path}'
        self.clientExecuteCmd(tracing_cmd)

    def copyTracingFile(self):
        # copy tracing output file from host to workstation
        os.makedirs(self.out_dir, exist_ok=True)
        scp_cmd = ['scp', '-F', '/dev/null',
                   f'{self.username}@{self.ip}:{self.tracing_kev_file_path}',
                   f'{self.tracing_kev_file_path}']
        subprocessRun(scp_cmd)

    def parseTracingFile(self):
        # using traceprinter to convert binary file to text file
        # for traceprinter options, reference:
        # http://www.qnx.com/developers/docs/7.0.0/index.html#com.qnx.doc.neutrino.utilities/topic/t/traceprinter.html
        global qnx_dev_dir
        traceprinter = os.path.join(qnx_dev_dir, 'host/linux/x86_64/usr/bin/', 'traceprinter')
        traceprinter_cmd = [traceprinter,
                            '-p', '%C %t %Z %z',
                            '-f', f'{self.tracing_kev_file_path}',
                            '-o', f'{self.tracing_printer_file_path}']
        subprocessRun(traceprinter_cmd)

        # convert tracing file in text format to json format:
        qnx2perfetto = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'qnx_perfetto.py')
        convert_cmd = [qnx2perfetto,
                       f'{self.tracing_printer_file_path}']
        subprocessRun(convert_cmd)

class AndroidTracingAgent(TracingAgent):
    def __init__(self, args):
        self.verbose = args.verbose
        self.vm_trace_file = 'guest.trace'
        self.vm_config = 'guest_config.pbtx'
        self.ip = args.guest_serial
        self.out_dir = args.out_dir
        self.trace_file_path = os.path.join(args.out_dir, self.vm_trace_file)
        self.config_file_path = args.guest_config
        self.vm_config_file_path = os.path.join('/data/misc/perfetto-configs/', self.vm_config)
        self.vm_trace_file_path = os.path.join('/data/misc/perfetto-traces/', self.vm_trace_file)
        super().__init__(f'android vm at adb://{self.ip}')

        self.adb(['connect'])
        self.adb(['root'])
        self.adb(['remount'])

    def copyConfigFile(self):
        self.adb(['push', self.config_file_path, self.vm_config_file_path])

    def startTracing(self):
        self.copyConfigFile()
        self.adb(['shell', '-t', 'perfetto',
                       '--txt', '-c', self.vm_config_file_path,
                       '--out', self.vm_trace_file_path])

    def copyTracingFile(self):
        os.makedirs(self.out_dir, exist_ok=True)
        self.adb(['pull', self.vm_trace_file_path, self.trace_file_path])

    def adb(self, cmd):
        adb_cmd = ['adb']
        if cmd == ['connect']:
            adb_cmd.extend(['connect', self.ip])
        else:
            adb_cmd.extend(['-s', self.ip])
            adb_cmd.extend(cmd)

        return subprocessRun(adb_cmd)

def merge_files(in_file1, in_file2, out_file):
    try:
        with open(in_file1, 'r') as f:
            trace_dict1 = json.loads(f.read())

        with open(in_file2, 'r') as f:
            trace_dict2 = json.loads(f.read())

        trace_dict1.update(trace_dict2)
        with open(out_file, 'w') as f:
            json.dump(trace_dict1, f)
        print(f"Updated trace data saved to {out_file}")
    except Exception as e:
        sys.exit(f'merge_files failure due to: {e}')

def update_and_merge_files(args, host_agent, guest_agent):
    # calculate the time offset
    try:
        time_offset = calculate_time_offset.CalculateTimeOffset(
            args.host_username, args.host_ip, args.guest_serial, "CLOCK_REALTIME", "trace")
    except Exception as e:
        sys.exit(f'Exception: catch calculate_time_offset exception {e}')

    # update the timestamp and process id in the host json file
    host_json_file = '{}.json'.format(host_agent.tracing_printer_file_path)
    if not update_trace.update_trace_file(host_json_file, time_offset):
        sys.exit('Error: update_trace_file')

    # convert guest trace file to .json format
    global perfetto_dev_dir
    traceconv_cmd = os.path.join(perfetto_dev_dir, 'traceconv')
    guest_json_file = '{}.json'.format(guest_agent.trace_file_path)
    subprocessRun([traceconv_cmd, 'json', guest_agent.trace_file_path, guest_json_file])

    # merge host and guest trace files
    merged_file_path = os.path.join(guest_agent.out_dir, 'merged_guest_host.json')
    host_update_json_file = '{}_updated.json'.format(host_agent.tracing_printer_file_path)
    merge_files(host_json_file, guest_json_file, merged_file_path)

def main():
    if perfetto_dev_dir is None:
        sys.exit(f'The {PERFETTO_DEV_DIR_ENV_NAME} variable is not defined')

    if qnx_dev_dir is None:
        sys.exit(f'The {QNX_DEV_DIR_ENV_NAME} variable is not defined')

    args = parseArguments()
    host_agent = QnxTracingAgent(args)
    guest_agent = AndroidTracingAgent(args)

    host_agent.start()
    guest_agent.start()
    host_agent.join()
    guest_agent.join()
    update_and_merge_files(args, host_agent, guest_agent)

if __name__ == "__main__":
    main()
