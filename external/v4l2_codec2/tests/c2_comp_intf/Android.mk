# Build the unit tests.
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk

LOCAL_MODULE := C2VDACompIntf_test

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := \
  C2CompIntfTest.cpp \
  C2VDACompIntf_test.cpp \

LOCAL_SHARED_LIBRARIES := \
  libchrome \
  libcodec2 \
  libcodec2_vndk \
  libcutils \
  liblog \
  libutils \
  libv4l2_codec2 \
  libv4l2_codec2_accel \

LOCAL_C_INCLUDES += \
  $(TOP)/external/v4l2_codec2/accel \
  $(TOP)/external/v4l2_codec2/common/include \
  $(TOP)/external/v4l2_codec2/include \
  $(TOP)/frameworks/av/media/codec2/components/base/include \
  $(TOP)/frameworks/av/media/codec2/core/include \
  $(TOP)/frameworks/av/media/codec2/vndk/include \
  $(TOP)/vendor/google_arc/libs/codec2/vdastore/include \

LOCAL_CFLAGS += -Werror -Wall
LOCAL_CLANG := true

LOCAL_LDFLAGS := -Wl,-Bsymbolic

include $(BUILD_NATIVE_TEST)


include $(CLEAR_VARS)
LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk

LOCAL_MODULE := C2VEACompIntf_test

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := \
  C2CompIntfTest.cpp \
  C2VEACompIntf_test.cpp \

LOCAL_SHARED_LIBRARIES := \
  libchrome \
  libcodec2 \
  libcodec2_vndk \
  libcutils \
  liblog \
  libui \
  libutils \
  libv4l2_codec2 \
  libv4l2_codec2_accel \

LOCAL_C_INCLUDES += \
  $(TOP)/external/v4l2_codec2/accel \
  $(TOP)/external/v4l2_codec2/common/include \
  $(TOP)/external/v4l2_codec2/include \
  $(TOP)/frameworks/av/media/codec2/components/base/include \
  $(TOP)/frameworks/av/media/codec2/core/include \
  $(TOP)/frameworks/av/media/codec2/vndk/include \

LOCAL_CFLAGS += -Werror -Wall
LOCAL_CLANG := true

LOCAL_LDFLAGS := -Wl,-Bsymbolic

include $(BUILD_NATIVE_TEST)


include $(CLEAR_VARS)
LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk

LOCAL_MODULE := C2VDAComponent_test

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := \
  C2VDAComponent_test.cpp \

LOCAL_SHARED_LIBRARIES := \
  android.hardware.media.bufferpool@2.0 \
  libchrome \
  libcodec2 \
  libcodec2_vndk \
  libcutils \
  libdatasource \
  liblog \
  libmedia \
  libstagefright \
  libstagefright_foundation \
  libutils \
  libv4l2_codec2 \
  libv4l2_codec2_accel \

LOCAL_C_INCLUDES += \
  $(TOP)/external/v4l2_codec2/accel \
  $(TOP)/external/v4l2_codec2/common/include \
  $(TOP)/external/v4l2_codec2/include \
  $(TOP)/frameworks/av/media/codec2/components/base/include \
  $(TOP)/frameworks/av/media/codec2/core/include \
  $(TOP)/frameworks/av/media/codec2/vndk/include \
  $(TOP)/frameworks/av/media/libstagefright/include \
  $(TOP)/vendor/google_arc/libs/codec2/vdastore/include \

# -Wno-unused-parameter is needed for libchrome/base codes
LOCAL_CFLAGS += -Werror -Wall -Wno-unused-parameter
LOCAL_CLANG := true

LOCAL_LDFLAGS := -Wl,-Bsymbolic

include $(BUILD_NATIVE_TEST)
