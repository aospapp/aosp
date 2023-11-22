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
 * mutex.h
 *
 *
 * Implementation of "non-robust" Szymanski linear-waiting algorithm. Types of
 * failures tolerated by Szymanski's "robust" algorithm are not specific for
 * Xtensa DSP cluster and therefore more lightweight version of the algorithm
 * is used. FIFO servicing property is of low importance, and faster/smaller
 * version with linear-wait property is preferable.
 *
 * We assume there is just a single mutex in the system, and all communication
 * variables are defined somewhere in board-specific headers and imported here
 * by means of macros MUTEX_SHARED_READ/WRITE.
 ******************************************************************************/

#ifndef __MUTEX_H
#define __MUTEX_H

/*******************************************************************************
 * Imported macros
 ******************************************************************************/

#if !defined(MUTEX_SHARED_READ) || !defined(MUTEX_SHARED_WRITE)
#error "Macros MUTEX_SHARED_READ and/or MUTEX_SHARED_WRITE not defined"
#endif

/*******************************************************************************
 * Entry points
 ******************************************************************************/

/* ...acquire global mutex from i-th core */
extern void mutex_lock(u32 i);

/* ...release global mutex from i-th core */
extern void mutex_unlock(u32 i);

#endif  /* __MUTEX_H */
