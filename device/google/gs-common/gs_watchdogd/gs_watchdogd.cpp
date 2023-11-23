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

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/stringprintf.h>

#include <errno.h>
#include <fcntl.h>
#include <glob.h>
#include <linux/watchdog.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define DEV_GLOB "/sys/devices/platform/*.watchdog_cl0/watchdog/watchdog*"

using android::base::Basename;
using android::base::StringPrintf;

int main(int argc, char** argv) {
    android::base::InitLogging(argv, &android::base::KernelLogger);

    int interval = 10;
    if (argc >= 2) interval = atoi(argv[1]);

    int margin = 10;
    if (argc >= 3) margin = atoi(argv[2]);

    LOG(INFO) << "gs_watchdogd started (interval " << interval << ", margin " << margin << ")!";

    glob_t globbuf;
    int ret = glob(DEV_GLOB, GLOB_MARK, nullptr, &globbuf);
    if (ret) {
        PLOG(ERROR) << "Failed to lookup glob " << DEV_GLOB << ": " << ret;
        return 1;
    }

    if (globbuf.gl_pathc > 1) {
        PLOG(WARNING) << "Multiple watchdog dev path found by " << DEV_GLOB;
    }

    std::string dev_path = StringPrintf("/dev/%s", Basename(globbuf.gl_pathv[0]).c_str());
    globfree(&globbuf);

    int fd = open(dev_path.c_str(), O_RDWR | O_CLOEXEC);
    if (fd == -1) {
        PLOG(ERROR) << "Failed to open " << dev_path;
        return 1;
    }

    int timeout = interval + margin;
    ret = ioctl(fd, WDIOC_SETTIMEOUT, &timeout);
    if (ret) {
        PLOG(ERROR) << "Failed to set timeout to " << timeout;
        ret = ioctl(fd, WDIOC_GETTIMEOUT, &timeout);
        if (ret) {
            PLOG(ERROR) << "Failed to get timeout";
        } else {
            if (timeout > margin) {
                interval = timeout - margin;
            } else {
                interval = 1;
            }
            LOG(WARNING) << "Adjusted interval to timeout returned by driver: "
                         << "timeout " << timeout << ", interval " << interval << ", margin "
                         << margin;
        }
    }

    while (true) {
        write(fd, "", 1);
        sleep(interval);
    }
}
