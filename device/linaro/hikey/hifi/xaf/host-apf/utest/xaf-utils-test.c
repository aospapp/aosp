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
#include <stdio.h>
#include <string.h>

#include "xaf-utils-test.h"

int audio_frmwk_buf_size;
int audio_comp_buf_size;

int print_banner(char *app_name)
{
    /*
    XAF_CHK_PTR(ver_info[0], "print_verinfo");
    XAF_CHK_PTR(ver_info[1], "print_verinfo");
    XAF_CHK_PTR(ver_info[2], "print_verinfo");
    */

    fprintf(stdout, "******************************************************************************\n");
    fprintf(stdout, "Cadence Audio Framework (Hosted) : %s \n",app_name);
    fprintf(stdout, "Copyright (c) 2018 Cadence Design Systems, Inc.\n");
    /*fprintf(stdout, "Lib Name        : %s\n", ver_info[0]);
    fprintf(stdout, "Lib Version     : %s\n", ver_info[1]);
    fprintf(stdout, "API Version     : %s\n", ver_info[2]);*/
    fprintf(stdout, "******************************************************************************\n");

    return 0;
}

int print_mem_mcps_info(mem_obj_t* mem_handle, int num_comp)
{
    int tot_dev_mem_size, tot_comp_mem_size, tot_size;

    /* ...unused arg */
    (void) num_comp;

    /* ...printing memory info*/

    tot_dev_mem_size = mem_get_alloc_size(mem_handle, XAF_MEM_ID_DEV);
    tot_comp_mem_size = mem_get_alloc_size(mem_handle, XAF_MEM_ID_COMP);
    tot_size = tot_dev_mem_size + tot_comp_mem_size;

    fprintf(stdout,"Shared Memory between AP and DSP for IPC: %8d \n", XF_CFG_REMOTE_IPC_POOL_SIZE);
    fprintf(stdout,"Memory allocated on AP  for Device      : %8d \n", tot_dev_mem_size);
    fprintf(stdout,"Memory allocated on AP  for Components  : %8d \n", tot_comp_mem_size);
    fprintf(stdout,"Memory allocated on DSP for Components  : %8d \n", XF_CFG_LOCAL_POOL_SIZE);

    return 0;
}

