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
#include <xtensa/xtruntime.h>
#include <xtensa/config/specreg.h>

extern const unsigned char Xthal_have_ccount;
extern const unsigned char Xthal_num_ccompare;
extern void xthal_set_ccompare(int n, unsigned value);
extern unsigned xthal_get_ccompare(int n);

/*--------------------------------------------*/
#include <xtensa/config/core.h>
#define TIMER_INTERVAL 0x1000

#define TIMER_INT_MASK (1 << XCHAL_TIMER0_INTERRUPT)
#define TIMER2_INT_MASK (1 << XCHAL_TIMER1_INTERRUPT)
#define TWO_TIMERS_INT_MASK ( TIMER_INT_MASK + TIMER2_INT_MASK )
#define _XTSTR(x) # x
#define XTSTR(x) _XTSTR(x)

static __inline__ int read_ccount()
{
    unsigned int ccount;
    __asm__ __volatile__ (
    "rsr %0, "XTSTR(CCOUNT)
    : "=a" (ccount)
    );
    return ccount;
}

static __inline__ int read_ccompare0()
{
    unsigned int ccompare0;
    __asm__ __volatile__ (
    "rsr %0, "XTSTR(CCOMPARE_0)
    : "=a" (ccompare0)
    );
    return ccompare0;
}

static __inline__ int read_ccompare1()
{
    unsigned int ccompare1;
    __asm__ __volatile__ (
    "rsr %0, "XTSTR(CCOMPARE_1)
    : "=a" (ccompare1)
    );
    return ccompare1;
}

static __inline__ unsigned int read_intenable()
{
    unsigned int intenable;
    __asm__ __volatile__ (
    "rsr %0, "XTSTR(INTENABLE)
    : "=a" (intenable)
    );
    return intenable;
}

static __inline__ void set_ccompare1(int val)
{
    __asm__ __volatile__ (
    "wsr %0, "XTSTR(CCOMPARE_1)"\n\t"
    "isync\n\t"
    :
    : "a" (val)
    );
}

static __inline__ void set_ccompare0(int val)
{
    __asm__ __volatile__ (
    "wsr %0, "XTSTR(CCOMPARE_0)"\n\t"
    "isync\n\t"
    :
    : "a" (val)
    );
}

/*---------------------------------------------------*/

static __inline__ void set_ccount(int val)
{
  __asm__ __volatile__ (
  "wsr %0, ccount\n"
  "isync\n"
  :
  : "a" (val)
  );
}

