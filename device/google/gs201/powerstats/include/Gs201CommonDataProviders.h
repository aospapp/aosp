/*
 * Copyright (C) 2021 The Android Open Source Project
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

using aidl::android::hardware::power::stats::PowerStats;

void addAoC(std::shared_ptr<PowerStats> p);
void addCPUclusters(std::shared_ptr<PowerStats> p);
void addCamera(std::shared_ptr<PowerStats> p);
void addDevfreq(std::shared_ptr<PowerStats> p);
void addDvfsStats(std::shared_ptr<PowerStats> p);
void addGNSS(std::shared_ptr<PowerStats> p);
void addGs201CommonDataProviders(std::shared_ptr<PowerStats> p);
void addMobileRadio(std::shared_ptr<PowerStats> p);
void addNFC(std::shared_ptr<PowerStats> p, const std::string& path);
void addPCIe(std::shared_ptr<PowerStats> p);
void addPixelStateResidencyDataProvider(std::shared_ptr<PowerStats> p);
void addPowerDomains(std::shared_ptr<PowerStats> p);
void addSoC(std::shared_ptr<PowerStats> p);
void addTPU(std::shared_ptr<PowerStats> p);
void addUfs(std::shared_ptr<PowerStats> p);
void addWifi(std::shared_ptr<PowerStats> p);
void addWlan(std::shared_ptr<PowerStats> p);
void setEnergyMeter(std::shared_ptr<PowerStats> p);
