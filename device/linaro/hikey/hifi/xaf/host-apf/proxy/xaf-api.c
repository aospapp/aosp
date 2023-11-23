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
#include "xf.h"
#include "xaf-api.h"
#include "xaf-structs.h"

#ifdef XAF_HOSTLESS
#include "xos-msgq-if.h"
#endif
#define MODULE_TAG                      DEVAPI

/*******************************************************************************
 * Tracing configuration
 ******************************************************************************/

TRACE_TAG(INIT, 1);
TRACE_TAG(DEBUG, 1);
TRACE_TAG(INFO, 1);

#define XAF_4BYTE_ALIGN    4
#define XAF_8BYTE_ALIGN    8
#define XAF_32BYTE_ALIGN   32


static void xaf_comp_response(xf_handle_t *h, xf_user_msg_t *msg)
{
    if (msg->opcode == XF_UNREGISTER)
    {
        /* ...component execution failed unexpectedly; die */
        BUG(1, _x("[%p] Abnormal termination"), h);
    }
    else
    {
        /* ...submit response to asynchronous delivery queue */
        xf_response_put(h, msg);
    }
}

static XAF_ERR_CODE xaf_comp_add(xaf_comp_t **pp_comp_chain, xaf_comp_t *p_comp)
{
    XAF_CHK_PTR(pp_comp_chain);
    XAF_CHK_PTR(p_comp);

    p_comp->next   = *pp_comp_chain;
    *pp_comp_chain = p_comp;

    return XAF_NO_ERROR;
}

static XAF_ERR_CODE xaf_comp_post_init_config(xaf_adev_t *p_adev, xaf_comp_t *p_comp, void *p_msg)
{
    xf_proxy_t *p_proxy = &p_adev->proxy; 
    xf_start_msg_t *smsg = p_msg;

    p_comp->out_format.sample_rate   = smsg->sample_rate;
    p_comp->out_format.channels      = smsg->channels;
    p_comp->out_format.pcm_width     = smsg->pcm_width;
    p_comp->out_format.input_length  = smsg->input_length;
    p_comp->out_format.output_length = smsg->output_length;

    TRACE(INFO, _b("Component[%x] Params: f=%d, c=%d, w=%d i=%d o=%d"), p_comp->handle.id, smsg->sample_rate, smsg->channels, smsg->pcm_width, smsg->input_length, smsg->output_length);

    if (p_comp->noutbuf)
    { 
        XF_CHK_API(xf_pool_alloc(p_proxy, p_comp->noutbuf, smsg->output_length, XF_POOL_OUTPUT, &p_comp->outpool, XAF_MEM_ID_COMP,
				p_adev->pxf_mem_malloc_fxn, p_adev->pxf_mem_free_fxn));
    }

    p_comp->init_done   = 1;
    p_comp->comp_status = XAF_INIT_DONE;

    return XAF_NO_ERROR;
}

#ifdef XAF_HOSTLESS
XAF_ERR_CODE xaf_xos_start()
{
#if defined BOARD
    xos_set_clock_freq(xtbsp_clock_freq_hz());
#else
    xos_set_clock_freq(XOS_CLOCK_FREQ);
#endif

    xos_start("main", 7, 0);
#if XCHAL_NUM_TIMERS > 0
    xos_start_system_timer(0, TICK_CYCLES);
#endif

    return XAF_NO_ERROR;
}
#endif

XAF_ERR_CODE xaf_adev_open(void** pp_adev, s32 audio_frmwk_buf_size, s32 audio_comp_buf_size, xaf_mem_malloc_fxn_t mem_malloc, xaf_mem_free_fxn_t mem_free)
{
    int size;
    void * pTmp;
    xaf_adev_t *p_adev;
    xf_proxy_t *p_proxy; 

    XAF_CHK_PTR(pp_adev);
    XAF_CHK_PTR(mem_malloc);
    XAF_CHK_PTR(mem_free);

    /* ...unused arg */
    (void) audio_comp_buf_size;

    //Memory allocation for adev struct pointer
    size = (sizeof(xaf_adev_t) +(XAF_4BYTE_ALIGN-1));
    pTmp = mem_malloc(size, XAF_MEM_ID_DEV);
    XAF_CHK_PTR(pTmp);
    memset(pTmp, 0, size);
    
    p_adev = (xaf_adev_t *) (((unsigned long)pTmp + (XAF_4BYTE_ALIGN-1))& ~(XAF_4BYTE_ALIGN-1));
    p_adev->adev_ptr = pTmp;
    *pp_adev = (void *)p_adev;

    p_proxy = &p_adev->proxy;    

    // Host side Memory allocation (BSS)
    p_adev->pxf_mem_malloc_fxn = mem_malloc;
    p_adev->pxf_mem_free_fxn  = mem_free;

    size = sizeof(xaf_ap_utils_t)+(XAF_8BYTE_ALIGN-1);
    p_adev->p_ap_utils = mem_malloc(size, XAF_MEM_ID_DEV);
    XAF_CHK_PTR(p_adev->p_ap_utils);
    //reset memory size stats
    memset(p_adev->p_ap_utils, 0, sizeof(xaf_ap_utils_t));

    // shmmem Memory allocation
    p_adev->p_ap_utils->xf_cfg_remote_ipc_pool_size = audio_frmwk_buf_size; //minimum size 256 KB, mmap multiple is 0x1000

    //DSP localbuf allocation is done in the DSP core; nothing to be done here

    /* ...open DSP proxy - specify "DSP#0" */
    XF_CHK_API(xf_proxy_init(p_proxy, 0, (void *)&p_adev->p_ap_utils->xf_cfg_remote_ipc_pool_size));

    /* ...create auxiliary buffers pool for control commands */
    XF_CHK_API(xf_pool_alloc(p_proxy, XAF_AUX_POOL_SIZE, XAF_AUX_POOL_MSG_LENGTH, XF_POOL_AUX, &p_proxy->aux, XAF_MEM_ID_DEV,
				p_adev->pxf_mem_malloc_fxn, p_adev->pxf_mem_free_fxn));

    return XAF_NO_ERROR;
}

XAF_ERR_CODE xaf_adev_close(void* adev_ptr, xaf_comp_flag flag)
{
    xaf_adev_t *p_adev;
    xf_proxy_t *p_proxy;

    XAF_CHK_PTR(adev_ptr);
    p_adev = (xaf_adev_t *)adev_ptr;

    /* ...unused arg */
    (void) flag;

    p_proxy = &p_adev->proxy;
    if(p_proxy->aux != NULL)
    {
        xf_pool_free(p_proxy->aux, XAF_MEM_ID_DEV, p_adev->pxf_mem_free_fxn);
    }

    xf_proxy_close(p_proxy);

    p_adev->pxf_mem_free_fxn(p_adev->p_ap_utils, XAF_MEM_ID_DEV);
    p_adev->p_ap_utils = NULL;
    p_adev->pxf_mem_free_fxn(p_adev->adev_ptr, XAF_MEM_ID_DEV);
    p_adev->adev_ptr = NULL;

    p_adev->pxf_mem_malloc_fxn = NULL;
    p_adev->pxf_mem_free_fxn  = NULL;

    return XAF_NO_ERROR;
}

XAF_ERR_CODE xaf_comp_create(void *adev_ptr, void **pp_comp, xf_id_t comp_id, u32 ninbuf, u32 noutbuf, void *pp_inbuf[], xaf_comp_type comp_type)
{
    xf_handle_t *p_handle;
    void * pTmp;
    int size;

    xaf_adev_t *p_adev;
    p_adev = (xaf_adev_t *)adev_ptr;
    xaf_comp_t *p_comp;

    XAF_CHK_PTR(p_adev);
    XAF_CHK_PTR(pp_comp);
    XAF_CHK_PTR(comp_id);
    if (ninbuf) XAF_CHK_PTR(pp_inbuf);

    XAF_CHK_RANGE(ninbuf, 0, XAF_MAX_INBUFS);
    XAF_CHK_RANGE(noutbuf, 0, 1);
    XAF_CHK_RANGE(comp_type, XAF_DECODER, XAF_POST_PROC); 

    //Memory allocation for component struct pointer
    size = (sizeof(xaf_comp_t) + (XAF_4BYTE_ALIGN-1));
    pTmp = p_adev->pxf_mem_malloc_fxn(size, XAF_MEM_ID_COMP);
    XAF_CHK_PTR(pTmp);
    memset(pTmp, 0, size);
    p_comp = (xaf_comp_t *) (((unsigned long)pTmp + (XAF_4BYTE_ALIGN-1))& ~(XAF_4BYTE_ALIGN-1));

    p_comp->comp_ptr = pTmp;
    *pp_comp = (void*)p_comp;

    memset(p_comp, 0, sizeof(xaf_comp_t));
    p_handle = &p_comp->handle;

    /* ...create component instance (select core-0) */
    XF_CHK_API(xf_open(&p_adev->proxy, p_handle, comp_id, 0, xaf_comp_response));

    xaf_comp_add(&p_adev->comp_chain, p_comp);
    
    // Temporary solution in place of component chain handling
    p_comp->p_adev = p_adev;
    p_adev->n_comp += 1;
    p_comp->ninbuf = ninbuf;

    /* ...allocate input buffer */
    if (ninbuf) 
    {
        xf_buffer_t *buf;
        u32 i;
        XF_CHK_API(xf_pool_alloc(&p_adev->proxy, ninbuf, XAF_INBUF_SIZE, XF_POOL_INPUT, &p_comp->inpool, XAF_MEM_ID_COMP,
				p_adev->pxf_mem_malloc_fxn, p_adev->pxf_mem_free_fxn));
        
        for (i=0; i<ninbuf; i++)
        {
            buf         = xf_buffer_get(p_comp->inpool);
            pp_inbuf[i] = xf_buffer_data(buf); 
        }

    }
    p_comp->noutbuf = noutbuf;

    p_comp->comp_type   = comp_type;
    p_comp->comp_status = XAF_STARTING;

    switch (comp_type)
    {
    case XAF_DECODER:
    case XAF_ENCODER:
    case XAF_PRE_PROC:
    case XAF_POST_PROC:
        p_comp->inp_ports = 1; p_comp->out_ports = 1;
        break;
    case XAF_MIXER:
        p_comp->inp_ports = 4; p_comp->out_ports = 1;
        break;
    }

    return XAF_NO_ERROR;
}

XAF_ERR_CODE xaf_comp_delete(void *comp_ptr)
{
    xaf_adev_t *p_adev;

    xaf_comp_t *p_comp;
    p_comp = (xaf_comp_t *)comp_ptr;

    XAF_CHK_PTR(p_comp);

    // Temporary solution in place of component chain handling
    p_adev = (xaf_adev_t *)(p_comp->p_adev);
    XF_CHK_ERR((p_adev->n_comp > 0), XAF_API_ERR);
    p_adev->n_comp -= 1;


    if (p_comp->inpool)  xf_pool_free(p_comp->inpool, XAF_MEM_ID_COMP, p_adev->pxf_mem_free_fxn);
    if (p_comp->outpool) xf_pool_free(p_comp->outpool, XAF_MEM_ID_COMP, p_adev->pxf_mem_free_fxn);

    xf_close(&p_comp->handle);
   
    /* ...tbd - remove from chain */
    p_adev->pxf_mem_free_fxn(p_comp->comp_ptr, XAF_MEM_ID_COMP);
    p_comp->comp_ptr = NULL;
     
    return XAF_NO_ERROR;
}

XAF_ERR_CODE xaf_comp_set_config(void *comp_ptr, s32 num_param, s32 *p_param)
{
    xaf_comp_t              *p_comp;
    xf_user_msg_t           rmsg;
    xf_set_param_msg_t     *smsg;
    xf_handle_t            *p_handle;
    s32                     i, j;

    p_comp = (xaf_comp_t *)comp_ptr;

    XAF_CHK_PTR(p_comp);
    XAF_CHK_PTR(p_param);
    XAF_CHK_RANGE(num_param, 1, XAF_MAX_CONFIG_PARAMS); 
    
    p_handle = &p_comp->handle;
    XAF_CHK_PTR(p_handle);

    /* ...set persistent stream characteristics */
    smsg = xf_buffer_data(p_handle->aux);

    j = 0;
    for (i=0; i<num_param; i++)
    {
        smsg->item[i].id    = p_param[j++];
        smsg->item[i].value = p_param[j++];
    }
    
    /* ...pass command to the component */
    /* ...tbd - command goes port 0 always, check if okay */
    XF_CHK_API(xf_command(p_handle, 0, XF_SET_PARAM, smsg, sizeof(xf_set_param_item_t)*num_param));

    /* ...wait until result is delivered */
    XF_CHK_API(xf_response_get(p_handle, &rmsg));

    /* ...make sure response is expected */
    XF_CHK_ERR(rmsg.opcode == (u32) XF_SET_PARAM && rmsg.buffer == smsg, XAF_API_ERR);

    return XAF_NO_ERROR;
}

XAF_ERR_CODE xaf_comp_get_config(void *comp_ptr, s32 num_param, s32 *p_param)
{
    xaf_comp_t             *p_comp;
    xf_user_msg_t           rmsg;
    xf_get_param_msg_t     *smsg;
    xf_handle_t            *p_handle;
    s32                     i;

    p_comp = (xaf_comp_t *)comp_ptr;

    XAF_CHK_PTR(p_comp);
    XAF_CHK_PTR(p_param);
    XAF_CHK_RANGE(num_param, 1, XAF_MAX_CONFIG_PARAMS); 
    
    p_handle = &p_comp->handle;
    XAF_CHK_PTR(p_handle);

    /* ...set persistent stream characteristics */
    smsg = xf_buffer_data(p_handle->aux);

    for (i=0; i<num_param; i++)
    {
        smsg->c.id[i] = p_param[i];
    }
    
    /* ...pass command to the component */
    /* ...tbd - command goes port 0 always, check if okay */
    XF_CHK_API(xf_command(p_handle, 0, XF_GET_PARAM, smsg, XF_GET_PARAM_CMD_LEN(num_param)));

    /* ...wait until result is delivered */
    XF_CHK_API(xf_response_get(p_handle, &rmsg));

    /* ...make sure response is expected */
    XF_CHK_ERR(rmsg.opcode == (u32) XF_GET_PARAM && rmsg.buffer == smsg, XAF_API_ERR);

    for (i=0; i<num_param; i++)
    {
        p_param[i] = smsg->r.value[i];
    }

    return XAF_NO_ERROR;
}
#ifdef XAF_HOSTLESS
XAF_ERR_CODE xaf_comp_get_status(xaf_adev_t *p_adev, xaf_comp_t *p_comp, xaf_comp_status *p_status, void *p_info)
#else
XAF_ERR_CODE xaf_comp_get_status(void *adev_ptr, void *comp_ptr, xaf_comp_status *p_status, xaf_info_t *p_info)
#endif
{
    xaf_adev_t *p_adev;
    xaf_comp_t *p_comp;
    xf_handle_t *p_handle;

    p_adev = (xaf_adev_t *)adev_ptr;
    p_comp = (xaf_comp_t *)comp_ptr;

    XAF_CHK_PTR(p_comp);
    XAF_CHK_PTR(p_status);
    XAF_CHK_PTR(p_info);
    if (!p_comp->init_done) XAF_CHK_PTR(p_adev);

    p_handle = &p_comp->handle;

    if (p_comp->pending_resp)
    {
        xf_user_msg_t rmsg;
        /* ...wait until result is delivered */
        XF_CHK_API(xf_response_get(p_handle, &rmsg)); 

        if (rmsg.opcode == XF_FILL_THIS_BUFFER) 
        {
            if (rmsg.buffer == p_comp->start_buf)
            {
                XF_CHK_API(xaf_comp_post_init_config(p_adev, p_comp, p_comp->start_buf));
            }
            else 
            {
#ifdef XAF_HOSTLESS
				s32 *p_buf = (s32 *) p_info;
                p_buf[0] = (s32) rmsg.buffer;
                p_buf[1] = (s32) rmsg.length;
#else
                p_info->buf = (void*) rmsg.buffer;
                p_info->length = (s32) rmsg.length;
#endif				
                if (!p_comp->inpool && p_comp->outpool) p_comp->pending_resp--;

                if (!rmsg.length) p_comp->comp_status = XAF_EXEC_DONE;
                else
                {
                    p_comp->comp_status = XAF_OUTPUT_READY;
                    p_comp->expect_out_cmd++;
                }
            }
        }
        else
        {
            /* ...make sure response is expected */
            XF_CHK_ERR(rmsg.opcode == (u32) XF_EMPTY_THIS_BUFFER, XAF_API_ERR);            
#ifdef XAF_HOSTLESS
			s32 *p_buf = (s32 *) p_info;
            p_buf[0] = (s32) rmsg.buffer;
            p_buf[1] = (s32) rmsg.length;
#else
            p_info->buf = (void*) rmsg.buffer;
			p_info->length = (s32) rmsg.length;
#endif            
            p_comp->pending_resp--;
            
            if (p_comp->input_over && rmsg.buffer == NULL) p_comp->comp_status = XAF_EXEC_DONE;
            else p_comp->comp_status = XAF_NEED_INPUT;
        }
    }
    else if ((p_comp->comp_status == XAF_STARTING && p_comp->start_cmd_issued) ||
             (p_comp->comp_status == XAF_INIT_DONE && p_comp->exec_cmd_issued))
    {
        if (p_comp->inpool) p_comp->comp_status = XAF_NEED_INPUT;
    }
    
    *p_status = p_comp->comp_status;
    
    return XAF_NO_ERROR;
}

XAF_ERR_CODE xaf_comp_process(void *adev_ptr, void *comp_ptr, void *p_buf, u32 length, xaf_comp_flag flag)
{
    xaf_adev_t *p_adev;
    xaf_comp_t *p_comp;
    xf_handle_t *p_handle;

    p_adev = (xaf_adev_t *)adev_ptr;
    p_comp = (xaf_comp_t *)comp_ptr;

    XAF_CHK_PTR(p_comp);
    if (!p_comp->init_done) XAF_CHK_PTR(p_adev);
    XAF_CHK_RANGE(flag, XAF_START_FLAG, XAF_NEED_OUTPUT_FLAG);
    if (flag == XAF_INPUT_READY_FLAG) XAF_CHK_RANGE(length, 0, XAF_INBUF_SIZE);

    p_handle = &p_comp->handle;
    
    switch (flag)
    {
    case XAF_START_FLAG:
        if (p_comp->start_cmd_issued)
            break;
        else
        {
            p_comp->start_buf = xf_buffer_data(p_handle->aux);
            XF_CHK_API(xf_command(p_handle, (p_comp->inp_ports), XF_FILL_THIS_BUFFER, p_comp->start_buf, 0));
            p_comp->start_cmd_issued = 1;

            if(p_comp->comp_type != XAF_DECODER) 
            {
                xf_user_msg_t rmsg;
                /* ...wait until result is delivered */
                XF_CHK_API(xf_response_get(p_handle, &rmsg)); 
            
                /* ...make sure response is expected */
                XF_CHK_ERR(rmsg.opcode == XF_FILL_THIS_BUFFER && rmsg.buffer == p_comp->start_buf, XAF_API_ERR);

                XF_CHK_API(xaf_comp_post_init_config(p_adev, p_comp, p_comp->start_buf));
            }            
        }
        break;
    
    case XAF_EXEC_FLAG:
        if (!p_comp->init_done || p_comp->exec_cmd_issued)
            break;
        p_comp->exec_cmd_issued = 1;
        if (p_comp->outpool)
        {
            u32 i;
            xf_buffer_t *p_buf;
            void *p_data;

            for (i=0; i<p_comp->noutbuf; i++)
            {
                p_buf = xf_buffer_get(p_comp->outpool);
                p_data = xf_buffer_data(p_buf);

                XF_CHK_API(xf_command(&p_comp->handle, (p_comp->inp_ports), XF_FILL_THIS_BUFFER, p_data, p_comp->out_format.output_length));
            }
            
            if (!p_comp->inpool) p_comp->pending_resp = p_comp->noutbuf;
        }
        break;
 
    case XAF_INPUT_OVER_FLAG:
        if (!p_comp->input_over)
        {
            XF_CHK_API(xf_command(p_handle, 0, XF_EMPTY_THIS_BUFFER, NULL, 0));
            p_comp->input_over = 1;
            p_comp->pending_resp++;
        }
        break;

    case XAF_INPUT_READY_FLAG:
        if (!p_comp->input_over)
        {
            XAF_CHK_PTR(p_buf);
            XF_CHK_API(xf_command(p_handle, 0, XF_EMPTY_THIS_BUFFER, p_buf, length));
            p_comp->pending_resp++;
        }
        break;

    case XAF_NEED_OUTPUT_FLAG:
        if (p_comp->expect_out_cmd)
        {
            XAF_CHK_PTR(p_buf);
            XF_CHK_API(xf_command(p_handle, (p_comp->inp_ports), XF_FILL_THIS_BUFFER, p_buf, length));
            p_comp->expect_out_cmd--;

            if (!p_comp->inpool && p_comp->outpool) p_comp->pending_resp++;
        }
        break;
    }
    
    return XAF_NO_ERROR;
}

XAF_ERR_CODE xaf_connect(void *src_ptr, void *dest_ptr, s32 num_buf)
{
    xaf_comp_t *p_src;
    xaf_comp_t *p_dest;

    p_src = (xaf_comp_t *)src_ptr;
    p_dest = (xaf_comp_t *)dest_ptr;

    XAF_CHK_PTR(p_src);
    XAF_CHK_PTR(p_dest);
    XAF_CHK_RANGE(num_buf, 2, 4);    

    if (!p_src->init_done || p_src->out_routed == p_src->out_ports || p_dest->inp_routed == p_dest->inp_ports)
        return XAF_ROUTING_ERROR;
                   
    XF_CHK_API(xf_route(&p_src->handle, (p_src->inp_ports + p_src->out_routed), &p_dest->handle, (p_dest->inp_routed), num_buf, p_src->out_format.output_length, 8));
    
    p_src->out_routed++;
    p_dest->inp_routed++;

    return XAF_NO_ERROR;
}

XAF_ERR_CODE xaf_disconnect(xaf_comp_t *p_comp)
{
    XAF_CHK_PTR(p_comp);
    
    /* ...tbd - support for multiple output ports */
    if (!p_comp->init_done || p_comp->out_routed != p_comp->out_ports)
        return XAF_ROUTING_ERROR;

    XF_CHK_API(xf_unroute(&p_comp->handle, (p_comp->inp_ports)));

    return XAF_NO_ERROR;
}






