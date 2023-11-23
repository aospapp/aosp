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
#include <stdio.h>
#include <string>
#include <android-base/properties.h>
#include <dump/pixel_dump.h>
#include "DumpstateUtil.h"

int main() {
    setbuf(stdout, NULL);

    runCommand("USF statistics", "/vendor/bin/usf_stats get --all");
    if (!::android::os::dumpstate::PropertiesHelper::IsUserBuild()) {
        // Not a user build, if this is also not a production device dump the USF registry.
        std::string hwRev = ::android::base::GetProperty("ro.boot.hardware.revision", "");
        if (hwRev.find("PROTO") != std::string::npos ||
            hwRev.find("EVT") != std::string::npos ||
            hwRev.find("DVT") != std::string::npos ||
            hwRev.find("PVT") != std::string::npos) {
            runCommand("USF Registry", "/vendor/bin/usf_reg_edit save -");
            dumpFileContent("USF Last Stat Buffer", "/data/vendor/sensors/debug/stats.history");
        }
    }
    return 0;
}
