/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <android-base/logging.h>
#include <android-base/macros.h>
#include <cutils/properties.h>
#include <gmock/gmock.h>

#include "wifi_chip.h"

#include "mock_interface_tool.h"
#include "mock_wifi_feature_flags.h"
#include "mock_wifi_iface_util.h"
#include "mock_wifi_legacy_hal.h"
#include "mock_wifi_mode_controller.h"

using testing::NiceMock;
using testing::Return;
using testing::Test;

namespace {
constexpr int kFakeChipId = 5;
}  // namespace

namespace aidl {
namespace android {
namespace hardware {
namespace wifi {

class WifiChipTest : public Test {
  protected:
    void setupV1IfaceCombination() {
        // clang-format off
		// 1 STA + 1 P2P
        const std::vector<IWifiChip::ChipConcurrencyCombination> combinationsSta =
		{
        	{
				{
					{{IfaceConcurrencyType::STA}, 1},
					{{IfaceConcurrencyType::P2P}, 1}
				}
			}
		};
		// 1 AP
        const std::vector<IWifiChip::ChipConcurrencyCombination> combinationsAp =
		{
            {
				{
					{{IfaceConcurrencyType::AP}, 1}
				}
			}
        };
        const std::vector<IWifiChip::ChipMode> modes = {
            {feature_flags::chip_mode_ids::kV1Sta, combinationsSta},
            {feature_flags::chip_mode_ids::kV1Ap, combinationsAp}
        };
        // clang-format on
        EXPECT_CALL(*feature_flags_, getChipModes(true)).WillRepeatedly(testing::Return(modes));
    }

    void setupV1_AwareIfaceCombination() {
        // clang-format off
		// 1 STA + 1 of (P2P or NAN)
        const std::vector<IWifiChip::ChipConcurrencyCombination> combinationsSta =
		{
            {
				{
					{{IfaceConcurrencyType::STA}, 1},
              		{{IfaceConcurrencyType::P2P, IfaceConcurrencyType::NAN_IFACE}, 1}
				}
			}
        };
		// 1 AP
        const std::vector<IWifiChip::ChipConcurrencyCombination> combinationsAp =
		{
            {
				{
					{{IfaceConcurrencyType::AP}, 1}
				}
			}
        };
        const std::vector<IWifiChip::ChipMode> modes = {
            {feature_flags::chip_mode_ids::kV1Sta, combinationsSta},
            {feature_flags::chip_mode_ids::kV1Ap, combinationsAp}
        };
        // clang-format on
        EXPECT_CALL(*feature_flags_, getChipModes(true)).WillRepeatedly(testing::Return(modes));
    }

    void setupV1_AwareDisabledApIfaceCombination() {
        // clang-format off
		// 1 STA + 1 of (P2P or NAN)
        const std::vector<IWifiChip::ChipConcurrencyCombination> combinationsSta =
		{
            {
				{
					{{IfaceConcurrencyType::STA}, 1},
              		{{IfaceConcurrencyType::P2P, IfaceConcurrencyType::NAN_IFACE}, 1}
				}
			}
        };
        const std::vector<IWifiChip::ChipMode> modes = {
            {feature_flags::chip_mode_ids::kV1Sta, combinationsSta}
        };
        // clang-format on
        EXPECT_CALL(*feature_flags_, getChipModes(true)).WillRepeatedly(testing::Return(modes));
    }

    void setupV2_AwareIfaceCombination() {
        // clang-format off
		// (1 STA + 1 AP) or (1 STA + 1 of (P2P or NAN))
        const std::vector<IWifiChip::ChipConcurrencyCombination> combinations =
		{
            {
				{
					{{IfaceConcurrencyType::STA}, 1},
					{{IfaceConcurrencyType::AP}, 1}
				}
			},
            {
				{
					{{IfaceConcurrencyType::STA}, 1},
              		{{IfaceConcurrencyType::P2P, IfaceConcurrencyType::NAN_IFACE}, 1}
				}
			}
        };
        const std::vector<IWifiChip::ChipMode> modes = {
            {feature_flags::chip_mode_ids::kV3, combinations}
        };
        // clang-format on
        EXPECT_CALL(*feature_flags_, getChipModes(true)).WillRepeatedly(testing::Return(modes));
    }

    void setupV2_AwareDisabledApIfaceCombination() {
        // clang-format off
		// 1 STA + 1 of (P2P or NAN)
        const std::vector<IWifiChip::ChipConcurrencyCombination> combinations =
		{
            {
				{
					{{IfaceConcurrencyType::STA}, 1},
              		{{IfaceConcurrencyType::P2P, IfaceConcurrencyType::NAN_IFACE}, 1}
				}
			}
        };
        const std::vector<IWifiChip::ChipMode> modes = {
            {feature_flags::chip_mode_ids::kV3, combinations}
        };
        // clang-format on
        EXPECT_CALL(*feature_flags_, getChipModes(true)).WillRepeatedly(testing::Return(modes));
    }

    void setup_MultiIfaceCombination() {
        // clang-format off
		// 3 STA + 1 AP
        const std::vector<IWifiChip::ChipConcurrencyCombination> combinations =
		{
            {
				{
					{{IfaceConcurrencyType::STA}, 3},
					{{IfaceConcurrencyType::AP}, 1}
				}
			}
        };
        const std::vector<IWifiChip::ChipMode> modes = {
            {feature_flags::chip_mode_ids::kV3, combinations}
        };
        // clang-format on
        EXPECT_CALL(*feature_flags_, getChipModes(true)).WillRepeatedly(testing::Return(modes));
    }

    void assertNumberOfModes(uint32_t num_modes) {
        std::vector<IWifiChip::ChipMode> modes;
        ASSERT_TRUE(chip_->getAvailableModes(&modes).isOk());
        // V2_Aware has 1 mode of operation.
        ASSERT_EQ(num_modes, modes.size());
    }

    void findModeAndConfigureForIfaceType(const IfaceConcurrencyType& type) {
        // This should be aligned with kInvalidModeId in wifi_chip.cpp
        int32_t mode_id = INT32_MAX;
        std::vector<IWifiChip::ChipMode> modes;
        ASSERT_TRUE(chip_->getAvailableModes(&modes).isOk());

        for (const auto& mode : modes) {
            for (const auto& combination : mode.availableCombinations) {
                for (const auto& limit : combination.limits) {
                    if (limit.types.end() !=
                        std::find(limit.types.begin(), limit.types.end(), type)) {
                        mode_id = mode.id;
                    }
                }
            }
        }

        ASSERT_NE(INT32_MAX, mode_id);
        ASSERT_TRUE(chip_->configureChip(mode_id).isOk());
    }

    // Returns an empty string on error.
    std::string createIface(const IfaceType& type) {
        std::string iface_name;
        if (type == IfaceType::AP) {
            std::shared_ptr<IWifiApIface> iface;
            if (!chip_->createApIface(&iface).isOk()) {
                return "";
            }
            EXPECT_NE(iface.get(), nullptr);
            EXPECT_TRUE(iface->getName(&iface_name).isOk());
        } else if (type == IfaceType::NAN_IFACE) {
            std::shared_ptr<IWifiNanIface> iface;
            if (!chip_->createNanIface(&iface).isOk()) {
                return "";
            }
            EXPECT_NE(iface.get(), nullptr);
            EXPECT_TRUE(iface->getName(&iface_name).isOk());
        } else if (type == IfaceType::P2P) {
            std::shared_ptr<IWifiP2pIface> iface;
            if (!chip_->createP2pIface(&iface).isOk()) {
                return "";
            }
            EXPECT_NE(iface.get(), nullptr);
            EXPECT_TRUE(iface->getName(&iface_name).isOk());
        } else if (type == IfaceType::STA) {
            std::shared_ptr<IWifiStaIface> iface;
            if (!chip_->createStaIface(&iface).isOk()) {
                return "";
            }
            EXPECT_NE(iface.get(), nullptr);
            EXPECT_TRUE(iface->getName(&iface_name).isOk());
        }
        return iface_name;
    }

    void removeIface(const IfaceType& type, const std::string& iface_name) {
        if (type == IfaceType::AP) {
            ASSERT_TRUE(chip_->removeApIface(iface_name).isOk());
        } else if (type == IfaceType::NAN_IFACE) {
            ASSERT_TRUE(chip_->removeNanIface(iface_name).isOk());
        } else if (type == IfaceType::P2P) {
            ASSERT_TRUE(chip_->removeP2pIface(iface_name).isOk());
        } else if (type == IfaceType::STA) {
            ASSERT_TRUE(chip_->removeStaIface(iface_name).isOk());
        }
    }

    bool createRttController() {
        std::shared_ptr<IWifiRttController> rtt_controller;
        auto status = chip_->createRttController(nullptr, &rtt_controller);
        return status.isOk();
    }

    static void subsystemRestartHandler(const std::string& /*error*/) {}

    std::shared_ptr<WifiChip> chip_;
    int chip_id_ = kFakeChipId;
    legacy_hal::wifi_hal_fn fake_func_table_;
    std::shared_ptr<NiceMock<::android::wifi_system::MockInterfaceTool>> iface_tool_{
            new NiceMock<::android::wifi_system::MockInterfaceTool>};
    std::shared_ptr<NiceMock<legacy_hal::MockWifiLegacyHal>> legacy_hal_{
            new NiceMock<legacy_hal::MockWifiLegacyHal>(iface_tool_, fake_func_table_, true)};
    std::shared_ptr<NiceMock<mode_controller::MockWifiModeController>> mode_controller_{
            new NiceMock<mode_controller::MockWifiModeController>};
    std::shared_ptr<NiceMock<iface_util::MockWifiIfaceUtil>> iface_util_{
            new NiceMock<iface_util::MockWifiIfaceUtil>(iface_tool_, legacy_hal_)};
    std::shared_ptr<NiceMock<feature_flags::MockWifiFeatureFlags>> feature_flags_{
            new NiceMock<feature_flags::MockWifiFeatureFlags>};

  public:
    void SetUp() override {
        chip_ = WifiChip::create(chip_id_, true, legacy_hal_, mode_controller_, iface_util_,
                                 feature_flags_, subsystemRestartHandler, true);

        EXPECT_CALL(*mode_controller_, changeFirmwareMode(testing::_))
                .WillRepeatedly(testing::Return(true));
        EXPECT_CALL(*legacy_hal_, start())
                .WillRepeatedly(testing::Return(legacy_hal::WIFI_SUCCESS));
        // Vendor HAL does not override the name by default.
        EXPECT_CALL(*legacy_hal_, getSupportedIfaceName(testing::_, testing::_))
                .WillRepeatedly(testing::Return(legacy_hal::WIFI_ERROR_UNKNOWN));
    }

    void TearDown() override {
        // Restore default system iface names (This should ideally be using a
        // mock).
        property_set("wifi.interface", "wlan0");
        property_set("wifi.concurrent.interface", "wlan1");
        property_set("wifi.aware.interface", nullptr);
    }
};

////////// V1 Iface Combinations ////////////
// Mode 1 - STA + P2P
// Mode 2 - AP
class WifiChipV1IfaceCombinationTest : public WifiChipTest {
  public:
    void SetUp() override {
        setupV1IfaceCombination();
        WifiChipTest::SetUp();
        // V1 has 2 modes of operation.
        assertNumberOfModes(2);
    }
};

TEST_F(WifiChipV1IfaceCombinationTest, StaMode_CreateSta_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_EQ(createIface(IfaceType::STA), "wlan0");
}

TEST_F(WifiChipV1IfaceCombinationTest, StaMode_CreateP2p_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::P2P).empty());
}

TEST_F(WifiChipV1IfaceCombinationTest, StaMode_CreateNan_ShouldFail) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_TRUE(createIface(IfaceType::NAN_IFACE).empty());
}

TEST_F(WifiChipV1IfaceCombinationTest, StaMode_CreateAp_ShouldFail) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_TRUE(createIface(IfaceType::AP).empty());
}

TEST_F(WifiChipV1IfaceCombinationTest, StaMode_CreateStaP2p_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    ASSERT_FALSE(createIface(IfaceType::P2P).empty());
}

TEST_F(WifiChipV1IfaceCombinationTest, ApMode_CreateAp_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    ASSERT_EQ(createIface(IfaceType::AP), "wlan0");
}

TEST_F(WifiChipV1IfaceCombinationTest, ApMode_CreateSta_ShouldFail) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    ASSERT_TRUE(createIface(IfaceType::STA).empty());
}

TEST_F(WifiChipV1IfaceCombinationTest, ApMode_CreateP2p_ShouldFail) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    ASSERT_TRUE(createIface(IfaceType::STA).empty());
}

TEST_F(WifiChipV1IfaceCombinationTest, ApMode_CreateNan_ShouldFail) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    ASSERT_TRUE(createIface(IfaceType::NAN_IFACE).empty());
}

////////// V1 + Aware Iface Combinations ////////////
// Mode 1 - STA + P2P/NAN
// Mode 2 - AP
class WifiChipV1_AwareIfaceCombinationTest : public WifiChipTest {
  public:
    void SetUp() override {
        setupV1_AwareIfaceCombination();
        WifiChipTest::SetUp();
        // V1_Aware has 2 modes of operation.
        assertNumberOfModes(2u);
    }
};

TEST_F(WifiChipV1_AwareIfaceCombinationTest, StaMode_CreateSta_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_EQ(createIface(IfaceType::STA), "wlan0");
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, StaMode_CreateP2p_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::P2P).empty());
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, StaMode_CreateNan_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::NAN_IFACE).empty());
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, StaMode_CreateAp_ShouldFail) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_TRUE(createIface(IfaceType::AP).empty());
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, StaMode_CreateStaP2p_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    ASSERT_FALSE(createIface(IfaceType::P2P).empty());
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, StaMode_CreateStaNan_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    ASSERT_FALSE(createIface(IfaceType::NAN_IFACE).empty());
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, StaMode_CreateStaP2PNan_ShouldFail) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    ASSERT_FALSE(createIface(IfaceType::P2P).empty());
    ASSERT_TRUE(createIface(IfaceType::NAN_IFACE).empty());
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, StaMode_CreateStaNan_AfterP2pRemove_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    std::string p2p_iface_name = createIface(IfaceType::P2P);
    ASSERT_FALSE(p2p_iface_name.empty());
    ASSERT_TRUE(createIface(IfaceType::NAN_IFACE).empty());

    // After removing P2P iface, NAN iface creation should succeed.
    removeIface(IfaceType::P2P, p2p_iface_name);
    ASSERT_FALSE(createIface(IfaceType::NAN_IFACE).empty());
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, StaMode_CreateStaP2p_AfterNanRemove_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    std::string nan_iface_name = createIface(IfaceType::NAN_IFACE);
    ASSERT_FALSE(nan_iface_name.empty());
    ASSERT_TRUE(createIface(IfaceType::P2P).empty());

    // After removing NAN iface, P2P iface creation should succeed.
    removeIface(IfaceType::NAN_IFACE, nan_iface_name);
    ASSERT_FALSE(createIface(IfaceType::P2P).empty());
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, ApMode_CreateAp_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    ASSERT_EQ(createIface(IfaceType::AP), "wlan0");
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, ApMode_CreateSta_ShouldFail) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    ASSERT_TRUE(createIface(IfaceType::STA).empty());
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, ApMode_CreateP2p_ShouldFail) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    ASSERT_TRUE(createIface(IfaceType::STA).empty());
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, ApMode_CreateNan_ShouldFail) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    ASSERT_TRUE(createIface(IfaceType::NAN_IFACE).empty());
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, RttControllerFlowStaModeNoSta) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_TRUE(createRttController());
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, RttControllerFlowStaModeWithSta) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    ASSERT_TRUE(createRttController());
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, RttControllerFlowApToSta) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    std::string ap_iface_name = createIface(IfaceType::AP);
    ASSERT_FALSE(ap_iface_name.empty());
    ASSERT_FALSE(createRttController());

    removeIface(IfaceType::AP, ap_iface_name);

    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_TRUE(createRttController());
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, SelectTxScenarioWithOnlySta) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_EQ(createIface(IfaceType::STA), "wlan0");
    EXPECT_CALL(*legacy_hal_, selectTxPowerScenario("wlan0", testing::_))
            .WillOnce(testing::Return(legacy_hal::WIFI_SUCCESS));
    ASSERT_TRUE(chip_->selectTxPowerScenario(IWifiChip::TxPowerScenario::ON_HEAD_CELL_OFF).isOk());
}

TEST_F(WifiChipV1_AwareIfaceCombinationTest, SelectTxScenarioWithOnlyAp) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    ASSERT_EQ(createIface(IfaceType::AP), "wlan0");
    EXPECT_CALL(*legacy_hal_, selectTxPowerScenario("wlan0", testing::_))
            .WillOnce(testing::Return(legacy_hal::WIFI_SUCCESS));
    ASSERT_TRUE(chip_->selectTxPowerScenario(IWifiChip::TxPowerScenario::ON_HEAD_CELL_OFF).isOk());
}

////////// V2 + Aware Iface Combinations ////////////
// Mode 1 - STA + STA/AP
//        - STA + P2P/NAN
class WifiChipV2_AwareIfaceCombinationTest : public WifiChipTest {
  public:
    void SetUp() override {
        setupV2_AwareIfaceCombination();
        WifiChipTest::SetUp();
        // V2_Aware has 1 mode of operation.
        assertNumberOfModes(1u);
    }
};

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateSta_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_EQ(createIface(IfaceType::STA), "wlan0");
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateP2p_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::P2P).empty());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateNan_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::NAN_IFACE).empty());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateAp_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_EQ(createIface(IfaceType::AP), "wlan1");
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateStaSta_ShouldFail) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    ASSERT_EQ(createIface(IfaceType::STA), "wlan0");
    ASSERT_TRUE(createIface(IfaceType::STA).empty());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateStaAp_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    ASSERT_EQ(createIface(IfaceType::STA), "wlan0");
    ASSERT_EQ(createIface(IfaceType::AP), "wlan1");
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateApSta_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    ASSERT_EQ(createIface(IfaceType::AP), "wlan1");
    ASSERT_EQ(createIface(IfaceType::STA), "wlan0");
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateSta_AfterStaApRemove_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    std::string sta_iface_name = createIface(IfaceType::STA);
    ASSERT_FALSE(sta_iface_name.empty());
    std::string ap_iface_name = createIface(IfaceType::AP);
    ASSERT_FALSE(ap_iface_name.empty());

    ASSERT_TRUE(createIface(IfaceType::STA).empty());

    // After removing AP & STA iface, STA iface creation should succeed.
    removeIface(IfaceType::STA, sta_iface_name);
    removeIface(IfaceType::AP, ap_iface_name);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateStaP2p_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    ASSERT_FALSE(createIface(IfaceType::P2P).empty());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateStaNan_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    ASSERT_FALSE(createIface(IfaceType::NAN_IFACE).empty());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateStaP2PNan_ShouldFail) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    ASSERT_FALSE(createIface(IfaceType::P2P).empty());
    ASSERT_TRUE(createIface(IfaceType::NAN_IFACE).empty());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateStaNan_AfterP2pRemove_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    std::string p2p_iface_name = createIface(IfaceType::P2P);
    ASSERT_FALSE(p2p_iface_name.empty());
    ASSERT_TRUE(createIface(IfaceType::NAN_IFACE).empty());

    // After removing P2P iface, NAN iface creation should succeed.
    removeIface(IfaceType::P2P, p2p_iface_name);
    ASSERT_FALSE(createIface(IfaceType::NAN_IFACE).empty());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateStaP2p_AfterNanRemove_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    std::string nan_iface_name = createIface(IfaceType::NAN_IFACE);
    ASSERT_FALSE(nan_iface_name.empty());
    ASSERT_TRUE(createIface(IfaceType::P2P).empty());

    // After removing NAN iface, P2P iface creation should succeed.
    removeIface(IfaceType::NAN_IFACE, nan_iface_name);
    ASSERT_FALSE(createIface(IfaceType::P2P).empty());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateApNan_ShouldFail) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    ASSERT_FALSE(createIface(IfaceType::AP).empty());
    ASSERT_TRUE(createIface(IfaceType::NAN_IFACE).empty());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateApP2p_ShouldFail) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    ASSERT_FALSE(createIface(IfaceType::AP).empty());
    ASSERT_TRUE(createIface(IfaceType::P2P).empty());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, StaMode_CreateStaNan_AfterP2pRemove_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    std::string p2p_iface_name = createIface(IfaceType::P2P);
    ASSERT_FALSE(p2p_iface_name.empty());
    ASSERT_TRUE(createIface(IfaceType::NAN_IFACE).empty());

    // After removing P2P iface, NAN iface creation should succeed.
    removeIface(IfaceType::P2P, p2p_iface_name);
    ASSERT_FALSE(createIface(IfaceType::NAN_IFACE).empty());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, StaMode_CreateStaP2p_AfterNanRemove_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    std::string nan_iface_name = createIface(IfaceType::NAN_IFACE);
    ASSERT_FALSE(nan_iface_name.empty());
    ASSERT_TRUE(createIface(IfaceType::P2P).empty());

    // After removing NAN iface, P2P iface creation should succeed.
    removeIface(IfaceType::NAN_IFACE, nan_iface_name);
    ASSERT_FALSE(createIface(IfaceType::P2P).empty());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateStaAp_EnsureDifferentIfaceNames) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    std::string sta_iface_name = createIface(IfaceType::STA);
    std::string ap_iface_name = createIface(IfaceType::AP);
    ASSERT_FALSE(sta_iface_name.empty());
    ASSERT_FALSE(ap_iface_name.empty());
    ASSERT_NE(sta_iface_name, ap_iface_name);
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, RttControllerFlowStaModeNoSta) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_TRUE(createRttController());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, RttControllerFlowStaModeWithSta) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    ASSERT_TRUE(createRttController());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, RttControllerFlow) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    ASSERT_FALSE(createIface(IfaceType::AP).empty());
    ASSERT_TRUE(createRttController());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, SelectTxScenarioWithOnlySta) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_EQ(createIface(IfaceType::STA), "wlan0");
    EXPECT_CALL(*legacy_hal_, selectTxPowerScenario("wlan0", testing::_))
            .WillOnce(testing::Return(legacy_hal::WIFI_SUCCESS));
    ASSERT_TRUE(chip_->selectTxPowerScenario(IWifiChip::TxPowerScenario::ON_HEAD_CELL_OFF).isOk());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, SelectTxScenarioWithOnlyAp) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::AP);
    ASSERT_EQ(createIface(IfaceType::AP), "wlan1");
    EXPECT_CALL(*legacy_hal_, selectTxPowerScenario("wlan1", testing::_))
            .WillOnce(testing::Return(legacy_hal::WIFI_SUCCESS));
    ASSERT_TRUE(chip_->selectTxPowerScenario(IWifiChip::TxPowerScenario::ON_HEAD_CELL_OFF).isOk());
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, InvalidateAndRemoveNanOnStaRemove) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_EQ(createIface(IfaceType::STA), "wlan0");

    // Create NAN iface
    ASSERT_EQ(createIface(IfaceType::NAN_IFACE), "wlan0");

    // We should have 1 nan iface.
    std::vector<std::string> iface_names;
    ASSERT_TRUE(chip_->getNanIfaceNames(&iface_names).isOk());
    ASSERT_EQ(iface_names.size(), 1u);
    ASSERT_EQ(iface_names[0], "wlan0");

    // Retrieve the nan iface object.
    std::shared_ptr<IWifiNanIface> nan_iface;
    ASSERT_TRUE(chip_->getNanIface("wlan0", &nan_iface).isOk());
    ASSERT_NE(nan_iface.get(), nullptr);

    // Remove the STA iface. We should have 0 nan ifaces now.
    removeIface(IfaceType::STA, "wlan0");
    ASSERT_TRUE(chip_->getNanIfaceNames(&iface_names).isOk());
    ASSERT_EQ(iface_names.size(), 0u);

    // Any operation on the nan iface object should now return an error.
    std::string name;
    auto status = nan_iface->getName(&name);
    ASSERT_EQ(status.getServiceSpecificError(),
              static_cast<int32_t>(WifiStatusCode::ERROR_WIFI_IFACE_INVALID));
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, InvalidateAndRemoveRttControllerOnStaRemove) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_EQ(createIface(IfaceType::STA), "wlan0");

    // Create RTT controller
    std::shared_ptr<IWifiRttController> rtt_controller;
    ASSERT_TRUE(chip_->createRttController(nullptr, &rtt_controller).isOk());

    // Remove the STA iface.
    removeIface(IfaceType::STA, "wlan0");

    // Any operation on the rtt controller object should now return an error.
    std::shared_ptr<IWifiStaIface> bound_iface;
    auto status = rtt_controller->getBoundIface(&bound_iface);
    ASSERT_EQ(status.getServiceSpecificError(),
              static_cast<int32_t>(WifiStatusCode::ERROR_WIFI_RTT_CONTROLLER_INVALID));
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateNanWithSharedNanIface) {
    property_set("wifi.aware.interface", nullptr);
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_EQ(createIface(IfaceType::STA), "wlan0");
    ASSERT_EQ(createIface(IfaceType::NAN_IFACE), "wlan0");
    removeIface(IfaceType::NAN_IFACE, "wlan0");
    EXPECT_CALL(*iface_util_, setUpState(testing::_, testing::_)).Times(0);
}

TEST_F(WifiChipV2_AwareIfaceCombinationTest, CreateNanWithDedicatedNanIface) {
    property_set("wifi.aware.interface", "aware0");
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_EQ(createIface(IfaceType::STA), "wlan0");
    EXPECT_CALL(*iface_util_, ifNameToIndex("aware0")).WillOnce(testing::Return(4));
    EXPECT_CALL(*iface_util_, setUpState("aware0", true)).WillOnce(testing::Return(true));
    ASSERT_EQ(createIface(IfaceType::NAN_IFACE), "aware0");

    EXPECT_CALL(*iface_util_, setUpState("aware0", false)).WillOnce(testing::Return(true));
    removeIface(IfaceType::NAN_IFACE, "aware0");
}

////////// V1 Iface Combinations when AP creation is disabled //////////
class WifiChipV1_AwareDisabledApIfaceCombinationTest : public WifiChipTest {
  public:
    void SetUp() override {
        setupV1_AwareDisabledApIfaceCombination();
        WifiChipTest::SetUp();
    }
};

TEST_F(WifiChipV1_AwareDisabledApIfaceCombinationTest, StaMode_CreateSta_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    ASSERT_TRUE(createIface(IfaceType::AP).empty());
}

////////// V2 Iface Combinations when AP creation is disabled //////////
class WifiChipV2_AwareDisabledApIfaceCombinationTest : public WifiChipTest {
  public:
    void SetUp() override {
        setupV2_AwareDisabledApIfaceCombination();
        WifiChipTest::SetUp();
    }
};

TEST_F(WifiChipV2_AwareDisabledApIfaceCombinationTest, CreateSta_ShouldSucceed) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    ASSERT_TRUE(createIface(IfaceType::AP).empty());
}

////////// Hypothetical Iface Combination with multiple ifaces //////////
class WifiChip_MultiIfaceTest : public WifiChipTest {
  public:
    void SetUp() override {
        setup_MultiIfaceCombination();
        WifiChipTest::SetUp();
    }
};

TEST_F(WifiChip_MultiIfaceTest, Create3Sta) {
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    ASSERT_FALSE(createIface(IfaceType::STA).empty());
    ASSERT_TRUE(createIface(IfaceType::STA).empty());
}

TEST_F(WifiChip_MultiIfaceTest, CreateStaWithDefaultNames) {
    property_set("wifi.interface.0", "");
    property_set("wifi.interface.1", "");
    property_set("wifi.interface.2", "");
    property_set("wifi.interface", "");
    property_set("wifi.concurrent.interface", "");
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_EQ(createIface(IfaceType::STA), "wlan0");
    ASSERT_EQ(createIface(IfaceType::STA), "wlan1");
    ASSERT_EQ(createIface(IfaceType::STA), "wlan2");
}

TEST_F(WifiChip_MultiIfaceTest, CreateStaWithCustomNames) {
    property_set("wifi.interface.0", "test0");
    property_set("wifi.interface.1", "test1");
    property_set("wifi.interface.2", "test2");
    property_set("wifi.interface", "bad0");
    property_set("wifi.concurrent.interface", "bad1");
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_EQ(createIface(IfaceType::STA), "bad0");
    ASSERT_EQ(createIface(IfaceType::STA), "bad1");
    ASSERT_EQ(createIface(IfaceType::STA), "test2");
}

TEST_F(WifiChip_MultiIfaceTest, CreateStaWithCustomAltNames) {
    property_set("wifi.interface.0", "");
    property_set("wifi.interface.1", "");
    property_set("wifi.interface.2", "");
    property_set("wifi.interface", "testA0");
    property_set("wifi.concurrent.interface", "testA1");
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    ASSERT_EQ(createIface(IfaceType::STA), "testA0");
    ASSERT_EQ(createIface(IfaceType::STA), "testA1");
    ASSERT_EQ(createIface(IfaceType::STA), "wlan2");
}

TEST_F(WifiChip_MultiIfaceTest, CreateApStartsWithIdx1) {
    // WifiChip_MultiIfaceTest iface combo: STAx3 + APx1
    // When the HAL support dual STAs, AP should start with idx 2.
    findModeAndConfigureForIfaceType(IfaceConcurrencyType::STA);
    // First AP will be slotted to wlan1.
    ASSERT_EQ(createIface(IfaceType::AP), "wlan2");
    // First STA will be slotted to wlan0.
    ASSERT_EQ(createIface(IfaceType::STA), "wlan0");
    // All further STA will be slotted to the remaining free indices.
    ASSERT_EQ(createIface(IfaceType::STA), "wlan1");
    ASSERT_EQ(createIface(IfaceType::STA), "wlan3");
}

}  // namespace wifi
}  // namespace hardware
}  // namespace android
}  // namespace aidl
