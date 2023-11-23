LOCAL_PATH := $(call my-dir)

############################################################
# CarAppsCommon app just for Robolectric test target.     #
############################################################
include $(CLEAR_VARS)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_PACKAGE_NAME := CarAppsCommon
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_MODULE_TAGS := optional

LOCAL_USE_AAPT2 := true

LOCAL_PRIVILEGED_MODULE := true

LOCAL_JAVA_LIBRARIES := android.car

LOCAL_STATIC_ANDROID_LIBRARIES := \
    car-apps-common

include $(BUILD_PACKAGE)

################################################
# Car Apps Common Robolectric test target. #
################################################
include $(CLEAR_VARS)

LOCAL_MODULE := CarAppsCommonRoboTests

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_RESOURCE_DIRS := config

# Include the testing libraries
LOCAL_JAVA_LIBRARIES := \
    android.car \
    robolectric_android-all-stub \
    Robolectric_all-target \
    mockito-robolectric-prebuilt \
    truth-prebuilt


LOCAL_INSTRUMENTATION_FOR := CarAppsCommon

LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_JAVA_LIBRARY)

##################################################################
# Car Apps Common runner target to run the previous target. #
##################################################################
include $(CLEAR_VARS)

LOCAL_MODULE := RunCarAppsCommonRoboTests

LOCAL_JAVA_LIBRARIES := \
    android.car \
    CarAppsCommonRoboTests \
    robolectric_android-all-stub \
    Robolectric_all-target \
    mockito-robolectric-prebuilt \
    truth-prebuilt

LOCAL_TEST_PACKAGE := CarAppsCommon

LOCAL_ROBOTEST_FILES := $(filter-out %/BaseRobolectricTest.java,\
    $(call find-files-in-subdirs,$(LOCAL_PATH)/src,*Test.java,.))

LOCAL_INSTRUMENT_SOURCE_DIRS := $(dir $(LOCAL_PATH))../src

include external/robolectric-shadows/run_robotests.mk
