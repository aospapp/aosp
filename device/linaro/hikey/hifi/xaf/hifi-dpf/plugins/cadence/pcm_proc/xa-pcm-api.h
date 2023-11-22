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
 * xa-pcm-api.h
 *
 * Generic PCM format converter API
 *
 ******************************************************************************/

#ifndef __XA_PCM_API_H__
#define __XA_PCM_API_H__

/*******************************************************************************
 * Includes
 ******************************************************************************/

#include "xa_type_def.h"
#include "xa_error_standards.h"
#include "xa_apicmd_standards.h"
#include "xa_memory_standards.h"

/*******************************************************************************
 * Constants definitions
 ******************************************************************************/

/* ...codec-specific configuration parameters */
enum xa_config_param_pcm {
    XA_PCM_CONFIG_PARAM_SAMPLE_RATE         = 0,
    XA_PCM_CONFIG_PARAM_IN_PCM_WIDTH        = 1,
    XA_PCM_CONFIG_PARAM_IN_CHANNELS         = 2,
    XA_PCM_CONFIG_PARAM_OUT_PCM_WIDTH       = 3,
    XA_PCM_CONFIG_PARAM_OUT_CHANNELS        = 4,
    XA_PCM_CONFIG_PARAM_CHANROUTING         = 5,
    XA_PCM_CONFIG_PARAM_NUM                 = 6,
};

/* ...component identifier (informative) */
#define XA_CODEC_PCM                  16

/*******************************************************************************
 * Class 0: API Errors
 ******************************************************************************/

#define XA_PCM_API_NONFATAL(e)          \
    XA_ERROR_CODE(xa_severity_nonfatal, xa_class_api, XA_CODEC_PCM, (e))

#define XA_PCM_API_FATAL(e)             \
    XA_ERROR_CODE(xa_severity_fatal, xa_class_api, XA_CODEC_PCM, (e))

enum xa_error_nonfatal_api_pcm {
    XA_PCM_API_NONFATAL_MAX = XA_PCM_API_NONFATAL(0)
};

enum xa_error_fatal_api_pcm {
    XA_PCM_API_FATAL_MAX = XA_PCM_API_FATAL(0)
};

/*******************************************************************************
 * Class 1: Configuration Errors
 ******************************************************************************/

#define XA_PCM_CONFIG_NONFATAL(e)       \
    XA_ERROR_CODE(xa_severity_nonfatal, xa_class_config, XA_CODEC_PCM, (e))

#define XA_PCM_CONFIG_FATAL(e)          \
    XA_ERROR_CODE(xa_severity_fatal, xa_class_config, XA_CODEC_PCM, (e))

enum xa_error_nonfatal_config_pcm {
    XA_PCM_CONFIG_NONFATAL_RANGE    = XA_PCM_CONFIG_NONFATAL(0),
    XA_PCM_CONFIG_NONFATAL_STATE    = XA_PCM_CONFIG_NONFATAL(1),
    XA_PCM_CONFIG_NONFATAL_MAX      = XA_PCM_CONFIG_NONFATAL(2)
};

enum xa_error_fatal_config_pcm {
    XA_PCM_CONFIG_FATAL_RANGE       = XA_PCM_CONFIG_FATAL(0),
    XA_PCM_CONFIG_FATAL_MAX         = XA_PCM_CONFIG_FATAL(1)
};

/*******************************************************************************
 * Class 2: Execution Class Errors
 ******************************************************************************/

#define XA_PCM_EXEC_NONFATAL(e)         \
    XA_ERROR_CODE(xa_severity_nonfatal, xa_class_execute, XA_CODEC_PCM, (e))

#define XA_PCM_EXEC_FATAL(e)            \
    XA_ERROR_CODE(xa_severity_fatal, xa_class_execute, XA_CODEC_PCM, (e))

enum xa_error_nonfatal_execute_pcm {
    XA_PCM_EXEC_NONFATAL_STATE      = XA_PCM_EXEC_NONFATAL(0),
    XA_PCM_EXEC_NONFATAL_NO_DATA    = XA_PCM_EXEC_NONFATAL(1),
    XA_PCM_EXEC_NONFATAL_INPUT      = XA_PCM_EXEC_NONFATAL(2),
    XA_PCM_EXEC_NONFATAL_OUTPUT     = XA_PCM_EXEC_NONFATAL(3),
    XA_PCM_EXEC_NONFATAL_MAX        = XA_PCM_EXEC_NONFATAL(4)
};

enum xa_error_fatal_execute_pcm {
    XA_PCM_EXEC_FATAL_STATE         = XA_PCM_EXEC_FATAL(0),
    XA_PCM_EXEC_FATAL_INPUT         = XA_PCM_EXEC_FATAL(1),
    XA_PCM_EXEC_FATAL_OUTPUT        = XA_PCM_EXEC_FATAL(2),
    XA_PCM_EXEC_FATAL_MAX           = XA_PCM_EXEC_FATAL(3)
};

/*******************************************************************************
 * API function definition
 ******************************************************************************/

#if defined(USE_DLL) && defined(_WIN32)
#define DLL_SHARED __declspec(dllimport)
#elif defined (_WINDLL)
#define DLL_SHARED __declspec(dllexport)
#else
#define DLL_SHARED
#endif

#if defined(__cplusplus)
extern "C" {
#endif  /* __cplusplus */
DLL_SHARED xa_codec_func_t xa_pcm_codec;
#if defined(__cplusplus)
}
#endif  /* __cplusplus */

#endif /* __XA_PCM_API_H__ */

