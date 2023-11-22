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

#include "DeviceManifestTest.h"

#include <vintf/VintfObject.h>
#include "SingleManifestTest.h"

namespace android {
namespace vintf {
namespace testing {

void DeviceManifestTest::SetUp() {
  VtsTrebleVintfTestBase::SetUp();

  vendor_manifest_ = VintfObject::GetDeviceHalManifest();
  ASSERT_NE(vendor_manifest_, nullptr)
      << "Failed to get vendor HAL manifest." << endl;
}

// Tests that Shipping FCM Version in the device manifest is at least the
// minimum Shipping FCM Version as required by Shipping API level.
TEST_F(DeviceManifestTest, ShippingFcmVersion) {
  uint64_t shipping_api_level = GetShippingApiLevel();
  ASSERT_NE(shipping_api_level, 0u)
      << "Device's shipping API level cannot be determined.";

  Level shipping_fcm_version = VintfObject::GetDeviceHalManifest()->level();
  if (shipping_fcm_version == Level::UNSPECIFIED) {
    // O / O-MR1 vendor image doesn't have shipping FCM version declared and
    // shipping FCM version is inferred from Shipping API level, hence it always
    // meets the requirement.
    return;
  }

  ASSERT_GE(shipping_api_level, kFcm2ApiLevelMap.begin()->first /* 25 */)
      << "Pre-N devices should not run this test.";

  auto it = kFcm2ApiLevelMap.find(shipping_api_level);
  ASSERT_TRUE(it != kFcm2ApiLevelMap.end())
      << "No launch requirement is set yet for Shipping API level "
      << shipping_api_level << ". Please update the test.";

  Level required_fcm_version = it->second;

  ASSERT_GE(shipping_fcm_version, required_fcm_version)
      << "Shipping API level == " << shipping_api_level
      << " requires Shipping FCM Version >= " << required_fcm_version
      << " (but is " << shipping_fcm_version << ")";
}

// Tests that deprecated HALs are not in the manifest, unless a higher,
// non-deprecated minor version is in the manifest.
TEST_F(DeviceManifestTest, NoDeprecatedHalsOnManifest) {
  string error;
  EXPECT_EQ(android::vintf::NO_DEPRECATED_HALS,
            VintfObject::CheckDeprecation(&error))
      << error;
}

// Tests that devices launching with Q support both gnss@2.0 and gnss@1.1 HALs
// or none. Since gnss@2.0 extends 1.1, this test is needed to workaround
// VINTF_ENFORCE_NO_UNUSED_HALS.
// TODO(b/121287858): Remove this test in R when this requirement is properly
// supported. Otherwise, it needs to be updated to reflect R version changes.
TEST_F(DeviceManifestTest, GnssHalVersionCompatibility) {
  const Level q_fcm_version = kFcm2ApiLevelMap.at(29 /* Q API level */);
  Level shipping_fcm_version = vendor_manifest_->level();
  if (shipping_fcm_version == Level::UNSPECIFIED ||
      shipping_fcm_version < q_fcm_version) {
    GTEST_SKIP();
  }

  ASSERT_EQ(shipping_fcm_version, q_fcm_version)
      << "Unsupported Shipping FCM Verson " << shipping_fcm_version;

  bool has_default_gnss_1_0 = vendor_manifest_->hasInstance(
      "android.hardware.gnss", {1, 0}, "IGnss", "default");
  bool has_default_gnss_2_0 = vendor_manifest_->hasInstance(
      "android.hardware.gnss", {2, 0}, "IGnss", "default");
  ASSERT_EQ(has_default_gnss_1_0, has_default_gnss_2_0)
      << "Devices launched with Android Q must support both gnss@2.0"
      << " and gnss@1.1 versions if gnss HAL package is present.";
}

static std::vector<HalManifestPtr> GetTestManifests() {
  return {
      VintfObject::GetDeviceHalManifest(),
  };
}

INSTANTIATE_TEST_CASE_P(DeviceManifest, SingleManifestTest,
                        ::testing::ValuesIn(GetTestManifests()));

}  // namespace testing
}  // namespace vintf
}  // namespace android
