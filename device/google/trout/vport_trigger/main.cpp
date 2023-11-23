/*
 * Copyright (C) 2021 The Android Open Source Project
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
 *
 * Copied from device/google/cuttlefish/guest/commands/vport_trigger in Android R
 * commit hash :69e0935b2929b26c5fc29646eef1d33c39e344f1
 * (Migrate vport_trigger's Android.mk to Android.bp)
 *
 */

#include <cutils/properties.h>

#include <sys/cdefs.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>

#include <cerrno>
#include <climits>
#include <string>
#include "android-base/file.h"
#include "android-base/logging.h"

int main(int argc __unused, char* argv[] __unused) {
    const char sysfs_base[] = "/sys/class/virtio-ports/";
    DIR* dir = opendir(sysfs_base);

    if (dir) {
        dirent* dp;
        while ((dp = readdir(dir)) != nullptr) {
            std::string dirname = dp->d_name;
            if (dirname == "." || dirname == "..") {
                continue;
            }
            std::string sysfs(sysfs_base + dirname + "/name");
            struct stat st;
            if (stat(sysfs.c_str(), &st)) {
                continue;
            }
            std::string content;
            if (!android::base::ReadFileToString(sysfs, &content, true)) {
                continue;
            }
            if (content.empty()) {
                continue;
            }
            // Removing the last \n character
            content.erase(content.end() - 1);
            std::string dev("/dev/" + dirname);
            std::string propname("vendor.ser." + content);
            property_set(propname.c_str(), dev.c_str());
        }
    }
    return 0;
}
