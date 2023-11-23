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

#include "hci/acl_manager/le_impl.h"

#include <gtest/gtest.h>

#include <chrono>

#include "common/bidi_queue.h"
#include "common/callback.h"
#include "hci/acl_manager.h"
#include "hci/address_with_type.h"
#include "hci/controller.h"
#include "hci/hci_packets.h"
#include "os/handler.h"
#include "os/log.h"
#include "packet/raw_builder.h"

using namespace std::chrono_literals;

using ::bluetooth::common::BidiQueue;
using ::bluetooth::common::Callback;
using ::bluetooth::os::Handler;
using ::bluetooth::os::Thread;

namespace bluetooth {
namespace hci {
namespace acl_manager {

class TestController : public Controller {
 public:
  uint16_t GetNumAclPacketBuffers() const {
    return max_acl_packet_credits_;
  }

  uint16_t GetAclPacketLength() const {
    return hci_mtu_;
  }

  LeBufferSize GetLeBufferSize() const {
    LeBufferSize le_buffer_size;
    le_buffer_size.le_data_packet_length_ = le_hci_mtu_;
    le_buffer_size.total_num_le_packets_ = le_max_acl_packet_credits_;
    return le_buffer_size;
  }

  void RegisterCompletedAclPacketsCallback(CompletedAclPacketsCallback cb) {
    acl_credits_callback_ = cb;
  }

  void SendCompletedAclPacketsCallback(uint16_t handle, uint16_t credits) {
    acl_credits_callback_.Invoke(handle, credits);
  }

  void UnregisterCompletedAclPacketsCallback() {
    acl_credits_callback_ = {};
  }

  const uint16_t max_acl_packet_credits_ = 10;
  const uint16_t hci_mtu_ = 1024;
  const uint16_t le_max_acl_packet_credits_ = 15;
  const uint16_t le_hci_mtu_ = 27;

 private:
  CompletedAclPacketsCallback acl_credits_callback_;
};

class TestHciLayer : public HciLayer {
  template <typename T>
  class CommandInterfaceImpl : public CommandInterface<T> {
   public:
    explicit CommandInterfaceImpl(HciLayer& hci) : hci_(hci) {}
    ~CommandInterfaceImpl() = default;

    void EnqueueCommand(
        std::unique_ptr<T> command, common::ContextualOnceCallback<void(CommandCompleteView)> on_complete) override {
      hci_.EnqueueCommand(move(command), std::move(on_complete));
    }

    void EnqueueCommand(
        std::unique_ptr<T> command, common::ContextualOnceCallback<void(CommandStatusView)> on_status) override {
      hci_.EnqueueCommand(move(command), std::move(on_status));
    }
    HciLayer& hci_;
  };

 public:
  LeAclConnectionInterface* GetLeAclConnectionInterface(
      common::ContextualCallback<void(LeMetaEventView)> event_handler,
      common::ContextualCallback<void(uint16_t, ErrorCode)> on_disconnect,
      common::ContextualCallback<
          void(hci::ErrorCode hci_status, uint16_t, uint8_t version, uint16_t manufacturer_name, uint16_t sub_version)>
          on_read_remote_version) override {
    disconnect_handlers_.push_back(on_disconnect);
    read_remote_version_handlers_.push_back(on_read_remote_version);
    return &le_acl_connection_manager_interface_2_;
  }

  void PutLeAclConnectionInterface() override {}

  CommandInterfaceImpl<AclCommandBuilder> le_acl_connection_manager_interface_2_{*this};
};

class LeImplTest : public ::testing::Test {
 public:
  void SetUp() override {
    thread_ = new Thread("thread", Thread::Priority::NORMAL);
    handler_ = new Handler(thread_);
    controller_ = new TestController();
    hci_layer_ = new TestHciLayer();

    round_robin_scheduler_ = new RoundRobinScheduler(handler_, controller_, hci_queue_.GetUpEnd());
    hci_queue_.GetDownEnd()->RegisterDequeue(
        handler_, common::Bind(&LeImplTest::HciDownEndDequeue, common::Unretained(this)));
    le_impl_ = new le_impl(hci_layer_, controller_, handler_, round_robin_scheduler_, true);
  }

  void TearDown() override {
    sync_handler();
    delete le_impl_;

    hci_queue_.GetDownEnd()->UnregisterDequeue();

    delete hci_layer_;
    delete round_robin_scheduler_;
    delete controller_;

    handler_->Clear();
    delete handler_;
    delete thread_;
  }

  void sync_handler() {
    std::promise<void> promise;
    auto future = promise.get_future();
    handler_->BindOnceOn(&promise, &std::promise<void>::set_value).Invoke();
    auto status = future.wait_for(10ms);
    ASSERT_EQ(status, std::future_status::ready);
  }

  void HciDownEndDequeue() {
    auto packet = hci_queue_.GetDownEnd()->TryDequeue();
    // Convert from a Builder to a View
    auto bytes = std::make_shared<std::vector<uint8_t>>();
    bluetooth::packet::BitInserter i(*bytes);
    bytes->reserve(packet->size());
    packet->Serialize(i);
    auto packet_view = bluetooth::packet::PacketView<bluetooth::packet::kLittleEndian>(bytes);
    AclView acl_packet_view = AclView::Create(packet_view);
    ASSERT_TRUE(acl_packet_view.IsValid());
    PacketView<true> count_view = acl_packet_view.GetPayload();
    sent_acl_packets_.push(acl_packet_view);

    packet_count_--;
    if (packet_count_ == 0) {
      packet_promise_->set_value();
      packet_promise_ = nullptr;
    }
  }

  uint16_t packet_count_;
  std::unique_ptr<std::promise<void>> packet_promise_;
  std::unique_ptr<std::future<void>> packet_future_;
  std::queue<AclView> sent_acl_packets_;

  BidiQueue<AclView, AclBuilder> hci_queue_{3};

  Thread* thread_;
  Handler* handler_;
  HciLayer* hci_layer_{nullptr};
  TestController* controller_;
  RoundRobinScheduler* round_robin_scheduler_{nullptr};

  struct le_impl* le_impl_;
};

TEST_F(LeImplTest, nop) {}

TEST_F(LeImplTest, add_device_to_connect_list) {
  le_impl_->add_device_to_connect_list({{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(1UL, le_impl_->connect_list.size());

  le_impl_->add_device_to_connect_list({{0x11, 0x12, 0x13, 0x14, 0x15, 0x16}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(2UL, le_impl_->connect_list.size());

  le_impl_->add_device_to_connect_list({{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(2UL, le_impl_->connect_list.size());

  le_impl_->add_device_to_connect_list({{0x11, 0x12, 0x13, 0x14, 0x15, 0x16}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(2UL, le_impl_->connect_list.size());
}

TEST_F(LeImplTest, remove_device_from_connect_list) {
  le_impl_->add_device_to_connect_list({{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}, AddressType::PUBLIC_DEVICE_ADDRESS});
  le_impl_->add_device_to_connect_list({{0x11, 0x12, 0x13, 0x14, 0x15, 0x16}, AddressType::PUBLIC_DEVICE_ADDRESS});
  le_impl_->add_device_to_connect_list({{0x21, 0x22, 0x23, 0x24, 0x25, 0x26}, AddressType::PUBLIC_DEVICE_ADDRESS});
  le_impl_->add_device_to_connect_list({{0x31, 0x32, 0x33, 0x34, 0x35, 0x36}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(4UL, le_impl_->connect_list.size());

  le_impl_->remove_device_from_connect_list({{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(3UL, le_impl_->connect_list.size());

  le_impl_->remove_device_from_connect_list({{0x11, 0x12, 0x13, 0x14, 0x15, 0x16}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(2UL, le_impl_->connect_list.size());

  le_impl_->remove_device_from_connect_list({{0x11, 0x12, 0x13, 0x14, 0x15, 0x16}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(2UL, le_impl_->connect_list.size());

  le_impl_->remove_device_from_connect_list({Address::kEmpty, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(2UL, le_impl_->connect_list.size());

  le_impl_->remove_device_from_connect_list({{0x21, 0x22, 0x23, 0x24, 0x25, 0x26}, AddressType::PUBLIC_DEVICE_ADDRESS});
  le_impl_->remove_device_from_connect_list({{0x31, 0x32, 0x33, 0x34, 0x35, 0x36}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(0UL, le_impl_->connect_list.size());
}

}  // namespace acl_manager
}  // namespace hci
}  // namespace bluetooth
