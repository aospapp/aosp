/******************************************************************************
 *
 *  Copyright 2018-2019,2022 NXP
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/
#define LOG_TAG "NxpEseHal"
#include "phNxpEse_Spm.h"

#include <errno.h>
#include <ese_logs.h>
#include <fcntl.h>
#include <log/log.h>
#include <phNxpEsePal.h>
#include <phNxpEse_Internal.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "phNxpEseFeatures.h"

/*********************** Global Variables *************************************/

static void* pEseDeviceHandle = NULL;
#define MAX_ESE_ACCESS_TIME_OUT_MS 2000 /*2 seconds*/

/**
 * \addtogroup SPI_Power_Management
 *
 * @{ */
/******************************************************************************
\section Introduction Introduction

 * This module provide power request to Pn54x nfc-i2c driver, it checks if
 * wired access is already granted. It should have access to pn54x drive.
 * Below are the apis provided by the SPM module.
 ******************************************************************************/
/******************************************************************************
 * Function         phNxpEse_SPM_Init
 *
 * Description      This function opens the nfc i2c driver to manage power
 *                  and synchronization for ese secure element.
 *
 * Returns          On Success ESESTATUS_SUCCESS else proper error code
 *
 ******************************************************************************/
ESESTATUS phNxpEse_SPM_Init(void* pDevHandle) {
  ESESTATUS status = ESESTATUS_SUCCESS;
  pEseDeviceHandle = pDevHandle;
  if (NULL == pEseDeviceHandle) {
    NXP_LOG_ESE_E("%s : failed, device handle is null", __FUNCTION__);
    status = ESESTATUS_FAILED;
  }
  NXP_LOG_ESE_D("%s : exit status = %d", __FUNCTION__, status);

  return status;
}

/******************************************************************************
 * Function         phNxpEse_SPM_DeInit
 *
 * Description      This function closes the nfc i2c driver node.
 *
 * Returns          Always returns ESESTATUS_SUCCESS
 *
 ******************************************************************************/
ESESTATUS phNxpEse_SPM_DeInit(void) {
  pEseDeviceHandle = NULL;
  return ESESTATUS_SUCCESS;
}

/******************************************************************************
 * Function         phNxpEse_SPM_ConfigPwr
 *
 * Description      This function request to the nfc i2c driver
 *                  to enable/disable power to ese. This api should be called
 *before
 *                  sending any apdu to ese/once apdu exchange is done.
 *
 * Returns          On Success ESESTATUS_SUCCESS else proper error code
 *
 ******************************************************************************/
ESESTATUS phNxpEse_SPM_ConfigPwr(spm_power_t arg) {
  int32_t ret = -1;
  ESESTATUS wSpmStatus = ESESTATUS_SUCCESS;
  spm_state_t current_spm_state = SPM_STATE_INVALID;
  if (GET_CHIP_OS_VERSION() > OS_VERSION_4_0) {
    /*None of the IOCTLs valid except SPM_RECOVERY_RESET*/
    if (arg != SPM_RECOVERY_RESET) {
      return ESESTATUS_SUCCESS;
    }
  }
  ret = phPalEse_ioctl(phPalEse_e_ChipRst, pEseDeviceHandle, arg);
  switch (arg) {
    case SPM_POWER_DISABLE: {
      if (ret < 0) {
        NXP_LOG_ESE_E("%s : failed errno = 0x%x", __FUNCTION__, errno);
        wSpmStatus = ESESTATUS_FAILED;
      } else {
        if (phNxpEse_SPM_RelAccess() != ESESTATUS_SUCCESS) {
          NXP_LOG_ESE_E(" %s phNxpEse_SPM_RelAccess : failed \n", __FUNCTION__);
        }
      }
    } break;
    case SPM_POWER_ENABLE: {
      if (ret < 0) {
        NXP_LOG_ESE_E("%s : failed errno = 0x%x", __FUNCTION__, errno);
        if (errno == -EBUSY) {
          wSpmStatus = phNxpEse_SPM_GetState(&current_spm_state);
          if (wSpmStatus != ESESTATUS_SUCCESS) {
            NXP_LOG_ESE_E(" %s : phNxpEse_SPM_GetPwrState Failed",
                          __FUNCTION__);
            if (phNxpEse_SPM_RelAccess() != ESESTATUS_SUCCESS) {
              NXP_LOG_ESE_E(" %s phNxpEse_SPM_RelAccess : failed \n",
                            __FUNCTION__);
            }
            return wSpmStatus;
          } else {
            if (current_spm_state & SPM_STATE_DWNLD) {
              wSpmStatus = ESESTATUS_DWNLD_BUSY;
            } else {
              wSpmStatus = ESESTATUS_BUSY;
            }
          }
        } else {
          wSpmStatus = ESESTATUS_FAILED;
        }
        if (wSpmStatus != ESESTATUS_SUCCESS) {
          if (phNxpEse_SPM_RelAccess() != ESESTATUS_SUCCESS) {
            NXP_LOG_ESE_E(" %s phNxpEse_SPM_RelAccess : failed \n",
                          __FUNCTION__);
          }
        }
      }
    } break;
    case SPM_POWER_RESET: {
      if (ret < 0) {
        NXP_LOG_ESE_E("%s : failed errno = 0x%x", __FUNCTION__, errno);
        if (errno == -EBUSY) {
          wSpmStatus = phNxpEse_SPM_GetState(&current_spm_state);
          if (wSpmStatus != ESESTATUS_SUCCESS) {
            NXP_LOG_ESE_E(" %s : phNxpEse_SPM_GetPwrState Failed",
                          __FUNCTION__);
            return wSpmStatus;
          } else {
            if (current_spm_state & SPM_STATE_DWNLD) {
              wSpmStatus = ESESTATUS_DWNLD_BUSY;
            } else {
              wSpmStatus = ESESTATUS_BUSY;
            }
          }
        } else {
          wSpmStatus = ESESTATUS_FAILED;
        }
      }
    } break;
    case SPM_POWER_PRIO_ENABLE: {
      if (ret < 0) {
        NXP_LOG_ESE_E("%s : failed errno = 0x%x", __FUNCTION__, errno);
        if (errno == -EBUSY) {
          wSpmStatus = phNxpEse_SPM_GetState(&current_spm_state);
          if (wSpmStatus != ESESTATUS_SUCCESS) {
            NXP_LOG_ESE_E(" %s : phNxpEse_SPM_GetPwrState Failed",
                          __FUNCTION__);
            return wSpmStatus;
          } else {
            if (current_spm_state & SPM_STATE_DWNLD) {
              wSpmStatus = ESESTATUS_DWNLD_BUSY;
            } else {
              wSpmStatus = ESESTATUS_BUSY;
            }
          }

        } else {
          wSpmStatus = ESESTATUS_FAILED;
        }
      }
    } break;
    case SPM_POWER_PRIO_DISABLE: {
      if (ret < 0) {
        NXP_LOG_ESE_E("%s : failed errno = 0x%x", __FUNCTION__, errno);
        wSpmStatus = ESESTATUS_FAILED;
      }
    } break;
    case SPM_RECOVERY_RESET: {
    } break;
  }
  return wSpmStatus;
}

/******************************************************************************
 * Function         phNxpEse_SPM_EnablePwr
 *
 * Description      This function request to the nfc i2c driver
 *                  to enable power to ese. This api should be called before
 *                  sending any apdu to ese.
 *
 * Returns          On Success ESESTATUS_SUCCESS else proper error code
 *
 ******************************************************************************/
ESESTATUS phNxpEse_SPM_EnablePwr(void) {
  int32_t ret = -1;
  ESESTATUS wSpmStatus = ESESTATUS_SUCCESS;
  spm_state_t current_spm_state = SPM_STATE_INVALID;
  NXP_LOG_ESE_D("%s : phNxpEse_SPM_EnablePwr is set to  = 0x%d", __FUNCTION__,
                0);
  ret = phPalEse_ioctl(phPalEse_e_ChipRst, pEseDeviceHandle, 0);
  if (ret < 0) {
    NXP_LOG_ESE_E("%s : failed errno = 0x%x", __FUNCTION__, errno);
    if (errno == -EBUSY) {
      wSpmStatus = phNxpEse_SPM_GetState(&current_spm_state);
      if (wSpmStatus != ESESTATUS_SUCCESS) {
        NXP_LOG_ESE_E(" %s : phNxpEse_SPM_GetPwrState Failed", __FUNCTION__);
        return wSpmStatus;
      } else {
        if (current_spm_state == SPM_STATE_DWNLD) {
          wSpmStatus = ESESTATUS_DWNLD_BUSY;
        } else {
          wSpmStatus = ESESTATUS_BUSY;
        }
      }

    } else {
      wSpmStatus = ESESTATUS_FAILED;
    }
  }

  return wSpmStatus;
}

/******************************************************************************
 * Function         phNxpEse_SPM_DisablePwr
 *
 * Description      This function request to the nfc i2c driver
 *                  to disable power to ese. This api should be called
 *                  once apdu exchange is done.
 *
 * Returns          On Success ESESTATUS_SUCCESS else proper error code
 *
 ******************************************************************************/
ESESTATUS phNxpEse_SPM_DisablePwr(void) {
  int32_t ret = -1;
  ESESTATUS status = ESESTATUS_SUCCESS;
  NXP_LOG_ESE_D("%s : phNxpEse_SPM_DisablePwr is set to  = 0x%d", __FUNCTION__,
                1);
  ret = phPalEse_ioctl(phPalEse_e_ChipRst, pEseDeviceHandle, 1);
  if (ret < 0) {
    NXP_LOG_ESE_E("%s : failed errno = 0x%x", __FUNCTION__, errno);
    status = ESESTATUS_FAILED;
  }

  return status;
}
/******************************************************************************
 * Function         phNxpEse_SPM_SetPwrScheme
 *
 * Description      This function request to the nfc i2c driver
 *                  to set the chip type and power scheme.
 *
 * Returns          On Success ESESTATUS_SUCCESS else proper error code
 *
 ******************************************************************************/
ESESTATUS phNxpEse_SPM_SetPwrScheme(long arg) {
  int32_t ret = -1;
  ESESTATUS status = ESESTATUS_SUCCESS;

  NXP_LOG_ESE_D("%s : Power scheme is set to  = 0x%ld", __FUNCTION__, arg);
  ret = phPalEse_ioctl(phPalEse_e_SetPowerScheme, pEseDeviceHandle, arg);
  if (ret < 0) {
    NXP_LOG_ESE_E("%s : failed errno = 0x%x", __FUNCTION__, errno);
    status = ESESTATUS_FAILED;
  }

  return status;
}

/******************************************************************************
 * Function         phNxpEseP61_SPM_EnableDisablePwrCntrl
 *
 * Description      This function request to the nfc i2c driver
 *                  to set the chip type and power scheme.
 *
 * Returns          On Success ESESTATUS_SUCCESS else proper error code
 *
 ******************************************************************************/
ESESTATUS phNxpEse_SPM_DisablePwrControl(unsigned long arg) {
  int32_t ret = -1;
  ESESTATUS status = ESESTATUS_SUCCESS;

  NXP_LOG_ESE_D("%s : Inhibit power control is set to  = 0x%ld", __FUNCTION__,
                arg);
  ret = phPalEse_ioctl(phPalEse_e_DisablePwrCntrl, pEseDeviceHandle, arg);
  if (ret < 0) {
    NXP_LOG_ESE_E("%s : failed errno = 0x%x", __FUNCTION__, errno);
    status = ESESTATUS_FAILED;
  }

  return status;
}

/******************************************************************************
 * Function         phNxpEse_SPM_GetState
 *
 * Description      This function gets the current power state of ESE
 *
 * Returns          On Success ESESTATUS_SUCCESS else proper error code
 *
 ******************************************************************************/
ESESTATUS phNxpEse_SPM_GetState(spm_state_t* current_state) {
  int32_t ret = -1;
  ESESTATUS status = ESESTATUS_SUCCESS;
  spm_state_t ese_current_state = SPM_STATE_INVALID;

  if (current_state == NULL) {
    NXP_LOG_ESE_E("%s : failed Invalid argument", __FUNCTION__);
    return ESESTATUS_FAILED;
  }
  ret = phPalEse_ioctl(phPalEse_e_GetSPMStatus, pEseDeviceHandle,
                       (unsigned long)&ese_current_state);
  if (ret < 0) {
    NXP_LOG_ESE_E("%s : failed errno = 0x%x", __FUNCTION__, errno);
    status = ESESTATUS_FAILED;
  } else {
    *current_state = ese_current_state; /* Current ESE state */
  }

  return status;
}

/******************************************************************************
 * Function         phNxpEse_SPM_SetJcopDwnldState
 *
 * Description      This function is used to set the JCOP OS download state
 *
 * Returns          On Success ESESTATUS_SUCCESS else proper error code
 *
 ******************************************************************************/
ESESTATUS phNxpEse_SPM_SetJcopDwnldState(long arg) {
  int ret = -1;
  ESESTATUS status = ESESTATUS_SUCCESS;

  NXP_LOG_ESE_D("%s :phNxpEse_SPM_SetJcopDwnldState  = 0x%ld", __FUNCTION__,
                arg);
  ret = phPalEse_ioctl(phPalEse_e_SetJcopDwnldState, pEseDeviceHandle, arg);
  if (ret < 0) {
    NXP_LOG_ESE_E("%s : failed errno = 0x%x", __FUNCTION__, errno);
    status = ESESTATUS_FAILED;
  }

  return status;
}

/******************************************************************************
 * Function         phNxpEse_SPM_SetEseClientUpdateState
 *
 * Description      This function is used to set the ese Update state
 *
 * Returns          On Success ESESTATUS_SUCCESS else proper error code
 *
 ******************************************************************************/
ESESTATUS phNxpEse_SPM_SetEseClientUpdateState(long arg) {
  int ret = -1;
  ESESTATUS status = ESESTATUS_SUCCESS;

  NXP_LOG_ESE_D("%s :phNxpEse_SPM_SetEseClientUpdateState  = 0x%ld",
                __FUNCTION__, arg);
  ret = phPalEse_ioctl(phPalEse_e_SetClientUpdateState, pEseDeviceHandle, arg);
  if (ret < 0) {
    NXP_LOG_ESE_E("%s : failed errno = 0x%x", __FUNCTION__, errno);
    status = ESESTATUS_FAILED;
  }

  return status;
}

/******************************************************************************
 * Function         phNxpEse_SPM_ResetPwr
 *
 * Description      This function request to the nfc i2c driver
 *                  to reset ese.
 *
 * Returns          On Success ESESTATUS_SUCCESS else proper error code
 *
 ******************************************************************************/
ESESTATUS phNxpEse_SPM_ResetPwr(void) {
  int32_t ret = -1;
  ESESTATUS wSpmStatus = ESESTATUS_SUCCESS;
  spm_state_t current_spm_state = SPM_STATE_INVALID;

  /* reset the ese */
  ret = phPalEse_ioctl(phPalEse_e_ChipRst, pEseDeviceHandle, 2);
  if (ret < 0) {
    NXP_LOG_ESE_E("%s : failed errno = 0x%x", __FUNCTION__, errno);
    if (errno == -EBUSY || errno == EBUSY) {
      wSpmStatus = phNxpEse_SPM_GetState(&current_spm_state);
      if (wSpmStatus != ESESTATUS_SUCCESS) {
        NXP_LOG_ESE_E(" %s : phNxpEse_SPM_GetPwrState Failed", __FUNCTION__);
        return wSpmStatus;
      } else {
        if (current_spm_state == SPM_STATE_DWNLD) {
          wSpmStatus = ESESTATUS_DWNLD_BUSY;
        } else {
          wSpmStatus = ESESTATUS_BUSY;
        }
      }

    } else {
      wSpmStatus = ESESTATUS_FAILED;
    }
  }

  return wSpmStatus;
}

/*******************************************************************************
**
** Function         phTmlEse_get_ese_access
**
** Description
**
** Parameters       timeout - timeout to wait for ese access
**
** Returns          success or failure
**
*******************************************************************************/
ESESTATUS phNxpEse_SPM_GetAccess(long timeout) {
  ESESTATUS status = ESESTATUS_SUCCESS;
  NXP_LOG_ESE_D("phTmlEse_get_ese_access(), timeout  %ld", timeout);
#if ((NFC_NXP_ESE_VER == JCOP_VER_3_1) || (NFC_NXP_ESE_VER == JCOP_VER_3_2))
  int ret = -1;

  ret = phPalEse_ioctl(phPalEse_e_GetEseAccess, pEseDeviceHandle, timeout);
  if (ret < 0) {
    if (ret == -EBUSY)
      status = ESESTATUS_BUSY;
    else
      status = ESESTATUS_FAILED;
  }
  NXP_LOG_ESE_D("phTmlEse_get_ese_access(), exit  %d", status);
#endif
  return status;
}
/*******************************************************************************
**
** Function         phNxpEse_SPM_RelAccess
**
** Description
**
** Parameters       timeout - Releases the ese access
**
** Returns          success or failure
**
*******************************************************************************/
ESESTATUS phNxpEse_SPM_RelAccess(void) {
  ESESTATUS status = ESESTATUS_SUCCESS;
#if ((NFC_NXP_ESE_VER == JCOP_VER_3_1) || (NFC_NXP_ESE_VER == JCOP_VER_3_2))
  int ret = -1;
  NXP_LOG_ESE_D("phNxpEse_SPM_RelAccess(): enter");

  ret = phPalEse_ioctl(phPalEse_e_ChipRst, pEseDeviceHandle, 5);
  if (ret < 0) {
    status = ESESTATUS_FAILED;
  }
  NXP_LOG_ESE_D("phNxpEse_SPM_RelAccess(): exit  %d", status);
#endif
  return status;
}
/** @} */
