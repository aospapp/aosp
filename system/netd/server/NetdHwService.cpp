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

#include <binder/IPCThreadState.h>
#include <hidl/HidlTransportSupport.h>
#include <hwbinder/IPCThreadState.h>
#include "Controllers.h"
#include "Fwmark.h"
#include "NetdHwService.h"

using android::hardware::configureRpcThreadpool;
using android::hardware::IPCThreadState;
using android::hardware::Void;

namespace android {
namespace net {

/**
 * This lock exists to make NetdHwService RPCs (which come in on multiple HwBinder threads)
 * coexist with the commands in CommandListener.cpp. These are presumed not thread-safe because
 * CommandListener has only one user (NetworkManagementService), which is connected through a
 * FrameworkListener that passes in commands one at a time.
 */
extern android::RWLock gBigNetdLock;

static INetd::StatusCode toHalStatus(int ret) {
    switch(ret) {
        case 0:
            return INetd::StatusCode::OK;
        case -EINVAL:
            return INetd::StatusCode::INVALID_ARGUMENTS;
        case -EEXIST:
            return INetd::StatusCode::ALREADY_EXISTS;
        case -ENONET:
            return INetd::StatusCode::NO_NETWORK;
        case -EPERM:
            return INetd::StatusCode::PERMISSION_DENIED;
        default:
            ALOGE("HAL service error=%d", ret);
            return INetd::StatusCode::UNKNOWN_ERROR;
    }
}

// Minimal service start.
status_t NetdHwService::start() {
    IPCThreadState::self()->disableBackgroundScheduling(true);
    // Usage of this HAL is anticipated to be thin; one thread should suffice.
    configureRpcThreadpool(1, false /* callerWillNotJoin */);
    // Register hardware service with ServiceManager.
    return INetd::registerAsService();
}

Return<void> NetdHwService::createOemNetwork(createOemNetwork_cb _hidl_cb) {
    unsigned netId;
    Permission permission = PERMISSION_SYSTEM;

    android::RWLock::AutoWLock _lock(gBigNetdLock);
    int ret = gCtls->netCtrl.createPhysicalOemNetwork(permission, &netId);

    Fwmark fwmark;
    fwmark.netId = netId;
    fwmark.explicitlySelected = true;
    fwmark.protectedFromVpn = true;
    fwmark.permission = PERMISSION_SYSTEM;
    _hidl_cb(netIdToNetHandle(netId), fwmark.intValue, toHalStatus(ret));

    return Void();
}

Return<INetd::StatusCode> NetdHwService::destroyOemNetwork(uint64_t netHandle) {
    unsigned netId = netHandleToNetId(netHandle);
    if ((netId < NetworkController::MIN_OEM_ID) ||
            (netId > NetworkController::MAX_OEM_ID)) {
        return INetd::StatusCode::INVALID_ARGUMENTS;
    }

    android::RWLock::AutoWLock _lock(gBigNetdLock);

    return toHalStatus(gCtls->netCtrl.destroyNetwork(netId));
}

}  // namespace net
}  // namespace android
