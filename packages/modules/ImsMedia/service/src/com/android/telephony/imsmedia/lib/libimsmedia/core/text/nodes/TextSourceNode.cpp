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

#include <TextSourceNode.h>
#include <TextConfig.h>
#include <ImsMediaTimer.h>
#include <ImsMediaTrace.h>

TextSourceNode::TextSourceNode(BaseSessionCallback* callback) :
        BaseNode(callback)
{
    mCodecType = TextConfig::TEXT_CODEC_NONE;
    mRedundantLevel = 0;
    mRedundantCount = 0;
    mTimeLastSent = 0;
    mBomEnabled = false;
    mSentBOM = false;
}

TextSourceNode::~TextSourceNode() {}

kBaseNodeId TextSourceNode::GetNodeId()
{
    return kNodeIdTextSource;
}

ImsMediaResult TextSourceNode::Start()
{
    IMLOGD2("[Start] codec[%d], redundant level[%d]", mCodecType, mRedundantLevel);

    if (mCodecType == TextConfig::TEXT_CODEC_NONE)
    {
        return RESULT_INVALID_PARAM;
    }

    mRedundantCount = 0;
    mTimeLastSent = 0;
    mSentBOM = false;
    mNodeState = kNodeStateRunning;
    return RESULT_SUCCESS;
}

void TextSourceNode::Stop()
{
    IMLOGD0("[Stop]");
    ClearDataQueue();
    mNodeState = kNodeStateStopped;
}

bool TextSourceNode::IsRunTime()
{
    return false;
}

bool TextSourceNode::IsSourceNode()
{
    return true;
}

void TextSourceNode::SetConfig(void* config)
{
    if (config == nullptr)
    {
        return;
    }

    TextConfig* pConfig = reinterpret_cast<TextConfig*>(config);
    mCodecType = pConfig->getCodecType();
    mRedundantLevel = pConfig->getRedundantLevel();
    mBitrate = pConfig->getBitrate();
    mBomEnabled = pConfig->getKeepRedundantLevel();
}

bool TextSourceNode::IsSameConfig(void* config)
{
    if (config == nullptr)
    {
        return true;
    }

    TextConfig* pConfig = reinterpret_cast<TextConfig*>(config);

    return (mCodecType == pConfig->getCodecType() &&
            mRedundantLevel == pConfig->getRedundantLevel() && mBitrate == pConfig->getBitrate() &&
            mBomEnabled == pConfig->getKeepRedundantLevel());
}

void TextSourceNode::ProcessData()
{
    // RFC 4103 recommended T.140 buffering time is 300ms
    if (mTimeLastSent != 0 &&
            ImsMediaTimer::GetTimeInMilliSeconds() - mTimeLastSent < T140_BUFFERING_TIME)
    {
        return;
    }

    if (mBomEnabled && !mSentBOM)
    {
        SendBom();
        mSentBOM = true;
    }

    std::lock_guard<std::mutex> guard(mMutex);

    ImsMediaSubType subtype = MEDIASUBTYPE_UNDEFINED;
    ImsMediaSubType datatype = MEDIASUBTYPE_UNDEFINED;
    uint8_t* data = NULL;
    uint32_t size = 0;
    uint32_t timestamp = 0;
    bool mark = false;
    uint32_t seq = 0;

    if (GetData(&subtype, &data, &size, &timestamp, &mark, &seq, &datatype))
    {
        mTimeLastSent = ImsMediaTimer::GetTimeInMilliSeconds();
        SendDataToRearNode(MEDIASUBTYPE_BITSTREAM_T140, data, size, mTimeLastSent, false, 0);
        DeleteData();

        mRedundantCount = mRedundantLevel;

        if (mRedundantCount == 0)
        {
            mRedundantCount = 1;
        }
    }
    else if (mRedundantCount > 0)
    {
        /** RFC 4103. 5.2
         * When valid T.140 data has been sent and no new T.140 data is available for transmission
         * after the selected buffering time, an empty T140block SHOULD be transmitted.  This
         * situation is regarded as the beginning of an idle period.
         */
        IMLOGD1("[ProcessData] send empty, redundant count[%d]", mRedundantCount);
        // send default if there is no data to send
        SendDataToRearNode(MEDIASUBTYPE_BITSTREAM_T140, nullptr, 0,
                ImsMediaTimer::GetTimeInMilliSeconds(), false, 0);
        mRedundantCount--;
    }
}

void TextSourceNode::SendRtt(const android::String8* text)
{
    if (text == NULL || text->length() == 0 || text->length() > MAX_RTT_LEN)
    {
        IMLOGE0("[SendRtt] invalid data");
        return;
    }

    IMLOGD2("[SendRtt] size[%u], listSize[%d]", text->length(), mDataQueue.GetCount());

    uint8_t tempBuffer[MAX_RTT_LEN] = {'\0'};
    memcpy(tempBuffer, text->string(), text->length());

    std::lock_guard<std::mutex> guard(mMutex);
    AddData(tempBuffer, text->length(), 0, false, 0);
}

void TextSourceNode::SendBom()
{
    IMLOGD0("[ProcessData] send BOM");
    uint8_t bom[3] = {0xEF, 0xBB, 0xBF};

    std::lock_guard<std::mutex> guard(mMutex);
    AddData(bom, sizeof(bom), 0, false, 0, MEDIASUBTYPE_UNDEFINED, MEDIASUBTYPE_UNDEFINED, 0, 0);
}