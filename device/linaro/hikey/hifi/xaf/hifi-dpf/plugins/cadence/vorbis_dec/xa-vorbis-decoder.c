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
 * xa-vorbis-decoder.c
 *
 * VORBIS decoder plugin - thin wrapper around VORBISDEC library
 *
 ******************************************************************************/

#define MODULE_TAG                      VORBISDEC

/*******************************************************************************
 * Includes
 ******************************************************************************/

#include "xf-plugin.h"
#include "audio/xa-audio-decoder-api.h"
#include "xa_vorbis_dec_api.h"

/*******************************************************************************
 * Override GET-CONFIG-PARAM function
 ******************************************************************************/

static inline XA_ERRORCODE xa_vorbis_get_config_param(xa_codec_handle_t handle, WORD32 i_idx, pVOID pv_value)
{
    /* ...translate "standard" parameter index into internal value */
    switch (i_idx)
    {
    case XA_CODEC_CONFIG_PARAM_CHANNELS:
        /* ...return number of output channels */
        i_idx = XA_VORBISDEC_CONFIG_PARAM_NUM_CHANNELS;
        break;
        
    case XA_CODEC_CONFIG_PARAM_SAMPLE_RATE:
        /* ...return output sampling frequency */
        i_idx = XA_VORBISDEC_CONFIG_PARAM_SAMP_FREQ;
        break;
        
    case XA_CODEC_CONFIG_PARAM_PCM_WIDTH:
        /* ...return sample bit-width */
        i_idx = XA_VORBISDEC_CONFIG_PARAM_PCM_WDSZ;
        break;
    }
    
    /* ...pass to library */
    return xa_vorbis_dec(handle, XA_API_CMD_GET_CONFIG_PARAM, i_idx, pv_value);       
}

/*******************************************************************************
 * API entry point
 ******************************************************************************/

XA_ERRORCODE xa_vorbis_decoder(xa_codec_handle_t p_xa_module_obj, WORD32 i_cmd, WORD32 i_idx, pVOID pv_value)
{
    /* ...process common audio-decoder commands */
    if (i_cmd == XA_API_CMD_GET_CONFIG_PARAM)
    {
        return xa_vorbis_get_config_param(p_xa_module_obj, i_idx, pv_value);
    }
    else
    {
        return xa_vorbis_dec(p_xa_module_obj, i_cmd, i_idx, pv_value);
    }
}
