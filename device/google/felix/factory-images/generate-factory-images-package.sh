#!/bin/sh

# Copyright 2023 The Android Open Source Project
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

source ../../../common/clear-factory-images-variables.sh
BUILD=9548499
DEVICE=felix
PRODUCT=felix
VERSION=td3a.230201.001
SRCPREFIX=signed-
BOOTLOADER=felix-1.0-9477737
RADIO=g5300n-230112-230118-b-9502391
source ../../../common/generate-factory-images-common.sh
