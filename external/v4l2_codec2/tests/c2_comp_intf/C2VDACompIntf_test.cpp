// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

//#define LOG_NDEBUG 0
#define LOG_TAG "C2VDACompIntf_test"

#include <C2CompIntfTest.h>

#include <C2VDAAllocatorStore.h>
#include <C2VDAComponent.h>

#include <C2PlatformSupport.h>

#include <gtest/gtest.h>
#include <utils/Log.h>

#include <inttypes.h>
#include <stdio.h>
#include <limits>

namespace android {

const C2String testCompName = "c2.vda.avc.decoder";
const c2_node_id_t testCompNodeId = 12345;

const char* MEDIA_MIMETYPE_VIDEO_RAW = "video/raw";
const char* MEDIA_MIMETYPE_VIDEO_AVC = "video/avc";

const size_t kMaxInputBufferSize = 1048576;
const C2Allocator::id_t kInputAllocators[] = {C2PlatformAllocatorStore::BLOB};
const C2Allocator::id_t kOutputAllocators[] = {C2VDAAllocatorStore::V4L2_BUFFERPOOL};
const C2Allocator::id_t kSurfaceAllocator = C2VDAAllocatorStore::V4L2_BUFFERQUEUE;
const C2BlockPool::local_id_t kDefaultOutputBlockPool = C2BlockPool::BASIC_GRAPHIC;

class C2VDACompIntfTest : public C2CompIntfTest {
protected:
    C2VDACompIntfTest() {
        mReflector = std::make_shared<C2ReflectorHelper>();
        mIntf = std::shared_ptr<C2ComponentInterface>(new SimpleInterface<C2VDAComponent::IntfImpl>(
                testCompName.c_str(), testCompNodeId,
                std::make_shared<C2VDAComponent::IntfImpl>(testCompName, mReflector)));
    }
    ~C2VDACompIntfTest() override {}
};

#define TRACED_FAILURE(func)                            \
    do {                                                \
        SCOPED_TRACE(#func);                            \
        func;                                           \
        if (::testing::Test::HasFatalFailure()) return; \
    } while (false)

TEST_F(C2VDACompIntfTest, CreateInstance) {
    auto name = mIntf->getName();
    auto id = mIntf->getId();
    printf("name = %s\n", name.c_str());
    printf("node_id = %u\n", id);
    EXPECT_STREQ(name.c_str(), testCompName.c_str());
    EXPECT_EQ(id, testCompNodeId);
}

TEST_F(C2VDACompIntfTest, TestInputFormat) {
    C2StreamBufferTypeSetting::input expected(0u, C2BufferData::LINEAR);
    C2StreamBufferTypeSetting::input invalid(0u, C2BufferData::GRAPHIC);
    TRACED_FAILURE(testReadOnlyParam(&expected, &invalid));
}

TEST_F(C2VDACompIntfTest, TestOutputFormat) {
    C2StreamBufferTypeSetting::output expected(0u, C2BufferData::GRAPHIC);
    C2StreamBufferTypeSetting::output invalid(0u, C2BufferData::LINEAR);
    TRACED_FAILURE(testReadOnlyParam(&expected, &invalid));
}

TEST_F(C2VDACompIntfTest, TestInputPortMime) {
    std::shared_ptr<C2PortMediaTypeSetting::input> expected(
            AllocSharedString<C2PortMediaTypeSetting::input>(MEDIA_MIMETYPE_VIDEO_AVC));
    std::shared_ptr<C2PortMediaTypeSetting::input> invalid(
            AllocSharedString<C2PortMediaTypeSetting::input>(MEDIA_MIMETYPE_VIDEO_RAW));
    TRACED_FAILURE(testReadOnlyParamOnHeap(expected.get(), invalid.get()));
}

TEST_F(C2VDACompIntfTest, TestOutputPortMime) {
    std::shared_ptr<C2PortMediaTypeSetting::output> expected(
            AllocSharedString<C2PortMediaTypeSetting::output>(MEDIA_MIMETYPE_VIDEO_RAW));
    std::shared_ptr<C2PortMediaTypeSetting::output> invalid(
            AllocSharedString<C2PortMediaTypeSetting::output>(MEDIA_MIMETYPE_VIDEO_AVC));
    TRACED_FAILURE(testReadOnlyParamOnHeap(expected.get(), invalid.get()));
}

TEST_F(C2VDACompIntfTest, TestProfileLevel) {
    // Iterate all possible profile and level combination
    TRACED_FAILURE(testWritableProfileLevelParam<C2StreamProfileLevelInfo::input>());
}

TEST_F(C2VDACompIntfTest, TestVideoSize) {
    C2StreamPictureSizeInfo::output videoSize;
    videoSize.setStream(0);  // only support single stream
    std::vector<C2FieldSupportedValuesQuery> widthC2FSV = {
            {C2ParamField(&videoSize, &C2StreamPictureSizeInfo::width),
             C2FieldSupportedValuesQuery::CURRENT},
    };
    ASSERT_EQ(C2_OK, mIntf->querySupportedValues_vb(widthC2FSV, C2_DONT_BLOCK));
    std::vector<C2FieldSupportedValuesQuery> heightC2FSV = {
            {C2ParamField(&videoSize, &C2StreamPictureSizeInfo::height),
             C2FieldSupportedValuesQuery::CURRENT},
    };
    ASSERT_EQ(C2_OK, mIntf->querySupportedValues_vb(heightC2FSV, C2_DONT_BLOCK));
    ASSERT_EQ(1u, widthC2FSV.size());
    ASSERT_EQ(C2_OK, widthC2FSV[0].status);
    ASSERT_EQ(C2FieldSupportedValues::RANGE, widthC2FSV[0].values.type);
    auto& widthFSVRange = widthC2FSV[0].values.range;
    int32_t widthMin = widthFSVRange.min.i32;
    int32_t widthMax = widthFSVRange.max.i32;
    int32_t widthStep = widthFSVRange.step.i32;

    ASSERT_EQ(1u, heightC2FSV.size());
    ASSERT_EQ(C2_OK, heightC2FSV[0].status);
    ASSERT_EQ(C2FieldSupportedValues::RANGE, heightC2FSV[0].values.type);
    auto& heightFSVRange = heightC2FSV[0].values.range;
    int32_t heightMin = heightFSVRange.min.i32;
    int32_t heightMax = heightFSVRange.max.i32;
    int32_t heightStep = heightFSVRange.step.i32;

    // test updating valid and invalid values
    TRACED_FAILURE(testWritableVideoSizeParam<C2StreamPictureSizeInfo::output>(
            widthMin, widthMax, widthStep, heightMin, heightMax, heightStep));
}

TEST_F(C2VDACompIntfTest, TestMaxInputSize) {
    // If output video size <= 1080p, max input size = kMaxInputBufferSize.
    C2StreamPictureSizeInfo::output videoSize(0u, 320, 240);
    std::vector<C2Param*> params{&videoSize};
    std::vector<std::unique_ptr<C2SettingResult>> failures;
    ASSERT_EQ(C2_OK, mIntf->config_vb(params, C2_DONT_BLOCK, &failures));
    EXPECT_EQ(0u, failures.size());

    C2StreamMaxBufferSizeInfo::input expected(0u, kMaxInputBufferSize);
    C2StreamMaxBufferSizeInfo::input invalid(0u, kMaxInputBufferSize * 4);
    TRACED_FAILURE(testReadOnlyParam(&expected, &invalid));

    // If output video size > 1080p, max input size = kMaxInputBufferSize * 4.
    videoSize.width = 3840;
    videoSize.height = 2160;
    failures.clear();
    ASSERT_EQ(C2_OK, mIntf->config_vb(params, C2_DONT_BLOCK, &failures));
    EXPECT_EQ(0u, failures.size());

    expected.value = kMaxInputBufferSize * 4;
    invalid.value = kMaxInputBufferSize;
    TRACED_FAILURE(testReadOnlyParam(&expected, &invalid));
}

TEST_F(C2VDACompIntfTest, TestInputAllocatorIds) {
    std::shared_ptr<C2PortAllocatorsTuning::input> expected(
            C2PortAllocatorsTuning::input::AllocShared(kInputAllocators));
    std::shared_ptr<C2PortAllocatorsTuning::input> invalid(
            C2PortAllocatorsTuning::input::AllocShared(kOutputAllocators));
    TRACED_FAILURE(testReadOnlyParamOnHeap(expected.get(), invalid.get()));
}

TEST_F(C2VDACompIntfTest, TestOutputAllocatorIds) {
    std::shared_ptr<C2PortAllocatorsTuning::output> expected(
            C2PortAllocatorsTuning::output::AllocShared(kOutputAllocators));
    std::shared_ptr<C2PortAllocatorsTuning::output> invalid(
            C2PortAllocatorsTuning::output::AllocShared(kInputAllocators));
    TRACED_FAILURE(testReadOnlyParamOnHeap(expected.get(), invalid.get()));
}

TEST_F(C2VDACompIntfTest, TestSurfaceAllocatorId) {
    C2PortSurfaceAllocatorTuning::output expected(kSurfaceAllocator);
    C2PortSurfaceAllocatorTuning::output invalid(kOutputAllocators[0]);
    TRACED_FAILURE(testReadOnlyParam(&expected, &invalid));
}

TEST_F(C2VDACompIntfTest, TestOutputBlockPoolIds) {
    std::vector<std::unique_ptr<C2Param>> heapParams;
    C2Param::Index index = C2PortBlockPoolsTuning::output::PARAM_TYPE;

    // Query the param and check the default value.
    ASSERT_EQ(C2_OK, mIntf->query_vb({}, {index}, C2_DONT_BLOCK, &heapParams));
    ASSERT_EQ(1u, heapParams.size());
    C2BlockPool::local_id_t value = ((C2PortBlockPoolsTuning*)heapParams[0].get())->m.values[0];
    ASSERT_EQ(kDefaultOutputBlockPool, value);

    // Configure the param.
    C2BlockPool::local_id_t configBlockPools[] = {C2BlockPool::PLATFORM_START + 1};
    std::shared_ptr<C2PortBlockPoolsTuning::output> newParam(
            C2PortBlockPoolsTuning::output::AllocShared(configBlockPools));

    std::vector<C2Param*> params{newParam.get()};
    std::vector<std::unique_ptr<C2SettingResult>> failures;
    ASSERT_EQ(C2_OK, mIntf->config_vb(params, C2_DONT_BLOCK, &failures));
    EXPECT_EQ(0u, failures.size());

    // Query the param again and check the value is as configured
    heapParams.clear();
    ASSERT_EQ(C2_OK, mIntf->query_vb({}, {index}, C2_DONT_BLOCK, &heapParams));
    ASSERT_EQ(1u, heapParams.size());
    value = ((C2PortBlockPoolsTuning*)heapParams[0].get())->m.values[0];
    ASSERT_EQ(configBlockPools[0], value);
}

TEST_F(C2VDACompIntfTest, TestColorAspects) {
    // Combined color aspects takes values from both coded color aspects first. If unspecified, take
    // default color aspects alternatively.
    C2StreamColorAspectsTuning::output defaultAspects(
            0u, C2Color::RANGE_FULL, C2Color::PRIMARIES_BT709, C2Color::TRANSFER_LINEAR,
            C2Color::MATRIX_BT709);
    C2StreamColorAspectsInfo::input codedAspects1(
            0u, C2Color::RANGE_LIMITED, C2Color::PRIMARIES_UNSPECIFIED, C2Color::TRANSFER_SRGB,
            C2Color::MATRIX_UNSPECIFIED);
    C2StreamColorAspectsInfo::input codedAspects2(
            0u, C2Color::RANGE_UNSPECIFIED, C2Color::PRIMARIES_BT2020,
            C2Color::TRANSFER_UNSPECIFIED, C2Color::MATRIX_BT601);

    C2StreamColorAspectsInfo::output expectedMergedAspects1(
            0u, C2Color::RANGE_LIMITED, C2Color::PRIMARIES_BT709, C2Color::TRANSFER_SRGB,
            C2Color::MATRIX_BT709);
    C2StreamColorAspectsInfo::output expectedMergedAspects2(
            0u, C2Color::RANGE_FULL, C2Color::PRIMARIES_BT2020, C2Color::TRANSFER_LINEAR,
            C2Color::MATRIX_BT601);

    // Test: default + coded 1 --> expected merged 1
    std::vector<C2Param*> params1{&defaultAspects, &codedAspects1};
    std::vector<std::unique_ptr<C2SettingResult>> failures;
    ASSERT_EQ(C2_OK, mIntf->config_vb(params1, C2_DONT_BLOCK, &failures));
    EXPECT_EQ(0u, failures.size());

    C2StreamColorAspectsInfo::output mergedAspects;
    std::vector<C2Param*> stackParams{&mergedAspects};
    ASSERT_EQ(C2_OK, mIntf->query_vb(stackParams, {}, C2_DONT_BLOCK, nullptr));
    EXPECT_EQ(mergedAspects, expectedMergedAspects1);

    // Test: default + coded 2 --> expected merged 2
    std::vector<C2Param*> params2{&codedAspects2};
    failures.clear();
    ASSERT_EQ(C2_OK, mIntf->config_vb(params2, C2_DONT_BLOCK, &failures));
    EXPECT_EQ(0u, failures.size());

    ASSERT_EQ(C2_OK, mIntf->query_vb(stackParams, {}, C2_DONT_BLOCK, nullptr));
    EXPECT_EQ(mergedAspects, expectedMergedAspects2);
}

TEST_F(C2VDACompIntfTest, TestUnsupportedParam) {
    C2ComponentTimeStretchTuning unsupportedParam;
    std::vector<C2Param*> stackParams{&unsupportedParam};
    ASSERT_EQ(C2_BAD_INDEX, mIntf->query_vb(stackParams, {}, C2_DONT_BLOCK, nullptr));
    EXPECT_EQ(0u, unsupportedParam.size());  // invalidated
}

TEST_F(C2VDACompIntfTest, ParamReflector) {
    dumpParamDescriptions();
}
}  // namespace android
