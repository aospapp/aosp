/*
 * Copyright 2021, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include "hal_nxpuwb.h"
#include "phNxpUciHal_Adaptation.h"
#include "uwb.h"

namespace {
constexpr static int32_t kAndroidUciVersion = 1;
}

namespace android {
namespace hardware {
namespace uwb {
namespace impl {
using namespace ::aidl::android::hardware::uwb;

UwbChip::UwbChip(const std::string& name) : name_(name){};
std::shared_ptr<IUwbClientCallback> UwbChip:: mClientCallback = nullptr;
UwbChip::~UwbChip() {}

::ndk::ScopedAStatus UwbChip::getName(std::string* name) {
    *name = name_;
    return ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus UwbChip::open(const std::shared_ptr<IUwbClientCallback>& clientCallback) {
    mClientCallback = clientCallback;
      LOG(INFO) << "AIDL-open Enter";

      if (mClientCallback == nullptr) {
        LOG(ERROR) << "AIDL-HAL open clientCallback is null......";
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
      }
      int status = phNxpUciHal_open(eventCallback, dataCallback);
      LOG(INFO) << "AIDL-open Exit" << status;
      return ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus UwbChip::close() {
     LOG(INFO) << "AIDL-Close Enter";
     if (mClientCallback == nullptr) {
        LOG(ERROR) << "AIDL-HAL close mCallback is null......";
        return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
      }
     phNxpUciHal_close();
     if (mClientCallback != nullptr) {
       mClientCallback = nullptr;
     }
     return ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus UwbChip::coreInit() {
      LOG(INFO) << "AIDL-coreInit Enter";
     phNxpUciHal_coreInitialization();
    return ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus UwbChip::getSupportedAndroidUciVersion(int32_t* version) {
    *version = kAndroidUciVersion;
    return ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus UwbChip::sendUciMessage(const std::vector<uint8_t>&  data,
                                             int32_t* _aidl_return /* bytes_written */) {
    // TODO(b/195992658): Need emulator support for UCI stack.
     std::vector<uint8_t> copy = data;
     LOG(INFO) << "AIDL-Write Enter";
     int32_t ret = phNxpUciHal_write(data.size(), &copy[0]);
     *_aidl_return = ret;
     return ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus UwbChip::sessionInit(int32_t sessionId) {
      LOG(INFO) << "AIDL-SessionInitialization Enter";
      phNxpUciHal_sessionInitialization(sessionId);
      return ndk::ScopedAStatus::ok();
}
}  // namespace impl
}  // namespace uwb
}  // namespace hardware
}  // namespace android
