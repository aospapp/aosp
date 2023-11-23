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

#include <google/protobuf/util/json_util.h>

#include <cstdint>
#include <string>

#include "gtest/gtest.h"
#include "startup.pb.h"

namespace netsim {
namespace testing {
namespace {

// Test json format of the proto
TEST(StartupTest, MessageToJsonStringTest) {
  netsim::startup::StartupInfo info;

  auto device = info.add_devices();
  device->set_name("emulator-5554");

  auto chip = device->add_chips();
  chip->set_kind(common::ChipKind::BLUETOOTH);
  chip->set_fd_in(1);
  chip->set_fd_out(2);

  std::string json_string;
  google::protobuf::util::JsonPrintOptions options;
  MessageToJsonString(info, &json_string, options);

  EXPECT_EQ(
      json_string,
      std::string(
          R"({"devices":[{"name":"emulator-5554","chips":[{"kind":"BLUETOOTH","fdIn":1,"fdOut":2}]}]})"));
}

// Test reading json format of the proto
TEST(StartupTest, JsonStringToMessageTest) {
  auto r =
      R"({devices:[
           {name:"0.0.0.0:6520",chips:[{kind:"BLUETOOTH", fd_in:1,fd_out:2}]},
           {name: "0.0.0.0:6521", chips:[{kind: "BLUETOOTH", fd_in:2, fd_out:3}]}]})";

  google::protobuf::util::JsonParseOptions options;
  netsim::startup::StartupInfo info;
  JsonStringToMessage(r, &info, options);

  ASSERT_EQ(info.devices().size(), 2);
  auto &device = info.devices().Get(0);
  ASSERT_EQ(device.name(), "0.0.0.0:6520");
  ASSERT_EQ(device.chips().size(), 1);
  auto &chip = device.chips().Get(0);
  ASSERT_EQ(chip.kind(), common::ChipKind::BLUETOOTH);
  ASSERT_EQ(chip.fd_in(), 1);
}

}  // namespace
}  // namespace testing
}  // namespace netsim
