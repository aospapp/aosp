/*
 * Copyright 2012-2019, 2022-2023 NXP
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
#include <log/log.h>
#include <phNxpUciHal_ext.h>
#include <phNxpUciHal.h>
#include <phTmlUwb.h>
#include <phDal4Uwb_messageQueueLib.h>
#include <phNxpLog.h>
#include <phUwbCommon.h>
#include <sys/stat.h>
#include <cutils/properties.h>
#include "phNxpConfig.h"

#define MAX_PROPERTY_SIZE (PROPERTY_VALUE_MAX)
/* Timeout value to wait for response from SR1xx */
#define HAL_EXTNS_WRITE_RSP_TIMEOUT (100)

/******************* Global variables *****************************************/
extern phNxpUciHal_Control_t nxpucihal_ctrl;

extern uint32_t cleanup_timer;
extern bool uwb_debug_enabled;
extern uint32_t timeoutTimerId;

/************** HAL extension functions ***************************************/
static void hal_extns_write_rsp_timeout_cb(uint32_t TimerId, void *pContext);
/******************************************************************************
 * Function         phNxpUciHal_process_ext_cmd_rsp
 *
 * Description      This function process the extension command response. It
 *                  also checks the received response to expected response.
 *
 * Returns          returns UWBSTATUS_SUCCESS if response is as expected else
 *                  returns failure.
 *
 ******************************************************************************/
static tHAL_UWB_STATUS phNxpUciHal_process_ext_cmd_rsp(uint16_t cmd_len,
                                                 uint8_t* p_cmd) {
  tHAL_UWB_STATUS status = UWBSTATUS_FAILED;
  uint16_t data_written = 0;
  uint8_t ext_cmd_retry_cnt = 0;
  uint8_t invalid_len_retry_cnt = 0;
  bool exit_loop = 0;

  /* Create the local semaphore */
  if (phNxpUciHal_init_cb_data(&nxpucihal_ctrl.ext_cb_data, NULL) !=
      UWBSTATUS_SUCCESS) {
    NXPLOG_UCIHAL_D("Create ext_cb_data failed");
    return UWBSTATUS_FAILED;
  }


  /* Send ext command */
  do {
    NXPLOG_UCIHAL_D("Entered do while loop");
    nxpucihal_ctrl.ext_cb_data.status = UWBSTATUS_SUCCESS;
    data_written = phNxpUciHal_write_unlocked(cmd_len, p_cmd);
    if (data_written != cmd_len) {
      NXPLOG_UCIHAL_D("phNxpUciHal_write failed for hal ext");
      goto clean_and_return;
    }

    NXPLOG_UCIHAL_D("ext_cmd_retry_cnt is %d",ext_cmd_retry_cnt);

    /* Start timer */
    status = phOsalUwb_Timer_Start(timeoutTimerId, HAL_EXTNS_WRITE_RSP_TIMEOUT,
                                   &hal_extns_write_rsp_timeout_cb, NULL);
    if (UWBSTATUS_SUCCESS == status) {
      NXPLOG_UCIHAL_D("Response timer started");
    } else {
      NXPLOG_UCIHAL_E("Response timer not started!!!");
      status = UWBSTATUS_FAILED;
      goto clean_and_return;
    }

    /* Wait for rsp */
    NXPLOG_UCIHAL_D("Waiting after ext cmd sent");
    if (SEM_WAIT(nxpucihal_ctrl.ext_cb_data)) {
      NXPLOG_UCIHAL_E("p_hal_ext->ext_cb_data.sem semaphore error");
      goto clean_and_return;
    }

    switch(nxpucihal_ctrl.ext_cb_data.status) {
      case UWBSTATUS_RESPONSE_TIMEOUT:
      case UWBSTATUS_COMMAND_RETRANSMIT:
            ext_cmd_retry_cnt++;
            break;
      case UWBSTATUS_INVALID_COMMAND_LENGTH:
        invalid_len_retry_cnt ++;
        break;
        default:
          exit_loop = 1;
          break;
    }
    if ((ext_cmd_retry_cnt >= MAX_COMMAND_RETRY_COUNT) || (invalid_len_retry_cnt >= 0x03))
      exit_loop = 1;
  } while(exit_loop == 0);

  /* Stop Timer */
  status = phOsalUwb_Timer_Stop(timeoutTimerId);

  if (UWBSTATUS_SUCCESS == status) {
    NXPLOG_UCIHAL_D("Response timer stopped");
  } else {
    NXPLOG_UCIHAL_E("Response timer stop ERROR!!!");
    status = UWBSTATUS_FAILED;
    goto clean_and_return;
  }

  if (nxpucihal_ctrl.ext_cb_data.status != UWBSTATUS_SUCCESS) {
    NXPLOG_UCIHAL_E("Response Status = 0x%x", nxpucihal_ctrl.ext_cb_data.status);
    status = UWBSTATUS_FAILED;
    goto clean_and_return;
  }
  NXPLOG_UCIHAL_D("Checking response");
  status = UWBSTATUS_SUCCESS;

clean_and_return:
  phNxpUciHal_cleanup_cb_data(&nxpucihal_ctrl.ext_cb_data);

  return status;
}

/******************************************************************************
 * Function         phNxpUciHal_write_ext
 *
 * Description      This function inform the status of phNxpUciHal_open
 *                  function to libuwb-uci.
 *
 * Returns          It return UWBSTATUS_SUCCESS then continue with send else
 *                  sends UWBSTATUS_FAILED direct response is prepared and
 *                  do not send anything to UWBC.
 *
 ******************************************************************************/

tHAL_UWB_STATUS phNxpUciHal_write_ext(uint16_t* cmd_len, uint8_t* p_cmd_data,
                                uint16_t* rsp_len, uint8_t* p_rsp_data) {
  tHAL_UWB_STATUS status = UWBSTATUS_SUCCESS;

  return status;
}

/******************************************************************************
 * Function         phNxpUciHal_send_ext_cmd
 *
 * Description      This function send the extension command to UWBC. No
 *                  response is checked by this function but it waits for
 *                  the response to come.
 *
 * Returns          Returns UWBSTATUS_SUCCESS if sending cmd is successful and
 *                  response is received.
 *
 ******************************************************************************/
tHAL_UWB_STATUS phNxpUciHal_send_ext_cmd(uint16_t cmd_len, const uint8_t* p_cmd) {
  tHAL_UWB_STATUS status;

  if (cmd_len >= UCI_MAX_DATA_LEN) {
    status = UWBSTATUS_FAILED;
    return status;
  }
  HAL_ENABLE_EXT();
  nxpucihal_ctrl.cmd_len = cmd_len;
  memcpy(nxpucihal_ctrl.p_cmd_data, p_cmd, cmd_len);
  status = phNxpUciHal_process_ext_cmd_rsp(nxpucihal_ctrl.cmd_len,
                                           nxpucihal_ctrl.p_cmd_data);
  HAL_DISABLE_EXT();

  return status;
}

/******************************************************************************
 * Function         hal_extns_write_rsp_timeout_cb
 *
 * Description      Timer call back function
 *
 * Returns          None
 *
 ******************************************************************************/
static void hal_extns_write_rsp_timeout_cb(uint32_t timerId, void* pContext) {
  UNUSED(timerId);
  UNUSED(pContext);
  NXPLOG_UCIHAL_E("hal_extns_write_rsp_timeout_cb - write timeout!!!");
  nxpucihal_ctrl.ext_cb_data.status = UWBSTATUS_RESPONSE_TIMEOUT;
  usleep(1);
  SEM_POST(&(nxpucihal_ctrl.ext_cb_data));

  return;
}

/******************************************************************************
 * Function         phNxpUciHal_set_board_config
 *
 * Description      This function is called to set the board varaint config
 * Returns          return 0 on success and -1 on fail, On success
 *                  update the acutual state of operation in arg pointer
 *
 ******************************************************************************/
tHAL_UWB_STATUS phNxpUciHal_set_board_config(){
  tHAL_UWB_STATUS status;
  uint8_t buffer[] = {0x2E,0x00,0x00,0x02,0x01,0x01};
  /* Set the board variant configurations */
  unsigned long num = 0;
  NXPLOG_UCIHAL_D("%s: enter; ", __func__);
  uint8_t boardConfig = 0, boardVersion = 0;

  if(GetNxpConfigNumValue(NAME_UWB_BOARD_VARIANT_CONFIG, &num, sizeof(num))){
    boardConfig = (uint8_t)num;
    NXPLOG_UCIHAL_D("%s: NAME_UWB_BOARD_VARIANT_CONFIG: %x", __func__,boardConfig);
  } else {
    NXPLOG_UCIHAL_D("%s: NAME_UWB_BOARD_VARIANT_CONFIG: failed %x", __func__,boardConfig);
  }
  if(GetNxpConfigNumValue(NAME_UWB_BOARD_VARIANT_VERSION, &num, sizeof(num))){
    boardVersion = (uint8_t)num;
    NXPLOG_UCIHAL_D("%s: NAME_UWB_BOARD_VARIANT_VERSION: %x", __func__,boardVersion);
  } else{
    NXPLOG_UCIHAL_D("%s: NAME_UWB_BOARD_VARIANT_VERSION: failed %lx", __func__,num);
  }
  buffer[4] = boardConfig;
  buffer[5] = boardVersion;

  status = phNxpUciHal_send_ext_cmd(sizeof(buffer), buffer);

  return status;
}

/*******************************************************************************
**
** Function         phNxpUciHal_process_ext_rsp
**
** Description      Process extension function response
**
** Returns          UWBSTATUS_SUCCESS if success
**
*******************************************************************************/
tHAL_UWB_STATUS phNxpUciHal_process_ext_rsp(uint16_t rsp_len, uint8_t* p_buff){
  tHAL_UWB_STATUS status;
  int NumOfTlv, index;
  uint8_t paramId, extParamId, IdStatus;
  index = UCI_NTF_PAYLOAD_OFFSET; // index for payload start
  status = p_buff[index++];
  if(status  == UCI_STATUS_OK){
    NXPLOG_UCIHAL_D("%s: status success %d", __func__, status);
    return UWBSTATUS_SUCCESS;
  }
  NumOfTlv = p_buff[index++];
  while (index < rsp_len) {
      paramId = p_buff[index++];
      if(paramId == EXTENDED_DEVICE_CONFIG_ID) {
        extParamId = p_buff[index++];
        IdStatus = p_buff[index++];

        switch(extParamId) {
          case UCI_EXT_PARAM_DELAY_CALIBRATION_VALUE:
          case UCI_EXT_PARAM_AOA_CALIBRATION_CTRL:
          case UCI_EXT_PARAM_DPD_WAKEUP_SRC:
          case UCI_EXT_PARAM_WTX_COUNT_CONFIG:
          case UCI_EXT_PARAM_DPD_ENTRY_TIMEOUT:
          case UCI_EXT_PARAM_WIFI_COEX_FEATURE:
          case UCI_EXT_PARAM_TX_BASE_BAND_CONFIG:
          case UCI_EXT_PARAM_DDFS_TONE_CONFIG:
          case UCI_EXT_PARAM_TX_PULSE_SHAPE_CONFIG:
          case UCI_EXT_PARAM_CLK_CONFIG_CTRL:
            if(IdStatus == UCI_STATUS_FEATURE_NOT_SUPPORTED){
              NXPLOG_UCIHAL_E("%s: Vendor config param: %x %x is Not Supported", __func__, paramId, extParamId);
              status = UWBSTATUS_SUCCESS;
            } else {
              status = UWBSTATUS_FAILED;
              return status;
            }
            break;
          default:
            NXPLOG_UCIHAL_D("%s: Vendor param ID: %x", __func__, extParamId);
            break;
        }
      } else {
        IdStatus = p_buff[index++];
        switch(paramId) {
          case UCI_PARAM_ID_LOW_POWER_MODE:
            if(IdStatus == UCI_STATUS_FEATURE_NOT_SUPPORTED){
              NXPLOG_UCIHAL_E("%s: Generic config param: %x is Not Supported", __func__, paramId);
              status = UWBSTATUS_SUCCESS;
            } else {
              status = UWBSTATUS_FAILED;
              return status;
            }
            break;
          default:
            NXPLOG_UCIHAL_D("%s: Generic param ID: %x", __func__, paramId);
            break;
        }
      }
    }
 NXPLOG_UCIHAL_D("%s: exit %d", __func__, status);
 return status;
}
