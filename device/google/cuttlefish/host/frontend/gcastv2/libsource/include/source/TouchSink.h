/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <https/RunLoop.h>

#include <memory>

#include <source/InputSink.h>

namespace android {

struct TouchSink {
    explicit TouchSink(std::shared_ptr<RunLoop> runLoop, int serverFd,
                       bool write_virtio_input);
    ~TouchSink() = default;

    void start() {sink_->start();}

    void injectTouchEvent(int32_t x, int32_t y, bool down);
    void injectMultiTouchEvent(int32_t id, int32_t slot, int32_t x, int32_t y,
                               bool initialDown);

   private:
    std::shared_ptr<InputSink> sink_;
};

}  // namespace android


