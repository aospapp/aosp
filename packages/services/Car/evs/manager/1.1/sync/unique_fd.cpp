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

#include "unique_fd.h"

#include <errno.h>
#include <string.h>

#include <android-base/logging.h>

namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {

UniqueFd::UniqueFd() : fd_(-1) {}

UniqueFd::UniqueFd(int fd) : fd_(fd) {
}

UniqueFd::~UniqueFd() {
    InternalClose();
}

UniqueFd::UniqueFd(UniqueFd&& other) : fd_(other.fd_) {
    other.fd_ = -1;
}

UniqueFd& UniqueFd::operator=(UniqueFd&& other) {
    InternalClose();
    fd_ = other.fd_;
    other.fd_ = -1;
    return *this;
}

void UniqueFd::Reset(int new_fd) {
    InternalClose();
    fd_ = new_fd;
}

UniqueFd UniqueFd::Dup() const {
    return (fd_ >= 0) ? UniqueFd(InternalDup()) : UniqueFd(fd_);
}

UniqueFd::operator bool() const {
    return fd_ >= 0;
}

int UniqueFd::Get() const {
    return fd_;
}

int UniqueFd::GetUnowned() const {
    return InternalDup();
}

int UniqueFd::Release() {
    int ret = fd_;
    fd_ = -1;
    return ret;
}

void UniqueFd::InternalClose() {
    if (fd_ >= 0) {
        int err = close(fd_);
        if (err < 0) {
            PLOG(FATAL) << "Error closing UniqueFd";
        }
    }
    fd_ = -1;
}

int UniqueFd::InternalDup() const {
    int new_fd = fd_ >= 0 ? dup(fd_) : fd_;
    if (new_fd < 0 && fd_ >= 0) {
        PLOG(FATAL) << "Error duplicating UniqueFd";
    }
    return new_fd;
}

}  // namespace implementation
}  // namespace V1_1
}  // namespace evs
}  // namespace automotive
}  // namespace android
