/**
 * Copyright (c) 2020, The Android Open Source Project
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

#ifndef CPP_WATCHDOG_SERVER_SRC_PROCSTATCOLLECTOR_H_
#define CPP_WATCHDOG_SERVER_SRC_PROCSTATCOLLECTOR_H_

#include <android-base/result.h>
#include <utils/Mutex.h>
#include <utils/RefBase.h>

#include <stdint.h>

namespace android {
namespace automotive {
namespace watchdog {

constexpr const char* kProcStatPath = "/proc/stat";

struct CpuStats {
    uint64_t userTime = 0;       // Time spent in user mode.
    uint64_t niceTime = 0;       // Time spent in user mode with low priority (nice).
    uint64_t sysTime = 0;        // Time spent in system mode.
    uint64_t idleTime = 0;       // Time spent in the idle task.
    uint64_t ioWaitTime = 0;     // Time spent on context switching/waiting due to I/O operations.
    uint64_t irqTime = 0;        // Time servicing interrupts.
    uint64_t softIrqTime = 0;    // Time servicing soft interrupts.
    uint64_t stealTime = 0;      // Stolen time (Time spent in other OS in a virtualized env).
    uint64_t guestTime = 0;      // Time spent running a virtual CPU for guest OS.
    uint64_t guestNiceTime = 0;  // Time spent running a niced virtual CPU for guest OS.

    CpuStats& operator-=(const CpuStats& rhs) {
        userTime -= rhs.userTime;
        niceTime -= rhs.niceTime;
        sysTime -= rhs.sysTime;
        idleTime -= rhs.idleTime;
        ioWaitTime -= rhs.ioWaitTime;
        irqTime -= rhs.irqTime;
        softIrqTime -= rhs.softIrqTime;
        stealTime -= rhs.stealTime;
        guestTime -= rhs.guestTime;
        guestNiceTime -= rhs.guestNiceTime;
        return *this;
    }
};

class ProcStatInfo {
public:
    ProcStatInfo() : cpuStats({}), runnableProcessCount(0), ioBlockedProcessCount(0) {}
    ProcStatInfo(CpuStats stats, uint32_t runnableCnt, uint32_t ioBlockedCnt) :
          cpuStats(stats), runnableProcessCount(runnableCnt), ioBlockedProcessCount(ioBlockedCnt) {}
    CpuStats cpuStats;
    uint32_t runnableProcessCount;
    uint32_t ioBlockedProcessCount;

    uint64_t totalCpuTime() const {
        return cpuStats.userTime + cpuStats.niceTime + cpuStats.sysTime + cpuStats.idleTime +
                cpuStats.ioWaitTime + cpuStats.irqTime + cpuStats.softIrqTime + cpuStats.stealTime +
                cpuStats.guestTime + cpuStats.guestNiceTime;
    }
    uint32_t totalProcessCount() const { return runnableProcessCount + ioBlockedProcessCount; }
    bool operator==(const ProcStatInfo& info) const {
        return memcmp(&cpuStats, &info.cpuStats, sizeof(cpuStats)) == 0 &&
                runnableProcessCount == info.runnableProcessCount &&
                ioBlockedProcessCount == info.ioBlockedProcessCount;
    }
    ProcStatInfo& operator-=(const ProcStatInfo& rhs) {
        cpuStats -= rhs.cpuStats;
        /* Don't diff *ProcessCount as they are real-time values unlike |cpuStats|, which are
         * aggregated values since system startup.
         */
        return *this;
    }
};

class ProcStatCollectorInterface : public RefBase {
public:
    // Initializes the collector.
    virtual void init() = 0;

    // Collects proc stat delta since the last collection.
    virtual android::base::Result<void> collect() = 0;

    /* Returns true when the proc stat file is accessible. Otherwise, returns false.
     * Called by WatchdogPerfService and tests.
     */
    virtual bool enabled() = 0;

    virtual std::string filePath() = 0;

    // Returns the latest stats.
    virtual const ProcStatInfo latestStats() const = 0;

    // Returns the delta of stats from the latest collection.
    virtual const ProcStatInfo deltaStats() const = 0;
};

// Collector/parser for `/proc/stat` file.
class ProcStatCollector final : public ProcStatCollectorInterface {
public:
    explicit ProcStatCollector(const std::string& path = kProcStatPath) :
          kPath(path), mLatestStats({}) {}

    ~ProcStatCollector() {}

    void init() {
        Mutex::Autolock lock(mMutex);
        // Note: Verify proc file access outside the constructor. Otherwise, the unittests of
        // dependent classes would call the constructor before mocking and get killed due to
        // sepolicy violation.
        mEnabled = access(kPath.c_str(), R_OK) == 0;
    }

    android::base::Result<void> collect();

    bool enabled() {
        Mutex::Autolock lock(mMutex);
        return mEnabled;
    }

    std::string filePath() { return kProcStatPath; }

    const ProcStatInfo latestStats() const {
        Mutex::Autolock lock(mMutex);
        return mLatestStats;
    }

    const ProcStatInfo deltaStats() const {
        Mutex::Autolock lock(mMutex);
        return mDeltaStats;
    }

private:
    // Reads the contents of |kPath|.
    android::base::Result<ProcStatInfo> getProcStatLocked() const;

    // Path to proc stat file. Default path is |kProcStatPath|.
    const std::string kPath;

    // Makes sure only one collection is running at any given time.
    mutable Mutex mMutex;

    // True if |kPath| is accessible.
    bool mEnabled GUARDED_BY(mMutex);

    // Latest dump of CPU stats from the file at |kPath|.
    ProcStatInfo mLatestStats GUARDED_BY(mMutex);

    // Delta of CPU stats from the latest collection.
    ProcStatInfo mDeltaStats GUARDED_BY(mMutex);
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  CPP_WATCHDOG_SERVER_SRC_PROCSTATCOLLECTOR_H_
