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

#ifndef AUDIO_JITTER_BUFFER_INCLUDED
#define AUDIO_JITTER_BUFFER_INCLUDED

#include <BaseJitterBuffer.h>
#include <JitterNetworkAnalyser.h>

class AudioJitterBuffer : public BaseJitterBuffer
{
public:
    AudioJitterBuffer();
    virtual ~AudioJitterBuffer();
    virtual void Reset();
    virtual void SetJitterBufferSize(uint32_t nInit, uint32_t nMin, uint32_t nMax);
    void SetJitterOptions(uint32_t nReduceTH, uint32_t nStepSize, double zValue, bool bIgnoreSID);
    virtual void Add(ImsMediaSubType subtype, uint8_t* pbBuffer, uint32_t nBufferSize,
            uint32_t nTimestamp, bool bMark, uint32_t nSeqNum,
            ImsMediaSubType nDataType = ImsMediaSubType::MEDIASUBTYPE_UNDEFINED,
            uint32_t arrivalTime = 0);
    virtual bool Get(ImsMediaSubType* psubtype, uint8_t** ppData, uint32_t* pnDataSize,
            uint32_t* pnTimestamp, bool* pbMark, uint32_t* pnSeqNum, uint32_t currentTime);

private:
    bool IsSID(uint32_t nBufferSize);
    bool Resync(uint32_t currentTime);
    void CollectRxRtpStatus(int32_t seq, kRtpPacketStatus status);
    void CollectJitterBufferStatus(int32_t currSize, int32_t maxSize);

    JitterNetworkAnalyser mJitterAnalyzer;
    bool mDtxOn;
    bool mBufferIgnoreSIDPacket;
    bool mNeedToUpdateBasePacket;
    bool mWaiting;
    bool mEnforceUpdate;
    uint32_t mCannotGetCount;
    uint32_t mCurrPlayingTS;
    uint32_t mBaseTimestamp;
    uint32_t mBaseArrivalTime;
    uint32_t mCheckUpdateJitterPacketCnt;
    uint32_t mCurrJitterBufferSize;
    uint32_t mSIDCount;
    uint32_t mDeleteCount;
    uint32_t mNextJitterBufferSize;
};

#endif