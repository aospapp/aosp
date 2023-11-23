/*
 * Copyright 2012-2022 NXP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef _PHNXPUCIHAL_H_
#define _PHNXPUCIHAL_H_

#include <phNxpUciHal_utils.h>
#include <phNxpUciHal_Adaptation.h>
#include <hal_nxpuwb.h>
#include <phTmlUwb.h>
#include <uci_defs.h>
/********************* Definitions and structures *****************************/
#define MAX_RETRY_COUNT 0x05
#define UCI_MAX_DATA_LEN 4200 // maximum data packet size
#define UCI_MAX_PAYLOAD_LEN 4200
//#define UCI_RESPONSE_STATUS_OFFSET 0x04
#define UCI_PKT_HDR_LEN 0x04
#define UCI_PKT_PAYLOAD_STATUS_LEN 0x01
#define UCI_PKT_NUM_CAPS_LEN 0x01
#define UWB_CHANNELS 0x0B



#define MAX_RESPONSE_STATUS              0x0C
#define MAX_COMMAND_RETRY_COUNT          0x05

#define UCI_MT_MASK                      0xE0
#define UCI_PBF_MASK                     0x10
#define UCI_GID_MASK                     0x0F
#define UCI_OID_MASK                     0x3F

#define UCI_GID_RANGE_MANAGE             0x02  /* 0010b Range Management group */
#define UCI_OID_RANGE_DATA_NTF           0x00

#define UCI_NTF_PAYLOAD_OFFSET           0x04
#define NORMAL_MODE_LENGTH_OFFSET        0x03
#define EXTENDED_MODE_LEN_OFFSET         0x02
#define EXTENDED_MODE_LEN_SHIFT          0x08
#define EXTND_LEN_INDICATOR_OFFSET       0x01
#define EXTND_LEN_INDICATOR_OFFSET_MASK  0x80
#define UCI_SESSION_ID_OFFSET            0x04
#define FWD_MAX_RETRY_COUNT              0x05

#define USER_FW_BOOT_MODE 0x01
#define FW_BOOT_MODE_PARAM_ID 0x04

#define EXT_CONFIG_TAG_ID 0xE3
#define DEVICE_NAME_PARAM_ID 0x00

#define SR1xxT 'T'
#define SR1xxS 'S'

/* Mem alloc. with 8 byte alignment */
#define nxp_malloc(x) malloc(((x - 1) | 7) + 1)

/* FW debug and crash log path */
const char debug_log_path[] = "/data/vendor/uwb/";

/* UCI Data */
#define NXP_MAX_CONFIG_STRING_LEN 260
typedef struct uci_data {
  uint16_t len;
  uint8_t p_data[UCI_MAX_DATA_LEN];
} uci_data_t;

typedef enum {
  HAL_STATUS_CLOSE = 0,
  HAL_STATUS_OPEN
} phNxpUci_HalStatus;

typedef enum {
  UWB_DEVICE_INIT = 0,
  UWB_DEVICE_READY,
  UWB_DEVICE_BUSY,
  UWB_DEVICE_STATE_UNKNOWN = 0XA0,
  UWB_DEVICE_ERROR = 0xFF
}phNxpUci_UwbcState;

/* Macros to enable and disable extensions */
#define HAL_ENABLE_EXT() (nxpucihal_ctrl.hal_ext_enabled = 1)
#define HAL_DISABLE_EXT() (nxpucihal_ctrl.hal_ext_enabled = 0)

/* UCI Control structure */
typedef struct phNxpUciHal_Control {
  phNxpUci_HalStatus halStatus; /* Indicate if hal is open or closed */
  pthread_t client_thread;      /* Integration thread handle */
  uint8_t thread_running;       /* Thread running if set to 1, else set to 0 */
  phLibUwb_sConfig_t gDrvCfg;   /* Driver config data */

  /* Rx data */
  uint8_t* p_rx_data;
  uint16_t rx_data_len;

  /* libuwb-uci callbacks */
  uwb_stack_callback_t* p_uwb_stack_cback;
  uwb_stack_data_callback_t* p_uwb_stack_data_cback;

  /* HAL open status */
  bool_t hal_open_status;

  /* HAL extensions */
  uint8_t hal_ext_enabled;

  /* Waiting semaphore */
  phNxpUciHal_Sem_t ext_cb_data;

  phNxpUciHal_Sem_t dev_status_ntf_wait;
  phNxpUciHal_Sem_t uwb_binding_status_ntf_wait;
  uint16_t cmd_len;
  uint8_t p_cmd_data[UCI_MAX_DATA_LEN];
  uint16_t rsp_len;
  uint8_t p_rsp_data[UCI_MAX_DATA_LEN];
  uint8_t p_caps_resp[UCI_MAX_DATA_LEN];

  /* retry count used to force download */
  uint8_t read_retry_cnt;

  bool_t isRecoveryTimerStarted;
  /* To skip sending packets to upper layer from HAL*/
  uint8_t isSkipPacket;
  bool_t fw_dwnld_mode;
  uint8_t  uwbc_device_state;
  uint8_t dev_state_ntf_wait;
} phNxpUciHal_Control_t;

/* Internal messages to handle callbacks */
#define UCI_HAL_OPEN_CPLT_MSG 0x411
#define UCI_HAL_CLOSE_CPLT_MSG 0x412
#define UCI_HAL_INIT_CPLT_MSG 0x413
#define UCI_HAL_HW_RESET_MSG 0x414
#define UCI_HAL_ERROR_MSG 0x415

#define UCIHAL_CMD_CODE_LEN_BYTE_OFFSET (2U)
#define UCIHAL_CMD_CODE_BYTE_LEN (3U)

/******************** UCI HAL exposed functions *******************************/

tHAL_UWB_STATUS phNxpUciHal_write_unlocked(uint16_t data_len,
                                           const uint8_t *p_data);
void phNxpUciHal_read_complete(void* pContext,
                                      phTmlUwb_TransactInfo_t* pInfo);
tHAL_UWB_STATUS phNxpUciHal_uwb_reset();
uint8_t phNxpUciHal_sendGetCoreDeviceInfo();
tHAL_UWB_STATUS phNxpUciHal_applyVendorConfig();

#endif /* _PHNXPUCIHAL_H_ */
