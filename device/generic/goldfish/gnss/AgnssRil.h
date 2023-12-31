/*
 * Copyright (C) 2023 The Android Open Source Project
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

#pragma once
#include <aidl/android/hardware/gnss/BnAGnssRil.h>

namespace aidl {
namespace android {
namespace hardware {
namespace gnss {
namespace implementation {

using AGnssRefLocation = BnAGnssRil::AGnssRefLocation;

struct AGnssRil : public BnAGnssRil {
    ndk::ScopedAStatus setCallback(const std::shared_ptr<IAGnssRilCallback>& callback) override;
    ndk::ScopedAStatus setRefLocation(const AGnssRefLocation& agnssReflocation) override;
    ndk::ScopedAStatus setSetId(SetIdType type, const std::string& setid) override;
    ndk::ScopedAStatus updateNetworkState(const NetworkAttributes& attributes) override;
};

}  // namespace implementation
}  // namespace gnss
}  // namespace hardware
}  // namespace android
}  // namespace
