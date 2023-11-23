/*
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

// #define LOG_NDEBUG 0
#define LOG_TAG "audio_utils_mel_processor_tests"

#include <audio_utils/MelProcessor.h>

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <chrono>
#include <cmath>
#include <tuple>
#include <unordered_map>
#include <log/log.h>

namespace android::audio_utils {
namespace {

using ::testing::_;
using ::testing::AtMost;
using ::testing::Eq;
using ::testing::Le;
using ::testing::Gt;
using ::testing::TestWithParam;
using ::testing::Values;
using ::testing::Combine;

// Contains the sample rate and frequency for sine wave
using AudioParam = std::tuple<int32_t, int32_t>;

const std::unordered_map<int32_t, int32_t> kAWeightDelta1000 =
    {{80, 23}, {100, 19}, {500, 3}, {1000, 0}, {2000, 1}, {3000, 1},
     {8000, 1}};

// TODO(b/276849537): should replace this with proper synchornization
constexpr size_t kCallbackTimeoutInMs = 20;

class MelCallbackMock : public MelProcessor::MelCallback {
public:
    MOCK_METHOD(void, onNewMelValues, (const std::vector<float>&, size_t, size_t,
              audio_port_handle_t), (const override));
    MOCK_METHOD(void, onMomentaryExposure, (float, audio_port_handle_t), (const override));
};

void appendSineWaveBuffer(std::vector<float>& buffer,
                          float frequency,
                          size_t samples,
                          int32_t sampleRate,
                          float attenuation = 1.0f) {
    float rad = 2.0f * (float) M_PI * frequency / (float) sampleRate;
    for (size_t j = 0; j < samples; ++j) {
        buffer.push_back(sinf(j * rad) * attenuation);
    }
}

class MelProcessorFixtureTest : public TestWithParam<AudioParam> {
protected:
    MelProcessorFixtureTest()
        : mSampleRate(std::get<0>(GetParam())),
          mFrequency(std::get<1>(GetParam())),
          mMelCallback(sp<MelCallbackMock>::make()),
          mProcessor(sp<MelProcessor>::make(mSampleRate,
                                            1,
                                            AUDIO_FORMAT_PCM_FLOAT,
                                            mMelCallback,
                                            mDeviceId,
                                            mDefaultRs2,
                                            mMaxMelsCallback)) {}


    int32_t mSampleRate;
    int32_t mFrequency;
    size_t mMaxMelsCallback = 2;
    audio_port_handle_t mDeviceId = 1;
    int32_t mDefaultRs2 = 100;

    sp<MelCallbackMock> mMelCallback;
    sp<MelProcessor> mProcessor;

    std::vector<float> mBuffer;
};

TEST(MelProcessorTest, UnsupportedSamplerateCheck) {
    sp<MelCallbackMock> callback = sp<MelCallbackMock>::make();
    auto processor = sp<MelProcessor>::make(1000, 1, AUDIO_FORMAT_PCM_FLOAT, callback, 1, 100);
    std::vector<float> buffer(1000);

    EXPECT_EQ(processor->process(buffer.data(), 1000), 0);
}

TEST_P(MelProcessorFixtureTest, CheckNumberOfCallbacks) {
    if (mFrequency != 1000.0f) {
        ALOGV("NOTE: CheckNumberOfCallbacks disabled for frequency %d", mFrequency);
        return;
    }

    appendSineWaveBuffer(mBuffer, 1000.0f, mSampleRate * mMaxMelsCallback, mSampleRate);
    appendSineWaveBuffer(mBuffer, 1000.0f, mSampleRate * mMaxMelsCallback, mSampleRate, 0.01f);

    EXPECT_CALL(*mMelCallback.get(), onMomentaryExposure(Gt(mDefaultRs2), Eq(mDeviceId)))
        .Times(AtMost(2));
    EXPECT_CALL(*mMelCallback.get(), onNewMelValues(_, _, Le(size_t{2}), Eq(mDeviceId))).Times(1);

    EXPECT_GT(mProcessor->process(mBuffer.data(), mBuffer.size() * sizeof(float)), 0);
    std::this_thread::sleep_for(std::chrono::milliseconds(kCallbackTimeoutInMs));
}

TEST_P(MelProcessorFixtureTest, CheckAWeightingFrequency) {
    appendSineWaveBuffer(mBuffer, mFrequency, mSampleRate, mSampleRate);
    appendSineWaveBuffer(mBuffer, 1000.0f, mSampleRate, mSampleRate);

    EXPECT_CALL(*mMelCallback.get(), onMomentaryExposure(Gt(mDefaultRs2), Eq(mDeviceId)))
        .Times(AtMost(2));
    EXPECT_CALL(*mMelCallback.get(), onNewMelValues(_, _, _, Eq(mDeviceId)))
        .Times(1)
        .WillRepeatedly([&] (const std::vector<float>& mel, size_t offset, size_t length,
                                audio_port_handle_t deviceId) {
            EXPECT_EQ(offset, size_t{0});
            EXPECT_EQ(length, mMaxMelsCallback);
            EXPECT_EQ(deviceId, mDeviceId);
            int32_t deltaValue = abs(mel[0] - mel[1]);
            ALOGV("MEL[%d] = %.2f,  MEL[1000] = %.2f\n", mFrequency, mel[0], mel[1]);
            EXPECT_TRUE(abs(deltaValue - kAWeightDelta1000.at(mFrequency)) <= 1.f);
        });

    EXPECT_GT(mProcessor->process(mBuffer.data(), mBuffer.size() * sizeof(float)), 0);
    std::this_thread::sleep_for(std::chrono::milliseconds(kCallbackTimeoutInMs));
}

TEST_P(MelProcessorFixtureTest, AttenuationCheck) {
    auto processorAttenuation =
        sp<MelProcessor>::make(mSampleRate, 1, AUDIO_FORMAT_PCM_FLOAT, mMelCallback, mDeviceId+1,
                     mDefaultRs2, mMaxMelsCallback);
    float attenuationDB = -10.f;
    std::vector<float> bufferAttenuation;
    float melAttenuation = 0.f;
    float melNoAttenuation = 0.f;

    processorAttenuation->setAttenuation(attenuationDB);
    appendSineWaveBuffer(bufferAttenuation, mFrequency, mSampleRate * mMaxMelsCallback,
                         mSampleRate);
    appendSineWaveBuffer(mBuffer, mFrequency, mSampleRate * mMaxMelsCallback, mSampleRate);

    EXPECT_CALL(*mMelCallback.get(), onMomentaryExposure(Gt(mDefaultRs2), _))
        .Times(AtMost(2 * mMaxMelsCallback));
    EXPECT_CALL(*mMelCallback.get(), onNewMelValues(_, _, _, _))
        .Times(AtMost(2))
        .WillRepeatedly([&] (const std::vector<float>& mel, size_t offset, size_t length,
                                audio_port_handle_t deviceId) {
            EXPECT_EQ(offset, size_t{0});
            EXPECT_EQ(length, mMaxMelsCallback);

            if (deviceId == mDeviceId) {
                melNoAttenuation = mel[0];
            } else {
                melAttenuation = mel[0];
            }
        });
    EXPECT_GT(mProcessor->process(mBuffer.data(),
                                  mSampleRate * mMaxMelsCallback * sizeof(float)), 0);
    EXPECT_GT(processorAttenuation->process(bufferAttenuation.data(),
                                            mSampleRate * mMaxMelsCallback * sizeof(float)), 0);
    std::this_thread::sleep_for(std::chrono::milliseconds(kCallbackTimeoutInMs));
    // with attenuation for some frequencies the MEL callback does not exceed the RS1 threshold
    if (melAttenuation > 0.f) {
        EXPECT_EQ(fabsf(melAttenuation - melNoAttenuation), fabsf(attenuationDB));
    }
}

INSTANTIATE_TEST_SUITE_P(MelProcessorTestSuite,
    MelProcessorFixtureTest,
    Combine(Values(44100, 48000), Values(80, 100, 500, 1000, 2000, 3000, 8000))
);

}  // namespace
}  // namespace android
