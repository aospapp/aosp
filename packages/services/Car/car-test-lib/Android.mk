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
#

#disble build in PDK, missing aidl import breaks build
ifneq ($(TARGET_BUILD_PDK),true)

include $(CLEAR_VARS)

ifeq ($(BOARD_IS_AUTOMOTIVE), true)
full_classes_jar := $(call intermediates-dir-for,JAVA_LIBRARIES,android.car.testapi,,COMMON)/classes.jar
$(call dist-for-goals,dist_files,$(full_classes_jar):android.car.testapi.jar)
endif

endif #TARGET_BUILD_PDK
