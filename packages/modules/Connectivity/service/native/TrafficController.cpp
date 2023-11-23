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

#define LOG_TAG "TrafficController"
#include <inttypes.h>
#include <linux/if_ether.h>
#include <linux/in.h>
#include <linux/inet_diag.h>
#include <linux/netlink.h>
#include <linux/sock_diag.h>
#include <linux/unistd.h>
#include <net/if.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <map>
#include <mutex>
#include <unordered_set>
#include <vector>

#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <netdutils/StatusOr.h>
#include <netdutils/Syscalls.h>
#include <netdutils/UidConstants.h>
#include <netdutils/Utils.h>
#include <private/android_filesystem_config.h>

#include "TrafficController.h"
#include "bpf/BpfMap.h"
#include "netdutils/DumpWriter.h"

namespace android {
namespace net {

using base::StringPrintf;
using base::unique_fd;
using bpf::BpfMap;
using bpf::synchronizeKernelRCU;
using netdutils::DumpWriter;
using netdutils::NetlinkListener;
using netdutils::NetlinkListenerInterface;
using netdutils::ScopedIndent;
using netdutils::Slice;
using netdutils::sSyscalls;
using netdutils::Status;
using netdutils::statusFromErrno;
using netdutils::StatusOr;

constexpr int kSockDiagMsgType = SOCK_DIAG_BY_FAMILY;
constexpr int kSockDiagDoneMsgType = NLMSG_DONE;

const char* TrafficController::LOCAL_DOZABLE = "fw_dozable";
const char* TrafficController::LOCAL_STANDBY = "fw_standby";
const char* TrafficController::LOCAL_POWERSAVE = "fw_powersave";
const char* TrafficController::LOCAL_RESTRICTED = "fw_restricted";
const char* TrafficController::LOCAL_LOW_POWER_STANDBY = "fw_low_power_standby";
const char* TrafficController::LOCAL_OEM_DENY_1 = "fw_oem_deny_1";
const char* TrafficController::LOCAL_OEM_DENY_2 = "fw_oem_deny_2";
const char* TrafficController::LOCAL_OEM_DENY_3 = "fw_oem_deny_3";

static_assert(BPF_PERMISSION_INTERNET == INetd::PERMISSION_INTERNET,
              "Mismatch between BPF and AIDL permissions: PERMISSION_INTERNET");
static_assert(BPF_PERMISSION_UPDATE_DEVICE_STATS == INetd::PERMISSION_UPDATE_DEVICE_STATS,
              "Mismatch between BPF and AIDL permissions: PERMISSION_UPDATE_DEVICE_STATS");

#define FLAG_MSG_TRANS(result, flag, value) \
    do {                                    \
        if ((value) & (flag)) {             \
            (result).append(" " #flag);     \
            (value) &= ~(flag);             \
        }                                   \
    } while (0)

const std::string uidMatchTypeToString(uint32_t match) {
    std::string matchType;
    FLAG_MSG_TRANS(matchType, HAPPY_BOX_MATCH, match);
    FLAG_MSG_TRANS(matchType, PENALTY_BOX_MATCH, match);
    FLAG_MSG_TRANS(matchType, DOZABLE_MATCH, match);
    FLAG_MSG_TRANS(matchType, STANDBY_MATCH, match);
    FLAG_MSG_TRANS(matchType, POWERSAVE_MATCH, match);
    FLAG_MSG_TRANS(matchType, RESTRICTED_MATCH, match);
    FLAG_MSG_TRANS(matchType, LOW_POWER_STANDBY_MATCH, match);
    FLAG_MSG_TRANS(matchType, IIF_MATCH, match);
    FLAG_MSG_TRANS(matchType, LOCKDOWN_VPN_MATCH, match);
    FLAG_MSG_TRANS(matchType, OEM_DENY_1_MATCH, match);
    FLAG_MSG_TRANS(matchType, OEM_DENY_2_MATCH, match);
    FLAG_MSG_TRANS(matchType, OEM_DENY_3_MATCH, match);
    if (match) {
        return StringPrintf("Unknown match: %u", match);
    }
    return matchType;
}

const std::string UidPermissionTypeToString(int permission) {
    if (permission == INetd::PERMISSION_NONE) {
        return "PERMISSION_NONE";
    }
    if (permission == INetd::PERMISSION_UNINSTALLED) {
        // This should never appear in the map, complain loudly if it does.
        return "PERMISSION_UNINSTALLED error!";
    }
    std::string permissionType;
    FLAG_MSG_TRANS(permissionType, BPF_PERMISSION_INTERNET, permission);
    FLAG_MSG_TRANS(permissionType, BPF_PERMISSION_UPDATE_DEVICE_STATS, permission);
    if (permission) {
        return StringPrintf("Unknown permission: %u", permission);
    }
    return permissionType;
}

StatusOr<std::unique_ptr<NetlinkListenerInterface>> TrafficController::makeSkDestroyListener() {
    const auto& sys = sSyscalls.get();
    ASSIGN_OR_RETURN(auto event, sys.eventfd(0, EFD_CLOEXEC));
    const int domain = AF_NETLINK;
    const int type = SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK;
    const int protocol = NETLINK_INET_DIAG;
    ASSIGN_OR_RETURN(auto sock, sys.socket(domain, type, protocol));

    // TODO: if too many sockets are closed too quickly, we can overflow the socket buffer, and
    // some entries in mCookieTagMap will not be freed. In order to fix this we would need to
    // periodically dump all sockets and remove the tag entries for sockets that have been closed.
    // For now, set a large-enough buffer that we can close hundreds of sockets without getting
    // ENOBUFS and leaking mCookieTagMap entries.
    int rcvbuf = 512 * 1024;
    auto ret = sys.setsockopt(sock, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));
    if (!ret.ok()) {
        ALOGW("Failed to set SkDestroyListener buffer size to %d: %s", rcvbuf, ret.msg().c_str());
    }

    sockaddr_nl addr = {
        .nl_family = AF_NETLINK,
        .nl_groups = 1 << (SKNLGRP_INET_TCP_DESTROY - 1) | 1 << (SKNLGRP_INET_UDP_DESTROY - 1) |
                     1 << (SKNLGRP_INET6_TCP_DESTROY - 1) | 1 << (SKNLGRP_INET6_UDP_DESTROY - 1)};
    RETURN_IF_NOT_OK(sys.bind(sock, addr));

    const sockaddr_nl kernel = {.nl_family = AF_NETLINK};
    RETURN_IF_NOT_OK(sys.connect(sock, kernel));

    std::unique_ptr<NetlinkListenerInterface> listener =
            std::make_unique<NetlinkListener>(std::move(event), std::move(sock), "SkDestroyListen");

    return listener;
}

Status TrafficController::initMaps() {
    std::lock_guard guard(mMutex);

    RETURN_IF_NOT_OK(mCookieTagMap.init(COOKIE_TAG_MAP_PATH));
    RETURN_IF_NOT_OK(mUidCounterSetMap.init(UID_COUNTERSET_MAP_PATH));
    RETURN_IF_NOT_OK(mAppUidStatsMap.init(APP_UID_STATS_MAP_PATH));
    RETURN_IF_NOT_OK(mStatsMapA.init(STATS_MAP_A_PATH));
    RETURN_IF_NOT_OK(mStatsMapB.init(STATS_MAP_B_PATH));
    RETURN_IF_NOT_OK(mIfaceIndexNameMap.init(IFACE_INDEX_NAME_MAP_PATH));
    RETURN_IF_NOT_OK(mIfaceStatsMap.init(IFACE_STATS_MAP_PATH));

    RETURN_IF_NOT_OK(mConfigurationMap.init(CONFIGURATION_MAP_PATH));

    RETURN_IF_NOT_OK(mUidOwnerMap.init(UID_OWNER_MAP_PATH));
    RETURN_IF_NOT_OK(mUidPermissionMap.init(UID_PERMISSION_MAP_PATH));
    ALOGI("%s successfully", __func__);

    return netdutils::status::ok;
}

Status TrafficController::start(bool startSkDestroyListener) {
    RETURN_IF_NOT_OK(initMaps());

    if (!startSkDestroyListener) {
        return netdutils::status::ok;
    }

    auto result = makeSkDestroyListener();
    if (!isOk(result)) {
        ALOGE("Unable to create SkDestroyListener: %s", toString(result).c_str());
    } else {
        mSkDestroyListener = std::move(result.value());
    }
    // Rx handler extracts nfgenmsg looks up and invokes registered dispatch function.
    const auto rxHandler = [this](const nlmsghdr&, const Slice msg) {
        std::lock_guard guard(mMutex);
        inet_diag_msg diagmsg = {};
        if (extract(msg, diagmsg) < sizeof(inet_diag_msg)) {
            ALOGE("Unrecognized netlink message: %s", toString(msg).c_str());
            return;
        }
        uint64_t sock_cookie = static_cast<uint64_t>(diagmsg.id.idiag_cookie[0]) |
                               (static_cast<uint64_t>(diagmsg.id.idiag_cookie[1]) << 32);

        Status s = mCookieTagMap.deleteValue(sock_cookie);
        if (!isOk(s) && s.code() != ENOENT) {
            ALOGE("Failed to delete cookie %" PRIx64 ": %s", sock_cookie, toString(s).c_str());
            return;
        }
    };
    expectOk(mSkDestroyListener->subscribe(kSockDiagMsgType, rxHandler));

    // In case multiple netlink message comes in as a stream, we need to handle the rxDone message
    // properly.
    const auto rxDoneHandler = [](const nlmsghdr&, const Slice msg) {
        // Ignore NLMSG_DONE  messages
        inet_diag_msg diagmsg = {};
        extract(msg, diagmsg);
    };
    expectOk(mSkDestroyListener->subscribe(kSockDiagDoneMsgType, rxDoneHandler));

    return netdutils::status::ok;
}

Status TrafficController::updateOwnerMapEntry(UidOwnerMatchType match, uid_t uid, FirewallRule rule,
                                              FirewallType type) {
    std::lock_guard guard(mMutex);
    if ((rule == ALLOW && type == ALLOWLIST) || (rule == DENY && type == DENYLIST)) {
        RETURN_IF_NOT_OK(addRule(uid, match));
    } else if ((rule == ALLOW && type == DENYLIST) || (rule == DENY && type == ALLOWLIST)) {
        RETURN_IF_NOT_OK(removeRule(uid, match));
    } else {
        //Cannot happen.
        return statusFromErrno(EINVAL, "");
    }
    return netdutils::status::ok;
}

Status TrafficController::removeRule(uint32_t uid, UidOwnerMatchType match) {
    auto oldMatch = mUidOwnerMap.readValue(uid);
    if (oldMatch.ok()) {
        UidOwnerValue newMatch = {
                .iif = (match == IIF_MATCH) ? 0 : oldMatch.value().iif,
                .rule = oldMatch.value().rule & ~match,
        };
        if (newMatch.rule == 0) {
            RETURN_IF_NOT_OK(mUidOwnerMap.deleteValue(uid));
        } else {
            RETURN_IF_NOT_OK(mUidOwnerMap.writeValue(uid, newMatch, BPF_ANY));
        }
    } else {
        return statusFromErrno(ENOENT, StringPrintf("uid: %u does not exist in map", uid));
    }
    return netdutils::status::ok;
}

Status TrafficController::addRule(uint32_t uid, UidOwnerMatchType match, uint32_t iif) {
    if (match != IIF_MATCH && iif != 0) {
        return statusFromErrno(EINVAL, "Non-interface match must have zero interface index");
    }
    auto oldMatch = mUidOwnerMap.readValue(uid);
    if (oldMatch.ok()) {
        UidOwnerValue newMatch = {
                .iif = (match == IIF_MATCH) ? iif : oldMatch.value().iif,
                .rule = oldMatch.value().rule | match,
        };
        RETURN_IF_NOT_OK(mUidOwnerMap.writeValue(uid, newMatch, BPF_ANY));
    } else {
        UidOwnerValue newMatch = {
                .iif = iif,
                .rule = match,
        };
        RETURN_IF_NOT_OK(mUidOwnerMap.writeValue(uid, newMatch, BPF_ANY));
    }
    return netdutils::status::ok;
}

Status TrafficController::updateUidOwnerMap(const uint32_t uid,
                                            UidOwnerMatchType matchType, IptOp op) {
    std::lock_guard guard(mMutex);
    if (op == IptOpDelete) {
        RETURN_IF_NOT_OK(removeRule(uid, matchType));
    } else if (op == IptOpInsert) {
        RETURN_IF_NOT_OK(addRule(uid, matchType));
    } else {
        // Cannot happen.
        return statusFromErrno(EINVAL, StringPrintf("invalid IptOp: %d, %d", op, matchType));
    }
    return netdutils::status::ok;
}

FirewallType TrafficController::getFirewallType(ChildChain chain) {
    switch (chain) {
        case DOZABLE:
            return ALLOWLIST;
        case STANDBY:
            return DENYLIST;
        case POWERSAVE:
            return ALLOWLIST;
        case RESTRICTED:
            return ALLOWLIST;
        case LOW_POWER_STANDBY:
            return ALLOWLIST;
        case OEM_DENY_1:
            return DENYLIST;
        case OEM_DENY_2:
            return DENYLIST;
        case OEM_DENY_3:
            return DENYLIST;
        case NONE:
        default:
            return DENYLIST;
    }
}

int TrafficController::changeUidOwnerRule(ChildChain chain, uid_t uid, FirewallRule rule,
                                          FirewallType type) {
    Status res;
    switch (chain) {
        case DOZABLE:
            res = updateOwnerMapEntry(DOZABLE_MATCH, uid, rule, type);
            break;
        case STANDBY:
            res = updateOwnerMapEntry(STANDBY_MATCH, uid, rule, type);
            break;
        case POWERSAVE:
            res = updateOwnerMapEntry(POWERSAVE_MATCH, uid, rule, type);
            break;
        case RESTRICTED:
            res = updateOwnerMapEntry(RESTRICTED_MATCH, uid, rule, type);
            break;
        case LOW_POWER_STANDBY:
            res = updateOwnerMapEntry(LOW_POWER_STANDBY_MATCH, uid, rule, type);
            break;
        case OEM_DENY_1:
            res = updateOwnerMapEntry(OEM_DENY_1_MATCH, uid, rule, type);
            break;
        case OEM_DENY_2:
            res = updateOwnerMapEntry(OEM_DENY_2_MATCH, uid, rule, type);
            break;
        case OEM_DENY_3:
            res = updateOwnerMapEntry(OEM_DENY_3_MATCH, uid, rule, type);
            break;
        case NONE:
        default:
            ALOGW("Unknown child chain: %d", chain);
            return -EINVAL;
    }
    if (!isOk(res)) {
        ALOGE("change uid(%u) rule of %d failed: %s, rule: %d, type: %d", uid, chain,
              res.msg().c_str(), rule, type);
        return -res.code();
    }
    return 0;
}

Status TrafficController::replaceRulesInMap(const UidOwnerMatchType match,
                                            const std::vector<int32_t>& uids) {
    std::lock_guard guard(mMutex);
    std::set<int32_t> uidSet(uids.begin(), uids.end());
    std::vector<uint32_t> uidsToDelete;
    auto getUidsToDelete = [&uidsToDelete, &uidSet](const uint32_t& key,
                                                    const BpfMap<uint32_t, UidOwnerValue>&) {
        if (uidSet.find((int32_t) key) == uidSet.end()) {
            uidsToDelete.push_back(key);
        }
        return base::Result<void>();
    };
    RETURN_IF_NOT_OK(mUidOwnerMap.iterate(getUidsToDelete));

    for(auto uid : uidsToDelete) {
        RETURN_IF_NOT_OK(removeRule(uid, match));
    }

    for (auto uid : uids) {
        RETURN_IF_NOT_OK(addRule(uid, match));
    }
    return netdutils::status::ok;
}

Status TrafficController::addUidInterfaceRules(const int iif,
                                               const std::vector<int32_t>& uidsToAdd) {
    std::lock_guard guard(mMutex);

    for (auto uid : uidsToAdd) {
        netdutils::Status result = addRule(uid, IIF_MATCH, iif);
        if (!isOk(result)) {
            ALOGW("addRule failed(%d): uid=%d iif=%d", result.code(), uid, iif);
        }
    }
    return netdutils::status::ok;
}

Status TrafficController::removeUidInterfaceRules(const std::vector<int32_t>& uidsToDelete) {
    std::lock_guard guard(mMutex);

    for (auto uid : uidsToDelete) {
        netdutils::Status result = removeRule(uid, IIF_MATCH);
        if (!isOk(result)) {
            ALOGW("removeRule failed(%d): uid=%d", result.code(), uid);
        }
    }
    return netdutils::status::ok;
}

Status TrafficController::updateUidLockdownRule(const uid_t uid, const bool add) {
    std::lock_guard guard(mMutex);

    netdutils::Status result = add ? addRule(uid, LOCKDOWN_VPN_MATCH)
                               : removeRule(uid, LOCKDOWN_VPN_MATCH);
    if (!isOk(result)) {
        ALOGW("%s Lockdown rule failed(%d): uid=%d",
              (add ? "add": "remove"), result.code(), uid);
    }
    return result;
}

int TrafficController::replaceUidOwnerMap(const std::string& name, bool isAllowlist __unused,
                                          const std::vector<int32_t>& uids) {
    // FirewallRule rule = isAllowlist ? ALLOW : DENY;
    // FirewallType type = isAllowlist ? ALLOWLIST : DENYLIST;
    Status res;
    if (!name.compare(LOCAL_DOZABLE)) {
        res = replaceRulesInMap(DOZABLE_MATCH, uids);
    } else if (!name.compare(LOCAL_STANDBY)) {
        res = replaceRulesInMap(STANDBY_MATCH, uids);
    } else if (!name.compare(LOCAL_POWERSAVE)) {
        res = replaceRulesInMap(POWERSAVE_MATCH, uids);
    } else if (!name.compare(LOCAL_RESTRICTED)) {
        res = replaceRulesInMap(RESTRICTED_MATCH, uids);
    } else if (!name.compare(LOCAL_LOW_POWER_STANDBY)) {
        res = replaceRulesInMap(LOW_POWER_STANDBY_MATCH, uids);
    } else if (!name.compare(LOCAL_OEM_DENY_1)) {
        res = replaceRulesInMap(OEM_DENY_1_MATCH, uids);
    } else if (!name.compare(LOCAL_OEM_DENY_2)) {
        res = replaceRulesInMap(OEM_DENY_2_MATCH, uids);
    } else if (!name.compare(LOCAL_OEM_DENY_3)) {
        res = replaceRulesInMap(OEM_DENY_3_MATCH, uids);
    } else {
        ALOGE("unknown chain name: %s", name.c_str());
        return -EINVAL;
    }
    if (!isOk(res)) {
        ALOGE("Failed to clean up chain: %s: %s", name.c_str(), res.msg().c_str());
        return -res.code();
    }
    return 0;
}

int TrafficController::toggleUidOwnerMap(ChildChain chain, bool enable) {
    std::lock_guard guard(mMutex);
    uint32_t key = UID_RULES_CONFIGURATION_KEY;
    auto oldConfigure = mConfigurationMap.readValue(key);
    if (!oldConfigure.ok()) {
        ALOGE("Cannot read the old configuration from map: %s",
              oldConfigure.error().message().c_str());
        return -oldConfigure.error().code();
    }
    uint32_t match;
    switch (chain) {
        case DOZABLE:
            match = DOZABLE_MATCH;
            break;
        case STANDBY:
            match = STANDBY_MATCH;
            break;
        case POWERSAVE:
            match = POWERSAVE_MATCH;
            break;
        case RESTRICTED:
            match = RESTRICTED_MATCH;
            break;
        case LOW_POWER_STANDBY:
            match = LOW_POWER_STANDBY_MATCH;
            break;
        case OEM_DENY_1:
            match = OEM_DENY_1_MATCH;
            break;
        case OEM_DENY_2:
            match = OEM_DENY_2_MATCH;
            break;
        case OEM_DENY_3:
            match = OEM_DENY_3_MATCH;
            break;
        default:
            return -EINVAL;
    }
    BpfConfig newConfiguration =
            enable ? (oldConfigure.value() | match) : (oldConfigure.value() & ~match);
    Status res = mConfigurationMap.writeValue(key, newConfiguration, BPF_EXIST);
    if (!isOk(res)) {
        ALOGE("Failed to toggleUidOwnerMap(%d): %s", chain, res.msg().c_str());
    }
    return -res.code();
}

Status TrafficController::swapActiveStatsMap() {
    std::lock_guard guard(mMutex);

    uint32_t key = CURRENT_STATS_MAP_CONFIGURATION_KEY;
    auto oldConfigure = mConfigurationMap.readValue(key);
    if (!oldConfigure.ok()) {
        ALOGE("Cannot read the old configuration from map: %s",
              oldConfigure.error().message().c_str());
        return Status(oldConfigure.error().code(), oldConfigure.error().message());
    }

    // Write to the configuration map to inform the kernel eBPF program to switch
    // from using one map to the other. Use flag BPF_EXIST here since the map should
    // be already populated in initMaps.
    uint32_t newConfigure = (oldConfigure.value() == SELECT_MAP_A) ? SELECT_MAP_B : SELECT_MAP_A;
    auto res = mConfigurationMap.writeValue(CURRENT_STATS_MAP_CONFIGURATION_KEY, newConfigure,
                                            BPF_EXIST);
    if (!res.ok()) {
        ALOGE("Failed to toggle the stats map: %s", strerror(res.error().code()));
        return res;
    }
    // After changing the config, we need to make sure all the current running
    // eBPF programs are finished and all the CPUs are aware of this config change
    // before we modify the old map. So we do a special hack here to wait for
    // the kernel to do a synchronize_rcu(). Once the kernel called
    // synchronize_rcu(), the config we just updated will be available to all cores
    // and the next eBPF programs triggered inside the kernel will use the new
    // map configuration. So once this function returns we can safely modify the
    // old stats map without concerning about race between the kernel and
    // userspace.
    int ret = synchronizeKernelRCU();
    if (ret) {
        ALOGE("map swap synchronize_rcu() ended with failure: %s", strerror(-ret));
        return statusFromErrno(-ret, "map swap synchronize_rcu() failed");
    }
    return netdutils::status::ok;
}

void TrafficController::setPermissionForUids(int permission, const std::vector<uid_t>& uids) {
    std::lock_guard guard(mMutex);
    if (permission == INetd::PERMISSION_UNINSTALLED) {
        for (uid_t uid : uids) {
            // Clean up all permission information for the related uid if all the
            // packages related to it are uninstalled.
            mPrivilegedUser.erase(uid);
            Status ret = mUidPermissionMap.deleteValue(uid);
            if (!isOk(ret) && ret.code() != ENOENT) {
                ALOGE("Failed to clean up the permission for %u: %s", uid, strerror(ret.code()));
            }
        }
        return;
    }

    bool privileged = (permission & INetd::PERMISSION_UPDATE_DEVICE_STATS);

    for (uid_t uid : uids) {
        if (privileged) {
            mPrivilegedUser.insert(uid);
        } else {
            mPrivilegedUser.erase(uid);
        }

        // The map stores all the permissions that the UID has, except if the only permission
        // the UID has is the INTERNET permission, then the UID should not appear in the map.
        if (permission != INetd::PERMISSION_INTERNET) {
            Status ret = mUidPermissionMap.writeValue(uid, permission, BPF_ANY);
            if (!isOk(ret)) {
                ALOGE("Failed to set permission: %s of uid(%u) to permission map: %s",
                      UidPermissionTypeToString(permission).c_str(), uid, strerror(ret.code()));
            }
        } else {
            Status ret = mUidPermissionMap.deleteValue(uid);
            if (!isOk(ret) && ret.code() != ENOENT) {
                ALOGE("Failed to remove uid %u from permission map: %s", uid, strerror(ret.code()));
            }
        }
    }
}

std::string getMapStatus(const base::unique_fd& map_fd, const char* path) {
    if (map_fd.get() < 0) {
        return StringPrintf("map fd lost");
    }
    if (access(path, F_OK) != 0) {
        return StringPrintf("map not pinned to location: %s", path);
    }
    return StringPrintf("OK");
}

// NOLINTNEXTLINE(google-runtime-references): grandfathered pass by non-const reference
void dumpBpfMap(const std::string& mapName, DumpWriter& dw, const std::string& header) {
    dw.blankline();
    dw.println("%s:", mapName.c_str());
    if (!header.empty()) {
        dw.println(header);
    }
}

void TrafficController::dump(int fd, bool verbose __unused) {
    std::lock_guard guard(mMutex);
    DumpWriter dw(fd);

    ScopedIndent indentTop(dw);
    dw.println("TrafficController");

    ScopedIndent indentPreBpfModule(dw);

    dw.blankline();
    dw.println("mCookieTagMap status: %s",
               getMapStatus(mCookieTagMap.getMap(), COOKIE_TAG_MAP_PATH).c_str());
    dw.println("mUidCounterSetMap status: %s",
               getMapStatus(mUidCounterSetMap.getMap(), UID_COUNTERSET_MAP_PATH).c_str());
    dw.println("mAppUidStatsMap status: %s",
               getMapStatus(mAppUidStatsMap.getMap(), APP_UID_STATS_MAP_PATH).c_str());
    dw.println("mStatsMapA status: %s",
               getMapStatus(mStatsMapA.getMap(), STATS_MAP_A_PATH).c_str());
    dw.println("mStatsMapB status: %s",
               getMapStatus(mStatsMapB.getMap(), STATS_MAP_B_PATH).c_str());
    dw.println("mIfaceIndexNameMap status: %s",
               getMapStatus(mIfaceIndexNameMap.getMap(), IFACE_INDEX_NAME_MAP_PATH).c_str());
    dw.println("mIfaceStatsMap status: %s",
               getMapStatus(mIfaceStatsMap.getMap(), IFACE_STATS_MAP_PATH).c_str());
    dw.println("mConfigurationMap status: %s",
               getMapStatus(mConfigurationMap.getMap(), CONFIGURATION_MAP_PATH).c_str());
    dw.println("mUidOwnerMap status: %s",
               getMapStatus(mUidOwnerMap.getMap(), UID_OWNER_MAP_PATH).c_str());
}

}  // namespace net
}  // namespace android
