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


/* ...number of max input buffers */
#define XAF_MAX_INBUFS                      2
#define XAF_INBUF_SIZE                      8192

typedef enum {
    XAF_DECODER         = 0,
    XAF_ENCODER         = 1,
    XAF_MIXER           = 2,
    XAF_PRE_PROC        = 3,
    XAF_POST_PROC       = 4,
} xaf_comp_type;

typedef enum {
    XAF_STARTING        = 0,
    XAF_INIT_DONE       = 1,
    XAF_NEED_INPUT      = 2,
    XAF_OUTPUT_READY    = 3,
    XAF_EXEC_DONE       = 4,
} xaf_comp_status;

typedef enum {
    XAF_START_FLAG          = 1,
    XAF_EXEC_FLAG           = 2,
    XAF_INPUT_OVER_FLAG     = 3,
    XAF_INPUT_READY_FLAG    = 4,
    XAF_NEED_OUTPUT_FLAG    = 5,
} xaf_comp_flag;

typedef enum {
    XAF_NO_ERROR        =  0,
    XAF_PTR_ERROR       = -1,
    XAF_INVALID_VALUE   = -2,
    XAF_ROUTING_ERROR   = -3,
    /*XAF_XOS_ERROR       = -4,*/
    XAF_API_ERR         = -5,
} XAF_ERR_CODE;

typedef enum {
    XAF_MEM_ID_DEV  = 0,
    XAF_MEM_ID_COMP = 1,
} XAF_MEM_ID;

/* structure for component memory sizes */
typedef struct xaf_mem_size_s{
    u32 persist;
    u32 scratch;
    u32 input;
    u32 output;
}xaf_mem_size_t;

/* structure for host-side utility handles */
typedef struct xaf_ap_utils_s{
  int            xf_cfg_remote_ipc_pool_size;
  xaf_mem_size_t mem_size;
}xaf_ap_utils_t;

typedef struct xaf_format_s {
    u32             sample_rate;
    u32             channels;
    u32             pcm_width;
    u32             input_length;
    u32             output_length;
} xaf_format_t;

#ifndef XAF_HOSTLESS
typedef struct xaf_info_s {
    void *          buf;
    u32             length;
} xaf_info_t;
#endif

XAF_ERR_CODE xaf_adev_open(void **pp_adev, s32 audio_frmwk_buf_size, s32 audio_comp_buf_size, xaf_mem_malloc_fxn_t mm_malloc, xaf_mem_free_fxn_t mm_free);
XAF_ERR_CODE xaf_adev_close(void *adev_ptr, xaf_comp_flag flag);

XAF_ERR_CODE xaf_comp_create(void* p_adev, void **p_comp, xf_id_t comp_id, u32 ninbuf, u32 noutbuf, void *pp_inbuf[], xaf_comp_type comp_type);
XAF_ERR_CODE xaf_comp_delete(void* p_comp);
XAF_ERR_CODE xaf_comp_set_config(void *p_comp, s32 num_param, s32 *p_param);
XAF_ERR_CODE xaf_comp_get_config(void *p_comp, s32 num_param, s32 *p_param);
XAF_ERR_CODE xaf_comp_process(void *p_adev, void *p_comp, void *p_buf, u32 length, xaf_comp_flag flag);
XAF_ERR_CODE xaf_connect(void *p_src, void *p_dest, s32 num_buf);

/* Not available in this version yet.
XAF_ERR_CODE xaf_disconnect(xaf_comp_t *p_comp);
*/

XAF_ERR_CODE xaf_comp_get_status(void *p_adev, void *p_comp, xaf_comp_status *p_status, xaf_info_t *p_info);

/* ...check null pointer */ 
#define XAF_CHK_PTR(ptr)                                     \
({                                                          \
    int __ret;                                              \
                                                            \
    if ((__ret = (int)(ptr)) == 0)                          \
    {                                                       \
        TRACE(ERROR, _x("Null pointer error: %d"), __ret);  \
        return XAF_PTR_ERROR;                               \
    }                                                       \
    __ret;                                                  \
})

/* ...check range */
#define XAF_CHK_RANGE(val, min, max)                         \
({                                                          \
    int __ret = val;                                        \
                                                            \
    if ((__ret < (int)min) || (__ret > (int)max))           \
    {                                                       \
        TRACE(ERROR, _x("Invalid value: %d"), __ret);       \
        return XAF_INVALID_VALUE;                           \
    }                                                       \
    __ret;                                                  \
})




