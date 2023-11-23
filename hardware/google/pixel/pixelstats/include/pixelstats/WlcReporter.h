/*
 * Copyright (C) 2020 The Android Open Source Project
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

#ifndef HARDWARE_GOOGLE_PIXEL_PIXELSTATS_WLCREPORTER_H
#define HARDWARE_GOOGLE_PIXEL_PIXELSTATS_WLCREPORTER_H

#include <android/frameworks/stats/1.0/IStats.h>
#include <hardware/google/pixel/pixelstats/pixelatoms.pb.h>

using android::frameworks::stats::V1_0::IStats;

namespace android {
namespace hardware {
namespace google {
namespace pixel {

/**
 * A class to upload wireless metrics
 */
class WlcReporter : public RefBase {
  public:
    /* checkAndReport
     * isWirelessChargingLast: last wireless charge state
     *                             true, for wireless charging
     * Return: current wireless charge state
     */
    bool checkAndReport(bool isWirelessChargingLast);
    bool isWlcSupported();

  private:
    bool isWlcOnline();
    bool readFileToInt(const char *path, int *val);

    void doLog();
    // Translate device orientation value from sensor Hal to atom enum value
    int translateDeviceOrientationToAtomValue(int orientation);

    // Proto messages are 1-indexed and VendorAtom field numbers start at 2, so
    // store everything in the values array at the index of the field number
    // -2.
    const int kVendorAtomOffset = 2;
    int readPtmcId();
};

}  // namespace pixel
}  // namespace google
}  // namespace hardware
}  // namespace android

#endif  // HARDWARE_GOOGLE_PIXEL_PIXELSTATS_WLCREPORTER_H
