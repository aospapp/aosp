/******************************************************************************
 *
 *  Copyright 2022 Google LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#include <base/logging.h>

#include <filesystem>
#include <string>
#include <vector>

namespace fs = std::filesystem;

// The user data should be stored in the subdirectory of |USER_DE_PATH|
static const std::string USER_DE_PATH = "/data/user_de/0";

// The migration process start only if |MIGRATION_FILE_CHECKER| is found in a
// previous location
static const std::string MIGRATION_FILE_CHECKER = "databases/bluetooth_db";

// List of possible package_name for bluetooth to get the data from / to
static const std::vector<std::string> ALLOWED_BT_PACKAGE_NAME = {
    "com.android.bluetooth",                  // legacy name
    "com.android.bluetooth.services",         // Beta users
    "com.google.android.bluetooth.services",  // Droid fooder users
};

// Accessor to get the default allowed package list to be used in migration
// OEM can call their own method with their own allowed list
const std::vector<std::string> get_allowed_bt_package_name(void) {
  return ALLOWED_BT_PACKAGE_NAME;
}

// Check if |dst| is in |base_dir| subdirectory and check the package name in
// |dst| is a allowed package name in the |pkg_list|
//
// Return an empty string if an issue occurred
// or the package name contained in |dst| on success
static std::string parse_destination_package_name(
    const std::string& dst, const std::string& base_dir,
    const std::vector<std::string>& pkg_list) {
  const std::size_t found = dst.rfind("/");
  // |dst| must contain a '/'
  if (found == std::string::npos) {
    LOG(ERROR) << "Destination format not valid " << dst;
    return "";
  }
  // |dst| directory is supposed to be in |base_dir|
  if (found != base_dir.length()) {
    LOG(ERROR) << "Destination location not allowed: " << dst;
    return "";
  }
  // This check prevent a '/' to be at the end of |dst|
  if (found >= dst.length() - 1) {
    LOG(ERROR) << "Destination format not valid " << dst;
    return "";
  }

  const std::string dst_package_name = dst.substr(found + 1);  // +1 for '/'

  if (std::find(pkg_list.begin(), pkg_list.end(), dst_package_name) ==
      pkg_list.end()) {
    LOG(ERROR) << "Destination package_name not valid: " << dst_package_name
               << " Created from " << dst;
    return "";
  }
  LOG(INFO) << "Current Bluetooth package name is: " << dst_package_name;
  return dst_package_name;
}

// Check for data to migrate from the |allowed_bt_package_name|
// A migration will be performed if:
// * |dst| is different than |allowed_bt_package_name|
// * the following file is found:
//    |USER_DE_PATH|/|allowed_bt_package_name|/|MIGRATION_FILE_CHECKER|
//
// After migration occurred, the |MIGRATION_FILE_CHECKER| is deleted to ensure
// the migration is only performed once
void handle_migration(const std::string& dst,
                      const std::vector<std::string>& allowed_bt_package_name) {
  const std::string dst_package_name = parse_destination_package_name(
      dst, USER_DE_PATH, allowed_bt_package_name);
  if (dst_package_name.empty()) return;

  for (const auto& pkg_name : allowed_bt_package_name) {
    std::error_code error;

    if (dst_package_name == pkg_name) {
      LOG(INFO) << "Same location skipped: " << dst_package_name;
      continue;
    }
    const fs::path dst_path = dst;
    const fs::path pkg_path = USER_DE_PATH + "/" + pkg_name;
    const fs::path local_migration_file_checker =
        pkg_path.string() + "/" + MIGRATION_FILE_CHECKER;
    if (!fs::exists(local_migration_file_checker, error)) {
      LOG(INFO) << "Not a valid candidate for migration: " << pkg_path;
      continue;
    }

    const fs::copy_options copy_flag =
        fs::copy_options::overwrite_existing | fs::copy_options::recursive;
    fs::copy(pkg_path, dst_path, copy_flag, error);

    if (error) {
      LOG(ERROR) << "Migration failed: " << error.message();
    } else {
      fs::remove(local_migration_file_checker);
      LOG(INFO) << "Migration completed from " << pkg_path << " to " << dst;
    }
    break;  // Copy from one and only one directory
  }
}
