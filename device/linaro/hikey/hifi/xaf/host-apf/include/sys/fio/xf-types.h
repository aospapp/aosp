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

#ifndef __XF_H
#error "xf-types.h mustn't be included directly"
#endif

/*******************************************************************************
 * Standard includes
 ******************************************************************************/

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <assert.h>
#include <errno.h>

#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <limits.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <sys/time.h>

/*******************************************************************************
 * Primitive types
 ******************************************************************************/

typedef uint32_t        u32;
typedef int32_t         s32;
typedef uint16_t        u16;
typedef int16_t         s16;
typedef uint8_t         u8;
typedef int8_t          s8;

/*******************************************************************************
 * Macros definitions
 ******************************************************************************/

/* ...NULL-address specification */
#define XF_PROXY_NULL           (~0U)

/* ...invalid proxy address */
#define XF_PROXY_BADADDR        XF_CFG_REMOTE_IPC_POOL_SIZE

/*******************************************************************************
 * Auxiliary helpers
 ******************************************************************************/

/* ...next power-of-two calculation */
#define xf_next_power_of_two(v)     __xf_power_of_two_1((v) - 1)
#define __xf_power_of_two_1(v)      __xf_power_of_two_2((v) | ((v) >> 1))
#define __xf_power_of_two_2(v)      __xf_power_of_two_3((v) | ((v) >> 2))
#define __xf_power_of_two_3(v)      __xf_power_of_two_4((v) | ((v) >> 4))
#define __xf_power_of_two_4(v)      __xf_power_of_two_5((v) | ((v) >> 8))
#define __xf_power_of_two_5(v)      __xf_power_of_two_6((v) | ((v) >> 16))
#define __xf_power_of_two_6(v)      ((v) + 1)

/* ...check if non-zero value is a power-of-two */
#define xf_is_power_of_two(v)       (((v) & ((v) - 1)) == 0)

