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

#include <IAudioSourceNode.h>
#include <ImsMediaAudioSource.h>
#include <ImsMediaTrace.h>
#include <ImsMediaAudioUtil.h>
#include <string.h>
#include <AudioConfig.h>
#include <RtpConfig.h>
#include <EvsParams.h>

IAudioSourceNode::IAudioSourceNode(BaseSessionCallback* callback) :
        BaseNode(callback)
{
    std::unique_ptr<ImsMediaAudioSource> recoder(new ImsMediaAudioSource());
    mAudioSource = std::move(recoder);
    mCodecType = 0;
    mCodecMode = 0;
    mRunningCodecMode = 0;
    mFirstFrame = false;
    mMediaDirection = 0;
    mIsOctetAligned = false;
    mIsDtxEnabled = false;
}

IAudioSourceNode::~IAudioSourceNode() {}

kBaseNodeId IAudioSourceNode::GetNodeId()
{
    return kNodeIdAudioSource;
}

ImsMediaResult IAudioSourceNode::ProcessStart()
{
    IMLOGD2("[ProcessStart] codec[%d], mode[%d]", mCodecType, mCodecMode);

    if (mAudioSource)
    {
        mAudioSource->SetUplinkCallback(this);
        mAudioSource->SetCodec(mCodecType);
        mRunningCodecMode = ImsMediaAudioUtil::GetMaximumAmrMode(mCodecMode);
        mAudioSource->SetPtime(mPtime);
        mAudioSource->SetSamplingRate(mSamplingRate * 1000);
        mAudioSource->SetMediaDirection(mMediaDirection);
        mAudioSource->SetDtxEnabled(mIsDtxEnabled);
        mAudioSource->SetOctetAligned(mIsOctetAligned);

        if (mCodecType == kAudioCodecEvs)
        {
            mAudioSource->SetEvsBandwidth((int32_t)mEvsBandwidth);
            mAudioSource->SetEvsChAwOffset(mEvsChAwOffset);
            mRunningCodecMode = ImsMediaAudioUtil::GetMaximumEvsMode(mCodecMode);
            mAudioSource->SetEvsBitRate(
                    ImsMediaAudioUtil::ConvertEVSModeToBitRate(mRunningCodecMode));
        }
        mAudioSource->SetCodecMode(mRunningCodecMode);

        if (mAudioSource->Start())
        {
            mNodeState = kNodeStateRunning;
            mFirstFrame = false;
            return RESULT_SUCCESS;
        }
    }
    else
    {
        IMLOGE0("[IAudioSourceNode] Not able to start AudioSource");
    }

    return RESULT_NOT_READY;
}

void IAudioSourceNode::Stop()
{
    IMLOGD0("[Stop]");

    if (mAudioSource)
    {
        mAudioSource->Stop();
    }

    mNodeState = kNodeStateStopped;
}

bool IAudioSourceNode::IsRunTime()
{
    return true;
}

bool IAudioSourceNode::IsRunTimeStart()
{
    return false;
}

bool IAudioSourceNode::IsSourceNode()
{
    return true;
}

void IAudioSourceNode::SetConfig(void* config)
{
    if (config == nullptr)
    {
        return;
    }

    AudioConfig* pConfig = reinterpret_cast<AudioConfig*>(config);
    mCodecType = ImsMediaAudioUtil::ConvertCodecType(pConfig->getCodecType());

    if (mCodecType == kAudioCodecAmr || mCodecType == kAudioCodecAmrWb)
    {
        mCodecMode = pConfig->getAmrParams().getAmrMode();
        mIsOctetAligned = pConfig->getAmrParams().getOctetAligned();
    }
    else if (mCodecType == kAudioCodecEvs)
    {
        mCodecMode = pConfig->getEvsParams().getEvsMode();
        mEvsBandwidth = ImsMediaAudioUtil::FindMaxEvsBandwidthFromRange(
                pConfig->getEvsParams().getEvsBandwidth());
        mEvsChAwOffset = pConfig->getEvsParams().getChannelAwareMode();
    }

    mMediaDirection = pConfig->getMediaDirection();
    mSamplingRate = pConfig->getSamplingRateKHz();
    mPtime = pConfig->getPtimeMillis();
    mIsDtxEnabled = pConfig->getDtxEnabled();
}

bool IAudioSourceNode::IsSameConfig(void* config)
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
            return (mCodecMode == pConfig->getAmrParams().getAmrMode() &&
                    mSamplingRate == pConfig->getSamplingRateKHz() &&
                    mMediaDirection == pConfig->getMediaDirection() &&
                    mIsDtxEnabled == pConfig->getDtxEnabled() &&
                    mIsOctetAligned == pConfig->getAmrParams().getOctetAligned());
        }
        else if (mCodecType == kAudioCodecEvs)
        {
            return (mCodecMode == pConfig->getEvsParams().getEvsMode() &&
                    mEvsBandwidth ==
                            ImsMediaAudioUtil::FindMaxEvsBandwidthFromRange(
                                    pConfig->getEvsParams().getEvsBandwidth()) &&
                    mEvsChAwOffset == pConfig->getEvsParams().getChannelAwareMode() &&
                    mSamplingRate == pConfig->getSamplingRateKHz() &&
                    mMediaDirection == pConfig->getMediaDirection() &&
                    mIsDtxEnabled == pConfig->getDtxEnabled());
        }
    }

    return false;
}

void IAudioSourceNode::onDataFrame(uint8_t* buffer, uint32_t size, int64_t timestamp, uint32_t flag)
{
    IMLOGD_PACKET3(IM_PACKET_LOG_AUDIO, "[onDataFrame] size[%zu], TS[%ld], flag[%d]", size,
            timestamp, flag);
    SendDataToRearNode(
            MEDIASUBTYPE_UNDEFINED, buffer, size, timestamp, !mFirstFrame, MEDIASUBTYPE_UNDEFINED);

    if (!mFirstFrame)
    {
        mFirstFrame = true;
    }
}

void IAudioSourceNode::ProcessCmr(uint32_t cmr)
{
    IMLOGD1("[ProcessCmr] cmr[%d]", cmr);

    if (mAudioSource == nullptr)
    {
        return;
    }

    if (cmr == 15)  // change mode to original one
    {
        if (mCodecType == kAudioCodecAmr || mCodecType == kAudioCodecAmrWb)
        {
            int32_t mode = ImsMediaAudioUtil::GetMaximumAmrMode(mCodecMode);

            if (mRunningCodecMode != mode)
            {
                mAudioSource->ProcessCmr(mode);
                mRunningCodecMode = mode;
            }
        }
        else if (mCodecType == kAudioCodecEvs)
        {
            /** TODO: add implementation */
        }
    }
    else
    {
        if (mRunningCodecMode != cmr)
        {
            mAudioSource->ProcessCmr(cmr);
            mRunningCodecMode = cmr;
        }
    }
}
