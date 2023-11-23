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
 *   Functions generated:17
 *
 *  mockcify.pl ver 0.2.1
 */

#include <cstdint>
#include <functional>
#include <map>
#include <string>

#include "test/common/mock_functions.h"

// Original included files, if any
// NOTE: Since this is a mock file with mock definitions some number of
//       include files may not be required.  The include-what-you-use
//       still applies, but crafting proper inclusion is out of scope
//       for this effort.  This compilation unit may compile as-is, or
//       may need attention to prune the inclusion set.
#include "include/hardware/bluetooth.h"
#include "types/raw_address.h"

// Mocked compile conditionals, if any
#ifndef UNUSED_ATTR
#define UNUSED_ATTR
#endif

namespace test {
namespace mock {
namespace btif_bluetooth {

// Shared state between mocked functions and tests
// Name: is_atv_device
// Params:
// Returns: bool
struct is_atv_device {
  std::function<bool()> body{[]() { return false; }};
  bool operator()() { return body(); };
};
extern struct is_atv_device is_atv_device;
// Name: is_common_criteria_mode
// Params:
// Returns: bool
struct is_common_criteria_mode {
  std::function<bool()> body{[]() { return false; }};
  bool operator()() { return body(); };
};
extern struct is_common_criteria_mode is_common_criteria_mode;
// Name: is_restricted_mode
// Params:
// Returns: bool
struct is_restricted_mode {
  std::function<bool()> body{[]() { return false; }};
  bool operator()() { return body(); };
};
extern struct is_restricted_mode is_restricted_mode;
// Name: get_common_criteria_config_compare_result
// Params:
// Returns: int
struct get_common_criteria_config_compare_result {
  std::function<int()> body{[]() { return 0; }};
  int operator()() { return body(); };
};
extern struct get_common_criteria_config_compare_result
    get_common_criteria_config_compare_result;
// Name: get_remote_device_properties
// Params: RawAddress* remote_addr
// Returns: int
struct get_remote_device_properties {
  std::function<int(RawAddress* remote_addr)> body{
      [](RawAddress* remote_addr) { return 0; }};
  int operator()(RawAddress* remote_addr) { return body(remote_addr); };
};
extern struct get_remote_device_properties get_remote_device_properties;
// Name: get_remote_device_property
// Params: RawAddress* remote_addr, bt_property_type_t type
// Returns: int
struct get_remote_device_property {
  std::function<int(RawAddress* remote_addr, bt_property_type_t type)> body{
      [](RawAddress* remote_addr, bt_property_type_t type) { return 0; }};
  int operator()(RawAddress* remote_addr, bt_property_type_t type) {
    return body(remote_addr, type);
  };
};
extern struct get_remote_device_property get_remote_device_property;
// Name: get_remote_services
// Params: RawAddress* remote_addr
// Returns: int
struct get_remote_services {
  std::function<int(RawAddress* remote_addr)> body{
      [](RawAddress* remote_addr) { return 0; }};
  int operator()(RawAddress* remote_addr) { return body(remote_addr); };
};
extern struct get_remote_services get_remote_services;
// Name: set_remote_device_property
// Params: RawAddress* remote_addr, const bt_property_t* property
// Returns: int
struct set_remote_device_property {
  std::function<int(RawAddress* remote_addr, const bt_property_t* property)>
      body{[](RawAddress* remote_addr, const bt_property_t* property) {
        return 0;
      }};
  int operator()(RawAddress* remote_addr, const bt_property_t* property) {
    return body(remote_addr, property);
  };
};
extern struct set_remote_device_property set_remote_device_property;
// Name: set_hal_cbacks
// Params: bt_callbacks_t* callbacks
// Returns: void
struct set_hal_cbacks {
  std::function<void(bt_callbacks_t* callbacks)> body{
      [](bt_callbacks_t* callbacks) { ; }};
  void operator()(bt_callbacks_t* callbacks) { body(callbacks); };
};
extern struct set_hal_cbacks set_hal_cbacks;

}  // namespace btif_bluetooth
}  // namespace mock
}  // namespace test

// END mockcify generation
