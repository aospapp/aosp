/*
 * Copyright (C) 2020 The Android Open Source Project
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
#include "linkerconfig/stringutil.h"

#include <set>
#include <sstream>
#include <vector>

#include <android-base/strings.h>

namespace android {
namespace linkerconfig {
namespace modules {
std::string TrimPrefix(const std::string& s, const std::string& prefix) {
  if (android::base::StartsWith(s, prefix)) {
    return s.substr(prefix.size());
  }
  return s;
}

// merge a list of libs into a single value (concat with ":")
std::string MergeLibs(const std::vector<std::string>& libs) {
  std::set<std::string> seen;
  std::ostringstream oss;
  for (const auto& part : libs) {
    std::istringstream iss(part);
    std::string lib;
    while (std::getline(iss, lib, ':')) {
      if (!lib.empty() && seen.insert(lib).second) {  // for a new lib
        if (oss.tellp() > 0) {
          oss << ':';
        }
        oss << lib;
      }
    }
  }
  return oss.str();
}
}  // namespace modules
}  // namespace linkerconfig
}  // namespace android