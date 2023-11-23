/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include "chre/pal/ble.h"

#include "chre.h"
#include "chre/platform/linux/task_util/task_manager.h"
#include "chre/util/memory.h"
#include "chre/util/unique_ptr.h"

#include <chrono>
#include <optional>

/**
 * A simulated implementation of the BLE PAL for the linux platform.
 */
namespace {

using chre::TaskManagerSingleton;

const struct chrePalSystemApi *gSystemApi = nullptr;
const struct chrePalBleCallbacks *gCallbacks = nullptr;

bool gBleEnabled = false;

// Tasks for startScan and stopScan.
std::optional<uint32_t> gBleStartScanTaskId;
std::optional<uint32_t> gBleStopScanTaskId;

std::chrono::milliseconds scanModeToInterval(chreBleScanMode mode) {
  std::chrono::milliseconds interval(1000);
  switch (mode) {
    case CHRE_BLE_SCAN_MODE_BACKGROUND:
      interval = std::chrono::milliseconds(2000);
      break;
    case CHRE_BLE_SCAN_MODE_FOREGROUND:
      interval = std::chrono::milliseconds(1000);
      break;
    case CHRE_BLE_SCAN_MODE_AGGRESSIVE:
      interval = std::chrono::milliseconds(500);
      break;
  }
  return interval;
}

void startScan() {
  auto event = chre::MakeUniqueZeroFill<struct chreBleAdvertisementEvent>();
  auto report = chre::MakeUniqueZeroFill<struct chreBleAdvertisingReport>();
  uint8_t *data =
      static_cast<uint8_t *>(chre::memoryAlloc(sizeof(uint8_t) * 2));
  data[0] = 0x01;
  data[1] = 0x16;
  report->timestamp = chreGetTime();
  report->data = data;
  report->dataLength = 2;
  event->reports = report.release();
  event->numReports = 1;
  gCallbacks->advertisingEventCallback(event.release());
}

void stopScan() {
  gCallbacks->scanStatusChangeCallback(false, CHRE_ERROR_NONE);
}

void stopAllTasks() {
  if (gBleStartScanTaskId.has_value()) {
    TaskManagerSingleton::get()->cancelTask(gBleStartScanTaskId.value());
  }

  if (gBleStopScanTaskId.has_value()) {
    TaskManagerSingleton::get()->cancelTask(gBleStopScanTaskId.value());
  }
}

uint32_t chrePalBleGetCapabilities() {
  return CHRE_BLE_CAPABILITIES_SCAN |
         CHRE_BLE_CAPABILITIES_SCAN_RESULT_BATCHING |
         CHRE_BLE_CAPABILITIES_SCAN_FILTER_BEST_EFFORT;
}

uint32_t chrePalBleGetFilterCapabilities() {
  return CHRE_BLE_FILTER_CAPABILITIES_RSSI |
         CHRE_BLE_FILTER_CAPABILITIES_SERVICE_DATA;
}

bool chrePalBleStartScan(chreBleScanMode mode, uint32_t /* reportDelayMs */,
                         const struct chreBleScanFilter * /* filter */) {
  stopAllTasks();

  gCallbacks->scanStatusChangeCallback(true, CHRE_ERROR_NONE);
  gBleStartScanTaskId =
      TaskManagerSingleton::get()->addTask(startScan, scanModeToInterval(mode));
  if (!gBleStartScanTaskId.has_value()) {
    return false;
  }

  gBleEnabled = true;
  return true;
}

bool chrePalBleStopScan() {
  stopAllTasks();
  gBleStopScanTaskId = TaskManagerSingleton::get()->addTask(stopScan);
  if (!gBleStopScanTaskId.has_value()) {
    return false;
  }

  gBleEnabled = false;
  return true;
}

void chrePalBleReleaseAdvertisingEvent(
    struct chreBleAdvertisementEvent *event) {
  for (size_t i = 0; i < event->numReports; i++) {
    auto report = const_cast<chreBleAdvertisingReport *>(&(event->reports[i]));
    chre::memoryFree(const_cast<uint8_t *>(report->data));
  }
  chre::memoryFree(const_cast<chreBleAdvertisingReport *>(event->reports));
  chre::memoryFree(event);
}

bool chrePalBleReadRssi(uint16_t connectionHandle) {
  gCallbacks->readRssiCallback(CHRE_ERROR_NONE, connectionHandle, -65);
  return true;
}

void chrePalBleApiClose() {
  stopAllTasks();
}

bool chrePalBleApiOpen(const struct chrePalSystemApi *systemApi,
                       const struct chrePalBleCallbacks *callbacks) {
  chrePalBleApiClose();

  bool success = false;
  if (systemApi != nullptr && callbacks != nullptr) {
    gSystemApi = systemApi;
    gCallbacks = callbacks;
    success = true;
  }

  return success;
}

}  // anonymous namespace

bool chrePalIsBleEnabled() {
  return gBleEnabled;
}

const struct chrePalBleApi *chrePalBleGetApi(uint32_t requestedApiVersion) {
  static const struct chrePalBleApi kApi = {
      .moduleVersion = CHRE_PAL_BLE_API_CURRENT_VERSION,
      .open = chrePalBleApiOpen,
      .close = chrePalBleApiClose,
      .getCapabilities = chrePalBleGetCapabilities,
      .getFilterCapabilities = chrePalBleGetFilterCapabilities,
      .startScan = chrePalBleStartScan,
      .stopScan = chrePalBleStopScan,
      .releaseAdvertisingEvent = chrePalBleReleaseAdvertisingEvent,
      .readRssi = chrePalBleReadRssi,
  };

  if (!CHRE_PAL_VERSIONS_ARE_COMPATIBLE(kApi.moduleVersion,
                                        requestedApiVersion)) {
    return nullptr;
  } else {
    return &kApi;
  }
}
