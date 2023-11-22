LOCAL_PATH:= $(call my-dir)

# MAP API module

include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, lib/mapapi)
LOCAL_MODULE := bluetooth.mapsapi
include $(BUILD_STATIC_JAVA_LIBRARY)

# Bluetooth APK

include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_PACKAGE_NAME := Bluetooth
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_CERTIFICATE := platform
LOCAL_USE_AAPT2 := true
LOCAL_JNI_SHARED_LIBRARIES := libbluetooth_jni
LOCAL_JAVA_LIBRARIES := javax.obex telephony-common services.net
LOCAL_STATIC_JAVA_LIBRARIES := \
        com.android.vcard \
        bluetooth.mapsapi \
        sap-api-java-static \
        services.net \
        libprotobuf-java-lite \
        bluetooth-protos-lite \

LOCAL_STATIC_ANDROID_LIBRARIES := \
        androidx.core_core \
        androidx.lifecycle_lifecycle-livedata \
        androidx.room_room-runtime \

LOCAL_ANNOTATION_PROCESSORS := \
        bt-androidx-annotation-nodeps \
        bt-androidx-room-common-nodeps \
        bt-androidx-room-compiler-nodeps \
        bt-androidx-room-migration-nodeps \
        bt-antlr4-nodeps \
        bt-apache-commons-codec-nodeps \
        bt-auto-common-nodeps \
        bt-javapoet-nodeps \
        bt-kotlin-metadata-nodeps \
        bt-sqlite-jdbc-nodeps \
        bt-jetbrain-nodeps \
        guava-21.0 \
        kotlin-stdlib

LOCAL_ANNOTATION_PROCESSOR_CLASSES := \
        androidx.room.RoomProcessor

LOCAL_REQUIRED_MODULES := libbluetooth
LOCAL_PROGUARD_ENABLED := disabled
include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))

include $(CLEAR_VARS)

COMMON_LIBS_PATH := ../../../../../prebuilts/tools/common/m2/repository
ROOM_LIBS_PATH := ../../lib/room

LOCAL_PREBUILT_STATIC_JAVA_LIBRARIES := \
        bt-androidx-annotation-nodeps:$(ROOM_LIBS_PATH)/annotation-1.0.0-beta01.jar \
        bt-androidx-room-common-nodeps:$(ROOM_LIBS_PATH)/room-common-2.0.0-beta01.jar \
        bt-androidx-room-compiler-nodeps:$(ROOM_LIBS_PATH)/room-compiler-2.0.0-beta01.jar \
        bt-androidx-room-migration-nodeps:$(ROOM_LIBS_PATH)/room-migration-2.0.0-beta01.jar \
        bt-antlr4-nodeps:$(COMMON_LIBS_PATH)/org/antlr/antlr4/4.5.3/antlr4-4.5.3.jar \
        bt-apache-commons-codec-nodeps:$(COMMON_LIBS_PATH)/org/eclipse/tycho/tycho-bundles-external/0.18.1/eclipse/plugins/org.apache.commons.codec_1.4.0.v201209201156.jar \
        bt-auto-common-nodeps:$(COMMON_LIBS_PATH)/com/google/auto/auto-common/0.9/auto-common-0.9.jar \
        bt-javapoet-nodeps:$(COMMON_LIBS_PATH)/com/squareup/javapoet/1.8.0/javapoet-1.8.0.jar \
        bt-kotlin-metadata-nodeps:$(COMMON_LIBS_PATH)/me/eugeniomarletti/kotlin-metadata/1.2.1/kotlin-metadata-1.2.1.jar \
        bt-sqlite-jdbc-nodeps:$(COMMON_LIBS_PATH)/org/xerial/sqlite-jdbc/3.20.1/sqlite-jdbc-3.20.1.jar \
        bt-jetbrain-nodeps:../../../../../prebuilts/tools/common/m2/repository/org/jetbrains/annotations/13.0/annotations-13.0.jar

include $(BUILD_HOST_PREBUILT)
