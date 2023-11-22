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

#ifndef ANDROID_NET_HW_SERVICE_H
#define ANDROID_NET_HW_SERVICE_H

#include <android/system/net/netd/1.0/INetd.h>

using android::hardware::Return;
using android::system::net::netd::V1_0::INetd;

namespace android {
namespace net {

class NetdHwService : public INetd {
public:
    status_t start();
    Return<void> createOemNetwork(createOemNetwork_cb _hidl_cb) override;
    Return<INetd::StatusCode> destroyOemNetwork(uint64_t netHandle) override;
};

}  // namespace net
}  // namespace android

#endif  // ANDROID_NET_HW_SERVICE_H

