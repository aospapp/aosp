/*
 * Copyright 2018, The Android Open Source Project
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

#include "address_assigner.h"
#include "commander.h"
#include "commands/wifi_command.h"
#include "log.h"
#include "monitor.h"
#include "poller.h"
#include "wifi_forwarder.h"

#include <arpa/inet.h>
#include <netinet/in.h>

#include <functional>

static const char kWifiMonitorInterface[] = "hwsim0";

static void usage(const char* name) {
    LOGE("Usage: %s --if-prefix <prefix> --network <ip/mask>", name);
    LOGE("  <prefix> indicates the name of network interfaces to configure.");
    LOGE("  <ip/mask> is the base IP address to assign to the first interface");
    LOGE("  and mask indicates the netmask and broadcast to set.");
    LOGE("  Additionally mask is used to determine the address");
    LOGE("  for the second interface by skipping ahead one subnet");
    LOGE("  and the size of the subnet is indicated by <mask>");
}

static bool parseNetwork(const char* network,
                         in_addr_t* address,
                         uint32_t* mask) {
    const char* divider = strchr(network, '/');
    if (divider == nullptr) {
        LOGE("Network specifier '%s' is missing netmask length", network);
        return false;
    }
    if (divider - network >= INET_ADDRSTRLEN) {
        LOGE("Network specifier '%s' contains an IP address that is too long",
             network);
        return false;
    }

    char buffer[INET_ADDRSTRLEN];
    strlcpy(buffer, network, divider - network + 1);
    struct in_addr addr;
    if (!::inet_aton(buffer, &addr)) {
        // String could not be converted to IP address
        LOGE("Network specifier '%s' contains an invalid IP address '%s'",
             network, buffer);
        return false;
    }

    ++divider;

    char dummy = 0;
    if (sscanf(divider, "%u%c", mask, &dummy) != 1) {
        LOGE("Netork specifier '%s' contains an invalid netmask length '%s'",
             network, divider);
        return false;
    }

    *address = addr.s_addr;
    return true;
}

int main(int argc, char* argv[]) {
    const char* interfacePrefix = nullptr;
    const char* network = nullptr;

    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--if-prefix") == 0 && i + 1 < argc) {
            interfacePrefix = argv[++i];
        } else if (strcmp(argv[i], "--network") == 0 && i + 1 < argc) {
            network = argv[++i];
        } else {
            LOGE("Unknown parameter '%s'", argv[i]);
            usage(argv[0]);
            return 1;
        }
    }

    if (interfacePrefix == nullptr) {
        LOGE("Missing parameter --if-prefix");
    }
    if (network == nullptr) {
        LOGE("Missing parameter --network");
    }
    if (network == nullptr || interfacePrefix == nullptr) {
        usage(argv[0]);
        return 1;
    }

    in_addr_t address = 0;
    uint32_t mask = 0;
    if (!parseNetwork(network, &address, &mask)) {
        return 1;
    }

    AddressAssigner assigner(interfacePrefix, address, mask);
    Monitor monitor;

    monitor.setOnInterfaceState(std::bind(&AddressAssigner::onInterfaceState,
                                          &assigner,
                                          std::placeholders::_1,
                                          std::placeholders::_2,
                                          std::placeholders::_3));

    Result res = monitor.init();
    if (!res) {
        LOGE("%s", res.c_str());
        return 1;
    }

    Commander commander;
    res = commander.init();
    if (!res) {
        LOGE("%s", res.c_str());
        return 1;
    }

    WifiCommand wifiCommand;
    commander.registerCommand("wifi", &wifiCommand);

    WifiForwarder forwarder(kWifiMonitorInterface);
    res = forwarder.init();
    if (!res) {
        LOGE("%s", res.c_str());
        return 1;
    }

    Poller poller;
    poller.addPollable(&monitor);
    poller.addPollable(&commander);
    poller.addPollable(&forwarder);
    return poller.run();
}

