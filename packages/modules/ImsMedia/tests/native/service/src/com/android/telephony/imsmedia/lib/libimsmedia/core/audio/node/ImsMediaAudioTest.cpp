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
#include <gmock/gmock.h>
#include <ImsMediaAudioSource.h>
#include <ImsMediaAudioPlayer.h>
#include <IFrameCallback.h>
#include <ImsMediaCondition.h>
#include <ImsMediaAudioUtil.h>

using namespace android::telephony::imsmedia;

using ::testing::_;
using ::testing::AnyNumber;
using ::testing::NotNull;
using ::testing::Return;

static ImsMediaCondition condition;

class MockIFrameCallback : public IFrameCallback
{
public:
    MockIFrameCallback() { mAudioPlayer = nullptr; }
    virtual ~MockIFrameCallback() {}
    MOCK_METHOD(void, onDataFrame,
            (uint8_t * buffer, uint32_t size, int64_t timestamp, uint32_t flag), (override));

    void DelegateToPlayer()
    {
        ON_CALL(*this, onDataFrame)
                .WillByDefault(
                        [this](uint8_t* buffer, uint32_t size, int64_t timestamp, uint32_t flag)
                        {
                            (void)timestamp;
                            (void)flag;

                            if (mAudioPlayer != nullptr && size > 0)
                            {
                                EXPECT_EQ(mAudioPlayer->onDataFrame(buffer, size), true);
                                condition.signal();
                            }
                        });
    }

    void SetAudioPlayer(ImsMediaAudioPlayer* player) { mAudioPlayer = player; }

private:
    ImsMediaAudioPlayer* mAudioPlayer;
};

class ImsMediaAudioTest : public ::testing::Test
{
public:
    ImsMediaAudioTest()
    {
        audioSource = nullptr;
        audioPlayer = nullptr;
        mockAudioCallback = nullptr;
    }
    virtual ~ImsMediaAudioTest() {}

    ImsMediaAudioSource* audioSource;
    ImsMediaAudioPlayer* audioPlayer;
    MockIFrameCallback* mockAudioCallback;

protected:
    virtual void SetUp() override
    {
        audioSource = new ImsMediaAudioSource();
        audioPlayer = new ImsMediaAudioPlayer();
        mockAudioCallback = new MockIFrameCallback();

        EXPECT_NE(audioSource, nullptr);
        EXPECT_NE(audioPlayer, nullptr);
        EXPECT_NE(mockAudioCallback, nullptr);

        audioSource->SetUplinkCallback(mockAudioCallback);
        mockAudioCallback->SetAudioPlayer(audioPlayer);
    }

    virtual void TearDown() override
    {
        if (audioSource != nullptr)
        {
            delete audioSource;
        }

        if (audioPlayer != nullptr)
        {
            delete audioPlayer;
        }

        if (mockAudioCallback != nullptr)
        {
            delete mockAudioCallback;
        }
    }
};

TEST_F(ImsMediaAudioTest, TestAudioStartFail)
{
    audioSource->SetCodec(kAudioCodecNone);
    EXPECT_EQ(audioSource->Start(), false);

    audioPlayer->SetCodec(kAudioCodecNone);
    EXPECT_EQ(audioPlayer->Start(), false);
}

TEST_F(ImsMediaAudioTest, TestAudioAmr)
{
    int32_t mode = ImsMediaAudioUtil::GetMaximumAmrMode(AmrParams::AMR_MODE_7);
    EXPECT_CALL(*mockAudioCallback,
            onDataFrame(NotNull(), ImsMediaAudioUtil::ConvertAmrModeToLen(mode) + 1, _, _))
            .Times(AnyNumber())
            .WillRepeatedly(Return());

    audioSource->SetCodec(kAudioCodecAmr);
    audioSource->SetCodecMode(mode);
    audioSource->SetPtime(20);
    audioSource->SetSamplingRate(8000);

    mockAudioCallback->DelegateToPlayer();

    audioPlayer->SetCodec(kAudioCodecAmr);
    audioPlayer->SetSamplingRate(8000);

    EXPECT_EQ(audioPlayer->Start(), true);
    EXPECT_EQ(audioSource->Start(), true);

    condition.wait_timeout(1000);
    audioSource->Stop();
    audioPlayer->Stop();
}

TEST_F(ImsMediaAudioTest, TestAudioAmrWb)
{
    int32_t mode = ImsMediaAudioUtil::GetMaximumAmrMode(AmrParams::AMR_MODE_8);
    EXPECT_CALL(*mockAudioCallback,
            onDataFrame(NotNull(), ImsMediaAudioUtil::ConvertAmrWbModeToLen(mode) + 1, _, _))
            .Times(AnyNumber())
            .WillRepeatedly(Return());

    audioSource->SetCodec(kAudioCodecAmrWb);
    audioSource->SetCodecMode(mode);
    audioSource->SetPtime(20);
    audioSource->SetSamplingRate(16000);

    mockAudioCallback->DelegateToPlayer();

    audioPlayer->SetCodec(kAudioCodecAmr);
    audioPlayer->SetSamplingRate(16000);

    EXPECT_EQ(audioPlayer->Start(), true);
    EXPECT_EQ(audioSource->Start(), true);

    condition.wait_timeout(1000);
    audioSource->Stop();
    audioPlayer->Stop();
}