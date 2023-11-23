/**
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License") {
}

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

#include <MediaQualityAnalyzer.h>
#include <ImsMediaTimer.h>
#include <ImsMediaTrace.h>
#include <ImsMediaAudioUtil.h>
#include <RtcpXrEncoder.h>
#include <AudioConfig.h>
#include <stdlib.h>
#include <algorithm>
#include <numeric>

using namespace android::telephony::imsmedia;

#define DEFAULT_PARAM                            (-1)
#define DEFAULT_INACTIVITY_TIME_FOR_CALL_QUALITY (4)
#define CALL_QUALITY_MONITORING_TIME             (5)
#define MAX_NUM_PACKET_STORED                    (500)
#define DELETE_ALL                               (65536)
#define TIMER_INTERVAL                           (1000)   // 1 sec
#define STOP_TIMEOUT                             (1000)   // 1 sec
#define MESSAGE_PROCESSING_INTERVAL              (20000)  // 20 msec
#define MEDIA_DIRECTION_CONTAINS_RECEIVE(a)            \
    ((a) == RtpConfig::MEDIA_DIRECTION_SEND_RECEIVE || \
            (a) == RtpConfig::MEDIA_DIRECTION_RECEIVE_ONLY)

MediaQualityAnalyzer::MediaQualityAnalyzer()
{
    mTimeStarted = 0;
    mCodecType = 0;
    mCodecAttribute = 0;
    mIsRxRtpEnabled = false;
    mIsRtcpEnabled = false;
    mCallback = nullptr;
    std::unique_ptr<RtcpXrEncoder> analyzer(new RtcpXrEncoder());
    mRtcpXrEncoder = std::move(analyzer);
    mBaseRtpInactivityTimes.clear();
    mCurrentRtpInactivityTimes.clear();
    mRtcpInactivityTime = 0;
    mRtpHysteresisTime = 0;
    mPacketLossDuration = 0;
    mPacketLossThreshold.clear();
    mJitterThreshold.clear();
    mNotifyStatus = false;
    mCountRtpInactivity = 0;
    mCountRtcpInactivity = 0;
    mNumRtcpPacketReceived = 0;
    reset();
}

MediaQualityAnalyzer::~MediaQualityAnalyzer()
{
    if (!IsThreadStopped())
    {
        stop();
    }
}

void MediaQualityAnalyzer::setConfig(AudioConfig* config)
{
    if (!isSameConfig(config))
    {
        reset();
    }

    mIsRxRtpEnabled = MEDIA_DIRECTION_CONTAINS_RECEIVE(config->getMediaDirection());
    mCodecType = config->getCodecType();
    mCodecAttribute = config->getEvsParams().getEvsBandwidth();

    mCallQuality.setCodecType(convertAudioCodecType(
            mCodecType, ImsMediaAudioUtil::FindMaxEvsBandwidthFromRange(mCodecAttribute)));

    if (mCodecType == AudioConfig::CODEC_AMR)
    {
        mRtcpXrEncoder->setSamplingRate(8);
    }
    else
    {
        mRtcpXrEncoder->setSamplingRate(16);
    }

    // Enable RTCP if both interval and direction is valid
    bool isRtcpEnabled = (config->getRtcpConfig().getIntervalSec() > 0 &&
            config->getMediaDirection() != RtpConfig::MEDIA_DIRECTION_NO_FLOW);

    if (mIsRtcpEnabled != isRtcpEnabled)
    {
        mIsRtcpEnabled = isRtcpEnabled;
        mCountRtcpInactivity = 0;
        mNumRtcpPacketReceived = 0;
    }

    IMLOGI4("[setConfig] codec type[%d], bandwidth[%d], rxRtp[%d], rtcp[%d]", mCodecType,
            mCodecAttribute, mIsRxRtpEnabled, mIsRtcpEnabled);
}

void MediaQualityAnalyzer::setCallback(BaseSessionCallback* callback)
{
    mCallback = callback;
}

void MediaQualityAnalyzer::setMediaQualityThreshold(const MediaQualityThreshold& threshold)
{
    mBaseRtpInactivityTimes = threshold.getRtpInactivityTimerMillis();
    mCurrentRtpInactivityTimes = mBaseRtpInactivityTimes;
    mRtcpInactivityTime = threshold.getRtcpInactivityTimerMillis();
    mRtpHysteresisTime = threshold.getRtpHysteresisTimeInMillis();
    mPacketLossDuration = threshold.getRtpPacketLossDurationMillis();
    mPacketLossThreshold = threshold.getRtpPacketLossRate();
    mJitterThreshold = threshold.getRtpJitterMillis();
    mNotifyStatus = threshold.getNotifyCurrentStatus();

    mCountRtpInactivity = 0;
    mCountRtcpInactivity = 0;
    mNumRtcpPacketReceived = 0;

    // reset the status
    mQualityStatus = MediaQualityStatus();

    mPacketLossChecker.initialize(mRtpHysteresisTime);
    mJitterChecker.initialize(mRtpHysteresisTime);
}

bool MediaQualityAnalyzer::isSameConfig(AudioConfig* config)
{
    return (mCodecType == config->getCodecType() &&
            mCodecAttribute == config->getEvsParams().getEvsBandwidth() &&
            mIsRxRtpEnabled == MEDIA_DIRECTION_CONTAINS_RECEIVE(config->getMediaDirection()));
}

void MediaQualityAnalyzer::start()
{
    if (IsThreadStopped())
    {
        IMLOGD0("[start]");
        mTimeStarted = ImsMediaTimer::GetTimeInMilliSeconds();
        StartThread();
    }
}

void MediaQualityAnalyzer::stop()
{
    IMLOGD0("[stop]");

    if (!IsThreadStopped())
    {
        StopThread();
        mConditionExit.wait_timeout(STOP_TIMEOUT);
        notifyCallQuality();
    }

    reset();
}

void MediaQualityAnalyzer::collectInfo(const int32_t streamType, RtpPacket* packet)
{
    if (streamType == kStreamRtpTx && packet != nullptr)
    {
        mListTxPacket.push_back(packet);

        if (mListTxPacket.size() >= MAX_NUM_PACKET_STORED)
        {
            RtpPacket* pPacket = mListTxPacket.front();
            mListTxPacket.pop_front();
            delete pPacket;
        }

        mCallQuality.setNumRtpPacketsTransmitted(mCallQuality.getNumRtpPacketsTransmitted() + 1);
        IMLOGD_PACKET1(IM_PACKET_LOG_RTP, "[collectInfo] tx list size[%d]", mListTxPacket.size());
    }
    else if (streamType == kStreamRtpRx && packet != nullptr)
    {
        // for call quality report
        mCallQuality.setNumRtpPacketsReceived(mCallQuality.getNumRtpPacketsReceived() + 1);
        mCallQualitySumRelativeJitter += packet->jitter;

        if (mCallQuality.getMaxRelativeJitter() < packet->jitter)
        {
            mCallQuality.setMaxRelativeJitter(packet->jitter);
        }

        mCallQuality.setAverageRelativeJitter(
                mCallQualitySumRelativeJitter / mCallQuality.getNumRtpPacketsReceived());

        switch (packet->rtpDataType)
        {
            case kRtpDataTypeNoData:
                mCallQuality.setNumNoDataFrames(mCallQuality.getNumNoDataFrames() + 1);
                break;
            case kRtpDataTypeSid:
                mCallQuality.setNumRtpSidPacketsReceived(
                        mCallQuality.getNumRtpSidPacketsReceived() + 1);
                break;
            default:
            case kRtpDataTypeNormal:
                break;
        }

        // for jitter check
        if (mSSRC != packet->ssrc)  // stream is reset
        {
            mJitterRxPacket = std::abs(packet->jitter);
            // update rtcp-xr params
            mRtcpXrEncoder->setSsrc(packet->ssrc);
        }
        else
        {
            mJitterRxPacket =
                    mJitterRxPacket + (double)(std::abs(packet->jitter) - mJitterRxPacket) * 0.0625;
        }

        mSSRC = packet->ssrc;
        mNumRxPacket++;
        mListRxPacket.push_back(packet);

        if (mListRxPacket.size() >= MAX_NUM_PACKET_STORED)
        {
            RtpPacket* pPacket = mListRxPacket.front();
            mListRxPacket.pop_front();
            delete pPacket;
        }

        IMLOGD_PACKET3(IM_PACKET_LOG_RTP, "[collectInfo] seq[%d], jitter[%d], rx list size[%d]",
                packet->seqNum, packet->jitter, mListRxPacket.size());
    }
    else if (streamType == kStreamRtcp)
    {
        mNumRtcpPacketReceived++;
        IMLOGD_PACKET1(
                IM_PACKET_LOG_RTP, "[collectInfo] rtcp received[%d]", mNumRtcpPacketReceived);
    }
}

void MediaQualityAnalyzer::collectOptionalInfo(
        const int32_t optionType, const int32_t seq, const int32_t value)
{
    IMLOGD_PACKET3(IM_PACKET_LOG_RTP, "[collectOptionalInfo] optionType[%d], seq[%d], value[%d]",
            optionType, seq, value);

    if (optionType == kTimeToLive)
    {
        // TODO : pass data to rtcp-xr
    }
    else if (optionType == kRoundTripDelay)
    {
        mSumRoundTripTime += value;
        mCountRoundTripTime++;
        mCallQuality.setAverageRoundTripTime(mSumRoundTripTime / mCountRoundTripTime);

        mRtcpXrEncoder->setRoundTripDelay(value);
    }
    else if (optionType == kReportPacketLossGap)
    {
        LostPacket* entry = new LostPacket(seq, value, ImsMediaTimer::GetTimeInMilliSeconds());
        mListLostPacket.push_back(entry);

        for (int32_t i = 0; i < value; i++)
        {
            // for rtcp xr
            mRtcpXrEncoder->stackRxRtpStatus(kRtpStatusLost, 0);

            // for call quality report
            mCallQuality.setNumRtpPacketsNotReceived(
                    mCallQuality.getNumRtpPacketsNotReceived() + 1);
            mCallQualityNumLostPacket++;
            // for loss checking
            mNumLostPacket++;
        }

        IMLOGD_PACKET3(IM_PACKET_LOG_RTP,
                "[collectOptionalInfo] lost packet seq[%d], value[%d], list size[%d]", seq, value,
                mListLostPacket.size());
    }
}

void MediaQualityAnalyzer::collectRxRtpStatus(
        const int32_t seq, const kRtpPacketStatus status, const uint32_t time)
{
    if (mListRxPacket.empty())
    {
        return;
    }

    bool found = false;

    for (std::list<RtpPacket*>::reverse_iterator rit = mListRxPacket.rbegin();
            rit != mListRxPacket.rend(); ++rit)
    {
        RtpPacket* packet = *rit;

        if (packet->seqNum == seq)
        {
            packet->status = status;
            uint32_t delay = time - packet->arrival;
            mRtcpXrEncoder->stackRxRtpStatus(packet->status, delay);
            IMLOGD_PACKET3(IM_PACKET_LOG_RTP, "[collectRxRtpStatus] seq[%d], status[%d], delay[%u]",
                    seq, packet->status, delay);

            // set the max playout delay
            if (delay > mCallQuality.getMaxPlayoutDelayMillis())
            {
                mCallQuality.setMaxPlayoutDelayMillis(delay);
            }

            // set the min playout delay
            if (delay < mCallQuality.getMinPlayoutDelayMillis() ||
                    mCallQuality.getMinPlayoutDelayMillis() == 0)
            {
                mCallQuality.setMinPlayoutDelayMillis(delay);
            }

            found = true;
            break;
        }
    }

    if (!found)
    {
        IMLOGW1("[collectRxRtpStatus] no rtp packet found seq[%d]", seq);
        return;
    }

    switch (status)
    {
        case kRtpStatusNormal:
            mCallQuality.setNumVoiceFrames(mCallQuality.getNumVoiceFrames() + 1);
            mCallQualityNumRxPacket++;
            break;
        case kRtpStatusLate:
        case kRtpStatusDiscarded:
            mCallQuality.setNumDroppedRtpPackets(mCallQuality.getNumDroppedRtpPackets() + 1);
            mCallQualityNumRxPacket++;
            break;
        case kRtpStatusDuplicated:
            mCallQuality.setNumRtpDuplicatePackets(mCallQuality.getNumRtpDuplicatePackets() + 1);
            mCallQualityNumRxPacket++;
            break;
        default:
            break;
    }

    if (mBeginSeq == -1)
    {
        mBeginSeq = seq;
        mEndSeq = seq;
    }
    else
    {
        if (USHORT_SEQ_ROUND_COMPARE(seq, mEndSeq))
        {
            mEndSeq = seq;
        }
    }
}

void MediaQualityAnalyzer::collectJitterBufferSize(const int32_t currSize, const int32_t maxSize)
{
    IMLOGD_PACKET2(IM_PACKET_LOG_RTP, "[collectJitterBufferSize] current size[%d], max size[%d]",
            currSize, maxSize);

    mCurrentBufferSize = currSize;
    mMaxBufferSize = maxSize;

    mRtcpXrEncoder->setJitterBufferStatus(currSize, maxSize);
}

void MediaQualityAnalyzer::processData(const int32_t timeCount)
{
    IMLOGD_PACKET1(IM_PACKET_LOG_RTP, "[processData] count[%d]", timeCount);

    // call quality inactivity
    if (timeCount == DEFAULT_INACTIVITY_TIME_FOR_CALL_QUALITY &&
            mCallQuality.getNumRtpPacketsReceived() == 0)
    {
        mCallQuality.setRtpInactivityDetected(true);
        notifyCallQuality();
    }

    // call quality packet loss
    if (timeCount % CALL_QUALITY_MONITORING_TIME == 0)
    {
        double lossRate = 0;

        mCallQualityNumRxPacket == 0 ? lossRate = 0
                                     : lossRate = (double)mCallQualityNumLostPacket /
                        (mCallQualityNumLostPacket + mCallQualityNumRxPacket) * 100;

        int32_t quality = getCallQuality(lossRate);

        IMLOGD3("[processData] lost[%d], received[%d], quality[%d]", mCallQualityNumLostPacket,
                mCallQualityNumRxPacket, quality);

        if (mCallQuality.getDownlinkCallQualityLevel() != quality)
        {
            mCallQuality.setDownlinkCallQualityLevel(quality);
            notifyCallQuality();
        }

        mCallQualityNumLostPacket = 0;
        mCallQualityNumRxPacket = 0;
    }

    processMediaQuality();
}

void MediaQualityAnalyzer::processMediaQuality()
{
    // media quality rtp inactivity
    if (mNumRxPacket == 0 && mIsRxRtpEnabled)
    {
        mCountRtpInactivity += 1000;
    }
    else
    {
        mCountRtpInactivity = 0;
        mNumRxPacket = 0;
        mCurrentRtpInactivityTimes = mBaseRtpInactivityTimes;
    }

    // media quality rtcp inactivity
    if (mNumRtcpPacketReceived == 0 && mIsRtcpEnabled)
    {
        mCountRtcpInactivity += 1000;
    }
    else
    {
        mCountRtcpInactivity = 0;
        mNumRtcpPacketReceived = 0;
    }

    mQualityStatus.setRtpInactivityTimeMillis(mCountRtpInactivity);
    mQualityStatus.setRtcpInactivityTimeMillis(mCountRtcpInactivity);
    mQualityStatus.setRtpJitterMillis(mJitterRxPacket);

    if (mPacketLossDuration != 0 && !mListLostPacket.empty())
    {
        // counts received packets for the duration
        int32_t numReceivedPacketsInDuration =
                std::count_if(mListRxPacket.begin(), mListRxPacket.end(),
                        [=](RtpPacket* packet)
                        {
                            return (ImsMediaTimer::GetTimeInMilliSeconds() - packet->arrival <=
                                    mPacketLossDuration);
                        });

        // cumulates the number of lost packets for the duration
        std::list<LostPacket*> listLostPacketInDuration;
        std::copy_if(mListLostPacket.begin(), mListLostPacket.end(),
                std::back_inserter(listLostPacketInDuration),
                [=](LostPacket* packet)
                {
                    return (ImsMediaTimer::GetTimeInMilliSeconds() - packet->markedTime <=
                            mPacketLossDuration);
                });

        int32_t numLostPacketsInDuration =
                std::accumulate(begin(listLostPacketInDuration), end(listLostPacketInDuration), 0,
                        [=](int i, const LostPacket* packet)
                        {
                            return packet->numLoss + i;
                        });

        if (numLostPacketsInDuration == 0 || numReceivedPacketsInDuration == 0)
        {
            mQualityStatus.setRtpPacketLossRate(0);
        }
        else
        {
            int32_t lossRate = numLostPacketsInDuration * 100 /
                    (numReceivedPacketsInDuration + numLostPacketsInDuration);

            IMLOGD3("[processMediaQuality] lossRate[%d], received[%d], lost[%d]", lossRate,
                    numReceivedPacketsInDuration, numLostPacketsInDuration);
            mQualityStatus.setRtpPacketLossRate(lossRate);
        }
    }
    else
    {
        mQualityStatus.setRtpPacketLossRate(0);
    }

    bool shouldNotify = false;

    // check jitter notification
    if (!mJitterThreshold.empty() && mIsRxRtpEnabled)
    {
        if (mJitterChecker.checkNotifiable(mJitterThreshold, mQualityStatus.getRtpJitterMillis()))
        {
            shouldNotify = true;
        }
    }

    // check packet loss notification
    if (!mPacketLossThreshold.empty() && mIsRxRtpEnabled)
    {
        if (mPacketLossChecker.checkNotifiable(
                    mPacketLossThreshold, mQualityStatus.getRtpPacketLossRate()))
        {
            shouldNotify = true;
        }
    }

    IMLOGD_PACKET4(IM_PACKET_LOG_RTP,
            "[processMediaQuality] rtpInactivity[%d], rtcpInactivity[%d], lossRate[%d], "
            "jitter[%d]",
            mQualityStatus.getRtpInactivityTimeMillis(),
            mQualityStatus.getRtcpInactivityTimeMillis(), mQualityStatus.getRtpPacketLossRate(),
            mQualityStatus.getRtpJitterMillis());

    if (mNotifyStatus)
    {
        notifyMediaQualityStatus();
        mNotifyStatus = false;
        return;
    }

    if (!mCurrentRtpInactivityTimes.empty() && mIsRxRtpEnabled)
    {
        std::vector<int32_t>::iterator rtpIter = std::find_if(mCurrentRtpInactivityTimes.begin(),
                mCurrentRtpInactivityTimes.end(),
                [=](int32_t inactivityTime)
                {
                    return (inactivityTime != 0 &&
                            mCountRtpInactivity >= inactivityTime);  // check cross the threshold
                });

        if (rtpIter != mCurrentRtpInactivityTimes.end())  // found
        {
            mCurrentRtpInactivityTimes.erase(rtpIter);
            notifyMediaQualityStatus();
            return;
        }
    }

    if (mRtcpInactivityTime != 0 && mCountRtcpInactivity == mRtcpInactivityTime && mIsRtcpEnabled)
    {
        notifyMediaQualityStatus();
        mCountRtcpInactivity = 0;
        return;
    }

    if (shouldNotify)
    {
        notifyMediaQualityStatus();
    }
}

void MediaQualityAnalyzer::notifyCallQuality()
{
    if (mCallback != nullptr)
    {
        mCallQuality.setCallDuration(ImsMediaTimer::GetTimeInMilliSeconds() - mTimeStarted);

        IMLOGD1("[notifyCallQuality] duration[%d]", mCallQuality.getCallDuration());
        CallQuality* callQuality = new CallQuality(mCallQuality);
        mCallback->SendEvent(kAudioCallQualityChangedInd, reinterpret_cast<uint64_t>(callQuality));

        // reset the items to keep in reporting interval
        mCallQuality.setMinPlayoutDelayMillis(0);
        mCallQuality.setMaxPlayoutDelayMillis(0);
    }
}

void MediaQualityAnalyzer::notifyMediaQualityStatus()
{
    IMLOGD0("[notifyMediaQualityStatus]");
    MediaQualityStatus* status = new MediaQualityStatus(mQualityStatus);
    mCallback->SendEvent(kImsMediaEventMediaQualityStatus, reinterpret_cast<uint64_t>(status));
}

bool MediaQualityAnalyzer::getRtcpXrReportBlock(
        const uint32_t rtcpXrReport, uint8_t* data, uint32_t& size)
{
    IMLOGD1("[getRtcpXrReportBlock] rtcpXrReport[%d]", rtcpXrReport);

    if (rtcpXrReport == 0)
    {
        return false;
    }

    if (!mRtcpXrEncoder->createRtcpXrReport(
                rtcpXrReport, &mListRxPacket, &mListLostPacket, mBeginSeq, mEndSeq, data, size))
    {
        IMLOGW0("[getRtcpXrReportBlock] fail to createRtcpXrReport");
        return false;
    }

    mBeginSeq = mEndSeq + 1;
    clearPacketList(mListRxPacket, mEndSeq);
    clearPacketList(mListTxPacket, mEndSeq);
    clearLostPacketList(mEndSeq);
    return true;
}

CallQuality MediaQualityAnalyzer::getCallQuality()
{
    return mCallQuality;
}

uint32_t MediaQualityAnalyzer::getRxPacketSize()
{
    return mListRxPacket.size();
}

uint32_t MediaQualityAnalyzer::getTxPacketSize()
{
    return mListTxPacket.size();
}

uint32_t MediaQualityAnalyzer::getLostPacketSize()
{
    return std::accumulate(begin(mListLostPacket), end(mListLostPacket), 0,
            [](int i, const LostPacket* packet)
            {
                return packet->numLoss + i;
            });
}

void MediaQualityAnalyzer::SendEvent(uint32_t event, uint64_t paramA, uint64_t paramB)
{
    AddEvent(event, paramA, paramB);
}

void MediaQualityAnalyzer::AddEvent(uint32_t event, uint64_t paramA, uint64_t paramB)
{
    IMLOGD_PACKET2(IM_PACKET_LOG_RTP, "[AddEvent] event[%d], size[%d]", event, mListevent.size());
    std::lock_guard<std::mutex> guard(mEventMutex);
    mListevent.push_back(event);
    mListParamA.push_back(paramA);
    mListParamB.push_back(paramB);
}

void MediaQualityAnalyzer::processEvent(uint32_t event, uint64_t paramA, uint64_t paramB)
{
    switch (event)
    {
        case kRequestRoundTripTimeDelayUpdate:
            collectOptionalInfo(kRoundTripDelay, 0, paramA);
            break;
        case kCollectPacketInfo:
            collectInfo(
                    static_cast<ImsMediaStreamType>(paramA), reinterpret_cast<RtpPacket*>(paramB));
            break;
        case kCollectOptionalInfo:
            if (paramA != 0)
            {
                SessionCallbackParameter* param =
                        reinterpret_cast<SessionCallbackParameter*>(paramA);
                collectOptionalInfo(param->type, param->param1, param->param2);
                delete param;
            }
            break;
        case kCollectRxRtpStatus:
            if (paramA != 0)
            {
                SessionCallbackParameter* param =
                        reinterpret_cast<SessionCallbackParameter*>(paramA);
                collectRxRtpStatus(
                        param->type, static_cast<kRtpPacketStatus>(param->param1), param->param2);
                delete param;
            }
            break;
        case kCollectJitterBufferSize:
            collectJitterBufferSize(static_cast<int32_t>(paramA), static_cast<int32_t>(paramB));
            break;
        case kGetRtcpXrReportBlock:
        {
            uint32_t size = 0;
            uint8_t* reportBlock = new uint8_t[MAX_BLOCK_LENGTH]{};

            if (getRtcpXrReportBlock(static_cast<int32_t>(paramA), reportBlock, size))
            {
                mCallback->SendEvent(
                        kRequestSendRtcpXrReport, reinterpret_cast<uint64_t>(reportBlock), size);
            }
            else
            {
                delete[] reportBlock;
            }
        }
        break;
        default:
            break;
    }
}

void* MediaQualityAnalyzer::run()
{
    IMLOGD1("[run] enter, %p", this);
    uint64_t nextTime = ImsMediaTimer::GetTimeInMicroSeconds();
    int32_t timeCount = 0;
    uint32_t prevTimeInMsec = ImsMediaTimer::GetTimeInMilliSeconds();

    while (true)
    {
        if (IsThreadStopped())
        {
            IMLOGD0("[run] terminated");
            break;
        }

        nextTime += MESSAGE_PROCESSING_INTERVAL;
        uint64_t nCurrTime = ImsMediaTimer::GetTimeInMicroSeconds();
        int64_t nTime = nextTime - nCurrTime;

        if (nTime > 0)
        {
            ImsMediaTimer::USleep(nTime);
        }

        // process event in the list
        for (;;)
        {
            mEventMutex.lock();

            if (IsThreadStopped() || mListevent.size() == 0)
            {
                mEventMutex.unlock();
                break;
            }

            processEvent(mListevent.front(), mListParamA.front(), mListParamB.front());

            mListevent.pop_front();
            mListParamA.pop_front();
            mListParamB.pop_front();
            mEventMutex.unlock();
        }

        if (IsThreadStopped())
        {
            IMLOGD0("[run] terminated");
            break;
        }

        uint32_t currTimeInMsec = ImsMediaTimer::GetTimeInMilliSeconds();

        // process every TIMER_INTERVAL
        if (currTimeInMsec - prevTimeInMsec >= TIMER_INTERVAL)
        {
            processData(++timeCount);
            prevTimeInMsec = currTimeInMsec;
        }
    }

    IMLOGD1("[run] exit %p", this);
    mConditionExit.signal();
    return nullptr;
}

void MediaQualityAnalyzer::reset()
{
    mSSRC = DEFAULT_PARAM;
    mBeginSeq = -1;
    mEndSeq = -1;

    mCallQuality = CallQuality();
    mCallQualitySumRelativeJitter = 0;
    mSumRoundTripTime = 0;
    mCountRoundTripTime = 0;
    mCurrentBufferSize = 0;
    mMaxBufferSize = 0;
    mCallQualityNumRxPacket = 0;
    mCallQualityNumLostPacket = 0;
    clearPacketList(mListRxPacket, DELETE_ALL);
    clearPacketList(mListTxPacket, DELETE_ALL);
    clearLostPacketList(DELETE_ALL);
    mNumRxPacket = 0;
    mNumLostPacket = 0;
    mJitterRxPacket = 0.0;

    // rtp and rtcp inactivity
    mCountRtpInactivity = 0;
    mCountRtcpInactivity = 0;
    mNumRtcpPacketReceived = 0;

    // reset the status
    mQualityStatus = MediaQualityStatus();

    mPacketLossChecker.initialize(mRtpHysteresisTime);
    mJitterChecker.initialize(mRtpHysteresisTime);
}

void MediaQualityAnalyzer::clearPacketList(std::list<RtpPacket*>& list, const int32_t seq)
{
    if (list.empty())
    {
        return;
    }

    for (std::list<RtpPacket*>::iterator iter = list.begin(); iter != list.end();)
    {
        RtpPacket* packet = *iter;
        // do not remove the packet seq is larger than target seq
        if (packet->seqNum > seq)
        {
            iter++;
            continue;
        }

        iter = list.erase(iter);
        delete packet;
    }
}

void MediaQualityAnalyzer::clearLostPacketList(const int32_t seq)
{
    if (mListLostPacket.empty())
    {
        return;
    }

    for (std::list<LostPacket*>::iterator iter = mListLostPacket.begin();
            iter != mListLostPacket.end();)
    {
        LostPacket* packet = *iter;
        // do not remove the lost packet entry seq is larger than target seq
        if (packet->seqNum > seq)
        {
            iter++;
            continue;
        }

        iter = mListLostPacket.erase(iter);
        delete packet;
    }
}

uint32_t MediaQualityAnalyzer::getCallQuality(const double lossRate)
{
    if (lossRate < 1.0f)
    {
        return CallQuality::kCallQualityExcellent;
    }
    else if (lossRate < 3.0f)
    {
        return CallQuality::kCallQualityGood;
    }
    else if (lossRate < 5.0f)
    {
        return CallQuality::kCallQualityFair;
    }
    else if (lossRate < 8.0f)
    {
        return CallQuality::kCallQualityPoor;
    }
    else
    {
        return CallQuality::kCallQualityBad;
    }
}

int32_t MediaQualityAnalyzer::convertAudioCodecType(const int32_t codec, const int32_t bandwidth)
{
    switch (codec)
    {
        default:
            return CallQuality::AUDIO_QUALITY_NONE;
        case AudioConfig::CODEC_AMR:
            return CallQuality::AUDIO_QUALITY_AMR;
        case AudioConfig::CODEC_AMR_WB:
            return CallQuality::AUDIO_QUALITY_AMR_WB;
        case AudioConfig::CODEC_EVS:
        {
            switch (bandwidth)
            {
                default:
                case EvsParams::EVS_BAND_NONE:
                    break;
                case EvsParams::EVS_NARROW_BAND:
                    return CallQuality::AUDIO_QUALITY_EVS_NB;
                case EvsParams::EVS_WIDE_BAND:
                    return CallQuality::AUDIO_QUALITY_EVS_WB;
                case EvsParams::EVS_SUPER_WIDE_BAND:
                    return CallQuality::AUDIO_QUALITY_EVS_SWB;
                case EvsParams::EVS_FULL_BAND:
                    return CallQuality::AUDIO_QUALITY_EVS_FB;
            }
        }
    }

    return CallQuality::AUDIO_QUALITY_NONE;
}