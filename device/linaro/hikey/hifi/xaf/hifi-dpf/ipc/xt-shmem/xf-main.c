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
 * xf-main.c
 *
 * DSP processing framework main file
 *
 ******************************************************************************/

#define MODULE_TAG                      MAIN

/*******************************************************************************
 * Includes
 ******************************************************************************/

#include "xf.h"
#ifndef XAF_ENABLE_NON_HIKEY
volatile int waitstate;
#endif

#ifdef XAF_PROFILE_DSP
/* ... MCPS/profile info */
#include "xa_profiler.h"
#endif

/*******************************************************************************
 * Global data definition
 ******************************************************************************/
/* ...per-core execution data */
xf_core_data_t          xf_core_data[XF_CFG_CORES_NUM];

/* ...AP-DSP shared memory pool */
xf_mm_pool_t            xf_ap_shmem_pool;

/* ...per-core local memory pool */
xf_mm_pool_t            xf_dsp_local_pool[XF_CFG_CORES_NUM];

#if XF_CFG_CORES_NUM > 1
/* ...DSP cluster shared memory pool */
xf_mm_pool_t            xf_dsp_shmem_pool;
#endif

/* ...per-core shared memory with read-only access */
__xf_core_ro_data_t     xf_core_ro_data[XF_CFG_CORES_NUM];

/* ...per-core shared memory with read-write access */
__xf_core_rw_data_t     xf_core_rw_data[XF_CFG_CORES_NUM];

/*******************************************************************************
 * Memory buffers - take them from linker file somehow - tbd
 ******************************************************************************/

/* ...unallocated memory region - AP-DSP shared memory buffer - export from linker somehow */
//u8                      xf_ap_shmem_buffer[XF_CFG_REMOTE_IPC_POOL_SIZE] __xf_shmem__;

#if XF_CFG_CORES_NUM > 1
/* ...unallocated DSP shared memory buffer */
u8                      xf_dsp_shmem_buffer[XF_CFG_LOCAL_IPC_POOL_SIZE] __xf_shmem__;
#endif

/* ...unallocated per-core local memory (in 32-byte chunks) */
#ifdef XAF_ENABLE_NON_HIKEY
u8                      xf_dsp_local_buffer[XF_CFG_CORES_NUM][XF_CFG_LOCAL_POOL_SIZE] __xf_mm__;
#else
u8                      xf_dsp_local_buffer[XF_CFG_CORES_NUM][XF_CFG_LOCAL_POOL_SIZE];
//u8                      g_tmp[2];
#endif

#ifdef XAF_PROFILE_DSP
xa_profiler prof;
#endif

/*******************************************************************************
 * Timer interrupt - tbd
 ******************************************************************************/

/*******************************************************************************
 * IPC layer initialization
 ******************************************************************************/

/* ...system-specific IPC layer initialization */
int xf_ipc_init(u32 core)
{
    xf_core_data_t  *cd = XF_CORE_DATA(core);
    xf_shmem_data_t *shmem = (xf_shmem_data_t *)XF_CFG_SHMEM_ADDRESS(core);

    /* ...initialize pointer to shared memory */
    cd->shmem = (xf_shmem_handle_t *)shmem;
    shmem->buffer = (uint8_t *) HIFI_MUSIC_DATA_LOCATION;
    /* ...global memory pool initialization */
#ifdef XAF_ENABLE_NON_HIKEY
    XF_CHK_API(xf_mm_init(&cd->shared_pool, shmem->buffer, XF_CFG_REMOTE_IPC_POOL_SIZE));
#else
    XF_CHK_API(xf_mm_init(&cd->shared_pool, (void *) HIFI_MUSIC_DATA_LOCATION, XF_CFG_REMOTE_IPC_POOL_SIZE));
#endif
    return 0;
}

/*******************************************************************************
 * Core executive loop
 ******************************************************************************/

static void xf_core_loop(u32 core)
{
    /* ...initialize internal core structures */
    xf_core_init(core);
    
#ifdef XAF_PROFILE_DSP
/* Profiler initialization */
    INIT_XA_PROFILER(prof,"DSP core");
#endif

    for(;;)
    {
        /* ...wait in a low-power mode until event is triggered */
        xf_ipi_wait(core);
        
        /* ...service core event */
        xf_core_service(core);
    }
}

/*******************************************************************************
 * Global entry point
 ******************************************************************************/

int main(void)
{
#if XF_CFG_CORES_NUM > 1
    u32     i;
#endif

    /* ...reset ro/rw core data - tbd */
    memset(xf_core_rw_data, 0, sizeof(xf_core_rw_data));
    memset(xf_core_ro_data, 0, sizeof(xf_core_ro_data));

    TRACE_INIT("Xtensa Audio DSP Codec Server");
#ifdef XAF_ENABLE_NON_HIKEY
    /* ...initialize board */
    xf_board_init();

    /* ...global framework data initialization */
    xf_global_init();
#endif

#if XF_CFG_CORES_NUM > 1
    /* ...DSP shared memory pool initialization */
    XF_CHK_API(xf_mm_init(&xf_dsp_shmem_pool, xf_dsp_shmem_buffer, XF_CFG_LOCAL_IPC_POOL_SIZE));
#endif

    /* ...initialize per-core memory loop */
    XF_CHK_API(xf_mm_init(&xf_core_data[0].local_pool, xf_dsp_local_buffer[0], XF_CFG_LOCAL_POOL_SIZE));

#if XF_CFG_CORES_NUM > 1    
    /* ...bring up all cores */
    for (i = 1; i < XF_CFG_CORES_NUM; i++)
    {
        /* ...wake-up secondary core somehow and make it execute xf_core_loop */
        xf_core_secondary_startup(i, xf_core_loop, i);
        
    }
#endif

    /* ...enter execution loop on main core #0 */
    xf_core_loop(0);

    /* ...not reachable */
    return 0;
}
