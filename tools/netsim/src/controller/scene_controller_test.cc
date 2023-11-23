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

#include "controller/scene_controller.h"

#include <memory>

#include "common.pb.h"
#include "controller/controller.h"
#include "controller/device.h"
#include "gtest/gtest.h"
#include "hci/bluetooth_facade.h"
#include "model.pb.h"

namespace netsim {
namespace controller {

class SceneControllerTest : public ::testing::Test {
 protected:
  void SetUp() override { netsim::hci::facade::Start(); }
  void TearDown() { netsim::hci::facade::Stop(); }
  std::shared_ptr<Device> match(const std::string &name) {
    return SceneController::Singleton().MatchDevice(name);
  }
};

TEST_F(SceneControllerTest, GetTest) {
  const auto size = SceneController::Singleton().Get().devices_size();
  EXPECT_EQ(size, 0);
}

#ifdef NETSIM_ANDROID_EMULATOR
TEST_F(SceneControllerTest, AddChipTest) {
  auto guid = "guid-SceneControllerTest-AddChipTest";
  auto device_name = "device_name-SceneControllerTest-AddChipTest";
  auto [device_id, chip_id1, _1] =
      scene_controller::AddChip(guid, device_name, common::ChipKind::BLUETOOTH);
  auto [device_id2, chip_id2, _2] =
      scene_controller::AddChip(guid, device_name, common::ChipKind::WIFI);

  EXPECT_EQ(device_id, device_id2);
  EXPECT_EQ(SceneController::Singleton().Get().devices_size(), 1);
  auto device = match(device_name);
  EXPECT_TRUE(device != nullptr);
  auto device_proto = device->Get();
  EXPECT_EQ(device_proto.id(), device_id);
  EXPECT_EQ(device_proto.name(), device_name);
  EXPECT_TRUE(device_proto.visible());
  EXPECT_TRUE(device_proto.has_position());
  EXPECT_TRUE(device_proto.has_orientation());

  EXPECT_EQ(device_proto.chips_size(), 2);
  for (const auto &chip : device_proto.chips()) {
    EXPECT_TRUE(chip.id() == chip_id1 || chip.id() == chip_id2);
    if (chip.id() == chip_id1) {
      EXPECT_TRUE(chip.has_bt());
      EXPECT_EQ(chip.id(), chip_id1);

    } else if (chip.id() == chip_id2) {
      EXPECT_TRUE(chip.has_wifi());
      EXPECT_EQ(chip.id(), chip_id2);
    } else {
      FAIL() << "Unknown chip id: " << chip.id() << ". Should be in ["
             << chip_id1 << "," << chip_id2 << "].";
    }
  }
}

TEST_F(SceneControllerTest, MatchDeviceTest) {
  auto guid1 = "guid-1-SceneControllerTest-MatchDeviceTest";
  auto device_name1 = "device_name-1-SceneControllerTest-MatchDeviceTest";
  auto guid2 = "guid-2-SceneControllerTest-MatchDeviceTest";
  auto device_name2 = "device_name-2-SceneControllerTest-MatchDeviceTest";
  auto guid3 = "guid-3-SceneControllerTest-MatchDeviceTest";
  auto device_name3 = "device_name-3-SceneControllerTest-MatchDeviceTest";
  scene_controller::AddChip(guid1, device_name1, common::ChipKind::BLUETOOTH);
  scene_controller::AddChip(guid2, device_name2, common::ChipKind::BLUETOOTH);
  scene_controller::AddChip(guid3, device_name3, common::ChipKind::BLUETOOTH);

  //  exact matches with name
  ASSERT_TRUE(match(device_name1));
  ASSERT_TRUE(match(device_name2));
  ASSERT_TRUE(match(device_name3));

  //  matches with unique substring of name
  ASSERT_TRUE(match("1-SceneControllerTest-MatchDeviceTest"));
  ASSERT_TRUE(match("2-SceneControllerTest-MatchDeviceTest"));
  ASSERT_TRUE(match("3-SceneControllerTest-MatchDeviceTest"));

  //  ambiguous matches and no match
  ASSERT_TRUE(match("MatchDeviceTest") == nullptr);
  ASSERT_TRUE(match("non-existing-name") == nullptr);
}

TEST_F(SceneControllerTest, PatchDeviceTest) {
  auto guid = "guid-SceneControllerTest-PatchDeviceTest";
  auto device_name = "device_name-SceneControllerTest-PatchDeviceTest";
  auto [device_id, chip_id, _] =
      scene_controller::AddChip(guid, device_name, common::ChipKind::BLUETOOTH);
  model::Device model;
  model.set_name(device_name);
  model.set_visible(false);
  model.mutable_position()->set_x(10.0);
  model.mutable_position()->set_y(20.0);
  model.mutable_position()->set_z(30.0);
  model.mutable_orientation()->set_pitch(1.0);
  model.mutable_orientation()->set_roll(2.0);
  model.mutable_orientation()->set_yaw(3.0);

  auto status = SceneController::Singleton().PatchDevice(model);
  EXPECT_TRUE(status);
  auto device = match(device_name);
  model = device->Get();
  EXPECT_EQ(model.visible(), false);
  EXPECT_EQ(model.position().x(), 10.0);
  EXPECT_EQ(model.position().y(), 20.0);
  EXPECT_EQ(model.position().z(), 30.0);
  EXPECT_EQ(model.orientation().pitch(), 1.0);
  EXPECT_EQ(model.orientation().roll(), 2.0);
  EXPECT_EQ(model.orientation().yaw(), 3.0);
}

TEST_F(SceneControllerTest, ResetTest) {
  auto guid = "guid-SceneControllerTest-ResetTest";
  auto device_name = "device_name-SceneControllerTest-ResetTest";
  auto [device_id, chip_id, _] =
      scene_controller::AddChip(guid, device_name, common::ChipKind::BLUETOOTH);
  model::Device model;
  model.set_name(device_name);
  model.set_visible(false);
  model.mutable_position()->set_x(10.0);
  model.mutable_position()->set_y(20.0);
  model.mutable_position()->set_z(30.0);
  model.mutable_orientation()->set_pitch(1.0);
  model.mutable_orientation()->set_roll(2.0);
  model.mutable_orientation()->set_yaw(3.0);

  auto status = SceneController::Singleton().PatchDevice(model);
  EXPECT_TRUE(status);
  auto device = match(device_name);
  model = device->Get();
  EXPECT_EQ(model.visible(), false);
  EXPECT_EQ(model.position().x(), 10.0);
  EXPECT_EQ(model.orientation().pitch(), 1.0);

  SceneController::Singleton().Reset();

  device = match(device_name);
  model = device->Get();

  EXPECT_EQ(model.visible(), true);
  EXPECT_EQ(model.position().x(), 0.0);
  EXPECT_EQ(model.position().y(), 0.0);
  EXPECT_EQ(model.position().z(), 0.0);
  EXPECT_EQ(model.orientation().pitch(), 0.0);
  EXPECT_EQ(model.orientation().roll(), 0.0);
  EXPECT_EQ(model.orientation().yaw(), 0.0);
}
#endif

}  // namespace controller
}  // namespace netsim
