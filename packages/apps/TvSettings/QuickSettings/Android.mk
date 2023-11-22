LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := TvQuickSettings

LOCAL_MODULE_TAGS := optional
LOCAL_SDK_VERSION := current
LOCAL_PROGUARD_FLAG_FILES := proguard.cfg

LOCAL_SRC_FILES := \
    $(call all-java-files-under, src)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_STATIC_ANDROID_LIBRARIES := \
    androidx.legacy_legacy-support-v4 \
    androidx.recyclerview_recyclerview \
    androidx.preference_preference \
    androidx.appcompat_appcompat \
    androidx.legacy_legacy-preference-v14 \
    androidx.leanback_leanback-preference \
    androidx.leanback_leanback

LOCAL_USE_AAPT2 := true

LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true

include $(BUILD_PACKAGE)
