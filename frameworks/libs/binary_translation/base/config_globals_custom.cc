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

#include "berberis/base/config_globals.h"

#include <bitset>
#include <string>

#include "berberis/base/logging.h"
#include "berberis/base/strings.h"

namespace berberis {

namespace {

std::string ToString(ConfigFlag flag) {
  switch (flag) {
    case kVerboseTranslation:
      return "verbose-translation";
    case kNumConfigFlags:
      break;
  }
  return "<unknown-config-flag>";
}

std::bitset<kNumConfigFlags> MakeConfigFlagsSet() {
  ConfigStr var("BERBERIS_FLAGS", "ro.berberis.flags");
  std::bitset<kNumConfigFlags> flags_set;
  if (!var.get()) {
    return flags_set;
  }
  auto token_vector = Split(var.get(), ",");
  for (const auto& token : token_vector) {
    bool found = false;
    for (int flag = 0; flag < kNumConfigFlags; flag++) {
      if (token == ToString(ConfigFlag(flag))) {
        flags_set.set(flag);
        found = true;
        break;
      }
    }
    if (!found) {
      ALOGW("Unrecognized config flag '%s' - ignoring", token.c_str());
    }
  }
  return flags_set;
}

}  // namespace

const char* GetTracingConfig() {
  static ConfigStr var("BERBERIS_TRACING", "berberis.tracing");
  return var.get();
}

const char* GetTranslationModeConfig() {
  static ConfigStr var("BERBERIS_MODE", "berberis.mode");
  return var.get();
}

const char* GetProfilingConfig() {
  static ConfigStr var("BERBERIS_PROFILING", "berberis.profiling");
  return var.get();
}

bool IsConfigFlagSet(ConfigFlag flag) {
  static auto flags_set = MakeConfigFlagsSet();
  return flags_set.test(flag);
}

}  // namespace berberis
