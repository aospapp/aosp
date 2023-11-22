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

#include "ResourceTracker.h"
#include "goldfish_vk_private_defs.h"

#include "../OpenglSystemCommon/EmulatorFeatureInfo.h"

#ifdef VK_USE_PLATFORM_ANDROID_KHR

#include "../egl/goldfish_sync.h"

typedef uint32_t zx_handle_t;
#define ZX_HANDLE_INVALID         ((zx_handle_t)0)
void zx_handle_close(zx_handle_t) { }
void zx_event_create(int, zx_handle_t*) { }

#include "AndroidHardwareBuffer.h"

#endif // VK_USE_PLATFORM_ANDROID_KHR

#ifdef VK_USE_PLATFORM_FUCHSIA

#include <cutils/native_handle.h>
#include <fuchsia/hardware/goldfish/control/c/fidl.h>
#include <fuchsia/sysmem/cpp/fidl.h>
#include <lib/fdio/directory.h>
#include <lib/fdio/fd.h>
#include <lib/fdio/fdio.h>
#include <lib/fdio/io.h>
#include <lib/zx/channel.h>
#include <zircon/process.h>
#include <zircon/syscalls.h>
#include <zircon/syscalls/object.h>

struct AHardwareBuffer;

void AHardwareBuffer_release(AHardwareBuffer*) { }

native_handle_t *AHardwareBuffer_getNativeHandle(AHardwareBuffer*) { return NULL; }

uint64_t getAndroidHardwareBufferUsageFromVkUsage(
    const VkImageCreateFlags vk_create,
    const VkImageUsageFlags vk_usage) {
  return AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
}

VkResult importAndroidHardwareBuffer(
    const VkImportAndroidHardwareBufferInfoANDROID* info,
    struct AHardwareBuffer **importOut) {
  return VK_SUCCESS;
}

VkResult createAndroidHardwareBuffer(
    bool hasDedicatedImage,
    bool hasDedicatedBuffer,
    const VkExtent3D& imageExtent,
    uint32_t imageLayers,
    VkFormat imageFormat,
    VkImageUsageFlags imageUsage,
    VkImageCreateFlags imageCreateFlags,
    VkDeviceSize bufferSize,
    VkDeviceSize allocationInfoAllocSize,
    struct AHardwareBuffer **out) {
  return VK_SUCCESS;
}

namespace goldfish_vk {
struct HostVisibleMemoryVirtualizationInfo;
}

VkResult getAndroidHardwareBufferPropertiesANDROID(
    const goldfish_vk::HostVisibleMemoryVirtualizationInfo*,
    VkDevice,
    const AHardwareBuffer*,
    VkAndroidHardwareBufferPropertiesANDROID*) { return VK_SUCCESS; }

VkResult getMemoryAndroidHardwareBufferANDROID(struct AHardwareBuffer **) { return VK_SUCCESS; }

#endif // VK_USE_PLATFORM_FUCHSIA

#include "HostVisibleMemoryVirtualization.h"
#include "Resources.h"
#include "VkEncoder.h"

#include "android/base/AlignedBuf.h"
#include "android/base/synchronization/AndroidLock.h"

#include "gralloc_cb.h"
#include "goldfish_address_space.h"
#include "goldfish_vk_private_defs.h"
#include "vk_format_info.h"
#include "vk_util.h"

#include <string>
#include <unordered_map>
#include <set>

#include <vndk/hardware_buffer.h>
#include <log/log.h>
#include <stdlib.h>
#include <sync/sync.h>

#ifdef VK_USE_PLATFORM_ANDROID_KHR

#include <sys/mman.h>
#include <sys/syscall.h>

#ifdef HOST_BUILD
#include "android/utils/tempfile.h"
#endif

#ifndef HAVE_MEMFD_CREATE
static inline int
memfd_create(const char *name, unsigned int flags) {
#ifdef HOST_BUILD
    TempFile* tmpFile = tempfile_create();
    return open(tempfile_path(tmpFile), O_RDWR);
    // TODO: Windows is not suppose to support VkSemaphoreGetFdInfoKHR
#else
    return syscall(SYS_memfd_create, name, flags);
#endif
}
#endif // !HAVE_MEMFD_CREATE
#endif // !VK_USE_PLATFORM_ANDROID_KHR

#define RESOURCE_TRACKER_DEBUG 0

#if RESOURCE_TRACKER_DEBUG
#undef D
#define D(fmt,...) ALOGD("%s: " fmt, __func__, ##__VA_ARGS__);
#else
#ifndef D
#define D(fmt,...)
#endif
#endif

using android::aligned_buf_alloc;
using android::aligned_buf_free;
using android::base::guest::AutoLock;
using android::base::guest::Lock;

namespace goldfish_vk {

#define MAKE_HANDLE_MAPPING_FOREACH(type_name, map_impl, map_to_u64_impl, map_from_u64_impl) \
    void mapHandles_##type_name(type_name* handles, size_t count) override { \
        for (size_t i = 0; i < count; ++i) { \
            map_impl; \
        } \
    } \
    void mapHandles_##type_name##_u64(const type_name* handles, uint64_t* handle_u64s, size_t count) override { \
        for (size_t i = 0; i < count; ++i) { \
            map_to_u64_impl; \
        } \
    } \
    void mapHandles_u64_##type_name(const uint64_t* handle_u64s, type_name* handles, size_t count) override { \
        for (size_t i = 0; i < count; ++i) { \
            map_from_u64_impl; \
        } \
    } \

#define DEFINE_RESOURCE_TRACKING_CLASS(class_name, impl) \
class class_name : public VulkanHandleMapping { \
public: \
    virtual ~class_name() { } \
    GOLDFISH_VK_LIST_HANDLE_TYPES(impl) \
}; \

#define CREATE_MAPPING_IMPL_FOR_TYPE(type_name) \
    MAKE_HANDLE_MAPPING_FOREACH(type_name, \
        handles[i] = new_from_host_##type_name(handles[i]); ResourceTracker::get()->register_##type_name(handles[i]);, \
        handle_u64s[i] = (uint64_t)new_from_host_##type_name(handles[i]), \
        handles[i] = (type_name)new_from_host_u64_##type_name(handle_u64s[i]); ResourceTracker::get()->register_##type_name(handles[i]);)

#define UNWRAP_MAPPING_IMPL_FOR_TYPE(type_name) \
    MAKE_HANDLE_MAPPING_FOREACH(type_name, \
        handles[i] = get_host_##type_name(handles[i]), \
        handle_u64s[i] = (uint64_t)get_host_u64_##type_name(handles[i]), \
        handles[i] = (type_name)get_host_##type_name((type_name)handle_u64s[i]))

#define DESTROY_MAPPING_IMPL_FOR_TYPE(type_name) \
    MAKE_HANDLE_MAPPING_FOREACH(type_name, \
        ResourceTracker::get()->unregister_##type_name(handles[i]); delete_goldfish_##type_name(handles[i]), \
        (void)handle_u64s[i]; delete_goldfish_##type_name(handles[i]), \
        (void)handles[i]; delete_goldfish_##type_name((type_name)handle_u64s[i]))

DEFINE_RESOURCE_TRACKING_CLASS(CreateMapping, CREATE_MAPPING_IMPL_FOR_TYPE)
DEFINE_RESOURCE_TRACKING_CLASS(UnwrapMapping, UNWRAP_MAPPING_IMPL_FOR_TYPE)
DEFINE_RESOURCE_TRACKING_CLASS(DestroyMapping, DESTROY_MAPPING_IMPL_FOR_TYPE)

class ResourceTracker::Impl {
public:
    Impl() = default;
    CreateMapping createMapping;
    UnwrapMapping unwrapMapping;
    DestroyMapping destroyMapping;
    DefaultHandleMapping defaultMapping;

#define HANDLE_DEFINE_TRIVIAL_INFO_STRUCT(type) \
    struct type##_Info { \
        uint32_t unused; \
    }; \

    GOLDFISH_VK_LIST_TRIVIAL_HANDLE_TYPES(HANDLE_DEFINE_TRIVIAL_INFO_STRUCT)

    struct VkInstance_Info {
        uint32_t highestApiVersion;
        std::set<std::string> enabledExtensions;
        // Fodder for vkEnumeratePhysicalDevices.
        std::vector<VkPhysicalDevice> physicalDevices;
    };

    using HostMemBlocks = std::vector<HostMemAlloc>;
    using HostMemBlockIndex = size_t;

#define INVALID_HOST_MEM_BLOCK (-1)

    struct VkDevice_Info {
        VkPhysicalDevice physdev;
        VkPhysicalDeviceProperties props;
        VkPhysicalDeviceMemoryProperties memProps;
        std::vector<HostMemBlocks> hostMemBlocks { VK_MAX_MEMORY_TYPES };
        uint32_t apiVersion;
        std::set<std::string> enabledExtensions;
        VkFence fence = VK_NULL_HANDLE;
    };

    struct VkDeviceMemory_Info {
        VkDeviceSize allocationSize = 0;
        VkDeviceSize mappedSize = 0;
        uint8_t* mappedPtr = nullptr;
        uint32_t memoryTypeIndex = 0;
        bool virtualHostVisibleBacking = false;
        bool directMapped = false;
        GoldfishAddressSpaceBlock*
            goldfishAddressSpaceBlock = nullptr;
        SubAlloc subAlloc;
        AHardwareBuffer* ahw = nullptr;
        zx_handle_t vmoHandle = ZX_HANDLE_INVALID;
    };

    // custom guest-side structs for images/buffers because of AHardwareBuffer :((
    struct VkImage_Info {
        VkDevice device;
        VkImageCreateInfo createInfo;
        bool external = false;
        VkExternalMemoryImageCreateInfo externalCreateInfo;
        VkDeviceMemory currentBacking = VK_NULL_HANDLE;
        VkDeviceSize currentBackingOffset = 0;
        VkDeviceSize currentBackingSize = 0;
    };

    struct VkBuffer_Info {
        VkDevice device;
        VkBufferCreateInfo createInfo;
        bool external = false;
        VkExternalMemoryBufferCreateInfo externalCreateInfo;
        VkDeviceMemory currentBacking = VK_NULL_HANDLE;
        VkDeviceSize currentBackingOffset = 0;
        VkDeviceSize currentBackingSize = 0;
    };

    struct VkSemaphore_Info {
        VkDevice device;
        zx_handle_t eventHandle = ZX_HANDLE_INVALID;
        int syncFd = -1;
    };

    struct VkDescriptorUpdateTemplate_Info {
        std::vector<VkDescriptorUpdateTemplateEntry> templateEntries;

        // Flattened versions
        std::vector<uint32_t> imageInfoEntryIndices;
        std::vector<uint32_t> bufferInfoEntryIndices;
        std::vector<uint32_t> bufferViewEntryIndices;
        std::vector<VkDescriptorImageInfo> imageInfos;
        std::vector<VkDescriptorBufferInfo> bufferInfos;
        std::vector<VkBufferView> bufferViews;
    };

#define HANDLE_REGISTER_IMPL_IMPL(type) \
    std::unordered_map<type, type##_Info> info_##type; \
    void register_##type(type obj) { \
        AutoLock lock(mLock); \
        info_##type[obj] = type##_Info(); \
    } \

#define HANDLE_UNREGISTER_IMPL_IMPL(type) \
    void unregister_##type(type obj) { \
        AutoLock lock(mLock); \
        info_##type.erase(obj); \
    } \

    GOLDFISH_VK_LIST_HANDLE_TYPES(HANDLE_REGISTER_IMPL_IMPL)
    GOLDFISH_VK_LIST_TRIVIAL_HANDLE_TYPES(HANDLE_UNREGISTER_IMPL_IMPL)

    void unregister_VkInstance(VkInstance instance) {
        AutoLock lock(mLock);

        auto it = info_VkInstance.find(instance);
        if (it == info_VkInstance.end()) return;
        auto info = it->second;
        info_VkInstance.erase(instance);
        lock.unlock();
    }

    void unregister_VkDevice(VkDevice device) {
        AutoLock lock(mLock);

        auto it = info_VkDevice.find(device);
        if (it == info_VkDevice.end()) return;
        auto info = it->second;
        info_VkDevice.erase(device);
        lock.unlock();
    }

    void unregister_VkDeviceMemory(VkDeviceMemory mem) {
        AutoLock lock(mLock);

        auto it = info_VkDeviceMemory.find(mem);
        if (it == info_VkDeviceMemory.end()) return;

        auto& memInfo = it->second;

        if (memInfo.ahw) {
            AHardwareBuffer_release(memInfo.ahw);
        }

        if (memInfo.vmoHandle != ZX_HANDLE_INVALID) {
            zx_handle_close(memInfo.vmoHandle);
        }

        if (memInfo.mappedPtr &&
            !memInfo.virtualHostVisibleBacking &&
            !memInfo.directMapped) {
            aligned_buf_free(memInfo.mappedPtr);
        }

        if (memInfo.directMapped) {
            subFreeHostMemory(&memInfo.subAlloc);
        }

        delete memInfo.goldfishAddressSpaceBlock;

        info_VkDeviceMemory.erase(mem);
    }

    void unregister_VkImage(VkImage img) {
        AutoLock lock(mLock);

        auto it = info_VkImage.find(img);
        if (it == info_VkImage.end()) return;

        auto& imageInfo = it->second;

        info_VkImage.erase(img);
    }

    void unregister_VkBuffer(VkBuffer buf) {
        AutoLock lock(mLock);

        auto it = info_VkBuffer.find(buf);
        if (it == info_VkBuffer.end()) return;

        info_VkBuffer.erase(buf);
    }

    void unregister_VkSemaphore(VkSemaphore sem) {
        AutoLock lock(mLock);

        auto it = info_VkSemaphore.find(sem);
        if (it == info_VkSemaphore.end()) return;

        auto& semInfo = it->second;

        if (semInfo.eventHandle != ZX_HANDLE_INVALID) {
            zx_handle_close(semInfo.eventHandle);
        }

        info_VkSemaphore.erase(sem);
    }

    void unregister_VkDescriptorUpdateTemplate(VkDescriptorUpdateTemplate templ) {
        info_VkDescriptorUpdateTemplate.erase(templ);
    }

    // TODO: Upgrade to 1.1
    static constexpr uint32_t kMaxApiVersion = VK_MAKE_VERSION(1, 1, 0);
    static constexpr uint32_t kMinApiVersion = VK_MAKE_VERSION(1, 0, 0);

    void setInstanceInfo(VkInstance instance,
                         uint32_t enabledExtensionCount,
                         const char* const* ppEnabledExtensionNames,
                         uint32_t apiVersion) {
        AutoLock lock(mLock);
        auto& info = info_VkInstance[instance];
        info.highestApiVersion = apiVersion;

        if (!ppEnabledExtensionNames) return;

        for (uint32_t i = 0; i < enabledExtensionCount; ++i) {
            info.enabledExtensions.insert(ppEnabledExtensionNames[i]);
        }
    }

    void setDeviceInfo(VkDevice device,
                       VkPhysicalDevice physdev,
                       VkPhysicalDeviceProperties props,
                       VkPhysicalDeviceMemoryProperties memProps,
                       uint32_t enabledExtensionCount,
                       const char* const* ppEnabledExtensionNames) {
        AutoLock lock(mLock);
        auto& info = info_VkDevice[device];
        info.physdev = physdev;
        info.props = props;
        info.memProps = memProps;
        initHostVisibleMemoryVirtualizationInfo(
            physdev, &memProps,
            mFeatureInfo->hasDirectMem,
            &mHostVisibleMemoryVirtInfo);
        info.apiVersion = props.apiVersion;

        if (!ppEnabledExtensionNames) return;

        for (uint32_t i = 0; i < enabledExtensionCount; ++i) {
            info.enabledExtensions.insert(ppEnabledExtensionNames[i]);
        }
    }

    void setDeviceMemoryInfo(VkDevice device,
                             VkDeviceMemory memory,
                             VkDeviceSize allocationSize,
                             VkDeviceSize mappedSize,
                             uint8_t* ptr,
                             uint32_t memoryTypeIndex,
                             AHardwareBuffer* ahw = nullptr,
                             zx_handle_t vmoHandle = ZX_HANDLE_INVALID) {
        AutoLock lock(mLock);
        auto& deviceInfo = info_VkDevice[device];
        auto& info = info_VkDeviceMemory[memory];

        info.allocationSize = allocationSize;
        info.mappedSize = mappedSize;
        info.mappedPtr = ptr;
        info.memoryTypeIndex = memoryTypeIndex;
        info.ahw = ahw;
        info.vmoHandle = vmoHandle;
    }

    void setImageInfo(VkImage image,
                      VkDevice device,
                      const VkImageCreateInfo *pCreateInfo) {
        AutoLock lock(mLock);
        auto& info = info_VkImage[image];

        info.device = device;
        info.createInfo = *pCreateInfo;
    }

    bool isMemoryTypeHostVisible(VkDevice device, uint32_t typeIndex) const {
        AutoLock lock(mLock);
        const auto it = info_VkDevice.find(device);

        if (it == info_VkDevice.end()) return false;

        const auto& info = it->second;
        return info.memProps.memoryTypes[typeIndex].propertyFlags &
               VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
    }

    uint8_t* getMappedPointer(VkDeviceMemory memory) {
        AutoLock lock(mLock);
        const auto it = info_VkDeviceMemory.find(memory);
        if (it == info_VkDeviceMemory.end()) return nullptr;

        const auto& info = it->second;
        return info.mappedPtr;
    }

    VkDeviceSize getMappedSize(VkDeviceMemory memory) {
        AutoLock lock(mLock);
        const auto it = info_VkDeviceMemory.find(memory);
        if (it == info_VkDeviceMemory.end()) return 0;

        const auto& info = it->second;
        return info.mappedSize;
    }

    VkDeviceSize getNonCoherentExtendedSize(VkDevice device, VkDeviceSize basicSize) const {
        AutoLock lock(mLock);
        const auto it = info_VkDevice.find(device);
        if (it == info_VkDevice.end()) return basicSize;
        const auto& info = it->second;

        VkDeviceSize nonCoherentAtomSize =
            info.props.limits.nonCoherentAtomSize;
        VkDeviceSize atoms =
            (basicSize + nonCoherentAtomSize - 1) / nonCoherentAtomSize;
        return atoms * nonCoherentAtomSize;
    }

    bool isValidMemoryRange(const VkMappedMemoryRange& range) const {
        AutoLock lock(mLock);
        const auto it = info_VkDeviceMemory.find(range.memory);
        if (it == info_VkDeviceMemory.end()) return false;
        const auto& info = it->second;

        if (!info.mappedPtr) return false;

        VkDeviceSize offset = range.offset;
        VkDeviceSize size = range.size;

        if (size == VK_WHOLE_SIZE) {
            return offset <= info.mappedSize;
        }

        return offset + size <= info.mappedSize;
    }

    void setupFeatures(const EmulatorFeatureInfo* features) {
        if (!features || mFeatureInfo) return;
        mFeatureInfo.reset(new EmulatorFeatureInfo);
        *mFeatureInfo = *features;

        if (mFeatureInfo->hasDirectMem) {
            mGoldfishAddressSpaceBlockProvider.reset(
                new GoldfishAddressSpaceBlockProvider);
        }

#ifdef VK_USE_PLATFORM_FUCHSIA
        if (mFeatureInfo->hasVulkan) {
            int fd = open("/dev/class/goldfish-control/000", O_RDWR);
            if (fd < 0) {
                ALOGE("failed to open control device");
                abort();
            }
            zx_status_t status = fdio_get_service_handle(fd, &mControlDevice);
            if (status != ZX_OK) {
                ALOGE("failed to get control service handle, status %d", status);
                abort();
            }
            status = fuchsia_hardware_goldfish_control_DeviceConnectSysmem(
                mControlDevice,
                mSysmemAllocator.NewRequest().TakeChannel().release());
            if (status != ZX_OK) {
                ALOGE("failed to get sysmem connection, status %d", status);
                abort();
            }
        }
#endif
    }

    bool hostSupportsVulkan() const {
        if (!mFeatureInfo) return false;

        return mFeatureInfo->hasVulkan;
    }

    bool usingDirectMapping() const {
        return mHostVisibleMemoryVirtInfo.virtualizationSupported;
    }

    bool supportsDeferredCommands() const {
        if (!mFeatureInfo) return false;
        return mFeatureInfo->hasDeferredVulkanCommands;
    }

    int getHostInstanceExtensionIndex(const std::string& extName) const {
        int i = 0;
        for (const auto& prop : mHostInstanceExtensions) {
            if (extName == std::string(prop.extensionName)) {
                return i;
            }
            ++i;
        }
        return -1;
    }

    int getHostDeviceExtensionIndex(const std::string& extName) const {
        int i = 0;
        for (const auto& prop : mHostDeviceExtensions) {
            if (extName == std::string(prop.extensionName)) {
                return i;
            }
            ++i;
        }
        return -1;
    }

    void deviceMemoryTransform_tohost(
        VkDeviceMemory* memory, uint32_t memoryCount,
        VkDeviceSize* offset, uint32_t offsetCount,
        VkDeviceSize* size, uint32_t sizeCount,
        uint32_t* typeIndex, uint32_t typeIndexCount,
        uint32_t* typeBits, uint32_t typeBitsCount) {

        (void)memoryCount;
        (void)offsetCount;
        (void)sizeCount;

        const auto& hostVirt =
            mHostVisibleMemoryVirtInfo;

        if (!hostVirt.virtualizationSupported) return;

        if (memory) {
            AutoLock lock (mLock);

            for (uint32_t i = 0; i < memoryCount; ++i) {
                VkDeviceMemory mem = memory[i];

                auto it = info_VkDeviceMemory.find(mem);
                if (it == info_VkDeviceMemory.end()) return;

                const auto& info = it->second;

                if (!info.directMapped) continue;

                memory[i] = info.subAlloc.baseMemory;

                if (offset) {
                    offset[i] = info.subAlloc.baseOffset + offset[i];
                }

                if (size) {
                    if (size[i] == VK_WHOLE_SIZE) {
                        size[i] = info.subAlloc.subMappedSize;
                    }
                }

                // TODO
                (void)memory;
                (void)offset;
                (void)size;
            }
        }

        for (uint32_t i = 0; i < typeIndexCount; ++i) {
            typeIndex[i] =
                hostVirt.memoryTypeIndexMappingToHost[typeIndex[i]];
        }

        for (uint32_t i = 0; i < typeBitsCount; ++i) {
            uint32_t bits = 0;
            for (uint32_t j = 0; j < VK_MAX_MEMORY_TYPES; ++j) {
                bool guestHas = typeBits[i] & (1 << j);
                uint32_t hostIndex =
                    hostVirt.memoryTypeIndexMappingToHost[j];
                bits |= guestHas ? (1 << hostIndex) : 0;
            }
            typeBits[i] = bits;
        }
    }

    void deviceMemoryTransform_fromhost(
        VkDeviceMemory* memory, uint32_t memoryCount,
        VkDeviceSize* offset, uint32_t offsetCount,
        VkDeviceSize* size, uint32_t sizeCount,
        uint32_t* typeIndex, uint32_t typeIndexCount,
        uint32_t* typeBits, uint32_t typeBitsCount) {

        (void)memoryCount;
        (void)offsetCount;
        (void)sizeCount;

        const auto& hostVirt =
            mHostVisibleMemoryVirtInfo;

        if (!hostVirt.virtualizationSupported) return;

        AutoLock lock (mLock);

        for (uint32_t i = 0; i < memoryCount; ++i) {
            // TODO
            (void)memory;
            (void)offset;
            (void)size;
        }

        for (uint32_t i = 0; i < typeIndexCount; ++i) {
            typeIndex[i] =
                hostVirt.memoryTypeIndexMappingFromHost[typeIndex[i]];
        }

        for (uint32_t i = 0; i < typeBitsCount; ++i) {
            uint32_t bits = 0;
            for (uint32_t j = 0; j < VK_MAX_MEMORY_TYPES; ++j) {
                bool hostHas = typeBits[i] & (1 << j);
                uint32_t guestIndex =
                    hostVirt.memoryTypeIndexMappingFromHost[j];
                bits |= hostHas ? (1 << guestIndex) : 0;

                if (hostVirt.memoryTypeBitsShouldAdvertiseBoth[j]) {
                    bits |= hostHas ? (1 << j) : 0;
                }
            }
            typeBits[i] = bits;
        }
    }

    VkResult on_vkEnumerateInstanceExtensionProperties(
        void* context,
        VkResult,
        const char*,
        uint32_t* pPropertyCount,
        VkExtensionProperties* pProperties) {
        std::vector<const char*> allowedExtensionNames = {
            "VK_KHR_get_physical_device_properties2",
            "VK_KHR_sampler_ycbcr_conversion",
#ifdef VK_USE_PLATFORM_ANDROID_KHR
            "VK_KHR_external_semaphore_capabilities",
            "VK_KHR_external_memory_capabilities",
#endif
            // TODO:
            // VK_KHR_external_memory_capabilities
        };

        VkEncoder* enc = (VkEncoder*)context;

        // Only advertise a select set of extensions.
        if (mHostInstanceExtensions.empty()) {
            uint32_t hostPropCount = 0;
            enc->vkEnumerateInstanceExtensionProperties(nullptr, &hostPropCount, nullptr);
            mHostInstanceExtensions.resize(hostPropCount);

            VkResult hostRes =
                enc->vkEnumerateInstanceExtensionProperties(
                    nullptr, &hostPropCount, mHostInstanceExtensions.data());

            if (hostRes != VK_SUCCESS) {
                return hostRes;
            }
        }

        std::vector<VkExtensionProperties> filteredExts;

        for (size_t i = 0; i < allowedExtensionNames.size(); ++i) {
            auto extIndex = getHostInstanceExtensionIndex(allowedExtensionNames[i]);
            if (extIndex != -1) {
                filteredExts.push_back(mHostInstanceExtensions[extIndex]);
            }
        }

        VkExtensionProperties anbExtProps[] = {
#ifdef VK_USE_PLATFORM_ANDROID_KHR
            { "VK_ANDROID_native_buffer", 7 },
#endif
#ifdef VK_USE_PLATFORM_FUCHSIA
            { "VK_KHR_external_memory_capabilities", 1},
            { "VK_KHR_external_semaphore_capabilities", 1},
#endif
        };

        for (auto& anbExtProp: anbExtProps) {
            filteredExts.push_back(anbExtProp);
        }

        if (pPropertyCount) {
            *pPropertyCount = filteredExts.size();
        }

        if (pPropertyCount && pProperties) {
            for (size_t i = 0; i < *pPropertyCount; ++i) {
                pProperties[i] = filteredExts[i];
            }
        }

        return VK_SUCCESS;
    }

    VkResult on_vkEnumerateDeviceExtensionProperties(
        void* context,
        VkResult,
        VkPhysicalDevice physdev,
        const char*,
        uint32_t* pPropertyCount,
        VkExtensionProperties* pProperties) {

        std::vector<const char*> allowedExtensionNames = {
            "VK_KHR_maintenance1",
            "VK_KHR_get_memory_requirements2",
            "VK_KHR_dedicated_allocation",
            "VK_KHR_bind_memory2",
            "VK_KHR_sampler_ycbcr_conversion",
#ifdef VK_USE_PLATFORM_ANDROID_KHR
            "VK_KHR_external_semaphore",
            "VK_KHR_external_semaphore_fd",
            // "VK_KHR_external_semaphore_win32", not exposed because it's translated to fd
            "VK_KHR_external_memory",
#endif
            // "VK_KHR_maintenance2",
            // "VK_KHR_maintenance3",
            // TODO:
            // VK_KHR_external_memory_capabilities
        };

        VkEncoder* enc = (VkEncoder*)context;

        if (mHostDeviceExtensions.empty()) {
            uint32_t hostPropCount = 0;
            enc->vkEnumerateDeviceExtensionProperties(physdev, nullptr, &hostPropCount, nullptr);
            mHostDeviceExtensions.resize(hostPropCount);

            VkResult hostRes =
                enc->vkEnumerateDeviceExtensionProperties(
                    physdev, nullptr, &hostPropCount, mHostDeviceExtensions.data());

            if (hostRes != VK_SUCCESS) {
                return hostRes;
            }
        }

        bool hostHasWin32ExternalSemaphore =
            getHostDeviceExtensionIndex(
                "VK_KHR_external_semaphore_win32") != -1;

        bool hostHasPosixExternalSemaphore =
            getHostDeviceExtensionIndex(
                "VK_KHR_external_semaphore_fd") != -1;

        ALOGD("%s: host has ext semaphore? win32 %d posix %d\n", __func__,
                hostHasWin32ExternalSemaphore,
                hostHasPosixExternalSemaphore);

        bool hostSupportsExternalSemaphore =
            hostHasWin32ExternalSemaphore ||
            hostHasPosixExternalSemaphore;

        std::vector<VkExtensionProperties> filteredExts;

        for (size_t i = 0; i < allowedExtensionNames.size(); ++i) {
            auto extIndex = getHostDeviceExtensionIndex(allowedExtensionNames[i]);
            if (extIndex != -1) {
                filteredExts.push_back(mHostDeviceExtensions[extIndex]);
            }
        }

        VkExtensionProperties anbExtProps[] = {
#ifdef VK_USE_PLATFORM_ANDROID_KHR
            { "VK_ANDROID_native_buffer", 7 },
#endif
#ifdef VK_USE_PLATFORM_FUCHSIA
            { "VK_KHR_external_memory", 1 },
            { "VK_KHR_external_semaphore", 1 },
            { "VK_FUCHSIA_external_semaphore", 1 },
            { "VK_FUCHSIA_buffer_collection", 1 },
#endif
        };

        for (auto& anbExtProp: anbExtProps) {
            filteredExts.push_back(anbExtProp);
        }

        if (hostSupportsExternalSemaphore &&
            !hostHasPosixExternalSemaphore) {
            filteredExts.push_back(
                { "VK_KHR_external_semaphore_fd", 1});
        }

        bool win32ExtMemAvailable =
            getHostDeviceExtensionIndex(
                "VK_KHR_external_memory_win32") != -1;
        bool posixExtMemAvailable =
            getHostDeviceExtensionIndex(
                "VK_KHR_external_memory_fd") != -1;

        bool hostHasExternalMemorySupport =
            win32ExtMemAvailable || posixExtMemAvailable;

        if (hostHasExternalMemorySupport) {
#ifdef VK_USE_PLATFORM_ANDROID_KHR
            filteredExts.push_back({
                "VK_ANDROID_external_memory_android_hardware_buffer", 7
            });
#endif
#ifdef VK_USE_PLATFORM_FUCHSIA
            filteredExts.push_back({
                "VK_FUCHSIA_external_memory", 1
            });
#endif
        }

        if (pPropertyCount) {
            *pPropertyCount = filteredExts.size();
        }

        if (pPropertyCount && pProperties) {
            for (size_t i = 0; i < *pPropertyCount; ++i) {
                pProperties[i] = filteredExts[i];
            }
        }


        return VK_SUCCESS;
    }

    VkResult on_vkEnumeratePhysicalDevices(
        void* context, VkResult,
        VkInstance instance, uint32_t* pPhysicalDeviceCount,
        VkPhysicalDevice* pPhysicalDevices) {

        VkEncoder* enc = (VkEncoder*)context;

        if (!instance) return VK_ERROR_INITIALIZATION_FAILED;

        if (!pPhysicalDeviceCount) return VK_ERROR_INITIALIZATION_FAILED;

        AutoLock lock(mLock);

        auto it = info_VkInstance.find(instance);

        if (it == info_VkInstance.end()) return VK_ERROR_INITIALIZATION_FAILED;

        auto& info = it->second;

        if (info.physicalDevices.empty()) {
            uint32_t physdevCount = 0;

            lock.unlock();
            VkResult countRes = enc->vkEnumeratePhysicalDevices(
                instance, &physdevCount, nullptr);
            lock.lock();

            if (countRes != VK_SUCCESS) {
                ALOGE("%s: failed: could not count host physical devices. "
                      "Error %d\n", __func__, countRes);
                return countRes;
            }

            info.physicalDevices.resize(physdevCount);

            lock.unlock();
            VkResult enumRes = enc->vkEnumeratePhysicalDevices(
                instance, &physdevCount, info.physicalDevices.data());
            lock.lock();

            if (enumRes != VK_SUCCESS) {
                ALOGE("%s: failed: could not retrieve host physical devices. "
                      "Error %d\n", __func__, enumRes);
                return enumRes;
            }
        }

        *pPhysicalDeviceCount = (uint32_t)info.physicalDevices.size();

        if (pPhysicalDevices && *pPhysicalDeviceCount) {
            memcpy(pPhysicalDevices,
                   info.physicalDevices.data(),
                   sizeof(VkPhysicalDevice) *
                   info.physicalDevices.size());
        }

        return VK_SUCCESS;
    }

    void on_vkGetPhysicalDeviceMemoryProperties(
        void*,
        VkPhysicalDevice physdev,
        VkPhysicalDeviceMemoryProperties* out) {

        initHostVisibleMemoryVirtualizationInfo(
            physdev,
            out,
            mFeatureInfo->hasDirectMem,
            &mHostVisibleMemoryVirtInfo);

        if (mHostVisibleMemoryVirtInfo.virtualizationSupported) {
            *out = mHostVisibleMemoryVirtInfo.guestMemoryProperties;
        }
    }

    void on_vkGetPhysicalDeviceMemoryProperties2(
        void*,
        VkPhysicalDevice physdev,
        VkPhysicalDeviceMemoryProperties2* out) {

        initHostVisibleMemoryVirtualizationInfo(
            physdev,
            &out->memoryProperties,
            mFeatureInfo->hasDirectMem,
            &mHostVisibleMemoryVirtInfo);

        if (mHostVisibleMemoryVirtInfo.virtualizationSupported) {
            out->memoryProperties = mHostVisibleMemoryVirtInfo.guestMemoryProperties;
        }
    }

    VkResult on_vkCreateInstance(
        void* context,
        VkResult input_result,
        const VkInstanceCreateInfo* createInfo,
        const VkAllocationCallbacks*,
        VkInstance* pInstance) {

        if (input_result != VK_SUCCESS) return input_result;

        VkEncoder* enc = (VkEncoder*)context;

        uint32_t apiVersion;
        VkResult enumInstanceVersionRes =
            enc->vkEnumerateInstanceVersion(&apiVersion);

        setInstanceInfo(
            *pInstance,
            createInfo->enabledExtensionCount,
            createInfo->ppEnabledExtensionNames,
            apiVersion);

        return input_result;
    }

    VkResult on_vkCreateDevice(
        void* context,
        VkResult input_result,
        VkPhysicalDevice physicalDevice,
        const VkDeviceCreateInfo* pCreateInfo,
        const VkAllocationCallbacks*,
        VkDevice* pDevice) {

        if (input_result != VK_SUCCESS) return input_result;

        VkEncoder* enc = (VkEncoder*)context;

        VkPhysicalDeviceProperties props;
        VkPhysicalDeviceMemoryProperties memProps;
        enc->vkGetPhysicalDeviceProperties(physicalDevice, &props);
        enc->vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memProps);

        setDeviceInfo(
            *pDevice, physicalDevice, props, memProps,
            pCreateInfo->enabledExtensionCount, pCreateInfo->ppEnabledExtensionNames);

        return input_result;
    }

    void on_vkDestroyDevice_pre(
        void* context,
        VkDevice device,
        const VkAllocationCallbacks*) {

        AutoLock lock(mLock);

        auto it = info_VkDevice.find(device);
        if (it == info_VkDevice.end()) return;
        auto info = it->second;

        lock.unlock();

        VkEncoder* enc = (VkEncoder*)context;

        for (uint32_t i = 0; i < VK_MAX_MEMORY_TYPES; ++i) {
            for (auto& block : info.hostMemBlocks[i]) {
                destroyHostMemAlloc(enc, device, &block);
            }
        }

        if (info.fence != VK_NULL_HANDLE) {
            enc->vkDestroyFence(device, info.fence, nullptr);
        }
    }

    VkResult on_vkGetAndroidHardwareBufferPropertiesANDROID(
        void*, VkResult,
        VkDevice device,
        const AHardwareBuffer* buffer,
        VkAndroidHardwareBufferPropertiesANDROID* pProperties) {
        return getAndroidHardwareBufferPropertiesANDROID(
            &mHostVisibleMemoryVirtInfo,
            device, buffer, pProperties);
    }

    VkResult on_vkGetMemoryAndroidHardwareBufferANDROID(
        void*, VkResult,
        VkDevice device,
        const VkMemoryGetAndroidHardwareBufferInfoANDROID *pInfo,
        struct AHardwareBuffer** pBuffer) {

        if (!pInfo) return VK_ERROR_INITIALIZATION_FAILED;
        if (!pInfo->memory) return VK_ERROR_INITIALIZATION_FAILED;

        AutoLock lock(mLock);

        auto deviceIt = info_VkDevice.find(device);

        if (deviceIt == info_VkDevice.end()) {
            return VK_ERROR_INITIALIZATION_FAILED;
        }

        auto memoryIt = info_VkDeviceMemory.find(pInfo->memory);

        if (memoryIt == info_VkDeviceMemory.end()) {
            return VK_ERROR_INITIALIZATION_FAILED;
        }

        auto& info = memoryIt->second;

        VkResult queryRes =
            getMemoryAndroidHardwareBufferANDROID(&info.ahw);

        if (queryRes != VK_SUCCESS) return queryRes;

        *pBuffer = info.ahw;

        return queryRes;
    }

#ifdef VK_USE_PLATFORM_FUCHSIA
    VkResult on_vkGetMemoryZirconHandleFUCHSIA(
        void*, VkResult,
        VkDevice device,
        const VkMemoryGetZirconHandleInfoFUCHSIA* pInfo,
        uint32_t* pHandle) {

        if (!pInfo) return VK_ERROR_INITIALIZATION_FAILED;
        if (!pInfo->memory) return VK_ERROR_INITIALIZATION_FAILED;

        AutoLock lock(mLock);

        auto deviceIt = info_VkDevice.find(device);

        if (deviceIt == info_VkDevice.end()) {
            return VK_ERROR_INITIALIZATION_FAILED;
        }

        auto memoryIt = info_VkDeviceMemory.find(pInfo->memory);

        if (memoryIt == info_VkDeviceMemory.end()) {
            return VK_ERROR_INITIALIZATION_FAILED;
        }

        auto& info = memoryIt->second;

        if (info.vmoHandle == ZX_HANDLE_INVALID) {
            ALOGE("%s: memory cannot be exported", __func__);
            return VK_ERROR_INITIALIZATION_FAILED;
        }

        *pHandle = ZX_HANDLE_INVALID;
        zx_handle_duplicate(info.vmoHandle, ZX_RIGHT_SAME_RIGHTS, pHandle);
        return VK_SUCCESS;
    }

    VkResult on_vkGetMemoryZirconHandlePropertiesFUCHSIA(
        void*, VkResult,
        VkDevice device,
        VkExternalMemoryHandleTypeFlagBits handleType,
        uint32_t handle,
        VkMemoryZirconHandlePropertiesFUCHSIA* pProperties) {
        if (handleType != VK_EXTERNAL_MEMORY_HANDLE_TYPE_TEMP_ZIRCON_VMO_BIT_FUCHSIA) {
            return VK_ERROR_INITIALIZATION_FAILED;
        }
        if (pProperties->sType != VK_STRUCTURE_TYPE_TEMP_MEMORY_ZIRCON_HANDLE_PROPERTIES_FUCHSIA) {
            return VK_ERROR_INITIALIZATION_FAILED;
        }

        AutoLock lock(mLock);

        auto deviceIt = info_VkDevice.find(device);

        if (deviceIt == info_VkDevice.end()) {
            return VK_ERROR_INITIALIZATION_FAILED;
        }

        auto& info = deviceIt->second;

        // Device local memory type supported.
        pProperties->memoryTypeBits = 0;
        for (uint32_t i = 0; i < info.memProps.memoryTypeCount; ++i) {
            if (info.memProps.memoryTypes[i].propertyFlags &
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) {
                pProperties->memoryTypeBits |= 1ull << i;
            }
        }
        return VK_SUCCESS;
    }

    VkResult on_vkImportSemaphoreZirconHandleFUCHSIA(
        void*, VkResult,
        VkDevice device,
        const VkImportSemaphoreZirconHandleInfoFUCHSIA* pInfo) {

        if (!pInfo) return VK_ERROR_INITIALIZATION_FAILED;
        if (!pInfo->semaphore) return VK_ERROR_INITIALIZATION_FAILED;

        AutoLock lock(mLock);

        auto deviceIt = info_VkDevice.find(device);

        if (deviceIt == info_VkDevice.end()) {
            return VK_ERROR_INITIALIZATION_FAILED;
        }

        auto semaphoreIt = info_VkSemaphore.find(pInfo->semaphore);

        if (semaphoreIt == info_VkSemaphore.end()) {
            return VK_ERROR_INITIALIZATION_FAILED;
        }

        auto& info = semaphoreIt->second;

        if (info.eventHandle != ZX_HANDLE_INVALID) {
            zx_handle_close(info.eventHandle);
        }
        info.eventHandle = pInfo->handle;

        return VK_SUCCESS;
    }

    VkResult on_vkGetSemaphoreZirconHandleFUCHSIA(
        void*, VkResult,
        VkDevice device,
        const VkSemaphoreGetZirconHandleInfoFUCHSIA* pInfo,
        uint32_t* pHandle) {

        if (!pInfo) return VK_ERROR_INITIALIZATION_FAILED;
        if (!pInfo->semaphore) return VK_ERROR_INITIALIZATION_FAILED;

        AutoLock lock(mLock);

        auto deviceIt = info_VkDevice.find(device);

        if (deviceIt == info_VkDevice.end()) {
            return VK_ERROR_INITIALIZATION_FAILED;
        }

        auto semaphoreIt = info_VkSemaphore.find(pInfo->semaphore);

        if (semaphoreIt == info_VkSemaphore.end()) {
            return VK_ERROR_INITIALIZATION_FAILED;
        }

        auto& info = semaphoreIt->second;

        if (info.eventHandle == ZX_HANDLE_INVALID) {
            return VK_ERROR_INITIALIZATION_FAILED;
        }

        *pHandle = ZX_HANDLE_INVALID;
        zx_handle_duplicate(info.eventHandle, ZX_RIGHT_SAME_RIGHTS, pHandle);
        return VK_SUCCESS;
    }

    VkResult on_vkCreateBufferCollectionFUCHSIA(
        void*, VkResult, VkDevice,
        const VkBufferCollectionCreateInfoFUCHSIA* pInfo,
        const VkAllocationCallbacks*,
        VkBufferCollectionFUCHSIA* pCollection) {
        fuchsia::sysmem::BufferCollectionTokenSyncPtr token;
        if (pInfo->collectionToken) {
            token.Bind(zx::channel(pInfo->collectionToken));
        } else {
            zx_status_t status = mSysmemAllocator->AllocateSharedCollection(token.NewRequest());
            if (status != ZX_OK) {
                ALOGE("AllocateSharedCollection failed: %d", status);
                return VK_ERROR_INITIALIZATION_FAILED;
            }
        }
        auto sysmem_collection = new fuchsia::sysmem::BufferCollectionSyncPtr;
        zx_status_t status = mSysmemAllocator->BindSharedCollection(
            std::move(token), sysmem_collection->NewRequest());
        if (status != ZX_OK) {
            ALOGE("BindSharedCollection failed: %d", status);
            return VK_ERROR_INITIALIZATION_FAILED;
        }
        *pCollection = reinterpret_cast<VkBufferCollectionFUCHSIA>(sysmem_collection);
        return VK_SUCCESS;
    }

    void on_vkDestroyBufferCollectionFUCHSIA(
        void*, VkResult, VkDevice,
        VkBufferCollectionFUCHSIA collection,
        const VkAllocationCallbacks*) {
        auto sysmem_collection = reinterpret_cast<fuchsia::sysmem::BufferCollectionSyncPtr*>(collection);
        if (sysmem_collection->is_bound()) {
            (*sysmem_collection)->Close();
        }
        delete sysmem_collection;
    }

    void setBufferCollectionConstraints(fuchsia::sysmem::BufferCollectionSyncPtr* collection,
                                        const VkImageCreateInfo* pImageInfo,
                                        size_t min_size_bytes) {
        fuchsia::sysmem::BufferCollectionConstraints constraints = {};
        constraints.usage.vulkan = fuchsia::sysmem::vulkanUsageColorAttachment |
                                   fuchsia::sysmem::vulkanUsageTransferSrc |
                                   fuchsia::sysmem::vulkanUsageTransferDst |
                                   fuchsia::sysmem::vulkanUsageSampled;
        constraints.min_buffer_count_for_camping = 1;
        constraints.has_buffer_memory_constraints = true;
        fuchsia::sysmem::BufferMemoryConstraints& buffer_constraints =
            constraints.buffer_memory_constraints;
        buffer_constraints.min_size_bytes = min_size_bytes;
        buffer_constraints.max_size_bytes = 0xffffffff;
        buffer_constraints.physically_contiguous_required = false;
        buffer_constraints.secure_required = false;
        buffer_constraints.secure_permitted = false;
        buffer_constraints.ram_domain_supported = false;
        buffer_constraints.cpu_domain_supported = false;
        buffer_constraints.gpu_domain_supported = true;
        constraints.image_format_constraints_count = 1;
        fuchsia::sysmem::ImageFormatConstraints& image_constraints =
            constraints.image_format_constraints[0];
        image_constraints.pixel_format.type = fuchsia::sysmem::PixelFormatType::BGRA32;
        image_constraints.color_spaces_count = 1;
        image_constraints.color_space[0].type = fuchsia::sysmem::ColorSpaceType::SRGB;
        image_constraints.min_coded_width = pImageInfo->extent.width;
        image_constraints.max_coded_width = 0xfffffff;
        image_constraints.min_coded_height = pImageInfo->extent.height;
        image_constraints.max_coded_height = 0xffffffff;
        image_constraints.min_bytes_per_row = pImageInfo->extent.width * 4;
        image_constraints.max_bytes_per_row = 0xffffffff;
        image_constraints.max_coded_width_times_coded_height = 0xffffffff;
        image_constraints.layers = 1;
        image_constraints.coded_width_divisor = 1;
        image_constraints.coded_height_divisor = 1;
        image_constraints.bytes_per_row_divisor = 1;
        image_constraints.start_offset_divisor = 1;
        image_constraints.display_width_divisor = 1;
        image_constraints.display_height_divisor = 1;

        (*collection)->SetConstraints(true, constraints);
    }

    VkResult on_vkSetBufferCollectionConstraintsFUCHSIA(
        void*, VkResult, VkDevice,
        VkBufferCollectionFUCHSIA collection,
        const VkImageCreateInfo* pImageInfo) {
        auto sysmem_collection =
            reinterpret_cast<fuchsia::sysmem::BufferCollectionSyncPtr*>(collection);
        setBufferCollectionConstraints(
            sysmem_collection, pImageInfo,
            pImageInfo->extent.width * pImageInfo->extent.height * 4);
        return VK_SUCCESS;
    }

    VkResult on_vkGetBufferCollectionPropertiesFUCHSIA(
        void*, VkResult,
        VkDevice device,
        VkBufferCollectionFUCHSIA collection,
        VkBufferCollectionPropertiesFUCHSIA* pProperties) {
        auto sysmem_collection = reinterpret_cast<fuchsia::sysmem::BufferCollectionSyncPtr*>(collection);
        fuchsia::sysmem::BufferCollectionInfo_2 info;
        zx_status_t status2;
        zx_status_t status = (*sysmem_collection)->WaitForBuffersAllocated(&status2, &info);
        if (status != ZX_OK || status2 != ZX_OK) {
            ALOGE("Failed wait for allocation: %d %d", status, status2);
            return VK_ERROR_INITIALIZATION_FAILED;
        }
        if (!info.settings.has_image_format_constraints) {
            return VK_ERROR_INITIALIZATION_FAILED;
        }
        pProperties->count = info.buffer_count;

        AutoLock lock(mLock);

        auto deviceIt = info_VkDevice.find(device);

        if (deviceIt == info_VkDevice.end()) {
            return VK_ERROR_INITIALIZATION_FAILED;
        }

        auto& deviceInfo = deviceIt->second;

        // Device local memory type supported.
        pProperties->memoryTypeBits = 0;
        for (uint32_t i = 0; i < deviceInfo.memProps.memoryTypeCount; ++i) {
            if (deviceInfo.memProps.memoryTypes[i].propertyFlags &
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) {
                pProperties->memoryTypeBits |= 1ull << i;
            }
        }
        return VK_SUCCESS;
    }
#endif

    HostMemBlockIndex getOrAllocateHostMemBlockLocked(
        HostMemBlocks& blocks,
        const VkMemoryAllocateInfo* pAllocateInfo,
        VkEncoder* enc,
        VkDevice device,
        const VkDevice_Info& deviceInfo) {

        HostMemBlockIndex res = 0;
        bool found = false;

        while (!found) {
            for (HostMemBlockIndex i = 0; i < blocks.size(); ++i) {
                if (blocks[i].initialized &&
                    blocks[i].initResult == VK_SUCCESS &&
                    canSubAlloc(
                        blocks[i].subAlloc,
                        pAllocateInfo->allocationSize)) {
                    res = i;
                    found = true;
                    return res;
                }
            }

            blocks.push_back({});

            auto& hostMemAlloc = blocks.back();

            // Uninitialized block; allocate on host.
            static constexpr VkDeviceSize oneMb = 1048576;
            static constexpr VkDeviceSize kDefaultHostMemBlockSize =
                16 * oneMb; // 16 mb
            VkDeviceSize roundedUpAllocSize =
                oneMb * ((pAllocateInfo->allocationSize + oneMb - 1) / oneMb);

            VkDeviceSize virtualHeapSize = VIRTUAL_HOST_VISIBLE_HEAP_SIZE;

            VkDeviceSize blockSizeNeeded =
                std::max(roundedUpAllocSize,
                    std::min(virtualHeapSize,
                             kDefaultHostMemBlockSize));

            VkMemoryAllocateInfo allocInfoForHost = *pAllocateInfo;

            allocInfoForHost.allocationSize = blockSizeNeeded;

            // TODO: Support dedicated/external host visible allocation
            allocInfoForHost.pNext = nullptr;

            mLock.unlock();
            VkResult host_res =
                enc->vkAllocateMemory(
                    device,
                    &allocInfoForHost,
                    nullptr,
                    &hostMemAlloc.memory);
            mLock.lock();

            if (host_res != VK_SUCCESS) {
                ALOGE("Could not allocate backing for virtual host visible memory: %d",
                      host_res);
                hostMemAlloc.initialized = true;
                hostMemAlloc.initResult = host_res;
                return INVALID_HOST_MEM_BLOCK;
            }

            auto& hostMemInfo = info_VkDeviceMemory[hostMemAlloc.memory];
            hostMemInfo.allocationSize = allocInfoForHost.allocationSize;
            VkDeviceSize nonCoherentAtomSize =
                deviceInfo.props.limits.nonCoherentAtomSize;
            hostMemInfo.mappedSize = hostMemInfo.allocationSize;
            hostMemInfo.memoryTypeIndex =
                pAllocateInfo->memoryTypeIndex;
            hostMemAlloc.nonCoherentAtomSize = nonCoherentAtomSize;

            uint64_t directMappedAddr = 0;

            mLock.unlock();
            VkResult directMapResult =
                enc->vkMapMemoryIntoAddressSpaceGOOGLE(
                    device, hostMemAlloc.memory, &directMappedAddr);
            mLock.lock();

            if (directMapResult != VK_SUCCESS) {
                hostMemAlloc.initialized = true;
                hostMemAlloc.initResult = directMapResult;
                mLock.unlock();
                enc->vkFreeMemory(device, hostMemAlloc.memory, nullptr);
                mLock.lock();
                return INVALID_HOST_MEM_BLOCK;
            }

            hostMemInfo.mappedPtr =
                (uint8_t*)(uintptr_t)directMappedAddr;
            hostMemInfo.virtualHostVisibleBacking = true;

            VkResult hostMemAllocRes =
                finishHostMemAllocInit(
                    enc,
                    device,
                    pAllocateInfo->memoryTypeIndex,
                    nonCoherentAtomSize,
                    hostMemInfo.allocationSize,
                    hostMemInfo.mappedSize,
                    hostMemInfo.mappedPtr,
                    &hostMemAlloc);

            if (hostMemAllocRes != VK_SUCCESS) {
                return INVALID_HOST_MEM_BLOCK;
            }
        }

        // unreacheable, but we need to make Werror happy
        return INVALID_HOST_MEM_BLOCK;
    }

    VkResult on_vkAllocateMemory(
        void* context,
        VkResult input_result,
        VkDevice device,
        const VkMemoryAllocateInfo* pAllocateInfo,
        const VkAllocationCallbacks* pAllocator,
        VkDeviceMemory* pMemory) {

        if (input_result != VK_SUCCESS) return input_result;

        VkEncoder* enc = (VkEncoder*)context;

        VkMemoryAllocateInfo finalAllocInfo = vk_make_orphan_copy(*pAllocateInfo);
        vk_struct_chain_iterator structChainIter = vk_make_chain_iterator(&finalAllocInfo);

        VkMemoryDedicatedAllocateInfo dedicatedAllocInfo;
        VkImportColorBufferGOOGLE importCbInfo = {
            VK_STRUCTURE_TYPE_IMPORT_COLOR_BUFFER_GOOGLE, 0,
        };
        // VkImportPhysicalAddressGOOGLE importPhysAddrInfo = {
        //     VK_STRUCTURE_TYPE_IMPORT_PHYSICAL_ADDRESS_GOOGLE, 0,
        // };

        const VkExportMemoryAllocateInfo* exportAllocateInfoPtr =
            vk_find_struct<VkExportMemoryAllocateInfo>(pAllocateInfo);

        const VkImportAndroidHardwareBufferInfoANDROID* importAhbInfoPtr =
            vk_find_struct<VkImportAndroidHardwareBufferInfoANDROID>(pAllocateInfo);

        const VkImportMemoryBufferCollectionFUCHSIA* importBufferCollectionInfoPtr =
            vk_find_struct<VkImportMemoryBufferCollectionFUCHSIA>(pAllocateInfo);

        const VkImportMemoryZirconHandleInfoFUCHSIA* importVmoInfoPtr =
            vk_find_struct<VkImportMemoryZirconHandleInfoFUCHSIA>(pAllocateInfo);

        const VkMemoryDedicatedAllocateInfo* dedicatedAllocInfoPtr =
            vk_find_struct<VkMemoryDedicatedAllocateInfo>(pAllocateInfo);

        bool shouldPassThroughDedicatedAllocInfo =
            !exportAllocateInfoPtr &&
            !importAhbInfoPtr &&
            !importBufferCollectionInfoPtr &&
            !importVmoInfoPtr &&
            !isHostVisibleMemoryTypeIndexForGuest(
                &mHostVisibleMemoryVirtInfo,
                pAllocateInfo->memoryTypeIndex);

        if (!exportAllocateInfoPtr &&
            (importAhbInfoPtr || importBufferCollectionInfoPtr || importVmoInfoPtr) &&
            dedicatedAllocInfoPtr &&
            isHostVisibleMemoryTypeIndexForGuest(
                &mHostVisibleMemoryVirtInfo,
                pAllocateInfo->memoryTypeIndex)) {
            ALOGE("FATAL: It is not yet supported to import-allocate "
                  "external memory that is both host visible and dedicated.");
            abort();
        }

        if (shouldPassThroughDedicatedAllocInfo &&
            dedicatedAllocInfoPtr) {
            dedicatedAllocInfo = vk_make_orphan_copy(*dedicatedAllocInfoPtr);
            vk_append_struct(&structChainIter, &dedicatedAllocInfo);
        }

        // State needed for import/export.
        bool exportAhb = false;
        bool exportVmo = false;
        bool importAhb = false;
        bool importBufferCollection = false;
        bool importVmo = false;
        (void)exportVmo;

        // Even if we export allocate, the underlying operation
        // for the host is always going to be an import operation.
        // This is also how Intel's implementation works,
        // and is generally simpler;
        // even in an export allocation,
        // we perform AHardwareBuffer allocation
        // on the guest side, at this layer,
        // and then we attach a new VkDeviceMemory
        // to the AHardwareBuffer on the host via an "import" operation.
        AHardwareBuffer* ahw = nullptr;

        if (exportAllocateInfoPtr) {
            exportAhb =
                exportAllocateInfoPtr->handleTypes &
                VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
            exportVmo =
                exportAllocateInfoPtr->handleTypes &
                VK_EXTERNAL_MEMORY_HANDLE_TYPE_TEMP_ZIRCON_VMO_BIT_FUCHSIA;
        } else if (importAhbInfoPtr) {
            importAhb = true;
        } else if (importBufferCollectionInfoPtr) {
            importBufferCollection = true;
        } else if (importVmoInfoPtr) {
            importVmo = true;
        }

        if (exportAhb) {
            bool hasDedicatedImage = dedicatedAllocInfoPtr &&
                (dedicatedAllocInfoPtr->image != VK_NULL_HANDLE);
            bool hasDedicatedBuffer = dedicatedAllocInfoPtr &&
                (dedicatedAllocInfoPtr->buffer != VK_NULL_HANDLE);
            VkExtent3D imageExtent = { 0, 0, 0 };
            uint32_t imageLayers = 0;
            VkFormat imageFormat = VK_FORMAT_UNDEFINED;
            VkImageUsageFlags imageUsage = 0;
            VkImageCreateFlags imageCreateFlags = 0;
            VkDeviceSize bufferSize = 0;
            VkDeviceSize allocationInfoAllocSize =
                finalAllocInfo.allocationSize;

            if (hasDedicatedImage) {
                AutoLock lock(mLock);

                auto it = info_VkImage.find(
                    dedicatedAllocInfoPtr->image);
                if (it == info_VkImage.end()) return VK_ERROR_INITIALIZATION_FAILED;
                const auto& info = it->second;
                const auto& imgCi = info.createInfo;

                imageExtent = imgCi.extent;
                imageLayers = imgCi.arrayLayers;
                imageFormat = imgCi.format;
                imageUsage = imgCi.usage;
                imageCreateFlags = imgCi.flags;
            }

            if (hasDedicatedBuffer) {
                AutoLock lock(mLock);

                auto it = info_VkBuffer.find(
                    dedicatedAllocInfoPtr->buffer);
                if (it == info_VkBuffer.end()) return VK_ERROR_INITIALIZATION_FAILED;
                const auto& info = it->second;
                const auto& bufCi = info.createInfo;

                bufferSize = bufCi.size;
            }

            VkResult ahbCreateRes =
                createAndroidHardwareBuffer(
                    hasDedicatedImage,
                    hasDedicatedBuffer,
                    imageExtent,
                    imageLayers,
                    imageFormat,
                    imageUsage,
                    imageCreateFlags,
                    bufferSize,
                    allocationInfoAllocSize,
                    &ahw);

            if (ahbCreateRes != VK_SUCCESS) {
                return ahbCreateRes;
            }
        }

        if (importAhb) {
            ahw = importAhbInfoPtr->buffer;
            // We still need to acquire the AHardwareBuffer.
            importAndroidHardwareBuffer(
                importAhbInfoPtr, nullptr);
        }

        if (ahw) {
            ALOGD("%s: Import AHardwareBulffer", __func__);
            const native_handle_t *handle =
                AHardwareBuffer_getNativeHandle(ahw);
            const cb_handle_t* cb_handle =
                reinterpret_cast<const cb_handle_t*>(handle);
            importCbInfo.colorBuffer = cb_handle->hostHandle;
            vk_append_struct(&structChainIter, &importCbInfo);
        }

        zx_handle_t vmo_handle = ZX_HANDLE_INVALID;

        if (importBufferCollection) {

#ifdef VK_USE_PLATFORM_FUCHSIA
            auto collection = reinterpret_cast<fuchsia::sysmem::BufferCollectionSyncPtr*>(
                importBufferCollectionInfoPtr->collection);
            fuchsia::sysmem::BufferCollectionInfo_2 info;
            zx_status_t status2;
            zx_status_t status = (*collection)->WaitForBuffersAllocated(&status2, &info);
            if (status != ZX_OK || status2 != ZX_OK) {
                ALOGE("WaitForBuffersAllocated failed: %d %d", status);
                return VK_ERROR_INITIALIZATION_FAILED;
            }
            uint32_t index = importBufferCollectionInfoPtr->index;
            if (info.buffer_count < index) {
                ALOGE("Invalid buffer index: %d %d", index);
                return VK_ERROR_INITIALIZATION_FAILED;
            }
            vmo_handle = info.buffers[index].vmo.release();
#endif

        }

        if (importVmo) {
            vmo_handle = importVmoInfoPtr->handle;
        }

#ifdef VK_USE_PLATFORM_FUCHSIA
        if (vmo_handle == ZX_HANDLE_INVALID &&
            !isHostVisibleMemoryTypeIndexForGuest(
                &mHostVisibleMemoryVirtInfo, finalAllocInfo.memoryTypeIndex)) {
            bool hasDedicatedImage = dedicatedAllocInfoPtr &&
                (dedicatedAllocInfoPtr->image != VK_NULL_HANDLE);
            VkImageCreateInfo imageCreateInfo = {};

            if (hasDedicatedImage) {
                AutoLock lock(mLock);

                auto it = info_VkImage.find(dedicatedAllocInfoPtr->image);
                if (it == info_VkImage.end()) return VK_ERROR_INITIALIZATION_FAILED;
                const auto& imageInfo = it->second;

                imageCreateInfo = imageInfo.createInfo;
            }

            if (imageCreateInfo.usage & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) {
                fuchsia::sysmem::BufferCollectionTokenSyncPtr token;
                zx_status_t status = mSysmemAllocator->AllocateSharedCollection(
                    token.NewRequest());
                if (status != ZX_OK) {
                    ALOGE("AllocateSharedCollection failed: %d", status);
                    abort();
                }

                fuchsia::sysmem::BufferCollectionSyncPtr collection;
                status = mSysmemAllocator->BindSharedCollection(
                    std::move(token), collection.NewRequest());
                if (status != ZX_OK) {
                    ALOGE("BindSharedCollection failed: %d", status);
                    abort();
                }
                setBufferCollectionConstraints(&collection,
                                               &imageCreateInfo,
                                               finalAllocInfo.allocationSize);

                fuchsia::sysmem::BufferCollectionInfo_2 info;
                zx_status_t status2;
                status = collection->WaitForBuffersAllocated(&status2, &info);
                if (status == ZX_OK && status2 == ZX_OK) {
                    if (!info.buffer_count) {
                      ALOGE("WaitForBuffersAllocated returned invalid count: %d", status);
                      abort();
                    }
                    vmo_handle = info.buffers[0].vmo.release();
                } else {
                    ALOGE("WaitForBuffersAllocated failed: %d %d", status, status2);
                    abort();
                }

                collection->Close();

                zx_handle_t vmo_copy;
                status = zx_handle_duplicate(vmo_handle, ZX_RIGHT_SAME_RIGHTS, &vmo_copy);
                if (status != ZX_OK) {
                    ALOGE("Failed to duplicate VMO: %d", status);
                    abort();
                }
                status = fuchsia_hardware_goldfish_control_DeviceCreateColorBuffer(
                    mControlDevice,
                    vmo_copy,
                    imageCreateInfo.extent.width,
                    imageCreateInfo.extent.height,
                    fuchsia_hardware_goldfish_control_FormatType_BGRA,
                    &status2);
                if (status != ZX_OK || status2 != ZX_OK) {
                    ALOGE("CreateColorBuffer failed: %d:%d", status, status2);
                    abort();
                }
            }
        }

        if (vmo_handle != ZX_HANDLE_INVALID) {
            zx_handle_t vmo_copy;
            zx_status_t status = zx_handle_duplicate(vmo_handle,
                                                     ZX_RIGHT_SAME_RIGHTS,
                                                     &vmo_copy);
            if (status != ZX_OK) {
                ALOGE("Failed to duplicate VMO: %d", status);
                abort();
            }
            zx_status_t status2 = ZX_OK;
            status = fuchsia_hardware_goldfish_control_DeviceGetColorBuffer(
                mControlDevice, vmo_copy, &status2, &importCbInfo.colorBuffer);
            if (status != ZX_OK || status2 != ZX_OK) {
                ALOGE("GetColorBuffer failed: %d:%d", status, status2);
            }
            vk_append_struct(&structChainIter, &importCbInfo);
        }
#endif

        // TODO if (exportVmo) { }

        if (!isHostVisibleMemoryTypeIndexForGuest(
                &mHostVisibleMemoryVirtInfo,
                finalAllocInfo.memoryTypeIndex)) {
            input_result =
                enc->vkAllocateMemory(
                    device, &finalAllocInfo, pAllocator, pMemory);

            if (input_result != VK_SUCCESS) return input_result;

            VkDeviceSize allocationSize = finalAllocInfo.allocationSize;
            setDeviceMemoryInfo(
                device, *pMemory,
                finalAllocInfo.allocationSize,
                0, nullptr,
                finalAllocInfo.memoryTypeIndex,
                ahw,
                vmo_handle);

            return VK_SUCCESS;
        }

        // Device-local memory dealing is over. What follows:
        // host-visible memory.

        if (ahw) {
            ALOGE("%s: Host visible export/import allocation "
                  "of Android hardware buffers is not supported.",
                  __func__);
            abort();
        }

        if (vmo_handle != ZX_HANDLE_INVALID) {
            ALOGE("%s: Host visible export/import allocation "
                  "of VMO is not supported yet.",
                  __func__);
            abort();
        }

        // Host visible memory, non external
        bool directMappingSupported = usingDirectMapping();
        if (!directMappingSupported) {
            input_result =
                enc->vkAllocateMemory(
                    device, &finalAllocInfo, pAllocator, pMemory);

            if (input_result != VK_SUCCESS) return input_result;

            VkDeviceSize mappedSize =
                getNonCoherentExtendedSize(device,
                    finalAllocInfo.allocationSize);
            uint8_t* mappedPtr = (uint8_t*)aligned_buf_alloc(4096, mappedSize);
            D("host visible alloc (non-direct): "
              "size 0x%llx host ptr %p mapped size 0x%llx",
              (unsigned long long)finalAllocInfo.allocationSize, mappedPtr,
              (unsigned long long)mappedSize);
            setDeviceMemoryInfo(
                device, *pMemory,
                finalAllocInfo.allocationSize,
                mappedSize, mappedPtr,
                finalAllocInfo.memoryTypeIndex);
            return VK_SUCCESS;
        }

        // Host visible memory with direct mapping via
        // VkImportPhysicalAddressGOOGLE
        // if (importPhysAddr) {
            // vkAllocateMemory(device, &finalAllocInfo, pAllocator, pMemory);
            //    host maps the host pointer to the guest physical address
            // TODO: the host side page offset of the
            // host pointer needs to be returned somehow.
        // }

        // Host visible memory with direct mapping
        AutoLock lock(mLock);

        auto it = info_VkDevice.find(device);
        if (it == info_VkDevice.end()) return VK_ERROR_DEVICE_LOST;
        auto& deviceInfo = it->second;

        auto& hostMemBlocksForTypeIndex =
            deviceInfo.hostMemBlocks[finalAllocInfo.memoryTypeIndex];

        HostMemBlockIndex blockIndex =
            getOrAllocateHostMemBlockLocked(
                hostMemBlocksForTypeIndex,
                &finalAllocInfo,
                enc,
                device,
                deviceInfo);

        if (blockIndex == (HostMemBlockIndex) INVALID_HOST_MEM_BLOCK) {
            return VK_ERROR_OUT_OF_HOST_MEMORY;
        }

        VkDeviceMemory_Info virtualMemInfo;

        subAllocHostMemory(
            &hostMemBlocksForTypeIndex[blockIndex],
            &finalAllocInfo,
            &virtualMemInfo.subAlloc);

        virtualMemInfo.allocationSize = virtualMemInfo.subAlloc.subAllocSize;
        virtualMemInfo.mappedSize = virtualMemInfo.subAlloc.subMappedSize;
        virtualMemInfo.mappedPtr = virtualMemInfo.subAlloc.mappedPtr;
        virtualMemInfo.memoryTypeIndex = finalAllocInfo.memoryTypeIndex;
        virtualMemInfo.directMapped = true;

        D("host visible alloc (direct, suballoc): "
          "size 0x%llx ptr %p mapped size 0x%llx",
          (unsigned long long)virtualMemInfo.allocationSize, virtualMemInfo.mappedPtr,
          (unsigned long long)virtualMemInfo.mappedSize);

        info_VkDeviceMemory[
            virtualMemInfo.subAlloc.subMemory] = virtualMemInfo;

        *pMemory = virtualMemInfo.subAlloc.subMemory;

        return VK_SUCCESS;
    }

    void on_vkFreeMemory(
        void* context,
        VkDevice device,
        VkDeviceMemory memory,
        const VkAllocationCallbacks* pAllocateInfo) {

        AutoLock lock(mLock);

        auto it = info_VkDeviceMemory.find(memory);
        if (it == info_VkDeviceMemory.end()) return;
        auto& info = it->second;

        if (!info.directMapped) {
            lock.unlock();
            VkEncoder* enc = (VkEncoder*)context;
            enc->vkFreeMemory(device, memory, pAllocateInfo);
            return;
        }

        subFreeHostMemory(&info.subAlloc);
    }

    VkResult on_vkMapMemory(
        void*,
        VkResult host_result,
        VkDevice,
        VkDeviceMemory memory,
        VkDeviceSize offset,
        VkDeviceSize size,
        VkMemoryMapFlags,
        void** ppData) {

        if (host_result != VK_SUCCESS) return host_result;

        AutoLock lock(mLock);

        auto it = info_VkDeviceMemory.find(memory);
        if (it == info_VkDeviceMemory.end()) return VK_ERROR_MEMORY_MAP_FAILED;

        auto& info = it->second;

        if (!info.mappedPtr) return VK_ERROR_MEMORY_MAP_FAILED;

        if (size != VK_WHOLE_SIZE &&
            (info.mappedPtr + offset + size > info.mappedPtr + info.allocationSize)) {
            return VK_ERROR_MEMORY_MAP_FAILED;
        }

        *ppData = info.mappedPtr + offset;

        return host_result;
    }

    void on_vkUnmapMemory(
        void*,
        VkDevice,
        VkDeviceMemory) {
        // no-op
    }

    uint32_t transformNonExternalResourceMemoryTypeBitsForGuest(
        uint32_t hostBits) {
        uint32_t res = 0;
        for (uint32_t i = 0; i < VK_MAX_MEMORY_TYPES; ++i) {
            if (isNoFlagsMemoryTypeIndexForGuest(
                    &mHostVisibleMemoryVirtInfo, i)) continue;
            if (hostBits & (1 << i)) {
                res |= (1 << i);
            }
        }
        return res;
    }

    uint32_t transformExternalResourceMemoryTypeBitsForGuest(
        uint32_t normalBits) {
        uint32_t res = 0;
        for (uint32_t i = 0; i < VK_MAX_MEMORY_TYPES; ++i) {
            if (isNoFlagsMemoryTypeIndexForGuest(
                    &mHostVisibleMemoryVirtInfo, i)) continue;
            if (normalBits & (1 << i) &&
                !isHostVisibleMemoryTypeIndexForGuest(
                    &mHostVisibleMemoryVirtInfo, i)) {
                res |= (1 << i);
            }
        }
        return res;
    }

    void transformNonExternalResourceMemoryRequirementsForGuest(
        VkMemoryRequirements* reqs) {
        reqs->memoryTypeBits =
            transformNonExternalResourceMemoryTypeBitsForGuest(
                reqs->memoryTypeBits);
    }

    void transformExternalResourceMemoryRequirementsForGuest(
        VkMemoryRequirements* reqs) {
        reqs->memoryTypeBits =
            transformExternalResourceMemoryTypeBitsForGuest(
                reqs->memoryTypeBits);
    }

    void transformExternalResourceMemoryDedicatedRequirementsForGuest(
        VkMemoryDedicatedRequirements* dedicatedReqs) {
        dedicatedReqs->prefersDedicatedAllocation = VK_TRUE;
        dedicatedReqs->requiresDedicatedAllocation = VK_TRUE;
    }

    void transformImageMemoryRequirementsForGuest(
        VkImage image,
        VkMemoryRequirements* reqs) {

        AutoLock lock(mLock);

        auto it = info_VkImage.find(image);
        if (it == info_VkImage.end()) return;

        auto& info = it->second;

        if (!info.external ||
            !info.externalCreateInfo.handleTypes) {
            transformNonExternalResourceMemoryRequirementsForGuest(reqs);
            return;
        }

        transformExternalResourceMemoryRequirementsForGuest(reqs);
    }

    void transformBufferMemoryRequirementsForGuest(
        VkBuffer buffer,
        VkMemoryRequirements* reqs) {

        AutoLock lock(mLock);

        auto it = info_VkBuffer.find(buffer);
        if (it == info_VkBuffer.end()) return;

        auto& info = it->second;

        if (!info.external ||
            !info.externalCreateInfo.handleTypes) {
            transformNonExternalResourceMemoryRequirementsForGuest(reqs);
            return;
        }

        transformExternalResourceMemoryRequirementsForGuest(reqs);
    }

    void transformImageMemoryRequirements2ForGuest(
        VkImage image,
        VkMemoryRequirements2* reqs2) {

        AutoLock lock(mLock);

        auto it = info_VkImage.find(image);
        if (it == info_VkImage.end()) return;

        auto& info = it->second;

        if (!info.external ||
            !info.externalCreateInfo.handleTypes) {
            transformNonExternalResourceMemoryRequirementsForGuest(
                &reqs2->memoryRequirements);
            return;
        }

        transformExternalResourceMemoryRequirementsForGuest(&reqs2->memoryRequirements);

        VkMemoryDedicatedRequirements* dedicatedReqs =
            vk_find_struct<VkMemoryDedicatedRequirements>(reqs2);

        if (!dedicatedReqs) return;

        transformExternalResourceMemoryDedicatedRequirementsForGuest(
            dedicatedReqs);
    }

    void transformBufferMemoryRequirements2ForGuest(
        VkBuffer buffer,
        VkMemoryRequirements2* reqs2) {

        AutoLock lock(mLock);

        auto it = info_VkBuffer.find(buffer);
        if (it == info_VkBuffer.end()) return;

        auto& info = it->second;

        if (!info.external ||
            !info.externalCreateInfo.handleTypes) {
            transformNonExternalResourceMemoryRequirementsForGuest(
                &reqs2->memoryRequirements);
            return;
        }

        transformExternalResourceMemoryRequirementsForGuest(&reqs2->memoryRequirements);

        VkMemoryDedicatedRequirements* dedicatedReqs =
            vk_find_struct<VkMemoryDedicatedRequirements>(reqs2);

        if (!dedicatedReqs) return;

        transformExternalResourceMemoryDedicatedRequirementsForGuest(
            dedicatedReqs);
    }

    VkResult on_vkCreateImage(
        void* context, VkResult,
        VkDevice device, const VkImageCreateInfo *pCreateInfo,
        const VkAllocationCallbacks *pAllocator,
        VkImage *pImage) {
        VkEncoder* enc = (VkEncoder*)context;

        VkImageCreateInfo localCreateInfo = vk_make_orphan_copy(*pCreateInfo);
        vk_struct_chain_iterator structChainIter = vk_make_chain_iterator(&localCreateInfo);
        VkExternalMemoryImageCreateInfo localExtImgCi;

        const VkExternalMemoryImageCreateInfo* extImgCiPtr =
            vk_find_struct<VkExternalMemoryImageCreateInfo>(pCreateInfo);
        if (extImgCiPtr) {
            localExtImgCi = vk_make_orphan_copy(*extImgCiPtr);
            vk_append_struct(&structChainIter, &localExtImgCi);
        }

#ifdef VK_USE_PLATFORM_ANDROID_KHR
        VkNativeBufferANDROID localAnb;
        const VkNativeBufferANDROID* anbInfoPtr =
            vk_find_struct<VkNativeBufferANDROID>(pCreateInfo);
        if (anbInfoPtr) {
            localAnb = vk_make_orphan_copy(*anbInfoPtr);
            vk_append_struct(&structChainIter, &localAnb);
        }

        VkExternalFormatANDROID localExtFormatAndroid;
        const VkExternalFormatANDROID* extFormatAndroidPtr =
            vk_find_struct<VkExternalFormatANDROID>(pCreateInfo);
        if (extFormatAndroidPtr) {
            localExtFormatAndroid = vk_make_orphan_copy(*extFormatAndroidPtr);

            // Do not append external format android;
            // instead, replace the local image localCreateInfo format
            // with the corresponding Vulkan format
            if (extFormatAndroidPtr->externalFormat) {
                localCreateInfo.format =
                    vk_format_from_android(extFormatAndroidPtr->externalFormat);
                if (localCreateInfo.format == VK_FORMAT_UNDEFINED)
                    return VK_ERROR_VALIDATION_FAILED_EXT;
            }
        }
#endif

#ifdef VK_USE_PLATFORM_FUCHSIA
        const VkBufferCollectionImageCreateInfoFUCHSIA* extBufferCollectionPtr =
            vk_find_struct<VkBufferCollectionImageCreateInfoFUCHSIA>(pCreateInfo);
        if (extBufferCollectionPtr) {
            auto collection = reinterpret_cast<fuchsia::sysmem::BufferCollectionSyncPtr*>(
                extBufferCollectionPtr->collection);
            uint32_t index = extBufferCollectionPtr->index;
            zx_handle_t vmo_handle = ZX_HANDLE_INVALID;

            fuchsia::sysmem::BufferCollectionInfo_2 info;
            zx_status_t status2;
            zx_status_t status = (*collection)->WaitForBuffersAllocated(&status2, &info);
            if (status == ZX_OK && status2 == ZX_OK) {
                if (index < info.buffer_count) {
                    vmo_handle = info.buffers[index].vmo.release();
                }
            } else {
                ALOGE("WaitForBuffersAllocated failed: %d %d", status, status2);
            }

            if (vmo_handle != ZX_HANDLE_INVALID) {
                zx_status_t status2 = ZX_OK;
                status = fuchsia_hardware_goldfish_control_DeviceCreateColorBuffer(
                    mControlDevice,
                    vmo_handle,
                    localCreateInfo.extent.width,
                    localCreateInfo.extent.height,
                    fuchsia_hardware_goldfish_control_FormatType_BGRA,
                    &status2);
                if (status != ZX_OK || status2 != ZX_OK) {
                    ALOGE("CreateColorBuffer failed: %d:%d", status, status2);
                }
            }
        }

        // Allow external memory for all color attachments on fuchsia.
        if (localCreateInfo.usage & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) {
            if (!extImgCiPtr) {
                localExtImgCi.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
                localExtImgCi.pNext = nullptr;
                localExtImgCi.handleTypes = ~0; // handle type just needs to be non-zero
                extImgCiPtr = &localExtImgCi;   // no vk_append_struct required
            }
        }
#endif

        VkResult res = enc->vkCreateImage(device, &localCreateInfo, pAllocator, pImage);

        if (res != VK_SUCCESS) return res;

        AutoLock lock(mLock);

        auto it = info_VkImage.find(*pImage);
        if (it == info_VkImage.end()) return VK_ERROR_INITIALIZATION_FAILED;

        auto& info = it->second;

        info.device = device;
        info.createInfo = *pCreateInfo;
        info.createInfo.pNext = nullptr;

        if (!extImgCiPtr) return res;

        info.external = true;
        info.externalCreateInfo = *extImgCiPtr;

        return res;
    }

    VkResult on_vkCreateSamplerYcbcrConversion(
        void* context, VkResult,
        VkDevice device,
        const VkSamplerYcbcrConversionCreateInfo* pCreateInfo,
        const VkAllocationCallbacks* pAllocator,
        VkSamplerYcbcrConversion* pYcbcrConversion) {

        VkSamplerYcbcrConversionCreateInfo localCreateInfo = vk_make_orphan_copy(*pCreateInfo);

#ifdef VK_USE_PLATFORM_ANDROID_KHR
        const VkExternalFormatANDROID* extFormatAndroidPtr =
            vk_find_struct<VkExternalFormatANDROID>(pCreateInfo);
        if (extFormatAndroidPtr) {
            if (extFormatAndroidPtr->externalFormat) {
                localCreateInfo.format =
                    vk_format_from_android(extFormatAndroidPtr->externalFormat);
            }
        }
#endif

        VkEncoder* enc = (VkEncoder*)context;
        return enc->vkCreateSamplerYcbcrConversion(
            device, &localCreateInfo, pAllocator, pYcbcrConversion);
    }

    VkResult on_vkCreateSamplerYcbcrConversionKHR(
        void* context, VkResult,
        VkDevice device,
        const VkSamplerYcbcrConversionCreateInfo* pCreateInfo,
        const VkAllocationCallbacks* pAllocator,
        VkSamplerYcbcrConversion* pYcbcrConversion) {

        VkSamplerYcbcrConversionCreateInfo localCreateInfo = vk_make_orphan_copy(*pCreateInfo);

#ifdef VK_USE_PLATFORM_ANDROID_KHR
        const VkExternalFormatANDROID* extFormatAndroidPtr =
            vk_find_struct<VkExternalFormatANDROID>(pCreateInfo);
        if (extFormatAndroidPtr) {
            if (extFormatAndroidPtr->externalFormat) {
                localCreateInfo.format =
                    vk_format_from_android(extFormatAndroidPtr->externalFormat);
            }
        }
#endif

        VkEncoder* enc = (VkEncoder*)context;
        return enc->vkCreateSamplerYcbcrConversionKHR(
            device, &localCreateInfo, pAllocator, pYcbcrConversion);
    }

    void on_vkDestroyImage(
        void* context,
        VkDevice device, VkImage image, const VkAllocationCallbacks *pAllocator) {
        VkEncoder* enc = (VkEncoder*)context;
        enc->vkDestroyImage(device, image, pAllocator);
    }

    void on_vkGetImageMemoryRequirements(
        void *context, VkDevice device, VkImage image,
        VkMemoryRequirements *pMemoryRequirements) {
        VkEncoder* enc = (VkEncoder*)context;
        enc->vkGetImageMemoryRequirements(
            device, image, pMemoryRequirements);
        transformImageMemoryRequirementsForGuest(
            image, pMemoryRequirements);
    }

    void on_vkGetImageMemoryRequirements2(
        void *context, VkDevice device, const VkImageMemoryRequirementsInfo2 *pInfo,
        VkMemoryRequirements2 *pMemoryRequirements) {
        VkEncoder* enc = (VkEncoder*)context;
        enc->vkGetImageMemoryRequirements2(
            device, pInfo, pMemoryRequirements);
        transformImageMemoryRequirements2ForGuest(
            pInfo->image, pMemoryRequirements);
    }

    void on_vkGetImageMemoryRequirements2KHR(
        void *context, VkDevice device, const VkImageMemoryRequirementsInfo2 *pInfo,
        VkMemoryRequirements2 *pMemoryRequirements) {
        VkEncoder* enc = (VkEncoder*)context;
        enc->vkGetImageMemoryRequirements2KHR(
            device, pInfo, pMemoryRequirements);
        transformImageMemoryRequirements2ForGuest(
            pInfo->image, pMemoryRequirements);
    }

    VkResult on_vkBindImageMemory(
        void* context, VkResult,
        VkDevice device, VkImage image, VkDeviceMemory memory,
        VkDeviceSize memoryOffset) {
        VkEncoder* enc = (VkEncoder*)context;
        return enc->vkBindImageMemory(device, image, memory, memoryOffset);
    }

    VkResult on_vkBindImageMemory2(
        void* context, VkResult,
        VkDevice device, uint32_t bindingCount, const VkBindImageMemoryInfo* pBindInfos) {
        VkEncoder* enc = (VkEncoder*)context;
        return enc->vkBindImageMemory2(device, bindingCount, pBindInfos);
    }

    VkResult on_vkBindImageMemory2KHR(
        void* context, VkResult,
        VkDevice device, uint32_t bindingCount, const VkBindImageMemoryInfo* pBindInfos) {
        VkEncoder* enc = (VkEncoder*)context;
        return enc->vkBindImageMemory2KHR(device, bindingCount, pBindInfos);
    }

    VkResult on_vkCreateBuffer(
        void* context, VkResult,
        VkDevice device, const VkBufferCreateInfo *pCreateInfo,
        const VkAllocationCallbacks *pAllocator,
        VkBuffer *pBuffer) {
        VkEncoder* enc = (VkEncoder*)context;

        VkResult res = enc->vkCreateBuffer(device, pCreateInfo, pAllocator, pBuffer);

        if (res != VK_SUCCESS) return res;

        AutoLock lock(mLock);

        auto it = info_VkBuffer.find(*pBuffer);
        if (it == info_VkBuffer.end()) return VK_ERROR_INITIALIZATION_FAILED;

        auto& info = it->second;

        info.createInfo = *pCreateInfo;
        info.createInfo.pNext = nullptr;

        const VkExternalMemoryBufferCreateInfo* extBufCi =
            vk_find_struct<VkExternalMemoryBufferCreateInfo>(pCreateInfo);

        if (!extBufCi) return res;

        info.external = true;
        info.externalCreateInfo = *extBufCi;

        return res;
    }

    void on_vkDestroyBuffer(
        void* context,
        VkDevice device, VkBuffer buffer, const VkAllocationCallbacks *pAllocator) {
        VkEncoder* enc = (VkEncoder*)context;
        enc->vkDestroyBuffer(device, buffer, pAllocator);
    }

    void on_vkGetBufferMemoryRequirements(
        void* context, VkDevice device, VkBuffer buffer, VkMemoryRequirements *pMemoryRequirements) {
        VkEncoder* enc = (VkEncoder*)context;
        enc->vkGetBufferMemoryRequirements(
            device, buffer, pMemoryRequirements);
        transformBufferMemoryRequirementsForGuest(
            buffer, pMemoryRequirements);
    }

    void on_vkGetBufferMemoryRequirements2(
        void* context, VkDevice device, const VkBufferMemoryRequirementsInfo2* pInfo,
        VkMemoryRequirements2* pMemoryRequirements) {
        VkEncoder* enc = (VkEncoder*)context;
        enc->vkGetBufferMemoryRequirements2(device, pInfo, pMemoryRequirements);
        transformBufferMemoryRequirements2ForGuest(
            pInfo->buffer, pMemoryRequirements);
    }

    void on_vkGetBufferMemoryRequirements2KHR(
        void* context, VkDevice device, const VkBufferMemoryRequirementsInfo2* pInfo,
        VkMemoryRequirements2* pMemoryRequirements) {
        VkEncoder* enc = (VkEncoder*)context;
        enc->vkGetBufferMemoryRequirements2KHR(device, pInfo, pMemoryRequirements);
        transformBufferMemoryRequirements2ForGuest(
            pInfo->buffer, pMemoryRequirements);
    }

    VkResult on_vkBindBufferMemory(
        void *context, VkResult,
        VkDevice device, VkBuffer buffer, VkDeviceMemory memory, VkDeviceSize memoryOffset) {
        VkEncoder *enc = (VkEncoder *)context;
        return enc->vkBindBufferMemory(
            device, buffer, memory, memoryOffset);
    }

    VkResult on_vkBindBufferMemory2(
        void *context, VkResult,
        VkDevice device, uint32_t bindInfoCount, const VkBindBufferMemoryInfo *pBindInfos) {
        VkEncoder *enc = (VkEncoder *)context;
        return enc->vkBindBufferMemory2(
            device, bindInfoCount, pBindInfos);
    }

    VkResult on_vkBindBufferMemory2KHR(
        void *context, VkResult,
        VkDevice device, uint32_t bindInfoCount, const VkBindBufferMemoryInfo *pBindInfos) {
        VkEncoder *enc = (VkEncoder *)context;
        return enc->vkBindBufferMemory2KHR(
            device, bindInfoCount, pBindInfos);
    }

    void ensureSyncDeviceFd() {
        if (mSyncDeviceFd >= 0) return;
#ifdef VK_USE_PLATFORM_ANDROID_KHR
        mSyncDeviceFd = goldfish_sync_open();
        if (mSyncDeviceFd >= 0) {
            ALOGD("%s: created sync device for current Vulkan process: %d\n", __func__, mSyncDeviceFd);
        } else {
            ALOGD("%s: failed to create sync device for current Vulkan process\n", __func__);
        }
#endif
    }

    VkResult on_vkCreateSemaphore(
        void* context, VkResult input_result,
        VkDevice device, const VkSemaphoreCreateInfo* pCreateInfo,
        const VkAllocationCallbacks* pAllocator,
        VkSemaphore* pSemaphore) {

        VkEncoder* enc = (VkEncoder*)context;

        VkSemaphoreCreateInfo finalCreateInfo = *pCreateInfo;

        const VkExportSemaphoreCreateInfoKHR* exportSemaphoreInfoPtr =
            vk_find_struct<VkExportSemaphoreCreateInfoKHR>(pCreateInfo);

#ifdef VK_USE_PLATFORM_FUCHSIA
        bool exportEvent = exportSemaphoreInfoPtr &&
            (exportSemaphoreInfoPtr->handleTypes &
             VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_TEMP_ZIRCON_EVENT_BIT_FUCHSIA);

        if (exportEvent) {
            finalCreateInfo.pNext = nullptr;
        }
#endif

#ifdef VK_USE_PLATFORM_ANDROID_KHR
        bool exportSyncFd = exportSemaphoreInfoPtr &&
            (exportSemaphoreInfoPtr->handleTypes &
             VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT);

        if (exportSyncFd) {
            finalCreateInfo.pNext = nullptr;
        }
#endif
        input_result = enc->vkCreateSemaphore(
            device, &finalCreateInfo, pAllocator, pSemaphore);

        zx_handle_t event_handle = ZX_HANDLE_INVALID;

#ifdef VK_USE_PLATFORM_FUCHSIA
        if (exportEvent) {
            zx_event_create(0, &event_handle);
        }
#endif

        AutoLock lock(mLock);

        auto it = info_VkSemaphore.find(*pSemaphore);
        if (it == info_VkSemaphore.end()) return VK_ERROR_INITIALIZATION_FAILED;

        auto& info = it->second;

        info.device = device;
        info.eventHandle = event_handle;

#ifdef VK_USE_PLATFORM_ANDROID_KHR
        if (exportSyncFd) {

            ensureSyncDeviceFd();

            if (exportSyncFd) {
                int syncFd = -1;
                goldfish_sync_queue_work(
                    mSyncDeviceFd,
                    get_host_u64_VkSemaphore(*pSemaphore) /* the handle */,
                    GOLDFISH_SYNC_VULKAN_SEMAPHORE_SYNC /* thread handle (doubling as type field) */,
                    &syncFd);
                info.syncFd = syncFd;
            }
        }
#endif

        return VK_SUCCESS;
    }

    void on_vkDestroySemaphore(
        void* context,
        VkDevice device, VkSemaphore semaphore, const VkAllocationCallbacks *pAllocator) {
        VkEncoder* enc = (VkEncoder*)context;
        enc->vkDestroySemaphore(device, semaphore, pAllocator);
    }

    // https://www.khronos.org/registry/vulkan/specs/1.0-extensions/html/vkspec.html#vkGetSemaphoreFdKHR
    // Each call to vkGetSemaphoreFdKHR must create a new file descriptor and transfer ownership
    // of it to the application. To avoid leaking resources, the application must release ownership
    // of the file descriptor when it is no longer needed.
    VkResult on_vkGetSemaphoreFdKHR(
        void* context, VkResult,
        VkDevice device, const VkSemaphoreGetFdInfoKHR* pGetFdInfo,
        int* pFd) {
#ifdef VK_USE_PLATFORM_ANDROID_KHR
        VkEncoder* enc = (VkEncoder*)context;
        bool getSyncFd =
            pGetFdInfo->handleType & VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;

        if (getSyncFd) {
            AutoLock lock(mLock);
            auto it = info_VkSemaphore.find(pGetFdInfo->semaphore);
            if (it == info_VkSemaphore.end()) return VK_ERROR_OUT_OF_HOST_MEMORY;
            auto& semInfo = it->second;
            *pFd = dup(semInfo.syncFd);
            return VK_SUCCESS;
        } else {
            // opaque fd
            int hostFd = 0;
            VkResult result = enc->vkGetSemaphoreFdKHR(device, pGetFdInfo, &hostFd);
            if (result != VK_SUCCESS) {
                return result;
            }
            *pFd = memfd_create("vk_opaque_fd", 0);
            write(*pFd, &hostFd, sizeof(hostFd));
            return VK_SUCCESS;
        }
#else
        (void)context;
        (void)device;
        (void)pGetFdInfo;
        (void)pFd;
        return VK_ERROR_INCOMPATIBLE_DRIVER;
#endif
    }

    VkResult on_vkImportSemaphoreFdKHR(
        void* context, VkResult input_result,
        VkDevice device,
        const VkImportSemaphoreFdInfoKHR* pImportSemaphoreFdInfo) {
#ifdef VK_USE_PLATFORM_ANDROID_KHR
        VkEncoder* enc = (VkEncoder*)context;
        if (input_result != VK_SUCCESS) {
            return input_result;
        }

        if (pImportSemaphoreFdInfo->handleType &
            VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT) {
            VkImportSemaphoreFdInfoKHR tmpInfo = *pImportSemaphoreFdInfo;

            AutoLock lock(mLock);

            auto semaphoreIt = info_VkSemaphore.find(pImportSemaphoreFdInfo->semaphore);
            auto& info = semaphoreIt->second;

            if (info.syncFd >= 0) {
                close(info.syncFd);
            }

            info.syncFd = pImportSemaphoreFdInfo->fd;

            return VK_SUCCESS;
        } else {
            int fd = pImportSemaphoreFdInfo->fd;
            int err = lseek(fd, 0, SEEK_SET);
            if (err == -1) {
                ALOGE("lseek fail on import semaphore");
            }
            int hostFd = 0;
            read(fd, &hostFd, sizeof(hostFd));
            VkImportSemaphoreFdInfoKHR tmpInfo = *pImportSemaphoreFdInfo;
            tmpInfo.fd = hostFd;
            VkResult result = enc->vkImportSemaphoreFdKHR(device, &tmpInfo);
            close(fd);
            return result;
        }
#else
        (void)context;
        (void)input_result;
        (void)device;
        (void)pImportSemaphoreFdInfo;
        return VK_ERROR_INCOMPATIBLE_DRIVER;
#endif
    }

    VkResult on_vkQueueSubmit(
        void* context, VkResult input_result,
        VkQueue queue, uint32_t submitCount, const VkSubmitInfo* pSubmits, VkFence fence) {

        std::vector<VkSemaphore> pre_signal_semaphores;
        std::vector<zx_handle_t> post_wait_events;
        std::vector<int> post_wait_sync_fds;
        VkDevice device = VK_NULL_HANDLE;
        VkFence* pFence = nullptr;

        VkEncoder* enc = (VkEncoder*)context;

        AutoLock lock(mLock);

        for (uint32_t i = 0; i < submitCount; ++i) {
            for (uint32_t j = 0; j < pSubmits[i].waitSemaphoreCount; ++j) {
                auto it = info_VkSemaphore.find(pSubmits[i].pWaitSemaphores[j]);
                if (it != info_VkSemaphore.end()) {
                    auto& semInfo = it->second;
#ifdef VK_USE_PLATFORM_FUCHSIA
                    if (semInfo.eventHandle) {
                        // Wait here instead of passing semaphore to host.
                        zx_object_wait_one(semInfo.eventHandle,
                                           ZX_EVENT_SIGNALED,
                                           ZX_TIME_INFINITE,
                                           nullptr);
                        pre_signal_semaphores.push_back(pSubmits[i].pWaitSemaphores[j]);
                    }
#endif
#ifdef VK_USE_PLATFORM_ANDROID_KHR
                    if (semInfo.syncFd >= 0) {
                        // Wait here instead of passing semaphore to host.
                        sync_wait(semInfo.syncFd, 3000);
                        pre_signal_semaphores.push_back(pSubmits[i].pWaitSemaphores[j]);
                    }
#endif
                }
            }
            for (uint32_t j = 0; j < pSubmits[i].signalSemaphoreCount; ++j) {
                auto it = info_VkSemaphore.find(pSubmits[i].pSignalSemaphores[j]);
                if (it != info_VkSemaphore.end()) {
                    auto& semInfo = it->second;
#ifdef VK_USE_PLATFORM_FUCHSIA
                    if (semInfo.eventHandle) {
                        post_wait_events.push_back(semInfo.eventHandle);
                        device = semInfo.device;
                        pFence = &info_VkDevice[device].fence;
                    }
#endif
#ifdef VK_USE_PLATFORM_ANDROID_KHR
                    if (semInfo.syncFd >= 0) {
                        post_wait_sync_fds.push_back(semInfo.syncFd);
                        device = semInfo.device;
                        pFence = &info_VkDevice[device].fence;
                    }
#endif
                }
            }
        }
        lock.unlock();

        if (!pre_signal_semaphores.empty()) {
            VkSubmitInfo submit_info = {
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .waitSemaphoreCount = 0,
                .pWaitSemaphores = nullptr,
                .pWaitDstStageMask = nullptr,
                .signalSemaphoreCount = static_cast<uint32_t>(pre_signal_semaphores.size()),
                .pSignalSemaphores = pre_signal_semaphores.data()};
            enc->vkQueueSubmit(queue, 1, &submit_info, VK_NULL_HANDLE);
        }

        input_result = enc->vkQueueSubmit(queue, submitCount, pSubmits, fence);

        if (input_result != VK_SUCCESS) return input_result;

        if (post_wait_events.empty())
            return VK_SUCCESS;

        if (*pFence == VK_NULL_HANDLE) {
            VkFenceCreateInfo fence_create_info = {
                VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, 0, 0,
            };
            enc->vkCreateFence(device, &fence_create_info, nullptr, pFence);
        }
        enc->vkQueueSubmit(queue, 0, nullptr, *pFence);
        static constexpr uint64_t MAX_WAIT_NS =
            5ULL * 1000ULL * 1000ULL * 1000ULL;
        enc->vkWaitForFences(device, 1, pFence, VK_TRUE, MAX_WAIT_NS);
        enc->vkResetFences(device, 1, pFence);

#ifdef VK_USE_PLATFORM_FUCHSIA
        for (auto& event : post_wait_events) {
            zx_object_signal(event, 0, ZX_EVENT_SIGNALED);
        }
#endif
#ifdef VK_USE_PLATFORM_ANDROID_KHR
        for (auto& fd : post_wait_sync_fds) {
            goldfish_sync_signal(fd);
        }
#endif

        return VK_SUCCESS;
    }

    void unwrap_VkNativeBufferANDROID(
        const VkImageCreateInfo* pCreateInfo,
        VkImageCreateInfo* local_pCreateInfo) {

        if (!pCreateInfo->pNext) return;

        const VkNativeBufferANDROID* nativeInfo =
            reinterpret_cast<const VkNativeBufferANDROID*>(pCreateInfo->pNext);

        if (VK_STRUCTURE_TYPE_NATIVE_BUFFER_ANDROID != nativeInfo->sType) {
            return;
        }

        const cb_handle_t* cb_handle =
            reinterpret_cast<const cb_handle_t*>(nativeInfo->handle);

        if (!cb_handle) return;

        VkNativeBufferANDROID* nativeInfoOut =
            reinterpret_cast<VkNativeBufferANDROID*>(
                const_cast<void*>(
                    local_pCreateInfo->pNext));

        if (!nativeInfoOut->handle) {
            ALOGE("FATAL: Local native buffer info not properly allocated!");
            abort();
        }

        *(uint32_t*)(nativeInfoOut->handle) = cb_handle->hostHandle;
    }

    void unwrap_vkAcquireImageANDROID_nativeFenceFd(int fd, int*) {
        if (fd != -1) {
            sync_wait(fd, 3000);
        }
    }

    // Action of vkMapMemoryIntoAddressSpaceGOOGLE:
    // 1. preprocess (on_vkMapMemoryIntoAddressSpaceGOOGLE_pre):
    //    uses address space device to reserve the right size of
    //    memory.
    // 2. the reservation results in a physical address. the physical
    //    address is set as |*pAddress|.
    // 3. after pre, the API call is encoded to the host, where the
    //    value of pAddress is also sent (the physical address).
    // 4. the host will obtain the actual gpu pointer and send it
    //    back out in |*pAddress|.
    // 5. postprocess (on_vkMapMemoryIntoAddressSpaceGOOGLE) will run,
    //    using the mmap() method of GoldfishAddressSpaceBlock to obtain
    //    a pointer in guest userspace corresponding to the host pointer.
    VkResult on_vkMapMemoryIntoAddressSpaceGOOGLE_pre(
        void*,
        VkResult,
        VkDevice,
        VkDeviceMemory memory,
        uint64_t* pAddress) {

        AutoLock lock(mLock);

        auto it = info_VkDeviceMemory.find(memory);
        if (it == info_VkDeviceMemory.end()) {
            return VK_ERROR_OUT_OF_HOST_MEMORY;
        }

        auto& memInfo = it->second;
        memInfo.goldfishAddressSpaceBlock =
            new GoldfishAddressSpaceBlock;
        auto& block = *(memInfo.goldfishAddressSpaceBlock);

        block.allocate(
            mGoldfishAddressSpaceBlockProvider.get(),
            memInfo.mappedSize);

        *pAddress = block.physAddr();

        return VK_SUCCESS;
    }

    VkResult on_vkMapMemoryIntoAddressSpaceGOOGLE(
        void*,
        VkResult input_result,
        VkDevice,
        VkDeviceMemory memory,
        uint64_t* pAddress) {

        if (input_result != VK_SUCCESS) {
            return input_result;
        }

        // Now pAddress points to the gpu addr from host.
        AutoLock lock(mLock);

        auto it = info_VkDeviceMemory.find(memory);
        if (it == info_VkDeviceMemory.end()) {
            return VK_ERROR_OUT_OF_HOST_MEMORY;
        }

        auto& memInfo = it->second;
        auto& block = *(memInfo.goldfishAddressSpaceBlock);

        uint64_t gpuAddr = *pAddress;

        void* userPtr = block.mmap(gpuAddr);

        D("%s: Got new host visible alloc. "
          "Sizeof void: %zu map size: %zu Range: [%p %p]",
          __func__,
          sizeof(void*), (size_t)memInfo.mappedSize,
          userPtr,
          (unsigned char*)userPtr + memInfo.mappedSize);

        *pAddress = (uint64_t)(uintptr_t)userPtr;

        return input_result;
    }

    bool isDescriptorTypeImageInfo(VkDescriptorType descType) {
        return (descType == VK_DESCRIPTOR_TYPE_SAMPLER) ||
               (descType == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) ||
               (descType == VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE) ||
               (descType == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE) ||
               (descType == VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT);
    }

    bool isDescriptorTypeBufferInfo(VkDescriptorType descType) {
        return (descType == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) ||
               (descType == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC) ||
               (descType == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER) ||
               (descType == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC);
    }

    bool isDescriptorTypeBufferView(VkDescriptorType descType) {
        return (descType == VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER) ||
               (descType == VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER);
    }

    VkResult initDescriptorUpdateTemplateBuffers(
        const VkDescriptorUpdateTemplateCreateInfo* pCreateInfo,
        VkDescriptorUpdateTemplate descriptorUpdateTemplate) {

        AutoLock lock(mLock);

        auto it = info_VkDescriptorUpdateTemplate.find(descriptorUpdateTemplate);
        if (it == info_VkDescriptorUpdateTemplate.end()) {
            return VK_ERROR_INITIALIZATION_FAILED;
        }

        auto& info = it->second;

        size_t imageInfosNeeded = 0;
        size_t bufferInfosNeeded = 0;
        size_t bufferViewsNeeded = 0;

        for (uint32_t i = 0; i < pCreateInfo->descriptorUpdateEntryCount; ++i) {
            const auto& entry = pCreateInfo->pDescriptorUpdateEntries[i];
            uint32_t descCount = entry.descriptorCount;
            VkDescriptorType descType = entry.descriptorType;

            info.templateEntries.push_back(entry);

            for (uint32_t j = 0; j < descCount; ++j) {
                if (isDescriptorTypeImageInfo(descType)) {
                    ++imageInfosNeeded;
                    info.imageInfoEntryIndices.push_back(i);
                } else if (isDescriptorTypeBufferInfo(descType)) {
                    ++bufferInfosNeeded;
                    info.bufferInfoEntryIndices.push_back(i);
                } else if (isDescriptorTypeBufferView(descType)) {
                    ++bufferViewsNeeded;
                    info.bufferViewEntryIndices.push_back(i);
                } else {
                    ALOGE("%s: FATAL: Unknown descriptor type %d\n", __func__, descType);
                    abort();
                }
            }
        }

        // To be filled in later (our flat structure)
        info.imageInfos.resize(imageInfosNeeded);
        info.bufferInfos.resize(bufferInfosNeeded);
        info.bufferViews.resize(bufferViewsNeeded);

        return VK_SUCCESS;
    }

    VkResult on_vkCreateDescriptorUpdateTemplate(
        void* context, VkResult input_result,
        VkDevice device,
        const VkDescriptorUpdateTemplateCreateInfo* pCreateInfo,
        const VkAllocationCallbacks* pAllocator,
        VkDescriptorUpdateTemplate* pDescriptorUpdateTemplate) {

        (void)context;
        (void)device;
        (void)pAllocator;

        if (input_result != VK_SUCCESS) return input_result;

        return initDescriptorUpdateTemplateBuffers(pCreateInfo, *pDescriptorUpdateTemplate);
    }

    VkResult on_vkCreateDescriptorUpdateTemplateKHR(
        void* context, VkResult input_result,
        VkDevice device,
        const VkDescriptorUpdateTemplateCreateInfo* pCreateInfo,
        const VkAllocationCallbacks* pAllocator,
        VkDescriptorUpdateTemplate* pDescriptorUpdateTemplate) {

        (void)context;
        (void)device;
        (void)pAllocator;

        if (input_result != VK_SUCCESS) return input_result;

        return initDescriptorUpdateTemplateBuffers(pCreateInfo, *pDescriptorUpdateTemplate);
    }

    void on_vkUpdateDescriptorSetWithTemplate(
        void* context,
        VkDevice device,
        VkDescriptorSet descriptorSet,
        VkDescriptorUpdateTemplate descriptorUpdateTemplate,
        const void* pData) {

        VkEncoder* enc = (VkEncoder*)context;

        uint8_t* userBuffer = (uint8_t*)pData;
        if (!userBuffer) return;

        AutoLock lock(mLock);

        auto it = info_VkDescriptorUpdateTemplate.find(descriptorUpdateTemplate);
        if (it == info_VkDescriptorUpdateTemplate.end()) {
            return;
        }

        auto& info = it->second;

        size_t currImageInfoOffset = 0;
        size_t currBufferInfoOffset = 0;
        size_t currBufferViewOffset = 0;

        for (const auto& entry : info.templateEntries) {
            VkDescriptorType descType = entry.descriptorType;

            auto offset = entry.offset;
            auto stride = entry.stride;

            uint32_t descCount = entry.descriptorCount;

            if (isDescriptorTypeImageInfo(descType)) {
                if (!stride) stride = sizeof(VkDescriptorImageInfo);
                for (uint32_t j = 0; j < descCount; ++j) {
                    memcpy(((uint8_t*)info.imageInfos.data()) + currImageInfoOffset,
                           userBuffer + offset + j * stride,
                           sizeof(VkDescriptorImageInfo));
                    currImageInfoOffset += sizeof(VkDescriptorImageInfo);
                }
            } else if (isDescriptorTypeBufferInfo(descType)) {
                if (!stride) stride = sizeof(VkDescriptorBufferInfo);
                for (uint32_t j = 0; j < descCount; ++j) {
                    memcpy(((uint8_t*)info.bufferInfos.data()) + currBufferInfoOffset,
                           userBuffer + offset + j * stride,
                           sizeof(VkDescriptorBufferInfo));
                    currBufferInfoOffset += sizeof(VkDescriptorBufferInfo);
                }
            } else if (isDescriptorTypeBufferView(descType)) {
                if (!stride) stride = sizeof(VkBufferView);
                for (uint32_t j = 0; j < descCount; ++j) {
                    memcpy(((uint8_t*)info.bufferViews.data()) + currBufferViewOffset,
                           userBuffer + offset + j * stride,
                           sizeof(VkBufferView));
                    currBufferViewOffset += sizeof(VkBufferView);
                }
            } else {
                ALOGE("%s: FATAL: Unknown descriptor type %d\n", __func__, descType);
                abort();
            }
        }

        enc->vkUpdateDescriptorSetWithTemplateSizedGOOGLE(
            device,
            descriptorSet,
            descriptorUpdateTemplate,
            (uint32_t)info.imageInfos.size(),
            (uint32_t)info.bufferInfos.size(),
            (uint32_t)info.bufferViews.size(),
            info.imageInfoEntryIndices.data(),
            info.bufferInfoEntryIndices.data(),
            info.bufferViewEntryIndices.data(),
            info.imageInfos.data(),
            info.bufferInfos.data(),
            info.bufferViews.data());
    }

    VkResult on_vkGetPhysicalDeviceImageFormatProperties2_common(
        bool isKhr,
        void* context, VkResult input_result,
        VkPhysicalDevice physicalDevice,
        const VkPhysicalDeviceImageFormatInfo2* pImageFormatInfo,
        VkImageFormatProperties2* pImageFormatProperties) {

        VkEncoder* enc = (VkEncoder*)context;
        (void)input_result;

        VkAndroidHardwareBufferUsageANDROID* output_ahw_usage =
            vk_find_struct<VkAndroidHardwareBufferUsageANDROID>(pImageFormatProperties);

        VkResult hostRes;

        if (isKhr) {
            hostRes = enc->vkGetPhysicalDeviceImageFormatProperties2KHR(
                physicalDevice, pImageFormatInfo,
                pImageFormatProperties);
        } else {
            hostRes = enc->vkGetPhysicalDeviceImageFormatProperties2(
                physicalDevice, pImageFormatInfo,
                pImageFormatProperties);
        }

        if (hostRes != VK_SUCCESS) return hostRes;

        if (output_ahw_usage) {
            output_ahw_usage->androidHardwareBufferUsage =
                getAndroidHardwareBufferUsageFromVkUsage(
                    pImageFormatInfo->flags,
                    pImageFormatInfo->usage);
        }

        return hostRes;
    }

    VkResult on_vkGetPhysicalDeviceImageFormatProperties2(
        void* context, VkResult input_result,
        VkPhysicalDevice physicalDevice,
        const VkPhysicalDeviceImageFormatInfo2* pImageFormatInfo,
        VkImageFormatProperties2* pImageFormatProperties) {
        return on_vkGetPhysicalDeviceImageFormatProperties2_common(
            false /* not KHR */, context, input_result,
            physicalDevice, pImageFormatInfo, pImageFormatProperties);
    }

    VkResult on_vkGetPhysicalDeviceImageFormatProperties2KHR(
        void* context, VkResult input_result,
        VkPhysicalDevice physicalDevice,
        const VkPhysicalDeviceImageFormatInfo2* pImageFormatInfo,
        VkImageFormatProperties2* pImageFormatProperties) {
        return on_vkGetPhysicalDeviceImageFormatProperties2_common(
            true /* is KHR */, context, input_result,
            physicalDevice, pImageFormatInfo, pImageFormatProperties);
    }

    VkResult on_vkBeginCommandBuffer(
        void* context, VkResult input_result,
        VkCommandBuffer commandBuffer,
        const VkCommandBufferBeginInfo* pBeginInfo) {

        VkEncoder* enc = (VkEncoder*)context;
        (void)input_result;

        if (!supportsDeferredCommands()) {
            return enc->vkBeginCommandBuffer(commandBuffer, pBeginInfo);
        }

        enc->vkBeginCommandBufferAsyncGOOGLE(commandBuffer, pBeginInfo);
        return VK_SUCCESS;
    }

    VkResult on_vkEndCommandBuffer(
        void* context, VkResult input_result,
        VkCommandBuffer commandBuffer) {

        VkEncoder* enc = (VkEncoder*)context;
        (void)input_result;

        if (!supportsDeferredCommands()) {
            return enc->vkEndCommandBuffer(commandBuffer);
        }

        enc->vkEndCommandBufferAsyncGOOGLE(commandBuffer);
        return VK_SUCCESS;
    }

    VkResult on_vkResetCommandBuffer(
        void* context, VkResult input_result,
        VkCommandBuffer commandBuffer,
        VkCommandBufferResetFlags flags) {

        VkEncoder* enc = (VkEncoder*)context;
        (void)input_result;

        if (!supportsDeferredCommands()) {
            return enc->vkResetCommandBuffer(commandBuffer, flags);
        }

        enc->vkResetCommandBufferAsyncGOOGLE(commandBuffer, flags);
        return VK_SUCCESS;
    }

    VkResult on_vkCreateImageView(
        void* context, VkResult input_result,
        VkDevice device,
        const VkImageViewCreateInfo* pCreateInfo,
        const VkAllocationCallbacks* pAllocator,
        VkImageView* pView) {

        VkEncoder* enc = (VkEncoder*)context;
        (void)input_result;

        VkImageViewCreateInfo localCreateInfo = vk_make_orphan_copy(*pCreateInfo);

#ifdef VK_USE_PLATFORM_ANDROID_KHR
        const VkExternalFormatANDROID* extFormatAndroidPtr =
            vk_find_struct<VkExternalFormatANDROID>(pCreateInfo);
        if (extFormatAndroidPtr) {
            if (extFormatAndroidPtr->externalFormat) {
                localCreateInfo.format =
                    vk_format_from_android(extFormatAndroidPtr->externalFormat);
            }
        }
#endif

        return enc->vkCreateImageView(device, &localCreateInfo, pAllocator, pView);
    }

    uint32_t getApiVersionFromInstance(VkInstance instance) const {
        AutoLock lock(mLock);
        uint32_t api = kMinApiVersion;

        auto it = info_VkInstance.find(instance);
        if (it == info_VkInstance.end()) return api;

        api = it->second.highestApiVersion;

        return api;
    }

    uint32_t getApiVersionFromDevice(VkDevice device) const {
        AutoLock lock(mLock);

        uint32_t api = kMinApiVersion;

        auto it = info_VkDevice.find(device);
        if (it == info_VkDevice.end()) return api;

        api = it->second.apiVersion;

        return api;
    }

    bool hasInstanceExtension(VkInstance instance, const std::string& name) const {
        AutoLock lock(mLock);

        auto it = info_VkInstance.find(instance);
        if (it == info_VkInstance.end()) return false;

        return it->second.enabledExtensions.find(name) !=
               it->second.enabledExtensions.end();
    }

    bool hasDeviceExtension(VkDevice device, const std::string& name) const {
        AutoLock lock(mLock);

        auto it = info_VkDevice.find(device);
        if (it == info_VkDevice.end()) return false;

        return it->second.enabledExtensions.find(name) !=
               it->second.enabledExtensions.end();
    }

private:
    mutable Lock mLock;
    HostVisibleMemoryVirtualizationInfo mHostVisibleMemoryVirtInfo;
    std::unique_ptr<EmulatorFeatureInfo> mFeatureInfo;
    std::unique_ptr<GoldfishAddressSpaceBlockProvider> mGoldfishAddressSpaceBlockProvider;

    std::vector<VkExtensionProperties> mHostInstanceExtensions;
    std::vector<VkExtensionProperties> mHostDeviceExtensions;

    int mSyncDeviceFd = -1;

#ifdef VK_USE_PLATFORM_FUCHSIA
    zx_handle_t mControlDevice = ZX_HANDLE_INVALID;
    fuchsia::sysmem::AllocatorSyncPtr mSysmemAllocator;
#endif
};

ResourceTracker::ResourceTracker() : mImpl(new ResourceTracker::Impl()) { }
ResourceTracker::~ResourceTracker() { }
VulkanHandleMapping* ResourceTracker::createMapping() {
    return &mImpl->createMapping;
}
VulkanHandleMapping* ResourceTracker::unwrapMapping() {
    return &mImpl->unwrapMapping;
}
VulkanHandleMapping* ResourceTracker::destroyMapping() {
    return &mImpl->destroyMapping;
}
VulkanHandleMapping* ResourceTracker::defaultMapping() {
    return &mImpl->defaultMapping;
}
static ResourceTracker* sTracker = nullptr;
// static
ResourceTracker* ResourceTracker::get() {
    if (!sTracker) {
        // To be initialized once on vulkan device open.
        sTracker = new ResourceTracker;
    }
    return sTracker;
}

#define HANDLE_REGISTER_IMPL(type) \
    void ResourceTracker::register_##type(type obj) { \
        mImpl->register_##type(obj); \
    } \
    void ResourceTracker::unregister_##type(type obj) { \
        mImpl->unregister_##type(obj); \
    } \

GOLDFISH_VK_LIST_HANDLE_TYPES(HANDLE_REGISTER_IMPL)

bool ResourceTracker::isMemoryTypeHostVisible(
    VkDevice device, uint32_t typeIndex) const {
    return mImpl->isMemoryTypeHostVisible(device, typeIndex);
}

uint8_t* ResourceTracker::getMappedPointer(VkDeviceMemory memory) {
    return mImpl->getMappedPointer(memory);
}

VkDeviceSize ResourceTracker::getMappedSize(VkDeviceMemory memory) {
    return mImpl->getMappedSize(memory);
}

VkDeviceSize ResourceTracker::getNonCoherentExtendedSize(VkDevice device, VkDeviceSize basicSize) const {
    return mImpl->getNonCoherentExtendedSize(device, basicSize);
}

bool ResourceTracker::isValidMemoryRange(const VkMappedMemoryRange& range) const {
    return mImpl->isValidMemoryRange(range);
}

void ResourceTracker::setupFeatures(const EmulatorFeatureInfo* features) {
    mImpl->setupFeatures(features);
}

bool ResourceTracker::hostSupportsVulkan() const {
    return mImpl->hostSupportsVulkan();
}

bool ResourceTracker::usingDirectMapping() const {
    return mImpl->usingDirectMapping();
}

uint32_t ResourceTracker::getApiVersionFromInstance(VkInstance instance) const {
    return mImpl->getApiVersionFromInstance(instance);
}

uint32_t ResourceTracker::getApiVersionFromDevice(VkDevice device) const {
    return mImpl->getApiVersionFromDevice(device);
}
bool ResourceTracker::hasInstanceExtension(VkInstance instance, const std::string &name) const {
    return mImpl->hasInstanceExtension(instance, name);
}
bool ResourceTracker::hasDeviceExtension(VkDevice device, const std::string &name) const {
    return mImpl->hasDeviceExtension(device, name);
}

VkResult ResourceTracker::on_vkEnumerateInstanceExtensionProperties(
    void* context,
    VkResult input_result,
    const char* pLayerName,
    uint32_t* pPropertyCount,
    VkExtensionProperties* pProperties) {
    return mImpl->on_vkEnumerateInstanceExtensionProperties(
        context, input_result, pLayerName, pPropertyCount, pProperties);
}

VkResult ResourceTracker::on_vkEnumerateDeviceExtensionProperties(
    void* context,
    VkResult input_result,
    VkPhysicalDevice physicalDevice,
    const char* pLayerName,
    uint32_t* pPropertyCount,
    VkExtensionProperties* pProperties) {
    return mImpl->on_vkEnumerateDeviceExtensionProperties(
        context, input_result, physicalDevice, pLayerName, pPropertyCount, pProperties);
}

VkResult ResourceTracker::on_vkEnumeratePhysicalDevices(
    void* context, VkResult input_result,
    VkInstance instance, uint32_t* pPhysicalDeviceCount,
    VkPhysicalDevice* pPhysicalDevices) {
    return mImpl->on_vkEnumeratePhysicalDevices(
        context, input_result, instance, pPhysicalDeviceCount,
        pPhysicalDevices);
}

void ResourceTracker::on_vkGetPhysicalDeviceMemoryProperties(
    void* context,
    VkPhysicalDevice physicalDevice,
    VkPhysicalDeviceMemoryProperties* pMemoryProperties) {
    mImpl->on_vkGetPhysicalDeviceMemoryProperties(
        context, physicalDevice, pMemoryProperties);
}

void ResourceTracker::on_vkGetPhysicalDeviceMemoryProperties2(
    void* context,
    VkPhysicalDevice physicalDevice,
    VkPhysicalDeviceMemoryProperties2* pMemoryProperties) {
    mImpl->on_vkGetPhysicalDeviceMemoryProperties2(
        context, physicalDevice, pMemoryProperties);
}

void ResourceTracker::on_vkGetPhysicalDeviceMemoryProperties2KHR(
    void* context,
    VkPhysicalDevice physicalDevice,
    VkPhysicalDeviceMemoryProperties2* pMemoryProperties) {
    mImpl->on_vkGetPhysicalDeviceMemoryProperties2(
        context, physicalDevice, pMemoryProperties);
}

VkResult ResourceTracker::on_vkCreateInstance(
    void* context,
    VkResult input_result,
    const VkInstanceCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkInstance* pInstance) {
    return mImpl->on_vkCreateInstance(
        context, input_result, pCreateInfo, pAllocator, pInstance);
}

VkResult ResourceTracker::on_vkCreateDevice(
    void* context,
    VkResult input_result,
    VkPhysicalDevice physicalDevice,
    const VkDeviceCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkDevice* pDevice) {
    return mImpl->on_vkCreateDevice(
        context, input_result, physicalDevice, pCreateInfo, pAllocator, pDevice);
}

void ResourceTracker::on_vkDestroyDevice_pre(
    void* context,
    VkDevice device,
    const VkAllocationCallbacks* pAllocator) {
    mImpl->on_vkDestroyDevice_pre(context, device, pAllocator);
}

VkResult ResourceTracker::on_vkAllocateMemory(
    void* context,
    VkResult input_result,
    VkDevice device,
    const VkMemoryAllocateInfo* pAllocateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkDeviceMemory* pMemory) {
    return mImpl->on_vkAllocateMemory(
        context, input_result, device, pAllocateInfo, pAllocator, pMemory);
}

void ResourceTracker::on_vkFreeMemory(
    void* context,
    VkDevice device,
    VkDeviceMemory memory,
    const VkAllocationCallbacks* pAllocator) {
    return mImpl->on_vkFreeMemory(
        context, device, memory, pAllocator);
}

VkResult ResourceTracker::on_vkMapMemory(
    void* context,
    VkResult input_result,
    VkDevice device,
    VkDeviceMemory memory,
    VkDeviceSize offset,
    VkDeviceSize size,
    VkMemoryMapFlags flags,
    void** ppData) {
    return mImpl->on_vkMapMemory(
        context, input_result, device, memory, offset, size, flags, ppData);
}

void ResourceTracker::on_vkUnmapMemory(
    void* context,
    VkDevice device,
    VkDeviceMemory memory) {
    mImpl->on_vkUnmapMemory(context, device, memory);
}

VkResult ResourceTracker::on_vkCreateImage(
    void* context, VkResult input_result,
    VkDevice device, const VkImageCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkImage *pImage) {
    return mImpl->on_vkCreateImage(
        context, input_result,
        device, pCreateInfo, pAllocator, pImage);
}

void ResourceTracker::on_vkDestroyImage(
    void* context,
    VkDevice device, VkImage image, const VkAllocationCallbacks *pAllocator) {
    mImpl->on_vkDestroyImage(context,
        device, image, pAllocator);
}

void ResourceTracker::on_vkGetImageMemoryRequirements(
    void *context, VkDevice device, VkImage image,
    VkMemoryRequirements *pMemoryRequirements) {
    mImpl->on_vkGetImageMemoryRequirements(
        context, device, image, pMemoryRequirements);
}

void ResourceTracker::on_vkGetImageMemoryRequirements2(
    void *context, VkDevice device, const VkImageMemoryRequirementsInfo2 *pInfo,
    VkMemoryRequirements2 *pMemoryRequirements) {
    mImpl->on_vkGetImageMemoryRequirements2(
        context, device, pInfo, pMemoryRequirements);
}

void ResourceTracker::on_vkGetImageMemoryRequirements2KHR(
    void *context, VkDevice device, const VkImageMemoryRequirementsInfo2 *pInfo,
    VkMemoryRequirements2 *pMemoryRequirements) {
    mImpl->on_vkGetImageMemoryRequirements2KHR(
        context, device, pInfo, pMemoryRequirements);
}

VkResult ResourceTracker::on_vkBindImageMemory(
    void* context, VkResult input_result,
    VkDevice device, VkImage image, VkDeviceMemory memory,
    VkDeviceSize memoryOffset) {
    return mImpl->on_vkBindImageMemory(
        context, input_result, device, image, memory, memoryOffset);
}

VkResult ResourceTracker::on_vkBindImageMemory2(
    void* context, VkResult input_result,
    VkDevice device, uint32_t bindingCount, const VkBindImageMemoryInfo* pBindInfos) {
    return mImpl->on_vkBindImageMemory2(
        context, input_result, device, bindingCount, pBindInfos);
}

VkResult ResourceTracker::on_vkBindImageMemory2KHR(
    void* context, VkResult input_result,
    VkDevice device, uint32_t bindingCount, const VkBindImageMemoryInfo* pBindInfos) {
    return mImpl->on_vkBindImageMemory2KHR(
        context, input_result, device, bindingCount, pBindInfos);
}

VkResult ResourceTracker::on_vkCreateBuffer(
    void* context, VkResult input_result,
    VkDevice device, const VkBufferCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkBuffer *pBuffer) {
    return mImpl->on_vkCreateBuffer(
        context, input_result,
        device, pCreateInfo, pAllocator, pBuffer);
}

void ResourceTracker::on_vkDestroyBuffer(
    void* context,
    VkDevice device, VkBuffer buffer, const VkAllocationCallbacks *pAllocator) {
    mImpl->on_vkDestroyBuffer(context, device, buffer, pAllocator);
}

void ResourceTracker::on_vkGetBufferMemoryRequirements(
    void* context, VkDevice device, VkBuffer buffer, VkMemoryRequirements *pMemoryRequirements) {
    mImpl->on_vkGetBufferMemoryRequirements(context, device, buffer, pMemoryRequirements);
}

void ResourceTracker::on_vkGetBufferMemoryRequirements2(
    void* context, VkDevice device, const VkBufferMemoryRequirementsInfo2* pInfo,
    VkMemoryRequirements2* pMemoryRequirements) {
    mImpl->on_vkGetBufferMemoryRequirements2(
        context, device, pInfo, pMemoryRequirements);
}

void ResourceTracker::on_vkGetBufferMemoryRequirements2KHR(
    void* context, VkDevice device, const VkBufferMemoryRequirementsInfo2* pInfo,
    VkMemoryRequirements2* pMemoryRequirements) {
    mImpl->on_vkGetBufferMemoryRequirements2KHR(
        context, device, pInfo, pMemoryRequirements);
}

VkResult ResourceTracker::on_vkBindBufferMemory(
    void* context, VkResult input_result,
    VkDevice device, VkBuffer buffer, VkDeviceMemory memory, VkDeviceSize memoryOffset) {
    return mImpl->on_vkBindBufferMemory(
        context, input_result,
        device, buffer, memory, memoryOffset);
}

VkResult ResourceTracker::on_vkBindBufferMemory2(
    void* context, VkResult input_result,
    VkDevice device, uint32_t bindInfoCount, const VkBindBufferMemoryInfo *pBindInfos) {
    return mImpl->on_vkBindBufferMemory2(
        context, input_result,
        device, bindInfoCount, pBindInfos);
}

VkResult ResourceTracker::on_vkBindBufferMemory2KHR(
    void* context, VkResult input_result,
    VkDevice device, uint32_t bindInfoCount, const VkBindBufferMemoryInfo *pBindInfos) {
    return mImpl->on_vkBindBufferMemory2KHR(
        context, input_result,
        device, bindInfoCount, pBindInfos);
}

VkResult ResourceTracker::on_vkCreateSemaphore(
    void* context, VkResult input_result,
    VkDevice device, const VkSemaphoreCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkSemaphore *pSemaphore) {
    return mImpl->on_vkCreateSemaphore(
        context, input_result,
        device, pCreateInfo, pAllocator, pSemaphore);
}

void ResourceTracker::on_vkDestroySemaphore(
    void* context,
    VkDevice device, VkSemaphore semaphore, const VkAllocationCallbacks *pAllocator) {
    mImpl->on_vkDestroySemaphore(context, device, semaphore, pAllocator);
}

VkResult ResourceTracker::on_vkQueueSubmit(
    void* context, VkResult input_result,
    VkQueue queue, uint32_t submitCount, const VkSubmitInfo* pSubmits, VkFence fence) {
    return mImpl->on_vkQueueSubmit(
        context, input_result, queue, submitCount, pSubmits, fence);
}

VkResult ResourceTracker::on_vkGetSemaphoreFdKHR(
    void* context, VkResult input_result,
    VkDevice device,
    const VkSemaphoreGetFdInfoKHR* pGetFdInfo,
    int* pFd) {
    return mImpl->on_vkGetSemaphoreFdKHR(context, input_result, device, pGetFdInfo, pFd);
}

VkResult ResourceTracker::on_vkImportSemaphoreFdKHR(
    void* context, VkResult input_result,
    VkDevice device,
    const VkImportSemaphoreFdInfoKHR* pImportSemaphoreFdInfo) {
    return mImpl->on_vkImportSemaphoreFdKHR(context, input_result, device, pImportSemaphoreFdInfo);
}

void ResourceTracker::unwrap_VkNativeBufferANDROID(
    const VkImageCreateInfo* pCreateInfo,
    VkImageCreateInfo* local_pCreateInfo) {
    mImpl->unwrap_VkNativeBufferANDROID(pCreateInfo, local_pCreateInfo);
}

void ResourceTracker::unwrap_vkAcquireImageANDROID_nativeFenceFd(int fd, int* fd_out) {
    mImpl->unwrap_vkAcquireImageANDROID_nativeFenceFd(fd, fd_out);
}

#ifdef VK_USE_PLATFORM_FUCHSIA
VkResult ResourceTracker::on_vkGetMemoryZirconHandleFUCHSIA(
    void* context, VkResult input_result,
    VkDevice device,
    const VkMemoryGetZirconHandleInfoFUCHSIA* pInfo,
    uint32_t* pHandle) {
    return mImpl->on_vkGetMemoryZirconHandleFUCHSIA(
        context, input_result, device, pInfo, pHandle);
}

VkResult ResourceTracker::on_vkGetMemoryZirconHandlePropertiesFUCHSIA(
    void* context, VkResult input_result,
    VkDevice device,
    VkExternalMemoryHandleTypeFlagBits handleType,
    uint32_t handle,
    VkMemoryZirconHandlePropertiesFUCHSIA* pProperties) {
    return mImpl->on_vkGetMemoryZirconHandlePropertiesFUCHSIA(
        context, input_result, device, handleType, handle, pProperties);
}

VkResult ResourceTracker::on_vkGetSemaphoreZirconHandleFUCHSIA(
    void* context, VkResult input_result,
    VkDevice device,
    const VkSemaphoreGetZirconHandleInfoFUCHSIA* pInfo,
    uint32_t* pHandle) {
    return mImpl->on_vkGetSemaphoreZirconHandleFUCHSIA(
        context, input_result, device, pInfo, pHandle);
}

VkResult ResourceTracker::on_vkImportSemaphoreZirconHandleFUCHSIA(
    void* context, VkResult input_result,
    VkDevice device,
    const VkImportSemaphoreZirconHandleInfoFUCHSIA* pInfo) {
    return mImpl->on_vkImportSemaphoreZirconHandleFUCHSIA(
        context, input_result, device, pInfo);
}

VkResult ResourceTracker::on_vkCreateBufferCollectionFUCHSIA(
    void* context, VkResult input_result,
    VkDevice device,
    const VkBufferCollectionCreateInfoFUCHSIA* pInfo,
    const VkAllocationCallbacks* pAllocator,
    VkBufferCollectionFUCHSIA* pCollection) {
    return mImpl->on_vkCreateBufferCollectionFUCHSIA(
        context, input_result, device, pInfo, pAllocator, pCollection);
}

void ResourceTracker::on_vkDestroyBufferCollectionFUCHSIA(
        void* context, VkResult input_result,
        VkDevice device,
        VkBufferCollectionFUCHSIA collection,
        const VkAllocationCallbacks* pAllocator) {
    return mImpl->on_vkDestroyBufferCollectionFUCHSIA(
        context, input_result, device, collection, pAllocator);
}

VkResult ResourceTracker::on_vkSetBufferCollectionConstraintsFUCHSIA(
        void* context, VkResult input_result,
        VkDevice device,
        VkBufferCollectionFUCHSIA collection,
        const VkImageCreateInfo* pImageInfo) {
    return mImpl->on_vkSetBufferCollectionConstraintsFUCHSIA(
        context, input_result, device, collection, pImageInfo);
}

VkResult ResourceTracker::on_vkGetBufferCollectionPropertiesFUCHSIA(
        void* context, VkResult input_result,
        VkDevice device,
        VkBufferCollectionFUCHSIA collection,
        VkBufferCollectionPropertiesFUCHSIA* pProperties) {
    return mImpl->on_vkGetBufferCollectionPropertiesFUCHSIA(
        context, input_result, device, collection, pProperties);
}
#endif

VkResult ResourceTracker::on_vkGetAndroidHardwareBufferPropertiesANDROID(
    void* context, VkResult input_result,
    VkDevice device,
    const AHardwareBuffer* buffer,
    VkAndroidHardwareBufferPropertiesANDROID* pProperties) {
    return mImpl->on_vkGetAndroidHardwareBufferPropertiesANDROID(
        context, input_result, device, buffer, pProperties);
}
VkResult ResourceTracker::on_vkGetMemoryAndroidHardwareBufferANDROID(
    void* context, VkResult input_result,
    VkDevice device,
    const VkMemoryGetAndroidHardwareBufferInfoANDROID *pInfo,
    struct AHardwareBuffer** pBuffer) {
    return mImpl->on_vkGetMemoryAndroidHardwareBufferANDROID(
        context, input_result,
        device, pInfo, pBuffer);
}

VkResult ResourceTracker::on_vkCreateSamplerYcbcrConversion(
    void* context, VkResult input_result,
    VkDevice device,
    const VkSamplerYcbcrConversionCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkSamplerYcbcrConversion* pYcbcrConversion) {
    return mImpl->on_vkCreateSamplerYcbcrConversion(
        context, input_result, device, pCreateInfo, pAllocator, pYcbcrConversion);
}

VkResult ResourceTracker::on_vkCreateSamplerYcbcrConversionKHR(
    void* context, VkResult input_result,
    VkDevice device,
    const VkSamplerYcbcrConversionCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkSamplerYcbcrConversion* pYcbcrConversion) {
    return mImpl->on_vkCreateSamplerYcbcrConversionKHR(
        context, input_result, device, pCreateInfo, pAllocator, pYcbcrConversion);
}

VkResult ResourceTracker::on_vkMapMemoryIntoAddressSpaceGOOGLE_pre(
    void* context,
    VkResult input_result,
    VkDevice device,
    VkDeviceMemory memory,
    uint64_t* pAddress) {
    return mImpl->on_vkMapMemoryIntoAddressSpaceGOOGLE_pre(
        context, input_result, device, memory, pAddress);
}

VkResult ResourceTracker::on_vkMapMemoryIntoAddressSpaceGOOGLE(
    void* context,
    VkResult input_result,
    VkDevice device,
    VkDeviceMemory memory,
    uint64_t* pAddress) {
    return mImpl->on_vkMapMemoryIntoAddressSpaceGOOGLE(
        context, input_result, device, memory, pAddress);
}

VkResult ResourceTracker::on_vkCreateDescriptorUpdateTemplate(
    void* context, VkResult input_result,
    VkDevice device,
    const VkDescriptorUpdateTemplateCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkDescriptorUpdateTemplate* pDescriptorUpdateTemplate) {
    return mImpl->on_vkCreateDescriptorUpdateTemplate(
        context, input_result,
        device, pCreateInfo, pAllocator, pDescriptorUpdateTemplate);
}

VkResult ResourceTracker::on_vkCreateDescriptorUpdateTemplateKHR(
    void* context, VkResult input_result,
    VkDevice device,
    const VkDescriptorUpdateTemplateCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkDescriptorUpdateTemplate* pDescriptorUpdateTemplate) {
    return mImpl->on_vkCreateDescriptorUpdateTemplateKHR(
        context, input_result,
        device, pCreateInfo, pAllocator, pDescriptorUpdateTemplate);
}

void ResourceTracker::on_vkUpdateDescriptorSetWithTemplate(
    void* context,
    VkDevice device,
    VkDescriptorSet descriptorSet,
    VkDescriptorUpdateTemplate descriptorUpdateTemplate,
    const void* pData) {
    mImpl->on_vkUpdateDescriptorSetWithTemplate(
        context, device, descriptorSet,
        descriptorUpdateTemplate, pData);
}

VkResult ResourceTracker::on_vkGetPhysicalDeviceImageFormatProperties2(
    void* context, VkResult input_result,
    VkPhysicalDevice physicalDevice,
    const VkPhysicalDeviceImageFormatInfo2* pImageFormatInfo,
    VkImageFormatProperties2* pImageFormatProperties) {
    return mImpl->on_vkGetPhysicalDeviceImageFormatProperties2(
        context, input_result, physicalDevice, pImageFormatInfo,
        pImageFormatProperties);
}

VkResult ResourceTracker::on_vkGetPhysicalDeviceImageFormatProperties2KHR(
    void* context, VkResult input_result,
    VkPhysicalDevice physicalDevice,
    const VkPhysicalDeviceImageFormatInfo2* pImageFormatInfo,
    VkImageFormatProperties2* pImageFormatProperties) {
    return mImpl->on_vkGetPhysicalDeviceImageFormatProperties2KHR(
        context, input_result, physicalDevice, pImageFormatInfo,
        pImageFormatProperties);
}

VkResult ResourceTracker::on_vkBeginCommandBuffer(
    void* context, VkResult input_result,
    VkCommandBuffer commandBuffer,
    const VkCommandBufferBeginInfo* pBeginInfo) {
    return mImpl->on_vkBeginCommandBuffer(
        context, input_result, commandBuffer, pBeginInfo);
}

VkResult ResourceTracker::on_vkEndCommandBuffer(
    void* context, VkResult input_result,
    VkCommandBuffer commandBuffer) {
    return mImpl->on_vkEndCommandBuffer(
        context, input_result, commandBuffer);
}

VkResult ResourceTracker::on_vkResetCommandBuffer(
    void* context, VkResult input_result,
    VkCommandBuffer commandBuffer,
    VkCommandBufferResetFlags flags) {
    return mImpl->on_vkResetCommandBuffer(
        context, input_result, commandBuffer, flags);
}

VkResult ResourceTracker::on_vkCreateImageView(
    void* context, VkResult input_result,
    VkDevice device,
    const VkImageViewCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkImageView* pView) {
    return mImpl->on_vkCreateImageView(
        context, input_result, device, pCreateInfo, pAllocator, pView);
}

void ResourceTracker::deviceMemoryTransform_tohost(
    VkDeviceMemory* memory, uint32_t memoryCount,
    VkDeviceSize* offset, uint32_t offsetCount,
    VkDeviceSize* size, uint32_t sizeCount,
    uint32_t* typeIndex, uint32_t typeIndexCount,
    uint32_t* typeBits, uint32_t typeBitsCount) {
    mImpl->deviceMemoryTransform_tohost(
        memory, memoryCount,
        offset, offsetCount,
        size, sizeCount,
        typeIndex, typeIndexCount,
        typeBits, typeBitsCount);
}

void ResourceTracker::deviceMemoryTransform_fromhost(
    VkDeviceMemory* memory, uint32_t memoryCount,
    VkDeviceSize* offset, uint32_t offsetCount,
    VkDeviceSize* size, uint32_t sizeCount,
    uint32_t* typeIndex, uint32_t typeIndexCount,
    uint32_t* typeBits, uint32_t typeBitsCount) {
    mImpl->deviceMemoryTransform_fromhost(
        memory, memoryCount,
        offset, offsetCount,
        size, sizeCount,
        typeIndex, typeIndexCount,
        typeBits, typeBitsCount);
}

#define DEFINE_TRANSFORMED_TYPE_IMPL(type) \
    void ResourceTracker::transformImpl_##type##_tohost(const type*, uint32_t) { } \
    void ResourceTracker::transformImpl_##type##_fromhost(const type*, uint32_t) { } \

LIST_TRANSFORMED_TYPES(DEFINE_TRANSFORMED_TYPE_IMPL)

} // namespace goldfish_vk
