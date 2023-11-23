/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include <android-base/properties.h>
#include <android/hardware/automotive/vehicle/2.0/IVehicle.h>
#include <gtest/gtest.h>

namespace android {
namespace automotive {
namespace security {
namespace {

using android::hardware::automotive::vehicle::V2_0::IVehicle;
using android::hardware::automotive::vehicle::V2_0::StatusCode;
using android::hardware::automotive::vehicle::V2_0::VehiclePropConfig;
using android::hardware::automotive::vehicle::V2_0::VehicleProperty;

template <typename T>
using hidl_vec = android::hardware::hidl_vec<T>;

bool isSeedVhalPropertySupported(sp<IVehicle> vehicle) {
    bool is_supported = false;

    hidl_vec<int32_t> props = {
            static_cast<int32_t>(VehicleProperty::STORAGE_ENCRYPTION_BINDING_SEED)};
    vehicle->getPropConfigs(props,
                            [&is_supported](StatusCode status,
                                            hidl_vec<VehiclePropConfig> /*propConfigs*/) {
                                is_supported = (status == StatusCode::OK);
                            });
    return is_supported;
}

// Verify that vold got the binding seed if VHAL reports a seed
TEST(VehicleBindingIntegrationTedt, TestVehicleBindingSeedSet) {
    std::string expected_value = "1";
    if (!isSeedVhalPropertySupported(IVehicle::getService())) {
        GTEST_LOG_(INFO) << "Device does not support vehicle binding seed "
                            "(STORAGE_ENCRYPTION_BINDING_SEED).";
        expected_value = "";
    }

    ASSERT_EQ(expected_value, android::base::GetProperty("vold.storage_seed_bound", ""));
}

}  // namespace
}  // namespace security
}  // namespace automotive
}  // namespace android
