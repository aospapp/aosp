/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "chre/platform/shared/platform_log.h"

extern "C" {

#include "a1std/AEEstd.h"
#include "HAP_farf.h"
#include "smp2p.h"

}  // extern "C"

#include <cinttypes>

#include "chre/platform/assert.h"
#include "chre/platform/log.h"
#include "chre/platform/host_link.h"
#include "chre/platform/system_time.h"
#include "chre/util/lock_guard.h"

namespace chre {

PlatformLog::PlatformLog() {}

PlatformLog::~PlatformLog() {}

void PlatformLog::log(const char *formatStr, ...) {
  LockGuard<Mutex> lock(mMutex);

  if ((mLogBufferSize + kMaxLogMessageSize) < kLogBufferSize) {
    // TODO: Pass in the log level. All messages are logged at info level.
    char *logBuffer = &mLogBuffer[mLogBufferSize];
    *logBuffer = CHRE_LOG_LEVEL_INFO;
    logBuffer++;

    // Insert the timestamp of the log prior to the string.
    // XXX Here be dragons. There are no endianness conversion macros in the
    // SLPI source tree that support 64-bit width integers. This code assumes
    // that it is running on a little-endian system! You have been warned.
    uint64_t timestamp = SystemTime::getMonotonicTime().toRawNanoseconds();
    memcpy(logBuffer, &timestamp, sizeof(uint64_t));
    logBuffer += sizeof(uint64_t);

    // Format the log message after the timestamp.
    va_list argList;
    va_start(argList, formatStr);
    const size_t messageLen = kMaxLogMessageSize - 1 - sizeof(uint64_t);
    int strLen = vsnprintf(logBuffer, messageLen, formatStr, argList);
    va_end(argList);

    if (strLen < 0) {
      // A formatting error occured. Don't advance the log buffer index.
      FARF(MEDIUM, "Failed to format log string. Dropping message");
    } else {
      // Null-terminate if the formatted string does not fit into the buffer.
      if (static_cast<size_t>(strLen) >= messageLen) {
        logBuffer[messageLen] = '\0';
        strLen--;
      }

      // Increment the size of logged messages, leaving space for level,
      // timestamp and null-terminator.
      mLogBufferSize += 1 + sizeof(uint64_t) + strLen + 1;

      // Only request a log flush if there is not one pending and the log buffer
      // has exceeded the watermark.
      if (!mLogFlushPending && mLogBufferSize > kLogBufferWatermarkSize) {
        mLogFlushPending = true;

        // Manually unlock the mutex in the event that the request for a log flush
        // attempts to log. This would result in a deadlock.
        // TODO: Consider a more elegant way to handle this unlock/lock pair.
        mMutex.unlock();
        requestHostLinkLogBufferFlush();
        mMutex.lock();
      }
    }
  } else {
    // TODO: Handle the condition where there is space for less than 1 message
    // left. This might be considered a fatal error or an assert at the minimum.
  }
}

void PlatformLogBase::flushLogBuffer(FlushLogBufferCallback callback,
                                     void *context) {
  CHRE_ASSERT(callback != nullptr);

  LockGuard<Mutex> lock(mMutex);
  CHRE_ASSERT(mLogFlushPending);
  callback(mLogBuffer, mLogBufferSize, context);
  mLogBufferSize = 0;
  mLogFlushPending = false;
}

}  // namespace chre
