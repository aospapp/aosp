LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := CarTrustAgentClient

LOCAL_USE_AAPT2 := true
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_STATIC_ANDROID_LIBRARIES := \
    androidx.appcompat_appcompat \
    androidx-constraintlayout_constraintlayout \
    androidx.legacy_legacy-support-v4

LOCAL_CERTIFICATE := platform
LOCAL_MODULE_TAGS := optional
LOCAL_MIN_SDK_VERSION := 23
LOCAL_SDK_VERSION := current

LOCAL_PROGUARD_ENABLED := disabled

LOCAL_DEX_PREOPT := false

include $(BUILD_PACKAGE)
