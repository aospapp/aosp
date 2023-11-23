/*
 * Copyright (C) 2022 The Android Open Source Project
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

#ifndef CHRE_CHANNEL_OUTPUT_H_
#define CHRE_CHANNEL_OUTPUT_H_

#include <cstdint>

#include "chre/util/pigweed/permission.h"
#include "chre_api/chre.h"
#include "pw_rpc/channel.h"
#include "pw_span/span.h"

namespace chre {

/**
 * Message format used for communicating between nanoapps since CHRE doesn't
 * have a standard format for this as part of the API definition.
 */
struct ChrePigweedNanoappMessage {
  size_t msgSize;
  void *msg;
};

/**
 * ChannelOutput that can be used for nanoapps wishing to utilize
 * pw::rpc::Server and pw::rpc::Client for RPC communication between other
 * nanoapps and Android app host clients.
 */
class ChreChannelOutputBase : public pw::rpc::ChannelOutput {
 public:
  // Random value chosen that matches Java client util, but is random enough
  // to not conflict with other CHRE messages the nanoapp and client may send.
  static constexpr uint32_t PW_RPC_CHRE_HOST_MESSAGE_TYPE = INT32_MAX - 10;

  // Random values chosen to be towards the end of the nanoapp event type region
  // so it doesn't conflict with existing nanoapp messages that can be sent.
  static constexpr uint16_t PW_RPC_CHRE_NAPP_REQUEST_EVENT_TYPE =
      UINT16_MAX - 10;
  static constexpr uint16_t PW_RPC_CHRE_NAPP_RESPONSE_EVENT_TYPE =
      UINT16_MAX - 9;

  size_t MaximumTransmissionUnit() override;

 protected:
  ChreChannelOutputBase();
};

/**
 * Channel output that must be used on the server side of the channel between
 * two nanoapps.
 */
class ChreServerNanoappChannelOutput : public ChreChannelOutputBase {
 public:
  explicit ChreServerNanoappChannelOutput(RpcPermission &permission)
      : mPermission(permission) {}
  /**
   * Sets the nanoapp instance ID that is being communicated with over this
   * channel output.
   */
  void setClient(uint32_t nanoappInstanceId);

  pw::Status Send(pw::span<const std::byte> buffer) override;

 private:
  uint16_t mClientInstanceId = 0;
  RpcPermission &mPermission;
};

/**
 * Channel output that must be used on the client side of the channel between
 * two nanoapps.
 */
class ChreClientNanoappChannelOutput : public ChreChannelOutputBase {
 public:
  /**
   * Sets the server instance ID.
   *
   * This method must only be called for clients.
   *
   * @param instanceId The instance ID of the server.
   */
  void setServer(uint32_t instanceId);

  pw::Status Send(pw::span<const std::byte> buffer) override;

 private:
  uint16_t mServerInstanceId = 0;
};

/**
 * Channel output that must be used if the channel is between a nanoapp and
 * host client.
 */
class ChreServerHostChannelOutput : public ChreChannelOutputBase {
 public:
  explicit ChreServerHostChannelOutput(RpcPermission &permission)
      : mPermission(permission) {}
  /**
   * Sets the host endpoint being communicated with.
   */
  void setHostEndpoint(uint16_t hostEndpoint);

  pw::Status Send(pw::span<const std::byte> buffer) override;

 private:
  uint16_t mEndpointId = CHRE_HOST_ENDPOINT_UNSPECIFIED;
  RpcPermission &mPermission;
};

}  // namespace chre

#endif  // CHRE_CHANNEL_OUTPUT_H_
