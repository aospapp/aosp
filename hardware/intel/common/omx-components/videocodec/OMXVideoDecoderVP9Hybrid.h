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



#ifndef OMX_VIDEO_DECODER_VP9_HYBRID_H_
#define OMX_VIDEO_DECODER_VP9_HYBRID_H_

#include "OMXVideoDecoderBase.h"
#include <dlfcn.h> 
#define VPX_DECODE_BORDER 0

class OMXVideoDecoderVP9Hybrid : public OMXVideoDecoderBase {
public:
    OMXVideoDecoderVP9Hybrid();
    virtual ~OMXVideoDecoderVP9Hybrid();

protected:
    virtual OMX_ERRORTYPE InitInputPortFormatSpecific(OMX_PARAM_PORTDEFINITIONTYPE *paramPortDefinitionInput);
    virtual OMX_ERRORTYPE ProcessorInit(void);
    virtual OMX_ERRORTYPE ProcessorDeinit(void);
    virtual OMX_ERRORTYPE ProcessorStop(void);
    virtual OMX_ERRORTYPE ProcessorFlush(OMX_U32 portIndex);
    virtual OMX_ERRORTYPE ProcessorProcess(
            OMX_BUFFERHEADERTYPE ***pBuffers,
            buffer_retain_t *retains,
            OMX_U32 numberBuffers);

    virtual OMX_ERRORTYPE ProcessorPreFillBuffer(OMX_BUFFERHEADERTYPE* buffer);
    virtual bool IsAllBufferAvailable(void);

    virtual OMX_ERRORTYPE PrepareConfigBuffer(VideoConfigBuffer *p);
    virtual OMX_ERRORTYPE PrepareDecodeBuffer(OMX_BUFFERHEADERTYPE *buffer, buffer_retain_t *retain, VideoDecodeBuffer *p);

    virtual OMX_ERRORTYPE BuildHandlerList(void);

    virtual OMX_ERRORTYPE FillRenderBuffer(OMX_BUFFERHEADERTYPE **pBuffer,  buffer_retain_t *retain, OMX_U32 inportBufferFlags);

    virtual OMX_COLOR_FORMATTYPE GetOutputColorFormat(int width);
    virtual OMX_ERRORTYPE GetDecoderOutputCropSpecific(OMX_PTR pStructure);
    virtual OMX_ERRORTYPE GetNativeBufferUsageSpecific(OMX_PTR pStructure);
    virtual OMX_ERRORTYPE SetNativeBufferModeSpecific(OMX_PTR pStructure);

    DECLARE_HANDLER(OMXVideoDecoderVP9Hybrid, ParamVideoVp9);

private:
    void *mCtx;
    void *mHybridCtx;
    void *mLibHandle;
    typedef bool (*OpenFunc)(void ** , void **);
    typedef bool (*InitFunc)(void *,unsigned int, unsigned int, int, unsigned int *);
    typedef bool (*CloseFunc)(void *, void *);
    typedef bool (*SingalRenderDoneFunc)(unsigned int);
    typedef bool (*DecodeFunc)(void *, void *, unsigned char *, unsigned int);
    typedef bool (*IsBufferAvailableFunc)();	
    typedef int (*GetOutputFunc)(void *);
    OpenFunc mOpenDecoder;
    InitFunc mInitDecoder;
    CloseFunc mCloseDecoder;
    SingalRenderDoneFunc mSingalRenderDone;
    DecodeFunc mDecoderDecode;
    IsBufferAvailableFunc mCheckBufferAvailable;
    GetOutputFunc mGetOutput;
    enum {
        // OMX_PARAM_PORTDEFINITIONTYPE
        INPORT_MIN_BUFFER_COUNT = 1,
        INPORT_ACTUAL_BUFFER_COUNT = 5,
        INPORT_BUFFER_SIZE = 1382400,
        OUTPORT_NATIVE_BUFFER_COUNT = 12, // 8 reference + 1 current + 3 for asynchronized mode
    };

};

#endif

