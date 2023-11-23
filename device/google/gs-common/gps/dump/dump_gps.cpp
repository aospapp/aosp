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

#define GPS_LOG_NUMBER_PROPERTY "persist.vendor.gps.aol.log_num"
#define GPS_LOG_DIRECTORY "/data/vendor/gps/logs"
#define GPS_TMP_LOG_DIRECTORY "/data/vendor/gps/logs/.tmp"
#define GPS_LOG_PREFIX "gl-"
#define GPS_MCU_LOG_PREFIX "esw-"
#define GPS_MALLOC_LOG_DIRECTORY "/data/vendor/gps"
#define GPS_MALLOC_LOG_PREFIX "malloc_"

int main() {
    if(!::android::base::GetBoolProperty("vendor.gps.aol.enabled", false)) {
        printf("vendor.gps.aol.enabled is false. gps logging is not running.\n");
        return 0;
    }
    int maxFileNum = ::android::base::GetIntProperty(GPS_LOG_NUMBER_PROPERTY, 20);
    std::string outputDir = concatenatePath(BUGREPORT_PACKING_DIR, "gps");
    if (mkdir(outputDir.c_str(), 0777) == -1) {
        printf("Unable to create folder: %s\n", outputDir.c_str());
        return 0;
    }

    dumpLogs(GPS_TMP_LOG_DIRECTORY, outputDir.c_str(), 1, GPS_LOG_PREFIX);
    dumpLogs(GPS_LOG_DIRECTORY, outputDir.c_str(), 3, GPS_MCU_LOG_PREFIX);
    dumpLogs(GPS_LOG_DIRECTORY, outputDir.c_str(), maxFileNum, GPS_LOG_PREFIX);
    dumpLogs(GPS_MALLOC_LOG_DIRECTORY, outputDir.c_str(), 1, GPS_MALLOC_LOG_PREFIX);
    return 0;
}

