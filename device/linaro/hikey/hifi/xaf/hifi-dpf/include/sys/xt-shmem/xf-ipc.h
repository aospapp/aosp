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
 * xf-ipc.h
 *
 * Xtensa IPC mechanism
 *
 *******************************************************************************/

#ifndef __XF_H
#error "xf-ipc.h mustn't be included directly"
#endif

/*******************************************************************************
 * Includes
 ******************************************************************************/

/* ...system-specific shared memory configuration */
#include "xf-shmem.h"
#ifndef XAF_ENABLE_NON_HIKEY
#include <xtensa/xtruntime.h>
extern volatile int waitstate;
#endif

#ifdef XAF_ENABLE_NON_HIKEY
/*******************************************************************************
 * Macros definitions (should better go to some other header)
 ******************************************************************************/

/*
 *  Execute WAITI 0 (enabling interrupts) only if *(ptr) is zero.
 *  The decision to execute WAITI is done atomically by disabling
 *  interrupts at level 'level' (level must be a constant)
 *  before checking the pointer.  Interrupts are always re-enabled
 *  on exit from this macro.
 */
#define _WAITI_ON_PTR(ptr, level)                       \
do {                                                    \
    int __tmp;                                          \
    __asm__ ("  rsil  %0, " #level " \n"                \
			 "  l32i  %0, %1, 0 \n"                     \
			 "  bnez  %0, 1f    \n"                     \
			 "  waiti 0         \n"                     \
             "1:rsil  %0, 0     \n"                     \
             : "=a" (__tmp) : "a" (ptr) : "memory");    \
} while(0)

/* ...enable gdbstub */
//#define XF_CFG_USE_GDBSTUB              0

#ifndef XF_CFG_USE_GDBSTUB
/* ...maybe "level" should be hidden here - we always magically set 15 */
#define WAITI_ON_PTR(ptr, level)    _WAITI_ON_PTR(ptr, level)
#else
/* ...if debugger is enabled, do polling instead of waiting */
static inline void WAITI_ON_PTR(volatile u32 *ptr, u32 level)
{
    extern void poll_debug_ring(void);
    
    while (*ptr == 0)
    {
        /* ...should be called with interrupts disabled - tbd */
        poll_debug_ring();
    }
}
#endif

/*******************************************************************************
 * Remote IPI interrupt mode
 ******************************************************************************/

/* ...enable/disable IPI interrupt */
static inline void xf_ipi_enable(u32 core, int on)
{
    if (on)
        _xtos_ints_on(1 << XF_PROXY_IPI_NUM(core));
    else
        _xtos_ints_off(1 << XF_PROXY_IPI_NUM(core));
}

/* ...wait in low-power mode for interrupt arrival if "ptr" is 0 */
static inline void xf_ipi_wait(u32 core)
{
    xf_core_ro_data_t  *ro = XF_CORE_RO_DATA(core);
    
    /* ...enable IPI interrupt before sleeping */
    xf_ipi_enable(core, 1);

    /* ...wait in low-power mode, atomically checking *ipc != 0 */
    WAITI_ON_PTR(&ro->ipc.wait, 15);

    /* ...force disabling of IPI interrupts */
    xf_ipi_enable(core, 0);

    /* ...reset waiting object upon leaving */
    ro->ipc.wait = 0;
}
#else
#define _WAITI_ON_PTR(ptr, level)                       \
    do {                                                    \
        int __tmp;                                          \
        __asm__ ("  rsil  %0, " #level " \n"                \
                "  l32i  %0, %1, 0 \n"                     \
                "  bnez  %0, 1f    \n"                     \
                "  waiti 0         \n"                     \
                "1:rsil  %0, 0     \n"                     \
                : "=a" (__tmp) : "a" (ptr) : "memory");    \
    } while(0)

#define WAITI_ON_PTR(ptr, level)    _WAITI_ON_PTR(ptr, level)
static inline void xf_ipi_wait(u32 core)
{
#if 0    
    //  VOS_EnableInterrupt(DSP_IPC_FROM_AP_INT_NO);
    _xtos_ints_on(1 << DSP_IPC_FROM_AP_INT_NO);
    while(1)
    {
        if(waitstate ==1)
        {
            //            VOS_DisableInterrupt(DSP_IPC_FROM_AP_INT_NO);
            _xtos_ints_off(1 << DSP_IPC_FROM_AP_INT_NO);
            waitstate = 0;
            break;
        }
    }
#else

    _xtos_ints_on(1 << DSP_IPC_FROM_AP_INT_NO);
    /* ...wait in low-power mode, atomically checking *ipc != 0 */
    WAITI_ON_PTR(&waitstate, 15);

    /* ...force disabling of IPI interrupts */

    _xtos_ints_off(1 << DSP_IPC_FROM_AP_INT_NO);
    /* ...reset waiting object upon leaving */
    waitstate = 0;
    
#endif
}	
#endif
#ifdef XAF_ENABLE_NON_HIKEY
/* ...complete IPI waiting (may be called from any context on local core) */
static inline void xf_ipi_resume(u32 core)
{
    xf_core_ro_data_t  *ro = XF_CORE_RO_DATA(core);
    
    /* ...single instruction is written atomically; no need to mask interrupts */
    ro->ipc.wait = 1;
}
#else
/* ...complete IPI waiting (may be called from any context on local core) */
static inline void xf_ipi_resume(u32 core)
{
    unsigned int ipc_int_state = 0;
    unsigned int ipc_data = 0;

    _xtos_ints_off(1 << DSP_IPC_FROM_AP_INT_NO);

    //process message
    ipc_int_state = SYS_IPC_CPUIRST(DSP_SYS_IPC_BASE_ADDR_NS, SYS_IPC_CORE_HIFI);

    if (ipc_int_state & BIT_MASK(DSP_AP_TO_DSP_MAILBOX_NO)) {       //mailbox-18
        SYS_IPC_ICLR(DSP_SYS_IPC_BASE_ADDR_NS, DSP_AP_TO_DSP_MAILBOX_NO) = BIT_MASK(SYS_IPC_CORE_HIFI);
        waitstate = 1;
    }
    
    //_xtos_ints_on(1 << DSP_IPC_FROM_AP_INT_NO);

    return;
}	
#endif
#if 0//ndef HIKEY_XAF_IPC_COMMENT_OUT
/* ...notify remote side about status change */
//#define XF_PROXY_NOTIFY_PEER(core)      dsp_ipc_send_irq_to_ap()

static inline void dsp_ipc_send_irq_to_ap(void)
{
    unsigned int mode = 0;
    unsigned int mode_1 = 0;

    mode = SYS_IPC_MODE(DSP_SYS_IPC_BASE_ADDR_NS, DSP_DSP_TO_AP_MAILBOX_NO);

    if (mode & BIT_MASK(SYS_IPC_MODE_IDLE)) {
        mode_1=0;
    } else {
        return;
    }


    SYS_IPC_SOURCE(DSP_SYS_IPC_BASE_ADDR_NS, DSP_DSP_TO_AP_MAILBOX_NO) = BIT_MASK(SYS_IPC_CORE_HIFI);
    SYS_IPC_IMASK(DSP_SYS_IPC_BASE_ADDR_NS, DSP_DSP_TO_AP_MAILBOX_NO) = ~((unsigned int)(BIT_MASK(SYS_IPC_CORE_HIFI)|BIT_MASK(SYS_IPC_CORE_A15)));
    SYS_IPC_DATA(DSP_SYS_IPC_BASE_ADDR_NS, DSP_DSP_TO_AP_MAILBOX_NO, 0) = IPC_ACPU_INT_SRC_HIFI_MSG;
    SYS_IPC_MODE(DSP_SYS_IPC_BASE_ADDR_NS, DSP_DSP_TO_AP_MAILBOX_NO) = BIT_MASK(SYS_IPC_MODE_AUTOACK);
    SYS_IPC_SEND(DSP_SYS_IPC_BASE_ADDR_NS, DSP_DSP_TO_AP_MAILBOX_NO) = BIT_MASK(SYS_IPC_CORE_HIFI);

    return;
}	
#endif
/* ...assert IPI interrupt on remote core - board-specific */
static inline void xf_ipi_assert(u32 core)
{
    XF_PROXY_NOTIFY_PEER(core);
}
	
#ifdef XAF_ENABLE_NON_HIKEY
/* ...initialize IPI subsystem */
static inline int xf_ipi_init(u32 core)
{
    xf_core_ro_data_t  *ro = XF_CORE_RO_DATA(core);
    extern void (* const xf_ipi_handlers[])(void);
    
    /* ...reset IPC data - no interrupt yet */
    ro->ipc.wait = 0;

    /* ...install interrupt handler */
    _xtos_set_interrupt_handler(XF_PROXY_IPI_NUM(core), xf_ipi_handlers[core]);

    return 0;
}
#else
/* ...initialize IPI subsystem */
static inline int xf_ipi_init(u32 core)
{

    waitstate =0;

    dsp_debug_init();
    //dsp_init_share_mem(HIKEY_AP2DSP_MSG_QUEUE_ADDR,HIKEY_DSP2AP_MSG_QUEUE_SIZE);
    /* unlock reg */
    SYS_IPC_LOCK(DSP_SYS_IPC_BASE_ADDR_NS) = 0x1ACCE551;
    //VOS_ConnectInterrupt(DSP_IPC_FROM_AP_INT_NO, _ap_to_dsp_ipc_irq_proc);
    VOS_ConnectInterrupt(DSP_IPC_FROM_AP_INT_NO, xf_ipi_resume);

//    VOS_EnableInterrupt(DSP_IPC_FROM_AP_INT_NO);

    return;   
}	
#endif

/*******************************************************************************
 * Shared memory operations
 ******************************************************************************/

/* ...NULL-address specification */
#define XF_PROXY_NULL       (~0U)

/* ...invalid proxy address */
#define XF_PROXY_BADADDR    XF_CFG_REMOTE_IPC_POOL_SIZE
/* ...translate buffer address to shared proxy address */
static inline u32 xf_ipc_b2a(u32 core, void *b)
{
    xf_shmem_data_t    *shmem = XF_CORE_DATA(core)->shmem;
    void               *start = shmem->buffer;

    if (b == NULL)
        return XF_PROXY_NULL;
    else if ((s32)(b - start) < XF_CFG_REMOTE_IPC_POOL_SIZE)
        return (u32)(b - start);
    else
        return XF_PROXY_BADADDR;
}
/* ...translate shared proxy address to local pointer */
static inline void * xf_ipc_a2b(u32 core, u32 address)
{
    xf_shmem_data_t    *shmem = XF_CORE_DATA(core)->shmem;
    void               *start = shmem->buffer;
    
    if (address < XF_CFG_REMOTE_IPC_POOL_SIZE)
        return start + address;
    else if (address == XF_PROXY_NULL)
        return NULL;
    else
        return (void *)-1;
}

/* ...component association with remote IPC client */
static inline void xf_ipc_component_addref(u32 session)
{
}

/* ...delete record about component association with remote IPC client */
static inline void xf_ipc_component_rmref(u32 id)
{
}

/* ...system-specific IPC layer initialization */
extern int xf_ipc_init(u32 core);

/*******************************************************************************
 * Mutex definitions
 ******************************************************************************/

/* ...export shared memory access macros */
#define MUTEX_SHARED_READ(core)         \
    ({  xf_core_ro_data_t  *__ro = XF_CORE_RO_DATA(core); __ro->lock[0]; })

#define MUTEX_SHARED_WRITE(core, val)   \
    ({  xf_core_ro_data_t  *__ro = XF_CORE_RO_DATA(core); __ro->lock[0] = (val); })

/* ...include library header */
#include "lib/mutex.h"

#if XF_CFG_CORES_NUM > 1
/* ...rename API functions */
static inline void xf_mutex_lock(u32 core)
{
    mutex_lock(core);
}

static inline void xf_mutex_unlock(u32 core)
{
    mutex_unlock(core);
}

#else
/* ...for single-core setting no locking is actually needed */
static inline void xf_mutex_lock(u32 core)
{
}

static inline void xf_mutex_unlock(u32 core)
{
}

#endif  /* XF_CFG_CORES_NUM > 1 */
