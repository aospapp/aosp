/*
 * Copyright 2020 The Android Open Source Project
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

/*
 * Generated mock file from original source file
 *   Functions generated:27
 */

#include <base/functional/bind.h>

#include <cstdint>
#include <string>

#include "bta/include/bta_api.h"
#include "btif/include/btif_common.h"
#include "include/hardware/bluetooth.h"
#include "test/common/jni_thread.h"
#include "test/common/mock_functions.h"
#include "types/raw_address.h"

#ifndef UNUSED_ATTR
#define UNUSED_ATTR
#endif

bool is_on_jni_thread() {
  inc_func_call_count(__func__);
  return false;
}
bt_property_t* property_deep_copy(const bt_property_t* prop) {
  inc_func_call_count(__func__);
  return nullptr;
}
bt_status_t btif_cleanup_bluetooth() {
  inc_func_call_count(__func__);
  return BT_STATUS_SUCCESS;
}
bt_status_t btif_init_bluetooth() {
  inc_func_call_count(__func__);
  return BT_STATUS_SUCCESS;
}
bt_status_t btif_set_dynamic_audio_buffer_size(int codec, int size) {
  inc_func_call_count(__func__);
  return BT_STATUS_SUCCESS;
}
bt_status_t btif_transfer_context(tBTIF_CBACK* p_cback, uint16_t event,
                                  char* p_params, int param_len,
                                  tBTIF_COPY_CBACK* p_copy_cback) {
  inc_func_call_count(__func__);
  return BT_STATUS_SUCCESS;
}
bt_status_t do_in_jni_thread(base::OnceClosure task) {
  inc_func_call_count(__func__);
  do_in_jni_thread_task_queue.push(std::move(task));
  return BT_STATUS_SUCCESS;
}
bt_status_t do_in_jni_thread(const base::Location& from_here,
                             base::OnceClosure task) {
  inc_func_call_count(__func__);
  do_in_jni_thread_task_queue.push(std::move(task));
  return BT_STATUS_SUCCESS;
}
btbase::AbstractMessageLoop* get_jni_message_loop() {
  inc_func_call_count(__func__);
  return nullptr;
}
int btif_is_enabled(void) {
  inc_func_call_count(__func__);
  return 0;
}
tBTA_SERVICE_MASK btif_get_enabled_services_mask(void) {
  inc_func_call_count(__func__);
  return 0;
}
void DynamicAudiobufferSizeCompleteCallback(tBTM_VSC_CMPL* p_vsc_cmpl_params) {
  inc_func_call_count(__func__);
}
void btif_adapter_properties_evt(bt_status_t status, uint32_t num_props,
                                 bt_property_t* p_props) {
  inc_func_call_count(__func__);
}
void btif_disable_service(tBTA_SERVICE_ID service_id) {
  inc_func_call_count(__func__);
}
void btif_enable_bluetooth_evt() { inc_func_call_count(__func__); }
void btif_enable_service(tBTA_SERVICE_ID service_id) {
  inc_func_call_count(__func__);
}
void btif_get_adapter_properties(void) { inc_func_call_count(__func__); }
void btif_get_adapter_property(bt_property_type_t type) {
  inc_func_call_count(__func__);
}
void btif_get_remote_device_properties(RawAddress remote_addr) {
  inc_func_call_count(__func__);
}
void btif_get_remote_device_property(RawAddress remote_addr,
                                     bt_property_type_t type) {
  inc_func_call_count(__func__);
}
void btif_init_ok() { inc_func_call_count(__func__); }
void btif_remote_properties_evt(bt_status_t status, RawAddress* remote_addr,
                                uint32_t num_props, bt_property_t* p_props) {
  inc_func_call_count(__func__);
}
void btif_set_adapter_property(bt_property_t* property) {
  inc_func_call_count(__func__);
}
void btif_set_remote_device_property(RawAddress* remote_addr,
                                     bt_property_t* property) {
  inc_func_call_count(__func__);
}
