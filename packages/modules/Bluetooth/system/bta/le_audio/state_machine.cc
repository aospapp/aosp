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

#include "state_machine.h"

#include <base/bind.h>
#include <base/callback.h>

#include <map>

#include "bt_types.h"
#include "bta_gatt_queue.h"
#include "bta_le_audio_api.h"
#include "btm_iso_api.h"
#include "client_parser.h"
#include "codec_manager.h"
#include "devices.h"
#include "gd/common/strings.h"
#include "hcimsgs.h"
#include "le_audio_types.h"
#include "osi/include/alarm.h"
#include "osi/include/log.h"
#include "osi/include/osi.h"
#include "osi/include/properties.h"

// clang-format off
/* ASCS state machine 1.0
 *
 * State machine manages group of ASEs to make transition from one state to
 * another according to specification and keeping involved necessary externals
 * like: ISO, CIG, ISO data path, audio path form/to upper layer.
 *
 * GroupStream (API): GroupStream method of this le audio implementation class
 *                    object should allow transition from Idle (No Caching),
 *                    Codec Configured (Caching after release) state to
 *                    Streaming for all ASEs in group within time limit. Time
 *                    limit should keep safe whole state machine from being
 *                    stucked in any in-middle state, which is not a destination
 *                    state.
 *
 *                    TODO Second functionality of streaming should be switch
 *                    context which will base on previous state, context type.
 *
 * GroupStop (API): GroupStop method of this le audio implementation class
 *                  object should allow safe transition from any state to Idle
 *                  or Codec Configured (if caching supported).
 *
 * ╔══════════════════╦═════════════════════════════╦══════════════╦══════════════════╦══════╗
 * ║  Current State   ║ ASE Control Point Operation ║    Result    ║    Next State    ║ Note ║
 * ╠══════════════════╬═════════════════════════════╬══════════════╬══════════════════╬══════╣
 * ║ Idle             ║ Config Codec                ║ Success      ║ Codec Configured ║  +   ║
 * ║ Codec Configured ║ Config Codec                ║ Success      ║ Codec Configured ║  -   ║
 * ║ Codec Configured ║ Release                     ║ Success      ║ Releasing        ║  +   ║
 * ║ Codec Configured ║ Config QoS                  ║ Success      ║ QoS Configured   ║  +   ║
 * ║ QoS Configured   ║ Config Codec                ║ Success      ║ Codec Configured ║  -   ║
 * ║ QoS Configured   ║ Config QoS                  ║ Success      ║ QoS Configured   ║  -   ║
 * ║ QoS Configured   ║ Release                     ║ Success      ║ Releasing        ║  +   ║
 * ║ QoS Configured   ║ Enable                      ║ Success      ║ Enabling         ║  +   ║
 * ║ Enabling         ║ Release                     ║ Success      ║ Releasing        ║  +   ║
 * ║ Enabling         ║ Update Metadata             ║ Success      ║ Enabling         ║  -   ║
 * ║ Enabling         ║ Disable                     ║ Success      ║ Disabling        ║  -   ║
 * ║ Enabling         ║ Receiver Start Ready        ║ Success      ║ Streaming        ║  +   ║
 * ║ Streaming        ║ Update Metadata             ║ Success      ║ Streaming        ║  -   ║
 * ║ Streaming        ║ Disable                     ║ Success      ║ Disabling        ║  +   ║
 * ║ Streaming        ║ Release                     ║ Success      ║ Releasing        ║  +   ║
 * ║ Disabling        ║ Receiver Stop Ready         ║ Success      ║ QoS Configured   ║  +   ║
 * ║ Disabling        ║ Release                     ║ Success      ║ Releasing        ║  +   ║
 * ║ Releasing        ║ Released (no caching)       ║ Success      ║ Idle             ║  +   ║
 * ║ Releasing        ║ Released (caching)          ║ Success      ║ Codec Configured ║  -   ║
 * ╚══════════════════╩═════════════════════════════╩══════════════╩══════════════════╩══════╝
 *
 * + - supported transition
 * - - not supported
 */
// clang-format on

using bluetooth::common::ToString;
using bluetooth::hci::IsoManager;
using bluetooth::le_audio::GroupStreamStatus;
using le_audio::CodecManager;
using le_audio::LeAudioDevice;
using le_audio::LeAudioDeviceGroup;
using le_audio::LeAudioGroupStateMachine;

using le_audio::types::ase;
using le_audio::types::AseState;
using le_audio::types::AudioStreamDataPathState;
using le_audio::types::CodecLocation;

namespace {

constexpr int linkQualityCheckInterval = 4000;

static void link_quality_cb(void* data) {
  // very ugly, but we need to pass just two bytes
  uint16_t cis_conn_handle = *((uint16_t*)data);

  IsoManager::GetInstance()->ReadIsoLinkQuality(cis_conn_handle);
}

class LeAudioGroupStateMachineImpl;
LeAudioGroupStateMachineImpl* instance;

class LeAudioGroupStateMachineImpl : public LeAudioGroupStateMachine {
 public:
  LeAudioGroupStateMachineImpl(Callbacks* state_machine_callbacks_)
      : state_machine_callbacks_(state_machine_callbacks_),
        watchdog_(alarm_new("LeAudioStateMachineTimer")) {}

  ~LeAudioGroupStateMachineImpl() {
    alarm_free(watchdog_);
    watchdog_ = nullptr;
  }

  bool AttachToStream(LeAudioDeviceGroup* group,
                      LeAudioDevice* leAudioDevice) override {
    LOG(INFO) << __func__ << " group id: " << group->group_id_
              << " device: " << leAudioDevice->address_;

    /* This function is used to attach the device to the stream.
     * Limitation here is that device should be previously in the streaming
     * group and just got reconnected.
     */
    if (group->GetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      LOG_ERROR(" group not in the streaming state: %s",
                ToString(group->GetState()).c_str());
      return false;
    }

    PrepareAndSendCodecConfigure(group, leAudioDevice);
    return true;
  }

  bool StartStream(LeAudioDeviceGroup* group,
                   le_audio::types::LeAudioContextType context_type,
                   int ccid) override {
    LOG_INFO(" current state: %s", ToString(group->GetState()).c_str());

    switch (group->GetState()) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED:
        if (group->GetCurrentContextType() == context_type) {
          group->Activate();
          SetTargetState(group, AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
          CigCreate(group);
          return true;
        }

        /* If configuration is needed */
        FALLTHROUGH;
      case AseState::BTA_LE_AUDIO_ASE_STATE_IDLE:
        if (!group->Configure(context_type, ccid)) {
          LOG(ERROR) << __func__ << ", failed to set ASE configuration";
          return false;
        }

        /* All ASEs should aim to achieve target state */
        group->SetContextType(context_type);
        SetTargetState(group, AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
        PrepareAndSendCodecConfigure(group, group->GetFirstActiveDevice());
        break;

      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED: {
        LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
        if (!leAudioDevice) {
          LOG(ERROR) << __func__ << ", group has no active devices";
          return false;
        }

        /* All ASEs should aim to achieve target state */
        SetTargetState(group, AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
        PrepareAndSendEnable(leAudioDevice);
        break;
      }

      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING: {
        /* This case just updates the metadata for the stream, in case
         * stream configuration is satisfied
         */
        if (!group->IsMetadataChanged(context_type, ccid)) return true;

        LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
        if (!leAudioDevice) {
          LOG(ERROR) << __func__ << ", group has no active devices";
          return false;
        }

        PrepareAndSendUpdateMetadata(group, leAudioDevice, context_type, ccid);
        break;
      }

      default:
        LOG_ERROR("Unable to transit from %s",
                  ToString(group->GetState()).c_str());
        return false;
    }

    return true;
  }

  bool ConfigureStream(LeAudioDeviceGroup* group,
                       le_audio::types::LeAudioContextType context_type,
                       int ccid) override {
    if (group->GetState() > AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED) {
      LOG_ERROR(
          "Stream should be stopped or in configured stream. Current state: %s",
          ToString(group->GetState()).c_str());
      return false;
    }

    if (!group->Configure(context_type, ccid)) {
      LOG_ERROR("Could not configure ASEs for group %d content type %d",
                group->group_id_, int(context_type));

      return false;
    }

    SetTargetState(group, AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);
    PrepareAndSendCodecConfigure(group, group->GetFirstActiveDevice());

    return true;
  }

  void SuspendStream(LeAudioDeviceGroup* group) override {
    LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
    LOG_ASSERT(leAudioDevice)
        << __func__ << " Shouldn't be called without an active device.";

    /* All ASEs should aim to achieve target state */
    SetTargetState(group, AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
    PrepareAndSendDisable(leAudioDevice);
    state_machine_callbacks_->StatusReportCb(group->group_id_,
                                             GroupStreamStatus::SUSPENDING);
  }

  void StopStream(LeAudioDeviceGroup* group) override {
    if (group->IsReleasing()) {
      LOG(INFO) << __func__ << ", group: " << group->group_id_
                << " already in releasing process";
      return;
    }

    LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
    if (leAudioDevice == nullptr) {
      LOG(ERROR) << __func__
                 << " Shouldn't be called without an active device.";
      state_machine_callbacks_->StatusReportCb(group->group_id_,
                                               GroupStreamStatus::IDLE);
      return;
    }

    /* All Ases should aim to achieve target state */
    SetTargetState(group, AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
    PrepareAndSendRelease(leAudioDevice);
    state_machine_callbacks_->StatusReportCb(group->group_id_,
                                             GroupStreamStatus::RELEASING);
  }

  void ProcessGattNotifEvent(uint8_t* value, uint16_t len, struct ase* ase,
                             LeAudioDevice* leAudioDevice,
                             LeAudioDeviceGroup* group) override {
    struct le_audio::client_parser::ascs::ase_rsp_hdr arh;

    ParseAseStatusHeader(arh, len, value);

    LOG_INFO(" %s , ASE id: %d, state changed %s -> %s ",
             leAudioDevice->address_.ToString().c_str(), +ase->id,
             ToString(ase->state).c_str(),
             ToString(AseState(arh.state)).c_str());

    switch (static_cast<AseState>(arh.state)) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_IDLE:
        AseStateMachineProcessIdle(arh, ase, group, leAudioDevice);
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED:
        AseStateMachineProcessCodecConfigured(
            arh, ase, value + le_audio::client_parser::ascs::kAseRspHdrMinLen,
            len - le_audio::client_parser::ascs::kAseRspHdrMinLen, group,
            leAudioDevice);
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED:
        AseStateMachineProcessQosConfigured(arh, ase, group, leAudioDevice);
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING:
        AseStateMachineProcessEnabling(arh, ase, group, leAudioDevice);
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING:
        AseStateMachineProcessStreaming(
            arh, ase, value + le_audio::client_parser::ascs::kAseRspHdrMinLen,
            len - le_audio::client_parser::ascs::kAseRspHdrMinLen, group,
            leAudioDevice);
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING:
        AseStateMachineProcessDisabling(arh, ase, group, leAudioDevice);
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING:
        AseStateMachineProcessReleasing(arh, ase, group, leAudioDevice);
        break;
      default:
        LOG(ERROR) << __func__
                   << ", Wrong AES status: " << static_cast<int>(arh.state);
        StopStream(group);
        break;
    }
  }

  void ProcessHciNotifOnCigCreate(LeAudioDeviceGroup* group, uint8_t status,
                                  uint8_t cig_id,
                                  std::vector<uint16_t> conn_handles) override {
    uint8_t i = 0;
    LeAudioDevice* leAudioDevice;
    struct le_audio::types::ase* ase;

    /* TODO: What if not all cises will be configured ?
     * conn_handle.size() != active ases in group
     */

    if (!group) {
      LOG_ERROR(", group is null");
      return;
    }

    if (status != HCI_SUCCESS) {
      group->cig_state_ = le_audio::types::CigState::NONE;
      LOG_ERROR(", failed to create CIG, reason: 0x%02x, new cig state: %s",
                +status, ToString(group->cig_state_).c_str());
      StopStream(group);
      return;
    }

    ASSERT_LOG(group->cig_state_ == le_audio::types::CigState::CREATING,
               "Unexpected CIG creation group id: %d, cig state: %s",
               group->group_id_, ToString(group->cig_state_).c_str());

    group->cig_state_ = le_audio::types::CigState::CREATED;
    LOG_INFO("Group: %p, id: %d cig state: %s, number of cis handles: %d",
             group, group->group_id_, ToString(group->cig_state_).c_str(),
             static_cast<int>(conn_handles.size()));

    /* Assign all connection handles to ases. CIS ID order is represented by the
     * order of active ASEs in active leAudioDevices
     */

    leAudioDevice = group->GetFirstActiveDevice();
    LOG_ASSERT(leAudioDevice)
        << __func__ << " Shouldn't be called without an active device.";

    /* Assign all connection handles to ases */
    do {
      ase = leAudioDevice->GetFirstActiveAseByDataPathState(
          AudioStreamDataPathState::IDLE);
      LOG_ASSERT(ase) << __func__
                      << " shouldn't be called without an active ASE";
      do {
        auto ases_pair = leAudioDevice->GetAsesByCisId(ase->cis_id);

        if (ases_pair.sink && ases_pair.sink->active) {
          ases_pair.sink->cis_conn_hdl = conn_handles[i];
          ases_pair.sink->data_path_state =
              AudioStreamDataPathState::CIS_ASSIGNED;
        }
        if (ases_pair.source && ases_pair.source->active) {
          ases_pair.source->cis_conn_hdl = conn_handles[i];
          ases_pair.source->data_path_state =
              AudioStreamDataPathState::CIS_ASSIGNED;
        }
        i++;
      } while ((ase = leAudioDevice->GetFirstActiveAseByDataPathState(
                    AudioStreamDataPathState::IDLE)) &&
               (i < conn_handles.size()));
    } while ((leAudioDevice = group->GetNextActiveDevice(leAudioDevice)) &&
             (i < conn_handles.size()));

    /* Last node configured, process group to codec configured state */
    group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);

    if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      StartConfigQoSForTheGroup(group);
    } else {
      LOG_ERROR(", invalid state transition, from: %s , to: %s",
                ToString(group->GetState()).c_str(),
                ToString(group->GetTargetState()).c_str());
      StopStream(group);
      return;
    }
  }

  void FreeLinkQualityReports(LeAudioDevice* leAudioDevice) {
    if (leAudioDevice->link_quality_timer == nullptr) return;

    alarm_free(leAudioDevice->link_quality_timer);
    leAudioDevice->link_quality_timer = nullptr;
  }

  void ProcessHciNotifOnCigRemove(uint8_t status,
                                  LeAudioDeviceGroup* group) override {
    if (status) {
      group->cig_state_ = le_audio::types::CigState::CREATED;
      LOG_ERROR(
          "failed to remove cig, id: %d, status 0x%02x, new cig state: %s",
          group->group_id_, +status, ToString(group->cig_state_).c_str());
      return;
    }

    ASSERT_LOG(group->cig_state_ == le_audio::types::CigState::REMOVING,
               "Unexpected CIG remove group id: %d, cig state %s",
               group->group_id_, ToString(group->cig_state_).c_str());

    group->cig_state_ = le_audio::types::CigState::NONE;

    LeAudioDevice* leAudioDevice = group->GetFirstDevice();
    if (!leAudioDevice) return;

    do {
      FreeLinkQualityReports(leAudioDevice);

      for (auto& ase : leAudioDevice->ases_) {
        ase.data_path_state = AudioStreamDataPathState::IDLE;
      }
    } while ((leAudioDevice = group->GetNextDevice(leAudioDevice)));
  }

  void ProcessHciNotifSetupIsoDataPath(LeAudioDeviceGroup* group,
                                       LeAudioDevice* leAudioDevice,
                                       uint8_t status,
                                       uint16_t conn_handle) override {
    if (status) {
      LOG(ERROR) << __func__ << ", failed to setup data path";
      StopStream(group);

      return;
    }

    /* Update state for the given cis.*/
    auto ase = leAudioDevice->GetFirstActiveAseByDataPathState(
        AudioStreamDataPathState::CIS_ESTABLISHED);

    if (ase->cis_conn_hdl != conn_handle) {
      LOG(ERROR) << __func__ << " Cannot find ase by handle " << +conn_handle;
      return;
    }

    ase->data_path_state = AudioStreamDataPathState::DATA_PATH_ESTABLISHED;

    if (group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      LOG(WARNING) << __func__ << " Group " << group->group_id_
                   << " is not targeting streaming state any more";
      return;
    }

    ase = leAudioDevice->GetNextActiveAse(ase);
    if (!ase) {
      leAudioDevice = group->GetNextActiveDevice(leAudioDevice);

      if (!leAudioDevice) {
        state_machine_callbacks_->StatusReportCb(group->group_id_,
                                                 GroupStreamStatus::STREAMING);
        return;
      }

      ase = leAudioDevice->GetFirstActiveAse();
    }

    LOG_ASSERT(ase) << __func__ << " shouldn't be called without an active ASE";
    if (ase->data_path_state == AudioStreamDataPathState::CIS_ESTABLISHED)
      PrepareDataPath(ase);
    else
      LOG(ERROR) << __func__
                 << " CIS got disconnected? handle: " << +ase->cis_conn_hdl;
  }

  void ProcessHciNotifRemoveIsoDataPath(LeAudioDeviceGroup* group,
                                        LeAudioDevice* leAudioDevice,
                                        uint8_t status,
                                        uint16_t conn_hdl) override {
    if (status != HCI_SUCCESS) {
      LOG(ERROR) << __func__ << ", failed to remove ISO data path, reason: "
                 << loghex(status);
      StopStream(group);

      return;
    }

    bool do_disconnect = false;

    auto ases_pair = leAudioDevice->GetAsesByCisConnHdl(conn_hdl);
    if (ases_pair.sink && (ases_pair.sink->data_path_state ==
                           AudioStreamDataPathState::DATA_PATH_ESTABLISHED)) {
      ases_pair.sink->data_path_state =
          AudioStreamDataPathState::CIS_DISCONNECTING;
      do_disconnect = true;
    }

    if (ases_pair.source &&
        ases_pair.source->data_path_state ==
            AudioStreamDataPathState::DATA_PATH_ESTABLISHED) {
      ases_pair.source->data_path_state =
          AudioStreamDataPathState::CIS_DISCONNECTING;
      do_disconnect = true;
    }

    if (do_disconnect)
      IsoManager::GetInstance()->DisconnectCis(conn_hdl, HCI_ERR_PEER_USER);
  }

  void ProcessHciNotifIsoLinkQualityRead(
      LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
      uint8_t conn_handle, uint32_t txUnackedPackets, uint32_t txFlushedPackets,
      uint32_t txLastSubeventPackets, uint32_t retransmittedPackets,
      uint32_t crcErrorPackets, uint32_t rxUnreceivedPackets,
      uint32_t duplicatePackets) {
    LOG(INFO) << "conn_handle: " << loghex(conn_handle)
              << ", txUnackedPackets: " << loghex(txUnackedPackets)
              << ", txFlushedPackets: " << loghex(txFlushedPackets)
              << ", txLastSubeventPackets: " << loghex(txLastSubeventPackets)
              << ", retransmittedPackets: " << loghex(retransmittedPackets)
              << ", crcErrorPackets: " << loghex(crcErrorPackets)
              << ", rxUnreceivedPackets: " << loghex(rxUnreceivedPackets)
              << ", duplicatePackets: " << loghex(duplicatePackets);
  }

  void ReleaseCisIds(LeAudioDeviceGroup* group) {
    if (group == nullptr) {
      LOG_DEBUG(" Group is null.");
      return;
    }
    LOG_DEBUG(" Releasing CIS is for group %d", group->group_id_);

    LeAudioDevice* leAudioDevice = group->GetFirstDevice();
    while (leAudioDevice != nullptr) {
      for (auto& ase : leAudioDevice->ases_) {
        ase.cis_id = le_audio::kInvalidCisId;
      }
      leAudioDevice = group->GetNextDevice(leAudioDevice);
    }
  }

  void RemoveCigForGroup(LeAudioDeviceGroup* group) {
    LOG_DEBUG("Group: %p, id: %d cig state: %s", group, group->group_id_,
              ToString(group->cig_state_).c_str());
    if (group->cig_state_ != le_audio::types::CigState::CREATED) {
      LOG_WARN("Group: %p, id: %d cig state: %s cannot be removed", group,
               group->group_id_, ToString(group->cig_state_).c_str());
      return;
    }

    group->cig_state_ = le_audio::types::CigState::REMOVING;
    IsoManager::GetInstance()->RemoveCig(group->group_id_);
    LOG_DEBUG("Group: %p, id: %d cig state: %s", group, group->group_id_,
              ToString(group->cig_state_).c_str());
  }

  void ProcessHciNotifAclDisconnected(LeAudioDeviceGroup* group,
                                      LeAudioDevice* leAudioDevice) {
    FreeLinkQualityReports(leAudioDevice);
    leAudioDevice->conn_id_ = GATT_INVALID_CONN_ID;

    if (!group) {
      LOG(ERROR) << __func__
                 << " group is null for device: " << leAudioDevice->address_
                 << " group_id: " << leAudioDevice->group_id_;
      return;
    }

    /* If group is in Idle there is nothing to do here */
    if ((group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_IDLE) &&
        (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_IDLE)) {
      LOG(INFO) << __func__ << " group: " << group->group_id_ << " is in IDLE";
      return;
    }

    auto* stream_conf = &group->stream_conf;
    if (!stream_conf->sink_streams.empty() ||
        !stream_conf->source_streams.empty()) {
      stream_conf->sink_streams.erase(
          std::remove_if(stream_conf->sink_streams.begin(),
                         stream_conf->sink_streams.end(),
                         [leAudioDevice](auto& pair) {
                           auto ases =
                               leAudioDevice->GetAsesByCisConnHdl(pair.first);
                           return ases.sink;
                         }),
          stream_conf->sink_streams.end());

      stream_conf->source_streams.erase(
          std::remove_if(stream_conf->source_streams.begin(),
                         stream_conf->source_streams.end(),
                         [leAudioDevice](auto& pair) {
                           auto ases =
                               leAudioDevice->GetAsesByCisConnHdl(pair.first);
                           return ases.source;
                         }),
          stream_conf->source_streams.end());
    }

    /* mark ASEs as not used. */
    leAudioDevice->DeactivateAllAses();

    DLOG(INFO) << __func__ << " device: " << leAudioDevice->address_
               << " group connected: " << group->IsAnyDeviceConnected()
               << " all active ase disconnected: "
               << group->HaveAllActiveDevicesCisDisc();

    /* Group has changed. Lets update available contexts */
    group->UpdateActiveContextsMap();

    /* ACL of one of the device has been dropped.
     * If there is active CIS, do nothing here. Just update the active contexts
     * table
     */
    if (group->IsAnyDeviceConnected() &&
        !group->HaveAllActiveDevicesCisDisc()) {
      return;
    }

    /* Group is not connected and all the CISes are down.
     * Clean states and destroy HCI group
     */
    group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
    group->SetTargetState(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
    if (alarm_is_scheduled(watchdog_)) alarm_cancel(watchdog_);
    ReleaseCisIds(group);
    state_machine_callbacks_->StatusReportCb(group->group_id_,
                                             GroupStreamStatus::IDLE);
    RemoveCigForGroup(group);
  }

  void ProcessHciNotifCisEstablished(
      LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
      const bluetooth::hci::iso_manager::cis_establish_cmpl_evt* event)
      override {
    std::vector<uint8_t> value;

    auto ases_pair = leAudioDevice->GetAsesByCisConnHdl(event->cis_conn_hdl);

    if (event->status) {
      if (ases_pair.sink)
        ases_pair.sink->data_path_state =
            AudioStreamDataPathState::CIS_ASSIGNED;
      if (ases_pair.source)
        ases_pair.source->data_path_state =
            AudioStreamDataPathState::CIS_ASSIGNED;

      /* CIS establishment failed. Remove CIG if no other CIS is already created
       * or pending. If CIS is established, this will be handled in disconnected
       * complete event
       */
      if (group->HaveAllActiveDevicesCisDisc()) {
        RemoveCigForGroup(group);
      }

      LOG(ERROR) << __func__
                 << ", failed to create CIS, status: " << loghex(event->status);

      StopStream(group);
      return;
    }

    if (group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      LOG(ERROR) << __func__
                 << ", Unintended CIS establishement event came for group id:"
                 << group->group_id_;
      StopStream(group);
      return;
    }

    if (ases_pair.sink)
      ases_pair.sink->data_path_state =
          AudioStreamDataPathState::CIS_ESTABLISHED;
    if (ases_pair.source)
      ases_pair.source->data_path_state =
          AudioStreamDataPathState::CIS_ESTABLISHED;

    if (osi_property_get_bool("persist.bluetooth.iso_link_quality_report",
                              false)) {
      leAudioDevice->link_quality_timer =
          alarm_new_periodic("le_audio_cis_link_quality");
      leAudioDevice->link_quality_timer_data = event->cis_conn_hdl;
      alarm_set_on_mloop(leAudioDevice->link_quality_timer,
                         linkQualityCheckInterval, link_quality_cb,
                         &leAudioDevice->link_quality_timer_data);
    }

    if (!leAudioDevice->HaveAllActiveAsesCisEst()) {
      /* More cis established event has to come */
      return;
    }

    std::vector<uint8_t> ids;

    /* All CISes created. Send start ready for source ASE before we can go
     * to streaming state.
     */
    struct ase* ase = leAudioDevice->GetFirstActiveAse();
    ASSERT_LOG(ase != nullptr,
               "shouldn't be called without an active ASE, device %s, group "
               "id: %d, cis handle 0x%04x",
               leAudioDevice->address_.ToString().c_str(), event->cig_id,
               event->cis_conn_hdl);
    do {
      if (ase->direction == le_audio::types::kLeAudioDirectionSource)
        ids.push_back(ase->id);
    } while ((ase = leAudioDevice->GetNextActiveAse(ase)));

    if (ids.size() > 0) {
      le_audio::client_parser::ascs::PrepareAseCtpAudioReceiverStartReady(
          ids, value);

      BtaGattQueue::WriteCharacteristic(leAudioDevice->conn_id_,
                                        leAudioDevice->ctp_hdls_.val_hdl, value,
                                        GATT_WRITE_NO_RSP, NULL, NULL);

      return;
    }

    /* Cis establishment may came after setting group state to streaming, e.g.
     * for autonomous scenario when ase is sink */
    if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING &&
        group->IsGroupStreamReady()) {
      /* No more transition for group */
      alarm_cancel(watchdog_);
      PrepareDataPath(group);
    }
  }

  static void RemoveDataPathByCisHandle(LeAudioDevice* leAudioDevice,
                                        uint16_t cis_conn_hdl) {
    auto ases_pair = leAudioDevice->GetAsesByCisConnHdl(cis_conn_hdl);
    IsoManager::GetInstance()->RemoveIsoDataPath(
        cis_conn_hdl,
        (ases_pair.sink
             ? bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput
             : 0x00) |
            (ases_pair.source ? bluetooth::hci::iso_manager::
                                    kRemoveIsoDataPathDirectionOutput
                              : 0x00));
  }

  void ProcessHciNotifCisDisconnected(
      LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
      const bluetooth::hci::iso_manager::cis_disconnected_evt* event) override {
    /* Reset the disconnected CIS states */

    FreeLinkQualityReports(leAudioDevice);

    auto ases_pair = leAudioDevice->GetAsesByCisConnHdl(event->cis_conn_hdl);
    if (ases_pair.sink) {
      ases_pair.sink->data_path_state = AudioStreamDataPathState::CIS_ASSIGNED;
    }
    if (ases_pair.source) {
      ases_pair.source->data_path_state =
          AudioStreamDataPathState::CIS_ASSIGNED;
    }

    /* Invalidate stream configuration if needed */
    auto* stream_conf = &group->stream_conf;
    if (!stream_conf->sink_streams.empty() ||
        !stream_conf->source_streams.empty()) {
      if (ases_pair.sink) {
        stream_conf->sink_streams.erase(
            std::remove_if(stream_conf->sink_streams.begin(),
                           stream_conf->sink_streams.end(),
                           [&event](auto& pair) {
                             return event->cis_conn_hdl == pair.first;
                           }),
            stream_conf->sink_streams.end());
      }

      if (ases_pair.source) {
        stream_conf->source_streams.erase(
            std::remove_if(stream_conf->source_streams.begin(),
                           stream_conf->source_streams.end(),
                           [&event](auto& pair) {
                             return event->cis_conn_hdl == pair.first;
                           }),
            stream_conf->source_streams.end());
      }
    }

    auto target_state = group->GetTargetState();
    switch (target_state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING:
        /* Something wrong happen when streaming or when creating stream.
         * If there is other device connected and streaming, just leave it as it
         * is, otherwise stop the stream.
         */
        if (!group->HaveAllActiveDevicesCisDisc()) {
          /* There is ASE streaming for some device. Continue streaming. */
          LOG_WARN(
              "Group member disconnected during streaming. Cis handle 0x%04x",
              event->cis_conn_hdl);
          return;
        }

        LOG_INFO("Lost all members from the group %d", group->group_id_);
        RemoveCigForGroup(group);

        group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
        group->SetTargetState(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
        /* If there is no more ase to stream. Notify it is in IDLE. */
        state_machine_callbacks_->StatusReportCb(group->group_id_,
                                                 GroupStreamStatus::IDLE);
        return;

      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED:
        /* Intentional group disconnect has finished, but the last CIS in the
         * event came after the ASE notification.
         * If group is already suspended and all CIS are disconnected, we can
         * report SUSPENDED state.
         */
        if ((group->GetState() ==
             AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) &&
            group->HaveAllActiveDevicesCisDisc()) {
          /* No more transition for group */
          alarm_cancel(watchdog_);

          state_machine_callbacks_->StatusReportCb(
              group->group_id_, GroupStreamStatus::SUSPENDED);
          return;
        }
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_IDLE:
      case AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED:
        /* Those two are used when closing the stream and CIS disconnection is
         * expected */
        if (group->HaveAllActiveDevicesCisDisc()) {
          RemoveCigForGroup(group);
          return;
        }

        break;
      default:
        break;
    }

    /* We should send Receiver Stop Ready when acting as a source */
    if (ases_pair.source &&
        ases_pair.source->state == AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING) {
      std::vector<uint8_t> ids = {ases_pair.source->id};
      std::vector<uint8_t> value;

      le_audio::client_parser::ascs::PrepareAseCtpAudioReceiverStopReady(ids,
                                                                         value);
      BtaGattQueue::WriteCharacteristic(leAudioDevice->conn_id_,
                                        leAudioDevice->ctp_hdls_.val_hdl, value,
                                        GATT_WRITE_NO_RSP, NULL, NULL);
    }

    /* Tear down CIS's data paths within the group */
    struct ase* ase = leAudioDevice->GetFirstActiveAseByDataPathState(
        AudioStreamDataPathState::DATA_PATH_ESTABLISHED);
    if (!ase) {
      leAudioDevice = group->GetNextActiveDevice(leAudioDevice);
      /* No more ASEs to disconnect their CISes */
      if (!leAudioDevice) return;

      ase = leAudioDevice->GetFirstActiveAse();
    }

    LOG_ASSERT(ase) << __func__ << " shouldn't be called without an active ASE";
    if (ase->data_path_state ==
        AudioStreamDataPathState::DATA_PATH_ESTABLISHED) {
      RemoveDataPathByCisHandle(leAudioDevice, ase->cis_conn_hdl);
    }
  }

 private:
  static constexpr uint64_t kStateTransitionTimeoutMs = 3500;
  static constexpr char kStateTransitionTimeoutMsProp[] =
      "persist.bluetooth.leaudio.device.set.state.timeoutms";
  Callbacks* state_machine_callbacks_;
  alarm_t* watchdog_;

  /* This callback is called on timeout during transition to target state */
  void OnStateTransitionTimeout(int group_id) {
    state_machine_callbacks_->OnStateTransitionTimeout(group_id);
  }

  void SetTargetState(LeAudioDeviceGroup* group, AseState state) {
    group->SetTargetState(state);

    /* Group should tie in time to get requested status */
    uint64_t timeoutMs = kStateTransitionTimeoutMs;
    timeoutMs =
        osi_property_get_int32(kStateTransitionTimeoutMsProp, timeoutMs);

    if (alarm_is_scheduled(watchdog_)) alarm_cancel(watchdog_);

    alarm_set_on_mloop(
        watchdog_, timeoutMs,
        [](void* data) {
          if (instance) instance->OnStateTransitionTimeout(PTR_TO_INT(data));
        },
        INT_TO_PTR(group->group_id_));
  }

  void CigCreate(LeAudioDeviceGroup* group) {
    LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
    struct ase* ase;
    uint32_t sdu_interval_mtos, sdu_interval_stom;
    uint8_t packing, framing, sca;
    std::vector<EXT_CIS_CFG> cis_cfgs;

    LOG_DEBUG("Group: %p, id: %d cig state: %s", group, group->group_id_,
              ToString(group->cig_state_).c_str());

    if (group->cig_state_ != le_audio::types::CigState::NONE) {
      LOG_WARN(" Group %p, id: %d has invalid cig state: %s ", group,
               group->group_id_, ToString(group->cig_state_).c_str());
      return;
    }

    if (!leAudioDevice) {
      LOG_ERROR("No active devices in group id: %d", group->group_id_);
      return;
    }

    sdu_interval_mtos =
        group->GetSduInterval(le_audio::types::kLeAudioDirectionSink);
    sdu_interval_stom =
        group->GetSduInterval(le_audio::types::kLeAudioDirectionSource);
    sca = group->GetSCA();
    packing = group->GetPacking();
    framing = group->GetFraming();
    uint16_t max_trans_lat_mtos = group->GetMaxTransportLatencyMtos();
    uint16_t max_trans_lat_stom = group->GetMaxTransportLatencyStom();

    do {
      ase = leAudioDevice->GetFirstActiveAse();
      LOG_ASSERT(ase) << __func__
                      << " shouldn't be called without an active ASE";
      do {
        auto& cis = ase->cis_id;
        ASSERT_LOG(ase->cis_id != le_audio::kInvalidCisId,
                   " ase id %d has invalid cis id %d", ase->id, ase->cis_id);
        auto iter =
            find_if(cis_cfgs.begin(), cis_cfgs.end(),
                    [&cis](auto const& cfg) { return cis == cfg.cis_id; });

        /* CIS configuration already on list */
        if (iter != cis_cfgs.end()) continue;

        auto ases_pair = leAudioDevice->GetAsesByCisId(cis);
        EXT_CIS_CFG cis_cfg = {0, 0, 0, 0, 0, 0, 0};

        cis_cfg.cis_id = ase->cis_id;
        cis_cfg.phy_mtos =
            group->GetPhyBitmask(le_audio::types::kLeAudioDirectionSink);
        cis_cfg.phy_stom =
            group->GetPhyBitmask(le_audio::types::kLeAudioDirectionSource);

        if (ases_pair.sink) {
          cis_cfg.max_sdu_size_mtos = ases_pair.sink->max_sdu_size;
          cis_cfg.rtn_mtos = ases_pair.sink->retrans_nb;
        }
        if (ases_pair.source) {
          cis_cfg.max_sdu_size_stom = ases_pair.source->max_sdu_size;
          cis_cfg.rtn_stom = ases_pair.source->retrans_nb;
        }

        cis_cfgs.push_back(cis_cfg);
      } while ((ase = leAudioDevice->GetNextActiveAse(ase)));
    } while ((leAudioDevice = group->GetNextActiveDevice(leAudioDevice)));

    bluetooth::hci::iso_manager::cig_create_params param = {
        .sdu_itv_mtos = sdu_interval_mtos,
        .sdu_itv_stom = sdu_interval_stom,
        .sca = sca,
        .packing = packing,
        .framing = framing,
        .max_trans_lat_stom = max_trans_lat_stom,
        .max_trans_lat_mtos = max_trans_lat_mtos,
        .cis_cfgs = std::move(cis_cfgs),
    };
    group->cig_state_ = le_audio::types::CigState::CREATING;
    IsoManager::GetInstance()->CreateCig(group->group_id_, std::move(param));
    LOG_DEBUG("Group: %p, id: %d cig state: %s", group, group->group_id_,
              ToString(group->cig_state_).c_str());
  }

  static void CisCreateForDevice(LeAudioDevice* leAudioDevice) {
    std::vector<EXT_CIS_CREATE_CFG> conn_pairs;
    struct ase* ase = leAudioDevice->GetFirstActiveAse();
    do {
      /* First in ase pair is Sink, second Source */
      auto ases_pair = leAudioDevice->GetAsesByCisConnHdl(ase->cis_conn_hdl);

      /* Already in pending state - bi-directional CIS */
      if (ase->data_path_state == AudioStreamDataPathState::CIS_PENDING)
        continue;

      if (ases_pair.sink)
        ases_pair.sink->data_path_state = AudioStreamDataPathState::CIS_PENDING;
      if (ases_pair.source)
        ases_pair.source->data_path_state =
            AudioStreamDataPathState::CIS_PENDING;

      uint16_t acl_handle =
          BTM_GetHCIConnHandle(leAudioDevice->address_, BT_TRANSPORT_LE);
      conn_pairs.push_back({.cis_conn_handle = ase->cis_conn_hdl,
                            .acl_conn_handle = acl_handle});
      LOG(INFO) << __func__ << " cis handle: " << +ase->cis_conn_hdl
                << " acl handle : " << loghex(+acl_handle);

    } while ((ase = leAudioDevice->GetNextActiveAse(ase)));

    IsoManager::GetInstance()->EstablishCis(
        {.conn_pairs = std::move(conn_pairs)});
  }

  static void CisCreate(LeAudioDeviceGroup* group) {
    LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
    struct ase* ase;
    std::vector<EXT_CIS_CREATE_CFG> conn_pairs;

    LOG_ASSERT(leAudioDevice)
        << __func__ << " Shouldn't be called without an active device.";

    do {
      ase = leAudioDevice->GetFirstActiveAse();
      LOG_ASSERT(ase) << __func__
                      << " shouldn't be called without an active ASE";
      do {
        /* First is ase pair is Sink, second Source */
        auto ases_pair = leAudioDevice->GetAsesByCisConnHdl(ase->cis_conn_hdl);

        /* Already in pending state - bi-directional CIS */
        if (ase->data_path_state == AudioStreamDataPathState::CIS_PENDING)
          continue;

        if (ases_pair.sink)
          ases_pair.sink->data_path_state =
              AudioStreamDataPathState::CIS_PENDING;
        if (ases_pair.source)
          ases_pair.source->data_path_state =
              AudioStreamDataPathState::CIS_PENDING;

        uint16_t acl_handle =
            BTM_GetHCIConnHandle(leAudioDevice->address_, BT_TRANSPORT_LE);
        conn_pairs.push_back({.cis_conn_handle = ase->cis_conn_hdl,
                              .acl_conn_handle = acl_handle});
        DLOG(INFO) << __func__ << " cis handle: " << +ase->cis_conn_hdl
                   << " acl handle : " << loghex(+acl_handle);

      } while ((ase = leAudioDevice->GetNextActiveAse(ase)));
    } while ((leAudioDevice = group->GetNextActiveDevice(leAudioDevice)));

    IsoManager::GetInstance()->EstablishCis(
        {.conn_pairs = std::move(conn_pairs)});
  }

  static void PrepareDataPath(const struct ase* ase) {
    /* TODO: Handle HW offloading decode as we handle here, force to use SW
     * decode for now */
    auto data_path_id = bluetooth::hci::iso_manager::kIsoDataPathHci;
    if (CodecManager::GetInstance()->GetCodecLocation() !=
        CodecLocation::HOST) {
      data_path_id = bluetooth::hci::iso_manager::kIsoDataPathPlatformDefault;
    }
    /* TODO: Need to set coding_format when we support the codec location inside
     * the controller, force to use transparent for now */
    bluetooth::hci::iso_manager::iso_data_path_params param = {
        .data_path_dir =
            ase->direction == le_audio::types::kLeAudioDirectionSink
                ? bluetooth::hci::iso_manager::kIsoDataPathDirectionIn
                : bluetooth::hci::iso_manager::kIsoDataPathDirectionOut,
        .data_path_id = data_path_id,
        .codec_id_format = bluetooth::hci::kIsoCodingFormatTransparent,
        .codec_id_company = ase->codec_id.vendor_company_id,
        .codec_id_vendor = ase->codec_id.vendor_codec_id,
        .controller_delay = 0x00000000,
        .codec_conf = std::vector<uint8_t>(),
    };
    IsoManager::GetInstance()->SetupIsoDataPath(ase->cis_conn_hdl,
                                                std::move(param));
  }

  static inline void PrepareDataPath(LeAudioDeviceGroup* group) {
    auto* leAudioDevice = group->GetFirstActiveDevice();
    LOG_ASSERT(leAudioDevice)
        << __func__ << " Shouldn't be called without an active device.";

    auto* ase = leAudioDevice->GetFirstActiveAse();
    LOG_ASSERT(ase) << __func__ << " shouldn't be called without an active ASE";
    PrepareDataPath(ase);
  }

  static void ReleaseDataPath(LeAudioDeviceGroup* group) {
    LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
    LOG_ASSERT(leAudioDevice)
        << __func__ << " Shouldn't be called without an active device.";

    auto ase = leAudioDevice->GetFirstActiveAseByDataPathState(
        AudioStreamDataPathState::DATA_PATH_ESTABLISHED);
    LOG_ASSERT(ase) << __func__
                    << " Shouldn't be called without an active ASE.";
    RemoveDataPathByCisHandle(leAudioDevice, ase->cis_conn_hdl);
  }

  void AseStateMachineProcessIdle(
      struct le_audio::client_parser::ascs::ase_rsp_hdr& arh, struct ase* ase,
      LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    switch (ase->state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_IDLE:
        if (ase->id == 0x00) {
          /* Initial state of Ase - update id */
          LOG(INFO) << __func__
                    << ", discovered ase id: " << static_cast<int>(arh.id);
          ase->id = arh.id;
        }
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING: {
        LeAudioDevice* leAudioDeviceNext;
        ase->state = AseState::BTA_LE_AUDIO_ASE_STATE_IDLE;
        ase->active = false;

        if (!leAudioDevice->HaveAllActiveAsesSameState(
                AseState::BTA_LE_AUDIO_ASE_STATE_IDLE)) {
          /* More ASEs notification from this device has to come for this group
           */
          LOG_DEBUG("Wait for more ASE to configure for device %s",
                    leAudioDevice->address_.ToString().c_str());
          return;
        }

        /* Before continue with release, make sure this is what is requested.
         * If not (e.g. only single device got disconnected), stop here
         */
        if (group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_IDLE) {
          LOG_DEBUG("Autonomus change of stated for device %s, ase id: %d",
                    leAudioDevice->address_.ToString().c_str(), ase->id);
          return;
        }

        leAudioDeviceNext = group->GetNextActiveDevice(leAudioDevice);

        /* Configure ASEs for next device in group */
        if (leAudioDeviceNext) {
          PrepareAndSendRelease(leAudioDeviceNext);
        } else {
          /* Last node is in releasing state*/
          if (alarm_is_scheduled(watchdog_)) alarm_cancel(watchdog_);

          group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
          ReleaseCisIds(group);
          state_machine_callbacks_->StatusReportCb(group->group_id_,
                                                   GroupStreamStatus::IDLE);
        }
        break;
      }
      default:
        LOG(ERROR) << __func__ << ", invalid state transition, from: "
                   << static_cast<int>(ase->state) << ", to: "
                   << static_cast<int>(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
        StopStream(group);
        break;
    }
  }

  void StartConfigQoSForTheGroup(LeAudioDeviceGroup* group) {
    LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
    if (!leAudioDevice) {
      LOG(ERROR) << __func__ << ", no active devices in group";
      StopStream(group);
      return;
    }

    PrepareAndSendConfigQos(group, leAudioDevice);
  }

  void PrepareAndSendCodecConfigure(LeAudioDeviceGroup* group,
                                    LeAudioDevice* leAudioDevice) {
    struct le_audio::client_parser::ascs::ctp_codec_conf conf;
    std::vector<struct le_audio::client_parser::ascs::ctp_codec_conf> confs;
    struct ase* ase;

    ase = leAudioDevice->GetFirstActiveAse();
    LOG_ASSERT(ase) << __func__ << " shouldn't be called without an active ASE";
    do {
      uint8_t cis_id = ase->cis_id;
      if (cis_id == le_audio::kInvalidCisId) {
        /* Get completive (to be bi-directional CIS) CIS ID for ASE */
        cis_id = leAudioDevice->GetMatchingBidirectionCisId(ase);
        if (cis_id == le_audio::kInvalidCisId) {
          /* Get next free CIS ID for group */
          cis_id = group->GetFirstFreeCisId();
          if (cis_id == le_audio::kInvalidCisId) {
            LOG(ERROR) << __func__ << ", failed to get free CIS ID";
            StopStream(group);
            return;
          }
        }
      }

      LOG_INFO(" Configure ase_id %d, cis_id %d, ase state %s", ase->id, cis_id,
               ToString(ase->state).c_str());

      ase->cis_id = cis_id;

      conf.ase_id = ase->id;
      conf.target_latency = ase->target_latency;
      conf.target_phy = group->GetTargetPhy(ase->direction);
      conf.codec_id = ase->codec_id;
      conf.codec_config = ase->codec_config;
      confs.push_back(conf);
    } while ((ase = leAudioDevice->GetNextActiveAse(ase)));

    std::vector<uint8_t> value;
    le_audio::client_parser::ascs::PrepareAseCtpCodecConfig(confs, value);
    BtaGattQueue::WriteCharacteristic(leAudioDevice->conn_id_,
                                      leAudioDevice->ctp_hdls_.val_hdl, value,
                                      GATT_WRITE_NO_RSP, NULL, NULL);
  }

  void AseStateMachineProcessCodecConfigured(
      struct le_audio::client_parser::ascs::ase_rsp_hdr& arh, struct ase* ase,
      uint8_t* data, uint16_t len, LeAudioDeviceGroup* group,
      LeAudioDevice* leAudioDevice) {
    if (!group) {
      LOG(ERROR) << __func__ << ", leAudioDevice doesn't belong to any group";

      return;
    }

    /* ase contain current ASE state. New state is in "arh" */
    switch (ase->state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_IDLE: {
        if (ase->id == 0x00) {
          /* Initial state of Ase - update id */
          LOG(INFO) << __func__
                    << ", discovered ase id: " << static_cast<int>(arh.id);
          ase->id = arh.id;
        }

        LeAudioDevice* leAudioDeviceNext;

        struct le_audio::client_parser::ascs::ase_codec_configured_state_params
            rsp;

        /* Cache codec configured status values for further
         * configuration/reconfiguration
         */
        if (!ParseAseStatusCodecConfiguredStateParams(rsp, len, data)) {
          StopStream(group);
          return;
        }
        ase->framing = rsp.framing;
        ase->preferred_phy = rsp.preferred_phy;
        /* Validate and update QoS settings to be consistent */
        if ((!ase->max_transport_latency ||
             ase->max_transport_latency > rsp.max_transport_latency) ||
            !ase->retrans_nb) {
          ase->max_transport_latency = rsp.max_transport_latency;
          ase->retrans_nb = rsp.preferred_retrans_nb;
          LOG_INFO(
              " Using server preferred QoS settings. Max Transport Latency: %d"
              ", Retransmission Number: %d",
              +ase->max_transport_latency, ase->retrans_nb);
        }
        ase->pres_delay_min = rsp.pres_delay_min;
        ase->pres_delay_max = rsp.pres_delay_max;
        ase->preferred_pres_delay_min = rsp.preferred_pres_delay_min;
        ase->preferred_pres_delay_max = rsp.preferred_pres_delay_max;

        ase->state = AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED;

        if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_IDLE) {
          /* This is autonomus change of the remote device */
          LOG_DEBUG("Autonomus change for device %s, ase id %d. Just store it.",
                    leAudioDevice->address_.ToString().c_str(), ase->id);
          return;
        }

        if (leAudioDevice->HaveAnyUnconfiguredAses()) {
          /* More ASEs notification from this device has to come for this group
           */
          LOG_DEBUG("More Ases to be configured for the device %s",
                    leAudioDevice->address_.ToString().c_str());
          return;
        }

        if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          /* We are here because of the reconnection of the single device. */
          PrepareAndSendConfigQos(group, leAudioDevice);
          return;
        }

        leAudioDeviceNext = group->GetNextActiveDevice(leAudioDevice);

        /* Configure ASEs for next device in group */
        if (leAudioDeviceNext) {
          PrepareAndSendCodecConfigure(group, leAudioDeviceNext);
        } else {
          /* Last node configured, process group to codec configured state */
          group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

          if (group->GetTargetState() ==
              AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
            CigCreate(group);
            return;
          }

          if (group->GetTargetState() ==
                  AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED &&
              group->stream_conf.pending_configuration) {
            LOG_INFO(" Configured state completed ");
            group->stream_conf.pending_configuration = false;
            state_machine_callbacks_->StatusReportCb(
                group->group_id_, GroupStreamStatus::CONFIGURED_BY_USER);

            /* No more transition for group */
            alarm_cancel(watchdog_);
            return;
          }

          LOG_ERROR(", invalid state transition, from: %s to %s",
                    ToString(group->GetState()).c_str(),
                    ToString(group->GetTargetState()).c_str());
          StopStream(group);
          return;
        }

        break;
      }
      case AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED: {
        /* Received Configured in Configured state. This could be done
         * autonomously because of the reconfiguration done by us
         */

        struct le_audio::client_parser::ascs::ase_codec_configured_state_params
            rsp;

        /* Cache codec configured status values for further
         * configuration/reconfiguration
         */
        if (!ParseAseStatusCodecConfiguredStateParams(rsp, len, data)) {
          StopStream(group);
          return;
        }

        ase->framing = rsp.framing;
        ase->preferred_phy = rsp.preferred_phy;
        /* Validate and update QoS settings to be consistent */
        if ((!ase->max_transport_latency ||
             ase->max_transport_latency > rsp.max_transport_latency) ||
            !ase->retrans_nb) {
          ase->max_transport_latency = rsp.max_transport_latency;
          ase->retrans_nb = rsp.preferred_retrans_nb;
          LOG(INFO) << __func__ << " Using server preferred QoS settings."
                    << " Max Transport Latency: " << +ase->max_transport_latency
                    << ", Retransmission Number: " << +ase->retrans_nb;
        }
        ase->pres_delay_min = rsp.pres_delay_min;
        ase->pres_delay_max = rsp.pres_delay_max;
        ase->preferred_pres_delay_min = rsp.preferred_pres_delay_min;
        ase->preferred_pres_delay_max = rsp.preferred_pres_delay_max;

        /* This may be a notification from a re-configured ASE */
        ase->reconfigure = false;

        if (leAudioDevice->HaveAnyUnconfiguredAses()) {
          /* Waiting for others to be reconfigured */
          return;
        }

        if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          /* We are here because of the reconnection of the single device. */
          PrepareAndSendConfigQos(group, leAudioDevice);
          return;
        }

        LeAudioDevice* leAudioDeviceNext =
            group->GetNextActiveDevice(leAudioDevice);

        /* Configure ASEs for next device in group */
        if (leAudioDeviceNext) {
          PrepareAndSendCodecConfigure(group, leAudioDeviceNext);
        } else {
          /* Last node configured, process group to codec configured state */
          group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

          if (group->GetTargetState() ==
              AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
            CigCreate(group);
            return;
          }

          if (group->GetTargetState() ==
                  AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED &&
              group->stream_conf.pending_configuration) {
            LOG_INFO(" Configured state completed ");
            group->stream_conf.pending_configuration = false;
            state_machine_callbacks_->StatusReportCb(
                group->group_id_, GroupStreamStatus::CONFIGURED_BY_USER);

            /* No more transition for group */
            alarm_cancel(watchdog_);
            return;
          }

          LOG_ERROR(", Autonomouse change, from: %s to %s",
                    ToString(group->GetState()).c_str(),
                    ToString(group->GetTargetState()).c_str());
        }

        break;
      }
      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED:
        /* TODO: Config Codec */
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING:
        LeAudioDevice* leAudioDeviceNext;
        ase->state = AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED;
        ase->active = false;

        if (!leAudioDevice->HaveAllActiveAsesSameState(
                AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED)) {
          /* More ASEs notification from this device has to come for this group
           */
          LOG_DEBUG("Wait for more ASE to configure for device %s",
                    leAudioDevice->address_.ToString().c_str());
          return;
        }

        /* Before continue with release, make sure this is what is requested.
         * If not (e.g. only single device got disconnected), stop here
         */
        if (group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_IDLE) {
          LOG_DEBUG("Autonomus change of stated for device %s, ase id: %d",
                    leAudioDevice->address_.ToString().c_str(), ase->id);
          return;
        }

        leAudioDeviceNext = group->GetNextActiveDevice(leAudioDevice);

        /* Configure ASEs for next device in group */
        if (leAudioDeviceNext) {
          PrepareAndSendRelease(leAudioDeviceNext);
        } else {
          /* Last node is in releasing state*/
          if (alarm_is_scheduled(watchdog_)) alarm_cancel(watchdog_);

          group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);
          /* Remote device has cache and keep staying in configured state after
           * release. Therefore, we assume this is a target state requested by
           * remote device.
           */
          group->SetTargetState(group->GetState());
          state_machine_callbacks_->StatusReportCb(
              group->group_id_, GroupStreamStatus::CONFIGURED_AUTONOMOUS);
        }
        break;
      default:
        LOG(ERROR) << __func__ << ", invalid state transition, from: "
                   << static_cast<int>(ase->state) << ", to: "
                   << static_cast<int>(
                          AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
        StopStream(group);
        break;
    }
  }

  void AseStateMachineProcessQosConfigured(
      struct le_audio::client_parser::ascs::ase_rsp_hdr& arh, struct ase* ase,
      LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    if (!group) {
      LOG(ERROR) << __func__ << ", leAudioDevice doesn't belong to any group";

      return;
    }

    switch (ase->state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED: {
        ase->state = AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED;

        if (!leAudioDevice->HaveAllActiveAsesSameState(
                AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED)) {
          /* More ASEs notification from this device has to come for this group
           */
          return;
        }

        if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          /* We are here because of the reconnection of the single device. */
          PrepareAndSendEnable(leAudioDevice);
          return;
        }

        LeAudioDevice* leAudioDeviceNext =
            group->GetNextActiveDevice(leAudioDevice);

        /* Configure ASEs qos for next device in group */
        if (leAudioDeviceNext) {
          PrepareAndSendConfigQos(group, leAudioDeviceNext);
        } else {
          leAudioDevice = group->GetFirstActiveDevice();
          LOG_ASSERT(leAudioDevice)
              << __func__ << " Shouldn't be called without an active device.";
          PrepareAndSendEnable(leAudioDevice);
        }

        break;
      }
      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED:
        /* TODO: Config Codec error/Config Qos/Config QoS error/Enable error */
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING:
        if (ase->direction == le_audio::types::kLeAudioDirectionSource) {
          /* Source ASE cannot go from Streaming to QoS Configured state */
          LOG(ERROR) << __func__ << ", invalid state transition, from: "
                     << static_cast<int>(ase->state) << ", to: "
                     << static_cast<int>(
                            AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
          StopStream(group);
          return;
        }

        ase->state = AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED;

        /* Process the Disable Transition of the rest of group members if no
         * more ASE notifications has to come from this device. */
        if (leAudioDevice->IsReadyToSuspendStream())
          ProcessGroupDisable(group, leAudioDevice);

        break;

      case AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING: {
        ase->state = AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED;

        /* More ASEs notification from this device has to come for this group */
        if (!group->HaveAllActiveDevicesAsesTheSameState(
                AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED))
          return;

        group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);

        if (!group->HaveAllActiveDevicesCisDisc()) return;

        if (group->GetTargetState() ==
            AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) {
          /* No more transition for group */
          alarm_cancel(watchdog_);

          state_machine_callbacks_->StatusReportCb(
              group->group_id_, GroupStreamStatus::SUSPENDED);
        } else {
          LOG_ERROR(", invalid state transition, from: %s, to: %s",
                    ToString(group->GetState()).c_str(),
                    ToString(group->GetTargetState()).c_str());
          StopStream(group);
          return;
        }
        break;
      }
      default:
        LOG(ERROR) << __func__ << ", invalid state transition, from: "
                   << static_cast<int>(ase->state) << ", to: "
                   << static_cast<int>(
                          AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
        StopStream(group);
        break;
    }
  }

  void PrepareAndSendEnable(LeAudioDevice* leAudioDevice) {
    struct le_audio::client_parser::ascs::ctp_enable conf;
    std::vector<struct le_audio::client_parser::ascs::ctp_enable> confs;
    std::vector<uint8_t> value;
    struct ase* ase;

    ase = leAudioDevice->GetFirstActiveAse();
    LOG_ASSERT(ase) << __func__ << " shouldn't be called without an active ASE";
    do {
      conf.ase_id = ase->id;
      conf.metadata = ase->metadata;
      confs.push_back(conf);
    } while ((ase = leAudioDevice->GetNextActiveAse(ase)));

    le_audio::client_parser::ascs::PrepareAseCtpEnable(confs, value);

    BtaGattQueue::WriteCharacteristic(leAudioDevice->conn_id_,
                                      leAudioDevice->ctp_hdls_.val_hdl, value,
                                      GATT_WRITE_NO_RSP, NULL, NULL);
  }

  void PrepareAndSendDisable(LeAudioDevice* leAudioDevice) {
    ase* ase = leAudioDevice->GetFirstActiveAse();
    LOG_ASSERT(ase) << __func__ << " shouldn't be called without an active ASE";

    std::vector<uint8_t> ids;
    do {
      ids.push_back(ase->id);
    } while ((ase = leAudioDevice->GetNextActiveAse(ase)));

    std::vector<uint8_t> value;
    le_audio::client_parser::ascs::PrepareAseCtpDisable(ids, value);

    BtaGattQueue::WriteCharacteristic(leAudioDevice->conn_id_,
                                      leAudioDevice->ctp_hdls_.val_hdl, value,
                                      GATT_WRITE_NO_RSP, NULL, NULL);
  }

  void PrepareAndSendRelease(LeAudioDevice* leAudioDevice) {
    ase* ase = leAudioDevice->GetFirstActiveAse();
    LOG_ASSERT(ase) << __func__ << " shouldn't be called without an active ASE";

    std::vector<uint8_t> ids;
    do {
      ids.push_back(ase->id);
    } while ((ase = leAudioDevice->GetNextActiveAse(ase)));

    std::vector<uint8_t> value;
    le_audio::client_parser::ascs::PrepareAseCtpRelease(ids, value);

    BtaGattQueue::WriteCharacteristic(leAudioDevice->conn_id_,
                                      leAudioDevice->ctp_hdls_.val_hdl, value,
                                      GATT_WRITE_NO_RSP, NULL, NULL);
  }

  void PrepareAndSendConfigQos(LeAudioDeviceGroup* group,
                               LeAudioDevice* leAudioDevice) {
    std::vector<struct le_audio::client_parser::ascs::ctp_qos_conf> confs;

    for (struct ase* ase = leAudioDevice->GetFirstActiveAse(); ase != nullptr;
         ase = leAudioDevice->GetNextActiveAse(ase)) {
      /* TODO: Configure first ASE qos according to context type */
      struct le_audio::client_parser::ascs::ctp_qos_conf conf;
      conf.ase_id = ase->id;
      conf.cig = group->group_id_;
      conf.cis = ase->cis_id;
      conf.framing = group->GetFraming();
      conf.phy = group->GetPhyBitmask(ase->direction);
      conf.max_sdu = ase->max_sdu_size;
      conf.retrans_nb = ase->retrans_nb;
      if (!group->GetPresentationDelay(&conf.pres_delay, ase->direction)) {
        LOG(ERROR) << __func__ << ", inconsistent presentation delay for group";
        StopStream(group);
        return;
      }

      conf.sdu_interval = group->GetSduInterval(ase->direction);
      if (!conf.sdu_interval) {
        LOG(ERROR) << __func__ << ", unsupported SDU interval for group";
        StopStream(group);
        return;
      }

      if (ase->direction == le_audio::types::kLeAudioDirectionSink) {
        conf.max_transport_latency = group->GetMaxTransportLatencyMtos();
      } else {
        conf.max_transport_latency = group->GetMaxTransportLatencyStom();
      }
      confs.push_back(conf);
    }

    LOG_ASSERT(confs.size() > 0)
        << __func__ << " shouldn't be called without an active ASE";

    std::vector<uint8_t> value;
    le_audio::client_parser::ascs::PrepareAseCtpConfigQos(confs, value);

    BtaGattQueue::WriteCharacteristic(leAudioDevice->conn_id_,
                                      leAudioDevice->ctp_hdls_.val_hdl, value,
                                      GATT_WRITE_NO_RSP, NULL, NULL);
  }

  void PrepareAndSendUpdateMetadata(
      LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
      le_audio::types::LeAudioContextType context_type, int ccid) {
    std::vector<struct le_audio::client_parser::ascs::ctp_update_metadata>
        confs;

    for (; leAudioDevice;
         leAudioDevice = group->GetNextActiveDevice(leAudioDevice)) {
      if (!leAudioDevice->IsMetadataChanged(context_type, ccid)) continue;

      auto new_metadata = leAudioDevice->GetMetadata(context_type, ccid);

      /* Request server to update ASEs with new metadata */
      for (struct ase* ase = leAudioDevice->GetFirstActiveAse(); ase != nullptr;
           ase = leAudioDevice->GetNextActiveAse(ase)) {
        struct le_audio::client_parser::ascs::ctp_update_metadata conf;

        conf.ase_id = ase->id;
        conf.metadata = new_metadata;

        confs.push_back(conf);
      }

      std::vector<uint8_t> value;
      le_audio::client_parser::ascs::PrepareAseCtpUpdateMetadata(confs, value);

      BtaGattQueue::WriteCharacteristic(leAudioDevice->conn_id_,
                                        leAudioDevice->ctp_hdls_.val_hdl, value,
                                        GATT_WRITE_NO_RSP, NULL, NULL);

      return;
    }
  }

  void AseStateMachineProcessEnabling(
      struct le_audio::client_parser::ascs::ase_rsp_hdr& arh, struct ase* ase,
      LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    if (!group) {
      LOG(ERROR) << __func__ << ", leAudioDevice doesn't belong to any group";
      return;
    }

    if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      /* We are here because of the reconnection of the single device. */
      ase->state = AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING;
      CisCreateForDevice(leAudioDevice);
      return;
    }

    switch (ase->state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED:
        ase->state = AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING;

        if (leAudioDevice->IsReadyToCreateStream())
          ProcessGroupEnable(group, leAudioDevice);

        break;

      case AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING:
        /* Enable/Switch Content */
        break;
      default:
        LOG(ERROR) << __func__ << ", invalid state transition, from: "
                   << static_cast<int>(ase->state) << ", to: "
                   << static_cast<int>(
                          AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING);
        StopStream(group);
        break;
    }
  }

  void AseStateMachineProcessStreaming(
      struct le_audio::client_parser::ascs::ase_rsp_hdr& arh, struct ase* ase,
      uint8_t* data, uint16_t len, LeAudioDeviceGroup* group,
      LeAudioDevice* leAudioDevice) {
    if (!group) {
      LOG(ERROR) << __func__ << ", leAudioDevice doesn't belong to any group";

      return;
    }

    switch (ase->state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED:
        /* As per ASCS 1.0 :
         * If a CIS has been established and the server is acting as Audio Sink
         * for the ASE, and if the server is ready to receive audio data
         * transmitted by the client, the server may autonomously initiate the
         * Receiver Start Ready, as defined in Section 5.4, without first
         * sending a notification of the ASE characteristic value in the
         * Enabling state.
         */
        if (ase->direction != le_audio::types::kLeAudioDirectionSink) {
          LOG(ERROR) << __func__ << ", invalid state transition, from: "
                     << static_cast<int>(ase->state) << ", to: "
                     << static_cast<int>(
                            AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
          StopStream(group);
          return;
        }

        ase->state = AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING;

        if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          /* We are here because of the reconnection of the single device. */
          return;
        }

        if (leAudioDevice->IsReadyToCreateStream())
          ProcessGroupEnable(group, leAudioDevice);

        break;

      case AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING: {
        std::vector<uint8_t> value;

        ase->state = AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING;

        if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          /* We are here because of the reconnection of the single device. */
          auto* stream_conf = &group->stream_conf;
          if (ase->direction == le_audio::types::kLeAudioDirectionSource) {
            stream_conf->source_streams.emplace_back(
                std::make_pair(ase->cis_conn_hdl,
                               *ase->codec_config.audio_channel_allocation));
          } else {
            stream_conf->sink_streams.emplace_back(
                std::make_pair(ase->cis_conn_hdl,
                               *ase->codec_config.audio_channel_allocation));
          }
        }

        if (!group->HaveAllActiveDevicesAsesTheSameState(
                AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING)) {
          /* More ASEs notification form this device has to come for this group
           */

          return;
        }

        if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          /* We are here because of the reconnection of the single device. */
          return;
        }

        /* Last node is in streaming state */
        group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

        /* Not all CISes establish evens came */
        if (!group->IsGroupStreamReady()) return;

        if (group->GetTargetState() ==
            AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          /* No more transition for group */
          alarm_cancel(watchdog_);
          PrepareDataPath(group);

          return;
        } else {
          LOG_ERROR(", invalid state transition, from: %s, to: %s",
                    ToString(group->GetState()).c_str(),
                    ToString(group->GetTargetState()).c_str());
          StopStream(group);
          return;
        }

        break;
      }
      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING: {
        struct le_audio::client_parser::ascs::ase_transient_state_params rsp;

        if (!ParseAseStatusTransientStateParams(rsp, len, data)) {
          StopStream(group);
          return;
        }

        /* Cache current set up metadata values for for further possible
         * reconfiguration
         */
        if (!rsp.metadata.empty()) {
          ase->metadata = rsp.metadata;
        }

        break;
      }
      default:
        LOG(ERROR) << __func__ << ", invalid state transition, from: "
                   << static_cast<int>(ase->state) << ", to: "
                   << static_cast<int>(
                          AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
        StopStream(group);
        break;
    }
  }

  void AseStateMachineProcessDisabling(
      struct le_audio::client_parser::ascs::ase_rsp_hdr& arh, struct ase* ase,
      LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    if (!group) {
      LOG(ERROR) << __func__ << ", leAudioDevice doesn't belong to any group";

      return;
    }

    if (ase->direction == le_audio::types::kLeAudioDirectionSink) {
      /* Sink ASE state machine does not have Disabling state */
      LOG_ERROR(", invalid state transition, from: %s , to: %s ",
                ToString(group->GetState()).c_str(),
                ToString(group->GetTargetState()).c_str());
      StopStream(group);
      return;
    }

    switch (ase->state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING:
        /* TODO: Disable */
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING:
        ase->state = AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING;

        /* Process the Disable Transition of the rest of group members if no
         * more ASE notifications has to come from this device. */
        if (leAudioDevice->IsReadyToSuspendStream())
          ProcessGroupDisable(group, leAudioDevice);

        break;

      default:
        LOG(ERROR) << __func__ << ", invalid state transition, from: "
                   << static_cast<int>(ase->state) << ", to: "
                   << static_cast<int>(
                          AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING);
        StopStream(group);
        break;
    }
  }

  void AseStateMachineProcessReleasing(
      struct le_audio::client_parser::ascs::ase_rsp_hdr& arh, struct ase* ase,
      LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    if (!group) {
      LOG(ERROR) << __func__ << ", leAudioDevice doesn't belong to any group";

      return;
    }

    switch (ase->state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED:
      case AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING: {
        ase->state = AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING;
        break;
      }
      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED:
        /* At this point all of the active ASEs within group are released. */
        RemoveCigForGroup(group);

        ase->state = AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING;
        if (group->HaveAllActiveDevicesAsesTheSameState(
                AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING))
          group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING);

        break;

      case AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING:
      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING: {
        ase->state = AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING;

        /* Happens when bi-directional completive ASE releasing state came */
        if (ase->data_path_state == AudioStreamDataPathState::CIS_DISCONNECTING)
          break;

        if (ase->data_path_state ==
            AudioStreamDataPathState::DATA_PATH_ESTABLISHED) {
          RemoveDataPathByCisHandle(leAudioDevice, ase->cis_conn_hdl);
        } else if (ase->data_path_state ==
                       AudioStreamDataPathState::CIS_ESTABLISHED ||
                   ase->data_path_state ==
                       AudioStreamDataPathState::CIS_PENDING) {
          IsoManager::GetInstance()->DisconnectCis(ase->cis_conn_hdl,
                                                   HCI_ERR_PEER_USER);
        } else {
          DLOG(INFO) << __func__ << ", Nothing to do ase data path state: "
                     << static_cast<int>(ase->data_path_state);
        }
        break;
      }
      default:
        LOG(ERROR) << __func__ << ", invalid state transition, from: "
                   << static_cast<int>(ase->state) << ", to: "
                   << static_cast<int>(
                          AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING);
        break;
    }
  }

  void ProcessGroupEnable(LeAudioDeviceGroup* group, LeAudioDevice* device) {
    /* Enable ASEs for next device in group. */
    LeAudioDevice* deviceNext = group->GetNextActiveDevice(device);
    if (deviceNext) {
      PrepareAndSendEnable(deviceNext);
      return;
    }

    /* At this point all of the active ASEs within group are enabled. The server
     * might perform autonomous state transition for Sink ASE and skip Enabling
     * state notification and transit to Streaming directly. So check the group
     * state, because we might be ready to create CIS. */
    if (group->HaveAllActiveDevicesAsesTheSameState(
            AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING)) {
      group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
    } else {
      group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING);
    }

    if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      CisCreate(group);
    } else {
      LOG_ERROR(", invalid state transition, from: %s , to: %s ",
                ToString(group->GetState()).c_str(),
                ToString(group->GetTargetState()).c_str());
      StopStream(group);
    }
  }

  void ProcessGroupDisable(LeAudioDeviceGroup* group, LeAudioDevice* device) {
    /* Disable ASEs for next device in group. */
    LeAudioDevice* deviceNext = group->GetNextActiveDevice(device);
    if (deviceNext) {
      PrepareAndSendDisable(deviceNext);
      return;
    }

    /* At this point all of the active ASEs within group are disabled. As there
     * is no Disabling state for Sink ASE, it might happen that all of the
     * active ASEs are Sink ASE and will transit to QoS state. So check
     * the group state, because we might be ready to release data path. */
    if (group->HaveAllActiveDevicesAsesTheSameState(
            AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED)) {
      group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
    } else {
      group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING);
    }

    /* Transition to QoS configured is done by CIS disconnection */
    if (group->GetTargetState() ==
        AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) {
      ReleaseDataPath(group);
    } else {
      LOG_ERROR(", invalid state transition, from: %s , to: %s ",
                ToString(group->GetState()).c_str(),
                ToString(group->GetTargetState()).c_str());
      StopStream(group);
    }
  }
};
}  // namespace

namespace le_audio {
void LeAudioGroupStateMachine::Initialize(Callbacks* state_machine_callbacks_) {
  if (instance) {
    LOG(ERROR) << "Already initialized";
    return;
  }

  instance = new LeAudioGroupStateMachineImpl(state_machine_callbacks_);
}

void LeAudioGroupStateMachine::Cleanup() {
  if (!instance) return;

  LeAudioGroupStateMachineImpl* ptr = instance;
  instance = nullptr;

  delete ptr;
}

LeAudioGroupStateMachine* LeAudioGroupStateMachine::Get() {
  CHECK(instance);
  return instance;
}
}  // namespace le_audio
