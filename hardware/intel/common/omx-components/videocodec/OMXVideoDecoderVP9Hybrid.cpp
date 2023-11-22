/*
* Copyright (c) 2012 Intel Corporation.  All rights reserved.
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

#define LOG_TIME 0
//#define LOG_NDEBUG 0
#define LOG_TAG "OMXVideoDecoderVP9Hybrid"
#include <wrs_omxil_core/log.h>
#include "OMXVideoDecoderVP9Hybrid.h"

#include <system/window.h>
#include <hardware/hardware.h>
#include <hardware/gralloc.h>
#include <system/graphics.h>

static const char* VP9_MIME_TYPE = "video/x-vnd.on2.vp9";

OMXVideoDecoderVP9Hybrid::OMXVideoDecoderVP9Hybrid() {
    LOGV("OMXVideoDecoderVP9Hybrid is constructed.");
    mNativeBufferCount = OUTPORT_NATIVE_BUFFER_COUNT;
    BuildHandlerList();
    mLibHandle = NULL;
    mOpenDecoder = NULL;
    mInitDecoder = NULL;
    mCloseDecoder = NULL;
    mSingalRenderDone = NULL;
    mDecoderDecode = NULL;
    mCheckBufferAvailable = NULL;
    mGetOutput = NULL;
}

OMXVideoDecoderVP9Hybrid::~OMXVideoDecoderVP9Hybrid() {
    LOGV("OMXVideoDecoderVP9Hybrid is destructed.");
}

OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::InitInputPortFormatSpecific(
    OMX_PARAM_PORTDEFINITIONTYPE *paramPortDefinitionInput) {
    // OMX_PARAM_PORTDEFINITIONTYPE
    paramPortDefinitionInput->nBufferCountActual = INPORT_ACTUAL_BUFFER_COUNT;
    paramPortDefinitionInput->nBufferCountMin = INPORT_MIN_BUFFER_COUNT;
    paramPortDefinitionInput->nBufferSize = INPORT_BUFFER_SIZE;
    paramPortDefinitionInput->format.video.cMIMEType = (OMX_STRING)VP9_MIME_TYPE;
    paramPortDefinitionInput->format.video.eCompressionFormat = OMX_VIDEO_CodingVP9;
    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::ProcessorInit(void) {
    unsigned int buff[MAX_GRAPHIC_BUFFER_NUM];
    unsigned int i;
    int bufferSize = mGraphicBufferParam.graphicBufferStride *
                          mGraphicBufferParam.graphicBufferHeight * 1.5;
    int bufferStride = mGraphicBufferParam.graphicBufferStride;

    for (i = 0; i < mOMXBufferHeaderTypePtrNum; i++ ) {
        OMX_BUFFERHEADERTYPE *buf_hdr = mOMXBufferHeaderTypePtrArray[i];
        buff[i] = (unsigned int)(buf_hdr->pBuffer);
    }

    mLibHandle = dlopen("libDecoderVP9Hybrid.so", RTLD_NOW);
    if (mLibHandle == NULL) {
        LOGE("dlopen libDecoderVP9Hybrid.so fail\n");
        return OMX_ErrorBadParameter;
    } else {
        LOGI("dlopen libDecoderVP9Hybrid.so successfully\n");
    }
    mOpenDecoder = (OpenFunc)dlsym(mLibHandle, "Decoder_Open");
    mCloseDecoder = (CloseFunc)dlsym(mLibHandle, "Decoder_Close");
    mInitDecoder = (InitFunc)dlsym(mLibHandle, "Decoder_Init");
    mSingalRenderDone = (SingalRenderDoneFunc)dlsym(mLibHandle, "Decoder_SingalRenderDone");
    mDecoderDecode = (DecodeFunc)dlsym(mLibHandle, "Decoder_Decode");
    mCheckBufferAvailable = (IsBufferAvailableFunc)dlsym(mLibHandle, "Decoder_IsBufferAvailable");
    mGetOutput = (GetOutputFunc)dlsym(mLibHandle, "Decoder_GetOutput");
    if (mOpenDecoder == NULL || mCloseDecoder == NULL
        || mInitDecoder == NULL || mSingalRenderDone == NULL
        || mDecoderDecode == NULL || mCheckBufferAvailable == NULL
        || mGetOutput == NULL) {
        return OMX_ErrorBadParameter;
    }

    if (mOpenDecoder(&mCtx,&mHybridCtx) == false) {
        LOGE("open hybrid Decoder fail\n");
        return OMX_ErrorBadParameter;
    }

    mInitDecoder(mHybridCtx,bufferSize,bufferStride,mOMXBufferHeaderTypePtrNum, buff);
    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::ProcessorDeinit(void) {
    mCloseDecoder(mCtx,mHybridCtx);
    mOMXBufferHeaderTypePtrNum = 0;
    if (mLibHandle != NULL) {
        dlclose(mLibHandle);
        mLibHandle = NULL;
    }
    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::ProcessorStop(void) {
    return OMXComponentCodecBase::ProcessorStop();
}

OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::ProcessorFlush(OMX_U32) {
    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::ProcessorPreFillBuffer(OMX_BUFFERHEADERTYPE* buffer) {
    unsigned int handle = (unsigned int)buffer->pBuffer;
    unsigned int i = 0;

    if (buffer->nOutputPortIndex == OUTPORT_INDEX){
        mSingalRenderDone(handle);
    }
    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::ProcessorProcess(
        OMX_BUFFERHEADERTYPE ***pBuffers,
        buffer_retain_t *retains,
        OMX_U32)
{
    OMX_ERRORTYPE ret;
    OMX_BUFFERHEADERTYPE *inBuffer = *pBuffers[INPORT_INDEX];
    OMX_BUFFERHEADERTYPE *outBuffer = *pBuffers[OUTPORT_INDEX];

    if (inBuffer->pBuffer == NULL) {
        LOGE("Buffer to decode is empty.");
        return OMX_ErrorBadParameter;
    }

    if (inBuffer->nFlags & OMX_BUFFERFLAG_CODECCONFIG) {
        LOGI("Buffer has OMX_BUFFERFLAG_CODECCONFIG flag.");
    }

    if (inBuffer->nFlags & OMX_BUFFERFLAG_DECODEONLY) {
        LOGW("Buffer has OMX_BUFFERFLAG_DECODEONLY flag.");
    }

    if (inBuffer->nFlags & OMX_BUFFERFLAG_EOS) {
        if (inBuffer->nFilledLen == 0) {
            (*pBuffers[OUTPORT_INDEX])->nFilledLen = 0;
            (*pBuffers[OUTPORT_INDEX])->nFlags = OMX_BUFFERFLAG_EOS;
            return OMX_ErrorNone;
        }
    }

#if LOG_TIME == 1
    struct timeval tv_start, tv_end;
    int32_t time_ms;
    gettimeofday(&tv_start,NULL);
#endif
    if (mDecoderDecode(mCtx,mHybridCtx,inBuffer->pBuffer + inBuffer->nOffset,inBuffer->nFilledLen) == false) {
        LOGE("on2 decoder failed to decode frame.");
        return OMX_ErrorBadParameter;
    }

#if LOG_TIME == 1
    gettimeofday(&tv_end,NULL);
    time_ms = (int32_t)(tv_end.tv_sec - tv_start.tv_sec) * 1000 + (int32_t)(tv_end.tv_usec - tv_start.tv_usec)/1000;
    LOGI("vpx_codec_decode: %d ms", time_ms);
#endif

    ret = FillRenderBuffer(pBuffers[OUTPORT_INDEX],
                           &retains[OUTPORT_INDEX],
                           ((*pBuffers[INPORT_INDEX]))->nFlags);

    if (ret == OMX_ErrorNone) {
        (*pBuffers[OUTPORT_INDEX])->nTimeStamp = inBuffer->nTimeStamp;
    }

    bool inputEoS = ((*pBuffers[INPORT_INDEX])->nFlags & OMX_BUFFERFLAG_EOS);
    bool outputEoS = ((*pBuffers[OUTPORT_INDEX])->nFlags & OMX_BUFFERFLAG_EOS);
    // if output port is not eos, retain the input buffer
    // until all the output buffers are drained.
    if (inputEoS && !outputEoS) {
        retains[INPORT_INDEX] = BUFFER_RETAIN_GETAGAIN;
        // the input buffer is retained for draining purpose.
        // Set nFilledLen to 0 so buffer will not be decoded again.
        (*pBuffers[INPORT_INDEX])->nFilledLen = 0;
    }

    if (ret == OMX_ErrorNotReady) {
        retains[OUTPORT_INDEX] = BUFFER_RETAIN_GETAGAIN;
        ret = OMX_ErrorNone;
    }

    return ret;
}

static int ALIGN(int x, int y) {
    // y must be a power of 2.
    return (x + y - 1) & ~(y - 1);
}

OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::FillRenderBuffer(OMX_BUFFERHEADERTYPE **pBuffer,
                                                      buffer_retain_t *retain,
                                                      OMX_U32 inportBufferFlags)
{
    OMX_BUFFERHEADERTYPE *buffer = *pBuffer;
    OMX_BUFFERHEADERTYPE *buffer_orign = buffer;

    OMX_ERRORTYPE ret = OMX_ErrorNone;

    if (mWorkingMode != GRAPHICBUFFER_MODE) {
        LOGE("Working Mode is not GRAPHICBUFFER_MODE");
        ret = OMX_ErrorBadParameter;
    }
    int fb_index = mGetOutput(mCtx);
    if (fb_index == -1) {
        LOGE("vpx_codec_get_frame return NULL.");
        return OMX_ErrorNotReady;
    }

    buffer = *pBuffer = mOMXBufferHeaderTypePtrArray[fb_index];

    size_t dst_y_size = mGraphicBufferParam.graphicBufferStride *
                        mGraphicBufferParam.graphicBufferHeight;
    size_t dst_c_stride = ALIGN(mGraphicBufferParam.graphicBufferStride / 2, 16);
    size_t dst_c_size = dst_c_stride * mGraphicBufferParam.graphicBufferHeight / 2;
    buffer->nOffset = 0;
    buffer->nFilledLen = sizeof(OMX_U8*);
    if (inportBufferFlags & OMX_BUFFERFLAG_EOS) {
        buffer->nFlags = OMX_BUFFERFLAG_EOS;
    }

    if (buffer_orign != buffer) {
        *retain = BUFFER_RETAIN_OVERRIDDEN;
    }

    ret = OMX_ErrorNone;

    return ret;

}

OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::PrepareConfigBuffer(VideoConfigBuffer *) {
    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::PrepareDecodeBuffer(OMX_BUFFERHEADERTYPE *,
                                                         buffer_retain_t *,
                                                         VideoDecodeBuffer *) {
    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::BuildHandlerList(void) {
    OMXVideoDecoderBase::BuildHandlerList();
    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::GetParamVideoVp9(OMX_PTR) {
    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::SetParamVideoVp9(OMX_PTR) {
    return OMX_ErrorNone;
}

OMX_COLOR_FORMATTYPE OMXVideoDecoderVP9Hybrid::GetOutputColorFormat(int) {
    LOGV("Output color format is HAL_PIXEL_FORMAT_YV12.");
    return (OMX_COLOR_FORMATTYPE)HAL_PIXEL_FORMAT_YV12;
}

OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::GetDecoderOutputCropSpecific(OMX_PTR pStructure) {

    OMX_ERRORTYPE ret = OMX_ErrorNone;
    OMX_CONFIG_RECTTYPE *rectParams = (OMX_CONFIG_RECTTYPE *)pStructure;

    CHECK_TYPE_HEADER(rectParams);

    if (rectParams->nPortIndex != OUTPORT_INDEX) {
        return OMX_ErrorUndefined;
    }

    const OMX_PARAM_PORTDEFINITIONTYPE *paramPortDefinitionInput
                                      = this->ports[INPORT_INDEX]->GetPortDefinition();

    rectParams->nLeft = VPX_DECODE_BORDER;
    rectParams->nTop = VPX_DECODE_BORDER;
    rectParams->nWidth = paramPortDefinitionInput->format.video.nFrameWidth;
    rectParams->nHeight = paramPortDefinitionInput->format.video.nFrameHeight;

    return ret;
}

OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::GetNativeBufferUsageSpecific(OMX_PTR pStructure) {
    OMX_ERRORTYPE ret;
    android::GetAndroidNativeBufferUsageParams *param =
        (android::GetAndroidNativeBufferUsageParams*)pStructure;
    CHECK_TYPE_HEADER(param);

    param->nUsage |= (GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_SW_READ_NEVER \
                     | GRALLOC_USAGE_SW_WRITE_OFTEN | GRALLOC_USAGE_EXTERNAL_DISP);
    return OMX_ErrorNone;

}
OMX_ERRORTYPE OMXVideoDecoderVP9Hybrid::SetNativeBufferModeSpecific(OMX_PTR pStructure) {
    OMX_ERRORTYPE ret;
    android::EnableAndroidNativeBuffersParams *param =
        (android::EnableAndroidNativeBuffersParams*)pStructure;

    CHECK_TYPE_HEADER(param);
    CHECK_PORT_INDEX_RANGE(param);
    CHECK_SET_PARAM_STATE();

    if (!param->enable) {
        mWorkingMode = RAWDATA_MODE;
        return OMX_ErrorNone;
    }
    mWorkingMode = GRAPHICBUFFER_MODE;
    PortVideo *port = NULL;
    port = static_cast<PortVideo *>(this->ports[OUTPORT_INDEX]);

    OMX_PARAM_PORTDEFINITIONTYPE port_def;
    memcpy(&port_def,port->GetPortDefinition(),sizeof(port_def));
    port_def.nBufferCountMin = mNativeBufferCount;
    port_def.nBufferCountActual = mNativeBufferCount;
    port_def.format.video.cMIMEType = (OMX_STRING)VA_VED_RAW_MIME_TYPE;
    port_def.format.video.eColorFormat = (OMX_COLOR_FORMATTYPE)OMX_INTEL_COLOR_FormatYUV420PackedSemiPlanar;
    // add borders for libvpx decode need.
    port_def.format.video.nFrameHeight += VPX_DECODE_BORDER * 2;
    port_def.format.video.nFrameWidth += VPX_DECODE_BORDER * 2;
    // make heigth 32bit align
    port_def.format.video.nFrameHeight = (port_def.format.video.nFrameHeight + 0x1f) & ~0x1f;
    port_def.format.video.eColorFormat = GetOutputColorFormat(port_def.format.video.nFrameWidth);
    port->SetPortDefinition(&port_def,true);

     return OMX_ErrorNone;
}


bool OMXVideoDecoderVP9Hybrid::IsAllBufferAvailable(void) {
    bool b = ComponentBase::IsAllBufferAvailable();
    if (b == false) {
        return false;
    }

    PortVideo *port = NULL;
    port = static_cast<PortVideo *>(this->ports[OUTPORT_INDEX]);
    const OMX_PARAM_PORTDEFINITIONTYPE* port_def = port->GetPortDefinition();
     // if output port is disabled, retain the input buffer
    if (!port_def->bEnabled) {
        return false;
    }
    return mCheckBufferAvailable();
}

DECLARE_OMX_COMPONENT("OMX.Intel.VideoDecoder.VP9.hybrid", "video_decoder.vp9", OMXVideoDecoderVP9Hybrid);
