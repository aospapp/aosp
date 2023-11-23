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
BUILD=9456232
DEVICE=lynx
PRODUCT=lynx
VERSION=td4a.221205.017
SRCPREFIX=signed-
BOOTLOADER=lynx-1.0-9450676
RADIO=g5300n-221222-221226-b-9437664
source ../../../common/generate-factory-images-common.sh
