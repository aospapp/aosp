#!/bin/sh

# Copyright 2022 The Android Open Source Project
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

AOSP_DIR=$(pwd)/../../
export VK_CEREAL_GUEST_ENCODER_DIR=$AOSP_DIR/device/generic/goldfish-opengl/system/vulkan_enc
export VK_CEREAL_GUEST_HAL_DIR=$AOSP_DIR/device/generic/goldfish-opengl/system/vulkan
export VK_CEREAL_HOST_DECODER_DIR=$AOSP_DIR/external/qemu/android/android-emugl/host/libs/libOpenglRender/vulkan

VK_CEREAL_OUTPUT_DIR=$VK_CEREAL_HOST_DECODER_DIR/cereal
mkdir -p $VK_CEREAL_GUEST_HAL_DIR
mkdir -p $VK_CEREAL_GUEST_HAL_DIR
mkdir -p $VK_CEREAL_OUTPUT_DIR

VULKAN_REGISTRY_DIR=$AOSP_DIR/external/gfxstream-protocols/registry/vulkan
VULKAN_REGISTRY_XML_DIR=$VULKAN_REGISTRY_DIR/xml
VULKAN_REGISTRY_SCRIPTS_DIR=$VULKAN_REGISTRY_DIR/scripts

python3 $VULKAN_REGISTRY_SCRIPTS_DIR/genvk.py -registry $VULKAN_REGISTRY_XML_DIR/vk.xml cereal -o $VK_CEREAL_OUTPUT_DIR
