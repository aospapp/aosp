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

CAR_UI_RRO_SET_NAME := generated_caruiportrait_customization
CAR_UI_RRO_MANIFEST_FILE := $(LOCAL_PATH)/AndroidManifest.xml
CAR_UI_RESOURCE_DIR := $(LOCAL_PATH)/res
CAR_UI_RRO_TARGETS := \
    com.android.car.ui.paintbooth \
    com.google.android.car.ui.paintbooth \
    com.google.android.carui.ats \
    com.android.car.rotaryplayground \
    com.android.car.themeplayground \
    com.android.car.carlauncher \
    com.android.car.home \
    com.android.car.media \
    com.android.car.radio \
    com.android.car.calendar \
    com.android.car.companiondevicesupport \
    com.android.car.systemupdater \
    com.android.car.dialer \
    com.android.car.linkviewer \
    com.android.car.settings \
    com.android.car.voicecontrol \
    com.android.car.faceenroll \
    com.android.car.developeroptions \
    com.android.managedprovisioning \
    com.android.settings.intelligence \
    com.google.android.apps.automotive.inputmethod \
    com.google.android.apps.automotive.inputmethod.dev \
    com.google.android.apps.automotive.templates.host \
    com.google.android.embedded.projection \
    com.google.android.gms \
    com.google.android.gsf \
    com.google.android.packageinstaller \
    com.google.android.permissioncontroller \
    com.google.android.carassistant \
    com.google.android.tts \
    com.android.htmlviewer \
    com.android.vending \

include packages/apps/Car/libs/car-ui-lib/generate_rros.mk
