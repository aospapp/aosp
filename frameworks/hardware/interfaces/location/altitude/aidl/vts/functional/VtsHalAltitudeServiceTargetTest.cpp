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

#include <aidl/Gtest.h>
#include <aidl/Vintf.h>
#include <aidl/android/frameworks/location/altitude/AddMslAltitudeToLocationRequest.h>
#include <aidl/android/frameworks/location/altitude/AddMslAltitudeToLocationResponse.h>
#include <aidl/android/frameworks/location/altitude/IAltitudeService.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

using ::aidl::android::frameworks::location::altitude::AddMslAltitudeToLocationRequest;
using ::aidl::android::frameworks::location::altitude::AddMslAltitudeToLocationResponse;
using ::aidl::android::frameworks::location::altitude::IAltitudeService;
using ::android::getAidlHalInstanceNames;
using ::android::PrintInstanceNameToString;
using ndk::SpAIBinder;
using ::testing::Eq;
using ::testing::InitGoogleTest;
using ::testing::TestWithParam;
using ::testing::ValuesIn;

class AltitudeServiceTest : public TestWithParam<std::string> {
   public:
    void SetUp() override {
        SpAIBinder binder(AServiceManager_waitForService(GetParam().c_str()));
        service = IAltitudeService::fromBinder(binder);
        ASSERT_NE(service, nullptr);
    }
    std::shared_ptr<IAltitudeService> service;
};

TEST_P(AltitudeServiceTest, TestGetDistanceBasedExpiringGeoidHeight) {
    // Test known location near Hawaii.
    AddMslAltitudeToLocationRequest request;
    request.latitudeDegrees = 19.545519;
    request.longitudeDegrees = -155.998774;
    request.altitudeMeters = -1;
    request.verticalAccuracyMeters = 1;

    AddMslAltitudeToLocationResponse response;
    service->addMslAltitudeToLocation(request, &response);
    ASSERT_NEAR(response.mslAltitudeMeters, -19.2359, 2);
    ASSERT_NEAR(response.mslAltitudeAccuracyMeters, 1.05f, 0.5f);
}

GTEST_ALLOW_UNINSTANTIATED_PARAMETERIZED_TEST(AltitudeServiceTest);
INSTANTIATE_TEST_SUITE_P(AltitudeService, AltitudeServiceTest,
                         ValuesIn(getAidlHalInstanceNames(IAltitudeService::descriptor)),
                         PrintInstanceNameToString);

int main(int argc, char** argv) {
    InitGoogleTest(&argc, argv);
    ABinderProcess_setThreadPoolMaxThreadCount(1);
    ABinderProcess_startThreadPool();
    return RUN_ALL_TESTS();
}
