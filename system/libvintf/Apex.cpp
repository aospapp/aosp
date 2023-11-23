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
#include "Apex.h"

#include <android-base/format.h>
#include <android-base/logging.h>
#include <android-base/strings.h>

#include "com_android_apex.h"
#include "constants-private.h"

using android::base::StartsWith;

namespace android {
namespace vintf {
namespace details {

status_t Apex::DeviceVintfDirs(FileSystem* fileSystem, std::vector<std::string>* dirs,
                               std::string* error) {
    std::vector<std::string> vendor;
    std::vector<std::string> odm;

    // Update cached mtime_
    int64_t mtime;
    auto status = fileSystem->modifiedTime(kApexInfoFile, &mtime, error);

    if (status != OK) {
        switch (status) {
            case NAME_NOT_FOUND:
                status = OK;
                break;
            case -EACCES:
                // Don't error out on access errors, but log it
                LOG(WARNING) << "APEX Device VINTF Dirs: EACCES: "
                             << (error ? *error : "(unknown error message)");
                status = OK;
                break;
            default:
                break;
        }

        if ((status == OK) && (error)) {
            error->clear();
        }

        return status;
    }

    mtime_ = mtime;

    // Load apex-info-list
    std::string xml;
    status = fileSystem->fetch(kApexInfoFile, &xml, error);
    if (status == NAME_NOT_FOUND) {
        if (error) {
            error->clear();
        }
        return OK;
    }
    if (status != OK) return status;

    auto apexInfoList = com::android::apex::parseApexInfoList(xml.c_str());
    if (!apexInfoList.has_value()) {
        if (error) {
            *error = std::string("Not a valid XML ") + kApexInfoFile;
        }
        return UNKNOWN_ERROR;
    }

    // Get vendor apex vintf dirs
    for (const auto& apexInfo : apexInfoList->getApexInfo()) {
        // Skip non-active apexes
        if (!apexInfo.getIsActive()) continue;
        // Skip if no preinstalled paths. This shouldn't happen but XML schema says it's optional.
        if (!apexInfo.hasPreinstalledModulePath()) continue;

        const std::string& path = apexInfo.getPreinstalledModulePath();
        if (StartsWith(path, "/vendor/apex/") || StartsWith(path, "/system/vendor/apex/")) {
            dirs->push_back(fmt::format("/apex/{}/" VINTF_SUB_DIR, apexInfo.getModuleName()));
        }
    }
    return OK;
}

// Returns true when /apex/apex-info-list.xml is updated
bool Apex::HasUpdate(FileSystem* fileSystem) const {
    int64_t mtime{};
    std::string error;
    status_t status = fileSystem->modifiedTime(kApexInfoFile, &mtime, &error);
    if (status == NAME_NOT_FOUND) {
        return false;
    }
    if (status != OK) {
        LOG(ERROR) << error;
        return false;
    }
    return mtime != mtime_;
}

}  // namespace details
}  // namespace vintf
}  // namespace android
