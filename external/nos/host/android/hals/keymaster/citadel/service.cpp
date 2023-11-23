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

#include <android-base/logging.h>
#include <hidl/HidlTransportSupport.h>
#include <utils/StrongPointer.h>

#include <application.h>
#include <nos/AppClient.h>
#include <nos/CitadeldProxyClient.h>
#include <nos/debug.h>

#include <KeymasterDevice.h>
#include <Keymaster.client.h>

#include <nugget/app/keymaster/keymaster.pb.h>
#include "../proto_utils.h"

using ::android::OK;
using ::android::sp;
using ::android::status_t;
using ::android::hardware::configureRpcThreadpool;
using ::android::hardware::joinRpcThreadpool;

using ::android::hardware::keymaster::KeymasterDevice;
using ::android::hardware::keymaster::translate_error_code;

using ::nos::CitadeldProxyClient;
using ::nos::AppClient;
using ::nugget::app::keymaster::ProvisionPresharedSecretRequest;
using ::nugget::app::keymaster::ProvisionPresharedSecretResponse;
using ::nugget::app::keymaster::PresharedSecretStatus;
using ErrorCodeNos = ::nugget::app::keymaster::ErrorCode;
using ::android::hardware::keymaster::V4_0::ErrorCode;

using KeymasterClient = ::nugget::app::keymaster::Keymaster;

int main() {
    LOG(INFO) << "Keymaster HAL service starting";

    // Connect to citadeld
    CitadeldProxyClient citadeldProxy;
    citadeldProxy.Open();
    if (!citadeldProxy.IsOpen()) {
        LOG(FATAL) << "Failed to open citadeld client";
    }

    // This thread will become the only thread of the daemon
    constexpr bool thisThreadWillJoinPool = true;
    configureRpcThreadpool(1, thisThreadWillJoinPool);

    // Start the HAL service
    KeymasterClient keymasterClient{citadeldProxy};
    sp<KeymasterDevice> keymaster = new KeymasterDevice{keymasterClient};

    const status_t status = keymaster->registerAsService("strongbox");
    if (status != OK) {
        LOG(FATAL) << "Failed to register Keymaster as a service (status: "
                   << status << ")";
    }

    joinRpcThreadpool();
    return -1; // Should never be reached
}
