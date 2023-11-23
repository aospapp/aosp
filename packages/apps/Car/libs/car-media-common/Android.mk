#
# Copyright (C) 2016 The Android Open Source Project
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

LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_MODULE := car-media-common

LOCAL_MODULE_TAGS := optional

LOCAL_PRIVILEGED_MODULE := true

LOCAL_PROGUARD_ENABLED := disabled

LOCAL_STATIC_ANDROID_LIBRARIES += \
    androidx.cardview_cardview \
    androidx.legacy_legacy-support-v4 \
    androidx.recyclerview_recyclerview \
    androidx.mediarouter_mediarouter \
    androidx-constraintlayout_constraintlayout \
    car-apps-common \
    car-arch-common

LOCAL_STATIC_JAVA_LIBRARIES += \
    car-media-common-glide-target \
    car-media-common-gifdecoder-target \
    car-media-common-disklrucache-target \
    androidx-constraintlayout_constraintlayout-solver

LOCAL_USE_AAPT2 := true

LOCAL_JAVA_LIBRARIES += android.car

include $(BUILD_STATIC_JAVA_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_MODULE := car-media-common-disklrucache-target
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := ../../../../../prebuilts/maven_repo/bumptech/com/github/bumptech/glide/disklrucache/SNAPSHOT/disklrucache-SNAPSHOT$(COMMON_JAVA_PACKAGE_SUFFIX)
LOCAL_JETIFIER_ENABLED := true
LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_PREBUILT)

include $(CLEAR_VARS)

LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_MODULE := car-media-common-gifdecoder-target
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := ../../../../../prebuilts/maven_repo/bumptech/com/github/bumptech/glide/gifdecoder/SNAPSHOT/gifdecoder-SNAPSHOT$(COMMON_JAVA_PACKAGE_SUFFIX)
LOCAL_JETIFIER_ENABLED := true
LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_PREBUILT)

include $(CLEAR_VARS)

LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_MODULE := car-media-common-glide-target
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := ../../../../../prebuilts/maven_repo/bumptech/com/github/bumptech/glide/glide/SNAPSHOT/glide-SNAPSHOT$(COMMON_JAVA_PACKAGE_SUFFIX)
LOCAL_JETIFIER_ENABLED := true
LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_PREBUILT)

ifeq (,$(ONE_SHOT_MAKEFILE))
    include $(call all-makefiles-under,$(LOCAL_PATH))
endif