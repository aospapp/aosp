/*
 * Copyright (C) 2016 The Android Open Source Project
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
#ifndef ANDROID_HARDWARE_DUMPSTATE_V1_1_DUMPSTATEDEVICE_H
#define ANDROID_HARDWARE_DUMPSTATE_V1_1_DUMPSTATEDEVICE_H

#include <android/hardware/dumpstate/1.1/IDumpstateDevice.h>
#include <hidl/MQDescriptor.h>
#include <hidl/Status.h>
#include <string>

namespace android {
namespace hardware {
namespace dumpstate {
namespace V1_1 {
namespace implementation {

using ::android::hardware::dumpstate::V1_1::DumpstateMode;
using ::android::hardware::dumpstate::V1_1::DumpstateStatus;
using ::android::hardware::dumpstate::V1_1::IDumpstateDevice;
using ::android::hardware::hidl_handle;
using ::android::hardware::hidl_string;
using ::android::hardware::hidl_vec;
using ::android::hardware::Return;

struct DumpstateDevice : public IDumpstateDevice {
  public:
    DumpstateDevice();

    // Methods from ::android::hardware::dumpstate::V1_0::IDumpstateDevice follow.
    Return<void> dumpstateBoard(const hidl_handle& h) override;

    // Methods from ::android::hardware::dumpstate::V1_1::IDumpstateDevice follow.
    Return<DumpstateStatus> dumpstateBoard_1_1(const hidl_handle& h,
                                               const DumpstateMode mode,
                                               const uint64_t timeoutMillis) override;
    Return<void> setVerboseLoggingEnabled(const bool enable) override;
    Return<bool> getVerboseLoggingEnabled() override;

    // Methods from ::android::hidl::base::V1_0::IBase follow.
    Return<void> debug(const hidl_handle &fd, const hidl_vec<hidl_string> &args) override;

  private:
    const std::string kAllSections = "all";

    std::vector<std::pair<std::string, std::function<void(int)>>> mTextSections;

    void dumpTextSection(int fd, std::string const& sectionName);

    // Text sections that can be dumped individually on the command line in
    // addition to being included in full dumps
    void dumpWlanSection(int fd);
    void dumpPowerSection(int fd);
    void dumpThermalSection(int fd);
    void dumpPreTouchSection(int fd);
    void dumpTouchSection(int fd);
    void dumpSocSection(int fd);
    void dumpCpuSection(int fd);
    void dumpDevfreqSection(int fd);
    void dumpMemorySection(int fd);
    void dumpStorageSection(int fd);
    void dumpDisplaySection(int fd);
    void dumpSensorsUSFSection(int fd);
    void dumpAoCSection(int fd);
    void dumpRamdumpSection(int fd);
    void dumpMiscSection(int fd);
    void dumpGscSection(int fd);
    void dumpCameraSection(int fd);
    void dumpTrustySection(int fd);
    void dumpModemSection(int fd);
};

}  // namespace implementation
}  // namespace V1_0
}  // namespace dumpstate
}  // namespace hardware
}  // namespace android

#endif  // ANDROID_HARDWARE_DUMPSTATE_V1_1_DUMPSTATEDEVICE_H
