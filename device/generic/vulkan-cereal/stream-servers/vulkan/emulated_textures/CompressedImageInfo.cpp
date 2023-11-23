// Copyright 2022 The Android Open Source Project
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

#include "CompressedImageInfo.h"

#include "aemu/base/ArraySize.h"
#include "stream-servers/vulkan/VkFormatUtils.h"
#include "stream-servers/vulkan/emulated_textures/shaders/DecompressionShaders.h"

namespace gfxstream {
namespace vk {
namespace {

#define _RETURN_ON_FAILURE(cmd)                                                                \
    {                                                                                          \
        VkResult result = cmd;                                                                 \
        if (result != VK_SUCCESS) {                                                            \
            WARN("Warning: %s %s:%d vulkan failure %d", __func__, __FILE__, __LINE__, result); \
            return result;                                                                     \
        }                                                                                      \
    }

using android::base::arraySize;

struct Etc2PushConstant {
    uint32_t compFormat;
    uint32_t baseLayer;
};

struct AstcPushConstant {
    uint32_t blockSize[2];
    uint32_t baseLayer;
    uint32_t smallBlock;
};

struct ShaderData {
    const uint32_t* code;  // Pointer to shader's compiled spir-v code
    const size_t size;     // size of the code in bytes
};

struct ShaderGroup {
    ShaderData shader1D;
    ShaderData shader2D;
    ShaderData shader3D;
};

// Helper macro to declare the shader goups
#define DECLARE_SHADER_GROUP(Format)                                      \
    constexpr ShaderGroup kShader##Format {                               \
        .shader1D = {.code = decompression_shaders::Format##_1D,          \
                     .size = sizeof(decompression_shaders::Format##_1D)}, \
        .shader2D = {.code = decompression_shaders::Format##_2D,          \
                     .size = sizeof(decompression_shaders::Format##_2D)}, \
        .shader3D = {.code = decompression_shaders::Format##_3D,          \
                     .size = sizeof(decompression_shaders::Format##_3D)}, \
    }

DECLARE_SHADER_GROUP(Astc);
DECLARE_SHADER_GROUP(EacR11Snorm);
DECLARE_SHADER_GROUP(EacR11Unorm);
DECLARE_SHADER_GROUP(EacRG11Snorm);
DECLARE_SHADER_GROUP(EacRG11Unorm);
DECLARE_SHADER_GROUP(Etc2RGB8);
DECLARE_SHADER_GROUP(Etc2RGBA8);

#undef DECLARE_SHADER_GROUP

// Returns the group of shaders that can decompress a given format, or null if none is found.
const ShaderGroup* getShaderGroup(VkFormat format) {
    switch (format) {
        case VK_FORMAT_ASTC_4x4_UNORM_BLOCK:
        case VK_FORMAT_ASTC_5x4_UNORM_BLOCK:
        case VK_FORMAT_ASTC_5x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_6x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_6x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x8_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x8_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x10_UNORM_BLOCK:
        case VK_FORMAT_ASTC_12x10_UNORM_BLOCK:
        case VK_FORMAT_ASTC_12x12_UNORM_BLOCK:
        case VK_FORMAT_ASTC_4x4_SRGB_BLOCK:
        case VK_FORMAT_ASTC_5x4_SRGB_BLOCK:
        case VK_FORMAT_ASTC_5x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_6x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_6x6_SRGB_BLOCK:
        case VK_FORMAT_ASTC_8x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_8x6_SRGB_BLOCK:
        case VK_FORMAT_ASTC_8x8_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x6_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x8_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x10_SRGB_BLOCK:
        case VK_FORMAT_ASTC_12x10_SRGB_BLOCK:
        case VK_FORMAT_ASTC_12x12_SRGB_BLOCK:
            return &kShaderAstc;

        case VK_FORMAT_EAC_R11_SNORM_BLOCK:
            return &kShaderEacR11Snorm;

        case VK_FORMAT_EAC_R11_UNORM_BLOCK:
            return &kShaderEacR11Unorm;

        case VK_FORMAT_EAC_R11G11_SNORM_BLOCK:
            return &kShaderEacRG11Snorm;

        case VK_FORMAT_EAC_R11G11_UNORM_BLOCK:
            return &kShaderEacRG11Unorm;

        case VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A1_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A1_SRGB_BLOCK:
            return &kShaderEtc2RGB8;

        case VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A8_SRGB_BLOCK:
            return &kShaderEtc2RGBA8;

        default:
            return nullptr;
    }
}

// Returns the shader that can decompress a given image format and type
const ShaderData* getDecompressionShader(VkFormat format, VkImageType imageType) {
    const ShaderGroup* group = getShaderGroup(format);
    if (!group) return nullptr;

    switch (imageType) {
        case VK_IMAGE_TYPE_1D:
            return &group->shader1D;
        case VK_IMAGE_TYPE_2D:
            return &group->shader2D;
        case VK_IMAGE_TYPE_3D:
            return &group->shader3D;
        default:
            return nullptr;
    }
}

// Returns x / y, rounded up. E.g. ceil_div(7, 2) == 4
// Note the potential integer overflow for large numbers.
inline constexpr uint32_t ceil_div(uint32_t x, uint32_t y) { return (x + y - 1) / y; }

VkImageView createDefaultImageView(VulkanDispatch* vk, VkDevice device, VkImage image,
                                   VkFormat format, VkImageType imageType, uint32_t mipLevel,
                                   uint32_t layerCount) {
    VkImageViewCreateInfo imageViewInfo = {};
    imageViewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    imageViewInfo.image = image;
    switch (imageType) {
        case VK_IMAGE_TYPE_1D:
            imageViewInfo.viewType = VK_IMAGE_VIEW_TYPE_1D_ARRAY;
            break;
        case VK_IMAGE_TYPE_2D:
            imageViewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D_ARRAY;
            break;
        case VK_IMAGE_TYPE_3D:
            imageViewInfo.viewType = VK_IMAGE_VIEW_TYPE_3D;
            break;
        default:
            imageViewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D_ARRAY;
            break;
    }
    imageViewInfo.format = format;
    imageViewInfo.components.r = VK_COMPONENT_SWIZZLE_R;
    imageViewInfo.components.g = VK_COMPONENT_SWIZZLE_G;
    imageViewInfo.components.b = VK_COMPONENT_SWIZZLE_B;
    imageViewInfo.components.a = VK_COMPONENT_SWIZZLE_A;
    imageViewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    imageViewInfo.subresourceRange.baseMipLevel = mipLevel;
    imageViewInfo.subresourceRange.levelCount = 1;
    imageViewInfo.subresourceRange.baseArrayLayer = 0;
    imageViewInfo.subresourceRange.layerCount = layerCount;
    VkImageView imageView;
    if (vk->vkCreateImageView(device, &imageViewInfo, nullptr, &imageView) != VK_SUCCESS) {
        WARN("Warning: %s %s:%d failure", __func__, __FILE__, __LINE__);
        return VK_NULL_HANDLE;
    }
    return imageView;
}

VkExtent2D getBlockSize(VkFormat format) {
    switch (format) {
        case VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A1_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A1_SRGB_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A8_SRGB_BLOCK:
        case VK_FORMAT_EAC_R11_UNORM_BLOCK:
        case VK_FORMAT_EAC_R11_SNORM_BLOCK:
        case VK_FORMAT_EAC_R11G11_UNORM_BLOCK:
        case VK_FORMAT_EAC_R11G11_SNORM_BLOCK:
            return {4, 4};
        case VK_FORMAT_ASTC_4x4_UNORM_BLOCK:
        case VK_FORMAT_ASTC_4x4_SRGB_BLOCK:
            return {4, 4};
        case VK_FORMAT_ASTC_5x4_UNORM_BLOCK:
        case VK_FORMAT_ASTC_5x4_SRGB_BLOCK:
            return {5, 4};
        case VK_FORMAT_ASTC_5x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_5x5_SRGB_BLOCK:
            return {5, 5};
        case VK_FORMAT_ASTC_6x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_6x5_SRGB_BLOCK:
            return {6, 5};
        case VK_FORMAT_ASTC_6x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_6x6_SRGB_BLOCK:
            return {6, 6};
        case VK_FORMAT_ASTC_8x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x5_SRGB_BLOCK:
            return {8, 5};
        case VK_FORMAT_ASTC_8x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x6_SRGB_BLOCK:
            return {8, 6};
        case VK_FORMAT_ASTC_8x8_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x8_SRGB_BLOCK:
            return {8, 8};
        case VK_FORMAT_ASTC_10x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x5_SRGB_BLOCK:
            return {10, 5};
        case VK_FORMAT_ASTC_10x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x6_SRGB_BLOCK:
            return {10, 6};
        case VK_FORMAT_ASTC_10x8_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x8_SRGB_BLOCK:
            return {10, 8};
        case VK_FORMAT_ASTC_10x10_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x10_SRGB_BLOCK:
            return {10, 10};
        case VK_FORMAT_ASTC_12x10_UNORM_BLOCK:
        case VK_FORMAT_ASTC_12x10_SRGB_BLOCK:
            return {12, 10};
        case VK_FORMAT_ASTC_12x12_UNORM_BLOCK:
        case VK_FORMAT_ASTC_12x12_SRGB_BLOCK:
            return {12, 12};
        default:
            return {1, 1};
    }
}

// Returns whether a given memory barrier puts the image in a layout where it can be read from.
bool imageWillBecomeReadable(const VkImageMemoryBarrier& barrier) {
    return barrier.oldLayout != VK_IMAGE_LAYOUT_UNDEFINED &&
           (barrier.newLayout == VK_IMAGE_LAYOUT_GENERAL ||
            barrier.newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL ||
            barrier.newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
}

}  // namespace

CompressedImageInfo::CompressedImageInfo(VkDevice device) : mDevice(device) {}

CompressedImageInfo::CompressedImageInfo(VkDevice device, const VkImageCreateInfo& createInfo)
    : mDevice(device),
      mCompressedFormat(createInfo.format),
      mDecompressedFormat(getDecompressedFormat(mCompressedFormat)),
      mCompressedMipmapsFormat(getCompressedMipmapsFormat(mCompressedFormat)),
      mImageType(createInfo.imageType),
      mExtent(createInfo.extent),
      mBlock(getBlockSize(mCompressedFormat)),
      mLayerCount(createInfo.arrayLayers),
      mMipLevels(createInfo.mipLevels) {}

// static
VkFormat CompressedImageInfo::getDecompressedFormat(VkFormat compFmt) {
    switch (compFmt) {
        case VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A1_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK:
            return VK_FORMAT_R8G8B8A8_UNORM;
        case VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A1_SRGB_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A8_SRGB_BLOCK:
            return VK_FORMAT_R8G8B8A8_SRGB;
        case VK_FORMAT_EAC_R11_UNORM_BLOCK:
            return VK_FORMAT_R16_UNORM;
        case VK_FORMAT_EAC_R11_SNORM_BLOCK:
            return VK_FORMAT_R16_SNORM;
        case VK_FORMAT_EAC_R11G11_UNORM_BLOCK:
            return VK_FORMAT_R16G16_UNORM;
        case VK_FORMAT_EAC_R11G11_SNORM_BLOCK:
            return VK_FORMAT_R16G16_SNORM;
        case VK_FORMAT_ASTC_4x4_UNORM_BLOCK:
        case VK_FORMAT_ASTC_5x4_UNORM_BLOCK:
        case VK_FORMAT_ASTC_5x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_6x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_6x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x8_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x8_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x10_UNORM_BLOCK:
        case VK_FORMAT_ASTC_12x10_UNORM_BLOCK:
        case VK_FORMAT_ASTC_12x12_UNORM_BLOCK:
            return VK_FORMAT_R8G8B8A8_UNORM;
        case VK_FORMAT_ASTC_4x4_SRGB_BLOCK:
        case VK_FORMAT_ASTC_5x4_SRGB_BLOCK:
        case VK_FORMAT_ASTC_5x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_6x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_6x6_SRGB_BLOCK:
        case VK_FORMAT_ASTC_8x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_8x6_SRGB_BLOCK:
        case VK_FORMAT_ASTC_8x8_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x6_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x8_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x10_SRGB_BLOCK:
        case VK_FORMAT_ASTC_12x10_SRGB_BLOCK:
        case VK_FORMAT_ASTC_12x12_SRGB_BLOCK:
            return VK_FORMAT_R8G8B8A8_SRGB;
        default:
            return compFmt;
    }
}

// static
VkFormat CompressedImageInfo::getCompressedMipmapsFormat(VkFormat compFmt) {
    switch (compFmt) {
        case VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A1_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A1_SRGB_BLOCK:
            return VK_FORMAT_R16G16B16A16_UINT;
        case VK_FORMAT_EAC_R11_UNORM_BLOCK:
        case VK_FORMAT_EAC_R11_SNORM_BLOCK:
            return VK_FORMAT_R32G32_UINT;
        case VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A8_SRGB_BLOCK:
        case VK_FORMAT_EAC_R11G11_UNORM_BLOCK:
        case VK_FORMAT_EAC_R11G11_SNORM_BLOCK:
        case VK_FORMAT_ASTC_4x4_UNORM_BLOCK:
        case VK_FORMAT_ASTC_5x4_UNORM_BLOCK:
        case VK_FORMAT_ASTC_5x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_6x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_6x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x8_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x8_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x10_UNORM_BLOCK:
        case VK_FORMAT_ASTC_12x10_UNORM_BLOCK:
        case VK_FORMAT_ASTC_12x12_UNORM_BLOCK:
        case VK_FORMAT_ASTC_4x4_SRGB_BLOCK:
        case VK_FORMAT_ASTC_5x4_SRGB_BLOCK:
        case VK_FORMAT_ASTC_5x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_6x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_6x6_SRGB_BLOCK:
        case VK_FORMAT_ASTC_8x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_8x6_SRGB_BLOCK:
        case VK_FORMAT_ASTC_8x8_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x6_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x8_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x10_SRGB_BLOCK:
        case VK_FORMAT_ASTC_12x10_SRGB_BLOCK:
        case VK_FORMAT_ASTC_12x12_SRGB_BLOCK:
            return VK_FORMAT_R32G32B32A32_UINT;
        default:
            return compFmt;
    }
}

// static
bool CompressedImageInfo::isEtc2(VkFormat format) {
    switch (format) {
        case VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A1_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A1_SRGB_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A8_SRGB_BLOCK:
        case VK_FORMAT_EAC_R11_UNORM_BLOCK:
        case VK_FORMAT_EAC_R11_SNORM_BLOCK:
        case VK_FORMAT_EAC_R11G11_UNORM_BLOCK:
        case VK_FORMAT_EAC_R11G11_SNORM_BLOCK:
            return true;
        default:
            return false;
    }
}

// static
bool CompressedImageInfo::isAstc(VkFormat format) {
    switch (format) {
        case VK_FORMAT_ASTC_4x4_UNORM_BLOCK:
        case VK_FORMAT_ASTC_4x4_SRGB_BLOCK:
        case VK_FORMAT_ASTC_5x4_UNORM_BLOCK:
        case VK_FORMAT_ASTC_5x4_SRGB_BLOCK:
        case VK_FORMAT_ASTC_5x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_5x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_6x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_6x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_6x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_6x6_SRGB_BLOCK:
        case VK_FORMAT_ASTC_8x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_8x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x6_SRGB_BLOCK:
        case VK_FORMAT_ASTC_8x8_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x8_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x6_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x8_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x8_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x10_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x10_SRGB_BLOCK:
        case VK_FORMAT_ASTC_12x10_UNORM_BLOCK:
        case VK_FORMAT_ASTC_12x10_SRGB_BLOCK:
        case VK_FORMAT_ASTC_12x12_UNORM_BLOCK:
        case VK_FORMAT_ASTC_12x12_SRGB_BLOCK:
            return true;
        default:
            return false;
    }
}

// static
bool CompressedImageInfo::needEmulatedAlpha(VkFormat format) {
    switch (format) {
        case VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK:
            return true;
        default:
            return false;
    }
}

bool CompressedImageInfo::isEtc2() const { return isEtc2(mCompressedFormat); }

bool CompressedImageInfo::isAstc() const { return isAstc(mCompressedFormat); }

VkImageCreateInfo CompressedImageInfo::getDecompressedCreateInfo(
    const VkImageCreateInfo& createInfo) const {
    VkImageCreateInfo result = createInfo;
    result.format = mDecompressedFormat;
    result.flags &= ~VK_IMAGE_CREATE_BLOCK_TEXEL_VIEW_COMPATIBLE_BIT_KHR;
    result.flags |= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
    result.usage |= VK_IMAGE_USAGE_STORAGE_BIT;
    return result;
}

void CompressedImageInfo::createCompressedMipmapImages(VulkanDispatch* vk,
                                                       const VkImageCreateInfo& createInfo) {
    if (!mCompressedMipmaps.empty()) {
        return;
    }

    VkImageCreateInfo createInfoCopy = createInfo;
    createInfoCopy.format = mCompressedMipmapsFormat;
    createInfoCopy.usage |= VK_IMAGE_USAGE_STORAGE_BIT;
    createInfoCopy.flags &= ~VK_IMAGE_CREATE_BLOCK_TEXEL_VIEW_COMPATIBLE_BIT_KHR;
    createInfoCopy.flags |= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
    createInfoCopy.mipLevels = 1;

    mCompressedMipmaps.resize(mMipLevels);
    for (uint32_t i = 0; i < mMipLevels; i++) {
        createInfoCopy.extent = compressedMipmapExtent(i);
        vk->vkCreateImage(mDevice, &createInfoCopy, nullptr, mCompressedMipmaps.data() + i);
    }

    // Get the size of all images (decompressed image and compressed mipmaps)
    std::vector<VkDeviceSize> memSizes(mMipLevels + 1);
    memSizes[0] = getImageSize(vk, mDecompressedImage);
    for (size_t i = 0; i < mMipLevels; i++) {
        memSizes[i + 1] = getImageSize(vk, mCompressedMipmaps[i]);
    }

    // Initialize the memory offsets
    mMemoryOffsets.resize(mMipLevels + 1);
    for (size_t i = 0; i < mMipLevels + 1; i++) {
        VkDeviceSize alignedSize = memSizes[i];
        if (mAlignment != 0) {
            alignedSize = ceil_div(alignedSize, mAlignment) * mAlignment;
        }
        mMemoryOffsets[i] = (i == 0 ? 0 : mMemoryOffsets[i - 1]) + alignedSize;
    }
}

void CompressedImageInfo::initAstcCpuDecompression(VulkanDispatch* vk,
                                                   VkPhysicalDevice physicalDevice) {
    mAstcTexture = std::make_unique<AstcTexture>(vk, mDevice, physicalDevice, mExtent, mBlock.width,
                                                 mBlock.height, &AstcCpuDecompressor::get());
}

bool CompressedImageInfo::decompressIfNeeded(VulkanDispatch* vk, VkCommandBuffer commandBuffer,
                                             VkPipelineStageFlags srcStageMask,
                                             VkPipelineStageFlags dstStageMask,
                                             const VkImageMemoryBarrier& targetBarrier,
                                             std::vector<VkImageMemoryBarrier>& outputBarriers) {
    std::vector<VkImageMemoryBarrier> imageBarriers = getImageBarriers(targetBarrier);

    if (!imageWillBecomeReadable(targetBarrier)) {
        // We're not going to read from the image, no need to decompress it.
        // Apply the target barrier to the compressed mipmaps and the decompressed image.
        outputBarriers.insert(outputBarriers.end(), imageBarriers.begin(), imageBarriers.end());
        return false;
    }

    VkResult result = initializeDecompressionPipeline(vk, mDevice);
    if (result != VK_SUCCESS) {
        WARN("Failed to initialize pipeline for texture decompression");
        return false;
    }

    // Transition the layout of all the compressed mipmaps so that the shader can read from them.
    for (auto& barrier : imageBarriers) {
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    }

    // Transition the layout of the decompressed image so that we can write to it.
    imageBarriers.back().srcAccessMask = 0;
    imageBarriers.back().oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    imageBarriers.back().dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    imageBarriers.back().newLayout = VK_IMAGE_LAYOUT_GENERAL;

    // Do the layout transitions
    vk->vkCmdPipelineBarrier(commandBuffer, srcStageMask, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                             0, nullptr, 0, nullptr, imageBarriers.size(), imageBarriers.data());

    // Run the decompression shader
    decompress(vk, commandBuffer, getImageSubresourceRange(targetBarrier.subresourceRange));

    // Finally, transition the layout of all images to match the target barrier.
    for (auto& barrier : imageBarriers) {
        barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.dstAccessMask = targetBarrier.dstAccessMask;
        barrier.newLayout = targetBarrier.newLayout;
    }
    // (adjust the last barrier since it's for the decompressed image)
    imageBarriers.back().srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;

    // Do the layout transitions
    vk->vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, dstStageMask, 0,
                             0, nullptr, 0, nullptr, imageBarriers.size(), imageBarriers.data());

    return true;
}

void CompressedImageInfo::decompressOnCpu(VkCommandBuffer commandBuffer, uint8_t* srcAstcData,
                                          size_t astcDataSize, VkImage dstImage,
                                          VkImageLayout dstImageLayout, uint32_t regionCount,
                                          const VkBufferImageCopy* pRegions,
                                          const VkDecoderContext& context) {
    mAstcTexture->on_vkCmdCopyBufferToImage(commandBuffer, srcAstcData, astcDataSize, dstImage,
                                            dstImageLayout, regionCount, pRegions, context);
}

VkMemoryRequirements CompressedImageInfo::getMemoryRequirements() const {
    return {
        .size = mMemoryOffsets.back(),
        .alignment = mAlignment,
    };
}

VkResult CompressedImageInfo::bindCompressedMipmapsMemory(VulkanDispatch* vk, VkDeviceMemory memory,
                                                          VkDeviceSize memoryOffset) {
    VkResult result = VK_SUCCESS;
    for (size_t i = 0; i < mCompressedMipmaps.size(); i++) {
        VkResult res = vk->vkBindImageMemory(mDevice, mCompressedMipmaps[i], memory,
                                             memoryOffset + mMemoryOffsets[i]);
        if (res != VK_SUCCESS) result = res;
    }
    return result;
}

VkBufferImageCopy CompressedImageInfo::getBufferImageCopy(
    const VkBufferImageCopy& origRegion) const {
    VkBufferImageCopy region = origRegion;
    uint32_t mipLevel = region.imageSubresource.mipLevel;
    region.imageSubresource.mipLevel = 0;
    region.bufferRowLength /= mBlock.width;
    region.bufferImageHeight /= mBlock.height;
    region.imageOffset.x /= mBlock.width;
    region.imageOffset.y /= mBlock.height;
    region.imageExtent = compressedMipmapPortion(region.imageExtent, mipLevel);
    return region;
}

// static
VkImageCopy CompressedImageInfo::getCompressedMipmapsImageCopy(const VkImageCopy& origRegion,
                                                               const CompressedImageInfo& srcImg,
                                                               const CompressedImageInfo& dstImg,
                                                               bool needEmulatedSrc,
                                                               bool needEmulatedDst) {
    VkImageCopy region = origRegion;
    if (needEmulatedSrc) {
        uint32_t mipLevel = region.srcSubresource.mipLevel;
        region.srcSubresource.mipLevel = 0;
        region.srcOffset.x /= srcImg.mBlock.width;
        region.srcOffset.y /= srcImg.mBlock.height;
        region.extent = srcImg.compressedMipmapPortion(region.extent, mipLevel);
    }
    if (needEmulatedDst) {
        region.dstSubresource.mipLevel = 0;
        region.dstOffset.x /= dstImg.mBlock.width;
        region.dstOffset.y /= dstImg.mBlock.height;
    }
    return region;
}

void CompressedImageInfo::destroy(VulkanDispatch* vk) {
    for (const auto& image : mCompressedMipmaps) {
        vk->vkDestroyImage(mDevice, image, nullptr);
    }
    vk->vkDestroyDescriptorSetLayout(mDevice, mDecompDescriptorSetLayout, nullptr);
    vk->vkDestroyDescriptorPool(mDevice, mDecompDescriptorPool, nullptr);
    vk->vkDestroyShaderModule(mDevice, mDecompShader, nullptr);
    vk->vkDestroyPipelineLayout(mDevice, mDecompPipelineLayout, nullptr);
    vk->vkDestroyPipeline(mDevice, mDecompPipeline, nullptr);
    for (const auto& imageView : mCompressedMipmapsImageViews) {
        vk->vkDestroyImageView(mDevice, imageView, nullptr);
    }
    for (const auto& imageView : mDecompImageViews) {
        vk->vkDestroyImageView(mDevice, imageView, nullptr);
    }
    vk->vkDestroyImage(mDevice, mDecompressedImage, nullptr);
}

VkDeviceSize CompressedImageInfo::getImageSize(VulkanDispatch* vk, VkImage image) {
    VkMemoryRequirements memRequirements;
    vk->vkGetImageMemoryRequirements(mDevice, image, &memRequirements);
    mAlignment = std::max(mAlignment, memRequirements.alignment);
    return memRequirements.size;
}

std::vector<VkImageMemoryBarrier> CompressedImageInfo::getImageBarriers(
    const VkImageMemoryBarrier& srcBarrier) {
    const VkImageSubresourceRange range = getImageSubresourceRange(srcBarrier.subresourceRange);

    std::vector<VkImageMemoryBarrier> imageBarriers;
    imageBarriers.reserve(range.levelCount + 1);

    // Add the barriers for the compressed mipmaps
    VkImageMemoryBarrier mipmapBarrier = srcBarrier;
    mipmapBarrier.subresourceRange.baseMipLevel = 0;
    mipmapBarrier.subresourceRange.levelCount = 1;
    imageBarriers.insert(imageBarriers.begin(), range.levelCount, mipmapBarrier);
    for (uint32_t j = 0; j < range.levelCount; j++) {
        imageBarriers[j].image = mCompressedMipmaps[range.baseMipLevel + j];
    }

    // Add a barrier for the decompressed image
    imageBarriers.push_back(srcBarrier);
    imageBarriers.back().image = mDecompressedImage;

    return imageBarriers;
}

VkImageSubresourceRange CompressedImageInfo::getImageSubresourceRange(
    const VkImageSubresourceRange& range) const {
    VkImageSubresourceRange result = range;
    if (result.levelCount == VK_REMAINING_MIP_LEVELS) {
        result.levelCount = mMipLevels - range.baseMipLevel;
    }
    if (result.layerCount == VK_REMAINING_ARRAY_LAYERS) {
        result.layerCount = mLayerCount - range.baseArrayLayer;
    }
    return result;
}

VkResult CompressedImageInfo::initializeDecompressionPipeline(VulkanDispatch* vk, VkDevice device) {
    if (mDecompPipeline != nullptr) {
        return VK_SUCCESS;
    }

    const ShaderData* shader = getDecompressionShader(mCompressedFormat, mImageType);
    if (!shader) {
        WARN("No decompression shader found for format %s and img type %s",
             string_VkFormat(mCompressedFormat), string_VkImageType(mImageType));
        return VK_ERROR_FORMAT_NOT_SUPPORTED;
    }

    VkShaderModuleCreateInfo shaderInfo = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = shader->size,
        .pCode = shader->code,
    };
    _RETURN_ON_FAILURE(vk->vkCreateShaderModule(device, &shaderInfo, nullptr, &mDecompShader));

    VkDescriptorSetLayoutBinding dsLayoutBindings[] = {
        {
            .binding = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        },
        {
            .binding = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        },
    };
    VkDescriptorSetLayoutCreateInfo dsLayoutInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 2,
        .pBindings = dsLayoutBindings,
    };
    _RETURN_ON_FAILURE(vk->vkCreateDescriptorSetLayout(device, &dsLayoutInfo, nullptr,
                                                       &mDecompDescriptorSetLayout));

    VkDescriptorPoolSize poolSize = {
        .type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
        .descriptorCount = 2 * mMipLevels,
    };
    VkDescriptorPoolCreateInfo dsPoolInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT,
        .maxSets = mMipLevels,
        .poolSizeCount = 1,
        .pPoolSizes = &poolSize,
    };
    _RETURN_ON_FAILURE(
        vk->vkCreateDescriptorPool(device, &dsPoolInfo, nullptr, &mDecompDescriptorPool));

    std::vector<VkDescriptorSetLayout> layouts(mMipLevels, mDecompDescriptorSetLayout);

    VkDescriptorSetAllocateInfo dsInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = mDecompDescriptorPool,
        .descriptorSetCount = mMipLevels,
        .pSetLayouts = layouts.data(),
    };
    mDecompDescriptorSets.resize(mMipLevels);
    _RETURN_ON_FAILURE(vk->vkAllocateDescriptorSets(device, &dsInfo, mDecompDescriptorSets.data()));

    VkPushConstantRange pushConstant = {.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT};
    if (isEtc2()) {
        pushConstant.size = sizeof(Etc2PushConstant);
    } else if (isAstc()) {
        pushConstant.size = sizeof(AstcPushConstant);
    }

    VkPipelineLayoutCreateInfo pipelineLayoutInfo = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1,
        .pSetLayouts = &mDecompDescriptorSetLayout,
        .pushConstantRangeCount = 1,
        .pPushConstantRanges = &pushConstant,
    };
    _RETURN_ON_FAILURE(
        vk->vkCreatePipelineLayout(device, &pipelineLayoutInfo, nullptr, &mDecompPipelineLayout));

    VkComputePipelineCreateInfo computePipelineInfo = {
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .stage = {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                  .stage = VK_SHADER_STAGE_COMPUTE_BIT,
                  .module = mDecompShader,
                  .pName = "main"},
        .layout = mDecompPipelineLayout,
    };
    _RETURN_ON_FAILURE(vk->vkCreateComputePipelines(device, nullptr, 1, &computePipelineInfo,
                                                    nullptr, &mDecompPipeline));

    VkFormat intermediateFormat = VK_FORMAT_R8G8B8A8_UINT;
    switch (mCompressedFormat) {
        case VK_FORMAT_EAC_R11_UNORM_BLOCK:
        case VK_FORMAT_EAC_R11_SNORM_BLOCK:
        case VK_FORMAT_EAC_R11G11_UNORM_BLOCK:
        case VK_FORMAT_EAC_R11G11_SNORM_BLOCK:
            intermediateFormat = mDecompressedFormat;
            break;
        default:
            break;
    }

    mCompressedMipmapsImageViews.resize(mMipLevels);
    mDecompImageViews.resize(mMipLevels);

    VkDescriptorImageInfo compressedMipmapsDescriptorImageInfo = {.imageLayout =
                                                                      VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo mDecompDescriptorImageInfo = {.imageLayout = VK_IMAGE_LAYOUT_GENERAL};
    VkWriteDescriptorSet writeDescriptorSets[2] = {
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstBinding = 0,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .pImageInfo = &compressedMipmapsDescriptorImageInfo,
        },
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstBinding = 1,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .pImageInfo = &mDecompDescriptorImageInfo,
        }};

    for (uint32_t i = 0; i < mMipLevels; i++) {
        mCompressedMipmapsImageViews[i] =
            createDefaultImageView(vk, device, mCompressedMipmaps[i], mCompressedMipmapsFormat,
                                   mImageType, 0, mLayerCount);
        mDecompImageViews[i] = createDefaultImageView(
            vk, device, mDecompressedImage, intermediateFormat, mImageType, i, mLayerCount);
        compressedMipmapsDescriptorImageInfo.imageView = mCompressedMipmapsImageViews[i];
        mDecompDescriptorImageInfo.imageView = mDecompImageViews[i];
        writeDescriptorSets[0].dstSet = mDecompDescriptorSets[i];
        writeDescriptorSets[1].dstSet = mDecompDescriptorSets[i];
        vk->vkUpdateDescriptorSets(device, 2, writeDescriptorSets, 0, nullptr);
    }
    return VK_SUCCESS;
}

void CompressedImageInfo::decompress(VulkanDispatch* vk, VkCommandBuffer commandBuffer,
                                     const VkImageSubresourceRange& range) {
    vk->vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, mDecompPipeline);
    uint32_t dispatchZ = mExtent.depth == 1 ? range.layerCount : mExtent.depth;

    if (isEtc2()) {
        const Etc2PushConstant pushConstant = {
            .compFormat = (uint32_t)mCompressedFormat,
            .baseLayer = mExtent.depth == 1 ? range.baseArrayLayer : 0};
        vk->vkCmdPushConstants(commandBuffer, mDecompPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                               sizeof(pushConstant), &pushConstant);
    } else if (isAstc()) {
        uint32_t smallBlock = false;
        switch (mCompressedFormat) {
            case VK_FORMAT_ASTC_4x4_UNORM_BLOCK:
            case VK_FORMAT_ASTC_5x4_UNORM_BLOCK:
            case VK_FORMAT_ASTC_5x5_UNORM_BLOCK:
            case VK_FORMAT_ASTC_6x5_UNORM_BLOCK:
            case VK_FORMAT_ASTC_4x4_SRGB_BLOCK:
            case VK_FORMAT_ASTC_5x4_SRGB_BLOCK:
            case VK_FORMAT_ASTC_5x5_SRGB_BLOCK:
            case VK_FORMAT_ASTC_6x5_SRGB_BLOCK:
                smallBlock = true;
                break;
            default:
                break;
        }
        const AstcPushConstant pushConstant = {
            .blockSize = {mBlock.width, mBlock.height},
            .baseLayer = mExtent.depth == 1 ? range.baseArrayLayer : 0,
            .smallBlock = smallBlock};
        vk->vkCmdPushConstants(commandBuffer, mDecompPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                               sizeof(pushConstant), &pushConstant);
    }
    for (uint32_t i = range.baseMipLevel; i < range.baseMipLevel + range.levelCount; i++) {
        vk->vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                                    mDecompPipelineLayout, 0, 1, mDecompDescriptorSets.data() + i,
                                    0, nullptr);
        VkExtent3D compExtent = compressedMipmapExtent(i);
        vk->vkCmdDispatch(commandBuffer, compExtent.width, compExtent.height, dispatchZ);
    }
}

VkExtent3D CompressedImageInfo::mipmapExtent(uint32_t level) const {
    return {
        .width = std::max<uint32_t>(mExtent.width >> level, 1),
        .height = std::max<uint32_t>(mExtent.height >> level, 1),
        .depth = std::max<uint32_t>(mExtent.depth >> level, 1),
    };
}

VkExtent3D CompressedImageInfo::compressedMipmapExtent(uint32_t level) const {
    VkExtent3D result = mipmapExtent(level);
    result.width = ceil_div(result.width, mBlock.width);
    result.height = ceil_div(result.height, mBlock.height);
    return result;
}

VkExtent3D CompressedImageInfo::compressedMipmapPortion(const VkExtent3D& origExtent,
                                                        uint32_t level) const {
    VkExtent3D maxExtent = compressedMipmapExtent(level);
    return {
        .width = std::min(ceil_div(origExtent.width, mBlock.width), maxExtent.width),
        .height = std::min(ceil_div(origExtent.height, mBlock.height), maxExtent.height),
        .depth = origExtent.depth,
    };
}

}  // namespace vk
}  // namespace gfxstream
