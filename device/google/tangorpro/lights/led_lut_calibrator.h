/*
 * Copyright (C) 2022 The Android Open Source Project
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

#ifndef GOOGLE_TANGOTRON_LIGHTS_LED_LUT_CALIBRATOR_H_
#define GOOGLE_TANGOTRON_LIGHTS_LED_LUT_CALIBRATOR_H_

#include <string>
#include <unordered_map>
#include <vector>
#include <aidl/com/google/hardware/pixel/display/IDisplay.h>

using PanelCalibrationStatus = aidl::com::google::hardware::pixel::display::PanelCalibrationStatus;

class LedLutCalibrator {
  public:
    LedLutCalibrator(PanelCalibrationStatus status);
    LedLutCalibrator &operator=(const LedLutCalibrator &) = delete;
    ~LedLutCalibrator() = default;
    int GetByColorIntensity(const std::string &color, int intensity) const;

  private:
    bool ReadCalibrationTable();
    bool ParseBlock(const std::vector<uint8_t> *table);
    std::string MakeLutKey(const std::string &color, int intensity) const;

    std::unordered_map<std::string, int> cal_table_;
    PanelCalibrationStatus cal_status_;
};

#endif  // GOOGLE_TANGOTRON_LIGHTS_LED_LUT_CALIBRATOR_H_
