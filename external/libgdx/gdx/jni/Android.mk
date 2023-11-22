LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := libgdx
LOCAL_C_INCLUDES :=

# Ignore warnings until they are fixed upstream. See https://github.com/libgdx/libgdx/issues/4169
LOCAL_CFLAGS := -O2 -Wall -Wno-unused-parameter -Wno-unused-variable -Wno-unused-function \
        -Wno-sign-compare -Wno-shift-negative-value -Wno-pointer-arith -Wno-invalid-noreturn
LOCAL_LDLIBS := -lm -lGLESv2 -llog
LOCAL_ARM_MODE  := arm
LOCAL_SDK_VERSION := 23

LOCAL_SRC_FILES := android/AndroidGL20.cpp\
       com.badlogic.gdx.graphics.g2d.Gdx2DPixmap.cpp\
       com.badlogic.gdx.graphics.glutils.ETC1.cpp\
       com.badlogic.gdx.math.Matrix4.cpp\
       com.badlogic.gdx.utils.BufferUtils.cpp\
       etc1/etc1_utils.cpp\
       gdx2d/gdx2d.c\
       gdx2d/jpgd.cpp\
       gdx2d/jpgd_c.cpp\
       memcpy_wrap.c

include $(BUILD_SHARED_LIBRARY)
