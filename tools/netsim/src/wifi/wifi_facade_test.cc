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

// Unit tests for the WiFi facade.

#include "wifi/wifi_facade.h"

#include "gtest/gtest.h"

namespace netsim::wifi::facade {

class WiFiFacadeTest : public ::testing::Test {
  void TearDown() { netsim::wifi::facade::Remove(SIMULATION_DEVICE); }

 protected:
  const int SIMULATION_DEVICE = 123;
};

TEST_F(WiFiFacadeTest, AddAndGetTest) {
  auto facade_id = Add(SIMULATION_DEVICE);

  auto radio = Get(facade_id);
  EXPECT_EQ(model::State::ON, radio.state());
  EXPECT_EQ(0, radio.tx_count());
  EXPECT_EQ(0, radio.rx_count());
}

TEST_F(WiFiFacadeTest, RemoveTest) {
  auto facade_id = Add(SIMULATION_DEVICE);

  Remove(facade_id);

  auto radio = Get(facade_id);
  EXPECT_EQ(model::State::UNKNOWN, radio.state());
}

TEST_F(WiFiFacadeTest, PatchTest) {
  auto facade_id = Add(SIMULATION_DEVICE);

  model::Chip::Radio request;
  request.set_state(model::State::OFF);
  Patch(facade_id, request);

  auto radio = Get(facade_id);
  EXPECT_EQ(model::State::OFF, radio.state());
}

TEST_F(WiFiFacadeTest, ResetTest) {
  auto facade_id = Add(SIMULATION_DEVICE);

  Reset(facade_id);

  auto radio = Get(facade_id);
  EXPECT_EQ(model::State::ON, radio.state());
  EXPECT_EQ(0, radio.tx_count());
  EXPECT_EQ(0, radio.rx_count());
}

}  // namespace netsim::wifi::facade