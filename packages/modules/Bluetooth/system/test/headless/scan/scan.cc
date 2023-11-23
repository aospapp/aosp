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

#define LOG_TAG "bt_headless_scan"

#include "test/headless/scan/scan.h"

#include <future>

#include "base/logging.h"  // LOG() stdout and android log
#include "btif/include/btif_api.h"
#include "osi/include/log.h"  // android log only
#include "stack/include/sdp_api.h"
#include "test/headless/bt_property.h"
#include "test/headless/get_options.h"
#include "test/headless/headless.h"
#include "test/headless/interface.h"
#include "test/headless/log.h"
#include "test/headless/stopwatch.h"
#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

using namespace bluetooth::test::headless;
using namespace std::chrono_literals;

namespace scan {
std::promise<acl_state_changed_params_t> acl_state_changed_promise;
std::promise<remote_device_properties_params_t>
    remote_device_properties_promise;

std::mutex mutex;
std::condition_variable cv;

std::queue<acl_state_changed_params_t> acl_state_changed_params_queue;
std::queue<discovery_state_changed_params_t>
    discovery_state_changed_params_queue;
;

// Callback from another thread
void callback_interface(callback_data_t* data) {
  if (data->Name() == "discovery_state_changed") {
    LOG(INFO) << "Received discovery_state_changed";
    auto params = static_cast<discovery_state_changed_params_t*>(data);
    discovery_state_changed_params_queue.push(*params);
    LOG_CONSOLE("Received discovery state change callback %s",
                params->ToString().c_str());
    cv.notify_all();
    return;
  }
  LOG(ERROR) << "Received unexpected interface callback";
}

}  // namespace scan

namespace {

int start_scan([[maybe_unused]] unsigned int num_loops) {
  LOG(INFO) << "Started Device Scan";

  Stopwatch stop_watch("Inquiry_timeout");
  auto check_point = messenger::inquiry::get_check_point();

  ASSERT(bluetoothInterface.start_discovery() == BT_STATUS_SUCCESS);
  LOG_CONSOLE("Started inquiry - device discovery");

  while (stop_watch.LapMs() < 10000) {
    if (messenger::inquiry::await_inquiry_result(1s, check_point, 1)) {
      auto callback_queue = messenger::inquiry::collect_from(check_point);
      while (!callback_queue.empty()) {
        remote_device_properties_params_t params = callback_queue.front();
        callback_queue.pop_front();
        LOG_CONSOLE("Received remote inquiry :%s", STR(params));
        bt_property_t* prop = params.properties;
        for (int i = 0; i < params.num_properties; ++i, prop++) {
          process_property(params.bd_addr, prop);
        }
      }
    }
  }

  LOG_CONSOLE("Stopped inquiry - device discovery");
  return 0;
}

}  // namespace

extern uint8_t btu_trace_level;

int bluetooth::test::headless::Scan::Run() {
  if (options_.loop_ < 1) {
    LOG_CONSOLE("This test requires at least a single loop");
    options_.Usage();
    return -1;
  }
  return RunOnHeadlessStack<int>([this]() {
    btif_trace_level = BT_TRACE_LEVEL_DEBUG;
    appl_trace_level = BT_TRACE_LEVEL_DEBUG;
    btu_trace_level = BT_TRACE_LEVEL_DEBUG;
    return start_scan(options_.loop_);
  });
}
