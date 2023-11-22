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
 * mutex.c
 *
 * Implementation of non-robust Szymanski linear-waiting algorithm. Types of
 * failures tolerated by Szymanski's "robust" algorithm are not specific for
 * Xtensa DSP cluster and therefore more lightweight version of the algorithm
 * is used. FIFO servicing property is of low importance, and faster/smaller
 * version with linear-wait property is preferable.
 ******************************************************************************/
 
#include "xf.h"

/*******************************************************************************
 * Local constants definitions
 ******************************************************************************/

/* ...communication variables */
#define __M_A                           (1 << 0)
#define __M_W                           (1 << 1)
#define __M_S                           (1 << 2)

/* ...process states (updated atomically) */
#define M_PASSIVE                       (0)
#define M_ENTRY                         (__M_A)
#define M_INSIDE                        (__M_W)
#define M_TRANSIENT                     (__M_S | __M_W)
#define M_EXIT                          (__M_S)

/* ...total number of cores */
#define M_N                             XF_CFG_CORES_NUM

/* ...do not compile the code if there is just a single core */
#if M_N > 1
/*******************************************************************************
 * Entry points
 ******************************************************************************/

void mutex_lock(u32 i)
{
    u32     j;
    
    /* ...p1: i-th core goes into "entry" state (aws = true,false,false) */
    MUTEX_SHARED_WRITE(i, M_ENTRY);

    /* ...p2: wait all processes have sj=false (waiting room door is open) */
    for (j = 0; j < M_N; j++)
    {
        /* ...wait until sj = false */
        while (MUTEX_SHARED_READ(j) & __M_S) (void) 0;
    }
    
    /* ...p3: i-th core enters "inside" state (aws = false,true,false) */
    MUTEX_SHARED_WRITE(i, M_INSIDE);

p4:
    /* ...p4: wait in "inside" state */
    for (j = 0; j < M_N; j++)
    {
        /* ...p5: check if any of the cores appears is in "entry" state (aj=true) */
        if (MUTEX_SHARED_READ(j) & __M_A)
        {
            /* ...p5: found core in "entry" state (j < n); wait until it enters waiting room */
            goto p7;
        }
    }

    /* ...p6: j == n; enter into "transient" state (ai=false, wi=true, si=true) */
    MUTEX_SHARED_WRITE(i, M_TRANSIENT);

    /* ...p6.1: check for any core appearing in "entry" room */
    for (j = 0; j < M_N; j++)
    {
        if (MUTEX_SHARED_READ(j) & __M_A)
        {
            /* ...p6.2: found core in "entry" state (j < n) */
            MUTEX_SHARED_WRITE(i, M_INSIDE);

            /* ...back of to the "inside" state */
            goto p7;
        }
    }
        
    /* ...p6.3: no cores in "entry" room (j == n); go to "exit" state (ai=false, wi=false, si=true) */
    MUTEX_SHARED_WRITE(i, M_EXIT);

    /* ...p6.4: allow all cores to leave "transient" state (i.e. switch to "exit") */
    for (j = 0; j < M_N; j++)
    {
        while (MUTEX_SHARED_READ(j) & __M_W) (void) 0;
    }

    goto p9;
    
p7:
    /* ...j < n condition is met; find any cores in "inside" state (wj = true, sj = false) */
    for (j = 0; j < M_N; j++)
    {
        /* ...check if the core is in "exit" state */
        if (MUTEX_SHARED_READ(j) == M_EXIT)
        {
            /* ...p8.1: different core is a leader; go to "exit" state (ai=false, wi=false, si=true) */
            MUTEX_SHARED_WRITE(i, M_EXIT);

            goto p9;
        }
    }

    /* ...wait in "inside" state while all transients settle */
    goto p4;

p9:
    /* ...p9: i-th core is in "exit" state; enter critical section in accordance with numbering */
    for (j = 0; j < i; j++)
    {
        /* ...wait until core with lower number in "inside"/"transient"/"exit" states leaves */
        while (MUTEX_SHARED_READ(j) & (__M_W | __M_S)) (void) 0;
    }
    
    /* ...critical section entered */
}

/*******************************************************************************
 * mutex_unlock
 *
 * Release multi-core mutex
 ******************************************************************************/

void mutex_unlock(u32 i)
{
    /* ...enter into "passive" state (ai=false, wi=false, si=false) */
    MUTEX_SHARED_WRITE(i, M_PASSIVE);
}

#endif  /* M_N > 1 */
