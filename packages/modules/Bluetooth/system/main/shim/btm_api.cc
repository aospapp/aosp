/*
 * Copyright 2019 The Android Open Source Project
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

#define LOG_TAG "bt_shim_btm"

#include "main/shim/btm_api.h"

#include <base/functional/callback.h>
#include <base/logging.h>

#include <mutex>

#include "common/metric_id_allocator.h"
#include "common/time_util.h"
#include "gd/common/callback.h"
#include "gd/os/log.h"
#include "gd/security/security_module.h"
#include "gd/security/ui.h"
#include "main/shim/btm.h"
#include "main/shim/controller.h"
#include "main/shim/helpers.h"
#include "main/shim/metric_id_api.h"
#include "main/shim/shim.h"
#include "main/shim/stack.h"
#include "osi/include/allocator.h"
#include "osi/include/osi.h"  // UNUSED_ATTR
#include "osi/include/properties.h"
#include "stack/btm/btm_ble_int.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/btm_sec.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_octets.h"
#include "stack/include/hci_error_code.h"
#include "types/ble_address_with_type.h"
#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

using bluetooth::common::MetricIdAllocator;

#define BTIF_DM_DEFAULT_INQ_MAX_RESULTS 0
#define BTIF_DM_DEFAULT_INQ_MAX_DURATION 10

/**
 * Legacy bluetooth module global control block state
 *
 * Mutex is used to synchronize access from the shim
 * layer into the global control block.  This is used
 * by the shim despite potentially arbitrary
 * unsynchronized access by the legacy stack.
 */
extern tBTM_CB btm_cb;
std::mutex btm_cb_mutex_;

bool btm_inq_find_bdaddr(const RawAddress& p_bda);
extern tINQ_DB_ENT* btm_inq_db_find(const RawAddress& raw_address);
extern tINQ_DB_ENT* btm_inq_db_new(const RawAddress& p_bda);

/**
 * Legacy bluetooth btm stack entry points
 */
void btm_acl_update_inquiry_status(uint8_t status);
void btm_clear_all_pending_le_entry(void);
void btm_clr_inq_result_flt(void);
void btm_set_eir_uuid(const uint8_t* p_eir, tBTM_INQ_RESULTS* p_results);
void btm_sort_inq_result(void);
void btm_process_inq_complete(tHCI_STATUS status, uint8_t result_type);

static bool is_classic_device(tBT_DEVICE_TYPE device_type) {
  return device_type == BT_DEVICE_TYPE_BREDR;
}

static bool has_classic_device(tBT_DEVICE_TYPE device_type) {
  return device_type & BT_DEVICE_TYPE_BREDR;
}

void btm_api_process_inquiry_result(const RawAddress& raw_address,
                                    uint8_t page_scan_rep_mode,
                                    DEV_CLASS device_class,
                                    uint16_t clock_offset) {
  tINQ_DB_ENT* p_i = btm_inq_db_find(raw_address);

  if (p_i == nullptr) {
    p_i = btm_inq_db_new(raw_address);
    CHECK(p_i != nullptr);
  } else if (p_i->inq_count == btm_cb.btm_inq_vars.inq_counter &&
             is_classic_device(p_i->inq_info.results.device_type)) {
    return;
  }

  p_i->inq_info.results.page_scan_rep_mode = page_scan_rep_mode;
  p_i->inq_info.results.page_scan_per_mode = 0;  // RESERVED
  p_i->inq_info.results.page_scan_mode = 0;      // RESERVED
  p_i->inq_info.results.dev_class[0] = device_class[0];
  p_i->inq_info.results.dev_class[1] = device_class[1];
  p_i->inq_info.results.dev_class[2] = device_class[2];
  p_i->inq_info.results.clock_offset = clock_offset | BTM_CLOCK_OFFSET_VALID;
  p_i->inq_info.results.inq_result_type = BTM_INQ_RESULT_BR;
  p_i->inq_info.results.rssi = BTM_INQ_RES_IGNORE_RSSI;

  p_i->time_of_resp = bluetooth::common::time_get_os_boottime_ms();
  p_i->inq_count = btm_cb.btm_inq_vars.inq_counter;
  p_i->inq_info.appl_knows_rem_name = false;

  if (p_i->inq_count != btm_cb.btm_inq_vars.inq_counter) {
    p_i->inq_info.results.device_type = BT_DEVICE_TYPE_BREDR;
    btm_cb.btm_inq_vars.inq_cmpl_info.num_resp++;
    p_i->scan_rsp = false;
  } else {
    p_i->inq_info.results.device_type |= BT_DEVICE_TYPE_BREDR;
  }

  if (btm_cb.btm_inq_vars.p_inq_results_cb == nullptr) {
    return;
  }

  (btm_cb.btm_inq_vars.p_inq_results_cb)(&p_i->inq_info.results, nullptr, 0);
}

void btm_api_process_inquiry_result_with_rssi(RawAddress raw_address,
                                              uint8_t page_scan_rep_mode,
                                              DEV_CLASS device_class,
                                              uint16_t clock_offset,
                                              int8_t rssi) {
  tINQ_DB_ENT* p_i = btm_inq_db_find(raw_address);

  bool update = false;
  if (btm_inq_find_bdaddr(raw_address)) {
    if (p_i != nullptr &&
        (rssi > p_i->inq_info.results.rssi || p_i->inq_info.results.rssi == 0 ||
         has_classic_device(p_i->inq_info.results.device_type))) {
      update = true;
    }
  }

  bool is_new = true;
  if (p_i == nullptr) {
    p_i = btm_inq_db_new(raw_address);
    CHECK(p_i != nullptr);
  } else if (p_i->inq_count == btm_cb.btm_inq_vars.inq_counter &&
             is_classic_device(p_i->inq_info.results.device_type)) {
    is_new = false;
  }

  p_i->inq_info.results.rssi = rssi;

  if (is_new) {
    p_i->inq_info.results.page_scan_rep_mode = page_scan_rep_mode;
    p_i->inq_info.results.page_scan_per_mode = 0;  // RESERVED
    p_i->inq_info.results.page_scan_mode = 0;      // RESERVED
    p_i->inq_info.results.dev_class[0] = device_class[0];
    p_i->inq_info.results.dev_class[1] = device_class[1];
    p_i->inq_info.results.dev_class[2] = device_class[2];
    p_i->inq_info.results.clock_offset = clock_offset | BTM_CLOCK_OFFSET_VALID;
    p_i->inq_info.results.inq_result_type = BTM_INQ_RESULT_BR;

    p_i->time_of_resp = bluetooth::common::time_get_os_boottime_ms();
    p_i->inq_count = btm_cb.btm_inq_vars.inq_counter;
    p_i->inq_info.appl_knows_rem_name = false;

    if (p_i->inq_count != btm_cb.btm_inq_vars.inq_counter) {
      p_i->inq_info.results.device_type = BT_DEVICE_TYPE_BREDR;
      btm_cb.btm_inq_vars.inq_cmpl_info.num_resp++;
      p_i->scan_rsp = false;
    } else {
      p_i->inq_info.results.device_type |= BT_DEVICE_TYPE_BREDR;
    }
  }

  if (btm_cb.btm_inq_vars.p_inq_results_cb == nullptr) {
    return;
  }

  if (is_new || update) {
    (btm_cb.btm_inq_vars.p_inq_results_cb)(&p_i->inq_info.results, nullptr, 0);
  }
}
void btm_api_process_extended_inquiry_result(RawAddress raw_address,
                                             uint8_t page_scan_rep_mode,
                                             DEV_CLASS device_class,
                                             uint16_t clock_offset, int8_t rssi,
                                             const uint8_t* eir_data,
                                             size_t eir_len) {
  tINQ_DB_ENT* p_i = btm_inq_db_find(raw_address);

  bool update = false;
  if (btm_inq_find_bdaddr(raw_address) && p_i != nullptr) {
    update = true;
  }

  bool is_new = true;
  if (p_i == nullptr) {
    p_i = btm_inq_db_new(raw_address);
  } else if (p_i->inq_count == btm_cb.btm_inq_vars.inq_counter &&
             (p_i->inq_info.results.device_type == BT_DEVICE_TYPE_BREDR)) {
    is_new = false;
  }

  p_i->inq_info.results.rssi = rssi;

  if (is_new) {
    p_i->inq_info.results.page_scan_rep_mode = page_scan_rep_mode;
    p_i->inq_info.results.page_scan_per_mode = 0;  // RESERVED
    p_i->inq_info.results.page_scan_mode = 0;      // RESERVED
    p_i->inq_info.results.dev_class[0] = device_class[0];
    p_i->inq_info.results.dev_class[1] = device_class[1];
    p_i->inq_info.results.dev_class[2] = device_class[2];
    p_i->inq_info.results.clock_offset = clock_offset | BTM_CLOCK_OFFSET_VALID;
    p_i->inq_info.results.inq_result_type = BTM_INQ_RESULT_BR;

    p_i->time_of_resp = bluetooth::common::time_get_os_boottime_ms();
    p_i->inq_count = btm_cb.btm_inq_vars.inq_counter;
    p_i->inq_info.appl_knows_rem_name = false;

    if (p_i->inq_count != btm_cb.btm_inq_vars.inq_counter) {
      p_i->inq_info.results.device_type = BT_DEVICE_TYPE_BREDR;
      btm_cb.btm_inq_vars.inq_cmpl_info.num_resp++;
      p_i->scan_rsp = false;
    } else {
      p_i->inq_info.results.device_type |= BT_DEVICE_TYPE_BREDR;
    }
  }

  if (btm_cb.btm_inq_vars.p_inq_results_cb == nullptr) {
    return;
  }

  if (is_new || update) {
    memset(p_i->inq_info.results.eir_uuid, 0,
           BTM_EIR_SERVICE_ARRAY_SIZE * (BTM_EIR_ARRAY_BITS / 8));
    btm_set_eir_uuid(const_cast<uint8_t*>(eir_data), &p_i->inq_info.results);
    uint8_t* p_eir_data = const_cast<uint8_t*>(eir_data);
    (btm_cb.btm_inq_vars.p_inq_results_cb)(&p_i->inq_info.results, p_eir_data,
                                           eir_len);
  }
}

namespace {
std::unordered_map<bluetooth::hci::AddressWithType, bt_bdname_t>
    address_name_map_;

std::unordered_map<bluetooth::hci::IoCapability, int> gd_legacy_io_caps_map_ = {
    {bluetooth::hci::IoCapability::DISPLAY_ONLY, BTM_IO_CAP_OUT},
    {bluetooth::hci::IoCapability::DISPLAY_YES_NO, BTM_IO_CAP_IO},
    {bluetooth::hci::IoCapability::KEYBOARD_ONLY, BTM_IO_CAP_IN},
    {bluetooth::hci::IoCapability::NO_INPUT_NO_OUTPUT, BTM_IO_CAP_NONE},
};

std::unordered_map<bluetooth::hci::AuthenticationRequirements, int>
    gd_legacy_auth_reqs_map_ = {
        {bluetooth::hci::AuthenticationRequirements::NO_BONDING,
         BTM_AUTH_SP_NO},
        {bluetooth::hci::AuthenticationRequirements::NO_BONDING_MITM_PROTECTION,
         BTM_AUTH_SP_YES},
        {bluetooth::hci::AuthenticationRequirements::DEDICATED_BONDING,
         BTM_AUTH_AP_NO},
        {bluetooth::hci::AuthenticationRequirements::
             DEDICATED_BONDING_MITM_PROTECTION,
         BTM_AUTH_AP_YES},
        {bluetooth::hci::AuthenticationRequirements::GENERAL_BONDING,
         BTM_AUTH_SPGB_NO},
        {bluetooth::hci::AuthenticationRequirements::
             GENERAL_BONDING_MITM_PROTECTION,
         BTM_AUTH_SPGB_YES},
};
}

class ShimUi : public bluetooth::security::UI {
 public:
  static ShimUi* GetInstance() {
    static ShimUi instance;
    return &instance;
  }

  ShimUi(const ShimUi&) = delete;
  ShimUi& operator=(const ShimUi&) = delete;

  void SetBtaCallbacks(const tBTM_APPL_INFO* bta_callbacks) {
    bta_callbacks_ = bta_callbacks;
    if (bta_callbacks->p_pin_callback == nullptr) {
      LOG_INFO("UNIMPLEMENTED %s pin_callback", __func__);
    }

    if (bta_callbacks->p_link_key_callback == nullptr) {
      LOG_INFO("UNIMPLEMENTED %s link_key_callback", __func__);
    }

    if (bta_callbacks->p_auth_complete_callback == nullptr) {
      LOG_INFO("UNIMPLEMENTED %s auth_complete_callback", __func__);
    }

    if (bta_callbacks->p_bond_cancel_cmpl_callback == nullptr) {
      LOG_INFO("UNIMPLEMENTED %s bond_cancel_complete_callback", __func__);
    }

    if (bta_callbacks->p_le_callback == nullptr) {
      LOG_INFO("UNIMPLEMENTED %s le_callback", __func__);
    }

    if (bta_callbacks->p_le_key_callback == nullptr) {
      LOG_INFO("UNIMPLEMENTED %s le_key_callback", __func__);
    }
  }

  void DisplayPairingPrompt(const bluetooth::hci::AddressWithType& address,
                            std::string name) {
    waiting_for_pairing_prompt_ = true;
    bt_bdname_t legacy_name{0};
    memcpy(legacy_name.name, name.data(), name.length());
    // TODO(optedoblivion): Handle callback to BTA for BLE
  }

  void Cancel(const bluetooth::hci::AddressWithType& address) {
    LOG(WARNING) << " ■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■ " << __func__;
  }

  void HandleConfirm(bluetooth::security::ConfirmationData data) {
    const bluetooth::hci::AddressWithType& address = data.GetAddressWithType();
    uint32_t numeric_value = data.GetNumericValue();
    bt_bdname_t legacy_name{0};
    memcpy(legacy_name.name, data.GetName().data(), data.GetName().length());

    if (bta_callbacks_->p_sp_callback) {
      // Call sp_cback for IO_REQ
      tBTM_SP_IO_REQ io_req_evt_data;
      io_req_evt_data.bd_addr = bluetooth::ToRawAddress(address.GetAddress());
      // Local IO Caps (Phone is always DisplayYesNo)
      io_req_evt_data.io_cap = BTM_IO_CAP_IO;
      // Local Auth Reqs (Phone is always DEDICATED_BONDING)
      io_req_evt_data.auth_req = BTM_AUTH_AP_NO;
      io_req_evt_data.oob_data = BTM_OOB_NONE;
      (*bta_callbacks_->p_sp_callback)(BTM_SP_IO_REQ_EVT,
                                       (tBTM_SP_EVT_DATA*)&io_req_evt_data);

      // Call sp_cback for IO_RSP
      tBTM_SP_IO_RSP io_rsp_evt_data;
      io_rsp_evt_data.bd_addr = bluetooth::ToRawAddress(address.GetAddress());
      io_rsp_evt_data.io_cap = gd_legacy_io_caps_map_[data.GetRemoteIoCaps()];
      io_rsp_evt_data.auth_req =
          gd_legacy_auth_reqs_map_[data.GetRemoteAuthReqs()];
      io_rsp_evt_data.auth_req = BTM_AUTH_AP_YES;
      io_rsp_evt_data.oob_data = BTM_OOB_NONE;
      (*bta_callbacks_->p_sp_callback)(BTM_SP_IO_RSP_EVT,
                                       (tBTM_SP_EVT_DATA*)&io_rsp_evt_data);

      // Call sp_cback for USER_CONFIRMATION
      tBTM_SP_EVT_DATA user_cfm_req_evt_data;
      user_cfm_req_evt_data.cfm_req.bd_addr =
          bluetooth::ToRawAddress(address.GetAddress());
      user_cfm_req_evt_data.cfm_req.num_val = numeric_value;
      // If we pop a dialog then it isn't just_works
      user_cfm_req_evt_data.cfm_req.just_works = data.IsJustWorks();

      address_name_map_.emplace(address, legacy_name);
      memcpy((char*)user_cfm_req_evt_data.cfm_req.bd_name, legacy_name.name,
             BD_NAME_LEN);

      (*bta_callbacks_->p_sp_callback)(BTM_SP_CFM_REQ_EVT,
                                       &user_cfm_req_evt_data);
    }
  }

  void DisplayConfirmValue(bluetooth::security::ConfirmationData data) {
    waiting_for_pairing_prompt_ = false;
    data.SetJustWorks(false);
    HandleConfirm(data);
  }

  void DisplayYesNoDialog(bluetooth::security::ConfirmationData data) {
    waiting_for_pairing_prompt_ = false;
    data.SetJustWorks(true);
    HandleConfirm(data);
  }

  void DisplayEnterPasskeyDialog(bluetooth::security::ConfirmationData data) {
    waiting_for_pairing_prompt_ = false;
    LOG_WARN("UNIMPLEMENTED, Passkey not supported in GD");
  }

  void DisplayPasskey(bluetooth::security::ConfirmationData data) {
    waiting_for_pairing_prompt_ = false;
    LOG_WARN("UNIMPLEMENTED, Passkey not supported in GD");
  }

  void DisplayEnterPinDialog(bluetooth::security::ConfirmationData data) {
    waiting_for_pairing_prompt_ = false;
    LOG_WARN("UNIMPLEMENTED, PIN not supported in GD");
  }

  bool waiting_for_pairing_prompt_ = false;

 private:
  ShimUi() : bta_callbacks_(nullptr) {}
  ~ShimUi() {}
  const tBTM_APPL_INFO* bta_callbacks_;
};

ShimUi* shim_ui_ = nullptr;

class ShimBondListener : public bluetooth::security::ISecurityManagerListener {
 public:
  static ShimBondListener* GetInstance() {
    static ShimBondListener instance;
    return &instance;
  }

  ShimBondListener(const ShimBondListener&) = delete;
  ShimBondListener& operator=(const ShimBondListener&) = delete;

  void SetBtaCallbacks(const tBTM_APPL_INFO* bta_callbacks) {
    bta_callbacks_ = bta_callbacks;
    if (bta_callbacks->p_pin_callback == nullptr) {
      LOG_INFO("UNIMPLEMENTED %s pin_callback", __func__);
    }

    if (bta_callbacks->p_link_key_callback == nullptr) {
      LOG_INFO("UNIMPLEMENTED %s link_key_callback", __func__);
    }

    if (bta_callbacks->p_auth_complete_callback == nullptr) {
      LOG_INFO("UNIMPLEMENTED %s auth_complete_callback", __func__);
    }

    if (bta_callbacks->p_bond_cancel_cmpl_callback == nullptr) {
      LOG_INFO("UNIMPLEMENTED %s bond_cancel_complete_callback", __func__);
    }

    if (bta_callbacks->p_le_callback == nullptr) {
      LOG_INFO("UNIMPLEMENTED %s le_callback", __func__);
    }

    if (bta_callbacks->p_le_key_callback == nullptr) {
      LOG_INFO("UNIMPLEMENTED %s le_key_callback", __func__);
    }
  }

  void OnDeviceBonded(bluetooth::hci::AddressWithType device) override {
    // Call sp_cback for LINK_KEY_NOTIFICATION
    // Call AUTHENTICATION_COMPLETE callback
    if (device.GetAddressType() ==
        bluetooth::hci::AddressType::PUBLIC_DEVICE_ADDRESS) {
      auto it = address_name_map_.find(device);
      bt_bdname_t tmp_name;
      if (it != address_name_map_.end()) {
        tmp_name = it->second;
      }
      BD_NAME name;
      memcpy((char*)name, tmp_name.name, BD_NAME_LEN);

      if (*bta_callbacks_->p_link_key_callback) {
        LinkKey key;  // Never want to send the key to the stack
        (*bta_callbacks_->p_link_key_callback)(
            bluetooth::ToRawAddress(device.GetAddress()), 0, name, key,
            BTM_LKEY_TYPE_COMBINATION, false /* is_ctkd */);
      }
      if (*bta_callbacks_->p_auth_complete_callback) {
        (*bta_callbacks_->p_auth_complete_callback)(
            bluetooth::ToRawAddress(device.GetAddress()), 0, name, HCI_SUCCESS);
      }
    }
    bluetooth::shim::AllocateIdFromMetricIdAllocator(
        bluetooth::ToRawAddress(device.GetAddress()));
    bool is_saving_successful = bluetooth::shim::SaveDeviceOnMetricIdAllocator(
        bluetooth::ToRawAddress(device.GetAddress()));
    if (!is_saving_successful) {
      LOG(FATAL) << __func__ << ": Fail to save metric id for device "
                 << bluetooth::ToRawAddress(device.GetAddress());
    }
  }

  void OnDeviceUnbonded(bluetooth::hci::AddressWithType device) override {
    if (bta_callbacks_->p_bond_cancel_cmpl_callback) {
      (*bta_callbacks_->p_bond_cancel_cmpl_callback)(BTM_SUCCESS);
    }
    bluetooth::shim::ForgetDeviceFromMetricIdAllocator(
        bluetooth::ToRawAddress(device.GetAddress()));
  }

  void OnDeviceBondFailed(bluetooth::hci::AddressWithType device,
                          bluetooth::security::PairingFailure status) override {
    auto it = address_name_map_.find(device);
    bt_bdname_t tmp_name;
    if (it != address_name_map_.end()) {
      tmp_name = it->second;
    }
    BD_NAME name;
    memcpy((char*)name, tmp_name.name, BD_NAME_LEN);

    if (bta_callbacks_->p_auth_complete_callback) {
      (*bta_callbacks_->p_auth_complete_callback)(
          bluetooth::ToRawAddress(device.GetAddress()), 0, name,
          HCI_ERR_AUTH_FAILURE);
    }
  }

  void OnEncryptionStateChanged(
      bluetooth::hci::EncryptionChangeView encryption_change_view) override {
    // TODO(optedoblivion): Find BTA callback for this to call
  }

 private:
  ShimBondListener() : bta_callbacks_(nullptr) {}
  ~ShimBondListener() {}
  const tBTM_APPL_INFO* bta_callbacks_;
};

tBTM_STATUS bluetooth::shim::BTM_CancelRemoteDeviceName(void) {
  return Stack::GetInstance()->GetBtm()->CancelAllReadRemoteDeviceName();
}

tBTM_INQ_INFO* bluetooth::shim::BTM_InqDbRead(const RawAddress& p_bda) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
  return nullptr;
}

tBTM_INQ_INFO* bluetooth::shim::BTM_InqDbFirst(void) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
  return nullptr;
}

tBTM_INQ_INFO* bluetooth::shim::BTM_InqDbNext(tBTM_INQ_INFO* p_cur) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
  CHECK(p_cur != nullptr);
  return nullptr;
}

tBTM_STATUS bluetooth::shim::BTM_ClearInqDb(const RawAddress* p_bda) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
  if (p_bda == nullptr) {
    // clear all entries
  } else {
    // clear specific entry
  }
  return BTM_NO_RESOURCES;
}

bool bluetooth::shim::BTM_HasEirService(const uint32_t* p_eir_uuid,
                                        uint16_t uuid16) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
  CHECK(p_eir_uuid != nullptr);
  return false;
}

tBTM_EIR_SEARCH_RESULT bluetooth::shim::BTM_HasInquiryEirService(
    tBTM_INQ_RESULTS* p_results, uint16_t uuid16) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
  CHECK(p_results != nullptr);
  return BTM_EIR_UNKNOWN;
}

void bluetooth::shim::BTM_AddEirService(uint32_t* p_eir_uuid, uint16_t uuid16) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
  CHECK(p_eir_uuid != nullptr);
}

void bluetooth::shim::BTM_ReadConnectionAddr(const RawAddress& remote_bda,
                                             RawAddress& local_conn_addr,
                                             tBLE_ADDR_TYPE* p_addr_type) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
  CHECK(p_addr_type != nullptr);
}

bool bluetooth::shim::BTM_ReadRemoteConnectionAddr(
    const RawAddress& pseudo_addr, RawAddress& conn_addr,
    tBLE_ADDR_TYPE* p_addr_type) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
  CHECK(p_addr_type != nullptr);
  return false;
}

void bluetooth::shim::BTM_BleSetConnScanParams(uint32_t scan_interval,
                                               uint32_t scan_window) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
}

void bluetooth::shim::BTM_ReadDevInfo(const RawAddress& remote_bda,
                                      tBT_DEVICE_TYPE* p_dev_type,
                                      tBLE_ADDR_TYPE* p_addr_type) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
  CHECK(p_dev_type != nullptr);
  CHECK(p_addr_type != nullptr);
}

bool bluetooth::shim::BTM_ReadConnectedTransportAddress(
    RawAddress* remote_bda, tBT_TRANSPORT transport) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
  CHECK(remote_bda != nullptr);
  return false;
}

bool bluetooth::shim::BTM_GetLeSecurityState(const RawAddress& bd_addr,
                                             uint8_t* p_le_dev_sec_flags,
                                             uint8_t* p_le_key_size) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
  CHECK(p_le_dev_sec_flags != nullptr);
  CHECK(p_le_key_size != nullptr);
  return false;
}

bool bluetooth::shim::BTM_BleSecurityProcedureIsRunning(
    const RawAddress& bd_addr) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
  return false;
}

uint8_t bluetooth::shim::BTM_BleGetSupportedKeySize(const RawAddress& bd_addr) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
  return 0;
}

/**
 * This function update(add,delete or clear) the adv local name filtering
 * condition.
 */
void bluetooth::shim::BTM_LE_PF_local_name(tBTM_BLE_SCAN_COND_OP action,
                                           tBTM_BLE_PF_FILT_INDEX filt_index,
                                           std::vector<uint8_t> name,
                                           tBTM_BLE_PF_CFG_CBACK cb) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
}

void bluetooth::shim::BTM_LE_PF_srvc_data(tBTM_BLE_SCAN_COND_OP action,
                                          tBTM_BLE_PF_FILT_INDEX filt_index) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
}

void bluetooth::shim::BTM_LE_PF_manu_data(
    tBTM_BLE_SCAN_COND_OP action, tBTM_BLE_PF_FILT_INDEX filt_index,
    uint16_t company_id, uint16_t company_id_mask, std::vector<uint8_t> data,
    std::vector<uint8_t> data_mask, tBTM_BLE_PF_CFG_CBACK cb) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
}

void bluetooth::shim::BTM_LE_PF_srvc_data_pattern(
    tBTM_BLE_SCAN_COND_OP action, tBTM_BLE_PF_FILT_INDEX filt_index,
    std::vector<uint8_t> data, std::vector<uint8_t> data_mask,
    tBTM_BLE_PF_CFG_CBACK cb) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
}

void bluetooth::shim::BTM_LE_PF_addr_filter(tBTM_BLE_SCAN_COND_OP action,
                                            tBTM_BLE_PF_FILT_INDEX filt_index,
                                            tBLE_BD_ADDR addr,
                                            tBTM_BLE_PF_CFG_CBACK cb) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
}

void bluetooth::shim::BTM_LE_PF_uuid_filter(tBTM_BLE_SCAN_COND_OP action,
                                            tBTM_BLE_PF_FILT_INDEX filt_index,
                                            tBTM_BLE_PF_COND_TYPE filter_type,
                                            const bluetooth::Uuid& uuid,
                                            tBTM_BLE_PF_LOGIC_TYPE cond_logic,
                                            const bluetooth::Uuid& uuid_mask,
                                            tBTM_BLE_PF_CFG_CBACK cb) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
}

void bluetooth::shim::BTM_LE_PF_set(tBTM_BLE_PF_FILT_INDEX filt_index,
                                    std::vector<ApcfCommand> commands,
                                    tBTM_BLE_PF_CFG_CBACK cb) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
}

void bluetooth::shim::BTM_LE_PF_clear(tBTM_BLE_PF_FILT_INDEX filt_index,
                                      tBTM_BLE_PF_CFG_CBACK cb) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
}

void bluetooth::shim::BTM_BleAdvFilterParamSetup(
    tBTM_BLE_SCAN_COND_OP action, tBTM_BLE_PF_FILT_INDEX filt_index,
    std::unique_ptr<btgatt_filt_param_setup_t> p_filt_params,
    tBTM_BLE_PF_PARAM_CB cb) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
}

void bluetooth::shim::BTM_BleUpdateAdvFilterPolicy(tBTM_BLE_AFP adv_policy) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
}

void bluetooth::shim::BTM_BleEnableDisableFilterFeature(
    uint8_t enable, tBTM_BLE_PF_STATUS_CBACK p_stat_cback) {
  LOG_INFO("UNIMPLEMENTED %s", __func__);
}

tBTM_STATUS bluetooth::shim::BTM_SecBond(const RawAddress& bd_addr,
                                         tBLE_ADDR_TYPE addr_type,
                                         tBT_TRANSPORT transport,
                                         tBT_DEVICE_TYPE device_type) {
  return Stack::GetInstance()->GetBtm()->CreateBond(bd_addr, addr_type,
                                                    transport, device_type);
}

bool bluetooth::shim::BTM_SecAddDevice(const RawAddress& bd_addr,
                                       DEV_CLASS dev_class,
                                       const BD_NAME& bd_name,
                                       uint8_t* features, LinkKey* link_key,
                                       uint8_t key_type, uint8_t pin_length) {
  // Check if GD has a security record for the device
  return BTM_SUCCESS;
}

void bluetooth::shim::BTM_ConfirmReqReply(tBTM_STATUS res,
                                          const RawAddress& bd_addr) {
  // Send for both Classic and LE until we can determine the type
  bool accept = res == BTM_SUCCESS;
  hci::AddressWithType address = ToAddressWithType(bd_addr, BLE_ADDR_PUBLIC);
  hci::AddressWithType address2 = ToAddressWithType(bd_addr, BLE_ADDR_RANDOM);
  auto security_manager =
      bluetooth::shim::GetSecurityModule()->GetSecurityManager();
  if (ShimUi::GetInstance()->waiting_for_pairing_prompt_) {
    LOG(INFO) << "interpreting confirmation as pairing accept " << address;
    security_manager->OnPairingPromptAccepted(address, accept);
    security_manager->OnPairingPromptAccepted(address2, accept);
    ShimUi::GetInstance()->waiting_for_pairing_prompt_ = false;
  } else {
    LOG(INFO) << "interpreting confirmation as yes/no confirmation " << address;
    security_manager->OnConfirmYesNo(address, accept);
    security_manager->OnConfirmYesNo(address2, accept);
  }
}

uint16_t bluetooth::shim::BTM_GetHCIConnHandle(const RawAddress& remote_bda,
                                               tBT_TRANSPORT transport) {
  return Stack::GetInstance()->GetBtm()->GetAclHandle(remote_bda, transport);
}

void bluetooth::shim::BTM_SecClearSecurityFlags(const RawAddress& bd_addr) {
  // TODO(optedoblivion): Call RemoveBond on device address
}

char* bluetooth::shim::BTM_SecReadDevName(const RawAddress& address) {
  static char name[] = "TODO: See if this is needed";
  return name;
}

bool bluetooth::shim::BTM_SecAddRmtNameNotifyCallback(
    tBTM_RMT_NAME_CALLBACK* p_callback) {
  // TODO(optedoblivion): keep track of callback
  LOG_WARN("Unimplemented");
  return true;
}

bool bluetooth::shim::BTM_SecDeleteRmtNameNotifyCallback(
    tBTM_RMT_NAME_CALLBACK* p_callback) {
  // TODO(optedoblivion): stop keeping track of callback
  LOG_WARN("Unimplemented");
  return true;
}

void bluetooth::shim::BTM_PINCodeReply(const RawAddress& bd_addr,
                                       tBTM_STATUS res, uint8_t pin_len,
                                       uint8_t* p_pin) {
  ASSERT_LOG(!bluetooth::shim::is_gd_shim_enabled(), "Unreachable code path");
}

void bluetooth::shim::BTM_RemoteOobDataReply(tBTM_STATUS res,
                                             const RawAddress& bd_addr,
                                             const Octet16& c,
                                             const Octet16& r) {
  ASSERT_LOG(!bluetooth::shim::is_gd_shim_enabled(), "Unreachable code path");
}

tBTM_STATUS bluetooth::shim::BTM_SetDeviceClass(DEV_CLASS dev_class) {
  // TODO(optedoblivion): see if we need this, I don't think we do
  LOG_WARN("Unimplemented");
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_ClearEventFilter() {
  controller_get_interface()->clear_event_filter();
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_ClearEventMask() {
  controller_get_interface()->clear_event_mask();
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_ClearFilterAcceptList() {
  Stack::GetInstance()->GetAcl()->ClearFilterAcceptList();
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_DisconnectAllAcls() {
  Stack::GetInstance()->GetAcl()->DisconnectAllForSuspend();
//  Stack::GetInstance()->GetAcl()->Shutdown();
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_LeRand(LeRandCallback cb) {
  Stack::GetInstance()->GetAcl()->LeRand(cb);
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_SetEventFilterConnectionSetupAllDevices() {
  // Autoplumbed
  controller_get_interface()->set_event_filter_connection_setup_all_devices();
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_AllowWakeByHid(
    std::vector<RawAddress> classic_hid_devices,
    std::vector<std::pair<RawAddress, uint8_t>> le_hid_devices) {
  // First set ACL to suspended state.
  Stack::GetInstance()->GetAcl()->SetSystemSuspendState(/*suspended=*/true);

  // Allow classic HID wake.
  controller_get_interface()->set_event_filter_allow_device_connection(
      std::move(classic_hid_devices));

  // Allow BLE HID
  for (auto hid_address : le_hid_devices) {
    std::promise<bool> accept_promise;
    auto accept_future = accept_promise.get_future();

    Stack::GetInstance()->GetAcl()->AcceptLeConnectionFrom(
        ToAddressWithType(hid_address.first, hid_address.second),
        /*is_direct=*/false, std::move(accept_promise));

    accept_future.wait();
  }

  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_RestoreFilterAcceptList(
    std::vector<std::pair<RawAddress, uint8_t>> le_devices) {
  // First, mark ACL as no longer suspended.
  Stack::GetInstance()->GetAcl()->SetSystemSuspendState(/*suspended=*/false);

  // Next, Allow BLE connection from all devices that need to be restored.
  // This will also re-arm the LE connection.
  for (auto address_pair : le_devices) {
    std::promise<bool> accept_promise;
    auto accept_future = accept_promise.get_future();

    Stack::GetInstance()->GetAcl()->AcceptLeConnectionFrom(
        ToAddressWithType(address_pair.first, address_pair.second),
        /*is_direct=*/false, std::move(accept_promise));

    accept_future.wait();
  }

  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_SetDefaultEventMaskExcept(uint64_t mask,
                                                           uint64_t le_mask) {
  // Autoplumbed
  controller_get_interface()->set_default_event_mask_except(mask, le_mask);
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_SetEventFilterInquiryResultAllDevices() {
  // Autoplumbed
  controller_get_interface()->set_event_filter_inquiry_result_all_devices();
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_BleResetId() {
  btm_ble_reset_id();
  return BTM_SUCCESS;
}
