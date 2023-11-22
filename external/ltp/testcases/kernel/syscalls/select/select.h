/*
 * Copyright (c) 2019 Google, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

#ifndef SELECT_H__
#define SELECT_H__

#include <stdlib.h>
#include <sys/select.h>
#include <sys/time.h>
#include <sys/types.h>
#include <unistd.h>

#define str_expand(s) str(s)
#define str(s) #s

#if defined(SYSCALL_SELECT_LIBC)

// bionic and GNU libc actually use pselect6 instead, others?
#define SELECT_TEST_SYSCALL select
#define SELECT_TEST_FILENAME(x) x

#else

#ifndef TCONF
#include "test.h"
#endif
#ifndef tst_brkm
#include <stdio.h>
#define tst_brkm(a1, a2, whatever...) \
	{ \
		printf("BROK : "); \
		printf(whatever); \
		printf("\n"); \
		_exit(0); \
	}
#endif

#include "lapi/syscalls.h"

#define undefined __LTP__NR_INVALID_SYSCALL

#ifndef __NR_select
#define __NR_select undefined
#endif
#if defined(__LP64__)
#define select_sys_select(n, inp, outp, exp, tvp) \
	return ltp_syscall(__NR_select, n, inp, outp, exp, tvp)
#else
struct compat_sel_arg_struct {
	long _n;
	long _inp;
	long _outp;
	long _exp;
	long _tvp;
};
#define select_sys_select(n, inp, outp, exp, tvp) \
	struct compat_sel_arg_struct arg; \
\
	arg._n = (long)n; \
	arg._inp = (long)inp; \
	arg._outp = (long)outp; \
	arg._exp = (long)exp; \
	arg._tvp = (long)tvp; \
	return ltp_syscall(__NR_select, &arg)
#endif

#ifndef __NR__newselect
#define __NR__newselect undefined
#endif
#define select_sys__newselect(n, inp, outp, exp, tvp) \
	return ltp_syscall(__NR__newselect, n, inp, outp, exp, tvp)

#define select_sys_pselect6(n, inp, outp, exp, tvp) \
	int ret; \
	struct timespec ts; \
\
	ts.tv_sec = tvp->tv_sec; \
	ts.tv_nsec = tvp->tv_usec * 1000; \
	ret = ltp_syscall(__NR_pselect6, n, inp, outp, exp, &ts, NULL); \
	tvp->tv_sec = ts.tv_sec; \
	tvp->tv_usec = ts.tv_nsec / 1000; \
	return ret

#if defined(SYSCALL_SELECT__NEWSELECT)
#define SELECT_TEST_SYSCALL _newselect
#elif defined(SYSCALL_SELECT_SELECT)
#define SELECT_TEST_SYSCALL select
#elif defined(SYSCALL_SELECT_PSELECT6)
#define SELECT_TEST_SYSCALL pselect6
#else
/* automatically select between newselect, select or pselect6 if available */
#if __NR__newselect != __LTP__NR_INVALID_SYSCALL
#define SELECT_TEST_SYSCALL _newselect
#elif __NR_select != __LTP__NR_INVALID_SYSCALL
#define SELECT_TEST_SYSCALL select
#else
#define SELECT_TEST_SYSCALL pselect6
#endif
#endif

#define __MY_select(x) select_sys_ ## x
#define _MY_select(x) __MY_select(x)
#define MY_select _MY_select(SELECT_TEST_SYSCALL)

int select(int __fd_count, fd_set* __read_fds, fd_set* __write_fds,
	   fd_set* __exception_fds, struct timeval* __timeout)
{
	MY_select(__fd_count, __read_fds, __write_fds, __exception_fds,
		  __timeout);
}

#define SELECT_TEST_FILENAME(x) x "_SYS_" str_expand(SELECT_TEST_SYSCALL)

#endif /* ! SYSCALL_SELECT_LIBC */

#endif  /* SELECT_H__ */
