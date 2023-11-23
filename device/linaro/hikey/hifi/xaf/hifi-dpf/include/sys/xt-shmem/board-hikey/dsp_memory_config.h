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

#ifndef __DSP_MEMORY_CONFIG_H__
#define __DSP_MEMORY_CONFIG_H__

/** for chicago only **/
/**Non Secure 3.5M **/
/* |0x8B300000|0x8B432000|0x8B532000|0x8B5B1000|0x8B5B2000|0x8B5C5000|0x8B5C6000|0x8B5C7000|0x8B5F9800|~~0x8B609800~~|~~0x8B618800~~|~0x8B618880~|0x8B627880|~0x8B629880~|0x8B62C880~~~| */
/* |Music data|~~PCM data|~hifi uart|panicstack|icc debug~|flag data~|DDRsechead|~~AP NV ~~|AP&HIFIMB~|codec dma buff|codecdmaconfig|soundtrigger|pcm upload|~hikey share|unsec reserve| */
/* |~~~~1.2M~~|~~~~1M~~~~|~~508k~~~~|~~~~~4k~~~|~~76k~~~~~|~~~4k~~~~~|~~~4k~~~~~|~~202k~~~~|~~~64k~~~~|~~~~60k~~~~~~~|~~~~128b~~~~~~|~~~~60k~~~~~|~~~8k~~~~~|~~~~~12k~~~~|~~334k-128b~~| */
/* |0x8B431fff|0x8B531fff|0x8B5B0fff|0x8B5B1fff|0x8B5C4fff|0x8B5C5fff|0x8B5C6fff|0x8B5F97ff|0x8B6097ff|~~0x8B6187FF~~|~~0x8B61887F~~|~0x8B62787F~|0x8B62987F|0x8B62C87F~~|~~0x8B67FFFF~| */

/** Secure9.5M **/
/* |~~~0x89200000~~~|~~~0x89800000~~~|~~~0x89830000~~|~~~0x89864000~~~| */
/* |~~HIFI RUNNING~~|~OCRAM img bak~~|~~TCM img bak~~|~~~~IMG bak~~~~~| */
/* |~~~~~~~6M~~~~~~~|~~~~~~192K~~~~~~|~~~~~208k~~~~~~|~~~~~~3.1M ~~~~~| */
/* |~~~0x897fffff~~~|~~~0x8982ffff~~~|~~~0x89863fff~~|~~~0x89B80000~~~| */

 
#define HIFI_UNSEC_REGION_SIZE              (0x380000)
#define HIFI_MUSIC_DATA_SIZE                (0x132000)
#define PCM_PLAY_BUFF_SIZE                  (0x100000)
#define DRV_DSP_UART_TO_MEM_SIZE            (0x7f000)
#define DRV_DSP_UART_TO_MEM_RESERVE_SIZE    (0x100)
#define DRV_DSP_STACK_TO_MEM_SIZE           (0x1000)
#define HIFI_ICC_DEBUG_SIZE                 (0x13000)
#define HIFI_FLAG_DATA_SIZE                 (0x1000)
#define HIFI_SEC_HEAD_SIZE                  (0x1000)
#define HIFI_AP_NV_DATA_SIZE                (0x32800)
#define HIFI_AP_MAILBOX_TOTAL_SIZE          (0x10000)
#define CODEC_DSP_OM_DMA_BUFFER_SIZE        (0xF000)
#define CODEC_DSP_OM_DMA_CONFIG_SIZE        (0x80)
#define CODEC_DSP_SOUNDTRIGGER_TOTAL_SIZE   (0xF000)
#define HIFI_PCM_UPLOAD_BUFFER_SIZE         (0x2000)
#define HIFI_HIKEY_SHARE_SIZE               (0x1800 * 2)
#define HIFI_UNSEC_RESERVE_SIZE             (0x53780)

#define HIFI_UNSEC_BASE_ADDR                (0x8B300000)
#define HIFI_MUSIC_DATA_LOCATION        (HIFI_UNSEC_BASE_ADDR)
#define PCM_PLAY_BUFF_LOCATION          (HIFI_MUSIC_DATA_LOCATION + HIFI_MUSIC_DATA_SIZE)
#define DRV_DSP_UART_TO_MEM             (PCM_PLAY_BUFF_LOCATION + PCM_PLAY_BUFF_SIZE)
#define DRV_DSP_STACK_TO_MEM            (DRV_DSP_UART_TO_MEM + DRV_DSP_UART_TO_MEM_SIZE)
#define HIFI_ICC_DEBUG_LOCATION         (DRV_DSP_STACK_TO_MEM + DRV_DSP_STACK_TO_MEM_SIZE)
#define HIFI_FLAG_DATA_ADDR             (HIFI_ICC_DEBUG_LOCATION + HIFI_ICC_DEBUG_SIZE)
#define HIFI_SEC_HEAD_BACKUP            (HIFI_FLAG_DATA_ADDR + HIFI_FLAG_DATA_SIZE)
#define HIFI_AP_NV_DATA_ADDR            (HIFI_SEC_HEAD_BACKUP + HIFI_SEC_HEAD_SIZE)
#define HIFI_AP_MAILBOX_BASE_ADDR       (HIFI_AP_NV_DATA_ADDR + HIFI_AP_NV_DATA_SIZE)
#define CODEC_DSP_OM_DMA_BUFFER_ADDR    (HIFI_AP_MAILBOX_BASE_ADDR + HIFI_AP_MAILBOX_TOTAL_SIZE)
#define CODEC_DSP_OM_DMA_CONFIG_ADDR    (CODEC_DSP_OM_DMA_BUFFER_ADDR + CODEC_DSP_OM_DMA_BUFFER_SIZE)
#define CODEC_DSP_SOUNDTRIGGER_BASE_ADDR (CODEC_DSP_OM_DMA_CONFIG_ADDR + CODEC_DSP_OM_DMA_CONFIG_SIZE)
#define HIFI_PCM_UPLOAD_BUFFER_ADDR     (CODEC_DSP_SOUNDTRIGGER_BASE_ADDR + CODEC_DSP_SOUNDTRIGGER_TOTAL_SIZE)
#define HIFI_HIKEY_SHARE_MEM_ADDR       (HIFI_PCM_UPLOAD_BUFFER_ADDR+HIFI_AP_MAILBOX_TOTAL_SIZE)
#define HIFI_UNSEC_RESERVE_ADDR         (HIFI_HIKEY_SHARE_MEM_ADDR + HIFI_HIKEY_SHARE_SIZE)

#define DRV_DSP_PANIC_MARK              (HIFI_FLAG_DATA_ADDR)
#define DRV_DSP_UART_LOG_LEVEL          (DRV_DSP_PANIC_MARK + 4)
#define DRV_DSP_UART_TO_MEM_CUR_ADDR    (DRV_DSP_UART_LOG_LEVEL + 4)

#define HIFI_SEC_REGION_SIZE            (0x980000)
#define HIFI_IMAGE_OCRAMBAK_SIZE        (0x30000)
#define HIFI_RUN_SIZE                   (0x600000)
#define HIFI_IMAGE_TCMBAK_SIZE          (0x34000)
#define HIFI_IMAGE_SIZE                 (0x31C000)
#define HIFI_RUN_ITCM_BASE              (0xe8080000)
#define HIFI_RUN_ITCM_SIZE              (0x9000)
#define HIFI_RUN_DTCM_BASE              (0xe8058000)
#define HIFI_RUN_DTCM_SIZE              (0x28000)

 
#define HIFI_SEC_REGION_ADDR            (0x89200000) /* chciago */
#define HIFI_RUN_LOCATION               (HIFI_SEC_REGION_ADDR)
#define HIFI_IMAGE_OCRAMBAK_LOCATION    (HIFI_RUN_LOCATION + HIFI_RUN_SIZE)
#define HIFI_IMAGE_TCMBAK_LOCATION      (HIFI_IMAGE_OCRAMBAK_LOCATION + HIFI_IMAGE_OCRAMBAK_SIZE)
#define HIFI_IMAGE_LOCATION             (HIFI_IMAGE_TCMBAK_LOCATION + HIFI_IMAGE_TCMBAK_SIZE)

#endif
