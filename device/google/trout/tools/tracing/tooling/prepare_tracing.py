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
import os
import subprocess
import sys

def subprocess_run(command, shell=True):
    try:
        print(f'subprocess run {command}')
        result = subprocess.run(command, shell=shell, stdout=subprocess.PIPE, check=True)
    except Exception as e:
        raise Exception(f'Execution error for {command}: {e}')

    return result.stdout.decode('utf-8')

def adb_run(ip, cmd):
    if cmd == ['connect']:
        adb_cmd = ['adb', 'connect', ip]
    else:
        adb_cmd = ['adb', '-s', ip] + cmd
    return subprocess_run(adb_cmd, False)

def prepare_qnx(tracing_dir, qnx_dev_dir, qnx_ip, filepath):
    source_file = os.path.join(tracing_dir, 'tooling', 'qnx_perfetto.py')
    target_file = os.path.join(qnx_dev_dir, 'qnx_perfetto.py')
    try:
        os.symlink(source_file, target_file)
    except FileExistsError:
        print(f"Symbolic link creation failed: {target_file} already exists.")
    except Exception as e:
        sys.exit(f"An error occurred: {type(e).__name__}: {e}")

    # setup qnx environment source qnxsdp-env.sh
    qnx_env_file = os.path.join(qnx_dev_dir, "qnxsdp-env.sh")
    clock_util = 'QnxClocktime'
    command = f'source {qnx_env_file} && qcc -Vgcc_ntoaarch64le {filepath} -o {clock_util}'
    subprocess_run(command)

    command = f'scp -F /dev/null {clock_util} root@{qnx_ip}:/bin/'
    subprocess_run(command)

def prepare_android(serial_num, aaos_time_util):
    adb_run(serial_num, ['connect'])
    adb_run(serial_num, ['root'])
    adb_run(serial_num, ['remount'])

    command = ['push', aaos_time_util, '/vendor/bin/android.automotive.time_util']
    adb_run(serial_num, command)

def parse_arguments():
    parser = argparse.ArgumentParser(
        prog = 'prepare_tracing.py',
        description='Setup environment and tools for cross-VM Android tracing')
    parser.add_argument('--host_ip', required=True,
                             help = 'host IP address')
    parser.add_argument('--qnx_dev_dir', required=True,
                             help = 'QNX SDK Directory')
    parser.add_argument('--tracing_tool_dir', required=True,
                             help = 'Tracing Tool Directory Path')
    # One can build Anroid time utility function with source code and locates at:
    # ./target/product/trout_arm64/vendor/bin/android.automotive.time_util
    parser.add_argument('--aaos_time_util', help = 'Android Clock Executable File')
    parser.add_argument('--guest_serial', required='--aaos_time_util' in sys.argv,
                        help = 'Guest VM serial number. Required argument if --aaos_time_util is set')
    return parser.parse_args()

def main():
    args = parse_arguments()

    clock_file_path = os.path.join(args.tracing_tool_dir, "time_utility", "ClocktimeMain.cpp")
    prepare_qnx(args.tracing_tool_dir, args.qnx_dev_dir, args.host_ip, clock_file_path)

    if args.aaos_time_util:
        prepare_android(args.guest_serial, args.aaos_time_util)

if __name__ == '__main__':
    main()
