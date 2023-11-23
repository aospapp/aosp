// Copyright (C) 2018 The Android Open Source Project
// Copyright (C) 2018 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
#pragma once

#include <vulkan/vulkan.h>

#define GOLDFISH_VK_LIST_TRIVIAL_DISPATCHABLE_HANDLE_TYPES(f) \
    f(VkPhysicalDevice) \
    f(VkQueue) \
    f(VkCommandBuffer) \

#define GOLDFISH_VK_LIST_DISPATCHABLE_HANDLE_TYPES(f) \
    f(VkInstance) \
    f(VkDevice) \
    GOLDFISH_VK_LIST_TRIVIAL_DISPATCHABLE_HANDLE_TYPES(f)

#define GOLDFISH_VK_LIST_TRIVIAL_NON_DISPATCHABLE_HANDLE_TYPES(f) \
    f(VkBufferView) \
    f(VkImageView) \
    f(VkShaderModule) \
    f(VkDescriptorPool) \
    f(VkDescriptorSetLayout) \
    f(VkDescriptorSet) \
    f(VkSampler) \
    f(VkPipeline) \
    f(VkPipelineCache) \
    f(VkPipelineLayout) \
    f(VkRenderPass) \
    f(VkFramebuffer) \
    f(VkCommandPool) \
    f(VkFence) \
    f(VkEvent) \
    f(VkQueryPool) \
    f(VkSamplerYcbcrConversion) \
    f(VkSurfaceKHR) \
    f(VkSwapchainKHR) \
    f(VkDisplayKHR) \
    f(VkDisplayModeKHR) \
    f(VkObjectTableNVX) \
    f(VkIndirectCommandsLayoutNVX) \
    f(VkValidationCacheEXT) \
    f(VkDebugReportCallbackEXT) \
    f(VkDebugUtilsMessengerEXT) \

#define GOLDFISH_VK_LIST_NON_DISPATCHABLE_HANDLE_TYPES(f) \
    f(VkDeviceMemory) \
    f(VkBuffer) \
    f(VkImage) \
    f(VkSemaphore) \
    f(VkDescriptorUpdateTemplate) \
    GOLDFISH_VK_LIST_TRIVIAL_NON_DISPATCHABLE_HANDLE_TYPES(f) \

#define GOLDFISH_VK_LIST_HANDLE_TYPES(f) \
    GOLDFISH_VK_LIST_DISPATCHABLE_HANDLE_TYPES(f) \
    GOLDFISH_VK_LIST_NON_DISPATCHABLE_HANDLE_TYPES(f)

#define GOLDFISH_VK_LIST_TRIVIAL_HANDLE_TYPES(f) \
    GOLDFISH_VK_LIST_TRIVIAL_DISPATCHABLE_HANDLE_TYPES(f) \
    GOLDFISH_VK_LIST_TRIVIAL_NON_DISPATCHABLE_HANDLE_TYPES(f)
