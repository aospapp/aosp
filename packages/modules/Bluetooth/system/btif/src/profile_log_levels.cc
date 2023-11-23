/*
 * Copyright 2022 The Android Open Source Project
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

#define LOG_TAG "bt_bte"

#include <base/logging.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <time.h>

#include "a2dp_api.h"
#include "avdt_api.h"
#include "avrc_api.h"
#include "bta_api.h"
#include "btm_api.h"
#include "btu.h"
#include "l2c_api.h"
#include "osi/include/config.h"
#include "osi/include/log.h"
#include "port_api.h"
#include "sdp_api.h"
#include "stack_config.h"
#if (BNEP_INCLUDED == TRUE)
#include "bnep_api.h"
#endif
#if (PAN_INCLUDED == TRUE)
#include "pan_api.h"
#endif
#if (HID_HOST_INCLUDED == TRUE)
#include "hidh_api.h"
#endif
#if (HID_DEV_INCLUDED == TRUE)
#include "hidd_api.h"
#endif

#include "gd/common/init_flags.h"
#include "smp_api.h"

#ifndef DEFAULT_CONF_TRACE_LEVEL
#define DEFAULT_CONF_TRACE_LEVEL BT_TRACE_LEVEL_WARNING
#endif

static uint8_t BTAPP_SetTraceLevel(uint8_t new_level);
static uint8_t BTIF_SetTraceLevel(uint8_t new_level);
static uint8_t BTU_SetTraceLevel(uint8_t new_level);

/* make sure list is order by increasing layer id!!! */
static tBTTRC_FUNC_MAP bttrc_set_level_map[] = {
    {BTTRC_ID_STK_BTU, BTTRC_ID_STK_HCI, BTU_SetTraceLevel, "TRC_HCI",
     DEFAULT_CONF_TRACE_LEVEL},
    {BTTRC_ID_STK_L2CAP, BTTRC_ID_STK_L2CAP, L2CA_SetTraceLevel, "TRC_L2CAP",
     DEFAULT_CONF_TRACE_LEVEL},
    {BTTRC_ID_STK_RFCOMM, BTTRC_ID_STK_RFCOMM_DATA, PORT_SetTraceLevel,
     "TRC_RFCOMM", DEFAULT_CONF_TRACE_LEVEL},
    {BTTRC_ID_STK_AVCT, BTTRC_ID_STK_AVCT, AVCT_SetTraceLevel, "TRC_AVCT",
     DEFAULT_CONF_TRACE_LEVEL},
    {BTTRC_ID_STK_AVDT, BTTRC_ID_STK_AVDT, AVDT_SetTraceLevel, "TRC_AVDT",
     DEFAULT_CONF_TRACE_LEVEL},
    {BTTRC_ID_STK_AVRC, BTTRC_ID_STK_AVRC, AVRC_SetTraceLevel, "TRC_AVRC",
     DEFAULT_CONF_TRACE_LEVEL},
    {BTTRC_ID_STK_A2DP, BTTRC_ID_STK_A2DP, A2DP_SetTraceLevel, "TRC_A2D",
     DEFAULT_CONF_TRACE_LEVEL},
#if (BNEP_INCLUDED == TRUE)
    {BTTRC_ID_STK_BNEP, BTTRC_ID_STK_BNEP, BNEP_SetTraceLevel, "TRC_BNEP",
     DEFAULT_CONF_TRACE_LEVEL},
#endif
    {BTTRC_ID_STK_BTM_ACL, BTTRC_ID_STK_BTM_SEC, BTM_SetTraceLevel, "TRC_BTM",
     DEFAULT_CONF_TRACE_LEVEL},
#if (HID_HOST_INCLUDED == TRUE)
    {BTTRC_ID_STK_HID, BTTRC_ID_STK_HID, HID_HostSetTraceLevel, "TRC_HID_HOST",
     DEFAULT_CONF_TRACE_LEVEL},
#endif
#if (PAN_INCLUDED == TRUE)
    {BTTRC_ID_STK_PAN, BTTRC_ID_STK_PAN, PAN_SetTraceLevel, "TRC_PAN",
     DEFAULT_CONF_TRACE_LEVEL},
#endif
    {BTTRC_ID_STK_SDP, BTTRC_ID_STK_SDP, SDP_SetTraceLevel, "TRC_SDP",
     DEFAULT_CONF_TRACE_LEVEL},
    {BTTRC_ID_STK_SMP, BTTRC_ID_STK_SMP, SMP_SetTraceLevel, "TRC_SMP",
     DEFAULT_CONF_TRACE_LEVEL},
#if (HID_DEV_INCLUDED == TRUE)
    {BTTRC_ID_STK_HIDD, BTTRC_ID_STK_HIDD, HID_DevSetTraceLevel, "TRC_HID_DEV",
     DEFAULT_CONF_TRACE_LEVEL},
#endif

    /* LayerIDs for BTA, currently everything maps onto appl_trace_level.
     */
    {BTTRC_ID_BTA_ACC, BTTRC_ID_BTAPP, BTAPP_SetTraceLevel, "TRC_BTAPP",
     DEFAULT_CONF_TRACE_LEVEL},
    {BTTRC_ID_BTA_ACC, BTTRC_ID_BTAPP, BTIF_SetTraceLevel, "TRC_BTIF",
     DEFAULT_CONF_TRACE_LEVEL},

    {0, 0, NULL, NULL, DEFAULT_CONF_TRACE_LEVEL}};

/* this function should go into BTAPP_DM for example */
static uint8_t BTAPP_SetTraceLevel(uint8_t new_level) {
  if (new_level != 0xFF) appl_trace_level = new_level;

  return appl_trace_level;
}

static uint8_t BTIF_SetTraceLevel(uint8_t new_level) {
  if (new_level != 0xFF) btif_trace_level = new_level;

  return btif_trace_level;
}

static uint8_t BTU_SetTraceLevel(uint8_t new_level) {
  if (new_level != 0xFF) btu_trace_level = new_level;

  return btu_trace_level;
}

void load_levels_from_config(const config_t* config) {
  CHECK(config != NULL);

  for (tBTTRC_FUNC_MAP* functions = &bttrc_set_level_map[0];
       functions->trc_name; ++functions) {
    int value = config_get_int(*config, CONFIG_DEFAULT_SECTION,
                               functions->trc_name, -1);
    if (value != -1) {
      functions->trace_level = value;
    }
    if (bluetooth::common::InitFlags::GetDefaultLogLevel() >= LOG_TAG_VERBOSE) {
      LOG_INFO("Enable logging for %s because all debug logs are enabled",
               functions->trc_name);
      functions->trace_level = BT_TRACE_LEVEL_VERBOSE;
    }
    LOG_INFO("BTE_InitTraceLevels -- %s : Level %d", functions->trc_name,
             functions->trace_level);
    if (functions->p_f) functions->p_f(functions->trace_level);
  }
}
