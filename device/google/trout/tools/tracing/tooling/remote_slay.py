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

import paramiko
import argparse

# Usage:
# ./remote_slay.py --ip_address 10.42.0.247 --process_name memdump_tracelogger --user_name root
# If the program is not running on the QNX, output will be:
# memdump_tracelogger is not running on 10.42.0.247
#
# If the program is running on the QNX, output will be:
# 1 memdump_tracelogger running on 10.42.0.247 are slayed

def slay_process(sshclient, process_name):
    command = f"slay {process_name}"
    try:
        stdin, stdout, stderr = sshclient.exec_command(command)
    except Exception as e:
        print(f"slay_process catch an exception: {str(e)}")
        return False

    # Wait for the command to finish
    exit_status = stdout.channel.recv_exit_status()
    print(f"{exit_status} processes are slayed.")
    return exit_status != 0

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Check if a process is running on a remote QNX system.')
    parser.add_argument('--ip_address', required=True, help='IP address of the remote QNX system')
    parser.add_argument('--user_name', required=True, help='Name of user')
    parser.add_argument('--process_name', required=True, help='Name of the process to check')
    args = parser.parse_args()

    remote_ip = args.ip_address
    user_name = args.user_name
    sshclient = paramiko.SSHClient()
    sshclient.load_system_host_keys()
    sshclient.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    sshclient.connect(remote_ip, username=user_name)

    process_name = args.process_name
    result = slay_process(sshclient, process_name)
    sshclient.close()

    if result:
        print(f'{process_name} running on {remote_ip} are slayed')
    else:
        sys.exit('No processes matched the supplied criteria, an error occurred, '
                 'or the number of processes matched and acted upon was an even multiple of 256')
