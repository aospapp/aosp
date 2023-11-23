/*
 * Copyright (C) 2018 The Android Open Source Project
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

#ifndef __NOGROD_STRING_TABLE_
#define __NOGROD_STRING_TABLE_

#include <cstdint>
#include <string>

#include <berberis/base/checks.h>
#include <berberis/base/macros.h>

namespace nogrod {

class StringTable {
 public:
  StringTable() : strtab_(nullptr), strtab_size_(0) {}

  StringTable(const char* strtab, size_t strtab_size) : strtab_(strtab), strtab_size_(strtab_size) {
    // string table should be \0 terminated.
    CHECK(strtab_[strtab_size_ - 1] == 0);
  }

  StringTable(const StringTable&) = default;
  StringTable& operator=(const StringTable&) = default;
  StringTable(StringTable&&) = default;
  StringTable& operator=(StringTable&&) = default;

  [[nodiscard]] const char* GetString(size_t index) const {
    CHECK(index < strtab_size_);
    return strtab_ + index;
  }

 private:
  const char* strtab_;
  size_t strtab_size_;
};

}  // namespace nogrod
#endif  // __NOGROD_STRING_TABLE_
