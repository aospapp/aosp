/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.cts.devicepolicy;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Set of tests for device owner use cases that also apply to profile owners.
 * Tests that should be run identically in both cases are added in DeviceAndProfileOwnerTestApi25.
 */
public final class MixedDeviceOwnerTestApi25 extends DeviceAndProfileOwnerTestApi25 {

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mUserId = mPrimaryUserId;

        installDeviceOwnerApp(DEVICE_ADMIN_APK);
        if (!setDeviceOwner(
                DEVICE_ADMIN_PKG + "/" + ADMIN_RECEIVER_TEST_CLASS, mDeviceOwnerUserId,
                /*expectFailure*/ false)) {
            removeAdmin(DEVICE_ADMIN_PKG + "/" + ADMIN_RECEIVER_TEST_CLASS, mDeviceOwnerUserId);
            getDevice().uninstallPackage(DEVICE_ADMIN_PKG);
            fail("Failed to set device owner");
        }
    }

    @Override
    public void tearDown() throws Exception {
        assertTrue("Failed to remove device owner",
                removeAdmin(DEVICE_ADMIN_PKG + "/" + ADMIN_RECEIVER_TEST_CLASS, mUserId));

        super.tearDown();
    }

    // All tests for this class are defined in DeviceAndProfileOwnerTestApi25
}
