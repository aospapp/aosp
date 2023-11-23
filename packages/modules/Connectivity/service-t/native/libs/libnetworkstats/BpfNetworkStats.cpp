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

#include <inttypes.h>
#include <net/if.h>
#include <string.h>
#include <unordered_set>

#include <utils/Log.h>
#include <utils/misc.h>

#include "android-base/file.h"
#include "android-base/strings.h"
#include "android-base/unique_fd.h"
#include "bpf/BpfMap.h"
#include "netd.h"
#include "netdbpf/BpfNetworkStats.h"

#ifdef LOG_TAG
#undef LOG_TAG
#endif

#define LOG_TAG "BpfNetworkStats"

namespace android {
namespace bpf {

using base::Result;

int bpfGetUidStatsInternal(uid_t uid, Stats* stats,
                           const BpfMap<uint32_t, StatsValue>& appUidStatsMap) {
    auto statsEntry = appUidStatsMap.readValue(uid);
    if (statsEntry.ok()) {
        stats->rxPackets = statsEntry.value().rxPackets;
        stats->txPackets = statsEntry.value().txPackets;
        stats->rxBytes = statsEntry.value().rxBytes;
        stats->txBytes = statsEntry.value().txBytes;
    }
    return (statsEntry.ok() || statsEntry.error().code() == ENOENT) ? 0
                                                                    : -statsEntry.error().code();
}

int bpfGetUidStats(uid_t uid, Stats* stats) {
    static BpfMapRO<uint32_t, StatsValue> appUidStatsMap(APP_UID_STATS_MAP_PATH);
    return bpfGetUidStatsInternal(uid, stats, appUidStatsMap);
}

int bpfGetIfaceStatsInternal(const char* iface, Stats* stats,
                             const BpfMap<uint32_t, StatsValue>& ifaceStatsMap,
                             const BpfMap<uint32_t, IfaceValue>& ifaceNameMap) {
    int64_t unknownIfaceBytesTotal = 0;
    stats->tcpRxPackets = -1;
    stats->tcpTxPackets = -1;
    const auto processIfaceStats =
            [iface, stats, &ifaceNameMap, &unknownIfaceBytesTotal](
                    const uint32_t& key,
                    const BpfMap<uint32_t, StatsValue>& ifaceStatsMap) -> Result<void> {
        char ifname[IFNAMSIZ];
        if (getIfaceNameFromMap(ifaceNameMap, ifaceStatsMap, key, ifname, key,
                                &unknownIfaceBytesTotal)) {
            return Result<void>();
        }
        if (!iface || !strcmp(iface, ifname)) {
            Result<StatsValue> statsEntry = ifaceStatsMap.readValue(key);
            if (!statsEntry.ok()) {
                return statsEntry.error();
            }
            stats->rxPackets += statsEntry.value().rxPackets;
            stats->txPackets += statsEntry.value().txPackets;
            stats->rxBytes += statsEntry.value().rxBytes;
            stats->txBytes += statsEntry.value().txBytes;
        }
        return Result<void>();
    };
    auto res = ifaceStatsMap.iterate(processIfaceStats);
    return res.ok() ? 0 : -res.error().code();
}

int bpfGetIfaceStats(const char* iface, Stats* stats) {
    static BpfMapRO<uint32_t, StatsValue> ifaceStatsMap(IFACE_STATS_MAP_PATH);
    static BpfMapRO<uint32_t, IfaceValue> ifaceIndexNameMap(IFACE_INDEX_NAME_MAP_PATH);
    return bpfGetIfaceStatsInternal(iface, stats, ifaceStatsMap, ifaceIndexNameMap);
}

stats_line populateStatsEntry(const StatsKey& statsKey, const StatsValue& statsEntry,
                              const char* ifname) {
    stats_line newLine;
    strlcpy(newLine.iface, ifname, sizeof(newLine.iface));
    newLine.uid = (int32_t)statsKey.uid;
    newLine.set = (int32_t)statsKey.counterSet;
    newLine.tag = (int32_t)statsKey.tag;
    newLine.rxPackets = statsEntry.rxPackets;
    newLine.txPackets = statsEntry.txPackets;
    newLine.rxBytes = statsEntry.rxBytes;
    newLine.txBytes = statsEntry.txBytes;
    return newLine;
}

int parseBpfNetworkStatsDetailInternal(std::vector<stats_line>& lines,
                                       const BpfMap<StatsKey, StatsValue>& statsMap,
                                       const BpfMap<uint32_t, IfaceValue>& ifaceMap) {
    int64_t unknownIfaceBytesTotal = 0;
    const auto processDetailUidStats =
            [&lines, &unknownIfaceBytesTotal, &ifaceMap](
                    const StatsKey& key,
                    const BpfMap<StatsKey, StatsValue>& statsMap) -> Result<void> {
        char ifname[IFNAMSIZ];
        if (getIfaceNameFromMap(ifaceMap, statsMap, key.ifaceIndex, ifname, key,
                                &unknownIfaceBytesTotal)) {
            return Result<void>();
        }
        Result<StatsValue> statsEntry = statsMap.readValue(key);
        if (!statsEntry.ok()) {
            return base::ResultError(statsEntry.error().message(), statsEntry.error().code());
        }
        stats_line newLine = populateStatsEntry(key, statsEntry.value(), ifname);
        lines.push_back(newLine);
        if (newLine.tag) {
            // account tagged traffic in the untagged stats (for historical reasons?)
            newLine.tag = 0;
            lines.push_back(newLine);
        }
        return Result<void>();
    };
    Result<void> res = statsMap.iterate(processDetailUidStats);
    if (!res.ok()) {
        ALOGE("failed to iterate per uid Stats map for detail traffic stats: %s",
              strerror(res.error().code()));
        return -res.error().code();
    }

    // Since eBPF use hash map to record stats, network stats collected from
    // eBPF will be out of order. And the performance of findIndexHinted in
    // NetworkStats will also be impacted.
    //
    // Furthermore, since the StatsKey contains iface index, the network stats
    // reported to framework would create items with the same iface, uid, tag
    // and set, which causes NetworkStats maps wrong item to subtract.
    //
    // Thus, the stats needs to be properly sorted and grouped before reported.
    groupNetworkStats(lines);
    return 0;
}

int parseBpfNetworkStatsDetail(std::vector<stats_line>* lines) {
    static BpfMapRO<uint32_t, IfaceValue> ifaceIndexNameMap(IFACE_INDEX_NAME_MAP_PATH);
    static BpfMapRO<uint32_t, uint32_t> configurationMap(CONFIGURATION_MAP_PATH);
    static BpfMap<StatsKey, StatsValue> statsMapA(STATS_MAP_A_PATH);
    static BpfMap<StatsKey, StatsValue> statsMapB(STATS_MAP_B_PATH);
    auto configuration = configurationMap.readValue(CURRENT_STATS_MAP_CONFIGURATION_KEY);
    if (!configuration.ok()) {
        ALOGE("Cannot read the old configuration from map: %s",
              configuration.error().message().c_str());
        return -configuration.error().code();
    }
    // The target map for stats reading should be the inactive map, which is opposite
    // from the config value.
    BpfMap<StatsKey, StatsValue> *inactiveStatsMap;
    switch (configuration.value()) {
      case SELECT_MAP_A:
        inactiveStatsMap = &statsMapB;
        break;
      case SELECT_MAP_B:
        inactiveStatsMap = &statsMapA;
        break;
      default:
        ALOGE("%s unknown configuration value: %d", __func__, configuration.value());
        return -EINVAL;
    }

    // It is safe to read and clear the old map now since the
    // networkStatsFactory should call netd to swap the map in advance already.
    // TODO: the above comment feels like it may be obsolete / out of date,
    // since we no longer swap the map via netd binder rpc - though we do
    // still swap it.
    int ret = parseBpfNetworkStatsDetailInternal(*lines, *inactiveStatsMap, ifaceIndexNameMap);
    if (ret) {
        ALOGE("parse detail network stats failed: %s", strerror(errno));
        return ret;
    }

    Result<void> res = inactiveStatsMap->clear();
    if (!res.ok()) {
        ALOGE("Clean up current stats map failed: %s", strerror(res.error().code()));
        return -res.error().code();
    }

    return 0;
}

int parseBpfNetworkStatsDevInternal(std::vector<stats_line>& lines,
                                    const BpfMap<uint32_t, StatsValue>& statsMap,
                                    const BpfMap<uint32_t, IfaceValue>& ifaceMap) {
    int64_t unknownIfaceBytesTotal = 0;
    const auto processDetailIfaceStats = [&lines, &unknownIfaceBytesTotal, &ifaceMap, &statsMap](
                                             const uint32_t& key, const StatsValue& value,
                                             const BpfMap<uint32_t, StatsValue>&) {
        char ifname[IFNAMSIZ];
        if (getIfaceNameFromMap(ifaceMap, statsMap, key, ifname, key, &unknownIfaceBytesTotal)) {
            return Result<void>();
        }
        StatsKey fakeKey = {
                .uid = (uint32_t)UID_ALL,
                .tag = (uint32_t)TAG_NONE,
                .counterSet = (uint32_t)SET_ALL,
        };
        lines.push_back(populateStatsEntry(fakeKey, value, ifname));
        return Result<void>();
    };
    Result<void> res = statsMap.iterateWithValue(processDetailIfaceStats);
    if (!res.ok()) {
        ALOGE("failed to iterate per uid Stats map for detail traffic stats: %s",
              strerror(res.error().code()));
        return -res.error().code();
    }

    groupNetworkStats(lines);
    return 0;
}

int parseBpfNetworkStatsDev(std::vector<stats_line>* lines) {
    static BpfMapRO<uint32_t, IfaceValue> ifaceIndexNameMap(IFACE_INDEX_NAME_MAP_PATH);
    static BpfMapRO<uint32_t, StatsValue> ifaceStatsMap(IFACE_STATS_MAP_PATH);
    return parseBpfNetworkStatsDevInternal(*lines, ifaceStatsMap, ifaceIndexNameMap);
}

void groupNetworkStats(std::vector<stats_line>& lines) {
    if (lines.size() <= 1) return;
    std::sort(lines.begin(), lines.end());

    // Similar to std::unique(), but aggregates the duplicates rather than discarding them.
    size_t currentOutput = 0;
    for (size_t i = 1; i < lines.size(); i++) {
        // note that == operator only compares the 'key' portion: iface/uid/tag/set
        if (lines[currentOutput] == lines[i]) {
            // while += operator only affects the 'data' portion: {rx,tx}{Bytes,Packets}
            lines[currentOutput] += lines[i];
        } else {
            // okay, we're done aggregating the current line, move to the next one
            lines[++currentOutput] = lines[i];
        }
    }

    // possibly shrink the vector - currentOutput is the last line with valid data
    lines.resize(currentOutput + 1);
}

// True if lhs equals to rhs, only compare iface, uid, tag and set.
bool operator==(const stats_line& lhs, const stats_line& rhs) {
    return ((lhs.uid == rhs.uid) && (lhs.tag == rhs.tag) && (lhs.set == rhs.set) &&
            !strncmp(lhs.iface, rhs.iface, sizeof(lhs.iface)));
}

// True if lhs is smaller than rhs, only compare iface, uid, tag and set.
bool operator<(const stats_line& lhs, const stats_line& rhs) {
    int ret = strncmp(lhs.iface, rhs.iface, sizeof(lhs.iface));
    if (ret != 0) return ret < 0;
    if (lhs.uid < rhs.uid) return true;
    if (lhs.uid > rhs.uid) return false;
    if (lhs.tag < rhs.tag) return true;
    if (lhs.tag > rhs.tag) return false;
    if (lhs.set < rhs.set) return true;
    if (lhs.set > rhs.set) return false;
    return false;
}

stats_line& stats_line::operator=(const stats_line& rhs) {
    if (this == &rhs) return *this;

    strlcpy(iface, rhs.iface, sizeof(iface));
    uid = rhs.uid;
    set = rhs.set;
    tag = rhs.tag;
    rxPackets = rhs.rxPackets;
    txPackets = rhs.txPackets;
    rxBytes = rhs.rxBytes;
    txBytes = rhs.txBytes;
    return *this;
}

stats_line& stats_line::operator+=(const stats_line& rhs) {
    rxPackets += rhs.rxPackets;
    txPackets += rhs.txPackets;
    rxBytes += rhs.rxBytes;
    txBytes += rhs.txBytes;
    return *this;
}

}  // namespace bpf
}  // namespace android
