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

#include <AudioJitterBuffer.h>
#include <ImsMediaDataQueue.h>
#include <ImsMediaTimer.h>
#include <ImsMediaTrace.h>

#define AUDIO_JITTER_BUFFER_MIN_SIZE   (3)
#define AUDIO_JITTER_BUFFER_MAX_SIZE   (9)
#define AUDIO_JITTER_BUFFER_START_SIZE (4)
#define GET_SEQ_GAP(a, b)              ((uint16_t)(a) - (uint16_t)(b))
#define JITTER_BUFFER_UPDATE_INTERVAL  (2000)   // ms unit
#define FRAME_INTERVAL                 (20)     // ms unit
#define ALLOWABLE_ERROR                (10)     // ms unit
#define RESET_THRESHOLD                (10000)  // ms unit
#define TS_ROUND_QUARD                 (3000)   // ms unit
#define USHORT_TS_ROUND_COMPARE(a, b)                                             \
    (((a) >= (b) && (b) >= TS_ROUND_QUARD) || ((a) <= 0xffff - TS_ROUND_QUARD) || \
            ((a) <= TS_ROUND_QUARD && (b) >= 0xffff - TS_ROUND_QUARD))

AudioJitterBuffer::AudioJitterBuffer()
{
    mInitJitterBufferSize = AUDIO_JITTER_BUFFER_START_SIZE;
    mMinJitterBufferSize = AUDIO_JITTER_BUFFER_MIN_SIZE;
    mMaxJitterBufferSize = AUDIO_JITTER_BUFFER_MAX_SIZE;
    AudioJitterBuffer::Reset();
    mBufferIgnoreSIDPacket = false;
}

AudioJitterBuffer::~AudioJitterBuffer() {}

void AudioJitterBuffer::Reset()
{
    mFirstFrameReceived = false;
    mNewInputData = false;
    mLastPlayedSeqNum = 0;
    mLastPlayedTimestamp = 0;
    mNextJitterBufferSize = mCurrJitterBufferSize;
    mDtxOn = false;
    mSIDCount = 0;
    mWaiting = true;
    mDeleteCount = 0;
    mBaseTimestamp = 0;
    mBaseArrivalTime = 0;
    mCannotGetCount = 0;
    mCheckUpdateJitterPacketCnt = 0;
    mEnforceUpdate = false;
    mNeedToUpdateBasePacket = false;

    mMutex.lock();
    DataEntry* entry = nullptr;

    while (mDataQueue.Get(&entry))
    {
        CollectRxRtpStatus(entry->nSeqNum, kRtpStatusDiscarded);
        mDataQueue.Delete();
    }

    mMutex.unlock();

    mJitterAnalyzer.Reset();
    mJitterAnalyzer.SetMinMaxJitterBufferSize(mMinJitterBufferSize, mMaxJitterBufferSize);
}

void AudioJitterBuffer::SetJitterBufferSize(uint32_t nInit, uint32_t nMin, uint32_t nMax)
{
    IMLOGD3("[SetJitterBufferSize] %02x, %02x, %02x", nInit, nMin, nMax);

    if (nMin > 0)
    {
        mMinJitterBufferSize = nMin;
    }

    if (nMax > 0)
    {
        mMaxJitterBufferSize = nMax;
    }

    if (nInit > 0)
    {
        if (nInit < mMinJitterBufferSize)
        {
            nInit = mMinJitterBufferSize;
        }

        if (nInit > mMaxJitterBufferSize)
        {
            nInit = mMaxJitterBufferSize;
        }

        mInitJitterBufferSize = nInit;
        mCurrJitterBufferSize = mInitJitterBufferSize;
        mNextJitterBufferSize = mInitJitterBufferSize;
    }

    mJitterAnalyzer.SetMinMaxJitterBufferSize(mMinJitterBufferSize, mMaxJitterBufferSize);
}

void AudioJitterBuffer::SetJitterOptions(
        uint32_t nReduceTH, uint32_t nStepSize, double zValue, bool bIgnoreSID)
{
    mBufferIgnoreSIDPacket = bIgnoreSID;
    mJitterAnalyzer.SetJitterOptions(nReduceTH, nStepSize, zValue);
}

void AudioJitterBuffer::Add(ImsMediaSubType subtype, uint8_t* pbBuffer, uint32_t nBufferSize,
        uint32_t nTimestamp, bool bMark, uint32_t nSeqNum, ImsMediaSubType /*nDataType*/,
        uint32_t arrivalTime)
{
    DataEntry currEntry = DataEntry();
    currEntry.subtype = subtype;
    currEntry.pbBuffer = pbBuffer;
    currEntry.nBufferSize = nBufferSize;
    currEntry.nTimestamp = nTimestamp;
    currEntry.bMark = bMark;
    currEntry.nSeqNum = nSeqNum;
    currEntry.bHeader = true;
    currEntry.bValid = true;
    currEntry.arrivalTime = arrivalTime;

    int32_t jitter = 0;

    if (mCannotGetCount > mMaxJitterBufferSize)
    {
        IMLOGD0("[Add] reset");
        Reset();
    }

    if (!mBufferIgnoreSIDPacket)
    {
        jitter = mJitterAnalyzer.CalculateTransitTimeDifference(nTimestamp, arrivalTime);
        mBaseTimestamp = currEntry.nTimestamp;
        mBaseArrivalTime = currEntry.arrivalTime;
        mJitterAnalyzer.UpdateBaseTimestamp(mBaseTimestamp, mBaseArrivalTime);
    }
    // TODO: remove mBufferIgnoreSIDPacket logic and the statements
    else if (mBufferIgnoreSIDPacket && !IsSID(currEntry.nBufferSize))
    {
        // first packet delay compensation
        if ((mBaseTimestamp == 0 && mBaseArrivalTime == 0) || mNeedToUpdateBasePacket)
        {
            mBaseTimestamp = currEntry.nTimestamp;
            mBaseArrivalTime = currEntry.arrivalTime;
            mJitterAnalyzer.UpdateBaseTimestamp(mBaseTimestamp, mBaseArrivalTime);
            mNeedToUpdateBasePacket = false;
        }
        else if (mBaseTimestamp > currEntry.nTimestamp || mBaseArrivalTime > currEntry.arrivalTime)
        {
            // rounding case (more consider case)
            mBaseTimestamp = currEntry.nTimestamp;
            mBaseArrivalTime = currEntry.arrivalTime;
            mJitterAnalyzer.UpdateBaseTimestamp(mBaseTimestamp, mBaseArrivalTime);
        }
        else
        {
            // update case
            if (currEntry.nTimestamp - mBaseTimestamp > currEntry.arrivalTime - mBaseArrivalTime)
            {
                mBaseTimestamp = currEntry.nTimestamp;
                mBaseArrivalTime = currEntry.arrivalTime;
                mJitterAnalyzer.UpdateBaseTimestamp(mBaseTimestamp, mBaseArrivalTime);
            }
            else
            {
                // compensation case
                uint32_t temp = currEntry.arrivalTime;
                IMLOGD_PACKET2(IM_PACKET_LOG_JITTER, "Before compensation[%u], nSeqNum[%d]", temp,
                        currEntry.nSeqNum);
                currEntry.arrivalTime = mBaseArrivalTime + (currEntry.nTimestamp - mBaseTimestamp);
                IMLOGD_PACKET2(IM_PACKET_LOG_JITTER, "After compensation[%u], delay[%u]",
                        currEntry.arrivalTime, temp - currEntry.arrivalTime);
            }
        }

        jitter = mJitterAnalyzer.CalculateTransitTimeDifference(nTimestamp, arrivalTime);
    }

    RtpPacket* packet = new RtpPacket();

    if (nBufferSize == 0)
    {
        packet->rtpDataType = kRtpDataTypeNoData;
    }
    else
    {
        IsSID(currEntry.nBufferSize) ? packet->rtpDataType = kRtpDataTypeSid
                                     : packet->rtpDataType = kRtpDataTypeNormal;
    }

    packet->ssrc = mSsrc;
    packet->seqNum = nSeqNum;
    packet->jitter = jitter;
    packet->arrival = arrivalTime;
    mCallback->SendEvent(kCollectPacketInfo, kStreamRtpRx, reinterpret_cast<uint64_t>(packet));

    if (nBufferSize == 0)
    {
        return;
    }

    std::lock_guard<std::mutex> guard(mMutex);

    IMLOGD_PACKET7(IM_PACKET_LOG_JITTER,
            "[Add] seq[%d], bMark[%d], TS[%d], size[%d] subtype[%d] queueSize[%d], arrivalTime[%u]",
            nSeqNum, bMark, nTimestamp, nBufferSize, subtype, mDataQueue.GetCount() + 1,
            arrivalTime);

    if (mDataQueue.GetCount() == 0)
    {  // jitter buffer is empty
        mDataQueue.Add(&currEntry);
    }
    else
    {
        DataEntry* pEntry;
        mDataQueue.GetLast(&pEntry);

        if (pEntry == nullptr)
        {
            return;
        }

        // current data is the latest data
        if (USHORT_SEQ_ROUND_COMPARE(nSeqNum, pEntry->nSeqNum))
        {
            mDataQueue.Add(&currEntry);
        }
        else
        {
            // find the position of current data and insert current data to the correct position
            mDataQueue.SetReadPosFirst();

            for (int32_t i = 0; mDataQueue.GetNext(&pEntry); i++)
            {
                // late arrival packet
                if (!USHORT_SEQ_ROUND_COMPARE(nSeqNum, pEntry->nSeqNum))
                {
                    mDataQueue.InsertAt(i, &currEntry);
                    break;
                }
            }
        }
    }
}

bool AudioJitterBuffer::Get(ImsMediaSubType* psubtype, uint8_t** ppData, uint32_t* pnDataSize,
        uint32_t* pnTimestamp, bool* pbMark, uint32_t* pnSeqNum, uint32_t currentTime)
{
    std::lock_guard<std::mutex> guard(mMutex);

    DataEntry* pEntry = nullptr;
    bool bForceToPlay = false;
    mCheckUpdateJitterPacketCnt++;

    // update jitter buffer size
    if (mCheckUpdateJitterPacketCnt * FRAME_INTERVAL > JITTER_BUFFER_UPDATE_INTERVAL)
    {
        mCurrJitterBufferSize =
                mJitterAnalyzer.GetNextJitterBufferSize(mCurrJitterBufferSize, currentTime);
        mCheckUpdateJitterPacketCnt = 0;
    }

    // enforce update when breach the reset threshold
    if (mCannotGetCount * FRAME_INTERVAL > RESET_THRESHOLD)
    {
        IMLOGD_PACKET0(IM_PACKET_LOG_JITTER, "[Get] enforce update");
        mEnforceUpdate = true;
        mWaiting = false;
        mCannotGetCount = 0;
    }

    if (mDataQueue.GetCount() == 0)
    {
        IMLOGD_PACKET0(IM_PACKET_LOG_JITTER, "[Get] fail - empty");

        if (!mWaiting)
        {
            mCurrPlayingTS += FRAME_INTERVAL;
        }

        return false;
    }
    else if (mDataQueue.Get(&pEntry) && mWaiting)
    {
        uint32_t jitterDelay = currentTime - pEntry->arrivalTime;

        if (jitterDelay <= (mCurrJitterBufferSize - 1) * FRAME_INTERVAL)
        {
            if (psubtype)
                *psubtype = MEDIASUBTYPE_UNDEFINED;
            if (ppData)
                *ppData = nullptr;
            if (pnDataSize)
                *pnDataSize = 0;
            if (pnTimestamp)
                *pnTimestamp = 0;
            if (pbMark)
                *pbMark = false;
            if (pnSeqNum)
                *pnSeqNum = 0;

            IMLOGD_PACKET4(IM_PACKET_LOG_JITTER,
                    "[Get] Wait - seq[%u], CurrJBSize[%u], delay[%u], QueueCount[%u]",
                    pEntry->nSeqNum, mCurrJitterBufferSize, jitterDelay, mDataQueue.GetCount());
            return false;
        }
        else
        {
            // resync until the frame delay is lower than current jitter buffer size
            if (Resync(currentTime))
            {
                mWaiting = false;
            }
            else
            {
                IMLOGD_PACKET4(IM_PACKET_LOG_JITTER,
                        "[Get] Wait - seq[%u], CurrJBSize[%u], delay[%u], QueueCount[%u]",
                        pEntry->nSeqNum, mCurrJitterBufferSize, jitterDelay, mDataQueue.GetCount());
                return false;
            }
        }
    }

    // adjust the playing timestamp
    if (mDataQueue.Get(&pEntry) && pEntry->nTimestamp != mCurrPlayingTS &&
            ((mCurrPlayingTS - ALLOWABLE_ERROR) < pEntry->nTimestamp) &&
            (pEntry->nTimestamp < (mCurrPlayingTS + ALLOWABLE_ERROR)))
    {
        mCurrPlayingTS = pEntry->nTimestamp;
        IMLOGD_PACKET2(IM_PACKET_LOG_JITTER, "[Get] sync playing TS[%u], seq[%d]", mCurrPlayingTS,
                pEntry->nSeqNum);
    }

    while (mDataQueue.Get(&pEntry))
    {
        if (mDeleteCount > mMinJitterBufferSize &&
                mDataQueue.GetCount() < mCurrJitterBufferSize + 1)
        {
            IMLOGD0("[Get] resync");
            uint32_t nTempBuferSize = (mCurrJitterBufferSize + AUDIO_JITTER_BUFFER_MIN_SIZE) / 2;

            if (mDataQueue.GetCount() >= nTempBuferSize)
            {
                mCurrPlayingTS = pEntry->nTimestamp;
            }
            else
            {
                mCurrPlayingTS = pEntry->nTimestamp -
                        (nTempBuferSize - mDataQueue.GetCount()) * FRAME_INTERVAL;
            }

            mNeedToUpdateBasePacket = true;
            mDeleteCount = 0;
            break;
        }

        // a >= b
        if (USHORT_TS_ROUND_COMPARE(pEntry->nTimestamp, mCurrPlayingTS))
        {
            uint32_t timediff = pEntry->nTimestamp - mCurrPlayingTS;
            mDeleteCount = 0;

            // timestamp compensation logic
            if ((timediff > 0) && (timediff < FRAME_INTERVAL))
            {
                IMLOGD2("[Get] resync - TS[%u], currTS[%u]", pEntry->nTimestamp, mCurrPlayingTS);
                bForceToPlay = true;
            }

            break;
        }
        else  // late arrival
        {
            if (IsSID(pEntry->nBufferSize))
            {
                mSIDCount++;
                mDtxOn = true;
            }
            else
            {
                mSIDCount = 0;
            }

            CollectRxRtpStatus(pEntry->nSeqNum, kRtpStatusLate);
            mDeleteCount++;
            mDataQueue.Delete();
            IMLOGD_PACKET0(IM_PACKET_LOG_JITTER, "[Get] delete late arrival");
        }
    }

    // decrease jitter buffer
    if (mDtxOn && mSIDCount > 4 && mDataQueue.GetCount() > mCurrJitterBufferSize)
    {
        if (mDataQueue.Get(&pEntry) && IsSID(pEntry->nBufferSize))
        {
            IMLOGD_PACKET5(IM_PACKET_LOG_JITTER,
                    "[Get] delete SID - seq[%d], mark[%d], TS[%u], currTS[%u], queue[%d]",
                    pEntry->nSeqNum, pEntry->bMark, pEntry->nTimestamp, mCurrPlayingTS,
                    mDataQueue.GetCount());

            mSIDCount++;
            mDtxOn = true;
            CollectRxRtpStatus(pEntry->nSeqNum, kRtpStatusDiscarded);
            mDeleteCount++;
            mDataQueue.Delete();
            bForceToPlay = true;
        }
    }

    // add condition in case of changing Seq# & TS
    if (mDataQueue.Get(&pEntry) && (pEntry->nTimestamp - mCurrPlayingTS) > TS_ROUND_QUARD)
    {
        IMLOGD4("[Get] TS changing case, enforce play [ %d / %u / %u / %d ]", pEntry->nSeqNum,
                pEntry->nTimestamp, mCurrPlayingTS, mDataQueue.GetCount());
        bForceToPlay = true;
    }

    if (mEnforceUpdate)
    {
        // removing delete packet in min JitterBuffer size
        if (mDataQueue.GetCount() > mCurrJitterBufferSize + 1)
        {
            if (mDataQueue.Get(&pEntry))
            {
                IMLOGD_PACKET5(IM_PACKET_LOG_JITTER,
                        "[Get] Delete Packets - seq[%d], bMark[%d], TS[%u], curTS[%u], "
                        "size[%d]",
                        pEntry->nSeqNum, pEntry->bMark, pEntry->nTimestamp, mCurrPlayingTS,
                        mDataQueue.GetCount());

                if (IsSID(pEntry->nBufferSize))
                {
                    mSIDCount++;
                    mDtxOn = true;
                    IMLOGD_PACKET0(IM_PACKET_LOG_JITTER, "[Get] Dtx On");
                }
                else
                {
                    mSIDCount = 0;
                    mDtxOn = false;
                    IMLOGD_PACKET0(IM_PACKET_LOG_JITTER, "[Get] Dtx Off");
                }

                CollectRxRtpStatus(pEntry->nSeqNum, kRtpStatusDiscarded);
                mDataQueue.Delete();
                bForceToPlay = true;
            }
        }

        mEnforceUpdate = false;

        if ((mDataQueue.GetCount() < 2) ||
                (mDataQueue.GetCount() < mCurrJitterBufferSize - mMinJitterBufferSize))
        {
            IMLOGD_PACKET0(IM_PACKET_LOG_JITTER, "[Get] wait stacking");
            return false;
        }
    }

    // discard duplicated packet
    if (mDataQueue.Get(&pEntry) && mFirstFrameReceived && pEntry->nSeqNum == mLastPlayedSeqNum)
    {
        IMLOGD_PACKET6(IM_PACKET_LOG_JITTER,
                "[Get] duplicate - curTS[%u], seq[%d], Mark[%d], TS[%u], size[%d], queue[%d]",
                mCurrPlayingTS, pEntry->nSeqNum, pEntry->bMark, pEntry->nTimestamp,
                pEntry->nBufferSize, mDataQueue.GetCount());
        CollectRxRtpStatus(pEntry->nSeqNum, kRtpStatusDuplicated);
        mDataQueue.Delete();
        mDeleteCount++;
    }

    if (mDataQueue.Get(&pEntry) &&
            (pEntry->nTimestamp == mCurrPlayingTS || bForceToPlay ||
                    (pEntry->nTimestamp < TS_ROUND_QUARD && mCurrPlayingTS > 0xFFFF)))
    {
        if (psubtype)
            *psubtype = pEntry->subtype;
        if (ppData)
            *ppData = pEntry->pbBuffer;
        if (pnDataSize)
            *pnDataSize = pEntry->nBufferSize;
        if (pnTimestamp)
            *pnTimestamp = pEntry->nTimestamp;
        if (pbMark)
            *pbMark = pEntry->bMark;
        if (pnSeqNum)
            *pnSeqNum = pEntry->nSeqNum;

        if (IsSID(pEntry->nBufferSize))
        {
            mSIDCount++;
            mDtxOn = true;
        }
        else
        {
            mSIDCount = 0;
        }

        if (mFirstFrameReceived)
        {
            /** Report the loss gap if the loss gap is over 0 */
            uint16_t lostGap = GET_SEQ_GAP(pEntry->nSeqNum, mLastPlayedSeqNum);

            if (lostGap > 1)
            {
                uint16_t lostSeq = mLastPlayedSeqNum + 1;
                SessionCallbackParameter* param =
                        new SessionCallbackParameter(kReportPacketLossGap, lostSeq, lostGap - 1);
                mCallback->SendEvent(kCollectOptionalInfo, reinterpret_cast<uint64_t>(param), 0);
            }
        }

        IMLOGD_PACKET7(IM_PACKET_LOG_JITTER,
                "[Get] OK - dtx[%d], curTS[%u], seq[%u], TS[%u], size[%u], delay[%u], queue[%u]",
                mDtxOn, mCurrPlayingTS, pEntry->nSeqNum, pEntry->nTimestamp, pEntry->nBufferSize,
                currentTime - pEntry->arrivalTime, mDataQueue.GetCount());

        mCurrPlayingTS = pEntry->nTimestamp + FRAME_INTERVAL;
        mFirstFrameReceived = true;
        mLastPlayedSeqNum = pEntry->nSeqNum;
        mCannotGetCount = 0;
        CollectRxRtpStatus(pEntry->nSeqNum, kRtpStatusNormal);
        CollectJitterBufferStatus(
                mCurrJitterBufferSize * FRAME_INTERVAL, mMaxJitterBufferSize * FRAME_INTERVAL);
        return true;
    }
    else
    {
        // TODO: check EVS redundancy in channel aware mode

        if (!mDtxOn)
        {
            mCannotGetCount++;
        }

        if (psubtype)
            *psubtype = MEDIASUBTYPE_UNDEFINED;
        if (ppData)
            *ppData = nullptr;
        if (pnDataSize)
            *pnDataSize = 0;
        if (pnTimestamp)
            *pnTimestamp = 0;
        if (pbMark)
            *pbMark = false;
        if (pnSeqNum)
            *pnSeqNum = 0;

        IMLOGD_PACKET2(IM_PACKET_LOG_JITTER, "[Get] fail - dtx mode[%d], curTS[%d]", mDtxOn,
                mCurrPlayingTS);

        mCurrPlayingTS += FRAME_INTERVAL;
        return false;
    }

    return false;
}

bool AudioJitterBuffer::IsSID(uint32_t frameSize)
{
    switch (mCodecType)
    {
        case kAudioCodecAmr:
        case kAudioCodecAmrWb:
        case kAudioCodecEvs:
            if (frameSize == 6 || frameSize == 5)
            {
                return true;
            }
            break;
        case kAudioCodecPcmu:
        case kAudioCodecPcma:
            return false;
        default:
            IMLOGE1("[IsSID] DTX detect method is not defined for[%u] codec", mCodecType);
            return false;
    }

    return false;
}

bool AudioJitterBuffer::Resync(uint32_t currentTime)
{
    IMLOGD0("[Resync]");
    DataEntry* entry = nullptr;

    while (mDataQueue.Get(&entry))
    {
        uint32_t timeDiff = currentTime - entry->arrivalTime;

        if (timeDiff > mCurrJitterBufferSize * FRAME_INTERVAL + ALLOWABLE_ERROR)
        {
            CollectRxRtpStatus(entry->nSeqNum, kRtpStatusDiscarded);
            mDataQueue.Delete();
        }
        else
        {
            if (!IsSID(entry->nBufferSize) ||
                    timeDiff > (mCurrJitterBufferSize - 1) * FRAME_INTERVAL)
            {
                mCurrPlayingTS = entry->nTimestamp;
                IMLOGD2("[Resync] currTs[%d], delay[%d]", mCurrPlayingTS, timeDiff);
                return true;
            }

            break;
        }
    }

    return false;
}

void AudioJitterBuffer::CollectRxRtpStatus(int32_t seq, kRtpPacketStatus status)
{
    IMLOGD_PACKET2(IM_PACKET_LOG_JITTER, "[CollectRxRtpStatus] seq[%d], status[%d]", seq, status);

    if (mCallback != nullptr)
    {
        SessionCallbackParameter* param =
                new SessionCallbackParameter(seq, status, ImsMediaTimer::GetTimeInMilliSeconds());
        mCallback->SendEvent(kCollectRxRtpStatus, reinterpret_cast<uint64_t>(param));
    }
}
void AudioJitterBuffer::CollectJitterBufferStatus(int32_t currSize, int32_t maxSize)
{
    IMLOGD_PACKET2(IM_PACKET_LOG_JITTER, "[CollectJitterBufferStatus] currSize[%d], maxSize[%d]",
            currSize, maxSize);

    if (mCallback != nullptr)
    {
        mCallback->SendEvent(kCollectJitterBufferSize, currSize, maxSize);
    }
}
