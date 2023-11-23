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

#include <base/bind.h>

#include "bta/include/bta_le_audio_api.h"
#include "bta/include/bta_le_audio_broadcaster_api.h"
#include "bta/le_audio/broadcaster/state_machine.h"
#include "bta/le_audio/le_audio_types.h"
#include "device/include/controller.h"
#include "embdrv/lc3/include/lc3.h"
#include "gd/common/strings.h"
#include "osi/include/log.h"
#include "osi/include/properties.h"
#include "stack/include/btm_api_types.h"
#include "stack/include/btm_iso_api.h"

using bluetooth::common::ToString;
using bluetooth::hci::IsoManager;
using bluetooth::hci::iso_manager::big_create_cmpl_evt;
using bluetooth::hci::iso_manager::big_terminate_cmpl_evt;
using bluetooth::hci::iso_manager::BigCallbacks;
using bluetooth::le_audio::BasicAudioAnnouncementData;
using bluetooth::le_audio::BroadcastId;
using le_audio::broadcaster::BigConfig;
using le_audio::broadcaster::BroadcastCodecWrapper;
using le_audio::broadcaster::BroadcastStateMachine;
using le_audio::broadcaster::BroadcastStateMachineConfig;
using le_audio::broadcaster::IBroadcastStateMachineCallbacks;
using le_audio::types::kLeAudioCodingFormatLC3;
using le_audio::types::LeAudioLtvMap;

namespace {
class LeAudioBroadcasterImpl;
LeAudioBroadcasterImpl* instance;
LeAudioBroadcastClientAudioSource* leAudioClientAudioSource;

/* Class definitions */

/* LeAudioBroadcasterImpl class represents main implementation class for le
 * audio broadcaster feature in the stack.
 *
 * This class may be bonded with Test socket which allows to drive an instance
 * for test purposes.
 */
class LeAudioBroadcasterImpl : public LeAudioBroadcaster, public BigCallbacks {
  enum class AudioDataPathState {
    INACTIVE,
    ACTIVE,
    SUSPENDED,
  };

 public:
  LeAudioBroadcasterImpl(
      bluetooth::le_audio::LeAudioBroadcasterCallbacks* callbacks_)
      : callbacks_(callbacks_),
        current_phy_(PHY_LE_2M),
        num_retransmit_(3),
        audio_data_path_state_(AudioDataPathState::INACTIVE),
        audio_instance_(nullptr) {
    LOG_INFO();

    /* Register State machine callbacks */
    BroadcastStateMachine::Initialize(&state_machine_callbacks_);

    GenerateBroadcastIds();
  }

  ~LeAudioBroadcasterImpl() override = default;

  void GenerateBroadcastIds(void) {
    btsnd_hcic_ble_rand(base::Bind([](BT_OCTET8 rand) {
      if (!instance) return;

      /* LE Rand returns 8 octets. Lets' make 2 outstanding Broadcast Ids out
       * of it */
      for (int i = 0; i < 8; i += 4) {
        BroadcastId broadcast_id = 0;
        /* Broadcast ID should be 3 octets long (BAP v1.0 spec.) */
        STREAM_TO_UINT24(broadcast_id, rand);
        if (broadcast_id == bluetooth::le_audio::kBroadcastIdInvalid) continue;
        instance->available_broadcast_ids_.emplace_back(broadcast_id);
      }

      if (instance->available_broadcast_ids_.empty()) {
        LOG_ALWAYS_FATAL("Unable to generate proper broadcast identifiers.");
      }
    }));
  }

  void CleanUp() {
    LOG_INFO("Broadcaster");
    broadcasts_.clear();
    callbacks_ = nullptr;

    if (audio_instance_) {
      leAudioClientAudioSource->Stop();
      leAudioClientAudioSource->Release(audio_instance_);
      audio_instance_ = nullptr;
    }
  }

  void Stop() {
    LOG_INFO("Broadcaster");

    for (auto& sm_pair : broadcasts_) {
      StopAudioBroadcast(sm_pair.first);
    }
  }

  static BasicAudioAnnouncementData prepareAnnouncement(
      const BroadcastCodecWrapper& codec_config, LeAudioLtvMap metadata) {
    BasicAudioAnnouncementData announcement;

    /* Prepare the announcement */
    announcement.presentation_delay = 0x004E20; /* TODO: Use the proper value */

    auto const& codec_id = codec_config.GetLeAudioCodecId();

    /* Note: Currently we have a single audio source configured with a one
     *       set of codec/pcm parameters thus we can use a single subgroup
     *       for all the BISes. Configure common BIS codec params at the
     *       subgroup level.
     */
    announcement.subgroup_configs = {{
        .codec_config =
            {
                .codec_id = codec_id.coding_format,
                .vendor_company_id = codec_id.vendor_company_id,
                .vendor_codec_id = codec_id.vendor_codec_id,
                .codec_specific_params =
                    codec_config.GetSubgroupCodecSpecData().Values(),
            },
        .metadata = metadata.Values(),
        .bis_configs = {},
    }};

    /* BIS indices range is [1-31] - BASS, Sec.3.2 Broadcast Receive State. */
    for (uint8_t i = 0; i < codec_config.GetNumChannels(); ++i) {
      announcement.subgroup_configs[0].bis_configs.push_back(
          {.codec_specific_params =
               codec_config.GetBisCodecSpecData(i + 1).Values(),
           .bis_index = static_cast<uint8_t>(i + 1)});
    }

    return announcement;
  }

  void UpdateMetadata(uint32_t broadcast_id,
                      std::vector<uint8_t> metadata) override {
    if (broadcasts_.count(broadcast_id) == 0) {
      LOG_ERROR("No such broadcast_id=%d", broadcast_id);
      return;
    }

    LOG_INFO("For broadcast_id=%d", broadcast_id);

    auto& codec_config = broadcasts_[broadcast_id]->GetCodecConfig();

    /* Prepare the announcement format */
    bool is_metadata_valid;
    auto ltv = LeAudioLtvMap::Parse(metadata.data(), metadata.size(),
                                    is_metadata_valid);
    if (!is_metadata_valid) {
      LOG_ERROR("Invalid metadata provided.");
      return;
    }

    BasicAudioAnnouncementData announcement =
        prepareAnnouncement(codec_config, std::move(ltv));

    broadcasts_[broadcast_id]->UpdateBroadcastAnnouncement(
        std::move(announcement));
  }

  void CreateAudioBroadcast(std::vector<uint8_t> metadata,
                            LeAudioBroadcaster::AudioProfile profile,
                            std::optional<bluetooth::le_audio::BroadcastCode>
                                broadcast_code) override {
    LOG_INFO("Audio profile: %s",
             profile == LeAudioBroadcaster::AudioProfile::MEDIA
                 ? "Media"
                 : "Sonification");

    auto& codec_wrapper =
        BroadcastCodecWrapper::getCodecConfigForProfile(profile);

    auto broadcast_id = available_broadcast_ids_.back();
    available_broadcast_ids_.pop_back();
    if (available_broadcast_ids_.size() == 0) GenerateBroadcastIds();

    /* Prepare the announcement format */
    bool is_metadata_valid;
    auto ltv = LeAudioLtvMap::Parse(metadata.data(), metadata.size(),
                                    is_metadata_valid);
    if (!is_metadata_valid) {
      LOG_ERROR("Invalid metadata provided.");
      return;
    }

    BroadcastStateMachineConfig msg = {
        .broadcast_id = broadcast_id,
        .streaming_phy = GetStreamingPhy(),
        .codec_wrapper = codec_wrapper,
        .announcement = prepareAnnouncement(codec_wrapper, std::move(ltv)),
        .broadcast_code = std::move(broadcast_code)};

    /* Create the broadcaster instance - we'll receive it's init state in the
     * async callback
     */
    pending_broadcasts_.push_back(
        std::move(BroadcastStateMachine::CreateInstance(std::move(msg))));

    // Notify the error instead just fail silently
    if (!pending_broadcasts_.back()->Initialize()) {
      pending_broadcasts_.pop_back();
      callbacks_->OnBroadcastCreated(bluetooth::le_audio::kBroadcastIdInvalid,
                                     false);
    }
  }

  void SuspendAudioBroadcast(uint32_t broadcast_id) override {
    LOG_INFO("broadcast_id=%d", broadcast_id);

    if (broadcasts_.count(broadcast_id) != 0) {
      LOG_INFO("Stopping LeAudioClientAudioSource");
      leAudioClientAudioSource->Stop();
      broadcasts_[broadcast_id]->SetMuted(true);
      broadcasts_[broadcast_id]->ProcessMessage(
          BroadcastStateMachine::Message::SUSPEND, nullptr);
    } else {
      LOG_ERROR("No such broadcast_id=%d", broadcast_id);
    }
  }

  static bool IsAnyoneStreaming() {
    if (!instance) return false;

    auto const& iter =
        std::find_if(instance->broadcasts_.cbegin(),
                     instance->broadcasts_.cend(), [](auto const& sm) {
                       return sm.second->GetState() ==
                              BroadcastStateMachine::State::STREAMING;
                     });
    return (iter != instance->broadcasts_.cend());
  }

  void StartAudioBroadcast(uint32_t broadcast_id) override {
    LOG_INFO("Starting broadcast_id=%d", broadcast_id);

    if (IsAnyoneStreaming()) {
      LOG_ERROR("Stop the other broadcast first!");
      return;
    }

    if (broadcasts_.count(broadcast_id) != 0) {
      if (!audio_instance_) {
        audio_instance_ = leAudioClientAudioSource->Acquire();
        if (!audio_instance_) {
          LOG_ERROR("Could not acquire le audio");
          return;
        }
      }

      broadcasts_[broadcast_id]->ProcessMessage(
          BroadcastStateMachine::Message::START, nullptr);
    } else {
      LOG_ERROR("No such broadcast_id=%d", broadcast_id);
    }
  }

  void StopAudioBroadcast(uint32_t broadcast_id) override {
    if (broadcasts_.count(broadcast_id) == 0) {
      LOG_ERROR("no such broadcast_id=%d", broadcast_id);
      return;
    }

    LOG_INFO("Stopping LeAudioClientAudioSource, broadcast_id=%d",
             broadcast_id);
    leAudioClientAudioSource->Stop();
    broadcasts_[broadcast_id]->SetMuted(true);
    broadcasts_[broadcast_id]->ProcessMessage(
        BroadcastStateMachine::Message::STOP, nullptr);
  }

  void DestroyAudioBroadcast(uint32_t broadcast_id) override {
    LOG_INFO("Destroying broadcast_id=%d", broadcast_id);
    broadcasts_.erase(broadcast_id);
  }

  std::optional<bluetooth::le_audio::BroadcastMetadata> GetBroadcastMetadataOpt(
      bluetooth::le_audio::BroadcastId broadcast_id) {
    bluetooth::le_audio::BroadcastMetadata metadata;
    for (auto const& kv_it : broadcasts_) {
      if (kv_it.second->GetBroadcastId() == broadcast_id) {
        metadata.broadcast_id = kv_it.second->GetBroadcastId();
        metadata.adv_sid = kv_it.second->GetAdvertisingSid();
        metadata.pa_interval = kv_it.second->GetPaInterval();
        metadata.addr = kv_it.second->GetOwnAddress();
        metadata.addr_type = kv_it.second->GetOwnAddressType();
        metadata.broadcast_code = kv_it.second->GetBroadcastCode();
        metadata.basic_audio_announcement =
            kv_it.second->GetBroadcastAnnouncement();
        return metadata;
      }
    }
    return std::nullopt;
  }

  void GetBroadcastMetadata(uint32_t broadcast_id) override {
    if (broadcasts_.count(broadcast_id) == 0) {
      LOG_ERROR("No such broadcast_id=%d", broadcast_id);
      return;
    }

    auto meta = GetBroadcastMetadataOpt(broadcast_id);
    if (!meta) {
      LOG_ERROR("No metadata for broadcast_id=%d", broadcast_id);
      return;
    }
    callbacks_->OnBroadcastMetadataChanged(broadcast_id,
                                           std::move(meta.value()));
  }

  void GetAllBroadcastStates(void) override {
    for (auto const& kv_it : broadcasts_) {
      callbacks_->OnBroadcastStateChanged(
          kv_it.second->GetBroadcastId(),
          static_cast<bluetooth::le_audio::BroadcastState>(
              kv_it.second->GetState()));
    }
  }

  void IsValidBroadcast(
      uint32_t broadcast_id, uint8_t addr_type, RawAddress addr,
      base::Callback<void(uint8_t /* broadcast_id */, uint8_t /* addr_type */,
                          RawAddress /* addr */, bool /* is_local */)>
          cb) override {
    if (broadcasts_.count(broadcast_id) == 0) {
      LOG_ERROR("No such broadcast_id=%d", broadcast_id);
      std::move(cb).Run(broadcast_id, addr_type, addr, false);
      return;
    }

    broadcasts_[broadcast_id]->RequestOwnAddress(base::Bind(
        [](uint32_t broadcast_id, uint8_t req_address_type,
           RawAddress req_address,
           base::Callback<void(uint8_t /* broadcast_id */,
                               uint8_t /* addr_type */, RawAddress /* addr */,
                               bool /* is_local */)>
               cb,
           uint8_t rcv_address_type, RawAddress rcv_address) {
          bool is_local = (req_address_type == rcv_address_type) &&
                          (req_address == rcv_address);
          std::move(cb).Run(broadcast_id, req_address_type, req_address,
                            is_local);
        },
        broadcast_id, addr_type, addr, std::move(cb)));
  }

  void SetNumRetransmit(uint8_t count) override { num_retransmit_ = count; }

  uint8_t GetNumRetransmit(void) const override { return num_retransmit_; }

  void SetStreamingPhy(uint8_t phy) override { current_phy_ = phy; }

  uint8_t GetStreamingPhy(void) const override { return current_phy_; }

  BroadcastId BroadcastIdFromBigHandle(uint8_t big_handle) const {
    auto pair_it =
        std::find_if(broadcasts_.begin(), broadcasts_.end(),
                     [big_handle](auto const& entry) {
                       return entry.second->GetAdvertisingSid() == big_handle;
                     });
    if (pair_it != broadcasts_.end()) {
      return pair_it->second->GetBroadcastId();
    }
    return bluetooth::le_audio::kBroadcastIdInvalid;
  }

  void OnSetupIsoDataPath(uint8_t status, uint16_t conn_handle,
                          uint8_t big_handle) override {
    auto broadcast_id = BroadcastIdFromBigHandle(big_handle);
    CHECK(broadcasts_.count(broadcast_id) != 0);
    broadcasts_[broadcast_id]->OnSetupIsoDataPath(status, conn_handle);
  }

  void OnRemoveIsoDataPath(uint8_t status, uint16_t conn_handle,
                           uint8_t big_handle) override {
    auto broadcast_id = BroadcastIdFromBigHandle(big_handle);
    CHECK(broadcasts_.count(broadcast_id) != 0);
    broadcasts_[broadcast_id]->OnRemoveIsoDataPath(status, conn_handle);
  }

  void OnBigEvent(uint8_t event, void* data) override {
    switch (event) {
      case bluetooth::hci::iso_manager::kIsoEventBigOnCreateCmpl: {
        auto* evt = static_cast<big_create_cmpl_evt*>(data);
        auto broadcast_id = BroadcastIdFromBigHandle(evt->big_id);
        CHECK(broadcasts_.count(broadcast_id) != 0);
        broadcasts_[broadcast_id]->HandleHciEvent(HCI_BLE_CREATE_BIG_CPL_EVT,
                                                  evt);

      } break;
      case bluetooth::hci::iso_manager::kIsoEventBigOnTerminateCmpl: {
        auto* evt = static_cast<big_terminate_cmpl_evt*>(data);
        auto broadcast_id = BroadcastIdFromBigHandle(evt->big_id);
        CHECK(broadcasts_.count(broadcast_id) != 0);
        broadcasts_[broadcast_id]->HandleHciEvent(HCI_BLE_TERM_BIG_CPL_EVT,
                                                  evt);
        leAudioClientAudioSource->Release(audio_instance_);
        audio_instance_ = nullptr;
      } break;
      default:
        LOG_ERROR("Invalid event=%d", event);
    }
  }

  void Dump(int fd) {
    std::stringstream stream;

    stream << "    Number of broadcasts: " << broadcasts_.size() << "\n";
    for (auto& broadcast_pair : broadcasts_) {
      auto& broadcast = broadcast_pair.second;
      if (broadcast) stream << *broadcast;
    }

    dprintf(fd, "%s", stream.str().c_str());
  }

 private:
  uint8_t GetNumRetransmit(uint32_t broadcast_id) {
    /* TODO: Should be based on QOS settings */
    return GetNumRetransmit();
  }

  uint32_t GetSduItv(uint32_t broadcast_id) {
    /* TODO: Should be based on QOS settings
     * currently tuned for media profile (music band)
     */
    return 0x002710;
  }

  uint16_t GetMaxTransportLatency(uint32_t broadcast_id) {
    /* TODO: Should be based on QOS settings
     * currently tuned for media profile (music band)
     */
    return 0x3C;
  }

  static class BroadcastStateMachineCallbacks
      : public IBroadcastStateMachineCallbacks {
    void OnStateMachineCreateStatus(uint32_t broadcast_id,
                                    bool initialized) override {
      auto pending_broadcast = std::find_if(
          instance->pending_broadcasts_.begin(),
          instance->pending_broadcasts_.end(), [broadcast_id](auto& sm) {
            return (sm->GetBroadcastId() == broadcast_id);
          });
      LOG_ASSERT(pending_broadcast != instance->pending_broadcasts_.end());
      LOG_ASSERT(instance->broadcasts_.count(broadcast_id) == 0);

      if (initialized) {
        const uint32_t broadcast_id = (*pending_broadcast)->GetBroadcastId();
        LOG_INFO("broadcast_id=%d state=%s", broadcast_id,
                 ToString((*pending_broadcast)->GetState()).c_str());

        instance->broadcasts_[broadcast_id] = std::move(*pending_broadcast);
      } else {
        LOG_ERROR("Failed creating broadcast!");
      }
      instance->pending_broadcasts_.erase(pending_broadcast);
      instance->callbacks_->OnBroadcastCreated(broadcast_id, initialized);
    }

    void OnStateMachineDestroyed(uint32_t broadcast_id) override {
      /* This is a special case when state machine destructor calls this
       * callback. It may happen during the Cleanup() call when all state
       * machines are erased and instance can already be set to null to avoid
       * unnecessary calls.
       */
      if (instance) instance->callbacks_->OnBroadcastDestroyed(broadcast_id);
    }

    static int getStreamerCount() {
      return std::count_if(instance->broadcasts_.begin(),
                           instance->broadcasts_.end(), [](auto const& sm) {
                             LOG_VERBOSE(
                                 "broadcast_id=%d, state=%s",
                                 sm.second->GetBroadcastId(),
                                 ToString(sm.second->GetState()).c_str());
                             return sm.second->GetState() ==
                                    BroadcastStateMachine::State::STREAMING;
                           });
    }

    void OnStateMachineEvent(uint32_t broadcast_id,
                             BroadcastStateMachine::State state,
                             const void* data) override {
      LOG_INFO("broadcast_id=%d state=%s", broadcast_id,
               ToString(state).c_str());

      switch (state) {
        case BroadcastStateMachine::State::STOPPED:
          /* Pass through */
        case BroadcastStateMachine::State::CONFIGURING:
          /* Pass through */
        case BroadcastStateMachine::State::CONFIGURED:
          /* Pass through */
        case BroadcastStateMachine::State::STOPPING:
          /* Nothing to do here? */
          break;
        case BroadcastStateMachine::State::STREAMING:
          if (getStreamerCount() == 1) {
            LOG_INFO("Starting LeAudioClientAudioSource");

            if (instance->broadcasts_.count(broadcast_id) != 0) {
              const auto& broadcast = instance->broadcasts_.at(broadcast_id);

              // Reconfigure encoder instance for the new stream requirements
              audio_receiver_.setCurrentCodecConfig(
                  broadcast->GetCodecConfig());
              audio_receiver_.CheckAndReconfigureEncoders();

              broadcast->SetMuted(false);
              auto cfg = static_cast<const LeAudioCodecConfiguration*>(data);
              auto is_started =
                  leAudioClientAudioSource->Start(*cfg, &audio_receiver_);
              if (!is_started) {
                /* Audio Source setup failed - stop the broadcast */
                instance->StopAudioBroadcast(broadcast_id);
                return;
              }

              instance->audio_data_path_state_ = AudioDataPathState::ACTIVE;
            }
          }
          break;
      };

      instance->callbacks_->OnBroadcastStateChanged(
          broadcast_id,
          static_cast<bluetooth::le_audio::BroadcastState>(state));
    }

    void OnOwnAddressResponse(uint32_t broadcast_id, uint8_t addr_type,
                              RawAddress addr) override {
      /* Not used currently */
    }

    uint8_t GetNumRetransmit(uint32_t broadcast_id) override {
      return instance->GetNumRetransmit(broadcast_id);
    }

    uint32_t GetSduItv(uint32_t broadcast_id) override {
      return instance->GetSduItv(broadcast_id);
    }

    uint16_t GetMaxTransportLatency(uint32_t broadcast_id) override {
      return instance->GetMaxTransportLatency(broadcast_id);
    }
  } state_machine_callbacks_;

  static class LeAudioClientAudioSinkReceiverImpl
      : public LeAudioClientAudioSinkReceiver {
   public:
    LeAudioClientAudioSinkReceiverImpl()
        : codec_wrapper_(BroadcastCodecWrapper::getCodecConfigForProfile(
              LeAudioBroadcaster::AudioProfile::SONIFICATION)) {}

    void CheckAndReconfigureEncoders() {
      auto const& codec_id = codec_wrapper_.GetLeAudioCodecId();
      if (codec_id.coding_format != kLeAudioCodingFormatLC3) {
        LOG_ERROR("Invalid codec ID: [%d:%d:%d]", codec_id.coding_format,
                  codec_id.vendor_company_id, codec_id.vendor_codec_id);
        return;
      }

      if (enc_audio_buffers_.size() != codec_wrapper_.GetNumChannels()) {
        enc_audio_buffers_.resize(codec_wrapper_.GetNumChannels());
      }

      const int dt_us = codec_wrapper_.GetDataIntervalUs();
      const int sr_hz = codec_wrapper_.GetSampleRate();
      const auto encoder_bytes = lc3_encoder_size(dt_us, sr_hz);
      const auto channel_bytes = codec_wrapper_.GetMaxSduSizePerChannel();

      /* TODO: We should act smart and reuse current configurations */
      encoders_.clear();
      encoders_mem_.clear();
      while (encoders_.size() < codec_wrapper_.GetNumChannels()) {
        auto& encoder_buf = enc_audio_buffers_.at(encoders_.size());
        encoder_buf.resize(channel_bytes);

        encoders_mem_.emplace_back(malloc(encoder_bytes), &std::free);
        encoders_.emplace_back(
            lc3_setup_encoder(dt_us, sr_hz, 0, encoders_mem_.back().get()));
      }
    }

    const BroadcastCodecWrapper& getCurrentCodecConfig(void) const {
      return codec_wrapper_;
    }

    void setCurrentCodecConfig(BroadcastCodecWrapper const& config) {
      codec_wrapper_ = config;
    }

    void encodeLc3Channel(lc3_encoder_t encoder,
                          std::vector<uint8_t>& out_buffer,
                          const std::vector<uint8_t>& data,
                          int initial_channel_offset, int pitch_samples,
                          int num_channels) {
      auto encoder_status =
          lc3_encode(encoder, LC3_PCM_FORMAT_S16,
                     (int16_t*)(data.data() + initial_channel_offset),
                     pitch_samples, out_buffer.size(), out_buffer.data());
      if (encoder_status != 0) {
        LOG_ERROR("Encoding error=%d", encoder_status);
      }
    }

    static void sendBroadcastData(
        const std::unique_ptr<BroadcastStateMachine>& broadcast,
        std::vector<std::vector<uint8_t>>& encoded_channels) {
      auto const& config = broadcast->GetBigConfig();
      if (config == std::nullopt) {
        LOG_ERROR(
            "Broadcast broadcast_id=%d has no valid BIS configurations in "
            "state=%s",
            broadcast->GetBroadcastId(),
            ToString(broadcast->GetState()).c_str());
        return;
      }

      if (config->connection_handles.size() < encoded_channels.size()) {
        LOG_ERROR("Not enough BIS'es to broadcast all channels!");
        return;
      }

      for (uint8_t chan = 0; chan < encoded_channels.size(); ++chan) {
        IsoManager::GetInstance()->SendIsoData(config->connection_handles[chan],
                                               encoded_channels[chan].data(),
                                               encoded_channels[chan].size());
      }
    }

    virtual void OnAudioDataReady(const std::vector<uint8_t>& data) override {
      if (!instance) return;

      LOG_VERBOSE("Received %zu bytes.", data.size());

      /* Constants for the channel data configuration */
      const auto num_channels = codec_wrapper_.GetNumChannels();
      const auto bytes_per_sample = (codec_wrapper_.GetBitsPerSample() / 8);

      /* Prepare encoded data for all channels */
      for (uint8_t chan = 0; chan < num_channels; ++chan) {
        /* TODO: Use encoder agnostic wrapper */
        encodeLc3Channel(encoders_[chan], enc_audio_buffers_[chan], data,
                         chan * bytes_per_sample, num_channels, num_channels);
      }

      /* Currently there is no way to broadcast multiple distinct streams.
       * We just receive all system sounds mixed into a one stream and each
       * broadcast gets the same data.
       */
      for (auto& broadcast_pair : instance->broadcasts_) {
        auto& broadcast = broadcast_pair.second;
        if ((broadcast->GetState() ==
             BroadcastStateMachine::State::STREAMING) &&
            !broadcast->IsMuted())
          sendBroadcastData(broadcast, enc_audio_buffers_);
      }
      LOG_VERBOSE("All data sent.");
    }

    virtual void OnAudioSuspend(
        std::promise<void> do_suspend_promise) override {
      LOG_INFO();
      /* TODO: Should we suspend all broadcasts - remove BIGs? */
      do_suspend_promise.set_value();
      if (instance)
        instance->audio_data_path_state_ = AudioDataPathState::SUSPENDED;
    }

    virtual void OnAudioResume(void) override {
      LOG_INFO();
      /* TODO: Should we resume all broadcasts - recreate BIGs? */
      if (instance)
        instance->audio_data_path_state_ = AudioDataPathState::ACTIVE;

      if (!IsAnyoneStreaming()) {
        leAudioClientAudioSource->CancelStreamingRequest();
        return;
      }

      leAudioClientAudioSource->ConfirmStreamingRequest();
    }

    virtual void OnAudioMetadataUpdate(
        std::promise<void> do_update_metadata_promise,
        const source_metadata_t& source_metadata) override {
      LOG_INFO();
      if (!instance) return;
      do_update_metadata_promise.set_value();
      /* TODO: We probably don't want to change stream type or update the
       * advertized metadata on each call. We should rather make sure we get
       * only a single content audio stream from the media frameworks.
       */
    }

   private:
    BroadcastCodecWrapper codec_wrapper_;
    std::vector<lc3_encoder_t> encoders_;
    std::vector<std::unique_ptr<void, decltype(&std::free)>> encoders_mem_;
    std::vector<std::vector<uint8_t>> enc_audio_buffers_;
  } audio_receiver_;

  bluetooth::le_audio::LeAudioBroadcasterCallbacks* callbacks_;
  std::map<uint32_t, std::unique_ptr<BroadcastStateMachine>> broadcasts_;
  std::vector<std::unique_ptr<BroadcastStateMachine>> pending_broadcasts_;

  /* Some BIG params are set globally */
  uint8_t current_phy_;
  uint8_t num_retransmit_;
  AudioDataPathState audio_data_path_state_;
  const void* audio_instance_;
  std::vector<BroadcastId> available_broadcast_ids_;
};

/* Static members definitions */
LeAudioBroadcasterImpl::BroadcastStateMachineCallbacks
    LeAudioBroadcasterImpl::state_machine_callbacks_;
LeAudioBroadcasterImpl::LeAudioClientAudioSinkReceiverImpl
    LeAudioBroadcasterImpl::audio_receiver_;

} /* namespace */

void LeAudioBroadcaster::Initialize(
    bluetooth::le_audio::LeAudioBroadcasterCallbacks* callbacks,
    base::Callback<bool()> audio_hal_verifier) {
  LOG_INFO();
  if (instance) {
    LOG_ERROR("Already initialized");
    return;
  }

  if (!controller_get_interface()->supports_ble_isochronous_broadcaster() &&
      !osi_property_get_bool("persist.bluetooth.fake_iso_support", false)) {
    LOG_WARN("Isochronous Broadcast not supported by the controller!");
    return;
  }

  if (!std::move(audio_hal_verifier).Run()) {
    LOG_ALWAYS_FATAL("HAL requirements not met. Init aborted.");
  }

  /* Create new client audio broadcast instance */
  InitializeAudioClient(nullptr);
  IsoManager::GetInstance()->Start();

  instance = new LeAudioBroadcasterImpl(callbacks);
  /* Register HCI event handlers */
  IsoManager::GetInstance()->RegisterBigCallbacks(instance);
}

bool LeAudioBroadcaster::IsLeAudioBroadcasterRunning() { return instance; }

LeAudioBroadcaster* LeAudioBroadcaster::Get(void) {
  LOG_INFO();
  CHECK(instance);
  return instance;
}

void LeAudioBroadcaster::Stop(void) {
  LOG_INFO();

  if (instance) {
    instance->Stop();
  }
}

void LeAudioBroadcaster::Cleanup(void) {
  LOG_INFO();

  if (instance == nullptr) return;

  LeAudioBroadcasterImpl* ptr = instance;
  instance = nullptr;

  ptr->CleanUp();
  if (leAudioClientAudioSource) {
    delete leAudioClientAudioSource;
    leAudioClientAudioSource = nullptr;
  }
  delete ptr;
}

void LeAudioBroadcaster::DebugDump(int fd) {
  dprintf(fd, "Le Audio Broadcaster:\n");
  if (instance) instance->Dump(fd);
  dprintf(fd, "\n");
}

void LeAudioBroadcaster::InitializeAudioClient(
    LeAudioBroadcastClientAudioSource* clientAudioSource) {
  if (leAudioClientAudioSource) {
    LOG(WARNING) << __func__ << ", audio clients already initialized";
    return;
  }

  if (!clientAudioSource) {
    /* Create new instance if no pre-created is delivered */
    leAudioClientAudioSource = new LeAudioBroadcastClientAudioSource();
  } else {
    /* Use pre-created instance e.g. from test suit */
    leAudioClientAudioSource = clientAudioSource;
  }
}
