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
    dumpFileContent("MIF DVFS", "/sys/devices/platform/17000010.devfreq_mif/devfreq/17000010.devfreq_mif/time_in_state");
    dumpFileContent("INT DVFS", "/sys/devices/platform/17000020.devfreq_int/devfreq/17000020.devfreq_int/time_in_state");
    dumpFileContent("INTCAM DVFS", "/sys/devices/platform/17000030.devfreq_intcam/devfreq/17000030.devfreq_intcam/time_in_state");
    dumpFileContent("DISP DVFS", "/sys/devices/platform/17000040.devfreq_disp/devfreq/17000040.devfreq_disp/time_in_state");
    dumpFileContent("CAM DVFS", "/sys/devices/platform/17000050.devfreq_cam/devfreq/17000050.devfreq_cam/time_in_state");
    dumpFileContent("TNR DVFS", "/sys/devices/platform/17000060.devfreq_tnr/devfreq/17000060.devfreq_tnr/time_in_state");
    dumpFileContent("MFC DVFS", "/sys/devices/platform/17000070.devfreq_mfc/devfreq/17000070.devfreq_mfc/time_in_state");
    dumpFileContent("BO DVFS", "/sys/devices/platform/17000080.devfreq_bo/devfreq/17000080.devfreq_bo/time_in_state");
    dumpFileContent("BTS stats", "/sys/devices/platform/exynos-bts/bts_stats");
    return 0;
}
