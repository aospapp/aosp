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

uint32_t mErrLogSize = 0;
uint32_t mFenceLogSize = 0;

int32_t saveErrorLog(const String8 &errString, ExynosDisplay *display)
{
    int32_t ret = NO_ERROR;
    if (mErrLogSize >= ERR_LOG_SIZE)
        return -1;

    FILE *pFile = NULL;
    char filePath[128];
    sprintf(filePath, "%s/hwc_error_log.txt", ERROR_LOG_PATH0);
    pFile = fopen(filePath, "a");
    if (pFile == NULL) {
        ALOGE("Fail to open file %s/hwc_error_log.txt, error: %s", ERROR_LOG_PATH0, strerror(errno));
        sprintf(filePath, "%s/hwc_error_log.txt", ERROR_LOG_PATH1);
        pFile = fopen(filePath, "a");
    }
    if (pFile == NULL) {
        ALOGE("Fail to open file %s/hwc_error_log.txt, error: %s", ERROR_LOG_PATH1, strerror(errno));
        return -errno;
    }

    mErrLogSize = ftell(pFile);
    if (mErrLogSize >= ERR_LOG_SIZE) {
        if (pFile != NULL)
            fclose(pFile);
        return -1;
    }

    String8 saveString;
    struct timeval tv;
    struct tm* localTime;
    gettimeofday(&tv, NULL);
    localTime = (struct tm*)localtime((time_t*)&tv.tv_sec);

    if (display != NULL) {
        saveString.appendFormat("%02d-%02d %02d:%02d:%02d.%03lu(%lu) %s %" PRIu64 ": %s\n",
                localTime->tm_mon+1, localTime->tm_mday,
                localTime->tm_hour, localTime->tm_min,
                localTime->tm_sec, tv.tv_usec/1000, ((tv.tv_sec * 1000) + (tv.tv_usec / 1000)),
                display->mDisplayName.string(), display->mErrorFrameCount,
                errString.string());
    } else {
        saveString.appendFormat("%02d-%02d %02d:%02d:%02d.%03lu(%lu) : %s\n",
                localTime->tm_mon+1, localTime->tm_mday,
                localTime->tm_hour, localTime->tm_min,
                localTime->tm_sec, tv.tv_usec/1000, ((tv.tv_sec * 1000) + (tv.tv_usec / 1000)),
                errString.string());
    }

    if (pFile != NULL) {
        fwrite(saveString.string(), 1, saveString.size(), pFile);
        mErrLogSize = (uint32_t)ftell(pFile);
        ret = mErrLogSize;
        fclose(pFile);
    }
    return ret;
}

int32_t saveFenceTrace(ExynosDisplay *display) {
    int32_t ret = NO_ERROR;

    if (mFenceLogSize >= FENCE_ERR_LOG_SIZE)
        return -1;

    FILE *pFile = NULL;
    char filePath[128];
    sprintf(filePath, "%s/hwc_fence_state.txt", ERROR_LOG_PATH0);
    pFile = fopen(filePath, "a");
    if (pFile == NULL) {
        ALOGE("Fail to open file %s/hwc_fence_state.txt, error: %s", ERROR_LOG_PATH0, strerror(errno));
        sprintf(filePath, "%s/hwc_fence_state.txt", ERROR_LOG_PATH1);
        pFile = fopen(filePath, "a");
    }
    if (pFile == NULL) {
        ALOGE("Fail to open file %s, error: %s", ERROR_LOG_PATH1, strerror(errno));
        return -errno;
    }

    mFenceLogSize = ftell(pFile);
    if (mFenceLogSize >= FENCE_ERR_LOG_SIZE) {
        if (pFile != NULL)
            fclose(pFile);
        return -1;
    }

    String8 saveString;
    struct timeval tv;

    ExynosDevice *device = display->mDevice;
    hwc_fence_info_t* _info = device->mFenceInfo;

    if (device != NULL) {
        for(int i=0; i<1024;i ++){

            bool sysFdOpen = false;
            hwc_fence_info_t info = _info[i];

            // FIXME: sync_fence_info and sync_pt_info are deprecated
            //        HWC guys should fix this
#if 0
            struct sync_pt_info* pt_info = NULL;
            info.sync_data = sync_fence_info(i);
            if (info.sync_data != NULL) {
                pt_info = sync_pt_info(info.sync_data, pt_info);
                if (pt_info !=NULL) {
                    saveString.appendFormat("\n-- FD sys : %d\n", i);
                    saveString.appendFormat("real name : %s, status : %s, pt_obj : %s, pt_drv : %s, time : %llu",
                            info.sync_data->name, info.sync_data->status==1 ? "Active":"Signaled",
                            pt_info->obj_name, pt_info->driver_name, static_cast<unsigned long long>(pt_info->timestamp_ns));
                } else {
                    saveString.appendFormat("\n-- FD sys : %d\n", i);
                    saveString.appendFormat("real name : %s, status : %d, pt_info : %p",
                            info.sync_data->name, info.sync_data->status, pt_info);
                }
                sysFdOpen = true;
                sync_fence_info_free(info.sync_data);
            }
#endif

            if ((info.usage == 0) && !sysFdOpen) continue;

            saveString.appendFormat("\n-- FD hwc : %d, usage %d\n", i, info.usage);

            switch(info.last_dir) {
            case FENCE_FROM:
                saveString.appendFormat("Last state : from %d, %d\n",
                        info.from.type, info.from.ip);
                tv = info.from.time;
                break;
            case FENCE_TO:
                saveString.appendFormat("Last state : to %d, %d\n",
                        info.to.type, info.to.ip);
                tv = info.to.time;
                break;
            case FENCE_DUP:
                saveString.appendFormat("Last state : dup %d, %d\n",
                        info.dup.type, info.dup.ip);
                tv = info.dup.time;
                break;
            case FENCE_CLOSE:
                saveString.appendFormat("Last state : Close %d, %d\n",
                        info.close.type, info.close.ip);
                tv = info.close.time;
                break;
                break;
            default:
                saveString.appendFormat("Fence trace : Undefined direction!\n");
                break;
            }

            struct tm* localTime = (struct tm*)localtime((time_t*)&tv.tv_sec);

            saveString.appendFormat("from : %d, %d (cur : %d), to : %d, %d (cur : %d), hwc_dup : %d, %d (cur : %d), hwc_close : %d, %d (cur : %d)\n",
                    info.from.type, info.from.ip, info.from.curFlag,
                    info.to.type, info.to.ip, info.to.curFlag,
                    info.dup.type, info.dup.ip, info.dup.curFlag,
                    info.close.type, info.close.ip, info.close.curFlag);
            saveString.appendFormat("usage : %d, time:%02d-%02d %02d:%02d:%02d.%03lu(%lu)", info.usage,
                    localTime->tm_mon+1, localTime->tm_mday,
                    localTime->tm_hour, localTime->tm_min,
                    localTime->tm_sec, tv.tv_usec/1000,
                    ((tv.tv_sec * 1000) + (tv.tv_usec / 1000)));
        }
    }

    if (pFile != NULL) {
        fwrite(saveString.string(), 1, saveString.size(), pFile);
        mFenceLogSize = (uint32_t)ftell(pFile);
        ret = mFenceLogSize;
        fclose(pFile);
    }

    return ret;
}
