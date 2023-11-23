

#pragma once

#include <cstddef>
#include <deque>

#include "test/headless/interface.h"
#include "test/headless/timeout.h"

namespace bluetooth::test::headless {

using CheckPoint = size_t;

void start_messenger();
void stop_messenger();

namespace messenger {
namespace acl {

bool await_connected(const Timeout& timeout);
bool await_disconnected(const Timeout& timeout);

}  // namespace acl

namespace sdp {

CheckPoint get_check_point();
bool await_service_discovery(const Timeout& timeout,
                             const CheckPoint& check_point, const size_t count);
std::deque<remote_device_properties_params_t> collect_from(
    CheckPoint& check_point);

}  // namespace sdp

namespace inquiry {

CheckPoint get_check_point();
bool await_inquiry_result(const Timeout& timeout, const CheckPoint& check_point,
                          const size_t count);
std::deque<remote_device_properties_params_t> collect_from(
    CheckPoint& check_point);

}  // namespace inquiry

}  // namespace messenger

}  // namespace bluetooth::test::headless
