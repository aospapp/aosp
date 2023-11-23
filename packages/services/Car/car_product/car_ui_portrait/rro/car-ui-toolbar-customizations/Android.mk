#
# Copyright (C) 2021 The Android Open-Source Project
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

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

CAR_UI_RRO_SET_NAME := generated_caruiportrait_toolbar
CAR_UI_RRO_MANIFEST_FILE := $(LOCAL_PATH)/AndroidManifest.xml
CAR_UI_RESOURCE_DIR := $(LOCAL_PATH)/res

CAR_UI_RRO_TARGETS := \
    com.android.car.media \
    com.android.car.dialer \

include packages/apps/Car/libs/car-ui-lib/generate_rros.mk
