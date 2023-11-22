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


#ifndef __DSP_COMM_H__
#define __DSP_COMM_H__

#include <xtensa/config/core.h>
#include <xtensa/simcall.h>

#define DSP_FLUSH_PIECE_CACHE(addr, size) xthal_dcache_region_writeback(addr, size)
#define DSP_FLUSH_ALL_CACHE() xthal_dcache_all_writeback()
#define DSP_INVALIDATE_PIECE_CACHE(addr, size) xthal_dcache_region_invalidate(addr, size)
#define DSP_INVALIDATE_ALL_CACHE() xthal_dcache_all_invalidate()

#define memset(d,uCData,size) dsp_memset(d,uCData,size)
#define memcpy(d,s,size) dsp_memcpy(d,s,size)
#define divsi3(a,b) division(a,b)
#define _divsi3(a,b) division(a,b)

void dsp_memcpy(void *d, void *s, unsigned int size);
void dsp_memset(void *d, unsigned char ucData, unsigned int size);
int division(int a, int b);

#endif /* end of dsp_comm.h */

