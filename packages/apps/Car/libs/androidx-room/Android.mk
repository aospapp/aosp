#
# Copyright (C) 2019 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_PREBUILT_STATIC_JAVA_LIBRARIES := \
    car-androidx-room-runtime-nodeps:androidx.room/room-runtime-2.0.0-alpha1.aar \
    car-androidx-room-common-nodeps:androidx.room/room-common-2.0.0-alpha1.jar

include $(BUILD_MULTI_PREBUILT)

include $(CLEAR_VARS)

COMMON_LIBS_PATH := ../../../../../prebuilts/tools/common/m2/repository

LOCAL_PREBUILT_STATIC_JAVA_LIBRARIES := \
    car-androidx-room-common-nodeps:androidx.room/room-common-2.0.0-alpha1.jar \
    car-androidx-room-compiler-nodeps:androidx.room/room-compiler-2.0.0-alpha1.jar \
    car-androidx-room-migration-nodeps:androidx.room/room-migration-2.0.0-alpha1.jar \
    car-androidx-annotation-nodeps:annotation-1.0.0-alpha1.jar \
    car-antlr4-nodeps:$(COMMON_LIBS_PATH)/org/antlr/antlr4/4.5.3/antlr4-4.5.3.jar \
    car-apache-commons-codec-nodeps:$(COMMON_LIBS_PATH)/org/eclipse/tycho/tycho-bundles-external/0.18.1/eclipse/plugins/org.apache.commons.codec_1.4.0.v201209201156.jar \
    car-auto-common-nodeps:$(COMMON_LIBS_PATH)/com/google/auto/auto-common/0.9/auto-common-0.9.jar \
    car-javapoet-nodeps:$(COMMON_LIBS_PATH)/com/squareup/javapoet/1.8.0/javapoet-1.8.0.jar \
    car-kotlin-metadata-nodeps:$(COMMON_LIBS_PATH)/me/eugeniomarletti/kotlin-metadata/1.2.1/kotlin-metadata-1.2.1.jar \
    car-sqlite-jdbc-nodeps:$(COMMON_LIBS_PATH)/org/xerial/sqlite-jdbc/3.20.1/sqlite-jdbc-3.20.1.jar

include $(BUILD_HOST_PREBUILT)
