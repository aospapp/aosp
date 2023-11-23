/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.devicepolicy.cts;

import static android.content.pm.PackageManager.FEATURE_AUTOMOTIVE;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Build.VERSION_CODES.O;

import static com.android.bedstead.harrier.Defaults.DEFAULT_PASSWORD;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.Postsubmit;
import com.android.bedstead.harrier.annotations.RequireDoesNotHaveFeature;
import com.android.bedstead.harrier.annotations.RequireTargetSdkVersion;
import com.android.bedstead.harrier.annotations.enterprise.CanSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.CannotSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.PolicyAppliesTest;
import com.android.bedstead.harrier.annotations.enterprise.PolicyDoesNotApplyTest;
import com.android.bedstead.harrier.policies.DeprecatedResetPassword;
import com.android.bedstead.harrier.policies.FailedPasswordAttempts;
import com.android.bedstead.harrier.policies.PasswordExpirationTimeout;
import com.android.bedstead.harrier.policies.StrongAuthTimeout;
import com.android.bedstead.nene.TestApis;
import com.android.compatibility.common.util.ApiTest;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.runner.RunWith;

@RunWith(BedsteadJUnit4.class)
public final class PasswordTest {

    private static final long TIMEOUT = 51234;

    @ClassRule @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    @CannotSetPolicyTest(policy = PasswordExpirationTimeout.class, includeNonDeviceAdminStates = false)
    @Postsubmit(reason = "New test")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#setPasswordExpirationTimeout")
    public void setPasswordExpirationTimeout_notPermitted_throwsException() {
        assertThrows(SecurityException.class, () -> sDeviceState.dpc().devicePolicyManager()
                .setPasswordExpirationTimeout(sDeviceState.dpc().componentName(), TIMEOUT));
    }

    @PolicyAppliesTest(policy = PasswordExpirationTimeout.class)
    @Postsubmit(reason = "New test")
    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setPasswordExpirationTimeout",
            "android.app.admin.DevicePolicyManager#getPasswordExpirationTimeout"})
    public void setPasswordExpirationTimeout_passwordExpirationTimeoutIsSet() {
        long originalTimeout = sDeviceState.dpc().devicePolicyManager()
                .getPasswordExpirationTimeout(sDeviceState.dpc().componentName());

        try {
            sDeviceState.dpc().devicePolicyManager()
                    .setPasswordExpirationTimeout(sDeviceState.dpc().componentName(), TIMEOUT);

            assertThat(TestApis.devicePolicy().getPasswordExpirationTimeout()).isEqualTo(TIMEOUT);
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setPasswordExpirationTimeout(
                            sDeviceState.dpc().componentName(), originalTimeout);
        }
    }

    @PolicyDoesNotApplyTest(policy = PasswordExpirationTimeout.class)
    @Postsubmit(reason = "New test")
    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setPasswordExpirationTimeout",
            "android.app.admin.DevicePolicyManager#getPasswordExpirationTimeout"})
    public void setPasswordExpirationTimeout_doesNotApply_passwordExpirationTimeoutIsNotSet() {
        long originalTimeout = sDeviceState.dpc().devicePolicyManager()
                .getPasswordExpirationTimeout(sDeviceState.dpc().componentName());

        try {
            sDeviceState.dpc().devicePolicyManager()
                    .setPasswordExpirationTimeout(sDeviceState.dpc().componentName(), TIMEOUT);

            assertThat(TestApis.devicePolicy().getPasswordExpirationTimeout())
                    .isNotEqualTo(TIMEOUT);
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setPasswordExpirationTimeout(
                            sDeviceState.dpc().componentName(), originalTimeout);
        }
    }

    // TODO: Test effect of PasswordExpirationTimeout

    @CannotSetPolicyTest(policy = FailedPasswordAttempts.class, includeNonDeviceAdminStates = false)
    @Postsubmit(reason = "New test")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#getCurrentFailedPasswordAttempts")
    public void getCurrentFailedPasswordAttempts_notPermitted_throwsException() {
        assertThrows(SecurityException.class, () -> sDeviceState.dpc()
                .devicePolicyManager().getCurrentFailedPasswordAttempts());
    }

    @CanSetPolicyTest(policy = FailedPasswordAttempts.class)
    @Postsubmit(reason = "New test")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#getCurrentFailedPasswordAttempts")
    public void getCurrentFailedPasswordAttempts_permitted_doesNotThrow() {
        sDeviceState.dpc().devicePolicyManager().getCurrentFailedPasswordAttempts();
    }

    // TODO: Create an interactive test to test functionality of getCurrentFailedPasswordAttempts

    @CannotSetPolicyTest(policy = StrongAuthTimeout.class, includeNonDeviceAdminStates = false)
    @Postsubmit(reason = "New test")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#setRequiredStrongAuthTimeout")
    public void setRequiredStrongAuthTimeout_notPermitted_throwsException() {
        assertThrows(SecurityException.class, () -> sDeviceState.dpc().devicePolicyManager()
                .setRequiredStrongAuthTimeout(sDeviceState.dpc().componentName(), TIMEOUT));
    }

    @PolicyAppliesTest(policy = StrongAuthTimeout.class)
    @Postsubmit(reason = "New test")
    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setRequiredStrongAuthTimeout",
            "android.app.admin.DevicePolicyManager#getRequiredStrongAuthTimeout"})
    public void setRequiredStrongAuthTimeout_strongAuthTimeoutIsSet() {
        long originalTimeout = sDeviceState.dpc().devicePolicyManager()
                .getPasswordExpirationTimeout(sDeviceState.dpc().componentName());

        try {
            sDeviceState.dpc().devicePolicyManager()
                    .setPasswordExpirationTimeout(sDeviceState.dpc().componentName(), TIMEOUT);

            assertThat(TestApis.devicePolicy().getPasswordExpirationTimeout()).isEqualTo(TIMEOUT);
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setPasswordExpirationTimeout(
                            sDeviceState.dpc().componentName(), originalTimeout);
        }
    }

    @PolicyDoesNotApplyTest(policy = StrongAuthTimeout.class)
    @Postsubmit(reason = "New test")
    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setRequiredStrongAuthTimeout",
            "android.app.admin.DevicePolicyManager#getRequiredStrongAuthTimeout"})
    public void setRequiredStrongAuthTimeout_doesNotApply_strongAuthTimeoutIsNotSet() {
        long originalTimeout = sDeviceState.dpc().devicePolicyManager()
                .getRequiredStrongAuthTimeout(sDeviceState.dpc().componentName());

        try {
            sDeviceState.dpc().devicePolicyManager()
                    .setRequiredStrongAuthTimeout(sDeviceState.dpc().componentName(), TIMEOUT);

            assertThat(TestApis.devicePolicy()
                    .getRequiredStrongAuthTimeout()).isNotEqualTo(TIMEOUT);
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setRequiredStrongAuthTimeout(
                            sDeviceState.dpc().componentName(), originalTimeout);
        }
    }

    @RequireDoesNotHaveFeature(FEATURE_AUTOMOTIVE)
    @RequireTargetSdkVersion(max = N)
    @PolicyAppliesTest(policy = DeprecatedResetPassword.class)
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#resetPassword")
    public void resetPassword_targetBeforeN_returnsFalse() {
        assertThat(sDeviceState.dpc()
                .devicePolicyManager().resetPassword(DEFAULT_PASSWORD, /* flags= */ 0)).isFalse();
    }

    @RequireDoesNotHaveFeature(FEATURE_AUTOMOTIVE)
    @RequireTargetSdkVersion(min = O)
    @PolicyAppliesTest(policy = DeprecatedResetPassword.class)
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#resetPassword")
    public void resetPassword_targetAfterO_throwsSecurityException() {
        assertThrows(SecurityException.class,
                () -> sDeviceState.dpc().devicePolicyManager()
                        .resetPassword(DEFAULT_PASSWORD, /* flags= */ 0));
    }
}
