/*
 * Copyright 2016 The Android Open Source Project
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

#define LOG_TAG "dumpstate_device"

#include <inttypes.h>

#include <android-base/file.h>
#include <android-base/stringprintf.h>
#include <android-base/properties.h>
#include <android-base/unique_fd.h>
#include <hidl/HidlBinderSupport.h>
#include <log/log.h>
#include <pthread.h>
#include <sys/stat.h>

#include "DumpstateDevice.h"

#include "DumpstateUtil.h"

#define MODEM_LOG_DIRECTORY "/data/vendor/radio/logs/always-on"
#define MODEM_EXTENDED_LOG_DIRECTORY "/data/vendor/radio/extended_logs"
#define RIL_LOG_DIRECTORY "/data/vendor/radio"
#define RIL_LOG_DIRECTORY_PROPERTY "persist.vendor.ril.log.base_dir"
#define RIL_LOG_NUMBER_PROPERTY "persist.vendor.ril.log.num_file"
#define MODEM_LOGGING_PERSIST_PROPERTY "persist.vendor.sys.modem.logging.enable"
#define MODEM_LOGGING_PROPERTY "vendor.sys.modem.logging.enable"
#define MODEM_LOGGING_STATUS_PROPERTY "vendor.sys.modem.logging.status"
#define MODEM_LOGGING_NUMBER_BUGREPORT_PROPERTY "persist.vendor.sys.modem.logging.br_num"
#define MODEM_LOGGING_PATH_PROPERTY "vendor.sys.modem.logging.log_path"
#define GPS_LOG_DIRECTORY "/data/vendor/gps/logs"
#define GPS_LOG_NUMBER_PROPERTY "persist.vendor.gps.aol.log_num"
#define GPS_LOGGING_STATUS_PROPERTY "vendor.gps.aol.enabled"

#define UFS_BOOTDEVICE "ro.boot.bootdevice"

#define TCPDUMP_LOG_DIRECTORY "/data/vendor/tcpdump_logger/logs"
#define TCPDUMP_NUMBER_BUGREPORT "persist.vendor.tcpdump.log.br_num"
#define TCPDUMP_PERSIST_PROPERTY "persist.vendor.tcpdump.log.alwayson"

#define HW_REVISION "ro.boot.hardware.revision"

using android::os::dumpstate::CommandOptions;
using android::os::dumpstate::DumpFileToFd;
using android::os::dumpstate::PropertiesHelper;
using android::os::dumpstate::RunCommandToFd;

namespace android {
namespace hardware {
namespace dumpstate {
namespace V1_1 {
namespace implementation {

#define GPS_LOG_PREFIX "gl-"
#define GPS_MCU_LOG_PREFIX "esw-"
#define MODEM_LOG_PREFIX "sbuff_"
#define EXTENDED_LOG_PREFIX "extended_log_"
#define RIL_LOG_PREFIX "rild.log."
#define BUFSIZE 65536
#define TCPDUMP_LOG_PREFIX "tcpdump"

typedef std::chrono::time_point<std::chrono::steady_clock> timepoint_t;

const char kVerboseLoggingProperty[] = "persist.vendor.verbose_logging_enabled";

void copyFile(std::string srcFile, std::string destFile) {
    uint8_t buffer[BUFSIZE];
    ssize_t size;

    int fdSrc = open(srcFile.c_str(), O_RDONLY);
    if (fdSrc < 0) {
        ALOGD("Failed to open source file %s\n", srcFile.c_str());
        return;
    }

    int fdDest = open(destFile.c_str(), O_WRONLY | O_CREAT, 0666);
    if (fdDest < 0) {
        ALOGD("Failed to open destination file %s\n", destFile.c_str());
        close(fdSrc);
        return;
    }

    ALOGD("Copying %s to %s\n", srcFile.c_str(), destFile.c_str());
    while ((size = TEMP_FAILURE_RETRY(read(fdSrc, buffer, BUFSIZE))) > 0) {
        TEMP_FAILURE_RETRY(write(fdDest, buffer, size));
    }

    close(fdDest);
    close(fdSrc);
}

void dumpLogs(int fd, std::string srcDir, std::string destDir, int maxFileNum,
              const char *logPrefix) {
    (void) fd;
    struct dirent **dirent_list = NULL;
    int num_entries = scandir(srcDir.c_str(),
                              &dirent_list,
                              0,
                              (int (*)(const struct dirent **, const struct dirent **)) alphasort);
    if (!dirent_list) {
        return;
    } else if (num_entries <= 0) {
        return;
    }

    int copiedFiles = 0;

    for (int i = num_entries - 1; i >= 0; i--) {
        ALOGD("Found %s\n", dirent_list[i]->d_name);

        if (0 != strncmp(dirent_list[i]->d_name, logPrefix, strlen(logPrefix))) {
            continue;
        }

        if ((copiedFiles >= maxFileNum) && (maxFileNum != -1)) {
            ALOGD("Skipped %s\n", dirent_list[i]->d_name);
            continue;
        }

        copiedFiles++;

        std::string srcLogFile = srcDir + "/" + dirent_list[i]->d_name;
        std::string destLogFile = destDir + "/" + dirent_list[i]->d_name;
        copyFile(srcLogFile, destLogFile);

        ALOGD("Copying %s to %s\n", srcLogFile.c_str(), destLogFile.c_str());
    }

    while (num_entries--) {
        free(dirent_list[num_entries]);
    }

    free(dirent_list);
}

void dumpRilLogs(int fd, std::string destDir) {
    std::string rilLogDir =
            android::base::GetProperty(RIL_LOG_DIRECTORY_PROPERTY, RIL_LOG_DIRECTORY);

    int maxFileNum = android::base::GetIntProperty(RIL_LOG_NUMBER_PROPERTY, 50);

    const std::string currentLogDir = rilLogDir + "/cur";
    const std::string previousLogDir = rilLogDir + "/prev";
    const std::string currentDestDir = destDir + "/cur";
    const std::string previousDestDir = destDir + "/prev";

    RunCommandToFd(fd, "MKDIR RIL CUR LOG", {"/vendor/bin/mkdir", "-p", currentDestDir.c_str()},
                   CommandOptions::WithTimeout(2).Build());
    RunCommandToFd(fd, "MKDIR RIL PREV LOG", {"/vendor/bin/mkdir", "-p", previousDestDir.c_str()},
                   CommandOptions::WithTimeout(2).Build());

    dumpLogs(fd, currentLogDir, currentDestDir, maxFileNum, RIL_LOG_PREFIX);
    dumpLogs(fd, previousLogDir, previousDestDir, maxFileNum, RIL_LOG_PREFIX);
}

void dumpNetmgrLogs(std::string destDir) {
    const std::vector <std::string> netmgrLogs
        {
            "/data/vendor/radio/metrics_data",
            "/data/vendor/radio/omadm_logs.txt",
            "/data/vendor/radio/power_anomaly_data.txt",
        };
    for (const auto& logFile : netmgrLogs) {
        copyFile(logFile, destDir + "/" + basename(logFile.c_str()));
    }
}

/** Dumps last synced NV data into bugreports */
void dumpModemEFS(std::string destDir) {
    const std::string EFS_DIRECTORY = "/mnt/vendor/efs/";
    const std::vector <std::string> nv_files
        {
            EFS_DIRECTORY+"nv_normal.bin",
            EFS_DIRECTORY+"nv_protected.bin",
        };
    for (const auto& logFile : nv_files) {
        copyFile(logFile, destDir + "/" + basename(logFile.c_str()));
    }
}

void dumpGpsLogs(int fd, std::string destDir) {
    const std::string gpsLogDir = GPS_LOG_DIRECTORY;
    const std::string gpsTmpLogDir = gpsLogDir + "/.tmp";
    const std::string gpsDestDir = destDir + "/gps";
    int maxFileNum = android::base::GetIntProperty(GPS_LOG_NUMBER_PROPERTY, 20);

    RunCommandToFd(fd, "MKDIR GPS LOG", {"/vendor/bin/mkdir", "-p", gpsDestDir.c_str()},
                   CommandOptions::WithTimeout(2).Build());

    dumpLogs(fd, gpsTmpLogDir, gpsDestDir, 1, GPS_LOG_PREFIX);
    dumpLogs(fd, gpsLogDir, gpsDestDir, 3, GPS_MCU_LOG_PREFIX);
    dumpLogs(fd, gpsLogDir, gpsDestDir, maxFileNum, GPS_LOG_PREFIX);
}

void dumpCameraLogs(int fd, const std::string &destDir) {
    static const std::string kCameraLogDir = "/data/vendor/camera/profiler";
    const std::string cameraDestDir = destDir + "/camera";
    RunCommandToFd(fd, "MKDIR CAMERA LOG", {"/vendor/bin/mkdir", "-p", cameraDestDir.c_str()},
                   CommandOptions::WithTimeout(2).Build());
    // Attach multiple latest sessions (in case the user is running concurrent
    // sessions or starts a new session after the one with performance issues).
    dumpLogs(fd, kCameraLogDir, cameraDestDir, 10, "session-ended-");
    dumpLogs(fd, kCameraLogDir, cameraDestDir, 5, "high-drop-rate-");
    dumpLogs(fd, kCameraLogDir, cameraDestDir, 5, "watchdog-");
    dumpLogs(fd, kCameraLogDir, cameraDestDir, 5, "camera-ended-");
}

timepoint_t startSection(int fd, const std::string &sectionName) {
    android::base::WriteStringToFd(
            "\n"
            "------ Section start: " + sectionName + " ------\n"
            "\n", fd);
    return std::chrono::steady_clock::now();
}

void endSection(int fd, const std::string &sectionName, timepoint_t startTime) {
    auto endTime = std::chrono::steady_clock::now();
    auto elapsedMsec = std::chrono::duration_cast<std::chrono::milliseconds>
            (endTime - startTime).count();

    android::base::WriteStringToFd(
            "\n"
            "------ Section end: " + sectionName + " ------\n"
            "Elapsed msec: " + std::to_string(elapsedMsec) + "\n"
            "\n", fd);
}

// If you are adding a single RunCommandToFd() or DumpFileToFd() call, please
// add it to dumpMiscSection().  But if you are adding multiple items that are
// related to each other - for instance, for a Foo peripheral - please add them
// to a new dump function and include it in this table so it can be accessed from the
// command line, e.g.:
//   lshal debug android.hardware.dumpstate@1.0::IDumpstateDevice/default foo
//
// However, if your addition generates attachments and/or binary data for the
// bugreport (i.e. if it requires two file descriptors to execute), it must not be
// added to this table and should instead be added to dumpstateBoard() below.

DumpstateDevice::DumpstateDevice()
  : mTextSections{
        { "pre-touch", [this](int fd) { dumpPreTouchSection(fd); } },
        { "wlan", [this](int fd) { dumpWlanSection(fd); } },
        { "soc", [this](int fd) { dumpSocSection(fd); } },
        { "storage", [this](int fd) { dumpStorageSection(fd); } },
        { "memory", [this](int fd) { dumpMemorySection(fd); } },
        { "Devfreq", [this](int fd) { dumpDevfreqSection(fd); } },
        { "cpu", [this](int fd) { dumpCpuSection(fd); } },
        { "power", [this](int fd) { dumpPowerSection(fd); } },
        { "thermal", [this](int fd) { dumpThermalSection(fd); } },
        { "touch", [this](int fd) { dumpTouchSection(fd); } },
        { "display", [this](int fd) { dumpDisplaySection(fd); } },
        { "sensors-usf", [this](int fd) { dumpSensorsUSFSection(fd); } },
        { "aoc", [this](int fd) { dumpAoCSection(fd); } },
        { "ramdump", [this](int fd) { dumpRamdumpSection(fd); } },
        { "misc", [this](int fd) { dumpMiscSection(fd); } },
        { "gsc", [this](int fd) { dumpGscSection(fd); } },
        { "camera", [this](int fd) { dumpCameraSection(fd); } },
        { "trusty", [this](int fd) { dumpTrustySection(fd); } },
        { "modem", [this](int fd) { dumpModemSection(fd); } },
    } {
}

// Dump data requested by an argument to the "debug" HAL interface, or help info
// if the specified section is not supported.
void DumpstateDevice::dumpTextSection(int fd, const std::string &sectionName) {
    bool dumpAll = (sectionName == kAllSections);

    for (const auto &section : mTextSections) {
        if (dumpAll || sectionName == section.first) {
            auto startTime = startSection(fd, section.first);
            section.second(fd);
            endSection(fd, section.first, startTime);

            if (!dumpAll) {
                return;
            }
        }
    }

    if (dumpAll) {
        return;
    }

    // An unsupported section was requested on the command line
    android::base::WriteStringToFd("Unrecognized text section: " + sectionName + "\n", fd);
    android::base::WriteStringToFd("Try \"" + kAllSections + "\" or one of the following:", fd);
    for (const auto &section : mTextSections) {
        android::base::WriteStringToFd(" " + section.first, fd);
    }
    android::base::WriteStringToFd("\nNote: sections with attachments (e.g. modem) are"
                                   "not avalable from the command line.\n", fd);
}

// Dump items related to wlan
void DumpstateDevice::dumpWlanSection(int fd) {
    RunCommandToFd(fd, "WLAN Debug Dump", {"/vendor/bin/sh", "-c",
                   "cat /sys/wifi/dump_start"});
}

// Dump items related to power and battery
void DumpstateDevice::dumpPowerSection(int fd) {
    struct stat buffer;

    RunCommandToFd(fd, "Power Stats Times", {"/vendor/bin/sh", "-c",
                   "echo -n \"Boot: \" && /vendor/bin/uptime -s && "
                   "echo -n \"Now: \" && date"});

    RunCommandToFd(fd, "ACPM stats", {"/vendor/bin/sh", "-c",
                   "for f in /sys/devices/platform/acpm_stats/*_stats ; do "
                   "echo \"\\n\\n$f\" ; cat $f ; "
                   "done"});

    DumpFileToFd(fd, "CPU PM stats", "/sys/devices/system/cpu/cpupm/cpupm/time_in_state");

    DumpFileToFd(fd, "GENPD summary", "/d/pm_genpd/pm_genpd_summary");

    DumpFileToFd(fd, "Power supply property battery", "/sys/class/power_supply/battery/uevent");
    DumpFileToFd(fd, "Power supply property dc", "/sys/class/power_supply/dc/uevent");
    DumpFileToFd(fd, "Power supply property gcpm", "/sys/class/power_supply/gcpm/uevent");
    DumpFileToFd(fd, "Power supply property gcpm_pps", "/sys/class/power_supply/gcpm_pps/uevent");
    DumpFileToFd(fd, "Power supply property main-charger", "/sys/class/power_supply/main-charger/uevent");
    DumpFileToFd(fd, "Power supply property pca9486-mains", "/sys/class/power_supply/pca9468-mains/uevent");
    DumpFileToFd(fd, "Power supply property tcpm", "/sys/class/power_supply/tcpm-source-psy-i2c-max77759tcpc/uevent");
    DumpFileToFd(fd, "Power supply property usb", "/sys/class/power_supply/usb/uevent");
    DumpFileToFd(fd, "Power supply property wireless", "/sys/class/power_supply/wireless/uevent");
    if (!stat("/sys/class/power_supply/maxfg", &buffer)) {
        DumpFileToFd(fd, "Power supply property maxfg", "/sys/class/power_supply/maxfg/uevent");
        DumpFileToFd(fd, "m5_state", "/sys/class/power_supply/maxfg/m5_model_state");
        DumpFileToFd(fd, "maxfg", "/dev/logbuffer_maxfg");
        DumpFileToFd(fd, "maxfg", "/dev/logbuffer_maxfg_monitor");
    } else {
        DumpFileToFd(fd, "Power supply property maxfg_base", "/sys/class/power_supply/maxfg_base/uevent");
        DumpFileToFd(fd, "Power supply property maxfg_flip", "/sys/class/power_supply/maxfg_flip/uevent");
        DumpFileToFd(fd, "m5_state", "/sys/class/power_supply/maxfg_base/m5_model_state");
        DumpFileToFd(fd, "maxfg_base", "/dev/logbuffer_maxfg_base");
        DumpFileToFd(fd, "maxfg_flip", "/dev/logbuffer_maxfg_flip");
        DumpFileToFd(fd, "maxfg_base", "/dev/logbuffer_maxfg_base_monitor");
        DumpFileToFd(fd, "maxfg_flip", "/dev/logbuffer_maxfg_flip_monitor");
    }
    if (!stat("/sys/class/power_supply/dock", &buffer)) {
        DumpFileToFd(fd, "Power supply property dock", "/sys/class/power_supply/dock/uevent");
    }

    if (!stat("/dev/logbuffer_tcpm", &buffer)) {
        DumpFileToFd(fd, "Logbuffer TCPM", "/dev/logbuffer_tcpm");
    } else if (!PropertiesHelper::IsUserBuild()) {
        if (!stat("/sys/kernel/debug/tcpm", &buffer)) {
            RunCommandToFd(fd, "TCPM logs", {"/vendor/bin/sh", "-c", "cat /sys/kernel/debug/tcpm/*"});
        } else {
            RunCommandToFd(fd, "TCPM logs", {"/vendor/bin/sh", "-c", "cat /sys/kernel/debug/usb/tcpm*"});
        }
    }

    RunCommandToFd(fd, "TCPC", {"/vendor/bin/sh", "-c",
		       "for f in /sys/devices/platform/10d50000.hsi2c/i2c-*/i2c-max77759tcpc;"
		       "do echo \"registers:\"; cat $f/registers;"
		       "echo \"frs:\"; cat $f/frs;"
		       "echo \"auto_discharge:\"; cat $f/auto_discharge;"
		       "echo \"bc12_enabled:\"; cat $f/bc12_enabled;"
		       "echo \"cc_toggle_enable:\"; cat $f/cc_toggle_enable;"
		       "echo \"contaminant_detection:\"; cat $f/contaminant_detection;"
		       "echo \"contaminant_detection_status:\"; cat $f/contaminant_detection_status;  done"});

    DumpFileToFd(fd, "PD Engine", "/dev/logbuffer_usbpd");
    DumpFileToFd(fd, "POGO Transport", "/dev/logbuffer_pogo_transport");
    DumpFileToFd(fd, "PPS-google_cpm", "/dev/logbuffer_cpm");
    DumpFileToFd(fd, "PPS-dc", "/dev/logbuffer_pca9468");

    DumpFileToFd(fd, "BMS", "/dev/logbuffer_ssoc");
    DumpFileToFd(fd, "TTF", "/dev/logbuffer_ttf");
    DumpFileToFd(fd, "TTF details", "/sys/class/power_supply/battery/ttf_details");
    DumpFileToFd(fd, "TTF stats", "/sys/class/power_supply/battery/ttf_stats");
    DumpFileToFd(fd, "maxq", "/dev/logbuffer_maxq");
    DumpFileToFd(fd, "aacr_state", "/sys/class/power_supply/battery/aacr_state");

    RunCommandToFd(fd, "TRICKLE-DEFEND Config", {"/vendor/bin/sh", "-c",
                        " cd /sys/devices/platform/google,battery/power_supply/battery/;"
                        " for f in `ls bd_*` ; do echo \"$f: `cat $f`\" ; done"});

    RunCommandToFd(fd, "DWELL-DEFEND Config", {"/vendor/bin/sh", "-c",
                        " cd /sys/devices/platform/google,charger/;"
                        " for f in `ls charge_s*` ; do echo \"$f: `cat $f`\" ; done"});

    RunCommandToFd(fd, "TEMP-DEFEND Config", {"/vendor/bin/sh", "-c",
                        " cd /sys/devices/platform/google,charger/;"
                        " for f in `ls bd_*` ; do echo \"$f: `cat $f`\" ; done"});
    if (!PropertiesHelper::IsUserBuild()) {

        DumpFileToFd(fd, "DC_registers dump", "/sys/class/power_supply/pca9468-mains/device/registers_dump");
        DumpFileToFd(fd, "max77759_chg registers dump", "/d/max77759_chg/registers");
        DumpFileToFd(fd, "max77729_pmic registers dump", "/d/max77729_pmic/registers");
        DumpFileToFd(fd, "Charging table dump", "/d/google_battery/chg_raw_profile");


        RunCommandToFd(fd, "fg_model", {"/vendor/bin/sh", "-c",
                        "for f in /d/maxfg* ; do "
                        "regs=`cat $f/fg_model`; echo $f: ;"
                        "echo \"$regs\"; done"});

        RunCommandToFd(fd, "fg_alo_ver", {"/vendor/bin/sh", "-c",
                        "for f in /d/maxfg* ; do "
                        "regs=`cat $f/algo_ver`; echo $f: ;"
                        "echo \"$regs\"; done"});

        RunCommandToFd(fd, "fg_model_ok", {"/vendor/bin/sh", "-c",
                        "for f in /d/maxfg* ; do "
                        "regs=`cat $f/model_ok`; echo $f: ;"
                        "echo \"$regs\"; done"});


        /* FG Registers */
        RunCommandToFd(fd, "fg registers", {"/vendor/bin/sh", "-c",
                        "for f in /d/maxfg* ; do "
                        "regs=`cat $f/registers`; echo $f: ;"
                        "echo \"$regs\"; done"});
    }

    /* EEPROM State */
    if (!stat("/sys/devices/platform/10970000.hsi2c/i2c-4/4-0050/eeprom", &buffer)) {
        RunCommandToFd(fd, "Battery EEPROM", {"/vendor/bin/sh", "-c", "xxd /sys/devices/platform/10970000.hsi2c/i2c-4/4-0050/eeprom"});
    } else if(!stat("/sys/devices/platform/10970000.hsi2c/i2c-5/5-0050/eeprom", &buffer)) {
        RunCommandToFd(fd, "Battery EEPROM", {"/vendor/bin/sh", "-c", "xxd /sys/devices/platform/10970000.hsi2c/i2c-5/5-0050/eeprom"});
    } else if(!stat("/sys/devices/platform/10970000.hsi2c/i2c-6/6-0050/eeprom", &buffer)) {
        RunCommandToFd(fd, "Battery EEPROM", {"/vendor/bin/sh", "-c", "xxd /sys/devices/platform/10970000.hsi2c/i2c-6/6-0050/eeprom"});
    } else if(!stat("/sys/devices/platform/10970000.hsi2c/i2c-7/7-0050/eeprom", &buffer)) {
        RunCommandToFd(fd, "Battery EEPROM", {"/vendor/bin/sh", "-c", "xxd /sys/devices/platform/10970000.hsi2c/i2c-7/7-0050/eeprom"});
    } else {
        RunCommandToFd(fd, "Battery EEPROM", {"/vendor/bin/sh", "-c", "xxd /sys/devices/platform/10970000.hsi2c/i2c-8/8-0050/eeprom"});
    }

    DumpFileToFd(fd, "Charger Stats", "/sys/class/power_supply/battery/charge_details");
    if (!PropertiesHelper::IsUserBuild()) {
        RunCommandToFd(fd, "Google Charger", {"/vendor/bin/sh", "-c", "cd /sys/kernel/debug/google_charger/; "
                        "for f in `ls pps_*` ; do echo \"$f: `cat $f`\" ; done"});
        RunCommandToFd(fd, "Google Battery", {"/vendor/bin/sh", "-c", "cd /sys/kernel/debug/google_battery/; "
                        "for f in `ls ssoc_*` ; do echo \"$f: `cat $f`\" ; done"});
    }

    DumpFileToFd(fd, "WLC logs", "/dev/logbuffer_wireless");
    DumpFileToFd(fd, "WLC VER", "/sys/class/power_supply/wireless/device/version");
    DumpFileToFd(fd, "WLC STATUS", "/sys/class/power_supply/wireless/device/status");
    DumpFileToFd(fd, "WLC FW Version", "/sys/class/power_supply/wireless/device/fw_rev");
    DumpFileToFd(fd, "RTX", "/dev/logbuffer_rtx");

    if (!PropertiesHelper::IsUserBuild()) {
        RunCommandToFd(fd, "gvotables", {"/vendor/bin/sh", "-c", "cat /sys/kernel/debug/gvotables/*/status"});
    }
    RunCommandToFd(fd, "Mitigation Stats", {"/vendor/bin/sh", "-c", "echo \"Source\\t\\tCount\\tSOC\\tTime\\tVoltage\"; "
                        "for f in `ls /sys/devices/virtual/pmic/mitigation/last_triggered_count/*` ; "
                        "do count=`cat $f`; "
                        "a=${f/\\/sys\\/devices\\/virtual\\/pmic\\/mitigation\\/last_triggered_count\\//}; "
                        "b=${f/last_triggered_count/last_triggered_capacity}; "
                        "c=${f/last_triggered_count/last_triggered_timestamp/}; "
                        "d=${f/last_triggered_count/last_triggered_voltage/}; "
                        "cnt=`cat $f`; "
                        "cap=`cat ${b/count/cap}`; "
                        "ti=`cat ${c/count/time}`; "
                        "volt=`cat ${d/count/volt}`; "
                        "echo \"${a/_count/} "
                        "\\t$cnt\\t$cap\\t$ti\\t$volt\" ; done"});
    RunCommandToFd(fd, "Clock Divider Ratio", {"/vendor/bin/sh", "-c", "echo \"Source\\t\\tRatio\"; "
                        "for f in `ls /sys/devices/virtual/pmic/mitigation/clock_ratio/*` ; "
                        "do ratio=`cat $f`; "
                        "a=${f/\\/sys\\/devices\\/virtual\\/pmic\\/mitigation\\/clock_ratio\\//}; "
                        "echo \"${a/_ratio/} \\t$ratio\" ; done"});
    RunCommandToFd(fd, "Clock Stats", {"/vendor/bin/sh", "-c", "echo \"Source\\t\\tStats\"; "
                        "for f in `ls /sys/devices/virtual/pmic/mitigation/clock_stats/*` ; "
                        "do stats=`cat $f`; "
                        "a=${f/\\/sys\\/devices\\/virtual\\/pmic\\/mitigation\\/clock_stats\\//}; "
                        "echo \"${a/_stats/} \\t$stats\" ; done"});
    RunCommandToFd(fd, "Triggered Level", {"/vendor/bin/sh", "-c", "echo \"Source\\t\\tLevel\"; "
                        "for f in `ls /sys/devices/virtual/pmic/mitigation/triggered_lvl/*` ; "
                        "do lvl=`cat $f`; "
                        "a=${f/\\/sys\\/devices\\/virtual\\/pmic\\/mitigation\\/triggered_lvl\\//}; "
                        "echo \"${a/_lvl/} \\t$lvl\" ; done"});
    RunCommandToFd(fd, "Instruction", {"/vendor/bin/sh", "-c",
                        "for f in `ls /sys/devices/virtual/pmic/mitigation/instruction/*` ; "
                        "do val=`cat $f` ; "
                        "a=${f/\\/sys\\/devices\\/virtual\\/pmic\\/mitigation\\/instruction\\//}; "
                        "echo \"$a=$val\" ; done"});

}

// Dump items related to thermal
void DumpstateDevice::dumpThermalSection(int fd) {
    RunCommandToFd(fd, "Temperatures", {"/vendor/bin/sh", "-c",
                   "for f in /sys/class/thermal/thermal* ; do "
                       "type=`cat $f/type` ; temp=`cat $f/temp` ; echo \"$type: $temp\" ; "
                       "done"});
    RunCommandToFd(fd, "Cooling Device Current State", {"/vendor/bin/sh", "-c",
                   "for f in /sys/class/thermal/cooling* ; do "
                       "type=`cat $f/type` ; temp=`cat $f/cur_state` ; echo \"$type: $temp\" ; "
                       "done"});
    RunCommandToFd(fd, "Cooling Device User Vote State", {"/vendor/bin/sh", "-c",
                   "for f in /sys/class/thermal/cooling* ; do "
                   "if [ ! -f $f/user_vote ]; then continue; fi; "
                   "type=`cat $f/type` ; temp=`cat $f/user_vote` ; echo \"$type: $temp\" ; "
                   "done"});
    RunCommandToFd(fd, "Cooling Device Time in State", {"/vendor/bin/sh", "-c", "for f in /sys/class/thermal/cooling* ; "
                   "do type=`cat $f/type` ; temp=`cat $f/stats/time_in_state_ms` ; echo \"$type:\n$temp\" ; done"});
    RunCommandToFd(fd, "Cooling Device Trans Table", {"/vendor/bin/sh", "-c", "for f in /sys/class/thermal/cooling* ; "
                   "do type=`cat $f/type` ; temp=`cat $f/stats/trans_table` ; echo \"$type:\n$temp\" ; done"});
    RunCommandToFd(fd, "Cooling Device State2Power Table", {"/vendor/bin/sh", "-c",
                   "for f in /sys/class/thermal/cooling* ; do "
                   "if [ ! -f $f/state2power_table ]; then continue; fi; "
                   "type=`cat $f/type` ; state2power_table=`cat $f/state2power_table` ; echo \"$type: $state2power_table\" ; "
                   "done"});
    DumpFileToFd(fd, "TMU state:", "/sys/module/gs101_thermal/parameters/tmu_reg_dump_state");
    DumpFileToFd(fd, "TMU current temperature:", "/sys/module/gs101_thermal/parameters/tmu_reg_dump_current_temp");
    DumpFileToFd(fd, "TMU_TOP rise thresholds:", "/sys/module/gs101_thermal/parameters/tmu_top_reg_dump_rise_thres");
    DumpFileToFd(fd, "TMU_TOP fall thresholds:", "/sys/module/gs101_thermal/parameters/tmu_top_reg_dump_fall_thres");
    DumpFileToFd(fd, "TMU_SUB rise thresholds:", "/sys/module/gs101_thermal/parameters/tmu_sub_reg_dump_rise_thres");
    DumpFileToFd(fd, "TMU_SUB fall thresholds:", "/sys/module/gs101_thermal/parameters/tmu_sub_reg_dump_fall_thres");
}

// Dump items related to touch
void DumpstateDevice::dumpPreTouchSection(int fd) {
    const char nvt_spi_path[] = "/sys/class/spi_master/spi11/spi11.0/input/nvt_touch";
    char cmd[256];

    /* NVT touch */
    if (!access(nvt_spi_path, R_OK)) {
        snprintf(cmd, sizeof(cmd),
                 "echo %s > %s/%s",
                 "0x21",
                 nvt_spi_path,
                 "force_touch_active");
        RunCommandToFd(fd, "Force Touch Active(Enable)", {"/vendor/bin/sh", "-c", cmd});

        snprintf(cmd, sizeof(cmd), "/proc/nvt_fw_version");
        if (!access(cmd, R_OK))
            DumpFileToFd(fd, "FW version", cmd);

#if 0	/* b/193467774: remove this temporarily */
        snprintf(cmd, sizeof(cmd), "/proc/nvt_diff");
        if (!access(cmd, R_OK))
            DumpFileToFd(fd, "Diff", cmd);

        snprintf(cmd, sizeof(cmd), "%s/nvt_fw_history", nvt_spi_path);
        if (!access(nvt_spi_path, R_OK))
            DumpFileToFd(fd, "FW History", cmd);
#endif

        snprintf(cmd, sizeof(cmd),
                 "echo %s > %s/%s",
                 "0x20",
                 nvt_spi_path,
                 "force_touch_active");
        RunCommandToFd(fd, "Force Touch Active(Disable)", {"/vendor/bin/sh", "-c", cmd});
    }
}

void DumpstateDevice::dumpTouchSection(int fd) {
    const char stm_cmd_path[4][50] = {"/sys/class/spi_master/spi11/spi11.0",
                                      "/proc/fts/driver_test",
                                      "/sys/class/spi_master/spi6/spi6.0",
                                      "/proc/fts_ext/driver_test"};
    const char lsi_spi_path[] = "/sys/devices/virtual/sec/tsp";
    char cmd[256];

    for (int i = 0; i < 4; i+=2) {
        snprintf(cmd, sizeof(cmd), "%s", stm_cmd_path[i + 1]);
        if (!access(cmd, R_OK)) {
            snprintf(cmd, sizeof(cmd), "echo A0 01 01 > %s", stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "Force Set AP as Bus Owner with Bugreport Flag",
                           {"/vendor/bin/sh", "-c", cmd});
        }

        snprintf(cmd, sizeof(cmd), "%s/appid", stm_cmd_path[i]);
        if (!access(cmd, R_OK)) {
            // Touch firmware version
            DumpFileToFd(fd, "STM touch firmware version", cmd);

            // Touch controller status
            snprintf(cmd, sizeof(cmd), "%s/status", stm_cmd_path[i]);
            DumpFileToFd(fd, "STM touch status", cmd);

            // Mutual raw data
            snprintf(cmd, sizeof(cmd),
                     "echo 13 00 01 > %s/stm_fts_cmd && cat %s/stm_fts_cmd",
                     stm_cmd_path[i], stm_cmd_path[i]);
            RunCommandToFd(fd, "Mutual Raw", {"/vendor/bin/sh", "-c", cmd});

            // Mutual strength data
            snprintf(cmd, sizeof(cmd),
                     "echo 17 01 > %s/stm_fts_cmd && cat %s/stm_fts_cmd",
                     stm_cmd_path[i], stm_cmd_path[i]);
            RunCommandToFd(fd, "Mutual Strength", {"/vendor/bin/sh", "-c", cmd});

            // Self raw data
            snprintf(cmd, sizeof(cmd),
                     "echo 15 00 01 > %s/stm_fts_cmd && cat %s/stm_fts_cmd",
                     stm_cmd_path[i], stm_cmd_path[i]);
            RunCommandToFd(fd, "Self Raw", {"/vendor/bin/sh", "-c", cmd});
        }

        snprintf(cmd, sizeof(cmd), "%s", stm_cmd_path[i + 1]);
        if (!access(cmd, R_OK)) {
            snprintf(cmd, sizeof(cmd),
                     "echo 01 A4 06 C3 > %s; echo 02 A7 00 00 00 40 00 > %s && cat %s",
                     stm_cmd_path[i + 1], stm_cmd_path[i + 1], stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "HDM debug information (32 bytes)",
                           {"/vendor/bin/sh", "-c", cmd});

            snprintf(cmd, sizeof(cmd), "echo 23 00 > %s && cat %s",
                     stm_cmd_path[i + 1], stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "Mutual Raw Data",
                           {"/vendor/bin/sh", "-c", cmd});

            snprintf(cmd, sizeof(cmd), "echo 23 03 > %s && cat %s",
                     stm_cmd_path[i + 1], stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "Mutual Baseline Data",
                           {"/vendor/bin/sh", "-c", cmd});

            snprintf(cmd, sizeof(cmd), "echo 23 02 > %s && cat %s",
                     stm_cmd_path[i + 1], stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "Mutual Strength Data",
                           {"/vendor/bin/sh", "-c", cmd});

            snprintf(cmd, sizeof(cmd), "echo 24 00 > %s && cat %s",
                     stm_cmd_path[i + 1], stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "Self Raw Data",
                           {"/vendor/bin/sh", "-c", cmd});

            snprintf(cmd, sizeof(cmd), "echo 24 03 > %s && cat %s",
                     stm_cmd_path[i + 1], stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "Self Baseline Data",
                           {"/vendor/bin/sh", "-c", cmd});

            snprintf(cmd, sizeof(cmd), "echo 24 02 > %s && cat %s",
                     stm_cmd_path[i + 1], stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "Self Strength Data",
                           {"/vendor/bin/sh", "-c", cmd});

            snprintf(cmd, sizeof(cmd), "echo 32 10 > %s && cat %s",
                     stm_cmd_path[i + 1], stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "Mutual Compensation",
                           {"/vendor/bin/sh", "-c", cmd});

            snprintf(cmd, sizeof(cmd), "echo 32 11 > %s && cat %s",
                     stm_cmd_path[i + 1], stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "Mutual Low Power Compensation",
                           {"/vendor/bin/sh", "-c", cmd});

            snprintf(cmd, sizeof(cmd), "echo 33 12 > %s && cat %s",
                     stm_cmd_path[i + 1], stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "Self Compensation",
                           {"/vendor/bin/sh", "-c", cmd});

            snprintf(cmd, sizeof(cmd), "echo 34 > %s && cat %s",
                     stm_cmd_path[i + 1], stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "Golden Mutual Raw Data",
                           {"/vendor/bin/sh", "-c", cmd});

            snprintf(cmd, sizeof(cmd), "echo 01 FA 20 00 00 24 80 > %s",
                     stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "Packaging Plant - HW reset",
                           {"/vendor/bin/sh", "-c", cmd});
            snprintf(cmd, sizeof(cmd), "echo 01 FA 20 00 00 68 08 > %s",
                     stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "Packaging Plant - Hibernate Memory",
                           {"/vendor/bin/sh", "-c", cmd});
            snprintf(cmd, sizeof(cmd),
                     "echo 02 FB 00 04 3F D8 00 10 01 > %s && cat %s",
                     stm_cmd_path[i + 1], stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "Packaging Plant - Read 16 bytes from Address 0x00041FD8",
                           {"/vendor/bin/sh", "-c", cmd});

            snprintf(cmd, sizeof(cmd),
                     "echo 01 A4 06 C3 > %s; echo 02 A7 00 00 00 40 00 > %s && cat %s",
                     stm_cmd_path[i + 1], stm_cmd_path[i + 1], stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "HDM debug information (32 bytes)",
                           {"/vendor/bin/sh", "-c", cmd});
        }

        snprintf(cmd, sizeof(cmd), "%s/stm_fts_cmd", stm_cmd_path[i]);
        if (!access(cmd, R_OK)) {
            // ITO raw data
            snprintf(cmd, sizeof(cmd),
                     "echo 01 > %s/stm_fts_cmd && cat %s/stm_fts_cmd",
                     stm_cmd_path[i], stm_cmd_path[i]);
            RunCommandToFd(fd, "ITO Raw", {"/vendor/bin/sh", "-c", cmd});
        }

        snprintf(cmd, sizeof(cmd), "%s", stm_cmd_path[i + 1]);
        if (!access(cmd, R_OK)) {
            snprintf(cmd, sizeof(cmd), "echo A0 00 01 > %s", stm_cmd_path[i + 1]);
            RunCommandToFd(fd, "Restore Bus Owner",
                           {"/vendor/bin/sh", "-c", cmd});
        }
    }

    if (!access(lsi_spi_path, R_OK)) {
        // Enable: force touch active
        snprintf(cmd, sizeof(cmd),
                 "echo %s > %s/cmd && cat %s/cmd_result",
                 "force_touch_active,2,1",
                 lsi_spi_path, lsi_spi_path);
        RunCommandToFd(fd, "Force Touch Active", {"/vendor/bin/sh", "-c", cmd});

        // Firmware info
        snprintf(cmd, sizeof(cmd), "%s/fw_version", lsi_spi_path);
        DumpFileToFd(fd, "LSI firmware version", cmd);

        // Touch status
        snprintf(cmd, sizeof(cmd), "%s/status", lsi_spi_path);
        DumpFileToFd(fd, "LSI touch status", cmd);

        // Calibration info
        snprintf(cmd, sizeof(cmd),
                 "echo %s > %s/cmd && cat %s/cmd_result",
                 "get_mis_cal_info",
                 lsi_spi_path, lsi_spi_path);
        RunCommandToFd(fd, "Calibration info", {"/vendor/bin/sh", "-c", cmd});

        // Mutual strength
        snprintf(cmd, sizeof(cmd),
                 "echo %s > %s/cmd && cat %s/cmd_result",
                 "run_delta_read_all",
                 lsi_spi_path, lsi_spi_path);
        RunCommandToFd(fd, "Mutual Strength", {"/vendor/bin/sh", "-c", cmd});

        // Self strength
        snprintf(cmd, sizeof(cmd),
                 "echo %s > %s/cmd && cat %s/cmd_result",
                 "run_self_delta_read_all",
                 lsi_spi_path, lsi_spi_path);
        RunCommandToFd(fd, "Self Strength", {"/vendor/bin/sh", "-c", cmd});

        // TYPE_AMBIENT_DATA
        snprintf(cmd, sizeof(cmd),
                 "echo %s > %s/cmd && cat %s/cmd_result",
                 "run_rawdata_read_type,3",
                 lsi_spi_path, lsi_spi_path);
        RunCommandToFd(fd, "TYPE_AMBIENT_DATA", {"/vendor/bin/sh", "-c", cmd});

        // TYPE_DECODED_DATA
        snprintf(cmd, sizeof(cmd),
                 "echo %s > %s/cmd && cat %s/cmd_result",
                 "run_rawdata_read_type,5",
                 lsi_spi_path, lsi_spi_path);
        RunCommandToFd(fd, "TYPE_DECODED_DATA", {"/vendor/bin/sh", "-c", cmd});

        // TYPE_OFFSET_DATA_SEC
        snprintf(cmd, sizeof(cmd),
                 "echo %s > %s/cmd && cat %s/cmd_result",
                 "run_rawdata_read_type,19",
                 lsi_spi_path, lsi_spi_path);
        RunCommandToFd(fd, "TYPE_OFFSET_DATA_SEC", {"/vendor/bin/sh", "-c", cmd});

        // TYPE_NOI_P2P_MIN
        snprintf(cmd, sizeof(cmd),
                 "echo %s > %s/cmd && cat %s/cmd_result",
                 "run_rawdata_read_type,30",
                 lsi_spi_path, lsi_spi_path);
        RunCommandToFd(fd, "TYPE_NOI_P2P_MIN", {"/vendor/bin/sh", "-c", cmd});

        // TYPE_NOI_P2P_MAX
        snprintf(cmd, sizeof(cmd),
                 "echo %s > %s/cmd && cat %s/cmd_result",
                 "run_rawdata_read_type,31",
                 lsi_spi_path, lsi_spi_path);
        RunCommandToFd(fd, "TYPE_NOI_P2P_MAX", {"/vendor/bin/sh", "-c", cmd});

        // Raw cap
        snprintf(cmd, sizeof(cmd),
                 "echo %s > %s/cmd && cat %s/cmd_result",
                 "run_rawcap_read_all",
                 lsi_spi_path, lsi_spi_path);
        RunCommandToFd(fd, "Mutual Raw Cap", {"/vendor/bin/sh", "-c", cmd});

        // Self raw cap
        snprintf(cmd, sizeof(cmd),
                 "echo %s > %s/cmd && cat %s/cmd_result",
                 "run_self_rawcap_read_all",
                 lsi_spi_path, lsi_spi_path);
        RunCommandToFd(fd, "Self Raw Cap", {"/vendor/bin/sh", "-c", cmd});

        // CM2
        snprintf(cmd, sizeof(cmd),
                 "echo %s > %s/cmd && cat %s/cmd_result",
                 "run_rawcap_high_freq_read_all",
                 lsi_spi_path, lsi_spi_path);
        RunCommandToFd(fd, "CM2", {"/vendor/bin/sh", "-c", cmd});

        // Disable: force touch active
        snprintf(cmd, sizeof(cmd),
                 "echo %s > %s/cmd && cat %s/cmd_result",
                 "force_touch_active,2,0",
                 lsi_spi_path, lsi_spi_path);
        RunCommandToFd(fd, "Force Touch Active", {"/vendor/bin/sh", "-c", cmd});
    }
}

// Dump items related to SoC
void DumpstateDevice::dumpSocSection(int fd) {
    DumpFileToFd(fd, "AP HW TUNE", "/sys/devices/system/chip-id/ap_hw_tune_str");
    DumpFileToFd(fd, "EVT VERSION", "/sys/devices/system/chip-id/evt_ver");
    DumpFileToFd(fd, "LOT ID", "/sys/devices/system/chip-id/lot_id");
    DumpFileToFd(fd, "PRODUCT ID", "/sys/devices/system/chip-id/product_id");
    DumpFileToFd(fd, "REVISION", "/sys/devices/system/chip-id/revision");
    DumpFileToFd(fd, "RAW STR", "/sys/devices/system/chip-id/raw_str");
}

// Dump items related to CPUs
void DumpstateDevice::dumpCpuSection(int fd) {
    DumpFileToFd(fd, "CPU present", "/sys/devices/system/cpu/present");
    DumpFileToFd(fd, "CPU online", "/sys/devices/system/cpu/online");
    RunCommandToFd(fd, "CPU time-in-state", {"/vendor/bin/sh", "-c",
                   "for cpu in /sys/devices/system/cpu/cpu*; do "
                       "f=$cpu/cpufreq/stats/time_in_state; "
                       "if [ ! -f $f ]; then continue; fi; "
                       "echo $f:; cat $f; "
                       "done"});
    RunCommandToFd(fd, "CPU cpuidle", {"/vendor/bin/sh", "-c",
                   "for cpu in /sys/devices/system/cpu/cpu*; do "
                       "for d in $cpu/cpuidle/state*; do "
                           "if [ ! -d $d ]; then continue; fi; "
                           "echo \"$d: `cat $d/name` `cat $d/desc` `cat $d/time` `cat $d/usage`\"; "
                           "done; "
                       "done"});
    DumpFileToFd(fd, "INTERRUPTS", "/proc/interrupts");
}

// Dump items related to Devfreq & BTS
void DumpstateDevice::dumpDevfreqSection(int fd) {
    DumpFileToFd(fd, "MIF DVFS",
                 "/sys/devices/platform/17000010.devfreq_mif/devfreq/17000010.devfreq_mif/time_in_state");
    DumpFileToFd(fd, "INT DVFS",
                 "/sys/devices/platform/17000020.devfreq_int/devfreq/17000020.devfreq_int/time_in_state");
    DumpFileToFd(fd, "INTCAM DVFS",
                 "/sys/devices/platform/17000030.devfreq_intcam/devfreq/17000030.devfreq_intcam/time_in_state");
    DumpFileToFd(fd, "DISP DVFS",
                 "/sys/devices/platform/17000040.devfreq_disp/devfreq/17000040.devfreq_disp/time_in_state");
    DumpFileToFd(fd, "CAM DVFS",
                 "/sys/devices/platform/17000050.devfreq_cam/devfreq/17000050.devfreq_cam/time_in_state");
    DumpFileToFd(fd, "TNR DVFS",
                 "/sys/devices/platform/17000060.devfreq_tnr/devfreq/17000060.devfreq_tnr/time_in_state");
    DumpFileToFd(fd, "MFC DVFS",
                 "/sys/devices/platform/17000070.devfreq_mfc/devfreq/17000070.devfreq_mfc/time_in_state");
    DumpFileToFd(fd, "BO DVFS",
                 "/sys/devices/platform/17000080.devfreq_bo/devfreq/17000080.devfreq_bo/time_in_state");
    DumpFileToFd(fd, "BTS stats", "/sys/devices/platform/exynos-bts/bts_stats");
}

// Dump items related to memory
void DumpstateDevice::dumpMemorySection(int fd) {
    RunCommandToFd(fd, "ION HEAPS", {"/vendor/bin/sh", "-c",
                   "for d in $(ls -d /d/ion/*); do "
                       "if [ -f $d ]; then "
                           "echo --- $d; cat $d; "
                       "else "
                           "for f in $(ls $d); do "
                               "echo --- $d/$f; cat $d/$f; "
                               "done; "
                        "fi; "
                        "done"});
    DumpFileToFd(fd, "dmabuf info", "/d/dma_buf/bufinfo");
    DumpFileToFd(fd, "Page Pinner - longterm pin", "/sys/kernel/debug/page_pinner/buffer");
    RunCommandToFd(fd, "Pixel CMA stat", {"/vendor/bin/sh", "-c",
                   "for d in $(ls -d /sys/kernel/pixel_stat/mm/cma/*); do "
                       "if [ -f $d ]; then "
                           "echo --- $d; cat $d; "
                       "else "
                           "for f in $(ls $d); do "
                               "echo --- $d/$f; cat $d/$f; "
                               "done; "
                        "fi; "
                        "done"});
}

static void DumpF2FS(int fd) {
    DumpFileToFd(fd, "F2FS", "/sys/kernel/debug/f2fs/status");
    RunCommandToFd(fd, "F2FS - fsck time (ms)", {"/vendor/bin/sh", "-c", "getprop ro.boottime.init.fsck.data"});
    RunCommandToFd(fd, "F2FS - checkpoint=disable time (ms)", {"/vendor/bin/sh", "-c", "getprop ro.boottime.init.mount.data"});
}

static void DumpUFS(int fd) {
    DumpFileToFd(fd, "UFS model", "/sys/block/sda/device/model");
    DumpFileToFd(fd, "UFS rev", "/sys/block/sda/device/rev");
    DumpFileToFd(fd, "UFS size", "/sys/block/sda/size");

    DumpFileToFd(fd, "UFS Slow IO Read", "/dev/sys/block/bootdevice/slowio_read_cnt");
    DumpFileToFd(fd, "UFS Slow IO Write", "/dev/sys/block/bootdevice/slowio_write_cnt");
    DumpFileToFd(fd, "UFS Slow IO Unmap", "/dev/sys/block/bootdevice/slowio_unmap_cnt");
    DumpFileToFd(fd, "UFS Slow IO Sync", "/dev/sys/block/bootdevice/slowio_sync_cnt");

    RunCommandToFd(fd, "UFS err_stats", {"/vendor/bin/sh", "-c",
                       "path=\"/dev/sys/block/bootdevice/err_stats\"; "
                       "for node in `ls $path/* | grep -v reset_err_status`; do "
                       "printf \"%s:%d\\n\" $(basename $node) $(cat $node); done;"});


    RunCommandToFd(fd, "UFS io_stats", {"/vendor/bin/sh", "-c",
                       "path=\"/dev/sys/block/bootdevice/io_stats\"; "
                       "printf \"\\t\\t%-10s %-10s %-10s %-10s %-10s %-10s\\n\" "
                       "ReadCnt ReadBytes WriteCnt WriteBytes RWCnt RWBytes; "
                       "str=$(cat $path/*_start); arr=($str); "
                       "printf \"Started: \\t%-10s %-10s %-10s %-10s %-10s %-10s\\n\" "
                       "${arr[1]} ${arr[0]} ${arr[5]} ${arr[4]} ${arr[3]} ${arr[2]}; "
                       "str=$(cat $path/*_complete); arr=($str); "
                       "printf \"Completed: \\t%-10s %-10s %-10s %-10s %-10s %-10s\\n\" "
                       "${arr[1]} ${arr[0]} ${arr[5]} ${arr[4]} ${arr[3]} ${arr[2]}; "
                       "str=$(cat $path/*_maxdiff); arr=($str); "
                       "printf \"MaxDiff: \\t%-10s %-10s %-10s %-10s %-10s %-10s\\n\\n\" "
                       "${arr[1]} ${arr[0]} ${arr[5]} ${arr[4]} ${arr[3]} ${arr[2]}; "});

    RunCommandToFd(fd, "UFS req_stats", {"/vendor/bin/sh", "-c",
                       "path=\"/dev/sys/block/bootdevice/req_stats\"; "
                       "printf \"\\t%-10s %-10s %-10s %-10s %-10s %-10s\\n\" "
                       "All Write Read Security Flush Discard; "
                       "str=$(cat $path/*_min); arr=($str); "
                       "printf \"Min:\\t%-10s %-10s %-10s %-10s %-10s %-10s\\n\" "
                       "${arr[0]} ${arr[5]} ${arr[3]} ${arr[4]} ${arr[2]} ${arr[1]}; "
                       "str=$(cat $path/*_max); arr=($str); "
                       "printf \"Max:\\t%-10s %-10s %-10s %-10s %-10s %-10s\\n\" "
                       "${arr[0]} ${arr[5]} ${arr[3]} ${arr[4]} ${arr[2]} ${arr[1]}; "
                       "str=$(cat $path/*_avg); arr=($str); "
                       "printf \"Avg.:\\t%-10s %-10s %-10s %-10s %-10s %-10s\\n\" "
                       "${arr[0]} ${arr[5]} ${arr[3]} ${arr[4]} ${arr[2]} ${arr[1]}; "
                       "str=$(cat $path/*_sum); arr=($str); "
                       "printf \"Count:\\t%-10s %-10s %-10s %-10s %-10s %-10s\\n\\n\" "
                       "${arr[0]} ${arr[5]} ${arr[3]} ${arr[4]} ${arr[2]} ${arr[1]};"});

    std::string ufs_health = "for f in $(find /dev/sys/block/bootdevice/health_descriptor -type f); do if [[ -r $f && -f $f ]]; then echo --- $f; cat $f; echo ''; fi; done";
    RunCommandToFd(fd, "UFS health", {"/vendor/bin/sh", "-c", ufs_health.c_str()});
}

// Dump items related to storage
void DumpstateDevice::dumpStorageSection(int fd) {
    DumpF2FS(fd);
    DumpUFS(fd);
}

// Dump items related to display
void DumpstateDevice::dumpDisplaySection(int fd) {
    DumpFileToFd(fd, "CRTC-0 underrun count", "/sys/kernel/debug/dri/0/crtc-0/underrun_cnt");
    DumpFileToFd(fd, "CRTC-0 crc count", "/sys/kernel/debug/dri/0/crtc-0/crc_cnt");
    DumpFileToFd(fd, "CRTC-0 ecc count", "/sys/kernel/debug/dri/0/crtc-0/ecc_cnt");
    DumpFileToFd(fd, "CRTC-0 idma err count", "/sys/kernel/debug/dri/0/crtc-0/idma_err_cnt");
    DumpFileToFd(fd, "CRTC-0 event log", "/sys/kernel/debug/dri/0/crtc-0/event");
    DumpFileToFd(fd, "CRTC-1 underrun count", "/sys/kernel/debug/dri/0/crtc-1/underrun_cnt");
    DumpFileToFd(fd, "CRTC-1 crc count", "/sys/kernel/debug/dri/0/crtc-1/crc_cnt");
    DumpFileToFd(fd, "CRTC-1 ecc count", "/sys/kernel/debug/dri/0/crtc-1/ecc_cnt");
    DumpFileToFd(fd, "CRTC-1 idma err count", "/sys/kernel/debug/dri/0/crtc-1/idma_err_cnt");
    DumpFileToFd(fd, "CRTC-1 event log", "/sys/kernel/debug/dri/0/crtc-1/event");
    RunCommandToFd(fd, "libdisplaycolor", {"/vendor/bin/dumpsys", "displaycolor", "-v"},
                   CommandOptions::WithTimeout(2).Build());
    DumpFileToFd(fd, "Primary panel extra info", "/sys/devices/platform/exynos-drm/primary-panel/panel_extinfo");
    DumpFileToFd(fd, "secondary panel extra info", "/sys/devices/platform/exynos-drm/secondary-panel/panel_extinfo");
}

// Dump items related to AoC
void DumpstateDevice::dumpAoCSection(int fd) {
    DumpFileToFd(fd, "AoC Service Status", "/sys/devices/platform/19000000.aoc/services");
    DumpFileToFd(fd, "AoC Restarts", "/sys/devices/platform/19000000.aoc/restart_count");
    DumpFileToFd(fd, "AoC Coredumps", "/sys/devices/platform/19000000.aoc/coredump_count");
    DumpFileToFd(fd, "AoC ring buf wake", "/sys/devices/platform/19000000.aoc/control/ring_buffer_wakeup");
    DumpFileToFd(fd, "AoC host ipc wake", "/sys/devices/platform/19000000.aoc/control/host_ipc_wakeup");
    DumpFileToFd(fd, "AoC usf wake", "/sys/devices/platform/19000000.aoc/control/usf_wakeup");
    DumpFileToFd(fd, "AoC audio wake", "/sys/devices/platform/19000000.aoc/control/audio_wakeup");
    DumpFileToFd(fd, "AoC logging wake", "/sys/devices/platform/19000000.aoc/control/logging_wakeup");
    DumpFileToFd(fd, "AoC hotword wake", "/sys/devices/platform/19000000.aoc/control/hotword_wakeup");
    DumpFileToFd(fd, "AoC memory exception wake", "/sys/devices/platform/19000000.aoc/control/memory_exception");
    DumpFileToFd(fd, "AoC memory votes", "/sys/devices/platform/19000000.aoc/control/memory_votes");
    RunCommandToFd(fd, "AoC Heap Stats (A32)",
      {"/vendor/bin/sh", "-c", "echo 'dbg heap -c 1' > /dev/acd-debug; timeout 0.1 cat /dev/acd-debug"},
      CommandOptions::WithTimeout(1).Build());
    RunCommandToFd(fd, "AoC Heap Stats (F1)",
      {"/vendor/bin/sh", "-c", "echo 'dbg heap -c 2' > /dev/acd-debug; timeout 0.1 cat /dev/acd-debug"},
      CommandOptions::WithTimeout(1).Build());
    RunCommandToFd(fd, "AoC Heap Stats (HF0)",
      {"/vendor/bin/sh", "-c", "echo 'dbg heap -c 3' > /dev/acd-debug; timeout 0.1 cat /dev/acd-debug"},
      CommandOptions::WithTimeout(1).Build());
    RunCommandToFd(fd, "AoC Heap Stats (HF1)",
      {"/vendor/bin/sh", "-c", "echo 'dbg heap -c 4' > /dev/acd-debug; timeout 0.1 cat /dev/acd-debug"},
      CommandOptions::WithTimeout(1).Build());
    RunCommandToFd(fd, "AoC MIF Stats",
      {"/vendor/bin/sh", "-c", "echo 'mif details' > /dev/acd-debug; timeout 0.1 cat /dev/acd-debug"},
      CommandOptions::WithTimeout(1).Build());
}

// Dump items related to sensors usf.
void DumpstateDevice::dumpSensorsUSFSection(int fd) {
    CommandOptions options = CommandOptions::WithTimeout(2).Build();
    RunCommandToFd(fd, "USF statistics",
                   {"/vendor/bin/sh", "-c", "usf_stats get --all"},
                   options);
    if (!PropertiesHelper::IsUserBuild()) {
        // Not a user build, if this is also not a production device dump the USF registry.
        std::string hwRev = android::base::GetProperty(HW_REVISION, "");
        if (hwRev.find("PROTO") != std::string::npos ||
            hwRev.find("EVT") != std::string::npos ||
            hwRev.find("DVT") != std::string::npos) {
            RunCommandToFd(fd, "USF Registry",
                           {"/vendor/bin/sh", "-c", "usf_reg_edit save -"},
                           options);
        }
    }
}

// Gzip binary data and dump to fd in base64 format. Cmd to decode is also attached.
void dumpGzippedFileInBase64ToFd(int fd, const char* title, const char* file_path) {
    auto cmd = android::base::StringPrintf("echo 'base64 -d <<EOF | gunzip' ; "
               "/vendor/bin/gzip < \"%s\" | /vendor/bin/base64 ; "
               "echo 'EOF'", file_path);
    RunCommandToFd(fd, title,
                   {"/vendor/bin/sh", "-c", cmd.c_str()},
                   CommandOptions::WithTimeout(10).Build());
}

struct abl_log_header {
    uint64_t i;
    uint64_t size;
    char buf[];
} __attribute__((packed));

// Dump items related to ramdump.
void DumpstateDevice::dumpRamdumpSection(int fd) {
    std::string abl_log;
    if (android::base::ReadFileToString("/mnt/vendor/ramdump/abl.log", &abl_log)) {
        const struct abl_log_header *header = (const struct abl_log_header*) abl_log.c_str();
        android::base::WriteStringToFd(android::base::StringPrintf(
                    "------ Ramdump misc file: abl.log (i:0x%" PRIx64 " size:0x%" PRIx64 ") ------\n%s\n",
                    header->i, header->size, std::string(header->buf, header->i).c_str()), fd);
    } else {
        android::base::WriteStringToFd("*** Ramdump misc file: abl.log: File not found\n", fd);
    }
    dumpGzippedFileInBase64ToFd(
        fd, "Ramdump misc file: acpm.lst (gzipped in base64)", "/mnt/vendor/ramdump/acpm.lst");
    dumpGzippedFileInBase64ToFd(
        fd, "Ramdump misc file: s2d.lst (gzipped in base64)", "/mnt/vendor/ramdump/s2d.lst");
}

// Dump items that don't fit well into any other section
void DumpstateDevice::dumpMiscSection(int fd) {
    RunCommandToFd(fd, "VENDOR PROPERTIES", {"/vendor/bin/getprop"});
    DumpFileToFd(fd, "VENDOR PROC DUMP", "/proc/vendor_sched/dump_task");
}

// Dump items related to GSC
void DumpstateDevice::dumpGscSection(int fd) {
    RunCommandToFd(fd, "Citadel VERSION", {"vendor/bin/hw/citadel_updater", "-lv"});
    RunCommandToFd(fd, "Citadel STATS", {"vendor/bin/hw/citadel_updater", "--stats"});
    RunCommandToFd(fd, "GSC DEBUG DUMP", {"vendor/bin/hw/citadel_updater", "-D"});
}

// Dump essential camera debugging logs
void DumpstateDevice::dumpCameraSection(int fd) {
    RunCommandToFd(fd, "Camera HAL Graph State Dump", {"/vendor/bin/sh", "-c",
                       "for f in $(ls -t /data/vendor/camera/hal_graph_state*.txt |head -1); do "
                       "echo $f ; cat $f ; done"},
                       CommandOptions::WithTimeout(4).Build());
}

void DumpstateDevice::dumpTrustySection(int fd) {
    DumpFileToFd(fd, "Trusty TEE0 Logs", "/dev/trusty-log0");
}

// Dump items related to modem
void DumpstateDevice::dumpModemSection(int fd) {
    DumpFileToFd(fd, "Modem Stat", "/data/vendor/modem_stat/debug.txt");
    RunCommandToFd(fd, "Modem SSR history", {"/vendor/bin/sh", "-c",
                       "for f in $(ls /data/vendor/ssrdump/crashinfo_modem*); do "
                       "echo $f ; cat $f ; done"},
                       CommandOptions::WithTimeout(2).Build());
    RunCommandToFd(fd, "RFSD error log", {"/vendor/bin/sh", "-c",
                       "for f in $(ls /data/vendor/log/rfsd/rfslog_*); do "
                       "echo $f ; cat $f ; done"},
                       CommandOptions::WithTimeout(2).Build());
}

static void *dumpModemThread(void *data) {
    std::string modemLogDir = MODEM_LOG_DIRECTORY;
    std::string extendedLogDir = MODEM_EXTENDED_LOG_DIRECTORY;
    std::string tcpdumpLogDir = TCPDUMP_LOG_DIRECTORY;
    static const std::string sectionName = "modem";

    const std::string modemLogCombined = modemLogDir + "/modem_log_all.tar";
    const std::string modemLogAllDir = modemLogDir + "/modem_log";

    long fdModem = (long)data;

    ALOGD("dumpModemThread started\n");

    RunCommandToFd(STDOUT_FILENO, "MKDIR MODEM LOG", {"/vendor/bin/mkdir", "-p", modemLogAllDir.c_str()}, CommandOptions::WithTimeout(2).Build());

    bool modemLogEnabled = android::base::GetBoolProperty(MODEM_LOGGING_PERSIST_PROPERTY, false);
    if (modemLogEnabled && android::base::GetProperty(MODEM_LOGGING_PATH_PROPERTY, "") == MODEM_LOG_DIRECTORY) {
        bool modemLogStarted = android::base::GetBoolProperty(MODEM_LOGGING_STATUS_PROPERTY, false);
        int maxFileNum = android::base::GetIntProperty(MODEM_LOGGING_NUMBER_BUGREPORT_PROPERTY, 100);

        if (modemLogStarted) {
            android::base::SetProperty(MODEM_LOGGING_PROPERTY, "false");
            ALOGD("Stopping modem logging...\n");
        } else {
            ALOGD("modem logging is not running\n");
        }

        for (int i = 0; i < 15; i++) {
            if (!android::base::GetBoolProperty(MODEM_LOGGING_STATUS_PROPERTY, false)) {
                ALOGD("modem logging stopped\n");
                sleep(1);
                break;
            }
            sleep(1);
        }

        dumpLogs(STDOUT_FILENO, modemLogDir, modemLogAllDir, maxFileNum, MODEM_LOG_PREFIX);

        if (modemLogStarted) {
            ALOGD("Restarting modem logging...\n");
            android::base::SetProperty(MODEM_LOGGING_PROPERTY, "true");
        }
    }

    if (!PropertiesHelper::IsUserBuild()) {
        bool gpsLogEnabled = android::base::GetBoolProperty(GPS_LOGGING_STATUS_PROPERTY, false);
        bool tcpdumpEnabled = android::base::GetBoolProperty(TCPDUMP_PERSIST_PROPERTY, false);
        bool cameraLogsEnabled = android::base::GetBoolProperty(
                "vendor.camera.debug.camera_performance_analyzer.attach_to_bugreport", true);

        if (tcpdumpEnabled) {
            dumpLogs(STDOUT_FILENO, tcpdumpLogDir, modemLogAllDir, android::base::GetIntProperty(TCPDUMP_NUMBER_BUGREPORT, 5), TCPDUMP_LOG_PREFIX);
        }

        if (gpsLogEnabled) {
            dumpGpsLogs(STDOUT_FILENO, modemLogAllDir);
        } else {
            ALOGD("gps logging is not running\n");
        }

        if (cameraLogsEnabled) {
            dumpCameraLogs(STDOUT_FILENO, modemLogAllDir);
        }

        dumpLogs(STDOUT_FILENO, extendedLogDir, modemLogAllDir, 50, EXTENDED_LOG_PREFIX);
        dumpRilLogs(STDOUT_FILENO, modemLogAllDir);
        dumpNetmgrLogs(modemLogAllDir);
        dumpModemEFS(modemLogAllDir);
    }

    ALOGD("Going to compress logs\n");

    RunCommandToFd(STDOUT_FILENO, "TAR LOG", {"/vendor/bin/tar", "cvf", modemLogCombined.c_str(), "-C", modemLogAllDir.c_str(), "."}, CommandOptions::WithTimeout(25).Build());
    RunCommandToFd(STDOUT_FILENO, "CHG PERM", {"/vendor/bin/chmod", "a+w", modemLogCombined.c_str()}, CommandOptions::WithTimeout(2).Build());

    std::vector<uint8_t> buffer(65536);
    android::base::unique_fd fdLog(TEMP_FAILURE_RETRY(open(modemLogCombined.c_str(), O_RDONLY | O_CLOEXEC | O_NONBLOCK)));

    ALOGD("Going to write to dumpstate board binary\n");
    if (fdLog >= 0) {
        while (1) {
            ssize_t bytes_read = TEMP_FAILURE_RETRY(read(fdLog, buffer.data(), buffer.size()));

            if (bytes_read == 0) {
                break;
            } else if (bytes_read < 0) {
                ALOGD("read(%s): %s\n", modemLogCombined.c_str(), strerror(errno));
                break;
            }

            ssize_t result = TEMP_FAILURE_RETRY(write(fdModem, buffer.data(), bytes_read));

            if (result != bytes_read) {
                ALOGD("Failed to write %ld bytes, actually written: %ld", bytes_read, result);
                break;
            }
        }
    }

    ALOGD("Going to remove logs\n");

    RunCommandToFd(STDOUT_FILENO, "RM MODEM DIR", { "/vendor/bin/rm", "-r", modemLogAllDir.c_str()}, CommandOptions::WithTimeout(2).Build());
    RunCommandToFd(STDOUT_FILENO, "RM LOG", { "/vendor/bin/rm", modemLogCombined.c_str()}, CommandOptions::WithTimeout(2).Build());

    ALOGD("dumpModemThread finished\n");

    return NULL;
}

// Methods from ::android::hardware::dumpstate::V1_0::IDumpstateDevice follow.
Return<void> DumpstateDevice::dumpstateBoard(const hidl_handle &handle) {
// Ignore return value, just return an empty status.
    dumpstateBoard_1_1(handle, DumpstateMode::DEFAULT, 30 * 1000 /* timeoutMillis */);
    return Void();
}

// Methods from ::android::hardware::dumpstate::V1_1::IDumpstateDevice follow.
Return<DumpstateStatus> DumpstateDevice::dumpstateBoard_1_1(const hidl_handle& handle,
                                                            const DumpstateMode mode,
                                                            const uint64_t timeoutMillis) {
    // Unused arguments.
    (void) timeoutMillis;

    if (handle == nullptr || handle->numFds < 1) {
        ALOGE("no FDs\n");
        return DumpstateStatus::ILLEGAL_ARGUMENT;
    }

    int fd = handle->data[0];
    if (fd < 0) {
        ALOGE("invalid FD: %d\n", handle->data[0]);
        return DumpstateStatus::ILLEGAL_ARGUMENT;
    }

    if (mode == DumpstateMode::WEAR) {
        // We aren't a Wear device.
        ALOGE("Unsupported mode: %d\n", mode);
        return DumpstateStatus::UNSUPPORTED_MODE;
    } else if (mode < DumpstateMode::FULL || mode > DumpstateMode::PROTO) {
        ALOGE("Invalid mode: %d\n", mode);
        return DumpstateStatus::ILLEGAL_ARGUMENT;
    }

    // Create thread to collect modem related logs
    pthread_t modemThreadHandle = 0;
    if (getVerboseLoggingEnabled()) {
        if (handle->numFds < 2) {
            ALOGE("no FD for modem\n");
        } else {
            int fdModem = handle->data[1];
            if (pthread_create(&modemThreadHandle, NULL, dumpModemThread, (void *)((long)fdModem)) != 0) {
                ALOGE("could not create thread for dumpModem\n");
            }
        }
    } else {
        ALOGD("Verbose logging is not enabled\n");
    }

    dumpTextSection(fd, kAllSections);

    if (modemThreadHandle) {
        pthread_join(modemThreadHandle, NULL);
    }

    return DumpstateStatus::OK;
}

Return<void> DumpstateDevice::setVerboseLoggingEnabled(const bool enable) {
    ::android::base::SetProperty(kVerboseLoggingProperty, enable ? "true" : "false");
    return Void();
}

Return<bool> DumpstateDevice::getVerboseLoggingEnabled() {
    return ::android::base::GetBoolProperty(kVerboseLoggingProperty, false);
}

// Since HALs that support the debug() interface are automatically invoked during
// bugreport generation and we don't want to generate a second copy of the same
// data that will go into dumpstate_board.txt, this function will only do
// something if it is called with an option, e.g.
//   lshal debug android.hardware.dumpstate@1.0::IDumpstateDevice/default all
//
// Also, note that sections which generate attachments and/or binary data when
// included in a bugreport are not available through the debug() interface.
Return<void> DumpstateDevice::debug(const hidl_handle &handle, const hidl_vec<hidl_string> &args) {
    // Exit when dump is completed since this is a lazy HAL.
    addPostCommandTask([]() {
        exit(0);
    });

    if (handle == nullptr || handle->numFds < 1 || args.size() != 1) {
        return Void();
    }

    int fd = handle->data[0];
    dumpTextSection(fd, static_cast<std::string>(args[0]));

    fsync(fd);
    return Void();
}


}  // namespace implementation
}  // namespace V1_1
}  // namespace dumpstate
}  // namespace hardware
}  // namespace android
