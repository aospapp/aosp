/*
* Copyright (C) 2011 The Android Open Source Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
#include "HostConnection.h"

#include "aemu/base/threads/AndroidThread.h"
#include "aemu/base/AndroidHealthMonitor.h"
#include "aemu/base/AndroidHealthMonitorConsumerBasic.h"
#include "cutils/properties.h"
#include "renderControl_types.h"

#ifdef HOST_BUILD
#include "aemu/base/Tracing.h"
#endif
#include "aemu/base/Process.h"

#define DEBUG_HOSTCONNECTION 0

#if DEBUG_HOSTCONNECTION
#define DPRINT(fmt,...) ALOGD("%s: " fmt, __FUNCTION__, ##__VA_ARGS__);
#else
#define DPRINT(...)
#endif

using android::base::guest::CreateHealthMonitor;
using android::base::guest::HealthMonitor;
using android::base::guest::HealthMonitorConsumerBasic;

#ifdef GOLDFISH_NO_GL
struct gl_client_context_t {
    int placeholder;
};
class GLEncoder : public gl_client_context_t {
public:
    GLEncoder(IOStream*, ChecksumCalculator*) { }
    void setContextAccessor(gl_client_context_t *()) { }
};
struct gl2_client_context_t {
    int placeholder;
};
class GL2Encoder : public gl2_client_context_t {
public:
    GL2Encoder(IOStream*, ChecksumCalculator*) { }
    void setContextAccessor(gl2_client_context_t *()) { }
    void setNoHostError(bool) { }
    void setDrawCallFlushInterval(uint32_t) { }
    void setHasAsyncUnmapBuffer(int) { }
    void setHasSyncBufferData(int) { }
};
#else
#include "GLEncoder.h"
#include "GL2Encoder.h"
#endif

#ifdef GFXSTREAM
#include "VkEncoder.h"
#include "AddressSpaceStream.h"
#else
namespace gfxstream {
namespace vk {
struct VkEncoder {
    VkEncoder(IOStream* stream, HealthMonitor<>* healthMonitor = nullptr) { }
    void decRef() { }
    int placeholder;
};
}  // namespace vk
}  // namespace gfxstream
class QemuPipeStream;
typedef QemuPipeStream AddressSpaceStream;
AddressSpaceStream* createAddressSpaceStream(size_t bufSize, HealthMonitor<>* healthMonitor) {
    ALOGE("%s: FATAL: Trying to create ASG stream in unsupported build\n", __func__);
    abort();
}
AddressSpaceStream* createVirtioGpuAddressSpaceStream(HealthMonitor<>* healthMonitor) {
    ALOGE("%s: FATAL: Trying to create VirtioGpu ASG stream in unsupported build\n", __func__);
    abort();
}
#endif

using gfxstream::vk::VkEncoder;

#include "ProcessPipe.h"
#include "QemuPipeStream.h"
#include "TcpStream.h"
#include "ThreadInfo.h"
#include <gralloc_cb_bp.h>
#include <unistd.h>

using android::base::guest::getCurrentThreadId;

#ifdef VIRTIO_GPU

#include "VirtGpu.h"
#include "VirtioGpuPipeStream.h"
#include "virtgpu_drm.h"

#include <cros_gralloc_handle.h>
#include <xf86drm.h>

#endif

#if defined(__linux__) || defined(__ANDROID__)
#include <fstream>
#include <string>
#endif

#undef LOG_TAG
#define LOG_TAG "HostConnection"
#if PLATFORM_SDK_VERSION < 26
#include <cutils/log.h>
#else
#include <log/log.h>
#endif

#define STREAM_BUFFER_SIZE  (4*1024*1024)
#define STREAM_PORT_NUM     22468

HealthMonitor<>* getGlobalHealthMonitor() {
    // Initialize HealthMonitor
    // Rather than inject as a construct arg, we keep it as a static variable in the .cpp
    // to avoid setting up dependencies in other repos (external/qemu)
    static HealthMonitorConsumerBasic sHealthMonitorConsumerBasic;
    static std::unique_ptr<HealthMonitor<>> sHealthMonitor = CreateHealthMonitor(sHealthMonitorConsumerBasic);
    return sHealthMonitor.get();
}

static HostConnectionType getConnectionTypeFromProperty() {
#ifdef __Fuchsia__
    return HOST_CONNECTION_ADDRESS_SPACE;
#elif defined(__ANDROID__) || defined(HOST_BUILD)
    char transportValue[PROPERTY_VALUE_MAX] = "";

    do {
        property_get("ro.boot.qemu.gltransport.name", transportValue, "");
        if (transportValue[0]) { break; }

        property_get("ro.boot.qemu.gltransport", transportValue, "");
        if (transportValue[0]) { break; }

        property_get("ro.boot.hardware.gltransport", transportValue, "");
    } while (false);

    if (!transportValue[0]) return HOST_CONNECTION_QEMU_PIPE;

    if (!strcmp("tcp", transportValue)) return HOST_CONNECTION_TCP;
    if (!strcmp("pipe", transportValue)) return HOST_CONNECTION_QEMU_PIPE;
    if (!strcmp("asg", transportValue)) return HOST_CONNECTION_ADDRESS_SPACE;
    if (!strcmp("virtio-gpu-pipe", transportValue)) return HOST_CONNECTION_VIRTIO_GPU_PIPE;
    if (!strcmp("virtio-gpu-asg", transportValue)) return HOST_CONNECTION_VIRTIO_GPU_ADDRESS_SPACE;

    return HOST_CONNECTION_QEMU_PIPE;
#else
    return HOST_CONNECTION_VIRTIO_GPU_ADDRESS_SPACE;
#endif
}

static uint32_t getDrawCallFlushIntervalFromProperty() {
    constexpr uint32_t kDefaultValue = 800;

    char flushValue[PROPERTY_VALUE_MAX] = "";
    property_get("ro.boot.qemu.gltransport.drawFlushInterval", flushValue, "");
    if (!flushValue[0]) return kDefaultValue;

    const long interval = strtol(flushValue, 0, 10);
    return (interval > 0) ? uint32_t(interval) : kDefaultValue;
}

static GrallocType getGrallocTypeFromProperty() {
    char value[PROPERTY_VALUE_MAX] = "";
    property_get("ro.hardware.gralloc", value, "");

    if (!value[0]) return GRALLOC_TYPE_RANCHU;

    if (!strcmp("ranchu", value)) return GRALLOC_TYPE_RANCHU;
    if (!strcmp("minigbm", value)) return GRALLOC_TYPE_MINIGBM;
    return GRALLOC_TYPE_RANCHU;
}

class GoldfishGralloc : public Gralloc
{
public:
    virtual uint32_t createColorBuffer(
        ExtendedRCEncoderContext* rcEnc,
        int width, int height, uint32_t glformat) {
        return rcEnc->rcCreateColorBuffer(
            rcEnc, width, height, glformat);
    }

    virtual uint32_t getHostHandle(native_handle_t const* handle)
    {
        return cb_handle_t::from(handle)->hostHandle;
    }

    virtual int getFormat(native_handle_t const* handle)
    {
        return cb_handle_t::from(handle)->format;
    }

    virtual size_t getAllocatedSize(native_handle_t const* handle)
    {
        return static_cast<size_t>(cb_handle_t::from(handle)->allocatedSize());
    }
};

static inline uint32_t align_up(uint32_t n, uint32_t a) {
    return ((n + a - 1) / a) * a;
}

#if defined(VIRTIO_GPU)

class MinigbmGralloc : public Gralloc {
public:
    virtual uint32_t createColorBuffer(
        ExtendedRCEncoderContext*,
        int width, int height, uint32_t glformat) {

        // Only supported format for pbuffers in gfxstream
        // should be RGBA8
        const uint32_t kGlRGB = 0x1907;
        const uint32_t kGlRGBA = 0x1908;
        const uint32_t kVirglFormatRGBA = 67; // VIRGL_FORMAT_R8G8B8A8_UNORM;
        uint32_t virtgpu_format = 0;
        uint32_t bpp = 0;
        switch (glformat) {
            case kGlRGB:
                DPRINT("Note: egl wanted GL_RGB, still using RGBA");
                virtgpu_format = kVirglFormatRGBA;
                bpp = 4;
                break;
            case kGlRGBA:
                virtgpu_format = kVirglFormatRGBA;
                bpp = 4;
                break;
            default:
                DPRINT("Note: egl wanted 0x%x, still using RGBA", glformat);
                virtgpu_format = kVirglFormatRGBA;
                bpp = 4;
                break;
        }
        const uint32_t kPipeTexture2D = 2; // PIPE_TEXTURE_2D
        const uint32_t kBindRenderTarget = 1 << 1; // VIRGL_BIND_RENDER_TARGET
        struct drm_virtgpu_resource_create res_create;
        memset(&res_create, 0, sizeof(res_create));
        res_create.target = kPipeTexture2D;
        res_create.format = virtgpu_format;
        res_create.bind = kBindRenderTarget;
        res_create.width = width;
        res_create.height = height;
        res_create.depth = 1;
        res_create.array_size = 1;
        res_create.last_level = 0;
        res_create.nr_samples = 0;
        res_create.stride = bpp * width;
        res_create.size = align_up(bpp * width * height, PAGE_SIZE);

        int ret = drmIoctl(m_fd, DRM_IOCTL_VIRTGPU_RESOURCE_CREATE, &res_create);
        if (ret) {
            ALOGE("%s: DRM_IOCTL_VIRTGPU_RESOURCE_CREATE failed with %s (%d)\n", __func__,
                  strerror(errno), errno);
            abort();
        }

        return res_create.res_handle;
    }

    virtual uint32_t getHostHandle(native_handle_t const* handle) {
        struct drm_virtgpu_resource_info info;
        if (!getResInfo(handle, &info)) {
            ALOGE("%s: failed to get resource info\n", __func__);
            return 0;
        }

        return info.res_handle;
    }

    virtual int getFormat(native_handle_t const* handle) {
        return ((cros_gralloc_handle *)handle)->droid_format;
    }

    virtual uint32_t getFormatDrmFourcc(native_handle_t const* handle) override {
	return ((cros_gralloc_handle *)handle)->format;
    }

    virtual size_t getAllocatedSize(native_handle_t const* handle) {
        struct drm_virtgpu_resource_info info;
        if (!getResInfo(handle, &info)) {
            ALOGE("%s: failed to get resource info\n", __func__);
            return 0;
        }

        return info.size;
    }

    void setFd(int fd) { m_fd = fd; }

private:

    bool getResInfo(native_handle_t const* handle,
                    struct drm_virtgpu_resource_info* info) {
        memset(info, 0x0, sizeof(*info));
        if (m_fd < 0) {
            ALOGE("%s: Error, rendernode fd missing\n", __func__);
            return false;
        }

        struct drm_gem_close gem_close;
        memset(&gem_close, 0x0, sizeof(gem_close));

        cros_gralloc_handle const* cros_handle =
            reinterpret_cast<cros_gralloc_handle const*>(handle);

        uint32_t prime_handle;
        int ret = drmPrimeFDToHandle(m_fd, cros_handle->fds[0], &prime_handle);
        if (ret) {
            ALOGE("%s: DRM_IOCTL_PRIME_FD_TO_HANDLE failed: %s (errno %d)\n",
                  __func__, strerror(errno), errno);
            return false;
        }
        struct ManagedDrmGem {
            ManagedDrmGem(const ManagedDrmGem&) = delete;
            ~ManagedDrmGem() {
                struct drm_gem_close gem_close {
                    .handle = m_prime_handle,
                    .pad = 0,
                };
                int ret = drmIoctl(m_fd, DRM_IOCTL_GEM_CLOSE, &gem_close);
                if (ret) {
                    ALOGE("%s: DRM_IOCTL_GEM_CLOSE failed on handle %" PRIu32 ": %s(%d).",
                          __func__, m_prime_handle, strerror(errno), errno);
                }
            }

            int m_fd;
            uint32_t m_prime_handle;
        } managed_prime_handle{
            .m_fd = m_fd,
            .m_prime_handle = prime_handle,
        };

        info->bo_handle = managed_prime_handle.m_prime_handle;

        struct drm_virtgpu_3d_wait virtgpuWait{
            .handle = managed_prime_handle.m_prime_handle,
            .flags = 0,
        };
        // This only works for host resources by VIRTGPU_RESOURCE_CREATE ioctl.
        // We need to use a different mechanism to synchonize with the host if
        // the minigbm gralloc swiches to virtio-gpu blobs or cross-domain
        // backend.
        ret = drmIoctl(m_fd, DRM_IOCTL_VIRTGPU_WAIT, &virtgpuWait);
        if (ret) {
            ALOGE("%s: DRM_IOCTL_VIRTGPU_WAIT failed: %s(%d)", __func__, strerror(errno), errno);
            return false;
        }

        ret = drmIoctl(m_fd, DRM_IOCTL_VIRTGPU_RESOURCE_INFO, info);
        if (ret) {
            ALOGE("%s: DRM_IOCTL_VIRTGPU_RESOURCE_INFO failed: %s (errno %d)\n",
                  __func__, strerror(errno), errno);
            return false;
        }

        return true;
    }

    int m_fd = -1;
};

#else

class MinigbmGralloc : public Gralloc {
public:
    virtual uint32_t createColorBuffer(
        ExtendedRCEncoderContext*,
        int width, int height, uint32_t glformat) {
        ALOGE("%s: Error: using minigbm without -DVIRTIO_GPU\n", __func__);
        return 0;
    }

    virtual uint32_t getHostHandle(native_handle_t const* handle) {
        ALOGE("%s: Error: using minigbm without -DVIRTIO_GPU\n", __func__);
        return 0;
    }

    virtual int getFormat(native_handle_t const* handle) {
        ALOGE("%s: Error: using minigbm without -DVIRTIO_GPU\n", __func__);
        return 0;
    }

    virtual size_t getAllocatedSize(native_handle_t const* handle) {
        ALOGE("%s: Error: using minigbm without -DVIRTIO_GPU\n", __func__);
        return 0;
    }

    void setFd(int fd) { m_fd = fd; }

private:

    int m_fd = -1;
};

#endif

class GoldfishProcessPipe : public ProcessPipe
{
public:
    bool processPipeInit(int stream_handle, HostConnectionType connType, renderControl_encoder_context_t *rcEnc)
    {
        return ::processPipeInit(stream_handle, connType, rcEnc);
    }

};

static GoldfishGralloc m_goldfishGralloc;
static GoldfishProcessPipe m_goldfishProcessPipe;

HostConnection::HostConnection() :
    exitUncleanly(false),
    m_checksumHelper(),
    m_hostExtensions(),
    m_grallocOnly(true),
    m_noHostError(true),
    m_rendernodeFd(-1) {
#ifdef HOST_BUILD
    android::base::initializeTracing();
#endif
}

HostConnection::~HostConnection()
{
    // round-trip to ensure that queued commands have been processed
    // before process pipe closure is detected.
    if (m_rcEnc && !exitUncleanly) {
        (void)m_rcEnc->rcGetRendererVersion(m_rcEnc.get());
    }

    if (m_grallocType == GRALLOC_TYPE_MINIGBM) {
        delete m_grallocHelper;
    }

    if (m_vkEnc) {
        m_vkEnc->decRef();
    }

    if (m_stream) {
        m_stream->decRef();
    }
}


// static
std::unique_ptr<HostConnection> HostConnection::connect(uint32_t capset_id) {
    const enum HostConnectionType connType = getConnectionTypeFromProperty();

    // Use "new" to access a non-public constructor.
    auto con = std::unique_ptr<HostConnection>(new HostConnection);

    switch (connType) {
        case HOST_CONNECTION_ADDRESS_SPACE: {
            auto stream = createAddressSpaceStream(STREAM_BUFFER_SIZE, getGlobalHealthMonitor());
            if (!stream) {
                ALOGE("Failed to create AddressSpaceStream for host connection\n");
                return nullptr;
            }
            con->m_connectionType = HOST_CONNECTION_ADDRESS_SPACE;
            con->m_grallocType = GRALLOC_TYPE_RANCHU;
            con->m_stream = stream;
            con->m_grallocHelper = &m_goldfishGralloc;
            con->m_processPipe = &m_goldfishProcessPipe;
            break;
        }
        case HOST_CONNECTION_QEMU_PIPE: {
            auto stream = new QemuPipeStream(STREAM_BUFFER_SIZE);
            if (!stream) {
                ALOGE("Failed to create QemuPipeStream for host connection\n");
                return nullptr;
            }
            if (stream->connect() < 0) {
                ALOGE("Failed to connect to host (QemuPipeStream)\n");
                return nullptr;
            }
            con->m_connectionType = HOST_CONNECTION_QEMU_PIPE;
            con->m_grallocType = GRALLOC_TYPE_RANCHU;
            con->m_stream = stream;
            con->m_grallocHelper = &m_goldfishGralloc;
            con->m_processPipe = &m_goldfishProcessPipe;
            break;
        }
        case HOST_CONNECTION_TCP: {
#ifndef __ANDROID__
            ALOGE("Failed to create TCP connection on non-Android guest\n");
            return nullptr;
            break;
#else
            auto stream = new TcpStream(STREAM_BUFFER_SIZE);
            if (!stream) {
                ALOGE("Failed to create TcpStream for host connection\n");
                return nullptr;
            }

            if (stream->connect("10.0.2.2", STREAM_PORT_NUM) < 0) {
                ALOGE("Failed to connect to host (TcpStream)\n");
                return nullptr;
            }
            con->m_connectionType = HOST_CONNECTION_TCP;
            con->m_grallocType = GRALLOC_TYPE_RANCHU;
            con->m_stream = stream;
            con->m_grallocHelper = &m_goldfishGralloc;
            con->m_processPipe = &m_goldfishProcessPipe;
            break;
#endif
        }
#if defined(VIRTIO_GPU) && !defined(HOST_BUILD)
        case HOST_CONNECTION_VIRTIO_GPU_PIPE: {
            auto stream = new VirtioGpuPipeStream(STREAM_BUFFER_SIZE);
            if (!stream) {
                ALOGE("Failed to create VirtioGpu for host connection\n");
                return nullptr;
            }
            if (stream->connect() < 0) {
                ALOGE("Failed to connect to host (VirtioGpu)\n");
                return nullptr;
            }
            con->m_connectionType = HOST_CONNECTION_VIRTIO_GPU_PIPE;
            con->m_grallocType = getGrallocTypeFromProperty();
            auto rendernodeFd = stream->getRendernodeFd();
            con->m_stream = stream;
            con->m_rendernodeFd = rendernodeFd;
            switch (con->m_grallocType) {
                case GRALLOC_TYPE_RANCHU:
                    con->m_grallocHelper = &m_goldfishGralloc;
                    break;
                case GRALLOC_TYPE_MINIGBM: {
                    MinigbmGralloc* m = new MinigbmGralloc;
                    m->setFd(rendernodeFd);
                    con->m_grallocHelper = m;
                    break;
                }
                default:
                    ALOGE("Fatal: Unknown gralloc type 0x%x\n", con->m_grallocType);
                    abort();
            }
            con->m_processPipe = &m_goldfishProcessPipe;
            break;
        }
        case HOST_CONNECTION_VIRTIO_GPU_ADDRESS_SPACE: {
            VirtGpuDevice& instance = VirtGpuDevice::getInstance((enum VirtGpuCapset)capset_id);
            auto deviceHandle = instance.getDeviceHandle();
            auto stream = createVirtioGpuAddressSpaceStream(getGlobalHealthMonitor());
            if (!stream) {
                ALOGE("Failed to create virtgpu AddressSpaceStream\n");
                return nullptr;
            }
            con->m_connectionType = HOST_CONNECTION_VIRTIO_GPU_ADDRESS_SPACE;
            con->m_grallocType = getGrallocTypeFromProperty();
            con->m_stream = stream;
            con->m_rendernodeFd = deviceHandle;
            switch (con->m_grallocType) {
                case GRALLOC_TYPE_RANCHU:
                    con->m_grallocHelper = &m_goldfishGralloc;
                    break;
                case GRALLOC_TYPE_MINIGBM: {
                    MinigbmGralloc* m = new MinigbmGralloc;
                    m->setFd(deviceHandle);
                    con->m_grallocHelper = m;
                    break;
                }
                default:
                    ALOGE("Fatal: Unknown gralloc type 0x%x\n", con->m_grallocType);
                    abort();
            }
            con->m_processPipe = &m_goldfishProcessPipe;
            break;
        }
#endif // !VIRTIO_GPU && !HOST_BUILD_
        default:
            break;
    }

    // send zero 'clientFlags' to the host.
    unsigned int *pClientFlags =
            (unsigned int *)con->m_stream->allocBuffer(sizeof(unsigned int));
    *pClientFlags = 0;
    con->m_stream->commitBuffer(sizeof(unsigned int));

#if defined(__linux__) || defined(__ANDROID__)
    auto rcEnc = con->rcEncoder();
    if (rcEnc != nullptr) {
        auto processName = android::base::guest::getProcessName();
        if (!processName.empty()) {
            rcEnc->rcSetProcessMetadata(
                rcEnc, const_cast<char*>("process_name"),
                const_cast<RenderControlByte*>(processName.c_str()),
                strlen(processName.c_str())+ 1);
        }
    }
#endif

    return con;
}

HostConnection *HostConnection::get() {
    return getWithThreadInfo(getEGLThreadInfo(), VIRTIO_GPU_CAPSET_NONE);
}

HostConnection *HostConnection::getOrCreate(uint32_t capset_id) {
    return getWithThreadInfo(getEGLThreadInfo(), capset_id);
}

HostConnection *HostConnection::getWithThreadInfo(EGLThreadInfo* tinfo, uint32_t capset_id) {
    // Get thread info
    if (!tinfo) {
        return NULL;
    }

    if (tinfo->hostConn == NULL) {
        tinfo->hostConn = HostConnection::createUnique(capset_id);
    }

    return tinfo->hostConn.get();
}

void HostConnection::exit() {
    EGLThreadInfo *tinfo = getEGLThreadInfo();
    if (!tinfo) {
        return;
    }

    tinfo->hostConn.reset();
}

void HostConnection::exitUnclean() {
    EGLThreadInfo *tinfo = getEGLThreadInfo();
    if (!tinfo) {
        return;
    }

    tinfo->hostConn->exitUncleanly = true;
    tinfo->hostConn.reset();
}

// static
std::unique_ptr<HostConnection> HostConnection::createUnique(uint32_t capset_id) {
    return connect(capset_id);
}

GLEncoder *HostConnection::glEncoder()
{
    if (!m_glEnc) {
        m_glEnc = std::make_unique<GLEncoder>(m_stream, checksumHelper());
        DBG("HostConnection::glEncoder new encoder %p, tid %lu", m_glEnc, getCurrentThreadId());
        m_glEnc->setContextAccessor(s_getGLContext);
    }
    return m_glEnc.get();
}

GL2Encoder *HostConnection::gl2Encoder()
{
    if (!m_gl2Enc) {
        m_gl2Enc =
            std::make_unique<GL2Encoder>(m_stream, checksumHelper());
        DBG("HostConnection::gl2Encoder new encoder %p, tid %lu", m_gl2Enc, getCurrentThreadId());
        m_gl2Enc->setContextAccessor(s_getGL2Context);
        m_gl2Enc->setNoHostError(m_noHostError);
        m_gl2Enc->setDrawCallFlushInterval(
            getDrawCallFlushIntervalFromProperty());
        m_gl2Enc->setHasAsyncUnmapBuffer(m_rcEnc->hasAsyncUnmapBuffer());
        m_gl2Enc->setHasSyncBufferData(m_rcEnc->hasSyncBufferData());
    }
    return m_gl2Enc.get();
}

VkEncoder *HostConnection::vkEncoder()
{
    rcEncoder();
    if (!m_vkEnc) {
        m_vkEnc = new VkEncoder(m_stream, getGlobalHealthMonitor());
    }
    return m_vkEnc;
}

ExtendedRCEncoderContext *HostConnection::rcEncoder()
{
    if (!m_rcEnc) {
        m_rcEnc = std::make_unique<ExtendedRCEncoderContext>(m_stream,
                                                             checksumHelper());

        ExtendedRCEncoderContext* rcEnc = m_rcEnc.get();
        setChecksumHelper(rcEnc);
        queryAndSetSyncImpl(rcEnc);
        queryAndSetDmaImpl(rcEnc);
        queryAndSetGLESMaxVersion(rcEnc);
        queryAndSetNoErrorState(rcEnc);
        queryAndSetHostCompositionImpl(rcEnc);
        queryAndSetDirectMemSupport(rcEnc);
        queryAndSetVulkanSupport(rcEnc);
        queryAndSetDeferredVulkanCommandsSupport(rcEnc);
        queryAndSetVulkanNullOptionalStringsSupport(rcEnc);
        queryAndSetVulkanCreateResourcesWithRequirementsSupport(rcEnc);
        queryAndSetVulkanIgnoredHandles(rcEnc);
        queryAndSetYUVCache(rcEnc);
        queryAndSetAsyncUnmapBuffer(rcEnc);
        queryAndSetVirtioGpuNext(rcEnc);
        queryHasSharedSlotsHostMemoryAllocator(rcEnc);
        queryAndSetVulkanFreeMemorySync(rcEnc);
        queryAndSetVirtioGpuNativeSync(rcEnc);
        queryAndSetVulkanShaderFloat16Int8Support(rcEnc);
        queryAndSetVulkanAsyncQueueSubmitSupport(rcEnc);
        queryAndSetHostSideTracingSupport(rcEnc);
        queryAndSetAsyncFrameCommands(rcEnc);
        queryAndSetVulkanQueueSubmitWithCommandsSupport(rcEnc);
        queryAndSetVulkanBatchedDescriptorSetUpdateSupport(rcEnc);
        queryAndSetSyncBufferData(rcEnc);
        queryAndSetVulkanAsyncQsri(rcEnc);
        queryAndSetReadColorBufferDma(rcEnc);
        queryAndSetHWCMultiConfigs(rcEnc);
        queryAndSetVulkanAuxCommandBufferMemory(rcEnc);
        queryVersion(rcEnc);
        if (m_processPipe) {
            auto fd = (m_connectionType == HOST_CONNECTION_VIRTIO_GPU_ADDRESS_SPACE) ? m_rendernodeFd : -1;
            m_processPipe->processPipeInit(fd, m_connectionType, rcEnc);
        }
    }
    return m_rcEnc.get();
}

gl_client_context_t *HostConnection::s_getGLContext()
{
    EGLThreadInfo *ti = getEGLThreadInfo();
    if (ti->hostConn) {
        return ti->hostConn->m_glEnc.get();
    }
    return NULL;
}

gl2_client_context_t *HostConnection::s_getGL2Context()
{
    EGLThreadInfo *ti = getEGLThreadInfo();
    if (ti->hostConn) {
        return ti->hostConn->m_gl2Enc.get();
    }
    return NULL;
}

const std::string& HostConnection::queryHostExtensions(ExtendedRCEncoderContext *rcEnc) {
    if (!m_hostExtensions.empty()) {
        return m_hostExtensions;
    }

    // Extensions strings are usually quite long, preallocate enough here.
    std::string extensionsBuffer(1023, '\0');

    // Returns the required size including the 0-terminator, so
    // account it when passing/using the sizes.
    int extensionSize = rcEnc->rcGetHostExtensionsString(rcEnc,
                                                         extensionsBuffer.size() + 1,
                                                         &extensionsBuffer[0]);
    if (extensionSize < 0) {
        extensionsBuffer.resize(-extensionSize);
        extensionSize = rcEnc->rcGetHostExtensionsString(rcEnc,
                                                         -extensionSize + 1,
                                                         &extensionsBuffer[0]);
    }

    if (extensionSize > 0) {
        extensionsBuffer.resize(extensionSize - 1);
        m_hostExtensions.swap(extensionsBuffer);
    }

    return m_hostExtensions;
}

void HostConnection::queryAndSetHostCompositionImpl(ExtendedRCEncoderContext *rcEnc) {
    const std::string& hostExtensions = queryHostExtensions(rcEnc);
    DPRINT("HostComposition ext %s", hostExtensions.c_str());
    // make sure V2 is checked first before V1, as host may declare supporting both
    if (hostExtensions.find(kHostCompositionV2) != std::string::npos) {
        rcEnc->setHostComposition(HOST_COMPOSITION_V2);
    }
    else if (hostExtensions.find(kHostCompositionV1) != std::string::npos) {
        rcEnc->setHostComposition(HOST_COMPOSITION_V1);
    }
    else {
        rcEnc->setHostComposition(HOST_COMPOSITION_NONE);
    }
}

void HostConnection::setChecksumHelper(ExtendedRCEncoderContext *rcEnc) {
    const std::string& hostExtensions = queryHostExtensions(rcEnc);
    // check the host supported version
    uint32_t checksumVersion = 0;
    const char* checksumPrefix = ChecksumCalculator::getMaxVersionStrPrefix();
    const char* glProtocolStr = strstr(hostExtensions.c_str(), checksumPrefix);
    if (glProtocolStr) {
        uint32_t maxVersion = ChecksumCalculator::getMaxVersion();
        sscanf(glProtocolStr+strlen(checksumPrefix), "%d", &checksumVersion);
        if (maxVersion < checksumVersion) {
            checksumVersion = maxVersion;
        }
        // The ordering of the following two commands matters!
        // Must tell the host first before setting it in the guest
        rcEnc->rcSelectChecksumHelper(rcEnc, checksumVersion, 0);
        m_checksumHelper.setVersion(checksumVersion);
    }
}

void HostConnection::queryAndSetSyncImpl(ExtendedRCEncoderContext *rcEnc) {
    const std::string& hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kRCNativeSyncV4) != std::string::npos) {
        rcEnc->setSyncImpl(SYNC_IMPL_NATIVE_SYNC_V4);
    } else if (hostExtensions.find(kRCNativeSyncV3) != std::string::npos) {
        rcEnc->setSyncImpl(SYNC_IMPL_NATIVE_SYNC_V3);
    } else if (hostExtensions.find(kRCNativeSyncV2) != std::string::npos) {
        rcEnc->setSyncImpl(SYNC_IMPL_NATIVE_SYNC_V2);
    } else {
        rcEnc->setSyncImpl(SYNC_IMPL_NONE);
    }
}

void HostConnection::queryAndSetDmaImpl(ExtendedRCEncoderContext *rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kDmaExtStr_v1) != std::string::npos) {
        rcEnc->setDmaImpl(DMA_IMPL_v1);
    } else {
        rcEnc->setDmaImpl(DMA_IMPL_NONE);
    }
}

void HostConnection::queryAndSetGLESMaxVersion(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kGLESMaxVersion_2) != std::string::npos) {
        rcEnc->setGLESMaxVersion(GLES_MAX_VERSION_2);
    } else if (hostExtensions.find(kGLESMaxVersion_3_0) != std::string::npos) {
        rcEnc->setGLESMaxVersion(GLES_MAX_VERSION_3_0);
    } else if (hostExtensions.find(kGLESMaxVersion_3_1) != std::string::npos) {
        rcEnc->setGLESMaxVersion(GLES_MAX_VERSION_3_1);
    } else if (hostExtensions.find(kGLESMaxVersion_3_2) != std::string::npos) {
        rcEnc->setGLESMaxVersion(GLES_MAX_VERSION_3_2);
    } else {
        ALOGW("Unrecognized GLES max version string in extensions: %s",
              hostExtensions.c_str());
        rcEnc->setGLESMaxVersion(GLES_MAX_VERSION_2);
    }
}

void HostConnection::queryAndSetNoErrorState(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kGLESUseHostError) != std::string::npos) {
        m_noHostError = false;
    }
}

void HostConnection::queryAndSetDirectMemSupport(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kGLDirectMem) != std::string::npos) {
        rcEnc->featureInfo()->hasDirectMem = true;
    }
}

void HostConnection::queryAndSetVulkanSupport(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kVulkan) != std::string::npos) {
        rcEnc->featureInfo()->hasVulkan = true;
    }
}

void HostConnection::queryAndSetDeferredVulkanCommandsSupport(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kDeferredVulkanCommands) != std::string::npos) {
        rcEnc->featureInfo()->hasDeferredVulkanCommands = true;
    }
}

void HostConnection::queryAndSetVulkanNullOptionalStringsSupport(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kVulkanNullOptionalStrings) != std::string::npos) {
        rcEnc->featureInfo()->hasVulkanNullOptionalStrings = true;
    }
}

void HostConnection::queryAndSetVulkanCreateResourcesWithRequirementsSupport(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kVulkanCreateResourcesWithRequirements) != std::string::npos) {
        rcEnc->featureInfo()->hasVulkanCreateResourcesWithRequirements = true;
    }
}

void HostConnection::queryAndSetVulkanIgnoredHandles(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kVulkanIgnoredHandles) != std::string::npos) {
        rcEnc->featureInfo()->hasVulkanIgnoredHandles = true;
    }
}

void HostConnection::queryAndSetYUVCache(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kYUVCache) != std::string::npos) {
        rcEnc->featureInfo()->hasYUVCache = true;
    }
}

void HostConnection::queryAndSetAsyncUnmapBuffer(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kAsyncUnmapBuffer) != std::string::npos) {
        rcEnc->featureInfo()->hasAsyncUnmapBuffer = true;
    }
}

void HostConnection::queryAndSetVirtioGpuNext(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kVirtioGpuNext) != std::string::npos) {
        rcEnc->featureInfo()->hasVirtioGpuNext = true;
    }
}

void HostConnection::queryHasSharedSlotsHostMemoryAllocator(ExtendedRCEncoderContext *rcEnc) {
    const std::string& hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kHasSharedSlotsHostMemoryAllocator) != std::string::npos) {
        rcEnc->featureInfo()->hasSharedSlotsHostMemoryAllocator = true;
    }
}

void HostConnection::queryAndSetVulkanFreeMemorySync(ExtendedRCEncoderContext *rcEnc) {
    const std::string& hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kVulkanFreeMemorySync) != std::string::npos) {
        rcEnc->featureInfo()->hasVulkanFreeMemorySync = true;
    }
}

void HostConnection::queryAndSetVirtioGpuNativeSync(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kVirtioGpuNativeSync) != std::string::npos) {
        rcEnc->featureInfo()->hasVirtioGpuNativeSync = true;
    }
}

void HostConnection::queryAndSetVulkanShaderFloat16Int8Support(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kVulkanShaderFloat16Int8) != std::string::npos) {
        rcEnc->featureInfo()->hasVulkanShaderFloat16Int8 = true;
    }
}

void HostConnection::queryAndSetVulkanAsyncQueueSubmitSupport(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kVulkanAsyncQueueSubmit) != std::string::npos) {
        rcEnc->featureInfo()->hasVulkanAsyncQueueSubmit = true;
    }
}

void HostConnection::queryAndSetHostSideTracingSupport(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kHostSideTracing) != std::string::npos) {
        rcEnc->featureInfo()->hasHostSideTracing = true;
    }
}

void HostConnection::queryAndSetAsyncFrameCommands(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kAsyncFrameCommands) != std::string::npos) {
        rcEnc->featureInfo()->hasAsyncFrameCommands = true;
    }
}

void HostConnection::queryAndSetVulkanQueueSubmitWithCommandsSupport(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kVulkanQueueSubmitWithCommands) != std::string::npos) {
        rcEnc->featureInfo()->hasVulkanQueueSubmitWithCommands = true;
    }
}

void HostConnection::queryAndSetVulkanBatchedDescriptorSetUpdateSupport(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kVulkanBatchedDescriptorSetUpdate) != std::string::npos) {
        rcEnc->featureInfo()->hasVulkanBatchedDescriptorSetUpdate = true;
    }
}

void HostConnection::queryAndSetSyncBufferData(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kSyncBufferData) != std::string::npos) {
        rcEnc->featureInfo()->hasSyncBufferData = true;
    }
}

void HostConnection::queryAndSetVulkanAsyncQsri(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kVulkanAsyncQsri) != std::string::npos) {
        rcEnc->featureInfo()->hasVulkanAsyncQsri = true;
    }
}

void HostConnection::queryAndSetReadColorBufferDma(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kReadColorBufferDma) != std::string::npos) {
        rcEnc->featureInfo()->hasReadColorBufferDma = true;
    }
}

void HostConnection::queryAndSetHWCMultiConfigs(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    if (hostExtensions.find(kHWCMultiConfigs) != std::string::npos) {
        rcEnc->featureInfo()->hasHWCMultiConfigs = true;
    }
}

void HostConnection::queryAndSetVulkanAuxCommandBufferMemory(ExtendedRCEncoderContext* rcEnc) {
    std::string hostExtensions = queryHostExtensions(rcEnc);
    rcEnc->featureInfo()->hasVulkanAuxCommandMemory = hostExtensions.find(kVulkanAuxCommandMemory) != std::string::npos;
}


GLint HostConnection::queryVersion(ExtendedRCEncoderContext* rcEnc) {
    GLint version = m_rcEnc->rcGetRendererVersion(m_rcEnc.get());
    return version;
}
