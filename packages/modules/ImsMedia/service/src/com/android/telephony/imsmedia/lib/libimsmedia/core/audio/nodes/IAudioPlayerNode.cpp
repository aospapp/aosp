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

#include <IAudioPlayerNode.h>
#include <ImsMediaAudioPlayer.h>
#include <ImsMediaTrace.h>
#include <ImsMediaTimer.h>
#include <ImsMediaAudioUtil.h>
#include <AudioConfig.h>
#include <RtpConfig.h>
#include <string.h>

IAudioPlayerNode::IAudioPlayerNode(BaseSessionCallback* callback) :
        JitterBufferControlNode(callback, IMS_MEDIA_AUDIO)
{
    std::unique_ptr<ImsMediaAudioPlayer> track(new ImsMediaAudioPlayer());
    mAudioPlayer = std::move(track);
    mConfig = nullptr;
    mIsOctetAligned = false;
    mIsDtxEnabled = false;
}

IAudioPlayerNode::~IAudioPlayerNode()
{
    if (mConfig != nullptr)
    {
        delete mConfig;
    }
}

kBaseNodeId IAudioPlayerNode::GetNodeId()
{
    return kNodeIdAudioPlayer;
}

ImsMediaResult IAudioPlayerNode::ProcessStart()
{
    IMLOGD2("[ProcessStart] codec[%d], mode[%d]", mCodecType, mMode);
    if (mJitterBuffer)
    {
        mJitterBuffer->SetCodecType(mCodecType);
    }

    // reset the jitter
    Reset();

    if (mAudioPlayer)
    {
        mAudioPlayer->SetCodec(mCodecType);
        mAudioPlayer->SetSamplingRate(mSamplingRate * 1000);
        mAudioPlayer->SetDtxEnabled(mIsDtxEnabled);
        mAudioPlayer->SetOctetAligned(mIsOctetAligned);

        if (mCodecType == kAudioCodecEvs)
        {
            mAudioPlayer->SetEvsBandwidth((int32_t)mEvsBandwidth);
            mAudioPlayer->SetEvsPayloadHeaderMode(mEvsPayloadHeaderMode);
            mAudioPlayer->SetEvsBitRate(ImsMediaAudioUtil::ConvertEVSModeToBitRate(
                    ImsMediaAudioUtil::GetMaximumEvsMode(mMode)));
            mAudioPlayer->SetCodecMode(ImsMediaAudioUtil::GetMaximumEvsMode(mMode));
        }

        mAudioPlayer->Start();
    }
    else
    {
        IMLOGE0("[IAudioPlayer] Not able to start AudioPlayer");
    }

    mNodeState = kNodeStateRunning;
    StartThread();
    return RESULT_SUCCESS;
}

void IAudioPlayerNode::Stop()
{
    IMLOGD0("[Stop]");

    if (mAudioPlayer)
    {
        mAudioPlayer->Stop();
    }

    StopThread();
    mCondition.wait_timeout(AUDIO_STOP_TIMEOUT);
    mNodeState = kNodeStateStopped;
}

bool IAudioPlayerNode::IsRunTime()
{
    return true;
}

bool IAudioPlayerNode::IsRunTimeStart()
{
    return false;
}

bool IAudioPlayerNode::IsSourceNode()
{
    return false;
}

void IAudioPlayerNode::SetConfig(void* config)
{
    if (config == nullptr)
    {
        return;
    }

    if (mConfig != nullptr)
    {
        delete mConfig;
        mConfig = nullptr;
    }

    mConfig = new AudioConfig(*static_cast<AudioConfig*>(config));
    mCodecType = ImsMediaAudioUtil::ConvertCodecType(mConfig->getCodecType());

    if (mCodecType == kAudioCodecAmr || mCodecType == kAudioCodecAmrWb)
    {
        mMode = mConfig->getAmrParams().getAmrMode();
        mIsOctetAligned = mConfig->getAmrParams().getOctetAligned();
    }
    else if (mCodecType == kAudioCodecEvs)
    {
        mMode = mConfig->getEvsParams().getEvsMode();
        mEvsChannelAwOffset = mConfig->getEvsParams().getChannelAwareMode();
        mEvsBandwidth = ImsMediaAudioUtil::FindMaxEvsBandwidthFromRange(
                mConfig->getEvsParams().getEvsBandwidth());
        mEvsPayloadHeaderMode = mConfig->getEvsParams().getUseHeaderFullOnly();
    }

    mSamplingRate = mConfig->getSamplingRateKHz();
    mIsDtxEnabled = mConfig->getDtxEnabled();
    SetJitterBufferSize(3, 3, 9);
    SetJitterOptions(
            80, 1, (double)25 / 10, false /** TODO: when enable DTX, set this true on condition*/
    );
}

bool IAudioPlayerNode::IsSameConfig(void* config)
{
    if (config == nullptr)
    {
        return true;
    }

    AudioConfig* pConfig = reinterpret_cast<AudioConfig*>(config);

    if (mCodecType == ImsMediaAudioUtil::ConvertCodecType(pConfig->getCodecType()))
    {
        if (mCodecType == kAudioCodecAmr || mCodecType == kAudioCodecAmrWb)
        {
            return (mMode == pConfig->getAmrParams().getAmrMode() &&
                    mSamplingRate == pConfig->getSamplingRateKHz() &&
                    mIsDtxEnabled == pConfig->getDtxEnabled() &&
                    mIsOctetAligned == pConfig->getAmrParams().getOctetAligned());
        }
        else if (mCodecType == kAudioCodecEvs)
        {
            return (mMode == pConfig->getEvsParams().getEvsMode() &&
                    mEvsBandwidth ==
                            ImsMediaAudioUtil::FindMaxEvsBandwidthFromRange(
                                    pConfig->getEvsParams().getEvsBandwidth()) &&
                    mEvsChannelAwOffset == pConfig->getEvsParams().getChannelAwareMode() &&
                    mSamplingRate == pConfig->getSamplingRateKHz() &&
                    mEvsPayloadHeaderMode == pConfig->getEvsParams().getUseHeaderFullOnly() &&
                    mIsDtxEnabled == pConfig->getDtxEnabled());
        }
    }

    return false;
}

void* IAudioPlayerNode::run()
{
    IMLOGD0("[run] enter");
    ImsMediaSubType subtype = MEDIASUBTYPE_UNDEFINED;
    ImsMediaSubType datatype = MEDIASUBTYPE_UNDEFINED;
    uint8_t* pData = nullptr;
    uint32_t nDataSize = 0;
    uint32_t nTimestamp = 0;
    bool bMark = false;
    uint32_t nSeqNum = 0;
    uint64_t nNextTime = ImsMediaTimer::GetTimeInMicroSeconds();
    bool isFirstFrameReceived = false;

    while (true)
    {
        if (IsThreadStopped())
        {
            IMLOGD0("[run] terminated");
            mCondition.signal();
            break;
        }

        if (GetData(&subtype, &pData, &nDataSize, &nTimestamp, &bMark, &nSeqNum, &datatype) == true)
        {
            IMLOGD_PACKET2(IM_PACKET_LOG_AUDIO, "[run] write buffer size[%d], TS[%u]", nDataSize,
                    nTimestamp);
            if (nDataSize != 0)
            {
                if (mAudioPlayer->onDataFrame(pData, nDataSize))
                {
                    // send buffering complete message to client
                    if (isFirstFrameReceived == false)
                    {
                        mCallback->SendEvent(kImsMediaEventFirstPacketReceived,
                                reinterpret_cast<uint64_t>(new AudioConfig(*mConfig)));
                        isFirstFrameReceived = true;
                    }
                }
            }
            DeleteData();
        }
        else if (isFirstFrameReceived)
        {
            IMLOGE0("[run] GetData returned 0 bytes");
            mAudioPlayer->onDataFrame(nullptr, 0);
        }

        nNextTime += 20000;
        uint64_t nCurrTime = ImsMediaTimer::GetTimeInMicroSeconds();
        int64_t nTime = nNextTime - nCurrTime;

        if (nTime < 0)
        {
            continue;
        }

        ImsMediaTimer::USleep(nTime);
    }
    return nullptr;
}
