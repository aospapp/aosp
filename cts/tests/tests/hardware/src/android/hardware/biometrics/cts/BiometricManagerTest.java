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

package android.hardware.biometrics.cts;

import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.platform.test.annotations.Presubmit;
import android.test.AndroidTestCase;

/**
 * Basic test cases for BiometricManager
 */
public class BiometricManagerTest extends AndroidTestCase {

    private BiometricManager mBiometricManager;
    private boolean mHasBiometric;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        PackageManager pm = getContext().getPackageManager();

        mHasBiometric |= pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
        mHasBiometric |= pm.hasSystemFeature(PackageManager.FEATURE_FACE);
        mHasBiometric |= pm.hasSystemFeature(PackageManager.FEATURE_IRIS);

        mBiometricManager = getContext().getSystemService(BiometricManager.class);
    }

    @Presubmit
    public void test_canAuthenticate() {
        if (!mHasBiometric) {
            assertTrue(mBiometricManager.canAuthenticate()
                    == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE);
        } else {
            // No biometrics are enrolled. CTSVerifier should test the other error cases.
            assertTrue(mBiometricManager.canAuthenticate()
                    == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED);
        }
    }
}
