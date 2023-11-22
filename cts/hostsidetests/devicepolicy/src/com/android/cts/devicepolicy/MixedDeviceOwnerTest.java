/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static com.android.cts.devicepolicy.metrics.DevicePolicyEventLogVerifier.assertMetricsLogged;

import android.stats.devicepolicy.EventId;

import com.android.cts.devicepolicy.metrics.DevicePolicyEventWrapper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Set of tests for device owner use cases that also apply to profile owners.
 * Tests that should be run identically in both cases are added in DeviceAndProfileOwnerTest.
 */
public class MixedDeviceOwnerTest extends DeviceAndProfileOwnerTest {

    private static final String DELEGATION_NETWORK_LOGGING = "delegation-network-logging";

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        if (mHasFeature) {
            mUserId = mPrimaryUserId;

            installAppAsUser(DEVICE_ADMIN_APK, mUserId);
            if (!setDeviceOwner(
                    DEVICE_ADMIN_PKG + "/" + ADMIN_RECEIVER_TEST_CLASS, mUserId,
                    /*expectFailure*/ false)) {
                removeAdmin(DEVICE_ADMIN_PKG + "/" + ADMIN_RECEIVER_TEST_CLASS, mUserId);
                getDevice().uninstallPackage(DEVICE_ADMIN_PKG);
                fail("Failed to set device owner");
            }
        }
    }

    @Override
    protected void tearDown() throws Exception {
        if (mHasFeature) {
            assertTrue("Failed to remove device owner",
                    removeAdmin(DEVICE_ADMIN_PKG + "/" + ADMIN_RECEIVER_TEST_CLASS, mUserId));
        }
        super.tearDown();
    }

    public void testDelegatedCertInstallerDeviceIdAttestation() throws Exception {
        if (!mHasFeature) {
            return;
        }

        setUpDelegatedCertInstallerAndRunTests(() ->
                runDeviceTestsAsUser("com.android.cts.certinstaller",
                        ".DelegatedDeviceIdAttestationTest",
                        "testGenerateKeyPairWithDeviceIdAttestationExpectingSuccess", mUserId));
    }

    @Override
    Map<String, DevicePolicyEventWrapper[]> getAdditionalDelegationTests() {
        final Map<String, DevicePolicyEventWrapper[]> result = new HashMap<>();
        DevicePolicyEventWrapper[] expectedMetrics = new DevicePolicyEventWrapper[] {
                new DevicePolicyEventWrapper.Builder(EventId.SET_NETWORK_LOGGING_ENABLED_VALUE)
                        .setAdminPackageName(DELEGATE_APP_PKG)
                        .setBoolean(true)
                        .setInt(1)
                        .build(),
                new DevicePolicyEventWrapper.Builder(EventId.RETRIEVE_NETWORK_LOGS_VALUE)
                        .setAdminPackageName(DELEGATE_APP_PKG)
                        .setBoolean(true)
                        .build(),
                new DevicePolicyEventWrapper.Builder(EventId.SET_NETWORK_LOGGING_ENABLED_VALUE)
                        .setAdminPackageName(DELEGATE_APP_PKG)
                        .setBoolean(true)
                        .setInt(0)
                        .build(),
        };
        result.put(".NetworkLoggingDelegateTest", expectedMetrics);
        return result;
    }

    @Override
    List<String> getAdditionalDelegationScopes() {
        final List<String> result = new ArrayList<>();
        result.add(DELEGATION_NETWORK_LOGGING);
        return result;
    }
}
