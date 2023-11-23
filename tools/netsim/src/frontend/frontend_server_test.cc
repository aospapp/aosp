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

// Unit tests for the FrontendServer class.

#include "frontend/frontend_server.cc"

#include <string>

#include "common.pb.h"
#include "controller/device.h"
#include "controller/scene_controller.h"
#include "frontend.pb.h"
#include "grpcpp/server_context.h"
#include "grpcpp/support/status.h"
#include "gtest/gtest.h"
#include "model.pb.h"

namespace netsim {
namespace controller {

class FrontendServerTest : public ::testing::Test {
 protected:
  // A sample ServerContext for tests.
  grpc::ServerContext context_;
  // An instance of the service under test.
  FrontendServer service_;

  std::shared_ptr<Device> match(const std::string &name) {
    return SceneController::Singleton().MatchDevice(name);
  }
};

TEST_F(FrontendServerTest, VerifyVersion) {
  frontend::VersionResponse response;
  grpc::Status status = service_.GetVersion(&context_, {}, &response);
  ASSERT_TRUE(status.ok());
  EXPECT_FALSE(response.version().empty());
}

#ifdef NETSIM_ANDROID_EMULATOR
TEST_F(FrontendServerTest, PatchDevicePosition) {
  auto name = "test-device-name-for-set-position";
  auto [device_id, _1, _2] =
      scene_controller::AddChip("guid-fs-1", name, common::ChipKind::BLUETOOTH);

  google::protobuf::Empty response;
  frontend::PatchDeviceRequest request;
  request.mutable_device()->set_name(name);
  request.mutable_device()->mutable_position()->set_x(1.1);
  request.mutable_device()->mutable_position()->set_y(2.2);
  request.mutable_device()->mutable_position()->set_z(3.3);
  grpc::Status status = service_.PatchDevice(&context_, &request, &response);
  ASSERT_TRUE(status.ok());
  const auto &scene = netsim::controller::SceneController::Singleton().Get();
  // NOTE: Singleton won't be reset between tests. Need to either deprecate
  // Singleton pattern or provide reset().
  bool found = false;
  for (const auto &device : scene.devices()) {
    if (device.name() == request.device().name()) {
      EXPECT_EQ(device.position().x(), request.device().position().x());
      EXPECT_EQ(device.position().y(), request.device().position().y());
      EXPECT_EQ(device.position().z(), request.device().position().z());
      found = true;
      break;
    }
  }
  EXPECT_TRUE(found);
}

TEST_F(FrontendServerTest, PatchDevice) {
  auto name = "name-for-update";
  auto [device_id, chip_id, _] =
      scene_controller::AddChip("guid-fs-2", name, common::ChipKind::BLUETOOTH);

  model::Device model;
  model.set_name(name);
  model.set_visible(false);
  auto chip = model.mutable_chips()->Add();
  chip->mutable_bt()->mutable_classic()->set_state(model::State::OFF);
  chip->set_id(chip_id);
  chip->set_kind(common::ChipKind::BLUETOOTH);

  frontend::PatchDeviceRequest request;
  google::protobuf::Empty response;
  request.mutable_device()->CopyFrom(model);

  grpc::Status status = service_.PatchDevice(&context_, &request, &response);
  ASSERT_TRUE(status.ok());
  auto optional_device = match(name);
  ASSERT_TRUE(optional_device != nullptr);
  model = optional_device->Get();
  ASSERT_TRUE(model.name() == name);
  ASSERT_TRUE(model.chips().size() == 1);
  ASSERT_TRUE(model.chips().Get(0).chip_case() == model::Chip::ChipCase::kBt);
  ASSERT_TRUE(model.chips().Get(0).bt().classic().state() == model::State::OFF);
}
#endif

TEST_F(FrontendServerTest, PatchDeviceNotFound) {
  google::protobuf::Empty response;
  frontend::PatchDeviceRequest request;
  request.mutable_device()->set_name("non-existing-device-name");
  grpc::Status status = service_.PatchDevice(&context_, &request, &response);
  ASSERT_FALSE(status.ok());
  EXPECT_EQ(status.error_code(), grpc::StatusCode::NOT_FOUND);
}

}  // namespace controller
}  // namespace netsim
