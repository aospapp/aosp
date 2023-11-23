/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com. Represented by EHIMA -
 * www.ehima.com
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
#include <base/strings/string_number_conversions.h>

#include "advertise_data_parser.h"
#include "audio_hal_interface/le_audio_software.h"
#include "bta/csis/csis_types.h"
#include "bta_api.h"
#include "bta_gatt_api.h"
#include "bta_gatt_queue.h"
#include "bta_groups.h"
#include "bta_le_audio_api.h"
#include "btif_storage.h"
#include "btm_iso_api.h"
#include "client_audio.h"
#include "client_parser.h"
#include "codec_manager.h"
#include "common/time_util.h"
#include "device/include/controller.h"
#include "devices.h"
#include "embdrv/lc3/include/lc3.h"
#include "gatt/bta_gattc_int.h"
#include "gd/common/strings.h"
#include "le_audio_set_configuration_provider.h"
#include "le_audio_types.h"
#include "metrics_collector.h"
#include "osi/include/log.h"
#include "osi/include/osi.h"
#include "osi/include/properties.h"
#include "stack/btm/btm_sec.h"
#include "stack/include/btu.h"  // do_in_main_thread
#include "state_machine.h"

using base::Closure;
using bluetooth::Uuid;
using bluetooth::common::ToString;
using bluetooth::groups::DeviceGroups;
using bluetooth::groups::DeviceGroupsCallbacks;
using bluetooth::hci::IsoManager;
using bluetooth::hci::iso_manager::cig_create_cmpl_evt;
using bluetooth::hci::iso_manager::cig_remove_cmpl_evt;
using bluetooth::hci::iso_manager::CigCallbacks;
using bluetooth::le_audio::ConnectionState;
using bluetooth::le_audio::GroupNodeStatus;
using bluetooth::le_audio::GroupStatus;
using bluetooth::le_audio::GroupStreamStatus;
using le_audio::CodecManager;
using le_audio::LeAudioDevice;
using le_audio::LeAudioDeviceGroup;
using le_audio::LeAudioDeviceGroups;
using le_audio::LeAudioDevices;
using le_audio::LeAudioGroupStateMachine;
using le_audio::types::ase;
using le_audio::types::AseState;
using le_audio::types::AudioContexts;
using le_audio::types::AudioLocations;
using le_audio::types::AudioStreamDataPathState;
using le_audio::types::hdl_pair;
using le_audio::types::kDefaultScanDurationS;
using le_audio::types::LeAudioContextType;

using le_audio::client_parser::ascs::
    kCtpResponseCodeInvalidConfigurationParameterValue;
using le_audio::client_parser::ascs::kCtpResponseCodeSuccess;
using le_audio::client_parser::ascs::kCtpResponseInvalidAseCisMapping;
using le_audio::client_parser::ascs::kCtpResponseNoReason;

/* Enums */
enum class AudioState {
  IDLE = 0x00,
  READY_TO_START,
  STARTED,
  READY_TO_RELEASE,
  RELEASING,
};

std::ostream& operator<<(std::ostream& os, const AudioState& audio_state) {
  switch (audio_state) {
    case AudioState::IDLE:
      os << "IDLE";
      break;
    case AudioState::READY_TO_START:
      os << "READY_TO_START";
      break;
    case AudioState::STARTED:
      os << "STARTED";
      break;
    case AudioState::READY_TO_RELEASE:
      os << "READY_TO_RELEASE";
      break;
    case AudioState::RELEASING:
      os << "RELEASING";
      break;
    default:
      os << "UNKNOWN";
      break;
  }
  return os;
}

namespace {
void le_audio_gattc_callback(tBTA_GATTC_EVT event, tBTA_GATTC* p_data);

inline uint8_t bits_to_bytes_per_sample(uint8_t bits_per_sample) {
  // 24 bit audio stream is sent as unpacked, each sample takes 4 bytes.
  if (bits_per_sample == 24) return 4;

  return bits_per_sample / 8;
}

inline lc3_pcm_format bits_to_lc3_bits(uint8_t bits_per_sample) {
  if (bits_per_sample == 16) return LC3_PCM_FORMAT_S16;

  if (bits_per_sample == 24) return LC3_PCM_FORMAT_S24;

  LOG_ALWAYS_FATAL("Encoder/decoder don't know how to handle %d",
                   bits_per_sample);
  return LC3_PCM_FORMAT_S16;
}

class LeAudioClientImpl;
LeAudioClientImpl* instance;
LeAudioClientAudioSinkReceiver* audioSinkReceiver;
LeAudioClientAudioSourceReceiver* audioSourceReceiver;
LeAudioUnicastClientAudioSource* leAudioClientAudioSource;
LeAudioUnicastClientAudioSink* leAudioClientAudioSink;
CigCallbacks* stateMachineHciCallbacks;
LeAudioGroupStateMachine::Callbacks* stateMachineCallbacks;
DeviceGroupsCallbacks* device_group_callbacks;

/*
 * Coordinatet Set Identification Profile (CSIP) based on CSIP 1.0
 * and Coordinatet Set Identification Service (CSIS) 1.0
 *
 * CSIP allows to organize audio servers into sets e.g. Stereo Set, 5.1 Set
 * and speed up connecting it.
 *
 * Since leaudio has already grouping API it was decided to integrate here CSIS
 * and allow it to group devices semi-automatically.
 *
 * Flow:
 * If connected device contains CSIS services, and it is included into CAP
 * service, implementation marks device as a set member and waits for the
 * bta/csis to learn about groups and notify implementation about assigned
 * group id.
 *
 */
/* LeAudioClientImpl class represents main implementation class for le audio
 * feature in stack. This class implements GATT, le audio and ISO related parts.
 *
 * This class is represented in single instance and manages a group of devices,
 * and devices. All devices calls back static method from it and are dispatched
 * to target receivers (e.g. ASEs, devices).
 *
 * This instance also implements a LeAudioClient which is a upper layer API.
 * Also LeAudioClientCallbacks are callbacks for upper layer.
 *
 * This class may be bonded with Test socket which allows to drive an instance
 * for test purposes.
 */
class LeAudioClientImpl : public LeAudioClient {
 public:
  ~LeAudioClientImpl() {
    alarm_free(suspend_timeout_);
    suspend_timeout_ = nullptr;
  };

  LeAudioClientImpl(
      bluetooth::le_audio::LeAudioClientCallbacks* callbacks_,
      LeAudioGroupStateMachine::Callbacks* state_machine_callbacks_,
      base::Closure initCb)
      : gatt_if_(0),
        callbacks_(callbacks_),
        active_group_id_(bluetooth::groups::kGroupUnknown),
        current_context_type_(LeAudioContextType::MEDIA),
        stream_setup_start_timestamp_(0),
        stream_setup_end_timestamp_(0),
        audio_receiver_state_(AudioState::IDLE),
        audio_sender_state_(AudioState::IDLE),
        current_source_codec_config({0, 0, 0, 0}),
        current_sink_codec_config({0, 0, 0, 0}),
        lc3_encoder_left_mem(nullptr),
        lc3_encoder_right_mem(nullptr),
        lc3_decoder_left_mem(nullptr),
        lc3_decoder_right_mem(nullptr),
        lc3_decoder_left(nullptr),
        lc3_decoder_right(nullptr),
        audio_source_instance_(nullptr),
        audio_sink_instance_(nullptr),
        suspend_timeout_(alarm_new("LeAudioSuspendTimeout")) {
    LeAudioGroupStateMachine::Initialize(state_machine_callbacks_);
    groupStateMachine_ = LeAudioGroupStateMachine::Get();

    BTA_GATTC_AppRegister(
        le_audio_gattc_callback,
        base::Bind(
            [](base::Closure initCb, uint8_t client_id, uint8_t status) {
              if (status != GATT_SUCCESS) {
                LOG(ERROR) << "Can't start LeAudio profile - no gatt "
                              "clients left!";
                return;
              }
              instance->gatt_if_ = client_id;
              initCb.Run();
            },
            initCb),
        true);

    DeviceGroups::Get()->Initialize(device_group_callbacks);
  }

  void AseInitialStateReadRequest(LeAudioDevice* leAudioDevice) {
    int ases_num = leAudioDevice->ases_.size();
    void* notify_flag_ptr = NULL;

    for (int i = 0; i < ases_num; i++) {
      /* Last read ase characteristic should issue connected state callback
       * to upper layer
       */

      if (leAudioDevice->notify_connected_after_read_ &&
          (i == (ases_num - 1))) {
        notify_flag_ptr =
            INT_TO_PTR(leAudioDevice->notify_connected_after_read_);
      }

      BtaGattQueue::ReadCharacteristic(leAudioDevice->conn_id_,
                                       leAudioDevice->ases_[i].hdls.val_hdl,
                                       OnGattReadRspStatic, notify_flag_ptr);
    }
  }

  void OnGroupAddedCb(const RawAddress& address, const bluetooth::Uuid& uuid,
                      int group_id) {
    LOG(INFO) << __func__ << " address: " << address << " group uuid " << uuid
              << " group_id: " << group_id;

    /* We are interested in the groups which are in the context of CAP */
    if (uuid != le_audio::uuid::kCapServiceUuid) return;

    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (!leAudioDevice) return;
    if (leAudioDevice->group_id_ != bluetooth::groups::kGroupUnknown) {
      LOG(INFO) << __func__
                << " group already set: " << leAudioDevice->group_id_;
      return;
    }

    group_add_node(group_id, address);
  }

  void OnGroupMemberAddedCb(const RawAddress& address, int group_id) {
    LOG(INFO) << __func__ << " address: " << address
              << " group_id: " << group_id;

    auto group = aseGroups_.FindById(group_id);
    if (!group) {
      LOG(ERROR) << __func__ << " Not interested in group id: " << group_id;
      return;
    }

    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (!leAudioDevice) return;
    if (leAudioDevice->group_id_ != bluetooth::groups::kGroupUnknown) {
      LOG(INFO) << __func__
                << " group already set: " << leAudioDevice->group_id_;
      return;
    }

    group_add_node(group_id, address);
  }

  void OnGroupMemberRemovedCb(const RawAddress& address, int group_id) {
    LOG(INFO) << __func__ << " address: " << address
              << " group_id: " << group_id;

    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (!leAudioDevice) return;
    if (leAudioDevice->group_id_ == bluetooth::groups::kGroupUnknown) {
      LOG(INFO) << __func__ << " device already not assigned to the group.";
      return;
    }

    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);
    if (group == NULL) {
      LOG(INFO) << __func__
                << " device not in the group: " << leAudioDevice->address_
                << ", " << group_id;
      return;
    }

    group_remove_node(group, address);
  }

  int GetCcid(le_audio::types::LeAudioContextType context_type) {
    if (ccids_.count(context_type) == 0) {
      return -1;
    }

    return ccids_[context_type];
  }

  /* This callback happens if kLeAudioDeviceSetStateTimeoutMs timeout happens
   * during transition from origin to target state
   */
  void OnLeAudioDeviceSetStateTimeout(int group_id) {
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);

    if (!group) {
      /* Group removed */
      return;
    }

    LOG_ERROR(
        " State not achieved on time for group: group id %d, current state %s, "
        "target state: %s",
        group_id, ToString(group->GetState()).c_str(),
        ToString(group->GetTargetState()).c_str());
    group->SetTargetState(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);

    /* There is an issue with a setting up stream or any other operation which
     * are gatt operations. It means peer is not responsable. Lets close ACL
     */
    CancelStreamingRequest();
    LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
    if (leAudioDevice == nullptr) {
      LOG_ERROR(" Shouldn't be called without an active device.");
      leAudioDevice = group->GetFirstDevice();
      if (leAudioDevice == nullptr) {
        LOG_ERROR(" Front device is null. Number of devices: %d",
                  group->Size());
        return;
      }
    }

    do {
      if (instance) instance->DisconnectDevice(leAudioDevice, true);
      leAudioDevice = group->GetNextActiveDevice(leAudioDevice);
    } while (leAudioDevice);
  }

  void UpdateContextAndLocations(LeAudioDeviceGroup* group,
                                 LeAudioDevice* leAudioDevice) {
    std::optional<AudioContexts> new_group_updated_contexts =
        group->UpdateActiveContextsMap(leAudioDevice->GetAvailableContexts());

    if (new_group_updated_contexts || group->ReloadAudioLocations()) {
      callbacks_->OnAudioConf(group->audio_directions_, group->group_id_,
                              group->snk_audio_locations_.to_ulong(),
                              group->src_audio_locations_.to_ulong(),
                              new_group_updated_contexts->to_ulong());
    }
  }

  void SuspendedForReconfiguration() {
    if (audio_sender_state_ > AudioState::IDLE) {
      leAudioClientAudioSource->SuspendedForReconfiguration();
    }
    if (audio_receiver_state_ > AudioState::IDLE) {
      leAudioClientAudioSink->SuspendedForReconfiguration();
    }
  }

  void CancelStreamingRequest() {
    if (audio_sender_state_ >= AudioState::READY_TO_START) {
      leAudioClientAudioSource->CancelStreamingRequest();
      audio_sender_state_ = AudioState::IDLE;
    }

    if (audio_receiver_state_ >= AudioState::READY_TO_START) {
      leAudioClientAudioSink->CancelStreamingRequest();
      audio_receiver_state_ = AudioState::IDLE;
    }
  }

  void ControlPointNotificationHandler(
      struct le_audio::client_parser::ascs::ctp_ntf& ntf) {
    for (auto& entry : ntf.entries) {
      switch (entry.response_code) {
        case kCtpResponseCodeInvalidConfigurationParameterValue:
          switch (entry.reason) {
            case kCtpResponseInvalidAseCisMapping:
              CancelStreamingRequest();
              break;
            case kCtpResponseNoReason:
            default:
              break;
          }
          break;
        case kCtpResponseCodeSuccess:
          FALLTHROUGH;
        default:
          break;
      }
    }
  }

  void group_add_node(const int group_id, const RawAddress& address,
                      bool update_group_module = false) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    LeAudioDeviceGroup* new_group;
    LeAudioDeviceGroup* old_group = nullptr;
    int old_group_id = bluetooth::groups::kGroupUnknown;

    if (!leAudioDevice) {
      /* TODO This part possible to remove as this is to handle adding device to
       * the group which is unknown and not connected.
       */
      LOG(INFO) << __func__ << ", leAudioDevice unknown , address: " << address
                << " group: " << loghex(group_id);

      if (group_id == bluetooth::groups::kGroupUnknown) return;

      LOG(INFO) << __func__ << "Set member adding ...";
      leAudioDevices_.Add(address, true);
      leAudioDevice = leAudioDevices_.FindByAddress(address);
    } else {
      if (leAudioDevice->group_id_ != bluetooth::groups::kGroupUnknown) {
        old_group = aseGroups_.FindById(leAudioDevice->group_id_);
        old_group_id = old_group->group_id_;
      }
    }

    auto id = DeviceGroups::Get()->GetGroupId(address,
                                              le_audio::uuid::kCapServiceUuid);
    if (group_id == bluetooth::groups::kGroupUnknown) {
      if (id == bluetooth::groups::kGroupUnknown) {
        DeviceGroups::Get()->AddDevice(address,
                                       le_audio::uuid::kCapServiceUuid);
        /* We will get back here when group will be created */
        return;
      }

      new_group = aseGroups_.Add(id);
      if (!new_group) {
        LOG(ERROR) << __func__
                   << ", can't create group - group is already there?";
        return;
      }
    } else {
      ASSERT_LOG(id == group_id,
                 " group id missmatch? leaudio id: %d, groups module %d",
                 group_id, id);
      new_group = aseGroups_.FindById(group_id);
      if (!new_group) {
        new_group = aseGroups_.Add(group_id);
      } else {
        if (new_group->IsDeviceInTheGroup(leAudioDevice)) return;
      }
    }

    LOG_DEBUG("New group %p, id: %d", new_group, new_group->group_id_);

    /* If device was in the group and it was not removed by the application,
     * lets do it now
     */
    if (old_group) group_remove_node(old_group, address, update_group_module);

    new_group->AddNode(leAudioDevices_.GetByAddress(address));

    callbacks_->OnGroupNodeStatus(address, new_group->group_id_,
                                  GroupNodeStatus::ADDED);

    /* If device is connected and added to the group, lets read ASE states */
    if (leAudioDevice->conn_id_ != GATT_INVALID_CONN_ID)
      AseInitialStateReadRequest(leAudioDevice);

    /* Group may be destroyed once moved its last node to new group */
    if (aseGroups_.FindById(old_group_id) != nullptr) {
      /* Removing node from group may touch its context integrity */
      std::optional<AudioContexts> old_group_updated_contexts =
          old_group->UpdateActiveContextsMap(old_group->GetActiveContexts());

      if (old_group_updated_contexts || old_group->ReloadAudioLocations()) {
        callbacks_->OnAudioConf(old_group->audio_directions_, old_group_id,
                                old_group->snk_audio_locations_.to_ulong(),
                                old_group->src_audio_locations_.to_ulong(),
                                old_group->GetActiveContexts().to_ulong());
      }
    }

    UpdateContextAndLocations(new_group, leAudioDevice);
  }

  void GroupAddNode(const int group_id, const RawAddress& address) override {
    auto id = DeviceGroups::Get()->GetGroupId(address,
                                              le_audio::uuid::kCapServiceUuid);
    if (id == group_id) return;

    if (id != bluetooth::groups::kGroupUnknown) {
      DeviceGroups::Get()->RemoveDevice(address, id);
    }

    DeviceGroups::Get()->AddDevice(address, le_audio::uuid::kCapServiceUuid,
                                   group_id);
  }

  void remove_group_if_possible(LeAudioDeviceGroup* group) {
    if (!group) {
      LOG_DEBUG("group is null");
      return;
    }
    LOG_DEBUG("Group %p, id: %d, size: %d, is cig_state %s", group,
              group->group_id_, group->Size(),
              ToString(group->cig_state_).c_str());
    if (group->IsEmpty() &&
        (group->cig_state_ == le_audio::types::CigState::NONE)) {
      aseGroups_.Remove(group->group_id_);
    }
  }

  void group_remove_node(LeAudioDeviceGroup* group, const RawAddress& address,
                         bool update_group_module = false) {
    int group_id = group->group_id_;
    group->RemoveNode(leAudioDevices_.GetByAddress(address));

    if (update_group_module) {
      int groups_group_id = DeviceGroups::Get()->GetGroupId(
          address, le_audio::uuid::kCapServiceUuid);
      if (groups_group_id == group_id) {
        DeviceGroups::Get()->RemoveDevice(address, group_id);
      }
    }

    callbacks_->OnGroupNodeStatus(address, group_id, GroupNodeStatus::REMOVED);

    /* Remove group if this was the last leAudioDevice in this group */
    if (group->IsEmpty()) {
      remove_group_if_possible(group);
      return;
    }

    /* Removing node from group touch its context integrity */
    std::optional<AudioContexts> updated_contexts =
        group->UpdateActiveContextsMap(group->GetActiveContexts());

    if (updated_contexts || group->ReloadAudioLocations())
      callbacks_->OnAudioConf(group->audio_directions_, group->group_id_,
                              group->snk_audio_locations_.to_ulong(),
                              group->src_audio_locations_.to_ulong(),
                              group->GetActiveContexts().to_ulong());
  }

  void GroupRemoveNode(const int group_id, const RawAddress& address) override {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);

    LOG(INFO) << __func__ << " group_id: " << group_id
              << " address: " << address;

    if (!leAudioDevice) {
      LOG(ERROR) << __func__
                 << ", Skipping unknown leAudioDevice, address: " << address;
      return;
    }

    if (leAudioDevice->group_id_ != group_id) {
      LOG(ERROR) << __func__ << "Device is not in group_id: " << group_id
                 << ", but in group_id: " << leAudioDevice->group_id_;
      return;
    }

    if (group == NULL) {
      LOG(ERROR) << __func__ << " device not in the group ?!";
      return;
    }

    group_remove_node(group, address, true);
  }

  bool InternalGroupStream(const int group_id, const uint16_t context_type) {
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);
    auto final_context_type = context_type;

    DLOG(INFO) << __func__;
    if (context_type >= static_cast<uint16_t>(LeAudioContextType::RFU)) {
      LOG(ERROR) << __func__ << ", stream context type is not supported: "
                 << loghex(context_type);
      return false;
    }

    if (!group) {
      LOG(ERROR) << __func__ << ", unknown group id: " << group_id;
      return false;
    }

    auto supported_context_type = group->GetActiveContexts();
    if (!(context_type & supported_context_type.to_ulong())) {
      LOG(ERROR) << " Unsupported context type by remote device: "
                 << loghex(context_type) << ". Switching to unspecified";
      final_context_type =
          static_cast<uint16_t>(LeAudioContextType::UNSPECIFIED);
    }

    if (!group->IsAnyDeviceConnected()) {
      LOG(ERROR) << __func__ << ", group " << group_id << " is not connected ";
      return false;
    }

    /* Check if any group is in the transition state. If so, we don't allow to
     * start new group to stream */
    if (aseGroups_.IsAnyInTransition()) {
      LOG(INFO) << __func__ << " some group is already in the transition state";
      return false;
    }

    bool result = groupStateMachine_->StartStream(
        group, static_cast<LeAudioContextType>(final_context_type),
        GetCcid(static_cast<LeAudioContextType>(final_context_type)));
    if (result)
      stream_setup_start_timestamp_ =
          bluetooth::common::time_get_os_boottime_us();

    return result;
  }

  void GroupStream(const int group_id, const uint16_t context_type) override {
    InternalGroupStream(group_id, context_type);
  }

  void GroupSuspend(const int group_id) override {
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);

    if (!group) {
      LOG(ERROR) << __func__ << ", unknown group id: " << group_id;
      return;
    }

    if (!group->IsAnyDeviceConnected()) {
      LOG(ERROR) << __func__ << ", group is not connected";
      return;
    }

    if (group->IsInTransition()) {
      LOG_INFO(", group is in transition from: %s to: %s",
               ToString(group->GetState()).c_str(),
               ToString(group->GetTargetState()).c_str());
      return;
    }

    if (group->GetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      LOG_ERROR(", invalid current state of group: %s",
                ToString(group->GetState()).c_str());
      return;
    }

    groupStateMachine_->SuspendStream(group);
  }

  void GroupStop(const int group_id) override {
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);

    if (!group) {
      LOG(ERROR) << __func__ << ", unknown group id: " << group_id;
      return;
    }

    if (group->IsEmpty()) {
      LOG(ERROR) << __func__ << ", group is empty";
      return;
    }

    if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_IDLE) {
      LOG_ERROR(", group already stopped: %s",
                ToString(group->GetState()).c_str());

      return;
    }

    groupStateMachine_->StopStream(group);
  }

  void GroupDestroy(const int group_id) override {
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);

    if (!group) {
      LOG(ERROR) << __func__ << ", unknown group id: " << group_id;
      return;
    }

    // Disconnect and remove each device within the group
    auto* dev = group->GetFirstDevice();
    while (dev) {
      auto* next_dev = group->GetNextDevice(dev);
      RemoveDevice(dev->address_);
      dev = next_dev;
    }
  }

  void SetCodecConfigPreference(
      int group_id,
      bluetooth::le_audio::btle_audio_codec_config_t input_codec_config,
      bluetooth::le_audio::btle_audio_codec_config_t output_codec_config)
      override {
    // TODO Implement
  }

  void SetCcidInformation(int ccid, int context_type) override {
    LOG_DEBUG("Ccid: %d, context type %d", ccid, context_type);

    std::bitset<16> test{static_cast<uint16_t>(context_type)};
    auto ctx_type =
        static_cast<le_audio::types::LeAudioContextType>(context_type);
    if (test.count() > 1 ||
        ctx_type >= le_audio::types::LeAudioContextType::RFU) {
      LOG_ERROR("Unknownd context type %d", context_type);
      return;
    }

    ccids_[ctx_type] = ccid;
  }

  void StartAudioSession(LeAudioDeviceGroup* group,
                          LeAudioCodecConfiguration* source_config,
                          LeAudioCodecConfiguration* sink_config) {
    /* This function is called when group is not yet set to active.
     * This is why we don't have to check if session is started already.
     * Just check if it is acquired.
     */
    ASSERT_LOG(active_group_id_ == bluetooth::groups::kGroupUnknown,
               "Active group is not set.");
    ASSERT_LOG(audio_source_instance_, "Source session not acquired");
    ASSERT_LOG(audio_sink_instance_, "Sink session not acquired");

    /* We assume that peer device always use same frame duration */
    uint32_t frame_duration_us = 0;
    if (!source_config->IsInvalid()) {
      frame_duration_us = source_config->data_interval_us;
    } else if (!sink_config->IsInvalid()) {
      frame_duration_us = sink_config->data_interval_us;
    } else {
      ASSERT_LOG(true, "Both configs are invalid");
    }

    audio_framework_source_config.data_interval_us = frame_duration_us;
    leAudioClientAudioSource->Start(audio_framework_source_config,
                                    audioSinkReceiver);

    /* We use same frame duration for sink/source */
    audio_framework_sink_config.data_interval_us = frame_duration_us;

    /* If group supports more than 16kHz for the microphone in converstional
     * case let's use that also for Audio Framework.
     */
    std::optional<LeAudioCodecConfiguration> sink_configuration =
        group->GetCodecConfigurationByDirection(
            LeAudioContextType::CONVERSATIONAL,
            le_audio::types::kLeAudioDirectionSource);
    if (sink_configuration &&
        sink_configuration->sample_rate >
            bluetooth::audio::le_audio::kSampleRate16000) {
      audio_framework_sink_config.sample_rate = sink_configuration->sample_rate;
    }

    leAudioClientAudioSink->Start(audio_framework_sink_config,
                                  audioSourceReceiver);
  }

  void GroupSetActive(const int group_id) override {
    DLOG(INFO) << __func__ << " group_id: " << group_id;

    if (group_id == bluetooth::groups::kGroupUnknown) {
      if (active_group_id_ == bluetooth::groups::kGroupUnknown) {
        /* Nothing to do */
        return;
      }

      if (alarm_is_scheduled(suspend_timeout_)) alarm_cancel(suspend_timeout_);

      StopAudio();
      ClientAudioIntefraceRelease();

      GroupStop(active_group_id_);
      callbacks_->OnGroupStatus(active_group_id_, GroupStatus::INACTIVE);
      active_group_id_ = group_id;

      return;
    }

    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);
    if (!group) {
      LOG(ERROR) << __func__
                 << ", Invalid group: " << static_cast<int>(group_id);
      return;
    }

    if (active_group_id_ != bluetooth::groups::kGroupUnknown) {
      if (active_group_id_ == group_id) {
        LOG(INFO) << __func__ << ", Group is already active: "
                  << static_cast<int>(active_group_id_);
        callbacks_->OnGroupStatus(active_group_id_, GroupStatus::ACTIVE);
        return;
      }
      LOG(INFO) << __func__ << ", switching active group to: " << group_id;
    }

    if (!audio_source_instance_) {
      audio_source_instance_ = leAudioClientAudioSource->Acquire();
      if (!audio_source_instance_) {
        LOG(ERROR) << __func__ << ", could not acquire audio source interface";
        return;
      }
    }

    if (!audio_sink_instance_) {
      audio_sink_instance_ = leAudioClientAudioSink->Acquire();
      if (!audio_sink_instance_) {
        LOG(ERROR) << __func__ << ", could not acquire audio sink interface";
        leAudioClientAudioSource->Release(audio_source_instance_);
        return;
      }
    }

    /* Configure audio HAL sessions with most frequent context.
     * If reconfiguration is not needed it means, context type is not supported
     */
    UpdateConfigAndCheckIfReconfigurationIsNeeded(group_id,
                                                  LeAudioContextType::MEDIA);
    if (current_source_codec_config.IsInvalid() &&
        current_sink_codec_config.IsInvalid()) {
      LOG(WARNING) << __func__ << ", unsupported device configurations";
      return;
    }

    if (active_group_id_ == bluetooth::groups::kGroupUnknown) {
      /* Expose audio sessions if there was no previous active group */
      StartAudioSession(group, &current_source_codec_config,
                         &current_sink_codec_config);
    } else {
      /* In case there was an active group. Stop the stream */
      GroupStop(active_group_id_);
    }

    active_group_id_ = group_id;
    callbacks_->OnGroupStatus(active_group_id_, GroupStatus::ACTIVE);
  }

  void RemoveDevice(const RawAddress& address) override {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (!leAudioDevice) {
      return;
    }

    if (leAudioDevice->conn_id_ != GATT_INVALID_CONN_ID) {
      Disconnect(address);
      leAudioDevice->removing_device_ = true;
      return;
    }

    /* Remove the group assignment if not yet removed. It might happen that the
     * group module has already called the appropriate callback and we have
     * already removed the group assignment.
     */
    if (leAudioDevice->group_id_ != bluetooth::groups::kGroupUnknown) {
      auto group = aseGroups_.FindById(leAudioDevice->group_id_);
      group_remove_node(group, address, true);
    }

    leAudioDevices_.Remove(address);
  }

  void Connect(const RawAddress& address) override {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (!leAudioDevice) {
      leAudioDevices_.Add(address, true);
    } else {
      leAudioDevice->connecting_actively_ = true;

      le_audio::MetricsCollector::Get()->OnConnectionStateChanged(
          leAudioDevice->group_id_, address, ConnectionState::CONNECTING,
          le_audio::ConnectionStatus::SUCCESS);
    }

    BTA_GATTC_Open(gatt_if_, address, true, false);
  }

  std::vector<RawAddress> GetGroupDevices(const int group_id) override {
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);
    std::vector<RawAddress> all_group_device_addrs;

    if (group != nullptr) {
      LeAudioDevice* leAudioDevice = group->GetFirstDevice();
      while (leAudioDevice) {
        all_group_device_addrs.push_back(leAudioDevice->address_);
        leAudioDevice = group->GetNextDevice(leAudioDevice);
      };
    }

    return all_group_device_addrs;
  }

  /* Restore paired device from storage to recreate groups */
  void AddFromStorage(const RawAddress& address, bool autoconnect) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);

    LOG(INFO) << __func__ << ", restoring: " << address;

    if (!leAudioDevice) {
      leAudioDevices_.Add(address, false);
      leAudioDevice = leAudioDevices_.FindByAddress(address);
    }

    int group_id = DeviceGroups::Get()->GetGroupId(
        address, le_audio::uuid::kCapServiceUuid);
    if (group_id != bluetooth::groups::kGroupUnknown) {
      group_add_node(group_id, address);
    }

    if (autoconnect) {
      BTA_GATTC_Open(gatt_if_, address, false, false);
    }
  }

  void BackgroundConnectIfGroupConnected(LeAudioDevice* leAudioDevice) {
    DLOG(INFO) << __func__ << leAudioDevice->address_;
    auto group = aseGroups_.FindById(leAudioDevice->group_id_);
    if (!group) {
      DLOG(INFO) << __func__ << " Device is not yet part of the group. ";
      return;
    }

    if (!group->IsAnyDeviceConnected()) {
      DLOG(INFO) << __func__ << " group: " << leAudioDevice->group_id_
                 << " is not connected";
      return;
    }

    DLOG(INFO) << __func__ << "Add " << leAudioDevice->address_
               << " to background connect to connected group: "
               << leAudioDevice->group_id_;

    BTA_GATTC_Open(gatt_if_, leAudioDevice->address_, false, false);
  }

  void Disconnect(const RawAddress& address) override {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);

    if (!leAudioDevice) {
      LOG(ERROR) << __func__ << ", leAudioDevice not connected (" << address
                 << ")";
      return;
    }

    /* cancel pending direct connect */
    if (leAudioDevice->connecting_actively_) {
      BTA_GATTC_CancelOpen(gatt_if_, address, true);
      leAudioDevice->connecting_actively_ = false;
    }

    /* Removes all registrations for connection */
    BTA_GATTC_CancelOpen(0, address, false);

    if (leAudioDevice->conn_id_ != GATT_INVALID_CONN_ID) {
      /* User is disconnecting the device, we shall remove the autoconnect flag
       */
      btif_storage_set_leaudio_autoconnect(address, false);

      auto group = aseGroups_.FindById(leAudioDevice->group_id_);
      if (group &&
          group->GetState() ==
              le_audio::types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
        leAudioDevice->closing_stream_for_disconnection_ = true;
        groupStateMachine_->StopStream(group);
        return;
      }
      DisconnectDevice(leAudioDevice);
      return;
    }

    /* If this is a device which is a part of the group which is connected,
     * lets start backgroup connect
     */
    BackgroundConnectIfGroupConnected(leAudioDevice);
  }

  void DisconnectDevice(LeAudioDevice* leAudioDevice,
                        bool acl_force_disconnect = false) {
    if (leAudioDevice->conn_id_ == GATT_INVALID_CONN_ID) {
      return;
    }

    if (acl_force_disconnect) {
     leAudioDevice->DisconnectAcl();
     return;
    }

    BtaGattQueue::Clean(leAudioDevice->conn_id_);
    BTA_GATTC_Close(leAudioDevice->conn_id_);
    leAudioDevice->conn_id_ = GATT_INVALID_CONN_ID;
  }

  void DeregisterNotifications(LeAudioDevice* leAudioDevice) {
    /* GATTC will ommit not registered previously handles */
    for (auto pac_tuple : leAudioDevice->snk_pacs_) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, leAudioDevice->address_,
                                           std::get<0>(pac_tuple).val_hdl);
    }
    for (auto pac_tuple : leAudioDevice->src_pacs_) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, leAudioDevice->address_,
                                           std::get<0>(pac_tuple).val_hdl);
    }

    if (leAudioDevice->snk_audio_locations_hdls_.val_hdl != 0)
      BTA_GATTC_DeregisterForNotifications(
          gatt_if_, leAudioDevice->address_,
          leAudioDevice->snk_audio_locations_hdls_.val_hdl);
    if (leAudioDevice->src_audio_locations_hdls_.val_hdl != 0)
      BTA_GATTC_DeregisterForNotifications(
          gatt_if_, leAudioDevice->address_,
          leAudioDevice->src_audio_locations_hdls_.val_hdl);
    if (leAudioDevice->audio_avail_hdls_.val_hdl != 0)
      BTA_GATTC_DeregisterForNotifications(
          gatt_if_, leAudioDevice->address_,
          leAudioDevice->audio_avail_hdls_.val_hdl);
    if (leAudioDevice->audio_supp_cont_hdls_.val_hdl != 0)
      BTA_GATTC_DeregisterForNotifications(
          gatt_if_, leAudioDevice->address_,
          leAudioDevice->audio_supp_cont_hdls_.val_hdl);
    if (leAudioDevice->ctp_hdls_.val_hdl != 0)
      BTA_GATTC_DeregisterForNotifications(gatt_if_, leAudioDevice->address_,
                                           leAudioDevice->ctp_hdls_.val_hdl);

    for (struct ase& ase : leAudioDevice->ases_)
      BTA_GATTC_DeregisterForNotifications(gatt_if_, leAudioDevice->address_,
                                           ase.hdls.val_hdl);
  }

  /* This is a generic read/notify/indicate handler for gatt. Here messages
   * are dispatched to correct elements e.g. ASEs, PACs, audio locations etc.
   */
  void LeAudioCharValueHandle(uint16_t conn_id, uint16_t hdl, uint16_t len,
                              uint8_t* value) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByConnId(conn_id);
    struct ase* ase;

    if (!leAudioDevice) {
      LOG(ERROR) << __func__ << ", no leAudioDevice assigned to connection id: "
                 << static_cast<int>(conn_id);
      return;
    }

    ase = leAudioDevice->GetAseByValHandle(hdl);

    if (ase) {
      LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);
      groupStateMachine_->ProcessGattNotifEvent(value, len, ase, leAudioDevice,
                                                group);

      return;
    }

    auto snk_pac_ent = std::find_if(
        leAudioDevice->snk_pacs_.begin(), leAudioDevice->snk_pacs_.end(),
        [&hdl](auto& pac_ent) { return std::get<0>(pac_ent).val_hdl == hdl; });
    if (snk_pac_ent != leAudioDevice->snk_pacs_.end()) {
      std::vector<struct le_audio::types::acs_ac_record> pac_recs;

      /* Guard consistency of PAC records structure */
      if (!le_audio::client_parser::pacs::ParsePac(pac_recs, len, value))
        return;

      LOG(INFO) << __func__ << ", Registering sink PACs";
      leAudioDevice->RegisterPACs(&std::get<1>(*snk_pac_ent), &pac_recs);

      /* Update supported context types including internal capabilities */
      LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);

      /* Active context map should be considered to be updated in response to
       * PACs update.
       * Read of available context during initial attribute discovery.
       * Group would be assigned once service search is completed.
       */
      if (group)
        group->UpdateActiveContextsMap(leAudioDevice->GetAvailableContexts());

      return;
    }

    auto src_pac_ent = std::find_if(
        leAudioDevice->src_pacs_.begin(), leAudioDevice->src_pacs_.end(),
        [&hdl](auto& pac_ent) { return std::get<0>(pac_ent).val_hdl == hdl; });
    if (src_pac_ent != leAudioDevice->src_pacs_.end()) {
      std::vector<struct le_audio::types::acs_ac_record> pac_recs;

      /* Guard consistency of PAC records structure */
      if (!le_audio::client_parser::pacs::ParsePac(pac_recs, len, value))
        return;

      LOG(INFO) << __func__ << ", Registering source PACs";
      leAudioDevice->RegisterPACs(&std::get<1>(*src_pac_ent), &pac_recs);

      /* Update supported context types including internal capabilities */
      LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);

      /* Active context map should be considered to be updated in response to
       * PACs update.
       * Read of available context during initial attribute discovery.
       * Group would be assigned once service search is completed.
       */
      if (group)
        group->UpdateActiveContextsMap(leAudioDevice->GetAvailableContexts());

      return;
    }

    if (hdl == leAudioDevice->snk_audio_locations_hdls_.val_hdl) {
      AudioLocations snk_audio_locations;

      le_audio::client_parser::pacs::ParseAudioLocations(snk_audio_locations,
                                                         len, value);

      /* Value may not change */
      if ((leAudioDevice->audio_directions_ &
           le_audio::types::kLeAudioDirectionSink) &&
          (leAudioDevice->snk_audio_locations_ ^ snk_audio_locations).none())
        return;

      /* Presence of PAC characteristic for source means support for source
       * audio location. Value of 0x00000000 means mono/unspecified
       */
      leAudioDevice->audio_directions_ |=
          le_audio::types::kLeAudioDirectionSink;
      leAudioDevice->snk_audio_locations_ = snk_audio_locations;

      LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);
      callbacks_->OnSinkAudioLocationAvailable(leAudioDevice->address_,
                                               snk_audio_locations.to_ulong());
      /* Read of source audio locations during initial attribute discovery.
       * Group would be assigned once service search is completed.
       */
      if (group && group->ReloadAudioLocations()) {
        callbacks_->OnAudioConf(group->audio_directions_, group->group_id_,
                                group->snk_audio_locations_.to_ulong(),
                                group->src_audio_locations_.to_ulong(),
                                group->GetActiveContexts().to_ulong());
      }
    } else if (hdl == leAudioDevice->src_audio_locations_hdls_.val_hdl) {
      AudioLocations src_audio_locations;

      le_audio::client_parser::pacs::ParseAudioLocations(src_audio_locations,
                                                         len, value);

      /* Value may not change */
      if ((leAudioDevice->audio_directions_ &
           le_audio::types::kLeAudioDirectionSource) &&
          (leAudioDevice->src_audio_locations_ ^ src_audio_locations).none())
        return;

      /* Presence of PAC characteristic for source means support for source
       * audio location. Value of 0x00000000 means mono/unspecified
       */
      leAudioDevice->audio_directions_ |=
          le_audio::types::kLeAudioDirectionSource;
      leAudioDevice->src_audio_locations_ = src_audio_locations;

      LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);
      /* Read of source audio locations during initial attribute discovery.
       * Group would be assigned once service search is completed.
       */
      if (group && group->ReloadAudioLocations()) {
        callbacks_->OnAudioConf(group->audio_directions_, group->group_id_,
                                group->snk_audio_locations_.to_ulong(),
                                group->src_audio_locations_.to_ulong(),
                                group->GetActiveContexts().to_ulong());
      }
    } else if (hdl == leAudioDevice->audio_avail_hdls_.val_hdl) {
      auto avail_audio_contexts = std::make_unique<
          struct le_audio::client_parser::pacs::acs_available_audio_contexts>();

      le_audio::client_parser::pacs::ParseAvailableAudioContexts(
          *avail_audio_contexts, len, value);

      auto updated_avail_contexts = leAudioDevice->SetAvailableContexts(
          avail_audio_contexts->snk_avail_cont,
          avail_audio_contexts->src_avail_cont);

      if (updated_avail_contexts.any()) {
        /* Update scenario map considering changed active context types */
        LeAudioDeviceGroup* group =
            aseGroups_.FindById(leAudioDevice->group_id_);
        /* Read of available context during initial attribute discovery.
         * Group would be assigned once service search is completed.
         */
        if (group) {
          /* Update of available context may happen during state transition
           * or while streaming. Don't bother current transition or streaming
           * process. Update configuration once group became idle.
           */
          if (group->IsInTransition() ||
              (group->GetState() ==
               AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING)) {
            group->SetPendingUpdateAvailableContexts(updated_avail_contexts);
            return;
          }

          std::optional<AudioContexts> updated_contexts =
              group->UpdateActiveContextsMap(updated_avail_contexts);
          if (updated_contexts) {
            callbacks_->OnAudioConf(group->audio_directions_, group->group_id_,
                                    group->snk_audio_locations_.to_ulong(),
                                    group->src_audio_locations_.to_ulong(),
                                    group->GetActiveContexts().to_ulong());
          }
        }
      }
    } else if (hdl == leAudioDevice->audio_supp_cont_hdls_.val_hdl) {
      auto supp_audio_contexts = std::make_unique<
          struct le_audio::client_parser::pacs::acs_supported_audio_contexts>();

      le_audio::client_parser::pacs::ParseSupportedAudioContexts(
          *supp_audio_contexts, len, value);
      /* Just store if for now */
      leAudioDevice->SetSupportedContexts(supp_audio_contexts->snk_supp_cont,
                                          supp_audio_contexts->src_supp_cont);
    } else if (hdl == leAudioDevice->ctp_hdls_.val_hdl) {
      auto ntf =
          std::make_unique<struct le_audio::client_parser::ascs::ctp_ntf>();

      if (ParseAseCtpNotification(*ntf, len, value))
        ControlPointNotificationHandler(*ntf);
    } else {
      LOG(ERROR) << __func__ << ", Unknown attribute read: " << loghex(hdl);
    }
  }

  void OnGattReadRsp(uint16_t conn_id, tGATT_STATUS status, uint16_t hdl,
                     uint16_t len, uint8_t* value, void* data) {
    LeAudioCharValueHandle(conn_id, hdl, len, value);
  }

  void OnGattConnected(tGATT_STATUS status, uint16_t conn_id,
                       tGATT_IF client_if, RawAddress address,
                       tBT_TRANSPORT transport, uint16_t mtu) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);

    if (!leAudioDevice) return;

    if (status != GATT_SUCCESS) {
      /* autoconnect connection failed, that's ok */
      if (!leAudioDevice->connecting_actively_) return;

      LOG(ERROR) << "Failed to connect to LeAudio leAudioDevice, status: "
                 << +status;
      callbacks_->OnConnectionState(ConnectionState::DISCONNECTED, address);
      le_audio::MetricsCollector::Get()->OnConnectionStateChanged(
          leAudioDevice->group_id_, address, ConnectionState::CONNECTED,
          le_audio::ConnectionStatus::FAILED);
      return;
    }

    if (controller_get_interface()->supports_ble_2m_phy()) {
      LOG(INFO) << address << " set preferred PHY to 2M";
      BTM_BleSetPhy(address, PHY_LE_2M, PHY_LE_2M, 0);
    }

    BTM_RequestPeerSCA(leAudioDevice->address_, transport);

    leAudioDevice->connecting_actively_ = false;
    leAudioDevice->conn_id_ = conn_id;

    if (mtu == GATT_DEF_BLE_MTU_SIZE) {
      LOG(INFO) << __func__ << ", Configure MTU";
      BtaGattQueue::ConfigureMtu(leAudioDevice->conn_id_, 240);
    }

    /* If we know services, register for notifications */
    if (leAudioDevice->known_service_handles_)
      RegisterKnownNotifications(leAudioDevice);

    if (BTM_SecIsSecurityPending(address)) {
      /* if security collision happened, wait for encryption done
       * (BTA_GATTC_ENC_CMPL_CB_EVT) */
      return;
    }

    /* verify bond */
    if (BTM_IsEncrypted(address, BT_TRANSPORT_LE)) {
      /* if link has been encrypted */
      OnEncryptionComplete(address, BTM_SUCCESS);
      return;
    }

    if (BTM_IsLinkKeyKnown(address, BT_TRANSPORT_LE)) {
      int result = BTM_SetEncryption(
          address, BT_TRANSPORT_LE,
          [](const RawAddress* bd_addr, tBT_TRANSPORT transport,
             void* p_ref_data, tBTM_STATUS status) {
            if (instance) instance->OnEncryptionComplete(*bd_addr, status);
          },
          nullptr, BTM_BLE_SEC_ENCRYPT);

      LOG(INFO) << __func__
                << "Encryption required. Request result: " << result;
      return;
    }

    LOG(ERROR) << __func__ << " Encryption error";
    le_audio::MetricsCollector::Get()->OnConnectionStateChanged(
        leAudioDevice->group_id_, address, ConnectionState::CONNECTED,
        le_audio::ConnectionStatus::FAILED);
  }

  void RegisterKnownNotifications(LeAudioDevice* leAudioDevice) {
    LOG(INFO) << __func__ << " device: " << leAudioDevice->address_;

    /* GATTC will ommit not registered previously handles */
    for (auto pac_tuple : leAudioDevice->snk_pacs_) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, leAudioDevice->address_,
                                         std::get<0>(pac_tuple).val_hdl);
    }
    for (auto pac_tuple : leAudioDevice->src_pacs_) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, leAudioDevice->address_,
                                         std::get<0>(pac_tuple).val_hdl);
    }

    if (leAudioDevice->snk_audio_locations_hdls_.val_hdl != 0)
      BTA_GATTC_RegisterForNotifications(
          gatt_if_, leAudioDevice->address_,
          leAudioDevice->snk_audio_locations_hdls_.val_hdl);
    if (leAudioDevice->src_audio_locations_hdls_.val_hdl != 0)
      BTA_GATTC_RegisterForNotifications(
          gatt_if_, leAudioDevice->address_,
          leAudioDevice->src_audio_locations_hdls_.val_hdl);
    if (leAudioDevice->audio_avail_hdls_.val_hdl != 0)
      BTA_GATTC_RegisterForNotifications(
          gatt_if_, leAudioDevice->address_,
          leAudioDevice->audio_avail_hdls_.val_hdl);
    if (leAudioDevice->audio_supp_cont_hdls_.val_hdl != 0)
      BTA_GATTC_RegisterForNotifications(
          gatt_if_, leAudioDevice->address_,
          leAudioDevice->audio_supp_cont_hdls_.val_hdl);
    if (leAudioDevice->ctp_hdls_.val_hdl != 0)
      BTA_GATTC_RegisterForNotifications(gatt_if_, leAudioDevice->address_,
                                         leAudioDevice->ctp_hdls_.val_hdl);

    for (struct ase& ase : leAudioDevice->ases_)
      BTA_GATTC_RegisterForNotifications(gatt_if_, leAudioDevice->address_,
                                         ase.hdls.val_hdl);
  }

  void OnEncryptionComplete(const RawAddress& address, uint8_t status) {
    LOG(INFO) << __func__ << " " << address << "status: " << int{status};

    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (leAudioDevice == NULL) {
      LOG(WARNING) << "Skipping unknown device" << address;
      return;
    }

    if (status != BTM_SUCCESS) {
      LOG(ERROR) << "Encryption failed"
                 << " status: " << int{status};
      BTA_GATTC_Close(leAudioDevice->conn_id_);
      if (leAudioDevice->connecting_actively_) {
        callbacks_->OnConnectionState(ConnectionState::DISCONNECTED, address);
        le_audio::MetricsCollector::Get()->OnConnectionStateChanged(
            leAudioDevice->group_id_, address, ConnectionState::CONNECTED,
            le_audio::ConnectionStatus::FAILED);
      }
      return;
    }

    if (leAudioDevice->encrypted_) {
      LOG(INFO) << __func__ << " link already encrypted, nothing to do";
      return;
    }

    leAudioDevice->encrypted_ = true;

    /* If we know services and read is not ongoing, this is reconnection and
     * just notify connected  */
    if (leAudioDevice->known_service_handles_ &&
        !leAudioDevice->notify_connected_after_read_) {
      connectionReady(leAudioDevice);
      return;
    }

    BTA_GATTC_ServiceSearchRequest(
        leAudioDevice->conn_id_,
        &le_audio::uuid::kPublishedAudioCapabilityServiceUuid);
  }

  void OnGattDisconnected(uint16_t conn_id, tGATT_IF client_if,
                          RawAddress address, tGATT_DISCONN_REASON reason) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);

    if (!leAudioDevice) {
      LOG(ERROR) << ", skipping unknown leAudioDevice, address: " << address;
      return;
    }

    LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);

    groupStateMachine_->ProcessHciNotifAclDisconnected(group, leAudioDevice);

    DeregisterNotifications(leAudioDevice);

    callbacks_->OnConnectionState(ConnectionState::DISCONNECTED, address);
    leAudioDevice->conn_id_ = GATT_INVALID_CONN_ID;
    leAudioDevice->closing_stream_for_disconnection_ = false;
    leAudioDevice->encrypted_ = false;

    le_audio::MetricsCollector::Get()->OnConnectionStateChanged(
        leAudioDevice->group_id_, address, ConnectionState::DISCONNECTED,
        le_audio::ConnectionStatus::SUCCESS);

    if (leAudioDevice->removing_device_) {
      if (leAudioDevice->group_id_ != bluetooth::groups::kGroupUnknown) {
        auto group = aseGroups_.FindById(leAudioDevice->group_id_);
        group_remove_node(group, address, true);
      }
      leAudioDevices_.Remove(address);
      return;
    }
    /* Attempt background re-connect if disconnect was not intended locally */
    if (reason != GATT_CONN_TERMINATE_LOCAL_HOST) {
      BTA_GATTC_Open(gatt_if_, address, false, false);
    }
  }

  bool subscribe_for_indications(uint16_t conn_id, const RawAddress& address,
                                 uint16_t handle, uint16_t ccc_handle,
                                 bool ntf) {
    std::vector<uint8_t> value(2);
    uint8_t* ptr = value.data();

    if (BTA_GATTC_RegisterForNotifications(gatt_if_, address, handle) !=
        GATT_SUCCESS) {
      LOG(ERROR) << __func__ << ", cannot register for notification: "
                 << static_cast<int>(handle);
      return false;
    }

    UINT16_TO_STREAM(ptr, ntf ? GATT_CHAR_CLIENT_CONFIG_NOTIFICATION
                              : GATT_CHAR_CLIENT_CONFIG_INDICTION);

    BtaGattQueue::WriteDescriptor(
        conn_id, ccc_handle, std::move(value), GATT_WRITE,
        [](uint16_t conn_id, tGATT_STATUS status, uint16_t handle, uint16_t len,
           const uint8_t* value, void* data) {
          if (instance) instance->OnGattWriteCcc(conn_id, status, handle, data);
        },
        nullptr);
    return true;
  }

  /* Find the handle for the client characteristics configuration of a given
   * characteristics.
   */
  uint16_t find_ccc_handle(const gatt::Characteristic& charac) {
    auto iter = std::find_if(
        charac.descriptors.begin(), charac.descriptors.end(),
        [](const auto& desc) {
          return desc.uuid == Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG);
        });

    return iter == charac.descriptors.end() ? 0 : (*iter).handle;
  }

  void OnServiceChangeEvent(const RawAddress& address) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (!leAudioDevice) {
      DLOG(ERROR) << __func__
                  << ", skipping unknown leAudioDevice, address: " << address;
      return;
    }

    LOG(INFO) << __func__ << ": address=" << address;
    leAudioDevice->known_service_handles_ = false;
    leAudioDevice->csis_member_ = false;
    BtaGattQueue::Clean(leAudioDevice->conn_id_);
    DeregisterNotifications(leAudioDevice);
  }

  void OnGattServiceDiscoveryDone(const RawAddress& address) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (!leAudioDevice) {
      DLOG(ERROR) << __func__
                  << ", skipping unknown leAudioDevice, address: " << address;
      return;
    }

    if (!leAudioDevice->known_service_handles_)
      BTA_GATTC_ServiceSearchRequest(
          leAudioDevice->conn_id_,
          &le_audio::uuid::kPublishedAudioCapabilityServiceUuid);
  }
  /* This method is called after connection beginning to identify and initialize
   * a le audio device. Any missing mandatory attribute will result in reverting
   * and cleaning up device.
   */
  void OnServiceSearchComplete(uint16_t conn_id, tGATT_STATUS status) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByConnId(conn_id);

    if (!leAudioDevice) {
      DLOG(ERROR) << __func__ << ", skipping unknown leAudioDevice, conn_id: "
                  << loghex(conn_id);
      return;
    }

    LOG(INFO) << __func__ << " test csis_member "
              << leAudioDevice->csis_member_;

    if (status != GATT_SUCCESS) {
      /* close connection and report service discovery complete with error */
      LOG(ERROR) << "Service discovery failed";

      DisconnectDevice(leAudioDevice);
      return;
    }

    const std::list<gatt::Service>* services = BTA_GATTC_GetServices(conn_id);

    const gatt::Service* pac_svc = nullptr;
    const gatt::Service* ase_svc = nullptr;

    std::vector<uint16_t> csis_primary_handles;
    uint16_t cas_csis_included_handle = 0;

    for (const gatt::Service& tmp : *services) {
      if (tmp.uuid == le_audio::uuid::kPublishedAudioCapabilityServiceUuid) {
        LOG(INFO) << "Found Audio Capability service, handle: "
                  << loghex(tmp.handle);
        pac_svc = &tmp;
      } else if (tmp.uuid == le_audio::uuid::kAudioStreamControlServiceUuid) {
        LOG(INFO) << "Found Audio Stream Endpoint service, handle: "
                  << loghex(tmp.handle);
        ase_svc = &tmp;
      } else if (tmp.uuid == bluetooth::csis::kCsisServiceUuid) {
        LOG(INFO) << "Found CSIS service, handle: " << loghex(tmp.handle)
                  << " is primary? " << tmp.is_primary;
        if (tmp.is_primary) csis_primary_handles.push_back(tmp.handle);
      } else if (tmp.uuid == le_audio::uuid::kCapServiceUuid) {
        LOG(INFO) << "Found CAP Service, handle: " << loghex(tmp.handle);

        /* Try to find context for CSIS instances */
        for (auto& included_srvc : tmp.included_services) {
          if (included_srvc.uuid == bluetooth::csis::kCsisServiceUuid) {
            LOG(INFO) << __func__ << " CSIS included into CAS";
            if (bluetooth::csis::CsisClient::IsCsisClientRunning())
              cas_csis_included_handle = included_srvc.start_handle;

            break;
          }
        }
      }
    }

    /* Check if CAS includes primary CSIS service */
    if (!csis_primary_handles.empty() && cas_csis_included_handle) {
      auto iter =
          std::find(csis_primary_handles.begin(), csis_primary_handles.end(),
                    cas_csis_included_handle);
      if (iter != csis_primary_handles.end())
        leAudioDevice->csis_member_ = true;
    }

    if (!pac_svc || !ase_svc) {
      LOG(ERROR) << "No mandatory le audio services found";

      DisconnectDevice(leAudioDevice);
      return;
    }

    /* Refresh PACs handles */
    leAudioDevice->ClearPACs();

    for (const gatt::Characteristic& charac : pac_svc->characteristics) {
      if (charac.uuid ==
          le_audio::uuid::kSinkPublishedAudioCapabilityCharacteristicUuid) {
        struct hdl_pair hdl_pair;
        hdl_pair.val_hdl = charac.value_handle;
        hdl_pair.ccc_hdl = find_ccc_handle(charac);

        if (hdl_pair.ccc_hdl == 0) {
          LOG(ERROR) << __func__ << ", snk pac char doesn't have ccc";

          DisconnectDevice(leAudioDevice);
          return;
        }

        if (!subscribe_for_indications(conn_id, leAudioDevice->address_,
                                       hdl_pair.val_hdl, hdl_pair.ccc_hdl,
                                       true)) {
          DisconnectDevice(leAudioDevice);
          return;
        }

        /* Obtain initial state of sink PACs */
        BtaGattQueue::ReadCharacteristic(conn_id, hdl_pair.val_hdl,
                                         OnGattReadRspStatic, NULL);

        leAudioDevice->snk_pacs_.push_back(std::make_tuple(
            hdl_pair, std::vector<struct le_audio::types::acs_ac_record>()));

        LOG(INFO) << "Found Sink PAC characteristic, handle: "
                  << loghex(charac.value_handle)
                  << ", ccc handle: " << loghex(hdl_pair.ccc_hdl);
      } else if (charac.uuid ==
                 le_audio::uuid::
                     kSourcePublishedAudioCapabilityCharacteristicUuid) {
        struct hdl_pair hdl_pair;
        hdl_pair.val_hdl = charac.value_handle;
        hdl_pair.ccc_hdl = find_ccc_handle(charac);

        if (hdl_pair.ccc_hdl == 0) {
          LOG(ERROR) << __func__ << ", src pac char doesn't have ccc";

          DisconnectDevice(leAudioDevice);
          return;
        }

        if (!subscribe_for_indications(conn_id, leAudioDevice->address_,
                                       hdl_pair.val_hdl, hdl_pair.ccc_hdl,
                                       true)) {
          DisconnectDevice(leAudioDevice);
          return;
        }

        /* Obtain initial state of source PACs */
        BtaGattQueue::ReadCharacteristic(conn_id, hdl_pair.val_hdl,
                                         OnGattReadRspStatic, NULL);

        leAudioDevice->src_pacs_.push_back(std::make_tuple(
            hdl_pair, std::vector<struct le_audio::types::acs_ac_record>()));

        LOG(INFO) << "Found Source PAC characteristic, handle: "
                  << loghex(charac.value_handle)
                  << ", ccc handle: " << loghex(hdl_pair.ccc_hdl);
      } else if (charac.uuid ==
                 le_audio::uuid::kSinkAudioLocationCharacteristicUuid) {
        leAudioDevice->snk_audio_locations_hdls_.val_hdl = charac.value_handle;
        leAudioDevice->snk_audio_locations_hdls_.ccc_hdl =
            find_ccc_handle(charac);

        if (leAudioDevice->snk_audio_locations_hdls_.ccc_hdl == 0)
          LOG(INFO) << __func__
                    << ", snk audio locations char doesn't have"
                       "ccc";

        if (leAudioDevice->snk_audio_locations_hdls_.ccc_hdl != 0 &&
            !subscribe_for_indications(
                conn_id, leAudioDevice->address_,
                leAudioDevice->snk_audio_locations_hdls_.val_hdl,
                leAudioDevice->snk_audio_locations_hdls_.ccc_hdl, true)) {
          DisconnectDevice(leAudioDevice);
          return;
        }

        /* Obtain initial state of sink audio locations */
        BtaGattQueue::ReadCharacteristic(
            conn_id, leAudioDevice->snk_audio_locations_hdls_.val_hdl,
            OnGattReadRspStatic, NULL);

        LOG(INFO) << "Found Sink audio locations characteristic, handle: "
                  << loghex(charac.value_handle) << ", ccc handle: "
                  << loghex(leAudioDevice->snk_audio_locations_hdls_.ccc_hdl);
      } else if (charac.uuid ==
                 le_audio::uuid::kSourceAudioLocationCharacteristicUuid) {
        leAudioDevice->src_audio_locations_hdls_.val_hdl = charac.value_handle;
        leAudioDevice->src_audio_locations_hdls_.ccc_hdl =
            find_ccc_handle(charac);

        if (leAudioDevice->src_audio_locations_hdls_.ccc_hdl == 0)
          LOG(INFO) << __func__
                    << ", snk audio locations char doesn't have"
                       "ccc";

        if (leAudioDevice->src_audio_locations_hdls_.ccc_hdl != 0 &&
            !subscribe_for_indications(
                conn_id, leAudioDevice->address_,
                leAudioDevice->src_audio_locations_hdls_.val_hdl,
                leAudioDevice->src_audio_locations_hdls_.ccc_hdl, true)) {
          DisconnectDevice(leAudioDevice);
          return;
        }

        /* Obtain initial state of source audio locations */
        BtaGattQueue::ReadCharacteristic(
            conn_id, leAudioDevice->src_audio_locations_hdls_.val_hdl,
            OnGattReadRspStatic, NULL);

        LOG(INFO) << "Found Source audio locations characteristic, handle: "
                  << loghex(charac.value_handle) << ", ccc handle: "
                  << loghex(leAudioDevice->src_audio_locations_hdls_.ccc_hdl);
      } else if (charac.uuid ==
                 le_audio::uuid::kAudioContextAvailabilityCharacteristicUuid) {
        leAudioDevice->audio_avail_hdls_.val_hdl = charac.value_handle;
        leAudioDevice->audio_avail_hdls_.ccc_hdl = find_ccc_handle(charac);

        if (leAudioDevice->audio_avail_hdls_.ccc_hdl == 0) {
          LOG(ERROR) << __func__ << ", audio avails char doesn't have ccc";

          DisconnectDevice(leAudioDevice);
          return;
        }

        if (!subscribe_for_indications(conn_id, leAudioDevice->address_,
                                       leAudioDevice->audio_avail_hdls_.val_hdl,
                                       leAudioDevice->audio_avail_hdls_.ccc_hdl,
                                       true)) {
          DisconnectDevice(leAudioDevice);
          return;
        }

        /* Obtain initial state */
        BtaGattQueue::ReadCharacteristic(
            conn_id, leAudioDevice->audio_avail_hdls_.val_hdl,
            OnGattReadRspStatic, NULL);

        LOG(INFO) << "Found Audio Availability Context characteristic, handle: "
                  << loghex(charac.value_handle) << ", ccc handle: "
                  << loghex(leAudioDevice->audio_avail_hdls_.ccc_hdl);
      } else if (charac.uuid ==
                 le_audio::uuid::kAudioSupportedContextCharacteristicUuid) {
        leAudioDevice->audio_supp_cont_hdls_.val_hdl = charac.value_handle;
        leAudioDevice->audio_supp_cont_hdls_.ccc_hdl = find_ccc_handle(charac);

        if (leAudioDevice->audio_supp_cont_hdls_.ccc_hdl == 0)
          LOG(INFO) << __func__ << ", audio avails char doesn't have ccc";

        if (leAudioDevice->audio_supp_cont_hdls_.ccc_hdl != 0 &&
            !subscribe_for_indications(
                conn_id, leAudioDevice->address_,
                leAudioDevice->audio_supp_cont_hdls_.val_hdl,
                leAudioDevice->audio_supp_cont_hdls_.ccc_hdl, true)) {
          DisconnectDevice(leAudioDevice);
          return;
        }

        /* Obtain initial state */
        BtaGattQueue::ReadCharacteristic(
            conn_id, leAudioDevice->audio_supp_cont_hdls_.val_hdl,
            OnGattReadRspStatic, NULL);

        LOG(INFO) << "Found Audio Supported Context characteristic, handle: "
                  << loghex(charac.value_handle) << ", ccc handle: "
                  << loghex(leAudioDevice->audio_supp_cont_hdls_.ccc_hdl);
      }
    }

    /* Refresh ASE handles */
    leAudioDevice->ases_.clear();

    for (const gatt::Characteristic& charac : ase_svc->characteristics) {
      LOG(INFO) << "Found characteristic, uuid: " << charac.uuid.ToString();
      if (charac.uuid == le_audio::uuid::kSinkAudioStreamEndpointUuid ||
          charac.uuid == le_audio::uuid::kSourceAudioStreamEndpointUuid) {
        uint16_t ccc_handle = find_ccc_handle(charac);
        if (ccc_handle == 0) {
          LOG(ERROR) << __func__ << ", audio avails char doesn't have ccc";

          DisconnectDevice(leAudioDevice);
          return;
        }

        if (!subscribe_for_indications(conn_id, leAudioDevice->address_,
                                       charac.value_handle, ccc_handle, true)) {
          DisconnectDevice(leAudioDevice);
          return;
        }

        int direction =
            charac.uuid == le_audio::uuid::kSinkAudioStreamEndpointUuid
                ? le_audio::types::kLeAudioDirectionSink
                : le_audio::types::kLeAudioDirectionSource;

        leAudioDevice->ases_.emplace_back(charac.value_handle, ccc_handle,
                                          direction);

        LOG(INFO) << "Found ASE characteristic, handle: "
                  << loghex(charac.value_handle)
                  << ", ccc handle: " << loghex(ccc_handle)
                  << ", direction: " << direction;
      } else if (charac.uuid ==
                 le_audio::uuid::
                     kAudioStreamEndpointControlPointCharacteristicUuid) {
        leAudioDevice->ctp_hdls_.val_hdl = charac.value_handle;
        leAudioDevice->ctp_hdls_.ccc_hdl = find_ccc_handle(charac);

        if (leAudioDevice->ctp_hdls_.ccc_hdl == 0) {
          LOG(ERROR) << __func__ << ", ase ctp doesn't have ccc";

          DisconnectDevice(leAudioDevice);
          return;
        }

        if (!subscribe_for_indications(conn_id, leAudioDevice->address_,
                                       leAudioDevice->ctp_hdls_.val_hdl,
                                       leAudioDevice->ctp_hdls_.ccc_hdl,
                                       true)) {
          DisconnectDevice(leAudioDevice);
          return;
        }

        LOG(INFO) << "Found ASE Control Point characteristic, handle: "
                  << loghex(charac.value_handle) << ", ccc handle: "
                  << loghex(leAudioDevice->ctp_hdls_.ccc_hdl);
      }
    }

    leAudioDevice->known_service_handles_ = true;
    leAudioDevice->notify_connected_after_read_ = true;

    /* If already known group id */
    if (leAudioDevice->group_id_ != bluetooth::groups::kGroupUnknown) {
      AseInitialStateReadRequest(leAudioDevice);
      return;
    }

    /* If device does not belong to any group yet we either add it to the
     * group by our selfs now or wait for Csis to do it. In both cases, let's
     * check if group is already assigned.
     */
    int group_id = DeviceGroups::Get()->GetGroupId(
        leAudioDevice->address_, le_audio::uuid::kCapServiceUuid);
    if (group_id != bluetooth::groups::kGroupUnknown) {
      instance->group_add_node(group_id, leAudioDevice->address_);
      return;
    }

    /* CSIS will trigger adding to group */
    if (leAudioDevice->csis_member_) {
      LOG(INFO) << __func__ << " waiting for CSIS to create group for device "
                << leAudioDevice->address_;
      return;
    }

    /* If there is no Csis just add device by our own */
    DeviceGroups::Get()->AddDevice(leAudioDevice->address_,
                                   le_audio::uuid::kCapServiceUuid);
  }

  void OnGattWriteCcc(uint16_t conn_id, tGATT_STATUS status, uint16_t hdl,
                      void* data) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByConnId(conn_id);
    std::vector<struct ase>::iterator ase_it;

    if (!leAudioDevice) {
      LOG(ERROR) << __func__ << ", unknown conn_id=" << loghex(conn_id);
      return;
    }

    if (status == GATT_SUCCESS) {
      LOG(INFO) << __func__
                << ", successfully registered on ccc: " << loghex(hdl);
      return;
    }

    LOG(ERROR) << __func__
               << ", Failed to register for indications: " << loghex(hdl)
               << ", status: " << loghex((int)(status));

    ase_it =
        std::find_if(leAudioDevice->ases_.begin(), leAudioDevice->ases_.end(),
                     [&hdl](const struct ase& ase) -> bool {
                       return ase.hdls.ccc_hdl == hdl;
                     });

    if (ase_it == leAudioDevice->ases_.end()) {
      LOG(ERROR) << __func__
                 << ", unknown ccc handle: " << static_cast<int>(hdl);
      return;
    }

    BTA_GATTC_DeregisterForNotifications(gatt_if_, leAudioDevice->address_,
                                         ase_it->hdls.val_hdl);
  }

  void AttachToStreamingGroupIfNeeded(LeAudioDevice* leAudioDevice) {
    if (leAudioDevice->group_id_ != active_group_id_) {
      LOG(INFO) << __func__ << " group  " << leAudioDevice->group_id_
                << " is not streaming. Nothing to do";
      return;
    }

    LOG_INFO("Attaching to group: %d", leAudioDevice->group_id_);

    /* Restore configuration */
    LeAudioDeviceGroup* group = aseGroups_.FindById(active_group_id_);
    auto* stream_conf = &group->stream_conf;

    if (audio_sender_state_ == AudioState::IDLE &&
        audio_receiver_state_ == AudioState::IDLE) {
      DLOG(INFO) << __func__
                 << " Device not streaming but active - nothing to do";
      return;
    }

    auto num_of_devices =
        get_num_of_devices_in_configuration(stream_conf->conf);

    if (num_of_devices < group->NumOfConnected()) {
      /* Second device got just paired. We need to reconfigure CIG */
      group->SetPendingConfiguration();
      groupStateMachine_->StopStream(group);
      return;
    }

    /* Second device got reconnect. Try to get it to the stream seamlessly */
    le_audio::types::AudioLocations sink_group_audio_locations = 0;
    uint8_t sink_num_of_active_ases = 0;

    for (auto [cis_handle, audio_location] : stream_conf->sink_streams) {
      sink_group_audio_locations |= audio_location;
      sink_num_of_active_ases++;
    }

    le_audio::types::AudioLocations source_group_audio_locations = 0;
    uint8_t source_num_of_active_ases = 0;

    for (auto [cis_handle, audio_location] : stream_conf->source_streams) {
      source_group_audio_locations |= audio_location;
      source_num_of_active_ases++;
    }

    for (auto& ent : stream_conf->conf->confs) {
      if (ent.direction == le_audio::types::kLeAudioDirectionSink) {
        /* Sink*/
        if (!leAudioDevice->ConfigureAses(ent, group->GetCurrentContextType(),
                                          &sink_num_of_active_ases,
                                          sink_group_audio_locations,
                                          source_group_audio_locations, true)) {
          LOG(INFO) << __func__ << " Could not set sink configuration of "
                    << stream_conf->conf->name;
          return;
        }
      } else {
        /* Source*/
        if (!leAudioDevice->ConfigureAses(ent, group->GetCurrentContextType(),
                                          &source_num_of_active_ases,
                                          sink_group_audio_locations,
                                          source_group_audio_locations, true)) {
          LOG(INFO) << __func__ << " Could not set source configuration of "
                    << stream_conf->conf->name;
          return;
        }
      }
    }

    groupStateMachine_->AttachToStream(group, leAudioDevice);
  }

  void connectionReady(LeAudioDevice* leAudioDevice) {
    callbacks_->OnConnectionState(ConnectionState::CONNECTED,
                                  leAudioDevice->address_);

    if (leAudioDevice->group_id_ != bluetooth::groups::kGroupUnknown) {
      LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);
      UpdateContextAndLocations(group, leAudioDevice);
      AttachToStreamingGroupIfNeeded(leAudioDevice);
    }

    if (leAudioDevice->first_connection_) {
      btif_storage_set_leaudio_autoconnect(leAudioDevice->address_, true);
      leAudioDevice->first_connection_ = false;
    }
    le_audio::MetricsCollector::Get()->OnConnectionStateChanged(
        leAudioDevice->group_id_, leAudioDevice->address_,
        ConnectionState::CONNECTED, le_audio::ConnectionStatus::SUCCESS);
  }

  bool IsAseAcceptingAudioData(struct ase* ase) {
    if (ase == nullptr) return false;
    if (ase->state != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) return false;
    if (ase->data_path_state != AudioStreamDataPathState::DATA_PATH_ESTABLISHED)
      return false;

    return true;
  }

  // mix stero signal into mono
  std::vector<uint8_t> mono_blend(const std::vector<uint8_t>& buf,
                                  int bytes_per_sample, size_t frames) {
    std::vector<uint8_t> mono_out;
    mono_out.resize(frames * bytes_per_sample);

    if (bytes_per_sample == 2) {
      int16_t* out = (int16_t*)mono_out.data();
      for (size_t i = 0; i < frames; ++i) {
        const int16_t* in = (int16_t*)(buf.data());
        int accum = 0;
        accum += *in++;
        accum += *in++;
        accum /= 2;  // round to 0
        *out++ = accum;
      }
    } else if (bytes_per_sample == 4) {
      int32_t* out = (int32_t*)mono_out.data();
      for (size_t i = 0; i < frames; ++i) {
        const int32_t* in = (int32_t*)(buf.data());
        int accum = 0;
        accum += *in++;
        accum += *in++;
        accum /= 2;  // round to 0
        *out++ = accum;
      }
    } else {
      LOG_ERROR("Don't know how to mono blend that %d!", bytes_per_sample);
    }
    return mono_out;
  }

  void PrepareAndSendToTwoDevices(
      const std::vector<uint8_t>& data,
      struct le_audio::stream_configuration* stream_conf) {
    uint16_t byte_count = stream_conf->sink_octets_per_codec_frame;
    uint16_t left_cis_handle = 0;
    uint16_t right_cis_handle = 0;
    uint16_t number_of_required_samples_per_channel;

    int dt_us = current_source_codec_config.data_interval_us;
    int af_hz = audio_framework_source_config.sample_rate;
    number_of_required_samples_per_channel = lc3_frame_samples(dt_us, af_hz);

    lc3_pcm_format bits_per_sample =
        bits_to_lc3_bits(audio_framework_source_config.bits_per_sample);
    uint8_t bytes_per_sample =
        bits_to_bytes_per_sample(audio_framework_source_config.bits_per_sample);

    for (auto [cis_handle, audio_location] : stream_conf->sink_streams) {
      if (audio_location & le_audio::codec_spec_conf::kLeAudioLocationAnyLeft)
        left_cis_handle = cis_handle;
      if (audio_location & le_audio::codec_spec_conf::kLeAudioLocationAnyRight)
        right_cis_handle = cis_handle;
    }

    if (data.size() < bytes_per_sample * 2 /* channels */ *
                          number_of_required_samples_per_channel) {
      LOG(ERROR) << __func__ << " Missing samples. Data size: " << +data.size()
                 << " expected: "
                 << bytes_per_sample * 2 *
                        number_of_required_samples_per_channel;
      return;
    }

    std::vector<uint8_t> chan_left_enc(byte_count, 0);
    std::vector<uint8_t> chan_right_enc(byte_count, 0);

    bool mono = (left_cis_handle == 0) || (right_cis_handle == 0);

    if (!mono) {
      lc3_encode(lc3_encoder_left, bits_per_sample, data.data(), 2,
                 chan_left_enc.size(), chan_left_enc.data());
      lc3_encode(lc3_encoder_right, bits_per_sample,
                 data.data() + bytes_per_sample, 2, chan_right_enc.size(),
                 chan_right_enc.data());
    } else {
      std::vector<uint8_t> mono = mono_blend(
          data, bytes_per_sample, number_of_required_samples_per_channel);
      if (left_cis_handle) {
        lc3_encode(lc3_encoder_left, bits_per_sample, mono.data(), 1,
                   chan_left_enc.size(), chan_left_enc.data());
      }

      if (right_cis_handle) {
        lc3_encode(lc3_encoder_right, bits_per_sample, mono.data(), 1,
                   chan_right_enc.size(), chan_right_enc.data());
      }
    }

    DLOG(INFO) << __func__ << " left_cis_handle: " << +left_cis_handle
               << " right_cis_handle: " << right_cis_handle;
    /* Send data to the controller */
    if (left_cis_handle)
      IsoManager::GetInstance()->SendIsoData(
          left_cis_handle, chan_left_enc.data(), chan_left_enc.size());

    if (right_cis_handle)
      IsoManager::GetInstance()->SendIsoData(
          right_cis_handle, chan_right_enc.data(), chan_right_enc.size());
  }

  void PrepareAndSendToSingleDevice(
      const std::vector<uint8_t>& data,
      struct le_audio::stream_configuration* stream_conf) {
    int num_channels = stream_conf->sink_num_of_channels;
    uint16_t byte_count = stream_conf->sink_octets_per_codec_frame;
    auto cis_handle = stream_conf->sink_streams.front().first;
    uint16_t number_of_required_samples_per_channel;

    int dt_us = current_source_codec_config.data_interval_us;
    int af_hz = audio_framework_source_config.sample_rate;
    number_of_required_samples_per_channel = lc3_frame_samples(dt_us, af_hz);
    lc3_pcm_format bits_per_sample =
        bits_to_lc3_bits(audio_framework_source_config.bits_per_sample);
    uint8_t bytes_per_sample =
        bits_to_bytes_per_sample(audio_framework_source_config.bits_per_sample);

    if ((int)data.size() < (2 /* bytes per sample */ * num_channels *
                            number_of_required_samples_per_channel)) {
      LOG(ERROR) << __func__ << "Missing samples";
      return;
    }
    std::vector<uint8_t> chan_encoded(num_channels * byte_count, 0);

    if (num_channels == 1) {
      /* Since we always get two channels from framework, lets make it mono here
       */
      std::vector<uint8_t> mono = mono_blend(
          data, bytes_per_sample, number_of_required_samples_per_channel);

      auto err = lc3_encode(lc3_encoder_left, bits_per_sample, mono.data(), 1,
                            byte_count, chan_encoded.data());

      if (err < 0) {
        LOG(ERROR) << " error while encoding, error code: " << +err;
      }
    } else {
      lc3_encode(lc3_encoder_left, bits_per_sample, (const int16_t*)data.data(),
                 2, byte_count, chan_encoded.data());
      lc3_encode(lc3_encoder_right, bits_per_sample,
                 (const int16_t*)data.data() + 1, 2, byte_count,
                 chan_encoded.data() + byte_count);
    }

    /* Send data to the controller */
    IsoManager::GetInstance()->SendIsoData(cis_handle, chan_encoded.data(),
                                           chan_encoded.size());
  }

  struct le_audio::stream_configuration* GetStreamConfigurationByDirection(
      LeAudioDeviceGroup* group, uint8_t direction) {
    struct le_audio::stream_configuration* stream_conf = &group->stream_conf;
    int num_of_devices = 0;
    int num_of_channels = 0;
    uint32_t sample_freq_hz = 0;
    uint32_t frame_duration_us = 0;
    uint32_t audio_channel_allocation = 0;
    uint16_t octets_per_frame = 0;
    uint16_t codec_frames_blocks_per_sdu = 0;

    LOG(INFO) << __func__ << " group_id: " << group->group_id_;

    /* This contains pair of cis handle and audio location */
    std::vector<std::pair<uint16_t, uint32_t>> streams;

    for (auto* device = group->GetFirstActiveDevice(); device != nullptr;
         device = group->GetNextActiveDevice(device)) {
      auto* ase = device->GetFirstActiveAseByDirection(direction);

      if (ase) {
        LOG(INFO) << __func__ << "device: " << device->address_;
        num_of_devices++;
      }

      for (; ase != nullptr;
           ase = device->GetNextActiveAseWithSameDirection(ase)) {
        streams.emplace_back(std::make_pair(
            ase->cis_conn_hdl, *ase->codec_config.audio_channel_allocation));
        audio_channel_allocation |= *ase->codec_config.audio_channel_allocation;
        num_of_channels += ase->codec_config.channel_count;
        if (sample_freq_hz == 0) {
          sample_freq_hz = ase->codec_config.GetSamplingFrequencyHz();
        } else {
          LOG_ASSERT(sample_freq_hz ==
                     ase->codec_config.GetSamplingFrequencyHz())
              << __func__ << " sample freq mismatch: " << +sample_freq_hz
              << " != " << ase->codec_config.GetSamplingFrequencyHz();
        }

        if (frame_duration_us == 0) {
          frame_duration_us = ase->codec_config.GetFrameDurationUs();
        } else {
          LOG_ASSERT(frame_duration_us ==
                     ase->codec_config.GetFrameDurationUs())
              << __func__ << " frame duration mismatch: " << +frame_duration_us
              << " != " << ase->codec_config.GetFrameDurationUs();
        }

        if (octets_per_frame == 0) {
          octets_per_frame = *ase->codec_config.octets_per_codec_frame;
        } else {
          LOG_ASSERT(octets_per_frame ==
                     ase->codec_config.octets_per_codec_frame)
              << __func__ << " octets per frame mismatch: " << +octets_per_frame
              << " != " << *ase->codec_config.octets_per_codec_frame;
        }

        if (codec_frames_blocks_per_sdu == 0) {
          codec_frames_blocks_per_sdu =
              *ase->codec_config.codec_frames_blocks_per_sdu;
        } else {
          LOG_ASSERT(codec_frames_blocks_per_sdu ==
                     ase->codec_config.codec_frames_blocks_per_sdu)
              << __func__ << " codec_frames_blocks_per_sdu: "
              << +codec_frames_blocks_per_sdu
              << " != " << *ase->codec_config.codec_frames_blocks_per_sdu;
        }

        LOG(INFO) << __func__ << " Added CIS: " << +ase->cis_conn_hdl
                  << " to stream. Allocation: "
                  << +(*ase->codec_config.audio_channel_allocation)
                  << " sample_freq: " << +sample_freq_hz
                  << " frame_duration: " << +frame_duration_us
                  << " octects per frame: " << +octets_per_frame
                  << " codec_frame_blocks_per_sdu: "
                  << +codec_frames_blocks_per_sdu;
      }
    }

    if (streams.empty()) return nullptr;

    if (direction == le_audio::types::kLeAudioDirectionSource) {
      stream_conf->source_streams = std::move(streams);
      stream_conf->source_num_of_devices = num_of_devices;
      stream_conf->source_num_of_channels = num_of_channels;
      stream_conf->source_sample_frequency_hz = sample_freq_hz;
      stream_conf->source_frame_duration_us = frame_duration_us;
      stream_conf->source_audio_channel_allocation = audio_channel_allocation;
      stream_conf->source_octets_per_codec_frame = octets_per_frame;
      stream_conf->source_codec_frames_blocks_per_sdu =
          codec_frames_blocks_per_sdu;
    } else if (direction == le_audio::types::kLeAudioDirectionSink) {
      stream_conf->sink_streams = std::move(streams);
      stream_conf->sink_num_of_devices = num_of_devices;
      stream_conf->sink_num_of_channels = num_of_channels;
      stream_conf->sink_sample_frequency_hz = sample_freq_hz;
      stream_conf->sink_frame_duration_us = frame_duration_us;
      stream_conf->sink_audio_channel_allocation = audio_channel_allocation;
      stream_conf->sink_octets_per_codec_frame = octets_per_frame;
      stream_conf->sink_codec_frames_blocks_per_sdu =
          codec_frames_blocks_per_sdu;
    }

    LOG(INFO) << __func__ << " configuration: " << stream_conf->conf->name;

    return stream_conf;
  }

  struct le_audio::stream_configuration* GetStreamSinkConfiguration(
      LeAudioDeviceGroup* group) {
    return GetStreamConfigurationByDirection(
        group, le_audio::types::kLeAudioDirectionSink);
  }

  void OnAudioDataReady(const std::vector<uint8_t>& data) {
    if ((active_group_id_ == bluetooth::groups::kGroupUnknown) ||
        (audio_sender_state_ != AudioState::STARTED))
      return;

    LeAudioDeviceGroup* group = aseGroups_.FindById(active_group_id_);
    if (!group) {
      LOG(ERROR) << __func__ << "There is no streaming group available";
      return;
    }

    auto stream_conf = group->stream_conf;
    if ((stream_conf.sink_num_of_devices > 2) ||
        (stream_conf.sink_num_of_devices == 0) ||
        stream_conf.sink_streams.empty()) {
      LOG(ERROR) << __func__ << " Stream configufation is not valid.";
      return;
    }

    if (stream_conf.sink_num_of_devices == 2) {
      PrepareAndSendToTwoDevices(data, &stream_conf);
    } else {
      PrepareAndSendToSingleDevice(data, &stream_conf);
    }
  }

  void CleanCachedMicrophoneData() {
    cached_channel_data_.clear();
    cached_channel_timestamp_ = 0;
    cached_channel_is_left_ = false;
  }

  void SendAudioData(uint8_t* data, uint16_t size, uint16_t cis_conn_hdl,
                     uint32_t timestamp) {
    /* Get only one channel for MONO microphone */
    /* Gather data for channel */
    if ((active_group_id_ == bluetooth::groups::kGroupUnknown) ||
        (audio_receiver_state_ != AudioState::STARTED))
      return;

    LeAudioDeviceGroup* group = aseGroups_.FindById(active_group_id_);
    if (!group) {
      LOG(ERROR) << __func__ << "There is no streaming group available";
      return;
    }

    auto stream_conf = group->stream_conf;

    uint16_t left_cis_handle = 0;
    uint16_t right_cis_handle = 0;
    for (auto [cis_handle, audio_location] : stream_conf.source_streams) {
      if (audio_location & le_audio::codec_spec_conf::kLeAudioLocationAnyLeft) {
        left_cis_handle = cis_handle;
      }
      if (audio_location &
          le_audio::codec_spec_conf::kLeAudioLocationAnyRight) {
        right_cis_handle = cis_handle;
      }
    }

    bool is_left = true;
    if (cis_conn_hdl == left_cis_handle) {
      is_left = true;
    } else if (cis_conn_hdl == right_cis_handle) {
      is_left = false;
    } else {
      LOG_ERROR("Received data for unknown handle: %04x", cis_conn_hdl);
      return;
    }

    uint16_t required_for_channel_byte_count =
        stream_conf.source_octets_per_codec_frame;

    int dt_us = current_sink_codec_config.data_interval_us;
    int af_hz = audio_framework_sink_config.sample_rate;
    lc3_pcm_format bits_per_sample =
        bits_to_lc3_bits(audio_framework_sink_config.bits_per_sample);

    int pcm_size;
    if (dt_us == 10000) {
      if (af_hz == 44100)
        pcm_size = 480;
      else
        pcm_size = af_hz / 100;
    } else if (dt_us == 7500) {
      if (af_hz == 44100)
        pcm_size = 360;
      else
        pcm_size = (af_hz * 3) / 400;
    } else {
      LOG(ERROR) << "BAD dt_us: " << dt_us;
      return;
    }

    std::vector<int16_t> pcm_data_decoded(pcm_size, 0);

    int err = 0;

    if (required_for_channel_byte_count != size) {
      LOG(INFO) << "Insufficient data for decoding and send, required: "
                << int(required_for_channel_byte_count)
                << ", received: " << int(size) << ", will do PLC";
      size = 0;
      data = nullptr;
    }

    lc3_decoder_t decoder_to_use =
        is_left ? lc3_decoder_left : lc3_decoder_right;

    err = lc3_decode(decoder_to_use, data, size, bits_per_sample,
                     pcm_data_decoded.data(), 1 /* pitch */);

    if (err < 0) {
      LOG(ERROR) << " bad decoding parameters: " << static_cast<int>(err);
      return;
    }

    /* AF == Audio Framework */
    bool af_is_stereo = (audio_framework_sink_config.num_channels == 2);

    if (!left_cis_handle || !right_cis_handle) {
      /* mono or just one device connected */
      SendAudioDataToAF(false /* bt_got_stereo */, af_is_stereo,
                        &pcm_data_decoded, nullptr);
      return;
    }
    /* both devices are connected */

    if (cached_channel_timestamp_ == 0 && cached_channel_data_.empty()) {
      /* First packet received, cache it. We need both channel data to send it
       * to AF. */
      cached_channel_data_ = pcm_data_decoded;
      cached_channel_timestamp_ = timestamp;
      cached_channel_is_left_ = is_left;
      return;
    }

    /* We received either data for the other audio channel, or another
     * packet for same channel */

    if (cached_channel_is_left_ != is_left) {
      /* It's data for the 2nd channel */
      if (timestamp == cached_channel_timestamp_) {
        /* Ready to mix data and send out to AF */
        if (is_left) {
          SendAudioDataToAF(true /* bt_got_stereo */, af_is_stereo,
                            &cached_channel_data_, &pcm_data_decoded);
        } else {
          SendAudioDataToAF(true /* bt_got_stereo */, af_is_stereo,
                            &pcm_data_decoded, &cached_channel_data_);
        }

        CleanCachedMicrophoneData();
        return;
      }

      /* 2nd Channel is in the future compared to the cached data.
       Send the cached data to AF, and keep the new channel data in cache.
       This should happen only during stream setup */

      if (cached_channel_is_left_) {
        SendAudioDataToAF(false /* bt_got_stereo */, af_is_stereo,
                          &cached_channel_data_, nullptr);
      } else {
        SendAudioDataToAF(false /* bt_got_stereo */, af_is_stereo, nullptr,
                          &cached_channel_data_);
      }

      cached_channel_data_ = pcm_data_decoded;
      cached_channel_timestamp_ = timestamp;
      cached_channel_is_left_ = is_left;
      return;
    }

    /* Data for same channel received. 2nd channel is down/not sending
     * data */

    /* Send the cached data out */
    if (cached_channel_is_left_) {
      SendAudioDataToAF(false /* bt_got_stereo */, af_is_stereo,
                        &cached_channel_data_, nullptr);
    } else {
      SendAudioDataToAF(false /* bt_got_stereo */, af_is_stereo, nullptr,
                        &cached_channel_data_);
    }

    /* Cache the data in case 2nd channel connects */
    cached_channel_data_ = pcm_data_decoded;
    cached_channel_timestamp_ = timestamp;
    cached_channel_is_left_ = is_left;
  }

  void SendAudioDataToAF(bool bt_got_stereo, bool af_is_stereo,
                         std::vector<int16_t>* left,
                         std::vector<int16_t>* right) {
    uint16_t to_write = 0;
    uint16_t written = 0;
    if (!bt_got_stereo && !af_is_stereo) {
      std::vector<int16_t>* mono = left ? left : right;
      /* mono audio over bluetooth, audio framework expects mono */
      to_write = sizeof(int16_t) * mono->size();
      written =
          leAudioClientAudioSink->SendData((uint8_t*)mono->data(), to_write);
    } else if (bt_got_stereo && af_is_stereo) {
      /* stero audio over bluetooth, audio framework expects stereo */
      std::vector<uint16_t> mixed(left->size() * 2);

      for (size_t i = 0; i < left->size(); i++) {
        mixed[2 * i] = (*right)[i];
        mixed[2 * i + 1] = (*left)[i];
      }
      to_write = sizeof(int16_t) * mixed.size();
      written =
          leAudioClientAudioSink->SendData((uint8_t*)mixed.data(), to_write);
    } else if (bt_got_stereo && !af_is_stereo) {
      /* stero audio over bluetooth, audio framework expects mono */
      std::vector<uint16_t> mixed(left->size() * 2);

      for (size_t i = 0; i < left->size(); i++) {
        (*left)[i] = ((*left)[i] + (*right)[i]) / 2;
      }
      to_write = sizeof(int16_t) * left->size();
      written =
          leAudioClientAudioSink->SendData((uint8_t*)left->data(), to_write);
    } else if (!bt_got_stereo && af_is_stereo) {
      /* mono audio over bluetooth, audio framework expects stereo */
      const size_t mono_size = left ? left->size() : right->size();
      std::vector<uint16_t> mixed(mono_size * 2);

      for (size_t i = 0; i < mono_size; i++) {
        mixed[2 * i] = right ? (*right)[i] : 0;
        mixed[2 * i + 1] = left ? (*left)[i] : 0;
      }
      to_write = sizeof(int16_t) * mixed.size();
      written =
          leAudioClientAudioSink->SendData((uint8_t*)mixed.data(), to_write);
    }

    /* TODO: What to do if not all data sinked ? */
    if (written != to_write) LOG(ERROR) << __func__ << ", not all data sinked";
  }

  bool StartSendingAudio(int group_id) {
    LOG(INFO) << __func__;

    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);
    LeAudioDevice* device = group->GetFirstActiveDevice();
    LOG_ASSERT(device) << __func__
                       << " Shouldn't be called without an active device.";

    /* Assume 2 ases max just for now. */
    auto* stream_conf = GetStreamSinkConfiguration(group);
    if (stream_conf == nullptr) {
      LOG(ERROR) << __func__ << " could not get sink configuration";
      return false;
    }

    uint16_t remote_delay_ms =
        group->GetRemoteDelay(le_audio::types::kLeAudioDirectionSink);
    if (CodecManager::GetInstance()->GetCodecLocation() ==
        le_audio::types::CodecLocation::HOST) {
      if (lc3_encoder_left_mem) {
        LOG(WARNING)
            << " The encoder instance should have been already released.";
        free(lc3_encoder_left_mem);
        lc3_encoder_left_mem = nullptr;
        free(lc3_encoder_right_mem);
        lc3_encoder_right_mem = nullptr;
      }
      int dt_us = current_source_codec_config.data_interval_us;
      int sr_hz = current_source_codec_config.sample_rate;
      int af_hz = audio_framework_source_config.sample_rate;
      unsigned enc_size = lc3_encoder_size(dt_us, af_hz);

      lc3_encoder_left_mem = malloc(enc_size);
      lc3_encoder_right_mem = malloc(enc_size);

      lc3_encoder_left =
          lc3_setup_encoder(dt_us, sr_hz, af_hz, lc3_encoder_left_mem);
      lc3_encoder_right =
          lc3_setup_encoder(dt_us, sr_hz, af_hz, lc3_encoder_right_mem);

    } else if (CodecManager::GetInstance()->GetCodecLocation() ==
               le_audio::types::CodecLocation::ADSP) {
      CodecManager::GetInstance()->UpdateActiveSourceAudioConfig(
          *stream_conf, remote_delay_ms,
          std::bind(&LeAudioUnicastClientAudioSource::UpdateAudioConfigToHal,
                    leAudioClientAudioSource, std::placeholders::_1));
    }

    leAudioClientAudioSource->UpdateRemoteDelay(remote_delay_ms);
    leAudioClientAudioSource->ConfirmStreamingRequest();
    audio_sender_state_ = AudioState::STARTED;

    return true;
  }

  struct le_audio::stream_configuration* GetStreamSourceConfiguration(
      LeAudioDeviceGroup* group) {
    return GetStreamConfigurationByDirection(
        group, le_audio::types::kLeAudioDirectionSource);
  }

  void StartReceivingAudio(int group_id) {
    LOG(INFO) << __func__;

    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);

    auto* stream_conf = GetStreamSourceConfiguration(group);
    if (!stream_conf) {
      LOG(WARNING) << " Could not get source configuration for group "
                   << active_group_id_ << " probably microphone not configured";
      return;
    }

    uint16_t remote_delay_ms =
        group->GetRemoteDelay(le_audio::types::kLeAudioDirectionSource);

    CleanCachedMicrophoneData();

    if (CodecManager::GetInstance()->GetCodecLocation() ==
        le_audio::types::CodecLocation::HOST) {
      if (lc3_decoder_left_mem) {
        LOG(WARNING)
            << " The decoder instance should have been already released.";
        free(lc3_decoder_left_mem);
        lc3_decoder_left_mem = nullptr;
        free(lc3_decoder_right_mem);
        lc3_decoder_right_mem = nullptr;
      }

      int dt_us = current_sink_codec_config.data_interval_us;
      int sr_hz = current_sink_codec_config.sample_rate;
      int af_hz = audio_framework_sink_config.sample_rate;
      unsigned dec_size = lc3_decoder_size(dt_us, af_hz);
      lc3_decoder_left_mem = malloc(dec_size);
      lc3_decoder_right_mem = malloc(dec_size);

      lc3_decoder_left =
          lc3_setup_decoder(dt_us, sr_hz, af_hz, lc3_decoder_left_mem);
      lc3_decoder_right =
          lc3_setup_decoder(dt_us, sr_hz, af_hz, lc3_decoder_right_mem);
    } else if (CodecManager::GetInstance()->GetCodecLocation() ==
               le_audio::types::CodecLocation::ADSP) {
      CodecManager::GetInstance()->UpdateActiveSinkAudioConfig(
          *stream_conf, remote_delay_ms,
          std::bind(&LeAudioUnicastClientAudioSink::UpdateAudioConfigToHal,
                    leAudioClientAudioSink, std::placeholders::_1));
    }

    leAudioClientAudioSink->UpdateRemoteDelay(remote_delay_ms);
    leAudioClientAudioSink->ConfirmStreamingRequest();
    audio_receiver_state_ = AudioState::STARTED;
  }

  void SuspendAudio(void) {
    CancelStreamingRequest();

    if (lc3_encoder_left_mem) {
      free(lc3_encoder_left_mem);
      lc3_encoder_left_mem = nullptr;
      free(lc3_encoder_right_mem);
      lc3_encoder_right_mem = nullptr;
    }

    if (lc3_decoder_left_mem) {
      free(lc3_decoder_left_mem);
      lc3_decoder_left_mem = nullptr;
      free(lc3_decoder_right_mem);
      lc3_decoder_right_mem = nullptr;
    }
  }

  void StopAudio(void) { SuspendAudio(); }

  void printSingleConfiguration(int fd, LeAudioCodecConfiguration* conf,
                                bool print_audio_state, bool sender = false) {
    std::stringstream stream;
    if (print_audio_state) {
      if (sender) {
        stream << "   audio sender state: " << audio_sender_state_ << "\n";
      } else {
        stream << "   audio receiver state: " << audio_receiver_state_ << "\n";
      }
    }

    stream << "   num_channels: " << +conf->num_channels << "\n"
           << "   sample rate: " << +conf->sample_rate << "\n"
           << "   bits pers sample: " << +conf->bits_per_sample << "\n"
           << "   data_interval_us: " << +conf->data_interval_us << "\n";

    dprintf(fd, "%s", stream.str().c_str());
  }

  void printCurrentStreamConfiguration(int fd) {
    auto conf = &audio_framework_source_config;
    dprintf(fd, " Speaker codec config (audio framework) \n");
    if (conf) {
      printSingleConfiguration(fd, conf, false);
    }

    dprintf(fd, " Microphone codec config (audio framework) \n");
    conf = &audio_framework_sink_config;
    if (conf) {
      printSingleConfiguration(fd, conf, false);
    }

    conf = &current_source_codec_config;
    dprintf(fd, " Speaker codec config (Bluetooth)\n");
    if (conf) {
      printSingleConfiguration(fd, conf, true, true);
    }

    conf = &current_sink_codec_config;
    dprintf(fd, " Microphone codec config (Bluetooth)\n");
    if (conf) {
      printSingleConfiguration(fd, conf, true, false);
    }
  }

  void Dump(int fd) {
    dprintf(fd, "  Active group: %d\n", active_group_id_);
    dprintf(fd, "    current content type: 0x%08hx\n", current_context_type_);
    dprintf(
        fd, "    stream setup time if started: %d ms\n",
        (int)((stream_setup_end_timestamp_ - stream_setup_start_timestamp_) /
              1000));
    printCurrentStreamConfiguration(fd);
    dprintf(fd, "  ----------------\n ");
    dprintf(fd, "  LE Audio Groups:\n");
    aseGroups_.Dump(fd);
    dprintf(fd, "  Not grouped devices:\n");
    leAudioDevices_.Dump(fd, bluetooth::groups::kGroupUnknown);
  }

  void Cleanup(base::Callback<void()> cleanupCb) {
    if (alarm_is_scheduled(suspend_timeout_)) alarm_cancel(suspend_timeout_);

    if (active_group_id_ != bluetooth::groups::kGroupUnknown) {
      /* Bluetooth turned off while streaming */
      StopAudio();
      ClientAudioIntefraceRelease();
    }
    groupStateMachine_->Cleanup();
    aseGroups_.Cleanup();
    leAudioDevices_.Cleanup();
    if (gatt_if_) BTA_GATTC_AppDeregister(gatt_if_);

    std::move(cleanupCb).Run();
  }

  bool UpdateConfigAndCheckIfReconfigurationIsNeeded(
      int group_id, LeAudioContextType context_type) {
    bool reconfiguration_needed = false;
    auto group = aseGroups_.FindById(group_id);
    if (!group) {
      LOG(ERROR) << __func__
                 << ", Invalid group: " << static_cast<int>(group_id);
      return reconfiguration_needed;
    }

    std::optional<LeAudioCodecConfiguration> source_configuration =
        group->GetCodecConfigurationByDirection(
            context_type, le_audio::types::kLeAudioDirectionSink);
    std::optional<LeAudioCodecConfiguration> sink_configuration =
        group->GetCodecConfigurationByDirection(
            context_type, le_audio::types::kLeAudioDirectionSource);

    if (source_configuration) {
      if (*source_configuration != current_source_codec_config) {
        current_source_codec_config = *source_configuration;
        reconfiguration_needed = true;
      }
    } else {
      if (!current_source_codec_config.IsInvalid()) {
        current_source_codec_config = {0, 0, 0, 0};
        reconfiguration_needed = true;
      }

      LOG(INFO) << __func__
                << ", group does not supports source direction for"
                   " context: "
                << static_cast<int>(context_type);
    }

    if (sink_configuration) {
      if (*sink_configuration != current_sink_codec_config) {
        current_sink_codec_config = *sink_configuration;
        reconfiguration_needed = true;
      }
    } else {
      if (!current_sink_codec_config.IsInvalid()) {
        current_sink_codec_config = {0, 0, 0, 0};
        reconfiguration_needed = true;
      }

      LOG(INFO) << __func__
                << ", group does not supports sink direction for"
                   " context: "
                << static_cast<int>(context_type);
    }

    if (reconfiguration_needed) {
      LOG(INFO) << __func__
                << " Session reconfiguration needed group: " << group->group_id_
                << " for context type: " << static_cast<int>(context_type);
    }

    current_context_type_ = context_type;
    return reconfiguration_needed;
  }

  bool OnAudioResume(LeAudioDeviceGroup* group) {
    if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      return true;
    }
    return InternalGroupStream(active_group_id_,
                               static_cast<uint16_t>(current_context_type_));
  }

  void OnAudioSuspend() {
    if (active_group_id_ == bluetooth::groups::kGroupUnknown) {
      LOG(WARNING) << ", there is no longer active group";
      return;
    }

    /* Group should tie in time to get requested status */
    uint64_t timeoutMs = kAudioSuspentKeepIsoAliveTimeoutMs;
    timeoutMs = osi_property_get_int32(kAudioSuspentKeepIsoAliveTimeoutMsProp,
                                       timeoutMs);

    DLOG(INFO) << __func__
               << " Stream suspend_timeout_ started: " << suspend_timeout_;
    if (alarm_is_scheduled(suspend_timeout_)) alarm_cancel(suspend_timeout_);

    alarm_set_on_mloop(
        suspend_timeout_, timeoutMs,
        [](void* data) {
          if (instance) instance->GroupStop(PTR_TO_INT(data));
        },
        INT_TO_PTR(active_group_id_));
  }

  void OnAudioSinkSuspend() {
    DLOG(INFO) << __func__
               << " IN: audio_receiver_state_: " << audio_receiver_state_
               << " audio_sender_state_: " << audio_sender_state_;

    /* Note: This callback is from audio hal driver.
     * Bluetooth peer is a Sink for Audio Framework.
     * e.g. Peer is a speaker
     */
    switch (audio_sender_state_) {
      case AudioState::READY_TO_START:
      case AudioState::STARTED:
        audio_sender_state_ = AudioState::READY_TO_RELEASE;
        break;
      case AudioState::RELEASING:
        return;
      case AudioState::IDLE:
        if (audio_receiver_state_ == AudioState::READY_TO_RELEASE) {
          OnAudioSuspend();
        }
        return;
      case AudioState::READY_TO_RELEASE:
        break;
    }

    /* Last suspends group - triggers group stop */
    if ((audio_receiver_state_ == AudioState::IDLE) ||
        (audio_receiver_state_ == AudioState::READY_TO_RELEASE)) {
      OnAudioSuspend();
      le_audio::MetricsCollector::Get()->OnStreamEnded(active_group_id_);
    }

    DLOG(INFO) << __func__
               << " OUT: audio_receiver_state_: " << audio_receiver_state_
               << " audio_sender_state_: " << audio_sender_state_;
  }

  void OnAudioSinkResume() {
    LOG(INFO) << __func__;

    /* Note: This callback is from audio hal driver.
     * Bluetooth peer is a Sink for Audio Framework.
     * e.g. Peer is a speaker
     */
    auto group = aseGroups_.FindById(active_group_id_);
    if (!group) {
      LOG(ERROR) << __func__
                 << ", Invalid group: " << static_cast<int>(active_group_id_);
      return;
    }

    /* Check if the device resume is expected */
    if (!group->GetCodecConfigurationByDirection(
            current_context_type_, le_audio::types::kLeAudioDirectionSink)) {
      LOG(ERROR) << __func__ << ", invalid resume request for context type: "
                 << loghex(static_cast<int>(current_context_type_));
      leAudioClientAudioSource->CancelStreamingRequest();
      return;
    }

    DLOG(INFO) << __func__ << " active_group_id: " << active_group_id_ << "\n"
               << " audio_receiver_state: " << audio_receiver_state_ << "\n"
               << " audio_sender_state: " << audio_sender_state_ << "\n"
               << " current_context_type_: "
               << static_cast<int>(current_context_type_) << "\n"
               << " group " << (group ? " exist " : " does not exist ") << "\n";

    switch (audio_sender_state_) {
      case AudioState::STARTED:
        /* Looks like previous Confirm did not get to the Audio Framework*/
        leAudioClientAudioSource->ConfirmStreamingRequest();
        break;
      case AudioState::IDLE:
        switch (audio_receiver_state_) {
          case AudioState::IDLE:
            /* Stream is not started. Try to do it.*/
            if (OnAudioResume(group)) {
              audio_sender_state_ = AudioState::READY_TO_START;
            } else {
              leAudioClientAudioSource->CancelStreamingRequest();
            }
            break;
          case AudioState::READY_TO_START:
          case AudioState::STARTED:
            audio_sender_state_ = AudioState::READY_TO_START;
            /* If signalling part is completed trigger start receiving audio
             * here, otherwise it'll be called on group streaming state callback
             */
            if (group->GetState() ==
                AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
              StartSendingAudio(active_group_id_);
            }
            break;
          case AudioState::RELEASING:
            /* Group is reconfiguring, reassing state and wait for
             * the stream to be configured
             */
            audio_sender_state_ = audio_receiver_state_;
            break;
          case AudioState::READY_TO_RELEASE:
            LOG_WARN(
                " called in wrong state. \n audio_receiver_state: %s \n"
                "audio_sender_state: %s \n",
                ToString(audio_receiver_state_).c_str(),
                ToString(audio_sender_state_).c_str());
            CancelStreamingRequest();
            break;
        }
        break;
      case AudioState::READY_TO_START:
        LOG_WARN(
            " called in wrong state. \n audio_receiver_state: %s \n"
            "audio_sender_state: %s \n",
            ToString(audio_receiver_state_).c_str(),
            ToString(audio_sender_state_).c_str());
        CancelStreamingRequest();
        break;
      case AudioState::READY_TO_RELEASE:
        switch (audio_receiver_state_) {
          case AudioState::STARTED:
          case AudioState::READY_TO_START:
          case AudioState::IDLE:
          case AudioState::READY_TO_RELEASE:
            /* Stream is up just restore it */
            audio_sender_state_ = AudioState::STARTED;
            if (alarm_is_scheduled(suspend_timeout_))
              alarm_cancel(suspend_timeout_);
            leAudioClientAudioSource->ConfirmStreamingRequest();
            le_audio::MetricsCollector::Get()->OnStreamStarted(
                active_group_id_, current_context_type_);
            break;
          case AudioState::RELEASING:
            /* Keep wainting. After release is done, Audio Hal will be notified
             */
            break;
        }
        break;
      case AudioState::RELEASING:
        /* Keep wainting. After release is done, Audio Hal will be notified */
        break;
    }
  }

  void OnAudioSourceSuspend() {
    DLOG(INFO) << __func__
               << " IN: audio_receiver_state_: " << audio_receiver_state_
               << " audio_sender_state_: " << audio_sender_state_;

    /* Note: This callback is from audio hal driver.
     * Bluetooth peer is a Source for Audio Framework.
     * e.g. Peer is microphone.
     */
    switch (audio_receiver_state_) {
      case AudioState::READY_TO_START:
      case AudioState::STARTED:
        audio_receiver_state_ = AudioState::READY_TO_RELEASE;
        break;
      case AudioState::RELEASING:
        return;
      case AudioState::IDLE:
        if (audio_sender_state_ == AudioState::READY_TO_RELEASE) {
          OnAudioSuspend();
        }
        return;
      case AudioState::READY_TO_RELEASE:
        break;
    }

    /* Last suspends group - triggers group stop */
    if ((audio_sender_state_ == AudioState::IDLE) ||
        (audio_sender_state_ == AudioState::READY_TO_RELEASE))
      OnAudioSuspend();

    DLOG(INFO) << __func__
               << " OUT: audio_receiver_state_: " << audio_receiver_state_
               << " audio_sender_state_: " << audio_sender_state_;
  }

  bool IsAudioSourceAvailableForCurrentContentType() {
    if (current_context_type_ == LeAudioContextType::CONVERSATIONAL ||
        current_context_type_ == LeAudioContextType::VOICEASSISTANTS) {
      return true;
    }

    return false;
  }

  void OnAudioSourceResume() {
    LOG(INFO) << __func__;

    /* Note: This callback is from audio hal driver.
     * Bluetooth peer is a Source for Audio Framework.
     * e.g. Peer is microphone.
     */
    auto group = aseGroups_.FindById(active_group_id_);
    if (!group) {
      LOG(ERROR) << __func__
                 << ", Invalid group: " << static_cast<int>(active_group_id_);
      return;
    }

    /* Check if the device resume is expected */
    if (!group->GetCodecConfigurationByDirection(
            current_context_type_, le_audio::types::kLeAudioDirectionSource)) {
      LOG(ERROR) << __func__ << ", invalid resume request for context type: "
                 << loghex(static_cast<int>(current_context_type_));
      leAudioClientAudioSink->CancelStreamingRequest();
      return;
    }

    DLOG(INFO) << __func__ << " active_group_id: " << active_group_id_ << "\n"
               << " audio_receiver_state: " << audio_receiver_state_ << "\n"
               << " audio_sender_state: " << audio_sender_state_ << "\n"
               << " current_context_type_: "
               << static_cast<int>(current_context_type_) << "\n"
               << " group " << (group ? " exist " : " does not exist ") << "\n";

    switch (audio_receiver_state_) {
      case AudioState::STARTED:
        leAudioClientAudioSink->ConfirmStreamingRequest();
        break;
      case AudioState::IDLE:
        switch (audio_sender_state_) {
          case AudioState::IDLE:
            if (OnAudioResume(group)) {
              audio_receiver_state_ = AudioState::READY_TO_START;
            } else {
              leAudioClientAudioSink->CancelStreamingRequest();
            }
            break;
          case AudioState::READY_TO_START:
          case AudioState::STARTED:
            audio_receiver_state_ = AudioState::READY_TO_START;
            /* If signalling part is completed trigger start reveivin audio
             * here, otherwise it'll be called on group streaming state callback
             */
            if (group->GetState() ==
                AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
              if (!IsAudioSourceAvailableForCurrentContentType()) {
                StopStreamIfNeeded(group, LeAudioContextType::VOICEASSISTANTS);
                break;
              }

              StartReceivingAudio(active_group_id_);
            }
            break;
          case AudioState::RELEASING:
            /* Group is reconfiguring, reassing state and wait for
             * the stream to be configured
             */
            audio_receiver_state_ = audio_sender_state_;
            break;
          case AudioState::READY_TO_RELEASE:
            LOG_WARN(
                " called in wrong state. \n audio_receiver_state: %s \n"
                "audio_sender_state: %s \n",
                ToString(audio_receiver_state_).c_str(),
                ToString(audio_sender_state_).c_str());
            CancelStreamingRequest();
            break;
        }
        break;
      case AudioState::READY_TO_START:
        LOG_WARN(
            " called in wrong state. \n audio_receiver_state: %s \n"
            "audio_sender_state: %s \n",
            ToString(audio_receiver_state_).c_str(),
            ToString(audio_sender_state_).c_str());
        CancelStreamingRequest();
        break;
      case AudioState::READY_TO_RELEASE:
        switch (audio_sender_state_) {
          case AudioState::STARTED:
          case AudioState::IDLE:
          case AudioState::READY_TO_START:
          case AudioState::READY_TO_RELEASE:
            /* Stream is up just restore it */
            audio_receiver_state_ = AudioState::STARTED;
            if (alarm_is_scheduled(suspend_timeout_))
              alarm_cancel(suspend_timeout_);
            leAudioClientAudioSink->ConfirmStreamingRequest();
            break;
          case AudioState::RELEASING:
            /* Wait until releasing is completed */
            break;
        }

        break;
      case AudioState::RELEASING:
        /* Wait until releasing is completed */
        break;
    }
  }

  LeAudioContextType AudioContentToLeAudioContext(
      LeAudioContextType current_context_type,
      audio_content_type_t content_type, audio_usage_t usage) {
    /* Check audio attribute usage of stream */
    switch (usage) {
      case AUDIO_USAGE_MEDIA:
        return LeAudioContextType::MEDIA;
      case AUDIO_USAGE_VOICE_COMMUNICATION:
      case AUDIO_USAGE_VOICE_COMMUNICATION_SIGNALLING:
      case AUDIO_USAGE_CALL_ASSISTANT:
        return LeAudioContextType::CONVERSATIONAL;
      case AUDIO_USAGE_GAME:
        return LeAudioContextType::GAME;
      case AUDIO_USAGE_NOTIFICATION:
        return LeAudioContextType::NOTIFICATIONS;
      case AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE:
        return LeAudioContextType::RINGTONE;
      case AUDIO_USAGE_ALARM:
        return LeAudioContextType::ALERTS;
      case AUDIO_USAGE_EMERGENCY:
        return LeAudioContextType::EMERGENCYALARM;
      case AUDIO_USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
        return LeAudioContextType::INSTRUCTIONAL;
      default:
        break;
    }

    return LeAudioContextType::MEDIA;
  }

  LeAudioContextType ChooseContextType(
      std::vector<LeAudioContextType>& available_contents) {
    /* Mini policy. Voice is prio 1, media is prio 2 */
    auto iter = find(available_contents.begin(), available_contents.end(),
                     LeAudioContextType::CONVERSATIONAL);
    if (iter != available_contents.end())
      return LeAudioContextType::CONVERSATIONAL;

    iter = find(available_contents.begin(), available_contents.end(),
                LeAudioContextType::MEDIA);
    if (iter != available_contents.end()) return LeAudioContextType::MEDIA;

    /*TODO do something smarter here */
    return available_contents[0];
  }

  bool StopStreamIfNeeded(LeAudioDeviceGroup* group,
                          LeAudioContextType new_context_type) {
    DLOG(INFO) << __func__ << " context type " << int(new_context_type);
    if (!UpdateConfigAndCheckIfReconfigurationIsNeeded(group->group_id_,
                                                       new_context_type)) {
      DLOG(INFO) << __func__ << " reconfiguration not needed";
      return false;
    }

    if (group->GetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      DLOG(INFO) << __func__ << " Group is not streaming ";
      return false;
    }

    if (alarm_is_scheduled(suspend_timeout_)) alarm_cancel(suspend_timeout_);

    /* Need to reconfigure stream */
    group->SetPendingConfiguration();
    groupStateMachine_->StopStream(group);
    return true;
  }

  void OnAudioMetadataUpdate(const source_metadata_t& source_metadata) {
    auto tracks = source_metadata.tracks;
    auto track_count = source_metadata.track_count;

    std::vector<LeAudioContextType> contexts;

    while (track_count) {
      if (tracks->content_type == 0 && tracks->usage == 0) {
        --track_count;
        ++tracks;
        continue;
      }

      LOG_INFO("%s: usage=%d, content_type=%d, gain=%f", __func__,
               tracks->usage, tracks->content_type, tracks->gain);

      auto new_context = AudioContentToLeAudioContext(
          current_context_type_, tracks->content_type, tracks->usage);
      contexts.push_back(new_context);

      --track_count;
      ++tracks;
    }

    if (contexts.empty()) {
      DLOG(INFO) << __func__ << " invalid metadata update";
      return;
    }

    auto new_context = ChooseContextType(contexts);
    DLOG(INFO) << __func__
               << " new_context_type: " << static_cast<int>(new_context);

    auto group = aseGroups_.FindById(active_group_id_);
    if (!group) {
      LOG(ERROR) << __func__
                 << ", Invalid group: " << static_cast<int>(active_group_id_);
      return;
    }

    if (new_context == current_context_type_) {
      LOG(INFO) << __func__ << " Context did not changed.";
      return;
    }

    if (active_group_id_ == bluetooth::groups::kGroupUnknown) {
      LOG(WARNING) << ", cannot start streaming if no active group set";
      return;
    }

    current_context_type_ = new_context;
    if (StopStreamIfNeeded(group, new_context)) {
      return;
    }

    if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      /* Configuration is the same for new context, just will do update
       * metadata of stream
       */
      GroupStream(active_group_id_, static_cast<uint16_t>(new_context));
    }
  }

  void OnAudioSourceMetadataUpdate(const sink_metadata_t& sink_metadata) {
    auto tracks = sink_metadata.tracks;
    auto track_count = sink_metadata.track_count;
    bool is_audio_source_invalid = true;

    while (track_count) {
      LOG_INFO(
          "%s: source=%d, gain=%f, destination device=%d, "
          "destination device address=%.32s",
          __func__, tracks->source, tracks->gain, tracks->dest_device,
          tracks->dest_device_address);

      /* Don't differentiate source types, just check if it's valid */
      if (is_audio_source_invalid && tracks->source != AUDIO_SOURCE_INVALID)
        is_audio_source_invalid = false;

      --track_count;
      ++tracks;
    }

    auto group = aseGroups_.FindById(active_group_id_);
    if (!group) {
      LOG(ERROR) << __func__
                 << ", Invalid group: " << static_cast<int>(active_group_id_);
      return;
    }

    /* Do nothing, since audio source is not valid and if voice assistant
     * scenario is currently not supported by group
     */
    if (is_audio_source_invalid ||
        !group->IsContextSupported(LeAudioContextType::VOICEASSISTANTS) ||
        IsAudioSourceAvailableForCurrentContentType()) {
      return;
    }

    auto new_context = LeAudioContextType::VOICEASSISTANTS;

    if (StopStreamIfNeeded(group, new_context)) return;

    if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      /* Configuration is the same for new context, just will do update
       * metadata of stream
       */
      GroupStream(active_group_id_, static_cast<uint16_t>(new_context));
    }

    /* Audio sessions are not resumed yet and not streaming, let's pick voice
     * assistant as possible current context type.
     */
    current_context_type_ = new_context;
  }

  static void OnGattReadRspStatic(uint16_t conn_id, tGATT_STATUS status,
                                  uint16_t hdl, uint16_t len, uint8_t* value,
                                  void* data) {
    if (!instance) return;

    if (status == GATT_SUCCESS) {
      instance->LeAudioCharValueHandle(conn_id, hdl, len,
                                       static_cast<uint8_t*>(value));
    }

    /* We use data to keep notify connected flag. */
    if (data && !!PTR_TO_INT(data)) {
      LeAudioDevice* leAudioDevice =
          instance->leAudioDevices_.FindByConnId(conn_id);
      leAudioDevice->notify_connected_after_read_ = false;
      instance->connectionReady(leAudioDevice);
    }
  }

  void IsoCigEventsCb(uint16_t event_type, void* data) {
    switch (event_type) {
      case bluetooth::hci::iso_manager::kIsoEventCigOnCreateCmpl: {
        auto* evt = static_cast<cig_create_cmpl_evt*>(data);
        LeAudioDeviceGroup* group = aseGroups_.FindById(evt->cig_id);
        ASSERT_LOG(group, "Group id: %d is null", evt->cig_id);
        groupStateMachine_->ProcessHciNotifOnCigCreate(
            group, evt->status, evt->cig_id, evt->conn_handles);
      } break;
      case bluetooth::hci::iso_manager::kIsoEventCigOnRemoveCmpl: {
        auto* evt = static_cast<cig_remove_cmpl_evt*>(data);
        LeAudioDeviceGroup* group = aseGroups_.FindById(evt->cig_id);
        ASSERT_LOG(group, "Group id: %d is null", evt->cig_id);
        groupStateMachine_->ProcessHciNotifOnCigRemove(evt->status, group);
        remove_group_if_possible(group);
      } break;
      default:
        LOG_ERROR("Invalid event %d", +event_type);
    }
  }

  void IsoCisEventsCb(uint16_t event_type, void* data) {
    switch (event_type) {
      case bluetooth::hci::iso_manager::kIsoEventCisDataAvailable: {
        auto* event =
            static_cast<bluetooth::hci::iso_manager::cis_data_evt*>(data);

        if (audio_receiver_state_ != AudioState::STARTED) {
          LOG(ERROR) << __func__ << " receiver state not ready ";
          break;
        }

        SendAudioData(event->p_msg->data + event->p_msg->offset,
                      event->p_msg->len - event->p_msg->offset,
                      event->cis_conn_hdl, event->ts);
      } break;
      case bluetooth::hci::iso_manager::kIsoEventCisEstablishCmpl: {
        auto* event =
            static_cast<bluetooth::hci::iso_manager::cis_establish_cmpl_evt*>(
                data);

        LeAudioDevice* leAudioDevice =
            leAudioDevices_.FindByCisConnHdl(event->cis_conn_hdl);
        if (!leAudioDevice) {
          LOG(ERROR) << __func__ << ", no bonded Le Audio Device with CIS: "
                     << +event->cis_conn_hdl;
          break;
        }
        LeAudioDeviceGroup* group =
            aseGroups_.FindById(leAudioDevice->group_id_);

        if (event->max_pdu_mtos > 0)
          group->SetTransportLatency(le_audio::types::kLeAudioDirectionSink,
                                     event->trans_lat_mtos);
        if (event->max_pdu_stom > 0)
          group->SetTransportLatency(le_audio::types::kLeAudioDirectionSource,
                                     event->trans_lat_stom);

        groupStateMachine_->ProcessHciNotifCisEstablished(group, leAudioDevice,
                                                          event);
      } break;
      case bluetooth::hci::iso_manager::kIsoEventCisDisconnected: {
        auto* event =
            static_cast<bluetooth::hci::iso_manager::cis_disconnected_evt*>(
                data);

        LeAudioDevice* leAudioDevice =
            leAudioDevices_.FindByCisConnHdl(event->cis_conn_hdl);
        if (!leAudioDevice) {
          LOG(ERROR) << __func__ << ", no bonded Le Audio Device with CIS: "
                     << +event->cis_conn_hdl;
          break;
        }
        LeAudioDeviceGroup* group =
            aseGroups_.FindById(leAudioDevice->group_id_);

        groupStateMachine_->ProcessHciNotifCisDisconnected(group, leAudioDevice,
                                                           event);
      } break;
      default:
        LOG(INFO) << ", Not handeled ISO event";
        break;
    }
  }

  void IsoSetupIsoDataPathCb(uint8_t status, uint16_t conn_handle,
                             uint8_t /* cig_id */) {
    LeAudioDevice* leAudioDevice =
        leAudioDevices_.FindByCisConnHdl(conn_handle);
    LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);

    instance->groupStateMachine_->ProcessHciNotifSetupIsoDataPath(
        group, leAudioDevice, status, conn_handle);
  }

  void IsoRemoveIsoDataPathCb(uint8_t status, uint16_t conn_handle,
                              uint8_t /* cig_id */) {
    LeAudioDevice* leAudioDevice =
        leAudioDevices_.FindByCisConnHdl(conn_handle);
    LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);

    instance->groupStateMachine_->ProcessHciNotifRemoveIsoDataPath(
        group, leAudioDevice, status, conn_handle);
  }

  void IsoLinkQualityReadCb(
      uint8_t conn_handle, uint8_t cig_id, uint32_t txUnackedPackets,
      uint32_t txFlushedPackets, uint32_t txLastSubeventPackets,
      uint32_t retransmittedPackets, uint32_t crcErrorPackets,
      uint32_t rxUnreceivedPackets, uint32_t duplicatePackets) {
    LeAudioDevice* leAudioDevice =
        leAudioDevices_.FindByCisConnHdl(conn_handle);
    if (!leAudioDevice) {
      LOG(WARNING) << __func__ << ", device under connection handle: "
                   << loghex(conn_handle)
                   << ", has been disconnecected in meantime";
      return;
    }
    LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);

    instance->groupStateMachine_->ProcessHciNotifIsoLinkQualityRead(
        group, leAudioDevice, conn_handle, txUnackedPackets, txFlushedPackets,
        txLastSubeventPackets, retransmittedPackets, crcErrorPackets,
        rxUnreceivedPackets, duplicatePackets);
  }

  void HandlePendingAvailableContexts(LeAudioDeviceGroup* group) {
    if (!group) return;

    /* Update group configuration with pending available context */
    std::optional<AudioContexts> pending_update_available_contexts =
        group->GetPendingUpdateAvailableContexts();
    if (pending_update_available_contexts) {
      std::optional<AudioContexts> updated_contexts =
          group->UpdateActiveContextsMap(*pending_update_available_contexts);

      if (updated_contexts) {
        callbacks_->OnAudioConf(group->audio_directions_, group->group_id_,
                                group->snk_audio_locations_.to_ulong(),
                                group->src_audio_locations_.to_ulong(),
                                updated_contexts->to_ulong());
      }

      group->SetPendingUpdateAvailableContexts(std::nullopt);
    }
  }

  void HandlePendingDeviceDisconnection(LeAudioDeviceGroup* group) {
    LOG_DEBUG();
    auto leAudioDevice = group->GetFirstDevice();
    while (leAudioDevice) {
      if (leAudioDevice->closing_stream_for_disconnection_) {
        leAudioDevice->closing_stream_for_disconnection_ = false;
        LOG_DEBUG("Disconnecting group id: %d, address: %s", group->group_id_,
                  leAudioDevice->address_.ToString().c_str());
        DisconnectDevice(leAudioDevice);
      }
      leAudioDevice = group->GetNextDevice(leAudioDevice);
    }
  }

  void StatusReportCb(int group_id, GroupStreamStatus status) {
    LOG(INFO) << __func__ << "status: " << static_cast<int>(status)
              << " audio_sender_state_: " << audio_sender_state_
              << " audio_receiver_state_: " << audio_receiver_state_;
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);
    switch (status) {
      case GroupStreamStatus::STREAMING:
        LOG_ASSERT(group_id == active_group_id_)
            << __func__ << " invalid group id " << group_id
            << " active_group_id_ " << active_group_id_;
        if (audio_sender_state_ == AudioState::READY_TO_START)
          StartSendingAudio(group_id);
        if (audio_receiver_state_ == AudioState::READY_TO_START)
          StartReceivingAudio(group_id);

        stream_setup_end_timestamp_ =
            bluetooth::common::time_get_os_boottime_us();
        le_audio::MetricsCollector::Get()->OnStreamStarted(
            active_group_id_, current_context_type_);
        break;
      case GroupStreamStatus::SUSPENDED:
        stream_setup_end_timestamp_ = 0;
        stream_setup_start_timestamp_ = 0;
        /** Stop Audio but don't release all the Audio resources */
        SuspendAudio();
        break;
      case GroupStreamStatus::CONFIGURED_BY_USER:
        /* We are done with reconfiguration.
         * Clean state and if Audio HAL is waiting, cancel the request
         * so Audio HAL can Resume again.
         */
        CancelStreamingRequest();
        HandlePendingAvailableContexts(group);
        break;
      case GroupStreamStatus::CONFIGURED_AUTONOMOUS:
        /* This state is notified only when
         * groups stays into CONFIGURED state after
         * STREAMING. Peer device uses cache. For the moment
         * it is handled same as IDLE
         */
        FALLTHROUGH;
      case GroupStreamStatus::IDLE: {
        stream_setup_end_timestamp_ = 0;
        stream_setup_start_timestamp_ = 0;
        if (group && group->IsPendingConfiguration()) {
          SuspendedForReconfiguration();
          if (groupStateMachine_->ConfigureStream(
                  group, current_context_type_,
                  GetCcid(current_context_type_))) {
            /* If configuration succeed wait for new status. */
            return;
          }
        }
        CancelStreamingRequest();
        if (group) {
          HandlePendingAvailableContexts(group);
          HandlePendingDeviceDisconnection(group);
        }
        break;
      }
      case GroupStreamStatus::RELEASING:
      case GroupStreamStatus::SUSPENDING:
        if (audio_sender_state_ != AudioState::IDLE)
          audio_sender_state_ = AudioState::RELEASING;

        if (audio_receiver_state_ != AudioState::IDLE)
          audio_receiver_state_ = AudioState::RELEASING;

        break;
      default:
        break;
    }
  }

 private:
  tGATT_IF gatt_if_;
  bluetooth::le_audio::LeAudioClientCallbacks* callbacks_;
  LeAudioDevices leAudioDevices_;
  LeAudioDeviceGroups aseGroups_;
  LeAudioGroupStateMachine* groupStateMachine_;
  int active_group_id_;
  LeAudioContextType current_context_type_;
  uint64_t stream_setup_start_timestamp_;
  uint64_t stream_setup_end_timestamp_;

  /* Microphone (s) */
  AudioState audio_receiver_state_;
  /* Speaker(s) */
  AudioState audio_sender_state_;

  /* Ccid informations */
  std::map<le_audio::types::LeAudioContextType /* context */, int /*ccid */>
      ccids_;

  /* Current stream configuration */
  LeAudioCodecConfiguration current_source_codec_config;
  LeAudioCodecConfiguration current_sink_codec_config;

  /* Static Audio Framework session configuration.
   *  Resampling will be done inside the bt stack
   */
  LeAudioCodecConfiguration audio_framework_source_config = {
      .num_channels = 2,
      .sample_rate = bluetooth::audio::le_audio::kSampleRate48000,
      .bits_per_sample = bluetooth::audio::le_audio::kBitsPerSample16,
      .data_interval_us = LeAudioCodecConfiguration::kInterval10000Us,
  };

  LeAudioCodecConfiguration audio_framework_sink_config = {
      .num_channels = 2,
      .sample_rate = bluetooth::audio::le_audio::kSampleRate16000,
      .bits_per_sample = bluetooth::audio::le_audio::kBitsPerSample16,
      .data_interval_us = LeAudioCodecConfiguration::kInterval10000Us,
  };

  void* lc3_encoder_left_mem;
  void* lc3_encoder_right_mem;

  lc3_encoder_t lc3_encoder_left;
  lc3_encoder_t lc3_encoder_right;

  void* lc3_decoder_left_mem;
  void* lc3_decoder_right_mem;

  lc3_decoder_t lc3_decoder_left;
  lc3_decoder_t lc3_decoder_right;

  std::vector<uint8_t> encoded_data;
  const void* audio_source_instance_;
  const void* audio_sink_instance_;
  static constexpr uint64_t kAudioSuspentKeepIsoAliveTimeoutMs = 5000;
  static constexpr char kAudioSuspentKeepIsoAliveTimeoutMsProp[] =
      "persist.bluetooth.leaudio.audio.suspend.timeoutms";
  alarm_t* suspend_timeout_;

  std::vector<int16_t> cached_channel_data_;
  uint32_t cached_channel_timestamp_ = 0;
  uint32_t cached_channel_is_left_;

  void ClientAudioIntefraceRelease() {
    if (audio_source_instance_) {
      leAudioClientAudioSource->Stop();
      leAudioClientAudioSource->Release(audio_source_instance_);
      audio_source_instance_ = nullptr;
    }

    if (audio_sink_instance_) {
      leAudioClientAudioSink->Stop();
      leAudioClientAudioSink->Release(audio_sink_instance_);
      audio_sink_instance_ = nullptr;
    }
    le_audio::MetricsCollector::Get()->OnStreamEnded(active_group_id_);
  }
};

/* This is a generic callback method for gatt client which handles every client
 * application events.
 */
void le_audio_gattc_callback(tBTA_GATTC_EVT event, tBTA_GATTC* p_data) {
  if (!p_data || !instance) return;

  DLOG(INFO) << __func__ << " event = " << +event;

  switch (event) {
    case BTA_GATTC_DEREG_EVT:
      break;

    case BTA_GATTC_NOTIF_EVT:
      instance->LeAudioCharValueHandle(
          p_data->notify.conn_id, p_data->notify.handle, p_data->notify.len,
          static_cast<uint8_t*>(p_data->notify.value));

      if (!p_data->notify.is_notify)
        BTA_GATTC_SendIndConfirm(p_data->notify.conn_id, p_data->notify.handle);

      break;

    case BTA_GATTC_OPEN_EVT:
      instance->OnGattConnected(p_data->open.status, p_data->open.conn_id,
                                p_data->open.client_if, p_data->open.remote_bda,
                                p_data->open.transport, p_data->open.mtu);
      break;

    case BTA_GATTC_ENC_CMPL_CB_EVT: {
      uint8_t encryption_status;
      if (BTM_IsEncrypted(p_data->enc_cmpl.remote_bda, BT_TRANSPORT_LE)) {
        encryption_status = BTM_SUCCESS;
      } else {
        encryption_status = BTM_FAILED_ON_SECURITY;
      }
      instance->OnEncryptionComplete(p_data->enc_cmpl.remote_bda,
                                     encryption_status);
    } break;

    case BTA_GATTC_CLOSE_EVT:
      instance->OnGattDisconnected(
          p_data->close.conn_id, p_data->close.client_if,
          p_data->close.remote_bda, p_data->close.reason);
      break;

    case BTA_GATTC_SEARCH_CMPL_EVT:
      instance->OnServiceSearchComplete(p_data->search_cmpl.conn_id,
                                        p_data->search_cmpl.status);
      break;

    case BTA_GATTC_SRVC_DISC_DONE_EVT:
      instance->OnGattServiceDiscoveryDone(p_data->service_changed.remote_bda);
      break;

    case BTA_GATTC_SRVC_CHG_EVT:
      instance->OnServiceChangeEvent(p_data->remote_bda);
      break;
    case BTA_GATTC_CFG_MTU_EVT:
      break;

    default:
      break;
  }
}

class LeAudioStateMachineHciCallbacksImpl : public CigCallbacks {
 public:
  void OnCigEvent(uint8_t event, void* data) override {
    if (instance) instance->IsoCigEventsCb(event, data);
  }

  void OnCisEvent(uint8_t event, void* data) override {
    if (instance) instance->IsoCisEventsCb(event, data);
  }

  void OnSetupIsoDataPath(uint8_t status, uint16_t conn_handle,
                          uint8_t cig_id) override {
    if (instance) instance->IsoSetupIsoDataPathCb(status, conn_handle, cig_id);
  }

  void OnRemoveIsoDataPath(uint8_t status, uint16_t conn_handle,
                           uint8_t cig_id) override {
    if (instance) instance->IsoRemoveIsoDataPathCb(status, conn_handle, cig_id);
  }

  void OnIsoLinkQualityRead(
      uint8_t conn_handle, uint8_t cig_id, uint32_t txUnackedPackets,
      uint32_t txFlushedPackets, uint32_t txLastSubeventPackets,
      uint32_t retransmittedPackets, uint32_t crcErrorPackets,
      uint32_t rxUnreceivedPackets, uint32_t duplicatePackets) {
    if (instance)
      instance->IsoLinkQualityReadCb(conn_handle, cig_id, txUnackedPackets,
                                     txFlushedPackets, txLastSubeventPackets,
                                     retransmittedPackets, crcErrorPackets,
                                     rxUnreceivedPackets, duplicatePackets);
  }
};

LeAudioStateMachineHciCallbacksImpl stateMachineHciCallbacksImpl;

class CallbacksImpl : public LeAudioGroupStateMachine::Callbacks {
 public:
  void StatusReportCb(int group_id, GroupStreamStatus status) override {
    if (instance) instance->StatusReportCb(group_id, status);
  }

  void OnStateTransitionTimeout(int group_id) override {
    if (instance) instance->OnLeAudioDeviceSetStateTimeout(group_id);
  }
};

CallbacksImpl stateMachineCallbacksImpl;

class LeAudioClientAudioSinkReceiverImpl
    : public LeAudioClientAudioSinkReceiver {
 public:
  void OnAudioDataReady(const std::vector<uint8_t>& data) override {
    if (instance) instance->OnAudioDataReady(data);
  }
  void OnAudioSuspend(std::promise<void> do_suspend_promise) override {
    if (instance) instance->OnAudioSinkSuspend();
    do_suspend_promise.set_value();
  }

  void OnAudioResume(void) override {
    if (instance) instance->OnAudioSinkResume();
  }

  void OnAudioMetadataUpdate(
      std::promise<void> do_metadata_update_promise,
      const source_metadata_t& source_metadata) override {
    if (instance) instance->OnAudioMetadataUpdate(source_metadata);
    do_metadata_update_promise.set_value();
  }
};

class LeAudioClientAudioSourceReceiverImpl
    : public LeAudioClientAudioSourceReceiver {
 public:
  void OnAudioSuspend(std::promise<void> do_suspend_promise) override {
    if (instance) instance->OnAudioSourceSuspend();
    do_suspend_promise.set_value();
  }
  void OnAudioResume(void) override {
    if (instance) instance->OnAudioSourceResume();
  }

  void OnAudioMetadataUpdate(std::promise<void> do_metadata_update_promise,
                             const sink_metadata_t& sink_metadata) override {
    if (instance) instance->OnAudioSourceMetadataUpdate(sink_metadata);
    do_metadata_update_promise.set_value();
  }
};

LeAudioClientAudioSinkReceiverImpl audioSinkReceiverImpl;
LeAudioClientAudioSourceReceiverImpl audioSourceReceiverImpl;

class DeviceGroupsCallbacksImpl : public DeviceGroupsCallbacks {
 public:
  void OnGroupAdded(const RawAddress& address, const bluetooth::Uuid& uuid,
                    int group_id) override {
    if (instance) instance->OnGroupAddedCb(address, uuid, group_id);
  }
  void OnGroupMemberAdded(const RawAddress& address, int group_id) override {
    if (instance) instance->OnGroupMemberAddedCb(address, group_id);
  }
  void OnGroupMemberRemoved(const RawAddress& address, int group_id) override {
    if (instance) instance->OnGroupMemberRemovedCb(address, group_id);
  }
  void OnGroupRemoved(const bluetooth::Uuid& uuid, int group_id) {
    /* to implement if needed */
  }
  void OnGroupAddFromStorage(const RawAddress& address,
                             const bluetooth::Uuid& uuid, int group_id) {
    /* to implement if needed */
  }
};

class DeviceGroupsCallbacksImpl;
DeviceGroupsCallbacksImpl deviceGroupsCallbacksImpl;

}  // namespace

void LeAudioClient::AddFromStorage(const RawAddress& addr, bool autoconnect) {
  if (!instance) {
    LOG(ERROR) << "Not initialized yet";
    return;
  }

  instance->AddFromStorage(addr, autoconnect);
}

bool LeAudioClient::IsLeAudioClientRunning(void) { return instance != nullptr; }

LeAudioClient* LeAudioClient::Get() {
  CHECK(instance);
  return instance;
}

/* Initializer of main le audio implementation class and its instance */
void LeAudioClient::Initialize(
    bluetooth::le_audio::LeAudioClientCallbacks* callbacks_,
    base::Closure initCb, base::Callback<bool()> hal_2_1_verifier,
    const std::vector<bluetooth::le_audio::btle_audio_codec_config_t>&
        offloading_preference) {
  if (instance) {
    LOG(ERROR) << "Already initialized";
    return;
  }

  if (!controller_get_interface()
           ->supports_ble_connected_isochronous_stream_central() &&
      !controller_get_interface()
           ->supports_ble_connected_isochronous_stream_peripheral()) {
    LOG(ERROR) << "Controller reports no ISO support."
                  " LeAudioClient Init aborted.";
    return;
  }

  LOG_ASSERT(std::move(hal_2_1_verifier).Run())
      << __func__
      << ", LE Audio Client requires Bluetooth Audio HAL V2.1 at least. Either "
         "disable LE Audio Profile, or update your HAL";

  IsoManager::GetInstance()->Start();

  if (leAudioClientAudioSource == nullptr)
    leAudioClientAudioSource = new LeAudioUnicastClientAudioSource();
  if (leAudioClientAudioSink == nullptr)
    leAudioClientAudioSink = new LeAudioUnicastClientAudioSink();

  audioSinkReceiver = &audioSinkReceiverImpl;
  audioSourceReceiver = &audioSourceReceiverImpl;
  stateMachineHciCallbacks = &stateMachineHciCallbacksImpl;
  stateMachineCallbacks = &stateMachineCallbacksImpl;
  device_group_callbacks = &deviceGroupsCallbacksImpl;
  instance = new LeAudioClientImpl(callbacks_, stateMachineCallbacks, initCb);

  IsoManager::GetInstance()->RegisterCigCallbacks(stateMachineHciCallbacks);
  CodecManager::GetInstance()->Start(offloading_preference);

  callbacks_->OnInitialized();
}

void LeAudioClient::DebugDump(int fd) {
  DeviceGroups::DebugDump(fd);

  dprintf(fd, "LeAudio Manager: \n");
  if (instance)
    instance->Dump(fd);
  else
    dprintf(fd, "  Not initialized \n");

  LeAudioUnicastClientAudioSource::DebugDump(fd);
  LeAudioUnicastClientAudioSink::DebugDump(fd);
  le_audio::AudioSetConfigurationProvider::Get()->DebugDump(fd);
  IsoManager::GetInstance()->Dump(fd);
  dprintf(fd, "\n");
}

void LeAudioClient::Cleanup(base::Callback<void()> cleanupCb) {
  if (!instance) {
    LOG(ERROR) << "Not initialized";
    return;
  }

  LeAudioClientImpl* ptr = instance;
  instance = nullptr;
  ptr->Cleanup(cleanupCb);
  delete ptr;
  ptr = nullptr;
  if (leAudioClientAudioSource) {
    delete leAudioClientAudioSource;
    leAudioClientAudioSource = nullptr;
  }

  if (leAudioClientAudioSink) {
    delete leAudioClientAudioSink;
    leAudioClientAudioSink = nullptr;
  }

  CodecManager::GetInstance()->Stop();
  LeAudioGroupStateMachine::Cleanup();
  IsoManager::GetInstance()->Stop();
  le_audio::MetricsCollector::Get()->Flush();
}

void LeAudioClient::InitializeAudioClients(
    LeAudioUnicastClientAudioSource* clientAudioSource,
    LeAudioUnicastClientAudioSink* clientAudioSink) {
  if (leAudioClientAudioSource || leAudioClientAudioSink) {
    LOG(WARNING) << __func__ << ", audio clients already initialized";
    return;
  }

  leAudioClientAudioSource = clientAudioSource;
  leAudioClientAudioSink = clientAudioSink;
}
