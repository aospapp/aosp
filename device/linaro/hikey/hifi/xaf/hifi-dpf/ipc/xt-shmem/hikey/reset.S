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
  reset.S
  reset vector for BALONGV7R1 architechure,Any problem pls refer
  to the Xtensa Microprocessor Programmer's Guide.
******************************************************************************/

#include <xtensa/coreasm.h>
#include <xtensa/simcall.h>
#include "reset.h"

    .begin      literal_prefix .ResetVector
    .section    .ResetVector.text, "ax"
    .global     _ResetVector
    .align      4

_ResetVector:
       j       _Reset

    .end        literal_prefix

    /*****************************************************************************
    Reset
    *****************************************************************************/
    .begin      literal_prefix .Reset
    .section    .Reset.text, "ax"
    .global     _Reset
    .align      4

_Reset:
    /* clear all of interrupts*/
       movi    a0, 0
       wsr     a0, INTENABLE

    wsr     a0, IBREAKA_0
    wsr     a0, IBREAKA_1
    wsr     a0, DBREAKA_0
    wsr     a0, DBREAKA_1
       wsr     a0, DBREAKC_0
       wsr     a0, DBREAKC_1
    wsr     a0, IBREAKENABLE

    /* ICOUNT，ICOUNTLEVEL，ICOUNTexception  clear ICOUNT and ICOUNTLEVEL*/
    wsr     a0, ICOUNT
       wsr     a0, ICOUNTLEVEL
       isync

    /*debug disable XCHAL_DEBUGLEVEL*/
       rsil    a1, XCHAL_DEBUGLEVEL - 1

// write windowbase and windowstart, then do rsync.
// reload a0 with 0, because registers may have moved after
// writing windowbase/windowstart.
       wsr     a0, CCOUNT
    /* init register windows*/
       wsr     a0, WINDOWBASE
       rsync
       movi    a1, 1
       wsr     a1, WINDOWSTART

       ssai    0

    /*Level-1 init EXCSAVE_1 level-1 interrupt*/
       wsr     a0, EXCSAVE_1
       wsr     a0, EPC_1
       wsr     a0, EXCCAUSE

    /*Level-2 init EXCSAVE_1 level-2 interrupt*/
       wsr     a0, EPC_2
       wsr     a0, EPS_2
       wsr     a0, EXCSAVE_2

    /*Level-3 init EXCSAVE_1 level-3 interrupt*/
       wsr     a0, EPC_3
       wsr     a0, EPS_3
       wsr     a0, EXCSAVE_3

    /*Level-4 init EXCSAVE_1 level-4 interrupt*/
       wsr     a0, EPC_4
       wsr     a0, EPS_4
       wsr     a0, EXCSAVE_4

    /*Level-5(DEBUG) init EXCSAVE_1 level-5 interrupt*/
       wsr     a0, EPC_5
       wsr     a0, EPS_5
       wsr     a0, EXCSAVE_5

    /*NMI init EXCSAVE_1 level-6(NMI) interrupt */
    wsr     a0, EPC_6
       wsr     a0, EPS_6
       wsr     a0, EXCSAVE_6


       /*0/1/2 init timer 0&1 */
       wsr     a0, CCOMPARE_0
       wsr     a0, CCOMPARE_1
       //wsr     a0, CCOMPARE_2  //hifi3 CCOMPARE_0 CCOMPARE_1

    /* clear all of  */
       movi    a2, XCHAL_INTTYPE_MASK_EXTERN_EDGE | XCHAL_INTTYPE_MASK_SOFTWARE
       wsr     a2, INTCLEAR

       wsr     a0, BR

       /* disable coprocessor */
       /*wsr     a0, CPENABLE*/

       movi    a2, XCHAL_DEBUGLEVEL - 1
       wsr     a2, PS
       rsync


    /*icache init icache */
#if (XCHAL_ICACHE_SIZE != 0)
    #if (XCHAL_ICACHE_SIZE == 8192)     //212GP and 330HiFi
       movi    a2, 64
    #elif (XCHAL_ICACHE_SIZE == 16384)  //232L and 570T
    movi    a2, 128
    #elif (XCHAL_ICACHE_SIZE == 32768)  //BALONGV7R1
       movi    a2, 256
    #elif (XCHAL_ICACHE_SIZE == 65536)  //CHICAGO HIFI
       movi    a2, 256
       #endif

    /*icache unlock icache */
    #if (XCHAL_ICACHE_LINESIZE == 128)
       movi    a3, 0
       loop    a2, .L0
       iiu     a3, 0
       iiu     a3, XCHAL_ICACHE_LINESIZE
       addi    a3, a3, XCHAL_ICACHE_LINESIZE*2

       iiu     a3, 0
       iiu     a3, XCHAL_ICACHE_LINESIZE
       addi    a3, a3, XCHAL_ICACHE_LINESIZE*2
.L0:

    #else
       movi    a3, 0
       loop    a2, .L0
       iiu     a3, 0
       iiu     a3, XCHAL_ICACHE_LINESIZE
       iiu     a3, XCHAL_ICACHE_LINESIZE*2
       iiu     a3, XCHAL_ICACHE_LINESIZE*3
       addi    a3, a3, XCHAL_ICACHE_LINESIZE*4
.L0:
    #endif


    #if (XCHAL_ICACHE_SIZE == 8192)     //212GP and 330HiFi
       movi    a2, 64
    #elif (XCHAL_ICACHE_SIZE == 16384)  //232L and 570T
       movi    a2, 128
    #elif (XCHAL_ICACHE_SIZE == 32768)  //BALONGV7R1
       movi    a2, 256
    #elif (XCHAL_ICACHE_SIZE == 65536)  //CHICAGO HIFI
       movi    a2, 256
    #endif

    /*icache disable icache */
       movi    a3, 0
       loop    a2, .L1
       iii     a3, 0
       iii     a3, XCHAL_ICACHE_LINESIZE
       iii     a3, XCHAL_ICACHE_LINESIZE*2
       iii     a3, XCHAL_ICACHE_LINESIZE*3
       addi    a3, a3, XCHAL_ICACHE_LINESIZE*4
.L1:
       isync
#endif




    /*dcache init dcache */
#if (XCHAL_DCACHE_SIZE != 0)
    #if (XCHAL_DCACHE_SIZE == 8192)     //212GP and 330HiFi
       movi    a2, 64
    #elif (XCHAL_DCACHE_SIZE == 16384)  //232L and 570T
       movi    a2, 128
    #elif (XCHAL_DCACHE_SIZE == 32768)  //BALONGV7R1
       movi    a2, 256
    #elif (XCHAL_DCACHE_SIZE == 65536)  //CHICAGO HIFI
       movi    a2, 256
    #endif

    /*dcache unlock dcache */
    #if (XCHAL_DCACHE_LINESIZE == 128)
       movi    a3, 0
       loop    a2, .L2
       diu     a3, 0
       diu     a3, XCHAL_DCACHE_LINESIZE
       addi    a3, a3, XCHAL_DCACHE_LINESIZE*2

       diu     a3, 0
       diu     a3, XCHAL_DCACHE_LINESIZE
       addi    a3, a3, XCHAL_DCACHE_LINESIZE*2
.L2:

    #else
       movi    a3, 0
       loop    a2, .L2
       diu     a3, 0
       diu     a3, XCHAL_DCACHE_LINESIZE
       diu     a3, XCHAL_DCACHE_LINESIZE*2
       diu     a3, XCHAL_DCACHE_LINESIZE*3
       addi    a3, a3, XCHAL_DCACHE_LINESIZE*4
.L2:
    #endif
    #if (XCHAL_DCACHE_SIZE == 8192)     //212GP and 330HiFi
       movi    a2, 64
    #elif (XCHAL_DCACHE_SIZE == 16384)  //232L and 570T
       movi    a2, 128
    #elif (XCHAL_DCACHE_SIZE == 32768)  //BALONGV7R1
       movi    a2, 256
    #elif (XCHAL_DCACHE_SIZE == 65536)  //CHICAGO HIFI
       movi    a2, 256
    #endif


    /*dcache disable dcache */
    movi    a3, 0
       loop    a2, .L3
       dii     a3, 0
       dii     a3, XCHAL_DCACHE_LINESIZE
       dii     a3, XCHAL_DCACHE_LINESIZE*2
       dii     a3, XCHAL_DCACHE_LINESIZE*3
       addi    a3, a3, XCHAL_DCACHE_LINESIZE*4
.L3:
       dsync
#endif

       /*
       0x00000000-0x1fffffff   :   caches off(bypass)
    0x20000000-0x3fffffff   :  caches off(bypass)
    0x40000000-0x5fffffff   :   caches off(bypass)
    0x60000000-0x7fffffff   :   caches off(bypass)
    0x80000000-0x9fffffff   :   caches off(bypass)
    0xa0000000-0xbfffffff   :   caches off(bypass)
    0xc0000000-0xdfffffff   :   caches on，0x00000000-0x1fffffff
    0xe0000000-0xffffffff   :   caches off(bypass),:0xe0000000~0xefffffff 0x20000000~0x2fffffff
       */
       /* config the memory access right */
       movi    a3, 0x00000000     /*0x00000000-0x1fffffff*/
	set_access_mode PIF_BYPASS     /*can not access*/

       movi    a3, 0x20000000     /*0x20000000-0x3fffffff*/
       set_access_mode PIF_BYPASS

       movi    a3, 0x40000000     /*0x40000000-0x5fffffff*/
#if (XCHAL_DCACHE_LINESIZE == 128)
       set_access_mode PIF_BYPASS
#else
	set_access_mode PIF_BYPASS
#endif

       movi    a3, 0x60000000     /*0x60000000-0x7fffffff*/
       	set_access_mode PIF_BYPASS

       movi    a3, 0x80000000     /*0x80000000-0x9fffffff*/
    #if (XCHAL_DCACHE_LINESIZE == 128)
        set_access_mode PIF_BYPASS
    #else
       set_access_mode PIF_BYPASS
    #endif

       movi    a3, 0xa0000000     /*0xa0000000-0xbfffffff*/
       set_access_mode PIF_BYPASS

       movi    a3, 0xc0000000     /*0xc0000000-0xdfffffff*/
       set_access_mode PIF_CACHED_WBA /*PIF_CACHED*/

       movi    a3, 0xe0000000     /*0xe0000000-0xffffffff*/
       set_access_mode PIF_BYPASS

    /* 0x40000000 0xe0000000, 0x50000000 0xf0000000,*/
    /*movi a2, 0x40000000 //vpn
    movi a3, 0xe0000000 //ppn
    movi a5, 0xE0000000 // tlb mask, upper 3 bits
    and a4, a3, a5      // upper 3 bits of PPN area
    and a7, a2, a5      // upper 3 bits of VPN area

    ritlb1 a5, a7       // get current PPN+AM of segment for I
    rdtlb1 a6, a7       // get current PPN+AM of segment for D
    extui a5, a5, 0, 4  // keep only AM for I
    extui a6, a6, 0, 4  // keep only AM for D
    add a2, a4, a5      // combine new PPN with orig AM for I
    add a3, a4, a6      // combine new PPN with orig AM for D
    witlb a2, a7        // write new tlb mapping for I
    wdtlb a3, a7        // write new tlb mapping for D
    */
    isync
    dsync

    /*
    prepare the environment of run C code
    */
       movi    sp, __stack

       movi    a2, PS_WOE_MASK | PS_PROGSTACK_MASK
       wsr     a2, PS
       rsync

       movi    a8, _bss_start
       movi    a10, _bss_end
       sub     a11, a10, a8
       srli    a11, a11, 2

       movi    a9, 0
    loopnez a11, zerodone
       s32i    a9, a8, 0
       addi    a8, a8, 4
zerodone:

       movi    a8, _ulpp_bss_start
       movi    a10, _ulpp_bss_end
       sub     a11, a10, a8
       srli    a11, a11, 2

       movi    a9, 0
    loopnez a11, zerodone1
       s32i    a9, a8, 0
       addi    a8, a8, 4
zerodone1:

#ifdef HIFI_DTS_V3
       movi    a8, _dtsv3_bss_start
       movi    a10, _dtsv3_bss_end
       sub     a11, a10, a8
       srli    a11, a11, 2

       movi    a9, 0
    loopnez a11, zerodone2
       s32i    a9, a8, 0
       addi    a8, a8, 4
zerodone2:
#endif

#ifdef HIFI_DTS_V4
       movi    a8, _dtsv4_bss_start
       movi    a10, _dtsv4_bss_end
       sub     a11, a10, a8
       srli    a11, a11, 2

       movi    a9, 0
    loopnez a11, zerodone3
       s32i    a9, a8, 0
       addi    a8, a8, 4
zerodone3:
#endif

#ifdef HIFI_VLP
       movi    a8, _efr_fr_hr_vlpd_bss_start
       movi    a10, _efr_fr_hr_vlpd_bss_end
       sub     a11, a10, a8
       srli    a11, a11, 2

       movi    a9, 0
    loopnez a11, zerodone4
       s32i    a9, a8, 0
       addi    a8, a8, 4
zerodone4:

       movi    a8, _amr_vlpd_bss_start
       movi    a10, _amr_vlpd_bss_end
       sub     a11, a10, a8
       srli    a11, a11, 2

       movi    a9, 0
    loopnez a11, zerodone5
       s32i    a9, a8, 0
       addi    a8, a8, 4
zerodone5:

       movi    a8, _vlpd_bss_start
       movi    a10, _vlpd_bss_end
       sub     a11, a10, a8
       srli    a11, a11, 2

       movi    a9, 0
    loopnez a11, zerodone6
       s32i    a9, a8, 0
       addi    a8, a8, 4
zerodone6:

       movi    a8, _amrwb_vlpd_bss_start
       movi    a10, _amrwb_vlpd_bss_end
       sub     a11, a10, a8
       srli    a11, a11, 2

       movi    a9, 0
    loopnez a11, zerodone7
       s32i    a9, a8, 0
       addi    a8, a8, 4
zerodone7:
#endif

callmain:

// Do NOT modify a1 here, it is the stack pointer.
// Use another register instead (a3?)
// Not sure why the NOPs are present.
#movi a1, 0xdeadbeef
    movi a1, 0xe8073000
    movi a2, 0xe8075e10 /*HIFI_RESERVE1_LOCATION*/
    s32i a1, a2, 0

       movi    a0, 0
       movi    a6, 0   /* clear argc*/
       movi    a7, 0   /* clear argv*/
       movi    a8, 0   /* clear envp*/
       movi    a4, main
       nop
       nop
       callx4  a4

reset_exit:
       movi    a2, SYS_exit
// Where does it go from here ? execution will fall through into
// whatever happens to follow. Either halt here or jump to some
// specific code.

    .end        literal_prefix

#ifdef VOS_VENUS_TEST_STUB
       .text
       .global sim_call
       .type sim_call,@function
       .align 4
sim_call:
       entry sp, 64
       addi    a4, a3,-3
    movi    a3, g_auwVosTestMessageBuf
    movi    a2, SYS_log_msg
    simcall
       retw

#endif


