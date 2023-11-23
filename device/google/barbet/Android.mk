#
# Copyright 2018 The Android Open Source Project
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

$(eval $(call declare-copy-files-license-metadata,device/google/barbet,default-permissions.xml,SPDX-license-identifier-Apache-2.0,notice,build/soong/licenses/LICENSE,))
$(eval $(call declare-copy-files-license-metadata,device/google/barbet,libnfc-nci.conf,SPDX-license-identifier-Apache-2.0,notice,build/soong/licenses/LICENSE,))
$(eval $(call declare-copy-files-license-metadata,device/google/barbet,fstab.postinstall,SPDX-license-identifier-Apache-2.0,notice,build/soong/licenses/LICENSE,))
$(eval $(call declare-copy-files-license-metadata,device/google/barbet,ueventd.rc,SPDX-license-identifier-Apache-2.0,notice,build/soong/licenses/LICENSE,))
$(eval $(call declare-copy-files-license-metadata,device/google/barbet,wpa_supplicant.conf,SPDX-license-identifier-Apache-2.0,notice,build/soong/licenses/LICENSE,))
$(eval $(call declare-copy-files-license-metadata,device/google/barbet,hals.conf,SPDX-license-identifier-Apache-2.0,notice,build/soong/licenses/LICENSE,))
$(eval $(call declare-copy-files-license-metadata,device/google/barbet,media_profiles_V1_0.xml,SPDX-license-identifier-Apache-2.0,notice,build/soong/licenses/LICENSE,))
$(eval $(call declare-copy-files-license-metadata,device/google/barbet,media_codecs_performance.xml,SPDX-license-identifier-Apache-2.0,notice,build/soong/licenses/LICENSE,))
$(eval $(call declare-copy-files-license-metadata,device/google/barbet,device_state_configuration.xml,SPDX-license-identifier-Apache-2.0,notice,build/soong/licenses/LICENSE,))
$(eval $(call declare-copy-files-license-metadata,device/google/barbet,task_profiles.json,SPDX-license-identifier-Apache-2.0,notice,build/soong/licenses/LICENSE,))
$(eval $(call declare-copy-files-license-metadata,device/google/barbet,p2p_supplicant.conf,SPDX-license-identifier-Apache-2.0,notice,build/soong/licenses/LICENSE,))
$(eval $(call declare-copy-files-license-metadata,device/google/barbet,wpa_supplicant.conf,SPDX-license-identifier-Apache-2.0,notice,build/soong/licenses/LICENSE,))
$(eval $(call declare-copy-files-license-metadata,device/google/barbet,wpa_supplicant_overlay.conf,SPDX-license-identifier-Apache-2.0,notice,build/soong/licenses/LICENSE,))

$(eval $(call declare-1p-copy-files,device/google/barbet,audio_policy_configuration.xml))

ifeq ($(USES_DEVICE_GOOGLE_BARBET),true)
  subdir_makefiles=$(call first-makefiles-under,$(LOCAL_PATH))
  $(foreach mk,$(subdir_makefiles),$(info including $(mk) ...)$(eval include $(mk)))
endif
