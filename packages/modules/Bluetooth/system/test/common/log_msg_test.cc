/*
 * Copyright 2023 The Android Open Source Project
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

#include "test/common/log_msg.h"

#include <gtest/gtest.h>

#include <cstdint>
#include <string>

#include "internal_include/bt_trace.h"

namespace {
uint32_t kDefaultTraceSetMask = 0x5a5a5a5a;
}

class CommonLogMsgTest : public ::testing::Test {
 protected:
  void SetUp() override {}
  void TearDown() override {}
};

class CommonLogMsgOutputTest : public CommonLogMsgTest {
 protected:
  void SetUp() override {
    last_ = {};
    bluetooth::testing::common::log_msg = [this](uint32_t mask,
                                                 const char* data) {
      if (mask) {
        printf("This is printed :%s", data);
        last_.mask = mask;
        last_.data_len = strlen(data);
      }
    };
  }
  void TearDown() override { bluetooth::testing::common::log_msg = {}; }

  const size_t max_buffer_size_ =
      bluetooth::testing::common::get_common_log_msg_size();

  struct {
    uint32_t mask;
    size_t data_len;
  } last_;
};

TEST_F(CommonLogMsgTest, default_no_output) {
  LogMsg(kDefaultTraceSetMask, "This is a test");
}

TEST_F(CommonLogMsgOutputTest, simple_with_output) {
  LogMsg(kDefaultTraceSetMask, "This will be printed\n");
  ASSERT_EQ(kDefaultTraceSetMask, last_.mask);
  ASSERT_EQ(strlen("This will be printed\n"), last_.data_len);
}

TEST_F(CommonLogMsgOutputTest, simple_with_no_output) {
  LogMsg(0U, "This will not be printed\n");
  ASSERT_EQ(0U, last_.mask);
  ASSERT_EQ(0UL, last_.data_len);
}

TEST_F(CommonLogMsgOutputTest, max_string) {
  auto long_string = std::string(max_buffer_size_ - sizeof('\0'), 'x');
  LogMsg(kDefaultTraceSetMask, long_string.c_str());
  ASSERT_EQ(max_buffer_size_ - sizeof('\0'), last_.data_len);
}

TEST_F(CommonLogMsgOutputTest, max_string_plus_string_terminator) {
  auto long_string = std::string(max_buffer_size_, 'x');
  LogMsg(kDefaultTraceSetMask, long_string.c_str());
  ASSERT_EQ(max_buffer_size_ - sizeof('\0'), last_.data_len);
}

TEST_F(CommonLogMsgOutputTest, too_large_string) {
  auto long_string = std::string(4096UL, 'x');
  LogMsg(kDefaultTraceSetMask, long_string.c_str());
  ASSERT_EQ(max_buffer_size_ - sizeof('\0'), last_.data_len);
}
