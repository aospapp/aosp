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

/*
 * Generated mock file from original source file
 *   Functions generated:6
 */

#include <cstddef>
#include <cstdint>
#include <map>
#include <string>

#include "device/include/interop.h"
#include "test/common/mock_functions.h"
#include "types/raw_address.h"

#ifndef UNUSED_ATTR
#define UNUSED_ATTR
#endif

bool interop_match_addr(const interop_feature_t feature,
                        const RawAddress* addr) {
  inc_func_call_count(__func__);
  return false;
}
bool interop_match_name(const interop_feature_t feature, const char* name) {
  inc_func_call_count(__func__);
  return false;
}
void interop_database_add(uint16_t feature, const RawAddress* addr,
                          size_t length) {
  inc_func_call_count(__func__);
}
void interop_database_clear() { inc_func_call_count(__func__); }

bool interop_match_addr_or_name(const interop_feature_t feature,
                                const RawAddress* addr,
                                bt_status_t (*get_remote_device_property)(
                                    const RawAddress*, bt_property_t*)) {
  inc_func_call_count(__func__);
  return false;
}

bool interop_match_manufacturer(const interop_feature_t feature,
                                uint16_t manufacturer) {
  inc_func_call_count(__func__);
  return false;
}

bool interop_match_vendor_product_ids(const interop_feature_t feature,
                                      uint16_t vendor_id, uint16_t product_id) {
  inc_func_call_count(__func__);
  return false;
}

bool interop_database_match_version(const interop_feature_t feature,
                                    uint16_t version) {
  inc_func_call_count(__func__);
  return false;
}
bool interop_match_addr_get_max_lat(const interop_feature_t feature,
                                    const RawAddress* addr, uint16_t* max_lat) {
  inc_func_call_count(__func__);
  return false;
}

bool interop_get_allowlisted_media_players_list(list_t* p_bl_devices) {
  inc_func_call_count(__func__);
  return false;
}

int interop_feature_name_to_feature_id(const char* feature_name) {
  inc_func_call_count(__func__);
  return false;
}

void interop_database_add_addr(const uint16_t feature, const RawAddress* addr,
                               size_t length) {
  inc_func_call_count(__func__);
}
