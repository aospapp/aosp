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

#include "chre/util/pigweed/chre_channel_output.h"

#include <cstdint>

#include "chre/util/nanoapp/callbacks.h"
#include "chre/util/pigweed/rpc_helper.h"

namespace chre {
namespace {

void nappMessageFreeCb(uint16_t /* eventType */, void *eventData) {
  chreHeapFree(eventData);
}

/**
 * Sends the buffer to the nanoapp.
 *
 * The buffer is first wrapped into a ChrePigweedNanoappMessage struct.
 *
 * @param targetInstanceId The nanoapp to send the message to
 * @param eventType The event to send to the nanoapp
 * @param buffer The buffer to send
 * @return The status of the operation
 */
pw::Status sendToNanoapp(uint32_t targetInstanceId, uint16_t eventType,
                         pw::span<const std::byte> buffer) {
  CHRE_ASSERT(targetInstanceId != 0);

  if (buffer.size() > 0) {
    auto *data = static_cast<ChrePigweedNanoappMessage *>(
        chreHeapAlloc(buffer.size() + sizeof(ChrePigweedNanoappMessage)));
    if (data == nullptr) {
      return PW_STATUS_RESOURCE_EXHAUSTED;
    }

    data->msgSize = buffer.size();
    data->msg = &data[1];
    memcpy(data->msg, buffer.data(), buffer.size());

    if (!chreSendEvent(eventType, data, nappMessageFreeCb, targetInstanceId)) {
      return PW_STATUS_INVALID_ARGUMENT;
    }
  }

  return PW_STATUS_OK;
}

}  // namespace

ChreChannelOutputBase::ChreChannelOutputBase() : ChannelOutput("CHRE") {}

size_t ChreChannelOutputBase::MaximumTransmissionUnit() {
  return CHRE_MESSAGE_TO_HOST_MAX_SIZE - sizeof(ChrePigweedNanoappMessage);
}

void ChreServerNanoappChannelOutput::setClient(uint32_t nanoappInstanceId) {
  CHRE_ASSERT(nanoappInstanceId <= kRpcNanoappMaxId);
  if (nanoappInstanceId <= kRpcNanoappMaxId) {
    mClientInstanceId = static_cast<uint16_t>(nanoappInstanceId);
  } else {
    mClientInstanceId = 0;
  }
}

pw::Status ChreServerNanoappChannelOutput::Send(
    pw::span<const std::byte> buffer) {
  // The permission is not enforced across nanoapps but we still need to
  // reset the value as it is only applicable to the next message.
  mPermission.getAndReset();

  return sendToNanoapp(mClientInstanceId, PW_RPC_CHRE_NAPP_RESPONSE_EVENT_TYPE,
                       buffer);
}

void ChreClientNanoappChannelOutput::setServer(uint32_t instanceId) {
  CHRE_ASSERT(instanceId <= kRpcNanoappMaxId);
  if (instanceId <= kRpcNanoappMaxId) {
    mServerInstanceId = static_cast<uint16_t>(instanceId);
  } else {
    mServerInstanceId = 0;
  }
}

pw::Status ChreClientNanoappChannelOutput::Send(
    pw::span<const std::byte> buffer) {
  return sendToNanoapp(mServerInstanceId, PW_RPC_CHRE_NAPP_REQUEST_EVENT_TYPE,
                       buffer);
}

void ChreServerHostChannelOutput::setHostEndpoint(uint16_t hostEndpoint) {
  mEndpointId = hostEndpoint;
}

pw::Status ChreServerHostChannelOutput::Send(pw::span<const std::byte> buffer) {
  CHRE_ASSERT(mEndpointId != CHRE_HOST_ENDPOINT_UNSPECIFIED);
  pw::Status returnCode = PW_STATUS_OK;

  if (buffer.size() > 0) {
    uint32_t permission = mPermission.getAndReset();
    uint8_t *data = static_cast<uint8_t *>(chreHeapAlloc(buffer.size()));
    if (data == nullptr) {
      returnCode = PW_STATUS_RESOURCE_EXHAUSTED;
    } else {
      memcpy(data, buffer.data(), buffer.size());
      if (!chreSendMessageWithPermissions(
              data, buffer.size(), PW_RPC_CHRE_HOST_MESSAGE_TYPE, mEndpointId,
              permission, heapFreeMessageCallback)) {
        returnCode = PW_STATUS_INVALID_ARGUMENT;
      }
    }
  }

  return returnCode;
}

}  // namespace chre
