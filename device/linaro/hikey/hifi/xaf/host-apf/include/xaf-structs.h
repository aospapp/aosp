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

/* ...size of auxiliary pool for communication with HiFi */
#define XAF_AUX_POOL_SIZE                   32

/* ...length of auxiliary pool messages */
#define XAF_AUX_POOL_MSG_LENGTH             128
#define XAF_MAX_CONFIG_PARAMS               (XAF_AUX_POOL_MSG_LENGTH >> 3)

typedef struct xaf_comp xaf_comp_t;

struct xaf_comp {
    xf_handle_t     handle;     

    u32             inp_routed;
    u32             out_routed;
    u32             inp_ports;
    u32             out_ports;
    u32             init_done;
    u32             pending_resp;
    u32             expect_out_cmd;
    u32             input_over;

    xaf_comp_type   comp_type;
    xaf_comp_status comp_status;
    u32             start_cmd_issued;
    u32             exec_cmd_issued;
    void            *start_buf;

    xaf_format_t    inp_format;
    xaf_format_t    out_format;

    xf_pool_t       *inpool;
    xf_pool_t       *outpool;
    u32             noutbuf;

    xaf_comp_t      *next;

    u32             ninbuf;
    void            *p_adev;
    //xaf_comp_state  comp_state;
    void           *comp_ptr; 
};

typedef struct xaf_adev_s {
    xf_proxy_t      proxy;
    xaf_comp_t      *comp_chain;  

    u32   n_comp;
    void *adev_ptr;
    void *p_dspMem;
    void *p_apMem;
    void *p_dspLocalBuff;
    void *p_apSharedMem;

    xaf_ap_utils_t  *p_ap_utils; //host-side utility structure handle
    void  *(*pxf_mem_malloc_fxn)(s32, s32);
    void  (*pxf_mem_free_fxn)(void *,s32);
    //xaf_adev_state  adev_state;

} xaf_adev_t;

