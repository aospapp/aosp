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

#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <vector>

#include "btm_sco_hfp_hal.h"
#include "gd/common/init_flags.h"
#include "osi/include/log.h"
#include "stack/acl/acl.h"
#include "stack/include/acl_api.h"

namespace hfp_hal_interface {
namespace {
bool offload_supported = false;
bool offload_enabled = false;

struct mgmt_bt_codec {
  uint8_t codec;
  uint8_t packet_size;
  uint8_t data_path;
  uint32_t data_length;
  uint8_t data[];
} __attribute__((packed));

typedef struct cached_codec_info {
  struct bt_codec inner;
  uint8_t pkt_size;
} cached_codec_info;

std::vector<cached_codec_info> cached_codecs;

#define RETRY_ON_INTR(fn) \
  do {                    \
  } while ((fn) == -1 && errno == EINTR)

#define MGMT_EV_SIZE_MAX 1024
#define MGMT_PKT_HDR_SIZE 6
struct mgmt_pkt {
  uint16_t opcode;
  uint16_t index;
  uint16_t len;
  uint8_t data[MGMT_EV_SIZE_MAX];
} __attribute__((packed));

#define MGMT_EV_COMMAND_COMPLETE 0x1

struct mgmt_ev_cmd_complete {
  uint16_t opcode;
  uint8_t status;
  uint8_t data[];
} __attribute__((packed));

#define MGMT_OP_GET_SCO_CODEC_CAPABILITIES 0x0100
#define MGMT_SCO_CODEC_CVSD 0x1
#define MGMT_SCO_CODEC_MSBC_TRANSPARENT 0x2
#define MGMT_SCO_CODEC_MSBC 0x3

struct mgmt_cp_get_codec_capabilities {
  uint16_t hci_dev;
  uint32_t num_codecs;
  uint8_t codecs[];
} __attribute__((packed));

struct mgmt_rp_get_codec_capabilities {
  uint16_t hci_dev;
  uint8_t offload_capable;
  uint32_t num_codecs;
  struct mgmt_bt_codec codecs[];
} __attribute__((packed));

#define MGMT_POLL_TIMEOUT_MS 2000

void cache_codec_capabilities(struct mgmt_rp_get_codec_capabilities* rp) {
  uint8_t* ptr = reinterpret_cast<uint8_t*>(rp->codecs);
  // Copy into cached codec information
  offload_supported = rp->offload_capable;
  for (int i = 0; i < rp->num_codecs; i++) {
    struct mgmt_bt_codec* mc = reinterpret_cast<struct mgmt_bt_codec*>(ptr);
    cached_codec_info c = {
        .inner =
            {
                .codec = static_cast<codec>(1 << (mc->codec - 1)),
                .data_path = mc->data_path,
                .data = mc->data_length == 0
                            ? std::vector<uint8_t>{}
                            : std::vector<uint8_t>(mc->data,
                                                   mc->data + mc->data_length),
            },
        .pkt_size = mc->packet_size,
    };
    ptr += sizeof(*mc);

    LOG_INFO("Caching HFP codec %u, data path %u, data len %d, pkt_size %u",
             (uint64_t)c.inner.codec, c.inner.data_path, c.inner.data.size(),
             c.pkt_size);

    cached_codecs.push_back(c);
  }
}

struct sockaddr_hci {
  sa_family_t hci_family;
  unsigned short hci_dev;
  unsigned short hci_channel;
};

constexpr uint8_t BTPROTO_HCI = 1;
constexpr uint16_t HCI_CHANNEL_CONTROL = 3;
constexpr uint16_t HCI_DEV_NONE = 0xffff;

int btsocket_open_mgmt(uint16_t hci) {
  int fd = socket(PF_BLUETOOTH, SOCK_RAW | SOCK_NONBLOCK, BTPROTO_HCI);
  if (fd < 0) {
    LOG_DEBUG("Failed to open BT socket.");
    return -errno;
  }

  struct sockaddr_hci addr = {
      .hci_family = AF_BLUETOOTH,
      .hci_dev = HCI_DEV_NONE,
      .hci_channel = HCI_CHANNEL_CONTROL,
  };

  int ret = bind(fd, (struct sockaddr*)&addr, sizeof(addr));
  if (ret < 0) {
    LOG_DEBUG("Failed to bind BT socket.");
    close(fd);
    return -errno;
  }

  return fd;
}

int mgmt_get_codec_capabilities(int fd, uint16_t hci) {
  // Write read codec capabilities
  struct mgmt_pkt ev;
  ev.opcode = MGMT_OP_GET_SCO_CODEC_CAPABILITIES;
  ev.index = HCI_DEV_NONE;
  ev.len = sizeof(struct mgmt_cp_get_codec_capabilities) + 3;

  struct mgmt_cp_get_codec_capabilities* cp =
      reinterpret_cast<struct mgmt_cp_get_codec_capabilities*>(ev.data);
  cp->hci_dev = hci;
  cp->num_codecs = 3;
  cp->codecs[0] = MGMT_SCO_CODEC_CVSD;
  cp->codecs[1] = MGMT_SCO_CODEC_MSBC_TRANSPARENT;
  cp->codecs[2] = MGMT_SCO_CODEC_MSBC;

  int ret;

  struct pollfd writable[1];
  writable[0].fd = fd;
  writable[0].events = POLLOUT;

  do {
    ret = poll(writable, 1, MGMT_POLL_TIMEOUT_MS);
    if (ret > 0) {
      RETRY_ON_INTR(ret = write(fd, &ev, MGMT_PKT_HDR_SIZE + ev.len));
      if (ret < 0) {
        LOG_DEBUG("Failed to call MGMT_OP_GET_SCO_CODEC_CAPABILITIES: %d",
                  -errno);
        return -errno;
      };
      break;
    }
  } while (ret > 0);

  if (ret <= 0) {
    LOG_DEBUG("Failed waiting for mgmt socket to be writable.");
    return -1;
  }

  struct pollfd fds[1];

  fds[0].fd = fd;
  fds[0].events = POLLIN;

  do {
    ret = poll(fds, 1, MGMT_POLL_TIMEOUT_MS);
    if (ret > 0) {
      if (fds[0].revents & POLLIN) {
        RETRY_ON_INTR(ret = read(fd, &ev, sizeof(ev)));
        if (ret < 0) {
          LOG_DEBUG("Failed to read mgmt socket: %d", -errno);
          return -errno;
        }

        if (ev.opcode == MGMT_EV_COMMAND_COMPLETE) {
          struct mgmt_ev_cmd_complete* cc =
              reinterpret_cast<struct mgmt_ev_cmd_complete*>(ev.data);
          if (cc->opcode == MGMT_OP_GET_SCO_CODEC_CAPABILITIES &&
              cc->status == 0) {
            struct mgmt_rp_get_codec_capabilities* rp =
                reinterpret_cast<struct mgmt_rp_get_codec_capabilities*>(
                    cc->data);
            if (rp->hci_dev == hci) {
              cache_codec_capabilities(rp);
              return 0;
            }
          }
        }
      }
    } else if (ret == 0) {
      LOG_DEBUG("Timeout while waiting for codec capabilities response.");
      ret = -1;
    }
  } while (ret > 0);

  return ret;
}

#define MGMT_OP_NOTIFY_SCO_CONNECTION_CHANGE 0x0101
struct mgmt_cp_notify_sco_connection_change {
  uint16_t hci_dev;
  uint8_t addr[6];
  uint8_t addr_type;
  uint8_t connected;
  uint8_t codec;
} __attribute__((packed));

int mgmt_notify_sco_connection_change(int fd, int hci, RawAddress device,
                                      bool is_connected, int codec) {
  struct mgmt_pkt ev;
  ev.opcode = MGMT_OP_NOTIFY_SCO_CONNECTION_CHANGE;
  ev.index = HCI_DEV_NONE;
  ev.len = sizeof(struct mgmt_cp_notify_sco_connection_change);

  struct mgmt_cp_notify_sco_connection_change* cp =
      reinterpret_cast<struct mgmt_cp_notify_sco_connection_change*>(ev.data);

  cp->hci_dev = hci;
  cp->connected = is_connected;
  cp->codec = codec;
  memcpy(cp->addr, device.address, sizeof(cp->addr));
  cp->addr_type = 0;

  int ret;

  struct pollfd writable[1];
  writable[0].fd = fd;
  writable[0].events = POLLOUT;

  do {
    ret = poll(writable, 1, MGMT_POLL_TIMEOUT_MS);
    if (ret > 0) {
      RETRY_ON_INTR(ret = write(fd, &ev, MGMT_PKT_HDR_SIZE + ev.len));
      if (ret < 0) {
        LOG_ERROR("Failed to call MGMT_OP_NOTIFY_SCO_CONNECTION_CHANGE: %d",
                  -errno);
        return -errno;
      };
      break;
    }
  } while (ret > 0);

  if (ret <= 0) {
    LOG_DEBUG("Failed waiting for mgmt socket to be writable.");
    return -1;
  }

  return 0;
}
}  // namespace

void init() {
  int hci = bluetooth::common::InitFlags::GetAdapterIndex();
  int fd = btsocket_open_mgmt(hci);
  if (fd < 0) {
    LOG_ERROR("Failed to open mgmt channel, error= %d.", fd);
    return;
  }

  int ret = mgmt_get_codec_capabilities(fd, hci);
  if (ret) {
    LOG_ERROR("Failed to get codec capabilities with error = %d.", ret);
  } else {
    LOG_INFO("Successfully queried SCO codec capabilities.");
  }

  close(fd);
}

// Check if wideband speech is supported on local device
bool get_wbs_supported() {
  for (cached_codec_info c : cached_codecs) {
    if (c.inner.codec == MSBC || c.inner.codec == MSBC_TRANSPARENT) {
      return true;
    }
  }
  return false;
}

// Here SWB default to be false at linux, change if needed
bool get_swb_supported() { return false; }

// Checks the supported codecs
bt_codecs get_codec_capabilities(uint64_t codecs) {
  bt_codecs codec_list = {.offload_capable = offload_supported};

  for (auto c : cached_codecs) {
    if (c.inner.codec & codecs) {
      codec_list.codecs.push_back(c.inner);
    }
  }

  return codec_list;
}

// Check if hardware offload is supported
bool get_offload_supported() { return offload_supported; }

// Check if hardware offload is enabled
bool get_offload_enabled() { return offload_supported && offload_enabled; }

// Set offload enable/disable
bool enable_offload(bool enable) {
  if (!offload_supported && enable) {
    LOG_ERROR("%s: Cannot enable SCO-offload since it is not supported.",
              __func__);
    return false;
  }
  offload_enabled = enable;
  return true;
}

static bool get_single_codec(int codec, bt_codec** out) {
  for (cached_codec_info& c : cached_codecs) {
    if (c.inner.codec == codec) {
      *out = &c.inner;
      return true;
    }
  }

  return false;
}

constexpr uint8_t OFFLOAD_DATAPATH = 0x01;

// Notify the codec datapath to lower layer for offload mode
void set_codec_datapath(esco_coding_format_t coding_format) {
  bool found;
  bt_codec* codec;
  uint8_t codec_id;

  switch (coding_format) {
    case BTM_SCO_CODEC_CVSD:
      codec_id = codec::CVSD;
      break;
    case BTM_SCO_CODEC_MSBC:
      codec_id = get_offload_enabled() ? codec::MSBC : codec::MSBC_TRANSPARENT;
      break;
    default:
      LOG_WARN("Unsupported format (%u). Won't set datapath.", coding_format);
      return;
  }

  found = get_single_codec(codec_id, &codec);
  if (!found) {
    LOG_ERROR(
        "Failed to find codec config for format (%u). Won't set datapath.",
        coding_format);
    return;
  }

  LOG_INFO("Configuring datapath for codec (%u)", codec->codec);
  if (codec->codec == codec::MSBC && !get_offload_enabled()) {
    LOG_ERROR(
        "Tried to configure offload data path for format (%u) with offload "
        "disabled. Won't set datapath.",
        coding_format);
    return;
  }

  if (get_offload_enabled()) {
    /* TODO(b/237373343): expect the data content to be represented differently
     */
    std::vector<uint8_t> data;
    switch (coding_format) {
      case BTM_SCO_CODEC_CVSD:
        data = {0x00};
        break;
      case BTM_SCO_CODEC_MSBC:
        data = {0x01};
        break;
      default:
        break;
    }

    btm_configure_data_path(btm_data_direction::CONTROLLER_TO_HOST,
                            OFFLOAD_DATAPATH, data);
    btm_configure_data_path(btm_data_direction::HOST_TO_CONTROLLER,
                            OFFLOAD_DATAPATH, data);
  }
}

int get_packet_size(int codec) {
  for (const cached_codec_info& c : cached_codecs) {
    if (c.inner.codec == codec) {
      return c.pkt_size;
    }
  }

  return kDefaultPacketSize;
}

void notify_sco_connection_change(RawAddress device, bool is_connected,
                                  int codec) {
  int hci = bluetooth::common::InitFlags::GetAdapterIndex();
  int fd = btsocket_open_mgmt(hci);
  if (fd < 0) {
    LOG_ERROR("Failed to open mgmt channel, error= %d.", fd);
    return;
  }

  // Default to cvsd and try to convert codec.
  int converted_codec = MGMT_SCO_CODEC_CVSD;

  if (codec == codec::MSBC) {
    converted_codec = MGMT_SCO_CODEC_MSBC;
  } else if (codec == codec::MSBC_TRANSPARENT) {
    converted_codec = MGMT_SCO_CODEC_MSBC_TRANSPARENT;
  }

  int ret = mgmt_notify_sco_connection_change(fd, hci, device, is_connected,
                                              converted_codec);
  if (ret) {
    LOG_ERROR(
        "Failed to notify HAL of connection change: hci %d, device %s, "
        "connected %d, codec %d",
        hci, ADDRESS_TO_LOGGABLE_CSTR(device), is_connected, codec);
  } else {
    LOG_INFO(
        "Notified HAL of connection change: hci %d, device %s, connected %d, "
        "codec %d",
        hci, ADDRESS_TO_LOGGABLE_CSTR(device), is_connected, codec);
  }

  close(fd);
}

void update_esco_parameters(enh_esco_params_t* p_parms) {
  if (get_offload_enabled()) {
    p_parms->input_transport_unit_size = 0x01;
    p_parms->output_transport_unit_size = 0x01;
  } else {
    p_parms->input_transport_unit_size = 0x00;
    p_parms->output_transport_unit_size = 0x00;
  }
}
}  // namespace hfp_hal_interface
