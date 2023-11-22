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

# The classes needed to handle installation of time zone distros.
include $(CLEAR_VARS)
LOCAL_MODULE := time_zone_distro_installer
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src/main)
LOCAL_JAVA_LIBRARIES := time_zone_distro
include $(BUILD_STATIC_JAVA_LIBRARY)

# Tests for time_zone_distro_installer code
include $(CLEAR_VARS)
LOCAL_MODULE := time_zone_distro_installer-tests
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src/test)
LOCAL_STATIC_JAVA_LIBRARIES := time_zone_distro \
                               time_zone_distro_tools \
                               time_zone_distro_installer \
                               tzdata-testing \
                               junit
include $(BUILD_STATIC_JAVA_LIBRARY)
