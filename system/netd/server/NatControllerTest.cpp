/*
 * Copyright 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * NatControllerTest.cpp - unit tests for NatController.cpp
 */

#include <string>
#include <vector>

#include <fcntl.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/socket.h>

#include <gtest/gtest.h>

#include <android-base/stringprintf.h>
#include <android-base/strings.h>

#include "NatController.h"
#include "IptablesBaseTest.h"

using android::base::Join;
using android::base::StringPrintf;

class NatControllerTest : public IptablesBaseTest {
public:
    NatControllerTest() {
        NatController::execFunction = fake_android_fork_exec;
        NatController::iptablesRestoreFunction = fakeExecIptablesRestore;
    }

protected:
    NatController mNatCtrl;

    int setDefaults() {
        return mNatCtrl.setDefaults();
    }

    const ExpectedIptablesCommands FLUSH_COMMANDS = {
        { V4,   "*filter\n"
                ":natctrl_FORWARD -\n"
                "-A natctrl_FORWARD -j DROP\n"
                "COMMIT\n"
                "*nat\n"
                ":natctrl_nat_POSTROUTING -\n"
                "COMMIT\n" },
        { V6,   "*filter\n"
                ":natctrl_FORWARD -\n"
                "COMMIT\n"
                "*raw\n"
                ":natctrl_raw_PREROUTING -\n"
                "COMMIT\n" },
    };

    const ExpectedIptablesCommands SETUP_COMMANDS = {
        { V4,   "*filter\n"
                ":natctrl_FORWARD -\n"
                "-A natctrl_FORWARD -j DROP\n"
                "COMMIT\n"
                "*nat\n"
                ":natctrl_nat_POSTROUTING -\n"
                "COMMIT\n" },
        { V6,   "*filter\n"
                ":natctrl_FORWARD -\n"
                "COMMIT\n"
                "*raw\n"
                ":natctrl_raw_PREROUTING -\n"
                "COMMIT\n" },
        { V4,   "*mangle\n"
                "-A natctrl_mangle_FORWARD -p tcp --tcp-flags SYN SYN "
                    "-j TCPMSS --clamp-mss-to-pmtu\n"
                "COMMIT\n" },
        { V4V6, "*filter\n"
                ":natctrl_tether_counters -\n"
                "COMMIT\n" },
    };

    ExpectedIptablesCommands firstNatCommands(const char *extIf) {
        std::string v4Cmd = StringPrintf(
            "*nat\n"
            "-A natctrl_nat_POSTROUTING -o %s -j MASQUERADE\n"
            "COMMIT\n", extIf);
        std::string v6Cmd =
            "*filter\n"
            "-A natctrl_FORWARD -g natctrl_tether_counters\n"
            "COMMIT\n";
        return {
            { V4, v4Cmd },
            { V6, v6Cmd },
        };
    }

    ExpectedIptablesCommands startNatCommands(const char *intIf, const char *extIf) {
        std::string rpfilterCmd = StringPrintf(
            "*raw\n"
            "-A natctrl_raw_PREROUTING -i %s -m rpfilter --invert ! -s fe80::/64 -j DROP\n"
            "COMMIT\n", intIf);

        std::vector<std::string> v4Cmds = {
            "*filter",
            StringPrintf("-A natctrl_FORWARD -i %s -o %s -m state --state"
                         " ESTABLISHED,RELATED -g natctrl_tether_counters", extIf, intIf),
            StringPrintf("-A natctrl_FORWARD -i %s -o %s -m state --state INVALID -j DROP",
                         intIf, extIf),
            StringPrintf("-A natctrl_FORWARD -i %s -o %s -g natctrl_tether_counters",
                         intIf, extIf),
            StringPrintf("-A natctrl_tether_counters -i %s -o %s -j RETURN", intIf, extIf),
            StringPrintf("-A natctrl_tether_counters -i %s -o %s -j RETURN", extIf, intIf),
            "-D natctrl_FORWARD -j DROP",
            "-A natctrl_FORWARD -j DROP",
            "COMMIT\n",
        };

        std::vector<std::string> v6Cmds = {
            "*filter",
            StringPrintf("-A natctrl_tether_counters -i %s -o %s -j RETURN", intIf, extIf),
            StringPrintf("-A natctrl_tether_counters -i %s -o %s -j RETURN", extIf, intIf),
            "COMMIT\n",
        };

        return {
            { V6, rpfilterCmd },
            { V4, Join(v4Cmds, '\n') },
            { V6, Join(v6Cmds, '\n') },
        };
    }

    ExpectedIptablesCommands stopNatCommands(const char *intIf, const char *extIf) {
        std::string rpfilterCmd = StringPrintf(
            "*raw\n"
            "-D natctrl_raw_PREROUTING -i %s -m rpfilter --invert ! -s fe80::/64 -j DROP\n"
            "COMMIT\n", intIf);

        std::vector<std::string> v4Cmds = {
            "*filter",
            StringPrintf("-D natctrl_FORWARD -i %s -o %s -m state --state"
                         " ESTABLISHED,RELATED -g natctrl_tether_counters", extIf, intIf),
            StringPrintf("-D natctrl_FORWARD -i %s -o %s -m state --state INVALID -j DROP",
                         intIf, extIf),
            StringPrintf("-D natctrl_FORWARD -i %s -o %s -g natctrl_tether_counters",
                         intIf, extIf),
            "COMMIT\n",
        };

        return {
            { V6, rpfilterCmd },
            { V4, Join(v4Cmds, '\n') },
        };

    }
};

TEST_F(NatControllerTest, TestSetupIptablesHooks) {
    mNatCtrl.setupIptablesHooks();
    expectIptablesRestoreCommands(SETUP_COMMANDS);
}

TEST_F(NatControllerTest, TestSetDefaults) {
    setDefaults();
    expectIptablesRestoreCommands(FLUSH_COMMANDS);
}

TEST_F(NatControllerTest, TestAddAndRemoveNat) {
    ExpectedIptablesCommands expected;
    ExpectedIptablesCommands setupFirstNatCommands = firstNatCommands("rmnet0");
    ExpectedIptablesCommands startFirstNatCommands = startNatCommands("wlan0", "rmnet0");
    expected.insert(expected.end(), setupFirstNatCommands.begin(), setupFirstNatCommands.end());
    expected.insert(expected.end(), startFirstNatCommands.begin(), startFirstNatCommands.end());
    mNatCtrl.enableNat("wlan0", "rmnet0");
    expectIptablesRestoreCommands(expected);

    ExpectedIptablesCommands startOtherNat = startNatCommands("usb0", "rmnet0");
    mNatCtrl.enableNat("usb0", "rmnet0");
    expectIptablesRestoreCommands(startOtherNat);

    ExpectedIptablesCommands stopOtherNat = stopNatCommands("wlan0", "rmnet0");
    mNatCtrl.disableNat("wlan0", "rmnet0");
    expectIptablesRestoreCommands(stopOtherNat);

    expected = stopNatCommands("usb0", "rmnet0");
    expected.insert(expected.end(), FLUSH_COMMANDS.begin(), FLUSH_COMMANDS.end());
    mNatCtrl.disableNat("usb0", "rmnet0");
    expectIptablesRestoreCommands(expected);
}
