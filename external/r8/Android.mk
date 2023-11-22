LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_PREBUILT_STATIC_JAVA_LIBRARIES := \
    d8-support-asm:deps/asm-5.1.jar \
    d8-support-asm-common:deps/asm-commons-5.1.jar \
    d8-support-asm-tree:deps/asm-tree-5.1.jar \
    d8-support-asm-util:deps/asm-util-5.1.jar \
    d8-support-common-compress:deps/commons-compress-1.12.jar \
    d8-support-fastutil:deps/fastutil-7.2.0.jar \
    d8-support-jopt:deps/jopt-simple-4.6.jar
include $(BUILD_HOST_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := d8
LOCAL_JAR_MANIFEST := manifest.txt
LOCAL_SRC_FILES := $(call all-java-files-under,src/main)
LOCAL_STATIC_JAVA_LIBRARIES := \
    d8-support-asm \
    d8-support-asm-common \
    d8-support-asm-tree \
    d8-support-asm-util \
    d8-support-common-compress \
    d8-support-fastutil \
    d8-support-jopt \
    guavalib 
include $(BUILD_HOST_JAVA_LIBRARY)
