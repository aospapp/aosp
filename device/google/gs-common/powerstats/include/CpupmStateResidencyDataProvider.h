/*
 * Copyright (C) 2023 The Android Open Source Project
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
#pragma once

#include <PowerStatsAidl.h>

namespace aidl {
namespace android {
namespace hardware {
namespace power {
namespace stats {

class CpupmStateResidencyDataProvider : public PowerStats::IStateResidencyDataProvider {
  public:
    class Config {
      public:
        // List of power entity name pairs (name to display, name to parse)
        std::vector<std::pair<std::string, std::string>> entities;

        // List of state pairs (state to display, state to parse).
        std::vector<std::pair<std::string, std::string>> states;
    };

    typedef std::vector<std::string> SleepConfig;

    /*
     * path - path to cpupm sysfs node.
     */
    CpupmStateResidencyDataProvider(
        const std::string &path,
        const Config &config,
        const std::string &sleepPath,
        const SleepConfig &sleepConfig);
    ~CpupmStateResidencyDataProvider() = default;

    /*
     * See IStateResidencyDataProvider::getStateResidencies
     */
    bool getStateResidencies(
        std::unordered_map<std::string, std::vector<StateResidency>> *residencies) override;

    /*
     * See IStateResidencyDataProvider::getInfo
     */
    std::unordered_map<std::string, std::vector<State>> getInfo() override;

  private:
    int32_t matchEntity(char const *line);
    int32_t matchState(char const *line);
    bool parseState(char const *line, uint64_t *duration, uint64_t *count);

    // A constant to represent the number of microseconds in one millisecond.
    const uint64_t US_TO_MS = 1000;
    // A constant to represent the number of nanoseconds in one millisecond.
    const uint64_t NS_TO_MS = 1000000;

    const std::string mPath;
    const Config mConfig;
    const std::string mSleepPath;
    const SleepConfig mSleepConfig;
};

}  // namespace stats
}  // namespace power
}  // namespace hardware
}  // namespace android
}  // namespace aidl
