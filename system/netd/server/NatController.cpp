/*
 * Copyright (C) 2008 The Android Open Source Project
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

#define LOG_NDEBUG 0

#include <string>
#include <vector>

#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <arpa/inet.h>
#include <linux/in.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>

#define LOG_TAG "NatController"
#include <android-base/strings.h>
#include <android-base/stringprintf.h>
#include <cutils/log.h>
#include <cutils/properties.h>
#include <logwrap/logwrap.h>

#include "NatController.h"
#include "NetdConstants.h"
#include "RouteController.h"

using android::base::Join;
using android::base::StringPrintf;

const char* NatController::LOCAL_FORWARD = "natctrl_FORWARD";
const char* NatController::LOCAL_MANGLE_FORWARD = "natctrl_mangle_FORWARD";
const char* NatController::LOCAL_NAT_POSTROUTING = "natctrl_nat_POSTROUTING";
const char* NatController::LOCAL_RAW_PREROUTING = "natctrl_raw_PREROUTING";
const char* NatController::LOCAL_TETHER_COUNTERS_CHAIN = "natctrl_tether_counters";

auto NatController::execFunction = android_fork_execvp;
auto NatController::iptablesRestoreFunction = execIptablesRestore;

NatController::NatController() {
}

NatController::~NatController() {
}

struct CommandsAndArgs {
    /* The array size doesn't really matter as the compiler will barf if too many initializers are specified. */
    const char *cmd[32];
    bool checkRes;
};

int NatController::setupIptablesHooks() {
    int res;
    res = setDefaults();
    if (res < 0) {
        return res;
    }

    // Used to limit downstream mss to the upstream pmtu so we don't end up fragmenting every large
    // packet tethered devices send. This is IPv4-only, because in IPv6 we send the MTU in the RA.
    // This is no longer optional and tethering will fail to start if it fails.
    std::string mssRewriteCommand = StringPrintf(
        "*mangle\n"
        "-A %s -p tcp --tcp-flags SYN SYN -j TCPMSS --clamp-mss-to-pmtu\n"
        "COMMIT\n", LOCAL_MANGLE_FORWARD);

    // This is for tethering counters. This chain is reached via --goto, and then RETURNS.
    std::string defaultCommands = StringPrintf(
        "*filter\n"
        ":%s -\n"
        "COMMIT\n", LOCAL_TETHER_COUNTERS_CHAIN);

    res = iptablesRestoreFunction(V4, mssRewriteCommand);
    if (res < 0) {
        return res;
    }

    res = iptablesRestoreFunction(V4V6, defaultCommands);
    if (res < 0) {
        return res;
    }

    ifacePairList.clear();

    return 0;
}

int NatController::setDefaults() {
    std::string v4Cmd = StringPrintf(
        "*filter\n"
        ":%s -\n"
        "-A %s -j DROP\n"
        "COMMIT\n"
        "*nat\n"
        ":%s -\n"
        "COMMIT\n", LOCAL_FORWARD, LOCAL_FORWARD, LOCAL_NAT_POSTROUTING);

    std::string v6Cmd = StringPrintf(
        "*filter\n"
        ":%s -\n"
        "COMMIT\n"
        "*raw\n"
        ":%s -\n"
        "COMMIT\n", LOCAL_FORWARD, LOCAL_RAW_PREROUTING);

    int res = iptablesRestoreFunction(V4, v4Cmd);
    if (res < 0) {
        return res;
    }

    res = iptablesRestoreFunction(V6, v6Cmd);
    if (res < 0) {
        return res;
    }

    natCount = 0;

    return 0;
}

int NatController::enableNat(const char* intIface, const char* extIface) {
    ALOGV("enableNat(intIface=<%s>, extIface=<%s>)",intIface, extIface);

    if (!isIfaceName(intIface) || !isIfaceName(extIface)) {
        errno = ENODEV;
        return -1;
    }

    /* Bug: b/9565268. "enableNat wlan0 wlan0". For now we fail until java-land is fixed */
    if (!strcmp(intIface, extIface)) {
        ALOGE("Duplicate interface specified: %s %s", intIface, extIface);
        errno = EINVAL;
        return -1;
    }

    // add this if we are the first added nat
    if (natCount == 0) {
        std::vector<std::string> v4Cmds = {
            "*nat",
            StringPrintf("-A %s -o %s -j MASQUERADE", LOCAL_NAT_POSTROUTING, extIface),
            "COMMIT\n"
        };

        /*
         * IPv6 tethering doesn't need the state-based conntrack rules, so
         * it unconditionally jumps to the tether counters chain all the time.
         */
        std::vector<std::string> v6Cmds = {
            "*filter",
            StringPrintf("-A %s -g %s", LOCAL_FORWARD, LOCAL_TETHER_COUNTERS_CHAIN),
            "COMMIT\n"
        };

        if (iptablesRestoreFunction(V4, Join(v4Cmds, '\n')) ||
            iptablesRestoreFunction(V6, Join(v6Cmds, '\n'))) {
            ALOGE("Error setting postroute rule: iface=%s", extIface);
            // unwind what's been done, but don't care about success - what more could we do?
            setDefaults();
            return -1;
        }
    }

    if (setForwardRules(true, intIface, extIface) != 0) {
        ALOGE("Error setting forward rules");
        if (natCount == 0) {
            setDefaults();
        }
        errno = ENODEV;
        return -1;
    }

    natCount++;
    return 0;
}

bool NatController::checkTetherCountingRuleExist(const std::string& pair_name) {
    return std::find(ifacePairList.begin(), ifacePairList.end(), pair_name) != ifacePairList.end();
}

/* static */
std::string NatController::makeTetherCountingRule(const char *if1, const char *if2) {
    return StringPrintf("-A %s -i %s -o %s -j RETURN", LOCAL_TETHER_COUNTERS_CHAIN, if1, if2);
}

int NatController::setForwardRules(bool add, const char *intIface, const char *extIface) {
    const char *op = add ? "-A" : "-D";

    std::string rpfilterCmd = StringPrintf(
        "*raw\n"
        "%s %s -i %s -m rpfilter --invert ! -s fe80::/64 -j DROP\n"
        "COMMIT\n", op, LOCAL_RAW_PREROUTING, intIface);
    if (iptablesRestoreFunction(V6, rpfilterCmd) == -1 && add) {
        return -1;
    }

    std::vector<std::string> v4 = {
        "*filter",
        StringPrintf("%s %s -i %s -o %s -m state --state ESTABLISHED,RELATED -g %s",
                     op, LOCAL_FORWARD, extIface, intIface, LOCAL_TETHER_COUNTERS_CHAIN),
        StringPrintf("%s %s -i %s -o %s -m state --state INVALID -j DROP",
                     op, LOCAL_FORWARD, intIface, extIface),
        StringPrintf("%s %s -i %s -o %s -g %s",
                     op, LOCAL_FORWARD, intIface, extIface, LOCAL_TETHER_COUNTERS_CHAIN),
    };

    std::vector<std::string> v6 = {
        "*filter",
    };

    /* We only ever add tethering quota rules so that they stick. */
    std::string pair1 = StringPrintf("%s_%s", intIface, extIface);
    if (add && !checkTetherCountingRuleExist(pair1)) {
        v4.push_back(makeTetherCountingRule(intIface, extIface));
        v6.push_back(makeTetherCountingRule(intIface, extIface));
    }
    std::string pair2 = StringPrintf("%s_%s", extIface, intIface);
    if (add && !checkTetherCountingRuleExist(pair2)) {
        v4.push_back(makeTetherCountingRule(extIface, intIface));
        v6.push_back(makeTetherCountingRule(extIface, intIface));
    }

    // Always make sure the drop rule is at the end.
    // TODO: instead of doing this, consider just rebuilding LOCAL_FORWARD completely from scratch
    // every time, starting with ":natctrl_FORWARD -\n". This method would likely be a bit simpler.
    if (add) {
        v4.push_back(StringPrintf("-D %s -j DROP", LOCAL_FORWARD));
        v4.push_back(StringPrintf("-A %s -j DROP", LOCAL_FORWARD));
    }

    v4.push_back("COMMIT\n");
    v6.push_back("COMMIT\n");

    // We only add IPv6 rules here, never remove them.
    if (iptablesRestoreFunction(V4, Join(v4, '\n')) == -1 ||
        (add && iptablesRestoreFunction(V6, Join(v6, '\n')) == -1)) {
        // unwind what's been done, but don't care about success - what more could we do?
        if (add) {
            setForwardRules(false, intIface, extIface);
        }
        return -1;
    }

    if (add && !checkTetherCountingRuleExist(pair1)) {
        ifacePairList.push_front(pair1);
    }
    if (add && !checkTetherCountingRuleExist(pair2)) {
        ifacePairList.push_front(pair2);
    }

    return 0;
}

int NatController::disableNat(const char* intIface, const char* extIface) {
    if (!isIfaceName(intIface) || !isIfaceName(extIface)) {
        errno = ENODEV;
        return -1;
    }

    setForwardRules(false, intIface, extIface);
    if (--natCount <= 0) {
        // handle decrement to 0 case (do reset to defaults) and erroneous dec below 0
        setDefaults();
    }
    return 0;
}
