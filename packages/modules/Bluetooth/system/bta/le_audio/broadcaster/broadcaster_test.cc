/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <chrono>

#include "bta/include/bta_le_audio_api.h"
#include "bta/include/bta_le_audio_broadcaster_api.h"
#include "bta/le_audio/broadcaster/mock_state_machine.h"
#include "bta/le_audio/mock_iso_manager.h"
#include "bta/test/common/mock_controller.h"
#include "device/include/controller.h"
#include "stack/include/btm_iso_api.h"

using testing::_;
using testing::AtLeast;
using testing::DoAll;
using testing::Mock;
using testing::NotNull;
using testing::Return;
using testing::ReturnRef;
using testing::SaveArg;
using testing::Test;

using namespace bluetooth::le_audio;

std::map<std::string, int> mock_function_count_map;

// Disables most likely false-positives from base::SplitString()
extern "C" const char* __asan_default_options() {
  return "detect_container_overflow=0";
}

static base::Callback<void(BT_OCTET8)> generator_cb;

void btsnd_hcic_ble_rand(base::Callback<void(BT_OCTET8)> cb) {
  generator_cb = cb;
}

std::atomic<int> num_async_tasks;
bluetooth::common::MessageLoopThread message_loop_thread("test message loop");
bluetooth::common::MessageLoopThread* get_main_thread() {
  return &message_loop_thread;
}
void invoke_switch_buffer_size_cb(bool is_low_latency_buffer_size) {}

bt_status_t do_in_main_thread(const base::Location& from_here,
                              base::OnceClosure task) {
  // Wrap the task with task counter so we could later know if there are
  // any callbacks scheduled and we should wait before performing some actions
  if (!message_loop_thread.DoInThread(
          from_here,
          base::BindOnce(
              [](base::OnceClosure task, std::atomic<int>& num_async_tasks) {
                std::move(task).Run();
                num_async_tasks--;
              },
              std::move(task), std::ref(num_async_tasks)))) {
    LOG(ERROR) << __func__ << ": failed from " << from_here.ToString();
    return BT_STATUS_FAIL;
  }
  num_async_tasks++;
  return BT_STATUS_SUCCESS;
}

static base::MessageLoop* message_loop_;
base::MessageLoop* get_main_message_loop() { return message_loop_; }

static void init_message_loop_thread() {
  num_async_tasks = 0;
  message_loop_thread.StartUp();
  if (!message_loop_thread.IsRunning()) {
    FAIL() << "unable to create message loop thread.";
  }

  if (!message_loop_thread.EnableRealTimeScheduling())
    LOG(ERROR) << "Unable to set real time scheduling";

  message_loop_ = message_loop_thread.message_loop();
  if (message_loop_ == nullptr) FAIL() << "unable to get message loop.";
}

static void cleanup_message_loop_thread() {
  message_loop_ = nullptr;
  message_loop_thread.ShutDown();
}

namespace le_audio {
namespace broadcaster {
namespace {
static constexpr LeAudioBroadcaster::AudioProfile default_profile =
    LeAudioBroadcaster::AudioProfile::SONIFICATION;
static constexpr BroadcastCode default_code = {
    0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10};
static const std::vector<uint8_t> default_metadata = {0x03, 0x02, 0x01, 0x00};

class MockLeAudioBroadcasterCallbacks
    : public bluetooth::le_audio::LeAudioBroadcasterCallbacks {
 public:
  MOCK_METHOD((void), OnBroadcastCreated, (uint32_t broadcast_id, bool success),
              (override));
  MOCK_METHOD((void), OnBroadcastDestroyed, (uint32_t broadcast_id),
              (override));
  MOCK_METHOD((void), OnBroadcastStateChanged,
              (uint32_t broadcast_id,
               bluetooth::le_audio::BroadcastState state),
              (override));
  MOCK_METHOD((void), OnBroadcastMetadataChanged,
              (uint32_t broadcast_id,
               const BroadcastMetadata& broadcast_metadata),
              (override));
};

class MockLeAudioBroadcastClientAudioSource
    : public LeAudioBroadcastClientAudioSource {
 public:
  MOCK_METHOD((bool), Start,
              (const LeAudioCodecConfiguration& codecConfiguration,
               LeAudioClientAudioSinkReceiver* audioReceiver));
  MOCK_METHOD((void), Stop, ());
  MOCK_METHOD((const void*), Acquire, ());
  MOCK_METHOD((void), Release, (const void*));
  MOCK_METHOD((void), ConfirmStreamingRequest, ());
  MOCK_METHOD((void), CancelStreamingRequest, ());
  MOCK_METHOD((void), UpdateRemoteDelay, (uint16_t delay));
  MOCK_METHOD((void), DebugDump, (int fd));
  MOCK_METHOD((void), UpdateAudioConfigToHal,
              (const ::le_audio::offload_config&));
};

class BroadcasterTest : public Test {
 protected:
  void SetUp() override {
    init_message_loop_thread();

    mock_function_count_map.clear();
    ON_CALL(controller_interface_, SupportsBleIsochronousBroadcaster)
        .WillByDefault(Return(true));

    controller::SetMockControllerInterface(&controller_interface_);
    iso_manager_ = bluetooth::hci::IsoManager::GetInstance();
    ASSERT_NE(iso_manager_, nullptr);
    iso_manager_->Start();

    mock_audio_source_ = new MockLeAudioBroadcastClientAudioSource();

    ON_CALL(*mock_audio_source_, Start).WillByDefault(Return(true));

    is_audio_hal_acquired = false;
    ON_CALL(*mock_audio_source_, Acquire).WillByDefault([this]() -> void* {
      if (!is_audio_hal_acquired) {
        is_audio_hal_acquired = true;
        return mock_audio_source_;
      }

      return nullptr;
    });

    ON_CALL(*mock_audio_source_, Release)
        .WillByDefault([this](const void* inst) -> void {
          if (is_audio_hal_acquired) {
            is_audio_hal_acquired = false;
          }
        });

    ASSERT_FALSE(LeAudioBroadcaster::IsLeAudioBroadcasterRunning());
    LeAudioBroadcaster::InitializeAudioClient(mock_audio_source_);
    LeAudioBroadcaster::Initialize(&mock_broadcaster_callbacks_,
                                   base::Bind([]() -> bool { return true; }));

    /* Simulate random generator */
    uint8_t random[] = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
    generator_cb.Run(random);
  }

  void TearDown() override {
    // Message loop cleanup should wait for all the 'till now' scheduled calls
    // so it should be called right at the very begginning of teardown.
    cleanup_message_loop_thread();

    // This is required since Stop() and Cleanup() may trigger some callbacks.
    Mock::VerifyAndClearExpectations(&mock_broadcaster_callbacks_);

    LeAudioBroadcaster::Stop();
    LeAudioBroadcaster::Cleanup();
    ASSERT_FALSE(LeAudioBroadcaster::IsLeAudioBroadcasterRunning());

    iso_manager_->Stop();

    controller::SetMockControllerInterface(nullptr);
  }

  uint32_t InstantiateBroadcast(
      LeAudioBroadcaster::AudioProfile profile = default_profile,
      std::vector<uint8_t> metadata = default_metadata,
      BroadcastCode code = default_code) {
    uint32_t broadcast_id = LeAudioBroadcaster::kInstanceIdUndefined;
    EXPECT_CALL(mock_broadcaster_callbacks_, OnBroadcastCreated(_, true))
        .WillOnce(SaveArg<0>(&broadcast_id));
    LeAudioBroadcaster::Get()->CreateAudioBroadcast(metadata, profile, code);

    return broadcast_id;
  }

 protected:
  MockLeAudioBroadcastClientAudioSource* mock_audio_source_;
  MockLeAudioBroadcasterCallbacks mock_broadcaster_callbacks_;
  controller::MockControllerInterface controller_interface_;
  bluetooth::hci::IsoManager* iso_manager_;
  bool is_audio_hal_acquired;
};

TEST_F(BroadcasterTest, Initialize) {
  ASSERT_NE(LeAudioBroadcaster::Get(), nullptr);
  ASSERT_TRUE(LeAudioBroadcaster::IsLeAudioBroadcasterRunning());
}

TEST_F(BroadcasterTest, GetNumRetransmit) {
  LeAudioBroadcaster::Get()->SetNumRetransmit(8);
  ASSERT_EQ(LeAudioBroadcaster::Get()->GetNumRetransmit(), 8);
  LeAudioBroadcaster::Get()->SetNumRetransmit(12);
  ASSERT_EQ(LeAudioBroadcaster::Get()->GetNumRetransmit(), 12);
}

TEST_F(BroadcasterTest, GetStreamingPhy) {
  LeAudioBroadcaster::Get()->SetStreamingPhy(1);
  ASSERT_EQ(LeAudioBroadcaster::Get()->GetStreamingPhy(), 1);
  LeAudioBroadcaster::Get()->SetStreamingPhy(2);
  ASSERT_EQ(LeAudioBroadcaster::Get()->GetStreamingPhy(), 2);
}

TEST_F(BroadcasterTest, CreateAudioBroadcast) {
  auto broadcast_id = InstantiateBroadcast();
  ASSERT_NE(broadcast_id, LeAudioBroadcaster::kInstanceIdUndefined);
  ASSERT_EQ(broadcast_id,
            MockBroadcastStateMachine::GetLastInstance()->GetBroadcastId());

  auto& instance_config = MockBroadcastStateMachine::GetLastInstance()->cfg;
  ASSERT_EQ(instance_config.broadcast_code, default_code);
  for (auto& subgroup : instance_config.announcement.subgroup_configs) {
    ASSERT_EQ(types::LeAudioLtvMap(subgroup.metadata).RawPacket(),
              default_metadata);
  }
  // Note: There shall be a separate test to verify audio parameters
}

TEST_F(BroadcasterTest, SuspendAudioBroadcast) {
  auto broadcast_id = InstantiateBroadcast();
  LeAudioBroadcaster::Get()->StartAudioBroadcast(broadcast_id);

  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::CONFIGURED))
      .Times(1);

  EXPECT_CALL(*mock_audio_source_, Stop).Times(AtLeast(1));
  LeAudioBroadcaster::Get()->SuspendAudioBroadcast(broadcast_id);
}

TEST_F(BroadcasterTest, StartAudioBroadcast) {
  auto broadcast_id = InstantiateBroadcast();
  LeAudioBroadcaster::Get()->StopAudioBroadcast(broadcast_id);

  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STREAMING))
      .Times(1);

  LeAudioClientAudioSinkReceiver* audio_receiver;
  EXPECT_CALL(*mock_audio_source_, Start)
      .WillOnce(DoAll(SaveArg<1>(&audio_receiver), Return(true)));

  LeAudioBroadcaster::Get()->StartAudioBroadcast(broadcast_id);
  ASSERT_NE(audio_receiver, nullptr);

  // NOTICE: This is really an implementation specific part, we fake the BIG
  //         config as the mocked state machine does not even call the
  //         IsoManager to prepare one (and that's good since IsoManager is also
  //         a mocked one).
  BigConfig big_cfg;
  big_cfg.big_id =
      MockBroadcastStateMachine::GetLastInstance()->GetAdvertisingSid();
  big_cfg.connection_handles = {0x10, 0x12};
  big_cfg.max_pdu = 128;
  MockBroadcastStateMachine::GetLastInstance()->SetExpectedBigConfig(big_cfg);

  // Inject the audio and verify call on the Iso manager side.
  EXPECT_CALL(*MockIsoManager::GetInstance(), SendIsoData).Times(1);
  std::vector<uint8_t> sample_data(320, 0);
  audio_receiver->OnAudioDataReady(sample_data);
}

TEST_F(BroadcasterTest, StartAudioBroadcastMedia) {
  auto broadcast_id =
      InstantiateBroadcast(LeAudioBroadcaster::AudioProfile::MEDIA);
  LeAudioBroadcaster::Get()->StopAudioBroadcast(broadcast_id);

  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STREAMING))
      .Times(1);

  LeAudioClientAudioSinkReceiver* audio_receiver;
  EXPECT_CALL(*mock_audio_source_, Start)
      .WillOnce(DoAll(SaveArg<1>(&audio_receiver), Return(true)));

  LeAudioBroadcaster::Get()->StartAudioBroadcast(broadcast_id);
  ASSERT_NE(audio_receiver, nullptr);

  // NOTICE: This is really an implementation specific part, we fake the BIG
  //         config as the mocked state machine does not even call the
  //         IsoManager to prepare one (and that's good since IsoManager is also
  //         a mocked one).
  BigConfig big_cfg;
  big_cfg.big_id =
      MockBroadcastStateMachine::GetLastInstance()->GetAdvertisingSid();
  big_cfg.connection_handles = {0x10, 0x12};
  big_cfg.max_pdu = 128;
  MockBroadcastStateMachine::GetLastInstance()->SetExpectedBigConfig(big_cfg);

  // Inject the audio and verify call on the Iso manager side.
  EXPECT_CALL(*MockIsoManager::GetInstance(), SendIsoData).Times(2);
  std::vector<uint8_t> sample_data(1920, 0);
  audio_receiver->OnAudioDataReady(sample_data);
}

TEST_F(BroadcasterTest, StopAudioBroadcast) {
  auto broadcast_id = InstantiateBroadcast();
  LeAudioBroadcaster::Get()->StartAudioBroadcast(broadcast_id);

  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STOPPED))
      .Times(1);

  EXPECT_CALL(*mock_audio_source_, Stop).Times(AtLeast(1));
  LeAudioBroadcaster::Get()->StopAudioBroadcast(broadcast_id);
}

TEST_F(BroadcasterTest, DestroyAudioBroadcast) {
  auto broadcast_id = InstantiateBroadcast();

  EXPECT_CALL(mock_broadcaster_callbacks_, OnBroadcastDestroyed(broadcast_id))
      .Times(1);
  LeAudioBroadcaster::Get()->DestroyAudioBroadcast(broadcast_id);

  // Expect not being able to interact with this Broadcast
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, _))
      .Times(0);

  EXPECT_CALL(*mock_audio_source_, Stop).Times(0);
  LeAudioBroadcaster::Get()->StopAudioBroadcast(broadcast_id);

  EXPECT_CALL(*mock_audio_source_, Start).Times(0);
  LeAudioBroadcaster::Get()->StartAudioBroadcast(broadcast_id);

  EXPECT_CALL(*mock_audio_source_, Stop).Times(0);
  LeAudioBroadcaster::Get()->SuspendAudioBroadcast(broadcast_id);
}

TEST_F(BroadcasterTest, GetBroadcastAllStates) {
  auto broadcast_id = InstantiateBroadcast();
  auto broadcast_id2 = InstantiateBroadcast();
  ASSERT_NE(broadcast_id, LeAudioBroadcaster::kInstanceIdUndefined);
  ASSERT_NE(broadcast_id2, LeAudioBroadcaster::kInstanceIdUndefined);
  ASSERT_NE(broadcast_id, broadcast_id2);

  /* In the current implementation state machine switches to the correct state
   * on itself, therefore here when we use mocked state machine this is not
   * being verified.
   */
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, _))
      .Times(1);
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id2, _))
      .Times(1);

  LeAudioBroadcaster::Get()->GetAllBroadcastStates();
}

TEST_F(BroadcasterTest, UpdateMetadata) {
  auto broadcast_id = InstantiateBroadcast();

  EXPECT_CALL(*MockBroadcastStateMachine::GetLastInstance(),
              UpdateBroadcastAnnouncement)
      .Times(1);
  LeAudioBroadcaster::Get()->UpdateMetadata(
      broadcast_id, std::vector<uint8_t>({0x02, 0x01, 0x02}));
}

static BasicAudioAnnouncementData prepareAnnouncement(
    const BroadcastCodecWrapper& codec_config,
    std::map<uint8_t, std::vector<uint8_t>> metadata) {
  BasicAudioAnnouncementData announcement;

  announcement.presentation_delay = 0x004E20;
  auto const& codec_id = codec_config.GetLeAudioCodecId();

  announcement.subgroup_configs = {{
      .codec_config =
          {
              .codec_id = codec_id.coding_format,
              .vendor_company_id = codec_id.vendor_company_id,
              .vendor_codec_id = codec_id.vendor_codec_id,
              .codec_specific_params =
                  codec_config.GetSubgroupCodecSpecData().Values(),
          },
      .metadata = std::move(metadata),
      .bis_configs = {},
  }};

  for (uint8_t i = 0; i < codec_config.GetNumChannels(); ++i) {
    announcement.subgroup_configs[0].bis_configs.push_back(
        {.codec_specific_params =
             codec_config.GetBisCodecSpecData(i + 1).Values(),
         .bis_index = static_cast<uint8_t>(i + 1)});
  }

  return announcement;
}

TEST_F(BroadcasterTest, GetMetadata) {
  auto broadcast_id = InstantiateBroadcast();
  bluetooth::le_audio::BroadcastMetadata metadata;
  // bluetooth::le_audio::BasicAudioAnnouncementData announcement;

  static const uint8_t test_adv_sid = 0x14;
  std::optional<bluetooth::le_audio::BroadcastCode> test_broadcast_code =
      bluetooth::le_audio::BroadcastCode({1, 2, 3, 4, 5, 6});

  auto sm = MockBroadcastStateMachine::GetLastInstance();

  std::map<uint8_t, std::vector<uint8_t>> meta = {};
  BroadcastCodecWrapper codec_config(
      {.coding_format = le_audio::types::kLeAudioCodingFormatLC3,
       .vendor_company_id = le_audio::types::kLeAudioVendorCompanyIdUndefined,
       .vendor_codec_id = le_audio::types::kLeAudioVendorCodecIdUndefined},
      {.num_channels = LeAudioCodecConfiguration::kChannelNumberMono,
       .sample_rate = LeAudioCodecConfiguration::kSampleRate16000,
       .bits_per_sample = LeAudioCodecConfiguration::kBitsPerSample16,
       .data_interval_us = LeAudioCodecConfiguration::kInterval10000Us},
      32000, 40);
  auto announcement = prepareAnnouncement(codec_config, meta);

  ON_CALL(*sm, GetAdvertisingSid()).WillByDefault(Return(test_adv_sid));
  ON_CALL(*sm, GetBroadcastCode()).WillByDefault(Return(test_broadcast_code));
  ON_CALL(*sm, GetBroadcastAnnouncement())
      .WillByDefault(ReturnRef(announcement));

  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastMetadataChanged(broadcast_id, _))
      .Times(1)
      .WillOnce(SaveArg<1>(&metadata));
  LeAudioBroadcaster::Get()->GetBroadcastMetadata(broadcast_id);

  ASSERT_NE(LeAudioBroadcaster::kInstanceIdUndefined, metadata.broadcast_id);
  ASSERT_EQ(sm->GetBroadcastId(), metadata.broadcast_id);
  ASSERT_EQ(sm->GetBroadcastCode(), metadata.broadcast_code);
  ASSERT_EQ(sm->GetBroadcastAnnouncement(), metadata.basic_audio_announcement);
  ASSERT_EQ(sm->GetPaInterval(), metadata.pa_interval);
  ASSERT_EQ(sm->GetOwnAddress(), metadata.addr);
  ASSERT_EQ(sm->GetOwnAddressType(), metadata.addr_type);
  ASSERT_EQ(sm->GetAdvertisingSid(), metadata.adv_sid);
}

TEST_F(BroadcasterTest, SetNumRetransmit) {
  auto broadcast_id = InstantiateBroadcast();
  LeAudioBroadcaster::Get()->SetNumRetransmit(9);
  ASSERT_EQ(MockBroadcastStateMachine::GetLastInstance()->cb->GetNumRetransmit(
                broadcast_id),
            9);
  ASSERT_EQ(LeAudioBroadcaster::Get()->GetNumRetransmit(), 9);
}

TEST_F(BroadcasterTest, SetStreamingPhy) {
  LeAudioBroadcaster::Get()->SetStreamingPhy(2);
  // From now on new streams should be using Phy = 2.
  InstantiateBroadcast();
  ASSERT_EQ(MockBroadcastStateMachine::GetLastInstance()->cfg.streaming_phy, 2);

  // From now on new streams should be using Phy = 1.
  LeAudioBroadcaster::Get()->SetStreamingPhy(1);
  InstantiateBroadcast();
  ASSERT_EQ(MockBroadcastStateMachine::GetLastInstance()->cfg.streaming_phy, 1);
  ASSERT_EQ(LeAudioBroadcaster::Get()->GetStreamingPhy(), 1);
}

TEST_F(BroadcasterTest, StreamParamsSonification) {
  uint8_t expected_channels = 1u;

  InstantiateBroadcast(LeAudioBroadcaster::AudioProfile::SONIFICATION);
  auto config = MockBroadcastStateMachine::GetLastInstance()->cfg;

  // Check audio configuration
  ASSERT_EQ(config.codec_wrapper.GetNumChannels(), expected_channels);
  // Matches number of bises in the announcement
  ASSERT_EQ(config.announcement.subgroup_configs[0].bis_configs.size(),
            expected_channels);
  // Note: Num of bises at IsoManager level is verified by state machine tests
}

TEST_F(BroadcasterTest, StreamParamsMedia) {
  uint8_t expected_channels = 2u;

  InstantiateBroadcast(LeAudioBroadcaster::AudioProfile::MEDIA);
  auto config = MockBroadcastStateMachine::GetLastInstance()->cfg;

  // Check audio configuration
  ASSERT_EQ(config.codec_wrapper.GetNumChannels(), expected_channels);
  // Matches number of bises in the announcement
  ASSERT_EQ(config.announcement.subgroup_configs[0].bis_configs.size(),
            expected_channels);
  // Note: Num of bises at IsoManager level is verified by state machine tests
}

}  // namespace
}  // namespace broadcaster
}  // namespace le_audio
