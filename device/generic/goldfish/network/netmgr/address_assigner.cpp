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

#include "log.h"

#include <errno.h>
#include <net/if.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

AddressAssigner::AddressAssigner(const char* interfacePrefix,
                                 in_addr_t baseAddress,
                                 uint32_t maskLength) :
    mInterfacePrefix(interfacePrefix),
    mPrefixLength(strlen(interfacePrefix)),
    mBaseAddress(baseAddress),
    mMaskLength(maskLength) {

}

void AddressAssigner::onInterfaceState(unsigned int /*index*/,
                                       const char* name,
                                       InterfaceState state) {
    if (strncmp(name, mInterfacePrefix, mPrefixLength) != 0) {
        // The interface does not match the prefix, ignore this change
        return;
    }

    switch (state) {
        case InterfaceState::Up:
            assignAddress(name);
            break;
        case InterfaceState::Down:
            freeAddress(name);
            break;
    }
}

void AddressAssigner::assignAddress(const char* interfaceName) {
    if (mMaskLength > 30) {
        // The mask length is too long, we can't assign enough IP addresses from
        // this. A maximum of 30 bits is supported, leaving 4 remaining
        // addresses, one is network, one is broadcast, one is gateway, one is
        // client.
        return;
    }
    // Each subnet will have an amount of bits available to it that equals
    // 32-bits - <mask length>, so if mask length is 29 there will be 3
    // remaining bits for each subnet. Then the distance between each subnet
    // is 2 to the power of this number, in our example 2^3 = 8 so to get to the
    // next subnet we add 8 to the network address.
    in_addr_t increment = 1 << (32 - mMaskLength);

    // Convert the address to host byte-order first so we can do math on it.
    for (in_addr_t addr = ntohl(mBaseAddress); true; addr += increment) {
        // Take the reference of this lookup, that way we can assign a name to
        // it if needed.
        auto& usedName = mUsedIpAddresses[addr];
        if (usedName.empty()) {
            // This address is not in use, let's use it
            usedName = interfaceName;
            // Make sure we convert back to network byte-order when setting it.
            setIpAddress(interfaceName, htonl(addr));
            break;
        }
    }
}

void AddressAssigner::setIpAddress(const char* interfaceName,
                                   in_addr_t address) {
    int sock = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_IP);
    if (sock == -1) {
        LOGE("AddressAssigner unable to open IP socket: %s", strerror(errno));
        return;
    }
    if (!setAddress(sock, SIOCSIFADDR, interfaceName, address)) {
        LOGE("AddressAssigner unable to set interface address: %s",
             strerror(errno));
        ::close(sock);
        return;
    }

    // The netmask is the inverted maximum value of the lower bits. That is if
    // the mask length is 29 then the the largest value of the 3 (32-29) lowest
    // bits is 7 (2^3 - 1) (111 binary). Inverting this value gives the netmask
    // because it excludes those three bits and sets every other bit.
    in_addr_t netmask = htonl(~((1 << (32 - mMaskLength)) - 1));

    if (!setAddress(sock, SIOCSIFNETMASK, interfaceName, netmask)) {
        LOGE("AddressAssigner unable to set interface netmask: %s",
             strerror(errno));
        ::close(sock);
        return;
    }

    // The broadcast address is just the assigned address with all bits outside
    // of the netmask set to one.
    in_addr_t broadcast = address | ~netmask;

    if (!setAddress(sock, SIOCSIFBRDADDR, interfaceName, broadcast)) {
        LOGE("AddressAssigner unable to set interface broadcast: %s",
             strerror(errno));
        ::close(sock);
        return;
    }
    ::close(sock);
}

bool AddressAssigner::setAddress(int sock,
                                 int type,
                                 const char* interfaceName,
                                 in_addr_t address) {
    struct ifreq request;
    memset(&request, 0, sizeof(request));
    strlcpy(request.ifr_name, interfaceName, sizeof(request.ifr_name));
    auto addr = reinterpret_cast<struct sockaddr_in*>(&request.ifr_addr);
    addr->sin_family = AF_INET;
    addr->sin_addr.s_addr = address;

    if (::ioctl(sock, type, &request) != 0) {
        return false;
    }
    return true;
}

void AddressAssigner::freeAddress(const char* interfaceName) {
    for (auto& ipName : mUsedIpAddresses) {
        if (ipName.second == interfaceName) {
            // This is the one, free it up for future use
            mUsedIpAddresses.erase(ipName.first);
        }
    }
}

