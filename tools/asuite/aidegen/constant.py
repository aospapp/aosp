#!/usr/bin/env python3
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
"""The common definitions of AIDEgen"""

import os

from atest import constants

# Env constant
OUT_DIR_COMMON_BASE_ENV_VAR = 'OUT_DIR_COMMON_BASE'
ANDROID_DEFAULT_OUT = 'out'
ANDROID_HOST_OUT = os.environ.get(constants.ANDROID_HOST_OUT)
ANDROID_ROOT_PATH = os.environ.get(constants.ANDROID_BUILD_TOP)
AIDEGEN_ROOT_PATH = os.path.join(ANDROID_ROOT_PATH, 'tools/asuite/aidegen')
ANDROID_OUT_DIR = os.environ.get(constants.ANDROID_OUT_DIR)
OUT_DIR_COMMON_BASE = os.getenv(OUT_DIR_COMMON_BASE_ENV_VAR)

# Constants for out dir
ANDROID_OUT_DIR_COMMON_BASE = (os.path.join(
    OUT_DIR_COMMON_BASE, os.path.basename(ANDROID_ROOT_PATH))
                               if OUT_DIR_COMMON_BASE else None)
OUT_DIR = ANDROID_OUT_DIR or ANDROID_OUT_DIR_COMMON_BASE or ANDROID_DEFAULT_OUT
SOONG_OUT_DIR_PATH = os.path.join(ANDROID_ROOT_PATH, OUT_DIR, 'soong')
RELATIVE_HOST_OUT = os.path.relpath(ANDROID_HOST_OUT, ANDROID_ROOT_PATH)

# Constants for module's info.
KEY_PATH = 'path'
KEY_DEP = 'dependencies'
KEY_DEPTH = 'depth'

# Constants for IDE util.
IDE_ECLIPSE = 'Eclipse'
IDE_INTELLIJ = 'IntelliJ'
IDE_ANDROID_STUDIO = 'Android Studio'
IDE_NAME_DICT = {'j': IDE_INTELLIJ, 's': IDE_ANDROID_STUDIO, 'e': IDE_ECLIPSE}

# Constants for asuite metrics
EXIT_CODE_EXCEPTION = -1
EXIT_CODE_NORMAL = 0
EXIT_CODE_AIDEGEN_EXCEPTION = 1
AIDEGEN_TOOL_NAME = 'aidegen'
