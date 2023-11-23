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

#include <source/TouchSink.h>

#include <https/SafeCallbackable.h>
#include <https/Support.h>

#include <android-base/logging.h>

#include <linux/input.h>

#include <sys/socket.h>
#include <unistd.h>

namespace android {

TouchSink::TouchSink(std::shared_ptr<RunLoop> runLoop, int serverFd,
                     bool write_virtio_input)
    : sink_(
          std::make_shared<InputSink>(runLoop, serverFd, write_virtio_input)) {}

void TouchSink::injectTouchEvent(int32_t x, int32_t y, bool down) {
    LOG(VERBOSE)
        << "Received touch (down="
        << down
        << ", x="
        << x
        << ", y="
        << y;

    auto buffer = sink_->getEventBuffer();
    buffer->addEvent(EV_ABS, ABS_X, x);
    buffer->addEvent(EV_ABS, ABS_Y, y);
    buffer->addEvent(EV_KEY, BTN_TOUCH, down);
    buffer->addEvent(EV_SYN, 0, 0);
    sink_->SendEvents(std::move(buffer));
}

void TouchSink::injectMultiTouchEvent(int32_t /*id*/, int32_t /*slot*/,
                                      int32_t x, int32_t y, bool initialDown) {
  // TODO (muntsinger): Enable multitouch
  return injectTouchEvent(x, y, initialDown);
}

}  // namespace android

