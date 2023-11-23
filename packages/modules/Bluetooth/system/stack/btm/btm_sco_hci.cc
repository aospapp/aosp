/*
 * Copyright 2021 The Android Open Source Project
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

#include <errno.h>
#include <grp.h>
#include <math.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cfloat>
#include <memory>

// Define before including log.h
#define LOG_TAG "sco_hci"

#include "btif/include/core_callbacks.h"
#include "btif/include/stack_manager.h"
#include "osi/include/allocator.h"
#include "osi/include/log.h"
#include "stack/btm/btm_sco.h"
#include "udrv/include/uipc.h"

#define SCO_DATA_READ_POLL_MS 10
#define SCO_HOST_DATA_PATH "/var/run/bluetooth/audio/.sco_data"
// TODO(b/198260375): Make SCO data owner group configurable.
#define SCO_HOST_DATA_GROUP "bluetooth-audio"

/* Per Bluetooth Core v5.0 and HFP 1.7 specification. */
#define BTM_MSBC_H2_HEADER_0 0x01
#define BTM_MSBC_H2_HEADER_LEN 2
#define BTM_MSBC_PKT_LEN 60
#define BTM_MSBC_PKT_FRAME_LEN 57 /* Packet length without the header */
#define BTM_MSBC_SYNC_WORD 0xAD

/* Used by PLC */
#define BTM_MSBC_SAMPLE_SIZE 2 /* 2 bytes*/
#define BTM_MSBC_FS 120        /* Frame Size */

#define BTM_PLC_WL 256 /* 16ms - Window Length for pattern matching */
#define BTM_PLC_TL 64  /* 4ms - Template Length for matching */
#define BTM_PLC_HL \
  (BTM_PLC_WL + BTM_MSBC_FS - 1) /* Length of History buffer required */
#define BTM_PLC_SBCRL 36         /* SBC Reconvergence sample Length */
#define BTM_PLC_OLAL 16          /* OverLap-Add Length */

/* Disable the PLC when there are more than threshold of lost packets in the
 * window */
#define BTM_PLC_WINDOW_SIZE 5
#define BTM_PLC_PL_THRESHOLD 1

namespace {

std::unique_ptr<tUIPC_STATE> sco_uipc = nullptr;

void sco_data_cb(tUIPC_CH_ID, tUIPC_EVENT event) {
  switch (event) {
    case UIPC_OPEN_EVT:
      /*
       * Read directly from media task from here on (keep callback for
       * connection events.
       */
      UIPC_Ioctl(*sco_uipc, UIPC_CH_ID_AV_AUDIO, UIPC_REG_REMOVE_ACTIVE_READSET,
                 NULL);
      UIPC_Ioctl(*sco_uipc, UIPC_CH_ID_AV_AUDIO, UIPC_SET_READ_POLL_TMO,
                 reinterpret_cast<void*>(SCO_DATA_READ_POLL_MS));
      break;
    default:
      break;
  }
}

}  // namespace

namespace bluetooth {
namespace audio {
namespace sco {

void open() {
  if (sco_uipc != nullptr) {
    LOG_WARN("Re-opening UIPC that is already running");
  }

  sco_uipc = UIPC_Init();
  if (sco_uipc == nullptr) {
    LOG_ERROR("%s failed to init UIPC", __func__);
    return;
  }

  UIPC_Open(*sco_uipc, UIPC_CH_ID_AV_AUDIO, sco_data_cb, SCO_HOST_DATA_PATH);
  struct group* grp = getgrnam(SCO_HOST_DATA_GROUP);
  chmod(SCO_HOST_DATA_PATH, 0770);
  if (grp) {
    int res = chown(SCO_HOST_DATA_PATH, -1, grp->gr_gid);
    if (res == -1) {
      LOG_ERROR("%s failed: %s", __func__, strerror(errno));
    }
  }
}

void cleanup() {
  if (sco_uipc == nullptr) {
    return;
  }
  UIPC_Close(*sco_uipc, UIPC_CH_ID_ALL);
  sco_uipc = nullptr;
}

size_t read(uint8_t* p_buf, uint32_t len) {
  if (sco_uipc == nullptr) {
    LOG_WARN("Read from uninitialized or closed UIPC");
    return 0;
  }
  return UIPC_Read(*sco_uipc, UIPC_CH_ID_AV_AUDIO, p_buf, len);
}

size_t write(const uint8_t* p_buf, uint32_t len) {
  if (sco_uipc == nullptr) {
    LOG_WARN("Write to uninitialized or closed UIPC");
    return 0;
  }
  return UIPC_Send(*sco_uipc, UIPC_CH_ID_AV_AUDIO, 0, p_buf, len) ? len : 0;
}

namespace wbs {

/* Second octet of H2 header is composed by 4 bits fixed 0x8 and 4 bits
 * sequence number 0000, 0011, 1100, 1111. */
static const uint8_t btm_h2_header_frames_count[] = {0x08, 0x38, 0xc8, 0xf8};

/* Supported SCO packet sizes for mSBC. The wideband speech mSBC frame parsing
 * code ties to limited packet size values. Specifically list them out
 * to check against when setting packet size. The first entry is the default
 * value as a fallback. */
constexpr size_t btm_wbs_supported_pkt_size[] = {BTM_MSBC_PKT_LEN, 72, 0};
/* Buffer size should be set to least common multiple of SCO packet size and
 * BTM_MSBC_PKT_LEN for optimizing buffer copy. */
constexpr size_t btm_wbs_msbc_buffer_size[] = {BTM_MSBC_PKT_LEN, 360, 0};

/* The pre-computed SCO packet per HFP 1.7 spec. This mSBC packet will be
 * decoded into all-zero input PCM. */
static const uint8_t btm_msbc_zero_packet[] = {
    0x01, 0x08, /* Mock H2 header */
    0xad, 0x00, 0x00, 0xc5, 0x00, 0x00, 0x00, 0x00, 0x77, 0x6d, 0xb6, 0xdd,
    0xdb, 0x6d, 0xb7, 0x76, 0xdb, 0x6d, 0xdd, 0xb6, 0xdb, 0x77, 0x6d, 0xb6,
    0xdd, 0xdb, 0x6d, 0xb7, 0x76, 0xdb, 0x6d, 0xdd, 0xb6, 0xdb, 0x77, 0x6d,
    0xb6, 0xdd, 0xdb, 0x6d, 0xb7, 0x76, 0xdb, 0x6d, 0xdd, 0xb6, 0xdb, 0x77,
    0x6d, 0xb6, 0xdd, 0xdb, 0x6d, 0xb7, 0x76, 0xdb, 0x6c,
    /* End of Audio Samples */
    0x00 /* A padding byte defined by mSBC */};

/* Raised Cosine table for OLA */
static const float rcos[BTM_PLC_OLAL] = {
    0.99148655f, 0.96623611f, 0.92510857f, 0.86950446f,
    0.80131732f, 0.72286918f, 0.63683150f, 0.54613418f,
    0.45386582f, 0.36316850f, 0.27713082f, 0.19868268f,
    0.13049554f, 0.07489143f, 0.03376389f, 0.00851345f};

static int16_t f_to_s16(float input) {
  return input > INT16_MAX   ? INT16_MAX
         : input < INT16_MIN ? INT16_MIN
                             : (int16_t)input;
}
/* This structure tracks the packet loss for last PLC_WINDOW_SIZE of packets */
struct tBTM_MSBC_BTM_PLC_WINDOW {
  bool loss_hist[BTM_PLC_WINDOW_SIZE]; /* The packet loss history of receiving
                                      packets.*/
  unsigned int idx;   /* The index of the to be updated packet loss status. */
  unsigned int count; /* The count of lost packets in the window. */

 public:
  void update_plc_state(bool is_packet_loss) {
    bool* curr = &loss_hist[idx];
    if (is_packet_loss != *curr) {
      count += (is_packet_loss - *curr);
      *curr = is_packet_loss;
    }
    idx = (idx + 1) % BTM_PLC_WINDOW_SIZE;
  }

  bool is_packet_loss_too_high() {
    /* The packet loss count comes from a time window and we use it as an
     * indicator of our confidence of the PLC algorithm. It is known to
     * generate poorer and robotic feeling sounds, when the majority of
     * samples in the PLC history buffer are from the concealment results.
     */
    return count > BTM_PLC_PL_THRESHOLD;
  }
};

/* The PLC is specifically designed for mSBC. The algorithm searches the
 * history of receiving samples to find the best match samples and constructs
 * substitutions for the lost samples. The selection is based on pattern
 * matching a template, composed of a length of samples preceding to the lost
 * samples. It then uses the following samples after the best match as the
 * replacement samples and applies Overlap-Add to reduce the audible
 * distortion.
 *
 * This structure holds related info needed to conduct the PLC algorithm.
 */
struct tBTM_MSBC_PLC {
  int16_t hist[BTM_PLC_HL + BTM_MSBC_FS + BTM_PLC_SBCRL +
               BTM_PLC_OLAL]; /* The history buffer for receiving samples, we
                                 also use it to buffer the processed
                                 replacement samples */
  unsigned best_lag;      /* The index of the best substitution samples in the
                             sample history */
  int handled_bad_frames; /* Number of bad frames handled since the last good
                             frame */
  int16_t decoded_buffer[BTM_MSBC_FS]; /* Used for storing the samples from
                                      decoding the mSBC zero frame packet and
                                      also constructed frames */
  tBTM_MSBC_BTM_PLC_WINDOW*
      pl_window; /* Used to monitor how many packets are bad within the recent
                    BTM_PLC_WINDOW_SIZE of packets. We use this to determine if
                    we want to disable the PLC temporarily */

  int num_decoded_frames; /* Number of total read mSBC frames. */
  int num_lost_frames;    /* Number of total lost mSBC frames. */

  void overlap_add(int16_t* output, float scaler_d, const int16_t* desc,
                   float scaler_a, const int16_t* asc) {
    for (int i = 0; i < BTM_PLC_OLAL; i++) {
      output[i] = f_to_s16(scaler_d * desc[i] * rcos[i] +
                           scaler_a * asc[i] * rcos[BTM_PLC_OLAL - 1 - i]);
    }
  }

  float cross_correlation(int16_t* x, int16_t* y) {
    float sum = 0, x2 = 0, y2 = 0;

    for (int i = 0; i < BTM_PLC_TL; i++) {
      sum += ((float)x[i]) * y[i];
      x2 += ((float)x[i]) * x[i];
      y2 += ((float)y[i]) * y[i];
    }
    return sum / sqrtf(x2 * y2);
  }

  int pattern_match(int16_t* hist) {
    int best = 0;
    float cn, max_cn = FLT_MIN;

    for (int i = 0; i < BTM_PLC_WL; i++) {
      cn = cross_correlation(&hist[BTM_PLC_HL - BTM_PLC_TL], &hist[i]);
      if (cn > max_cn) {
        best = i;
        max_cn = cn;
      }
    }
    return best;
  }

  float amplitude_match(int16_t* x, int16_t* y) {
    uint32_t sum_x = 0, sum_y = 0;
    float scaler;
    for (int i = 0; i < BTM_MSBC_FS; i++) {
      sum_x += abs(x[i]);
      sum_y += abs(y[i]);
    }

    if (sum_y == 0) return 1.2f;

    scaler = (float)sum_x / sum_y;
    return scaler > 1.2f ? 1.2f : scaler < 0.75f ? 0.75f : scaler;
  }

 public:
  void init() {
    if (pl_window) osi_free(pl_window);
    pl_window = (tBTM_MSBC_BTM_PLC_WINDOW*)osi_calloc(sizeof(*pl_window));
  }

  void deinit() {
    if (pl_window) osi_free_and_reset((void**)&pl_window);
  }

  int get_num_decoded_frames() { return num_decoded_frames; }

  int get_num_lost_frames() { return num_lost_frames; }

  void handle_bad_frames(const uint8_t** output) {
    float scaler;
    int16_t* best_match_hist;
    int16_t* frame_head = &hist[BTM_PLC_HL];

    num_decoded_frames++;
    num_lost_frames++;

    /* mSBC codec is stateful, the history of signal would contribute to the
     * decode result decoded_buffer. This should never fail. */
    GetInterfaceToProfiles()->msbcCodec->decodePacket(
        btm_msbc_zero_packet, decoded_buffer, sizeof(decoded_buffer));

    /* The PLC algorithm is more likely to generate bad results that sound
     * robotic after severe packet losses happened. Only applying it when
     * we are confident. */
    if (!pl_window->is_packet_loss_too_high()) {
      if (handled_bad_frames == 0) {
        /* Finds the best matching samples and amplitude */
        best_lag = pattern_match(hist) + BTM_PLC_TL;
        best_match_hist = &hist[best_lag];
        scaler =
            amplitude_match(&hist[BTM_PLC_HL - BTM_MSBC_FS], best_match_hist);

        /* Constructs the substitution samples */
        overlap_add(frame_head, 1.0, decoded_buffer, scaler, best_match_hist);
        for (int i = BTM_PLC_OLAL; i < BTM_MSBC_FS; i++)
          hist[BTM_PLC_HL + i] = f_to_s16(scaler * best_match_hist[i]);
        overlap_add(&frame_head[BTM_MSBC_FS], scaler,
                    &best_match_hist[BTM_MSBC_FS], 1.0,
                    &best_match_hist[BTM_MSBC_FS]);

        memmove(&frame_head[BTM_MSBC_FS + BTM_PLC_OLAL],
                &best_match_hist[BTM_MSBC_FS + BTM_PLC_OLAL],
                BTM_PLC_SBCRL * BTM_MSBC_SAMPLE_SIZE);
      } else {
        /* Using the existing best lag and copy the following frames */
        memmove(frame_head, &hist[best_lag],
                (BTM_MSBC_FS + BTM_PLC_SBCRL + BTM_PLC_OLAL) *
                    BTM_MSBC_SAMPLE_SIZE);
      }
      /* Copy the constructed frames to decoded buffer for caller to use */
      std::copy(frame_head, &frame_head[BTM_MSBC_FS], decoded_buffer);

      handled_bad_frames++;
    } else {
      /* This is a case similar to receiving a good frame with all zeros, we set
       * handled_bad_frames to zero to prevent the following good frame from
       * being concealed to reconverge with the zero frames we fill in. The
       * concealment result sounds more artificial and weird than simply writing
       * zeros and following samples.
       */
      std::copy(std::begin(decoded_buffer), std::end(decoded_buffer),
                frame_head);
      std::fill(&frame_head[BTM_MSBC_FS],
                &frame_head[BTM_MSBC_FS + BTM_PLC_SBCRL + BTM_PLC_OLAL], 0);
      /* No need to copy the frames as we'll use the decoded zero frames in the
       * decoded buffer as our concealment frames */

      handled_bad_frames = 0;
    }

    *output = (const uint8_t*)decoded_buffer;

    /* Shift the frames to update the history window */
    memmove(hist, &hist[BTM_MSBC_FS],
            (BTM_PLC_HL + BTM_PLC_SBCRL + BTM_PLC_OLAL) * BTM_MSBC_SAMPLE_SIZE);
    pl_window->update_plc_state(1);
  }

  void handle_good_frames(int16_t* input) {
    int16_t* frame_head;
    num_decoded_frames++;
    if (handled_bad_frames != 0) {
      /* If there was a packet concealment before this good frame, we need to
       * reconverge the input frames */
      frame_head = &hist[BTM_PLC_HL];

      /* For the first good frame after packet loss, we need to conceal the
       * received samples to have it reconverge with the true output */
      std::copy(frame_head, &frame_head[BTM_PLC_SBCRL], input);
      /* Overlap the input frame with the previous output frame */
      overlap_add(&input[BTM_PLC_SBCRL], 1.0, &frame_head[BTM_PLC_SBCRL], 1.0,
                  &input[BTM_PLC_SBCRL]);
      handled_bad_frames = 0;
    }

    /* Shift the history and update the good frame to the end of it */
    memmove(hist, &hist[BTM_MSBC_FS],
            (BTM_PLC_HL - BTM_MSBC_FS) * BTM_MSBC_SAMPLE_SIZE);
    std::copy(input, &input[BTM_MSBC_FS], &hist[BTM_PLC_HL - BTM_MSBC_FS]);
    pl_window->update_plc_state(0);
  }
};

/* Define the structure that contains mSBC data */
struct tBTM_MSBC_INFO {
  size_t packet_size; /* SCO mSBC packet size supported by lower layer */
  size_t buf_size; /* The size of the buffer, determined by the packet_size. */

  uint8_t* msbc_decode_buf; /* Buffer to store mSBC packets to decode */
  size_t decode_buf_wo;     /* Write offset of the decode buffer */
  size_t decode_buf_ro;     /* Read offset of the decode buffer */
  bool read_corrupted;      /* If the current mSBC packet read is corrupted */

  uint8_t* msbc_encode_buf; /* Buffer to store the encoded SCO packets */
  size_t encode_buf_wo;     /* Write offset of the encode buffer */
  size_t encode_buf_ro;     /* Read offset of the encode buffer */

  int16_t decoded_pcm_buf[BTM_MSBC_FS]; /* Buffer to store decoded PCM */

  uint8_t num_encoded_msbc_pkts; /* Number of the encoded mSBC packets */

  tBTM_MSBC_PLC* plc; /* PLC component to handle the packet loss of input */
  static size_t get_supported_packet_size(size_t pkt_size,
                                          size_t* buffer_size) {
    int i;
    for (i = 0; btm_wbs_supported_pkt_size[i] != 0 &&
                btm_wbs_supported_pkt_size[i] != pkt_size;
         i++)
      ;
    /* In case of unsupported value, error log and fallback to
     * BTM_MSBC_PKT_LEN(60). */
    if (btm_wbs_supported_pkt_size[i] == 0) {
      LOG_WARN("Unsupported packet size %lu", (unsigned long)pkt_size);
      i = 0;
    }

    if (buffer_size) {
      *buffer_size = btm_wbs_msbc_buffer_size[i];
    }
    return btm_wbs_supported_pkt_size[i];
  }

  bool verify_h2_header_seq_num(const uint8_t num) {
    for (int i = 0; i < 4; i++) {
      if (num == btm_h2_header_frames_count[i]) {
        return true;
      }
    }
    return false;
  }

 public:
  size_t init(size_t pkt_size) {
    decode_buf_wo = 0;
    decode_buf_ro = 0;
    encode_buf_wo = 0;
    encode_buf_ro = 0;

    pkt_size = get_supported_packet_size(pkt_size, &buf_size);
    if (pkt_size == packet_size) return packet_size;
    packet_size = pkt_size;

    if (msbc_decode_buf) osi_free(msbc_decode_buf);
    msbc_decode_buf = (uint8_t*)osi_calloc(buf_size);

    if (msbc_encode_buf) osi_free(msbc_encode_buf);
    msbc_encode_buf = (uint8_t*)osi_calloc(buf_size);

    if (plc) {
      plc->deinit();
      osi_free(plc);
    }
    plc = (tBTM_MSBC_PLC*)osi_calloc(sizeof(*plc));
    plc->init();
    return packet_size;
  }

  void deinit() {
    if (msbc_decode_buf) osi_free(msbc_decode_buf);
    if (msbc_encode_buf) osi_free(msbc_encode_buf);
    if (plc) {
      plc->deinit();
      osi_free_and_reset((void**)&plc);
    }
  }

  size_t decodable() { return decode_buf_wo - decode_buf_ro; }

  void mark_pkt_decoded() {
    if (decode_buf_ro + BTM_MSBC_PKT_LEN > decode_buf_wo) {
      LOG_ERROR("Trying to mark read offset beyond write offset.");
      return;
    }

    decode_buf_ro += BTM_MSBC_PKT_LEN;
    if (decode_buf_ro == decode_buf_wo) {
      decode_buf_ro = 0;
      decode_buf_wo = 0;
    }
  }

  size_t write(const uint8_t* input, size_t len) {
    if (len > buf_size - decode_buf_wo) {
      return 0;
    }

    std::copy(input, input + len, msbc_decode_buf + decode_buf_wo);
    decode_buf_wo += len;
    return len;
  }

  const uint8_t* find_msbc_pkt_head() {
    if (read_corrupted) {
      LOG_DEBUG("Skip corrupted mSBC packets");
      read_corrupted = false;
      return nullptr;
    }

    size_t rp = 0;
    while (rp < BTM_MSBC_PKT_LEN &&
           decode_buf_wo - (decode_buf_ro + rp) >= BTM_MSBC_PKT_LEN) {
      if ((msbc_decode_buf[decode_buf_ro + rp] != BTM_MSBC_H2_HEADER_0) ||
          (!verify_h2_header_seq_num(
              msbc_decode_buf[decode_buf_ro + rp + 1])) ||
          (msbc_decode_buf[decode_buf_ro + rp + 2] != BTM_MSBC_SYNC_WORD)) {
        rp++;
        continue;
      }

      if (rp != 0) {
        LOG_WARN("Skipped %lu bytes of mSBC data ahead of a valid mSBC frame",
                 (unsigned long)rp);
        decode_buf_ro += rp;
      }
      return &msbc_decode_buf[decode_buf_ro];
    }

    return nullptr;
  }

  /* Fill in the mSBC header and update the buffer's write offset to guard the
   * buffer space to be written. Return a pointer to the start of mSBC packet's
   * body for the caller to fill the encoded mSBC data if there is enough space
   * in the buffer to fill in a new packet, otherwise return a nullptr. */
  uint8_t* fill_msbc_pkt_template() {
    uint8_t* wp = &msbc_encode_buf[encode_buf_wo];
    if (buf_size - encode_buf_wo < BTM_MSBC_PKT_LEN) {
      LOG_DEBUG("Packet queue can't accommodate more packets.");
      return nullptr;
    }

    wp[0] = BTM_MSBC_H2_HEADER_0;
    wp[1] = btm_h2_header_frames_count[num_encoded_msbc_pkts % 4];
    encode_buf_wo += BTM_MSBC_PKT_LEN;

    num_encoded_msbc_pkts++;
    return wp + BTM_MSBC_H2_HEADER_LEN;
  }

  size_t mark_pkt_dequeued() {
    LOG_DEBUG(
        "Try to mark an encoded packet dequeued: ro:%lu wo:%lu pkt_size:%lu",
        (unsigned long)encode_buf_ro, (unsigned long)encode_buf_wo,
        (unsigned long)packet_size);

    if (encode_buf_wo - encode_buf_ro < packet_size) return 0;

    encode_buf_ro += packet_size;
    if (encode_buf_ro == encode_buf_wo) {
      encode_buf_ro = 0;
      encode_buf_wo = 0;
    }

    return packet_size;
  }

  const uint8_t* sco_pkt_read_ptr() {
    if (encode_buf_wo - encode_buf_ro < packet_size) {
      LOG_DEBUG("Insufficient data as a SCO packet to read.");
      return nullptr;
    }

    return &msbc_encode_buf[encode_buf_ro];
  }
};

static tBTM_MSBC_INFO* msbc_info = nullptr;

size_t init(size_t pkt_size) {
  GetInterfaceToProfiles()->msbcCodec->initialize();

  if (msbc_info) {
    LOG_WARN("Re-initiating mSBC buffer that is active or not cleaned");
    msbc_info->deinit();
    osi_free(msbc_info);
  }

  msbc_info = (tBTM_MSBC_INFO*)osi_calloc(sizeof(*msbc_info));
  return msbc_info->init(pkt_size);
}

void cleanup() {
  GetInterfaceToProfiles()->msbcCodec->cleanup();

  if (msbc_info == nullptr) return;

  msbc_info->deinit();
  osi_free_and_reset((void**)&msbc_info);
}

bool fill_plc_stats(int* num_decoded_frames, double* packet_loss_ratio) {
  if (msbc_info == NULL || num_decoded_frames == NULL ||
      packet_loss_ratio == NULL)
    return false;

  int decoded_frames = msbc_info->plc->get_num_decoded_frames();
  int lost_frames = msbc_info->plc->get_num_lost_frames();
  if (decoded_frames <= 0 || lost_frames < 0 || lost_frames > decoded_frames)
    return false;

  *num_decoded_frames = decoded_frames;
  *packet_loss_ratio = (double)lost_frames / decoded_frames;
  return true;
}

size_t enqueue_packet(const uint8_t* data, size_t pkt_size, bool corrupted) {
  if (msbc_info == nullptr) {
    LOG_WARN("mSBC buffer uninitialized or cleaned");
    return 0;
  }

  if (pkt_size != msbc_info->packet_size) {
    LOG_WARN(
        "Ignoring the coming packet with size %lu that is inconsistent with "
        "the HAL reported packet size %lu",
        (unsigned long)pkt_size, (unsigned long)msbc_info->packet_size);
    return 0;
  }

  if (data == nullptr) {
    LOG_WARN("Invalid data to enqueue");
    return 0;
  }

  msbc_info->read_corrupted |= corrupted;
  if (msbc_info->write(data, pkt_size) != pkt_size) {
    LOG_DEBUG("Fail to write packet with size %lu to buffer",
              (unsigned long)pkt_size);
    return 0;
  }

  return pkt_size;
}

size_t decode(const uint8_t** out_data) {
  const uint8_t* frame_head = nullptr;

  if (msbc_info == nullptr) {
    LOG_WARN("mSBC buffer uninitialized or cleaned");
    return 0;
  }

  if (out_data == nullptr) {
    LOG_WARN("%s Invalid output pointer", __func__);
    return 0;
  }

  if (msbc_info->decodable() < BTM_MSBC_PKT_LEN) {
    LOG_DEBUG("No complete mSBC packet to decode");
    return 0;
  }

  frame_head = msbc_info->find_msbc_pkt_head();
  if (frame_head == nullptr) {
    LOG_DEBUG("No valid mSBC packet to decode %lu, %lu",
              (unsigned long)msbc_info->decode_buf_ro,
              (unsigned long)msbc_info->decode_buf_wo);
    /* Done with parsing the raw bytes just read. If we couldn't find a valid
     * mSBC frame head, we shall treat the existing BTM_MSBC_PKT_LEN length
     * of mSBC data as a corrupted packet and conduct the PLC. */
    goto packet_loss;
  }

  if (!GetInterfaceToProfiles()->msbcCodec->decodePacket(
          frame_head, msbc_info->decoded_pcm_buf,
          sizeof(msbc_info->decoded_pcm_buf))) {
    LOG_DEBUG("Decoding mSBC packet failed");
    goto packet_loss;
  }

  msbc_info->plc->handle_good_frames(msbc_info->decoded_pcm_buf);
  *out_data = (const uint8_t*)msbc_info->decoded_pcm_buf;
  msbc_info->mark_pkt_decoded();
  return BTM_MSBC_CODE_SIZE;

packet_loss:
  msbc_info->plc->handle_bad_frames(out_data);
  msbc_info->mark_pkt_decoded();
  return BTM_MSBC_CODE_SIZE;
}

size_t encode(int16_t* data, size_t len) {
  uint8_t* pkt_body = nullptr;
  uint32_t encoded_size = 0;
  if (msbc_info == nullptr) {
    LOG_WARN("mSBC buffer uninitialized or cleaned");
    return 0;
  }

  if (data == nullptr) {
    LOG_WARN("Invalid data to encode");
    return 0;
  }

  if (len < BTM_MSBC_CODE_SIZE) {
    LOG_DEBUG(
        "PCM frames with size %lu is insufficient to be encoded into a mSBC "
        "packet",
        (unsigned long)len);
    return 0;
  }

  pkt_body = msbc_info->fill_msbc_pkt_template();
  if (pkt_body == nullptr) {
    LOG_DEBUG("Failed to fill the template to fill the mSBC packet");
    return 0;
  }

  encoded_size =
      GetInterfaceToProfiles()->msbcCodec->encodePacket(data, pkt_body);
  if (encoded_size != BTM_MSBC_PKT_FRAME_LEN) {
    LOG_WARN("Encoding invalid packet size: %lu", (unsigned long)encoded_size);
    std::copy(&btm_msbc_zero_packet[BTM_MSBC_H2_HEADER_LEN],
              std::end(btm_msbc_zero_packet), pkt_body);
  }

  return BTM_MSBC_CODE_SIZE;
}

size_t dequeue_packet(const uint8_t** output) {
  if (msbc_info == nullptr) {
    LOG_WARN("mSBC buffer uninitialized or cleaned");
    return 0;
  }

  if (output == nullptr) {
    LOG_WARN("%s Invalid output pointer", __func__);
    return 0;
  }

  *output = msbc_info->sco_pkt_read_ptr();
  if (*output == nullptr) {
    LOG_DEBUG("Insufficient data to dequeue.");
    return 0;
  }

  return msbc_info->mark_pkt_dequeued();
}

}  // namespace wbs

}  // namespace sco
}  // namespace audio
}  // namespace bluetooth
