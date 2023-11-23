/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include <cstdint>

#include "chre/core/event_loop_manager.h"
#include "chre/core/settings.h"
#include "chre/platform/linux/pal_wifi.h"
#include "chre/platform/log.h"
#include "chre/util/system/napp_permissions.h"
#include "chre_api/chre/event.h"
#include "chre_api/chre/wifi.h"
#include "gtest/gtest.h"
#include "test_base.h"
#include "test_event.h"
#include "test_event_queue.h"
#include "test_util.h"

namespace chre {
namespace {
// WifiTimeoutTestBase needs to set timeout more than max wifi async timeout
// time. If not, waitForEvent will timeout before actual timeout happens in
// CHRE, making us unable to observe how system handles timeout.
class WifiTimeoutTestBase : public TestBase {
 protected:
  uint64_t getTimeoutNs() const override {
    return 2 * CHRE_TEST_WIFI_SCAN_RESULT_TIMEOUT_NS;
  }
};

TEST_F(WifiTimeoutTestBase, WifiScanRequestTimeoutTest) {
  CREATE_CHRE_TEST_EVENT(SCAN_REQUEST, 1);

  struct App : public TestNanoapp {
    uint32_t perms = NanoappPermissions::CHRE_PERMS_WIFI;

    decltype(nanoappHandleEvent) *handleEvent = [](uint32_t, uint16_t eventType,
                                                   const void *eventData) {
      static uint32_t cookie;

      switch (eventType) {
        case CHRE_EVENT_WIFI_ASYNC_RESULT: {
          auto *event = static_cast<const chreAsyncResult *>(eventData);
          if (event->success) {
            TestEventQueueSingleton::get()->pushEvent(
                CHRE_EVENT_WIFI_ASYNC_RESULT,
                *(static_cast<const uint32_t *>(event->cookie)));
          }
          break;
        }

        case CHRE_EVENT_WIFI_SCAN_RESULT: {
          TestEventQueueSingleton::get()->pushEvent(
              CHRE_EVENT_WIFI_SCAN_RESULT);
          break;
        }

        case CHRE_EVENT_TEST_EVENT: {
          auto event = static_cast<const TestEvent *>(eventData);
          switch (event->type) {
            case SCAN_REQUEST:
              cookie = *static_cast<uint32_t *>(event->data);
              bool success = chreWifiRequestScanAsyncDefault(&cookie);
              TestEventQueueSingleton::get()->pushEvent(SCAN_REQUEST, success);
          }
        }
      }
    };
  };

  auto app = loadNanoapp<App>();

  constexpr uint32_t timeOutCookie = 0xdead;
  chrePalWifiEnableResponse(PalWifiAsyncRequestTypes::SCAN, false);
  sendEventToNanoapp(app, SCAN_REQUEST, timeOutCookie);
  bool success;
  waitForEvent(SCAN_REQUEST, &success);
  EXPECT_TRUE(success);

  // Add 1 second to prevent race condition.
  constexpr uint8_t kWifiScanRequestTimeoutSec =
      (CHRE_TEST_WIFI_SCAN_RESULT_TIMEOUT_NS / CHRE_NSEC_PER_SEC) + 1;
  std::this_thread::sleep_for(std::chrono::seconds(kWifiScanRequestTimeoutSec));

  // Make sure that we can still request scan after a timedout
  // request.
  constexpr uint32_t successCookie = 0x0101;
  chrePalWifiEnableResponse(PalWifiAsyncRequestTypes::SCAN, true);
  sendEventToNanoapp(app, SCAN_REQUEST, successCookie);
  waitForEvent(SCAN_REQUEST, &success);
  EXPECT_TRUE(success);
  waitForEvent(CHRE_EVENT_WIFI_SCAN_RESULT);

  unloadNanoapp(app);
}

TEST_F(WifiTimeoutTestBase, WifiScanMonitorTimeoutTest) {
  CREATE_CHRE_TEST_EVENT(SCAN_MONITOR_REQUEST, 1);

  struct MonitoringRequest {
    bool enable;
    uint32_t cookie;
  };

  struct App : public TestNanoapp {
    uint32_t perms = NanoappPermissions::CHRE_PERMS_WIFI;

    decltype(nanoappHandleEvent) *handleEvent =
        [](uint32_t, uint16_t eventType, const void *eventData) {
          static uint32_t cookie;

          switch (eventType) {
            case CHRE_EVENT_WIFI_ASYNC_RESULT: {
              auto *event = static_cast<const chreAsyncResult *>(eventData);
              if (event->success) {
                TestEventQueueSingleton::get()->pushEvent(
                    CHRE_EVENT_WIFI_ASYNC_RESULT,
                    *(static_cast<const uint32_t *>(event->cookie)));
              }
              break;
            }

            case CHRE_EVENT_TEST_EVENT: {
              auto event = static_cast<const TestEvent *>(eventData);
              switch (event->type) {
                case SCAN_MONITOR_REQUEST:
                  auto request =
                      static_cast<const MonitoringRequest *>(event->data);
                  cookie = request->cookie;
                  bool success = chreWifiConfigureScanMonitorAsync(
                      request->enable, &cookie);
                  TestEventQueueSingleton::get()->pushEvent(
                      SCAN_MONITOR_REQUEST, success);
              }
            }
          }
        };
  };

  auto app = loadNanoapp<App>();

  MonitoringRequest timeoutRequest{.enable = true, .cookie = 0xdead};
  chrePalWifiEnableResponse(PalWifiAsyncRequestTypes::SCAN_MONITORING, false);
  sendEventToNanoapp(app, SCAN_MONITOR_REQUEST, timeoutRequest);
  bool success;
  waitForEvent(SCAN_MONITOR_REQUEST, &success);
  EXPECT_TRUE(success);

  // Add 1 second to prevent race condition.
  constexpr uint8_t kWifiConfigureScanMonitorTimeoutSec =
      (CHRE_TEST_ASYNC_RESULT_TIMEOUT_NS / CHRE_NSEC_PER_SEC) + 1;
  std::this_thread::sleep_for(
      std::chrono::seconds(kWifiConfigureScanMonitorTimeoutSec));

  // Make sure that we can still request to change scan monitor after a timedout
  // request.
  MonitoringRequest enableRequest{.enable = true, .cookie = 0x1010};
  chrePalWifiEnableResponse(PalWifiAsyncRequestTypes::SCAN_MONITORING, true);
  sendEventToNanoapp(app, SCAN_MONITOR_REQUEST, enableRequest);
  waitForEvent(SCAN_MONITOR_REQUEST, &success);
  EXPECT_TRUE(success);

  uint32_t cookie;
  waitForEvent(CHRE_EVENT_WIFI_ASYNC_RESULT, &cookie);
  EXPECT_EQ(cookie, enableRequest.cookie);
  EXPECT_TRUE(chrePalWifiIsScanMonitoringActive());

  MonitoringRequest disableRequest{.enable = false, .cookie = 0x0101};
  sendEventToNanoapp(app, SCAN_MONITOR_REQUEST, disableRequest);
  waitForEvent(SCAN_MONITOR_REQUEST, &success);
  EXPECT_TRUE(success);

  waitForEvent(CHRE_EVENT_WIFI_ASYNC_RESULT, &cookie);
  EXPECT_EQ(cookie, disableRequest.cookie);
  EXPECT_FALSE(chrePalWifiIsScanMonitoringActive());

  unloadNanoapp(app);
}

TEST_F(WifiTimeoutTestBase, WifiRequestRangingTimeoutTest) {
  CREATE_CHRE_TEST_EVENT(RANGING_REQUEST, 0);
  CREATE_CHRE_TEST_EVENT(RANGING_RESULT_TIMEOUT, 1);

  struct App : public TestNanoapp {
    uint32_t perms = NanoappPermissions::CHRE_PERMS_WIFI;

    decltype(nanoappHandleEvent) *handleEvent =
        [](uint32_t, uint16_t eventType, const void *eventData) {
          static uint32_t cookie;

          switch (eventType) {
            case CHRE_EVENT_WIFI_ASYNC_RESULT: {
              auto *event = static_cast<const chreAsyncResult *>(eventData);
              if (event->success) {
                if (event->errorCode == 0) {
                  TestEventQueueSingleton::get()->pushEvent(
                      CHRE_EVENT_WIFI_ASYNC_RESULT,
                      *(static_cast<const uint32_t *>(event->cookie)));
                }
              } else if (event->errorCode == CHRE_ERROR_TIMEOUT) {
                TestEventQueueSingleton::get()->pushEvent(
                    RANGING_RESULT_TIMEOUT,
                    *(static_cast<const uint32_t *>(event->cookie)));
              }
              break;
            }

            case CHRE_EVENT_TEST_EVENT: {
              auto event = static_cast<const TestEvent *>(eventData);
              switch (event->type) {
                case RANGING_REQUEST:
                  cookie = *static_cast<uint32_t *>(event->data);

                  // Placeholder parameters since linux PAL does not use this to
                  // generate response
                  struct chreWifiRangingTarget dummyRangingTarget = {
                      .macAddress = {0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc},
                      .primaryChannel = 0xdef02468,
                      .centerFreqPrimary = 0xace13579,
                      .centerFreqSecondary = 0xbdf369cf,
                      .channelWidth = 0x48,
                  };

                  struct chreWifiRangingParams dummyRangingParams = {
                      .targetListLen = 1,
                      .targetList = &dummyRangingTarget,
                  };

                  bool success =
                      chreWifiRequestRangingAsync(&dummyRangingParams, &cookie);
                  TestEventQueueSingleton::get()->pushEvent(RANGING_REQUEST,
                                                            success);
              }
            }
          }
        };
  };

  auto app = loadNanoapp<App>();
  uint32_t timeOutCookie = 0xdead;

  chrePalWifiEnableResponse(PalWifiAsyncRequestTypes::RANGING, false);
  sendEventToNanoapp(app, RANGING_REQUEST, timeOutCookie);
  bool success;
  waitForEvent(RANGING_REQUEST, &success);
  EXPECT_TRUE(success);

  // Add 1 second to prevent race condition
  constexpr uint8_t kWifiRequestRangingTimeoutSec =
      (CHRE_TEST_WIFI_RANGING_RESULT_TIMEOUT_NS / CHRE_NSEC_PER_SEC) + 1;
  std::this_thread::sleep_for(
      std::chrono::seconds(kWifiRequestRangingTimeoutSec));

  // Make sure that we can still request ranging after a timedout request
  uint32_t successCookie = 0x0101;
  chrePalWifiEnableResponse(PalWifiAsyncRequestTypes::RANGING, true);
  sendEventToNanoapp(app, RANGING_REQUEST, successCookie);
  waitForEvent(RANGING_REQUEST, &success);
  EXPECT_TRUE(success);

  uint32_t cookie;
  waitForEvent(CHRE_EVENT_WIFI_ASYNC_RESULT, &cookie);
  EXPECT_EQ(cookie, successCookie);

  unloadNanoapp(app);
}

}  // namespace
}  // namespace chre
