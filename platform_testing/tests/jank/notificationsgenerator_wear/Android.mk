LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_SDK_VERSION := current

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_PACKAGE_NAME := NotificationsGeneratorWear

LOCAL_STATIC_JAVA_LIBRARIES := \
    android-support-v4

LOCAL_CERTIFICATE := vendor/unbundled_google/libraries/certs/clockwork

LOCAL_PROGUARD_FLAG_FILES := proguard.flags

include $(BUILD_PACKAGE)