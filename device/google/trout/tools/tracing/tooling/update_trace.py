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

import sys
import os
import json
import argparse

def update_timestamp_perfetto_trace(trace_dict, time_offset):
    if time_offset == 0:
        return

    for event in trace_dict['traceEvents']:
        if 'ts' in event:
            event['ts'] += time_offset

def update_pid_perfetto_trace(trace_dict, start_pid, max_pid):
    if start_pid >= max_pid:
        raise SystemExit(f"Error: start_pid {start_pid} should be smaller than max_pid {max_pid}")
    if start_pid < 0 or max_pid <= 0:
        raise SystemExit(f"Error: both start_pid {start_pid} and max_pid {max_pid} should be larger than 0")

    pid_dict = {}
    current_max_pid = start_pid
    for event in trace_dict['traceEvents']:
        if 'pid' in event:
            old_pid = event['pid']
            new_pid = pid_dict.get(old_pid)
            if new_pid == None:
                if current_max_pid < max_pid:
                    new_pid = current_max_pid
                    pid_dict[old_pid] = new_pid
                    current_max_pid += 1
                else:
                    raise SystemExit("Error: due to out of range for allocating pids")
            event['pid'] = new_pid

def update_trace_file(input_file, time_offset, start_pid=(1<<16), max_pid = (1<<32)):
    try:
        with open(input_file, 'r') as f:
            trace_dict = json.loads(f.read())
    except Exception as e:
        print(f'Error: update_trace_file open input file: {input_file} : {e}')
        return False

    update_timestamp_perfetto_trace(trace_dict, time_offset)
    update_pid_perfetto_trace(trace_dict, start_pid, max_pid)

    # Save the updated trace data to a new JSON file
    # add '_updated' to the output filename
    file_path = os.path.splitext(input_file)
    output_file = f"{file_path[0]}_updated.json"
    try:
        with open(output_file, 'w') as f:
            json.dump(trace_dict, f)
    except Exception as e:
        print(f'Error: update_trace_file open output_file {output_file} : {e}')
        return False

    print(f"Updated trace data saved to {output_file}")
    return True

def parseArguments():
    parser = argparse.ArgumentParser(
        description='Update perfetto trace event timestamp with the given offset.')
    parser.add_argument('--input_file', required=True,
                        help='path to the input Perfetto JSON file')
    parser.add_argument('--time_offset', type=int, required=False, default=0,
                        help='time offset value in nanoseconds. If it is 0, timestamp will not be updated.')
    # At default set the start_pid = (2^16)
    parser.add_argument('--start_pid', type=int, required=False, default=65536,
                        help='the smallest pid value')
    # At default set the max_pid = (2^32)
    parser.add_argument('--max_pid', type=int, required=False, default=4294967296,
                        help='the largest pid value. If max_pid == start_[id, pid will not be updated.')

    args = parser.parse_args()
    return args

if __name__ == '__main__':
    args = parseArguments();
    update_trace_file(args.input_file, args.time_offset, args.start_pid, args.max_pid)
