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

#include <gtest/gtest.h>

#include "common/init_flags.h"
#include "internal_include/bt_trace.h"
#include "stack/btm/btm_int_types.h"
#include "stack/include/l2cap_hci_link_interface.h"
#include "stack/l2cap/l2c_int.h"
#include "types/raw_address.h"

tBTM_CB btm_cb;
extern tL2C_CB l2cb;

// Global trace level referred in the code under test
uint8_t appl_trace_level = BT_TRACE_LEVEL_VERBOSE;

extern "C" void LogMsg(uint32_t trace_set_mask, const char* fmt_str, ...) {}

const char* test_flags[] = {
    "INIT_logging_debug_enabled_for_all=true",
    nullptr,
};

class StackL2capTest : public ::testing::Test {
 protected:
  void SetUp() override {
    bluetooth::common::InitFlags::Load(test_flags);
    l2cb = {};  // TODO Use proper init/free APIs
  }

  void TearDown() override {}
};

TEST_F(StackL2capTest, l2cble_process_data_length_change_event) {
  l2cb.lcb_pool[0].tx_data_len = 0xdead;

  // ACL unknown and legal inputs
  l2cble_process_data_length_change_event(0x1234, 0x001b, 0x001b);
  ASSERT_EQ(0xdead, l2cb.lcb_pool[0].tx_data_len);

  l2cb.lcb_pool[0].in_use = true;
  l2cu_set_lcb_handle(l2cb.lcb_pool[0], 0x1234);
  ASSERT_EQ(0x1234, l2cb.lcb_pool[0].Handle());

  // ACL known and illegal inputs
  l2cble_process_data_length_change_event(0x1234, 1, 1);
  ASSERT_EQ(0xdead, l2cb.lcb_pool[0].tx_data_len);

  // ACL known and legal inputs
  l2cble_process_data_length_change_event(0x1234, 0x001b, 0x001b);
  ASSERT_EQ(0x001b, l2cb.lcb_pool[0].tx_data_len);
}
