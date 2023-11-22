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
#include "xtensa-defs.h"

.macro	SAVE_	reg, loc
	rsr	a1, \reg
	s32i	a1, a3, \loc * 4
.endm
.macro	SAVE	reg
	SAVE_	\reg, \reg
.endm

.macro	LOAD_	reg, loc
	l32i	a1, a3, \loc * 4
	wsr	a1, \reg
.endm
.macro	LOAD	reg
	LOAD_	\reg, \reg
.endm

	.section ".DebugExceptionVector.text", "ax"
	.global DebugExceptionVector

DebugExceptionVector:
	j	1f
	.align	4
	.literal_position
1:		
	xsr	a2, DEBUG_EXCSAVE
	jx	a2

	.text
	.global DebugExceptionEntry
	.align	4

DebugExceptionEntry:
	j	1f
	.align	4
	.literal_position
1:				
	movi	a2, aregs
	s32i	a0, a2, 0
	s32i	a1, a2, 4
	rsr	a1, DEBUG_EXCSAVE
	s32i	a1, a2, 8
	s32i	a3, a2, 12

	movi	a3, sregs
	SAVE	LBEG
	SAVE	LEND
	SAVE	LCOUNT
	SAVE	SAR
	SAVE	WINDOWBASE
	SAVE	WINDOWSTART

	rsr	a1, DEBUG_PC
	movi	a2, initial_breakpoint
	bne	a1, a2, 1f
	addi	a1, a1, 3
1:
	s32i	a1, a3, DEBUG_PC * 4

	SAVE	EXCSAVE_1
	SAVE_	DEBUG_PS, PS
	SAVE	EXCCAUSE
	SAVE	DEBUGCAUSE
	SAVE	EXCVADDR

	movi	a1, XCHAL_NUM_AREGS / 4 - 1
	movi	a2, aregs
1:
	s32i	a4, a2, 16
	s32i	a5, a2, 20
	s32i	a6, a2, 24
	s32i	a7, a2, 28

	addi	a6, a2, 16
	addi	a5, a1, -1
	rotw	1
	bnez	a1, 1b

	movi	a1, 1
	wsr	a1, windowstart
	movi	a0, 0
	wsr	a0, windowbase
	rsync

	movi	a0, 0
	movi	a1, stack + STACK_SIZE - 20
	rsr	a2, ps
	addi	a2, a2, -PS_EXCM_MASK
	wsr	a2, ps
	rsync

	movi	a4, handle_exception
	callx4	a4

DebugExceptionExit:
	movi	a2, DebugExceptionEntry
	wsr	a2, DEBUG_EXCSAVE

	rsr	a2, ps
	addi	a2, a2, PS_EXCM_MASK
	wsr	a2, ps
	rsync

	movi	a3, sregs
	LOAD	LBEG
	LOAD	LEND
	LOAD	LCOUNT
	/* TODO: handle unlikely return-to-lend case */
	LOAD	SAR
	LOAD	WINDOWBASE
	rsync
	movi	a3, sregs
	LOAD	WINDOWSTART
	LOAD	DEBUG_PC
	LOAD	EXCSAVE_1
	LOAD_	DEBUG_PS, PS
	LOAD	EXCCAUSE
	LOAD	EXCVADDR

	movi	a6, aregs
	movi	a5, XCHAL_NUM_AREGS / 4 - 2
1:
	l32i	a0, a6, 0
	l32i	a1, a6, 4
	l32i	a2, a6, 8
	l32i	a3, a6, 12

	beqz	a5, 2f
	addi	a10, a6, 16
	addi	a9, a5, -1
	rotw	1
	j	1b
2:
	l32i	a4, a6, 16
	l32i	a5, a6, 20
	l32i	a7, a6, 28
	l32i	a6, a6, 24
	rotw	2

	rfi	XCHAL_DEBUGLEVEL


#ifdef LIBC_LEVEL1_HANDLER
	.global	fault_handler
	.align	4
fault_handler:
	rsr	a2, epc1
	addi	a2, a2, 3
	wsr	a2, epc1
	rsync
	movi	a2, mem_err
	s32i	a2, a2, 0

	l32i	a2, a1, 16
	l32i	a3, a1, 20
	addi	a1, a1, 256
	rfe
#endif


	.global init_debug_entry
	.align	4
init_debug_entry:
	entry	a1, 16
	movi	a2, DebugExceptionEntry
	wsr	a2, DEBUG_EXCSAVE
	isync
	retw

	.global breakpoint
	.align	4
breakpoint:
	entry	a1, 16
initial_breakpoint:
	_break	0, 0
	retw
