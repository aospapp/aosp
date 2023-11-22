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


#include "cpu_c.h"
#include <xtensa/config/specreg.h>
#include <xtensa/config/core.h>
#include <xtensa/simcall.h>
#include "arch_hifi330.h"

#ifdef __cplusplus
#if __cplusplus
extern "C" {
#endif
#endif

/*lint -e838 -e835 -e750 -e529 -e533 -e40*/

//#define DSP_TRACES_TO_MEM_SIZE (0x800)

void *g_pfVosHookFuncTable[OS_HOOK_TYPE_BUTT];

void *g_pfVosIntrFuncTable[OS_INTR_CONNECT_BUTT];

//unsigned int g_uwSoftIntInfo = 0;

/* Interrupt nesting level */
unsigned char g_ucVosIntNesting;

/* Priority of current task */
unsigned char g_ucVosPrioCur;

/* Priority of highest priority task */
unsigned char g_ucVosPrioHighRdy;

/* Flag indicating that kernel is running */
unsigned short g_bVosRunning;

/* Pointer to highest priority TCB R-to-R */
unsigned int *g_pstVosTCBHighRdy;

/* Pointer to currently running TCB*/
unsigned int *g_pstVosTCBCur;

#define _XTSTR(x) #x
#define XTSTR(x) _XTSTR(x)

void OS_EnterIntHook(unsigned int uhwIntrNo)
{
    INTR_HOOK_FUN_TYPE pfIntrHook = (INTR_HOOK_FUN_TYPE)g_pfVosHookFuncTable[OS_HOOK_TYPE_ENTER_INTR];/*lint !e611*/

    if (0 != pfIntrHook)
    {
        pfIntrHook(uhwIntrNo);
    }
}

void OS_ExitIntHook(unsigned int uhwIntrNo)
{
    INTR_HOOK_FUN_TYPE pfIntrHook = (INTR_HOOK_FUN_TYPE)g_pfVosHookFuncTable[OS_HOOK_TYPE_EXIT_INTR];/*lint !e611*/

    if (0 != pfIntrHook)
    {
        pfIntrHook(uhwIntrNo);
    }
}

void OS_UserexecHandler(unsigned int uwExecCauseNo, unsigned int psAddr)
{
    VOS_EXCEPTION_HOOK_FUNC pfIntrHook = (VOS_EXCEPTION_HOOK_FUNC)g_pfVosHookFuncTable[OS_HOOK_TYPE_EXCEPTION];/*lint !e611*/

    if (0 != pfIntrHook)
    {
        pfIntrHook(uwExecCauseNo);
    }
    else
    {
        extern void OS_Panic(void);
        OS_Panic();
    }
}

void OS_InterruptHandler(unsigned int uhwIntrNo)
{
    HOOK_FUN_TYPE pfIntrHook = (HOOK_FUN_TYPE)g_pfVosIntrFuncTable[uhwIntrNo];

    if (0 != pfIntrHook)
        //pfIntrHook();
        pfIntrHook(0);
}

void OS_NmiHook(unsigned int uwExecCauseNo, unsigned int psAddr)
{
    HOOK_FUN_TYPE pfNmiHook = (HOOK_FUN_TYPE)g_pfVosHookFuncTable[OS_HOOK_TYPE_NMI];/*lint !e611*/

    if (0 != pfNmiHook)
    {
        //pfNmiHook();
        pfNmiHook(0);
    }

    //wait for hifi reset
    UCOM_SET_WFI_NMI(5);
}

void VOS_ConnectInterrupt(unsigned int uwIntrNo, HOOK_FUN_TYPE pfnInterruptHook)
{
    g_pfVosIntrFuncTable[uwIntrNo] = (void *)pfnInterruptHook;/*lint !e611*/
}

void VOS_EnableInterrupt(unsigned int uwIntNo)
{
    unsigned int uwBitEnable;

    uwBitEnable = xthal_get_intenable();
    xthal_set_intenable(uwBitEnable | (((unsigned int)1) << uwIntNo ));

}
void VOS_DisableInterrupt(unsigned int uwIntNo)
{
    unsigned int uwBitEnable;

    uwBitEnable = xthal_get_intenable();
    xthal_set_intenable(uwBitEnable | (((unsigned int)0) << uwIntNo ));

}

#ifdef __cplusplus
    #if __cplusplus
        }
    #endif
#endif

