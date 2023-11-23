/**
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

#include <ImsMediaTimer.h>
#include <ImsMediaTrace.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <chrono>
#include <thread>
#include <utils/Atomic.h>
#include <mutex>
#include <list>
#include <algorithm>

struct TimerInstance
{
    fn_TimerCb mTimerCb;
    uint32_t mDuration;
    bool mRepeat;
    void* mUserData;
    bool mTerminateThread;
    uint32_t mStartTimeSec;
    uint32_t mStartTimeMSec;
};

static std::mutex gMutex;
static std::mutex gMutexList;
static std::list<TimerInstance*> gTimerList;

static void AddTimerToList(TimerInstance* pInstance)
{
    std::lock_guard<std::mutex> guard(gMutexList);
    gTimerList.push_back(pInstance);
}

static void DeleteTimerFromList(TimerInstance* pInstance)
{
    std::lock_guard<std::mutex> guard(gMutexList);
    gTimerList.remove(pInstance);
}

static bool IsValidTimer(const TimerInstance* pInstance)
{
    std::lock_guard<std::mutex> guard(gMutexList);

    if (gTimerList.empty())
    {
        return false;
    }

    auto result = std::find(gTimerList.begin(), gTimerList.end(), pInstance);
    return (result != gTimerList.end());
}

static int32_t ImsMediaTimer_GetMilliSecDiff(
        uint32_t startTimeSec, uint32_t startTimeMSec, uint32_t currTimeSec, uint32_t currTimeMSec)
{
    uint32_t nDiffSec;
    uint32_t nDiffMSec;
    nDiffSec = currTimeSec - startTimeSec;
    currTimeMSec += (nDiffSec * 1000);
    nDiffMSec = currTimeMSec - startTimeMSec;
    return nDiffMSec;
}

static void* ImsMediaTimer_run(void* arg)
{
    TimerInstance* pInstance = reinterpret_cast<TimerInstance*>(arg);
    uint32_t nSleepTime;

    if (pInstance == nullptr)
    {
        return nullptr;
    }

    if (pInstance->mDuration < 100)
    {
        nSleepTime = 10;
    }
    else if (pInstance->mDuration < 1000)
    {
        nSleepTime = pInstance->mDuration / 10;
    }
    else
    {
        nSleepTime = 100;
    }

    for (;;)
    {
        struct timeval tp;

        if (pInstance->mTerminateThread)
        {
            break;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(nSleepTime));

        if (pInstance->mTerminateThread)
        {
            break;
        }

        if (gettimeofday(&tp, nullptr) != -1)
        {
            uint32_t nCurrTimeSec, nCurrTimeMSec;
            uint32_t nTimeDiff;
            nCurrTimeSec = tp.tv_sec;
            nCurrTimeMSec = tp.tv_usec / 1000;
            nTimeDiff = ImsMediaTimer_GetMilliSecDiff(pInstance->mStartTimeSec,
                    pInstance->mStartTimeMSec, nCurrTimeSec, nCurrTimeMSec);

            if (nTimeDiff >= pInstance->mDuration)
            {
                if (pInstance->mRepeat == true)
                {
                    pInstance->mStartTimeSec = nCurrTimeSec;
                    pInstance->mStartTimeMSec = nCurrTimeMSec;
                }

                gMutex.lock();

                if (pInstance->mTerminateThread)
                {
                    gMutex.unlock();
                    break;
                }

                if (pInstance->mTimerCb)
                {
                    pInstance->mTimerCb(pInstance, pInstance->mUserData);
                }

                gMutex.unlock();

                if (pInstance->mRepeat == false)
                {
                    break;
                }
            }
        }
    }

    DeleteTimerFromList(pInstance);

    if (pInstance != nullptr)
    {
        free(pInstance);
        pInstance = nullptr;
    }

    return nullptr;
}

hTimerHandler ImsMediaTimer::TimerStart(
        uint32_t nDuration, bool bRepeat, fn_TimerCb pTimerCb, void* pUserData)
{
    struct timeval tp;
    TimerInstance* pInstance = reinterpret_cast<TimerInstance*>(malloc(sizeof(TimerInstance)));

    if (pInstance == nullptr)
    {
        return nullptr;
    }

    pInstance->mTimerCb = pTimerCb;
    pInstance->mDuration = nDuration;
    pInstance->mRepeat = bRepeat;
    pInstance->mUserData = pUserData;
    pInstance->mTerminateThread = false;

    IMLOGD3("[TimerStart] Duratation[%u], bRepeat[%d], pUserData[%x]", pInstance->mDuration,
            bRepeat, pInstance->mUserData);

    if (gettimeofday(&tp, nullptr) != -1)
    {
        pInstance->mStartTimeSec = tp.tv_sec;
        pInstance->mStartTimeMSec = tp.tv_usec / 1000;
    }
    else
    {
        free(pInstance);
        return nullptr;
    }

    AddTimerToList(pInstance);

    std::thread t1(&ImsMediaTimer_run, pInstance);
    t1.detach();
    return (hTimerHandler)pInstance;
}

bool ImsMediaTimer::TimerStop(hTimerHandler hTimer, void** ppUserData)
{
    TimerInstance* pInstance = reinterpret_cast<TimerInstance*>(hTimer);

    if (pInstance == nullptr)
    {
        return false;
    }

    if (IsValidTimer(pInstance) == false)
    {
        return false;
    }

    gMutex.lock();  // just wait until timer callback returns...
    pInstance->mTerminateThread = true;

    if (ppUserData)
    {
        *ppUserData = pInstance->mUserData;
    }

    gMutex.unlock();
    return true;
}

void ImsMediaTimer::GetNtpTime(IMNtpTime* pNtpTime)
{
    struct timeval stAndrodTp;

    if (gettimeofday(&stAndrodTp, nullptr) != -1)
    {
        // To convert a UNIX timestamp (seconds since 1970) to NTP time, add 2,208,988,800 seconds
        pNtpTime->ntpHigh32Bits = stAndrodTp.tv_sec + 2208988800UL;
        pNtpTime->ntpLow32Bits = (unsigned int)(stAndrodTp.tv_usec * 4294UL);
    }
    else
    {
        pNtpTime->ntpHigh32Bits = 0;
        pNtpTime->ntpLow32Bits = 0;
    }
}

/*!
 * @brief       GetRtpTsFromNtpTs
 * @details     Transforms the current NTP time to the corresponding RTP TIme Stamp
 *              using the RTP time stamp rate for the session.
 */
uint32_t ImsMediaTimer::GetRtpTsFromNtpTs(IMNtpTime* initNtpTimestamp, uint32_t samplingRate)
{
    IMNtpTime currentNtpTs;
    int32_t timeDiffHigh32Bits;
    int32_t timeDiffLow32Bits;
    uint32_t timeDiff; /*! In Micro seconds: should always be positive */

    GetNtpTime(&currentNtpTs);

    /* SPR #1256 BEGIN */
    timeDiffHigh32Bits = currentNtpTs.ntpHigh32Bits - initNtpTimestamp->ntpHigh32Bits;
    timeDiffLow32Bits =
            (currentNtpTs.ntpLow32Bits / 4294) - (initNtpTimestamp->ntpLow32Bits / 4294);
    /*! timeDiffHigh32Bits should always be positive */
    timeDiff = (timeDiffHigh32Bits * 1000) + timeDiffLow32Bits / 1000;
    return timeDiff * (samplingRate / 1000);
}

uint32_t ImsMediaTimer::GetTimeInMilliSeconds(void)
{
    struct timeval tp;
    gettimeofday(&tp, nullptr);
    return (tp.tv_sec * 1000) + (tp.tv_usec / 1000);
}

uint64_t ImsMediaTimer::GetTimeInMicroSeconds(void)
{
    struct timeval tp;
    gettimeofday(&tp, nullptr);
    return (tp.tv_sec * 1000000) + (tp.tv_usec);
}

uint32_t ImsMediaTimer::GenerateRandom(uint32_t nRange)
{
    uint32_t rand;
    struct timeval tp;

    gettimeofday(&tp, nullptr);
    rand = (tp.tv_sec * 13) + (tp.tv_usec / 1000);

    if (0 == nRange)
    {
        return rand * 7;
    }

    return (rand * 7) % nRange;
}

int32_t ImsMediaTimer::Atomic_Inc(int32_t* v)
{
    return android_atomic_inc(v);
}
int32_t ImsMediaTimer::Atomic_Dec(int32_t* v)
{
    return android_atomic_dec(v);
}

void ImsMediaTimer::Sleep(unsigned int t)
{
    std::this_thread::sleep_for(std::chrono::milliseconds(t));
}

void ImsMediaTimer::USleep(unsigned int t)
{
    std::this_thread::sleep_for(std::chrono::microseconds(t));
}