LOCAL_PATH := $(call my-dir)
################################################################################
# libxtensa_proxy library building
################################################################################

include $(CLEAR_VARS)
LOCAL_VENDOR_MODULE := true
common_C_INCLUDES := 	\
        $(LOCAL_PATH)/include	\
        $(LOCAL_PATH)/include/audio		\
        $(LOCAL_PATH)/include/os/android	\
        $(LOCAL_PATH)/include/sys/fio  \
        $(LOCAL_PATH)/playback	\
        $(LOCAL_PATH)/playback/tinyalsa	\
        $(LOCAL_PATH)/utest/include

LOCAL_SRC_FILES := 		\
	proxy/xf-proxy.c	\
	proxy/xaf-api.c         \
	proxy/xf-trace.c	\
	proxy/xf-fio.c 		\
	playback/xa_playback.c  \
	playback/tinyalsa/pcm.c \
	utest/xaf-utils-test.c	\
	utest/xaf-mem-test.c

C_FLAGS := -DXF_TRACE=0 -Wall -Werror -Wno-everything

LOCAL_SHARED_LIBRARIES := liblog
LOCAL_C_INCLUDES := $(common_C_INCLUDES)
LOCAL_C_INCLUDES += external/expat/lib
LOCAL_CFLAGS := $(C_FLAGS)
LOCAL_MODULE := libxtensa_proxy
LOCAL_LICENSE_KINDS := SPDX-license-identifier-MIT
LOCAL_LICENSE_CONDITIONS := notice
LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_LIBRARY)

################################################################################
# xaf-dec-test: fileinput->ogg/pcm decoder->speaker output
################################################################################
include $(CLEAR_VARS)
LOCAL_VENDOR_MODULE := true
LOCAL_MODULE := xaf-dec-test
LOCAL_LICENSE_KINDS := SPDX-license-identifier-MIT
LOCAL_LICENSE_CONDITIONS := notice

LOCAL_SRC_FILES := \
    utest/xaf-dec-test.c

LOCAL_C_INCLUDES := $(common_C_INCLUDES)
LOCAL_CFLAGS := $(C_FLAGS)
LOCAL_STATIC_LIBRARIES := libxtensa_proxy
LOCAL_SHARED_LIBRARIES := liblog libcutils
LOCAL_MODULE_TAGS := optional
include $(BUILD_EXECUTABLE)

################################################################################
# xaf-dec-mix-test: fileinput->ogg orpcm decoder->Mixer->speaker output
################################################################################
include $(CLEAR_VARS)
LOCAL_VENDOR_MODULE := true
LOCAL_MODULE := xaf-dec-mix-test
LOCAL_LICENSE_KINDS := SPDX-license-identifier-MIT
LOCAL_LICENSE_CONDITIONS := notice

LOCAL_SRC_FILES := \
    utest/xaf-dec-mix-test.c

LOCAL_C_INCLUDES := $(common_C_INCLUDES)
LOCAL_CFLAGS := $(C_FLAGS)
LOCAL_STATIC_LIBRARIES := libxtensa_proxy
LOCAL_SHARED_LIBRARIES := liblog libcutils
LOCAL_MODULE_TAGS := optional
include $(BUILD_EXECUTABLE)
