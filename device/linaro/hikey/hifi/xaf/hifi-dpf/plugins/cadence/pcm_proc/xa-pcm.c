/*******************************************************************************
* Copyright (C) 2018 Cadence Design Systems, Inc.
* 
* Permission is hereby granted, free of charge, to any person obtaining
* a copy of this software and associated documentation files (the
* "Software"), to use this Software with Cadence processor cores only and 
* not with any other processors and platforms, subject to
* the following conditions:
* 
* The above copyright notice and this permission notice shall be included
* in all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
* MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
* IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
* CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
* TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
* SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

******************************************************************************/

/*******************************************************************************
 * xa-pcm.c
 *
 * PCM format converter plugin
 *
 ******************************************************************************/

#define MODULE_TAG                      PCM

/*******************************************************************************
 * Includes
 ******************************************************************************/

#include "xf-plugin.h"
#include "xa-audio-decoder-api.h"
#include "xa-pcm-api.h"

#include <fcntl.h>

/*******************************************************************************
 * Tracing configuration
 ******************************************************************************/

TRACE_TAG(INIT, 1);
TRACE_TAG(PROCESS, 0);

/*******************************************************************************
 * Local typedefs
 ******************************************************************************/

/* ...API structure */
typedef struct XAPcmCodec
{
    /* ...codec state */
    u32                 state;

    /* ...sampling rate of input/output signal (informative only) */
    u32                 sample_rate;

    /* ...number of input/output channels */
    u8                  in_channels, out_channels;
    
    /* ...PCM sample width of input/output streams */
    u8                  in_pcm_width, out_pcm_width;

    /* ...input/output stride size */
    u8                  in_stride, out_stride;
    
    /* ...channel routing map between input and output */
    u32                 chan_routing;

    /* ...data processing hook */
    XA_ERRORCODE      (*process)(struct XAPcmCodec *);

    /* ...number of samples in input/output buffers */
    u32                 insize, outsize;

    /* ...input/output memory indices */
    u32                 input_idx, output_idx;
    
    /* ...input/output buffers passed from/to caller */
    void               *input, *output;

    /* ...number of input bytes consumed/produced */
    u32                 consumed, produced;

    /* ...debug - file handles */
    int                 f_input, f_output;
    
}   XAPcmCodec;

/*******************************************************************************
 * Local execution flags
 ******************************************************************************/

#define XA_PCM_FLAG_PREINIT_DONE        (1 << 0)
#define XA_PCM_FLAG_POSTINIT_DONE       (1 << 1)
#define XA_PCM_FLAG_RUNNING             (1 << 2)
#define XA_PCM_FLAG_EOS                 (1 << 3)
#define XA_PCM_FLAG_COMPLETE            (1 << 4)

/*******************************************************************************
 * Local constants definitions
 ******************************************************************************/

/* ...process at most 1024 samples per call */
#define XA_PCM_MAX_SAMPLES              1024

/*******************************************************************************
 * Internal processing functions
 ******************************************************************************/

/* ...identity translation of PCM16/24 */
static XA_ERRORCODE xa_pcm_do_execute_copy(XAPcmCodec *d)
{
    u32     n = d->insize;
    u8      k = d->in_channels;
    u32     length = n * k * (d->in_pcm_width == 16 ? 2 : 4);
    s16    *input = d->input, *output = d->output;

    TRACE(PROCESS, _b("Copy PCM%d %p to %p (%u samples)"), d->in_pcm_width, input, output, n);
    
    /* ...check if we have all data setup */
    XF_CHK_ERR(input && n && output, XA_PCM_EXEC_FATAL_STATE);
    
    /* ...copy the samples without any processing */
    memcpy(output, input, length);

    /* ...set number of consumed/produced bytes */
    d->consumed = length;
    d->produced = length;

    /* ...reset input buffer length */
    d->insize = 0;

    /* ...copy input to output */
    return XA_NO_ERROR;
}

/* ...data processing for PCM16, channel mapping case */
static XA_ERRORCODE xa_pcm_do_execute_pcm16_chmap(XAPcmCodec *d)
{
    u32     n = d->insize, i;
    u8      k = d->in_channels, j;
    u32     chmap = d->chan_routing, map;
    s16    *input = d->input, *output = d->output;
    u32     length = n * k * (d->in_pcm_width == 16 ? 2 : 4);

    TRACE(PROCESS, _b("Map PCM16 %p to %p (%u samples, map: %X)"), input, output, n, chmap);    
    
    /* ...check if we have all data setup */
    XF_CHK_ERR(input && n && output, XA_PCM_EXEC_FATAL_STATE);

#if 0
    /* ...convert individual samples (that function could be CPU-optimized - tbd) */
    for (i = 0; i < n; i++, input += k)
    {
        /* ...process individual channels in a sample */
        for (j = 0, map = chmap; j < k; j++, map >>= 4)
        {
            u8      m = map & 0xF;
            
            /* ...fill output channel (zero unused channel) */
            *output++ = (m < 8 ? input[m] : 0);
        }
    }

    /* ...set number of consumed/produced bytes */
    d->consumed = (u32)((u8 *)input - (u8 *)d->input);
    d->produced = (u32)((u8 *)output - (u8 *)d->output);
#else
    memcpy(output, input, length);
    /* ...set number of consumed/produced bytes */
    d->consumed = length;
    d->produced = length;
#endif
    /* ...reset input buffer length */
    d->insize = 0;

    /* ...copy input to output */
    return XA_NO_ERROR;
}

/* ...data processing for PCM24/PCM32, channel mapping case */
static XA_ERRORCODE xa_pcm_do_execute_pcm24_chmap(XAPcmCodec *d)
{
    u32     n = d->insize, i;
    u8      k = d->in_channels, j;
    u32     chmap = d->chan_routing, map;
    s32    *input = d->input, *output = d->output;

    TRACE(PROCESS, _b("Map PCM24 %p to %p (%u samples, map: %X)"), input, output, n, chmap);

    /* ...check if we have all data setup */
    XF_CHK_ERR(input && n && output, XA_PCM_EXEC_FATAL_STATE);
    
    /* ...convert individual samples (that function could be CPU-optimized - tbd) */
    for (i = 0; i < n; i++, input += k)
    {
        /* ...process individual channels in a sample */
        for (j = 0, map = chmap; j < k; j++, map >>= 4)
        {
            u8      m = map & 0xF;
            
            /* ...fill output channel (zero unused channel) */
            *output++ = (m < 8 ? input[m] : 0);
        }
    }

    /* ...set number of consumed/produced bytes */
    d->consumed = (u32)((u8 *)input - (u8 *)d->input);
    d->produced = (u32)((u8 *)output - (u8 *)d->output);

    /* ...reset input buffer length */
    d->insize = 0;

    /* ...copy input to output */
    return XA_NO_ERROR;
}

/* ...convert multichannel 24-bit PCM to 16-bit PCM with channel mapping */
static XA_ERRORCODE xa_pcm_do_execute_pcm24_to_pcm16(XAPcmCodec *d)
{
    u32     n = d->insize, i;
    u8      k = d->in_channels, j;
    u32     chmap = d->chan_routing, map;
    s32    *input = d->input;
    s16    *output = d->output;

    TRACE(PROCESS, _b("Convert PCM24 %p to PCM16 %p (%u samples, map: %X)"), input, output, n, chmap);
    
    /* ...check if we have all data setup */
    XF_CHK_ERR(input && n && output, XA_PCM_EXEC_FATAL_STATE);

    /* ...convert individual samples (that function could be CPU-optimized - tbd) */
    for (i = 0; i < n; i++, input += k)
    {
        /* ...process individual channels in a sample */
        for (j = 0, map = chmap; j < k; j++, map >>= 4)
        {
            u8      m = map & 0xF;

            /* ...convert and zero out unused channels */
            *output++ = (m < 8 ? input[m] >> 16 : 0);
        }
    }

    /* ...set number of consumed/produced bytes */
    d->consumed = (u32)((u8 *)input - (u8 *)d->input);
    d->produced = (u32)((u8 *)output - (u8 *)d->output);

    /* ...dump output data */
    //BUG(write(d->f_input, d->input, d->consumed) != d->consumed, _x("%m"));
    //BUG(write(d->f_output, d->output, d->produced) != d->produced, _x("%m"));
    
    /* ...reset input buffer length (tbd - need that?) */
    d->insize = 0;
    
    /* ...copy input to output */
    return XA_NO_ERROR;    
}

/* ...convert multichannel 16-bit PCM to 24-bit PCM with channel mapping */
static XA_ERRORCODE xa_pcm_do_execute_pcm16_to_pcm24(XAPcmCodec *d)
{
    u32     n = d->insize, i;
    u8      k = d->in_channels, j;
    u32     chmap = d->chan_routing, map;
    s16    *input = d->input;
    s32    *output = d->output;

    TRACE(PROCESS, _b("Convert PCM16 %p to PCM24 %p (%u samples, map: %X)"), input, output, n, chmap);
    
    /* ...check if we have all data setup */
    XF_CHK_ERR(input && n && output, XA_PCM_EXEC_FATAL_STATE);

    /* ...convert individual samples (that function could be CPU-optimized - tbd) */
    for (i = 0; i < n; i++, input += k)
    {
        /* ...process individual channels in a sample */
        for (j = 0, map = chmap; j < k; j++, map >>= 4)
        {
            u8      m = map & 0xF;

            /* ...convert and zero out unused channels */
            *output++ = (m < 8 ? input[m] << 16 : 0);
        }
    }
    
    /* ...set number of consumed/produced bytes */
    d->consumed = (u32)((u8 *)input - (u8 *)d->input);
    d->produced = (u32)((u8 *)output - (u8 *)d->output);
    
    /* ...reset input buffer length (tbd - need that?) */
    d->insize = 0;
    
    /* ...copy input to output */
    return XA_NO_ERROR;    
}

/* ...determine if we need to do a channel routing */
static inline int xa_pcm_is_identity_mapping(u32 chmap, u8 k)
{
    u8      j;
    
    for (j = 0; j < k; j++, chmap >>= 4)
        if ((chmap & 0xF) != j)
            return 0;
    
    return 1;
}

/* ...runtime initialization */
static inline XA_ERRORCODE xa_pcm_do_runtime_init(XAPcmCodec *d)
{
    u8      in_width = d->in_pcm_width, out_width = d->out_pcm_width;
    u8      in_ch = d->in_channels, out_ch = d->out_channels;
    u32     chmap = d->chan_routing;
    
    /* ...check for supported processing schemes */
    if (in_width == out_width)
    {
        /* ...check if we need to do a channel mapping */
        if (in_ch != out_ch || !xa_pcm_is_identity_mapping(chmap, in_ch))
        {
            /* ...mapping is needed */
            d->process = (in_width == 16 ? xa_pcm_do_execute_pcm16_chmap : xa_pcm_do_execute_pcm24_chmap);
        }
        else
        {
            /* ...setup identity translation */
            d->process = xa_pcm_do_execute_copy;
        }
    }
    else
    {
        /* ...samples converion is required */
        d->process = (in_width == 16 ? xa_pcm_do_execute_pcm16_to_pcm24 : xa_pcm_do_execute_pcm24_to_pcm16);
    }
    
    /* ...mark the runtime initialization is completed */
    d->state = XA_PCM_FLAG_PREINIT_DONE | XA_PCM_FLAG_POSTINIT_DONE | XA_PCM_FLAG_RUNNING;

    TRACE(INIT, _b("PCM format converter initialized: PCM%u -> PCM%u, ich=%u, och=%u, map=%X"), in_width, out_width, in_ch, out_ch, chmap);

    return XA_NO_ERROR;
}

/*******************************************************************************
 * Commands processing
 ******************************************************************************/

/* ...standard codec initialization routine */
static XA_ERRORCODE xa_pcm_get_api_size(XAPcmCodec *d, WORD32 i_idx, pVOID pv_value)
{
    /* ...return API structure size */
    *(WORD32 *)pv_value = sizeof(*d);
    
    return XA_NO_ERROR;
}

/* ...standard codec initialization routine */
static XA_ERRORCODE xa_pcm_init(XAPcmCodec *d, WORD32 i_idx, pVOID pv_value)
{
    /* ...validity check */
    XF_CHK_ERR(d, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...process particular initialization type */
    switch (i_idx)
    {
    case XA_CMD_TYPE_INIT_API_PRE_CONFIG_PARAMS:
    {
        /* ...pre-configuration initialization; reset internal data */
        memset(d, 0, sizeof(*d));

        /* ...set default parameters */
        d->in_pcm_width = d->out_pcm_width = 16;
        d->in_channels = d->out_channels = 2;
        d->chan_routing = (0 << 0) | (1 << 1);
        d->sample_rate = 48000;
        
        /* ...open debug files */
        //BUG((d->f_input = open("pcm-in.dat", O_WRONLY | O_CREAT, 0664)) < 0, _x("%m"));
        //BUG((d->f_output = open("pcm-out.dat", O_WRONLY | O_CREAT, 0664)) < 0, _x("%m"));

        /* ...mark pre-initialization is done */
        d->state = XA_PCM_FLAG_PREINIT_DONE;
        
        return XA_NO_ERROR;
    }
    
    case XA_CMD_TYPE_INIT_API_POST_CONFIG_PARAMS:
    {
        /* ...post-configuration initialization (all parameters are set) */
        XF_CHK_ERR(d->state & XA_PCM_FLAG_PREINIT_DONE, XA_API_FATAL_INVALID_CMD_TYPE);

        /* ...calculate input sample stride size */
        d->in_stride = d->in_channels * (d->in_pcm_width == 16 ? 2 : 4);
        d->out_stride = d->out_channels * (d->out_pcm_width == 16 ? 2 : 4);

        /* ...mark post-initialization is complete */
        d->state |= XA_PCM_FLAG_POSTINIT_DONE;
        
        return XA_NO_ERROR;
    }

    case XA_CMD_TYPE_INIT_PROCESS:
    {
        /* ...run-time initialization process; make sure post-init is complete */
        XF_CHK_ERR(d->state & XA_PCM_FLAG_POSTINIT_DONE, XA_API_FATAL_INVALID_CMD_TYPE);

        /* ...initialize runtime for specified transformation function */
        return xa_pcm_do_runtime_init(d);
    }
    
    case XA_CMD_TYPE_INIT_DONE_QUERY:
    {
        /* ...check for runtime initialization completion; maske usre post-init is complete */
        XF_CHK_ERR(d->state & XA_PCM_FLAG_POSTINIT_DONE, XA_API_FATAL_INVALID_CMD_TYPE);

        /* ...put current status */
        *(WORD32 *)pv_value = (d->state & XA_PCM_FLAG_RUNNING ? 1 : 0);

        return XA_NO_ERROR;
    }

    default:
        /* ...unrecognized command */
        return XF_CHK_ERR(0, XA_API_FATAL_INVALID_CMD_TYPE);
    }
}

/* ...set configuration parameter */
static XA_ERRORCODE xa_pcm_set_config_param(XAPcmCodec *d, WORD32 i_idx, pVOID pv_value)
{
    WORD32      i_value;
    
    /* ...validity check */
    XF_CHK_ERR(d && pv_value, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...configuration is allowed only in PRE-CONFIG state */
    XF_CHK_ERR(d->state == XA_PCM_FLAG_PREINIT_DONE, XA_PCM_CONFIG_NONFATAL_STATE);

    /* ...get integer parameter value */
    i_value = *(WORD32 *)pv_value;

    /* ...process individual configuration parameter */
    switch (i_idx)
    {
    case XA_CODEC_CONFIG_PARAM_SAMPLE_RATE:
    case XA_PCM_CONFIG_PARAM_SAMPLE_RATE:
        /* ...accept any sampling rate */
        d->sample_rate = (u32)i_value;
        return XA_NO_ERROR;

    case XA_PCM_CONFIG_PARAM_IN_PCM_WIDTH:
        /* ...return input sample bit-width */
        XF_CHK_ERR(i_value == 16 || i_value == 24, XA_PCM_CONFIG_NONFATAL_RANGE);
        d->in_pcm_width = (u8)i_value;
        return XA_NO_ERROR;

    case XA_PCM_CONFIG_PARAM_IN_CHANNELS:
        /* ...support at most 8-channels stream */
        XF_CHK_ERR(i_value > 0 && i_value <= 8, XA_PCM_CONFIG_NONFATAL_RANGE);
        d->in_channels = (u8)i_value;
        return XA_NO_ERROR;
        
    case XA_CODEC_CONFIG_PARAM_PCM_WIDTH:
    case XA_PCM_CONFIG_PARAM_OUT_PCM_WIDTH:
        /* ...we only support PCM16 and PCM24 */
        XF_CHK_ERR(i_value == 16 || i_value == 24, XA_PCM_CONFIG_NONFATAL_RANGE);
        d->out_pcm_width = (u8)i_value;
        return XA_NO_ERROR;

    case XA_CODEC_CONFIG_PARAM_CHANNELS:
    case XA_PCM_CONFIG_PARAM_OUT_CHANNELS:
        /* ...support at most 8-channels stream */
        XF_CHK_ERR(i_value > 0 && i_value <= 8, XA_API_FATAL_INVALID_CMD_TYPE);
        d->out_channels = (u8)i_value;
        return XA_NO_ERROR;

    case XA_PCM_CONFIG_PARAM_CHANROUTING:
        /* ...accept any channel routing mask */
        d->chan_routing = (u32)i_value;
        return XA_NO_ERROR;

    default:
        /* ...unrecognized parameter */
        return XF_CHK_ERR(0, XA_API_FATAL_INVALID_CMD_TYPE);
    }
}

/* ...retrieve configuration parameter */
static XA_ERRORCODE xa_pcm_get_config_param(XAPcmCodec *d, WORD32 i_idx, pVOID pv_value)
{
    /* ...validity check */
    XF_CHK_ERR(d && pv_value, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...make sure pre-configuration is completed */
    XF_CHK_ERR(d->state & XA_PCM_FLAG_PREINIT_DONE, XA_PCM_CONFIG_NONFATAL_STATE);

    /* ...process individual parameter */
    switch (i_idx)
    {
    case XA_CODEC_CONFIG_PARAM_SAMPLE_RATE:
    case XA_PCM_CONFIG_PARAM_SAMPLE_RATE:
        /* ...return output sampling frequency */
        *(WORD32 *)pv_value = d->sample_rate;
        return XA_NO_ERROR;

    case XA_PCM_CONFIG_PARAM_IN_PCM_WIDTH:
        /* ...return input sample bit-width */
        *(WORD32 *)pv_value = d->in_pcm_width;
        return XA_NO_ERROR;

    case XA_PCM_CONFIG_PARAM_IN_CHANNELS:
        /* ...return number of input channels */
        *(WORD32 *)pv_value = d->in_channels;
        return XA_NO_ERROR;

    case XA_CODEC_CONFIG_PARAM_PCM_WIDTH:
    case XA_PCM_CONFIG_PARAM_OUT_PCM_WIDTH:
        /* ...return output sample bit-width */
        *(WORD32 *)pv_value = d->out_pcm_width;
        return XA_NO_ERROR;

    case XA_CODEC_CONFIG_PARAM_CHANNELS:
    case XA_PCM_CONFIG_PARAM_OUT_CHANNELS:
        /* ...return number of output channels */
        *(WORD32 *)pv_value = d->out_channels;
        return XA_NO_ERROR;

    case XA_PCM_CONFIG_PARAM_CHANROUTING:
        /* ...return current channel routing mask */
        *(WORD32 *)pv_value = d->chan_routing;
        return XA_NO_ERROR;

    default:
        /* ...unrecognized parameter */
        return XF_CHK_ERR(0, XA_API_FATAL_INVALID_CMD_TYPE);
    }
}

/* ...execution command */
static XA_ERRORCODE xa_pcm_execute(XAPcmCodec *d, WORD32 i_idx, pVOID pv_value)
{
    /* ...basic validity check */
    XF_CHK_ERR(d, XA_API_FATAL_INVALID_CMD_TYPE);

   /* ...codec must be in running state */
    XF_CHK_ERR(d->state & XA_PCM_FLAG_RUNNING, XA_API_FATAL_INVALID_CMD_TYPE);
     
    /* ...process individual command type */
    switch (i_idx)
    {
    case XA_CMD_TYPE_DO_EXECUTE:
        /* ...do data processing (tbd - result code is bad) */
        if (d->insize != 0)
        {
            XF_CHK_ERR(!XA_ERROR_SEVERITY(d->process(d)), XA_PCM_EXEC_FATAL_STATE);
        }

        /* ...process end-of-stream condition */
        (d->state & XA_PCM_FLAG_EOS ? d->state ^= XA_PCM_FLAG_EOS | XA_PCM_FLAG_COMPLETE : 0);
        
        return XA_NO_ERROR;
        
    case XA_CMD_TYPE_DONE_QUERY:
        /* ...check if processing is complete */
        XF_CHK_ERR(pv_value, XA_API_FATAL_INVALID_CMD_TYPE);

        /* ...return completion status */
        *(WORD32 *)pv_value = (d->state & XA_PCM_FLAG_COMPLETE ? 1 : 0);

        return XA_NO_ERROR;
        
    case XA_CMD_TYPE_DO_RUNTIME_INIT:
        /* ...reset codec operation */
        return xa_pcm_do_runtime_init(d);
        
    default:
        /* ...unrecognized command */
        return XF_CHK_ERR(0, XA_API_FATAL_INVALID_CMD_TYPE);
    }
}

/* ...set number of input bytes */
static XA_ERRORCODE xa_pcm_set_input_bytes(XAPcmCodec *d, WORD32 i_idx, pVOID pv_value)
{
    u32     in_stride = d->in_stride;
    u32     insize;
    
    /* ...validity check */
    XF_CHK_ERR(d && pv_value, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...track index must be valid */
    XF_CHK_ERR(i_idx == 0, XA_API_FATAL_INVALID_CMD_TYPE);
    
    /* ...component must be initialized */
    XF_CHK_ERR(d->state & XA_PCM_FLAG_POSTINIT_DONE, XA_API_FATAL_INVALID_CMD_TYPE);
    
    /* ...input buffer must exist */
    XF_CHK_ERR(d->input, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...convert bytes into samples (don't like division, but still...) */
    insize = *(WORD32 *)pv_value / in_stride;

    /* ...make sure we have integral amount of samples */
    XF_CHK_ERR(*(WORD32 *)pv_value == insize * in_stride, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...limit input buffer size to maximal value*/
    d->insize = (insize > XA_PCM_MAX_SAMPLES ? XA_PCM_MAX_SAMPLES : insize);
 
    return XA_NO_ERROR;
}

/* ...get number of output bytes produced */
static XA_ERRORCODE xa_pcm_get_output_bytes(XAPcmCodec *d, WORD32 i_idx, pVOID pv_value)
{
    /* ...validity check */
    XF_CHK_ERR(d && pv_value, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...buffer index must be valid */
    XF_CHK_ERR(i_idx == 1, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...component must be initialized */
    XF_CHK_ERR(d->state & XA_PCM_FLAG_POSTINIT_DONE, XA_API_FATAL_INVALID_CMD_TYPE);
    
    /* ...output buffer must exist */
    XF_CHK_ERR(d->output, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...return number of produced bytes (and reset instantly? - tbd) */
    *(WORD32 *)pv_value = d->produced;

    return XA_NO_ERROR;
}

/* ...get number of consumed bytes */
static XA_ERRORCODE xa_pcm_get_curidx_input_buf(XAPcmCodec *d, WORD32 i_idx, pVOID pv_value)
{
    /* ...validity check */
    XF_CHK_ERR(d && pv_value, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...index must be valid */
    XF_CHK_ERR(i_idx == 0, XA_API_FATAL_INVALID_CMD_TYPE);
    
    /* ...input buffer must exist */
    XF_CHK_ERR(d->input, XA_PCM_EXEC_NONFATAL_INPUT);

    /* ...return number of bytes consumed */
    *(WORD32 *)pv_value = d->consumed;

    return XA_NO_ERROR;
}

/* ...end-of-stream processing */
static XA_ERRORCODE xa_pcm_input_over(XAPcmCodec *d, WORD32 i_idx, pVOID pv_value)
{
    /* ...validity check */
    XF_CHK_ERR(d, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...put end-of-stream flag */
    d->state |= XA_PCM_FLAG_EOS;
    
    TRACE(PROCESS, _b("Input-over-condition signalled"));

    return XA_NO_ERROR;
}

/* ..get total amount of data for memory tables */
static XA_ERRORCODE xa_pcm_get_memtabs_size(XAPcmCodec *d, WORD32 i_idx, pVOID pv_value)
{
    /* ...basic validity checks */
    XF_CHK_ERR(d && pv_value, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...check mixer is pre-initialized */
    XF_CHK_ERR(d->state & XA_PCM_FLAG_PREINIT_DONE, XA_API_FATAL_INVALID_CMD_TYPE);
    
    /* ...we have all our tables inside API structure */
    *(WORD32 *)pv_value = 0;
    
    return XA_NO_ERROR;
}

/* ...return total amount of memory buffers */
static XA_ERRORCODE xa_pcm_get_n_memtabs(XAPcmCodec *d, WORD32 i_idx, pVOID pv_value)
{
    /* ...basic validity checks */
    XF_CHK_ERR(d && pv_value, XA_API_FATAL_INVALID_CMD_TYPE);
    
    /* ...we have 1 input buffer and 1 output buffer */
    *(WORD32 *)pv_value = 1 + 1;
    
    return XA_NO_ERROR;
}

/* ...return memory type data */
static XA_ERRORCODE xa_pcm_get_mem_info_type(XAPcmCodec *d, WORD32 i_idx, pVOID pv_value)
{
    /* ...basic validity check */
    XF_CHK_ERR(d && pv_value, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...codec must be in post-init state */
    XF_CHK_ERR(d->state & XA_PCM_FLAG_POSTINIT_DONE, XA_API_FATAL_INVALID_CMD_TYPE);
    
    /* ...check buffer type */
    switch (i_idx)
    {
    case 0:
        *(WORD32 *)pv_value = XA_MEMTYPE_INPUT;
        return XA_NO_ERROR;

    case 1:
        *(WORD32 *)pv_value = XA_MEMTYPE_OUTPUT;
        return XA_NO_ERROR;
        
    default:
        return XF_CHK_ERR(0, XA_API_FATAL_INVALID_CMD_TYPE);
    }
}

/* ...return memory buffer size */
static XA_ERRORCODE xa_pcm_get_mem_info_size(XAPcmCodec *d, WORD32 i_idx, pVOID pv_value)
{
    /* ...basic validity check */
    XF_CHK_ERR(d && pv_value, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...codec must be in post-init state */
    XF_CHK_ERR(d->state & XA_PCM_FLAG_POSTINIT_DONE, XA_API_FATAL_INVALID_CMD_TYPE);    
    
    /* ...determine particular buffer */
    switch (i_idx)
    {
    case 0:
        /* ...input buffer size can be any */
        *(WORD32 *)pv_value = 0;
        return XA_NO_ERROR;
        
    case 1:
        /* ...output buffer size is dependent on stride */
        *(WORD32 *)pv_value = XA_PCM_MAX_SAMPLES * d->out_stride;
        return XA_NO_ERROR;

    default:
        /* ...invalid buffer index */
        return XF_CHK_ERR(0, XA_API_FATAL_INVALID_CMD_TYPE);
    }
}

/* ...return memory alignment data */
static XA_ERRORCODE xa_pcm_get_mem_info_alignment(XAPcmCodec *d, WORD32 i_idx, pVOID pv_value)
{
    /* ...basic validity check */
    XF_CHK_ERR(d && pv_value, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...codec must be in post-initialization state */
    XF_CHK_ERR(d->state & XA_PCM_FLAG_POSTINIT_DONE, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...all buffers are 4-bytes aligned */
    *(WORD32 *)pv_value = 4;
        
    return XA_NO_ERROR;
}

/* ...set memory pointer */
static XA_ERRORCODE xa_pcm_set_mem_ptr(XAPcmCodec *d, WORD32 i_idx, pVOID pv_value)
{
    /* ...basic validity check */
    XF_CHK_ERR(d, XA_API_FATAL_INVALID_CMD_TYPE);
    XF_CHK_ERR(pv_value, XA_API_FATAL_INVALID_CMD_TYPE);

    /* ...codec must be in post-initialized state */
    XF_CHK_ERR(d->state & XA_PCM_FLAG_POSTINIT_DONE, XA_API_FATAL_INVALID_CMD_TYPE);
    
    /* ...select memory buffer */
    switch (i_idx)
    {
    case 0:
        /* ...input buffer */
        d->input = pv_value;
        return XA_NO_ERROR;
        
    case 1:
        /* ...output buffer */
        d->output = pv_value;
        return XA_NO_ERROR;
        
    default:
        /* ...invalid index */
        return XF_CHK_ERR(0, XA_API_FATAL_INVALID_CMD_TYPE);
    }
}

/*******************************************************************************
 * API command hooks
 ******************************************************************************/

static XA_ERRORCODE (* const xa_pcm_api[])(XAPcmCodec *, WORD32, pVOID) = 
{
    [XA_API_CMD_GET_API_SIZE]           = xa_pcm_get_api_size,

    [XA_API_CMD_INIT]                   = xa_pcm_init, 
    [XA_API_CMD_SET_CONFIG_PARAM]       = xa_pcm_set_config_param,
    [XA_API_CMD_GET_CONFIG_PARAM]       = xa_pcm_get_config_param,

    [XA_API_CMD_EXECUTE]                = xa_pcm_execute,
    [XA_API_CMD_SET_INPUT_BYTES]        = xa_pcm_set_input_bytes,
    [XA_API_CMD_GET_OUTPUT_BYTES]       = xa_pcm_get_output_bytes,
    [XA_API_CMD_GET_CURIDX_INPUT_BUF]   = xa_pcm_get_curidx_input_buf,
    [XA_API_CMD_INPUT_OVER]             = xa_pcm_input_over,

    [XA_API_CMD_GET_MEMTABS_SIZE]       = xa_pcm_get_memtabs_size,
    [XA_API_CMD_GET_N_MEMTABS]          = xa_pcm_get_n_memtabs,
    [XA_API_CMD_GET_MEM_INFO_TYPE]      = xa_pcm_get_mem_info_type,
    [XA_API_CMD_GET_MEM_INFO_SIZE]      = xa_pcm_get_mem_info_size,
    [XA_API_CMD_GET_MEM_INFO_ALIGNMENT] = xa_pcm_get_mem_info_alignment,
    [XA_API_CMD_SET_MEM_PTR]            = xa_pcm_set_mem_ptr,
};

/* ...total numer of commands supported */
#define XA_PCM_API_COMMANDS_NUM     (sizeof(xa_pcm_api) / sizeof(xa_pcm_api[0]))

/*******************************************************************************
 * API entry point
 ******************************************************************************/

XA_ERRORCODE xa_pcm_codec(xa_codec_handle_t p_xa_module_obj, WORD32 i_cmd, WORD32 i_idx, pVOID pv_value)
{
    XAPcmCodec *d = (XAPcmCodec *) p_xa_module_obj;

    /* ...check if command index is valid */
    XF_CHK_ERR(i_cmd < XA_PCM_API_COMMANDS_NUM, XA_API_FATAL_INVALID_CMD);
    
    /* ...see if command is defined */
    XF_CHK_ERR(xa_pcm_api[i_cmd], XA_API_FATAL_INVALID_CMD);
    
    /* ...execute requested command */
    return xa_pcm_api[i_cmd](d, i_idx, pv_value);
}
