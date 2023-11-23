LOCAL_PATH := $(call my-dir)

##############################
include $(CLEAR_VARS)

LOCAL_MODULE := TVLauncherNoGms
LOCAL_LICENSE_KINDS := legacy_restricted
LOCAL_LICENSE_CONDITIONS := restricted
LOCAL_SRC_FILES := TVLauncherNoGms.apk
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := .apk
LOCAL_BUILT_MODULE_STEM := package.apk
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_PRIVILEGED_MODULE := true

include $(BUILD_PREBUILT)
##############################
include $(CLEAR_VARS)

LOCAL_MODULE := TVRecommendationsNoGms
LOCAL_LICENSE_KINDS := legacy_restricted
LOCAL_LICENSE_CONDITIONS := restricted
LOCAL_SRC_FILES := TVRecommendationsNoGms.apk
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := .apk
LOCAL_BUILT_MODULE_STEM := package.apk
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_PRIVILEGED_MODULE := true

include $(BUILD_PREBUILT)
