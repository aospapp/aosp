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


#include <xtensa/coreasm.h>
#include <xtensa/simcall.h>
#define PIF_CACHED  1
#define PIF_BYPASS  2
#define PIF_CACHED_WBA   4
#define PIF_CACHED_WBNA  5
#define PIF_INVALID 15

#ifdef __cplusplus
#if __cplusplus
extern "C" {
#endif
#endif



/*set memory mapping attribute*/
.macro set_access_mode am
    rdtlb1 a4, a3
    ritlb1 a5, a3
    srli a4, a4, 4
    slli a4, a4, 4
    srli a5, a5, 4
    slli a5, a5, 4
    addi a4, a4, \am
    addi a5, a5, \am
    wdtlb a4, a3
    witlb a5, a3
.endm




#ifdef __cplusplus
    #if __cplusplus
        }
    #endif
#endif

