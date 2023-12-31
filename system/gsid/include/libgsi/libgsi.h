//
// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

#pragma once

#include <string>

#include <android-base/file.h>
#include <android-base/strings.h>

namespace android {
namespace gsi {

#define DSU_METADATA_PREFIX "/metadata/gsi/dsu/"

// These files need to be globally readable so that fs_mgr_fstab, which is
// statically linked into processes, can return consistent result for non-root
// processes:
// * kDsuActiveFile
// * kGsiBootedIndicatorFile
// * kGsiLpNamesFile
// * DsuMetadataKeyDirFile(slot)

static constexpr char kGsiBootedIndicatorFile[] = DSU_METADATA_PREFIX "booted";

static constexpr char kGsiLpNamesFile[] = DSU_METADATA_PREFIX "lp_names";

static constexpr char kDsuActiveFile[] = DSU_METADATA_PREFIX "active";

static constexpr char kDsuAvbKeyDir[] = DSU_METADATA_PREFIX "avb/";

static constexpr char kDsuMetadataKeyDirPrefix[] = "/metadata/vold/metadata_encryption/dsu/";

static constexpr char kDsuSDPrefix[] = "/mnt/media_rw/";

static inline std::string DsuLpMetadataFile(const std::string& dsu_slot) {
    return DSU_METADATA_PREFIX + dsu_slot + "/lp_metadata";
}

static inline std::string DsuInstallDirFile(const std::string& dsu_slot) {
    return DSU_METADATA_PREFIX + dsu_slot + "/install_dir";
}

static inline std::string DsuMetadataKeyDirFile(const std::string& dsu_slot) {
    return DSU_METADATA_PREFIX + dsu_slot + "/metadata_encryption_dir";
}

static inline std::string DefaultDsuMetadataKeyDir(const std::string& dsu_slot) {
    return kDsuMetadataKeyDirPrefix + dsu_slot;
}

static inline std::string GetDsuMetadataKeyDir(const std::string& dsu_slot) {
    auto key_dir_file = DsuMetadataKeyDirFile(dsu_slot);
    std::string key_dir;
    if (android::base::ReadFileToString(key_dir_file, &key_dir) &&
        android::base::StartsWith(key_dir, kDsuMetadataKeyDirPrefix)) {
        return key_dir;
    }
    return DefaultDsuMetadataKeyDir(dsu_slot);
}

// install_dir "/data/gsi/dsu/dsu" has a slot name "dsu"
// install_dir "/data/gsi/dsu/dsu2" has a slot name "dsu2"
std::string GetDsuSlot(const std::string& install_dir);

static constexpr char kGsiBootedProp[] = "ro.gsid.image_running";

static constexpr char kGsiInstalledProp[] = "gsid.image_installed";

static constexpr char kDsuPostfix[] = "_gsi";

inline constexpr char kDsuScratch[] = "scratch_gsi";
inline constexpr char kDsuUserdata[] = "userdata_gsi";

static constexpr int kMaxBootAttempts = 1;

// Get the currently active dsu slot
// Return true on success
static inline bool GetActiveDsu(std::string* active_dsu) {
    return android::base::ReadFileToString(kDsuActiveFile, active_dsu);
}

// Returns true if the currently running system image is a live GSI.
bool IsGsiRunning();

// Return true if a GSI is installed (but not necessarily running).
bool IsGsiInstalled();

// Set the GSI as no longer bootable. This effectively removes the GSI. If no
// GSI was bootable, false is returned.
bool UninstallGsi();

// Set the GSI as no longer bootable, without removing its installed files.
bool DisableGsi();

// Returns true if init should attempt to boot into a live GSI image, false
// otherwise. If false, an error message is set.
//
// This is only called by first-stage init.
bool CanBootIntoGsi(std::string* error);

// Called by first-stage init to indicate that we're about to boot into a
// GSI.
bool MarkSystemAsGsi();

}  // namespace gsi
}  // namespace android
