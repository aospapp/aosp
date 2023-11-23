/*
 * Copyright (C) 2016 The Android Open Source Project
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

#pragma once

namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {

// This is a simple C++ wrapper around a POSIX file descriptor. It is meant to
// enforce ownership just like unique_ptr<T>
//
// Instances of this type cannot be copied, but they can be moved.
class UniqueFd {
public:
    UniqueFd();
    explicit UniqueFd(int fd);
    ~UniqueFd();
    UniqueFd(UniqueFd&&);
    UniqueFd& operator=(UniqueFd&&);

    // Destroy the current descriptor, and take ownership of a new one.
    void Reset(int new_fd = -1);

    // Duplicate the current descriptor.
    UniqueFd Dup() const;

    // Returns true if the descriptor is valid. False otherwise.
    explicit operator bool() const;

    // Gets the descriptor
    int Get() const;

    // Gets a unowned duplicate of the descriptor. The caller is responsible for
    // closing it.
    int GetUnowned() const;

    // Gets the descriptor and releases ownership. The caller is responsible for
    // closing it.
    int Release();

private:
    UniqueFd(const UniqueFd&) = delete;
    UniqueFd& operator=(const UniqueFd&) = delete;

    void InternalClose();
    int InternalDup() const;

    int fd_;
};

}  // namespace implementation
}  // namespace V1_1
}  // namespace evs
}  // namespace automotive
}  // namespace android

