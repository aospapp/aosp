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

#include "dsp_comm.h"

void dsp_memcpy(void *d, void *s, unsigned int size)
{
    unsigned char *dest = (unsigned char*)d;
    unsigned char *src = (unsigned char*)s;

    if (s == d) {
        return;
    } else if (src > dest) {
        for (; dest < ((unsigned char*)d + size); dest++) {
            *dest = *src;
            src++;
        }
    } else {
        src = src + (size - 1);
        for (dest = dest + (size - 1); dest >= (unsigned char*)d; dest--) {
            *dest = *src;
            src--;
        }
    }
}


void dsp_memset(void *d, unsigned char ucData, unsigned int size)
{
    unsigned int i;
    unsigned char *dest  = (unsigned char*)d;

    for(i = 0; i < size; i++)
       *dest++  = ucData;
}
int division(int a, int b)
{
    const int bits_in_word_m1 = (int)(sizeof(int) * 8) - 1;
    int s_a = a >> bits_in_word_m1;           /* s_a = a < 0 ? -1 : 0 */
    int s_b = b >> bits_in_word_m1;           /* s_b = b < 0 ? -1 : 0 */
    a = (a ^ s_a) - s_a;                         /* negate if s_a == -1 */
    b = (b ^ s_b) - s_b;                         /* negate if s_b == -1 */
    s_a ^= s_b;                                  /* sign of quotient */
    /*
     * On CPUs without unsigned hardware division support,
     *  this calls __udivsi3 (notice the cast to su_int).
     * On CPUs with unsigned hardware division support,
     *  this uses the unsigned division instruction.
     */
    return ((int)a/(int)b ^ s_a) - s_a;    /* negate if s_a == -1 */

}




