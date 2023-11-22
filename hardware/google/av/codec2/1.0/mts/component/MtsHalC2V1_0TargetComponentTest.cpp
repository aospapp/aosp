/*
 * Copyright (C) 2018 The Android Open Source Project
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

#define LOG_NDEBUG 0
#define LOG_TAG "codec2_hidl_hal_component_test"

#include <android-base/logging.h>
#include <gtest/gtest.h>

#include <hardware/google/media/c2/1.0/IComponent.h>
#include <hardware/google/media/c2/1.0/IComponentStore.h>

using ::hardware::google::media::c2::V1_0::IComponent;
using ::hardware::google::media::c2::V1_0::IComponentStore;
using ::hardware::google::media::c2::V1_0::IComponentInterface;
using ::hardware::google::media::c2::V1_0::FieldSupportedValuesQuery;
using ::hardware::google::media::c2::V1_0::FieldSupportedValuesQueryResult;
using ::hardware::google::media::c2::V1_0::ParamDescriptor;
using ::hardware::google::media::c2::V1_0::SettingResult;
using ::android::hardware::hidl_vec;
using ::android::hardware::hidl_string;
using ::android::sp;

#include <VtsHalHidlTargetTestBase.h>
#include "media_c2_hidl_test_common.h"

static ComponentTestEnvironment* gEnv = nullptr;

namespace {

// google.codec2 Component test setup
class Codec2ComponentHalTest : public ::testing::VtsHalHidlTargetTestBase {
   private:
    typedef ::testing::VtsHalHidlTargetTestBase Super;

   public:
    virtual void SetUp() override {
        Super::SetUp();
        mStore = Super::getService<IComponentStore>(gEnv->getInstance());
        ASSERT_NE(mStore, nullptr);
        mListener = new CodecListener();
        ASSERT_NE(mListener, nullptr);
        mStore->createComponent(gEnv->getComponent().c_str(), mListener,
                                nullptr,
                                [&](Status _s, const sp<IComponent>& _nl) {
                                    ASSERT_EQ(_s, Status::OK);
                                    this->mComponent = _nl;
                                });
        ASSERT_NE(mComponent, nullptr);
    }

    virtual void TearDown() override {
        if (mComponent != nullptr) {
            // If you have encountered a fatal failure, it is possible that
            // freeNode() will not go through. Instead of hanging the app.
            // let it pass through and report errors
            if (::testing::Test::HasFatalFailure()) return;
            mComponent->release();
            mComponent = nullptr;
        }
        Super::TearDown();
    }

    sp<IComponent> mComponent;
    sp<IComponentStore> mStore;
    sp<CodecListener> mListener;

   protected:
    static void description(const std::string& description) {
        RecordProperty("description", description);
    }
};

// Test Empty Flush
TEST_F(Codec2ComponentHalTest, EmptyFlush) {
    ALOGV("Empty Flush Test");
    Status err = mComponent->start();
    ASSERT_EQ(err, Status::OK);

    // Flushed output expected to be of 0 size, as no input has been fed
    mComponent->flush([&](Status _s, const WorkBundle& _flushedWorkBundle) {
        ASSERT_EQ(_s, Status::OK);
        ASSERT_EQ(_flushedWorkBundle.works.size(), 0u)
            << "Invalid Flushed Work Size";
        ASSERT_EQ(_flushedWorkBundle.baseBlocks.size(), 0u);
    });
}

// Test Queue Empty Work
TEST_F(Codec2ComponentHalTest, QueueEmptyWork) {
    ALOGV("Queue Empty Work Test");
    Status err = mComponent->start();
    ASSERT_EQ(err, Status::OK);

    // Queueing an empty WorkBundle
    const WorkBundle workBundle = {};
    err = mComponent->queue(workBundle);
    ASSERT_EQ(err, Status::OK);

    err = mComponent->reset();
    ASSERT_EQ(err, Status::OK);
}

// Test Component Configuration
TEST_F(Codec2ComponentHalTest, Config) {
    ALOGV("Configuration Test");
    mComponent->getName([](const hidl_string& name) {
        EXPECT_NE(name.empty(), true) << "Invalid Component Store Name";
        ALOGV("Component under Test %s", name.c_str());
    });

#define MAX_PARAMS 64
    /* Querry Supported Params */
    hidl_vec<ParamDescriptor> params;
    mComponent->querySupportedParams(
        0, MAX_PARAMS,
        [&params](Status _s, const hidl_vec<ParamDescriptor>& _p) {
            ASSERT_EQ(_s, Status::OK);
            params = _p;
            ALOGE("TEMP - Params capacity - %zu", _p.size());
        });

    std::vector<uint32_t> tempIndices;
    std::vector<FieldSupportedValuesQuery> tempInFields;
    for (size_t i = 0; i < params.size(); i++) {
        EXPECT_NE(params[i].name.empty(), true)
            << "Invalid Supported Param Name";
        ALOGV("Params Supported : %s", params[i].name.c_str());
        tempIndices.push_back(params[i].index);
        FieldSupportedValuesQuery tempFSV;
        tempFSV.field.index = params[i].index;
        tempInFields.push_back(tempFSV);
    }


    /* Query Supported Values */
    const hidl_vec<FieldSupportedValuesQuery> inFields = tempInFields;
    bool mayBlock = false;
    hidl_vec<FieldSupportedValuesQueryResult> outFields;
    mComponent->querySupportedValues(
        inFields, mayBlock,
        [&outFields](
            Status _s,
            const hidl_vec<FieldSupportedValuesQueryResult>& _outFields) {
            ASSERT_EQ(_s, Status::OK);
            outFields = _outFields;
        });

    // Fileds size should match
    ASSERT_EQ(inFields.size(), outFields.size());

    /* TODO: How to give proper indices */
    const hidl_vec<uint32_t> indices = tempIndices;
    hidl_vec<uint8_t> inParamsQuery;
    mComponent->query(indices, mayBlock,
                      [&inParamsQuery](Status _s, const hidl_vec<uint8_t>& _p) {
                          ASSERT_EQ(_s, Status::OK);
                          inParamsQuery = _p;
                      });

    /* Config default parameters*/
    const hidl_vec<uint8_t> inParams = inParamsQuery;
    hidl_vec<uint8_t> outParamsQuery;
    mComponent->config(
        inParams, mayBlock,
        [&outParamsQuery](Status _s, const hidl_vec<SettingResult>& _f,
                          const hidl_vec<uint8_t>& _outParams) {
            ASSERT_EQ(_s, Status::OK);
            // There should be no failures, since default config is reapplied
            ASSERT_EQ(_f.size(), 0u);
            outParamsQuery = _outParams;
        });

    // Fileds size should match
    ASSERT_EQ(inParams.size(), outParamsQuery.size());
}

// Test Multiple Start Stop Reset Test
TEST_F(Codec2ComponentHalTest, MultipleStartStopReset) {
    ALOGV("Multiple Start Stop and Reset Test");
    Status err = Status::OK;

#define MAX_RETRY 16

    for (size_t i = 0; i < MAX_RETRY; i++) {
        err = mComponent->start();
        ASSERT_EQ(err, Status::OK);

        err = mComponent->stop();
        ASSERT_EQ(err, Status::OK);
    }

    err = mComponent->start();
    ASSERT_EQ(err, Status::OK);

    for (size_t i = 0; i < MAX_RETRY; i++) {
        err = mComponent->reset();
        ASSERT_EQ(err, Status::OK);
    }

    err = mComponent->start();
    ASSERT_EQ(err, Status::OK);

    err = mComponent->stop();
    ASSERT_EQ(err, Status::OK);

    // Second stop should return error
    err = mComponent->stop();
    ASSERT_NE(err, Status::OK);
}

}  // anonymous namespace

// TODO: Add test for Invalid work, Invalid Config handling
// TODO: Add test for Invalid states
int main(int argc, char** argv) {
    gEnv = new ComponentTestEnvironment();
    ::testing::AddGlobalTestEnvironment(gEnv);
    ::testing::InitGoogleTest(&argc, argv);
    gEnv->init(&argc, argv);
    int status = gEnv->initFromOptions(argc, argv);
    if (status == 0) {
        status = RUN_ALL_TESTS();
        LOG(INFO) << "C2 Test result = " << status;
    }
    return status;
}

