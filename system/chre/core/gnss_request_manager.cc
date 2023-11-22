/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "chre/core/gnss_request_manager.h"

#include "chre/core/event_loop_manager.h"
#include "chre/platform/assert.h"
#include "chre/platform/fatal_error.h"
#include "chre/util/system/debug_dump.h"

namespace chre {

GnssRequestManager::GnssRequestManager()
    : mCurrentLocationSessionInterval(UINT64_MAX) {
  if (!mLocationSessionRequests.reserve(1)) {
    FATAL_ERROR("Failed to allocate GNSS requests list at startup");
  }
}

void GnssRequestManager::init() {
  mPlatformGnss.init();
}

uint32_t GnssRequestManager::getCapabilities() {
  return mPlatformGnss.getCapabilities();
}

bool GnssRequestManager::startLocationSession(Nanoapp *nanoapp,
                                              Milliseconds minInterval,
                                              Milliseconds minTimeToNextFix,
                                              const void *cookie) {
  CHRE_ASSERT(nanoapp);
  return configureLocationSession(nanoapp, true /* enable */, minInterval,
                                  minTimeToNextFix, cookie);
}

bool GnssRequestManager::stopLocationSession(Nanoapp *nanoapp,
                                             const void *cookie) {
  CHRE_ASSERT(nanoapp);
  return configureLocationSession(nanoapp, false /* enable */,
                                  Milliseconds(UINT64_MAX),
                                  Milliseconds(UINT64_MAX), cookie);
}

void GnssRequestManager::handleLocationSessionStatusChange(bool enabled,
                                                           uint8_t errorCode) {
  struct CallbackState {
    bool enabled;
    uint8_t errorCode;
  };

  auto *cbState = memoryAlloc<CallbackState>();
  if (cbState == nullptr) {
    LOGE("Failed to allocate callback state for location session state change");
  } else {
    cbState->enabled = enabled;
    cbState->errorCode = errorCode;

    auto callback = [](uint16_t /* eventType */, void *eventData) {
      auto *state = static_cast<CallbackState *>(eventData);
      EventLoopManagerSingleton::get()->getGnssRequestManager()
          .handleLocationSessionStatusChangeSync(state->enabled,
                                                 state->errorCode);
      memoryFree(state);
    };

    bool callbackDeferred = EventLoopManagerSingleton::get()->deferCallback(
        SystemCallbackType::GnssLocationSessionStatusChange, cbState, callback);
    if (!callbackDeferred) {
      memoryFree(cbState);
    }
  }
}

void GnssRequestManager::handleLocationEvent(chreGnssLocationEvent *event) {
  bool eventPosted = EventLoopManagerSingleton::get()->getEventLoop()
      .postEvent(CHRE_EVENT_GNSS_LOCATION, event, freeLocationEventCallback,
                 kSystemInstanceId, kBroadcastInstanceId);
  if (!eventPosted) {
    FATAL_ERROR("Failed to send GNSS location event");
  }
}

bool GnssRequestManager::logStateToBuffer(char *buffer, size_t *bufferPos,
                                          size_t bufferSize) const {
  bool success = debugDumpPrint(buffer, bufferPos, bufferSize, "\nGNSS:"
                                " Current interval(ms)=%" PRIu64 "\n",
                                mCurrentLocationSessionInterval.
                                getMilliseconds());

  success &= debugDumpPrint(buffer, bufferPos, bufferSize,
                            " GNSS requests:\n");
  for (const auto& request : mLocationSessionRequests) {
    success &= debugDumpPrint(buffer, bufferPos, bufferSize, "  minInterval(ms)"
                              "=%" PRIu64 " nanoappId=%" PRIu32 "\n",
                              request.minInterval.getMilliseconds(),
                              request.nanoappInstanceId);
  }

  success &= debugDumpPrint(buffer, bufferPos, bufferSize,
                            " GNSS transition queue:\n");
  for (const auto& transition : mLocationSessionStateTransitions) {
    success &= debugDumpPrint(buffer, bufferPos, bufferSize,
                              "  minInterval(ms)=%" PRIu64 " enable=%d"
                              " nanoappId=%" PRIu32 "\n",
                              transition.minInterval.getMilliseconds(),
                              transition.enable,
                              transition.nanoappInstanceId);
  }

  return success;
}

bool GnssRequestManager::configureLocationSession(
    Nanoapp *nanoapp, bool enable, Milliseconds minInterval,
    Milliseconds minTimeToFirstFix, const void *cookie) {
  bool success = false;
  uint32_t instanceId = nanoapp->getInstanceId();
  size_t requestIndex = 0;
  bool nanoappHasRequest = nanoappHasLocationSessionRequest(instanceId,
                                                            &requestIndex);
  if (!mLocationSessionStateTransitions.empty()) {
    success = addLocationSessionRequestToQueue(instanceId, enable, minInterval,
                                               cookie);
  } else if (locationSessionIsInRequestedState(enable, minInterval,
                                               nanoappHasRequest)) {
    success = postLocationSessionAsyncResultEvent(
        instanceId, true /* success */, enable, minInterval, CHRE_ERROR_NONE,
        cookie);
  } else if (locationSessionStateTransitionIsRequired(enable, minInterval,
                                                      nanoappHasRequest,
                                                      requestIndex)) {
    success = addLocationSessionRequestToQueue(instanceId, enable,
                                               minInterval, cookie);
    if (success) {
      // TODO: Provide support for min time to next fix. It is currently sent
      // to the platform as zero.
      success = mPlatformGnss.controlLocationSession(enable, minInterval,
                                                     Milliseconds(0));
      if (!success) {
        // TODO: Add a pop_back method.
        mLocationSessionStateTransitions.remove(
            mLocationSessionRequests.size() - 1);
        LOGE("Failed to enable a GNSS location session for nanoapp instance "
             "%" PRIu32, instanceId);
      }
    }
  } else {
    CHRE_ASSERT_LOG(false, "Invalid location session configuration");
  }

  return success;
}

bool GnssRequestManager::nanoappHasLocationSessionRequest(
    uint32_t instanceId, size_t *requestIndex) {
  bool hasLocationSessionRequest = false;
  for (size_t i = 0; i < mLocationSessionRequests.size(); i++) {
    if (mLocationSessionRequests[i].nanoappInstanceId == instanceId) {
      hasLocationSessionRequest = true;
      if (requestIndex != nullptr) {
        *requestIndex = i;
      }

      break;
    }
  }

  return hasLocationSessionRequest;
}

bool GnssRequestManager::addLocationSessionRequestToQueue(
    uint32_t instanceId, bool enable, Milliseconds minInterval,
    const void *cookie) {
  LocationSessionStateTransition stateTransition;
  stateTransition.nanoappInstanceId = instanceId;
  stateTransition.enable = enable;
  stateTransition.minInterval = minInterval;
  stateTransition.cookie = cookie;

  bool success = mLocationSessionStateTransitions.push(stateTransition);
  if (!success) {
    LOGW("Too many location session state transitions");
  }

  return success;
}

bool GnssRequestManager::locationSessionIsEnabled() {
  return !mLocationSessionRequests.empty();
}

bool GnssRequestManager::locationSessionIsInRequestedState(
    bool requestedState, Milliseconds minInterval, bool nanoappHasRequest) {
  bool inTargetState = (requestedState == locationSessionIsEnabled());
  bool meetsMinInterval = (minInterval >= mCurrentLocationSessionInterval);
  bool hasMoreThanOneRequest = (mLocationSessionRequests.size() > 1);
  return ((inTargetState && (!requestedState || meetsMinInterval))
      || (!requestedState && (!nanoappHasRequest || hasMoreThanOneRequest)));
}

bool GnssRequestManager::locationSessionStateTransitionIsRequired(
    bool requestedState, Milliseconds minInterval, bool nanoappHasRequest,
    size_t requestIndex) {
  bool requestToEnable = (requestedState && !locationSessionIsEnabled());
  bool requestToIncreaseRate = (requestedState && locationSessionIsEnabled()
      && minInterval < mCurrentLocationSessionInterval);
  bool requestToDisable = (!requestedState && nanoappHasRequest
                           && mLocationSessionRequests.size() == 1);

  // An effective rate decrease for the location session can only occur if the
  // nanoapp has an existing request.
  bool requestToDecreaseRate = false;
  if (nanoappHasRequest) {
    // The nanoapp has an existing request. Check that the request does not
    // result in a rate decrease by checking if no other nanoapps have the
    // same request, the nanoapp's existing request is not equal to the current
    // requested interval and the new request is slower than the current
    // requested rate.
    size_t requestCount = 0;
    const auto& currentRequest = mLocationSessionRequests[requestIndex];
    for (size_t i = 0; i < mLocationSessionRequests.size(); i++) {
      LocationSessionRequest& request = mLocationSessionRequests[i];
      if (i != requestIndex
          && request.minInterval == currentRequest.minInterval) {
        requestCount++;
      }
    }

    requestToDecreaseRate = (minInterval > mCurrentLocationSessionInterval
        && currentRequest.minInterval == mCurrentLocationSessionInterval
        && requestCount == 0);
  }

  return (requestToEnable || requestToDisable
      || requestToIncreaseRate || requestToDecreaseRate);
}

bool GnssRequestManager::updateLocationSessionRequests(
    bool enable, Milliseconds minInterval, uint32_t instanceId) {
  bool success = true;
  Nanoapp *nanoapp = EventLoopManagerSingleton::get()->getEventLoop()
      .findNanoappByInstanceId(instanceId);
  if (nanoapp == nullptr) {
    CHRE_ASSERT_LOG(false, "Failed to update location session request list for "
                    "non-existent nanoapp");
  } else {
    size_t requestIndex;
    bool hasExistingRequest = nanoappHasLocationSessionRequest(instanceId,
                                                               &requestIndex);
    if (enable) {
      if (hasExistingRequest) {
        // If the nanoapp has an open request ensure that the minInterval is
        // kept up to date.
        mLocationSessionRequests[requestIndex].minInterval = minInterval;
      } else {
        success = nanoapp->registerForBroadcastEvent(CHRE_EVENT_GNSS_LOCATION);
        if (!success) {
          LOGE("Failed to register nanoapp for GNSS location events");
        } else {
          // The location session was successfully enabled for this nanoapp and
          // there is no existing request. Add it to the list of location
          // session nanoapps.
          LocationSessionRequest locationSessionRequest;
          locationSessionRequest.nanoappInstanceId = instanceId;
          locationSessionRequest.minInterval = minInterval;
          success = mLocationSessionRequests.push_back(locationSessionRequest);
          if (!success) {
            nanoapp->unregisterForBroadcastEvent(CHRE_EVENT_GNSS_LOCATION);
            LOGE("Failed to add nanoapp to the list of location session "
                 "nanoapps");
          }
        }
      }
    } else {
      if (!hasExistingRequest) {
        success = false;
        LOGE("Received a location session state change for a non-existent "
             "nanoapp");
      } else {
        // The location session was successfully disabled for a previously
        // enabled nanoapp. Remove it from the list of requests.
        mLocationSessionRequests.erase(requestIndex);
        nanoapp->unregisterForBroadcastEvent(CHRE_EVENT_GNSS_LOCATION);
      }
    }
  }

  return success;
}

bool GnssRequestManager::postLocationSessionAsyncResultEvent(
    uint32_t instanceId, bool success, bool enable, Milliseconds minInterval,
    uint8_t errorCode, const void *cookie) {
  bool eventPosted = false;
  if (!success || updateLocationSessionRequests(enable, minInterval,
                                                instanceId)) {
    chreAsyncResult *event = memoryAlloc<chreAsyncResult>();
    if (event == nullptr) {
      LOGE("Failed to allocate location session async result event");
    } else {
      if (enable) {
        event->requestType = CHRE_GNSS_REQUEST_TYPE_LOCATION_SESSION_START;
      } else {
        event->requestType = CHRE_GNSS_REQUEST_TYPE_LOCATION_SESSION_STOP;
      }

      event->success = success;
      event->errorCode = errorCode;
      event->reserved = 0;
      event->cookie = cookie;

      eventPosted = EventLoopManagerSingleton::get()->getEventLoop()
          .postEvent(CHRE_EVENT_GNSS_ASYNC_RESULT, event, freeEventDataCallback,
                     kSystemInstanceId, instanceId);

      if (!eventPosted) {
        memoryFree(event);
      }
    }
  }

  return eventPosted;
}

void GnssRequestManager::postLocationSessionAsyncResultEventFatal(
    uint32_t instanceId, bool success, bool enable, Milliseconds minInterval,
    uint8_t errorCode, const void *cookie) {
  if (!postLocationSessionAsyncResultEvent(instanceId, success, enable,
                                           minInterval, errorCode, cookie)) {
    FATAL_ERROR("Failed to send GNSS location request async result event");
  }
}

void GnssRequestManager::handleLocationSessionStatusChangeSync(
    bool enabled, uint8_t errorCode) {
  bool success = (errorCode == CHRE_ERROR_NONE);

  CHRE_ASSERT_LOG(!mLocationSessionStateTransitions.empty(),
                  "handleLocationSessionStatusChangeSync called with no "
                  "transitions");
  if (!mLocationSessionStateTransitions.empty()) {
    const auto& stateTransition = mLocationSessionStateTransitions.front();

    if (success) {
      mCurrentLocationSessionInterval = stateTransition.minInterval;
    }

    success &= (stateTransition.enable == enabled);
    postLocationSessionAsyncResultEventFatal(stateTransition.nanoappInstanceId,
                                             success, stateTransition.enable,
                                             stateTransition.minInterval,
                                             errorCode, stateTransition.cookie);
    mLocationSessionStateTransitions.pop();
  }

  while (!mLocationSessionStateTransitions.empty()) {
    const auto& stateTransition = mLocationSessionStateTransitions.front();

    size_t requestIndex;
    bool nanoappHasRequest = nanoappHasLocationSessionRequest(
        stateTransition.nanoappInstanceId, &requestIndex);

    if (locationSessionStateTransitionIsRequired(stateTransition.enable,
                                                 stateTransition.minInterval,
                                                 nanoappHasRequest,
                                                 requestIndex)) {
      if (mPlatformGnss.controlLocationSession(stateTransition.enable,
                                               stateTransition.minInterval,
                                               Milliseconds(0))) {
        break;
      } else {
        LOGE("Failed to enable a GNSS location session for nanoapp instance "
             "%" PRIu32, stateTransition.nanoappInstanceId);
        postLocationSessionAsyncResultEventFatal(
            stateTransition.nanoappInstanceId, false /* success */,
            stateTransition.enable, stateTransition.minInterval,
            CHRE_ERROR, stateTransition.cookie);
        mLocationSessionStateTransitions.pop();
      }
    } else {
      postLocationSessionAsyncResultEventFatal(
          stateTransition.nanoappInstanceId, true /* success */,
          stateTransition.enable, stateTransition.minInterval,
          errorCode, stateTransition.cookie);
      mLocationSessionStateTransitions.pop();
    }
  }
}

void GnssRequestManager::handleFreeLocationEvent(chreGnssLocationEvent *event) {
  mPlatformGnss.releaseLocationEvent(event);
}

void GnssRequestManager::freeLocationEventCallback(uint16_t eventType,
                                                   void *eventData) {
  auto *locationEvent = static_cast<chreGnssLocationEvent *>(eventData);
  EventLoopManagerSingleton::get()->getGnssRequestManager()
      .handleFreeLocationEvent(locationEvent);
}

}  // namespace chre
