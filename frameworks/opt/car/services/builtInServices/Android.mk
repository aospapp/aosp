LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# Multi-User IMMS is guarded by `BUILD_AUTOMOTIVE_IMMS_PREBUILT`.
# It should be only used in Android Auto Multi-User builds.
# Changes in Android Core IME/IMMS/IMF AIDLs should not be blocked by this module.
ifeq ($(BUILD_AUTOMOTIVE_IMMS_PREBUILT), true)

LOCAL_MODULE := mu_imms
LOCAL_SRC_FILES := $(call all-java-files-under, src_imms)
LOCAL_JAVA_LIBRARIES := services.core.unboosted

# This module should not be built as part of checkbuild
LOCAL_DONT_CHECK_MODULE := true

LOCAL_LICENSE_KINDS := SPDX-license-identifier-Apache-2.0
LOCAL_LICENSE_CONDITIONS := notice
include $(BUILD_STATIC_JAVA_LIBRARY)

endif  # BUILD_AUTOMOTIVE_IMMS_PREBUILT
