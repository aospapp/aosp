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
};
#else
#include "GLEncoder.h"
#include "GL2Encoder.h"
#endif

#ifdef GOLDFISH_VULKAN
#include "VkEncoder.h"
#else
namespace goldfish_vk {
struct VkEncoder {
    VkEncoder(IOStream*) { }
    int placeholder;
};
} // namespace goldfish_vk
#endif

using goldfish_vk::VkEncoder;

#include "ProcessPipe.h"
#include "QemuPipeStream.h"
#include "TcpStream.h"
#include "ThreadInfo.h"

#include "gralloc_cb.h"

#ifdef VIRTIO_GPU
#include "VirtioGpuStream.h"
#endif

#if PLATFORM_SDK_VERSION < 26
#include <cutils/log.h>
#else
#include <log/log.h>
#endif

#define STREAM_BUFFER_SIZE  (4*1024*1024)
#define STREAM_PORT_NUM     22468

enum HostConnectionType {
    HOST_CONNECTION_TCP = 0,
    HOST_CONNECTION_QEMU_PIPE = 1,
    HOST_CONNECTION_VIRTIO_GPU = 2,
};

class GoldfishGralloc : public Gralloc
{
public:
    uint32_t getHostHandle(native_handle_t const* handle)
    {
        return ((cb_handle_t *)handle)->hostHandle;
    }

    int getFormat(native_handle_t const* handle)
    {
        return ((cb_handle_t *)handle)->format;
    }
};

class GoldfishProcessPipe : public ProcessPipe
{
public:
    bool processPipeInit(renderControl_encoder_context_t *rcEnc)
    {
        return ::processPipeInit(rcEnc);
    }
};

static GoldfishGralloc m_goldfishGralloc;
static GoldfishProcessPipe m_goldfishProcessPipe;

HostConnection::HostConnection() :
    m_stream(NULL),
    m_glEnc(NULL),
    m_gl2Enc(NULL),
    m_vkEnc(NULL),
    m_rcEnc(NULL),
    m_checksumHelper(),
    m_glExtensions(),
    m_grallocOnly(true),
    m_noHostError(false)
{
}

HostConnection::~HostConnection()
{
    delete m_stream;
    delete m_glEnc;
    delete m_gl2Enc;
    delete m_rcEnc;
}

// static
HostConnection* HostConnection::connect(HostConnection* con) {
    if (!con) return con;

    const enum HostConnectionType connType = HOST_CONNECTION_VIRTIO_GPU;

    switch (connType) {
        default:
        case HOST_CONNECTION_QEMU_PIPE: {
            QemuPipeStream *stream = new QemuPipeStream(STREAM_BUFFER_SIZE);
            if (!stream) {
                ALOGE("Failed to create QemuPipeStream for host connection!!!\n");
                delete con;
                return NULL;
            }
            if (stream->connect() < 0) {
                ALOGE("Failed to connect to host (QemuPipeStream)!!!\n");
                delete stream;
                delete con;
                return NULL;
            }
            con->m_stream = stream;
            con->m_grallocHelper = &m_goldfishGralloc;
            con->m_processPipe = &m_goldfishProcessPipe;
            break;
        }
        case HOST_CONNECTION_TCP: {
            TcpStream *stream = new TcpStream(STREAM_BUFFER_SIZE);
            if (!stream) {
                ALOGE("Failed to create TcpStream for host connection!!!\n");
                delete con;
                return NULL;
            }

            if (stream->connect("10.0.2.2", STREAM_PORT_NUM) < 0) {
                ALOGE("Failed to connect to host (TcpStream)!!!\n");
                delete stream;
                delete con;
                return NULL;
            }
            con->m_stream = stream;
            con->m_grallocHelper = &m_goldfishGralloc;
            con->m_processPipe = &m_goldfishProcessPipe;
            break;
        }
#ifdef VIRTIO_GPU
        case HOST_CONNECTION_VIRTIO_GPU: {
            VirtioGpuStream *stream = new VirtioGpuStream(STREAM_BUFFER_SIZE);
            if (!stream) {
                ALOGE("Failed to create VirtioGpu for host connection!!!\n");
                delete con;
                return NULL;
            }
            if (stream->connect() < 0) {
                ALOGE("Failed to connect to host (VirtioGpu)!!!\n");
                delete stream;
                delete con;
                return NULL;
            }
            con->m_stream = stream;
            con->m_grallocHelper = stream->getGralloc();
            con->m_processPipe = stream->getProcessPipe();
            break;
        }
#endif
    }

    // send zero 'clientFlags' to the host.
    unsigned int *pClientFlags =
            (unsigned int *)con->m_stream->allocBuffer(sizeof(unsigned int));
    *pClientFlags = 0;
    con->m_stream->commitBuffer(sizeof(unsigned int));

    ALOGD("HostConnection::get() New Host Connection established %p, tid %d\n",
          con, getCurrentThreadId());
    return con;
}

HostConnection *HostConnection::get() {
    return getWithThreadInfo(getEGLThreadInfo());
}

HostConnection *HostConnection::getWithThreadInfo(EGLThreadInfo* tinfo) {

    /* TODO: Make this configurable with a system property */
    const enum HostConnectionType connType = HOST_CONNECTION_VIRTIO_GPU;

    // Get thread info
    if (!tinfo) {
        return NULL;
    }

    if (tinfo->hostConn == NULL) {
        HostConnection *con = new HostConnection();
        con = connect(con);

        tinfo->hostConn = con;
    }

    return tinfo->hostConn;
}

void HostConnection::exit() {
    EGLThreadInfo *tinfo = getEGLThreadInfo();
    if (!tinfo) {
        return;
    }

    if (tinfo->hostConn) {
        delete tinfo->hostConn;
        tinfo->hostConn = NULL;
    }
}

// static 
HostConnection *HostConnection::createUnique() {
    ALOGD("%s: call\n", __func__);
    return connect(new HostConnection());
}

// static
void HostConnection::teardownUnique(HostConnection* con) {
    delete con;
}

GLEncoder *HostConnection::glEncoder()
{
    if (!m_glEnc) {
        m_glEnc = new GLEncoder(m_stream, checksumHelper());
        DBG("HostConnection::glEncoder new encoder %p, tid %d",
            m_glEnc, getCurrentThreadId());
        m_glEnc->setContextAccessor(s_getGLContext);
    }
    return m_glEnc;
}

GL2Encoder *HostConnection::gl2Encoder()
{
    if (!m_gl2Enc) {
        m_gl2Enc = new GL2Encoder(m_stream, checksumHelper());
        DBG("HostConnection::gl2Encoder new encoder %p, tid %d",
            m_gl2Enc, getCurrentThreadId());
        m_gl2Enc->setContextAccessor(s_getGL2Context);
        m_gl2Enc->setNoHostError(m_noHostError);
    }
    return m_gl2Enc;
}

VkEncoder *HostConnection::vkEncoder()
{
    if (!m_vkEnc) {
        m_vkEnc = new VkEncoder(m_stream);
    }
    return m_vkEnc;
}

ExtendedRCEncoderContext *HostConnection::rcEncoder()
{
    if (!m_rcEnc) {
        m_rcEnc = new ExtendedRCEncoderContext(m_stream, checksumHelper());
        setChecksumHelper(m_rcEnc);
        queryAndSetSyncImpl(m_rcEnc);
        queryAndSetDmaImpl(m_rcEnc);
        queryAndSetGLESMaxVersion(m_rcEnc);
        queryAndSetNoErrorState(m_rcEnc);
        queryAndSetHostCompositionImpl(m_rcEnc);
        queryAndSetDirectMemSupport(m_rcEnc);
        queryAndSetVulkanSupport(m_rcEnc);
        if (m_processPipe) {
            m_processPipe->processPipeInit(m_rcEnc);
        }
    }
    return m_rcEnc;
}

gl_client_context_t *HostConnection::s_getGLContext()
{
    EGLThreadInfo *ti = getEGLThreadInfo();
    if (ti->hostConn) {
        return ti->hostConn->m_glEnc;
    }
    return NULL;
}

gl2_client_context_t *HostConnection::s_getGL2Context()
{
    EGLThreadInfo *ti = getEGLThreadInfo();
    if (ti->hostConn) {
        return ti->hostConn->m_gl2Enc;
    }
    return NULL;
}

const std::string& HostConnection::queryGLExtensions(ExtendedRCEncoderContext *rcEnc) {
    if (!m_glExtensions.empty()) {
        return m_glExtensions;
    }

    // Extensions strings are usually quite long, preallocate enough here.
    std::string extensions_buffer(1023, '\0');

    // rcGetGLString() returns required size including the 0-terminator, so
    // account it when passing/using the sizes.
    int extensionSize = rcEnc->rcGetGLString(rcEnc, GL_EXTENSIONS,
                                             &extensions_buffer[0],
                                             extensions_buffer.size() + 1);
    if (extensionSize < 0) {
        extensions_buffer.resize(-extensionSize);
        extensionSize = rcEnc->rcGetGLString(rcEnc, GL_EXTENSIONS,
                                             &extensions_buffer[0],
                                            -extensionSize + 1);
    }

    if (extensionSize > 0) {
        extensions_buffer.resize(extensionSize - 1);
        m_glExtensions.swap(extensions_buffer);
    }

    return m_glExtensions;
}

void HostConnection::queryAndSetHostCompositionImpl(ExtendedRCEncoderContext *rcEnc) {
    const std::string& glExtensions = queryGLExtensions(rcEnc);
    ALOGD("HostComposition ext %s", glExtensions.c_str());
    if (glExtensions.find(kHostCompositionV1) != std::string::npos) {
        rcEnc->setHostComposition(HOST_COMPOSITION_V1);
    }
    else {
        rcEnc->setHostComposition(HOST_COMPOSITION_NONE);
    }
}

void HostConnection::setChecksumHelper(ExtendedRCEncoderContext *rcEnc) {
    const std::string& glExtensions = queryGLExtensions(rcEnc);
    // check the host supported version
    uint32_t checksumVersion = 0;
    const char* checksumPrefix = ChecksumCalculator::getMaxVersionStrPrefix();
    const char* glProtocolStr = strstr(glExtensions.c_str(), checksumPrefix);
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
    const std::string& glExtensions = queryGLExtensions(rcEnc);
#if PLATFORM_SDK_VERSION <= 16 || (!defined(__i386__) && !defined(__x86_64__))
    rcEnc->setSyncImpl(SYNC_IMPL_NONE);
#else
    if (glExtensions.find(kRCNativeSyncV3) != std::string::npos) {
        rcEnc->setSyncImpl(SYNC_IMPL_NATIVE_SYNC_V3);
    } else if (glExtensions.find(kRCNativeSyncV2) != std::string::npos) {
        rcEnc->setSyncImpl(SYNC_IMPL_NATIVE_SYNC_V2);
    } else {
        rcEnc->setSyncImpl(SYNC_IMPL_NONE);
    }
#endif
}

void HostConnection::queryAndSetDmaImpl(ExtendedRCEncoderContext *rcEnc) {
    std::string glExtensions = queryGLExtensions(rcEnc);
#if PLATFORM_SDK_VERSION <= 16 || (!defined(__i386__) && !defined(__x86_64__))
    rcEnc->setDmaImpl(DMA_IMPL_NONE);
#else
    if (glExtensions.find(kDmaExtStr_v1) != std::string::npos) {
        rcEnc->setDmaImpl(DMA_IMPL_v1);
    } else {
        rcEnc->setDmaImpl(DMA_IMPL_NONE);
    }
#endif
}

void HostConnection::queryAndSetGLESMaxVersion(ExtendedRCEncoderContext* rcEnc) {
    std::string glExtensions = queryGLExtensions(rcEnc);
    if (glExtensions.find(kGLESMaxVersion_2) != std::string::npos) {
        rcEnc->setGLESMaxVersion(GLES_MAX_VERSION_2);
    } else if (glExtensions.find(kGLESMaxVersion_3_0) != std::string::npos) {
        rcEnc->setGLESMaxVersion(GLES_MAX_VERSION_3_0);
    } else if (glExtensions.find(kGLESMaxVersion_3_1) != std::string::npos) {
        rcEnc->setGLESMaxVersion(GLES_MAX_VERSION_3_1);
    } else if (glExtensions.find(kGLESMaxVersion_3_2) != std::string::npos) {
        rcEnc->setGLESMaxVersion(GLES_MAX_VERSION_3_2);
    } else {
        ALOGW("Unrecognized GLES max version string in extensions: %s",
              glExtensions.c_str());
        rcEnc->setGLESMaxVersion(GLES_MAX_VERSION_2);
    }
}

void HostConnection::queryAndSetNoErrorState(ExtendedRCEncoderContext* rcEnc) {
    std::string glExtensions = queryGLExtensions(rcEnc);
    if (glExtensions.find(kGLESNoHostError) != std::string::npos) {
        m_noHostError = true;
    }
}

void HostConnection::queryAndSetDirectMemSupport(ExtendedRCEncoderContext* rcEnc) {
    std::string glExtensions = queryGLExtensions(rcEnc);
    if (glExtensions.find(kGLDirectMem) != std::string::npos) {
        rcEnc->featureInfo()->hasDirectMem = true;
    }
}

void HostConnection::queryAndSetVulkanSupport(ExtendedRCEncoderContext* rcEnc) {
    std::string glExtensions = queryGLExtensions(rcEnc);
    if (glExtensions.find(kVulkan) != std::string::npos) {
        rcEnc->featureInfo()->hasVulkan = true;
    }
}

void HostConnection::queryAndSetDeferredVulkanCommandsSupport(ExtendedRCEncoderContext* rcEnc) {
    std::string glExtensions = queryGLExtensions(rcEnc);
    if (glExtensions.find(kDeferredVulkanCommands) != std::string::npos) {
        rcEnc->featureInfo()->hasDeferredVulkanCommands = true;
    }
}
