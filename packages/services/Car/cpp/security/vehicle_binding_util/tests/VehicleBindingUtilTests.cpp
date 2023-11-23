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

#include "VehicleBindingUtil.h"

#include <android/hardware/automotive/vehicle/2.0/IVehicle.h>
#include <android/hardware/automotive/vehicle/2.0/types.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <hidl/Status.h>
#include <utils/SystemClock.h>

#include <iterator>

namespace android {
namespace automotive {
namespace security {
namespace {

using android::hardware::Void;
using android::hardware::automotive::vehicle::V2_0::IVehicle;
using android::hardware::automotive::vehicle::V2_0::IVehicleCallback;
using android::hardware::automotive::vehicle::V2_0::StatusCode;
using android::hardware::automotive::vehicle::V2_0::SubscribeOptions;
using android::hardware::automotive::vehicle::V2_0::VehicleProperty;
using android::hardware::automotive::vehicle::V2_0::VehiclePropValue;

template <typename T>
using hidl_vec = android::hardware::hidl_vec<T>;
template <typename T>
using VhalReturn = android::hardware::Return<T>;

using ::testing::_;
using ::testing::DoAll;
using ::testing::ElementsAreArray;
using ::testing::NotNull;
using ::testing::Return;
using ::testing::SetArgPointee;
using ::testing::Test;

class MockVehicle : public IVehicle {
public:
    MOCK_METHOD(VhalReturn<void>, getAllPropConfigs, (getAllPropConfigs_cb), (override));

    MOCK_METHOD(VhalReturn<void>, getPropConfigs, (const hidl_vec<int32_t>&, getPropConfigs_cb),
                (override));

    MOCK_METHOD(VhalReturn<void>, get, (const VehiclePropValue&, get_cb), (override));

    MOCK_METHOD(VhalReturn<StatusCode>, set, (const VehiclePropValue&), (override));

    MOCK_METHOD(VhalReturn<StatusCode>, subscribe,
                (const sp<IVehicleCallback>&, const hidl_vec<SubscribeOptions>&), (override));

    MOCK_METHOD(VhalReturn<StatusCode>, unsubscribe, (const sp<IVehicleCallback>&, int32_t),
                (override));

    MOCK_METHOD(VhalReturn<void>, debugDump, (debugDump_cb), (override));
};

class MockCsrng : public Csrng {
public:
    MOCK_METHOD(bool, fill, (void*, size_t), (const override));
};

class MockExecutor : public Executor {
public:
    MOCK_METHOD(int, run, (const std::vector<std::string>&, int*), (const override));
};

class VehicleBindingUtilTests : public Test {
protected:
    void setMockVhalPropertySupported() {
        hidl_vec<int32_t> expectedProps = {toInt(VehicleProperty::STORAGE_ENCRYPTION_BINDING_SEED)};
        EXPECT_CALL(*mMockVehicle, getPropConfigs(expectedProps, _))
                .WillOnce([](const hidl_vec<int32_t>&, IVehicle::getPropConfigs_cb callback) {
                    callback(StatusCode::OK, {});
                    return Void();
                });
    }

    void setMockVhalPropertyValue(const std::vector<uint8_t>& seed) {
        EXPECT_CALL(*mMockVehicle, get(_, _))
                .WillOnce([seed](const VehiclePropValue& propValue, IVehicle::get_cb callback) {
                    EXPECT_EQ(propValue.prop,
                              toInt(VehicleProperty::STORAGE_ENCRYPTION_BINDING_SEED));
                    VehiclePropValue value;
                    value.prop = propValue.prop;
                    value.value.bytes = hidl_vec<uint8_t>{seed.begin(), seed.end()};
                    callback(StatusCode::OK, value);
                    return Void();
                });
    }

    void setTestRandomness(const char seed[SEED_BYTE_SIZE]) {
        EXPECT_CALL(mMockCsrng, fill(NotNull(), SEED_BYTE_SIZE))
                .WillOnce([seed](void* buf, size_t) {
                    memcpy(buf, seed, SEED_BYTE_SIZE);
                    return true;
                });
    }

    static std::vector<uint8_t> toVector(const char seed[SEED_BYTE_SIZE]) {
        return {seed, seed + SEED_BYTE_SIZE};
    }

    static std::vector<std::string> makeVdcArgs() {
        return {"/system/bin/vdc", "cryptfs", "bindkeys"};
    }

    sp<MockVehicle> mMockVehicle{new MockVehicle};
    MockExecutor mMockExecutor;
    MockCsrng mMockCsrng;
};

// Verify that we fail as expected if the VHAL property is not supported. This
// is not necessarily an error, and is expected on platforms that don't
// implement the feature.
TEST_F(VehicleBindingUtilTests, VhalPropertyUnsupported) {
    hidl_vec<int32_t> expectedProps = {toInt(VehicleProperty::STORAGE_ENCRYPTION_BINDING_SEED)};
    EXPECT_CALL(*mMockVehicle, getPropConfigs(expectedProps, _))
            .WillOnce([](const hidl_vec<int32_t>&, IVehicle::getPropConfigs_cb callback) {
                callback(StatusCode::INVALID_ARG, {});
                return Void();
            });

    EXPECT_EQ(BindingStatus::NOT_SUPPORTED,
              setVehicleBindingSeed(mMockVehicle, mMockExecutor, mMockCsrng));
}

// Verify that we properly handle an attempt to generate a random seed.
TEST_F(VehicleBindingUtilTests, GetRandomnessFails) {
    setMockVhalPropertySupported();
    setMockVhalPropertyValue({});
    EXPECT_CALL(mMockCsrng, fill(_, SEED_BYTE_SIZE)).WillOnce(Return(false));
    EXPECT_EQ(BindingStatus::ERROR, setVehicleBindingSeed(mMockVehicle, mMockExecutor, mMockCsrng));
}

// Verify that we properly handle an attempt to generate a random seed.
TEST_F(VehicleBindingUtilTests, GetSeedVhalPropertyFails) {
    setMockVhalPropertySupported();
    EXPECT_CALL(*mMockVehicle, get(_, _))
            .WillOnce([&](const VehiclePropValue& propValue, IVehicle::get_cb callback) {
                EXPECT_EQ(propValue.prop, toInt(VehicleProperty::STORAGE_ENCRYPTION_BINDING_SEED));
                callback(StatusCode::NOT_AVAILABLE, {});
                return Void();
            });
    EXPECT_EQ(BindingStatus::ERROR, setVehicleBindingSeed(mMockVehicle, mMockExecutor, mMockCsrng));
}

TEST_F(VehicleBindingUtilTests, SetSeedVhalPropertyFails) {
    setMockVhalPropertySupported();
    setMockVhalPropertyValue({});
    setTestRandomness("I am not random");

    EXPECT_CALL(*mMockVehicle, set(_)).WillOnce([](const VehiclePropValue&) {
        return StatusCode::NOT_AVAILABLE;
    });

    EXPECT_EQ(BindingStatus::ERROR, setVehicleBindingSeed(mMockVehicle, mMockExecutor, mMockCsrng));
}

TEST_F(VehicleBindingUtilTests, SetSeedWithNewRandomSeed) {
    setMockVhalPropertySupported();
    setMockVhalPropertyValue({});
    constexpr char SEED[SEED_BYTE_SIZE] = "Seed Value Here";
    setTestRandomness(SEED);

    EXPECT_CALL(*mMockVehicle, set(_)).WillOnce([&](const VehiclePropValue& value) {
        EXPECT_EQ(value.prop, toInt(VehicleProperty::STORAGE_ENCRYPTION_BINDING_SEED));
        EXPECT_THAT(value.value.bytes, testing::ElementsAreArray(SEED));
        return StatusCode::OK;
    });

    EXPECT_CALL(mMockExecutor, run(ElementsAreArray(makeVdcArgs()), _)).WillOnce(Return(0));

    EXPECT_EQ(BindingStatus::OK, setVehicleBindingSeed(mMockVehicle, mMockExecutor, mMockCsrng));
}

TEST_F(VehicleBindingUtilTests, SetSeedWithExistingProperty) {
    setMockVhalPropertySupported();
    const auto SEED = toVector("16 bytes of seed");
    setMockVhalPropertyValue(SEED);
    EXPECT_CALL(mMockExecutor, run(ElementsAreArray(makeVdcArgs()), _)).WillOnce(Return(0));
    EXPECT_EQ(BindingStatus::OK, setVehicleBindingSeed(mMockVehicle, mMockExecutor, mMockCsrng));
}

TEST_F(VehicleBindingUtilTests, SetSeedVdcExecFails) {
    setMockVhalPropertySupported();
    const auto SEED = toVector("abcdefghijklmnop");
    setMockVhalPropertyValue(SEED);
    EXPECT_CALL(mMockExecutor, run(ElementsAreArray(makeVdcArgs()), _)).WillOnce(Return(-1));
    EXPECT_EQ(BindingStatus::ERROR, setVehicleBindingSeed(mMockVehicle, mMockExecutor, mMockCsrng));
}

TEST_F(VehicleBindingUtilTests, SetSeedVdcExitsWithNonZeroStatus) {
    setMockVhalPropertySupported();
    const auto SEED = toVector("1123581321345589");
    setMockVhalPropertyValue(SEED);
    EXPECT_CALL(mMockExecutor, run(ElementsAreArray(makeVdcArgs()), _))
            .WillOnce(DoAll(SetArgPointee<1>(-1), Return(0)));
    EXPECT_EQ(BindingStatus::ERROR, setVehicleBindingSeed(mMockVehicle, mMockExecutor, mMockCsrng));
}

}  // namespace
}  // namespace security
}  // namespace automotive
}  // namespace android
