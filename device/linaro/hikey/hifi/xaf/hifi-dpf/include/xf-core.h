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
 * xf-core.h
 *
 * DSP processing framework core definitions
 *
 *******************************************************************************/

#ifndef __XF_H
#error "xf-core.h mustn't be included directly"
#endif

/*******************************************************************************
 * Shared core data
 ******************************************************************************/

/* ...core data with read-only access from remote cores */
typedef struct xf_core_ro_data
{
    /* ...platform-specific multi-core mutex data (want to have an array? - tbd) */
    xf_mutex_t          lock;

    /* ...opaque platform-specific IPC-data handle */
    xf_ipc_handle_t     ipc;

    /* ...shared memory message pool data - here? - tbd */
    xf_msg_pool_t       pool;
    
    /* ...anything else? - tbd */

}   xf_core_ro_data_t;

/* ...core data with read-write access from remote cores */
typedef struct xf_core_rw_data
{
    /* ...message queue containing local commands/responses */
    xf_msg_queue_t      local;

    /* ...message queue containing responses to remote proxy (if enabled) */
    xf_msg_queue_t      remote;

    /* ...pointer to shared memory data? anything else? - tbd */

}   xf_core_rw_data_t;

/* ...proper cache-line aligned core data */
XF_ALIGNED_TYPEDEF(xf_core_ro_data_t, __xf_core_ro_data_t);
XF_ALIGNED_TYPEDEF(xf_core_rw_data_t, __xf_core_rw_data_t);

/*******************************************************************************
 * Global data definition - hmm... - tbd
 ******************************************************************************/

/* ...per-core shared memory with read-only remote access */
extern __xf_core_ro_data_t  xf_core_ro_data[XF_CFG_CORES_NUM];

/* ...per-core shared memory with read-write remote access */
extern __xf_core_rw_data_t  xf_core_rw_data[XF_CFG_CORES_NUM];

/* ...shared read-only memory access */
#define XF_CORE_RO_DATA(core)   ((xf_core_ro_data_t *)(&xf_core_ro_data[(core)]))

/* ...shared read-write memory access */
#define XF_CORE_RW_DATA(core)   ((xf_core_rw_data_t *)(&xf_core_rw_data[(core)]))

/*******************************************************************************
 * Local core data (not accessible from remote cores)
 ******************************************************************************/

/* ...component map entry */
typedef union xf_cmap_link
{
    /* ...poiner to active client */
    xf_component_t     *c;
    
    /* ...index to a client in the list (values 0..XF_CFG_MAX_CLIENTS) */
    u32                 next;

}   xf_cmap_link_t;

/* ...per-core local data */
typedef struct xf_core_data
{
    /* ...scheduler queue (sorted by execution timestamp) */
    xf_sched_t          sched;

    /* ...command/response queue for communication within local core (including ISRs) */
    xf_msg_queue_t      queue;

    /* ...pending response queue (submitted from ISR context) */
    xf_msg_queue_t      response;
    
    /* ...per-core component mapping */
    xf_cmap_link_t      cmap[XF_CFG_MAX_CLIENTS];

    /* ...index of first free client */
    u32                 free;
    
    /* ...local DSP memory pool */
    xf_mm_pool_t        local_pool;
    
    /* ...shared AP-DSP memory pool (if enabled) */
    xf_mm_pool_t        shared_pool;    

    /* ...opaque system-specific shared memory data handle */
    xf_shmem_handle_t   shmem;

    /* ...scratch memory pointer */
    void               *scratch;

    /* ...tracer data */
    xf_trace_data_t     trace;

    /* ...any debugging information? for memory allocation etc... ? */

}   xf_core_data_t;

/*******************************************************************************
 * API functions
 ******************************************************************************/

/* ...initialize per-core framework data */
extern int  xf_core_init(u32 core);

/* ...global data initialization function */
extern int  xf_global_init(void);

/* ...process core events */
extern void xf_core_service(u32 core);
