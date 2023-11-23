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

#include "ShardOffsetProvider.h"

#include <errno.h>
#include <sys/random.h>
#include <unistd.h>

#include <chrono>

namespace android {
namespace os {
namespace statsd {

ShardOffsetProvider::ShardOffsetProvider() {
    unsigned int seed = 0;
    // getrandom() reads bytes from urandom source into buf. If getrandom()
    // is unable to read from urandom source, then it returns -1 and we set
    // our seed to be time(nullptr) as a fallback.
    if (TEMP_FAILURE_RETRY(
                getrandom(static_cast<void*>(&seed), sizeof(unsigned int), GRND_NONBLOCK)) < 0) {
        seed = time(nullptr);
    }
    srand(seed);
    mShardOffset = rand();
}

ShardOffsetProvider& ShardOffsetProvider::getInstance() {
    static ShardOffsetProvider sShardOffsetProvider;
    return sShardOffsetProvider;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
