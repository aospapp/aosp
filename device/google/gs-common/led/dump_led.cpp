/*
 * Copyright 2022 The Android Open Source Project
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

#include <dump/pixel_dump.h>

int main() {
    dumpFileContent("Green LED Brightness", "/sys/class/leds/green/brightness");
    dumpFileContent("Green LED Max Brightness", "/sys/class/leds/green/max_brightness");
    dumpFileContent("LED Calibration Data", "/mnt/vendor/persist/led/led_calibration_LUT.txt");
    return 0;
}
