# Copyright (C) 2015 The Android Open Source Project
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

LOCAL_PATH:= $(call my-dir)

# Library of classes for handling time zone distros. Used on-device for
# handling distros and within CTS tests.
#
# This is distinct from time_zone_distro_unbundled. It should be used
# for platform code as it avoids circular dependencies when stubs targets
# need to build framework (as appears to be the case in aosp/master).
include $(CLEAR_VARS)
LOCAL_MODULE := time_zone_distro
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src/main)
LOCAL_JAVACFLAGS := -encoding UTF-8
LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk
include $(BUILD_STATIC_JAVA_LIBRARY)

# Library of classes for handling time zone distros. Used in unbundled
# cases. Same as above, except dependent on system_current stubs.
include $(CLEAR_VARS)
LOCAL_MODULE := time_zone_distro_unbundled
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src/main)
LOCAL_JAVACFLAGS := -encoding UTF-8
LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk
LOCAL_SDK_VERSION := system_current
include $(BUILD_STATIC_JAVA_LIBRARY)

# Library of classes for handling time zone distros. Used on-device for
# handling distros and within CTS tests. Used on host for host-side tests.
include $(CLEAR_VARS)
LOCAL_MODULE := time_zone_distro-host
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src/main)
LOCAL_JAVACFLAGS := -encoding UTF-8
LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk
LOCAL_SDK_VERSION := system_current
include $(BUILD_HOST_JAVA_LIBRARY)

# Tests for time_zone_distro code.
include $(CLEAR_VARS)
LOCAL_MODULE := time_zone_distro-tests
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src/test)
LOCAL_JAVACFLAGS := -encoding UTF-8
LOCAL_STATIC_JAVA_LIBRARIES := time_zone_distro junit
LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk
include $(BUILD_STATIC_JAVA_LIBRARY)
