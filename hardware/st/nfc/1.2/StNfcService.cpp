/******************************************************************************
 *
 *  Copyright 2018 NXP
 *  Copyright 2018 ST Microelectronics
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

#define LOG_TAG "stnfc@1.2-service.st"
#include <android/hardware/nfc/1.1/INfc.h>
#include <cutils/properties.h>
#include <dlfcn.h>

#include <hidl/LegacySupport.h>
#include "Nfc.h"

// Generated HIDL files
using android::OK;
using android::sp;
using android::status_t;
using android::hardware::configureRpcThreadpool;
using android::hardware::joinRpcThreadpool;
using android::hardware::nfc::V1_2::INfc;
using android::hardware::nfc::V1_2::implementation::Nfc;

typedef int (*STEseReset)(void);

int main() {
  ALOGD(" ST NFC HAL Service 1.2 is starting.");
  sp<INfc> nfc_service = new Nfc();

  char valueStr[PROPERTY_VALUE_MAX] = {0};
  int len = property_get("persist.vendor.modem.esim.reset", valueStr, "");
  if (len > 0) {
    if (strncmp(valueStr,"needed", 6) == 0) {
      void* stdll = dlopen("/vendor/lib64/libstreset.so", RTLD_NOW);
      ALOGE(" ST NFC HAL eSIM Reset starting.");
      if(stdll) {
        ALOGE(" Recovery");
        STEseReset fn = (STEseReset) dlsym(stdll,"reset_start");
        if(fn){
          ALOGE("Result=%d", fn());
        }
      } else {
        ALOGE("libstreset.so not found, do nothing.");
      }
      ALOGE(" ST NFC HAL eSIM Reset Done.");
    }
  }
  property_set("persist.vendor.modem.esim.reset", "done");

  configureRpcThreadpool(1, true /*callerWillJoin*/);
  status_t status = nfc_service->registerAsService();
  if (status != OK) {
    LOG_ALWAYS_FATAL("Could not register service for NFC HAL Iface (%d).",
                     status);
    return -1;
  }
  ALOGD(" ST NFC service is ready");
  joinRpcThreadpool();
  return 1;
}
