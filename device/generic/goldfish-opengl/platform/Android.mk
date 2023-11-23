ifeq (true,$(GOLDFISH_OPENGL_BUILD_FOR_HOST))

LOCAL_PATH := $(call my-dir)

$(call emugl-begin-static-library,libplatform$(GOLDFISH_OPENGL_LIB_SUFFIX))

sources := stub/VirtGpuBlob.cpp \
           stub/VirtGpuBlobMapping.cpp \
           stub/VirtGpuDevice.cpp \

LOCAL_SRC_FILES := $(sources)
$(call emugl-export,C_INCLUDES,$(LOCAL_PATH)/include)
$(call emugl-end-module)

endif
