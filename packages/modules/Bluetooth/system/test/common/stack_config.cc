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

#include "internal_include/stack_config.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <cstdarg>
#include <cstring>

const std::string kSmpOptions("mock smp options");
bool get_trace_config_enabled(void) { return false; }
bool get_pts_avrcp_test(void) { return false; }
bool get_pts_secure_only_mode(void) { return false; }
bool get_pts_conn_updates_disabled(void) { return false; }
bool get_pts_crosskey_sdp_disable(void) { return false; }
const std::string* get_pts_smp_options(void) { return &kSmpOptions; }
int get_pts_smp_failure_case(void) { return 123; }
struct config_t;
config_t* get_all(void) { return nullptr; }
struct packet_fragmenter_t;
const packet_fragmenter_t* packet_fragmenter_get_interface() { return nullptr; }

stack_config_t mock_stack_config{
    .get_trace_config_enabled = get_trace_config_enabled,
    .get_pts_avrcp_test = get_pts_avrcp_test,
    .get_pts_secure_only_mode = get_pts_secure_only_mode,
    .get_pts_conn_updates_disabled = get_pts_conn_updates_disabled,
    .get_pts_crosskey_sdp_disable = get_pts_crosskey_sdp_disable,
    .get_pts_smp_options = get_pts_smp_options,
    .get_pts_smp_failure_case = get_pts_smp_failure_case,
    .get_all = get_all,
};

const stack_config_t* stack_config_get_interface(void) {
  return &mock_stack_config;
}
