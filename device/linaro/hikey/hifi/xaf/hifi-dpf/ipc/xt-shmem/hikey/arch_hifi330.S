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
/******************************************************************************
  arch_hifi330.S
******************************************************************************/

#include <xtensa/coreasm.h>
#include <xtensa/simcall.h>
#include <xtensa/corebits.h>
#include <xtensa/config/system.h>
#include <xtensa/config/core.h>
#include "arch_hifi330.h"

    .global    OSStartHighRdy
    .global    OSTaskSwHook
    .global    g_pstVosTCBHighRdy
    .global    g_bVosRunning

//    .data
    .section    .dram0.data, "aw"
    .type   OSIntCtxSw_Occur,@object
OSIntCtxSw_Occur:
    .byte 0

    .begin      literal_prefix .iram0
    .section    .iram0.text, "ax"
    .align 4
    .globl  VOSStartHighRdy
    .type   VOSStartHighRdy,@function
    .align  4
/*****************************************************************************
 VOSStartHighRdy
*****************************************************************************/
VOSStartHighRdy:
    ENTRY(16)

    /*OSIntCtxSw_Occur*/
    movi    a2,  OSIntCtxSw_Occur
    movi    a3,  0
    s8i     a3,  a2, 0

    movi    a2,  g_bVosRunning
    movi    a3,  1
    s8i    a3,  a2, 0

#if 0
    /*OS_TaskSwHook */
    movi    a2, OS_TaskSwHook
    callx4  a2
#endif
    call0 OS_TaskSwitch
    .end      literal_prefix

    .begin      literal_prefix .iram0
    .section    .iram0.text, "ax"
    .globl  VOSCtxSw
    .type   VOSCtxSw,@function
    .align  4
/*****************************************************************************
 OSCtxSw
*****************************************************************************/
VOSCtxSw:
    entry   sp,  XT_SOL_FRMSZ
    rsr     a2,  PS
    s32i    a0,  sp, XT_SOL_PC
    s32i    a2,  sp, XT_SOL_PS
    movi    a2,  g_pstVosTCBCur
    movi    a3,  0
    l32i    a2,  a2, 0
    s32i    a3,  sp, XT_SOL_EXIT
    s32i    sp,  a2, OSTCBStkPtr        /* g_pstVosTCBCur->OSTCBStkPtr = SP;      */

    call4   xthal_window_spill
#if 0
    /*OS_TaskSwHook */
    movi    a2, OS_TaskSwHook
    callx4  a2
#endif

    /* CPENABLE, co-processor state. */
    movi    a3, g_pstVosTCBCur
    l32i    a3, a3, 0
    l32i    a2, a3, OSTCBStkBottom
    movi    a3, 0

    wsr     a3, CPENABLE                /* disable all co-processors        */
    s8i     a3, a2, XT_CPENABLE         /* cp_state->cpenable = 0;          */


    call0   OS_TaskSwitch
    .end      literal_prefix

    .begin      literal_prefix .iram0
    .section    .iram0.text, "ax"
    .globl  VOSIntCtxSw
    .type   VOSIntCtxSw,@function
    .align  4
/*****************************************************************************
 OSIntCtxSw
*****************************************************************************/
VOSIntCtxSw:
    ENTRY(16)
#if 0
    /*OS_TaskSwHook */
    movi    a2, OS_TaskSwHook
    callx4  a2
#endif

    /*CPENABLE task co-processor, CPENABLE.  */
    movi    a3, g_pstVosTCBCur
    l32i    a3, a3, 0
    l32i    a2, a3, OSTCBStkBottom
    rsr     a3, CPENABLE
    s8i     a3, a2, XT_CPENABLE
    movi    a3, 0
    wsr     a3, CPENABLE

    movi    a2, OSIntCtxSw_Occur
    movi    a3, 1
    s8i     a3, a2, 0


    RET(16)

    .end      literal_prefix

    .begin      literal_prefix .iram0
    .section    .iram0.text, "ax"
    .global    OSCPUSaveSR
    .type   OSCPUSaveSR,@function
    .align 4
/*****************************************************************************
 OSCPUSaveSR
*****************************************************************************/
OSCPUSaveSR:
    entry     a1,32
    rsil    a2,15
    esync
    retw
    .end      literal_prefix

    .begin      literal_prefix .iram0
    .section    .iram0.text, "ax"
    .global    OSCPURestoreSR
    .type   OSCPURestoreSR,@function
    .align 4
/*****************************************************************************
 OSCPURestoreSR
*****************************************************************************/
OSCPURestoreSR:
    entry     a1,32
    wsr.ps    a2
    esync
    retw
    .end      literal_prefix

    .begin      literal_prefix .iram0
    .section    .iram0.text, "ax"
    .global    OS_TaskSwitch
    .type   OS_TaskSwitch,@function
    .align 4
/*****************************************************************************
 OS_TaskSwitch
*****************************************************************************/
OS_TaskSwitch:

    /*1)
    g_ucVosPrioCur = g_ucVosPrioHighRdy;
    g_pstVosTCBCur = g_pstVosTCBHighRdy;
    SP = g_pstVosTCBHighRdy->OSTCBStkPtr;
    */
    movi    a2,  g_ucVosPrioHighRdy
    movi    a3,  g_ucVosPrioCur
    l8ui   a2,  a2, 0
    movi    a4,  g_pstVosTCBHighRdy
    s8i     a2,  a3, 0
    movi    a2,  g_pstVosTCBCur
    l32i    a3,  a4, 0
    l32i    sp,  a3, OSTCBStkPtr
    s32i    a3,  a2, 0


    l32i    a2,  sp, XT_STK_EXIT
    bnez    a2,  .L_in_int


    l32i    a3,  sp, XT_SOL_PS
    l32i    a0,  sp, XT_SOL_PC

    rsync

    wsr     a3,  PS
    retw

.L_in_int:
    /* co-processor CPENABLE*/
    movi    a3, g_pstVosTCBCur
    l32i    a3, a3, 0
    l32i    a2, a3, OSTCBStkBottom
    l8ui    a3, a2, XT_CPENABLE
    wsr     a3, CPENABLE


    call0   OS_ContextRestore
    rsync

    l32i    a0, sp, XT_STK_EXIT
    ret
    .end      literal_prefix

    .begin      literal_prefix .iram0
    .section    .iram0.text, "ax"
    .globl  OS_ContextSave
    .type   OS_ContextSave,@function
    .align  4
/*****************************************************************************
 OS_ContextSave
*****************************************************************************/
OS_ContextSave:

    /*1)：
    A2
    A3
    A4
    A5
    A6
    A7
    A8
    A9
    A10
    A11
    SAR,LBENG,LEND,LCOUNT*/
    s32i    a2,  sp, XT_STK_A2
    s32i    a3,  sp, XT_STK_A3
    s32i    a4,  sp, XT_STK_A4
    s32i    a5,  sp, XT_STK_A5
    s32i    a6,  sp, XT_STK_A6
    s32i    a7,  sp, XT_STK_A7
    s32i    a8,  sp, XT_STK_A8
    s32i    a9,  sp, XT_STK_A9
    s32i    a10, sp, XT_STK_A10
    s32i    a11, sp, XT_STK_A11
    s32i    a14, sp, XT_STK_A14
    s32i    a15, sp, XT_STK_A15


    rsr     a3,  SAR
    s32i    a3,  sp, XT_STK_SAR

    rsr     a3,  LBEG
    s32i    a3,  sp, XT_STK_LBEG
    rsr     a3,  LEND
    s32i    a3,  sp, XT_STK_LEND
    rsr     a3,  LCOUNT
    s32i    a3,  sp, XT_STK_LCOUNT

    /*2)*/
    mov     a9,  a0
    s32i    a12, sp, XT_STK_TMP+0
    s32i    a13, sp, XT_STK_TMP+4
    s32i    a9,  sp, XT_STK_TMP+8
    l32i    a12, sp, XT_STK_A12
    l32i    a13, sp, XT_STK_A13
    l32i    a9,  sp, XT_STK_A9
    addi    sp,  sp, XT_STK_FRMSZ
    call0   xthal_window_spill_nw
    addi    sp,  sp, -XT_STK_FRMSZ
    l32i    a12, sp, XT_STK_TMP+0
    l32i    a13, sp, XT_STK_TMP+4
    l32i    a9,  sp, XT_STK_TMP+8

    addi    a2,  sp, XT_STK_EXTRA
    call0   xthal_save_extra_nw

    mov     a0, a9
    ret
    .end      literal_prefix

    .begin      literal_prefix .iram0
    .section    .iram0.text, "ax"
    .global OS_ContextRestore
    .type   OS_ContextRestore,@function
    .align  4
/*****************************************************************************
 OS_ContextRestore
*****************************************************************************/
OS_ContextRestore:
    /*
    1) xthal_restore_extra_nw*/
    mov     a13, a0
    addi    a2,  sp, XT_STK_EXTRA
    call0   xthal_restore_extra_nw
    mov     a0,  a13

    /*2)：
    LBEG
    LEND
    LCOUNT
    A2
    SAR
    A3
    A4
    A5
    A6
    A7
    A8
    A9
    A10
    A11
    A12
    A13
    A14
    A15*/
    l32i    a2,  sp, XT_STK_LBEG
    l32i    a3,  sp, XT_STK_LEND
    wsr     a2,  LBEG
    l32i    a2,  sp, XT_STK_LCOUNT
    wsr     a3,  LEND
    wsr     a2,  LCOUNT

    l32i    a3,  sp, XT_STK_SAR
    l32i    a2,  sp, XT_STK_A2
    wsr     a3,  SAR
    l32i    a3,  sp, XT_STK_A3
    l32i    a4,  sp, XT_STK_A4
    l32i    a5,  sp, XT_STK_A5
    l32i    a6,  sp, XT_STK_A6
    l32i    a7,  sp, XT_STK_A7
    l32i    a8,  sp, XT_STK_A8
    l32i    a9,  sp, XT_STK_A9
    l32i    a10, sp, XT_STK_A10
    l32i    a11, sp, XT_STK_A11

    l32i    a12, sp, XT_STK_A12
    l32i    a13, sp, XT_STK_A13
    l32i    a14, sp, XT_STK_A14
    l32i    a15, sp, XT_STK_A15

    ret

    .end      literal_prefix

    .begin      literal_prefix .iram0
    .section    .iram0.text, "ax"
    .globl  OS_IntEnter
    .type   OS_IntEnter,@function
    .align  4
OS_IntEnter:

    /* Save a12-13 in the stack frame as required by _xt_context_save. */
    s32i    a12, sp, XT_STK_A12
    s32i    a13, sp, XT_STK_A13

    /* Save return address in a safe place (free a0). */
    mov     a12, a0

    /* Save the rest of the interrupted context (preserves A12-13). */
    call0   OS_ContextSave

    /*
    Save interrupted task's SP in TCB only if not nesting.
    Manage nesting directly rather than call the generic OSIntEnter()
    (in windowed ABI we can't call a C function here anyway because PS.EXCM is still set).
    */
    movi    a2,  g_bVosRunning
    movi    a3,  g_ucVosIntNesting
    l8ui   a2,  a2, 0                  /*  if (g_bVosRunning == OS_TRUE) {         */
    beqz    a2,  2f
    l8ui   a2,  a3, 0                  /*      if (g_ucVosIntNesting == 0) {        */
    bnez    a2,  1f
    movi    a4,  g_pstVosTCBCur
    l32i    a4,  a4, 0
    s32i    sp,  a4, OSTCBStkPtr        /*          g_pstVosTCBCur->OSTCBStkPtr = SP; */
1:                                      /*      }                               */
    addi    a2,  a2, 1                  /*      if (g_ucVosIntNesting < 255u) {      */
    bgeui   a2,  256, 2f                /*           g_ucVosIntNesting++;            */
    s8i    a2,  a3, 0                  /*      }                               */
2:                                      /*  }                                   */

    /* Retrieve the return address and return to interrupt handler. */
    mov     a0,  a12
    ret
    .end      literal_prefix

    .begin      literal_prefix .iram0
    .section    .iram0.text, "ax"
    .globl  OS_IntExit
    .type   OS_IntExit,@function
    .align  4
OS_IntExit:
#if 0
    /* Call VOSIntExit() to deal with nesting and call the scheduler. */
    movi    a2, VOSIntExit

    rsil    a0, XCHAL_EXCM_LEVEL    /* enter critical section       */
    callx4  a2                      /* VOSIntExit()                  */
#endif
    /* Context-switch deferred from OSIntCtxSw(). Still in critical section. */
    movi    a2, OSIntCtxSw_Occur
    l8ui    a3, a2, 0
    beqz    a3, 1f                  /* if (OSIntCtxSw_Occur) {         */
    movi    a3, 0                   /*    OSIntCtxSw_Occur = false;    */
    s8i     a3, a2, 0
    call0   OS_TaskSwitch           /*    tail-call dispatcher;     */
    /* Never returns here. */       /* }                            */
1:

    /*
    We come here only if there was no context switch, that is if this
    is a nested interrupt or the interrupted task was not preempted.
    We are still on the same stack so there's no need to load the SP.
    */

    /* Restore full context from interrupt stack frame and return to exit dispatcher. */
    call0   OS_ContextRestore

    /*
    Must return via the exit dispatcher corresponding to the entrypoint from which
    this was called. Interruptee's A0, A1, PS, PC are restored and the interrupt
    stack frame is deallocated in the exit dispatcher.
    */
    l32i    a0, sp, XT_STK_EXIT
    ret
    .end      literal_prefix

    .begin      literal_prefix .iram0
    .section    .iram0.text, "ax"
    .globl  OS_GetTaskCoprocState
    .type   OS_GetTaskCoprocState,@function
    .align  4
OS_GetTaskCoprocState:

    movi    a2,  g_bVosRunning
    movi    a3,  g_ucVosIntNesting
    l8ui   a2,  a2, 0
    l8ui   a3,  a3, 0
    beqz    a2,  1f
    bnez    a3,  1f
    movi    a15, g_pstVosTCBCur
    l32i    a15, a15, 0
    beqz    a15, 2f
    l32i    a15,  a15, OSTCBStkBottom
    ret

1:  movi    a15, 0
2:  ret
    .end      literal_prefix


