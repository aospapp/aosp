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

#ifndef __DSP_DRIVER_IPC_H__
#define __DSP_DRIVER_IPC_H__

#if 0
#define DSP_IPC_FROM_AP_INT_NO     (3)
#define DSP_SYS_IPC_BASE_ADDR_NS   (0xe896b000)
#define DSP_AP_TO_DSP_MAILBOX_NO   (18)
#define DSP_DSP_TO_AP_MAILBOX_NO   (2)
#define IPC_BUSY_RETRY_COUNT       (1000)
#define IPC_ACPU_INT_SRC_HIFI_MSG  (1)
#define BIT_MASK(n) (1 << (n))

#define WORD_REF(address) (* ((unsigned int volatile *) (address)))

#define SYS_IPC_LOCK(base)                  WORD_REF(base + 0xA00)
#define SYS_IPC_ICLR(base, box)             WORD_REF(base + ((box) << 6) + 0x18)
#define SYS_IPC_DATA(base, box, num)        WORD_REF(base + ((box) << 6) + 0x20 + ((num) << 2))
#define SYS_IPC_CPUIRST(base, core)         WORD_REF(base + 0x804 + ((core) << 3))
#define SYS_IPC_MODE(base, box)             WORD_REF(base + ((box) << 6) + 0x10)
#define SYS_IPC_SOURCE(base, box)           WORD_REF(base + ((box) << 6))
#define SYS_IPC_DEST(base, box)             WORD_REF(base + ((box) * 64) + 0x04)
#define SYS_IPC_SEND(base, box)             WORD_REF(base + ((box) << 6) + 0x1c)
#define SYS_IPC_IMASK(base, box)            WORD_REF(base + ((box) << 6) + 0x14)
#define SYS_IPC_DCLR(base, box)             WORD_REF(base + ((box) * 64) + 0x08)
#define SYS_IPC_CPUIMST(base, core)         WORD_REF(base + 0x800 + ((core) * 8))
#define SYS_IPC_MODE_ACK     (7)
#define SYS_IPC_MODE_IDLE    (4)
#define SYS_IPC_MODE_AUTOACK (0)

#define SYS_IPC_CORE_HIFI  (4)
#define SYS_IPC_CORE_A15   (0)
#define SYS_IPC_CORE_LPM3  (3)
#endif
#if 0
extern void dsp_ipc_init(void);
extern void dsp_ipc_wait(void);
#endif
#endif

