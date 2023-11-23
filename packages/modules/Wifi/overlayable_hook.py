#!/usr/bin/python3

#  Copyright (C) 2022 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

from __future__ import print_function

from argparse import ArgumentParser
import subprocess
import sys

def is_commit_msg_valid(commit_msg):
    for line in commit_msg.splitlines():
        line = line.strip().lower()
        if line.startswith('updated-overlayable'):
            return True

    return False

def is_in_aosp():
    branches = subprocess.check_output(['git', 'branch', '-vv']).splitlines()

    for branch in branches:
        # current branch starts with a '*'
        if branch.startswith(b'*'):
            return b'[aosp/' in branch

    # otherwise assume in AOSP
    return True

def get_changed_resource_file(base_dir, commit_files):
    config_file = base_dir + "values/config.xml"
    string_file = base_dir + "values/strings.xml"
    styles_file = base_dir + "values/styles.xml"
    drawable_dir = base_dir + "drawable/"
    layout_dir = base_dir + "layout/"

    for commit_file in commit_files:
        if commit_file == config_file:
            return commit_file
        if commit_file == string_file:
            return commit_file
        if commit_file == styles_file:
            return commit_file
        if commit_file.startswith(drawable_dir):
            return commit_file
        if commit_file.startswith(layout_dir):
            return commit_file
    return None




def main():
    parser = ArgumentParser(description='Check if the overlayable file has been updated')
    parser.add_argument('commit_msg', type=str, help='commit message')
    parser.add_argument('commit_files', type=str, nargs='*', help='files changed in the commit')
    args = parser.parse_args()

    base_dir = "service/ServiceWifiResources/res/"
    overlay_file = base_dir + "values/overlayable.xml"
    commit_msg = args.commit_msg
    commit_files = args.commit_files

    if is_in_aosp():
        return 0

    changed_file = get_changed_resource_file(base_dir, commit_files)

    if not changed_file:
        return 0

    if is_commit_msg_valid(commit_msg):
        return 0

    print('This commit has changed: "{changed_file}".'.format(changed_file=changed_file))
    print()
    print('If this change added/changed/removed overlayable resources used by the Wifi Module, ')
    print('please update the "{overlay_file}".'.format(overlay_file=overlay_file))
    print('and acknowledge you have done so by adding this line to your commit message:')
    print()
    print('Updated-Overlayable: TRUE')
    print()
    print('Otherwise, please explain why the Overlayable does not need to be updated:')
    print()
    print('Updated-Overlayable: Not applicable - changing default value')
    print()
    return 1


if __name__ == '__main__':
    exit_code = main()
    sys.exit(exit_code)