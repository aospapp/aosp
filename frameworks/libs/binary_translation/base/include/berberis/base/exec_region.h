/*
 * Copyright (C) 2023 The Android Open Source Project
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

#ifndef BERBERIS_BASE_EXEC_REGION_H_
#define BERBERIS_BASE_EXEC_REGION_H_

#include <cstddef>
#include <cstdint>

namespace berberis {

// ExecRegion manages a range of writable executable memory.
// Move-only!
class ExecRegion {
 public:
  ExecRegion() = default;
  explicit ExecRegion(uint8_t* exec, size_t size) : exec_{exec}, size_{size} {}

  ExecRegion(const ExecRegion& other) = delete;
  ExecRegion& operator=(const ExecRegion& other) = delete;

  ExecRegion(ExecRegion&& other) noexcept {
    exec_ = other.exec_;
    size_ = other.size_;
    other.exec_ = nullptr;
    other.size_ = 0;
  }

  ExecRegion& operator=(ExecRegion&& other) noexcept {
    if (this == &other) {
      return *this;
    }
    exec_ = other.exec_;
    size_ = other.size_;
    other.exec_ = nullptr;
    other.size_ = 0;
    return *this;
  }

  [[nodiscard]] const uint8_t* begin() const { return exec_; }
  [[nodiscard]] const uint8_t* end() const { return exec_ + size_; }

  void Write(const uint8_t* dst, const void* src, size_t size);

  void Detach();
  void Free();

 private:
  uint8_t* exec_ = nullptr;
  size_t size_ = 0;
};

}  // namespace berberis

#endif  // BERBERIS_BASE_EXEC_REGION_H_
