// Copyright 2022 The Android Open Source Project
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

#include "util/os_utils.h"

#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>

#include <string>

#include "util/filesystem.h"
#include "util/ini_file.h"
#include "util/log.h"

namespace netsim {
namespace osutils {
namespace {

struct DiscoveryDir {
  const char *root_env;
  const char *subdir;
};

DiscoveryDir discovery {
#if defined(_WIN32)
  "LOCALAPPDATA", "Temp"
#elif defined(__linux__)
  "XDG_RUNTIME_DIR", ""
#elif defined(__APPLE__)
  "HOME", "Library/Caches/TemporaryItems"
#else
#error This platform is not supported.
#endif
};

}  // namespace

std::string GetEnv(const std::string &name, const std::string &default_value) {
  auto val = std::getenv(name.c_str());
  if (!val) {
    return default_value;
  }
  return val;
}

std::string GetDiscoveryDirectory() {
  // $TMPDIR is the temp directory on buildbots.
  const char *test_env_p = std::getenv("TMPDIR");
  if (test_env_p && *test_env_p) {
    return std::string(test_env_p);
  }
  const char *env_p = std::getenv(discovery.root_env);
  if (!env_p) {
    BtsLog("No discovery env for %s, using tmp/", discovery.root_env);
    env_p = "/tmp";
  }
  return std::string(env_p) + netsim::filesystem::slash + discovery.subdir;
}

std::string GetNetsimIniFilepath() {
  auto discovery_dir = GetDiscoveryDirectory();
  // Check if directory has a trailing slash.
  if (discovery_dir.back() != netsim::filesystem::slash.back())
    discovery_dir.append(netsim::filesystem::slash);
  return discovery_dir.append("netsim.ini");
}

std::optional<std::string> GetServerAddress(bool frontend_server) {
  auto filepath = GetNetsimIniFilepath();
  if (!netsim::filesystem::exists(filepath)) {
    BtsLog("Unable to find netsim ini file: %s", filepath.c_str());
    return std::nullopt;
  }
  if (!netsim::filesystem::is_regular_file(filepath)) {
    BtsLog("Not a regular file: %s", filepath.c_str());
    return std::nullopt;
  }
  IniFile iniFile(filepath);
  iniFile.Read();
  return iniFile.Get("grpc.port");
}
}  // namespace osutils
}  // namespace netsim
