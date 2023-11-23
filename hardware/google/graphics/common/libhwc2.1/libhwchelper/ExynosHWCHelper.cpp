/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include "ExynosHWCHelper.h"

#include <linux/videodev2.h>
#include <linux/videodev2_exynos_media.h>
#include <png.h>
#include <sync/sync.h>
#include <sys/mman.h>
#include <utils/CallStack.h>
#include <utils/Errors.h>

#include <iomanip>

#include "ExynosHWC.h"
#include "ExynosHWCDebug.h"
#include "ExynosLayer.h"
#include "ExynosResourceRestriction.h"
#include "VendorVideoAPI.h"
#include "exynos_sync.h"

using vendor::graphics::BufferUsage;
using vendor::graphics::VendorGraphicBufferUsage;
using vendor::graphics::VendorGraphicBufferMeta;

#define AFBC_MAGIC  0xafbc

#define FT_LOGD(msg, ...) \
    {\
        if (exynosHWCControl.fenceTracer >= 2) \
            ALOGD("[FenceTracer]::" msg, ##__VA_ARGS__); \
    }
#define FT_LOGE(msg, ...) \
    {\
        if (exynosHWCControl.fenceTracer > 0) \
            ALOGE("[FenceTracer]::" msg, ##__VA_ARGS__); \
    }
#define FT_LOGW(msg, ...) \
    {\
        if (exynosHWCControl.fenceTracer >= 1) \
            ALOGD("[FenceTracer]::" msg, ##__VA_ARGS__); \
    }

extern struct exynos_hwc_control exynosHWCControl;
extern char fence_names[FENCE_MAX][32];

uint32_t getHWC1CompType(int32_t type) {

    uint32_t cType = HWC_FRAMEBUFFER;

    switch(type) {
    case HWC2_COMPOSITION_DEVICE:
    case HWC2_COMPOSITION_EXYNOS:
        cType = HWC_OVERLAY;
        break;
    case HWC2_COMPOSITION_SOLID_COLOR:
        cType = HWC_BACKGROUND;
        break;
    case HWC2_COMPOSITION_CURSOR:
        cType = HWC_CURSOR_OVERLAY;
        break;
    case HWC2_COMPOSITION_SIDEBAND:
        cType = HWC_SIDEBAND;
        break;
    case HWC2_COMPOSITION_CLIENT:
    case HWC2_COMPOSITION_INVALID:
    default:
        cType = HWC_FRAMEBUFFER;
        break;
    }

    return cType;
}

uint32_t getDrmMode(uint64_t flags)
{
    if (flags & BufferUsage::PROTECTED) {
        if (flags & VendorGraphicBufferUsage::PRIVATE_NONSECURE)
            return NORMAL_DRM;
        else
            return SECURE_DRM;
    }
    return NO_DRM;
}

uint32_t getDrmMode(const buffer_handle_t handle)
{
    uint64_t usage = VendorGraphicBufferMeta::get_usage(handle);
    if (usage & BufferUsage::PROTECTED) {
        if (usage & VendorGraphicBufferUsage::PRIVATE_NONSECURE)
            return NORMAL_DRM;
        else
            return SECURE_DRM;
    }

    return NO_DRM;
}

unsigned int isNarrowRgb(int format, android_dataspace data_space)
{
    if (format == HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_FULL)
        return 0;
    else {
        if (isFormatRgb(format))
            return 0;
        else {
            uint32_t data_space_range = (data_space & HAL_DATASPACE_RANGE_MASK);
            if (data_space_range == HAL_DATASPACE_RANGE_UNSPECIFIED) {
                return 1;
            } else if (data_space_range == HAL_DATASPACE_RANGE_FULL) {
                return 0;
            } else {
                return 1;
            }
        }
    }
}

const format_description_t* halFormatToExynosFormat(int inHalFormat, uint32_t inCompressType) {
    for (unsigned int i = 0; i < FORMAT_MAX_CNT; i++) {
        const int descHalFormat = exynos_format_desc[i].halFormat;
        uint32_t descCompressType = exynos_format_desc[i].getCompression();

        // TODO: b/175381083, Skip checking SBWC compression type
        if (descCompressType == SBWC || descCompressType == SBWC_LOSSY) {
            descCompressType = COMP_ANY;
        }

        if ((inHalFormat == descHalFormat) &&
            ((inCompressType == COMP_ANY) || (descCompressType == COMP_ANY) ||
             (inCompressType == descCompressType))) {
            return &exynos_format_desc[i];
        }
    }
    return nullptr;
}

uint8_t formatToBpp(int format)
{
    for (unsigned int i = 0; i < FORMAT_MAX_CNT; i++){
        if (exynos_format_desc[i].halFormat == format)
            return exynos_format_desc[i].bpp;
    }

    ALOGW("unrecognized pixel format %u", format);
    return 0;
}

uint8_t DpuFormatToBpp(decon_pixel_format format)
{
    for (unsigned int i = 0; i < FORMAT_MAX_CNT; i++){
        if (exynos_format_desc[i].s3cFormat == format)
            return exynos_format_desc[i].bpp;
    }
    ALOGW("unrecognized decon format %u", format);
    return 0;
}

bool isFormatRgb(int format)
{
    for (unsigned int i = 0; i < FORMAT_MAX_CNT; i++){
        if (exynos_format_desc[i].halFormat == format) {
            if (exynos_format_desc[i].type & RGB)
                return true;
            else
                return false;
        }
    }
    return false;
}

bool isFormatYUV(int format)
{
    if (isFormatRgb(format))
        return false;
    return true;
}

bool isFormatSBWC(int format)
{
    for (unsigned int i = 0; i < FORMAT_MAX_CNT; i++){
        if (exynos_format_desc[i].halFormat == format) {
            if ((exynos_format_desc[i].type & SBWC) ||
                    (exynos_format_desc[i].type & SBWC_LOSSY))
                return true;
            else
                return false;
        }
    }
    return false;
}

bool isFormatYUV420(int format)
{
    for (unsigned int i = 0; i < FORMAT_MAX_CNT; i++){
        if (exynos_format_desc[i].halFormat == format) {
            if (exynos_format_desc[i].type & YUV420)
                return true;
            else
                return false;
        }
    }
    return false;
}

bool isFormatYUV8_2(int format)
{
    for (unsigned int i = 0; i < FORMAT_MAX_CNT; i++){
        if (exynos_format_desc[i].halFormat == format) {
            if ((exynos_format_desc[i].type & YUV420) &&
                (exynos_format_desc[i].type & BIT8_2))
                return true;
            else
                return false;
        }
    }
    return false;
}

bool isFormat10BitYUV420(int format)
{
    for (unsigned int i = 0; i < FORMAT_MAX_CNT; i++){
        if (exynos_format_desc[i].halFormat == format) {
            if ((exynos_format_desc[i].type & YUV420) &&
                (exynos_format_desc[i].type & BIT10))
                return true;
            else
                return false;
        }
    }
    return false;
}

bool isFormatYUV422(int format)
{
    for (unsigned int i = 0; i < FORMAT_MAX_CNT; i++){
        if (exynos_format_desc[i].halFormat == format) {
            if (exynos_format_desc[i].type & YUV422)
                return true;
            else
                return false;
        }
    }
    return false;
}

bool isFormatP010(int format)
{
    for (unsigned int i = 0; i < FORMAT_MAX_CNT; i++){
        if (exynos_format_desc[i].halFormat == format) {
            if (exynos_format_desc[i].type & P010)
                return true;
            else
                return false;
        }
    }
    return false;
}

bool isFormatYCrCb(int format)
{
    return format == HAL_PIXEL_FORMAT_EXYNOS_YV12_M;
}

bool isFormatLossy(int format)
{
    for (unsigned int i = 0; i < FORMAT_MAX_CNT; i++){
        if (exynos_format_desc[i].halFormat == format) {
            if (exynos_format_desc[i].type & SBWC_LOSSY)
                return true;
            else
                return false;
        }
    }
    return false;
}

bool formatHasAlphaChannel(int format)
{
    for (unsigned int i = 0; i < FORMAT_MAX_CNT; i++){
        if (exynos_format_desc[i].halFormat == format) {
            return exynos_format_desc[i].hasAlpha;
        }
    }
    return false;
}

bool isAFBCCompressed(const buffer_handle_t handle) {
    if (handle != NULL) {
        return VendorGraphicBufferMeta::is_afbc(handle);
    }

    return false;
}

uint32_t getCompressionType(const buffer_handle_t handle) {
    if (isAFBCCompressed(handle)) {
        return AFBC;
    }

    // TODO: b/175381083, Add SBWC check here or make a function in gralloc
    return 0;
}

uint32_t halDataSpaceToV4L2ColorSpace(android_dataspace data_space)
{
    uint32_t standard_data_space = (data_space & HAL_DATASPACE_STANDARD_MASK);
    switch (standard_data_space) {
    case HAL_DATASPACE_STANDARD_BT2020:
    case HAL_DATASPACE_STANDARD_BT2020_CONSTANT_LUMINANCE:
        return V4L2_COLORSPACE_BT2020;
    case HAL_DATASPACE_STANDARD_DCI_P3:
        return V4L2_COLORSPACE_DCI_P3;
    case HAL_DATASPACE_STANDARD_BT709:
        return V4L2_COLORSPACE_REC709;
    default:
        return V4L2_COLORSPACE_DEFAULT;
    }
    return V4L2_COLORSPACE_DEFAULT;
}

enum decon_pixel_format halFormatToDpuFormat(int format, uint32_t compressType) {
    auto exynosFormat = halFormatToExynosFormat(format, compressType);
    return (exynosFormat != nullptr) ? exynosFormat->s3cFormat : DECON_PIXEL_FORMAT_MAX;
}

uint32_t DpuFormatToHalFormat(int format, uint32_t /*compressType*/) {
    for (unsigned int i = 0; i < FORMAT_MAX_CNT; i++){
        if (exynos_format_desc[i].s3cFormat == static_cast<decon_pixel_format>(format))
            return exynos_format_desc[i].halFormat;
    }
    return HAL_PIXEL_FORMAT_EXYNOS_UNDEFINED;
}

int halFormatToDrmFormat(int format, uint32_t compressType)
{
    auto exynosFormat = halFormatToExynosFormat(format, compressType);
    return (exynosFormat != nullptr) ? exynosFormat->drmFormat : DRM_FORMAT_UNDEFINED;
}

int32_t drmFormatToHalFormats(int format, std::vector<uint32_t> *halFormats)
{
    if (halFormats == NULL)
        return -EINVAL;

    halFormats->clear();
    for (unsigned int i = 0; i < FORMAT_MAX_CNT; i++){
        if (exynos_format_desc[i].drmFormat == format) {
            halFormats->push_back(exynos_format_desc[i].halFormat);
        }
    }
    return NO_ERROR;
}

int drmFormatToHalFormat(int format)
{
    for (unsigned int i = 0; i < FORMAT_MAX_CNT; i++){
        if (exynos_format_desc[i].drmFormat == format)
            return exynos_format_desc[i].halFormat;
    }
    return HAL_PIXEL_FORMAT_EXYNOS_UNDEFINED;
}

android_dataspace colorModeToDataspace(android_color_mode_t mode)
{
    android_dataspace dataSpace = HAL_DATASPACE_UNKNOWN;
    switch (mode) {
        case HAL_COLOR_MODE_STANDARD_BT601_625:
            dataSpace = HAL_DATASPACE_STANDARD_BT601_625;
            break;
        case HAL_COLOR_MODE_STANDARD_BT601_625_UNADJUSTED:
            dataSpace = HAL_DATASPACE_STANDARD_BT601_625_UNADJUSTED;
            break;
        case HAL_COLOR_MODE_STANDARD_BT601_525:
            dataSpace = HAL_DATASPACE_STANDARD_BT601_525;
            break;
        case HAL_COLOR_MODE_STANDARD_BT601_525_UNADJUSTED:
            dataSpace = HAL_DATASPACE_STANDARD_BT601_525_UNADJUSTED;
            break;
        case HAL_COLOR_MODE_STANDARD_BT709:
            dataSpace = HAL_DATASPACE_STANDARD_BT709;
            break;
        case HAL_COLOR_MODE_DCI_P3:
            dataSpace = HAL_DATASPACE_DCI_P3;
            break;
        case HAL_COLOR_MODE_ADOBE_RGB:
            dataSpace = HAL_DATASPACE_ADOBE_RGB;
            break;
        case HAL_COLOR_MODE_DISPLAY_P3:
            dataSpace = HAL_DATASPACE_DISPLAY_P3;
            break;
        case HAL_COLOR_MODE_SRGB:
            dataSpace = HAL_DATASPACE_V0_SRGB;
            break;
        case HAL_COLOR_MODE_NATIVE:
            dataSpace = HAL_DATASPACE_UNKNOWN;
            break;
        default:
            break;
    }
    return dataSpace;
}

uint64_t halTransformToDrmRot(uint32_t halTransform)
{
    switch (halTransform) {
    case HAL_TRANSFORM_FLIP_H:
        return DRM_MODE_REFLECT_Y|DRM_MODE_ROTATE_0;
    case HAL_TRANSFORM_FLIP_V:
        return DRM_MODE_REFLECT_X|DRM_MODE_ROTATE_0;
    case HAL_TRANSFORM_ROT_180:
        return DRM_MODE_ROTATE_180;
    case HAL_TRANSFORM_ROT_90:
        return DRM_MODE_ROTATE_90;
    case (HAL_TRANSFORM_ROT_90|HAL_TRANSFORM_FLIP_H):
        /*
         * HAL: HAL_TRANSFORM_FLIP_H -> HAL_TRANSFORM_ROT_90
         * DPP: ROT_90 -> XFLIP
         */
        return (DRM_MODE_ROTATE_90|DRM_MODE_REFLECT_X);
    case (HAL_TRANSFORM_ROT_90|HAL_TRANSFORM_FLIP_V):
        /*
         * HAL: HAL_TRANSFORM_FLIP_V -> HAL_TRANSFORM_ROT_90
         * DPP: ROT_90 -> YFLIP
         */
        return (DRM_MODE_ROTATE_90|DRM_MODE_REFLECT_Y);
    case HAL_TRANSFORM_ROT_270:
        return DRM_MODE_ROTATE_270;
    default:
        return DRM_MODE_ROTATE_0;
    }
}

void dumpHandle(uint32_t type, buffer_handle_t h)
{
    if (h == NULL)
        return;

    VendorGraphicBufferMeta gmeta(h);

    HDEBUGLOGD(type, "\t\tformat = %d, width = %u, height = %u, stride = %u, vstride = %u",
            gmeta.format, gmeta.width, gmeta.height, gmeta.stride, gmeta.vstride);
}

void dumpExynosImage(uint32_t type, exynos_image &img)
{
    if (!hwcCheckDebugMessages(type))
        return;

    String8 result;
    dumpExynosImage(result, img);

    ALOGD("%s", result.string());
}

void dumpExynosImage(String8& result, exynos_image &img)
{
    result.appendFormat("\tbufferHandle: %p, fullWidth: %d, fullHeight: %d, x: %d, y: %d, w: %d, "
                        "h: %d, format: %s\n",
                        img.bufferHandle, img.fullWidth, img.fullHeight, img.x, img.y, img.w, img.h,
                        getFormatStr(img.format, img.compressed ? AFBC : 0).string());
    result.appendFormat("\tusageFlags: 0x%" PRIx64 ", layerFlags: 0x%8x, acquireFenceFd: %d, releaseFenceFd: %d\n",
            img.usageFlags, img.layerFlags, img.acquireFenceFd, img.releaseFenceFd);
    result.appendFormat("\tdataSpace(%d), blending(%d), transform(0x%2x), afbc(%d)\n",
                        img.dataSpace, img.blending, img.transform, img.compressed);
    if (img.bufferHandle != NULL) {
        VendorGraphicBufferMeta gmeta(img.bufferHandle);
        result.appendFormat("\tbuffer's stride: %d, %d\n", gmeta.stride, gmeta.vstride);
    }
}

void printExynosLayer(const ExynosLayer* layer) {
    if (layer == nullptr) {
        return;
    }

    const_cast<ExynosLayer*>(layer)->printLayer();
}

bool isSrcCropFloat(hwc_frect &frect)
{
    return (frect.left != (int)frect.left) ||
        (frect.top != (int)frect.top) ||
        (frect.right != (int)frect.right) ||
        (frect.bottom != (int)frect.bottom);
}

bool isScaled(exynos_image &src, exynos_image &dst)
{
    uint32_t srcW = src.w;
    uint32_t srcH = src.h;
    uint32_t dstW = dst.w;
    uint32_t dstH = dst.h;

    if (!!(src.transform & HAL_TRANSFORM_ROT_90)) {
        dstW = dst.h;
        dstH = dst.w;
    }

    return ((srcW != dstW) || (srcH != dstH));
}

bool isScaledDown(exynos_image &src, exynos_image &dst)
{
    uint32_t srcW = src.w;
    uint32_t srcH = src.h;
    uint32_t dstW = dst.w;
    uint32_t dstH = dst.h;

    if (!!(src.transform & HAL_TRANSFORM_ROT_90)) {
        dstW = dst.h;
        dstH = dst.w;
    }

    return ((srcW > dstW) || (srcH > dstH));
}

bool hasHdrInfo(const exynos_image& img) {
    uint32_t dataSpace = img.dataSpace;

    /* By reference Layer's dataspace */
    uint32_t standard = (dataSpace & HAL_DATASPACE_STANDARD_MASK);
    uint32_t transfer = (dataSpace & HAL_DATASPACE_TRANSFER_MASK);

    if ((standard == HAL_DATASPACE_STANDARD_BT2020) ||
            (standard == HAL_DATASPACE_STANDARD_BT2020_CONSTANT_LUMINANCE) ||
            (standard == HAL_DATASPACE_STANDARD_DCI_P3)) {
        if ((transfer == HAL_DATASPACE_TRANSFER_ST2084) ||
                (transfer == HAL_DATASPACE_TRANSFER_HLG))
            return true;
        else
            return false;
    }

    return false;
}

bool hasHdrInfo(android_dataspace dataSpace) {
    exynos_image img;
    img.dataSpace = dataSpace;
    return hasHdrInfo(img);
}

bool hasHdr10Plus(exynos_image &img) {
    /* TODO Check layer has hdr10 and dynamic metadata here */
    return (img.metaType & VIDEO_INFO_TYPE_HDR_DYNAMIC) ? true : false;
}

String8 getFormatStr(int format, uint32_t compressType) {
    auto exynosFormat = halFormatToExynosFormat(format, compressType);

    if (exynosFormat != nullptr) {
        return exynosFormat->name;
    }

    String8 result;
    result.appendFormat("? %08x", format);
    return result;
}

void adjustRect(hwc_rect_t &rect, int32_t width, int32_t height)
{
    if (rect.left < 0)
        rect.left = 0;
    if (rect.left > width)
        rect.left = width;
    if (rect.top < 0)
        rect.top = 0;
    if (rect.top > height)
        rect.top = height;
    if (rect.right < rect.left)
        rect.right = rect.left;
    if (rect.right > width)
        rect.right = width;
    if (rect.bottom < rect.top)
        rect.bottom = rect.top;
    if (rect.bottom > height)
        rect.bottom = height;
}

uint32_t getBufferNumOfFormat(int format, uint32_t compressType) {
    auto exynosFormat = halFormatToExynosFormat(format, compressType);
    return (exynosFormat != nullptr) ? exynosFormat->bufferNum : 0;
}

uint32_t getPlaneNumOfFormat(int format, uint32_t compressType) {
    auto exynosFormat = halFormatToExynosFormat(format, compressType);
    return (exynosFormat != nullptr) ? exynosFormat->planeNum : 0;
}

uint32_t getBytePerPixelOfPrimaryPlane(int format) {
    if (isFormatRgb(format))
        return (formatToBpp(format) / 8);
    else if (isFormat10BitYUV420(format))
        return 2;
    else if (isFormatYUV420(format))
        return 1;
    else
        return 0;
}

void setFenceName(int fenceFd, hwc_fence_type fenceType)
{
    if (fenceFd >= 3)
        ioctl(fenceFd, SYNC_IOC_FENCE_NAME, fence_names[fenceType]);
    else if (fenceFd == -1) {
        HDEBUGLOGD(eDebugFence, "%s : fence (type %d) is -1", __func__, (int)fenceType);
    }
    else {
        ALOGW("%s : fence (type %d) is less than 3", __func__, (int)fenceType);
        hwc_print_stack();
    }
}

uint32_t getExynosBufferYLength(uint32_t width, uint32_t height, int format)
{
    switch (format) {
    case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M:
    case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_FULL:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_PRIV:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M:
    case HAL_PIXEL_FORMAT_EXYNOS_YV12_M:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P_M:
        return NV12M_Y_SIZE(width, height);
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_S10B:
        HDEBUGLOGD(eDebugMPP, "8bit size(Y) : %d, extra size : %d", NV12M_Y_SIZE(width, height), NV12M_Y_2B_SIZE(width, height));
        return NV12M_Y_SIZE(width, height) + NV12M_Y_2B_SIZE(width, height);
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_S10B:
        return NV12N_10B_Y_8B_SIZE(width, height) + NV12N_10B_Y_2B_SIZE(width, height);
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_P010_M:
        HDEBUGLOGD(eDebugMPP, "size(Y) : %d", P010M_Y_SIZE(width, height));
        return P010M_Y_SIZE(width, height);
    case HAL_PIXEL_FORMAT_YCBCR_P010:
        HDEBUGLOGD(eDebugMPP, "size(Y) : %d", P010_Y_SIZE(width, height));
        return P010_Y_SIZE(width, height);
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN:
        return YUV420N_Y_SIZE(width, height);
    case HAL_PIXEL_FORMAT_GOOGLE_NV12_SP_10B:
        return 2 * __ALIGN_UP(width, 64) * __ALIGN_UP(height, 8);
    case HAL_PIXEL_FORMAT_GOOGLE_NV12_SP:
        return __ALIGN_UP(width, 64) * __ALIGN_UP(height, 8);
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L50:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L75:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L50:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L75:
    case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_SBWC:
        return SBWC_8B_Y_SIZE(width, height) +
            SBWC_8B_Y_HEADER_SIZE(width, height);
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L40:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L60:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L80:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L40:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L60:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L80:
    case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_10B_SBWC:
        return SBWC_10B_Y_SIZE(width, height) +
            SBWC_10B_Y_HEADER_SIZE(width, height);
    }

    return NV12M_Y_SIZE(width, height) + ((width % 128) == 0 ? 0 : 256);
}

uint32_t getExynosBufferCbCrLength(uint32_t width, uint32_t height, int format)
{
    switch (format) {
    case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M:
    case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_FULL:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_PRIV:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M:
    case HAL_PIXEL_FORMAT_EXYNOS_YV12_M:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_P_M:
        return NV12M_CBCR_SIZE(width, height);
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_S10B:
        HDEBUGLOGD(eDebugMPP, "8bit size(CbCr) : %d, extra size : %d",NV12M_CBCR_SIZE(width, height), NV12M_CBCR_2B_SIZE(width, height));
        return NV12M_CBCR_SIZE(width, height) + NV12M_CBCR_2B_SIZE(width, height);
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_P010_M:
        HDEBUGLOGD(eDebugMPP, "size(CbCr) : %d", P010M_CBCR_SIZE(width, height));
        return P010M_CBCR_SIZE(width, height);
    case HAL_PIXEL_FORMAT_YCBCR_P010:
        HDEBUGLOGD(eDebugMPP, "size(CbCr) : %d", P010_CBCR_SIZE(width, height));
        return P010_CBCR_SIZE(width, height);
    case HAL_PIXEL_FORMAT_GOOGLE_NV12_SP_10B:
        return __ALIGN_UP(width, 64) * __ALIGN_UP(height, 8);
    case HAL_PIXEL_FORMAT_GOOGLE_NV12_SP:
        return __ALIGN_UP(width, 64) * __ALIGN_UP(height, 8) / 2;
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L50:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_SBWC_L75:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L50:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_SBWC_L75:
    case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_SBWC:
        return SBWC_8B_CBCR_SIZE(width, height) +
            SBWC_8B_CBCR_HEADER_SIZE(width, height);
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L40:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L60:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SP_M_10B_SBWC_L80:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L40:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L60:
    case HAL_PIXEL_FORMAT_EXYNOS_YCbCr_420_SPN_10B_SBWC_L80:
    case HAL_PIXEL_FORMAT_EXYNOS_YCrCb_420_SP_M_10B_SBWC:
        return SBWC_10B_CBCR_SIZE(width, height) +
            SBWC_10B_CBCR_HEADER_SIZE(width, height);
    }

    return NV12M_CBCR_SIZE(width, height);
}

int getBufLength(buffer_handle_t handle, uint32_t planerNum, size_t *length, int format, uint32_t width, uint32_t height)
{
    uint32_t bufferNumber = getBufferNumOfFormat(format, getCompressionType(handle));
    if ((bufferNumber == 0) || (bufferNumber > planerNum))
        return -EINVAL;

    VendorGraphicBufferMeta gmeta(handle);

    switch (bufferNumber) {
        case 1:
            length[0] = gmeta.size;
            break;
        case 2:
            HDEBUGLOGD(eDebugMPP, "-- %s x : %d y : %d format : %d",__func__, width, height, format);
            length[0] = gmeta.size;
            length[1] = gmeta.size1;
            HDEBUGLOGD(eDebugMPP, "Y size : %zu CbCr size : %zu", length[0], length[1]);
            break;
        case 3:
            length[0] = width * height;
            length[1]= (length[0]/4);
            length[2]= (length[0]/4);
            break;
    }
    return NO_ERROR;
}

int fence_close(int fence, ExynosDisplay* display,
        hwc_fdebug_fence_type type, hwc_fdebug_ip_type ip) {
    if (display != NULL)
        setFenceInfo(fence, display, type, ip, FENCE_CLOSE);
    return hwcFdClose(fence);
}

bool fence_valid(int fence) {
    if (fence == -1){
        HDEBUGLOGD(eDebugFence, "%s : fence is -1", __func__);
        return false;
    } else if (fence < 3) {
        ALOGW("%s : fence (fd:%d) is less than 3", __func__, fence);
        hwc_print_stack();
        return true;
    } else if (fence >= MAX_FD_NUM) {
        ALOGW("%s : fence (fd:%d) over MAX fd number", __func__, fence);
        /* valid but fence will not be traced */
        return true;
    }
    return true;
}

int hwcFdClose(int fd) {
    if (fd>= 3)
        close(fd);
    else if (fd == -1){
        HDEBUGLOGD(eDebugFence, "%s : Fd is -1", __func__);
    } else {
        ALOGW("%s : Fd:%d is less than 3", __func__, fd);
        hwc_print_stack();
    }
    return -1;
}

int hwc_dup(int fd, ExynosDisplay* display, hwc_fdebug_fence_type type, hwc_fdebug_ip_type ip,
            bool pendingAllowed) {
    int dup_fd = -1;

    if (fd>= 3)
        dup_fd = dup(fd);
    else if (fd == -1){
        HDEBUGLOGD(eDebugFence, "%s : Fd is -1", __func__);
    } else {
        ALOGW("%s : Fd:%d is less than 3", __func__, fd);
        hwc_print_stack();
    }

    if ((dup_fd < 3) && (dup_fd != -1)) {
        ALOGW("%s : Dupulicated Fd:%d is less than 3 : %d", __func__, fd, dup_fd);
        hwc_print_stack();
    }

    setFenceInfo(dup_fd, display, type, ip, FENCE_FROM, pendingAllowed);
    FT_LOGD("duplicated %d from %d", dup_fd, fd);

    return dup_fd;
}

int hwc_print_stack() {
    /* CallStack stack; */
    /* stack.update(); */
    /* stack.log("HWCException", ANDROID_LOG_ERROR, "HWCException"); */
    return 0;
}

struct tm* getLocalTime(struct timeval tv) {
    return (struct tm*)localtime((time_t*)&tv.tv_sec);
}

void setFenceInfo(uint32_t fd, ExynosDisplay* display,
        hwc_fdebug_fence_type type, hwc_fdebug_ip_type ip,
        uint32_t direction, bool pendingAllowed) {

    if (!fence_valid(fd) || display == NULL) return;
    /* valid but fence will not be traced */
    if (fd >= MAX_FD_NUM) return;

    ExynosDevice* device = display->mDevice;
    hwc_fence_info_t* info = &device->mFenceInfo[fd];
    info->displayId = display->mDisplayId;
    struct timeval tv;

    // FIXME: sync_fence_info, sync_pt_info are deprecated
    //        HWC guys should fix this.
#if 0
    if (exynosHWCControl.sysFenceLogging) {
        struct sync_pt_info* pt_info = NULL;
        info->sync_data = NULL;
        if (info->sync_data != NULL) {
            pt_info = sync_pt_info(info->sync_data, pt_info);
            if (pt_info !=NULL) {
                FT_LOGD("real name : %s status : %s pt_obj : %s pt_drv : %s",
                        info->sync_data->name, info->sync_data->status==1 ? "Active":"Signaled",
                        pt_info->obj_name, pt_info->driver_name);
            } else {
                FT_LOGD("real name : %s status : %d pt_info : %p",
                        info->sync_data->name, info->sync_data->status, pt_info);
            }
            sync_fence_info_free(info->sync_data);
        }
    }
#endif

    fenceTrace_t *trace = NULL;

    switch(direction) {
    case FENCE_FROM:
        trace = &info->from;
        info->to.type = FENCE_TYPE_UNDEFINED;
        info->to.ip = FENCE_IP_UNDEFINED;
        info->usage++;
        break;
    case FENCE_TO:
        trace = &info->to;
        info->usage--;
        break;
    case FENCE_DUP:
        trace = &info->dup;
        info->usage++;
        break;
    case FENCE_CLOSE:
        trace = &info->close;
        info->usage--;
        if (info->usage < 0) info->usage = 0;
        break;
    default:
        ALOGE("Fence trace : Undefined direction!");
        break;
    }

    if (trace != NULL) {
        trace->type = type;
        trace->ip = ip;
        gettimeofday(&trace->time, NULL);
        tv = trace->time;
        trace->curFlag = 1;
        FT_LOGW("FD : %d, direction : %d, type : %d, ip : %d", fd, direction, trace->type, trace->ip);
    //  device->fenceTracing.appendFormat("FD : %d, From : %s\n", fd, info->trace.fromName);
    }

#if 0 // To be used ?
    struct tm* localTime = getLocalTime(tv);
    device->fenceTracing.appendFormat("usage : %d, time:%02d-%02d %02d:%02d:%02d.%03lu(%lu)\n", info->usage,
            localTime->tm_mon+1, localTime->tm_mday,
            localTime->tm_hour, localTime->tm_min,
            localTime->tm_sec, tv.tv_usec/1000,
            ((tv.tv_sec * 1000) + (tv.tv_usec / 1000)));
#endif

    // Fence's usage count shuld be zero at end of frame(present done).
    // This flag means usage count of the fence can be pended over frame.
    info->pendingAllowed = pendingAllowed;

    if (info->usage == 0)
        info->pendingAllowed = false;

    info->last_dir = direction;
}

void printLastFenceInfo(uint32_t fd, ExynosDisplay* display) {

    struct timeval tv;

    if (!fence_valid(fd)) return;
    /* valid but fence will not be traced */
    if (fd >= MAX_FD_NUM) return;

    ExynosDevice* device = display->mDevice;

    hwc_fence_info_t* info = &device->mFenceInfo[fd];
    FT_LOGD("---- Fence FD : %d, Display(%d) ----", fd, info->displayId);

    fenceTrace_t *trace = NULL;

    switch(info->last_dir) {
    case FENCE_FROM:
        trace = &info->from;
        break;
    case FENCE_TO:
        trace = &info->to;
        break;
    case FENCE_DUP:
        trace = &info->dup;
        break;
    case FENCE_CLOSE:
        trace = &info->close;
        break;
    default:
        ALOGE("Fence trace : Undefined direction!");
        break;
    }

    if (trace != NULL) {
        FT_LOGD("Last state : %d, type(%d), ip(%d)",
                info->last_dir, trace->type, trace->ip);
        tv = info->from.time;
    }

    FT_LOGD("from : %d, %d (cur : %d), to : %d, %d (cur : %d), hwc_dup : %d, %d (cur : %d),hwc_close : %d, %d (cur : %d)",
            info->from.type, info->from.ip, info->from.curFlag,
            info->to.type, info->to.ip, info->to.curFlag,
            info->dup.type, info->dup.ip, info->dup.curFlag,
            info->close.type, info->close.ip, info->close.curFlag);

    struct tm* localTime = getLocalTime(tv);

    FT_LOGD("usage : %d, time:%02d-%02d %02d:%02d:%02d.%03lu(%lu)", info->usage,
            localTime->tm_mon+1, localTime->tm_mday,
            localTime->tm_hour, localTime->tm_min,
            localTime->tm_sec, tv.tv_usec/1000,
            ((tv.tv_sec * 1000) + (tv.tv_usec / 1000)));
}

void dumpFenceInfo(ExynosDisplay *display, int32_t __unused depth) {

    ExynosDevice* device = display->mDevice;
    hwc_fence_info_t* info = device->mFenceInfo;

    FT_LOGD("Dump fence ++");
    for (int i=0; i < MAX_FD_NUM; i++){
        if ((info[i].usage >= 1 || info[i].usage <= -1) && (!info[i].pendingAllowed))
            printLastFenceInfo(i, display);
    }
    FT_LOGD("Dump fence --");
}

void printLeakFds(ExynosDisplay *display){

    ExynosDevice* device = display->mDevice;
    hwc_fence_info_t* info = device->mFenceInfo;

    int cnt = 1, i = 0;
    String8 errStringOne;
    String8 errStringMinus;

    errStringOne.appendFormat("Leak Fds (1) :\n");

    for (i=0; i < MAX_FD_NUM; i++){
        if(info[i].usage == 1) {
            errStringOne.appendFormat("%d,", i);
            if(cnt++%10 == 0)
                errStringOne.appendFormat("\n");
        }
    }
    FT_LOGW("%s", errStringOne.string());

    errStringMinus.appendFormat("Leak Fds (-1) :\n");

    cnt = 1;
    for (i=0; i < MAX_FD_NUM; i++){
        if(info[i].usage < 0) {
            errStringMinus.appendFormat("%d,", i);
            if(cnt++%10 == 0)
                errStringMinus.appendFormat("\n");
        }
    }
    FT_LOGW("%s", errStringMinus.string());
}

void dumpNCheckLeak(ExynosDisplay *display, int32_t __unused depth) {

    ExynosDevice* device = display->mDevice;
    hwc_fence_info_t* info = device->mFenceInfo;

    FT_LOGD("Dump leaking fence ++");
    for (int i=0; i < MAX_FD_NUM; i++){
        if ((info[i].usage >= 1 || info[i].usage <= -1) && (!info[i].pendingAllowed))
            // leak is occured in this frame first
            if (!info[i].leaking) {
                info[i].leaking = true;
                printLastFenceInfo(i, display);
            }
    }

    int priv = exynosHWCControl.fenceTracer;
    exynosHWCControl.fenceTracer = 3;
    printLeakFds(display);
    exynosHWCControl.fenceTracer = priv;

    FT_LOGD("Dump leaking fence --");
}

bool fenceWarn(ExynosDisplay *display, uint32_t threshold) {

    uint32_t cnt = 0, r_cnt = 0;
    ExynosDevice* device = display->mDevice;
    hwc_fence_info_t* info = device->mFenceInfo;

    // FIXME: sync_fence_info() is deprecated
    //        HWC guys should fix this.
#if 0
    if (exynosHWCControl.sysFenceLogging) {
        for (int i=3; i < MAX_FD_NUM; i++){
            struct sync_fence_info_data* data = nullptr;
            data = NULL; //sync_fence_info(i);
            if (data != NULL) {
                r_cnt++;
                sync_fence_info_free(data);
            }
        }
    }
#endif

    for (int i=0; i < MAX_FD_NUM; i++){
        if(info[i].usage >= 1 || info[i].usage <= -1)
        cnt++;
    }

    if ((cnt>threshold) || (exynosHWCControl.fenceTracer > 0))
        dumpFenceInfo(display, 0);

    if (r_cnt>threshold)
        ALOGE("Fence leak somewhare!!");

    FT_LOGD("fence hwc : %d, real : %d", cnt, r_cnt);

    return (cnt>threshold) ? true : false;
}

void resetFenceCurFlag(ExynosDisplay *display) {
    ExynosDevice* device = display->mDevice;
    hwc_fence_info_t* info = device->mFenceInfo;
    for (int i=0; i < MAX_FD_NUM; i++){
        if (info[i].usage == 0) {
            info[i].displayId = HWC_DISPLAY_PRIMARY;
            info[i].leaking = false;
            info[i].from.curFlag = 0;
            info[i].to.curFlag = 0;
            info[i].dup.curFlag = 0;
            info[i].close.curFlag = 0;
            info[i].curFlag = 0;
        } else {
            info[i].curFlag = 0;
        }
    }
}

bool validateFencePerFrame(ExynosDisplay *display) {

    ExynosDevice* device = display->mDevice;
    hwc_fence_info_t* info = device->mFenceInfo;
    bool ret = true;

    for (int i=0; i < MAX_FD_NUM; i++){
        if (info[i].displayId != display->mDisplayId)
            continue;
        if ((info[i].usage >= 1 || info[i].usage <= -1) &&
                (!info[i].pendingAllowed) && (!info[i].leaking)) {
            ret = false;
        }
    }

    if (!ret) {
        int priv = exynosHWCControl.fenceTracer;
        exynosHWCControl.fenceTracer = 3;
        dumpNCheckLeak(display, 0);
        exynosHWCControl.fenceTracer = priv;
    }

    return ret;
}

void setFenceName(uint32_t fd, ExynosDisplay *display,
        hwc_fdebug_fence_type type, hwc_fdebug_ip_type ip,
        uint32_t direction, bool pendingAllowed) {

    ExynosDevice* device = display->mDevice;
    if (!fence_valid(fd) || device == NULL) return;
    /* valid but fence will not be traced */
    if (fd >= MAX_FD_NUM) return;

    hwc_fence_info_t* info = &device->mFenceInfo[fd];
    info->displayId = display->mDisplayId;
    fenceTrace_t *trace = NULL;

    switch(direction) {
    case FENCE_FROM:
        trace = &info->from;
        break;
    case FENCE_TO:
        trace = &info->to;
        break;
    case FENCE_DUP:
        trace = &info->dup;
        break;
    case FENCE_CLOSE:
        trace = &info->close;
        break;
    default:
        ALOGE("Fence trace : Undefined direction!");
        break;
    }

    if (trace != NULL) {
        trace->type = type;
        trace->ip = ip;
        FT_LOGD("FD : %d, direction : %d, type(%d), ip(%d) (changed)", fd, direction, type, ip);
    }

    info->pendingAllowed = pendingAllowed;

    if (info->usage == 0)
        info->pendingAllowed = false;
}

String8 getMPPStr(int typeId) {
    if (typeId < MPP_DPP_NUM){
        int cnt = sizeof(AVAILABLE_OTF_MPP_UNITS)/sizeof(exynos_mpp_t);
        for (int i = 0; i < cnt; i++){
            if (AVAILABLE_OTF_MPP_UNITS[i].physicalType == typeId)
                return String8(AVAILABLE_OTF_MPP_UNITS[i].name);
        }
    } else {
        int cnt = sizeof(AVAILABLE_M2M_MPP_UNITS)/sizeof(exynos_mpp_t);
        for (int i = 0; i < cnt; i++){
            if (AVAILABLE_M2M_MPP_UNITS[i].physicalType == typeId)
                return String8(AVAILABLE_M2M_MPP_UNITS[i].name);
        }
    }
    String8 result;
    result.appendFormat("? %08x", typeId);
    return result;
}

bool hasPPC(uint32_t physicalType, uint32_t formatIndex, uint32_t rotIndex) {
    if (ppc_table_map.find(PPC_IDX(physicalType, formatIndex, rotIndex)) !=
            ppc_table_map.end()) {
        return true;
    }
    return false;
}

TableBuilder& TableBuilder::add(const std::string& key, const uint64_t& value, bool toHex) {
    std::stringstream v;
    if (toHex)
        v << "0x" << std::hex << value;
    else
        v << value;
    data.emplace_back(std::make_pair(key, v.str()));
    return *this;
}

TableBuilder& TableBuilder::add(const std::string& key, const std::vector<uint64_t>& values,
                                bool toHex) {
    std::stringstream value;
    for (int i = 0; i < values.size(); i++) {
        if (i) value << ", ";

        if (toHex)
            value << "0x" << std::hex << values[i];
        else
            value << values[i];
    }

    data.emplace_back(std::make_pair(key, value.str()));
    return *this;
}

std::string TableBuilder::build() {
    std::stringstream splitter, header, content;
    splitter << "+";
    header << "|";
    content << "|";
    for (const auto& [key, value] : data) {
        int size = std::max(key.size(), value.size()) + 2 /* for spaces around the string */;
        splitter << std::string(size, '-') << "+";
        header << buildPaddedString(key, size) << "|";
        content << buildPaddedString(value, size) << "|";
    }

    std::string output = splitter.str() + "\n" +
                         header.str() + "\n" +
                         splitter.str() + "\n" +
                         content.str() + "\n" +
                         splitter.str() + "\n";
    return output;
}

std::string TableBuilder::buildPaddedString(const std::string& str, int size) {
    int totalPadding = size - str.size();
    int leftPadding = totalPadding / 2.0;
    int rightPadding = (totalPadding / 2.0) + 0.6; // Poor person's ceil

    return std::string(leftPadding, ' ') + str + std::string(rightPadding, ' ');
}

void writeFileNode(FILE* fd, int value) {
    constexpr uint32_t kMaxWriteFileLen = 16;
    char val[kMaxWriteFileLen] = {0};

    if (fd == nullptr) {
        ALOGE("invalid fd pass to %s!", __func__);
        return;
    }

    if (int32_t ret = snprintf(val, kMaxWriteFileLen, "%d", value) <= 0) {
        ALOGE("failed to write file node, ret =%d", ret);
    } else {
        fwrite(val, sizeof(val), 1, fd);
        if (ferror(fd)) {
            ALOGE("write failed: %s", strerror(errno));
            clearerr(fd);
        }
        rewind(fd);
    }
}

int32_t writeIntToFile(const char* file, uint32_t value) {
    FILE* fd = fopen(file, "w+");
    if (fd == nullptr) {
        ALOGE("%s open failed! %s", file, strerror(errno));
        return -EINVAL;
    }
    writeFileNode(fd, value);
    fclose(fd);
    return 0;
}

uint32_t getDisplayId(int32_t displayType, int32_t displayIndex) {
    return (displayType << DISPLAYID_MASK_LEN) | displayIndex;
}

int32_t load_png_image(const char* filepath, buffer_handle_t buffer) {
    png_structp png_ptr;
    png_infop info_ptr;
    int width, height, bpp, color_type;

    VendorGraphicBufferMeta gmeta(buffer);

    FILE* fp = fopen(filepath, "rb");
    if (fp == NULL) {
        ALOGE("%s open failed ", filepath);
        return -ENOENT;
    }

    png_ptr = png_create_read_struct(PNG_LIBPNG_VER_STRING, 0, 0, 0);
    if (png_ptr == NULL) {
        fclose(fp);
        return -ENOMEM;
    }

    info_ptr = png_create_info_struct(png_ptr);
    if (info_ptr == NULL) {
        fclose(fp);
        png_destroy_read_struct(&png_ptr, NULL, NULL);
        return -ENOMEM;
    }

    if (setjmp(png_jmpbuf(png_ptr))) {
        fclose(fp);
        png_destroy_read_struct(&png_ptr, &info_ptr, NULL);
        return -EIO;
    }

    png_init_io(png_ptr, fp);

    png_set_sig_bytes(png_ptr, 0);
    png_read_info(png_ptr, info_ptr);

    width = png_get_image_width(png_ptr, info_ptr);
    height = png_get_image_height(png_ptr, info_ptr);
    if (width != gmeta.width || height != gmeta.height) {
        fclose(fp);
        png_destroy_read_struct(&png_ptr, &info_ptr, NULL);
        return -EINVAL;
    }

    bpp = png_get_bit_depth(png_ptr, info_ptr) * png_get_channels(png_ptr, info_ptr);
    color_type = png_get_color_type(png_ptr, info_ptr);
    if (color_type != PNG_COLOR_TYPE_RGB_ALPHA || bpp != formatToBpp(gmeta.format)) {
        fclose(fp);
        png_destroy_read_struct(&png_ptr, &info_ptr, NULL);
        return -EINVAL;
    }

    uint32_t bufferHandleSize = gmeta.stride * gmeta.vstride * formatToBpp(gmeta.format) / 8;
    if (bufferHandleSize > gmeta.size) {
        fclose(fp);
        png_destroy_read_struct(&png_ptr, &info_ptr, NULL);
        return -EINVAL;
    }
    void* bufferHandleData =
            mmap(0, bufferHandleSize, PROT_READ | PROT_WRITE, MAP_SHARED, gmeta.fd, 0);

    if (bufferHandleData != MAP_FAILED && bufferHandleData != NULL) {
        int strideBytes = gmeta.stride * (formatToBpp(gmeta.format) / 8);
        png_bytep row_ptr = (png_bytep)bufferHandleData;
        for (int y = 0; y < height; ++y) {
            png_read_row(png_ptr, row_ptr, NULL);
            row_ptr += strideBytes;
        }
        munmap(bufferHandleData, bufferHandleSize);
    }

    fclose(fp);
    png_destroy_read_struct(&png_ptr, &info_ptr, NULL);

    return 0;
}
