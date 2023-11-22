/*
 * Copyright (C) 2016 The Android Open Source Project
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

#define LOG_TAG "PixelStats-service"

#include <android-base/logging.h>
#include <binder/BinderService.h>
#include <hidl/HidlTransportSupport.h>
#include <utils/StrongPointer.h>

#include "PixelStats.h"

using hardware::google::pixelstats::V1_0::implementation::PixelStats;

int main(int /* argc */, char* /* argv */[]) {
    ::android::hardware::configureRpcThreadpool(1, true /*willJoinThreadpool*/);
    android::sp<PixelStats> ps = new PixelStats();
    if (ps->registerAsService() != android::OK) {
        LOG(ERROR) << "error starting pixelstats";
        return 2;
    }
    ::android::hardware::joinRpcThreadpool();
    return 1;
}
