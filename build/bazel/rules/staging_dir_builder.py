#!/usr/bin/env python3
#
# Copyright (C) 2021 The Android Open Source Project
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

import json
import os
import shutil
import subprocess
import sys
import argparse

def build_staging_dir(file_mapping_path, staging_dir_path, command_argv):
    '''Create a staging dir with provided file mapping and apply the command in the dir.

    At least

    Args:
      file_mapping_path (str): path to the file mapping json
      staging_dir_path (str): path to the staging directory
      command_argv (str list): the command to be executed, with the first arg as the executable
    '''

    try:
        with open(file_mapping_path, 'r') as f:
            file_mapping = json.load(f)
    except OSError as e:
        sys.exit(str(e))
    except json.JSONDecodeError as e:
        sys.exit(file_mapping_path + ": JSON decode error: " + str(e))

    # Validate and clean the file_mapping. This consists of:
    #   - Making sure it's a dict[str, str]
    #   - Normalizing the paths in the staging dir and stripping leading /s
    #   - Making sure there are no duplicate paths in the staging dir
    #   - Making sure no paths use .. to break out of the staging dir
    cleaned_file_mapping = {}
    if not isinstance(file_mapping, dict):
        sys.exit(file_mapping_path + ": expected a JSON dict[str, str]")
    for path_in_staging_dir, path_in_bazel in file_mapping.items():
        if not isinstance(path_in_staging_dir, str) or not isinstance(path_in_bazel, str):
            sys.exit(file_mapping_path + ": expected a JSON dict[str, str]")
        path_in_staging_dir = os.path.normpath(path_in_staging_dir).lstrip('/')
        if path_in_staging_dir in cleaned_file_mapping:
            sys.exit("Staging dir path repeated twice: " + path_in_staging_dir)
        if path_in_staging_dir.startswith('../'):
            sys.exit("Path attempts to break out of staging dir: " + path_in_staging_dir)
        cleaned_file_mapping[path_in_staging_dir] = path_in_bazel
    file_mapping = cleaned_file_mapping

    for path_in_staging_dir, path_in_bazel in file_mapping.items():
        path_in_staging_dir = os.path.join(staging_dir_path, path_in_staging_dir)

        # Because Bazel execution root is a symlink forest, all the input files are symlinks, these
        # include the dependency files declared in the BUILD files as well as the files declared
        # and created in the bzl files. For sandbox runs the former are two or more level symlinks and
        # latter are one level symlinks. For non-sandbox runs, the former are one level symlinks
        # and the latter are actual files. Here are some examples:
        #
        # Two level symlinks:
        # system/timezone/output_data/version/tz_version ->
        # /usr/local/google/home/...out/bazel/output_user_root/b1ed7e1e9af3ebbd1403e9cf794e4884/
        # execroot/__main__/system/timezone/output_data/version/tz_version ->
        # /usr/local/google/home/.../system/timezone/output_data/version/tz_version
        #
        # Three level symlinks:
        # bazel-out/android_x86_64-fastbuild-ST-4ecd5e98bfdd/bin/external/boringssl/libcrypto.so ->
        # /usr/local/google/home/yudiliu/android/aosp/master/out/bazel/output_user_root/b1ed7e1e9af3ebbd1403e9cf794e4884/
        # execroot/__main__/bazel-out/android_x86_64-fastbuild-ST-4ecd5e98bfdd/bin/external/boringssl/libcrypto.so ->
        # /usr/local/google/home/yudiliu/android/aosp/master/out/bazel/output_user_root/b1ed7e1e9af3ebbd1403e9cf794e4884/
        # execroot/__main__/bazel-out/android_x86_64-fastbuild-ST-4ecd5e98bfdd/bin/external/boringssl/
        # liblibcrypto_stripped.so ->
        # /usr/local/google/home/yudiliu/android/aosp/master/out/bazel/output_user_root/b1ed7e1e9af3ebbd1403e9cf794e4884/
        # execroot/__main__/bazel-out/android_x86_64-fastbuild-ST-4ecd5e98bfdd/bin/external/boringssl/
        # liblibcrypto_unstripped.so
        #
        # One level symlinks:
        # bazel-out/android_target-fastbuild/bin/system/timezone/apex/apex_manifest.pb ->
        # /usr/local/google/home/.../out/bazel/output_user_root/b1ed7e1e9af3ebbd1403e9cf794e4884/
        # execroot/__main__/bazel-out/android_target-fastbuild/bin/system/timezone/apex/
        # apex_manifest.pb
        if os.path.islink(path_in_bazel):
            path_in_bazel = os.readlink(path_in_bazel)

            # For sandbox run these are the 2nd level symlinks and we need to resolve
            while os.path.islink(path_in_bazel) and 'execroot/__main__' in path_in_bazel:
                path_in_bazel = os.readlink(path_in_bazel)

        os.makedirs(os.path.dirname(path_in_staging_dir), exist_ok=True)
        # shutil.copy copies the file data and the file's permission mode
        # file's permission mode is helpful for tools, such as build/soong/scripts/gen_ndk_usedby_apex.sh,
        # that rely on the permission mode of the artifacts
        shutil.copy(path_in_bazel, path_in_staging_dir, follow_symlinks=False)

    result = subprocess.run(command_argv)

    sys.exit(result.returncode)

def main():
    '''Build a staging directory, and then call a custom command.

    The first argument to this script must be the path to a file containing a json
    dictionary mapping paths in the staging directory to paths to files that should
    be copied there. The rest of the arguments will be run as a separate command.

    Example:
    staging_dir_builder file_mapping.json path/to/staging_dir path/to/apexer --various-apexer-flags path/to/out.apex.unsigned
    '''
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "file_mapping_path",
        help="Path to the <staging dir path>:<bazel input path> file mapping JSON.",
    )
    parser.add_argument(
        "staging_dir_path",
        help="Path to a directory to store the staging directory content.",
    )
    args, command_argv = parser.parse_known_args()
    build_staging_dir(args.file_mapping_path, args.staging_dir_path, command_argv)

if __name__ == '__main__':
    main()
