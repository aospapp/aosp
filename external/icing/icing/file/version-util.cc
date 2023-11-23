// Copyright (C) 2023 Google LLC
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

#include "icing/file/version-util.h"

#include <cstdint>
#include <string>
#include <utility>

#include "icing/text_classifier/lib3/utils/base/status.h"
#include "icing/text_classifier/lib3/utils/base/statusor.h"
#include "icing/absl_ports/canonical_errors.h"
#include "icing/file/filesystem.h"
#include "icing/index/index.h"

namespace icing {
namespace lib {

namespace version_util {

libtextclassifier3::StatusOr<VersionInfo> ReadVersion(
    const Filesystem& filesystem, const std::string& version_file_path,
    const std::string& index_base_dir) {
  // 1. Read the version info.
  VersionInfo existing_version_info(-1, -1);
  if (filesystem.FileExists(version_file_path.c_str()) &&
      !filesystem.PRead(version_file_path.c_str(), &existing_version_info,
                        sizeof(VersionInfo), /*offset=*/0)) {
    return absl_ports::InternalError("Fail to read version");
  }

  // 2. Check the Index magic to see if we're actually on version 0.
  libtextclassifier3::StatusOr<int> existing_flash_index_magic_or =
      Index::ReadFlashIndexMagic(&filesystem, index_base_dir);
  if (!existing_flash_index_magic_or.ok()) {
    if (absl_ports::IsNotFound(existing_flash_index_magic_or.status())) {
      // Flash index magic doesn't exist. In this case, we're unable to
      // determine the version change state correctly (regardless of the
      // existence of the version file), so invalidate VersionInfo by setting
      // version to -1, but still keep the max_version value read in step 1.
      existing_version_info.version = -1;
      return existing_version_info;
    }
    // Real error.
    return std::move(existing_flash_index_magic_or).status();
  }
  if (existing_flash_index_magic_or.ValueOrDie() ==
      kVersionZeroFlashIndexMagic) {
    existing_version_info.version = 0;
    if (existing_version_info.max_version == -1) {
      existing_version_info.max_version = 0;
    }
  }

  return existing_version_info;
}

libtextclassifier3::Status WriteVersion(const Filesystem& filesystem,
                                        const std::string& version_file_path,
                                        const VersionInfo& version_info) {
  ScopedFd scoped_fd(filesystem.OpenForWrite(version_file_path.c_str()));
  if (!scoped_fd.is_valid() ||
      !filesystem.PWrite(scoped_fd.get(), /*offset=*/0, &version_info,
                         sizeof(VersionInfo)) ||
      !filesystem.DataSync(scoped_fd.get())) {
    return absl_ports::InternalError("Fail to write version");
  }
  return libtextclassifier3::Status::OK;
}

StateChange GetVersionStateChange(const VersionInfo& existing_version_info,
                                  int32_t curr_version) {
  if (!existing_version_info.IsValid()) {
    return StateChange::kUndetermined;
  }

  if (existing_version_info.version == 0) {
    return (existing_version_info.max_version == existing_version_info.version)
               ? StateChange::kVersionZeroUpgrade
               : StateChange::kVersionZeroRollForward;
  }

  if (existing_version_info.version == curr_version) {
    return StateChange::kCompatible;
  } else if (existing_version_info.version > curr_version) {
    return StateChange::kRollBack;
  } else {  // existing_version_info.version < curr_version
    return (existing_version_info.max_version == existing_version_info.version)
               ? StateChange::kUpgrade
               : StateChange::kRollForward;
  }
}

}  // namespace version_util

}  // namespace lib
}  // namespace icing
