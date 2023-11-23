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

#ifndef __CPU_C_H__
#define __CPU_C_H__

enum OS_HOOK_TYPE_ENUM
{
    OS_HOOK_TYPE_ENTER_INTR=0,
    OS_HOOK_TYPE_EXIT_INTR,
    OS_HOOK_TYPE_TASK_SWITCH,
    OS_HOOK_TYPE_MSG_GET,
    OS_HOOK_TYPE_NMI,
    OS_HOOK_TYPE_EXCEPTION,
    OS_HOOK_TYPE_APPINIT,
    OS_HOOK_TYPE_IDLE,
    OS_HOOK_TYPE_BUTT
};

enum OS_INTR_CONNECT_TYPE_ENUM
{
    OS_INTR_CONNECT_00=0,        /*Int 0   type / priority level NMI / 6          */
    OS_INTR_CONNECT_01,          /*Int 1   type / priority level Software / 3  */
    OS_INTR_CONNECT_02,          /*Int 2   type / priority level ExtLevel / 2  */
    OS_INTR_CONNECT_03,          /*Int 3   type / priority level ExtLevel / 2  */
    OS_INTR_CONNECT_04,          /*Int 4   type / priority level ExtLevel / 2  */
    OS_INTR_CONNECT_05,          /*Int 5   type / priority level Timer / 3     */
    OS_INTR_CONNECT_06,          /*Int 6   type / priority level Timer / 4     */
    OS_INTR_CONNECT_07,          /*Int 7   type / priority level ExtLevel / 3  */
    OS_INTR_CONNECT_08,          /*Int 8   type / priority level ExtLevel / 3  */
    OS_INTR_CONNECT_09,          /*Int 9   type / priority level ExtLevel / 3  */
    OS_INTR_CONNECT_10,          /*Int 10   type / priority level ExtLevel / 2 */
    OS_INTR_CONNECT_11,          /*Int 11   type / priority level ExtLevel / 2 */
    OS_INTR_CONNECT_12,          /*Int 12   type / priority level ExtLevel / 2 */
    OS_INTR_CONNECT_13,          /*Int 13   type / priority level ExtLevel / 2 */
    OS_INTR_CONNECT_14,          /*Int 14   type / priority level ExtLevel / 2 */
    OS_INTR_CONNECT_15,          /*Int 15   type / priority level ExtLevel / 1 */
    OS_INTR_CONNECT_16,          /*Int 16   type / priority level ExtLevel / 1 */
    OS_INTR_CONNECT_17,          /*Int 17   type / priority level ExtLevel / 1 */
    OS_INTR_CONNECT_18,          /*Int 18   type / priority level ExtLevel / 1 */
    OS_INTR_CONNECT_19,          /*Int 19   type / priority level Timer / 2    */
    OS_INTR_CONNECT_20,          /*Int 20   type / priority level ExtLevel / 1 */
    OS_INTR_CONNECT_21,          /*Int 21   type / priority level ExtLevel / 1 */
    OS_INTR_CONNECT_22,          /*Int 22   type / priority level ExtLevel / 1 */
    OS_INTR_CONNECT_23,          /*Int 23   type / priority level ExtLevel / 1 */
    OS_INTR_CONNECT_24,          /*Int 24   type / priority level ExtLevel / 1 */
    OS_INTR_CONNECT_25,          /*Int 25   type / priority level ExtLevel / 1 */
    OS_INTR_CONNECT_26,          /*Int 26   type / priority level ExtLevel / 1 */
    OS_INTR_CONNECT_27,          /*Int 27   type / priority level ExtLevel / 1 */
    OS_INTR_CONNECT_28,          /*Int 28   type / priority level ExtLevel / 1 */
    OS_INTR_CONNECT_29,          /*Int 29   type / priority level ExtLevel / 1 */
    OS_INTR_CONNECT_30,          /*Int 30   type / priority level ExtLevel / 1 */
    OS_INTR_CONNECT_31,          /*Int 31   type / priority level ExtEdge / 1  */
    OS_INTR_CONNECT_BUTT
};

#define UCOM_SET_WFI_NMI(var1)          asm ("waiti 5": :)

#define UCOM_FlushCache(pAddr, uwSize)      \
    xthal_dcache_region_writeback(pAddr, uwSize)

#define UCOM_FlushCacheAll()      \
    xthal_dcache_all_writeback()

#define UCOM_InvalidateCache(pAddr, uwSize)      \
    xthal_dcache_region_invalidate(pAddr, uwSize)

#define UCOM_InvalidateCacheAll()      \
    xthal_dcache_all_invalidate()

#define ADD_TAG asm("movi a1, 0xdeadbaaf \n movi a2, 0xe8075e80 \n s32i a1, a2, 0": :)

//typedef void (*HOOK_FUN_TYPE)(void);
typedef void (*HOOK_FUN_TYPE)(unsigned int);

typedef void  (*INTR_HOOK_FUN_TYPE )(unsigned int uwIntNo);

typedef void (*VOS_EXCEPTION_HOOK_FUNC)( unsigned int uwExceptionNo);

extern void *g_pfVosHookFuncTable[OS_HOOK_TYPE_BUTT];

extern void VOS_ConnectInterrupt(unsigned int uwIntrNo, HOOK_FUN_TYPE pfnInterruptHook);

extern void VOS_EnableInterrupt(unsigned int uwIntNo);
extern void VOS_DisableInterrupt(unsigned int uwIntNo);

extern void  OS_UserExit(void);


#endif /* end of cpu_c.h */
