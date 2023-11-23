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
#include "chre/platform/linux/pal_nan.h"
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

CREATE_CHRE_TEST_EVENT(SCAN_REQUEST, 20);

struct WifiAsyncData {
  const uint32_t *cookie;
  chreError errorCode;
};

class WifiScanRequestQueueTestBase : public TestBase {
 public:
  void SetUp() {
    TestBase::SetUp();
    // Add delay to make sure the requests are queued.
    chrePalWifiDelayResponse(PalWifiAsyncRequestTypes::SCAN,
                             std::chrono::seconds(1));
  }

  void TearDown() {
    TestBase::TearDown();
    chrePalWifiDelayResponse(PalWifiAsyncRequestTypes::SCAN,
                             std::chrono::seconds(0));
  }
};

struct WifiScanTestNanoapp : public TestNanoapp {
  uint32_t perms = NanoappPermissions::CHRE_PERMS_WIFI;

  decltype(nanoappHandleEvent) *handleEvent = [](uint32_t, uint16_t eventType,
                                                 const void *eventData) {
    constexpr uint8_t kMaxPendingCookie = 10;
    static uint32_t cookies[kMaxPendingCookie];
    static uint8_t nextFreeCookieIndex = 0;

    switch (eventType) {
      case CHRE_EVENT_WIFI_ASYNC_RESULT: {
        auto *event = static_cast<const chreAsyncResult *>(eventData);
        TestEventQueueSingleton::get()->pushEvent(
            CHRE_EVENT_WIFI_ASYNC_RESULT,
            WifiAsyncData{
                .cookie = static_cast<const uint32_t *>(event->cookie),
                .errorCode = static_cast<chreError>(event->errorCode)});
        break;
      }

      case CHRE_EVENT_WIFI_SCAN_RESULT: {
        TestEventQueueSingleton::get()->pushEvent(CHRE_EVENT_WIFI_SCAN_RESULT);
        break;
      }

      case CHRE_EVENT_TEST_EVENT: {
        auto event = static_cast<const TestEvent *>(eventData);
        switch (event->type) {
          case SCAN_REQUEST:
            bool success = false;
            if (nextFreeCookieIndex < kMaxPendingCookie) {
              cookies[nextFreeCookieIndex] =
                  *static_cast<uint32_t *>(event->data);
              success = chreWifiRequestScanAsyncDefault(
                  &cookies[nextFreeCookieIndex]);
              nextFreeCookieIndex++;
            } else {
              LOGE("Too many cookies passed from test body!");
            }
            TestEventQueueSingleton::get()->pushEvent(SCAN_REQUEST, success);
        }
      }
    }
  };
};

TEST_F(TestBase, WifiScanBasicSettingTest) {
  auto app = loadNanoapp<WifiScanTestNanoapp>();

  EventLoopManagerSingleton::get()->getSettingManager().postSettingChange(
      Setting::WIFI_AVAILABLE, true /* enabled */);

  constexpr uint32_t firstCookie = 0x1010;
  bool success;
  WifiAsyncData wifiAsyncData;

  sendEventToNanoapp(app, SCAN_REQUEST, firstCookie);
  waitForEvent(SCAN_REQUEST, &success);
  EXPECT_TRUE(success);

  waitForEvent(CHRE_EVENT_WIFI_ASYNC_RESULT, &wifiAsyncData);
  EXPECT_EQ(wifiAsyncData.errorCode, CHRE_ERROR_NONE);
  EXPECT_EQ(*wifiAsyncData.cookie, firstCookie);
  waitForEvent(CHRE_EVENT_WIFI_SCAN_RESULT);

  EventLoopManagerSingleton::get()->getSettingManager().postSettingChange(
      Setting::WIFI_AVAILABLE, false /* enabled */);

  constexpr uint32_t secondCookie = 0x2020;
  sendEventToNanoapp(app, SCAN_REQUEST, secondCookie);
  waitForEvent(SCAN_REQUEST, &success);
  EXPECT_TRUE(success);

  waitForEvent(CHRE_EVENT_WIFI_ASYNC_RESULT, &wifiAsyncData);
  EXPECT_EQ(wifiAsyncData.errorCode, CHRE_ERROR_FUNCTION_DISABLED);
  EXPECT_EQ(*wifiAsyncData.cookie, secondCookie);

  EventLoopManagerSingleton::get()->getSettingManager().postSettingChange(
      Setting::WIFI_AVAILABLE, true /* enabled */);
  unloadNanoapp(app);
}

TEST_F(WifiScanRequestQueueTestBase, WifiQueuedScanSettingChangeTest) {
  struct WifiScanTestNanoappTwo : public WifiScanTestNanoapp {
    uint64_t id = 0x1123456789abcdef;
  };

  auto firstApp = loadNanoapp<WifiScanTestNanoapp>();
  auto secondApp = loadNanoapp<WifiScanTestNanoappTwo>();

  constexpr uint32_t firstRequestCookie = 0x1010;
  constexpr uint32_t secondRequestCookie = 0x2020;
  bool success;
  sendEventToNanoapp(firstApp, SCAN_REQUEST, firstRequestCookie);
  waitForEvent(SCAN_REQUEST, &success);
  EXPECT_TRUE(success);
  sendEventToNanoapp(secondApp, SCAN_REQUEST, secondRequestCookie);
  waitForEvent(SCAN_REQUEST, &success);
  EXPECT_TRUE(success);

  EventLoopManagerSingleton::get()->getSettingManager().postSettingChange(
      Setting::WIFI_AVAILABLE, false /* enabled */);

  WifiAsyncData wifiAsyncData;
  waitForEvent(CHRE_EVENT_WIFI_ASYNC_RESULT, &wifiAsyncData);
  EXPECT_EQ(wifiAsyncData.errorCode, CHRE_ERROR_NONE);
  EXPECT_EQ(*wifiAsyncData.cookie, firstRequestCookie);
  waitForEvent(CHRE_EVENT_WIFI_SCAN_RESULT);

  waitForEvent(CHRE_EVENT_WIFI_ASYNC_RESULT, &wifiAsyncData);
  EXPECT_EQ(wifiAsyncData.errorCode, CHRE_ERROR_FUNCTION_DISABLED);
  EXPECT_EQ(*wifiAsyncData.cookie, secondRequestCookie);

  EventLoopManagerSingleton::get()->getSettingManager().postSettingChange(
      Setting::WIFI_AVAILABLE, true /* enabled */);

  unloadNanoapp(firstApp);
  unloadNanoapp(secondApp);
}

TEST_F(WifiScanRequestQueueTestBase, WifiScanRejectRequestFromSameNanoapp) {
  auto app = loadNanoapp<WifiScanTestNanoapp>();

  constexpr uint32_t firstRequestCookie = 0x1010;
  constexpr uint32_t secondRequestCookie = 0x2020;
  bool success;
  sendEventToNanoapp(app, SCAN_REQUEST, firstRequestCookie);
  waitForEvent(SCAN_REQUEST, &success);
  EXPECT_TRUE(success);
  sendEventToNanoapp(app, SCAN_REQUEST, secondRequestCookie);
  waitForEvent(SCAN_REQUEST, &success);
  EXPECT_FALSE(success);

  WifiAsyncData wifiAsyncData;
  waitForEvent(CHRE_EVENT_WIFI_ASYNC_RESULT, &wifiAsyncData);
  EXPECT_EQ(wifiAsyncData.errorCode, CHRE_ERROR_NONE);
  EXPECT_EQ(*wifiAsyncData.cookie, firstRequestCookie);
  waitForEvent(CHRE_EVENT_WIFI_SCAN_RESULT);

  unloadNanoapp(app);
}

TEST_F(WifiScanRequestQueueTestBase, WifiScanActiveScanFromDistinctNanoapps) {
  CREATE_CHRE_TEST_EVENT(CONCURRENT_NANOAPP_RECEIVED_EXPECTED_ASYNC_EVENT_COUNT,
                         1);
  CREATE_CHRE_TEST_EVENT(CONCURRENT_NANOAPP_READ_COOKIE, 2);

  struct AppCookies {
    uint32_t sent = 0;
    uint32_t received = 0;
  };

  constexpr uint64_t kAppOneId = 0x0123456789000001;
  constexpr uint64_t kAppTwoId = 0x0123456789000002;

  struct WifiScanTestConcurrentNanoappOne : public TestNanoapp {
    uint32_t perms = NanoappPermissions::CHRE_PERMS_WIFI;
    uint64_t id = kAppOneId;

    decltype(nanoappHandleEvent) *handleEvent = [](uint32_t, uint16_t eventType,
                                                   const void *eventData) {
      constexpr uint8_t kExpectedReceiveAsyncResultCount = 2;
      static uint8_t receivedCookieCount = 0;
      static AppCookies appOneCookies;
      static AppCookies appTwoCookies;

      // Retrieve cookies from different apps that have the same access to
      // static storage due to inheritance.
      AppCookies *appCookies =
          chreGetAppId() == kAppTwoId ? &appTwoCookies : &appOneCookies;

      switch (eventType) {
        case CHRE_EVENT_WIFI_ASYNC_RESULT: {
          auto *event = static_cast<const chreAsyncResult *>(eventData);
          if (event->errorCode == CHRE_ERROR_NONE) {
            appCookies->received =
                *static_cast<const uint32_t *>(event->cookie);
            ++receivedCookieCount;
          } else {
            LOGE("Received failed async result");
          }

          if (receivedCookieCount == kExpectedReceiveAsyncResultCount) {
            TestEventQueueSingleton::get()->pushEvent(
                CONCURRENT_NANOAPP_RECEIVED_EXPECTED_ASYNC_EVENT_COUNT);
          }
          break;
        }

        case CHRE_EVENT_TEST_EVENT: {
          auto event = static_cast<const TestEvent *>(eventData);
          bool success = false;
          uint32_t expectedCookie;
          switch (event->type) {
            case SCAN_REQUEST:
              appCookies->sent = *static_cast<uint32_t *>(event->data);
              success = chreWifiRequestScanAsyncDefault(&(appCookies->sent));
              TestEventQueueSingleton::get()->pushEvent(SCAN_REQUEST, success);
              break;
            case CONCURRENT_NANOAPP_READ_COOKIE:
              TestEventQueueSingleton::get()->pushEvent(
                  CONCURRENT_NANOAPP_READ_COOKIE, appCookies->received);
              break;
          }
        }
      };
    };
  };

  struct WifiScanTestConcurrentNanoappTwo
      : public WifiScanTestConcurrentNanoappOne {
    uint64_t id = kAppTwoId;
  };

  auto appOne = loadNanoapp<WifiScanTestConcurrentNanoappOne>();
  auto appTwo = loadNanoapp<WifiScanTestConcurrentNanoappTwo>();

  constexpr uint32_t kAppOneRequestCookie = 0x1010;
  constexpr uint32_t kAppTwoRequestCookie = 0x2020;
  bool success;
  sendEventToNanoapp(appOne, SCAN_REQUEST, kAppOneRequestCookie);
  waitForEvent(SCAN_REQUEST, &success);
  EXPECT_TRUE(success);
  sendEventToNanoapp(appTwo, SCAN_REQUEST, kAppTwoRequestCookie);
  waitForEvent(SCAN_REQUEST, &success);
  EXPECT_TRUE(success);

  waitForEvent(CONCURRENT_NANOAPP_RECEIVED_EXPECTED_ASYNC_EVENT_COUNT);

  uint32_t receivedCookie;
  sendEventToNanoapp(appOne, CONCURRENT_NANOAPP_READ_COOKIE);
  waitForEvent(CONCURRENT_NANOAPP_READ_COOKIE, &receivedCookie);
  EXPECT_EQ(kAppOneRequestCookie, receivedCookie);

  sendEventToNanoapp(appTwo, CONCURRENT_NANOAPP_READ_COOKIE);
  waitForEvent(CONCURRENT_NANOAPP_READ_COOKIE, &receivedCookie);
  EXPECT_EQ(kAppTwoRequestCookie, receivedCookie);

  unloadNanoapp(appOne);
  unloadNanoapp(appTwo);
}

}  // namespace
}  // namespace chre