/*
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "PipeComm"

#include <log/log.h>

#include "qemud.h"

#include "PipeComm.h"

#define CAR_SERVICE_NAME "car"


namespace android {
namespace hardware {
namespace automotive {
namespace vehicle {
namespace V2_0 {

namespace impl {

PipeComm::PipeComm(MessageProcessor* messageProcessor) : CommConn(messageProcessor), mPipeFd(-1) {}

void PipeComm::start() {
    int fd = qemud_channel_open(CAR_SERVICE_NAME);

    if (fd < 0) {
        ALOGE("%s: Could not open connection to service: %s %d", __FUNCTION__, strerror(errno), fd);
        return;
    }

    ALOGI("%s: Starting pipe connection, fd=%d", __FUNCTION__, fd);
    mPipeFd = fd;

    CommConn::start();
}

void PipeComm::stop() {
    if (mPipeFd > 0) {
        ::close(mPipeFd);
        mPipeFd = -1;
    }
    CommConn::stop();
}

std::vector<uint8_t> PipeComm::read() {
    static constexpr int MAX_RX_MSG_SZ = 2048;
    std::vector<uint8_t> msg = std::vector<uint8_t>(MAX_RX_MSG_SZ);
    int numBytes;

    numBytes = qemud_channel_recv(mPipeFd, msg.data(), msg.size());

    if (numBytes == MAX_RX_MSG_SZ) {
        ALOGE("%s: Received max size = %d", __FUNCTION__, MAX_RX_MSG_SZ);
    } else if (numBytes > 0) {
        msg.resize(numBytes);
        return msg;
    } else {
        ALOGD("%s: Connection terminated on pipe %d, numBytes=%d", __FUNCTION__, mPipeFd, numBytes);
        mPipeFd = -1;
    }

    return std::vector<uint8_t>();
}

int PipeComm::write(const std::vector<uint8_t>& data) {
    int retVal = 0;

    if (mPipeFd != -1) {
        retVal = qemud_channel_send(mPipeFd, data.data(), data.size());
    }

    if (retVal < 0) {
        retVal = -errno;
        ALOGE("%s:  send_cmd: (fd=%d): ERROR: %s", __FUNCTION__, mPipeFd, strerror(errno));
    }

    return retVal;
}


}  // impl

}  // namespace V2_0
}  // namespace vehicle
}  // namespace automotive
}  // namespace hardware
}  // namespace android



