/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com. Represented by EHIMA
 * - www.ehima.com
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

#pragma once

#include <map>
#include <memory>
#include <optional>
#include <tuple>
#include <vector>

#include "bt_types.h"
#include "bta_groups.h"
#include "btm_iso_api_types.h"
#include "client_audio.h"
#include "gatt_api.h"
#include "le_audio_types.h"
#include "osi/include/alarm.h"
#include "raw_address.h"

namespace le_audio {
/* Class definitions */

/* LeAudioDevice class represents GATT server device with ASCS, PAC services as
 * mandatory. Device may contain multiple ASEs, PACs, audio locations. ASEs from
 * multiple devices may be formed in group.
 *
 * Device is created after connection or after storage restoration.
 *
 * Active device means that device has at least one ASE which will participate
 * in any state transition of state machine. ASEs and devices will be activated
 * according to requested by upper context type.
 */
class LeAudioDevice {
 public:
  RawAddress address_;

  bool known_service_handles_;
  bool notify_connected_after_read_;
  bool removing_device_;

  /* we are making active attempt to connect to this device, 'direct connect'.
   * This is true only during initial phase of first connection. */
  bool first_connection_;
  bool connecting_actively_;
  bool closing_stream_for_disconnection_;
  uint16_t conn_id_;
  bool encrypted_;
  int group_id_;
  bool csis_member_;

  uint8_t audio_directions_;
  types::AudioLocations snk_audio_locations_;
  types::AudioLocations src_audio_locations_;

  types::PublishedAudioCapabilities snk_pacs_;
  types::PublishedAudioCapabilities src_pacs_;

  struct types::hdl_pair snk_audio_locations_hdls_;
  struct types::hdl_pair src_audio_locations_hdls_;
  struct types::hdl_pair audio_avail_hdls_;
  struct types::hdl_pair audio_supp_cont_hdls_;
  std::vector<struct types::ase> ases_;
  struct types::hdl_pair ctp_hdls_;

  alarm_t* link_quality_timer;
  uint16_t link_quality_timer_data;

  LeAudioDevice(const RawAddress& address_, bool first_connection,
                int group_id = bluetooth::groups::kGroupUnknown)
      : address_(address_),
        known_service_handles_(false),
        notify_connected_after_read_(false),
        removing_device_(false),
        first_connection_(first_connection),
        connecting_actively_(first_connection),
        closing_stream_for_disconnection_(false),
        conn_id_(GATT_INVALID_CONN_ID),
        encrypted_(false),
        group_id_(group_id),
        csis_member_(false),
        audio_directions_(0),
        link_quality_timer(nullptr) {}
  ~LeAudioDevice(void);

  void ClearPACs(void);
  void RegisterPACs(std::vector<struct types::acs_ac_record>* apr_db,
                    std::vector<struct types::acs_ac_record>* apr);
  struct types::ase* GetAseByValHandle(uint16_t val_hdl);
  struct types::ase* GetFirstActiveAse(void);
  struct types::ase* GetFirstActiveAseByDirection(uint8_t direction);
  struct types::ase* GetNextActiveAseWithSameDirection(
      struct types::ase* base_ase);
  struct types::ase* GetFirstActiveAseByDataPathState(
      types::AudioStreamDataPathState state);
  struct types::ase* GetFirstInactiveAse(uint8_t direction,
                                         bool reconnect = false);
  struct types::ase* GetFirstInactiveAseWithState(uint8_t direction,
                                                  types::AseState state);
  struct types::ase* GetNextActiveAse(struct types::ase* ase);
  struct types::ase* GetAseToMatchBidirectionCis(struct types::ase* ase);
  types::BidirectAsesPair GetAsesByCisConnHdl(uint16_t conn_hdl);
  types::BidirectAsesPair GetAsesByCisId(uint8_t cis_id);
  bool HaveActiveAse(void);
  bool HaveAllActiveAsesSameState(types::AseState state);
  bool HaveAnyUnconfiguredAses(void);
  bool IsReadyToCreateStream(void);
  bool IsReadyToSuspendStream(void);
  bool HaveAllActiveAsesCisEst(void);
  bool HaveAllAsesCisDisc(void);
  bool HasCisId(uint8_t id);
  uint8_t GetMatchingBidirectionCisId(const struct types::ase* base_ase);
  const struct types::acs_ac_record* GetCodecConfigurationSupportedPac(
      uint8_t direction, const set_configurations::CodecCapabilitySetting&
                             codec_capability_setting);
  uint8_t GetLc3SupportedChannelCount(uint8_t direction);
  uint8_t GetPhyBitmask(void);
  bool ConfigureAses(const le_audio::set_configurations::SetConfiguration& ent,
                     types::LeAudioContextType context_type,
                     uint8_t* number_of_already_active_group_ase,
                     types::AudioLocations& group_snk_audio_locations,
                     types::AudioLocations& group_src_audio_locations,
                     bool reconnect = false, int ccid = -1);
  void SetSupportedContexts(types::AudioContexts snk_contexts,
                            types::AudioContexts src_contexts);
  types::AudioContexts GetAvailableContexts(void);
  types::AudioContexts SetAvailableContexts(types::AudioContexts snk_cont_val,
                                            types::AudioContexts src_cont_val);
  void DeactivateAllAses(void);
  void ActivateConfiguredAses(void);
  void Dump(int fd);
  void DisconnectAcl(void);
  std::vector<uint8_t> GetMetadata(types::LeAudioContextType context_type,
                                   int ccid);
  bool IsMetadataChanged(types::LeAudioContextType context_type, int ccid);

 private:
  types::AudioContexts avail_snk_contexts_;
  types::AudioContexts avail_src_contexts_;
  types::AudioContexts supp_snk_context_;
  types::AudioContexts supp_src_context_;
};

/* LeAudioDevices class represents a wraper helper over all devices in le audio
 * implementation. It allows to operate on device from a list (vector container)
 * using determinants like address, connection id etc.
 */
class LeAudioDevices {
 public:
  void Add(const RawAddress& address, bool first_connection,
           int group_id = bluetooth::groups::kGroupUnknown);
  void Remove(const RawAddress& address);
  LeAudioDevice* FindByAddress(const RawAddress& address);
  std::shared_ptr<LeAudioDevice> GetByAddress(const RawAddress& address);
  LeAudioDevice* FindByConnId(uint16_t conn_id);
  LeAudioDevice* FindByCisConnHdl(const uint16_t conn_hdl);
  size_t Size(void);
  void Dump(int fd, int group_id);
  void Cleanup(void);

 private:
  std::vector<std::shared_ptr<LeAudioDevice>> leAudioDevices_;
};

/* LeAudioDeviceGroup class represents group of LeAudioDevices and allows to
 * perform operations on them. Group states are ASE states due to nature of
 * group which operates finally of ASEs.
 *
 * Group is created after adding a node to new group id (which is not on list).
 */

class LeAudioDeviceGroup {
 public:
  const int group_id_;
  types::CigState cig_state_;

  struct stream_configuration stream_conf;

  uint8_t audio_directions_;
  types::AudioLocations snk_audio_locations_;
  types::AudioLocations src_audio_locations_;

  explicit LeAudioDeviceGroup(const int group_id)
      : group_id_(group_id),
        cig_state_(types::CigState::NONE),
        stream_conf({}),
        audio_directions_(0),
        transport_latency_mtos_us_(0),
        transport_latency_stom_us_(0),
        active_context_type_(types::LeAudioContextType::UNINITIALIZED),
        pending_update_available_contexts_(std::nullopt),
        target_state_(types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE),
        current_state_(types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE),
        context_type_(types::LeAudioContextType::UNINITIALIZED) {}
  ~LeAudioDeviceGroup(void);

  void AddNode(const std::shared_ptr<LeAudioDevice>& leAudioDevice);
  void RemoveNode(const std::shared_ptr<LeAudioDevice>& leAudioDevice);
  bool IsEmpty(void);
  bool IsAnyDeviceConnected(void);
  int Size(void);
  int NumOfConnected(
      types::LeAudioContextType context_type = types::LeAudioContextType::RFU);
  void Activate(void);
  void Deactivate(void);
  void Cleanup(void);
  LeAudioDevice* GetFirstDevice(void);
  LeAudioDevice* GetFirstDeviceWithActiveContext(
      types::LeAudioContextType context_type);
  LeAudioDevice* GetNextDevice(LeAudioDevice* leAudioDevice);
  LeAudioDevice* GetNextDeviceWithActiveContext(
      LeAudioDevice* leAudioDevice, types::LeAudioContextType context_type);
  LeAudioDevice* GetFirstActiveDevice(void);
  LeAudioDevice* GetNextActiveDevice(LeAudioDevice* leAudioDevice);
  bool IsDeviceInTheGroup(LeAudioDevice* leAudioDevice);
  bool HaveAllActiveDevicesAsesTheSameState(types::AseState state);
  bool IsGroupStreamReady(void);
  bool HaveAllActiveDevicesCisDisc(void);
  uint8_t GetFirstFreeCisId(void);
  bool Configure(types::LeAudioContextType context_type, int ccid = 1);
  bool SetContextType(types::LeAudioContextType context_type);
  types::LeAudioContextType GetContextType(void);
  uint32_t GetSduInterval(uint8_t direction);
  uint8_t GetSCA(void);
  uint8_t GetPacking(void);
  uint8_t GetFraming(void);
  uint16_t GetMaxTransportLatencyStom(void);
  uint16_t GetMaxTransportLatencyMtos(void);
  void SetTransportLatency(uint8_t direction, uint32_t transport_latency_us);
  uint8_t GetPhyBitmask(uint8_t direction);
  uint8_t GetTargetPhy(uint8_t direction);
  bool GetPresentationDelay(uint32_t* delay, uint8_t direction);
  uint16_t GetRemoteDelay(uint8_t direction);
  std::optional<types::AudioContexts> UpdateActiveContextsMap(
      types::AudioContexts contexts);
  std::optional<types::AudioContexts> UpdateActiveContextsMap(void);
  bool ReloadAudioLocations(void);
  const set_configurations::AudioSetConfiguration* GetActiveConfiguration(void);
  types::LeAudioContextType GetCurrentContextType(void);
  bool IsPendingConfiguration(void);
  void SetPendingConfiguration(void);
  types::AudioContexts GetActiveContexts(void);
  std::optional<LeAudioCodecConfiguration> GetCodecConfigurationByDirection(
      types::LeAudioContextType group_context_type, uint8_t direction);
  bool IsContextSupported(types::LeAudioContextType group_context_type);
  bool IsMetadataChanged(types::LeAudioContextType group_context_type,
                         int ccid);

  inline types::AseState GetState(void) const { return current_state_; }
  void SetState(types::AseState state) {
    LOG(INFO) << __func__ << " current state: " << current_state_
              << " new state: " << state;
    current_state_ = state;
  }

  inline types::AseState GetTargetState(void) const { return target_state_; }
  void SetTargetState(types::AseState state) {
    LOG(INFO) << __func__ << " target state: " << target_state_
              << " new target state: " << state;
    target_state_ = state;
  }

  inline std::optional<types::AudioContexts> GetPendingUpdateAvailableContexts()
      const {
    return pending_update_available_contexts_;
  }
  inline void SetPendingUpdateAvailableContexts(
      std::optional<types::AudioContexts> audio_contexts) {
    pending_update_available_contexts_ = audio_contexts;
  }

  bool IsInTransition(void);
  bool IsReleasing(void);
  void Dump(int fd);

 private:
  uint32_t transport_latency_mtos_us_;
  uint32_t transport_latency_stom_us_;

  const set_configurations::AudioSetConfiguration*
  FindFirstSupportedConfiguration(types::LeAudioContextType context_type);
  bool ConfigureAses(
      const set_configurations::AudioSetConfiguration* audio_set_conf,
      types::LeAudioContextType context_type, int ccid = 1);
  bool IsConfigurationSupported(
      const set_configurations::AudioSetConfiguration* audio_set_configuration,
      types::LeAudioContextType context_type);
  uint32_t GetTransportLatencyUs(uint8_t direction);

  /* Mask and table of currently supported contexts */
  types::LeAudioContextType active_context_type_;
  types::AudioContexts active_contexts_mask_;
  std::optional<types::AudioContexts> pending_update_available_contexts_;
  std::map<types::LeAudioContextType,
           const set_configurations::AudioSetConfiguration*>
      active_context_to_configuration_map;

  types::AseState target_state_;
  types::AseState current_state_;
  types::LeAudioContextType context_type_;
  std::vector<std::weak_ptr<LeAudioDevice>> leAudioDevices_;
};

/* LeAudioDeviceGroup class represents a wraper helper over all device groups in
 * le audio implementation. It allows to operate on device group from a list
 * (vector container) using determinants like id.
 */
class LeAudioDeviceGroups {
 public:
  LeAudioDeviceGroup* Add(int group_id);
  void Remove(const int group_id);
  LeAudioDeviceGroup* FindById(int group_id);
  std::vector<int> GetGroupsIds(void);
  size_t Size();
  bool IsAnyInTransition();
  void Cleanup(void);
  void Dump(int fd);

 private:
  std::vector<std::unique_ptr<LeAudioDeviceGroup>> groups_;
};
}  // namespace le_audio
