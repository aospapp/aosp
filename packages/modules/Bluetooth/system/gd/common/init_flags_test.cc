/******************************************************************************
 *
 *  Copyright 2019 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#include "common/init_flags.h"

#include <gtest/gtest.h>

#include "os/log_tags.h"

using bluetooth::common::InitFlags;

TEST(InitFlagsTest, test_enable_btm_flush_discovery_queue_on_search_cancel) {
  const char* input[] = {"INIT_btm_dm_flush_discovery_queue_on_search_cancel=true", nullptr};
  InitFlags::Load(input);
  ASSERT_TRUE(InitFlags::IsBtmDmFlushDiscoveryQueueOnSearchCancel());
}

TEST(InitFlagsTest, test_leaudio_targeted_announcement_reconnection_mode) {
  const char* input[] = {"INIT_leaudio_targeted_announcement_reconnection_mode=true", nullptr};
  InitFlags::Load(input);
  ASSERT_TRUE(InitFlags::IsTargetedAnnouncementReconnectionMode());
}

TEST(InitFlagsTest, test_enable_debug_logging_for_all) {
  const char* input[] = {"INIT_default_log_level=5", nullptr};
  InitFlags::Load(input);
  ASSERT_EQ(InitFlags::GetLogLevelForTag("foo"), LOG_TAG_DEBUG);
  ASSERT_EQ(InitFlags::GetLogLevelForTag("bar"), LOG_TAG_DEBUG);
  ASSERT_EQ(InitFlags::GetDefaultLogLevel(), LOG_TAG_DEBUG);
}

TEST(InitFlagsTest, test_enable_debug_logging_for_tags) {
  const char* input[] = {"INIT_logging_debug_enabled_for_tags=foo,bar,hello", nullptr};
  InitFlags::Load(input);
  ASSERT_EQ(InitFlags::GetLogLevelForTag("foo"), LOG_TAG_VERBOSE);
  ASSERT_EQ(InitFlags::GetLogLevelForTag("bar"), LOG_TAG_VERBOSE);
  ASSERT_EQ(InitFlags::GetLogLevelForTag("hello"), LOG_TAG_VERBOSE);
  ASSERT_EQ(InitFlags::GetLogLevelForTag("Foo"), LOG_TAG_INFO);
  ASSERT_EQ(InitFlags::GetDefaultLogLevel(), LOG_TAG_INFO);
}

TEST(InitFlagsTest, test_disable_debug_logging_for_tags) {
  const char* input[] = {
      "INIT_logging_debug_disabled_for_tags=foo,bar,hello",
      "INIT_default_log_level_str=LOG_DEBUG",
      nullptr};
  InitFlags::Load(input);
  ASSERT_EQ(InitFlags::GetLogLevelForTag("foo"), LOG_TAG_INFO);
  ASSERT_EQ(InitFlags::GetLogLevelForTag("bar"), LOG_TAG_INFO);
  ASSERT_EQ(InitFlags::GetLogLevelForTag("hello"), LOG_TAG_INFO);
  ASSERT_EQ(InitFlags::GetLogLevelForTag("Foo"), LOG_TAG_DEBUG);
  ASSERT_EQ(InitFlags::GetDefaultLogLevel(), LOG_TAG_DEBUG);
}

TEST(InitFlagsTest, test_debug_logging_multiple_flags) {
  const char* input[] = {
      "INIT_logging_debug_enabled_for_tags=foo,hello",
      "INIT_logging_debug_disabled_for_tags=foo,bar",
      "INIT_default_log_level_str=LOG_WARN",
      nullptr};
  InitFlags::Load(input);
  ASSERT_EQ(InitFlags::GetLogLevelForTag("foo"), LOG_TAG_INFO);
  ASSERT_EQ(InitFlags::GetLogLevelForTag("bar"), LOG_TAG_INFO);
  ASSERT_EQ(InitFlags::GetLogLevelForTag("hello"), LOG_TAG_VERBOSE);
  ASSERT_EQ(InitFlags::GetLogLevelForTag("Foo"), LOG_TAG_WARN);
  ASSERT_EQ(InitFlags::GetDefaultLogLevel(), LOG_TAG_WARN);
}

TEST(InitFlagsTest, test_enable_snoop_logger_socket) {
  const char* input[] = {"INIT_gd_hal_snoop_logger_socket=true", nullptr};
  InitFlags::Load(input);
  ASSERT_TRUE(InitFlags::IsSnoopLoggerSocketEnabled());
}

TEST(InitFlagsTest, test_device_iot_config_logging_is_enabled) {
  const char* input[] = {"INIT_device_iot_config_logging=true", nullptr};
  InitFlags::Load(input);
  ASSERT_TRUE(InitFlags::IsDeviceIotConfigLoggingEnabled());
}

TEST(InitFlagsTest, test_enable_snoop_logger_filtering) {
  const char* input[] = {"INIT_gd_hal_snoop_logger_filtering=true", nullptr};
  InitFlags::Load(input);
  ASSERT_TRUE(InitFlags::IsSnoopLoggerFilteringEnabled());
}

TEST(InitFlagsTest, test_enable_bluetooth_quality_report_callback) {
  const char* input[] = {"INIT_bluetooth_quality_report_callback=true", nullptr};
  InitFlags::Load(input);
  ASSERT_TRUE(InitFlags::IsBluetoothQualityReportCallbackEnabled());
}
