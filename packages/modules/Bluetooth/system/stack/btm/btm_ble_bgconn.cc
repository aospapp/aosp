/******************************************************************************
 *
 *  Copyright 1999-2012 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

/******************************************************************************
 *
 *  This file contains functions for BLE acceptlist operation.
 *
 ******************************************************************************/

#include <base/functional/bind.h>
#include <base/logging.h>

#include <cstdint>
#include <unordered_map>

#include "device/include/controller.h"
#include "main/shim/acl_api.h"
#include "main/shim/shim.h"
#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/security_device_record.h"
#include "stack/include/bt_types.h"
#include "types/raw_address.h"

extern tBTM_CB btm_cb;

namespace {


}  // namespace

// Unfortunately (for now?) we have to maintain a copy of the device acceptlist
// on the host to determine if a device is pending to be connected or not. This
// controls whether the host should keep trying to scan for acceptlisted
// peripherals or not.
// TODO: Move all of this to controller/le/background_list or similar?
struct BackgroundConnection {
  RawAddress address;
  uint8_t addr_type;
  bool in_controller_wl;
  uint8_t addr_type_in_wl;
  bool pending_removal;
};

struct BgConnHash {
  std::size_t operator()(const RawAddress& x) const {
    const uint8_t* a = x.address;
    return a[0] ^ (a[1] << 8) ^ (a[2] << 16) ^ (a[3] << 24) ^ a[4] ^
           (a[5] << 8);
  }
};

static std::unordered_map<RawAddress, BackgroundConnection, BgConnHash>
    background_connections;

const tBLE_BD_ADDR convert_to_address_with_type(
    const RawAddress& bd_addr, const tBTM_SEC_DEV_REC* p_dev_rec) {
  if (p_dev_rec == nullptr || !p_dev_rec->is_device_type_has_ble()) {
    return {
        .type = BLE_ADDR_PUBLIC,
        .bda = bd_addr,
    };
  }

  if (p_dev_rec->ble.identity_address_with_type.bda.IsEmpty()) {
    return {
        .type = p_dev_rec->ble.AddressType(),
        .bda = bd_addr,
    };
  } else {
    // Floss doesn't support LL Privacy (yet). To expedite ARC testing, always
    // connect to the latest LE random address rather than redesign.
    // TODO(b/235218533): Remove when LL Privacy is implemented.
#if TARGET_FLOSS
    return {
        .type = BLE_ADDR_RANDOM,
        .bda = p_dev_rec->ble.cur_rand_addr.address,
    };
#endif
    return p_dev_rec->ble.identity_address_with_type;
  }
}

/*******************************************************************************
 *
 * Function         btm_update_scanner_filter_policy
 *
 * Description      This function updates the filter policy of scanner
 ******************************************************************************/
void btm_update_scanner_filter_policy(tBTM_BLE_SFP scan_policy) {
  tBTM_BLE_INQ_CB* p_inq = &btm_cb.ble_ctr_cb.inq_var;

  uint32_t scan_interval =
      !p_inq->scan_interval ? BTM_BLE_GAP_DISC_SCAN_INT : p_inq->scan_interval;
  uint32_t scan_window =
      !p_inq->scan_window ? BTM_BLE_GAP_DISC_SCAN_WIN : p_inq->scan_window;

  BTM_TRACE_EVENT("%s", __func__);

  p_inq->sfp = scan_policy;
  p_inq->scan_type = p_inq->scan_type == BTM_BLE_SCAN_MODE_NONE
                         ? BTM_BLE_SCAN_MODE_ACTI
                         : p_inq->scan_type;

  btm_send_hci_set_scan_params(
      p_inq->scan_type, (uint16_t)scan_interval, (uint16_t)scan_window,
      btm_cb.ble_ctr_cb.addr_mgnt_cb.own_addr_type, scan_policy);
}

/*******************************************************************************
 *
 * Function         btm_ble_suspend_bg_conn
 *
 * Description      This function is to suspend an active background connection
 *                  procedure.
 *
 * Parameters       none.
 *
 * Returns          none.
 *
 ******************************************************************************/
bool btm_ble_suspend_bg_conn(void) {
    LOG_DEBUG("Gd acl_manager handles sync of background connections");
    return true;
}

/*******************************************************************************
 *
 * Function         btm_ble_resume_bg_conn
 *
 * Description      This function is to resume a background auto connection
 *                  procedure.
 *
 * Parameters       none.
 *
 * Returns          none.
 *
 ******************************************************************************/
bool btm_ble_resume_bg_conn(void) {
    LOG_DEBUG("Gd acl_manager handles sync of background connections");
    return true;
}

bool BTM_BackgroundConnectAddressKnown(const RawAddress& address) {
  tBTM_SEC_DEV_REC* p_dev_rec = btm_find_dev(address);

  //  not a known device, or a classic device, we assume public address
  if (p_dev_rec == NULL || (p_dev_rec->device_type & BT_DEVICE_TYPE_BLE) == 0)
    return true;

  // bonded device with identity address known
  if (!p_dev_rec->ble.identity_address_with_type.bda.IsEmpty()) {
    return true;
  }

  // Public address, Random Static, or Random Non-Resolvable Address known
  if (p_dev_rec->ble.AddressType() == BLE_ADDR_PUBLIC ||
      !BTM_BLE_IS_RESOLVE_BDA(address)) {
    return true;
  }

  // Only Resolvable Private Address (RPA) is known, we don't allow it into
  // the background connection procedure.
  return false;
}

/** Adds the device into acceptlist. Returns false if acceptlist is full and
 * device can't be added, true otherwise. */
bool BTM_AcceptlistAdd(const RawAddress& address) {
  return BTM_AcceptlistAdd(address, false);
}

/** Adds the device into acceptlist and indicates whether to using direct
 * connect parameters. Returns false if acceptlist is full and device can't
 * be added, true otherwise. */
bool BTM_AcceptlistAdd(const RawAddress& address, bool is_direct) {
  if (!controller_get_interface()->supports_ble()) {
    LOG_WARN("Controller does not support Le");
    return false;
  }

  tBTM_SEC_DEV_REC* p_dev_rec = btm_find_dev(address);

  return bluetooth::shim::ACL_AcceptLeConnectionFrom(
      convert_to_address_with_type(address, p_dev_rec), is_direct);
}

/** Removes the device from acceptlist */
void BTM_AcceptlistRemove(const RawAddress& address) {
  if (!controller_get_interface()->supports_ble()) {
    LOG_WARN("Controller does not support Le");
    return;
  }

  tBTM_SEC_DEV_REC* p_dev_rec = btm_find_dev(address);

  bluetooth::shim::ACL_IgnoreLeConnectionFrom(
      convert_to_address_with_type(address, p_dev_rec));
  return;
}

/** Clear the acceptlist, end any pending acceptlist connections */
void BTM_AcceptlistClear() {
  if (!controller_get_interface()->supports_ble()) {
    LOG_WARN("Controller does not support Le");
    return;
  }
  bluetooth::shim::ACL_IgnoreAllLeConnections();
}
