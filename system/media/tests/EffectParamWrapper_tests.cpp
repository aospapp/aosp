/*
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
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <locale>

#define LOG_TAG "EffectParamWrapper_Test"
#include <log/log.h>
#include <system/audio_effects/audio_effects_utils.h>

using namespace android;
using android::effect::utils::EffectParamReader;
using android::effect::utils::EffectParamWrapper;
using android::effect::utils::EffectParamWriter;

TEST(EffectParamWrapperTest, setAndGetMatches) {
    effect_param_t param = {.psize = 2, .vsize = 0x10};
    const auto wrapper = EffectParamWrapper(param);
    effect_param_t target = wrapper.getEffectParam();
    const auto targetWrapper = EffectParamWrapper(target);
    EXPECT_TRUE(0 == std::memcmp(&param, &target, sizeof(effect_param_t)));
    EXPECT_EQ(targetWrapper, wrapper);
}

TEST(EffectParamWrapperTest, validateCmdSize) {
    effect_param_t param = {.psize = 1, .vsize = 4};
    const auto wrapper = EffectParamWrapper(param);
    size_t minCmdSize = sizeof(effect_param_t) +
                        wrapper.getPaddedParameterSize() +
                        wrapper.getValueSize();
    EXPECT_FALSE(wrapper.validateCmdSize(minCmdSize - 1));
    EXPECT_TRUE(wrapper.validateCmdSize(minCmdSize));
    EXPECT_TRUE(wrapper.validateCmdSize(minCmdSize + 1));
}

TEST(EffectParamWrapperTest, validateCmdSizeOverflow) {
    effect_param_t param = {.psize = std::numeric_limits<uint32_t>::max(),
                            .vsize = std::numeric_limits<uint32_t>::max()};
    const auto wrapper = EffectParamWrapper(param);
    uint64_t minCmdSize = (uint64_t)sizeof(effect_param_t) +
                          wrapper.getPaddedParameterSize() +
                          wrapper.getValueSize();
    EXPECT_FALSE(wrapper.validateCmdSize(minCmdSize - 1));
    EXPECT_TRUE(wrapper.validateCmdSize(minCmdSize));
    EXPECT_TRUE(wrapper.validateCmdSize(minCmdSize + 1));
}

TEST(EffectParamWrapperTest, validateParamValueSize) {
    effect_param_t param = {.psize = 1, .vsize = 4};
    const auto wrapper = EffectParamWrapper(param);
    EXPECT_TRUE(wrapper.validateParamValueSize(param.psize, param.vsize));
    EXPECT_TRUE(wrapper.validateParamValueSize(0, param.vsize));
    EXPECT_TRUE(wrapper.validateParamValueSize(param.psize, 0));
    EXPECT_FALSE(wrapper.validateParamValueSize(param.psize + 1, 0));
    EXPECT_FALSE(wrapper.validateParamValueSize(0, param.vsize + 1));
}

TEST(EffectParamWrapperTest, padding) {
    for (size_t i = 0; i < 0x100; i++) {
      EXPECT_EQ(
          sizeof(uint32_t) * ((i + sizeof(uint32_t) - 1) / sizeof(uint32_t)),
          EffectParamWrapper::padding(i))
          << i;
    }
}

TEST(EffectParamWrapperTest, getPaddedParameterSize) {
    effect_param_t psize1 = {.psize = 1};
    const auto wrapper1 = EffectParamWrapper(psize1);
    EXPECT_EQ(4, wrapper1.getPaddedParameterSize());
    EXPECT_EQ(4, wrapper1.padding(psize1.psize));

    effect_param_t psize4 = {.psize = 4};
    const auto wrapper4 = EffectParamWrapper(psize4);
    EXPECT_EQ(4, wrapper4.getPaddedParameterSize());
    EXPECT_EQ(wrapper4.getPaddedParameterSize(), wrapper4.padding(psize4.psize));

    effect_param_t psize6 = {.psize = 6};
    const auto wrapper6 = EffectParamWrapper(psize6);
    EXPECT_EQ(8, wrapper6.getPaddedParameterSize());
    EXPECT_EQ(wrapper6.getPaddedParameterSize(), wrapper6.padding(psize6.psize));
}

TEST(EffectParamWrapperTest, getPVSize) {
    effect_param_t vsize1 = {.vsize = 1, .psize = 0xff};
    const auto wrapper1 = EffectParamWrapper(vsize1);
    EXPECT_EQ(vsize1.vsize, wrapper1.getValueSize());

    effect_param_t vsize2 = {.vsize = 0xff, .psize = 0xbe};
    const auto wrapper2 = EffectParamWrapper(vsize2);
    EXPECT_EQ(vsize2.vsize, wrapper2.getValueSize());

    EXPECT_EQ(vsize1.psize, wrapper1.getParameterSize());
    EXPECT_EQ(vsize1.vsize, wrapper1.getValueSize());
    EXPECT_EQ(sizeof(effect_param_t) + EffectParamWrapper::padding(vsize1.psize) + vsize1.vsize,
              wrapper1.getTotalSize());

    EXPECT_EQ(vsize2.psize, wrapper2.getParameterSize());
    EXPECT_EQ(vsize2.vsize, wrapper2.getValueSize());
    EXPECT_EQ(sizeof(effect_param_t) + EffectParamWrapper::padding(vsize2.psize) + vsize2.vsize,
              wrapper2.getTotalSize());
}

TEST(EffectParamWrapperTest, toString) {
    effect_param_t param = {.status = -1, .psize = 2, .vsize = 4};
    const auto wrapper = EffectParamWrapper(param);
    EXPECT_TRUE(wrapper.toString().find("effect_param_t: ") != std::string::npos);
    EXPECT_TRUE(wrapper.toString().find("status: -1") != std::string::npos);
    EXPECT_TRUE(wrapper.toString().find("p: 2") != std::string::npos);
    EXPECT_TRUE(wrapper.toString().find("v: 4") != std::string::npos);
}

TEST(EffectParamWriterTest, writeReadFromData) {
    constexpr uint16_t testData[8] = {0x200,  0x0,    0xffffu, 0xbead,
                                      0xfefe, 0x5555, 0xeeee,  0x2};
    uint16_t targetData[8];
    char buf[sizeof(effect_param_t) + 8 * sizeof(uint16_t)];
    effect_param_t *param = (effect_param_t *)(&buf);
    param->psize = 0;
    param->vsize = 8 * sizeof(uint16_t);
    auto wrapper = EffectParamWriter(*param);

    // write testData into effect_param_t data buffer
    ASSERT_EQ(OK, wrapper.writeToData(&testData, 8 * sizeof(uint16_t) /* len */,
                                0 /* offset */, 8 * sizeof(uint16_t) /* max */))
        << wrapper.toString();

    // read first half and compare
    std::memset(&targetData, 0, 8 * sizeof(uint16_t));
    EXPECT_EQ(OK, wrapper.readFromData(&targetData, 4 * sizeof(uint16_t) /* len */, 0 /* offset */,
                                       4 * sizeof(uint16_t) /* max */))
        << wrapper.toString();
    EXPECT_EQ(0, std::memcmp(&testData, &targetData, 4 * sizeof(uint16_t)));

    // read second half and compare
    std::memset(&targetData, 0, 8 * sizeof(uint16_t));
    EXPECT_EQ(OK, wrapper.readFromData(&targetData, 4 * sizeof(uint16_t) /* len */,
                                       4 * sizeof(uint16_t) /* offset */,
                                       8 * sizeof(uint16_t) /* max */))
        << wrapper.toString();
    EXPECT_EQ(0, std::memcmp(testData + 4, &targetData, 4 * sizeof(uint16_t)));

    // read all and compare
    std::memset(&targetData, 0, 8 * sizeof(uint16_t));
    EXPECT_EQ(OK, wrapper.readFromData(&targetData, 8 * sizeof(uint16_t), 0 /* offset */,
                                       8 * sizeof(uint16_t) /* max */))
        << wrapper.toString();
    EXPECT_EQ(0, std::memcmp(&testData, &targetData, 8 * sizeof(uint16_t)));
}

TEST(EffectParamWriterReaderTest, writeAndReadParameterOneByOne) {
    constexpr uint16_t data[11] = {
        0x0f0f, 0x2020, 0xffff, 0xbead, 0x5e5e, 0x0 /* padding */,
        0xe5e5, 0xeeee, 0x1111, 0x8888, 0xabab};
    char buf[sizeof(effect_param_t) + 11 * sizeof(uint16_t)] = {};
    effect_param_t *param = (effect_param_t *)(&buf);
    param->psize = 5 * sizeof(uint16_t);
    param->vsize = 5 * sizeof(uint16_t);
    auto writer = EffectParamWriter(*param);
    auto reader = EffectParamReader(*param);

    // write testData into effect_param_t data buffer
    EXPECT_EQ(OK, writer.writeToParameter(&data[0]));
    EXPECT_EQ(OK, writer.writeToParameter(&data[1]));
    EXPECT_EQ(OK, writer.writeToParameter(&data[2]));
    EXPECT_EQ(OK, writer.writeToParameter(&data[3]));
    EXPECT_EQ(OK, writer.writeToParameter(&data[4]));
    EXPECT_NE(OK, writer.writeToParameter(&data[5])); // expect write error
    EXPECT_EQ(OK, writer.writeToValue(&data[6]));
    EXPECT_EQ(OK, writer.writeToValue(&data[7]));
    EXPECT_EQ(OK, writer.writeToValue(&data[8]));
    EXPECT_EQ(OK, writer.writeToValue(&data[9]));
    EXPECT_EQ(OK, writer.writeToValue(&data[10]));
    EXPECT_NE(OK, writer.writeToValue(&data[10])); // expect write error

    // read and compare
    uint16_t getData[12] = {};
    EXPECT_EQ(OK, reader.readFromParameter(&getData[0]));
    EXPECT_EQ(OK, reader.readFromParameter(&getData[1]));
    EXPECT_EQ(OK, reader.readFromParameter(&getData[2]));
    EXPECT_EQ(OK, reader.readFromParameter(&getData[3]));
    EXPECT_EQ(OK, reader.readFromParameter(&getData[4]));
    EXPECT_NE(OK, reader.readFromParameter(&getData[5])); // expect read error

    EXPECT_EQ(OK, reader.readFromValue(&getData[6]));
    EXPECT_EQ(OK, reader.readFromValue(&getData[7]));
    EXPECT_EQ(OK, reader.readFromValue(&getData[8]));
    EXPECT_EQ(OK, reader.readFromValue(&getData[9]));
    EXPECT_EQ(OK, reader.readFromValue(&getData[10]));
    EXPECT_NE(OK, reader.readFromValue(&getData[11])); // expect read error

    EXPECT_EQ(0, std::memcmp(&buf[sizeof(effect_param_t)], &data, 11 * sizeof(uint16_t)));
    EXPECT_EQ(0, std::memcmp(&getData, &data, 11 * sizeof(uint16_t)));
}

TEST(EffectParamWriterReaderTest, writeAndReadParameterN) {
    constexpr uint16_t data[11] = {
        0x0f0f, 0x2020, 0xffff, 0x1111, 0xabab, 0x0 /* padding */,
        0xe5e5, 0xeeee, 0xbead, 0x8888, 0x5e5e};
    char buf[sizeof(effect_param_t) + 11 * sizeof(uint16_t)] = {};
    effect_param_t *param = (effect_param_t *)(&buf);
    param->psize = 5 * sizeof(uint16_t);
    param->vsize = 5 * sizeof(uint16_t);
    auto writer = EffectParamWriter(*param);
    auto reader = EffectParamReader(*param);

    // write testData into effect_param_t data buffer
    EXPECT_EQ(OK, writer.writeToParameter(&data[0]));
    EXPECT_EQ(OK, writer.writeToParameter(&data[1], 2));
    EXPECT_EQ(OK, writer.writeToParameter(&data[3], 2));
    EXPECT_NE(OK, writer.writeToParameter(&data[5])); // expect write error
    EXPECT_EQ(OK, writer.writeToValue(&data[6], 3));
    EXPECT_EQ(OK, writer.writeToValue(&data[9], 2));
    EXPECT_NE(OK, writer.writeToValue(&data[10])); // expect write error

    // read and compare
    uint16_t getData[12] = {};
    EXPECT_EQ(OK, reader.readFromParameter(&getData[0], 2));
    EXPECT_EQ(OK, reader.readFromParameter(&getData[2]));
    EXPECT_EQ(OK, reader.readFromParameter(&getData[3], 2));
    EXPECT_NE(OK, reader.readFromParameter(&getData[5])); // expect read error

    EXPECT_EQ(OK, reader.readFromValue(&getData[6]));
    EXPECT_EQ(OK, reader.readFromValue(&getData[7], 2));
    EXPECT_EQ(OK, reader.readFromValue(&getData[9], 2));
    EXPECT_NE(OK, reader.readFromValue(&getData[11])); // expect read error

    EXPECT_EQ(0, std::memcmp(&buf[sizeof(effect_param_t)], &data, 11 * sizeof(uint16_t)));
    EXPECT_EQ(0, std::memcmp(&getData, &data, 11 * sizeof(uint16_t)));
}

TEST(EffectParamWriterReaderTest, writeAndReadParameterBlock) {
    constexpr uint16_t data[11] = {
        0xe5e5, 0xeeee, 0x1111, 0x8888, 0xabab, 0x0, /* padding */
        0x0f0f, 0x2020, 0xffff, 0xbead, 0x5e5e,
    };
    char buf[sizeof(effect_param_t) + 11 * sizeof(uint16_t)] = {};
    effect_param_t *param = (effect_param_t *)(&buf);
    param->psize = 5 * sizeof(uint16_t);
    param->vsize = 5 * sizeof(uint16_t);
    auto writer = EffectParamWriter(*param);
    auto reader = EffectParamReader(*param);

    // write testData into effect_param_t data buffer
    EXPECT_EQ(OK, writer.writeToParameter(&data[0], 5));
    EXPECT_NE(OK, writer.writeToParameter(&data[5])); // expect write error
    EXPECT_EQ(OK, writer.writeToValue(&data[6], 5));
    EXPECT_NE(OK, writer.writeToValue(&data[10])); // expect write error
    writer.finishValueWrite();
    EXPECT_EQ(5 * sizeof(uint16_t), writer.getValueSize());
    EXPECT_EQ(sizeof(effect_param_t) +
                  6 * sizeof(uint16_t) /* padded parameter */ +
                  5 * sizeof(uint16_t),
              writer.getTotalSize())
        << writer.toString();

    // read and compare
    uint16_t getData[12] = {};
    EXPECT_EQ(OK, reader.readFromParameter(&getData[0], 5));
    EXPECT_NE(OK, reader.readFromParameter(&getData[5])); // expect read error

    EXPECT_EQ(OK, reader.readFromValue(&getData[6], 5));
    EXPECT_NE(OK, reader.readFromValue(&getData[11])); // expect read error

    EXPECT_EQ(0, std::memcmp(&buf[sizeof(effect_param_t)], &data, 11 * sizeof(uint16_t)));
    EXPECT_EQ(0, std::memcmp(&getData, &data, 11 * sizeof(uint16_t)));
}

TEST(EffectParamWriterTest, setStatus) {
    effect_param_t param = {.status = -1, .psize = 2, .vsize = 4};
    auto wrapper = EffectParamWriter(param);
    EXPECT_EQ(-1, wrapper.getStatus()) << wrapper.toString();
    wrapper.setStatus(0);
    EXPECT_EQ(0, wrapper.getStatus()) << wrapper.toString();
    EXPECT_EQ(wrapper.getStatus(), param.status);
    wrapper.setStatus(0x10);
    EXPECT_EQ(0x10, wrapper.getStatus()) << wrapper.toString();
    EXPECT_EQ(wrapper.getStatus(), param.status) << wrapper.toString();
}

TEST(EffectParamWriterReaderTest, writeAndReadParameterDiffSize) {
    constexpr uint16_t data[11] = {
        0xbead, 0x5e5e, 0x0f0f, 0x2020, 0xffff, 0x0 /* padding */,
        0xe5e5, 0xeeee, 0x1111, 0x8888, 0xabab};
    char buf[sizeof(effect_param_t) + 11 * sizeof(uint16_t)] = {};
    effect_param_t *param = (effect_param_t *)(&buf);
    param->psize = 5 * sizeof(uint16_t);
    param->vsize = 5 * sizeof(uint16_t);
    auto writer = EffectParamWriter(*param);
    auto reader = EffectParamReader(*param);

    // write testData into effect_param_t data buffer
    EXPECT_EQ(OK, writer.writeToParameter(&data[0]));
    EXPECT_EQ(OK, writer.writeToParameter((uint32_t *)&data[1]));
    EXPECT_EQ(OK, writer.writeToParameter((uint32_t *)&data[3]));
    EXPECT_NE(OK, writer.writeToParameter(&data[5])); // expect write error
    EXPECT_EQ(OK, writer.writeToValue((uint32_t *)&data[6], 2));
    EXPECT_EQ(OK, writer.writeToValue(&data[10]));
    writer.finishValueWrite();
    EXPECT_EQ(5 * sizeof(uint16_t), writer.getValueSize());
    EXPECT_EQ(sizeof(effect_param_t) + 11 * sizeof(uint16_t),
              writer.getTotalSize()) << writer.toString();
    EXPECT_NE(OK, writer.writeToValue(&data[10])); // expect write error
    writer.finishValueWrite();
    EXPECT_EQ(5 * sizeof(uint16_t), writer.getValueSize());
    EXPECT_EQ(sizeof(effect_param_t) + 11 * sizeof(uint16_t),
              writer.getTotalSize()) << writer.toString();

    // read and compare
    uint16_t getData[12] = {};
    EXPECT_EQ(OK, reader.readFromParameter((uint32_t *)&getData[0], 2));
    EXPECT_EQ(OK, reader.readFromParameter(&getData[4]));
    EXPECT_NE(OK, reader.readFromParameter(&getData[5])); // expect read error

    EXPECT_EQ(OK, reader.readFromValue(&getData[6]));
    EXPECT_EQ(OK, reader.readFromValue((uint32_t *)&getData[7]));
    EXPECT_EQ(OK, reader.readFromValue((uint32_t *)&getData[9]));
    EXPECT_NE(OK, reader.readFromValue(&getData[11])); // expect read error

    EXPECT_EQ(0, std::memcmp(&buf[sizeof(effect_param_t)], &data, 11 * sizeof(uint16_t)));
    EXPECT_EQ(0, std::memcmp(&getData, &data, 11 * sizeof(uint16_t)))
        << "\n"
        << std::hex << getData[0] << " " << getData[1] << " " << getData[2] << " "
        << getData[3] << " " << getData[4] << " " << getData[5] << " " << getData[6] << " "
        << getData[7] << " " << getData[8] << " " << getData[9] << " " << getData[10];
}
