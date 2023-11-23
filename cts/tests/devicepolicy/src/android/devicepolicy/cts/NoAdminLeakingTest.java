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

package android.devicepolicy.cts;

import static android.content.pm.PackageManager.FEATURE_AUTOMOTIVE;

import static com.android.bedstead.remotedpc.RemoteDpc.DPC_COMPONENT_NAME;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.app.admin.RemoteDevicePolicyManager;
import android.content.ComponentName;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.Postsubmit;
import com.android.bedstead.harrier.annotations.RequireDoesNotHaveFeature;
import com.android.bedstead.harrier.annotations.enterprise.CanSetPolicyTest;
import com.android.bedstead.harrier.policies.LockscreenPolicyWithUnifiedChallenge;
import com.android.bedstead.harrier.policies.ScreenCaptureDisabled;
import com.android.bedstead.testapp.TestApp;
import com.android.bedstead.testapp.TestAppInstance;
import com.android.bedstead.testapp.TestAppProvider;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.BiConsumer;

/**
 * Test that DevicePolicyManager getters that accept "ComponentName who" argument don't allow a
 * different app to probe for admins when policy is set: those getters should only allow either
 * calls where "who" is null or "who" is not null and belongs to caller. SecurityExceptions that are
 * thrown otherwise shouldn't leak that data either.
 */
@RunWith(BedsteadJUnit4.class)
// Password policies aren't supported on automotive
@RequireDoesNotHaveFeature(FEATURE_AUTOMOTIVE)
public class NoAdminLeakingTest {
    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private static final TestAppProvider sTestAppProvider = new TestAppProvider();
    private static final TestApp sTestApp = sTestAppProvider.any();

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = LockscreenPolicyWithUnifiedChallenge.class)
    public void testPasswordQuality_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getPasswordQuality(who));
    }

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = LockscreenPolicyWithUnifiedChallenge.class)
    public void testPasswordMinimumLength_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getPasswordMinimumLength(who));
    }

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = LockscreenPolicyWithUnifiedChallenge.class)
    public void testPasswordMinimumLetters_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getPasswordMinimumLetters(who));
    }

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = LockscreenPolicyWithUnifiedChallenge.class)
    public void testPasswordMinimumNonLetter_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getPasswordMinimumNonLetter(who));
    }

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = LockscreenPolicyWithUnifiedChallenge.class)
    public void testPasswordMinimumLowerCase_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getPasswordMinimumLowerCase(who));
    }

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = LockscreenPolicyWithUnifiedChallenge.class)
    public void testPasswordMinimumUpperCase_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getPasswordMinimumUpperCase(who));
    }

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = LockscreenPolicyWithUnifiedChallenge.class)
    public void testPasswordMinimumNumeric_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getPasswordMinimumNumeric(who));
    }

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = LockscreenPolicyWithUnifiedChallenge.class)
    public void testPasswordMinimumSymbols_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getPasswordMinimumSymbols(who));
    }

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = LockscreenPolicyWithUnifiedChallenge.class)
    public void testPasswordHistoryLength_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getPasswordHistoryLength(who));
    }

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = LockscreenPolicyWithUnifiedChallenge.class)
    public void testPasswordExpiration_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getPasswordExpiration(who));
    }

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = LockscreenPolicyWithUnifiedChallenge.class)
    public void testPasswordExpirationTimeout_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getPasswordExpirationTimeout(who));
    }

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = LockscreenPolicyWithUnifiedChallenge.class)
    public void testMaximumFailedPasswordsForWipe_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getMaximumFailedPasswordsForWipe(who));
    }

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = LockscreenPolicyWithUnifiedChallenge.class)
    public void testMaximumTimeToLock_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getMaximumTimeToLock(who));
    }

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = LockscreenPolicyWithUnifiedChallenge.class)
    public void testRequiredStrongAuthTimeout_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getRequiredStrongAuthTimeout(who));
    }

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = ScreenCaptureDisabled.class)
    public void testScreenCaptureDisabled_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getScreenCaptureDisabled(who));
    }

    @Test
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = LockscreenPolicyWithUnifiedChallenge.class)
    public void testTrustAgentConfiguration_adminPolicyNotAvailableToNonAdmin() {
        assertOnlyAggregatePolicyAvailableToNonAdmin(
                (dpm, who) -> dpm.getTrustAgentConfiguration(who,
                        DPC_COMPONENT_NAME /* agent component, need to be non-null */));
    }

    // TODO(b/210996030): replace this with test method parametrization and separate "null" case.
    private void assertOnlyAggregatePolicyAvailableToNonAdmin(
            BiConsumer<RemoteDevicePolicyManager, ComponentName> accessor) {
        try (TestAppInstance testApp = sTestApp.install()) {
            // Invoking with null admin should not throw.
            accessor.accept(testApp.devicePolicyManager(), /* who= */ null);

            SecurityException adminPackageEx = null;
            try {
                // Requesting policy for an admin from a different app should throw.
                accessor.accept(testApp.devicePolicyManager(), DPC_COMPONENT_NAME);
                fail("Checking particular admin policy shouldn't be allowed for non admin");
            } catch (SecurityException e) {
                adminPackageEx = e;
            }

            ComponentName nonexistentComponent = new ComponentName("bad_pkg_123", "bad_clz_456");
            SecurityException nonexistentPackageEx = null;
            try {
                // Requesting policy for a nonexistent admin should throw.
                accessor.accept(testApp.devicePolicyManager(), nonexistentComponent);
                fail("Querying policy for non-existent admin should have thrown an exception");
            } catch (SecurityException e) {
                nonexistentPackageEx = e;
            }

            // Both exceptions should have the same message (except package name) to avoid revealing
            // admin existence.
            String adminMessage = adminPackageEx.getMessage()
                    .replace(DPC_COMPONENT_NAME.toString(), "");
            String nonexistentMessage = nonexistentPackageEx.getMessage()
                    .replace(nonexistentComponent.toString(), "");
            assertThat(adminMessage).isEqualTo(nonexistentMessage);
        }
    }
}
