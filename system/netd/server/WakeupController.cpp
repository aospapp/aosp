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

#define LOG_TAG "WakeupController"

#include <endian.h>
#include <linux/netfilter/nfnetlink.h>
#include <linux/netfilter/nfnetlink_log.h>
#include <iostream>

#include <android-base/stringprintf.h>
#include <cutils/log.h>
#include <netdutils/Netfilter.h>
#include <netdutils/Netlink.h>

#include "IptablesRestoreController.h"
#include "NetlinkManager.h"
#include "WakeupController.h"

namespace android {
namespace net {

using base::StringPrintf;
using netdutils::Slice;
using netdutils::Status;

const char WakeupController::LOCAL_MANGLE_INPUT[] = "wakeupctrl_mangle_INPUT";

WakeupController::~WakeupController() {
    expectOk(mListener->unsubscribe(NetlinkManager::NFLOG_WAKEUP_GROUP));
}

netdutils::Status WakeupController::init(NFLogListenerInterface* listener) {
    mListener = listener;
    const auto msgHandler = [this](const nlmsghdr&, const nfgenmsg&, const Slice msg) {
        std::string prefix;
        uid_t uid = -1;
        gid_t gid = -1;
        uint64_t timestampNs = -1;
        const auto attrHandler = [&prefix, &uid, &gid, &timestampNs](const nlattr attr,
                                                                     const Slice payload) {
            switch (attr.nla_type) {
                case NFULA_TIMESTAMP: {
                    timespec timespec = {};
                    extract(payload, timespec);
                    constexpr uint64_t kNsPerS = 1000000000ULL;
                    timestampNs = be32toh(timespec.tv_nsec) + (be32toh(timespec.tv_sec) * kNsPerS);
                    break;
                }
                case NFULA_PREFIX:
                    // Strip trailing '\0'
                    prefix = toString(take(payload, payload.size() - 1));
                    break;
                case NFULA_UID:
                    extract(payload, uid);
                    uid = be32toh(uid);
                    break;
                case NFULA_GID:
                    extract(payload, gid);
                    gid = be32toh(gid);
                    break;
                default:
                    break;
            }
        };
        forEachNetlinkAttribute(msg, attrHandler);
        mReport(prefix, uid, gid, timestampNs);
    };
    return mListener->subscribe(NetlinkManager::NFLOG_WAKEUP_GROUP, msgHandler);
}

Status WakeupController::addInterface(const std::string& ifName, const std::string& prefix,
                                    uint32_t mark, uint32_t mask) {
    return execIptables("-A", ifName, prefix, mark, mask);
}

Status WakeupController::delInterface(const std::string& ifName, const std::string& prefix,
                                    uint32_t mark, uint32_t mask) {
    return execIptables("-D", ifName, prefix, mark, mask);
}

Status WakeupController::execIptables(const std::string& action, const std::string& ifName,
                                      const std::string& prefix, uint32_t mark, uint32_t mask) {
    // NFLOG messages to batch before releasing to userspace
    constexpr int kBatch = 8;
    // Max log message rate in packets/second
    constexpr int kRateLimit = 10;
    const char kFormat[] =
        "*mangle\n%s %s -i %s -j NFLOG --nflog-prefix %s --nflog-group %d --nflog-threshold %d"
        " -m mark --mark 0x%08x/0x%08x -m limit --limit %d/s\nCOMMIT\n";
    const auto cmd = StringPrintf(
        kFormat, action.c_str(), WakeupController::LOCAL_MANGLE_INPUT, ifName.c_str(),
        prefix.c_str(), NetlinkManager::NFLOG_WAKEUP_GROUP, kBatch, mark, mask, kRateLimit);

    std::string out;
    auto rv = mIptables->execute(V4V6, cmd, &out);
    if (rv != 0) {
        auto s = Status(rv, "Failed to execute iptables cmd: " + cmd + ", out: " + out);
        ALOGE("%s", toString(s).c_str());
        return s;
    }
    return netdutils::status::ok;
}

}  // namespace net
}  // namespace android
