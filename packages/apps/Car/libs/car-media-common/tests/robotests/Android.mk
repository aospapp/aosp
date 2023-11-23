LOCAL_PATH := $(call my-dir)

############################################################
# CarMediaCommon app just for Robolectric test target.     #
############################################################
include $(CLEAR_VARS)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_PACKAGE_NAME := CarMediaCommon
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_MODULE_TAGS := optional

LOCAL_USE_AAPT2 := true

LOCAL_PRIVILEGED_MODULE := true

LOCAL_STATIC_ANDROID_LIBRARIES := \
    car-arch-common \
    car-media-common

include $(BUILD_PACKAGE)

################################################
# Car Media Common Robolectric test target. #
################################################
include $(CLEAR_VARS)

LOCAL_MODULE := CarMediaCommonRoboTests

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_RESOURCE_DIRS := config

# Include the testing libraries
LOCAL_JAVA_LIBRARIES := \
    android.car \
    androidx.arch.core_core-runtime \
    androidx.arch.core_core-common \
    robolectric_android-all-stub \
    Robolectric_all-target \
    mockito-robolectric-prebuilt \
    truth-prebuilt


LOCAL_INSTRUMENTATION_FOR := CarMediaCommon

LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_JAVA_LIBRARY)

##################################################################
# Car Media Common runner target to run the previous target. #
##################################################################
include $(CLEAR_VARS)

LOCAL_MODULE := RunCarMediaCommonRoboTests

LOCAL_JAVA_LIBRARIES := \
    android.car \
    CarMediaCommonRoboTests \
    robolectric_android-all-stub \
    Robolectric_all-target \
    mockito-robolectric-prebuilt \
    truth-prebuilt

LOCAL_TEST_PACKAGE := CarMediaCommon

LOCAL_ROBOTEST_FILES := $(filter-out %/BaseRobolectricTest.java,\
    $(call find-files-in-subdirs,$(LOCAL_PATH)/src,*Test.java,.))

LOCAL_INSTRUMENT_SOURCE_DIRS := $(dir $(LOCAL_PATH))../src

include external/robolectric-shadows/run_robotests.mk
