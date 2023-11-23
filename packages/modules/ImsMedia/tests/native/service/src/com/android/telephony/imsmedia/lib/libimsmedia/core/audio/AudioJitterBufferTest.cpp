/**
 * Copyright (C) 2023 The Android Open Source Project
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

#include <gtest/gtest.h>
#include <AudioJitterBuffer.h>

#define TEST_BUFFER_SIZE    10
#define TEST_FRAME_INTERVAL 20

class AudioJitterBufferCallback : public BaseSessionCallback
{
public:
    AudioJitterBufferCallback()
    {
        numNormal = 0;
        numLost = 0;
        numDuplicated = 0;
        numDiscarded = 0;
    }
    virtual ~AudioJitterBufferCallback() {}

    virtual void onEvent(int32_t type, uint64_t param1, uint64_t /*param2*/)
    {
        if (type == kCollectRxRtpStatus)
        {
            SessionCallbackParameter* param = reinterpret_cast<SessionCallbackParameter*>(param1);

            if (param == nullptr)
            {
                return;
            }

            switch (param->param1)
            {
                case kRtpStatusDuplicated:
                    numDuplicated++;
                    break;
                case kRtpStatusDiscarded:
                    numDiscarded++;
                    break;
                case kRtpStatusNormal:
                    numNormal++;
                    break;
                default:
                    break;
            }

            delete param;
        }
        else if (type == kCollectOptionalInfo)
        {
            SessionCallbackParameter* param = reinterpret_cast<SessionCallbackParameter*>(param1);

            if (param == nullptr)
            {
                return;
            }

            if (param->type == kReportPacketLossGap)
            {
                numLost += param->param2;
            }

            delete param;
        }
    }

    int32_t getNumNormal() { return numNormal; }
    int32_t getNumLost() { return numLost; }
    int32_t getNumDuplicated() { return numDuplicated; }
    int32_t getNumDiscarded() { return numDiscarded; }

private:
    int32_t numNormal;
    int32_t numLost;
    int32_t numDuplicated;
    int32_t numDiscarded;
};

class AudioJitterBufferTest : public ::testing::Test
{
public:
    AudioJitterBufferTest() {}
    virtual ~AudioJitterBufferTest() {}

protected:
    AudioJitterBuffer* mJitterBuffer;
    AudioJitterBufferCallback mCallback;
    int32_t mStartJitterBufferSize;
    int32_t mMinJitterBufferSize;
    int32_t mMaxJitterBufferSize;

    virtual void SetUp() override
    {
        mStartJitterBufferSize = 4;
        mMinJitterBufferSize = 4;
        mMaxJitterBufferSize = 9;

        mJitterBuffer = new AudioJitterBuffer();
        mJitterBuffer->SetCodecType(kAudioCodecAmr);
        mJitterBuffer->SetSessionCallback(&mCallback);
        mJitterBuffer->SetJitterBufferSize(
                mStartJitterBufferSize, mMinJitterBufferSize, mMaxJitterBufferSize);
        mJitterBuffer->SetJitterOptions(80, 1, 2.5f, false);
    }

    virtual void TearDown() override { delete mJitterBuffer; }
};

TEST_F(AudioJitterBufferTest, TestNormalAddGet)
{
    const int32_t kNumFrames = 50;
    char buffer[TEST_BUFFER_SIZE] = {"\x1"};
    int32_t countGet = 0;
    int32_t countGetFrame = 0;
    int32_t countNotGet = 0;
    int32_t getTime = 0;

    ImsMediaSubType subtype = MEDIASUBTYPE_UNDEFINED;
    uint8_t* data = nullptr;
    uint32_t size = 0;
    uint32_t timestamp = 0;
    bool mark = false;
    uint32_t seq = 0;

    for (int32_t i = 0; i < kNumFrames; i++)
    {
        int32_t addTime = i * TEST_FRAME_INTERVAL;
        mJitterBuffer->Add(MEDIASUBTYPE_UNDEFINED, reinterpret_cast<uint8_t*>(buffer), 1,
                i * TEST_FRAME_INTERVAL, false, i, MEDIASUBTYPE_UNDEFINED, addTime);

        getTime = countGet * TEST_FRAME_INTERVAL;

        if (mJitterBuffer->Get(&subtype, &data, &size, &timestamp, &mark, &seq, getTime))
        {
            EXPECT_EQ(size, 1);
            EXPECT_EQ(timestamp, countGetFrame * TEST_FRAME_INTERVAL);
            EXPECT_EQ(seq, countGetFrame);
            mJitterBuffer->Delete();
            countGetFrame++;
        }
        else
        {
            countNotGet++;
        }

        countGet++;
    }

    while (mJitterBuffer->GetCount() > 0)
    {
        getTime = countGet * TEST_FRAME_INTERVAL;

        if (mJitterBuffer->Get(&subtype, &data, &size, &timestamp, &mark, &seq, getTime))
        {
            EXPECT_EQ(size, 1);
            EXPECT_EQ(timestamp, countGetFrame * TEST_FRAME_INTERVAL);
            EXPECT_EQ(seq, countGetFrame);
            mJitterBuffer->Delete();
            countGetFrame++;
        }
        else
        {
            countNotGet++;
        }

        countGet++;
    }

    EXPECT_EQ(countNotGet, mStartJitterBufferSize);
    EXPECT_EQ(mCallback.getNumNormal(), kNumFrames);
}

TEST_F(AudioJitterBufferTest, TestNormalAddGetSeqRounding)
{
    const int32_t kNumFrames = 20;
    char buffer[TEST_BUFFER_SIZE] = {"\x1"};
    int32_t countGet = 0;
    int32_t countGetFrame = 0;
    int32_t countNotGet = 0;
    int32_t getTime = 0;

    ImsMediaSubType subtype = MEDIASUBTYPE_UNDEFINED;
    uint8_t* data = nullptr;
    uint32_t size = 0;
    uint32_t timestamp = 0;
    bool mark = false;
    uint32_t seq = 0;
    uint16_t startSeq = 65530;
    uint16_t addSeq = 0;
    uint16_t getSeq = 0;

    for (int32_t i = 0; i < kNumFrames; i++)
    {
        addSeq = startSeq + (uint16_t)i;
        int32_t addTime = i * TEST_FRAME_INTERVAL;
        mJitterBuffer->Add(MEDIASUBTYPE_UNDEFINED, reinterpret_cast<uint8_t*>(buffer), 1,
                i * TEST_FRAME_INTERVAL, false, addSeq, MEDIASUBTYPE_UNDEFINED, addTime);

        getTime = countGet * TEST_FRAME_INTERVAL;

        if (mJitterBuffer->Get(&subtype, &data, &size, &timestamp, &mark, &seq, getTime))
        {
            getSeq = startSeq + (uint16_t)countGetFrame;
            EXPECT_EQ(size, 1);
            EXPECT_EQ(timestamp, countGetFrame * TEST_FRAME_INTERVAL);
            EXPECT_EQ(seq, getSeq);
            mJitterBuffer->Delete();
            countGetFrame++;
        }
        else
        {
            countNotGet++;
        }
        countGet++;
    }

    while (mJitterBuffer->GetCount() > 0)
    {
        getTime = countGet * TEST_FRAME_INTERVAL;

        if (mJitterBuffer->Get(&subtype, &data, &size, &timestamp, &mark, &seq, getTime))
        {
            getSeq = startSeq + (uint16_t)countGetFrame;
            EXPECT_EQ(size, 1);
            EXPECT_EQ(timestamp, countGetFrame * TEST_FRAME_INTERVAL);
            EXPECT_EQ(seq, getSeq);
            mJitterBuffer->Delete();
            countGetFrame++;
        }
        else
        {
            countNotGet++;
        }

        countGet++;
    }

    EXPECT_EQ(countNotGet, mStartJitterBufferSize);
    EXPECT_EQ(mCallback.getNumNormal(), kNumFrames);
}

TEST_F(AudioJitterBufferTest, TestNormalAddGetTimestampRounding)
{
    const int32_t kNumFrames = 50;
    char buffer[TEST_BUFFER_SIZE] = {"\x1"};
    int32_t countGet = 0;
    int32_t countGetFrame = 0;
    int32_t countNotGet = 0;
    int32_t getTime = 0;

    ImsMediaSubType subtype = MEDIASUBTYPE_UNDEFINED;
    uint8_t* data = nullptr;
    uint32_t size = 0;
    uint32_t timestamp = 0;
    bool mark = false;
    uint32_t seq = 0;
    uint32_t startTimestamp = 4294967295 - 200;
    uint32_t addTimestamp = 0;
    uint32_t getTimestamp = 0;

    for (int32_t i = 0; i < kNumFrames; i++)
    {
        addTimestamp = startTimestamp + i * TEST_FRAME_INTERVAL;
        int32_t addTime = i * TEST_FRAME_INTERVAL;
        mJitterBuffer->Add(MEDIASUBTYPE_UNDEFINED, reinterpret_cast<uint8_t*>(buffer), 1,
                addTimestamp, false, i, MEDIASUBTYPE_UNDEFINED, addTime);

        getTime = countGet * TEST_FRAME_INTERVAL;

        if (mJitterBuffer->Get(&subtype, &data, &size, &timestamp, &mark, &seq, getTime))
        {
            getTimestamp = startTimestamp + countGetFrame * TEST_FRAME_INTERVAL;
            EXPECT_EQ(size, 1);
            EXPECT_EQ(timestamp, getTimestamp);
            EXPECT_EQ(seq, countGetFrame);
            mJitterBuffer->Delete();
            countGetFrame++;
        }
        else
        {
            countNotGet++;
        }

        countGet++;
    }

    while (mJitterBuffer->GetCount() > 0)
    {
        getTime = countGet * TEST_FRAME_INTERVAL;

        if (mJitterBuffer->Get(&subtype, &data, &size, &timestamp, &mark, &seq, getTime))
        {
            getTimestamp = startTimestamp + countGetFrame * TEST_FRAME_INTERVAL;
            EXPECT_EQ(size, 1);
            EXPECT_EQ(timestamp, getTimestamp);
            EXPECT_EQ(seq, countGetFrame);
            mJitterBuffer->Delete();
            countGetFrame++;
        }
        else
        {
            countNotGet++;
        }

        countGet++;
    }

    EXPECT_EQ(countNotGet, mStartJitterBufferSize);
    EXPECT_EQ(mCallback.getNumNormal(), kNumFrames);
}

TEST_F(AudioJitterBufferTest, TestAddGetDuplicatedSeqDetection)
{
    const int32_t kNumFrames = 20;
    char buffer[TEST_BUFFER_SIZE] = {"\x1"};
    int32_t countGet = 0;
    int32_t countGetFrame = 0;
    int32_t countNotGet = 0;
    int32_t getTime = 0;

    ImsMediaSubType subtype = MEDIASUBTYPE_UNDEFINED;
    uint8_t* data = nullptr;
    uint32_t size = 0;
    uint32_t timestamp = 0;
    bool mark = false;
    uint32_t seq = 0;
    uint16_t startSeq = 0;
    uint16_t addSeq = 0;
    uint16_t getSeq = 0;

    for (int32_t i = 0; i < kNumFrames; i++)
    {
        addSeq = startSeq + (uint16_t)i;
        int32_t addTime = i * TEST_FRAME_INTERVAL;
        mJitterBuffer->Add(MEDIASUBTYPE_UNDEFINED, reinterpret_cast<uint8_t*>(buffer), 1,
                i * TEST_FRAME_INTERVAL, false, addSeq, MEDIASUBTYPE_UNDEFINED, addTime);

        if (i == 5)
        {
            mJitterBuffer->Add(MEDIASUBTYPE_UNDEFINED, reinterpret_cast<uint8_t*>(buffer), 1,
                    i * TEST_FRAME_INTERVAL, false, addSeq, MEDIASUBTYPE_UNDEFINED, addTime);
        }

        getTime = countGet * TEST_FRAME_INTERVAL;

        if (mJitterBuffer->Get(&subtype, &data, &size, &timestamp, &mark, &seq, getTime))
        {
            getSeq = startSeq + (uint16_t)countGetFrame;
            EXPECT_EQ(size, 1);
            EXPECT_EQ(timestamp, countGetFrame * TEST_FRAME_INTERVAL);
            EXPECT_EQ(seq, getSeq);
            mJitterBuffer->Delete();
            countGetFrame++;
        }
        else
        {
            countNotGet++;
        }

        countGet++;
    }

    while (mJitterBuffer->GetCount() > 0)
    {
        getTime = countGet * TEST_FRAME_INTERVAL;

        if (mJitterBuffer->Get(&subtype, &data, &size, &timestamp, &mark, &seq, getTime))
        {
            getSeq = startSeq + (uint16_t)countGetFrame;
            EXPECT_EQ(size, 1);
            EXPECT_EQ(timestamp, countGetFrame * TEST_FRAME_INTERVAL);
            EXPECT_EQ(seq, getSeq);
            mJitterBuffer->Delete();
            countGetFrame++;
        }
        else
        {
            countNotGet++;
        }

        countGet++;
    }

    EXPECT_EQ(mCallback.getNumLost(), 0);
    EXPECT_EQ(mCallback.getNumDuplicated(), 1);
    EXPECT_EQ(mCallback.getNumDiscarded(), 0);
    EXPECT_EQ(countNotGet, mStartJitterBufferSize);
    EXPECT_EQ(mCallback.getNumNormal(), kNumFrames);
}

TEST_F(AudioJitterBufferTest, TestAddGetInBurstIncoming)
{
    const int32_t kNumFrames = 20;
    char buffer[TEST_BUFFER_SIZE] = {"\x1"};
    int32_t countGet = 0;
    int32_t countGetFrame = 0;
    int32_t countNotGet = 0;
    int32_t getTime = 0;

    ImsMediaSubType subtype = MEDIASUBTYPE_UNDEFINED;
    uint8_t* data = nullptr;
    uint32_t size = 0;
    uint32_t timestamp = 0;
    bool mark = false;
    uint32_t seq = 0;
    uint16_t startSeq = 0;
    uint16_t addSeq = startSeq;
    uint16_t getSeq = startSeq;
    uint32_t addTimestamp = 0;

    int32_t addTime = 0;
    int iter = 0;

    while (addSeq < kNumFrames)
    {
        if (iter > 5 && iter < 10)  // not added for 4 frame interval
        {
            addTime += TEST_FRAME_INTERVAL;
        }
        else if (iter >= 10 && iter < 15)  // 5 frames burst added
        {
            mJitterBuffer->Add(MEDIASUBTYPE_UNDEFINED, reinterpret_cast<uint8_t*>(buffer), 1,
                    addTimestamp, false, addSeq++, MEDIASUBTYPE_UNDEFINED, addTime);
            addTime += 1;  // 1ms burst
            addTimestamp += TEST_FRAME_INTERVAL;
        }
        else  // normal
        {
            mJitterBuffer->Add(MEDIASUBTYPE_UNDEFINED, reinterpret_cast<uint8_t*>(buffer), 1,
                    addTimestamp, false, addSeq++, MEDIASUBTYPE_UNDEFINED, addTime);
            addTime += TEST_FRAME_INTERVAL;
            addTimestamp += TEST_FRAME_INTERVAL;
        }

        iter++;
        getTime = countGet * TEST_FRAME_INTERVAL;

        if (mJitterBuffer->Get(&subtype, &data, &size, &timestamp, &mark, &seq, getTime))
        {
            EXPECT_EQ(size, 1);
            EXPECT_EQ(timestamp, countGetFrame * TEST_FRAME_INTERVAL);
            EXPECT_EQ(seq, getSeq++);
            mJitterBuffer->Delete();
            countGetFrame++;
        }
        else
        {
            countNotGet++;
        }

        countGet++;
    }

    while (mJitterBuffer->GetCount() > 0)
    {
        getTime = countGet * TEST_FRAME_INTERVAL;

        if (mJitterBuffer->Get(&subtype, &data, &size, &timestamp, &mark, &seq, getTime))
        {
            getSeq = startSeq + (uint16_t)countGetFrame;
            EXPECT_EQ(size, 1);
            EXPECT_EQ(timestamp, countGetFrame * TEST_FRAME_INTERVAL);
            EXPECT_EQ(seq, getSeq);
            mJitterBuffer->Delete();
            countGetFrame++;
        }
        else
        {
            countNotGet++;
        }

        countGet++;
    }

    EXPECT_EQ(mCallback.getNumLost(), 0);
    EXPECT_EQ(mCallback.getNumDuplicated(), 0);
    EXPECT_EQ(mCallback.getNumDiscarded(), 0);
    EXPECT_EQ(countNotGet, mStartJitterBufferSize);
    EXPECT_EQ(mCallback.getNumNormal(), kNumFrames);
}