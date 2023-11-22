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
 * xa-factory.c
 *
 * DSP processing framework core - component factory
 *
 ******************************************************************************/

#define MODULE_TAG                      FACTORY

/*******************************************************************************
 * Includes
 ******************************************************************************/

#include "xf.h"
#include "audio/xa_type_def.h"

/*******************************************************************************
 * Tracing tags
 ******************************************************************************/

/* ...general initialization sequence */
TRACE_TAG(INIT, 1);

/*******************************************************************************
 * Local types definitions
 ******************************************************************************/

/* ...component descriptor */
typedef struct xf_component_id
{
    /* ...class id (string identifier) */
    const char         *id;
    
    /* ...class constructor */
    xf_component_t *  (*factory)(u32 core, xa_codec_func_t process);

    /* ...component API function */
    xa_codec_func_t    *process;

}   xf_component_id_t;

/*******************************************************************************
 * External functions
 ******************************************************************************/

/* ...components API functions */
extern XA_ERRORCODE xa_pcm_codec(xa_codec_handle_t, WORD32, WORD32, pVOID);
extern XA_ERRORCODE xa_mp3_decoder(xa_codec_handle_t, WORD32, WORD32, pVOID);
extern XA_ERRORCODE xa_aac_decoder(xa_codec_handle_t, WORD32, WORD32, pVOID);
extern XA_ERRORCODE xa_aac_encoder(xa_codec_handle_t, WORD32, WORD32, pVOID);
extern XA_ERRORCODE xa_vorbis_decoder(xa_codec_handle_t, WORD32, WORD32, pVOID);
extern XA_ERRORCODE xa_ac3_decoder(xa_codec_handle_t, WORD32, WORD32, pVOID);
extern XA_ERRORCODE xa_ddplus71_decoder(xa_codec_handle_t, WORD32, WORD32, pVOID);
extern XA_ERRORCODE xa_mixer(xa_codec_handle_t, WORD32, WORD32, pVOID);
extern XA_ERRORCODE xa_renderer(xa_codec_handle_t, WORD32, WORD32, pVOID);
extern XA_ERRORCODE xa_capturer(xa_codec_handle_t, WORD32, WORD32, pVOID);
extern XA_ERRORCODE xa_src_pp_fx(xa_codec_handle_t, WORD32, WORD32, pVOID);
extern XA_ERRORCODE xa_dts_hd_decoder(xa_codec_handle_t, WORD32, WORD32, pVOID);
extern XA_ERRORCODE xa_dap_fx(xa_codec_handle_t, WORD32, WORD32, pVOID);

/* ...component class factories */
extern xf_component_t * xa_audio_codec_factory(u32 core, xa_codec_func_t process);
extern xf_component_t * xa_audio_fx_factory(u32 core, xa_codec_func_t process);
extern xf_component_t * xa_mixer_factory(u32 core, xa_codec_func_t process);
extern xf_component_t * xa_renderer_factory(u32 core,xa_codec_func_t process);

/*******************************************************************************
 * Local constants definitions
 ******************************************************************************/
    
/* ...component class id */
static const xf_component_id_t xf_component_id[] = 
{
#if XA_PCM
    { "audio-decoder/pcm",      xa_audio_codec_factory,     xa_pcm_codec },
#endif
#if XA_MP3_DECODER
    { "audio-decoder/mp3",      xa_audio_codec_factory,     xa_mp3_decoder },
#endif
#if XA_AAC_DECODER
    { "audio-decoder/aac",      xa_audio_codec_factory,     xa_aac_decoder },
#endif
#if XA_AC3_DECODER
    { "audio-decoder/ac3",      xa_audio_codec_factory,     xa_ac3_decoder },
#endif
#if XA_DDP71_DECODER
    { "audio-decoder/ddplus71", xa_audio_codec_factory,     xa_ddplus71_decoder },
#endif
#if XA_DTS_HD_DECODER
    { "audio-decoder/dts-hd",   xa_audio_codec_factory,     xa_dts_hd_decoder },
#endif
#if XA_VORBIS_DECODER
    { "audio-decoder/vorbis",   xa_audio_codec_factory,     xa_vorbis_decoder },
#endif
#if XA_AAC_ENCODER
    { "audio-encoder/aac",      xa_audio_codec_factory,     xa_aac_encoder },
#endif
#if XA_SRC_PP_FX
    { "audio-fx/src-pp",        xa_audio_codec_factory,     xa_src_pp_fx },
#endif
#if XA_DAP_FX
    { "audio-fx/dap",           xa_audio_codec_factory,     xa_dap_fx },
#endif
#if XA_MIXER
    { "mixer",                  xa_mixer_factory,           xa_mixer },
#endif
#if XA_RENDERER
    { "renderer",               xa_renderer_factory,        xa_renderer },
#endif
#if XA_CAPTURER
    { "capturer",               xa_capturer_factory,        xa_capturer },
#endif
};

/* ...number of items in the map */
#define XF_COMPONENT_ID_MAX     (sizeof(xf_component_id) / sizeof(xf_component_id[0]))
   
/*******************************************************************************
 * Enry points
 ******************************************************************************/

xf_component_t * xf_component_factory(u32 core, xf_id_t id, u32 length)
{
    u32     i;

    /* ...find component-id in static map */
    for (i = 0; i < XF_COMPONENT_ID_MAX; i++)
    {
        /* ...symbolic search - not too good; would prefer GUIDs in some form */
        if (!strncmp(id, xf_component_id[i].id, length))
        {
            /* ...pass control to specific class factory */
            return xf_component_id[i].factory(core, xf_component_id[i].process);
        }
    }

    /* ...component string id is not recognized */
    TRACE(ERROR, _b("Unknown component type: %s"), id);

    return NULL;
}
