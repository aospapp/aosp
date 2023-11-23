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
# TODO(b/267675642): this is the sample code shared by QNX to convert traceprinter
# output to json file. We will clean it up and improve it as we make changes.
# For example:
# Using stdlib to do the JSON conversion.
# Add helper function for timestamp conversion.
#
# Usage:
# python3 qnx_perfetto.py qnx_traceprinter_output_file qnx_trace.json
#
#
import sys
import re

trace_input_regex = re.compile( r"(?P<CPU>\d+) (?P<time_s>\d+)\.(?P<time_ns>\d+)\.(?P<time_us>\d+)us (?P<etype>\w*) (?P<ename>\w*).*?pid:(?P<pid>\-?\d+)" )

def main():
    if len( sys.argv ) < 2:
        print(f'Missing trace output path argument. Correct usage: {sys.argv[0]} <trace_output_path>')
        sys.exit(1)

    first_line_written = False
    with open( sys.argv[1], 'r' ) as trace_input:
        with open( '{}.json'.format( sys.argv[1] ), 'w' ) as converted_output:
            converted_output.write( '{\n  "traceEvents": [' )
            trace_lines = trace_input.readlines()
            for (index, line) in enumerate( trace_lines ):
                match = trace_input_regex.match(line)
                if match != None:
                    line_output = '"name": "{}", "cat": "{}", "ph": "i", "ts": {}, "pid": {}, "s": "g"'.format(
                        match.group( 'ename' ),
                        match.group( 'etype' ),
                        (int(match.group( 'time_s' )) * 1000000) + (int(match.group( 'time_ns' )) * 1000) + int(match.group( 'time_us' )),
                        int(match.group( 'pid' ))
                    )

                    if first_line_written  and index + 1 <= len( trace_lines ):
                        converted_output.write( ',')
                    converted_output.write( "\n    { " + line_output + " }" )
                    first_line_written = True
            converted_output.write( '\n]}\n' )

if __name__ == '__main__':
    main()
