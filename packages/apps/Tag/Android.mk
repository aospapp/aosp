LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_USE_AAPT2 := true
LOCAL_STATIC_ANDROID_LIBRARIES := \
    androidx.legacy_legacy-support-v4
LOCAL_STATIC_JAVA_LIBRARIES := \
    guava com.android.vcard 

# Only compile source java files in this apk.
LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := Tag
LOCAL_PRIVILEGED_MODULE := true

LOCAL_SDK_VERSION := current

include $(BUILD_PACKAGE)
