/*
 * Copyright 2021 The Android Open Source Project
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

#include <future>
#include <map>

#include "bta/include/bta_ag_api.h"
#include "btcore/include/module.h"
#include "btif/include/btif_api.h"
#include "btif/include/btif_common.h"
#include "types/raw_address.h"

void set_hal_cbacks(bt_callbacks_t* callbacks);

uint8_t appl_trace_level = BT_TRACE_LEVEL_DEBUG;
uint8_t btif_trace_level = BT_TRACE_LEVEL_DEBUG;
uint8_t btu_trace_level = BT_TRACE_LEVEL_DEBUG;

const tBTA_AG_RES_DATA tBTA_AG_RES_DATA::kEmpty = {};

module_t bt_utils_module;
module_t gd_controller_module;
module_t gd_idle_module;
module_t gd_shim_module;
module_t osi_module;

namespace {

auto timeout_time = std::chrono::seconds(3);

std::map<std::string, std::function<void()>> callback_map_;
#define TESTCB                                             \
  if (callback_map_.find(__func__) != callback_map_.end()) \
    callback_map_[__func__]();

void adapter_state_changed_callback(bt_state_t state) {}
void adapter_properties_callback(bt_status_t status, int num_properties,
                                 bt_property_t* properties) {}
void remote_device_properties_callback(bt_status_t status, RawAddress* bd_addr,
                                       int num_properties,
                                       bt_property_t* properties) {}
void device_found_callback(int num_properties, bt_property_t* properties) {}
void discovery_state_changed_callback(bt_discovery_state_t state) {}
void pin_request_callback(RawAddress* remote_bd_addr, bt_bdname_t* bd_name,
                          uint32_t cod, bool min_16_digit) {}
void ssp_request_callback(RawAddress* remote_bd_addr, bt_bdname_t* bd_name,
                          uint32_t cod, bt_ssp_variant_t pairing_variant,
                          uint32_t pass_key) {}
void bond_state_changed_callback(bt_status_t status, RawAddress* remote_bd_addr,
                                 bt_bond_state_t state, int fail_reason) {}
void address_consolidate_callback(RawAddress* main_bd_addr,
                                  RawAddress* secondary_bd_addr) {}
void acl_state_changed_callback(bt_status_t status, RawAddress* remote_bd_addr,
                                bt_acl_state_t state, int transport_link_type,
                                bt_hci_error_code_t hci_reason) {}
void link_quality_report_callback(uint64_t timestamp, int report_id, int rssi,
                                  int snr, int retransmission_count,
                                  int packets_not_receive_count,
                                  int negative_acknowledgement_count) {}
void callback_thread_event(bt_cb_thread_evt evt) { TESTCB; }
void dut_mode_recv_callback(uint16_t opcode, uint8_t* buf, uint8_t len) {}
void le_test_mode_callback(bt_status_t status, uint16_t num_packets) {}
void energy_info_callback(bt_activity_energy_info* energy_info,
                          bt_uid_traffic_t* uid_data) {}
void generate_local_oob_data_callback(tBT_TRANSPORT transport,
                                      bt_oob_data_t oob_data) {}
void switch_buffer_size_callback(bool is_low_latency_buffer_size) {}
void switch_codec_callback(bool is_low_latency_buffer_size) {}
#undef TESTCB

bt_callbacks_t callbacks = {
    .size = sizeof(bt_callbacks_t),
    .adapter_state_changed_cb = adapter_state_changed_callback,
    .adapter_properties_cb = adapter_properties_callback,
    .remote_device_properties_cb = remote_device_properties_callback,
    .device_found_cb = device_found_callback,
    .discovery_state_changed_cb = discovery_state_changed_callback,
    .pin_request_cb = pin_request_callback,
    .ssp_request_cb = ssp_request_callback,
    .bond_state_changed_cb = bond_state_changed_callback,
    .address_consolidate_cb = address_consolidate_callback,
    .acl_state_changed_cb = acl_state_changed_callback,
    .thread_evt_cb = callback_thread_event,
    .dut_mode_recv_cb = dut_mode_recv_callback,
    .le_test_mode_cb = le_test_mode_callback,
    .energy_info_cb = energy_info_callback,
    .link_quality_report_cb = link_quality_report_callback,
    .generate_local_oob_data_cb = generate_local_oob_data_callback,
    .switch_buffer_size_cb = switch_buffer_size_callback,
    .switch_codec_cb = switch_codec_callback,
};

}  // namespace

class BtifCoreTest : public ::testing::Test {
 protected:
  void SetUp() override {
    callback_map_.clear();
    set_hal_cbacks(&callbacks);

    auto promise = std::promise<void>();
    auto future = promise.get_future();
    callback_map_["callback_thread_event"] = [&promise]() {
      promise.set_value();
    };
    btif_init_bluetooth();
    ASSERT_EQ(std::future_status::ready, future.wait_for(timeout_time));
    callback_map_.erase("callback_thread_event");
  }

  void TearDown() override {
    auto promise = std::promise<void>();
    auto future = promise.get_future();
    callback_map_["callback_thread_event"] = [&promise]() {
      promise.set_value();
    };
    btif_cleanup_bluetooth();
    ASSERT_EQ(std::future_status::ready, future.wait_for(timeout_time));
    callback_map_.erase("callback_thread_event");
  }
};

std::promise<int> promise0;
void callback0(int val) { promise0.set_value(val); }

TEST_F(BtifCoreTest, test_post_on_bt_simple0) {
  const int val = 123;
  promise0 = std::promise<int>();
  std::future<int> future0 = promise0.get_future();
  post_on_bt_jni([=]() { callback0(val); });
  ASSERT_EQ(std::future_status::ready, future0.wait_for(timeout_time));
  ASSERT_EQ(val, future0.get());
}

TEST_F(BtifCoreTest, test_post_on_bt_jni_simple1) {
  std::promise<void> promise;
  std::future<void> future = promise.get_future();
  post_on_bt_jni([=, &promise]() { promise.set_value(); });
  ASSERT_EQ(std::future_status::ready, future.wait_for(timeout_time));
}

TEST_F(BtifCoreTest, test_post_on_bt_jni_simple2) {
  std::promise<void> promise;
  std::future<void> future = promise.get_future();
  BtJniClosure closure = [&promise]() { promise.set_value(); };
  post_on_bt_jni(closure);
  ASSERT_EQ(std::future_status::ready, future.wait_for(timeout_time));
}

TEST_F(BtifCoreTest, test_post_on_bt_jni_simple3) {
  const int val = 456;
  std::promise<int> promise;
  auto future = promise.get_future();
  BtJniClosure closure = [&promise, val]() { promise.set_value(val); };
  post_on_bt_jni(closure);
  ASSERT_EQ(std::future_status::ready, future.wait_for(timeout_time));
  ASSERT_EQ(val, future.get());
}
