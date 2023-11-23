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

#define LOG_TAG "bt_headless_messenger"

#include "test/headless/messenger.h"

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
#include "test/headless/sdp/sdp.h"
#include "test/headless/stopwatch.h"
#include "test/headless/timeout.h"
#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

using namespace bluetooth::test::headless;
using namespace std::chrono_literals;

template <typename T>
struct messenger_t {
  std::mutex mutex;
  std::condition_variable cv;
  std::deque<T> params_queue;
  void Notify() { cv.notify_all(); }
};

namespace {

namespace acl {
messenger_t<acl_state_changed_params_t> acl_state_changed_;

void acl_state_changed_cb(callback_data_t* data) {
  auto params = static_cast<acl_state_changed_params_t*>(data);

  acl_state_changed_.params_queue.push_back(*params);
  acl_state_changed_.Notify();
}

bool await_event(const bt_acl_state_t& state, const Timeout& timeout) {
  std::unique_lock<std::mutex> lk(acl_state_changed_.mutex);
  if (!acl_state_changed_.params_queue.empty()) {
    auto params = acl_state_changed_.params_queue.back();
    if (params.state == state) return true;
  }
  return acl_state_changed_.cv.wait_for(lk, timeout, [=] {
    return !acl_state_changed_.params_queue.empty() &&
           acl_state_changed_.params_queue.back().state == state;
  });
}

}  // namespace acl

namespace sdp {
messenger_t<remote_device_properties_params_t> remote_device_properties_;

void remote_device_properties_cb(callback_data_t* data) {
  auto params = static_cast<remote_device_properties_params_t*>(data);
  // TODO Save timestamp into queue
  remote_device_properties_.params_queue.push_back(*params);
  remote_device_properties_.Notify();
}

bool await_event(const Timeout& timeout, const CheckPoint& check_point,
                 const size_t count) {
  std::unique_lock<std::mutex> lk(remote_device_properties_.mutex);
  if (!remote_device_properties_.params_queue.empty()) {
    if (remote_device_properties_.params_queue.size() - check_point > count)
      return true;
  }
  return remote_device_properties_.cv.wait_for(lk, timeout, [=] {
    return !remote_device_properties_.params_queue.empty() &&
           remote_device_properties_.params_queue.size() - check_point >= count;
  });
}

}  // namespace sdp

namespace inquiry {}  // namespace inquiry

}  // namespace

namespace bluetooth::test::headless {

namespace messenger {
namespace acl {

bool await_connected(const Timeout& timeout) {
  return ::acl::await_event(BT_ACL_STATE_CONNECTED, timeout);
}

bool await_disconnected(const Timeout& timeout) {
  return ::acl::await_event(BT_ACL_STATE_DISCONNECTED, timeout);
}

}  // namespace acl

namespace sdp {

bool await_service_discovery(const Timeout& timeout,
                             const CheckPoint& check_point,
                             const size_t count) {
  return ::sdp::await_event(timeout, check_point, count);
}

CheckPoint get_check_point() {
  std::unique_lock<std::mutex> lk(::sdp::remote_device_properties_.mutex);
  return ::sdp::remote_device_properties_.params_queue.size();
}

std::deque<remote_device_properties_params_t> collect_from(
    CheckPoint& check_point) {
  std::unique_lock<std::mutex> lk(::sdp::remote_device_properties_.mutex);
  ASSERT_LOG(
      !(check_point > ::sdp::remote_device_properties_.params_queue.size()),
      "Checkpoint larger than size");
  std::deque<remote_device_properties_params_t> deque;
  for (size_t size = check_point;
       size < ::sdp::remote_device_properties_.params_queue.size(); ++size) {
    deque.push_back(::sdp::remote_device_properties_.params_queue[size]);
  }
  return deque;
}

}  // namespace sdp

namespace inquiry {

CheckPoint get_check_point() {
  std::unique_lock<std::mutex> lk(::sdp::remote_device_properties_.mutex);
  return ::sdp::remote_device_properties_.params_queue.size();
}

bool await_inquiry_result(const Timeout& timeout, const CheckPoint& check_point,
                          const size_t count) {
  return ::sdp::await_event(timeout, check_point, count);
}

std::deque<remote_device_properties_params_t> collect_from(
    CheckPoint& check_point) {
  std::unique_lock<std::mutex> lk(::sdp::remote_device_properties_.mutex);
  ASSERT_LOG(
      !(check_point > ::sdp::remote_device_properties_.params_queue.size()),
      "Checkpoint larger than size");
  std::deque<remote_device_properties_params_t> deque;
  for (size_t size = check_point;
       size < ::sdp::remote_device_properties_.params_queue.size(); ++size) {
    deque.push_back(::sdp::remote_device_properties_.params_queue[size]);
  }
  check_point +=
      (::sdp::remote_device_properties_.params_queue.size() - check_point);
  return deque;
}

}  // namespace inquiry
}  // namespace messenger

void start_messenger() {
  headless_add_callback("acl_state_changed", ::acl::acl_state_changed_cb);
  headless_add_callback("remote_device_properties",
                        ::sdp::remote_device_properties_cb);

  LOG_CONSOLE("Started messenger service");
}

void stop_messenger() {
  headless_remove_callback("acl_state_changed", ::acl::acl_state_changed_cb);
  headless_remove_callback("remote_device_properties",
                           ::sdp::remote_device_properties_cb);

  LOG_CONSOLE("Stopped messenger service");
}

}  // namespace bluetooth::test::headless
