LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := managedprovisioning_protoslite
LOCAL_SRC_FILES := $(call all-proto-files-under, proto)
LOCAL_PROTOC_OPTIMIZE_TYPE := lite
LOCAL_MODULE_TAGS := optional
include $(BUILD_STATIC_JAVA_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_JAVA_LIBRARIES += android.car

LOCAL_STATIC_ANDROID_LIBRARIES += \
        androidx.legacy_legacy-support-v4 \
        car-ui-lib \
        setupcompat \
        setupdesign

LOCAL_STATIC_LIBRARIES := devicepolicyprotosnano
LOCAL_STATIC_JAVA_LIBRARIES := managedprovisioning_protoslite

LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_SRC_FILES += $(call all-Iaidl-files-under, src)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_USE_AAPT2 := true

LOCAL_PACKAGE_NAME := ManagedProvisioning
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true
LOCAL_PROGUARD_FLAG_FILES := proguard.flags

# Packages to be included in code coverage runs. This does not affect production builds.
LOCAL_JACK_COVERAGE_INCLUDE_FILTER := com.android.managedprovisioning.*

include $(BUILD_PACKAGE)

# additionally, build tests if we build via mmm / mm
ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call all-makefiles-under,$(LOCAL_PATH))
endif
