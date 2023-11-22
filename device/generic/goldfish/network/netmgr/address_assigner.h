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

#pragma once

#include "interface_state.h"

#include <string>
#include <unordered_map>

#include <netinet/in.h>
#include <stdint.h>

class AddressAssigner {
public:
    AddressAssigner(const char* interfacePrefix,
                    in_addr_t baseAddress,
                    uint32_t maskLength);

    void onInterfaceState(unsigned int index,
                          const char* name,
                          InterfaceState state);

private:
    void assignAddress(const char* interfaceName);
    void freeAddress(const char* interfaceName);

    void setIpAddress(const char* interfaceName, in_addr_t address);
    bool setAddress(int socket,
                    int type,
                    const char* interfaceName,
                    in_addr_t address);
    void removeIpAddress(const char* interfaceName, in_addr_t address);

    const char* mInterfacePrefix;
    size_t mPrefixLength;
    in_addr_t mBaseAddress;
    uint32_t mMaskLength;
    std::unordered_map<in_addr_t, std::string> mUsedIpAddresses;
};

