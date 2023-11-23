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
#include "CpupmStateResidencyDataProvider.h"

#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>

#include <string>
#include <utility>

using android::base::ParseUint;
using android::base::Split;
using android::base::StartsWith;
using android::base::Trim;

namespace aidl {
namespace android {
namespace hardware {
namespace power {
namespace stats {

CpupmStateResidencyDataProvider::CpupmStateResidencyDataProvider(
        const std::string &path,
        const Config &config,
        const std::string &sleepPath,
        const SleepConfig &sleepConfig)
    : mPath(std::move(path)),
      mConfig(std::move(config)),
      mSleepPath(std::move(sleepPath)),
      mSleepConfig(std::move(sleepConfig)) {}

int32_t CpupmStateResidencyDataProvider::matchState(char const *line) {
    for (int32_t i = 0; i < mConfig.states.size(); i++) {
        if (mConfig.states[i].second == Trim(std::string(line))) {
            return i;
        }
    }
    return -1;
}

int32_t CpupmStateResidencyDataProvider::matchEntity(char const *line) {
    for (int32_t i = 0; i < mConfig.entities.size(); i++) {
        if (StartsWith(Trim(std::string(line)), mConfig.entities[i].second)) {
            return i;
        }
    }
    return -1;
}

bool CpupmStateResidencyDataProvider::parseState(
        char const *line, uint64_t *duration, uint64_t *count) {
    std::vector<std::string> parts = Split(line, " ");
    if (parts.size() != 5) {
        return false;
    }
    if (!ParseUint(Trim(parts[1]), count)) {
        return false;
    }
    if (!ParseUint(Trim(parts[3]), duration)) {
        return false;
    }
    return true;
}

bool CpupmStateResidencyDataProvider::getStateResidencies(
        std::unordered_map<std::string, std::vector<StateResidency>> *residencies) {
    std::unique_ptr<FILE, decltype(&fclose)> fp(fopen(mPath.c_str(), "r"), fclose);
    if (!fp) {
        PLOG(ERROR) << __func__ << ":Failed to open file " << mPath;
        return false;
    }

    std::unique_ptr<FILE, decltype(&fclose)> sleepFp(fopen(mSleepPath.c_str(), "r"), fclose);
    if (!sleepFp) {
        PLOG(ERROR) << __func__ << ":Failed to open file " << mSleepPath;
        return false;
    }

    for (int32_t i = 0; i < mConfig.entities.size(); i++) {
        std::vector<StateResidency> stateResidencies(mConfig.states.size());
        for (int32_t j = 0; j < stateResidencies.size(); j++) {
            stateResidencies[j].id = j;
        }
        residencies->emplace(mConfig.entities[i].first, stateResidencies);
    }

    size_t len = 0;
    char *line = nullptr;

    int32_t temp, entityIndex, stateId = -1;
    uint64_t duration, count, sleepDurationMs = 0;
    auto it = residencies->end();
    int32_t sleepIndex = 0;

    // Parse state for sleep duration
    while (getline(&line, &len, sleepFp.get()) != -1) {
        std::string trimedLine = Trim(std::string(line));
        if (StartsWith(trimedLine, mSleepConfig[sleepIndex])) {
            if (sleepIndex < mSleepConfig.size() - 1) {
                sleepIndex++;
                continue;
            } else {
                std::vector<std::string> parts = Split(trimedLine, " ");
                if (parts.size() == 2) {
                    ParseUint(parts[1], &sleepDurationMs);
                    sleepDurationMs /= NS_TO_MS;
                }
                break;
            }
        }
    }

    // Parse state for CPUPM entities
    while (getline(&line, &len, fp.get()) != -1) {
        temp = matchState(line);
        // Assign new id only when a new valid state is encountered.
        if (temp >= 0) {
            stateId = temp;
        }

        if (stateId < 0) continue;

        entityIndex = matchEntity(line);

        if (entityIndex < 0) continue;

        it = residencies->find(mConfig.entities[entityIndex].first);
        if (it != residencies->end()) {
            if (parseState(line, &duration, &count)) {
                it->second[stateId].totalTimeInStateMs = duration / US_TO_MS + sleepDurationMs;
                it->second[stateId].totalStateEntryCount = count;
            } else {
                LOG(ERROR) << "Failed to parse duration and count from [" << std::string(line)
                           << "]";
                return false;
            }
        }
    }

    free(line);

    return true;
}

std::unordered_map<std::string, std::vector<State>> CpupmStateResidencyDataProvider::getInfo() {
    std::unordered_map<std::string, std::vector<State>> info;
    for (auto const &entity : mConfig.entities) {
        std::vector<State> stateInfo(mConfig.states.size());
        int32_t stateId = 0;
        for (auto const &state : mConfig.states) {
            stateInfo[stateId] = State{
                .id = stateId,
                .name = state.first
            };
            stateId++;
        }
        info.emplace(entity.first, stateInfo);
    }
    return info;
}

}  // namespace stats
}  // namespace power
}  // namespace hardware
}  // namespace android
}  // namespace aidl
