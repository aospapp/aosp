#############################################
# Messenger Robolectric test target. #
#############################################
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := DirectRenderingClusterTests

LOCAL_SRC_FILES := $(call all-java-files-under, src)

# Include the testing libraries
LOCAL_JAVA_LIBRARIES := \
    robolectric_android-all-stub \
    Robolectric_all-target \
    mockito-robolectric-prebuilt \
    truth-prebuilt \
    android.car

LOCAL_INSTRUMENTATION_FOR := DirectRenderingCluster

LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_JAVA_LIBRARY)

#############################################################
# Messenger runner target to run the previous target. #
#############################################################
include $(CLEAR_VARS)

LOCAL_MODULE := RunDirectRenderingClusterTests

LOCAL_SDK_VERSION := current

LOCAL_JAVA_LIBRARIES := \
    DirectRenderingClusterTests \
    robolectric_android-all-stub \
    Robolectric_all-target \
    mockito-robolectric-prebuilt \
    truth-prebuilt \
    android.car

LOCAL_TEST_PACKAGE := DirectRenderingCluster

LOCAL_INSTRUMENT_SOURCE_DIRS := $(dir $(LOCAL_PATH))../src

include external/robolectric-shadows/run_robotests.mk
