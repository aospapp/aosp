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
 * xf-hal.h
 *
 * Platform-specific HAL definitions
 *
 *******************************************************************************/

#ifndef __XF_H
#error "xf-hal.h mustn't be included directly"
#endif

/*******************************************************************************
 * Includes
 ******************************************************************************/

/* ...primitive types */
#include "xf-types.h"

/* ...XTOS runtime */
#include <xtensa/xtruntime.h>

/*******************************************************************************
 * Auxilliary macros definitions
 ******************************************************************************/

/* ...use system-specific cache-line size */
#define XF_PROXY_ALIGNMENT              XCHAL_DCACHE_LINESIZE

/* ...properly aligned shared memory structure */
#define __xf_shmem__        __attribute__((__aligned__(XF_PROXY_ALIGNMENT)))

/*******************************************************************************
 * Interrupt control
 ******************************************************************************/

/* ...disable interrupts on given core */
static inline u32 xf_isr_disable(u32 core)
{
    /* ...no actual dependency on the core identifier */
    return XTOS_SET_INTLEVEL(XCHAL_EXCM_LEVEL);
}

/* ...enable interrupts on given core */
static inline void xf_isr_restore(u32 core, u32 status)
{
    /* ...no actual dependency on the core identifier */
    XTOS_RESTORE_INTLEVEL(status);
}

/*******************************************************************************
 * Auxiliary system-specific functions
 ******************************************************************************/

#if XF_CFG_CORES_NUM > 1
/* ...current core identifier (from HW) */
static inline u32 xf_core_id(void)
{
    /* ...retrieve core identifier from HAL */
    return (u32) xthal_get_prid();
}
#else
#define xf_core_id()        0
#endif

/*******************************************************************************
 * Atomic operations (atomicity is assured on local core only)
 ******************************************************************************/

static inline int xf_atomic_test_and_set(volatile u32 *bitmap, u32 mask)
{
    u32     status;
    u32     v;

    /* ...atomicity is assured by interrupts masking */
    status = XTOS_DISABLE_ALL_INTERRUPTS;
    v = *bitmap, *bitmap = v | mask;
    XTOS_RESTORE_INTLEVEL(status);
    
    return !(v & mask);
}

static inline int xf_atomic_test_and_clear(volatile u32 *bitmap, u32 mask)
{
    u32     status;
    u32     v;

    /* ...atomicity is assured by interrupts masking */
    status = XTOS_DISABLE_ALL_INTERRUPTS;
    v = *bitmap, *bitmap = v & ~mask;
    XTOS_RESTORE_INTLEVEL(status);
    
    return (v & mask);
}

static inline u32 xf_atomic_set(volatile u32 *bitmap, u32 mask)
{
    u32     status;
    u32     v;

    /* ...atomicity is assured by interrupts masking */
    status = XTOS_DISABLE_ALL_INTERRUPTS;
    v = *bitmap, *bitmap = (v |= mask);
    XTOS_RESTORE_INTLEVEL(status);

    return v;
}

static inline u32 xf_atomic_clear(volatile u32 *bitmap, u32 mask)
{
    u32     status;
    u32     v;
    
    /* ...atomicity is assured by interrupts masking */
    status = XTOS_DISABLE_ALL_INTERRUPTS;
    v = *bitmap, *bitmap = (v &= ~mask);
    XTOS_RESTORE_INTLEVEL(status);

    return v;
}

/*******************************************************************************
 * Abortion macro (debugger should be configured)
 ******************************************************************************/

/* ...breakpoint function */
extern void breakpoint(void);

/* ...abort execution (enter into debugger) */
#define __xf_abort()            breakpoint()
