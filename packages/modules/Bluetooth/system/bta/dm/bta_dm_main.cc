/******************************************************************************
 *
 *  Copyright 2003-2012 Broadcom Corporation
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
 *  This is the main implementation file for the BTA device manager.
 *
 ******************************************************************************/
#include <base/strings/stringprintf.h>
#include <stddef.h>

#include "bt_trace.h"
#include "bta/dm/bta_dm_int.h"
#include "gd/common/circular_buffer.h"
#include "gd/common/strings.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_types.h"

/*****************************************************************************
 * Constants and types
 ****************************************************************************/

namespace {
constexpr size_t kSearchStateHistorySize = 50;
constexpr char kTimeFormatString[] = "%Y-%m-%d %H:%M:%S";

constexpr unsigned MillisPerSecond = 1000;
std::string EpochMillisToString(long long time_ms) {
  time_t time_sec = time_ms / MillisPerSecond;
  struct tm tm;
  localtime_r(&time_sec, &tm);
  std::string s = bluetooth::common::StringFormatTime(kTimeFormatString, tm);
  return base::StringPrintf(
      "%s.%03u", s.c_str(),
      static_cast<unsigned int>(time_ms % MillisPerSecond));
}
}  // namespace

tBTA_DM_CB bta_dm_cb;
tBTA_DM_SEARCH_CB bta_dm_search_cb;
tBTA_DM_DI_CB bta_dm_di_cb;

struct tSEARCH_STATE_HISTORY {
  const tBTA_DM_STATE state;
  const tBTA_DM_EVT event;
  std::string ToString() const {
    return base::StringPrintf("state:%25s event:%s",
                              bta_dm_state_text(state).c_str(),
                              bta_dm_event_text(event).c_str());
  }
};
bluetooth::common::TimestampedCircularBuffer<tSEARCH_STATE_HISTORY>
    search_state_history_(kSearchStateHistorySize);

/*******************************************************************************
 *
 * Function         bta_dm_sm_search_disable
 *
 * Description     unregister BTA SEARCH DM
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_dm_search_sm_disable() { bta_sys_deregister(BTA_ID_DM_SEARCH); }

/*******************************************************************************
 *
 * Function         bta_dm_search_sm_execute
 *
 * Description      State machine event handling function for DM
 *
 *
 * Returns          void
 *
 ******************************************************************************/
bool bta_dm_search_sm_execute(BT_HDR_RIGID* p_msg) {
  const tBTA_DM_EVT event = static_cast<tBTA_DM_EVT>(p_msg->event);
  LOG_INFO("state:%s, event:%s[0x%x]",
           bta_dm_state_text(bta_dm_search_get_state()).c_str(),
           bta_dm_event_text(event).c_str(), event);
  search_state_history_.Push({
      .state = bta_dm_search_get_state(),
      .event = event,
  });
  tBTA_DM_MSG* message = (tBTA_DM_MSG*)p_msg;
  switch (bta_dm_search_get_state()) {
    case BTA_DM_SEARCH_IDLE:
      switch (p_msg->event) {
        case BTA_DM_API_SEARCH_EVT:
          bta_dm_search_set_state(BTA_DM_SEARCH_ACTIVE);
          bta_dm_search_start(message);
          break;
        case BTA_DM_API_DISCOVER_EVT:
          bta_dm_search_set_state(BTA_DM_DISCOVER_ACTIVE);
          bta_dm_discover(message);
          break;
        case BTA_DM_API_SEARCH_CANCEL_EVT:
          bta_dm_search_clear_queue();
          bta_dm_search_cancel_notify();
          break;
        case BTA_DM_SDP_RESULT_EVT:
          bta_dm_free_sdp_db();
          break;
        case BTA_DM_DISC_CLOSE_TOUT_EVT:
          bta_dm_close_gatt_conn(message);
          break;
        default:
          LOG_INFO("Received unexpected event %s[0x%x] in state %s",
                   bta_dm_event_text(event).c_str(), event,
                   bta_dm_state_text(bta_dm_search_get_state()).c_str());
      }
      break;
    case BTA_DM_SEARCH_ACTIVE:
      switch (p_msg->event) {
        case BTA_DM_REMT_NAME_EVT:
          bta_dm_rmt_name(message);
          break;
        case BTA_DM_SEARCH_CMPL_EVT:
          bta_dm_search_cmpl();
          break;
        case BTA_DM_DISCOVERY_RESULT_EVT:
          bta_dm_search_result(message);
          break;
        case BTA_DM_DISC_CLOSE_TOUT_EVT:
          bta_dm_close_gatt_conn(message);
          break;
        case BTA_DM_API_DISCOVER_EVT:
          bta_dm_queue_disc(message);
          break;
        case BTA_DM_API_SEARCH_CANCEL_EVT:
          bta_dm_search_clear_queue();
          bta_dm_search_set_state(BTA_DM_SEARCH_CANCELLING);
          bta_dm_search_cancel();
          break;
        default:
          LOG_INFO("Received unexpected event %s[0x%x] in state %s",
                   bta_dm_event_text(event).c_str(), event,
                   bta_dm_state_text(bta_dm_search_get_state()).c_str());
      }
      break;
    case BTA_DM_SEARCH_CANCELLING:
      switch (p_msg->event) {
        case BTA_DM_API_SEARCH_EVT:
          bta_dm_queue_search(message);
          break;
        case BTA_DM_API_DISCOVER_EVT:
          bta_dm_queue_disc(message);
          break;
        case BTA_DM_API_SEARCH_CANCEL_EVT:
          bta_dm_search_clear_queue();
          bta_dm_search_cancel_notify();
          break;
        case BTA_DM_SDP_RESULT_EVT:
        case BTA_DM_REMT_NAME_EVT:
        case BTA_DM_SEARCH_CMPL_EVT:
        case BTA_DM_DISCOVERY_RESULT_EVT:
          bta_dm_search_set_state(BTA_DM_SEARCH_IDLE);
          bta_dm_free_sdp_db();
          bta_dm_search_cancel_notify();
          bta_dm_execute_queued_request();
          break;
        case BTA_DM_DISC_CLOSE_TOUT_EVT:
          if (bluetooth::common::init_flags::
                  bta_dm_clear_conn_id_on_client_close_is_enabled()) {
            bta_dm_close_gatt_conn(message);
            break;
          }
          [[fallthrough]];
        default:
          LOG_INFO("Received unexpected event %s[0x%x] in state %s",
                   bta_dm_event_text(event).c_str(), event,
                   bta_dm_state_text(bta_dm_search_get_state()).c_str());
      }
      break;
    case BTA_DM_DISCOVER_ACTIVE:
      switch (p_msg->event) {
        case BTA_DM_REMT_NAME_EVT:
          bta_dm_disc_rmt_name(message);
          break;
        case BTA_DM_SDP_RESULT_EVT:
          bta_dm_sdp_result(message);
          break;
        case BTA_DM_SEARCH_CMPL_EVT:
          bta_dm_search_cmpl();
          break;
        case BTA_DM_DISCOVERY_RESULT_EVT:
          bta_dm_disc_result(message);
          break;
        case BTA_DM_API_SEARCH_EVT:
          bta_dm_queue_search(message);
          break;
        case BTA_DM_API_DISCOVER_EVT:
          bta_dm_queue_disc(message);
          break;
        case BTA_DM_API_SEARCH_CANCEL_EVT:
          bta_dm_search_clear_queue();
          bta_dm_search_set_state(BTA_DM_SEARCH_CANCELLING);
          bta_dm_search_cancel_notify();
          break;
        case BTA_DM_DISC_CLOSE_TOUT_EVT:
          if (bluetooth::common::init_flags::
                  bta_dm_clear_conn_id_on_client_close_is_enabled()) {
            bta_dm_close_gatt_conn(message);
            break;
          }
          [[fallthrough]];
        default:
          LOG_INFO("Received unexpected event %s[0x%x] in state %s",
                   bta_dm_event_text(event).c_str(), event,
                   bta_dm_state_text(bta_dm_search_get_state()).c_str());
      }
      break;
  }
  return true;
}

#define DUMPSYS_TAG "shim::legacy::bta::dm"
void DumpsysBtaDm(int fd) {
  LOG_DUMPSYS_TITLE(fd, DUMPSYS_TAG);
  auto copy = search_state_history_.Pull();
  LOG_DUMPSYS(fd, " last %zu search state transitions", copy.size());
  for (const auto& it : copy) {
    LOG_DUMPSYS(fd, "   %s %s", EpochMillisToString(it.timestamp).c_str(),
                it.entry.ToString().c_str());
  }
  LOG_DUMPSYS(fd, " current bta_dm_search_state:%s",
              bta_dm_state_text(bta_dm_search_get_state()).c_str());
}
#undef DUMPSYS_TAG
