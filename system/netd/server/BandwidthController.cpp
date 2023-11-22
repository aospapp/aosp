/*
 * Copyright (C) 2011 The Android Open Source Project
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

// #define LOG_NDEBUG 0

/*
 * The CommandListener, FrameworkListener don't allow for
 * multiple calls in parallel to reach the BandwidthController.
 * If they ever were to allow it, then netd/ would need some tweaking.
 */

#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <vector>

#define __STDC_FORMAT_MACROS 1
#include <inttypes.h>

#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>

#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <linux/pkt_sched.h>

#include "android-base/stringprintf.h"
#include "android-base/strings.h"
#define LOG_TAG "BandwidthController"
#include <cutils/log.h>
#include <cutils/properties.h>
#include <logwrap/logwrap.h>

#include <netdutils/Syscalls.h>
#include "BandwidthController.h"
#include "FirewallController.h" /* For makeCriticalCommands */
#include "NatController.h" /* For LOCAL_TETHER_COUNTERS_CHAIN */
#include "NetdConstants.h"
#include "ResponseCode.h"

/* Alphabetical */
#define ALERT_IPT_TEMPLATE "%s %s -m quota2 ! --quota %" PRId64" --name %s\n"
const char BandwidthController::LOCAL_INPUT[] = "bw_INPUT";
const char BandwidthController::LOCAL_FORWARD[] = "bw_FORWARD";
const char BandwidthController::LOCAL_OUTPUT[] = "bw_OUTPUT";
const char BandwidthController::LOCAL_RAW_PREROUTING[] = "bw_raw_PREROUTING";
const char BandwidthController::LOCAL_MANGLE_POSTROUTING[] = "bw_mangle_POSTROUTING";

auto BandwidthController::iptablesRestoreFunction = execIptablesRestoreWithOutput;

using android::base::Join;
using android::base::StringAppendF;
using android::base::StringPrintf;
using android::netdutils::StatusOr;
using android::netdutils::UniqueFile;

namespace {

const char ALERT_GLOBAL_NAME[] = "globalAlert";
const int  MAX_IPT_OUTPUT_LINE_LEN = 256;
const std::string NEW_CHAIN_COMMAND = "-N ";
const std::string GET_TETHER_STATS_COMMAND = StringPrintf(
    "*filter\n"
    "-nvx -L %s\n"
    "COMMIT\n", NatController::LOCAL_TETHER_COUNTERS_CHAIN);

const char NAUGHTY_CHAIN[] = "bw_penalty_box";
const char NICE_CHAIN[] = "bw_happy_box";

/**
 * Some comments about the rules:
 *  * Ordering
 *    - when an interface is marked as costly it should be INSERTED into the INPUT/OUTPUT chains.
 *      E.g. "-I bw_INPUT -i rmnet0 --jump costly"
 *    - quota'd rules in the costly chain should be before bw_penalty_box lookups.
 *    - the qtaguid counting is done at the end of the bw_INPUT/bw_OUTPUT user chains.
 *
 * * global quota vs per interface quota
 *   - global quota for all costly interfaces uses a single costly chain:
 *    . initial rules
 *      iptables -N bw_costly_shared
 *      iptables -I bw_INPUT -i iface0 --jump bw_costly_shared
 *      iptables -I bw_OUTPUT -o iface0 --jump bw_costly_shared
 *      iptables -I bw_costly_shared -m quota \! --quota 500000 \
 *          --jump REJECT --reject-with icmp-net-prohibited
 *      iptables -A bw_costly_shared --jump bw_penalty_box
 *      iptables -A bw_penalty_box --jump bw_happy_box
 *      iptables -A bw_happy_box --jump bw_data_saver
 *
 *    . adding a new iface to this, E.g.:
 *      iptables -I bw_INPUT -i iface1 --jump bw_costly_shared
 *      iptables -I bw_OUTPUT -o iface1 --jump bw_costly_shared
 *
 *   - quota per interface. This is achieve by having "costly" chains per quota.
 *     E.g. adding a new costly interface iface0 with its own quota:
 *      iptables -N bw_costly_iface0
 *      iptables -I bw_INPUT -i iface0 --jump bw_costly_iface0
 *      iptables -I bw_OUTPUT -o iface0 --jump bw_costly_iface0
 *      iptables -A bw_costly_iface0 -m quota \! --quota 500000 \
 *          --jump REJECT --reject-with icmp-port-unreachable
 *      iptables -A bw_costly_iface0 --jump bw_penalty_box
 *
 * * Penalty box, happy box and data saver.
 *   - bw_penalty box is a blacklist of apps that are rejected.
 *   - bw_happy_box is a whitelist of apps. It always includes all system apps
 *   - bw_data_saver implements data usage restrictions.
 *   - Via the UI the user can add and remove apps from the whitelist and
 *     blacklist, and turn on/off data saver.
 *   - The blacklist takes precedence over the whitelist and the whitelist
 *     takes precedence over data saver.
 *
 * * bw_penalty_box handling:
 *  - only one bw_penalty_box for all interfaces
 *   E.g  Adding an app:
 *    iptables -I bw_penalty_box -m owner --uid-owner app_3 \
 *        --jump REJECT --reject-with icmp-port-unreachable
 *
 * * bw_happy_box handling:
 *  - The bw_happy_box comes after the penalty box.
 *   E.g  Adding a happy app,
 *    iptables -I bw_happy_box -m owner --uid-owner app_3 \
 *        --jump RETURN
 *
 * * bw_data_saver handling:
 *  - The bw_data_saver comes after the happy box.
 *    Enable data saver:
 *      iptables -R 1 bw_data_saver --jump REJECT --reject-with icmp-port-unreachable
 *    Disable data saver:
 *      iptables -R 1 bw_data_saver --jump RETURN
 */

const std::string COMMIT_AND_CLOSE = "COMMIT\n";
const std::string HAPPY_BOX_WHITELIST_COMMAND = StringPrintf(
    "-I bw_happy_box -m owner --uid-owner %d-%d --jump RETURN", 0, MAX_SYSTEM_UID);

static const std::vector<std::string> IPT_FLUSH_COMMANDS = {
    /*
     * Cleanup rules.
     * Should normally include bw_costly_<iface>, but we rely on the way they are setup
     * to allow coexistance.
     */
    "*filter",
    ":bw_INPUT -",
    ":bw_OUTPUT -",
    ":bw_FORWARD -",
    ":bw_happy_box -",
    ":bw_penalty_box -",
    ":bw_data_saver -",
    ":bw_costly_shared -",
    "COMMIT",
    "*raw",
    ":bw_raw_PREROUTING -",
    "COMMIT",
    "*mangle",
    ":bw_mangle_POSTROUTING -",
    COMMIT_AND_CLOSE
};

static const std::vector<std::string> IPT_BASIC_ACCOUNTING_COMMANDS = {
    "*filter",
    "-A bw_INPUT -m owner --socket-exists", /* This is a tracking rule. */
    "-A bw_OUTPUT -m owner --socket-exists", /* This is a tracking rule. */
    "-A bw_costly_shared --jump bw_penalty_box",
    "-A bw_penalty_box --jump bw_happy_box",
    "-A bw_happy_box --jump bw_data_saver",
    "-A bw_data_saver -j RETURN",
    HAPPY_BOX_WHITELIST_COMMAND,
    "COMMIT",

    "*raw",
    "-A bw_raw_PREROUTING -m owner --socket-exists", /* This is a tracking rule. */
    "COMMIT",

    "*mangle",
    "-A bw_mangle_POSTROUTING -m owner --socket-exists", /* This is a tracking rule. */
    COMMIT_AND_CLOSE
};

std::vector<std::string> toStrVec(int num, char* strs[]) {
    std::vector<std::string> tmp;
    for (int i = 0; i < num; ++i) {
        tmp.emplace_back(strs[i]);
    }
    return tmp;
}

}  // namespace

BandwidthController::BandwidthController() {
}

void BandwidthController::flushCleanTables(bool doClean) {
    /* Flush and remove the bw_costly_<iface> tables */
    flushExistingCostlyTables(doClean);

    std::string commands = Join(IPT_FLUSH_COMMANDS, '\n');
    iptablesRestoreFunction(V4V6, commands, nullptr);
}

int BandwidthController::setupIptablesHooks() {
    /* flush+clean is allowed to fail */
    flushCleanTables(true);
    return 0;
}

int BandwidthController::enableBandwidthControl(bool force) {
    char value[PROPERTY_VALUE_MAX];

    if (!force) {
            property_get("persist.bandwidth.enable", value, "1");
            if (!strcmp(value, "0"))
                    return 0;
    }

    /* Let's pretend we started from scratch ... */
    mSharedQuotaIfaces.clear();
    mQuotaIfaces.clear();
    mGlobalAlertBytes = 0;
    mGlobalAlertTetherCount = 0;
    mSharedQuotaBytes = mSharedAlertBytes = 0;

    flushCleanTables(false);
    std::string commands = Join(IPT_BASIC_ACCOUNTING_COMMANDS, '\n');
    return iptablesRestoreFunction(V4V6, commands, nullptr);
}

int BandwidthController::disableBandwidthControl() {

    flushCleanTables(false);
    return 0;
}

std::string BandwidthController::makeDataSaverCommand(IptablesTarget target, bool enable) {
    std::string cmd;
    const char *chainName = "bw_data_saver";
    const char *op = jumpToString(enable ? IptJumpReject : IptJumpReturn);
    std::string criticalCommands = enable ?
            FirewallController::makeCriticalCommands(target, chainName) : "";
    StringAppendF(&cmd,
        "*filter\n"
        ":%s -\n"
        "%s"
        "-A %s%s\n"
        "COMMIT\n", chainName, criticalCommands.c_str(), chainName, op);
    return cmd;
}

int BandwidthController::enableDataSaver(bool enable) {
    int ret = iptablesRestoreFunction(V4, makeDataSaverCommand(V4, enable), nullptr);
    ret |= iptablesRestoreFunction(V6, makeDataSaverCommand(V6, enable), nullptr);
    return ret;
}

int BandwidthController::addNaughtyApps(int numUids, char *appUids[]) {
    return manipulateSpecialApps(toStrVec(numUids, appUids), NAUGHTY_CHAIN,
                                 IptJumpReject, IptOpInsert);
}

int BandwidthController::removeNaughtyApps(int numUids, char *appUids[]) {
    return manipulateSpecialApps(toStrVec(numUids, appUids), NAUGHTY_CHAIN,
                                 IptJumpReject, IptOpDelete);
}

int BandwidthController::addNiceApps(int numUids, char *appUids[]) {
    return manipulateSpecialApps(toStrVec(numUids, appUids), NICE_CHAIN,
                                 IptJumpReturn, IptOpInsert);
}

int BandwidthController::removeNiceApps(int numUids, char *appUids[]) {
    return manipulateSpecialApps(toStrVec(numUids, appUids), NICE_CHAIN,
                                 IptJumpReturn, IptOpDelete);
}

int BandwidthController::manipulateSpecialApps(const std::vector<std::string>& appStrUids,
                                               const std::string& chain, IptJumpOp jumpHandling,
                                               IptOp op) {
    std::string cmd = "*filter\n";
    for (const auto& appStrUid : appStrUids) {
        StringAppendF(&cmd, "%s %s -m owner --uid-owner %s%s\n", opToString(op), chain.c_str(),
                      appStrUid.c_str(), jumpToString(jumpHandling));
    }
    StringAppendF(&cmd, "COMMIT\n");
    return iptablesRestoreFunction(V4V6, cmd, nullptr);
}

int BandwidthController::setInterfaceSharedQuota(const std::string& iface, int64_t maxBytes) {
    int res = 0;
    std::string quotaCmd;
    constexpr char cost[] = "shared";
    constexpr char chain[] = "bw_costly_shared";

    if (!maxBytes) {
        /* Don't talk about -1, deprecate it. */
        ALOGE("Invalid bytes value. 1..max_int64.");
        return -1;
    }
    if (!isIfaceName(iface))
        return -1;

    if (maxBytes == -1) {
        return removeInterfaceSharedQuota(iface);
    }

    auto it = mSharedQuotaIfaces.find(iface);

    if (it == mSharedQuotaIfaces.end()) {
        const int ruleInsertPos = (mGlobalAlertBytes) ? 2 : 1;
        std::vector<std::string> cmds = {
            "*filter",
            StringPrintf("-I bw_INPUT %d -i %s --jump %s", ruleInsertPos, iface.c_str(), chain),
            StringPrintf("-I bw_OUTPUT %d -o %s --jump %s", ruleInsertPos, iface.c_str(), chain),
            StringPrintf("-A bw_FORWARD -o %s --jump %s", iface.c_str(), chain),
        };
        if (mSharedQuotaIfaces.empty()) {
            cmds.push_back(StringPrintf("-I %s -m quota2 ! --quota %" PRId64
                                        " --name %s --jump REJECT",
                                        chain, maxBytes, cost));
        }
        cmds.push_back("COMMIT\n");

        res |= iptablesRestoreFunction(V4V6, Join(cmds, "\n"), nullptr);
        if (res) {
            ALOGE("Failed set quota rule");
            removeInterfaceSharedQuota(iface);
            return -1;
        }
        mSharedQuotaBytes = maxBytes;
        mSharedQuotaIfaces.insert(iface);
    }

    if (maxBytes != mSharedQuotaBytes) {
        res |= updateQuota(cost, maxBytes);
        if (res) {
            ALOGE("Failed update quota for %s", cost);
            removeInterfaceSharedQuota(iface);
            return -1;
        }
        mSharedQuotaBytes = maxBytes;
    }
    return 0;
}

/* It will also cleanup any shared alerts */
int BandwidthController::removeInterfaceSharedQuota(const std::string& iface) {
    constexpr char cost[] = "shared";
    constexpr char chain[] = "bw_costly_shared";

    if (!isIfaceName(iface))
        return -1;

    auto it = mSharedQuotaIfaces.find(iface);

    if (it == mSharedQuotaIfaces.end()) {
        ALOGE("No such iface %s to delete", iface.c_str());
        return -1;
    }

    std::vector<std::string> cmds = {
        "*filter",
        StringPrintf("-D bw_INPUT -i %s --jump %s", iface.c_str(), chain),
        StringPrintf("-D bw_OUTPUT -o %s --jump %s", iface.c_str(), chain),
        StringPrintf("-D bw_FORWARD -o %s --jump %s", iface.c_str(), chain),
    };
    if (mSharedQuotaIfaces.size() == 1) {
        cmds.push_back(StringPrintf("-D %s -m quota2 ! --quota %" PRIu64
                                    " --name %s --jump REJECT",
                                    chain, mSharedQuotaBytes, cost));
    }
    cmds.push_back("COMMIT\n");

    if (iptablesRestoreFunction(V4V6, Join(cmds, "\n"), nullptr) != 0) {
        ALOGE("Failed to remove shared quota on %s", iface.c_str());
        return -1;
    }

    int res = 0;
    mSharedQuotaIfaces.erase(it);
    if (mSharedQuotaIfaces.empty()) {
        mSharedQuotaBytes = 0;
        if (mSharedAlertBytes) {
            res = removeSharedAlert();
            if (res == 0) {
                mSharedAlertBytes = 0;
            }
        }
    }

    return res;

}

int BandwidthController::setInterfaceQuota(const std::string& iface, int64_t maxBytes) {
    const std::string& cost = iface;

    if (!isIfaceName(iface))
        return -1;

    if (!maxBytes) {
        /* Don't talk about -1, deprecate it. */
        ALOGE("Invalid bytes value. 1..max_int64.");
        return -1;
    }
    if (maxBytes == -1) {
        return removeInterfaceQuota(iface);
    }

    /* Insert ingress quota. */
    auto it = mQuotaIfaces.find(iface);

    if (it != mQuotaIfaces.end()) {
        if (updateQuota(cost, maxBytes) != 0) {
            ALOGE("Failed update quota for %s", iface.c_str());
            removeInterfaceQuota(iface);
            return -1;
        }
        it->second.quota = maxBytes;
        return 0;
    }

    const std::string chain = "bw_costly_" + iface;
    const int ruleInsertPos = (mGlobalAlertBytes) ? 2 : 1;
    std::vector<std::string> cmds = {
        "*filter",
        StringPrintf(":%s -", chain.c_str()),
        StringPrintf("-A %s -j bw_penalty_box", chain.c_str()),
        StringPrintf("-I bw_INPUT %d -i %s --jump %s", ruleInsertPos, iface.c_str(),
                     chain.c_str()),
        StringPrintf("-I bw_OUTPUT %d -o %s --jump %s", ruleInsertPos, iface.c_str(),
                     chain.c_str()),
        StringPrintf("-A bw_FORWARD -o %s --jump %s", iface.c_str(), chain.c_str()),
        StringPrintf("-A %s -m quota2 ! --quota %" PRId64 " --name %s --jump REJECT",
                     chain.c_str(), maxBytes, cost.c_str()),
        "COMMIT\n",
    };

    if (iptablesRestoreFunction(V4V6, Join(cmds, "\n"), nullptr) != 0) {
        ALOGE("Failed set quota rule");
        removeInterfaceQuota(iface);
        return -1;
    }

    mQuotaIfaces[iface] = QuotaInfo{maxBytes, 0};
    return 0;
}

int BandwidthController::getInterfaceSharedQuota(int64_t *bytes) {
    return getInterfaceQuota("shared", bytes);
}

int BandwidthController::getInterfaceQuota(const std::string& iface, int64_t* bytes) {
    const auto& sys = android::netdutils::sSyscalls.get();
    const std::string fname = "/proc/net/xt_quota/" + iface;

    if (!isIfaceName(iface)) return -1;

    StatusOr<UniqueFile> file = sys.fopen(fname, "re");
    if (!isOk(file)) {
        ALOGE("Reading quota %s failed (%s)", iface.c_str(), toString(file).c_str());
        return -1;
    }
    auto rv = sys.fscanf(file.value().get(), "%" SCNd64, bytes);
    if (!isOk(rv)) {
        ALOGE("Reading quota %s failed (%s)", iface.c_str(), toString(rv).c_str());
        return -1;
    }
    ALOGV("Read quota res=%d bytes=%" PRId64, rv.value(), *bytes);
    return rv.value() == 1 ? 0 : -1;
}

int BandwidthController::removeInterfaceQuota(const std::string& iface) {
    if (!isIfaceName(iface))
        return -1;

    auto it = mQuotaIfaces.find(iface);

    if (it == mQuotaIfaces.end()) {
        ALOGE("No such iface %s to delete", iface.c_str());
        return -1;
    }

    const std::string chain = "bw_costly_" + iface;
    std::vector<std::string> cmds = {
        "*filter",
        StringPrintf("-D bw_INPUT -i %s --jump %s", iface.c_str(), chain.c_str()),
        StringPrintf("-D bw_OUTPUT -o %s --jump %s", iface.c_str(), chain.c_str()),
        StringPrintf("-D bw_FORWARD -o %s --jump %s", iface.c_str(), chain.c_str()),
        StringPrintf("-F %s", chain.c_str()),
        StringPrintf("-X %s", chain.c_str()),
        "COMMIT\n",
    };

    const int res = iptablesRestoreFunction(V4V6, Join(cmds, "\n"), nullptr);

    if (res == 0) {
        mQuotaIfaces.erase(it);
    }

    return res;
}

int BandwidthController::updateQuota(const std::string& quotaName, int64_t bytes) {
    const auto& sys = android::netdutils::sSyscalls.get();
    const std::string fname = "/proc/net/xt_quota/" + quotaName;

    if (!isIfaceName(quotaName)) {
        ALOGE("updateQuota: Invalid quotaName \"%s\"", quotaName.c_str());
        return -1;
    }

    StatusOr<UniqueFile> file = sys.fopen(fname, "we");
    if (!isOk(file)) {
        ALOGE("Updating quota %s failed (%s)", quotaName.c_str(), toString(file).c_str());
        return -1;
    }
    sys.fprintf(file.value().get(), "%" PRId64 "\n", bytes);
    return 0;
}

int BandwidthController::runIptablesAlertCmd(IptOp op, const std::string& alertName,
                                             int64_t bytes) {
    const char *opFlag = opToString(op);
    std::string alertQuotaCmd = "*filter\n";

    // TODO: consider using an alternate template for the delete that does not include the --quota
    // value. This code works because the --quota value is ignored by deletes
    StringAppendF(&alertQuotaCmd, ALERT_IPT_TEMPLATE, opFlag, "bw_INPUT", bytes,
                  alertName.c_str());
    StringAppendF(&alertQuotaCmd, ALERT_IPT_TEMPLATE, opFlag, "bw_OUTPUT", bytes,
                  alertName.c_str());
    StringAppendF(&alertQuotaCmd, "COMMIT\n");

    return iptablesRestoreFunction(V4V6, alertQuotaCmd, nullptr);
}

int BandwidthController::runIptablesAlertFwdCmd(IptOp op, const std::string& alertName,
                                                int64_t bytes) {
    const char *opFlag = opToString(op);
    std::string alertQuotaCmd = "*filter\n";
    StringAppendF(&alertQuotaCmd, ALERT_IPT_TEMPLATE, opFlag, "bw_FORWARD", bytes,
                  alertName.c_str());
    StringAppendF(&alertQuotaCmd, "COMMIT\n");

    return iptablesRestoreFunction(V4V6, alertQuotaCmd, nullptr);
}

int BandwidthController::setGlobalAlert(int64_t bytes) {
    const char *alertName = ALERT_GLOBAL_NAME;
    int res = 0;

    if (!bytes) {
        ALOGE("Invalid bytes value. 1..max_int64.");
        return -1;
    }
    if (mGlobalAlertBytes) {
        res = updateQuota(alertName, bytes);
    } else {
        res = runIptablesAlertCmd(IptOpInsert, alertName, bytes);
        if (mGlobalAlertTetherCount) {
            ALOGV("setGlobalAlert for %d tether", mGlobalAlertTetherCount);
            res |= runIptablesAlertFwdCmd(IptOpInsert, alertName, bytes);
        }
    }
    mGlobalAlertBytes = bytes;
    return res;
}

int BandwidthController::setGlobalAlertInForwardChain() {
    const char *alertName = ALERT_GLOBAL_NAME;
    int res = 0;

    mGlobalAlertTetherCount++;
    ALOGV("setGlobalAlertInForwardChain(): %d tether", mGlobalAlertTetherCount);

    /*
     * If there is no globalAlert active we are done.
     * If there is an active globalAlert but this is not the 1st
     * tether, we are also done.
     */
    if (!mGlobalAlertBytes || mGlobalAlertTetherCount != 1) {
        return 0;
    }

    /* We only add the rule if this was the 1st tether added. */
    res = runIptablesAlertFwdCmd(IptOpInsert, alertName, mGlobalAlertBytes);
    return res;
}

int BandwidthController::removeGlobalAlert() {

    const char *alertName = ALERT_GLOBAL_NAME;
    int res = 0;

    if (!mGlobalAlertBytes) {
        ALOGE("No prior alert set");
        return -1;
    }
    res = runIptablesAlertCmd(IptOpDelete, alertName, mGlobalAlertBytes);
    if (mGlobalAlertTetherCount) {
        res |= runIptablesAlertFwdCmd(IptOpDelete, alertName, mGlobalAlertBytes);
    }
    mGlobalAlertBytes = 0;
    return res;
}

int BandwidthController::removeGlobalAlertInForwardChain() {
    int res = 0;
    const char *alertName = ALERT_GLOBAL_NAME;

    if (!mGlobalAlertTetherCount) {
        ALOGE("No prior alert set");
        return -1;
    }

    mGlobalAlertTetherCount--;
    /*
     * If there is no globalAlert active we are done.
     * If there is an active globalAlert but there are more
     * tethers, we are also done.
     */
    if (!mGlobalAlertBytes || mGlobalAlertTetherCount >= 1) {
        return 0;
    }

    /* We only detete the rule if this was the last tether removed. */
    res = runIptablesAlertFwdCmd(IptOpDelete, alertName, mGlobalAlertBytes);
    return res;
}

int BandwidthController::setSharedAlert(int64_t bytes) {
    if (!mSharedQuotaBytes) {
        ALOGE("Need to have a prior shared quota set to set an alert");
        return -1;
    }
    if (!bytes) {
        ALOGE("Invalid bytes value. 1..max_int64.");
        return -1;
    }
    return setCostlyAlert("shared", bytes, &mSharedAlertBytes);
}

int BandwidthController::removeSharedAlert() {
    return removeCostlyAlert("shared", &mSharedAlertBytes);
}

int BandwidthController::setInterfaceAlert(const std::string& iface, int64_t bytes) {
    if (!isIfaceName(iface)) {
        ALOGE("setInterfaceAlert: Invalid iface \"%s\"", iface.c_str());
        return -1;
    }

    if (!bytes) {
        ALOGE("Invalid bytes value. 1..max_int64.");
        return -1;
    }
    auto it = mQuotaIfaces.find(iface);

    if (it == mQuotaIfaces.end()) {
        ALOGE("Need to have a prior interface quota set to set an alert");
        return -1;
    }

    return setCostlyAlert(iface, bytes, &it->second.alert);
}

int BandwidthController::removeInterfaceAlert(const std::string& iface) {
    if (!isIfaceName(iface)) {
        ALOGE("removeInterfaceAlert: Invalid iface \"%s\"", iface.c_str());
        return -1;
    }

    auto it = mQuotaIfaces.find(iface);

    if (it == mQuotaIfaces.end()) {
        ALOGE("No prior alert set for interface %s", iface.c_str());
        return -1;
    }

    return removeCostlyAlert(iface, &it->second.alert);
}

int BandwidthController::setCostlyAlert(const std::string& costName, int64_t bytes,
                                        int64_t* alertBytes) {
    int res = 0;

    if (!isIfaceName(costName)) {
        ALOGE("setCostlyAlert: Invalid costName \"%s\"", costName.c_str());
        return -1;
    }

    if (!bytes) {
        ALOGE("Invalid bytes value. 1..max_int64.");
        return -1;
    }

    std::string alertName = costName + "Alert";
    std::string chainName = "bw_costly_" + costName;
    if (*alertBytes) {
        res = updateQuota(alertName, *alertBytes);
    } else {
        std::vector<std::string> commands = {
            "*filter\n",
            StringPrintf(ALERT_IPT_TEMPLATE, "-A", chainName.c_str(), bytes, alertName.c_str()),
            "COMMIT\n"
        };
        res = iptablesRestoreFunction(V4V6, Join(commands, ""), nullptr);
        if (res) {
            ALOGE("Failed to set costly alert for %s", costName.c_str());
        }
    }
    if (res == 0) {
        *alertBytes = bytes;
    }
    return res;
}

int BandwidthController::removeCostlyAlert(const std::string& costName, int64_t* alertBytes) {
    if (!isIfaceName(costName)) {
        ALOGE("removeCostlyAlert: Invalid costName \"%s\"", costName.c_str());
        return -1;
    }

    if (!*alertBytes) {
        ALOGE("No prior alert set for %s alert", costName.c_str());
        return -1;
    }

    std::string alertName = costName + "Alert";
    std::string chainName = "bw_costly_" + costName;
    std::vector<std::string> commands = {
        "*filter\n",
        StringPrintf(ALERT_IPT_TEMPLATE, "-D", chainName.c_str(), *alertBytes, alertName.c_str()),
        "COMMIT\n"
    };
    if (iptablesRestoreFunction(V4V6, Join(commands, ""), nullptr) != 0) {
        ALOGE("Failed to remove costly alert %s", costName.c_str());
        return -1;
    }

    *alertBytes = 0;
    return 0;
}

void BandwidthController::addStats(TetherStatsList& statsList, const TetherStats& stats) {
    for (TetherStats& existing : statsList) {
        if (existing.addStatsIfMatch(stats)) {
            return;
        }
    }
    // No match. Insert a new interface pair.
    statsList.push_back(stats);
}

/*
 * Parse the ptks and bytes out of:
 *   Chain natctrl_tether_counters (4 references)
 *       pkts      bytes target     prot opt in     out     source               destination
 *         26     2373 RETURN     all  --  wlan0  rmnet0  0.0.0.0/0            0.0.0.0/0
 *         27     2002 RETURN     all  --  rmnet0 wlan0   0.0.0.0/0            0.0.0.0/0
 *       1040   107471 RETURN     all  --  bt-pan rmnet0  0.0.0.0/0            0.0.0.0/0
 *       1450  1708806 RETURN     all  --  rmnet0 bt-pan  0.0.0.0/0            0.0.0.0/0
 * or:
 *   Chain natctrl_tether_counters (0 references)
 *       pkts      bytes target     prot opt in     out     source               destination
 *          0        0 RETURN     all      wlan0  rmnet_data0  ::/0                 ::/0
 *          0        0 RETURN     all      rmnet_data0 wlan0   ::/0                 ::/0
 *
 * It results in an error if invoked and no tethering counter rules exist. The constraint
 * helps detect complete parsing failure.
 */
int BandwidthController::addForwardChainStats(const TetherStats& filter,
                                              TetherStatsList& statsList,
                                              const std::string& statsOutput,
                                              std::string &extraProcessingInfo) {
    int res;
    std::string statsLine;
    char iface0[MAX_IPT_OUTPUT_LINE_LEN];
    char iface1[MAX_IPT_OUTPUT_LINE_LEN];
    char rest[MAX_IPT_OUTPUT_LINE_LEN];

    TetherStats stats;
    const char *buffPtr;
    int64_t packets, bytes;
    int statsFound = 0;

    bool filterPair = filter.intIface[0] && filter.extIface[0];

    ALOGV("filter: %s",  filter.getStatsLine().c_str());

    stats = filter;

    std::stringstream stream(statsOutput);
    while (std::getline(stream, statsLine, '\n')) {
        buffPtr = statsLine.c_str();

        /* Clean up, so a failed parse can still print info */
        iface0[0] = iface1[0] = rest[0] = packets = bytes = 0;
        if (strstr(buffPtr, "0.0.0.0")) {
            // IPv4 has -- indicating what to do with fragments...
            //       26     2373 RETURN     all  --  wlan0  rmnet0  0.0.0.0/0            0.0.0.0/0
            res = sscanf(buffPtr, "%" SCNd64" %" SCNd64" RETURN all -- %s %s 0.%s",
                    &packets, &bytes, iface0, iface1, rest);
        } else {
            // ... but IPv6 does not.
            //       26     2373 RETURN     all      wlan0  rmnet0  ::/0                 ::/0
            res = sscanf(buffPtr, "%" SCNd64" %" SCNd64" RETURN all %s %s ::/%s",
                    &packets, &bytes, iface0, iface1, rest);
        }
        ALOGV("parse res=%d iface0=<%s> iface1=<%s> pkts=%" PRId64" bytes=%" PRId64" rest=<%s> orig line=<%s>", res,
             iface0, iface1, packets, bytes, rest, buffPtr);
        extraProcessingInfo += buffPtr;
        extraProcessingInfo += "\n";

        if (res != 5) {
            continue;
        }
        /*
         * The following assumes that the 1st rule has in:extIface out:intIface,
         * which is what NatController sets up.
         * If not filtering, the 1st match rx, and sets up the pair for the tx side.
         */
        if (filter.intIface[0] && filter.extIface[0]) {
            if (filter.intIface == iface0 && filter.extIface == iface1) {
                ALOGV("2Filter RX iface_in=%s iface_out=%s rx_bytes=%" PRId64" rx_packets=%" PRId64" ", iface0, iface1, bytes, packets);
                stats.rxPackets = packets;
                stats.rxBytes = bytes;
            } else if (filter.intIface == iface1 && filter.extIface == iface0) {
                ALOGV("2Filter TX iface_in=%s iface_out=%s rx_bytes=%" PRId64" rx_packets=%" PRId64" ", iface0, iface1, bytes, packets);
                stats.txPackets = packets;
                stats.txBytes = bytes;
            }
        } else if (filter.intIface[0] || filter.extIface[0]) {
            if (filter.intIface == iface0 || filter.extIface == iface1) {
                ALOGV("1Filter RX iface_in=%s iface_out=%s rx_bytes=%" PRId64" rx_packets=%" PRId64" ", iface0, iface1, bytes, packets);
                stats.intIface = iface0;
                stats.extIface = iface1;
                stats.rxPackets = packets;
                stats.rxBytes = bytes;
            } else if (filter.intIface == iface1 || filter.extIface == iface0) {
                ALOGV("1Filter TX iface_in=%s iface_out=%s rx_bytes=%" PRId64" rx_packets=%" PRId64" ", iface0, iface1, bytes, packets);
                stats.intIface = iface1;
                stats.extIface = iface0;
                stats.txPackets = packets;
                stats.txBytes = bytes;
            }
        } else /* if (!filter.intFace[0] && !filter.extIface[0]) */ {
            if (!stats.intIface[0]) {
                ALOGV("0Filter RX iface_in=%s iface_out=%s rx_bytes=%" PRId64" rx_packets=%" PRId64" ", iface0, iface1, bytes, packets);
                stats.intIface = iface0;
                stats.extIface = iface1;
                stats.rxPackets = packets;
                stats.rxBytes = bytes;
            } else if (stats.intIface == iface1 && stats.extIface == iface0) {
                ALOGV("0Filter TX iface_in=%s iface_out=%s rx_bytes=%" PRId64" rx_packets=%" PRId64" ", iface0, iface1, bytes, packets);
                stats.txPackets = packets;
                stats.txBytes = bytes;
            }
        }
        if (stats.rxBytes != -1 && stats.txBytes != -1) {
            ALOGV("rx_bytes=%" PRId64" tx_bytes=%" PRId64" filterPair=%d", stats.rxBytes, stats.txBytes, filterPair);
            addStats(statsList, stats);
            if (filterPair) {
                return 0;
            } else {
                statsFound++;
                stats = filter;
            }
        }
    }

    /* It is always an error to find only one side of the stats. */
    /* It is an error to find nothing when not filtering. */
    if (((stats.rxBytes == -1) != (stats.txBytes == -1)) ||
        (!statsFound && !filterPair)) {
        return -1;
    }
    return 0;
}

std::string BandwidthController::TetherStats::getStatsLine() const {
    std::string msg;
    StringAppendF(&msg, "%s %s %" PRId64" %" PRId64" %" PRId64" %" PRId64, intIface.c_str(),
                  extIface.c_str(), rxBytes, rxPackets, txBytes, txPackets);
    return msg;
}

int BandwidthController::getTetherStats(SocketClient *cli, TetherStats& filter,
                                        std::string &extraProcessingInfo) {
    int res = 0;

    TetherStatsList statsList;

    for (const IptablesTarget target : {V4, V6}) {
        std::string statsString;
        res = iptablesRestoreFunction(target, GET_TETHER_STATS_COMMAND, &statsString);
        if (res != 0) {
            ALOGE("Failed to run %s err=%d", GET_TETHER_STATS_COMMAND.c_str(), res);
            return -1;
        }

        res = addForwardChainStats(filter, statsList, statsString, extraProcessingInfo);
        if (res != 0) {
            return res;
        }
    }

    if (filter.intIface[0] && filter.extIface[0] && statsList.size() == 1) {
        cli->sendMsg(ResponseCode::TetheringStatsResult,
                     statsList[0].getStatsLine().c_str(), false);
    } else {
        for (const auto& stats: statsList) {
            cli->sendMsg(ResponseCode::TetheringStatsListResult,
                         stats.getStatsLine().c_str(), false);
        }
        if (res == 0) {
            cli->sendMsg(ResponseCode::CommandOkay, "Tethering stats list completed", false);
        }
    }

    return res;
}

void BandwidthController::flushExistingCostlyTables(bool doClean) {
    std::string fullCmd = "*filter\n-S\nCOMMIT\n";
    std::string ruleList;

    /* Only lookup ip4 table names as ip6 will have the same tables ... */
    if (int ret = iptablesRestoreFunction(V4, fullCmd, &ruleList)) {
        ALOGE("Failed to list existing costly tables ret=%d", ret);
        return;
    }
    /* ... then flush/clean both ip4 and ip6 iptables. */
    parseAndFlushCostlyTables(ruleList, doClean);
}

void BandwidthController::parseAndFlushCostlyTables(const std::string& ruleList, bool doRemove) {
    std::stringstream stream(ruleList);
    std::string rule;
    std::vector<std::string> clearCommands = { "*filter" };
    std::string chainName;

    // Find and flush all rules starting with "-N bw_costly_<iface>" except "-N bw_costly_shared".
    while (std::getline(stream, rule, '\n')) {
        if (rule.find(NEW_CHAIN_COMMAND) != 0) continue;
        chainName = rule.substr(NEW_CHAIN_COMMAND.size());
        ALOGV("parse chainName=<%s> orig line=<%s>", chainName.c_str(), rule.c_str());

        if (chainName.find("bw_costly_") != 0 || chainName == std::string("bw_costly_shared")) {
            continue;
        }

        clearCommands.push_back(StringPrintf(":%s -", chainName.c_str()));
        if (doRemove) {
            clearCommands.push_back(StringPrintf("-X %s", chainName.c_str()));
        }
    }

    if (clearCommands.size() == 1) {
        // No rules found.
        return;
    }

    clearCommands.push_back("COMMIT\n");
    iptablesRestoreFunction(V4V6, Join(clearCommands, '\n'), nullptr);
}

inline const char *BandwidthController::opToString(IptOp op) {
    switch (op) {
    case IptOpInsert:
        return "-I";
    case IptOpDelete:
        return "-D";
    }
}

inline const char *BandwidthController::jumpToString(IptJumpOp jumpHandling) {
    /*
     * Must be careful what one rejects with, as upper layer protocols will just
     * keep on hammering the device until the number of retries are done.
     * For port-unreachable (default), TCP should consider as an abort (RFC1122).
     */
    switch (jumpHandling) {
    case IptJumpNoAdd:
        return "";
    case IptJumpReject:
        return " --jump REJECT";
    case IptJumpReturn:
        return " --jump RETURN";
    }
}
