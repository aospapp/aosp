/*
 * Copyright (C) 2022 The Android Open Source Project
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

#ifndef CHRE_HOST_TIME_SYNCER_H_
#define CHRE_HOST_TIME_SYNCER_H_

#include <cstdint>
#include <string>

#include "chre_connection.h"

namespace android::chre {

using hardware::contexthub::common::implementation::ChreConnection;

/** The class synchronizes time between the Context hub and Android. */
class TimeSyncer {
 public:
  explicit TimeSyncer(ChreConnection *connection) : mConnection(connection) {}

  /**
   * Sends time sync message to Context hub and retries numRetries times until
   * success.
   *
   * If the platform doesn't require the time sync the request will be ignored
   * and true is returned.
   *
   * @return true if success, false otherwise.
   */
  bool sendTimeSyncWithRetry(size_t numOfRetries, useconds_t retryDelayUs);

  /**
   * Sends a time sync message to Context hub for once.
   *
   * If the platform doesn't require the time sync the request will be ignored
   * and true is returned.
   *
   * @return true if success, false otherwise.
   */
  bool sendTimeSync();

 private:
  ChreConnection *mConnection;
};

}  // namespace android::chre

#endif  // CHRE_HOST_TIME_SYNCER_H_