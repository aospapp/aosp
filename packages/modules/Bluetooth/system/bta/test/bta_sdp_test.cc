/*
 * Copyright 2022 The Android Open Source Project
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

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdarg.h>

#include <string>

#include "bta/dm/bta_dm_int.h"
#include "test/common/main_handler.h"
#include "test/mock/mock_osi_alarm.h"
#include "test/mock/mock_osi_allocator.h"
#include "test/mock/mock_stack_gatt_api.h"

void BTA_dm_on_hw_on();
void BTA_dm_on_hw_off();

struct alarm_t {
  alarm_t(const char* name){};
  int any_value;
};

class BtaSdpTest : public testing::Test {
 protected:
  void SetUp() override {
    test::mock::osi_allocator::osi_calloc.body = [](size_t size) -> void* {
      return calloc(1, size);
    };
    test::mock::osi_allocator::osi_free.body = [](void* ptr) { free(ptr); };
    test::mock::osi_alarm::alarm_new.body = [](const char* name) -> alarm_t* {
      return new alarm_t(name);
    };
    test::mock::osi_alarm::alarm_free.body = [](alarm_t* alarm) {
      delete alarm;
    };
    test::mock::stack_gatt_api::GATT_Register.body =
        [](const bluetooth::Uuid& p_app_uuid128, const std::string name,
           tGATT_CBACK* p_cb_info, bool eatt_support) { return 5; };

    main_thread_start_up();
    sync_main_handler();

    BTA_dm_on_hw_on();
  }

  void TearDown() override {
    BTA_dm_on_hw_off();

    sync_main_handler();
    main_thread_shut_down();

    test::mock::stack_gatt_api::GATT_Register = {};
    test::mock::osi_allocator::osi_calloc = {};
    test::mock::osi_allocator::osi_free = {};
    test::mock::osi_alarm::alarm_new = {};
    test::mock::osi_alarm::alarm_free = {};
  }
};

class BtaSdpRegisteredTest : public BtaSdpTest {
 protected:
  void SetUp() override {
    BtaSdpTest::SetUp();
    bta_sys_register(BTA_ID_DM_SEARCH, &bta_sys_reg);
  }

  void TearDown() override {
    bta_sys_deregister(BTA_ID_DM_SEARCH);
    BtaSdpTest::TearDown();
  }

  tBTA_SYS_REG bta_sys_reg = {
      .evt_hdlr = [](BT_HDR_RIGID* p_msg) -> bool {
        osi_free(p_msg);
        return false;
      },
      .disable = []() {},
  };
};

TEST_F(BtaSdpTest, nop) {}

TEST_F(BtaSdpRegisteredTest, bta_dm_sdp_result_SDP_SUCCESS) {
  bta_dm_search_cb.service_index = BTA_MAX_SERVICE_ID;

  tBTA_DM_MSG msg = {
      .sdp_event =
          {
              .sdp_result = SDP_SUCCESS,
          },
  };
  bta_dm_sdp_result(&msg);
}
