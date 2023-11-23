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

#include <ImsMediaDefine.h>
#include <JitterNetworkAnalyser.h>
#include <ImsMediaTimer.h>
#include <ImsMediaTrace.h>
#include <numeric>
#include <cmath>
#include <algorithm>

#define MAX_JITTER_LIST_SIZE     (500)
#define PACKET_INTERVAL          (20)  // milliseconds
#define BUFFER_REDUCE_TH         (1000 * 20)
#define STD_DISTRIBUTION_Z_VALUE (2.5)
#define BUFFER_IN_DECREASE_SIZE  (2)
#define STATUS_INTERVAL          (1000)  // milliseconds

JitterNetworkAnalyser::JitterNetworkAnalyser()
{
    mMinJitterBufferSize = 0;
    mMaxJitterBufferSize = 0;
    mBufferReduceTH = BUFFER_REDUCE_TH;
    mBufferStepSize = BUFFER_IN_DECREASE_SIZE;
    mBufferZValue = STD_DISTRIBUTION_Z_VALUE;
    Reset();
}

JitterNetworkAnalyser::~JitterNetworkAnalyser() {}

void JitterNetworkAnalyser::Reset()
{
    mBasePacketTime = 0;
    mBaseArrivalTime = 0;
    mNetworkStatus = NETWORK_STATUS_NORMAL;
    mGoodStatusEnteringTime = 0;
    mBadStatusChangedTime = 0;

    std::lock_guard<std::mutex> guard(mMutex);
    mListJitters.clear();
}

void JitterNetworkAnalyser::SetMinMaxJitterBufferSize(
        uint32_t nMinBufferSize, uint32_t nMaxBufferSize)
{
    mMinJitterBufferSize = nMinBufferSize;
    mMaxJitterBufferSize = nMaxBufferSize;
}

void JitterNetworkAnalyser::SetJitterOptions(uint32_t nReduceTH, uint32_t nStepSize, double zValue)
{
    mBufferReduceTH = nReduceTH;
    mBufferStepSize = nStepSize;
    mBufferZValue = zValue;

    IMLOGD3("[SetJitterOptions] ReduceTH[%d], StepSize[%d], ZValue[%.lf]", mBufferReduceTH,
            mBufferStepSize, mBufferZValue);
}

int32_t JitterNetworkAnalyser::CalculateTransitTimeDifference(
        uint32_t timestamp, uint32_t arrivalTime)
{
    if (mBasePacketTime == 0)
    {
        return 0;
    }

    int32_t inputTimestampGap = timestamp - mBasePacketTime;
    int32_t inputTimeGap = arrivalTime - mBaseArrivalTime;
    int32_t jitter = inputTimeGap - inputTimestampGap;

    std::lock_guard<std::mutex> guard(mMutex);
    mListJitters.push_back(jitter);

    if (mListJitters.size() > MAX_JITTER_LIST_SIZE)
    {
        mListJitters.pop_front();
    }

    return jitter;
}

double JitterNetworkAnalyser::CalculateDeviation(double* pMean)
{
    std::lock_guard<std::mutex> guard(mMutex);

    if (mListJitters.empty())
    {
        *pMean = 0;
        return 0.0f;
    }

    double mean =
            std::accumulate(mListJitters.begin(), mListJitters.end(), 0.0f) / mListJitters.size();

    *pMean = mean;

    double dev = sqrt(std::accumulate(mListJitters.begin(), mListJitters.end(), 0.0f,
                              [mean](int x, int y)
                              {
                                  return x + std::pow(y - mean, 2);
                              }) /
            mListJitters.size());

    return dev;
}

int32_t JitterNetworkAnalyser::GetMaxJitterValue()
{
    std::lock_guard<std::mutex> guard(mMutex);

    if (mListJitters.empty())
    {
        return 0;
    }

    return *std::max_element(mListJitters.begin(), mListJitters.end());
}

void JitterNetworkAnalyser::UpdateBaseTimestamp(uint32_t packetTime, uint32_t arrivalTime)
{
    IMLOGD_PACKET2(IM_PACKET_LOG_JITTER, "[UpdateBaseTimestamp] packetTime[%d], arrivalTime[%u]",
            packetTime, arrivalTime);
    mBasePacketTime = packetTime;
    mBaseArrivalTime = arrivalTime;
}

uint32_t JitterNetworkAnalyser::GetNextJitterBufferSize(
        uint32_t nCurrJitterBufferSize, uint32_t currentTime)
{
    uint32_t nextJitterBuffer = nCurrJitterBufferSize;
    NETWORK_STATUS networkStatus;

    double dev, mean;
    // calcuatation of jitterSize
    double calcJitterSize = 0;
    int32_t maxJitter = GetMaxJitterValue();
    dev = CalculateDeviation(&mean);
    calcJitterSize = mean + mBufferZValue * dev;
    IMLOGD_PACKET4(IM_PACKET_LOG_JITTER,
            "[GetNextJitterBufferSize] size[%4.2f], dev[%lf], curr[%d], max jitter[%d]",
            calcJitterSize, dev, nCurrJitterBufferSize, maxJitter);

    if (calcJitterSize >= nCurrJitterBufferSize * PACKET_INTERVAL)
    {
        networkStatus = NETWORK_STATUS_BAD;
    }
    else if (calcJitterSize < ((nCurrJitterBufferSize - 1) * PACKET_INTERVAL - 10) &&
            maxJitter < ((nCurrJitterBufferSize - 1) * PACKET_INTERVAL - 10))
    {
        networkStatus = NETWORK_STATUS_GOOD;
    }
    else
    {
        networkStatus = NETWORK_STATUS_NORMAL;
    }

    switch (networkStatus)
    {
        case NETWORK_STATUS_BAD:
        {
            if (mBadStatusChangedTime == 0 ||
                    (currentTime - mBadStatusChangedTime) >= STATUS_INTERVAL)
            {
                if (nCurrJitterBufferSize < mMaxJitterBufferSize)
                {
                    nextJitterBuffer = nCurrJitterBufferSize + mBufferStepSize;
                }

                IMLOGD_PACKET2(IM_PACKET_LOG_JITTER,
                        "[GetNextJitterBufferSize] Increase next[%d], curr[%d]", nextJitterBuffer,
                        nCurrJitterBufferSize);
                mBadStatusChangedTime = currentTime;
            }

            break;
        }
        case NETWORK_STATUS_GOOD:
        {
            if (mNetworkStatus != NETWORK_STATUS_GOOD)
            {
                mGoodStatusEnteringTime = currentTime;
            }
            else
            {
                uint32_t nTimeDiff = currentTime - mGoodStatusEnteringTime;

                if (nTimeDiff >= mBufferReduceTH)
                {
                    if (nCurrJitterBufferSize > mMinJitterBufferSize)
                        nextJitterBuffer = nCurrJitterBufferSize - mBufferStepSize;
                    IMLOGD_PACKET2(IM_PACKET_LOG_JITTER,
                            "[GetNextJitterBufferSize] Decrease next[%d], curr[%d]",
                            nextJitterBuffer, nCurrJitterBufferSize);
                    networkStatus = NETWORK_STATUS_NORMAL;
                }
            }

            break;
        }
        default:
            nextJitterBuffer = nCurrJitterBufferSize;
            break;
    }

    mNetworkStatus = networkStatus;
    return nextJitterBuffer;
}
