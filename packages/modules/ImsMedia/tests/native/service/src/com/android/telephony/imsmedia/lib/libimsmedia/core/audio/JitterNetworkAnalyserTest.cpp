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

#include <gtest/gtest.h>
#include <JitterNetworkAnalyser.h>

#define TEST_FRAME_INTERVAL 20

class JitterNetworkAnalyserTest : public ::testing::Test
{
public:
    JitterNetworkAnalyserTest()
    {
        mAnalyzer = nullptr;
        mMinJitterBufferSize = 4;
        mMaxJitterBufferSize = 9;
        mReduceThreshold = 80;
        mStepSize = 1;
    }
    virtual ~JitterNetworkAnalyserTest() {}

protected:
    JitterNetworkAnalyser* mAnalyzer;
    uint32_t mMinJitterBufferSize;
    uint32_t mMaxJitterBufferSize;
    uint32_t mReduceThreshold;
    uint32_t mStepSize;

    virtual void SetUp() override
    {
        mAnalyzer = new JitterNetworkAnalyser();
        mAnalyzer->SetMinMaxJitterBufferSize(mMinJitterBufferSize, mMaxJitterBufferSize);
        mAnalyzer->SetJitterOptions(mReduceThreshold, mStepSize, 2.5f);
    }

    virtual void TearDown() override { delete mAnalyzer; }
};

TEST_F(JitterNetworkAnalyserTest, TestLowJitter)
{
    const int32_t kNumFrames = 50;
    const int32_t kJitter = 10;
    int32_t arrivalTime = 0;
    int32_t timestamp = 0;
    uint32_t currentJitterBufferSize = mMinJitterBufferSize;

    for (int32_t i = 0; i < kNumFrames; i++)
    {
        timestamp += TEST_FRAME_INTERVAL;
        arrivalTime += (TEST_FRAME_INTERVAL + kJitter);

        if (i == 0)
        {
            EXPECT_EQ(mAnalyzer->CalculateTransitTimeDifference(timestamp, arrivalTime), 0);
        }
        else
        {
            EXPECT_EQ(mAnalyzer->CalculateTransitTimeDifference(timestamp, arrivalTime), kJitter);
        }

        mAnalyzer->UpdateBaseTimestamp(timestamp, arrivalTime);

        currentJitterBufferSize =
                mAnalyzer->GetNextJitterBufferSize(currentJitterBufferSize, timestamp);

        EXPECT_EQ(mMinJitterBufferSize, currentJitterBufferSize);
    }
}

TEST_F(JitterNetworkAnalyserTest, TestHighJitter)
{
    const int32_t kNumFrames = 50;
    const int32_t kJitter = 200;  // high jitter
    const uint32_t kInterval = 1000;
    int32_t arrivalTime = 0;
    int32_t timestamp = 0;
    uint32_t currentJitterBufferSize = mMinJitterBufferSize;

    uint32_t statusInterval = 0;

    for (int32_t i = 0; i < kNumFrames; i++)
    {
        timestamp += TEST_FRAME_INTERVAL;
        arrivalTime += (TEST_FRAME_INTERVAL + kJitter);

        uint32_t nextJitterSizeTruth = currentJitterBufferSize;

        if (i == 0)
        {
            EXPECT_EQ(mAnalyzer->CalculateTransitTimeDifference(timestamp, arrivalTime), 0);
        }
        else
        {
            EXPECT_EQ(mAnalyzer->CalculateTransitTimeDifference(timestamp, arrivalTime), kJitter);
            nextJitterSizeTruth = currentJitterBufferSize + mStepSize;
        }

        mAnalyzer->UpdateBaseTimestamp(timestamp, arrivalTime);

        if (nextJitterSizeTruth >= mMaxJitterBufferSize)
        {
            nextJitterSizeTruth = mMaxJitterBufferSize;
        }

        currentJitterBufferSize =
                mAnalyzer->GetNextJitterBufferSize(currentJitterBufferSize, statusInterval);
        statusInterval += kInterval;

        EXPECT_EQ(currentJitterBufferSize, nextJitterSizeTruth);
    }
}

TEST_F(JitterNetworkAnalyserTest, TestJitterBufferDecrease)
{
    const int32_t kNumFrames = 50;
    const int32_t kJitter = 10;  // low jitter
    const uint32_t kInterval = 100;
    int32_t arrivalTime = 0;
    int32_t timestamp = 0;
    uint32_t currentJitterBufferSize = mMaxJitterBufferSize;

    uint32_t statusInterval = 0;

    for (int32_t i = 0; i < kNumFrames; i++)
    {
        timestamp += TEST_FRAME_INTERVAL;
        arrivalTime += (TEST_FRAME_INTERVAL + kJitter);

        uint32_t nextJitterSizeTruth = currentJitterBufferSize;

        if (i == 0)
        {
            EXPECT_EQ(mAnalyzer->CalculateTransitTimeDifference(timestamp, arrivalTime), 0);
        }
        else
        {
            EXPECT_EQ(mAnalyzer->CalculateTransitTimeDifference(timestamp, arrivalTime), kJitter);
        }

        if (i != 0 && i % 2 == 1)  // decrease is done every the other step
        {
            nextJitterSizeTruth = currentJitterBufferSize - mStepSize;
        }

        mAnalyzer->UpdateBaseTimestamp(timestamp, arrivalTime);

        if (nextJitterSizeTruth <= mMinJitterBufferSize)
        {
            nextJitterSizeTruth = mMinJitterBufferSize;
        }

        currentJitterBufferSize =
                mAnalyzer->GetNextJitterBufferSize(currentJitterBufferSize, statusInterval);
        statusInterval += kInterval;

        EXPECT_EQ(currentJitterBufferSize, nextJitterSizeTruth);
    }
}
