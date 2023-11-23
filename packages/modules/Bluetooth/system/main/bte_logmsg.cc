/******************************************************************************
 *
 *  Copyright 2001-2012 Broadcom Corporation
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

#define LOG_TAG "bt_bte"

#include <cstdarg>
#include <cstdint>

#include "internal_include/bt_trace.h"
#include "internal_include/stack_config.h"
#include "main/main_int.h"
#include "osi/include/log.h"

#ifndef BTE_LOG_BUF_SIZE
#define BTE_LOG_BUF_SIZE 256
#endif

#define BTE_LOG_MAX_SIZE (BTE_LOG_BUF_SIZE - 12)

#define MSG_BUFFER_OFFSET 0

/* LayerIDs for BTA, currently everything maps onto appl_trace_level */
static const char* const bt_layer_tags[] = {
    "bt_btif",
    "bt_usb",
    "bt_serial",
    "bt_socket",
    "bt_rs232",
    "bt_lc",
    "bt_lm",
    "bt_hci",
    "bt_l2cap",
    "bt_rfcomm",
    "bt_sdp",
    "bt_tcs",
    "bt_obex",
    "bt_btm",
    "bt_gap",
    "UNUSED",
    "UNUSED",
    "bt_icp",
    "bt_hsp2",
    "bt_spp",
    "bt_ctp",
    "bt_bpp",
    "bt_hcrp",
    "bt_ftp",
    "bt_opp",
    "bt_btu",
    "bt_gki_deprecated",
    "bt_bnep",
    "bt_pan",
    "bt_hfp",
    "bt_hid",
    "bt_bip",
    "bt_avp",
    "bt_a2d",
    "bt_sap",
    "bt_amp",
    "bt_mca_deprecated",
    "bt_att",
    "bt_smp",
    "bt_nfc",
    "bt_nci",
    "bt_idep",
    "bt_ndep",
    "bt_llcp",
    "bt_rw",
    "bt_ce",
    "bt_snep",
    "bt_ndef",
    "bt_nfa",
};

void LogMsg(uint32_t trace_set_mask, const char* fmt_str, ...) {
  char buffer[BTE_LOG_BUF_SIZE];
  int trace_layer = TRACE_GET_LAYER(trace_set_mask);
  if (trace_layer >= TRACE_LAYER_MAX_NUM) trace_layer = 0;

  va_list ap;
  va_start(ap, fmt_str);
  vsnprintf(&buffer[MSG_BUFFER_OFFSET], BTE_LOG_MAX_SIZE, fmt_str, ap);
  va_end(ap);

#undef LOG_TAG
#define LOG_TAG bt_layer_tags[trace_layer]

  switch (TRACE_GET_TYPE(trace_set_mask)) {
    case TRACE_TYPE_ERROR:
      LOG_ERROR("%s", buffer);
      break;
    case TRACE_TYPE_WARNING:
      LOG_WARN("%s", buffer);
      break;
    case TRACE_TYPE_API:
    case TRACE_TYPE_EVENT:
      LOG_INFO("%s", buffer);
      break;
    case TRACE_TYPE_DEBUG:
      LOG_INFO("%s", buffer);
      break;
    case TRACE_TYPE_INFO:
      LOG_INFO("%s", buffer);
      break;
    default:
      /* we should never get this */
      LOG_ERROR("!BAD TRACE TYPE! %s", buffer);
      CHECK(TRACE_GET_TYPE(trace_set_mask) == TRACE_TYPE_ERROR);
      break;
  }
#undef LOG_TAG
#define LOG_TAG "bt_bte"
}

static future_t* init(void) {
  const stack_config_t* stack_config = stack_config_get_interface();
  if (!stack_config->get_trace_config_enabled()) {
    LOG_INFO("using compile default trace settings");
    return NULL;
  }

  init_cpp_logging(stack_config->get_all());

  return NULL;
}

EXPORT_SYMBOL extern const module_t bte_logmsg_module = {
    .name = BTE_LOGMSG_MODULE,
    .init = init,
    .start_up = NULL,
    .shut_down = NULL,
    .clean_up = NULL,
    .dependencies = {STACK_CONFIG_MODULE, NULL}};
