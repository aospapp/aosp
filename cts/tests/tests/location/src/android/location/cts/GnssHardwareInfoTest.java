/*
 * Copyright (C) 2017 Google Inc.
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

package android.location.cts;

import android.location.LocationManager;
import android.util.Log;

/**
 * Test {@link LocationManager#getGnssHardwareModelName}.
 */
public class GnssHardwareInfoTest extends GnssTestCase {

  private static final String TAG = "GnssHardwareInfoTest";

  /**
   * Minimum plausible descriptive hardware model name length, e.g. "ABC1" for first GNSS version
   * ever shipped by ABC company.
   */
  private static final int MIN_HARDWARE_MODEL_NAME_LENGTH = 4;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    mTestLocationManager = new TestLocationManager(getContext());
  }

  /**
   * Verify GNSS hardware model year is reported as a valid, descriptive value.
   * Descriptive is limited to a character count, and not the older values.
   */
  public void testHardwareModelName() throws Exception {
    if (!TestUtils.deviceHasGpsFeature(getContext())) {
      return;
    }

    String gnssHardwareModelName =
        mTestLocationManager.getLocationManager().getGnssHardwareModelName();
    SoftAssert softAssert = new SoftAssert(TAG);
    softAssert.assertOrWarnTrue(/* strict= */ false, "gnssHardwareModelName must not be null",
            gnssHardwareModelName != null);
    if (gnssHardwareModelName != null) {
      assertTrue("gnssHardwareModelName must be descriptive - at least 4 characters long",
          gnssHardwareModelName.length() >= MIN_HARDWARE_MODEL_NAME_LENGTH);
    }
  }
}
