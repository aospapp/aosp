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

#pragma once

#include <cstdint>
#include <string>

#include "btm_sco_hfp_hal.h"
#include "device/include/esco_parameters.h"
#include "stack/include/btm_api_types.h"

#define BTM_MSBC_CODE_SIZE 240

constexpr uint16_t kMaxScoLinks = static_cast<uint16_t>(BTM_MAX_SCO_LINKS);

/* SCO-over-HCI audio related definitions */
namespace bluetooth::audio::sco {

/* Initialize SCO-over-HCI socket (UIPC); the client is audio server */
void init();

/* Open the socket when there is SCO connection open */
void open();

/* Clean up the socket when the SCO connection is done */
void cleanup();

/* Read PCM data from the socket (audio server) for SCO Tx */
size_t read(uint8_t* p_buf, uint32_t len);

/* Write PCM data to the socket from SCO Rx */
size_t write(const uint8_t* buf, uint32_t len);
}  // namespace bluetooth::audio::sco

/* SCO-over-HCI audio HFP WBS related definitions */
namespace bluetooth::audio::sco::wbs {

/* Initialize struct used for storing WBS related information.
 * Args:
 *    pkt_size - Length of the SCO packet. It is determined based on the BT-USB
 *    adapter's capability and alt mode setting. The value should be queried
 *    from HAL interface. It will be used to determine the size of the SCO
 *    packet buffer. Currently, the stack only supports 60 and 72.
 * Returns:
 *    The selected packet size. Will fallback to the typical mSBC packet
 *    length(60) if the pkt_size argument is not supported.
 */
size_t init(size_t pkt_size);

/* Clean up when the SCO connection is done */
void cleanup();

/* Fill in packet loss stats
 * Args:
 *    num_decoded_frames - Output argument for the number of decode frames
 *    packet_loss_ratio - Output argument for the ratio of lost frames
 * Returns:
 *    False for invalid arguments or unreasonable stats. True otherwise.
 */
bool fill_plc_stats(int* num_decoded_frames, double* packet_loss_ratio);

/* Try to enqueue a packet to a buffer.
 * Args:
 *    data - Pointer to received packet data bytes.
 *    pkt_size - Length of input packet. Passing packet with inconsistent size
 *        from the pkt_size set in init() will fail the call.
 *    corrupted - If the current mSBC packet read is corrupted.
 * Returns:
 *    The length of enqueued bytes. 0 if failed.
 */
size_t enqueue_packet(const uint8_t* data, size_t pkt_size, bool corrupted);

/* Try to decode mSBC frames from the packets in the buffer.
 * Args:
 *    output - Pointer to the decoded PCM bytes caller can read from.
 * Returns:
 *    The length of decoded bytes. 0 if failed.
 */
size_t decode(const uint8_t** output);

/* Try to encode PCM data into one SCO packet and put the packets in the buffer.
 * Args:
 *    data - Pointer to the input PCM bytes for the encoder to encode.
 *    len - Length of the input data.
 * Returns:
 *    The length of input data that is encoded. 0 if failed.
 */
size_t encode(int16_t* data, size_t len);

/* Dequeue a SCO packet with encoded mSBC data if possible. The length of the
 * packet is determined by the pkt_size set by the init().
 * Args:
 *    output - Pointer to output mSBC packets encoded by the encoder.
 * Returns:
 *    The length of dequeued packet. 0 if failed.
 */
size_t dequeue_packet(const uint8_t** output);

}  // namespace bluetooth::audio::sco::wbs

#ifndef CASE_RETURN_TEXT
#define CASE_RETURN_TEXT(code) \
  case code:                   \
    return #code
#endif

/* Define the structures needed by sco */
typedef enum : uint16_t {
  SCO_ST_UNUSED = 0,
  SCO_ST_LISTENING = 1,
  SCO_ST_W4_CONN_RSP = 2,
  SCO_ST_CONNECTING = 3,
  SCO_ST_CONNECTED = 4,
  SCO_ST_DISCONNECTING = 5,
  SCO_ST_PEND_UNPARK = 6,
  SCO_ST_PEND_ROLECHANGE = 7,
  SCO_ST_PEND_MODECHANGE = 8,
} tSCO_STATE;

inline std::string sco_state_text(const tSCO_STATE& state) {
  switch (state) {
    CASE_RETURN_TEXT(SCO_ST_UNUSED);
    CASE_RETURN_TEXT(SCO_ST_LISTENING);
    CASE_RETURN_TEXT(SCO_ST_W4_CONN_RSP);
    CASE_RETURN_TEXT(SCO_ST_CONNECTING);
    CASE_RETURN_TEXT(SCO_ST_CONNECTED);
    CASE_RETURN_TEXT(SCO_ST_DISCONNECTING);
    CASE_RETURN_TEXT(SCO_ST_PEND_UNPARK);
    CASE_RETURN_TEXT(SCO_ST_PEND_ROLECHANGE);
    CASE_RETURN_TEXT(SCO_ST_PEND_MODECHANGE);
    default:
      return std::string("unknown_sco_state: ") +
       std::to_string(static_cast<uint16_t>(state));
  }
}

#undef CASE_RETURN_TEXT

/* Define the structure that contains (e)SCO data */
typedef struct {
  tBTM_ESCO_CBACK* p_esco_cback; /* Callback for eSCO events     */
  enh_esco_params_t setup;
  tBTM_ESCO_DATA data; /* Connection complete information */
  uint8_t hci_status;
} tBTM_ESCO_INFO;

/* Define the structure used for SCO Management */
typedef struct {
  tBTM_ESCO_INFO esco;    /* Current settings             */
  tBTM_SCO_CB* p_conn_cb; /* Callback for when connected  */
  tBTM_SCO_CB* p_disc_cb; /* Callback for when disconnect */
  tSCO_STATE state;       /* The state of the SCO link    */

  uint16_t hci_handle;    /* HCI Handle                   */
 public:
  bool is_active() const { return state != SCO_ST_UNUSED; }
  bool is_inband() const {
    return esco.setup.input_data_path == ESCO_DATA_PATH_HCI;
  }
  bool is_wbs() const {
    return esco.setup.transmit_coding_format.coding_format ==
               ESCO_CODING_FORMAT_TRANSPNT ||
           esco.setup.transmit_coding_format.coding_format ==
               ESCO_CODING_FORMAT_MSBC;
  }
  uint16_t Handle() const { return hci_handle; }

  bool is_orig;           /* true if the originator       */
  bool rem_bd_known;      /* true if remote BD addr known */

} tSCO_CONN;

/* SCO Management control block */
typedef struct {
  tSCO_CONN sco_db[BTM_MAX_SCO_LINKS];
  enh_esco_params_t def_esco_parms;
  bool esco_supported;        /* true if 1.2 cntlr AND supports eSCO links */

  tSCO_CONN* get_sco_connection_from_index(uint16_t index) {
    return (index < kMaxScoLinks) ? (&sco_db[index]) : nullptr;
  }

  tSCO_CONN* get_sco_connection_from_handle(uint16_t handle) {
    tSCO_CONN* p_sco = sco_db;
    for (uint16_t xx = 0; xx < kMaxScoLinks; xx++, p_sco++) {
      if (p_sco->hci_handle == handle) {
        return p_sco;
      }
    }
    return nullptr;
  }

  void Init() {
    hfp_hal_interface::init();
    def_esco_parms = esco_parameters_for_codec(
        ESCO_CODEC_CVSD_S3, hfp_hal_interface::get_offload_enabled());
  }

  void Free() { bluetooth::audio::sco::cleanup(); }

  uint16_t get_index(const tSCO_CONN* p_sco) const {
    CHECK(p_sco != nullptr);
    const tSCO_CONN* p = sco_db;
    for (uint16_t xx = 0; xx < kMaxScoLinks; xx++, p++) {
      if (p_sco == p) {
        return xx;
      }
    }
    return 0xffff;
  }

} tSCO_CB;

void btm_sco_chk_pend_rolechange(uint16_t hci_handle);
void btm_sco_disc_chk_pend_for_modechange(uint16_t hci_handle);

/* Visible for test only */
BT_HDR* btm_sco_make_packet(std::vector<uint8_t> data, uint16_t sco_handle);

/* Send a SCO packet */
void btm_send_sco_packet(std::vector<uint8_t> data);
