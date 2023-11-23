/*
 * Copyright (C) 2020 The Android Open Source Project
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
#include <android/binder_manager.h>
#include <android/binder_process.h>

#include "DumpstateDevice.h"
#include "WatchdogClient.h"

#include <vsockinfo.h>

using ::aidl::android::hardware::dumpstate::implementation::DumpstateDevice;
using ::aidl::android::hardware::dumpstate::implementation::WatchdogClient;
using ::android::OK;
using ::android::sp;
using ::android::hardware::automotive::utils::VsockConnectionInfo;

int main() {
    const auto si = VsockConnectionInfo::fromRoPropertyStore(
            {
                    "ro.boot.vendor.dumpstate.server.cid",
                    "ro.vendor.dumpstate.server.cid",
            },
            {
                    "ro.boot.vendor.dumpstate.server.port",
                    "ro.vendor.dumpstate.server.port",
            });

    if (!si) {
        ALOGE("failed to get server connection cid/port; configure and try again.");
        return 1;
    } else {
        ALOGI("Connecting to vsock server at %s", si->str().c_str());
    }

    ABinderProcess_setThreadPoolMaxThreadCount(0);

    // Create an instance of our service class
    std::shared_ptr<DumpstateDevice> dumpstateImpl =
            ndk::SharedRefBase::make<DumpstateDevice>(si->str());

    const std::string instance = std::string() + DumpstateDevice::descriptor + "/default";
    binder_status_t status =
            AServiceManager_addService(dumpstateImpl->asBinder().get(), instance.c_str());
    CHECK(status == STATUS_OK);

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;  // should not reach
}
