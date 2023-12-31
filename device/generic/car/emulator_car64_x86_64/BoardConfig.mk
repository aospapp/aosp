# Copyright (C) 2020 The Android Open Source Project
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

BOARD_DO_NOT_STRIP_VENDOR_MODULES := true

# Use emulator64_x86_64_arm64 BoardConfig as base
include device/generic/goldfish/emulator64_x86_64/BoardConfig.mk
include device/generic/car/emulator/usbpt/BoardConfig.mk

# Override BOARD_SUPER_PARTITION_SIZE to inclease the mounted system partition.
BOARD_SUPER_PARTITION_SIZE := 5856296960

# 3.5G
BOARD_EMULATOR_DYNAMIC_PARTITIONS_SIZE = 3758096384
