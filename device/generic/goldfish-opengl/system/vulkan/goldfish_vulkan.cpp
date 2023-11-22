// Copyright (C) 2018 The Android Open Source Project
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
#include <hardware/hwvulkan.h>

#include <log/log.h>

#include <errno.h>
#include <string.h>

#include "HostConnection.h"
#include "ResourceTracker.h"
#include "VkEncoder.h"

#include "func_table.h"

#include <array>
#include <bitset>
#include <mutex>

// Used when there is no Vulkan support on the host.
// Copied from frameworks/native/vulkan/libvulkan/stubhal.cpp
namespace vkstubhal {

const size_t kMaxInstances = 32;
static std::mutex g_instance_mutex;
static std::bitset<kMaxInstances> g_instance_used(false);
static std::array<hwvulkan_dispatch_t, kMaxInstances> g_instances;

[[noreturn]] VKAPI_ATTR void NoOp() {
    LOG_ALWAYS_FATAL("invalid stub function called");
}

VkResult
EnumerateInstanceExtensionProperties(const char* /*layer_name*/,
                                     uint32_t* count,
                                     VkExtensionProperties* /*properties*/) {
    AEMU_SCOPED_TRACE("vkstubhal::EnumerateInstanceExtensionProperties");
    *count = 0;
    return VK_SUCCESS;
}

VkResult
EnumerateInstanceLayerProperties(uint32_t* count,
                                 VkLayerProperties* /*properties*/) {
    AEMU_SCOPED_TRACE("vkstubhal::EnumerateInstanceLayerProperties");
    *count = 0;
    return VK_SUCCESS;
}

VkResult CreateInstance(const VkInstanceCreateInfo* /*create_info*/,
                        const VkAllocationCallbacks* /*allocator*/,
                        VkInstance* instance) {
    AEMU_SCOPED_TRACE("vkstubhal::CreateInstance");
    std::lock_guard<std::mutex> lock(g_instance_mutex);
    for (size_t i = 0; i < kMaxInstances; i++) {
        if (!g_instance_used[i]) {
            g_instance_used[i] = true;
            g_instances[i].magic = HWVULKAN_DISPATCH_MAGIC;
            *instance = reinterpret_cast<VkInstance>(&g_instances[i]);
            return VK_SUCCESS;
        }
    }
    ALOGE("no more instances available (max=%zu)", kMaxInstances);
    return VK_ERROR_INITIALIZATION_FAILED;
}

void DestroyInstance(VkInstance instance,
                     const VkAllocationCallbacks* /*allocator*/) {
    AEMU_SCOPED_TRACE("vkstubhal::DestroyInstance");
    std::lock_guard<std::mutex> lock(g_instance_mutex);
    ssize_t idx =
        reinterpret_cast<hwvulkan_dispatch_t*>(instance) - &g_instances[0];
    ALOG_ASSERT(idx >= 0 && static_cast<size_t>(idx) < g_instance_used.size(),
                "DestroyInstance: invalid instance handle");
    g_instance_used[static_cast<size_t>(idx)] = false;
}

VkResult EnumeratePhysicalDevices(VkInstance /*instance*/,
                                  uint32_t* count,
                                  VkPhysicalDevice* /*gpus*/) {
    AEMU_SCOPED_TRACE("vkstubhal::EnumeratePhysicalDevices");
    *count = 0;
    return VK_SUCCESS;
}

VkResult
EnumeratePhysicalDeviceGroups(VkInstance /*instance*/,
                              uint32_t* count,
                              VkPhysicalDeviceGroupProperties* /*properties*/) {
    AEMU_SCOPED_TRACE("vkstubhal::EnumeratePhysicalDeviceGroups");
    *count = 0;
    return VK_SUCCESS;
}

#ifdef VK_USE_PLATFORM_FUCHSIA
VkResult
GetMemoryZirconHandleFUCHSIA(VkDevice /*device*/,
                             const VkMemoryGetZirconHandleInfoFUCHSIA* /*pInfo*/,
                             uint32_t* pHandle) {
    AEMU_SCOPED_TRACE("vkstubhal::GetMemoryZirconHandleFUCHSIA");
    *pHandle = 0;
    return VK_SUCCESS;
}

VkResult
GetMemoryZirconHandlePropertiesFUCHSIA(VkDevice /*device*/,
                                       VkExternalMemoryHandleTypeFlagBits /*handleType*/,
                                       uint32_t /*handle*/,
                                       VkMemoryZirconHandlePropertiesFUCHSIA* /*pProperties*/) {
    AEMU_SCOPED_TRACE("vkstubhal::GetMemoryZirconHandlePropertiesFUCHSIA");
    return VK_SUCCESS;
}

VkResult
GetSemaphoreZirconHandleFUCHSIA(VkDevice /*device*/,
                                const VkSemaphoreGetZirconHandleInfoFUCHSIA* /*pInfo*/,
                                uint32_t* pHandle) {
    AEMU_SCOPED_TRACE("vkstubhal::GetSemaphoreZirconHandleFUCHSIA");
    *pHandle = 0;
    return VK_SUCCESS;
}

VkResult
ImportSemaphoreZirconHandleFUCHSIA(VkDevice /*device*/,
                                   const VkImportSemaphoreZirconHandleInfoFUCHSIA* /*pInfo*/) {
    AEMU_SCOPED_TRACE("vkstubhal::ImportSemaphoreZirconHandleFUCHSIA");
    return VK_SUCCESS;
}

VkResult
CreateBufferCollectionFUCHSIA(VkDevice /*device*/,
                              const VkBufferCollectionCreateInfoFUCHSIA* /*pInfo*/,
                              const VkAllocationCallbacks* /*pAllocator*/,
                              VkBufferCollectionFUCHSIA* /*pCollection*/) {
    AEMU_SCOPED_TRACE("vkstubhal::CreateBufferCollectionFUCHSIA");
    return VK_SUCCESS;
}

void
DestroyBufferCollectionFUCHSIA(VkDevice /*device*/,
                               VkBufferCollectionFUCHSIA /*collection*/,
                               const VkAllocationCallbacks* /*pAllocator*/) {
    AEMU_SCOPED_TRACE("vkstubhal::DestroyBufferCollectionFUCHSIA");
}

VkResult
SetBufferCollectionConstraintsFUCHSIA(VkDevice /*device*/,
                                      VkBufferCollectionFUCHSIA /*collection*/,
                                      const VkImageCreateInfo* /*pImageInfo*/) {
    AEMU_SCOPED_TRACE("vkstubhal::SetBufferCollectionConstraintsFUCHSIA");
    return VK_SUCCESS;
}

VkResult
GetBufferCollectionPropertiesFUCHSIA(VkDevice /*device*/,
                                     VkBufferCollectionFUCHSIA /*collection*/,
                                     VkBufferCollectionPropertiesFUCHSIA* /*pProperties*/) {
    AEMU_SCOPED_TRACE("vkstubhal::GetBufferCollectionPropertiesFUCHSIA");
    return VK_SUCCESS;
}
#endif

PFN_vkVoidFunction GetInstanceProcAddr(VkInstance instance,
                                       const char* name) {
    AEMU_SCOPED_TRACE("vkstubhal::GetInstanceProcAddr");
    if (strcmp(name, "vkCreateInstance") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(CreateInstance);
    if (strcmp(name, "vkDestroyInstance") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(DestroyInstance);
    if (strcmp(name, "vkEnumerateInstanceExtensionProperties") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(
            EnumerateInstanceExtensionProperties);
    if (strcmp(name, "vkEnumeratePhysicalDevices") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(EnumeratePhysicalDevices);
    if (strcmp(name, "vkEnumeratePhysicalDeviceGroups") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(
            EnumeratePhysicalDeviceGroups);
    if (strcmp(name, "vkGetInstanceProcAddr") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(GetInstanceProcAddr);
#ifdef VK_USE_PLATFORM_FUCHSIA
    if (strcmp(name, "vkGetMemoryZirconHandleFUCHSIA") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(GetMemoryZirconHandleFUCHSIA);
    if (strcmp(name, "vkGetMemoryZirconHandlePropertiesFUCHSIA") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(GetMemoryZirconHandlePropertiesFUCHSIA);
    if (strcmp(name, "vkGetSemaphoreZirconHandleFUCHSIA") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(GetSemaphoreZirconHandleFUCHSIA);
    if (strcmp(name, "vkImportSemaphoreZirconHandleFUCHSIA") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(ImportSemaphoreZirconHandleFUCHSIA);
    if (strcmp(name, "vkCreateBufferCollectionFUCHSIA") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(CreateBufferCollectionFUCHSIA);
    if (strcmp(name, "vkDestroyBufferCollectionFUCHSIA") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(DestroyBufferCollectionFUCHSIA);
    if (strcmp(name, "vkSetBufferCollectionConstraintsFUCHSIA") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(SetBufferCollectionConstraintsFUCHSIA);
    if (strcmp(name, "vkGetBufferCollectionPropertiesFUCHSIA") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(GetBufferCollectionPropertiesFUCHSIA);
#endif
    // Per the spec, return NULL if instance is NULL.
    if (!instance)
        return nullptr;
    // None of the other Vulkan functions should ever be called, as they all
    // take a VkPhysicalDevice or other object obtained from a physical device.
    return reinterpret_cast<PFN_vkVoidFunction>(NoOp);
}

} // namespace vkstubhal

namespace {

#ifdef VK_USE_PLATFORM_ANDROID_KHR

int OpenDevice(const hw_module_t* module, const char* id, hw_device_t** device);

hw_module_methods_t goldfish_vulkan_module_methods = {
    .open = OpenDevice
};

extern "C" __attribute__((visibility("default"))) hwvulkan_module_t HAL_MODULE_INFO_SYM = {
    .common = {
        .tag = HARDWARE_MODULE_TAG,
        .module_api_version = HWVULKAN_MODULE_API_VERSION_0_1,
        .hal_api_version = HARDWARE_HAL_API_VERSION,
        .id = HWVULKAN_HARDWARE_MODULE_ID,
        .name = "Goldfish Vulkan Driver",
        .author = "The Android Open Source Project",
        .methods = &goldfish_vulkan_module_methods,
    },
};

int CloseDevice(struct hw_device_t* /*device*/) {
    AEMU_SCOPED_TRACE("goldfish_vulkan::GetInstanceProcAddr");
    // nothing to do - opening a device doesn't allocate any resources
    return 0;
}

#endif

#define VK_HOST_CONNECTION(ret) \
    HostConnection *hostCon = HostConnection::get(); \
    if (!hostCon) { \
        ALOGE("vulkan: Failed to get host connection\n"); \
        return ret; \
    } \
    ExtendedRCEncoderContext *rcEnc = hostCon->rcEncoder(); \
    if (!rcEnc) { \
        ALOGE("vulkan: Failed to get renderControl encoder context\n"); \
        return ret; \
    } \
    goldfish_vk::VkEncoder *vkEnc = hostCon->vkEncoder(); \
    if (!vkEnc) { \
        ALOGE("vulkan: Failed to get Vulkan encoder\n"); \
        return ret; \
    } \
    goldfish_vk::ResourceTracker::get()->setupFeatures(rcEnc->featureInfo_const()); \
    auto hostSupportsVulkan = goldfish_vk::ResourceTracker::get()->hostSupportsVulkan(); \

VKAPI_ATTR
VkResult EnumerateInstanceExtensionProperties(
    const char* layer_name,
    uint32_t* count,
    VkExtensionProperties* properties) {
    AEMU_SCOPED_TRACE("goldfish_vulkan::EnumerateInstanceExtensionProperties");

    VK_HOST_CONNECTION(VK_ERROR_DEVICE_LOST)

    if (!hostSupportsVulkan) {
        return vkstubhal::EnumerateInstanceExtensionProperties(layer_name, count, properties);
    }

    if (layer_name) {
        ALOGW(
            "Driver vkEnumerateInstanceExtensionProperties shouldn't be called "
            "with a layer name ('%s')",
            layer_name);
    }

    VkResult res = goldfish_vk::ResourceTracker::get()->on_vkEnumerateInstanceExtensionProperties(
        vkEnc, VK_SUCCESS, layer_name, count, properties);

    return res;
}

VKAPI_ATTR
VkResult CreateInstance(const VkInstanceCreateInfo* create_info,
                        const VkAllocationCallbacks* allocator,
                        VkInstance* out_instance) {
    AEMU_SCOPED_TRACE("goldfish_vulkan::CreateInstance");

    VK_HOST_CONNECTION(VK_ERROR_DEVICE_LOST)

    if (!hostSupportsVulkan) {
        return vkstubhal::CreateInstance(create_info, allocator, out_instance);
    }

    VkResult res = vkEnc->vkCreateInstance(create_info, nullptr, out_instance);

    return res;
}

#ifdef VK_USE_PLATFORM_FUCHSIA
VKAPI_ATTR
VkResult GetMemoryZirconHandleFUCHSIA(
    VkDevice device,
    const VkMemoryGetZirconHandleInfoFUCHSIA* pInfo,
    uint32_t* pHandle) {
    AEMU_SCOPED_TRACE("goldfish_vulkan::GetMemoryZirconHandleFUCHSIA");

    VK_HOST_CONNECTION(VK_ERROR_DEVICE_LOST)

    if (!hostSupportsVulkan) {
        return vkstubhal::GetMemoryZirconHandleFUCHSIA(device, pInfo, pHandle);
    }

    VkResult res = goldfish_vk::ResourceTracker::get()->
        on_vkGetMemoryZirconHandleFUCHSIA(
            vkEnc, VK_SUCCESS,
            device, pInfo, pHandle);

    return res;
}

VKAPI_ATTR
VkResult GetMemoryZirconHandlePropertiesFUCHSIA(
    VkDevice device,
    VkExternalMemoryHandleTypeFlagBits handleType,
    uint32_t handle,
    VkMemoryZirconHandlePropertiesFUCHSIA* pProperties) {
    AEMU_SCOPED_TRACE("goldfish_vulkan::GetMemoryZirconHandlePropertiesFUCHSIA");

    VK_HOST_CONNECTION(VK_ERROR_DEVICE_LOST)

    if (!hostSupportsVulkan) {
        return vkstubhal::GetMemoryZirconHandlePropertiesFUCHSIA(
            device, handleType, handle, pProperties);
    }

    VkResult res = goldfish_vk::ResourceTracker::get()->
        on_vkGetMemoryZirconHandlePropertiesFUCHSIA(
            vkEnc, VK_SUCCESS, device, handleType, handle, pProperties);

    return res;
}

VKAPI_ATTR
VkResult GetSemaphoreZirconHandleFUCHSIA(
    VkDevice device,
    const VkSemaphoreGetZirconHandleInfoFUCHSIA* pInfo,
    uint32_t* pHandle) {
    AEMU_SCOPED_TRACE("goldfish_vulkan::GetSemaphoreZirconHandleFUCHSIA");

    VK_HOST_CONNECTION(VK_ERROR_DEVICE_LOST)

    if (!hostSupportsVulkan) {
        return vkstubhal::GetSemaphoreZirconHandleFUCHSIA(device, pInfo, pHandle);
    }

    VkResult res = goldfish_vk::ResourceTracker::get()->
        on_vkGetSemaphoreZirconHandleFUCHSIA(
            vkEnc, VK_SUCCESS, device, pInfo, pHandle);

    return res;
}

VKAPI_ATTR
VkResult ImportSemaphoreZirconHandleFUCHSIA(
    VkDevice device,
    const VkImportSemaphoreZirconHandleInfoFUCHSIA* pInfo) {
    AEMU_SCOPED_TRACE("goldfish_vulkan::ImportSemaphoreZirconHandleFUCHSIA");

    VK_HOST_CONNECTION(VK_ERROR_DEVICE_LOST)

    if (!hostSupportsVulkan) {
        return vkstubhal::ImportSemaphoreZirconHandleFUCHSIA(device, pInfo);
    }

    VkResult res = goldfish_vk::ResourceTracker::get()->
        on_vkImportSemaphoreZirconHandleFUCHSIA(
            vkEnc, VK_SUCCESS, device, pInfo);

    return res;
}

VKAPI_ATTR
VkResult CreateBufferCollectionFUCHSIA(
    VkDevice device,
    const VkBufferCollectionCreateInfoFUCHSIA* pInfo,
    const VkAllocationCallbacks* pAllocator,
    VkBufferCollectionFUCHSIA* pCollection) {
    AEMU_SCOPED_TRACE("goldfish_vulkan::CreateBufferCollectionFUCHSIA");

    VK_HOST_CONNECTION(VK_ERROR_DEVICE_LOST)

    if (!hostSupportsVulkan) {
        return vkstubhal::CreateBufferCollectionFUCHSIA(device, pInfo, pAllocator, pCollection);
    }

    VkResult res = goldfish_vk::ResourceTracker::get()->
        on_vkCreateBufferCollectionFUCHSIA(
            vkEnc, VK_SUCCESS, device, pInfo, pAllocator, pCollection);

    return res;
}

VKAPI_ATTR
void DestroyBufferCollectionFUCHSIA(
    VkDevice device,
    VkBufferCollectionFUCHSIA collection,
    const VkAllocationCallbacks* pAllocator) {
    AEMU_SCOPED_TRACE("goldfish_vulkan::DestroyBufferCollectionFUCHSIA");

    VK_HOST_CONNECTION()

    if (!hostSupportsVulkan) {
        vkstubhal::DestroyBufferCollectionFUCHSIA(device, collection, pAllocator);
        return;
    }

    goldfish_vk::ResourceTracker::get()->
        on_vkDestroyBufferCollectionFUCHSIA(
            vkEnc, VK_SUCCESS, device, collection, pAllocator);
}

VKAPI_ATTR
VkResult SetBufferCollectionConstraintsFUCHSIA(
    VkDevice device,
    VkBufferCollectionFUCHSIA collection,
    const VkImageCreateInfo* pImageInfo) {
    AEMU_SCOPED_TRACE("goldfish_vulkan::SetBufferCollectionConstraintsFUCHSIA");

    VK_HOST_CONNECTION(VK_ERROR_DEVICE_LOST)

    if (!hostSupportsVulkan) {
        return vkstubhal::SetBufferCollectionConstraintsFUCHSIA(device, collection, pImageInfo);
    }

    VkResult res = goldfish_vk::ResourceTracker::get()->
        on_vkSetBufferCollectionConstraintsFUCHSIA(
            vkEnc, VK_SUCCESS, device, collection, pImageInfo);

    return res;
}

VKAPI_ATTR
VkResult GetBufferCollectionPropertiesFUCHSIA(
    VkDevice device,
    VkBufferCollectionFUCHSIA collection,
    VkBufferCollectionPropertiesFUCHSIA* pProperties) {
    AEMU_SCOPED_TRACE("goldfish_vulkan::GetBufferCollectionPropertiesFUCHSIA");

    VK_HOST_CONNECTION(VK_ERROR_DEVICE_LOST)

    if (!hostSupportsVulkan) {
        return vkstubhal::GetBufferCollectionPropertiesFUCHSIA(device, collection, pProperties);
    }

    VkResult res = goldfish_vk::ResourceTracker::get()->
        on_vkGetBufferCollectionPropertiesFUCHSIA(
            vkEnc, VK_SUCCESS, device, collection, pProperties);

    return res;
}
#endif

static PFN_vkVoidFunction GetDeviceProcAddr(VkDevice device, const char* name) {
    AEMU_SCOPED_TRACE("goldfish_vulkan::GetDeviceProcAddr");

    VK_HOST_CONNECTION(nullptr)

    if (!hostSupportsVulkan) {
        return nullptr;
    }

#ifdef VK_USE_PLATFORM_FUCHSIA
    if (!strcmp(name, "vkGetMemoryZirconHandleFUCHSIA")) {
        return (PFN_vkVoidFunction)GetMemoryZirconHandleFUCHSIA;
    }
    if (!strcmp(name, "vkGetMemoryZirconHandlePropertiesFUCHSIA")) {
        return (PFN_vkVoidFunction)GetMemoryZirconHandlePropertiesFUCHSIA;
    }
    if (!strcmp(name, "vkGetSemaphoreZirconHandleFUCHSIA")) {
        return (PFN_vkVoidFunction)GetSemaphoreZirconHandleFUCHSIA;
    }
    if (!strcmp(name, "vkImportSemaphoreZirconHandleFUCHSIA")) {
        return (PFN_vkVoidFunction)ImportSemaphoreZirconHandleFUCHSIA;
    }
    if (!strcmp(name, "vkCreateBufferCollectionFUCHSIA")) {
        return (PFN_vkVoidFunction)CreateBufferCollectionFUCHSIA;
    }
    if (!strcmp(name, "vkDestroyBufferCollectionFUCHSIA")) {
        return (PFN_vkVoidFunction)DestroyBufferCollectionFUCHSIA;
    }
    if (!strcmp(name, "vkSetBufferCollectionConstraintsFUCHSIA")) {
        return (PFN_vkVoidFunction)SetBufferCollectionConstraintsFUCHSIA;
    }
    if (!strcmp(name, "vkGetBufferCollectionPropertiesFUCHSIA")) {
        return (PFN_vkVoidFunction)GetBufferCollectionPropertiesFUCHSIA;
    }
#endif
    if (!strcmp(name, "vkGetDeviceProcAddr")) {
        return (PFN_vkVoidFunction)(GetDeviceProcAddr);
    }
    return (PFN_vkVoidFunction)(goldfish_vk::goldfish_vulkan_get_device_proc_address(device, name));
}

VKAPI_ATTR
PFN_vkVoidFunction GetInstanceProcAddr(VkInstance instance, const char* name) {
    AEMU_SCOPED_TRACE("goldfish_vulkan::GetInstanceProcAddr");

    VK_HOST_CONNECTION(nullptr)

    if (!hostSupportsVulkan) {
        return vkstubhal::GetInstanceProcAddr(instance, name);
    }

    if (!strcmp(name, "vkEnumerateInstanceExtensionProperties")) {
        return (PFN_vkVoidFunction)EnumerateInstanceExtensionProperties;
    }
    if (!strcmp(name, "vkCreateInstance")) {
        return (PFN_vkVoidFunction)CreateInstance;
    }
    if (!strcmp(name, "vkGetDeviceProcAddr")) {
        return (PFN_vkVoidFunction)(GetDeviceProcAddr);
    }
    return (PFN_vkVoidFunction)(goldfish_vk::goldfish_vulkan_get_instance_proc_address(instance, name));
}

#ifdef VK_USE_PLATFORM_ANDROID_KHR

hwvulkan_device_t goldfish_vulkan_device = {
    .common = {
        .tag = HARDWARE_DEVICE_TAG,
        .version = HWVULKAN_DEVICE_API_VERSION_0_1,
        .module = &HAL_MODULE_INFO_SYM.common,
        .close = CloseDevice,
    },
    .EnumerateInstanceExtensionProperties = EnumerateInstanceExtensionProperties,
    .CreateInstance = CreateInstance,
    .GetInstanceProcAddr = GetInstanceProcAddr,
};

int OpenDevice(const hw_module_t* /*module*/,
               const char* id,
               hw_device_t** device) {
    AEMU_SCOPED_TRACE("goldfish_vulkan::OpenDevice");

    if (strcmp(id, HWVULKAN_DEVICE_0) == 0) {
        *device = &goldfish_vulkan_device.common;
        goldfish_vk::ResourceTracker::get();
        return 0;
    }
    return -ENOENT;
}

#endif

#ifdef VK_USE_PLATFORM_FUCHSIA

class VulkanDevice {
public:
    VulkanDevice() {
        goldfish_vk::ResourceTracker::get();
    }

    static VulkanDevice& GetInstance() {
        static VulkanDevice g_instance;
        return g_instance;
    }

    PFN_vkVoidFunction GetInstanceProcAddr(VkInstance instance, const char* name) {
        return ::GetInstanceProcAddr(instance, name);
    }
};

extern "C" __attribute__((visibility("default"))) PFN_vkVoidFunction
vk_icdGetInstanceProcAddr(VkInstance instance, const char* name) {
    return VulkanDevice::GetInstance().GetInstanceProcAddr(instance, name);
}

extern "C" __attribute__((visibility("default"))) VkResult
vk_icdNegotiateLoaderICDInterfaceVersion(uint32_t* pSupportedVersion) {
    *pSupportedVersion = std::min(*pSupportedVersion, 3u);
    return VK_SUCCESS;
}

#endif

} // namespace
