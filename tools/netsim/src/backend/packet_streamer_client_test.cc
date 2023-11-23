// Copyright 2022 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
#include "backend/packet_streamer_client.h"

#include "aemu/base/process/Process.h"
#include "gtest/gtest.h"

namespace netsim::testing {
namespace {

class PacketStreamerClientTest : public ::testing::Test {
 private:
  void TearDown() override {
    // Kill netsimd.
    for (auto &process : android::base::Process::fromName("netsimd")) {
      process->terminate();
    }
  }
};

TEST_F(PacketStreamerClientTest, CreateChannelTest) {
  auto channel = packet::CreateChannel();
  ASSERT_TRUE(channel != nullptr);

  auto channel2 = packet::CreateChannel();
  ASSERT_TRUE(channel2 != nullptr);
  ASSERT_EQ(channel, channel2);  // Should reuse the channel.
}

}  // namespace
}  // namespace netsim::testing
