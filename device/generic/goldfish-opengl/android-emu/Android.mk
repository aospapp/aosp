ifeq (true,$(GOLDFISH_OPENGL_BUILD_FOR_HOST))
LOCAL_PATH := $(call my-dir)

$(call emugl-begin-shared-library,libandroidemu)
$(call emugl-export,C_INCLUDES,$(LOCAL_PATH))
$(call emugl-export,SHARED_LIBRARIES,libcutils libutils liblog)

LOCAL_CFLAGS += \
    -DLOG_TAG=\"androidemu\" \
    -Wno-missing-field-initializers \
    -fvisibility=default \
    -fstrict-aliasing \

LOCAL_SRC_FILES := \
    aemu/base/AlignedBuf.cpp \
    aemu/base/files/MemStream.cpp \
    aemu/base/files/Stream.cpp \
    aemu/base/files/StreamSerializing.cpp \
    aemu/base/Pool.cpp \
    aemu/base/StringFormat.cpp \
    aemu/base/Process.cpp \
    aemu/base/AndroidSubAllocator.cpp \
    aemu/base/synchronization/AndroidMessageChannel.cpp \
    aemu/base/threads/AndroidFunctorThread.cpp \
    aemu/base/threads/AndroidThreadStore.cpp \
    aemu/base/threads/AndroidThread_pthread.cpp \
    aemu/base/threads/AndroidWorkPool.cpp \
    aemu/base/AndroidHealthMonitor.cpp \
    aemu/base/AndroidHealthMonitorConsumerBasic.cpp \
    aemu/base/Tracing.cpp \
    android/utils/debug.c \

$(call emugl-end-module)

$(call emugl-begin-static-library,libringbuffer)
$(call emugl-export,C_INCLUDES,$(LOCAL_PATH))

LOCAL_SRC_FILES := \
    aemu/base/ring_buffer.c \

$(call emugl-end-module)
endif
