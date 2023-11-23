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

#pragma once

#include <algorithm>
#include <cstdint>
#include <deque>
#include <memory>
#include <string>

#include "include/hardware/bluetooth.h"
#include "test/headless/log.h"
#include "types/bluetooth/uuid.h"

namespace bluetooth {
namespace test {
namespace headless {

struct bt_property_t {
  int Type() const { return type; }

  virtual std::string ToString() const = 0;

 protected:
  bt_property_t(const uint8_t* data, const size_t len) {
    this->len = len;
    this->data = std::make_unique<uint8_t[]>(len);
    std::copy(data, data + len, this->data.get());
  }
  virtual ~bt_property_t() = default;

  std::unique_ptr<uint8_t[]> data;
  size_t len;
  int type;
};

namespace property {

struct void_t : public bt_property_t {
  void_t(const uint8_t* data, const size_t len, int type)
      : bt_property_t(data, len) {
    this->type = type;
  }

 public:
  virtual std::string ToString() const override {
    return base::StringPrintf("void property type:%d", type);
  }
};

struct uuid_t : public bt_property_t {
 public:
  uuid_t(const uint8_t* data, const size_t len) : bt_property_t(data, len) {}

  std::deque<bluetooth::Uuid> get_uuids() const {
    std::deque<bluetooth::Uuid> uuids;
    bluetooth::Uuid* p_uuid = reinterpret_cast<bluetooth::Uuid*>(data.get());
    for (size_t i = 0; i < num_uuid(); i++, p_uuid++) {
      bluetooth::Uuid uuid = bluetooth::Uuid::From128BitBE(
          reinterpret_cast<const uint8_t*>(p_uuid));
      uuids.push_back(uuid);
    }
    return uuids;
  }

  virtual std::string ToString() const override {
    return base::StringPrintf("Number of uuids:%zu", get_uuids().size());
  }

 private:
  size_t num_uuid() const { return len / sizeof(bluetooth::Uuid); }
};

struct name_t : public bt_property_t {
  name_t(const uint8_t* data, const size_t len) : bt_property_t(data, len) {
    type = BT_PROPERTY_BDNAME;
  }

  std::string get_name() const {
    char* s = reinterpret_cast<char*>(data.get());
    return std::string(s);
  }

  virtual std::string ToString() const override {
    return base::StringPrintf("Name:%s", get_name().c_str());
  }
};

struct class_of_device_t : public bt_property_t {
  class_of_device_t(const uint8_t* data, const size_t len)
      : bt_property_t(data, len) {
    type = BT_PROPERTY_CLASS_OF_DEVICE;
  }

  uint32_t get_class_of_device() const {
    uint32_t* cod = reinterpret_cast<uint32_t*>(data.get());
    return *cod;
  }

  virtual std::string ToString() const override {
    return base::StringPrintf("cod:0x%04x", get_class_of_device());
  }
};

struct type_of_device_t : public bt_property_t {
  type_of_device_t(const uint8_t* data, const size_t len)
      : bt_property_t(data, len) {
    type = BT_PROPERTY_TYPE_OF_DEVICE;
  }

  uint32_t get_type_of_device() const {
    uint32_t* tod = reinterpret_cast<uint32_t*>(data.get());
    return *tod;
  }

  virtual std::string ToString() const override {
    return base::StringPrintf("tod:0x%04x", get_type_of_device());
  }
};

}  // namespace property

bluetooth::test::headless::bt_property_t* property_factory(
    const ::bt_property_t& bt_property);

template <typename T>
T* get_property_type(bluetooth::test::headless::bt_property_t* bt_property) {
  return static_cast<T*>(bt_property);
}

}  // namespace headless
}  // namespace test
}  // namespace bluetooth
