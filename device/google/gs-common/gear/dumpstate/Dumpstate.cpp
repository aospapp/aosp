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
#define ATRACE_TAG ATRACE_TAG_ALWAYS

#include <inttypes.h>

#include <android-base/file.h>
#include <android-base/stringprintf.h>
#include <android-base/properties.h>
#include <android-base/unique_fd.h>
#include <cutils/trace.h>
#include <log/log.h>
#include <sys/stat.h>
#include <dump/pixel_dump.h>
#include "Dumpstate.h"

#include "DumpstateUtil.h"

#define HW_REVISION "ro.boot.hardware.revision"

using android::os::dumpstate::CommandOptions;
using android::os::dumpstate::DumpFileToFd;
using android::os::dumpstate::PropertiesHelper;
using android::os::dumpstate::RunCommandToFd;

namespace aidl {
namespace android {
namespace hardware {
namespace dumpstate {

typedef std::chrono::time_point<std::chrono::steady_clock> timepoint_t;

const char kVerboseLoggingProperty[] = "persist.vendor.verbose_logging_enabled";

timepoint_t startSection(int fd, const std::string &sectionName) {
    ATRACE_BEGIN(sectionName.c_str());
    ::android::base::WriteStringToFd(
            "\n"
            "------ Section start: " + sectionName + " ------\n"
            "\n", fd);
    return std::chrono::steady_clock::now();
}

void endSection(int fd, const std::string &sectionName, timepoint_t startTime) {
    ATRACE_END();
    auto endTime = std::chrono::steady_clock::now();
    auto elapsedMsec = std::chrono::duration_cast<std::chrono::milliseconds>
            (endTime - startTime).count();

    ::android::base::WriteStringToFd(
            "\n"
            "------ Section end: " + sectionName + " ------\n"
            "Elapsed msec: " + std::to_string(elapsedMsec) + "\n"
            "\n", fd);
}

// Dump data requested by an argument to the "dump" interface, or help info
// if the specified section is not supported.
void Dumpstate::dumpTextSection(int fd, const std::string &sectionName) {
    bool dumpAll = (sectionName == kAllSections);
    std::string dumpFiles;

    // Execute all or designated programs under vendor/bin/dump/
    std::unique_ptr<DIR, decltype(&closedir)> dir(opendir("/vendor/bin/dump"), closedir);
    if (!dir) {
        ALOGE("Fail To Open Dir vendor/bin/dump/");
        ::android::base::WriteStringToFd("Fail To Open Dir vendor/bin/dump/\n", fd);
        return;
    }
    dirent *entry;
    while ((entry = readdir(dir.get())) != nullptr) {
        // Skip '.', '..'
        if (entry->d_name[0] == '.') {
            continue;
        }
        std::string bin(entry->d_name);
        dumpFiles = dumpFiles + " " + bin;
        if (dumpAll || sectionName == bin) {
            auto startTime = startSection(fd, bin);
            RunCommandToFd(fd, "/vendor/bin/dump/"+bin, {"/vendor/bin/dump/"+bin}, CommandOptions::WithTimeout(15).Build());
            endSection(fd, bin, startTime);
            if (!dumpAll) {
                return;
            }
        }
    }

    if (dumpAll) {
        RunCommandToFd(fd, "VENDOR PROPERTIES", {"/vendor/bin/getprop"});
        return;
    }

    // An unsupported section was requested on the command line
    ::android::base::WriteStringToFd("Unrecognized text section: " + sectionName + "\n", fd);
    ::android::base::WriteStringToFd("Try \"" + kAllSections + "\" or one of the following:", fd);
    ::android::base::WriteStringToFd(dumpFiles, fd);
    ::android::base::WriteStringToFd("\nNote: sections with attachments (e.g. dump_soc) are"
                                   "not available from the command line.\n", fd);
}

void Dumpstate::dumpLogSection(int fd, int fd_bin)
{
    std::string logDir = MODEM_LOG_DIRECTORY;
    const std::string logCombined = logDir + "/combined_logs.tar";
    const std::string logAllDir = logDir + "/all_logs";

    RunCommandToFd(fd, "MKDIR LOG", {"/vendor/bin/mkdir", "-p", logAllDir.c_str()}, CommandOptions::WithTimeout(2).Build());

    dumpTextSection(fd, kAllSections);

    RunCommandToFd(fd, "TAR LOG", {"/vendor/bin/tar", "cvf", logCombined.c_str(), "-C", logAllDir.c_str(), "."}, CommandOptions::WithTimeout(20).Build());
    RunCommandToFd(fd, "CHG PERM", {"/vendor/bin/chmod", "a+w", logCombined.c_str()}, CommandOptions::WithTimeout(2).Build());

    std::vector<uint8_t> buffer(65536);
    ::android::base::unique_fd fdLog(TEMP_FAILURE_RETRY(open(logCombined.c_str(), O_RDONLY | O_CLOEXEC | O_NONBLOCK)));

    if (fdLog >= 0) {
        while (1) {
            ssize_t bytes_read = TEMP_FAILURE_RETRY(read(fdLog, buffer.data(), buffer.size()));

            if (bytes_read == 0) {
                break;
            } else if (bytes_read < 0) {
                ALOGD("read(%s): %s\n", logCombined.c_str(), strerror(errno));
                break;
            }

            ssize_t result = TEMP_FAILURE_RETRY(write(fd_bin, buffer.data(), bytes_read));

            if (result != bytes_read) {
                ALOGD("Failed to write %zd bytes, actually written: %zd", bytes_read, result);
                break;
            }
        }
    }

    RunCommandToFd(fd, "RM LOG DIR", { "/vendor/bin/rm", "-r", logAllDir.c_str()}, CommandOptions::WithTimeout(2).Build());
    RunCommandToFd(fd, "RM LOG", { "/vendor/bin/rm", logCombined.c_str()}, CommandOptions::WithTimeout(2).Build());
}

ndk::ScopedAStatus Dumpstate::dumpstateBoard(const std::vector<::ndk::ScopedFileDescriptor>& in_fds,
                                             IDumpstateDevice::DumpstateMode in_mode,
                                             int64_t in_timeoutMillis) {
    ATRACE_BEGIN("dumpstateBoard");
    // Unused arguments.
    (void) in_timeoutMillis;

    if (in_mode < IDumpstateDevice::DumpstateMode::FULL || in_mode > IDumpstateDevice::DumpstateMode::PROTO) {
        ALOGE("Invalid mode: %d\n", in_mode);
        return ndk::ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT, "Invalid mode");
    }

    if (in_fds.size() < 1) {
        ALOGE("no FDs\n");
        return ndk::ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT,
                                                                "No file descriptor");
    }

    int fd = in_fds[0].get();
    if (fd < 0) {
        ALOGE("invalid FD: %d\n", fd);
        return ndk::ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT,
                                                                "Invalid file descriptor");
    }

    if (in_fds.size() < 2) {
          ALOGE("no FD for dumpstate_board binary\n");
          dumpTextSection(fd, "");
    } else {
          int fd_bin = in_fds[1].get();
          dumpLogSection(fd, fd_bin);
    }

    ATRACE_END();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Dumpstate::setVerboseLoggingEnabled(bool in_enable) {
    ::android::base::SetProperty(kVerboseLoggingProperty, in_enable ? "true" : "false");
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Dumpstate::getVerboseLoggingEnabled(bool* _aidl_return) {
    *_aidl_return = ::android::base::GetBoolProperty(kVerboseLoggingProperty, false);
    return ndk::ScopedAStatus::ok();
}

// Since AIDLs that support the dump() interface are automatically invoked during
// bugreport generation and we don't want to generate a second copy of the same
// data that will go into dumpstate_board.txt, this function will only do
// something if it is called with an option, e.g.
//   dumpsys android.hardware.dumpstate.IDumpstateDevice/default all
//
// Also, note that sections which generate attachments and/or binary data when
// included in a bugreport are not available through the dump() interface.
binder_status_t Dumpstate::dump(int fd, const char** args, uint32_t numArgs) {

    if (numArgs != 1) {
        return STATUS_OK;
    }

    dumpTextSection(fd, static_cast<std::string>(args[0]));

    fsync(fd);
    return STATUS_OK;
}

}  // namespace dumpstate
}  // namespace hardware
}  // namespace android
}  // namespace aidl
