#
# Copyright (C) 2018 The Android Open Source Project
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

include $(CLEAR_VARS)

LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_MODULE := car-glide-disklrucache
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := ../../../../../prebuilts/maven_repo/bumptech/com/github/bumptech/glide/disklrucache/SNAPSHOT/disklrucache-SNAPSHOT$(COMMON_JAVA_PACKAGE_SUFFIX)
LOCAL_NOTICE_FILE := prebuilts/maven_repo/bumptech/LICENSE
LOCAL_JETIFIER_ENABLED := true
LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_PREBUILT)

include $(CLEAR_VARS)

LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_MODULE := car-gifdecoder
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := ../../../../../prebuilts/maven_repo/bumptech/com/github/bumptech/glide/gifdecoder/SNAPSHOT/gifdecoder-SNAPSHOT$(COMMON_JAVA_PACKAGE_SUFFIX)
LOCAL_NOTICE_FILE := prebuilts/maven_repo/bumptech/LICENSE
LOCAL_JETIFIER_ENABLED := true
LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_PREBUILT)

include $(CLEAR_VARS)

LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_MODULE := car-glide
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := ../../../../../prebuilts/maven_repo/bumptech/com/github/bumptech/glide/glide/SNAPSHOT/glide-SNAPSHOT$(COMMON_JAVA_PACKAGE_SUFFIX)
LOCAL_NOTICE_FILE := prebuilts/maven_repo/bumptech/LICENSE
LOCAL_JETIFIER_ENABLED := true
LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_PREBUILT)