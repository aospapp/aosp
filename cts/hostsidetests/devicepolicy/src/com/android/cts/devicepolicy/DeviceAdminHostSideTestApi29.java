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
package com.android.cts.devicepolicy;

/**
 * BaseDeviceAdminHostSideTest for device admin targeting API level 29.
 */
public class DeviceAdminHostSideTestApi29 extends DeviceAdminHostSideTestApi24 {
    @Override
    protected int getTargetApiVersion() {
        return 29;
    }

    /**
     * Test that we get expected SecurityExceptions for policies that were disallowed in version 29.
     */
    @Override
    public void testRunDeviceAdminTest() throws Exception {
        if (!mHasFeature) {
            return;
        }
        runTests(getDeviceAdminApkPackage(), "DeviceAdminWithEnterprisePoliciesBlockedTest");
    }

    /**
     * This test is no longer relevant once DA is disallowed from using password policies.
     */
    @Override
    public void testResetPassword_nycRestrictions() throws Exception {
        return;
    }

    /**
     * This test is no longer relevant since resetPassword() was deprecated in version 26.
     * Device Owner functionality is now tested in DeviceAndProfileOwnerTest.
     */
    @Override
    public void testRunDeviceOwnerPasswordTest() throws Exception {
        return;
    }
}
