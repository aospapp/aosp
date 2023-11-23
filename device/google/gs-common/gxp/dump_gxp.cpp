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
#include <android-base/properties.h>
#include <android-base/file.h>

#define maxGxpDebugDumps 3

int main() {
    /* TODO(b/277094681): Set default value to false around device beta milestone. */
    if(::android::base::GetBoolProperty("vendor.gxp.attach_to_bugreport", true)) {
        std::string outputDir = concatenatePath(BUGREPORT_PACKING_DIR, "gxp_ssrdump");
        printf("Creating %s", outputDir.c_str());
        if (mkdir(outputDir.c_str(), 0777) == -1) {
            printf("Unable to create folder: %s\n", outputDir.c_str());
            return 0;
        }

        dumpLogs("/data/vendor/ssrdump/coredump", outputDir.c_str(), maxGxpDebugDumps, "coredump_gxp_");
        dumpLogs("/data/vendor/ssrdump", outputDir.c_str(), maxGxpDebugDumps, "crashinfo_gxp_");
    }
    return 0;
}

