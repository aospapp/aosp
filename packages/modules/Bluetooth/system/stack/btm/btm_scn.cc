/*
 * Copyright 2020 The Android Open Source Project
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

#define LOG_TAG "btm"

#include <cstdint>
#include "stack/btm/btm_int_types.h"  // tBTM_CB
#include "stack/include/rfcdefs.h"    // PORT_MAX_RFC_PORTS

extern tBTM_CB btm_cb;

/*******************************************************************************
 *
 * Function         BTM_AllocateSCN
 *
 * Description      Look through the Server Channel Numbers for a free one.
 *
 * Returns          Allocated SCN number or 0 if none.
 *
 ******************************************************************************/
uint8_t BTM_AllocateSCN(void) {
  BTM_TRACE_DEBUG("BTM_AllocateSCN");

  // stack reserves scn 1 for HFP, HSP we still do the correct way
  for (uint8_t x = btm_cb.btm_available_index; x < PORT_MAX_RFC_PORTS; x++) {
    if (!btm_cb.btm_scn[x]) {
      btm_cb.btm_scn[x] = true;
      btm_cb.btm_available_index = (x + 1);
      return (x + 1);
    }
  }

  // In order to avoid OOB, btm_available_index must be less than or equal to
  // PORT_MAX_RFC_PORTS
  btm_cb.btm_available_index =
      std::min(btm_cb.btm_available_index, (uint8_t)PORT_MAX_RFC_PORTS);

  // If there's no empty SCN from _last_index to BTM_MAX_SCN.
  for (uint8_t y = 1; y < btm_cb.btm_available_index; y++) {
    if (!btm_cb.btm_scn[y]) {
      btm_cb.btm_scn[y] = true;
      btm_cb.btm_available_index = (y + 1);
      return (y + 1);
    }
  }

  return (0); /* No free ports */
}

/*******************************************************************************
 *
 * Function         BTM_TryAllocateSCN
 *
 * Description      Try to allocate a fixed server channel
 *
 * Returns          Returns true if server channel was available
 *
 ******************************************************************************/

bool BTM_TryAllocateSCN(uint8_t scn) {
  /* Make sure we don't exceed max port range.
   * Stack reserves scn 1 for HFP, HSP we still do the correct way.
   */
  if ((scn >= PORT_MAX_RFC_PORTS) || (scn == 1) || (scn == 0)) return false;

  /* check if this port is available */
  if (!btm_cb.btm_scn[scn - 1]) {
    btm_cb.btm_scn[scn - 1] = true;
    return true;
  }

  return (false); /* Port was busy */
}

/*******************************************************************************
 *
 * Function         BTM_FreeSCN
 *
 * Description      Free the specified SCN.
 *
 * Returns          true or false
 *
 ******************************************************************************/
bool BTM_FreeSCN(uint8_t scn) {
  BTM_TRACE_DEBUG("BTM_FreeSCN ");
  if (scn <= PORT_MAX_RFC_PORTS && scn > 0) {
    btm_cb.btm_scn[scn - 1] = false;
    return (true);
  } else {
    return (false); /* Illegal SCN passed in */
  }
}
