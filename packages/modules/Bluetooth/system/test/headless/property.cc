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

#include "test/headless/property.h"

#include <map>

#include "include/hardware/bluetooth.h"

using namespace bluetooth::test;

namespace {

// Map the bluetooth property names to the corresponding headless property
// structure
std::map<::bt_property_type_t, std::function<headless::bt_property_t*(
                                   const uint8_t* data, const size_t len)>>
    property_map = {
        {BT_PROPERTY_BDNAME,
         [](const uint8_t* data, const size_t len) -> headless::bt_property_t* {
           return new headless::property::name_t(data, len);
         }},
        {BT_PROPERTY_UUIDS,
         [](const uint8_t* data, const size_t len) -> headless::bt_property_t* {
           return new headless::property::uuid_t(data, len);
         }},
        {BT_PROPERTY_CLASS_OF_DEVICE,
         [](const uint8_t* data, const size_t len) -> headless::bt_property_t* {
           return new headless::property::class_of_device_t(data, len);
         }},
        {BT_PROPERTY_TYPE_OF_DEVICE,
         [](const uint8_t* data, const size_t len) -> headless::bt_property_t* {
           return new headless::property::type_of_device_t(data, len);
         }},
};

}  // namespace

// Caller owns the memory
headless::bt_property_t* bluetooth::test::headless::property_factory(
    const ::bt_property_t& bt_property) {
  ASSERT_LOG(bt_property.len > -1, "Property count is less than zero");
  ASSERT_LOG(bt_property.val != nullptr, "Property data value is null");

  const uint8_t* data = static_cast<uint8_t*>(bt_property.val);
  const size_t size = static_cast<size_t>(bt_property.len);

  const auto factory = property_map.find(bt_property.type);
  if (factory != property_map.end()) {
    return factory->second(data, size);
  }
  return new headless::property::void_t(data, size, bt_property.type);
}
