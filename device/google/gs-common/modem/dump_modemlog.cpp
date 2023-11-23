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
#include <log/log.h>

#define MODEM_LOGGING_PERSIST_PROPERTY "persist.vendor.sys.modem.logging.enable"
#define MODEM_LOGGING_PROPERTY "vendor.sys.modem.logging.enable"
#define MODEM_LOGGING_STATUS_PROPERTY "vendor.sys.modem.logging.status"
#define MODEM_LOGGING_NUMBER_BUGREPORT_PROPERTY "persist.vendor.sys.modem.logging.br_num"
#define MODEM_LOGGING_PATH_PROPERTY "vendor.sys.modem.logging.log_path"
#define MODEM_SIM_DIRECTORY "/data/vendor/radio/sim/"
#define MODEM_LOG_PREFIX "sbuff_"
#define SIM_POWERON_LOG_PREFIX "sim_poweron_log_"

int main() {
    bool modemLogEnabled = ::android::base::GetBoolProperty(MODEM_LOGGING_PERSIST_PROPERTY, false);
    if (modemLogEnabled && ::android::base::GetProperty(MODEM_LOGGING_PATH_PROPERTY, "") == MODEM_LOG_DIRECTORY) {
        bool modemLogStarted = ::android::base::GetBoolProperty(MODEM_LOGGING_STATUS_PROPERTY, false);
        int maxFileNum = ::android::base::GetIntProperty(MODEM_LOGGING_NUMBER_BUGREPORT_PROPERTY, 100);

        if (modemLogStarted) {
            ::android::base::SetProperty(MODEM_LOGGING_PROPERTY, "false");
            ALOGD("Stopping modem logging...\n");
        } else {
            ALOGD("modem logging is not running\n");
        }

        for (int i = 0; i < 15; i++) {
            if (!::android::base::GetBoolProperty(MODEM_LOGGING_STATUS_PROPERTY, false)) {
                ALOGD("modem logging stopped\n");
                sleep(1);
                break;
            }
            sleep(1);
        }

        dumpLogs(MODEM_LOG_DIRECTORY, BUGREPORT_PACKING_DIR, maxFileNum, MODEM_LOG_PREFIX);

        if (modemLogStarted) {
            ALOGD("Restarting modem logging...\n");
            ::android::base::SetProperty(MODEM_LOGGING_PROPERTY, "true");
        }
    }

    dumpLogs("/data/vendor/radio/extended_logs", BUGREPORT_PACKING_DIR, 20, "extended_log_");
    dumpLogs(MODEM_SIM_DIRECTORY, BUGREPORT_PACKING_DIR, 1, SIM_POWERON_LOG_PREFIX);
    copyFile("/mnt/vendor/efs/nv_normal.bin", "/data/vendor/radio/logs/always-on/all_logs/nv_normal.bin");
    copyFile("/mnt/vendor/efs/nv_protected.bin", "/data/vendor/radio/logs/always-on/all_logs/nv_protected.bin");
    return 0;
}
