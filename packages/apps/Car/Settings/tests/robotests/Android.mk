#############################################################
# Car Settings Robolectric test target.                     #
#############################################################
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := CarSettingsRoboTests
LOCAL_MODULE_CLASS := JAVA_LIBRARIES

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_RESOURCE_DIRS := config

LOCAL_JAVA_LIBRARIES := \
    Robolectric_all-target \
    robolectric_android-all-stub \
    mockito-robolectric-prebuilt \
    truth-prebuilt \
    testng \
    android.car

LOCAL_INSTRUMENTATION_FOR := CarSettingsForTesting

LOCAL_MODULE_TAGS := optional

# Generate test_config.properties
include external/robolectric-shadows/gen_test_config.mk

include $(BUILD_STATIC_JAVA_LIBRARY)

#############################################################
# Car Settings runner target to run the previous target.    #
#############################################################
include $(CLEAR_VARS)

LOCAL_MODULE := RunCarSettingsRoboTests

LOCAL_JAVA_LIBRARIES := \
    Robolectric_all-target \
    robolectric_android-all-stub \
    mockito-robolectric-prebuilt \
    truth-prebuilt \
    testng \
    CarSettingsRoboTests \
    android.car

LOCAL_TEST_PACKAGE := CarSettingsForTesting

LOCAL_INSTRUMENT_SOURCE_DIRS := $(dir $(LOCAL_PATH))../src

include external/robolectric-shadows/run_robotests.mk
