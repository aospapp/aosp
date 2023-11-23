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

#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <ctime>
#include <fstream>

namespace netsim {

void BtsLog(const char *fmt, ...) {
  char buffer[255];
  va_list arglist;

  va_start(arglist, fmt);
  vsnprintf(buffer, sizeof(buffer), fmt, arglist);
  va_end(arglist);

  auto now = std::chrono::system_clock::now();
  auto now_ms = std::chrono::time_point_cast<std::chrono::milliseconds>(now);
  auto now_t = std::chrono::system_clock::to_time_t(now);
  //"mm-dd_HH:MM:SS.sss\0" is 19 byte long
  char prefix[19];
  auto l = std::strftime(prefix, sizeof(prefix), "%m-%d %H:%M:%S",
                         std::localtime(&now_t));
  snprintf(prefix + l, sizeof(prefix) - l, ".%03u",
           static_cast<unsigned int>(now_ms.time_since_epoch().count() % 1000));
  fprintf(stderr, "netsim D %s %s\n", prefix, buffer);
}

}  // namespace netsim
