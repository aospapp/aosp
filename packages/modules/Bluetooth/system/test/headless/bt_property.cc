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

#define LOG_TAG "bt_headless_property"

#include "test/headless/bt_property.h"

#include "base/logging.h"  // LOG() stdout and android log
#include "btif/include/btif_api.h"
#include "osi/include/log.h"  // android log only
#include "stack/include/sdp_api.h"
#include "test/headless/get_options.h"
#include "test/headless/headless.h"
#include "test/headless/interface.h"
#include "test/headless/log.h"
#include "test/headless/sdp/sdp.h"
#include "test/headless/stopwatch.h"
#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

using namespace bluetooth::test::headless;
using namespace std::chrono_literals;

namespace bluetooth {
namespace test {
namespace headless {

void process_property(const RawAddress& bd_addr, const bt_property_t* prop) {
  LOG_INFO("%s bt_property type:%d len:%d val:%p",
           ADDRESS_TO_LOGGABLE_CSTR(bd_addr), prop->type,
           prop->len, prop->val);
  switch (prop->type) {
    case BT_PROPERTY_BDNAME: {
      ASSERT(prop->len >= 0);
      std::string name(static_cast<const char*>(prop->val),
                       static_cast<size_t>(prop->len));
      LOG_CONSOLE("BT_PROPERTY_BDNAME  NAME:%s", name.c_str());
    } break;
    case BT_PROPERTY_BDADDR:
      LOG_CONSOLE("BT_PROPERTY_BDADDR");
      break;
    case BT_PROPERTY_UUIDS: {
      const size_t remainder = prop->len % sizeof(bluetooth::Uuid);
      ASSERT(remainder == 0);
      bluetooth::Uuid* uuid = reinterpret_cast<bluetooth::Uuid*>(prop->val);
      for (int len = prop->len; len > 0; len -= sizeof(*uuid)) {
        LOG_CONSOLE("BT_PROPERTY_UUIDS  UUID:%s", uuid->ToString().c_str());
        uuid++;
      }
    } break;
    case BT_PROPERTY_CLASS_OF_DEVICE: {
      ASSERT(prop->len == 4);
      uint32_t cod = *(reinterpret_cast<uint32_t*>(prop->val));
      LOG_CONSOLE("BT_PROPERTY_CLASS_OF_DEVICE  0x%04x", cod);
    } break;
    case BT_PROPERTY_TYPE_OF_DEVICE: {
      ASSERT(prop->len == 4);
      uint32_t devtype = *(reinterpret_cast<uint32_t*>(prop->val));
      LOG_CONSOLE("BT_PROPERTY_TYPE_OF_DEVICE  0x%04x", devtype);
    } break;
    case BT_PROPERTY_SERVICE_RECORD:
      LOG_CONSOLE("BT_PROPERTY_SERVICE_RECORD");
      break;
    case BT_PROPERTY_ADAPTER_SCAN_MODE:
      LOG_CONSOLE("BT_PROPERTY_ADAPTER_SCAN_MODE");
      break;
    case BT_PROPERTY_ADAPTER_BONDED_DEVICES:
      LOG_CONSOLE("BT_PROPERTY_ADAPTER_BONDED_DEVICES");
      break;
    case BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT:
      LOG_CONSOLE("BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT");
      break;
    case BT_PROPERTY_REMOTE_FRIENDLY_NAME:
      LOG_CONSOLE("BT_PROPERTY_REMOTE_FRIENDLY_NAME");
      break;
    case BT_PROPERTY_REMOTE_RSSI:
      LOG_CONSOLE("BT_PROPERTY_REMOTE_RSSI");
      break;
    case BT_PROPERTY_REMOTE_VERSION_INFO:
      LOG_CONSOLE("BT_PROPERTY_REMOTE_VERSION_INFO");
      break;
    case BT_PROPERTY_LOCAL_LE_FEATURES:
      LOG_CONSOLE("BT_PROPERTY_LOCAL_LE_FEATURES");
      break;
    case BT_PROPERTY_LOCAL_IO_CAPS:
      LOG_CONSOLE("BT_PROPERTY_LOCAL_IO_CAPS");
      break;
    case BT_PROPERTY_DYNAMIC_AUDIO_BUFFER:
      LOG_CONSOLE("BT_PROPERTY_DYNAMIC_AUDIO_BUFFER");
      break;
    case BT_PROPERTY_REMOTE_IS_COORDINATED_SET_MEMBER:
      LOG_CONSOLE("BT_PROPERTY_REMOTE_IS_COORDINATED_SET_MEMBER");
      break;
    case BT_PROPERTY_REMOTE_DEVICE_TIMESTAMP:
      LOG_CONSOLE("BT_PROPERTY_REMOTE_IS_COORDINATED_SET_MEMBER");
      break;
    default: {
      LOG_CONSOLE("Unable to find BT property bd_addr:%s type:%d ptr:%p",
                  ADDRESS_TO_LOGGABLE_CSTR(bd_addr), prop->type, prop);
      const uint8_t* p = reinterpret_cast<const uint8_t*>(prop);
      for (size_t i = 0; i < sizeof(bt_property_t); i++, p++) {
        LOG_CONSOLE("  %p:0x%02x", p, *p);
      }
    } break;
  }
}

}  // namespace headless
}  // namespace test
}  // namespace bluetooth
