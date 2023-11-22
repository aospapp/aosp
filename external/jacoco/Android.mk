#
# Copyright (C) 2016 The Android Open Source Project
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
LOCAL_PATH := $(call my-dir)

# Some Jacoco source files depend on classes that do not exist in Android. While these classes are
# not executed at runtime (because we use offline instrumentation), they will cause issues when
# compiling them with ART during dex pre-opting. Therefore, it would prevent from applying code
# coverage on classes in the bootclasspath (frameworks, services, ...) or system apps.
# Note: we still may need to update the source code to cut dependencies in mandatory jacoco classes.
jacoco_android_exclude_list := \
  %org.jacoco.core/src/org/jacoco/core/runtime/ModifiedSystemClassRuntime.java \
  %org.jacoco.agent.rt/src/org/jacoco/agent/rt/internal/PreMain.java \
  %org.jacoco.agent.rt/src/org/jacoco/agent/rt/internal/CoverageTransformer.java \
  %org.jacoco.agent.rt/src/org/jacoco/agent/rt/internal/JmxRegistration.java


# Build jacoco-agent from sources for the platform
#
# Note: this is only intended to be used for the platform development. This is *not* intended
# to be used in the SDK where apps can use the official jacoco release.
include $(CLEAR_VARS)

jacocoagent_src_files := $(call all-java-files-under,org.jacoco.core/src)
jacocoagent_src_files += $(call all-java-files-under,org.jacoco.agent/src)
jacocoagent_src_files += $(call all-java-files-under,org.jacoco.agent.rt/src)

LOCAL_SRC_FILES := $(filter-out $(jacoco_android_exclude_list),$(jacocoagent_src_files))

# In order to include Jacoco in core libraries, we cannot depend on anything in the
# bootclasspath (or we would create dependency cycle). Therefore we compile against
# the SDK android.jar which gives the same APIs Jacoco depends on.
LOCAL_SDK_VERSION := 9

LOCAL_MODULE := jacocoagent
LOCAL_MODULE_TAGS := optional
LOCAL_STATIC_JAVA_LIBRARIES := jacoco-asm
include $(BUILD_STATIC_JAVA_LIBRARY)


# Build jacoco-cli from sources for the platform
include $(CLEAR_VARS)

# TODO(jeffrygaston) it'd be nice to keep the build process and/or list of source files in sync with
# what is defined in the pom.xml files, although it's probably much more trouble than it's worth
jacococli_src_files += $(call all-java-files-under,org.jacoco.core/src)
jacococli_src_files += $(call all-java-files-under,org.jacoco.report/src)
jacococli_src_files += $(call all-java-files-under,org.jacoco.cli/src)
LOCAL_JAVA_RESOURCE_DIRS := org.jacoco.core/src org.jacoco.report/src
LOCAL_JAR_MANIFEST := org.jacoco.cli/src/MANIFEST.MF

LOCAL_SRC_FILES := $(jacococli_src_files)

LOCAL_MODULE := jacoco-cli
LOCAL_STATIC_JAVA_LIBRARIES := jacoco-asm-host args4j-2.0.28

include $(BUILD_HOST_JAVA_LIBRARY)

# include jacoco-cli in the dist directory to enable running it to generate a code-coverage report
ifeq ($(ANDROID_COMPILE_WITH_JACK),false)
ifeq ($(EMMA_INSTRUMENT),true)
$(call dist-for-goals, dist_files, $(LOCAL_INSTALLED_MODULE))
endif
endif


#
# Build asm-5.0.1 as a static library for the device
#
include $(CLEAR_VARS)

LOCAL_MODULE := jacoco-asm
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_SRC_FILES := asm-debug-all-5.0.1$(COMMON_JAVA_PACKAGE_SUFFIX)
# Workaround for b/27319022
LOCAL_JACK_FLAGS := -D jack.import.jar.debug-info=false
LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_PREBUILT)


#
# Build asm-5.0.1 as a static library for the host
#
include $(CLEAR_VARS)

LOCAL_MODULE := jacoco-asm-host
LOCAL_IS_HOST_MODULE := true
LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_SRC_FILES := asm-debug-all-5.0.1$(COMMON_JAVA_PACKAGE_SUFFIX)

include $(BUILD_PREBUILT)
