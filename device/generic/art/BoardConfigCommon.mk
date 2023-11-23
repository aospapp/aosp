#
# Copyright (C) 2019 The Android Open-Source Project
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

# Configuration elements common to all ART generic device BoardConfig.mk files.

TARGET_NO_BOOTLOADER := true
TARGET_NO_KERNEL := true

TARGET_CPU_SMP := true

# Flatten APEX packages to make them simpler to use in
# ART testing/benchmarking environments.
TARGET_FLATTEN_APEX := true
