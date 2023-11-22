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

#define LOG_TAG "codec2_hidl_hal_master_test"

#include <android-base/logging.h>
#include <gtest/gtest.h>

#include <hardware/google/media/c2/1.0/IComponent.h>
#include <hardware/google/media/c2/1.0/IComponentStore.h>

using hardware::google::media::c2::V1_0::IComponent;
using hardware::google::media::c2::V1_0::IComponentStore;
using ::android::hardware::hidl_vec;
using ::android::hardware::hidl_string;
using ::android::sp;

#include <VtsHalHidlTargetTestBase.h>
#include "media_c2_hidl_test_common.h"

static ComponentTestEnvironment* gEnv = nullptr;

namespace {

// google.codec2 Master test setup
class Codec2MasterHalTest : public ::testing::VtsHalHidlTargetTestBase {
   private:
    typedef ::testing::VtsHalHidlTargetTestBase Super;

   public:
    virtual void SetUp() override {
        Super::SetUp();
        mStore = Super::getService<IComponentStore>(gEnv->getInstance());
        ASSERT_NE(mStore, nullptr);
    }

   protected:
    static void description(const std::string& description) {
        RecordProperty("description", description);
    }

    sp<IComponentStore> mStore;
};

void displayComponentInfo(
    hidl_vec<IComponentStore::ComponentTraits>& compList) {
    for (size_t i = 0; i < compList.size(); i++) {
        std::cout << compList[i].name << " | " << toString(compList[i].domain);
        std::cout << " | " << toString(compList[i].kind) << "\n";
    }
}

// List Components
TEST_F(Codec2MasterHalTest, ListComponents) {
    ALOGV("ListComponents Test");
    mStore->getName([](const ::android::hardware::hidl_string& name) {
        EXPECT_NE(name.empty(), true) << "Invalid Component Store Name";
    });

    android::hardware::hidl_vec<IComponentStore::ComponentTraits> listTraits;
    mStore->listComponents(
        [&](const android::hardware::hidl_vec<IComponentStore::ComponentTraits>&
                traits) { listTraits = traits; });

    bool isPass = true;
    if (listTraits.size() == 0)
        ALOGE("Warning, ComponentInfo list empty");
    else {
        (void)displayComponentInfo;
        // displayComponentInfo(listTraits);
        for (size_t i = 0; i < listTraits.size(); i++) {
            sp<CodecListener> listener = new CodecListener();
            ASSERT_NE(listener, nullptr);

            mStore->createComponent(
                listTraits[i].name.c_str(), listener, nullptr,
                [&](Status _s, const sp<IComponent>& _nl) {
                    ASSERT_EQ(_s, Status::OK);
                    if (!_nl) {
                        isPass = false;
                        std::cerr << "[    !OK   ] "
                                  << listTraits[i].name.c_str() << "\n";
                    }
                });
        }
    }
    EXPECT_TRUE(isPass);
}

}  // anonymous namespace

int main(int argc, char** argv) {
    gEnv = new ComponentTestEnvironment();
    ::testing::InitGoogleTest(&argc, argv);
    gEnv->init(&argc, argv);
    int status = gEnv->initFromOptions(argc, argv);
    if (status == 0) {
        status = RUN_ALL_TESTS();
        LOG(INFO) << "C2 Test result = " << status;
    }
    return status;
}
