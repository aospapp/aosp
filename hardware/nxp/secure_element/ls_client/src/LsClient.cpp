/******************************************************************************
 *
 *  Copyright 2018 NXP
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
#define LOG_TAG "LSClient"
#include "LsClient.h"
#include <dirent.h>
#include <log/log.h>
#include <pthread.h>
#include <stdlib.h>
#include "LsLib.h"

uint8_t datahex(char c);
extern bool ese_debug_enabled;
static android::sp<ISecureElementHalCallback> cCallback;
void* performLSDownload_thread(void* data);
/*******************************************************************************
**
** Function:        LSC_Start
**
** Description:     Starts the LSC update with encrypted data privided in the
                    updater file
**
** Returns:         SUCCESS if ok.
**
*******************************************************************************/
LSCSTATUS LSC_Start(const char* name, const char* dest, uint8_t* pdata,
                    uint16_t len, uint8_t* respSW) {
  static const char fn[] = "LSC_Start";
  LSCSTATUS status = LSCSTATUS_FAILED;
  if (name != NULL) {
    status = Perform_LSC(name, dest, pdata, len, respSW);
  } else {
    ALOGE("%s: LS script file is missing", fn);
  }
  ALOGD_IF(ese_debug_enabled, "%s: Exit; status=0x0%X", fn, status);
  return status;
}

/*******************************************************************************
**
** Function:        LSC_doDownload
**
** Description:     Start LS download process by creating thread
**
** Returns:         SUCCESS of ok
**
*******************************************************************************/
LSCSTATUS LSC_doDownload(
    const android::sp<ISecureElementHalCallback>& clientCallback) {
  static const char fn[] = "LSC_doDownload";
  LSCSTATUS status;
  pthread_t thread;
  pthread_attr_t attr;
  pthread_attr_init(&attr);
  pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
  cCallback = clientCallback;
  if (pthread_create(&thread, &attr, &performLSDownload_thread, NULL) < 0) {
    ALOGE("%s: Thread creation failed", fn);
    status = LSCSTATUS_FAILED;
  } else {
    status = LSCSTATUS_SUCCESS;
  }
  pthread_attr_destroy(&attr);
  return status;
}

/*******************************************************************************
**
** Function:        performLSDownload_thread
**
** Description:     Perform LS during hal init
**
** Returns:         None
**
*******************************************************************************/
void* performLSDownload_thread(__attribute__((unused)) void* data) {
  ALOGD_IF(ese_debug_enabled, "%s enter:  ", __func__);

  const char* lsUpdateBackupPath =
      "/data/vendor/secure_element/loaderservice_updater.txt";
  const char* lsUpdateBackupOutPath =
      "/data/vendor/secure_element/loaderservice_updater_out.txt";

  /*generated SHA-1 string for secureElementLS
  This will remain constant as handled in secureElement HAL*/
  char sha1[] = "6d583e84f2710e6b0f06beebc1a12a1083591373";
  uint8_t hash[20] = {0};

  for (int i = 0; i < 40; i = i + 2) {
    hash[i / 2] =
        (((datahex(sha1[i]) & 0x0F) << 4) | (datahex(sha1[i + 1]) & 0x0F));
  }

  uint8_t resSW[4] = {0x4e, 0x02, 0x69, 0x87};
  FILE* fIn = fopen(lsUpdateBackupPath, "rb");
  if (fIn == NULL) {
    ALOGE("%s Cannot open LS script file %s\n", __func__, lsUpdateBackupPath);
    ALOGE("%s Error : %s", __func__, strerror(errno));
    cCallback->onStateChange(true);
  } else {
    ALOGD_IF(ese_debug_enabled, "%s File opened %s\n", __func__,
             lsUpdateBackupPath);
    fseek(fIn, 0, SEEK_END);
    long fsize = ftell(fIn);
    rewind(fIn);

    char* lsUpdateBuf = (char*)phNxpEse_memalloc(fsize + 1);
    fread(lsUpdateBuf, fsize, 1, fIn);

    FILE* fOut = fopen(lsUpdateBackupOutPath, "wb+");
    if (fOut == NULL) {
      ALOGE("%s Failed to open file %s\n", __func__, lsUpdateBackupOutPath);
      phNxpEse_free(lsUpdateBuf);
      pthread_exit(NULL);
      cCallback->onStateChange(true);
      return NULL;
    }

    long size = fwrite(lsUpdateBuf, 1, fsize, fOut);
    if (size != fsize) {
      ALOGE("%s ERROR - Failed to write %ld bytes to file\n", __func__, fsize);
      phNxpEse_free(lsUpdateBuf);
      pthread_exit(NULL);
      cCallback->onStateChange(true);
      return NULL;
    }

    LSCSTATUS status = LSC_Start(lsUpdateBackupPath, lsUpdateBackupOutPath,
                                 (uint8_t*)hash, (uint16_t)sizeof(hash), resSW);
    ALOGD_IF(ese_debug_enabled, "%s LSC_Start completed\n", __func__);
    if (status == LSCSTATUS_SUCCESS) {
      if (remove(lsUpdateBackupPath) == 0) {
        ALOGD_IF(ese_debug_enabled, "%s  : %s file deleted successfully\n",
                 __func__, lsUpdateBackupPath);
      } else {
        ALOGD_IF(ese_debug_enabled, "%s  : %s file deletion failed!!!\n",
                 __func__, lsUpdateBackupPath);
      }
      cCallback->onStateChange(true);
    } else {
      ESESTATUS status = phNxpEse_deInit();
      if (status == ESESTATUS_SUCCESS) {
        status = phNxpEse_close();
        if (status == ESESTATUS_SUCCESS) {
          ALOGD_IF(ese_debug_enabled, "%s: Ese_close success\n", __func__);
        }
      } else {
        ALOGE("%s: Ese_deInit failed", __func__);
      }
      cCallback->onStateChange(false);
    }
    phNxpEse_free(lsUpdateBuf);
  }
  pthread_exit(NULL);
  ALOGD_IF(ese_debug_enabled, "%s pthread_exit\n", __func__);
  return NULL;
}

/*******************************************************************************
**
** Function:        datahex
**
** Description:     Converts char to uint8_t
**
** Returns:         uint8_t variable
**
*******************************************************************************/
uint8_t datahex(char c) {
  uint8_t value = 0;
  if (c >= '0' && c <= '9')
    value = (c - '0');
  else if (c >= 'A' && c <= 'F')
    value = (10 + (c - 'A'));
  else if (c >= 'a' && c <= 'f')
    value = (10 + (c - 'a'));
  return value;
}
