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

#ifndef __DSP_DEBUG_H__
#define __DSP_DEBUG_H__

 
#define DEBUG_LEVEL   0
#define INFO_LEVEL    1
#define WARNING_LEVEL 1
#define ERROR_LEVEL   1
#define TRACE_DUMP    1

#define LOG_TAG " DSP_LOG"

#if TRACE_DUMP
#define DSP_TRACE(fmt, ...) print_log(fmt" @%s:%d \n", ##__VA_ARGS__, __FUNCTION__, __LINE__)
#else
#define DSP_TRACE(fmt, ...)
#endif

#if DEBUG_LEVEL
#define DSP_LOGD(fmt, ...) print_log(LOG_TAG"[D]%s:%d: "fmt, __FUNCTION__, __LINE__, ##__VA_ARGS__)
#else
#define DSP_LOGD(fmt, ...)
#endif

#if INFO_LEVEL
#define DSP_LOGI(fmt, ...) print_log(LOG_TAG"[I]%s:%d: "fmt, __FUNCTION__, __LINE__, ##__VA_ARGS__)
#else
#define DSP_LOGI(fmt, ...)
#endif

#if WARNING_LEVEL
#define DSP_LOGW(fmt, ...) print_log(LOG_TAG"[W]%s:%d: "fmt, __FUNCTION__, __LINE__, ##__VA_ARGS__)
#else
#define DSP_LOGW(fmt, ...)
#endif

#if ERROR_LEVEL
#define DSP_LOGE(fmt, ...) print_log(LOG_TAG"[E]%s:%d: "fmt, __FUNCTION__, __LINE__, ##__VA_ARGS__)
#else
+#define DSP_LOGE(fmt, ...)
#endif


void print_log(const char *fmt, ...);
void dsp_debug_init();
#ifdef HIKEY_XAF_IPC_COMMENT_OUT
void dsp_om_func_proc(char *om_str, unsigned int str_len);
#endif
#endif
