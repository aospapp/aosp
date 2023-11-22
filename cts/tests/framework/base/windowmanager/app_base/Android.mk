LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

# Don't include this package in any target.
LOCAL_MODULE_TAGS := tests

LOCAL_STATIC_JAVA_LIBRARIES := \
    androidx.test.rules \
    cts-wm-util \

LOCAL_SRC_FILES := \
    $(call all-java-files-under, src) \

LOCAL_MODULE := cts-wm-app-base

LOCAL_SDK_VERSION := test_current

include $(BUILD_STATIC_JAVA_LIBRARY)
