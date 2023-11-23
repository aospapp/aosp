/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "BatteryDefender.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <utils/Timers.h>

#include <android-base/file.h>
#include <android-base/properties.h>

class HealthInterface {
  public:
    virtual ~HealthInterface() {}
    virtual bool ReadFileToString(const std::string &path, std::string *content,
                                  bool follow_symlinks);
    virtual int GetIntProperty(const std::string &key, int default_value, int min, int max);
    virtual bool GetBoolProperty(const std::string &key, bool default_value);
    virtual bool SetProperty(const std::string &key, const std::string &value);
    virtual bool WriteStringToFile(const std::string &content, const std::string &path,
                                   bool follow_symlinks);
};

class HealthInterfaceMock : public HealthInterface {
  public:
    virtual ~HealthInterfaceMock() {}

    MOCK_METHOD3(ReadFileToString,
                 bool(const std::string &path, std::string *content, bool follow_symlinks));
    MOCK_METHOD4(GetIntProperty, int(const std::string &key, int default_value, int min, int max));
    MOCK_METHOD2(GetBoolProperty, bool(const std::string &key, bool default_value));
    MOCK_METHOD2(SetProperty, bool(const std::string &key, const std::string &value));
    MOCK_METHOD3(WriteStringToFile,
                 bool(const std::string &content, const std::string &path, bool follow_symlinks));
};

HealthInterfaceMock *mock;

namespace android {
namespace base {

bool ReadFileToString(const std::string &path, std::string *content, bool follow_symlinks) {
    return mock->ReadFileToString(path, content, follow_symlinks);
}

bool WriteStringToFile(const std::string &content, const std::string &path, bool follow_symlinks) {
    return mock->WriteStringToFile(content, path, follow_symlinks);
}

template <typename T>
T GetIntProperty(const std::string &key, T default_value, T min, T max) {
    return (T)(mock->GetIntProperty(key, default_value, min, max));
}

bool GetBoolProperty(const std::string &key, bool default_value) {
    return mock->GetBoolProperty(key, default_value);
}

template int8_t GetIntProperty(const std::string &, int8_t, int8_t, int8_t);
template int16_t GetIntProperty(const std::string &, int16_t, int16_t, int16_t);
template int32_t GetIntProperty(const std::string &, int32_t, int32_t, int32_t);
template int64_t GetIntProperty(const std::string &, int64_t, int64_t, int64_t);

bool SetProperty(const std::string &key, const std::string &value) {
    return mock->SetProperty(key, value);
}

}  // namespace base
}  // namespace android

nsecs_t testvar_systemTimeSecs = 0;
nsecs_t systemTime(int clock) {
    UNUSED(clock);
    return seconds_to_nanoseconds(testvar_systemTimeSecs);
}

namespace hardware {
namespace google {
namespace pixel {
namespace health {

using ::testing::_;
using ::testing::AnyNumber;
using ::testing::AtLeast;
using ::testing::DoAll;
using ::testing::InSequence;
using ::testing::Return;
using ::testing::SetArgPointee;

class BatteryDefenderTest : public ::testing::Test {
  public:
    BatteryDefenderTest() {}

    void SetUp() {
        mock = &mockFixture;

        EXPECT_CALL(*mock, SetProperty(_, _)).Times(AnyNumber());
        EXPECT_CALL(*mock, ReadFileToString(_, _, _)).Times(AnyNumber());
        EXPECT_CALL(*mock, GetIntProperty(_, _, _, _)).Times(AnyNumber());
        EXPECT_CALL(*mock, GetBoolProperty(_, _)).Times(AnyNumber());
        EXPECT_CALL(*mock, WriteStringToFile(_, _, _)).Times(AnyNumber());

        ON_CALL(*mock, ReadFileToString(_, _, _))
                .WillByDefault(DoAll(SetArgPointee<1>(std::string("0")), Return(true)));

        ON_CALL(*mock, WriteStringToFile(_, _, _)).WillByDefault(Return(true));
    }

    void TearDown() {}

  private:
    HealthInterfaceMock mockFixture;
};

const char *kPathWirelessChargerOnline = "/sys/class/power_supply/wireless/online";
const char *kPathWiredChargerPresent = "/sys/class/power_supply/usb/present";
const char *kPathBatteryCapacity = "/sys/class/power_supply/battery/capacity";
const char *kPathPersistChargerPresentTime = "/mnt/vendor/persist/battery/defender_charger_time";
const char *kPathPersistDefenderActiveTime = "/mnt/vendor/persist/battery/defender_active_time";
const char *kPathStartLevel = "/sys/devices/platform/soc/soc:google,charger/charge_start_level";
const char *kPathStopLevel = "/sys/devices/platform/soc/soc:google,charger/charge_stop_level";

const char *kPropChargeLevelVendorStart = "persist.vendor.charge.start.level";
const char *kPropChargeLevelVendorStop = "persist.vendor.charge.stop.level";
const char *kPropBatteryDefenderState = "vendor.battery.defender.state";
const char *kPropBatteryDefenderDisable = "vendor.battery.defender.disable";
const char *kPropBatteryDefenderThreshold = "vendor.battery.defender.threshold";
const char *kPropDebuggable = "ro.debuggable";

static void enableDefender(void) {
    ON_CALL(*mock, GetIntProperty(kPropChargeLevelVendorStart, _, _, _)).WillByDefault(Return(0));
    ON_CALL(*mock, GetIntProperty(kPropChargeLevelVendorStop, _, _, _)).WillByDefault(Return(100));
    ON_CALL(*mock, GetBoolProperty(kPropBatteryDefenderDisable, _)).WillByDefault(Return(false));
    ON_CALL(*mock, GetBoolProperty(kPropDebuggable, _)).WillByDefault(Return(true));
}

static void powerAvailable(void) {
    ON_CALL(*mock, ReadFileToString(kPathWirelessChargerOnline, _, _))
            .WillByDefault(DoAll(SetArgPointee<1>(std::string("1")), Return(true)));
    ON_CALL(*mock, ReadFileToString(kPathWiredChargerPresent, _, _))
            .WillByDefault(DoAll(SetArgPointee<1>(std::string("1")), Return(true)));
}

static void defaultThreshold(void) {
    ON_CALL(*mock, GetIntProperty(kPropBatteryDefenderThreshold, _, _, _))
            .WillByDefault(Return(DEFAULT_TIME_TO_ACTIVATE_SECONDS));
}

static void capacityReached(void) {
    ON_CALL(*mock, ReadFileToString(kPathBatteryCapacity, _, _))
            .WillByDefault(DoAll(SetArgPointee<1>(std::to_string(100)), Return(true)));
}

static void initToConnectedCapacityReached(void) {
    ON_CALL(*mock, ReadFileToString(kPathPersistChargerPresentTime, _, _))
            .WillByDefault(DoAll(SetArgPointee<1>(std::to_string(1000)), Return(true)));
}

static void initToActive(void) {
    ON_CALL(*mock, ReadFileToString(kPathPersistChargerPresentTime, _, _))
            .WillByDefault(
                    DoAll(SetArgPointee<1>(std::to_string(DEFAULT_TIME_TO_ACTIVATE_SECONDS + 1)),
                          Return(true)));
}

TEST_F(BatteryDefenderTest, EnableAndDisconnected) {
    BatteryDefender battDefender;

    enableDefender();
    // No power

    // Enable Battery Defender
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "DISCONNECTED"));
    battDefender.update();
}

TEST_F(BatteryDefenderTest, DisableNonDefaultLevels) {
    BatteryDefender battDefender;

    // Enable Battery Defender
    EXPECT_CALL(*mock, GetIntProperty(kPropChargeLevelVendorStart, _, _, _)).WillOnce(Return(30));
    EXPECT_CALL(*mock, GetIntProperty(kPropChargeLevelVendorStop, _, _, _)).WillOnce(Return(35));

    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "DISABLED"));
    battDefender.update();
}

TEST_F(BatteryDefenderTest, DisableDebuggable) {
    BatteryDefender battDefender;

    // Enable Battery Defender
    EXPECT_CALL(*mock, GetBoolProperty(kPropDebuggable, _)).WillOnce(Return(false));

    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "DISABLED"));
    battDefender.update();
}

TEST_F(BatteryDefenderTest, DisableExplicit) {
    BatteryDefender battDefender;

    // Enable Battery Defender
    EXPECT_CALL(*mock, GetBoolProperty(kPropBatteryDefenderDisable, _)).WillOnce(Return(true));

    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "DISABLED"));
    battDefender.update();
}

TEST_F(BatteryDefenderTest, InitActive) {
    BatteryDefender battDefender;

    enableDefender();
    powerAvailable();
    defaultThreshold();

    EXPECT_CALL(*mock, ReadFileToString(kPathPersistChargerPresentTime, _, _))
            .WillOnce(DoAll(SetArgPointee<1>(std::to_string(DEFAULT_TIME_TO_ACTIVATE_SECONDS + 1)),
                            Return(true)));
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "ACTIVE"));
    battDefender.update();
}

TEST_F(BatteryDefenderTest, InitConnectedCapacityReached) {
    BatteryDefender battDefender;

    enableDefender();
    powerAvailable();
    defaultThreshold();

    InSequence s;

    EXPECT_CALL(*mock, ReadFileToString(kPathPersistChargerPresentTime, _, _))
            .WillOnce(DoAll(SetArgPointee<1>(std::to_string(DEFAULT_TIME_TO_ACTIVATE_SECONDS - 1)),
                            Return(true)));
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "CONNECTED"));
    battDefender.update();

    testvar_systemTimeSecs++;
    EXPECT_CALL(*mock, WriteStringToFile(std::to_string(DEFAULT_TIME_TO_ACTIVATE_SECONDS),
                                         kPathPersistChargerPresentTime, _));
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "CONNECTED"));
    battDefender.update();
}

TEST_F(BatteryDefenderTest, InitConnected) {
    BatteryDefender battDefender;

    enableDefender();
    powerAvailable();
    defaultThreshold();

    InSequence s;

    EXPECT_CALL(*mock, ReadFileToString(kPathPersistChargerPresentTime, _, _))
            .WillOnce(DoAll(SetArgPointee<1>(std::to_string(0)), Return(true)));
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "CONNECTED"));
    battDefender.update();

    // mHasReachedHighCapacityLevel shall be false
    testvar_systemTimeSecs += DEFAULT_TIME_TO_ACTIVATE_SECONDS + 1;
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "CONNECTED"));
    battDefender.update();
}

TEST_F(BatteryDefenderTest, TriggerTime) {
    BatteryDefender battDefender;

    enableDefender();
    powerAvailable();
    defaultThreshold();

    InSequence s;

    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "CONNECTED"));
    testvar_systemTimeSecs += 1;
    battDefender.update();

    // Reached 100% capacity at least once
    EXPECT_CALL(*mock, ReadFileToString(kPathBatteryCapacity, _, _))
            .WillOnce(DoAll(SetArgPointee<1>(std::to_string(100)), Return(true)));
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "CONNECTED"));
    testvar_systemTimeSecs += 1;
    battDefender.update();

    EXPECT_CALL(*mock, WriteStringToFile(std::to_string(DEFAULT_TIME_TO_ACTIVATE_SECONDS),
                                         kPathPersistChargerPresentTime, _));
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "CONNECTED"));
    testvar_systemTimeSecs += DEFAULT_TIME_TO_ACTIVATE_SECONDS;
    battDefender.update();

    EXPECT_CALL(*mock, WriteStringToFile(std::to_string(DEFAULT_TIME_TO_ACTIVATE_SECONDS + 1),
                                         kPathPersistChargerPresentTime, _));
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "ACTIVE"));
    testvar_systemTimeSecs += 1;
    battDefender.update();
}

TEST_F(BatteryDefenderTest, ChargeLevels) {
    BatteryDefender battDefender;

    enableDefender();
    powerAvailable();
    defaultThreshold();
    initToConnectedCapacityReached();

    InSequence s;

    // No expectations needed; default values already set
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "CONNECTED"));
    testvar_systemTimeSecs += 0;
    battDefender.update();

    EXPECT_CALL(*mock, WriteStringToFile(std::to_string(60), kPathStartLevel, _));
    EXPECT_CALL(*mock, WriteStringToFile(std::to_string(70), kPathStopLevel, _));
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "ACTIVE"));
    testvar_systemTimeSecs += DEFAULT_TIME_TO_ACTIVATE_SECONDS + 1;
    battDefender.update();
}

TEST_F(BatteryDefenderTest, ActiveTime) {
    BatteryDefender battDefender;

    enableDefender();
    powerAvailable();
    defaultThreshold();
    initToActive();

    InSequence s;

    EXPECT_CALL(*mock, WriteStringToFile(std::to_string(60), kPathStartLevel, _));
    EXPECT_CALL(*mock, WriteStringToFile(std::to_string(70), kPathStopLevel, _));
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "ACTIVE"));
    battDefender.update();
}

TEST_F(BatteryDefenderTest, ConnectDisconnectCycle) {
    BatteryDefender battDefender;

    enableDefender();
    defaultThreshold();
    initToConnectedCapacityReached();

    InSequence s;

    // Power ON
    ON_CALL(*mock, ReadFileToString(kPathWirelessChargerOnline, _, _))
            .WillByDefault(DoAll(SetArgPointee<1>(std::string("1")), Return(true)));

    EXPECT_CALL(*mock, WriteStringToFile(std::to_string(1000), kPathPersistChargerPresentTime, _));
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "CONNECTED"));
    testvar_systemTimeSecs += 60;
    battDefender.update();

    EXPECT_CALL(*mock, WriteStringToFile(std::to_string(1060), kPathPersistChargerPresentTime, _));
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "CONNECTED"));
    testvar_systemTimeSecs += 60;
    battDefender.update();

    // Power OFF
    ON_CALL(*mock, ReadFileToString(kPathWirelessChargerOnline, _, _))
            .WillByDefault(DoAll(SetArgPointee<1>(std::string("0")), Return(true)));

    // Maintain kPathPersistChargerPresentTime = 1060
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "CONNECTED"));
    testvar_systemTimeSecs += 60;
    battDefender.update();

    // Maintain kPathPersistChargerPresentTime = 1060
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "CONNECTED"));
    testvar_systemTimeSecs += 60 * 4;
    battDefender.update();

    EXPECT_CALL(*mock, WriteStringToFile(std::to_string(0), kPathPersistChargerPresentTime, _));
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "DISCONNECTED"));
    testvar_systemTimeSecs += 1;
    battDefender.update();

    // Power ON
    ON_CALL(*mock, ReadFileToString(kPathWirelessChargerOnline, _, _))
            .WillByDefault(DoAll(SetArgPointee<1>(std::string("1")), Return(true)));

    // Maintain kPathPersistChargerPresentTime = 0
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "CONNECTED"));
    testvar_systemTimeSecs += 60;
    battDefender.update();

    capacityReached();
    // Maintain kPathPersistChargerPresentTime = 0
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "CONNECTED"));
    testvar_systemTimeSecs += 60;
    battDefender.update();

    EXPECT_CALL(*mock, WriteStringToFile(std::to_string(60), kPathPersistChargerPresentTime, _));
    EXPECT_CALL(*mock, SetProperty(kPropBatteryDefenderState, "CONNECTED"));
    testvar_systemTimeSecs += 60;
    battDefender.update();
}

}  // namespace health
}  // namespace pixel
}  // namespace google
}  // namespace hardware
