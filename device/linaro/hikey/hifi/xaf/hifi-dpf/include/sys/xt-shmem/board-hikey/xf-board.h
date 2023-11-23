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
 * board-hikey/xf-board.h
 *
 * HiKey960-specific definitions
 *
 *******************************************************************************/

#ifndef __XF_H
#error "xf-board.h mustn't be included directly"
#endif

/*******************************************************************************
 * Global constants definitions
 ******************************************************************************/

/* ...shared memory interface address */
#define XF_CFG_SHMEM_ADDRESS(core)      HIFI_HIKEY_SHARE_MEM_ADDR

/* ...tracing buffer configuration (place at the end of memory) */
#define XF_CFG_TRACE_START(core)        0x78000000
#define XF_CFG_TRACE_END(core)          0x7FFFFFFF
#if 1
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
/* ...inter-processor interrupt number (SEL:10 - LEVEL:15) */
#define XF_PROXY_IPI_NUM(core)          15

/*******************************************************************************
 * External functions
 ******************************************************************************/

/* ...gdb stub initialization */
extern void init_gdbstub(void);

/* ...emit breakpoint */
extern void breakpoint(void);
#if 0// def HIKEY_XAF_IPC_COMMENT_OUT
/*******************************************************************************
 * Public proxy API
 ******************************************************************************/

/* ...notify remote side about status change */
#define XF_PROXY_NOTIFY_PEER(core)      h2_wake_core()

/* ...clear pending interrupt mask */
#define XF_PROXY_SYNC_PEER(core)        h2_local_ack()

/*******************************************************************************
 * Auxiliary helper functions
 ******************************************************************************/

/* ...generate inter-processor interrupt for remote core */
static inline void h2_wake_core(void)
{
    /* ...we have single INPUT-interrupt pin */
	*(volatile unsigned int *)0xec800044 = 1;
}

/* ...acknowledge pending IPI interrupt */
static inline void h2_local_ack(void)
{
    /* ...asserted interrupt cannot be cleared */
}

/*******************************************************************************
 * Board specific init
 ******************************************************************************/

static inline void xf_board_init(void)
{
    /* ...initialize GDB debugging interface */
    init_gdbstub();

    /* ...indicate the board has started */
	*(volatile unsigned int *)0xec800100 = 1;
}
#else
//#define XF_PROXY_NOTIFY_PEER(core)      dsp_ipc_send_irq_to_ap()
#define XF_PROXY_NOTIFY_PEER(core)      h2_wake_core()
/* ...clear pending interrupt mask */
#define XF_PROXY_SYNC_PEER(core)        h2_local_ack()

/* ...generate inter-processor interrupt for remote core */
static inline void h2_wake_core(void)
{
    unsigned int mode = 0;
    unsigned int mode_1 = 0;

    //    mode = SYS_IPC_MODE(DSP_SYS_IPC_BASE_ADDR_NS, DSP_DSP_TO_AP_MAILBOX_NO);
    //mode = (* ((unsigned int volatile *) (0xe896b000 + ((2) << 6) + 0x10)));
    mode = * (volatile unsigned int*) 0xe896b090;

    //if (mode & BIT_MASK(SYS_IPC_MODE_IDLE)) {
    //if (mode & (1 << (4))) {
    if (mode & 0x10) {
        mode_1=0;
    } else {
        return;
    }

    //SYS_IPC_SOURCE(DSP_SYS_IPC_BASE_ADDR_NS, DSP_DSP_TO_AP_MAILBOX_NO) = BIT_MASK(SYS_IPC_CORE_HIFI);
    //(* ((unsigned int volatile *) (0xe896b000 + ((2) << 6))))= (1 << (4));
    * (volatile unsigned int *) 0xe896b080 = (0x10);

    //SYS_IPC_IMASK(DSP_SYS_IPC_BASE_ADDR_NS, DSP_DSP_TO_AP_MAILBOX_NO) = ~((unsigned int)(BIT_MASK(SYS_IPC_CORE_HIFI)|BIT_MASK(SYS_IPC_CORE_A15)));
    //(* ((unsigned int volatile *) (0xe896b000 + ((2) << 6) + 0x14))) = ~ ((unsigned int)((1 << (4))|(1 << (0))));
    * (volatile unsigned int *)0xe896b094 = ~ ((unsigned int)(0x11));

    //SYS_IPC_DATA(DSP_SYS_IPC_BASE_ADDR_NS, DSP_DSP_TO_AP_MAILBOX_NO, 0) = IPC_ACPU_INT_SRC_HIFI_MSG;
    //(* ((unsigned int volatile *) (0xe896b000 + ((2) << 6) + 0x20 + ((0) << 2)))) = 1;
    * (volatile unsigned int *) 0xe896b0A0 = 1;

    //SYS_IPC_MODE(DSP_SYS_IPC_BASE_ADDR_NS, DSP_DSP_TO_AP_MAILBOX_NO) = BIT_MASK(SYS_IPC_MODE_AUTOACK);
    //(* ((unsigned int volatile *) (0xe896b000 + ((2) << 6) + 0x10))) = (1 << (0));
    * (volatile unsigned int *) 0xe896b090 = 1;

    //SYS_IPC_SEND(DSP_SYS_IPC_BASE_ADDR_NS, DSP_DSP_TO_AP_MAILBOX_NO) = BIT_MASK(SYS_IPC_CORE_HIFI);
    //(* ((unsigned int volatile *) (0xe896b000 + ((2) << 6) + 0x1c))) = (1 << (4));
    * (volatile unsigned int *) 0xe896b09C = 0x10;

    return;
}

/* ...acknowledge pending IPI interrupt */
static inline void h2_local_ack(void)
{
        /* ...asserted interrupt cannot be cleared */
}

#endif

