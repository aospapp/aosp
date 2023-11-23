// Copyright 2022 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/// Connect HCI file descriptors to root canal.

#include "fd_startup.h"

#include <google/protobuf/util/json_util.h>
#include <sys/uio.h>
#include <unistd.h>

#include <memory>
#include <thread>

#include "common.pb.h"
#include "controller/scene_controller.h"
#include "hci/bluetooth_facade.h"
#include "model/hci/h4_parser.h"
#include "model/hci/hci_transport.h"
#include "startup.pb.h"
#include "util/log.h"

namespace netsim {
namespace hci {
namespace {

enum HCIPacket : int {
  PACKET_TYPE_UNSPECIFIED = 0,
  PACKET_TYPE_HCI_COMMAND = 1,
  PACKET_TYPE_ACL = 2,
  PACKET_TYPE_SCO = 3,
  PACKET_TYPE_EVENT = 4,
  PACKET_TYPE_ISO = 5
};

// Class to move packets between root canal and file descriptor.

class FdHciForwarder : public rootcanal::HciTransport {
 public:
  ~FdHciForwarder() {}

  FdHciForwarder(std::string name, int fd_in, int fd_out)
      : fd_in_(fd_in), fd_out_(fd_out), name_(std::move(name)) {}

  // Called by HCITransport (rootcanal)
  void SendEvent(const std::vector<uint8_t> &data) override {
    this->Write(HCIPacket::PACKET_TYPE_EVENT, data.data(), data.size());
  }

  // Called by HCITransport (rootcanal)
  void SendAcl(const std::vector<uint8_t> &data) override {
    this->Write(HCIPacket::PACKET_TYPE_ACL, data.data(), data.size());
  }

  // Called by HCITransport (rootcanal)
  void SendSco(const std::vector<uint8_t> &data) override {
    this->Write(HCIPacket::PACKET_TYPE_SCO, data.data(), data.size());
  }

  // Called by HCITransport (rootcanal)
  void SendIso(const std::vector<uint8_t> &data) override {
    this->Write(HCIPacket::PACKET_TYPE_ISO, data.data(), data.size());
  }

  // A wrapper for the HCITransport that uses shared ptrs.

  static void SharedPacketCallback(const rootcanal::PacketCallback &cb,
                                   const std::vector<uint8_t> &raw_packet) {
    std::shared_ptr<std::vector<uint8_t>> packet_copy =
        std::make_shared<std::vector<uint8_t>>(raw_packet);
    cb(packet_copy);
  }
  // Called by HCITransport (rootcanal)
  // This case is simular to hci_socket_transport.cc
  void RegisterCallbacks(rootcanal::PacketCallback command_cb,
                         rootcanal::PacketCallback acl_cb,
                         rootcanal::PacketCallback sco_cb,
                         rootcanal::PacketCallback iso_cb,
                         rootcanal::CloseCallback close_cb) override {
    h4_parser_ = std::make_unique<rootcanal::H4Parser>(
        [command_cb](const std::vector<uint8_t> &raw_command) {
          SharedPacketCallback(command_cb, raw_command);
        },
        [](const std::vector<uint8_t> &) {
          BtsLog("Unexpected Event in FdTransport!");
        },
        [acl_cb](const std::vector<uint8_t> &raw_acl) {
          SharedPacketCallback(acl_cb, raw_acl);
        },
        [sco_cb](const std::vector<uint8_t> &raw_sco) {
          SharedPacketCallback(sco_cb, raw_sco);
        },
        [iso_cb](const std::vector<uint8_t> &raw_iso) {
          SharedPacketCallback(iso_cb, raw_iso);
        });
    this->close_cb_ = close_cb;
    // start the reader thread
    this->reader_ = std::move(std::thread([&] { StartReader(); }));
  }

  // Called by HCITransport (rootcanal)
  void Close() override {
    BtsLog("netsim - Close called for %s", name_.c_str());
    close(fd_in_);
    close(fd_out_);
  }

  // Called by HCITransport (rootcanal)
  void Tick() override {}

 private:
  const int fd_in_, fd_out_;
  std::thread reader_;
  bool disconnected_ = false;
  rootcanal::CloseCallback close_cb_;
  std::unique_ptr<rootcanal::H4Parser> h4_parser_;
  const std::string name_;

  // Write packets from rootcanal back to guest os
  int Write(uint8_t type, const uint8_t *data, size_t length) {
    struct iovec iov[] = {{&type, sizeof(type)},
                          {const_cast<uint8_t *>(data), length}};
    ssize_t ret = 0;
    do {
      ret = writev(fd_in_, iov, sizeof(iov) / sizeof(iov[0]));
    } while (-1 == ret && EAGAIN == errno);
    if (ret == -1) {
      BtsLog("Netsim: Error writing (%s)", strerror(errno));
    } else if (ret < static_cast<ssize_t>(length + 1)) {
      BtsLog("Netsim: %d / %d bytes written - something went wrong...",
             static_cast<int>(ret), static_cast<int>(length + 1));
    }
    return ret;
  }

  // Start reading packets from guest os fd and sending to root canal.
  void StartReader() {
    while (!disconnected_) {
      ssize_t bytes_to_read = h4_parser_->BytesRequested();
      std::vector<uint8_t> buffer(bytes_to_read);
      ssize_t bytes_read;
      bytes_read = read(fd_out_, buffer.data(), bytes_to_read);
      if (bytes_read == 0) {
        BtsLog("remote disconnected -- %s", name_.c_str());
        disconnected_ = true;
        close_cb_();
        break;
      } else if (bytes_read < 0) {
        if (errno == EAGAIN) {
          // No data, try again later.
          continue;
        } else if (errno == ECONNRESET) {
          // They probably rejected our packet
          disconnected_ = true;
          close_cb_();
          break;
        } else {
          BtsLog("Read error in %d %s", h4_parser_->CurrentState(),
                 strerror(errno));
        }
      } else {
        h4_parser_->Consume(buffer.data(), bytes_read);
      }
    }
  }
};

// Private implementation

class FdStartupImpl : public FdStartup {
 public:
  // Disallow copy and assign
  FdStartupImpl(const FdStartupImpl &) = delete;
  FdStartupImpl &operator=(const FdStartupImpl &) = delete;

  FdStartupImpl() {}

  // Connect multiple fd endpoints

  bool Connect(const std::string &startup_str) override {
    netsim::startup::StartupInfo info;
    google::protobuf::util::JsonParseOptions options;
    auto status = JsonStringToMessage(startup_str, &info, options);

    if (!status.ok()) {
      BtsLog("FdStartup failed to parse json '%s' - %s", startup_str.c_str(),
             status.ToString().c_str());
      return false;
    }

    for (const auto &device : info.devices()) {
      auto name = device.name();
      for (const auto &chip : device.chips()) {
        if (chip.kind() == common::ChipKind::BLUETOOTH) {
          auto fd_in = chip.fd_in();
          auto fd_out = chip.fd_out();

          BtsLog("Connecting %s on fd_in:%d, fd_out:%d", name.c_str(), fd_in,
                 fd_out);

          std::shared_ptr<rootcanal::HciTransport> transport =
              std::make_shared<FdHciForwarder>(name, fd_in, fd_out);
          auto guid =
              name + ":" + std::to_string(fd_in) + ":" + std::to_string(fd_out);
          netsim::controller::SceneController::Singleton().AddChip(
              guid, name, common::ChipKind::BLUETOOTH);

          BtsLog("FdHciForwarder is not supported");
        }
      }
    }
    return true;
  }
};

}  // namespace

FdStartup::~FdStartup(){};

// Public constructor for FdStartup that returns private implementation.

std::unique_ptr<FdStartup> FdStartup::Create() {
  std::cerr << "Creating FdStartup\n";
  return std::make_unique<FdStartupImpl>();
}

}  // namespace hci
}  // namespace netsim
