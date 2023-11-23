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

#include "chre/core/ble_request_manager.h"

#include "chre/core/event_loop_manager.h"
#include "chre/platform/fatal_error.h"
#include "chre/platform/log.h"
#include "chre/util/nested_data_ptr.h"
#include "chre/util/system/event_callbacks.h"

namespace chre {

void BleRequestManager::init() {
  mPlatformBle.init();
}

uint32_t BleRequestManager::getCapabilities() {
  return mPlatformBle.getCapabilities();
}

uint32_t BleRequestManager::getFilterCapabilities() {
  return mPlatformBle.getFilterCapabilities();
}

void BleRequestManager::handleExistingRequest(uint16_t instanceId,
                                              bool *hasExistingRequest,
                                              size_t *requestIndex) {
  const BleRequest *foundRequest =
      mRequests.findRequest(instanceId, requestIndex);
  *hasExistingRequest = (foundRequest != nullptr);
  if (foundRequest != nullptr &&
      foundRequest->getRequestStatus() != RequestStatus::APPLIED) {
    handleAsyncResult(instanceId, foundRequest->isEnabled(),
                      false /* success */, CHRE_ERROR_OBSOLETE_REQUEST,
                      true /* forceUnregister */);
  }
}

bool BleRequestManager::compliesWithBleSetting(uint16_t instanceId,
                                               bool enabled,
                                               bool hasExistingRequest,
                                               size_t requestIndex) {
  bool success = true;
  if (enabled && !bleSettingEnabled()) {
    success = false;
    handleAsyncResult(instanceId, enabled, false /* success */,
                      CHRE_ERROR_FUNCTION_DISABLED);
    if (hasExistingRequest) {
      bool requestChanged = false;
      mRequests.removeRequest(requestIndex, &requestChanged);
    }
  }
  return success;
}

bool BleRequestManager::updateRequests(BleRequest &&request,
                                       bool hasExistingRequest,
                                       bool *requestChanged,
                                       size_t *requestIndex) {
  bool success = true;
  if (hasExistingRequest) {
    mRequests.updateRequest(*requestIndex, std::move(request), requestChanged);
  } else if (request.isEnabled()) {
    success =
        mRequests.addRequest(std::move(request), requestIndex, requestChanged);
  } else {
    // Already disabled requests shouldn't result in work for the PAL.
    *requestChanged = false;
    *requestIndex = mRequests.getRequests().size();
  }
  return success;
}

bool BleRequestManager::startScanAsync(Nanoapp *nanoapp, chreBleScanMode mode,
                                       uint32_t reportDelayMs,
                                       const struct chreBleScanFilter *filter) {
  CHRE_ASSERT(nanoapp);
  BleRequest request(nanoapp->getInstanceId(), true, mode, reportDelayMs,
                     filter);
  return configure(std::move(request));
}

bool BleRequestManager::stopScanAsync(Nanoapp *nanoapp) {
  CHRE_ASSERT(nanoapp);
  BleRequest request(nanoapp->getInstanceId(), false /* enable */);
  return configure(std::move(request));
}

uint32_t BleRequestManager::disableActiveScan(const Nanoapp *nanoapp) {
  CHRE_ASSERT(nanoapp);

  size_t requestIndex;
  const BleRequest *foundRequest =
      mRequests.findRequest(nanoapp->getInstanceId(), &requestIndex);

  if (foundRequest == nullptr || !foundRequest->isEnabled()) {
    // No active request found.
    return 0;
  }

  BleRequest request(nanoapp->getInstanceId(), false /* enable */);
  configure(std::move(request));
  return 1;
}

#ifdef CHRE_BLE_READ_RSSI_SUPPORT_ENABLED
bool BleRequestManager::readRssiAsync(Nanoapp *nanoapp,
                                      uint16_t connectionHandle,
                                      const void *cookie) {
  CHRE_ASSERT(nanoapp);
  if (mPendingRssiRequests.full()) {
    LOG_OOM();
    return false;
  }
  if (mPendingRssiRequests.empty()) {
    // no previous request existed, so issue this one immediately to get
    // an early exit if we get a failure
    auto status = readRssi(connectionHandle);
    if (status != CHRE_ERROR_NONE) {
      return false;
    }
  }
  // it's pending, so report the result asynchronously
  mPendingRssiRequests.push(
      BleReadRssiRequest{nanoapp->getInstanceId(), connectionHandle, cookie});
  return true;
}
#endif

void BleRequestManager::addBleRequestLog(uint32_t instanceId, bool enabled,
                                         size_t requestIndex,
                                         bool compliesWithBleSetting) {
  BleRequestLog log(SystemTime::getMonotonicTime(), instanceId, enabled,
                    compliesWithBleSetting);
  if (enabled) {
    if (instanceId == CHRE_INSTANCE_ID) {
      log.populateRequestData(mRequests.getCurrentMaximalRequest());
    } else if (compliesWithBleSetting) {
      log.populateRequestData(mRequests.getRequests()[requestIndex]);
    }
  }
  mBleRequestLogs.kick_push(log);
}

bool BleRequestManager::configure(BleRequest &&request) {
  bool success = validateParams(request);
  if (success) {
    bool requestChanged = false;
    size_t requestIndex = 0;
    bool hasExistingRequest = false;
    uint16_t instanceId = request.getInstanceId();
    uint8_t enabled = request.isEnabled();
    handleExistingRequest(instanceId, &hasExistingRequest, &requestIndex);
    bool compliant = compliesWithBleSetting(instanceId, enabled,
                                            hasExistingRequest, requestIndex);
    if (compliant) {
      success = updateRequests(std::move(request), hasExistingRequest,
                               &requestChanged, &requestIndex);
      if (success) {
        if (!asyncResponsePending()) {
          if (!requestChanged) {
            handleAsyncResult(instanceId, enabled, true /* success */,
                              CHRE_ERROR_NONE);
            if (requestIndex < mRequests.getRequests().size()) {
              mRequests.getMutableRequests()[requestIndex].setRequestStatus(
                  RequestStatus::APPLIED);
            }
          } else {
            success = controlPlatform();
            if (!success) {
              handleNanoappEventRegistration(instanceId, enabled,
                                             false /* success */,
                                             true /* forceUnregister */);
              mRequests.removeRequest(requestIndex, &requestChanged);
            }
          }
        }
      }
    }
    if (success) {
      addBleRequestLog(instanceId, enabled, requestIndex, compliant);
    }
  }
  return success;
}

bool BleRequestManager::controlPlatform() {
  bool success = false;
  const BleRequest &maxRequest = mRequests.getCurrentMaximalRequest();
  bool enable = bleSettingEnabled() && maxRequest.isEnabled();
  if (enable) {
    chreBleScanFilter filter = maxRequest.getScanFilter();
    success = mPlatformBle.startScanAsync(
        maxRequest.getMode(), maxRequest.getReportDelayMs(), &filter);
    mPendingPlatformRequest =
        BleRequest(0, enable, maxRequest.getMode(),
                   maxRequest.getReportDelayMs(), &filter);
  } else {
    success = mPlatformBle.stopScanAsync();
    mPendingPlatformRequest = BleRequest(0, enable);
  }
  if (success) {
    for (BleRequest &req : mRequests.getMutableRequests()) {
      if (req.getRequestStatus() == RequestStatus::PENDING_REQ) {
        req.setRequestStatus(RequestStatus::PENDING_RESP);
      }
    }
  }

  return success;
}

void BleRequestManager::handleFreeAdvertisingEvent(
    struct chreBleAdvertisementEvent *event) {
  mPlatformBle.releaseAdvertisingEvent(event);
}

void BleRequestManager::freeAdvertisingEventCallback(uint16_t /* eventType */,
                                                     void *eventData) {
  auto event = static_cast<chreBleAdvertisementEvent *>(eventData);
  EventLoopManagerSingleton::get()
      ->getBleRequestManager()
      .handleFreeAdvertisingEvent(event);
}

void BleRequestManager::handleAdvertisementEvent(
    struct chreBleAdvertisementEvent *event) {
  EventLoopManagerSingleton::get()->getEventLoop().postEventOrDie(
      CHRE_EVENT_BLE_ADVERTISEMENT, event, freeAdvertisingEventCallback);
}

void BleRequestManager::handlePlatformChange(bool enable, uint8_t errorCode) {
  auto callback = [](uint16_t /*type*/, void *data, void *extraData) {
    bool enable = NestedDataPtr<bool>(data);
    uint8_t errorCode = NestedDataPtr<uint8_t>(extraData);
    EventLoopManagerSingleton::get()
        ->getBleRequestManager()
        .handlePlatformChangeSync(enable, errorCode);
  };

  EventLoopManagerSingleton::get()->deferCallback(
      SystemCallbackType::BleScanResponse, NestedDataPtr<bool>(enable),
      callback, NestedDataPtr<uint8_t>(errorCode));
}

void BleRequestManager::handlePlatformChangeSync(bool enable,
                                                 uint8_t errorCode) {
  bool success = (errorCode == CHRE_ERROR_NONE);
  // Requests to disable BLE scans should always succeed
  if (!mPendingPlatformRequest.isEnabled() && enable) {
    errorCode = CHRE_ERROR;
    success = false;
    CHRE_ASSERT_LOG(false, "Unable to stop BLE scan");
  }
  if (mInternalRequestPending) {
    mInternalRequestPending = false;
    if (!success) {
      LOGE("Failed to resync BLE platform");
    }
  } else {
    for (BleRequest &req : mRequests.getMutableRequests()) {
      if (req.getRequestStatus() == RequestStatus::PENDING_RESP) {
        handleAsyncResult(req.getInstanceId(), req.isEnabled(), success,
                          errorCode);
        if (success) {
          req.setRequestStatus(RequestStatus::APPLIED);
        }
      }
    }

    if (!success) {
      mRequests.removeRequests(RequestStatus::PENDING_RESP);
    }
  }
  if (success) {
    // No need to waste memory for requests that have no effect on the overall
    // maximal request.
    mRequests.removeDisabledRequests();
    mActivePlatformRequest = std::move(mPendingPlatformRequest);
  }

  dispatchPendingRequests();

  // Only clear mResyncPending if the request succeeded or after all pending
  // requests are dispatched and a resync request can be issued with only the
  // requests that were previously applied.
  if (mResyncPending) {
    if (success) {
      mResyncPending = false;
    } else if (!success && !asyncResponsePending()) {
      mResyncPending = false;
      updatePlatformRequest(true /* forceUpdate */);
    }
  }
  // Finish dispatching pending requests before processing the setting change
  // request to ensure nanoapps receive CHRE_ERROR_FUNCTION_DISABLED responses.
  // If both a resync and a setting change are pending, prioritize the resync.
  // If the resync successfully completes, the PAL will be in the correct state
  // and updatePlatformRequest will not begin a new request.
  if (mSettingChangePending && !asyncResponsePending()) {
    updatePlatformRequest();
    mSettingChangePending = false;
  }
}

void BleRequestManager::dispatchPendingRequests() {
  if (mRequests.hasRequests(RequestStatus::PENDING_REQ)) {
    uint8_t errorCode = CHRE_ERROR_NONE;
    if (!bleSettingEnabled() && mRequests.isMaximalRequestEnabled()) {
      errorCode = CHRE_ERROR_FUNCTION_DISABLED;
    } else if (!controlPlatform()) {
      errorCode = CHRE_ERROR;
    }
    if (errorCode != CHRE_ERROR_NONE) {
      for (const BleRequest &req : mRequests.getRequests()) {
        if (req.getRequestStatus() == RequestStatus::PENDING_REQ) {
          handleAsyncResult(req.getInstanceId(), req.isEnabled(),
                            false /* success */, errorCode);
        }
      }
      mRequests.removeRequests(RequestStatus::PENDING_REQ);
    }
  }
}

void BleRequestManager::handleAsyncResult(uint16_t instanceId, bool enabled,
                                          bool success, uint8_t errorCode,
                                          bool forceUnregister) {
  uint8_t requestType = enabled ? CHRE_BLE_REQUEST_TYPE_START_SCAN
                                : CHRE_BLE_REQUEST_TYPE_STOP_SCAN;
  postAsyncResultEventFatal(instanceId, requestType, success, errorCode);
  handleNanoappEventRegistration(instanceId, enabled, success, forceUnregister);
}

void BleRequestManager::handleNanoappEventRegistration(uint16_t instanceId,
                                                       bool enabled,
                                                       bool success,
                                                       bool forceUnregister) {
  Nanoapp *nanoapp =
      EventLoopManagerSingleton::get()->getEventLoop().findNanoappByInstanceId(
          instanceId);
  if (nanoapp != nullptr) {
    if (success && enabled) {
      nanoapp->registerForBroadcastEvent(CHRE_EVENT_BLE_ADVERTISEMENT);
    } else if (!enabled || forceUnregister) {
      nanoapp->unregisterForBroadcastEvent(CHRE_EVENT_BLE_ADVERTISEMENT);
    }
  }
}

void BleRequestManager::handleRequestStateResyncCallback() {
  auto callback = [](uint16_t /* eventType */, void * /* eventData */,
                     void * /* extraData */) {
    EventLoopManagerSingleton::get()
        ->getBleRequestManager()
        .handleRequestStateResyncCallbackSync();
  };
  EventLoopManagerSingleton::get()->deferCallback(
      SystemCallbackType::BleRequestResyncEvent, nullptr /* data */, callback);
}

void BleRequestManager::handleRequestStateResyncCallbackSync() {
  if (asyncResponsePending()) {
    mResyncPending = true;
  } else {
    updatePlatformRequest(true /* forceUpdate */);
  }
}

#ifdef CHRE_BLE_READ_RSSI_SUPPORT_ENABLED
void BleRequestManager::handleReadRssi(uint8_t errorCode,
                                       uint16_t connectionHandle, int8_t rssi) {
  struct readRssiResponse {
    uint8_t errorCode;
    int8_t rssi;
    uint16_t connectionHandle;
  };

  auto callback = [](uint16_t /* eventType */, void *eventData,
                     void * /* extraData */) {
    readRssiResponse response = NestedDataPtr<readRssiResponse>(eventData);
    EventLoopManagerSingleton::get()->getBleRequestManager().handleReadRssiSync(
        response.errorCode, response.connectionHandle, response.rssi);
  };

  EventLoopManagerSingleton::get()->deferCallback(
      SystemCallbackType::BleReadRssiEvent,
      NestedDataPtr<readRssiResponse>(
          readRssiResponse{errorCode, rssi, connectionHandle}),
      callback);
}

void BleRequestManager::handleReadRssiSync(uint8_t errorCode,
                                           uint16_t connectionHandle,
                                           int8_t rssi) {
  if (mPendingRssiRequests.empty()) {
    FATAL_ERROR(
        "Got unexpected handleReadRssi event without outstanding request");
  }

  if (mPendingRssiRequests.front().connectionHandle != connectionHandle) {
    FATAL_ERROR(
        "Got readRssi event for mismatched connection handle (%d != %d)",
        mPendingRssiRequests.front().connectionHandle, connectionHandle);
  }

  resolvePendingRssiRequest(errorCode, rssi);
  dispatchNextRssiRequestIfAny();
}

void BleRequestManager::resolvePendingRssiRequest(uint8_t errorCode,
                                                  int8_t rssi) {
  auto event = memoryAlloc<chreBleReadRssiEvent>();
  if (event == nullptr) {
    FATAL_ERROR("Failed to alloc BLE async result");
  }

  event->result.cookie = mPendingRssiRequests.front().cookie;
  event->result.success = (errorCode == CHRE_ERROR_NONE);
  event->result.requestType = CHRE_BLE_REQUEST_TYPE_READ_RSSI;
  event->result.errorCode = errorCode;
  event->result.reserved = 0;
  event->connectionHandle = mPendingRssiRequests.front().connectionHandle;
  event->rssi = rssi;

  EventLoopManagerSingleton::get()->getEventLoop().postEventOrDie(
      CHRE_EVENT_BLE_RSSI_READ, event, freeEventDataCallback,
      mPendingRssiRequests.front().instanceId);

  mPendingRssiRequests.pop();
}

void BleRequestManager::dispatchNextRssiRequestIfAny() {
  while (!mPendingRssiRequests.empty()) {
    auto req = mPendingRssiRequests.front();
    auto status = readRssi(req.connectionHandle);
    if (status == CHRE_ERROR_NONE) {
      // control flow resumes in the handleReadRssi() callback, on completion
      return;
    }
    resolvePendingRssiRequest(status, 0x7F /* failure RSSI from BT spec */);
  }
}

uint8_t BleRequestManager::readRssi(uint16_t connectionHandle) {
  if (!bleSettingEnabled()) {
    return CHRE_ERROR_FUNCTION_DISABLED;
  }
  auto success = mPlatformBle.readRssiAsync(connectionHandle);
  if (success) {
    return CHRE_ERROR_NONE;
  } else {
    return CHRE_ERROR;
  }
}
#endif

bool BleRequestManager::getScanStatus(struct chreBleScanStatus * /* status */) {
  // TODO(b/266820139): Implement this
  return false;
}

void BleRequestManager::onSettingChanged(Setting setting, bool /* state */) {
  if (setting == Setting::BLE_AVAILABLE) {
    if (asyncResponsePending()) {
      mSettingChangePending = true;
    } else {
      updatePlatformRequest();
    }
  }
}

void BleRequestManager::updatePlatformRequest(bool forceUpdate) {
  bool desiredPlatformState =
      bleSettingEnabled() && mRequests.isMaximalRequestEnabled();
  bool updatePlatform = (forceUpdate || (desiredPlatformState !=
                                         mActivePlatformRequest.isEnabled()));

  if (updatePlatform) {
    if (controlPlatform()) {
      mInternalRequestPending = true;
      addBleRequestLog(CHRE_INSTANCE_ID, desiredPlatformState,
                       mRequests.getRequests().size(),
                       true /* compliesWithBleSetting */);
    } else {
      FATAL_ERROR("Failed to send update BLE platform request");
    }
  }
}

bool BleRequestManager::asyncResponsePending() const {
  return (mInternalRequestPending ||
          mRequests.hasRequests(RequestStatus::PENDING_RESP));
}

bool BleRequestManager::validateParams(const BleRequest &request) {
  bool valid = true;
  if (request.isEnabled()) {
    for (const chreBleGenericFilter &filter : request.getGenericFilters()) {
      if (!isValidAdType(filter.type)) {
        valid = false;
        break;
      }
      if (filter.len == 0 || filter.len > CHRE_BLE_DATA_LEN_MAX) {
        valid = false;
        break;
      }
    }
  }
  return valid;
}

void BleRequestManager::postAsyncResultEventFatal(uint16_t instanceId,
                                                  uint8_t requestType,
                                                  bool success,
                                                  uint8_t errorCode) {
  chreAsyncResult *event = memoryAlloc<chreAsyncResult>();
  if (event == nullptr) {
    FATAL_ERROR("Failed to alloc BLE async result");
  } else {
    event->requestType = requestType;
    event->success = success;
    event->errorCode = errorCode;
    event->reserved = 0;

    EventLoopManagerSingleton::get()->getEventLoop().postEventOrDie(
        CHRE_EVENT_BLE_ASYNC_RESULT, event, freeEventDataCallback, instanceId);
  }
}

bool BleRequestManager::isValidAdType(uint8_t adType) {
  return adType == CHRE_BLE_AD_TYPE_SERVICE_DATA_WITH_UUID_16_LE;
}

bool BleRequestManager::bleSettingEnabled() {
  return EventLoopManagerSingleton::get()
      ->getSettingManager()
      .getSettingEnabled(Setting::BLE_AVAILABLE);
}

void BleRequestManager::logStateToBuffer(DebugDumpWrapper &debugDump) const {
  debugDump.print("\nBLE:\n");
  debugDump.print(" Active Platform Request:\n");
  mActivePlatformRequest.logStateToBuffer(debugDump,
                                          true /* isPlatformRequest */);
  if (asyncResponsePending()) {
    debugDump.print(" Pending Platform Request:\n");
    mPendingPlatformRequest.logStateToBuffer(debugDump,
                                             true /* isPlatformRequest */);
  }
  debugDump.print(" Request Multiplexer:\n");
  for (const BleRequest &req : mRequests.getRequests()) {
    req.logStateToBuffer(debugDump);
  }
  debugDump.print(" Last %zu valid BLE requests:\n", mBleRequestLogs.size());
  static_assert(kNumBleRequestLogs <= INT8_MAX,
                "kNumBleRequestLogs must be less than INT8_MAX.");
  for (int8_t i = static_cast<int8_t>(mBleRequestLogs.size()) - 1; i >= 0;
       i--) {
    const auto &log = mBleRequestLogs[static_cast<size_t>(i)];
    debugDump.print("  ts=%" PRIu64 " instanceId=%" PRIu32 " %s",
                    log.timestamp.toRawNanoseconds(), log.instanceId,
                    log.enable ? "enable" : "disable\n");
    if (log.enable && log.compliesWithBleSetting) {
      debugDump.print(" mode=%" PRIu8 " reportDelayMs=%" PRIu32
                      " rssiThreshold=%" PRId8 " scanCount=%" PRIu8 "\n",
                      static_cast<uint8_t>(log.mode), log.reportDelayMs,
                      log.rssiThreshold, log.scanFilterCount);
    } else if (log.enable) {
      debugDump.print(" request did not comply with BLE setting\n");
    }
  }
}

}  // namespace chre
