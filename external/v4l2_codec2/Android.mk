# Build only if vendor/google_arc/libs/codec2 is
# visible; otherwise, don't build any target under this repository.
ifneq (,$(findstring vendor/google_arc/libs/codec2,$(PRODUCT_SOONG_NAMESPACES)))

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
        C2EncoderInterface.cpp \
        C2VDAComponent.cpp \
        C2VDAAdaptor.cpp   \

LOCAL_C_INCLUDES += \
        $(TOP)/external/libchrome \
        $(TOP)/external/gtest/include \
        $(TOP)/external/v4l2_codec2/accel \
        $(TOP)/external/v4l2_codec2/common \
        $(TOP)/external/v4l2_codec2/include \
        $(TOP)/frameworks/av/media/codec2/components/base/include \
        $(TOP)/frameworks/av/media/codec2/core/include \
        $(TOP)/frameworks/av/media/codec2/vndk/include \
        $(TOP)/frameworks/av/media/libstagefright/include \

LOCAL_MODULE:= libv4l2_codec2
LOCAL_MODULE_TAGS := optional

LOCAL_SHARED_LIBRARIES := android.hardware.graphics.common@1.0 \
                          libarc_c2componentstore \
                          libbinder \
                          libc2plugin_store \
                          libchrome \
                          libcodec2 \
                          libcodec2_soft_common \
                          libcodec2_vndk \
                          libcutils \
                          liblog \
                          libmedia \
                          libsfplugin_ccodec_utils \
                          libstagefright \
                          libstagefright_bufferqueue_helper \
                          libstagefright_foundation \
                          libui \
                          libutils \
                          libv4l2_codec2_accel \
                          libvda_c2_pixelformat \

LOCAL_STATIC_LIBRARIES := libv4l2_codec2_common \

# -Wno-unused-parameter is needed for libchrome/base codes
LOCAL_CFLAGS += -Werror -Wall -Wno-unused-parameter
LOCAL_CFLAGS += -Wno-unused-lambda-capture -Wno-unknown-warning-option
LOCAL_CLANG := true
LOCAL_SANITIZE := unsigned-integer-overflow signed-integer-overflow

LOCAL_LDFLAGS := -Wl,-Bsymbolic

# Enable input format converter from C2VEAComponent.
LOCAL_CFLAGS += -DUSE_VEA_FORMAT_CONVERTER

# Build C2VDAAdaptorProxy only for ARC++ case.
ifneq (,$(findstring cheets_,$(TARGET_PRODUCT)))
LOCAL_CFLAGS += -DV4L2_CODEC2_ARC
LOCAL_SRC_FILES += \
                   C2VDAAdaptorProxy.cpp \
                   C2VEAAdaptorProxy.cpp \

LOCAL_SRC_FILES := $(filter-out C2VDAAdaptor.cpp, $(LOCAL_SRC_FILES))
LOCAL_SHARED_LIBRARIES += libarcbridge \
                          libarcbridgeservice \
                          libcodec2_arcva_factory \
                          libmojo \

endif # ifneq (,$(findstring cheets_,$(TARGET_PRODUCT)))

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))

endif  #ifneq (,$(findstring vendor/google_arc/libs/codec2,$(PRODUCT_SOONG_NAMESPACES)))
