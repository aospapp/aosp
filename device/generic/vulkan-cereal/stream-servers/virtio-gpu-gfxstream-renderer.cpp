// Copyright 2019 The Android Open Source Project
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
#include <vulkan/vulkan.h>

#include <deque>
#include <type_traits>
#include <unordered_map>

#include "FrameBuffer.h"
#include "GfxStreamAgents.h"
#include "VirtioGpuTimelines.h"
#include "VkCommonOperations.h"
#include "aemu/base/AlignedBuf.h"
#include "aemu/base/ManagedDescriptor.hpp"
#include "aemu/base/Metrics.h"
#include "aemu/base/Tracing.h"
#include "aemu/base/memory/SharedMemory.h"
#include "aemu/base/synchronization/Lock.h"
#include "host-common/AddressSpaceService.h"
#include "host-common/GfxstreamFatalError.h"
#include "host-common/HostmemIdMapping.h"
#include "host-common/address_space_device.h"
#include "host-common/android_pipe_common.h"
#include "host-common/android_pipe_device.h"
#include "host-common/feature_control.h"
#include "host-common/globals.h"
#include "host-common/linux_types.h"
#include "host-common/opengles-pipe.h"
#include "host-common/opengles.h"
#include "host-common/refcount-pipe.h"
#include "host-common/vm_operations.h"
#include "virgl_hw.h"
#include "virtgpu_gfxstream_protocol.h"
#include "vk_util.h"

extern "C" {
#include "drm_fourcc.h"
#include "host-common/goldfish_pipe.h"
#include "virgl_hw.h"
#include "virtio-gpu-gfxstream-renderer.h"
}  // extern "C"

#define DEBUG_VIRTIO_GOLDFISH_PIPE 0

#if DEBUG_VIRTIO_GOLDFISH_PIPE

#define VGPLOG(fmt, ...) fprintf(stderr, "%s:%d: " fmt "\n", __func__, __LINE__, ##__VA_ARGS__);

#else
#define VGPLOG(fmt, ...)
#endif

#define VGP_FATAL() \
    GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER)) << "virtio-goldfish-pipe fatal error: "

#ifdef VIRTIO_GOLDFISH_EXPORT_API

#ifdef _WIN32
#define VG_EXPORT __declspec(dllexport)
#else
#define VG_EXPORT __attribute__((visibility("default")))
#endif

#else

#define VG_EXPORT

#endif  // !VIRTIO_GOLDFISH_EXPORT_API

#define GFXSTREAM_DEBUG_LEVEL 1

#if GFXSTREAM_DEBUG_LEVEL >= 1
#define GFXS_LOG(fmt, ...)                                                     \
    do {                                                                       \
        fprintf(stdout, "%s:%d " fmt "\n", __func__, __LINE__, ##__VA_ARGS__); \
        fflush(stdout);                                                        \
    } while (0)

#else
#define GFXS_LOG(fmt, ...)
#endif

#define POST_CALLBACK_DISPLAY_TYPE_X 0
#define POST_CALLBACK_DISPLAY_TYPE_WAYLAND_SHARED_MEM 1
#define POST_CALLBACK_DISPLAY_TYPE_WINDOWS_HWND 2

// Virtio Goldfish Pipe: Overview-----------------------------------------------
//
// Virtio Goldfish Pipe is meant for running goldfish pipe services with a
// stock Linux kernel that is already capable of virtio-gpu. It runs DRM
// VIRTGPU ioctls on top of a custom implementation of virglrenderer on the
// host side that doesn't (directly) do any rendering, but instead talks to
// host-side pipe services.
//
// This is mainly used for graphics at the moment, though it's possible to run
// other pipe services over virtio-gpu as well. virtio-gpu is selected over
// other devices primarily because of the existence of an API (virglrenderer)
// that is already somewhat separate from virtio-gpu, and not needing to create
// a new virtio device to handle goldfish pipe.
//
// How it works is, existing virglrenderer API are remapped to perform pipe
// operations. First of all, pipe operations consist of the following:
//
// - open() / close(): Starts or stops an instance of a pipe service.
//
// - write(const void* buf, size_t len) / read(const void* buf, size_t len):
// Sends or receives data over the pipe. The first write() is the name of the
// pipe service. After the pipe service is determined, the host calls
// resetPipe() to replace the host-side pipe instance with an instance of the
// pipe service.
//
// - reset(void* initialPipe, void* actualPipe): the operation that replaces an
// initial pipe with an instance of a pipe service.
//
// Next, here's how the pipe operations map to virglrenderer commands:
//
// - open() -> virgl_renderer_context_create(),
//             virgl_renderer_resource_create(),
//             virgl_renderer_resource_attach_iov()
//
// The open() corresponds to a guest-side open of a rendernode, which triggers
// context creation. Each pipe corresponds 1:1 with a drm virtgpu context id.
// We also associate an R8 resource with each pipe as the backing data for
// write/read.
//
// - close() -> virgl_rendrerer_resource_unref(),
//              virgl_renderer_context_destroy()
//
// The close() corresponds to undoing the operations of open().
//
// - write() -> virgl_renderer_transfer_write_iov() OR
//              virgl_renderer_submit_cmd()
//
// Pipe write() operation corresponds to performing a TRANSFER_TO_HOST ioctl on
// the resource created alongside open(), OR an EXECBUFFER ioctl.
//
// - read() -> virgl_renderer_transfer_read_iov()
//
// Pipe read() operation corresponds to performing a TRANSFER_FROM_HOST ioctl on
// the resource created alongside open().
//
// Details on transfer mechanism: mapping 2D transfer to 1D ones----------------
//
// Resource objects are typically 2D textures, while we're wanting to transmit
// 1D buffers to the pipe services on the host.  DRM VIRTGPU uses the concept
// of a 'box' to represent transfers that do not involve an entire resource
// object.  Each box has a x, y, width and height parameter to define the
// extent of the transfer for a 2D texture.  In our use case, we only use the x
// and width parameters. We've also created the resource with R8 format
// (byte-by-byte) with width equal to the total size of the transfer buffer we
// want (around 1 MB).
//
// The resource object itself is currently backed via plain guest RAM, which
// can be physically not-contiguous from the guest POV, and therefore
// corresponds to a possibly-long list of pointers and sizes (iov) on the host
// side. The sync_iov helper function converts convert the list of pointers
// to one contiguous buffer on the host (or vice versa), at the cost of a copy.
// (TODO: see if we can use host coherent memory to do away with the copy).
//
// We can see this abstraction in use via the implementation of
// transferWriteIov and transferReadIov below, which sync the iovec to/from a
// linear buffer if necessary, and then perform a corresponding pip operation
// based on the box parameter's x and width values.

using android::AndroidPipe;
using android::base::AutoLock;
using android::base::DescriptorType;
using android::base::Lock;
using android::base::ManagedDescriptor;
using android::base::MetricsLogger;
using android::base::SharedMemory;

using android::emulation::HostmemIdMapping;
using android::emulation::ManagedDescriptorInfo;
using emugl::ABORT_REASON_OTHER;
using emugl::FatalError;

using VirtioGpuResId = uint32_t;

static constexpr int kPipeTryAgain = -2;

struct VirtioGpuCmd {
    uint32_t op;
    uint32_t cmdSize;
    unsigned char buf[0];
} __attribute__((packed));

struct PipeCtxEntry {
    std::string name;
    uint32_t capsetId;
    VirtioGpuCtxId ctxId;
    GoldfishHostPipe* hostPipe;
    int fence;
    uint32_t addressSpaceHandle;
    bool hasAddressSpaceHandle;
    std::unordered_map<VirtioGpuResId, uint32_t> addressSpaceHandles;
};

enum class ResType {
    // Used as a communication channel between the guest and the host
    // which does not need an allocation on the host GPU.
    PIPE,
    // Used as a GPU data buffer.
    BUFFER,
    // Used as a GPU texture.
    COLOR_BUFFER,
};

struct PipeResEntry {
    virgl_renderer_resource_create_args args;
    iovec* iov;
    uint32_t numIovs;
    void* linear;
    size_t linearSize;
    GoldfishHostPipe* hostPipe;
    VirtioGpuCtxId ctxId;
    void* hva;
    uint64_t hvaSize;
    uint64_t blobId;
    uint32_t blobMem;
    uint32_t blobFlags;
    uint32_t caching;
    ResType type;
    std::shared_ptr<SharedMemory> ringBlob = nullptr;
    bool externalAddr = false;
    std::shared_ptr<ManagedDescriptorInfo> descriptorInfo = nullptr;
};

static inline uint32_t align_up(uint32_t n, uint32_t a) { return ((n + a - 1) / a) * a; }

static inline uint32_t align_up_power_of_2(uint32_t n, uint32_t a) {
    return (n + (a - 1)) & ~(a - 1);
}

#define VIRGL_FORMAT_NV12 166
#define VIRGL_FORMAT_YV12 163
#define VIRGL_FORMAT_P010 314

const uint32_t kGlBgra = 0x80e1;
const uint32_t kGlRgba = 0x1908;
const uint32_t kGlRgba16f = 0x881A;
const uint32_t kGlRgb565 = 0x8d62;
const uint32_t kGlRgba1010102 = 0x8059;
const uint32_t kGlR8 = 0x8229;
const uint32_t kGlR16 = 0x822A;
const uint32_t kGlRg8 = 0x822b;
const uint32_t kGlLuminance = 0x1909;
const uint32_t kGlLuminanceAlpha = 0x190a;
const uint32_t kGlUnsignedByte = 0x1401;
const uint32_t kGlUnsignedShort565 = 0x8363;

constexpr uint32_t kFwkFormatGlCompat = 0;
constexpr uint32_t kFwkFormatYV12 = 1;
// constexpr uint32_t kFwkFormatYUV420888 = 2;
constexpr uint32_t kFwkFormatNV12 = 3;
constexpr uint32_t kFwkFormatP010 = 4;

static inline bool virgl_format_is_yuv(uint32_t format) {
    switch (format) {
        case VIRGL_FORMAT_B8G8R8X8_UNORM:
        case VIRGL_FORMAT_B8G8R8A8_UNORM:
        case VIRGL_FORMAT_R8G8B8X8_UNORM:
        case VIRGL_FORMAT_R8G8B8A8_UNORM:
        case VIRGL_FORMAT_B5G6R5_UNORM:
        case VIRGL_FORMAT_R8_UNORM:
        case VIRGL_FORMAT_R16_UNORM:
        case VIRGL_FORMAT_R16G16B16A16_FLOAT:
        case VIRGL_FORMAT_R8G8_UNORM:
        case VIRGL_FORMAT_R10G10B10A2_UNORM:
            return false;
        case VIRGL_FORMAT_NV12:
        case VIRGL_FORMAT_P010:
        case VIRGL_FORMAT_YV12:
            return true;
        default:
            VGP_FATAL() << "Unknown virgl format 0x" << std::hex << format;
            return false;
    }
}

static inline uint32_t virgl_format_to_gl(uint32_t virgl_format) {
    switch (virgl_format) {
        case VIRGL_FORMAT_B8G8R8X8_UNORM:
        case VIRGL_FORMAT_B8G8R8A8_UNORM:
            return kGlBgra;
        case VIRGL_FORMAT_R8G8B8X8_UNORM:
        case VIRGL_FORMAT_R8G8B8A8_UNORM:
            return kGlRgba;
        case VIRGL_FORMAT_B5G6R5_UNORM:
            return kGlRgb565;
        case VIRGL_FORMAT_R16_UNORM:
            return kGlR16;
        case VIRGL_FORMAT_R16G16B16A16_FLOAT:
            return kGlRgba16f;
        case VIRGL_FORMAT_R8_UNORM:
            return kGlR8;
        case VIRGL_FORMAT_R8G8_UNORM:
            return kGlRg8;
        case VIRGL_FORMAT_NV12:
        case VIRGL_FORMAT_P010:
        case VIRGL_FORMAT_YV12:
            // emulated as RGBA8888
            return kGlRgba;
        case VIRGL_FORMAT_R10G10B10A2_UNORM:
            return kGlRgba1010102;
        default:
            return kGlRgba;
    }
}

static inline uint32_t virgl_format_to_fwk_format(uint32_t virgl_format) {
    switch (virgl_format) {
        case VIRGL_FORMAT_NV12:
            return kFwkFormatNV12;
        case VIRGL_FORMAT_P010:
            return kFwkFormatP010;
        case VIRGL_FORMAT_YV12:
            return kFwkFormatYV12;
        case VIRGL_FORMAT_R8_UNORM:
        case VIRGL_FORMAT_R16_UNORM:
        case VIRGL_FORMAT_R16G16B16A16_FLOAT:
        case VIRGL_FORMAT_R8G8_UNORM:
        case VIRGL_FORMAT_B8G8R8X8_UNORM:
        case VIRGL_FORMAT_B8G8R8A8_UNORM:
        case VIRGL_FORMAT_R8G8B8X8_UNORM:
        case VIRGL_FORMAT_R8G8B8A8_UNORM:
        case VIRGL_FORMAT_B5G6R5_UNORM:
        case VIRGL_FORMAT_R10G10B10A2_UNORM:
        default:  // kFwkFormatGlCompat: No extra conversions needed
            return kFwkFormatGlCompat;
    }
}

static inline uint32_t gl_format_to_natural_type(uint32_t format) {
    switch (format) {
        case kGlBgra:
        case kGlRgba:
        case kGlLuminance:
        case kGlLuminanceAlpha:
            return kGlUnsignedByte;
        case kGlRgb565:
            return kGlUnsignedShort565;
        default:
            return kGlUnsignedByte;
    }
}

static inline size_t virgl_format_to_linear_base(uint32_t format, uint32_t totalWidth,
                                                 uint32_t totalHeight, uint32_t x, uint32_t y,
                                                 uint32_t w, uint32_t h) {
    if (virgl_format_is_yuv(format)) {
        return 0;
    } else {
        uint32_t bpp = 4;
        switch (format) {
            case VIRGL_FORMAT_R16G16B16A16_FLOAT:
                bpp = 8;
                break;
            case VIRGL_FORMAT_B8G8R8X8_UNORM:
            case VIRGL_FORMAT_B8G8R8A8_UNORM:
            case VIRGL_FORMAT_R8G8B8X8_UNORM:
            case VIRGL_FORMAT_R8G8B8A8_UNORM:
            case VIRGL_FORMAT_R10G10B10A2_UNORM:
                bpp = 4;
                break;
            case VIRGL_FORMAT_B5G6R5_UNORM:
            case VIRGL_FORMAT_R8G8_UNORM:
            case VIRGL_FORMAT_R16_UNORM:
                bpp = 2;
                break;
            case VIRGL_FORMAT_R8_UNORM:
                bpp = 1;
                break;
            default:
                VGP_FATAL() << "Unknown format: 0x" << std::hex << format;
        }

        uint32_t stride = totalWidth * bpp;
        return y * stride + x * bpp;
    }
    return 0;
}

static inline size_t virgl_format_to_total_xfer_len(uint32_t format, uint32_t totalWidth,
                                                    uint32_t totalHeight, uint32_t x, uint32_t y,
                                                    uint32_t w, uint32_t h) {
    if (virgl_format_is_yuv(format)) {
        uint32_t bpp = format == VIRGL_FORMAT_P010 ? 2 : 1;

        uint32_t yWidth = totalWidth;
        uint32_t yHeight = totalHeight;
        uint32_t yStridePixels;
        if (format == VIRGL_FORMAT_NV12) {
            yStridePixels = yWidth;
        } else if (format == VIRGL_FORMAT_P010) {
            yStridePixels = yWidth;
        } else if (format == VIRGL_FORMAT_YV12) {
            yStridePixels = align_up_power_of_2(yWidth, 32);
        } else {
            VGP_FATAL() << "Unknown yuv virgl format: 0x" << std::hex << format;
        }
        uint32_t yStrideBytes = yStridePixels * bpp;
        uint32_t ySize = yStrideBytes * yHeight;

        uint32_t uvStridePixels;
        uint32_t uvPlaneCount;
        if (format == VIRGL_FORMAT_NV12) {
            uvStridePixels = yStridePixels;
            uvPlaneCount = 1;
        } else if (format == VIRGL_FORMAT_P010) {
            uvStridePixels = yStridePixels;
            uvPlaneCount = 1;
        } else if (format == VIRGL_FORMAT_YV12) {
            uvStridePixels = yStridePixels / 2;
            uvPlaneCount = 2;
        } else {
            VGP_FATAL() << "Unknown yuv virgl format: 0x" << std::hex << format;
        }
        uint32_t uvStrideBytes = uvStridePixels * bpp;
        uint32_t uvHeight = totalHeight / 2;
        uint32_t uvSize = uvStrideBytes * uvHeight * uvPlaneCount;

        uint32_t dataSize = ySize + uvSize;
        return dataSize;
    } else {
        uint32_t bpp = 4;
        switch (format) {
            case VIRGL_FORMAT_R16G16B16A16_FLOAT:
                bpp = 8;
                break;
            case VIRGL_FORMAT_B8G8R8X8_UNORM:
            case VIRGL_FORMAT_B8G8R8A8_UNORM:
            case VIRGL_FORMAT_R8G8B8X8_UNORM:
            case VIRGL_FORMAT_R8G8B8A8_UNORM:
            case VIRGL_FORMAT_R10G10B10A2_UNORM:
                bpp = 4;
                break;
            case VIRGL_FORMAT_B5G6R5_UNORM:
            case VIRGL_FORMAT_R16_UNORM:
            case VIRGL_FORMAT_R8G8_UNORM:
                bpp = 2;
                break;
            case VIRGL_FORMAT_R8_UNORM:
                bpp = 1;
                break;
            default:
                VGP_FATAL() << "Unknown format: 0x" << std::hex << format;
        }

        uint32_t stride = totalWidth * bpp;
        return (h - 1U) * stride + w * bpp;
    }
    return 0;
}

enum IovSyncDir {
    IOV_TO_LINEAR = 0,
    LINEAR_TO_IOV = 1,
};

static int sync_iov(PipeResEntry* res, uint64_t offset, const virgl_box* box, IovSyncDir dir) {
    VGPLOG("offset: 0x%llx box: %u %u %u %u size %u x %u iovs %u linearSize %zu",
           (unsigned long long)offset, box->x, box->y, box->w, box->h, res->args.width,
           res->args.height, res->numIovs, res->linearSize);

    if (box->x > res->args.width || box->y > res->args.height) {
        VGP_FATAL() << "Box out of range of resource";
    }
    if (box->w == 0U || box->h == 0U) {
        VGP_FATAL() << "Empty transfer";
    }
    if (box->x + box->w > res->args.width) {
        VGP_FATAL() << "Box overflows resource width";
    }

    size_t linearBase = virgl_format_to_linear_base(
        res->args.format, res->args.width, res->args.height, box->x, box->y, box->w, box->h);
    size_t start = linearBase;
    // height - 1 in order to treat the (w * bpp) row specially
    // (i.e., the last row does not occupy the full stride)
    size_t length = virgl_format_to_total_xfer_len(
        res->args.format, res->args.width, res->args.height, box->x, box->y, box->w, box->h);
    size_t end = start + length;

    if (end > res->linearSize) {
        VGP_FATAL() << "start + length overflows! linearSize " << res->linearSize << " start "
                    << start << " length " << length << " (wanted " << start + length << ")";
    }

    uint32_t iovIndex = 0;
    size_t iovOffset = 0;
    size_t written = 0;
    char* linear = static_cast<char*>(res->linear);

    while (written < length) {
        if (iovIndex >= res->numIovs) {
            VGP_FATAL() << "write request overflowed numIovs";
        }

        const char* iovBase_const = static_cast<const char*>(res->iov[iovIndex].iov_base);
        char* iovBase = static_cast<char*>(res->iov[iovIndex].iov_base);
        size_t iovLen = res->iov[iovIndex].iov_len;
        size_t iovOffsetEnd = iovOffset + iovLen;

        auto lower_intersect = std::max(iovOffset, start);
        auto upper_intersect = std::min(iovOffsetEnd, end);
        if (lower_intersect < upper_intersect) {
            size_t toWrite = upper_intersect - lower_intersect;
            switch (dir) {
                case IOV_TO_LINEAR:
                    memcpy(linear + lower_intersect, iovBase_const + lower_intersect - iovOffset,
                           toWrite);
                    break;
                case LINEAR_TO_IOV:
                    memcpy(iovBase + lower_intersect - iovOffset, linear + lower_intersect,
                           toWrite);
                    break;
                default:
                    VGP_FATAL() << "Invalid sync dir " << dir;
            }
            written += toWrite;
        }
        ++iovIndex;
        iovOffset += iovLen;
    }

    return 0;
}

static uint64_t convert32to64(uint32_t lo, uint32_t hi) {
    return ((uint64_t)lo) | (((uint64_t)hi) << 32);
}

class PipeVirglRenderer {
   public:
    PipeVirglRenderer() = default;

    int init(void* cookie, int flags, const struct virgl_renderer_callbacks* callbacks) {
        VGPLOG("cookie: %p", cookie);
        mCookie = cookie;
        mVirglRendererCallbacks = *callbacks;
        mVirtioGpuOps = android_getVirtioGpuOps();
        if (!mVirtioGpuOps) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER)) << "Could not get virtio gpu ops!";
        }
        mReadPixelsFunc = android_getReadPixelsFunc();
        if (!mReadPixelsFunc) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER)) << "Could not get read pixels func!";
        }
        mAddressSpaceDeviceControlOps = get_address_space_device_control_ops();
        if (!mAddressSpaceDeviceControlOps) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
                << "Could not get address space device control ops!";
        }
        mVirtioGpuTimelines =
            VirtioGpuTimelines::create(flags & GFXSTREAM_RENDERER_FLAGS_ASYNC_FENCE_CB);
        VGPLOG("done");
        return 0;
    }

    void resetPipe(GoldfishHwPipe* hwPipe, GoldfishHostPipe* hostPipe) {
        VGPLOG("Want to reset hwpipe %p to hostpipe %p", hwPipe, hostPipe);
        VirtioGpuCtxId asCtxId = (VirtioGpuCtxId)(uintptr_t)hwPipe;
        auto it = mContexts.find(asCtxId);
        if (it == mContexts.end()) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
                << "fatal: pipe id " << asCtxId << " not found";
        }

        auto& entry = it->second;
        VGPLOG("ctxid: %u prev hostpipe: %p", asCtxId, entry.hostPipe);
        entry.hostPipe = hostPipe;
        VGPLOG("ctxid: %u next hostpipe: %p", asCtxId, entry.hostPipe);

        // Also update any resources associated with it
        auto resourcesIt = mContextResources.find(asCtxId);

        if (resourcesIt == mContextResources.end()) return;

        const auto& resIds = resourcesIt->second;

        for (auto resId : resIds) {
            auto resEntryIt = mResources.find(resId);
            if (resEntryIt == mResources.end()) {
                GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
                    << "res id " << resId << " entry not found";
            }

            auto& resEntry = resEntryIt->second;
            resEntry.hostPipe = hostPipe;
        }
    }

    int createContext(VirtioGpuCtxId ctx_id, uint32_t nlen, const char* name,
                      uint32_t context_init) {

        std::string contextName(name, nlen);

        VGPLOG("ctxid: %u len: %u name: %s", ctx_id, nlen, contextName.c_str());
        auto ops = ensureAndGetServiceOps();
        auto hostPipe = ops->guest_open_with_flags(reinterpret_cast<GoldfishHwPipe*>(ctx_id),
                                                   0x1 /* is virtio */);

        if (!hostPipe) {
            fprintf(stderr, "%s: failed to create hw pipe!\n", __func__);
            return -1;
        }
        std::unordered_map<uint32_t, uint32_t> map;

        PipeCtxEntry res = {
            std::move(contextName),  // contextName
            context_init,            // capsetId
            ctx_id,                  // ctxId
            hostPipe,                // hostPipe
            0,                       // fence
            0,                       // AS handle
            false,                   // does not have an AS handle
            map,                     // resourceId --> ASG handle map
        };

        VGPLOG("initial host pipe for ctxid %u: %p", ctx_id, hostPipe);
        mContexts[ctx_id] = res;
        return 0;
    }

    int destroyContext(VirtioGpuCtxId handle) {
        VGPLOG("ctxid: %u", handle);

        auto it = mContexts.find(handle);
        if (it == mContexts.end()) {
            fprintf(stderr, "%s: could not find context handle %u\n", __func__, handle);
            return -1;
        }

        if (it->second.hasAddressSpaceHandle) {
            for (auto const& [resourceId, handle] : it->second.addressSpaceHandles) {
                mAddressSpaceDeviceControlOps->destroy_handle(handle);
            }
        }

        auto ops = ensureAndGetServiceOps();
        auto hostPipe = it->second.hostPipe;

        if (!hostPipe) {
            fprintf(stderr, "%s: 0 is not a valid hostpipe\n", __func__);
            return -1;
        }

        ops->guest_close(hostPipe, GOLDFISH_PIPE_CLOSE_GRACEFUL);

        mContexts.erase(it);
        return 0;
    }

    void setContextAddressSpaceHandleLocked(VirtioGpuCtxId ctxId, uint32_t handle,
                                            uint32_t resourceId) {
        auto ctxIt = mContexts.find(ctxId);
        if (ctxIt == mContexts.end()) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER)) << "ctx id " << ctxId << " not found";
        }

        auto& ctxEntry = ctxIt->second;
        ctxEntry.addressSpaceHandle = handle;
        ctxEntry.hasAddressSpaceHandle = true;
        ctxEntry.addressSpaceHandles[resourceId] = handle;
    }

    uint32_t getAddressSpaceHandleLocked(VirtioGpuCtxId ctxId, uint32_t resourceId) {
        auto ctxIt = mContexts.find(ctxId);
        if (ctxIt == mContexts.end()) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER)) << "ctx id " << ctxId << " not found ";
        }

        auto& ctxEntry = ctxIt->second;

        if (!ctxEntry.addressSpaceHandles.count(resourceId)) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
                << "ASG context with resource id " << resourceId << " not found ";
        }

        return ctxEntry.addressSpaceHandles[resourceId];
    }

#define DECODE(variable, type, input) \
    type variable = {};               \
    memcpy(&variable, input, sizeof(type));

    void addressSpaceProcessCmd(VirtioGpuCtxId ctxId, uint32_t* dwords, int dwordCount) {
        DECODE(header, gfxstream::gfxstreamHeader, dwords)

        switch (header.opCode) {
            case GFXSTREAM_CONTEXT_CREATE: {
                DECODE(contextCreate, gfxstream::gfxstreamContextCreate, dwords)

                auto resEntryIt = mResources.find(contextCreate.resourceId);
                if (resEntryIt == mResources.end()) {
                    GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
                        << " ASG coherent resource " << contextCreate.resourceId << " not found";
                }

                auto ctxIt = mContexts.find(ctxId);
                if (ctxIt == mContexts.end()) {
                    GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
                        << "ctx id " << ctxId << " not found ";
                }

                auto& ctxEntry = ctxIt->second;
                auto& resEntry = resEntryIt->second;

                std::string name = ctxEntry.name + "-" + std::to_string(contextCreate.resourceId);
                uint32_t handle = mAddressSpaceDeviceControlOps->gen_handle();

                struct AddressSpaceCreateInfo createInfo = {
                    .handle = handle,
                    .type = android::emulation::VirtioGpuGraphics,
                    .createRenderThread = true,
                    .externalAddr = resEntry.hva,
                    .externalAddrSize = resEntry.hvaSize,
                    .virtioGpuContextId = ctxId,
                    .virtioGpuCapsetId = ctxEntry.capsetId,
                    .contextName = name.c_str(),
                    .contextNameSize = static_cast<uint32_t>(ctxEntry.name.size()),
                };

                mAddressSpaceDeviceControlOps->create_instance(createInfo);
                setContextAddressSpaceHandleLocked(ctxId, handle, contextCreate.resourceId);
                break;
            }
            case GFXSTREAM_CONTEXT_PING: {
                DECODE(contextPing, gfxstream::gfxstreamContextPing, dwords)

                struct android::emulation::AddressSpaceDevicePingInfo ping = {0};
                ping.metadata = ASG_NOTIFY_AVAILABLE;

                mAddressSpaceDeviceControlOps->ping_at_hva(
                    getAddressSpaceHandleLocked(ctxId, contextPing.resourceId), &ping);
                break;
            }
            default:
                break;
        }
    }

    int submitCmd(VirtioGpuCtxId ctxId, void* buffer, int dwordCount) {
        // TODO(kaiyili): embed the ring_idx into the command buffer to make it possible to dispatch
        // commands on different ring.
        VirtioGpuRing ring = VirtioGpuRingGlobal{};
        VGPLOG("ctx: %" PRIu32 ", ring: %s buffer: %p dwords: %d", ctxId, to_string(ring).c_str(),
               buffer, dwordCount);

        if (!buffer) {
            fprintf(stderr, "%s: error: buffer null\n", __func__);
            return -1;
        }

        if (dwordCount < 1) {
            fprintf(stderr, "%s: error: not enough dwords (got %d)\n", __func__, dwordCount);
            return -1;
        }

        DECODE(header, gfxstream::gfxstreamHeader, buffer);
        switch (header.opCode) {
            case GFXSTREAM_CONTEXT_CREATE:
            case GFXSTREAM_CONTEXT_PING:
            case GFXSTREAM_CONTEXT_PING_WITH_RESPONSE:
                addressSpaceProcessCmd(ctxId, (uint32_t*)buffer, dwordCount);
                break;
            case GFXSTREAM_CREATE_EXPORT_SYNC: {
                DECODE(exportSync, gfxstream::gfxstreamCreateExportSync, buffer)

                uint64_t sync_handle =
                    convert32to64(exportSync.syncHandleLo, exportSync.syncHandleHi);

                VGPLOG("wait for gpu ring %s", to_string(ring));
                auto taskId = mVirtioGpuTimelines->enqueueTask(ring);
                mVirtioGpuOps->async_wait_for_gpu_with_cb(sync_handle, [this, taskId] {
                    mVirtioGpuTimelines->notifyTaskCompletion(taskId);
                });
                break;
            }
            case GFXSTREAM_CREATE_EXPORT_SYNC_VK:
            case GFXSTREAM_CREATE_IMPORT_SYNC_VK: {
                DECODE(exportSyncVK, gfxstream::gfxstreamCreateExportSyncVK, buffer)

                uint64_t device_handle =
                    convert32to64(exportSyncVK.deviceHandleLo, exportSyncVK.deviceHandleHi);

                uint64_t fence_handle =
                    convert32to64(exportSyncVK.fenceHandleLo, exportSyncVK.fenceHandleHi);

                VGPLOG("wait for gpu ring %s", to_string(ring));
                auto taskId = mVirtioGpuTimelines->enqueueTask(ring);
                mVirtioGpuOps->async_wait_for_gpu_vulkan_with_cb(
                    device_handle, fence_handle,
                    [this, taskId] { mVirtioGpuTimelines->notifyTaskCompletion(taskId); });
                break;
            }
            case GFXSTREAM_CREATE_QSRI_EXPORT_VK: {
                // The guest QSRI export assumes fence context support and always uses
                // VIRTGPU_EXECBUF_RING_IDX. With this, the task created here must use
                // the same ring as the fence created for the virtio gpu command or the
                // fence may be signaled without properly waiting for the task to complete.
                ring = VirtioGpuRingContextSpecific{
                    .mCtxId = ctxId,
                    .mRingIdx = 0,
                };

                DECODE(exportQSRI, gfxstream::gfxstreamCreateQSRIExportVK, buffer)

                uint64_t image_handle =
                    convert32to64(exportQSRI.imageHandleLo, exportQSRI.imageHandleHi);

                VGPLOG("wait for gpu vk qsri ring %u image 0x%llx", to_string(ring).c_str(),
                       (unsigned long long)image_handle);
                auto taskId = mVirtioGpuTimelines->enqueueTask(ring);
                mVirtioGpuOps->async_wait_for_gpu_vulkan_qsri_with_cb(image_handle, [this, taskId] {
                    mVirtioGpuTimelines->notifyTaskCompletion(taskId);
                });
                break;
            }
            case GFXSTREAM_PLACEHOLDER_COMMAND_VK: {
                // Do nothing, this is a placeholder command
                break;
            }
            default:
                return -1;
        }

        return 0;
    }

    int createFence(uint64_t fence_id, const VirtioGpuRing& ring) {
        VGPLOG("fenceid: %llu ring: %s", (unsigned long long)fence_id, to_string(ring).c_str());

        struct {
            FenceCompletionCallback operator()(const VirtioGpuRingGlobal&) {
                return [renderer = mRenderer, fenceId = mFenceId] {
                    renderer->mVirglRendererCallbacks.write_fence(renderer->mCookie, fenceId);
                };
            }
            FenceCompletionCallback operator()(const VirtioGpuRingContextSpecific& ring) {
#ifdef VIRGL_RENDERER_UNSTABLE_APIS
                return [renderer = mRenderer, fenceId = mFenceId, ring] {
                    renderer->mVirglRendererCallbacks.write_context_fence(
                        renderer->mCookie, fenceId, ring.mCtxId, ring.mRingIdx);
                };
#else
                VGPLOG("enable unstable apis for the context specific fence feature");
                return {};
#endif
            }

            PipeVirglRenderer* mRenderer;
            VirtioGpuTimelines::FenceId mFenceId;
        } visitor{
            .mRenderer = this,
            .mFenceId = fence_id,
        };
        FenceCompletionCallback callback = std::visit(visitor, ring);
        if (!callback) {
            // A context specific ring passed in, but the project is compiled without
            // VIRGL_RENDERER_UNSTABLE_APIS defined.
            return -EINVAL;
        }
        mVirtioGpuTimelines->enqueueFence(ring, fence_id, callback);

        return 0;
    }

    void poll() { mVirtioGpuTimelines->poll(); }

    enum pipe_texture_target {
        PIPE_BUFFER,
        PIPE_TEXTURE_1D,
        PIPE_TEXTURE_2D,
        PIPE_TEXTURE_3D,
        PIPE_TEXTURE_CUBE,
        PIPE_TEXTURE_RECT,
        PIPE_TEXTURE_1D_ARRAY,
        PIPE_TEXTURE_2D_ARRAY,
        PIPE_TEXTURE_CUBE_ARRAY,
        PIPE_MAX_TEXTURE_TYPES,
    };

    /**
     *  * Resource binding flags -- state tracker must specify in advance all
     *   * the ways a resource might be used.
     *    */
#define PIPE_BIND_DEPTH_STENCIL (1 << 0)   /* create_surface */
#define PIPE_BIND_RENDER_TARGET (1 << 1)   /* create_surface */
#define PIPE_BIND_BLENDABLE (1 << 2)       /* create_surface */
#define PIPE_BIND_SAMPLER_VIEW (1 << 3)    /* create_sampler_view */
#define PIPE_BIND_VERTEX_BUFFER (1 << 4)   /* set_vertex_buffers */
#define PIPE_BIND_INDEX_BUFFER (1 << 5)    /* draw_elements */
#define PIPE_BIND_CONSTANT_BUFFER (1 << 6) /* set_constant_buffer */
#define PIPE_BIND_DISPLAY_TARGET (1 << 7)  /* flush_front_buffer */
    /* gap */
#define PIPE_BIND_STREAM_OUTPUT (1 << 10)       /* set_stream_output_buffers */
#define PIPE_BIND_CURSOR (1 << 11)              /* mouse cursor */
#define PIPE_BIND_CUSTOM (1 << 12)              /* state-tracker/winsys usages */
#define PIPE_BIND_GLOBAL (1 << 13)              /* set_global_binding */
#define PIPE_BIND_SHADER_BUFFER (1 << 14)       /* set_shader_buffers */
#define PIPE_BIND_SHADER_IMAGE (1 << 15)        /* set_shader_images */
#define PIPE_BIND_COMPUTE_RESOURCE (1 << 16)    /* set_compute_resources */
#define PIPE_BIND_COMMAND_ARGS_BUFFER (1 << 17) /* pipe_draw_info.indirect */
#define PIPE_BIND_QUERY_BUFFER (1 << 18)        /* get_query_result_resource */

    ResType getResourceType(const struct virgl_renderer_resource_create_args& args) const {
        if (args.target == PIPE_BUFFER) {
            return ResType::PIPE;
        }

        if (args.format != VIRGL_FORMAT_R8_UNORM) {
            return ResType::COLOR_BUFFER;
        }
        if (args.bind & VIRGL_BIND_SAMPLER_VIEW) {
            return ResType::COLOR_BUFFER;
        }
        if (args.bind & VIRGL_BIND_RENDER_TARGET) {
            return ResType::COLOR_BUFFER;
        }
        if (args.bind & VIRGL_BIND_SCANOUT) {
            return ResType::COLOR_BUFFER;
        }
        if (args.bind & VIRGL_BIND_CURSOR) {
            return ResType::COLOR_BUFFER;
        }
        if (!(args.bind & VIRGL_BIND_LINEAR)) {
            return ResType::COLOR_BUFFER;
        }

        return ResType::BUFFER;
    }

    void handleCreateResourceBuffer(struct virgl_renderer_resource_create_args* args) {
        mVirtioGpuOps->create_buffer_with_handle(args->width * args->height, args->handle);
    }

    void handleCreateResourceColorBuffer(struct virgl_renderer_resource_create_args* args) {
        // corresponds to allocation of gralloc buffer in minigbm
        VGPLOG("w h %u %u resid %u -> rcCreateColorBufferWithHandle", args->width, args->height,
               args->handle);

        const uint32_t glformat = virgl_format_to_gl(args->format);
        const uint32_t fwkformat = virgl_format_to_fwk_format(args->format);
        mVirtioGpuOps->create_color_buffer_with_handle(args->width, args->height, glformat,
                                                       fwkformat, args->handle);
        mVirtioGpuOps->set_guest_managed_color_buffer_lifetime(true /* guest manages lifetime */);
        mVirtioGpuOps->open_color_buffer(args->handle);
    }

    int createResource(struct virgl_renderer_resource_create_args* args, struct iovec* iov,
                       uint32_t num_iovs) {
        VGPLOG("handle: %u. num iovs: %u", args->handle, num_iovs);

        const auto resType = getResourceType(*args);
        switch (resType) {
            case ResType::PIPE:
                break;
            case ResType::BUFFER:
                handleCreateResourceBuffer(args);
                break;
            case ResType::COLOR_BUFFER:
                handleCreateResourceColorBuffer(args);
                break;
        }

        PipeResEntry e;
        e.args = *args;
        e.linear = 0;
        e.hostPipe = 0;
        e.hva = nullptr;
        e.hvaSize = 0;
        e.blobId = 0;
        e.blobMem = 0;
        e.type = resType;
        allocResource(e, iov, num_iovs);

        mResources[args->handle] = e;
        return 0;
    }

    void unrefResource(uint32_t toUnrefId) {
        VGPLOG("handle: %u", toUnrefId);

        auto it = mResources.find(toUnrefId);
        if (it == mResources.end()) return;

        auto contextsIt = mResourceContexts.find(toUnrefId);
        if (contextsIt != mResourceContexts.end()) {
            mResourceContexts.erase(contextsIt->first);
        }

        for (auto& ctxIdResources : mContextResources) {
            detachResourceLocked(ctxIdResources.first, toUnrefId);
        }

        auto& entry = it->second;
        switch (entry.type) {
            case ResType::PIPE:
                break;
            case ResType::BUFFER:
                mVirtioGpuOps->close_buffer(toUnrefId);
                break;
            case ResType::COLOR_BUFFER:
                mVirtioGpuOps->close_color_buffer(toUnrefId);
                break;
        }

        if (entry.linear) {
            free(entry.linear);
            entry.linear = nullptr;
        }

        if (entry.iov) {
            free(entry.iov);
            entry.iov = nullptr;
            entry.numIovs = 0;
        }

        if (entry.externalAddr && !entry.ringBlob) {
            android::aligned_buf_free(entry.hva);
        }

        entry.hva = nullptr;
        entry.hvaSize = 0;
        entry.blobId = 0;

        mResources.erase(it);
    }

    int attachIov(int resId, iovec* iov, int num_iovs) {

        VGPLOG("resid: %d numiovs: %d", resId, num_iovs);

        auto it = mResources.find(resId);
        if (it == mResources.end()) return ENOENT;

        auto& entry = it->second;
        VGPLOG("res linear: %p", entry.linear);
        if (!entry.linear) allocResource(entry, iov, num_iovs);

        VGPLOG("done");
        return 0;
    }

    void detachIov(int resId, iovec** iov, int* num_iovs) {

        auto it = mResources.find(resId);
        if (it == mResources.end()) return;

        auto& entry = it->second;

        if (num_iovs) {
            *num_iovs = entry.numIovs;
            VGPLOG("resid: %d numIovs: %d", resId, *num_iovs);
        } else {
            VGPLOG("resid: %d numIovs: 0", resId);
        }

        entry.numIovs = 0;

        if (entry.iov) free(entry.iov);
        entry.iov = nullptr;

        if (iov) {
            *iov = entry.iov;
        }

        allocResource(entry, entry.iov, entry.numIovs);
        VGPLOG("done");
    }

    int handleTransferReadPipe(PipeResEntry* res, uint64_t offset, virgl_box* box) {
        if (res->type != ResType::PIPE) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
                << "Resource " << res->args.handle << " is not a PIPE resource.";
            return -1;
        }

        // Do the pipe service op here, if there is an associated hostpipe.
        auto hostPipe = res->hostPipe;
        if (!hostPipe) return -1;

        auto ops = ensureAndGetServiceOps();

        size_t readBytes = 0;
        size_t wantedBytes = readBytes + (size_t)box->w;

        while (readBytes < wantedBytes) {
            GoldfishPipeBuffer buf = {
                ((char*)res->linear) + box->x + readBytes,
                wantedBytes - readBytes,
            };
            auto status = ops->guest_recv(hostPipe, &buf, 1);

            if (status > 0) {
                readBytes += status;
            } else if (status != kPipeTryAgain) {
                return EIO;
            }
        }

        return 0;
    }

    int handleTransferWritePipe(PipeResEntry* res, uint64_t offset, virgl_box* box) {
        if (res->type != ResType::PIPE) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
                << "Resource " << res->args.handle << " is not a PIPE resource.";
            return -1;
        }

        // Do the pipe service op here, if there is an associated hostpipe.
        auto hostPipe = res->hostPipe;
        if (!hostPipe) {
            VGPLOG("No hostPipe");
            return -1;
        }

        VGPLOG("resid: %d offset: 0x%llx hostpipe: %p", res->args.handle,
               (unsigned long long)offset, hostPipe);

        auto ops = ensureAndGetServiceOps();

        size_t writtenBytes = 0;
        size_t wantedBytes = (size_t)box->w;

        while (writtenBytes < wantedBytes) {
            GoldfishPipeBuffer buf = {
                ((char*)res->linear) + box->x + writtenBytes,
                wantedBytes - writtenBytes,
            };

            // guest_send can now reallocate the pipe.
            void* hostPipeBefore = hostPipe;
            auto status = ops->guest_send(&hostPipe, &buf, 1);
            if (hostPipe != hostPipeBefore) {
                resetPipe((GoldfishHwPipe*)(uintptr_t)(res->ctxId), hostPipe);
                auto it = mResources.find(res->args.handle);
                res = &it->second;
            }

            if (status > 0) {
                writtenBytes += status;
            } else if (status != kPipeTryAgain) {
                return EIO;
            }
        }

        return 0;
    }

    int handleTransferReadBuffer(PipeResEntry* res, uint64_t offset, virgl_box* box) {
        if (res->type != ResType::BUFFER) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
                << "Resource " << res->args.handle << " is not a BUFFER resource.";
            return -1;
        }

        mVirtioGpuOps->read_buffer(res->args.handle, 0, res->args.width * res->args.height,
                                   res->linear);
        return 0;
    }

    int handleTransferWriteBuffer(PipeResEntry* res, uint64_t offset, virgl_box* box) {
        if (res->type != ResType::BUFFER) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
                << res->args.handle << " is not a BUFFER resource.";
            return -1;
        }

        mVirtioGpuOps->update_buffer(res->args.handle, 0, res->args.width * res->args.height,
                                     res->linear);
        return 0;
    }

    void handleTransferReadColorBuffer(PipeResEntry* res, uint64_t offset, virgl_box* box) {
        if (res->type != ResType::COLOR_BUFFER) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
                << "Resource " << res->args.handle << " is not a COLOR_BUFFER resource.";
            return;
        }

        auto glformat = virgl_format_to_gl(res->args.format);
        auto gltype = gl_format_to_natural_type(glformat);

        // We always xfer the whole thing again from GL
        // since it's fiddly to calc / copy-out subregions
        if (virgl_format_is_yuv(res->args.format)) {
            mVirtioGpuOps->read_color_buffer_yuv(res->args.handle, 0, 0, res->args.width,
                                                 res->args.height, res->linear, res->linearSize);
        } else {
            mVirtioGpuOps->read_color_buffer(res->args.handle, 0, 0, res->args.width,
                                             res->args.height, glformat, gltype, res->linear);
        }
    }

    void handleTransferWriteColorBuffer(PipeResEntry* res, uint64_t offset, virgl_box* box) {
        if (res->type != ResType::COLOR_BUFFER) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
                << "Resource " << res->args.handle << " is not a COLOR_BUFFER resource.";
            return;
        }

        auto glformat = virgl_format_to_gl(res->args.format);
        auto gltype = gl_format_to_natural_type(glformat);

        // We always xfer the whole thing again to GL
        // since it's fiddly to calc / copy-out subregions
        mVirtioGpuOps->update_color_buffer(res->args.handle, 0, 0, res->args.width,
                                           res->args.height, glformat, gltype, res->linear);
    }

    int transferReadIov(int resId, uint64_t offset, virgl_box* box, struct iovec* iov,
                        int iovec_cnt) {

        VGPLOG("resid: %d offset: 0x%llx. box: %u %u %u %u", resId, (unsigned long long)offset,
               box->x, box->y, box->w, box->h);

        auto it = mResources.find(resId);
        if (it == mResources.end()) return EINVAL;

        int ret = 0;

        auto& entry = it->second;
        switch (entry.type) {
            case ResType::PIPE:
                ret = handleTransferReadPipe(&entry, offset, box);
                break;
            case ResType::BUFFER:
                ret = handleTransferReadBuffer(&entry, offset, box);
                break;
            case ResType::COLOR_BUFFER:
                handleTransferReadColorBuffer(&entry, offset, box);
                break;
        }

        if (ret != 0) {
            return ret;
        }

        VGPLOG("Linear first word: %d", *(int*)(entry.linear));

        if (iovec_cnt) {
            PipeResEntry e = {
                entry.args, iov, (uint32_t)iovec_cnt, entry.linear, entry.linearSize,
            };
            ret = sync_iov(&e, offset, box, LINEAR_TO_IOV);
        } else {
            ret = sync_iov(&entry, offset, box, LINEAR_TO_IOV);
        }

        VGPLOG("done");
        return ret;
    }

    int transferWriteIov(int resId, uint64_t offset, virgl_box* box, struct iovec* iov,
                         int iovec_cnt) {
        VGPLOG("resid: %d offset: 0x%llx", resId, (unsigned long long)offset);
        auto it = mResources.find(resId);
        if (it == mResources.end()) return EINVAL;

        auto& entry = it->second;

        int ret = 0;
        if (iovec_cnt) {
            PipeResEntry e = {
                entry.args, iov, (uint32_t)iovec_cnt, entry.linear, entry.linearSize,
            };
            ret = sync_iov(&e, offset, box, IOV_TO_LINEAR);
        } else {
            ret = sync_iov(&entry, offset, box, IOV_TO_LINEAR);
        }

        if (ret != 0) {
            return ret;
        }

        switch (entry.type) {
            case ResType::PIPE:
                ret = handleTransferWritePipe(&entry, offset, box);
                break;
            case ResType::BUFFER:
                ret = handleTransferWriteBuffer(&entry, offset, box);
                break;
            case ResType::COLOR_BUFFER:
                handleTransferWriteColorBuffer(&entry, offset, box);
                break;
        }

        VGPLOG("done");
        return ret;
    }

    void getCapset(uint32_t set, uint32_t *max_size) {
        // Only one capset right not
        *max_size = sizeof(struct gfxstream::gfxstreamCapset);
    }

    void fillCaps(uint32_t set, void* caps) {
        struct gfxstream::gfxstreamCapset *capset =
            reinterpret_cast<struct gfxstream::gfxstreamCapset*>(caps);
        if (capset) {
            memset(capset, 0, sizeof(*capset));

            capset->protocolVersion = 1;
            capset->ringSize = 12288;
            capset->bufferSize = 1048576;

            auto vk_emu = gfxstream::vk::getGlobalVkEmulation();
            if (vk_emu && vk_emu->live && vk_emu->representativeColorBufferMemoryTypeIndex) {
                capset->colorBufferMemoryIndex = *vk_emu->representativeColorBufferMemoryTypeIndex;
            }
        }
    }

    void attachResource(uint32_t ctxId, uint32_t resId) {
        VGPLOG("ctxid: %u resid: %u", ctxId, resId);

        auto resourcesIt = mContextResources.find(ctxId);

        if (resourcesIt == mContextResources.end()) {
            std::vector<VirtioGpuResId> ids;
            ids.push_back(resId);
            mContextResources[ctxId] = ids;
        } else {
            auto& ids = resourcesIt->second;
            auto idIt = std::find(ids.begin(), ids.end(), resId);
            if (idIt == ids.end()) ids.push_back(resId);
        }

        auto contextsIt = mResourceContexts.find(resId);

        if (contextsIt == mResourceContexts.end()) {
            std::vector<VirtioGpuCtxId> ids;
            ids.push_back(ctxId);
            mResourceContexts[resId] = ids;
        } else {
            auto& ids = contextsIt->second;
            auto idIt = std::find(ids.begin(), ids.end(), ctxId);
            if (idIt == ids.end()) ids.push_back(ctxId);
        }

        // Associate the host pipe of the resource entry with the host pipe of
        // the context entry.  That is, the last context to call attachResource
        // wins if there is any conflict.
        auto ctxEntryIt = mContexts.find(ctxId);
        auto resEntryIt = mResources.find(resId);

        if (ctxEntryIt == mContexts.end() || resEntryIt == mResources.end()) return;

        VGPLOG("hostPipe: %p", ctxEntryIt->second.hostPipe);
        resEntryIt->second.hostPipe = ctxEntryIt->second.hostPipe;
        resEntryIt->second.ctxId = ctxId;
    }

    void detachResource(uint32_t ctxId, uint32_t toUnrefId) {
        VGPLOG("ctxid: %u resid: %u", ctxId, toUnrefId);
        detachResourceLocked(ctxId, toUnrefId);
    }

    int getResourceInfo(uint32_t resId, struct virgl_renderer_resource_info* info) {
        VGPLOG("resid: %u", resId);
        if (!info) return EINVAL;

        auto it = mResources.find(resId);
        if (it == mResources.end()) return ENOENT;

        auto& entry = it->second;

        uint32_t bpp = 4U;
        switch (entry.args.format) {
            case VIRGL_FORMAT_B8G8R8A8_UNORM:
                info->drm_fourcc = DRM_FORMAT_ARGB8888;
                break;
            case VIRGL_FORMAT_B5G6R5_UNORM:
                info->drm_fourcc = DRM_FORMAT_RGB565;
                bpp = 2U;
                break;
            case VIRGL_FORMAT_R8G8B8A8_UNORM:
                info->drm_fourcc = DRM_FORMAT_ABGR8888;
                break;
            case VIRGL_FORMAT_R8G8B8X8_UNORM:
                info->drm_fourcc = DRM_FORMAT_XBGR8888;
                break;
            case VIRGL_FORMAT_R8_UNORM:
                info->drm_fourcc = DRM_FORMAT_R8;
                bpp = 1U;
                break;
            default:
                return EINVAL;
        }

        info->stride = align_up(entry.args.width * bpp, 16U);
        info->virgl_format = entry.args.format;
        info->handle = entry.args.handle;
        info->height = entry.args.height;
        info->width = entry.args.width;
        info->depth = entry.args.depth;
        info->flags = entry.args.flags;
        info->tex_id = 0;
        return 0;
    }

    void flushResourceAndReadback(uint32_t res_handle, uint32_t x, uint32_t y, uint32_t width,
                                  uint32_t height, void* pixels, uint32_t max_bytes) {
        (void)x;
        (void)y;
        (void)width;
        (void)height;
        auto taskId = mVirtioGpuTimelines->enqueueTask(VirtioGpuRingGlobal{});
        mVirtioGpuOps->async_post_color_buffer(
            res_handle, [this, taskId](std::shared_future<void> waitForGpu) {
                waitForGpu.wait();
                mVirtioGpuTimelines->notifyTaskCompletion(taskId);
            });
        // TODO: displayId > 0 ?
        uint32_t displayId = 0;
        if (pixels) {
            mReadPixelsFunc(pixels, max_bytes, displayId);
        }
    }

    int createRingBlob(PipeResEntry& entry, uint32_t res_handle,
                       const struct stream_renderer_create_blob* create_blob,
                       const struct stream_renderer_handle* handle) {
        if (feature_is_enabled(kFeature_ExternalBlob)) {
            std::string name = "shared-memory-" + std::to_string(res_handle);
            auto ringBlob = std::make_shared<SharedMemory>(name, create_blob->size);
            int ret = ringBlob->create(0600);
            if (ret) {
                VGPLOG("Failed to create shared memory blob");
                return ret;
            }

            entry.ringBlob = ringBlob;
            entry.hva = ringBlob->get();
        } else {
            void* addr =
                android::aligned_buf_alloc(ADDRESS_SPACE_GRAPHICS_PAGE_SIZE, create_blob->size);
            if (addr == nullptr) {
                VGPLOG("Failed to allocate ring blob");
                return -ENOMEM;
            }

            entry.hva = addr;
        }

        entry.hvaSize = create_blob->size;
        entry.externalAddr = true;
        entry.caching = STREAM_RENDERER_MAP_CACHE_CACHED;

        return 0;
    }

    int createBlob(uint32_t ctx_id, uint32_t res_handle,
                   const struct stream_renderer_create_blob* create_blob,
                   const struct stream_renderer_handle* handle) {
        PipeResEntry e;
        struct virgl_renderer_resource_create_args args = {0};
        e.args = args;
        e.hostPipe = 0;

        if (create_blob->blob_id == 0) {
            int ret = createRingBlob(e, res_handle, create_blob, handle);
            if (ret) {
                return ret;
            }
        } else if (feature_is_enabled(kFeature_ExternalBlob)) {
            if (create_blob->blob_mem == STREAM_BLOB_MEM_GUEST &&
                (create_blob->blob_flags & STREAM_BLOB_FLAG_CREATE_GUEST_HANDLE)) {
#if defined(__linux__) || defined(__QNX__)
                ManagedDescriptor managedHandle(handle->os_handle);
                HostmemIdMapping::get()->addDescriptorInfo(create_blob->blob_id,
                                                           std::move(managedHandle),
                                                           handle->handle_type, 0, std::nullopt);

                e.caching = STREAM_RENDERER_MAP_CACHE_CACHED;
#else
                return -EINVAL;
#endif
            } else {
                auto descriptorInfoOpt =
                    HostmemIdMapping::get()->removeDescriptorInfo(create_blob->blob_id);
                if (descriptorInfoOpt) {
                    e.descriptorInfo =
                        std::make_shared<ManagedDescriptorInfo>(std::move(*descriptorInfoOpt));
                } else {
                    return -EINVAL;
                }

                e.caching = e.descriptorInfo->caching;
            }
        } else {
            auto entry = HostmemIdMapping::get()->get(create_blob->blob_id);
            e.hva = entry.hva;
            e.hvaSize = entry.size;
            e.args.width = entry.size;
            e.caching = entry.caching;
        }

        e.blobId = create_blob->blob_id;
        e.blobMem = create_blob->blob_mem;
        e.blobFlags = create_blob->blob_flags;
        e.iov = nullptr;
        e.numIovs = 0;
        e.linear = 0;
        e.linearSize = 0;

        mResources[res_handle] = e;
        return 0;
    }

    int resourceMap(uint32_t res_handle, void** hvaOut, uint64_t* sizeOut) {

        if (feature_is_enabled(kFeature_ExternalBlob)) return -EINVAL;

        auto it = mResources.find(res_handle);
        if (it == mResources.end()) {
            if (hvaOut) *hvaOut = nullptr;
            if (sizeOut) *sizeOut = 0;
            return -1;
        }

        const auto& entry = it->second;

        if (hvaOut) *hvaOut = entry.hva;
        if (sizeOut) *sizeOut = entry.hvaSize;
        return 0;
    }

    int resourceUnmap(uint32_t res_handle) {
        auto it = mResources.find(res_handle);
        if (it == mResources.end()) {
            return -1;
        }

        // TODO(lfy): Good place to run any registered cleanup callbacks.
        // No-op for now.
        return 0;
    }

    int platformImportResource(int res_handle, int res_info, void* resource) {
        auto it = mResources.find(res_handle);
        if (it == mResources.end()) return -1;
        bool success = mVirtioGpuOps->platform_import_resource(res_handle, res_info, resource);
        return success ? 0 : -1;
    }

    int platformResourceInfo(int res_handle, int* width, int* height, int* internal_format) {
        auto it = mResources.find(res_handle);
        if (it == mResources.end()) return -1;
        bool success =
            mVirtioGpuOps->platform_resource_info(res_handle, width, height, internal_format);
        return success ? 0 : -1;
    }

    void* platformCreateSharedEglContext() {
        return mVirtioGpuOps->platform_create_shared_egl_context();
    }

    int platformDestroySharedEglContext(void* context) {
        bool success = mVirtioGpuOps->platform_destroy_shared_egl_context(context);
        return success ? 0 : -1;
    }

    int resourceMapInfo(uint32_t res_handle, uint32_t* map_info) {
        auto it = mResources.find(res_handle);
        if (it == mResources.end()) return -1;

        const auto& entry = it->second;
        *map_info = entry.caching;
        return 0;
    }

    int exportBlob(uint32_t res_handle, struct stream_renderer_handle* handle) {

        auto it = mResources.find(res_handle);
        if (it == mResources.end()) {
            return -EINVAL;
        }

        auto& entry = it->second;
        if (entry.ringBlob) {
            // Handle ownership transferred to VMM, gfxstream keeps the mapping.
#ifdef _WIN32
            handle->os_handle =
                static_cast<int64_t>(reinterpret_cast<intptr_t>(entry.ringBlob->releaseHandle()));
#else
            handle->os_handle = static_cast<int64_t>(entry.ringBlob->releaseHandle());
#endif
            handle->handle_type = STREAM_MEM_HANDLE_TYPE_SHM;
            return 0;
        }

        if (entry.descriptorInfo) {
            bool shareable = entry.blobFlags &
                             (STREAM_BLOB_FLAG_USE_SHAREABLE | STREAM_BLOB_FLAG_USE_CROSS_DEVICE);

            DescriptorType rawDescriptor;
            if (shareable) {
                // TODO: Add ManagedDescriptor::{clone, dup} method and use it;
                // This should have no affect since gfxstream allocates mappable-only buffers
                // currently
                return -EINVAL;
            } else {
                auto rawDescriptorOpt = entry.descriptorInfo->descriptor.release();
                if (rawDescriptorOpt)
                    rawDescriptor = *rawDescriptorOpt;
                else
                    return -EINVAL;
            }

            handle->handle_type = entry.descriptorInfo->handleType;

#ifdef _WIN32
            handle->os_handle = static_cast<int64_t>(reinterpret_cast<intptr_t>(rawDescriptor));
#else
            handle->os_handle = static_cast<int64_t>(rawDescriptor);
#endif

            return 0;
        }

        return -EINVAL;
    }

    int vulkanInfo(uint32_t res_handle, struct stream_renderer_vulkan_info* vulkan_info) {
        auto it = mResources.find(res_handle);
        if (it == mResources.end()) return -EINVAL;

        const auto& entry = it->second;
        if (entry.descriptorInfo && entry.descriptorInfo->vulkanInfoOpt) {
            vulkan_info->memory_index = (*entry.descriptorInfo->vulkanInfoOpt).memoryIndex;
            memcpy(vulkan_info->device_id.device_uuid,
                   (*entry.descriptorInfo->vulkanInfoOpt).deviceUUID,
                   sizeof(vulkan_info->device_id.device_uuid));
            memcpy(vulkan_info->device_id.driver_uuid,
                   (*entry.descriptorInfo->vulkanInfoOpt).driverUUID,
                   sizeof(vulkan_info->device_id.driver_uuid));
            return 0;
        }

        return -EINVAL;
    }

   private:
    void allocResource(PipeResEntry& entry, iovec* iov, int num_iovs) {
        VGPLOG("entry linear: %p", entry.linear);
        if (entry.linear) free(entry.linear);

        size_t linearSize = 0;
        for (uint32_t i = 0; i < num_iovs; ++i) {
            VGPLOG("iov base: %p", iov[i].iov_base);
            linearSize += iov[i].iov_len;
            VGPLOG("has iov of %zu. linearSize current: %zu", iov[i].iov_len, linearSize);
        }
        VGPLOG("final linearSize: %zu", linearSize);

        void* linear = nullptr;

        if (linearSize) linear = malloc(linearSize);

        entry.iov = (iovec*)malloc(sizeof(*iov) * num_iovs);
        entry.numIovs = num_iovs;
        memcpy(entry.iov, iov, num_iovs * sizeof(*iov));
        entry.linear = linear;
        entry.linearSize = linearSize;

        virgl_box initbox;
        initbox.x = 0;
        initbox.y = 0;
        initbox.w = (uint32_t)linearSize;
        initbox.h = 1;
    }

    void detachResourceLocked(uint32_t ctxId, uint32_t toUnrefId) {
        VGPLOG("ctxid: %u resid: %u", ctxId, toUnrefId);

        auto it = mContextResources.find(ctxId);
        if (it == mContextResources.end()) return;

        std::vector<VirtioGpuResId> withoutRes;
        for (auto resId : it->second) {
            if (resId != toUnrefId) {
                withoutRes.push_back(resId);
            }
        }
        mContextResources[ctxId] = withoutRes;

        auto resIt = mResources.find(toUnrefId);
        if (resIt == mResources.end()) return;

        resIt->second.hostPipe = 0;
        resIt->second.ctxId = 0;

        auto ctxIt = mContexts.find(ctxId);
        if (ctxIt != mContexts.end()) {
            auto& ctxEntry = ctxIt->second;
            if (ctxEntry.addressSpaceHandles.count(toUnrefId)) {
                uint32_t handle = ctxEntry.addressSpaceHandles[toUnrefId];
                mAddressSpaceDeviceControlOps->destroy_handle(handle);
                ctxEntry.addressSpaceHandles.erase(toUnrefId);
            }
        }
    }

    inline const GoldfishPipeServiceOps* ensureAndGetServiceOps() {
        if (mServiceOps) return mServiceOps;
        mServiceOps = goldfish_pipe_get_service_ops();
        return mServiceOps;
    }

    void* mCookie = nullptr;
    virgl_renderer_callbacks mVirglRendererCallbacks;
    AndroidVirtioGpuOps* mVirtioGpuOps = nullptr;
    ReadPixelsFunc mReadPixelsFunc = nullptr;
    struct address_space_device_control_ops* mAddressSpaceDeviceControlOps = nullptr;

    const GoldfishPipeServiceOps* mServiceOps = nullptr;

    std::unordered_map<VirtioGpuCtxId, PipeCtxEntry> mContexts;
    std::unordered_map<VirtioGpuResId, PipeResEntry> mResources;
    std::unordered_map<VirtioGpuCtxId, std::vector<VirtioGpuResId>> mContextResources;
    std::unordered_map<VirtioGpuResId, std::vector<VirtioGpuCtxId>> mResourceContexts;

    // When we wait for gpu or wait for gpu vulkan, the next (and subsequent)
    // fences created for that context should not be signaled immediately.
    // Rather, they should get in line.
    std::unique_ptr<VirtioGpuTimelines> mVirtioGpuTimelines = nullptr;
};

static PipeVirglRenderer* sRenderer() {
    static PipeVirglRenderer* p = new PipeVirglRenderer;
    return p;
}

extern "C" {

VG_EXPORT int pipe_virgl_renderer_init(void* cookie, int flags,
                                       struct virgl_renderer_callbacks* cb) {
    sRenderer()->init(cookie, flags, cb);
    return 0;
}

VG_EXPORT void pipe_virgl_renderer_poll(void) { sRenderer()->poll(); }

VG_EXPORT void* pipe_virgl_renderer_get_cursor_data(uint32_t resource_id, uint32_t* width,
                                                    uint32_t* height) {
    return 0;
}

VG_EXPORT int pipe_virgl_renderer_resource_create(struct virgl_renderer_resource_create_args* args,
                                                  struct iovec* iov, uint32_t num_iovs) {
    return sRenderer()->createResource(args, iov, num_iovs);
}

VG_EXPORT void pipe_virgl_renderer_resource_unref(uint32_t res_handle) {
    sRenderer()->unrefResource(res_handle);
}

VG_EXPORT int pipe_virgl_renderer_context_create(uint32_t handle, uint32_t nlen, const char* name) {
    return sRenderer()->createContext(handle, nlen, name, 0);
}

VG_EXPORT void pipe_virgl_renderer_context_destroy(uint32_t handle) {
    sRenderer()->destroyContext(handle);
}

VG_EXPORT int pipe_virgl_renderer_submit_cmd(void* buffer, int ctx_id, int dwordCount) {
    return sRenderer()->submitCmd(ctx_id, buffer, dwordCount);
}

VG_EXPORT int pipe_virgl_renderer_transfer_read_iov(uint32_t handle, uint32_t ctx_id,
                                                    uint32_t level, uint32_t stride,
                                                    uint32_t layer_stride, struct virgl_box* box,
                                                    uint64_t offset, struct iovec* iov,
                                                    int iovec_cnt) {
    return sRenderer()->transferReadIov(handle, offset, box, iov, iovec_cnt);
}

VG_EXPORT int pipe_virgl_renderer_transfer_write_iov(uint32_t handle, uint32_t ctx_id, int level,
                                                     uint32_t stride, uint32_t layer_stride,
                                                     struct virgl_box* box, uint64_t offset,
                                                     struct iovec* iovec, unsigned int iovec_cnt) {
    return sRenderer()->transferWriteIov(handle, offset, box, iovec, iovec_cnt);
}

VG_EXPORT void pipe_virgl_renderer_get_cap_set(uint32_t set, uint32_t* max_ver, uint32_t* max_size) {
    // `max_ver` not useful
    return sRenderer()->getCapset(set, max_size);
}

VG_EXPORT void pipe_virgl_renderer_fill_caps(uint32_t set, uint32_t version, void* caps) {
    // `version` not useful
    return sRenderer()->fillCaps(set, caps);
}

VG_EXPORT int pipe_virgl_renderer_resource_attach_iov(int res_handle, struct iovec* iov,
                                                      int num_iovs) {
    return sRenderer()->attachIov(res_handle, iov, num_iovs);
}

VG_EXPORT void pipe_virgl_renderer_resource_detach_iov(int res_handle, struct iovec** iov,
                                                       int* num_iovs) {
    return sRenderer()->detachIov(res_handle, iov, num_iovs);
}

VG_EXPORT int pipe_virgl_renderer_create_fence(int client_fence_id, uint32_t ctx_id) {
    sRenderer()->createFence(client_fence_id, VirtioGpuRingGlobal{});
    return 0;
}

VG_EXPORT void pipe_virgl_renderer_force_ctx_0(void) { VGPLOG("call"); }

VG_EXPORT void pipe_virgl_renderer_ctx_attach_resource(int ctx_id, int res_handle) {
    sRenderer()->attachResource(ctx_id, res_handle);
}

VG_EXPORT void pipe_virgl_renderer_ctx_detach_resource(int ctx_id, int res_handle) {
    sRenderer()->detachResource(ctx_id, res_handle);
}

VG_EXPORT int pipe_virgl_renderer_resource_get_info(int res_handle,
                                                    struct virgl_renderer_resource_info* info) {
    return sRenderer()->getResourceInfo(res_handle, info);
}

VG_EXPORT int pipe_virgl_renderer_resource_map(uint32_t res_handle, void** hvaOut,
                                               uint64_t* sizeOut) {
    return sRenderer()->resourceMap(res_handle, hvaOut, sizeOut);
}

VG_EXPORT int pipe_virgl_renderer_resource_unmap(uint32_t res_handle) {
    return sRenderer()->resourceUnmap(res_handle);
}

VG_EXPORT void stream_renderer_flush_resource_and_readback(uint32_t res_handle, uint32_t x,
                                                           uint32_t y, uint32_t width,
                                                           uint32_t height, void* pixels,
                                                           uint32_t max_bytes) {
    sRenderer()->flushResourceAndReadback(res_handle, x, y, width, height, pixels, max_bytes);
}

VG_EXPORT int stream_renderer_create_blob(uint32_t ctx_id, uint32_t res_handle,
                                          const struct stream_renderer_create_blob* create_blob,
                                          const struct iovec* iovecs, uint32_t num_iovs,
                                          const struct stream_renderer_handle* handle) {
    sRenderer()->createBlob(ctx_id, res_handle, create_blob, handle);
    return 0;
}

VG_EXPORT int stream_renderer_export_blob(uint32_t res_handle,
                                          struct stream_renderer_handle* handle) {
    return sRenderer()->exportBlob(res_handle, handle);
}

VG_EXPORT int stream_renderer_resource_map(uint32_t res_handle, void** hvaOut, uint64_t* sizeOut) {
    return sRenderer()->resourceMap(res_handle, hvaOut, sizeOut);
}

VG_EXPORT int stream_renderer_resource_unmap(uint32_t res_handle) {
    return sRenderer()->resourceUnmap(res_handle);
}

VG_EXPORT int stream_renderer_context_create(uint32_t ctx_id, uint32_t nlen, const char* name,
                                             uint32_t context_init) {
    return sRenderer()->createContext(ctx_id, nlen, name, context_init);
}

VG_EXPORT int stream_renderer_context_create_fence(uint64_t fence_id, uint32_t ctx_id,
                                                   uint8_t ring_idx) {
    sRenderer()->createFence(fence_id, VirtioGpuRingContextSpecific{
                                           .mCtxId = ctx_id,
                                           .mRingIdx = ring_idx,
                                       });
    return 0;
}

VG_EXPORT int stream_renderer_platform_import_resource(int res_handle, int res_info,
                                                       void* resource) {
    return sRenderer()->platformImportResource(res_handle, res_info, resource);
}

VG_EXPORT int stream_renderer_platform_resource_info(int res_handle, int* width, int* height,
                                                     int* internal_format) {
    return sRenderer()->platformResourceInfo(res_handle, width, height, internal_format);
}

VG_EXPORT void* stream_renderer_platform_create_shared_egl_context() {
    return sRenderer()->platformCreateSharedEglContext();
}

VG_EXPORT int stream_renderer_platform_destroy_shared_egl_context(void* context) {
    return sRenderer()->platformDestroySharedEglContext(context);
}

VG_EXPORT int stream_renderer_resource_map_info(uint32_t res_handle, uint32_t* map_info) {
    return sRenderer()->resourceMapInfo(res_handle, map_info);
}

VG_EXPORT int stream_renderer_vulkan_info(uint32_t res_handle,
                                          struct stream_renderer_vulkan_info* vulkan_info) {
    return sRenderer()->vulkanInfo(res_handle, vulkan_info);
}

static const GoldfishPipeServiceOps goldfish_pipe_service_ops = {
    // guest_open()
    [](GoldfishHwPipe* hwPipe) -> GoldfishHostPipe* {
        return static_cast<GoldfishHostPipe*>(android_pipe_guest_open(hwPipe));
    },
    // guest_open_with_flags()
    [](GoldfishHwPipe* hwPipe, uint32_t flags) -> GoldfishHostPipe* {
        return static_cast<GoldfishHostPipe*>(android_pipe_guest_open_with_flags(hwPipe, flags));
    },
    // guest_close()
    [](GoldfishHostPipe* hostPipe, GoldfishPipeCloseReason reason) {
        static_assert((int)GOLDFISH_PIPE_CLOSE_GRACEFUL == (int)PIPE_CLOSE_GRACEFUL,
                      "Invalid PIPE_CLOSE_GRACEFUL value");
        static_assert((int)GOLDFISH_PIPE_CLOSE_REBOOT == (int)PIPE_CLOSE_REBOOT,
                      "Invalid PIPE_CLOSE_REBOOT value");
        static_assert((int)GOLDFISH_PIPE_CLOSE_LOAD_SNAPSHOT == (int)PIPE_CLOSE_LOAD_SNAPSHOT,
                      "Invalid PIPE_CLOSE_LOAD_SNAPSHOT value");
        static_assert((int)GOLDFISH_PIPE_CLOSE_ERROR == (int)PIPE_CLOSE_ERROR,
                      "Invalid PIPE_CLOSE_ERROR value");

        android_pipe_guest_close(hostPipe, static_cast<PipeCloseReason>(reason));
    },
    // guest_pre_load()
    [](QEMUFile* file) { (void)file; },
    // guest_post_load()
    [](QEMUFile* file) { (void)file; },
    // guest_pre_save()
    [](QEMUFile* file) { (void)file; },
    // guest_post_save()
    [](QEMUFile* file) { (void)file; },
    // guest_load()
    [](QEMUFile* file, GoldfishHwPipe* hwPipe, char* force_close) -> GoldfishHostPipe* {
        (void)file;
        (void)hwPipe;
        (void)force_close;
        return nullptr;
    },
    // guest_save()
    [](GoldfishHostPipe* hostPipe, QEMUFile* file) {
        (void)hostPipe;
        (void)file;
    },
    // guest_poll()
    [](GoldfishHostPipe* hostPipe) {
        static_assert((int)GOLDFISH_PIPE_POLL_IN == (int)PIPE_POLL_IN, "invalid POLL_IN values");
        static_assert((int)GOLDFISH_PIPE_POLL_OUT == (int)PIPE_POLL_OUT, "invalid POLL_OUT values");
        static_assert((int)GOLDFISH_PIPE_POLL_HUP == (int)PIPE_POLL_HUP, "invalid POLL_HUP values");

        return static_cast<GoldfishPipePollFlags>(android_pipe_guest_poll(hostPipe));
    },
    // guest_recv()
    [](GoldfishHostPipe* hostPipe, GoldfishPipeBuffer* buffers, int numBuffers) -> int {
        // NOTE: Assumes that AndroidPipeBuffer and GoldfishPipeBuffer
        //       have exactly the same layout.
        static_assert(sizeof(AndroidPipeBuffer) == sizeof(GoldfishPipeBuffer),
                      "Invalid PipeBuffer sizes");
    // We can't use a static_assert with offsetof() because in msvc, it uses
    // reinterpret_cast.
    // TODO: Add runtime assertion instead?
    // https://developercommunity.visualstudio.com/content/problem/22196/static-assert-cannot-compile-constexprs-method-tha.html
#ifndef _MSC_VER
        static_assert(offsetof(AndroidPipeBuffer, data) == offsetof(GoldfishPipeBuffer, data),
                      "Invalid PipeBuffer::data offsets");
        static_assert(offsetof(AndroidPipeBuffer, size) == offsetof(GoldfishPipeBuffer, size),
                      "Invalid PipeBuffer::size offsets");
#endif
        return android_pipe_guest_recv(hostPipe, reinterpret_cast<AndroidPipeBuffer*>(buffers),
                                       numBuffers);
    },
    // guest_send()
    [](GoldfishHostPipe** hostPipe, const GoldfishPipeBuffer* buffers, int numBuffers) -> int {
        return android_pipe_guest_send(reinterpret_cast<void**>(hostPipe),
                                       reinterpret_cast<const AndroidPipeBuffer*>(buffers),
                                       numBuffers);
    },
    // guest_wake_on()
    [](GoldfishHostPipe* hostPipe, GoldfishPipeWakeFlags wakeFlags) {
        android_pipe_guest_wake_on(hostPipe, static_cast<int>(wakeFlags));
    },
    // dma_add_buffer()
    [](void* pipe, uint64_t paddr, uint64_t sz) {
        // not considered for virtio
    },
    // dma_remove_buffer()
    [](uint64_t paddr) {
        // not considered for virtio
    },
    // dma_invalidate_host_mappings()
    []() {
        // not considered for virtio
    },
    // dma_reset_host_mappings()
    []() {
        // not considered for virtio
    },
    // dma_save_mappings()
    [](QEMUFile* file) { (void)file; },
    // dma_load_mappings()
    [](QEMUFile* file) { (void)file; },
};

VG_EXPORT int stream_renderer_init(struct stream_renderer_param* stream_renderer_params,
                                   uint64_t num_params) {
    // Required parameters.
    std::unordered_set<uint64_t> required_params{STREAM_RENDERER_PARAM_USER_DATA,
                                                 STREAM_RENDERER_PARAM_RENDERER_FLAGS,
                                                 STREAM_RENDERER_PARAM_WRITE_FENCE_CALLBACK};

    // String names of the parameters.
    std::unordered_map<uint64_t, std::string> param_strings{
        {STREAM_RENDERER_PARAM_USER_DATA, "USER_DATA"},
        {STREAM_RENDERER_PARAM_RENDERER_FLAGS, "RENDERER_FLAGS"},
        {STREAM_RENDERER_PARAM_WRITE_FENCE_CALLBACK, "WRITE_FENCE_CALLBACK"},
        {STREAM_RENDERER_PARAM_WRITE_CONTEXT_FENCE_CALLBACK, "WRITE_CONTEXT_FENCE_CALLBACK"},
        {STREAM_RENDERER_PARAM_WIN0_WIDTH, "WIN0_WIDTH"},
        {STREAM_RENDERER_PARAM_WIN0_HEIGHT, "WIN0_HEIGHT"},
        {STREAM_RENDERER_PARAM_METRICS_CALLBACK_ADD_INSTANT_EVENT,
         "METRICS_CALLBACK_ADD_INSTANT_EVENT"},
        {STREAM_RENDERER_PARAM_METRICS_CALLBACK_ADD_INSTANT_EVENT_WITH_DESCRIPTOR,
         "METRICS_CALLBACK_ADD_INSTANT_EVENT_WITH_DESCRIPTOR"},
        {STREAM_RENDERER_PARAM_METRICS_CALLBACK_ADD_INSTANT_EVENT_WITH_METRIC,
         "METRICS_CALLBACK_ADD_INSTANT_EVENT_WITH_METRIC"},
        {STREAM_RENDERER_PARAM_METRICS_CALLBACK_ADD_VULKAN_OUT_OF_MEMORY_EVENT,
         "METRICS_CALLBACK_ADD_VULKAN_OUT_OF_MEMORY_EVENT"},
        {STREAM_RENDERER_PARAM_METRICS_CALLBACK_SET_ANNOTATION, "METRICS_CALLBACK_SET_ANNOTATION"},
        {STREAM_RENDERER_PARAM_METRICS_CALLBACK_ABORT, "METRICS_CALLBACK_ABORT"}};

    // Print full values for these parameters:
    // Values here must not be pointers (e.g. callback functions), to avoid potentially identifying
    // someone via ASLR. Pointers in ASLR are randomized on boot, which means pointers may be
    // different between users but similar across a single user's sessions.
    // As a convenience, any value <= 4096 is also printed, to catch small or null pointer errors.
    std::unordered_set<uint64_t> printed_param_values{STREAM_RENDERER_PARAM_RENDERER_FLAGS,
                                                      STREAM_RENDERER_PARAM_WIN0_WIDTH,
                                                      STREAM_RENDERER_PARAM_WIN0_HEIGHT};

    // We may have unknown parameters, so this function is lenient.
    auto get_param_string = [&](uint64_t key) -> std::string {
        auto param_string = param_strings.find(key);
        if (param_string != param_strings.end()) {
            return param_string->second;
        } else {
            return "Unknown param with key=" + std::to_string(key);
        }
    };

    // Initialization data.
    uint32_t display_width = 0;
    uint32_t display_height = 0;
    void* renderer_cookie = nullptr;
    int renderer_flags = 0;
    virgl_renderer_callbacks virglrenderer_callbacks = {};

    // Iterate all parameters that we support.
    GFXS_LOG("Reading stream renderer parameters:");
    for (uint64_t i = 0; i < num_params; ++i) {
        stream_renderer_param& param = stream_renderer_params[i];

        // Print out parameter we are processing. See comment above `printed_param_values` before
        // adding new prints.
        if (printed_param_values.find(param.key) != printed_param_values.end() ||
            param.value <= 4096) {
            GFXS_LOG("%s - %llu", get_param_string(param.key).c_str(),
                     static_cast<unsigned long long>(param.value));
        } else {
            // If not full value, print that it was passed.
            GFXS_LOG("%s", get_param_string(param.key).c_str());
        }

        // Removing every param we process will leave required_params empty if all provided.
        required_params.erase(param.key);

        switch (param.key) {
            case STREAM_RENDERER_PARAM_USER_DATA: {
                renderer_cookie = reinterpret_cast<void*>(static_cast<uintptr_t>(param.value));
                break;
            }
            case STREAM_RENDERER_PARAM_RENDERER_FLAGS: {
                renderer_flags = static_cast<int>(param.value);
                break;
            }
            case STREAM_RENDERER_PARAM_WRITE_FENCE_CALLBACK: {
                virglrenderer_callbacks.write_fence =
                    reinterpret_cast<stream_renderer_param_write_fence_callback>(
                        static_cast<uintptr_t>(param.value));
                break;
            }
            case STREAM_RENDERER_PARAM_WRITE_CONTEXT_FENCE_CALLBACK: {
#ifdef VIRGL_RENDERER_UNSTABLE_APIS
                virglrenderer_callbacks.write_context_fence =
                    reinterpret_cast<stream_renderer_param_write_context_fence_callback>(
                        static_cast<uintptr_t>(param.value));
#else
                ERR("Cannot use WRITE_CONTEXT_FENCE_CALLBACK with unstable APIs OFF.");
#endif
                break;
            }
            case STREAM_RENDERER_PARAM_WIN0_WIDTH: {
                display_width = static_cast<uint32_t>(param.value);
                break;
            }
            case STREAM_RENDERER_PARAM_WIN0_HEIGHT: {
                display_height = static_cast<uint32_t>(param.value);
                break;
            }
            case STREAM_RENDERER_PARAM_METRICS_CALLBACK_ADD_INSTANT_EVENT: {
                MetricsLogger::add_instant_event_callback =
                    reinterpret_cast<stream_renderer_param_metrics_callback_add_instant_event>(
                        static_cast<uintptr_t>(param.value));
                break;
            }
            case STREAM_RENDERER_PARAM_METRICS_CALLBACK_ADD_INSTANT_EVENT_WITH_DESCRIPTOR: {
                MetricsLogger::add_instant_event_with_descriptor_callback = reinterpret_cast<
                    stream_renderer_param_metrics_callback_add_instant_event_with_descriptor>(
                    static_cast<uintptr_t>(param.value));
                break;
            }
            case STREAM_RENDERER_PARAM_METRICS_CALLBACK_ADD_INSTANT_EVENT_WITH_METRIC: {
                MetricsLogger::add_instant_event_with_metric_callback = reinterpret_cast<
                    stream_renderer_param_metrics_callback_add_instant_event_with_metric>(
                    static_cast<uintptr_t>(param.value));
                break;
            }
            case STREAM_RENDERER_PARAM_METRICS_CALLBACK_ADD_VULKAN_OUT_OF_MEMORY_EVENT: {
                MetricsLogger::add_vulkan_out_of_memory_event = reinterpret_cast<
                    stream_renderer_param_metrics_callback_add_vulkan_out_of_memory_event>(
                    static_cast<uintptr_t>(param.value));
                break;
            }
            case STREAM_RENDERER_PARAM_METRICS_CALLBACK_SET_ANNOTATION: {
                MetricsLogger::set_crash_annotation_callback =
                    reinterpret_cast<stream_renderer_param_metrics_callback_set_annotation>(
                        static_cast<uintptr_t>(param.value));
                break;
            }
            case STREAM_RENDERER_PARAM_METRICS_CALLBACK_ABORT: {
                emugl::setDieFunction(
                    reinterpret_cast<stream_renderer_param_metrics_callback_abort>(
                        static_cast<uintptr_t>(param.value)));
                break;
            }
            default: {
                // We skip any parameters we don't recognize.
                ERR("Skipping unknown parameter key: %llu. May need to upgrade gfxstream.",
                    static_cast<unsigned long long>(param.key));
                break;
            }
        }
    }
    GFXS_LOG("Finished reading parameters");

    // Some required params not found.
    if (required_params.size() > 0) {
        ERR("Missing required parameters:");
        for (uint64_t param : required_params) {
            ERR("%s", get_param_string(param).c_str());
        }
        ERR("Failing initialization intentionally");
        return -1;
    }

    // Set non product-specific callbacks
    gfxstream::vk::vk_util::setVkCheckCallbacks(
        std::make_unique<gfxstream::vk::vk_util::VkCheckCallbacks>(
            gfxstream::vk::vk_util::VkCheckCallbacks{
                .onVkErrorOutOfMemory =
                    [](VkResult result, const char* function, int line) {
                        auto fb = gfxstream::FrameBuffer::getFB();
                        if (!fb) {
                            ERR("FrameBuffer not yet initialized. Dropping out of memory event");
                            return;
                        }
                        fb->logVulkanOutOfMemory(result, function, line);
                    },
                .onVkErrorOutOfMemoryOnAllocation =
                    [](VkResult result, const char* function, int line,
                       std::optional<uint64_t> allocationSize) {
                        auto fb = gfxstream::FrameBuffer::getFB();
                        if (!fb) {
                            ERR("FrameBuffer not yet initialized. Dropping out of memory event");
                            return;
                        }
                        fb->logVulkanOutOfMemory(result, function, line, allocationSize);
                    }}));

    GFXS_LOG("start. display dimensions: width %u height %u, renderer flags: 0x%x", display_width,
             display_height, renderer_flags);

    // Flags processing

    // TODO: hook up "gfxstream egl" to the renderer flags
    // GFXSTREAM_RENDERER_FLAGS_USE_EGL_BIT in crosvm
    // as it's specified from launch_cvd.
    // At the moment, use ANDROID_GFXSTREAM_EGL=1
    // For test on GCE
    if (android::base::getEnvironmentVariable("ANDROID_GFXSTREAM_EGL") == "1") {
        android::base::setEnvironmentVariable("ANDROID_EGL_ON_EGL", "1");
        android::base::setEnvironmentVariable("ANDROID_EMUGL_LOG_PRINT", "1");
        android::base::setEnvironmentVariable("ANDROID_EMUGL_VERBOSE", "1");
    }
    // end for test on GCE

    android::base::setEnvironmentVariable("ANDROID_EMU_HEADLESS", "1");
    bool enableVk = !(renderer_flags & GFXSTREAM_RENDERER_FLAGS_NO_VK_BIT);

    bool egl2eglByEnv = android::base::getEnvironmentVariable("ANDROID_EGL_ON_EGL") == "1";
    bool egl2eglByFlag = renderer_flags & GFXSTREAM_RENDERER_FLAGS_USE_EGL_BIT;
    bool enable_egl2egl = egl2eglByFlag || egl2eglByEnv;
    if (enable_egl2egl) {
        android::base::setEnvironmentVariable("ANDROID_GFXSTREAM_EGL", "1");
        android::base::setEnvironmentVariable("ANDROID_EGL_ON_EGL", "1");
    }

    bool surfaceless = renderer_flags & GFXSTREAM_RENDERER_FLAGS_USE_SURFACELESS_BIT;
    bool enableGlEs31Flag = renderer_flags & GFXSTREAM_RENDERER_FLAGS_ENABLE_GLES31_BIT;
    bool useExternalBlob = renderer_flags & GFXSTREAM_RENDERER_FLAGS_USE_EXTERNAL_BLOB;
    bool useSystemBlob = renderer_flags & GFXSTREAM_RENDERER_FLAGS_USE_SYSTEM_BLOB;
    bool guestUsesAngle = renderer_flags & GFXSTREAM_RENDERER_FLAGS_GUEST_USES_ANGLE;
    bool useVulkanNativeSwapchain =
        renderer_flags & GFXSTREAM_RENDERER_FLAGS_VULKAN_NATIVE_SWAPCHAIN_BIT;

    GFXS_LOG("Vulkan enabled? %d", enableVk);
    GFXS_LOG("egl2egl enabled? %d", enable_egl2egl);
    GFXS_LOG("surfaceless? %d", surfaceless);
    GFXS_LOG("OpenGL ES 3.1 enabled? %d", enableGlEs31Flag);
    GFXS_LOG("use external blob? %d", useExternalBlob);
    GFXS_LOG("use system blob? %d", useSystemBlob);
    GFXS_LOG("guest using ANGLE? %d", guestUsesAngle);
    GFXS_LOG("use Vulkan native swapchain on the host? %d", useVulkanNativeSwapchain);

    if (useSystemBlob) {
        if (!useExternalBlob) {
            GFXS_LOG("USE_EXTERNAL_BLOB must be on with USE_SYSTEM_BLOB");
            return -2;
        }

#ifndef _WIN32
        GFXS_LOG("Warning: USE_SYSTEM_BLOB has only been tested on Windows");
#endif
    }

    // Need to manually set the GLES backend paths in gfxstream environment
    // because the library search paths are not automatically set to include
    // the directory in whioch the GLES backend resides.
#if defined(__linux__)
#define GFXSTREAM_LIB_SUFFIX ".so"
#elif defined(__APPLE__)
#define GFXSTREAM_LIB_SUFFIX ".dylib"
#else  // Windows
#define GFXSTREAM_LIB_SUFFIX ".dll"
#endif

    feature_set_enabled_override(kFeature_GLPipeChecksum, false);
    feature_set_enabled_override(kFeature_GLESDynamicVersion, true);
    feature_set_enabled_override(kFeature_PlayStoreImage, !enableGlEs31Flag);
    feature_set_enabled_override(kFeature_GLDMA, false);
    feature_set_enabled_override(kFeature_GLAsyncSwap, false);
    feature_set_enabled_override(kFeature_RefCountPipe, false);
    feature_set_enabled_override(kFeature_NoDelayCloseColorBuffer, true);
    feature_set_enabled_override(kFeature_NativeTextureDecompression, false);
    feature_set_enabled_override(kFeature_GLDirectMem, false);
    feature_set_enabled_override(kFeature_Vulkan, enableVk);
    feature_set_enabled_override(kFeature_VulkanSnapshots, false);
    feature_set_enabled_override(kFeature_VulkanNullOptionalStrings, true);
    feature_set_enabled_override(kFeature_VulkanShaderFloat16Int8, true);
    feature_set_enabled_override(kFeature_HostComposition, true);
    feature_set_enabled_override(kFeature_VulkanIgnoredHandles, true);
    feature_set_enabled_override(kFeature_VirtioGpuNext, true);
    feature_set_enabled_override(kFeature_VirtioGpuNativeSync, true);
    feature_set_enabled_override(kFeature_GuestUsesAngle, guestUsesAngle);
    feature_set_enabled_override(kFeature_VulkanQueueSubmitWithCommands, true);
    feature_set_enabled_override(kFeature_VulkanNativeSwapchain, useVulkanNativeSwapchain);
    feature_set_enabled_override(kFeature_VulkanBatchedDescriptorSetUpdate, true);
    // TODO: Strictly speaking, renderer_flags check is insufficient because
    // fence contexts require us to be running a new-enough guest kernel.
    feature_set_enabled_override(kFeature_VirtioGpuFenceContexts,
                                 (renderer_flags & GFXSTREAM_RENDERER_FLAGS_ASYNC_FENCE_CB));
    feature_set_enabled_override(kFeature_ExternalBlob, useExternalBlob);
    feature_set_enabled_override(kFeature_SystemBlob, useSystemBlob);

    android::featurecontrol::productFeatureOverride();

    if (useVulkanNativeSwapchain && !enableVk) {
        GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
            << "can't enable vulkan native swapchain, Vulkan is disabled";
    }

    gfxstream::vk::vkDispatch(false /* don't use test ICD */);

    auto androidHw = aemu_get_android_hw();

    androidHw->hw_gltransport_asg_writeBufferSize = 1048576;
    androidHw->hw_gltransport_asg_writeStepSize = 262144;
    androidHw->hw_gltransport_asg_dataRingSize = 524288;
    androidHw->hw_gltransport_drawFlushInterval = 10000;

    EmuglConfig config;

    // Make all the console agents available.
    android::emulation::injectGraphicsAgents(android::emulation::GfxStreamGraphicsAgentFactory());

    emuglConfig_init(&config, true /* gpu enabled */, "auto",
                     enable_egl2egl ? "swiftshader_indirect" : "host", 64, /* bitness */
                     surfaceless,                                          /* no window */
                     false,                                                /* blocklisted */
                     false,                                                /* has guest renderer */
                     WINSYS_GLESBACKEND_PREFERENCE_AUTO, true /* force host gpu vulkan */);

    emuglConfig_setupEnv(&config);

    android_prepareOpenglesEmulation();

    {
        static gfxstream::RenderLibPtr renderLibPtr = gfxstream::initLibrary();
        android_setOpenglesEmulation(renderLibPtr.get(), nullptr, nullptr);
    }

    int maj;
    int min;
    android_startOpenglesRenderer(display_width, display_height, 1, 28, getGraphicsAgents()->vm,
                                  getGraphicsAgents()->emu, getGraphicsAgents()->multi_display,
                                  &maj, &min);

    char* vendor = nullptr;
    char* renderer = nullptr;
    char* version = nullptr;

    android_getOpenglesHardwareStrings(&vendor, &renderer, &version);

    GFXS_LOG("GL strings; [%s] [%s] [%s].\n", vendor, renderer, version);

    auto openglesRenderer = android_getOpenglesRenderer();

    if (!openglesRenderer) {
        GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER)) << "No renderer started, fatal";
    }

    address_space_set_vm_operations(getGraphicsAgents()->vm);
    android_init_opengles_pipe();
    android_opengles_pipe_set_recv_mode(2 /* virtio-gpu */);
    android_init_refcount_pipe();

    pipe_virgl_renderer_init(renderer_cookie, renderer_flags, &virglrenderer_callbacks);

    gfxstream::FrameBuffer::waitUntilInitialized();

    GFXS_LOG("Started renderer");

    return 0;
}

VG_EXPORT void gfxstream_backend_setup_window(void* native_window_handle, int32_t window_x,
                                              int32_t window_y, int32_t window_width,
                                              int32_t window_height, int32_t fb_width,
                                              int32_t fb_height) {
    android_showOpenglesWindow(native_window_handle, window_x, window_y, window_width,
                               window_height, fb_width, fb_height, 1.0f, 0, false, false);
}

VG_EXPORT void gfxstream_backend_teardown() {
    android_finishOpenglesRenderer();
    android_hideOpenglesWindow();
    android_stopOpenglesRenderer(true);
}

VG_EXPORT void gfxstream_backend_set_screen_mask(int width, int height,
                                                 const unsigned char* rgbaData) {
    android_setOpenglesScreenMask(width, height, rgbaData);
}

const GoldfishPipeServiceOps* goldfish_pipe_get_service_ops() { return &goldfish_pipe_service_ops; }

#define VIRGLRENDERER_API_PIPE_STRUCT_DEF(api) pipe_##api,

static struct virgl_renderer_virtio_interface s_virtio_interface = {
    LIST_VIRGLRENDERER_API(VIRGLRENDERER_API_PIPE_STRUCT_DEF)};

struct virgl_renderer_virtio_interface* get_goldfish_pipe_virgl_renderer_virtio_interface(void) {
    return &s_virtio_interface;
}


static_assert(sizeof(struct stream_renderer_device_id) == 32,
              "stream_renderer_device_id must be 32 bytes");
static_assert(offsetof(struct stream_renderer_device_id, device_uuid) == 0,
              "stream_renderer_device_id.device_uuid must be at offset 0");
static_assert(offsetof(struct stream_renderer_device_id, driver_uuid) == 16,
              "stream_renderer_device_id.driver_uuid must be at offset 16");

static_assert(sizeof(struct stream_renderer_vulkan_info) == 36,
              "stream_renderer_vulkan_info must be 36 bytes");
static_assert(offsetof(struct stream_renderer_vulkan_info, memory_index) == 0,
              "stream_renderer_vulkan_info.memory_index must be at offset 0");
static_assert(offsetof(struct stream_renderer_vulkan_info, device_id) == 4,
              "stream_renderer_vulkan_info.device_id must be at offset 4");

static_assert(sizeof(struct stream_renderer_param_host_visible_memory_mask_entry) == 36,
              "stream_renderer_param_host_visible_memory_mask_entry must be 36 bytes");
static_assert(offsetof(struct stream_renderer_param_host_visible_memory_mask_entry, device_id) == 0,
              "stream_renderer_param_host_visible_memory_mask_entry.device_id must be at offset 0");
static_assert(
    offsetof(struct stream_renderer_param_host_visible_memory_mask_entry, memory_type_mask) == 32,
    "stream_renderer_param_host_visible_memory_mask_entry.memory_type_mask must be at offset 32");

static_assert(sizeof(struct stream_renderer_param_host_visible_memory_mask) == 16,
              "stream_renderer_param_host_visible_memory_mask must be 16 bytes");
static_assert(offsetof(struct stream_renderer_param_host_visible_memory_mask, entries) == 0,
              "stream_renderer_param_host_visible_memory_mask.entries must be at offset 0");
static_assert(offsetof(struct stream_renderer_param_host_visible_memory_mask, num_entries) == 8,
              "stream_renderer_param_host_visible_memory_mask.num_entries must be at offset 8");

static_assert(sizeof(struct stream_renderer_param) == 16, "stream_renderer_param must be 16 bytes");
static_assert(offsetof(struct stream_renderer_param, key) == 0,
              "stream_renderer_param.key must be at offset 0");
static_assert(offsetof(struct stream_renderer_param, value) == 8,
              "stream_renderer_param.value must be at offset 8");
}  // extern "C"
