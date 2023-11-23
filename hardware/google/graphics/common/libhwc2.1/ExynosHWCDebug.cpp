/*
 * Copyright (C) 2012 The Android Open Source Project
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
#include "ExynosHWCDebug.h"
#include "ExynosDisplay.h"
#include <sync/sync.h>
#include "exynos_sync.h"

int32_t saveErrorLog(const String8 &errString, ExynosDisplay *display) {
    if (display == nullptr) return -1;
    int32_t ret = NO_ERROR;

    auto &fileWriter = display->mErrLogFileWriter;

    if (!fileWriter.chooseOpenedFile()) {
        return -1;
    }

    String8 saveString;
    struct timeval tv;
    gettimeofday(&tv, NULL);

    saveString.appendFormat("%s errFrameNumber %" PRIu64 ": %s\n", getLocalTimeStr(tv).string(),
                            display->mErrorFrameCount, errString.string());

    fileWriter.write(saveString);
    fileWriter.flush();
    return ret;
}

int32_t saveFenceTrace(ExynosDisplay *display) {
    int32_t ret = NO_ERROR;
    auto &fileWriter = display->mFenceFileWriter;

    if (!fileWriter.chooseOpenedFile()) {
        return -1;
    }

    ExynosDevice *device = display->mDevice;

    String8 saveString;

    struct timeval tv;
    gettimeofday(&tv, NULL);
    saveString.appendFormat("\n====== Fences at time:%s ======\n", getLocalTimeStr(tv).string());

    if (device != NULL) {
        for (const auto &[fd, info] : device->mFenceInfos) {
            saveString.appendFormat("---- Fence FD : %d, Display(%d) ----\n", fd, info.displayId);
            saveString.appendFormat("usage: %d, dupFrom: %d, pendingAllowed: %d, leaking: %d\n",
                                    info.usage, info.dupFrom, info.pendingAllowed, info.leaking);

            for (const auto &trace : info.traces) {
                saveString.appendFormat("> dir: %d, type: %d, ip: %d, time:%s\n", trace.direction,
                                        trace.type, trace.ip, getLocalTimeStr(trace.time).string());
            }
        }
    }

    fileWriter.write(saveString);
    fileWriter.flush();
    return ret;
}
