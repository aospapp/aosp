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

#define LOG_TAG "ResolverController"
#define DBG 0

#include <algorithm>
#include <cstdlib>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <utility>
#include <vector>
#include <cutils/log.h>
#include <net/if.h>
#include <sys/socket.h>
#include <netdb.h>

#include <arpa/inet.h>
// NOTE: <resolv_netid.h> is a private C library header that provides
//       declarations for _resolv_set_nameservers_for_net and
//       _resolv_flush_cache_for_net
#include <resolv_netid.h>
#include <resolv_params.h>
#include <resolv_stats.h>

#include <android-base/strings.h>
#include <android/net/INetd.h>

#include "DumpWriter.h"
#include "NetdConstants.h"
#include "ResolverController.h"
#include "ResolverStats.h"
#include "dns/DnsTlsTransport.h"

namespace android {
namespace net {

namespace {

struct PrivateDnsServer {
    PrivateDnsServer(const sockaddr_storage& ss) : ss(ss) {}
    const sockaddr_storage ss;
    // For now, the fingerprints are always SHA-256.  This is the only digest algorithm
    // that is mandatory to support (https://tools.ietf.org/html/rfc7858#section-4.2).
    std::set<std::vector<uint8_t>> fingerprints;
};

// This comparison ignores ports and fingerprints.
bool operator<(const PrivateDnsServer& x, const PrivateDnsServer& y) {
    if (x.ss.ss_family != y.ss.ss_family) {
        return x.ss.ss_family < y.ss.ss_family;
    }
    // Same address family.  Compare IP addresses.
    if (x.ss.ss_family == AF_INET) {
        const sockaddr_in& x_sin = reinterpret_cast<const sockaddr_in&>(x.ss);
        const sockaddr_in& y_sin = reinterpret_cast<const sockaddr_in&>(y.ss);
        return x_sin.sin_addr.s_addr < y_sin.sin_addr.s_addr;
    } else if (x.ss.ss_family == AF_INET6) {
        const sockaddr_in6& x_sin6 = reinterpret_cast<const sockaddr_in6&>(x.ss);
        const sockaddr_in6& y_sin6 = reinterpret_cast<const sockaddr_in6&>(y.ss);
        return std::memcmp(x_sin6.sin6_addr.s6_addr, y_sin6.sin6_addr.s6_addr, 16) < 0;
    }
    return false;  // Unknown address type.  This is an error.
}

bool parseServer(const char* server, in_port_t port, sockaddr_storage* parsed) {
    sockaddr_in* sin = reinterpret_cast<sockaddr_in*>(parsed);
    if (inet_pton(AF_INET, server, &(sin->sin_addr)) == 1) {
        // IPv4 parse succeeded, so it's IPv4
        sin->sin_family = AF_INET;
        sin->sin_port = htons(port);
        return true;
    }
    sockaddr_in6* sin6 = reinterpret_cast<sockaddr_in6*>(parsed);
    if (inet_pton(AF_INET6, server, &(sin6->sin6_addr)) == 1){
        // IPv6 parse succeeded, so it's IPv6.
        sin6->sin6_family = AF_INET6;
        sin6->sin6_port = htons(port);
        return true;
    }
    if (DBG) {
        ALOGW("Failed to parse server address: %s", server);
    }
    return false;
}

// Structure for tracking the entire set of known Private DNS servers.
std::mutex privateDnsLock;
typedef std::set<PrivateDnsServer> PrivateDnsSet;
PrivateDnsSet privateDnsServers;

// Structure for tracking the validation status of servers on a specific netid.
// Servers that fail validation are removed from the tracker, and can be retried.
enum class Validation : bool { in_process, success };
typedef std::map<PrivateDnsServer, Validation> PrivateDnsTracker;
std::map<unsigned, PrivateDnsTracker> privateDnsTransports;

PrivateDnsSet parseServers(const char** servers, int numservers, in_port_t port) {
    PrivateDnsSet set;
    for (int i = 0; i < numservers; ++i) {
        sockaddr_storage parsed;
        if (parseServer(servers[i], port, &parsed)) {
            set.insert(parsed);
        }
    }
    return set;
}

void checkPrivateDnsProviders(const unsigned netId, const char** servers, int numservers) {
    if (DBG) {
        ALOGD("checkPrivateDnsProviders(%u)", netId);
    }

    std::lock_guard<std::mutex> guard(privateDnsLock);
    if (privateDnsServers.empty()) {
        return;
    }

    // First compute the intersection of the servers to check with the
    // servers that are permitted to use DNS over TLS.  The intersection
    // will contain the port number to be used for Private DNS.
    PrivateDnsSet serversToCheck = parseServers(servers, numservers, 53);
    PrivateDnsSet intersection;
    std::set_intersection(privateDnsServers.begin(), privateDnsServers.end(),
        serversToCheck.begin(), serversToCheck.end(),
        std::inserter(intersection, intersection.begin()));
    if (intersection.empty()) {
        return;
    }

    auto netPair = privateDnsTransports.find(netId);
    if (netPair == privateDnsTransports.end()) {
        // New netId
        bool added;
        std::tie(netPair, added) = privateDnsTransports.emplace(netId, PrivateDnsTracker());
        if (!added) {
            ALOGE("Memory error while checking private DNS for netId %d", netId);
            return;
        }
    }

    auto& tracker = netPair->second;
    for (const auto& privateServer : intersection) {
        if (tracker.count(privateServer) != 0) {
            continue;
        }
        tracker[privateServer] = Validation::in_process;
        std::thread validate_thread([privateServer, netId] {
            // validateDnsTlsServer() is a blocking call that performs network operations.
            // It can take milliseconds to minutes, up to the SYN retry limit.
            bool success = validateDnsTlsServer(netId,
                    privateServer.ss, privateServer.fingerprints);
            std::lock_guard<std::mutex> guard(privateDnsLock);
            auto netPair = privateDnsTransports.find(netId);
            if (netPair == privateDnsTransports.end()) {
                ALOGW("netId %u was erased during private DNS validation", netId);
                return;
            }
            auto& tracker = netPair->second;
            if (privateDnsServers.count(privateServer) == 0) {
                ALOGW("Server was removed during private DNS validation");
                success = false;
            }
            if (success) {
                tracker[privateServer] = Validation::success;
            } else {
                // Validation failure is expected if a user is on a captive portal.
                // TODO: Trigger a second validation attempt after captive portal login
                // succeeds.
                tracker.erase(privateServer);
            }
        });
        validate_thread.detach();
    }
}

void clearPrivateDnsProviders(unsigned netId) {
    if (DBG) {
        ALOGD("clearPrivateDnsProviders(%u)", netId);
    }
    std::lock_guard<std::mutex> guard(privateDnsLock);
    privateDnsTransports.erase(netId);
}

}  // namespace

int ResolverController::setDnsServers(unsigned netId, const char* searchDomains,
        const char** servers, int numservers, const __res_params* params) {
    if (DBG) {
        ALOGD("setDnsServers netId = %u\n", netId);
    }
    checkPrivateDnsProviders(netId, servers, numservers);
    return -_resolv_set_nameservers_for_net(netId, servers, numservers, searchDomains, params);
}

bool ResolverController::shouldUseTls(unsigned netId, const sockaddr_storage& insecureServer,
        sockaddr_storage* secureServer, std::set<std::vector<uint8_t>>* fingerprints) {
    // This mutex is on the critical path of every DNS lookup that doesn't hit a local cache.
    // If the overhead of mutex acquisition proves too high, we could reduce it by maintaining
    // an atomic_int32_t counter of validated connections, and returning early if it's zero.
    std::lock_guard<std::mutex> guard(privateDnsLock);
    const auto netPair = privateDnsTransports.find(netId);
    if (netPair == privateDnsTransports.end()) {
        return false;
    }
    const auto& tracker = netPair->second;
    const auto serverPair = tracker.find(insecureServer);
    if (serverPair == tracker.end() || serverPair->second != Validation::success) {
        return false;
    }
    const auto& validatedServer = serverPair->first;
    *secureServer = validatedServer.ss;
    *fingerprints = validatedServer.fingerprints;
    return true;
}

int ResolverController::clearDnsServers(unsigned netId) {
    _resolv_set_nameservers_for_net(netId, NULL, 0, "", NULL);
    if (DBG) {
        ALOGD("clearDnsServers netId = %u\n", netId);
    }
    clearPrivateDnsProviders(netId);
    return 0;
}

int ResolverController::flushDnsCache(unsigned netId) {
    if (DBG) {
        ALOGD("flushDnsCache netId = %u\n", netId);
    }

    _resolv_flush_cache_for_net(netId);

    return 0;
}

int ResolverController::getDnsInfo(unsigned netId, std::vector<std::string>* servers,
        std::vector<std::string>* domains, __res_params* params,
        std::vector<android::net::ResolverStats>* stats) {
    using android::net::ResolverStats;
    using android::net::INetd;
    static_assert(ResolverStats::STATS_SUCCESSES == INetd::RESOLVER_STATS_SUCCESSES &&
            ResolverStats::STATS_ERRORS == INetd::RESOLVER_STATS_ERRORS &&
            ResolverStats::STATS_TIMEOUTS == INetd::RESOLVER_STATS_TIMEOUTS &&
            ResolverStats::STATS_INTERNAL_ERRORS == INetd::RESOLVER_STATS_INTERNAL_ERRORS &&
            ResolverStats::STATS_RTT_AVG == INetd::RESOLVER_STATS_RTT_AVG &&
            ResolverStats::STATS_LAST_SAMPLE_TIME == INetd::RESOLVER_STATS_LAST_SAMPLE_TIME &&
            ResolverStats::STATS_USABLE == INetd::RESOLVER_STATS_USABLE &&
            ResolverStats::STATS_COUNT == INetd::RESOLVER_STATS_COUNT,
            "AIDL and ResolverStats.h out of sync");
    int nscount = -1;
    sockaddr_storage res_servers[MAXNS];
    int dcount = -1;
    char res_domains[MAXDNSRCH][MAXDNSRCHPATH];
    __res_stats res_stats[MAXNS];
    servers->clear();
    domains->clear();
    *params = __res_params{};
    stats->clear();
    int revision_id = android_net_res_stats_get_info_for_net(netId, &nscount, res_servers, &dcount,
            res_domains, params, res_stats);

    // If the netId is unknown (which can happen for valid net IDs for which no DNS servers have
    // yet been configured), there is no revision ID. In this case there is no data to return.
    if (revision_id < 0) {
        return 0;
    }

    // Verify that the returned data is sane.
    if (nscount < 0 || nscount > MAXNS || dcount < 0 || dcount > MAXDNSRCH) {
        ALOGE("%s: nscount=%d, dcount=%d", __FUNCTION__, nscount, dcount);
        return -ENOTRECOVERABLE;
    }

    // Determine which servers are considered usable by the resolver.
    bool valid_servers[MAXNS];
    std::fill_n(valid_servers, MAXNS, false);
    android_net_res_stats_get_usable_servers(params, res_stats, nscount, valid_servers);

    // Convert the server sockaddr structures to std::string.
    stats->resize(nscount);
    for (int i = 0 ; i < nscount ; ++i) {
        char hbuf[NI_MAXHOST];
        int rv = getnameinfo(reinterpret_cast<const sockaddr*>(&res_servers[i]),
                sizeof(res_servers[i]), hbuf, sizeof(hbuf), nullptr, 0, NI_NUMERICHOST);
        std::string server_str;
        if (rv == 0) {
            server_str.assign(hbuf);
        } else {
            ALOGE("getnameinfo() failed for server #%d: %s", i, gai_strerror(rv));
            server_str.assign("<invalid>");
        }
        servers->push_back(std::move(server_str));
        android::net::ResolverStats& cur_stats = (*stats)[i];
        android_net_res_stats_aggregate(&res_stats[i], &cur_stats.successes, &cur_stats.errors,
                &cur_stats.timeouts, &cur_stats.internal_errors, &cur_stats.rtt_avg,
                &cur_stats.last_sample_time);
        cur_stats.usable = valid_servers[i];
    }

    // Convert the stack-allocated search domain strings to std::string.
    for (int i = 0 ; i < dcount ; ++i) {
        domains->push_back(res_domains[i]);
    }
    return 0;
}

int ResolverController::setResolverConfiguration(int32_t netId,
        const std::vector<std::string>& servers, const std::vector<std::string>& domains,
        const std::vector<int32_t>& params) {
    using android::net::INetd;
    if (params.size() != INetd::RESOLVER_PARAMS_COUNT) {
        ALOGE("%s: params.size()=%zu", __FUNCTION__, params.size());
        return -EINVAL;
    }

    auto server_count = std::min<size_t>(MAXNS, servers.size());
    std::vector<const char*> server_ptrs;
    for (size_t i = 0 ; i < server_count ; ++i) {
        server_ptrs.push_back(servers[i].c_str());
    }

    std::string domains_str;
    if (!domains.empty()) {
        domains_str = domains[0];
        for (size_t i = 1 ; i < domains.size() ; ++i) {
            domains_str += " " + domains[i];
        }
    }

    __res_params res_params;
    res_params.sample_validity = params[INetd::RESOLVER_PARAMS_SAMPLE_VALIDITY];
    res_params.success_threshold = params[INetd::RESOLVER_PARAMS_SUCCESS_THRESHOLD];
    res_params.min_samples = params[INetd::RESOLVER_PARAMS_MIN_SAMPLES];
    res_params.max_samples = params[INetd::RESOLVER_PARAMS_MAX_SAMPLES];

    return setDnsServers(netId, domains_str.c_str(), server_ptrs.data(), server_ptrs.size(),
            &res_params);
}

int ResolverController::getResolverInfo(int32_t netId, std::vector<std::string>* servers,
        std::vector<std::string>* domains, std::vector<int32_t>* params,
        std::vector<int32_t>* stats) {
    using android::net::ResolverStats;
    using android::net::INetd;
    __res_params res_params;
    std::vector<ResolverStats> res_stats;
    int ret = getDnsInfo(netId, servers, domains, &res_params, &res_stats);
    if (ret != 0) {
        return ret;
    }

    // Serialize the information for binder.
    ResolverStats::encodeAll(res_stats, stats);

    params->resize(INetd::RESOLVER_PARAMS_COUNT);
    (*params)[INetd::RESOLVER_PARAMS_SAMPLE_VALIDITY] = res_params.sample_validity;
    (*params)[INetd::RESOLVER_PARAMS_SUCCESS_THRESHOLD] = res_params.success_threshold;
    (*params)[INetd::RESOLVER_PARAMS_MIN_SAMPLES] = res_params.min_samples;
    (*params)[INetd::RESOLVER_PARAMS_MAX_SAMPLES] = res_params.max_samples;
    return 0;
}

void ResolverController::dump(DumpWriter& dw, unsigned netId) {
    // No lock needed since Bionic's resolver locks all accessed data structures internally.
    using android::net::ResolverStats;
    std::vector<std::string> servers;
    std::vector<std::string> domains;
    __res_params params;
    std::vector<ResolverStats> stats;
    time_t now = time(nullptr);
    int rv = getDnsInfo(netId, &servers, &domains, &params, &stats);
    dw.incIndent();
    if (rv != 0) {
        dw.println("getDnsInfo() failed for netid %u", netId);
    } else {
        if (servers.empty()) {
            dw.println("No DNS servers defined");
        } else {
            dw.println("DNS servers: # IP (total, successes, errors, timeouts, internal errors, "
                    "RTT avg, last sample)");
            dw.incIndent();
            for (size_t i = 0 ; i < servers.size() ; ++i) {
                if (i < stats.size()) {
                    const ResolverStats& s = stats[i];
                    int total = s.successes + s.errors + s.timeouts + s.internal_errors;
                    if (total > 0) {
                        int time_delta = (s.last_sample_time > 0) ? now - s.last_sample_time : -1;
                        dw.println("%s (%d, %d, %d, %d, %d, %dms, %ds)%s", servers[i].c_str(),
                                total, s.successes, s.errors, s.timeouts, s.internal_errors,
                                s.rtt_avg, time_delta, s.usable ? "" : " BROKEN");
                    } else {
                        dw.println("%s <no data>", servers[i].c_str());
                    }
                } else {
                    dw.println("%s <no stats>", servers[i].c_str());
                }
            }
            dw.decIndent();
        }
        if (domains.empty()) {
            dw.println("No search domains defined");
        } else {
            std::string domains_str = android::base::Join(domains, ", ");
            dw.println("search domains: %s", domains_str.c_str());
        }
        if (params.sample_validity != 0) {
            dw.println("DNS parameters: sample validity = %us, success threshold = %u%%, "
                    "samples (min, max) = (%u, %u)", params.sample_validity,
                    static_cast<unsigned>(params.success_threshold),
                    static_cast<unsigned>(params.min_samples),
                    static_cast<unsigned>(params.max_samples));
        }
    }
    dw.decIndent();
}

int ResolverController::addPrivateDnsServer(const std::string& server, int32_t port,
        const std::string& fingerprintAlgorithm,
        const std::set<std::vector<uint8_t>>& fingerprints) {
    using android::net::INetd;
    if (fingerprintAlgorithm.empty()) {
        if (!fingerprints.empty()) {
            return INetd::PRIVATE_DNS_BAD_FINGERPRINT;
        }
    } else if (fingerprintAlgorithm.compare("SHA-256") == 0) {
        if (fingerprints.empty()) {
            return INetd::PRIVATE_DNS_BAD_FINGERPRINT;
        }
        for (const auto& fingerprint : fingerprints) {
            if (fingerprint.size() != SHA256_SIZE) {
                return INetd::PRIVATE_DNS_BAD_FINGERPRINT;
            }
        }
    } else {
        return INetd::PRIVATE_DNS_UNKNOWN_ALGORITHM;
    }
    if (port <= 0 || port > 0xFFFF) {
        return INetd::PRIVATE_DNS_BAD_PORT;
    }
    sockaddr_storage parsed;
    if (!parseServer(server.c_str(), port, &parsed)) {
        return INetd::PRIVATE_DNS_BAD_ADDRESS;
    }
    PrivateDnsServer privateServer(parsed);
    privateServer.fingerprints = fingerprints;
    std::lock_guard<std::mutex> guard(privateDnsLock);
    // Ensure we overwrite any previous matching server.  This is necessary because equality is
    // based only on the IP address, not the port or fingerprints.
    privateDnsServers.erase(privateServer);
    privateDnsServers.insert(privateServer);
    return INetd::PRIVATE_DNS_SUCCESS;
}

int ResolverController::removePrivateDnsServer(const std::string& server) {
    using android::net::INetd;
    sockaddr_storage parsed;
    if (!parseServer(server.c_str(), 0, &parsed)) {
        return INetd::PRIVATE_DNS_BAD_ADDRESS;
    }
    std::lock_guard<std::mutex> guard(privateDnsLock);
    privateDnsServers.erase(parsed);
    for (auto& pair : privateDnsTransports) {
        pair.second.erase(parsed);
    }
    return INetd::PRIVATE_DNS_SUCCESS;
}

}  // namespace net
}  // namespace android
