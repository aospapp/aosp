LOCAL_PATH := $(call my-dir)

$(call emugl-begin-static-library,libandroidemu)
$(call emugl-export,C_INCLUDES,$(LOCAL_PATH))
$(call emugl-export,SHARED_LIBRARIES,libcutils libutils liblog)

LOCAL_CFLAGS += \
    -DLOG_TAG=\"androidemu\" \
    -Wno-missing-field-initializers \
    -fvisibility=default \
    -fstrict-aliasing \

LOCAL_SRC_FILES := \
    android/base/AlignedBuf.cpp \
    android/base/files/MemStream.cpp \
    android/base/files/Stream.cpp \
    android/base/files/StreamSerializing.cpp \
    android/base/Pool.cpp \
    android/base/StringFormat.cpp \
    android/base/SubAllocator.cpp \
    android/base/Tracing.cpp \
    android/utils/debug.c \

$(call emugl-end-module)
