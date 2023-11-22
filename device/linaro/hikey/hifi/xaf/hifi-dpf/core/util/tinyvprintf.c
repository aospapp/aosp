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


#include <string.h>
#include "lib/tinyput.h"


/*
 *  Simple formatted output routine.
 *  Designed primarily for small size (and secondarily for efficiency).
 *  Only a common subset of printf formats and options are handled:
 *
 *	%[-+ ][0][width]i	decimal signed integer
 *	%[-+ ][0][width]d	decimal signed integer
 *	%[-][0][width]u		decimal unsigned integer
 *	%[-][0][width]x		hex unsigned integer
 *	%[-][0][width]p		hex unsigned integer with 0x prefix ("pointer")
 *	%[-][width]c		single character
 *	%[-][width]s		string
 *
 *  These modifiers are ignored (legally on 32-bit Xtensa):
 *	#           (alternate format)
 *	h           (short)			expands to int on 32-bit Xtensa
 *	l           (long)			same as int on 32-bit Xtensa
 *	j           (intmax_t or uintmax_t)	same as int on 32-bit Xtensa
 *	z           (size_t or ssize_t)		same as int on 32-bit Xtensa
 *	t           (ptrdiff_t)			same as int on 32-bit Xtensa
 *
 *  Does NOT support:
 *	width.prec  (precision modifier)
 *	%X          (capitalized hex; handles this as lowercase hex)
 *	%o          (octal)
 *	%[L][feEgG] (floating point formats)
 *	%a %A       (hex floating point formats, C99)
 *	%C          (multibyte character)
 *	%S          (multibyte character string)
 *	%n          (returning count of character written)
 *	ll          (long long)
 *	q j z t     (other size modifiers, eg. see glibc)
 */
int tiny_vsprintf(char *out, const char *fmt, va_list ap)
{
    int total = 0;
    char c, space = ' ', buf[11];	/* largest 32-bit integer output (octal) */

    while ((c = *(char*)fmt++) != 0) {
        if (c != '%') {
            *out++ = c;
            total++;
        } else {
            int width = 0, len = 1, rightjust = 1;
            unsigned n;
            char *s = buf, *t, pad = ' ', sign = 0;
            while (1) {
                c = *(char*)fmt++;
                switch (c) {
                case 'c':	buf[0] = va_arg(ap, int);		goto donefmt;
                case 's':	s = va_arg(ap, char*);
                    if (s == 0)
                        len = 0;
                    else {
                        for (t = s; *t; t++) ;
                        len = t - s;
                    }
                    goto donefmt;

                case '#':	/* ignore (not supported) */
                case 'h':	/* ignore (short; passed as int) */
                case 'l':	/* ignore (long; same as int) */
                case 'j':	/* ignore (intmax_t or uintmax_t; same as int) */
                case 'z':	/* ignore (size_t or ssize_t; same as int) */
                case 't':	/* ignore (ptrdiff_t; same as int) */
                    break;

                case ' ':	sign = ' ';				break;
                case '+':	sign = '+';				break;
                case '-':	rightjust = 0;				break;

                case 'i':	/*FALLTHROUGH*/
                case 'd':	n = va_arg(ap, int);
                if ((int)n < 0) {
                    sign = '-';
                    n = -(int)n;
                }
                if (sign) {
                    if (rightjust && pad == ' ')
                        *s++ = sign;
                    else {
                        *out++ = sign;
                        width--;
                        total++;
                    }
                }
                goto do_decimal;
                case 'u':	n = va_arg(ap, int);
                do_decimal:
                {
                    /*  (avoids division or multiplication)  */
                    int digit, i, seen = 0;
                    for (digit = 0; n >= 1000000000; digit++)
                        n -= 1000000000;
                    for (i = 9;;) {
                        if (!seen && digit != 0)
                            seen = i;
                        if (seen)
                            *s++ = '0' + digit;
                        for (digit = 0; n >= 100000000; digit++)
                            n -= 100000000;
                        if (--i == 0) {
                            *s++ = '0' + digit;
                            len = s - buf;
                            s = buf;
                            goto donefmt;
                        }
                        n = ((n << 1) + (n << 3));
                    }
                }
                /*NOTREACHED*/

#if 0
                case 'o':	n = va_arg(ap, unsigned);
                    s = buf + 11;
                    do {
                        *--s = '0' + (n & 7);
                        n = (unsigned)n >> 3;
                    } while (n);
                    len = buf + 11 - s;
                    goto donefmt;
#endif

                case 'p':	*out++ = '0', *out++ = 'x';
                    total += 2;
                    /*FALLTHROUGH*/
                case 'X':	/*FALLTHROUGH*/
                case 'x':	n = va_arg(ap, unsigned);
                s = buf + 8;
                do {
                    *--s = "0123456789abcdef"[n & 0xF];
                    n = (unsigned)n >> 4;
                } while (n);
                len = buf + 8 - s;
                goto donefmt;

                case 0:	goto done;
                case '0':	if (width == 0) pad = '0';		/*FALLTHROUGH*/
                default:	if (c >= '0' && c <= '9')
                    width = ((width<<1) + (width<<3)) + (c - '0');
                else {
                    buf[0] = c;		/* handles case of '%' */
                    goto donefmt;
                }
                }
            }
            /*NOTREACHED*/
        donefmt:
            if (len < width) {
                total += width;
                if (rightjust)
                    do { *out++ = pad; } while (len < --width);
            } else
                total += len;
            for(n = len; n > 0; n--) *out++ = *s++;
            for (; len < width; len++) *out++ = space;
        }
    }
done:
    return total;
}

int tiny_sprintf(char *out, const char *fmt, ...)
{
  int n = 0;
  va_list ap;
  va_start(ap, fmt);
  n = tiny_vsprintf(out, fmt, ap);
  va_end(ap);
  return n;
}
