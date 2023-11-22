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
#ifndef _BANDWIDTH_CONTROLLER_H
#define _BANDWIDTH_CONTROLLER_H

#include <map>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include <sysutils/SocketClient.h>
#include <utils/RWLock.h>

#include "NetdConstants.h"

class BandwidthController {
public:
    android::RWLock lock;

    class TetherStats {
    public:
        TetherStats() = default;
        TetherStats(std::string intIfn, std::string extIfn,
                int64_t rxB, int64_t rxP,
                int64_t txB, int64_t txP)
                        : intIface(intIfn), extIface(extIfn),
                            rxBytes(rxB), rxPackets(rxP),
                            txBytes(txB), txPackets(txP) {};
        /* Internal interface. Same as NatController's notion. */
        std::string intIface;
        /* External interface. Same as NatController's notion. */
        std::string extIface;
        int64_t rxBytes = -1;
        int64_t rxPackets = -1;
        int64_t txBytes = -1;
        int64_t txPackets = -1;
        /*
         * Allocates a new string representing this:
         * intIface extIface rx_bytes rx_packets tx_bytes tx_packets
         * The caller is responsible for free()'ing the returned ptr.
         */
        std::string getStatsLine() const;

        bool addStatsIfMatch(const TetherStats& other) {
            if (intIface == other.intIface && extIface == other.extIface) {
                rxBytes   += other.rxBytes;
                rxPackets += other.rxPackets;
                txBytes   += other.txBytes;
                txPackets += other.txPackets;
                return true;
            }
            return false;
        }
    };

    BandwidthController();

    int setupIptablesHooks();

    int enableBandwidthControl(bool force);
    int disableBandwidthControl();
    int enableDataSaver(bool enable);

    int setInterfaceSharedQuota(const std::string& iface, int64_t bytes);
    int getInterfaceSharedQuota(int64_t *bytes);
    int removeInterfaceSharedQuota(const std::string& iface);

    int setInterfaceQuota(const std::string& iface, int64_t bytes);
    int getInterfaceQuota(const std::string& iface, int64_t* bytes);
    int removeInterfaceQuota(const std::string& iface);

    int addNaughtyApps(int numUids, char *appUids[]);
    int removeNaughtyApps(int numUids, char *appUids[]);
    int addNiceApps(int numUids, char *appUids[]);
    int removeNiceApps(int numUids, char *appUids[]);

    int setGlobalAlert(int64_t bytes);
    int removeGlobalAlert();
    int setGlobalAlertInForwardChain();
    int removeGlobalAlertInForwardChain();

    int setSharedAlert(int64_t bytes);
    int removeSharedAlert();

    int setInterfaceAlert(const std::string& iface, int64_t bytes);
    int removeInterfaceAlert(const std::string& iface);

    /*
     * For single pair of ifaces, stats should have ifaceIn and ifaceOut initialized.
     * For all pairs, stats should have ifaceIn=ifaceOut="".
     * Sends out to the cli the single stat (TetheringStatsReluts) or a list of stats
     * (TetheringStatsListResult+CommandOkay).
     * Error is to be handled on the outside.
     * It results in an error if invoked and no tethering counter rules exist.
     */
    int getTetherStats(SocketClient *cli, TetherStats &stats, std::string &extraProcessingInfo);

    static const char LOCAL_INPUT[];
    static const char LOCAL_FORWARD[];
    static const char LOCAL_OUTPUT[];
    static const char LOCAL_RAW_PREROUTING[];
    static const char LOCAL_MANGLE_POSTROUTING[];

  private:
    struct QuotaInfo {
        int64_t quota;
        int64_t alert;
    };

    enum IptIpVer { IptIpV4, IptIpV6 };
    enum IptFullOp { IptFullOpInsert, IptFullOpDelete, IptFullOpAppend };
    enum IptJumpOp { IptJumpReject, IptJumpReturn, IptJumpNoAdd };
    enum IptOp { IptOpInsert, IptOpDelete };
    enum QuotaType { QuotaUnique, QuotaShared };
    enum RunCmdErrHandling { RunCmdFailureBad, RunCmdFailureOk };
#if LOG_NDEBUG
    enum IptFailureLog { IptFailShow, IptFailHide };
#else
    enum IptFailureLog { IptFailShow, IptFailHide = IptFailShow };
#endif

    std::string makeDataSaverCommand(IptablesTarget target, bool enable);

    int manipulateSpecialApps(const std::vector<std::string>& appStrUids, const std::string& chain,
                              IptJumpOp jumpHandling, IptOp appOp);

    int runIptablesAlertCmd(IptOp op, const std::string& alertName, int64_t bytes);
    int runIptablesAlertFwdCmd(IptOp op, const std::string& alertName, int64_t bytes);

    int updateQuota(const std::string& alertName, int64_t bytes);

    int setCostlyAlert(const std::string& costName, int64_t bytes, int64_t* alertBytes);
    int removeCostlyAlert(const std::string& costName, int64_t* alertBytes);

    typedef std::vector<TetherStats> TetherStatsList;

    static void addStats(TetherStatsList& statsList, const TetherStats& stats);

    /*
     * stats should never have only intIface initialized. Other 3 combos are ok.
     * fp should be a file to the apropriate FORWARD chain of iptables rules.
     * extraProcessingInfo: contains raw parsed data, and error info.
     * This strongly requires that setup of the rules is in a specific order:
     *  in:intIface out:extIface
     *  in:extIface out:intIface
     * and the rules are grouped in pairs when more that one tethering was setup.
     */
    static int addForwardChainStats(const TetherStats& filter,
                                    TetherStatsList& statsList, const std::string& iptOutput,
                                    std::string &extraProcessingInfo);

    /*
     * Attempt to find the bw_costly_* tables that need flushing,
     * and flush them.
     * If doClean then remove the tables also.
     * Deals with both ip4 and ip6 tables.
     */
    void flushExistingCostlyTables(bool doClean);
    static void parseAndFlushCostlyTables(const std::string& ruleList, bool doRemove);

    /*
     * Attempt to flush our tables.
     * If doClean then remove them also.
     * Deals with both ip4 and ip6 tables.
     */
    void flushCleanTables(bool doClean);

    // For testing.
    friend class BandwidthControllerTest;
    static int (*execFunction)(int, char **, int *, bool, bool);
    static FILE *(*popenFunction)(const char *, const char *);
    static int (*iptablesRestoreFunction)(IptablesTarget, const std::string&, std::string *);

    static const char *opToString(IptOp op);
    static const char *jumpToString(IptJumpOp jumpHandling);

    int64_t mSharedQuotaBytes = 0;
    int64_t mSharedAlertBytes = 0;
    int64_t mGlobalAlertBytes = 0;
    /*
     * This tracks the number of tethers setup.
     * The FORWARD chain is updated in the following cases:
     *  - The 1st time a globalAlert is setup and there are tethers setup.
     *  - Anytime a globalAlert is removed and there are tethers setup.
     *  - The 1st tether is setup and there is a globalAlert active.
     *  - The last tether is removed and there is a globalAlert active.
     */
    int mGlobalAlertTetherCount = 0;

    std::map<std::string, QuotaInfo> mQuotaIfaces;
    std::set<std::string> mSharedQuotaIfaces;
};

#endif
