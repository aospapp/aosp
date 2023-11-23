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

#include "CarPowerPolicyServer.h"

#include <android-base/result.h>
#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <fuzzbinder/libbinder_ndk_driver.h>
#include <fuzzer/FuzzedDataProvider.h>
#include <log/log.h>
#include <utils/Looper.h>
#include <utils/StrongPointer.h>

using ::android::fuzzService;
using ::android::Looper;
using ::android::sp;
using ::android::frameworks::automotive::powerpolicy::CarPowerPolicyServer;
using ::ndk::SharedRefBase;

using ::android::IPCThreadState;
using ::android::Looper;
using ::android::ProcessState;
using ::android::sp;
using ::android::frameworks::automotive::powerpolicy::CarPowerPolicyServer;

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    // Set up the binder
    sp<ProcessState> ps(ProcessState::self());
    ps->setThreadPoolMaxThreadCount(2);
    ps->startThreadPool();
    ps->giveThreadPoolName();
    IPCThreadState::self()->disableBackgroundScheduling(true);

    sp<Looper> looper(Looper::prepare(/*opts=*/0));
    auto result = CarPowerPolicyServer::startService(looper);
    if (!result.ok()) {
        printf("Failed to start service: %s", result.error().message().c_str());
        CarPowerPolicyServer::terminateService();
        exit(result.error().code());
    }
    auto carpowerServer = *result;
    fuzzService(carpowerServer->asBinder().get(), FuzzedDataProvider(data, size));
    CarPowerPolicyServer::terminateService();

    return 0;
}
