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

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.UserType;
import com.android.bedstead.harrier.annotations.EnsureHasAdditionalUser;
import com.android.bedstead.harrier.annotations.EnsureHasNoAdditionalUser;
import com.android.bedstead.harrier.annotations.Postsubmit;
import com.android.bedstead.harrier.annotations.enterprise.CanSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.CannotSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasProfileOwner;
import com.android.bedstead.harrier.policies.GlobalSecurityLogging;
import com.android.bedstead.harrier.policies.SecurityLogging;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.exceptions.NeneException;
import com.android.bedstead.nene.users.UserReference;
import com.android.compatibility.common.util.ApiTest;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

@RunWith(BedsteadJUnit4.class)
public final class SecurityLoggingTest {

    @ClassRule @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    @CannotSetPolicyTest(policy = {GlobalSecurityLogging.class, SecurityLogging.class})
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#setSecurityLoggingEnabled")
    public void setSecurityLoggingEnabled_notPermitted_throwsException() {
        assertThrows(SecurityException.class,
                () -> sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                        sDeviceState.dpc().componentName(), true));
    }

    @CanSetPolicyTest(policy = {GlobalSecurityLogging.class, SecurityLogging.class})
    @Postsubmit(reason = "new test")
    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setSecurityLoggingEnabled",
            "android.app.admin.DevicePolicyManager#isSecurityLoggingEnabled"})
    public void setSecurityLoggingEnabled_true_securityLoggingIsEnabled() {
        boolean originalSecurityLoggingEnabled = sDeviceState.dpc()
                .devicePolicyManager().isSecurityLoggingEnabled(sDeviceState.dpc().componentName());

        try {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), true);

            assertThat(sDeviceState.dpc().devicePolicyManager().isSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName())).isTrue();
        } finally {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), originalSecurityLoggingEnabled);
        }
    }

    @CanSetPolicyTest(policy = {GlobalSecurityLogging.class, SecurityLogging.class})
    @Postsubmit(reason = "new test")
    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setSecurityLoggingEnabled",
            "android.app.admin.DevicePolicyManager#isSecurityLoggingEnabled"})
    public void setSecurityLoggingEnabled_false_securityLoggingIsNotEnabled() {
        boolean originalSecurityLoggingEnabled = sDeviceState.dpc()
                .devicePolicyManager().isSecurityLoggingEnabled(sDeviceState.dpc().componentName());

        try {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), false);

            assertThat(sDeviceState.dpc().devicePolicyManager().isSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName())).isFalse();
        } finally {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), originalSecurityLoggingEnabled);
        }
    }

    @CannotSetPolicyTest(policy = {GlobalSecurityLogging.class, SecurityLogging.class})
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#isSecurityLoggingEnabled")
    public void isSecurityLoggingEnabled_notPermitted_throwsException() {
        assertThrows(SecurityException.class,
                () -> sDeviceState.dpc().devicePolicyManager().isSecurityLoggingEnabled(
                        sDeviceState.dpc().componentName()));
    }

    @CannotSetPolicyTest(policy = {GlobalSecurityLogging.class, SecurityLogging.class})
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#retrieveSecurityLogs")
    public void retrieveSecurityLogs_notPermitted_throwsException() {
        ensureNoAdditionalUsers();

        assertThrows(SecurityException.class,
                () -> sDeviceState.dpc().devicePolicyManager().retrieveSecurityLogs(
                        sDeviceState.dpc().componentName()));
    }

    @CanSetPolicyTest(policy = {GlobalSecurityLogging.class, SecurityLogging.class})
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#retrieveSecurityLogs")
    public void retrieveSecurityLogs_returnsSecurityLogs() {
        ensureNoAdditionalUsers();

        boolean originalSecurityLoggingEnabled =
                sDeviceState.dpc().devicePolicyManager()
                        .isSecurityLoggingEnabled(sDeviceState.dpc().componentName());

        try {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), true);

            // TODO: Generate some security logs and assert on them

            sDeviceState.dpc().devicePolicyManager().retrieveSecurityLogs(
                    sDeviceState.dpc().componentName());
        } finally {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), originalSecurityLoggingEnabled);
        }
    }

    // TODO: Add test that logs are filtered for non-device-owner
    // TODO: Add test for rate limiting
    // TODO: Add test for onSecurityLogsAvailable

    @CannotSetPolicyTest(policy = {GlobalSecurityLogging.class, SecurityLogging.class})
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#retrievePreRebootSecurityLogs")
    public void retrievePreRebootSecurityLogs_notPermitted_throwsException() {
        ensureNoAdditionalUsers();

        assertThrows(SecurityException.class,
                () -> sDeviceState.dpc().devicePolicyManager().retrievePreRebootSecurityLogs(
                        sDeviceState.dpc().componentName()));
    }

    @CanSetPolicyTest(policy = {GlobalSecurityLogging.class, SecurityLogging.class})
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#retrievePreRebootSecurityLogs")
    public void retrievePreRebootSecurityLogs_doesNotThrow() {
        ensureNoAdditionalUsers();
        boolean originalSecurityLoggingEnabled =
                sDeviceState.dpc().devicePolicyManager()
                        .isSecurityLoggingEnabled(sDeviceState.dpc().componentName());

        try {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), true);

            // Nothing to assert as this can be null on some devices
            sDeviceState.dpc().devicePolicyManager().retrievePreRebootSecurityLogs(
                    sDeviceState.dpc().componentName());
        } finally {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), originalSecurityLoggingEnabled);
        }
    }

    @CanSetPolicyTest(policy = GlobalSecurityLogging.class, singleTestOnly = true)
    @EnsureHasAdditionalUser
    @EnsureHasProfileOwner(onUser = UserType.ADDITIONAL_USER, affiliationIds = {})
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#retrieveSecurityLogs")
    public void retrieveSecurityLogs_unaffiliatedAdditionalUser_throwsException() {
        try {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), true);

            assertThrows(SecurityException.class, () -> {
                sDeviceState.dpc().devicePolicyManager().retrieveSecurityLogs(
                        sDeviceState.dpc().componentName());
            });
        } finally {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), false);
        }
    }

    @CanSetPolicyTest(policy = {GlobalSecurityLogging.class, SecurityLogging.class})
    @EnsureHasAdditionalUser
    @EnsureHasProfileOwner(onUser = UserType.ADDITIONAL_USER, affiliationIds = "affiliated")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#retrieveSecurityLogs")
    public void retrieveSecurityLogs_affiliatedAdditionalUser_doesNotThrowException() {
        // TODO(273474964): Move into infra
        TestApis.users().all().stream()
                .filter(u -> !u.equals(TestApis.users().instrumented())
                        && !u.equals(TestApis.users().system())
                        && !u.equals(sDeviceState.additionalUser())
                        && !u.equals(TestApis.users().current()))
                .forEach(UserReference::remove);

        Set<String> affiliationIds = new HashSet<>(sDeviceState.dpcOnly().devicePolicyManager()
                .getAffiliationIds(sDeviceState.dpcOnly().componentName()));
        affiliationIds.add("affiliated");
        sDeviceState.dpcOnly().devicePolicyManager().setAffiliationIds(
                sDeviceState.dpc().componentName(),
                affiliationIds);

        try {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), true);

            sDeviceState.dpc().devicePolicyManager().retrieveSecurityLogs(
                    sDeviceState.dpc().componentName());
        } finally {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), false);
        }
    }

    @CanSetPolicyTest(policy = {GlobalSecurityLogging.class, SecurityLogging.class})
    @EnsureHasNoAdditionalUser
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#retrieveSecurityLogs")
    public void retrieveSecurityLogs_noAdditionalUser_doesNotThrowException() {
        ensureNoAdditionalUsers();

        try {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), true);

            sDeviceState.dpc().devicePolicyManager().retrieveSecurityLogs(
                    sDeviceState.dpc().componentName());
        } finally {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), false);
        }
    }

    @CanSetPolicyTest(policy = GlobalSecurityLogging.class)
    @EnsureHasAdditionalUser
    @EnsureHasProfileOwner(onUser = UserType.ADDITIONAL_USER, affiliationIds = {})
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#retrievePreRebootSecurityLogs")
    public void retrievePreRebootSecurityLogs_unaffiliatedAdditionalUser_throwsException() {
        try {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), true);

            assertThrows(SecurityException.class, () -> {
                sDeviceState.dpc().devicePolicyManager().retrievePreRebootSecurityLogs(
                        sDeviceState.dpc().componentName());
            });
        } finally {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), false);
        }
    }

    @CanSetPolicyTest(policy = {GlobalSecurityLogging.class, SecurityLogging.class})
    @EnsureHasAdditionalUser
    @EnsureHasProfileOwner(onUser = UserType.ADDITIONAL_USER, affiliationIds = "affiliated")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#retrievePreRebootSecurityLogs")
    public void retrievePreRebootSecurityLogs_affiliatedAdditionalUser_doesNotThrowException() {
        // TODO(273474964): Move into infra
        TestApis.users().all().stream()
                .filter(u -> !u.equals(TestApis.users().instrumented())
                        && !u.equals(TestApis.users().system())
                        && !u.equals(sDeviceState.additionalUser())
                        && !u.equals(TestApis.users().current()))
                .forEach(UserReference::remove);

        Set<String> affiliationIds = new HashSet<>(sDeviceState.dpcOnly().devicePolicyManager()
                .getAffiliationIds(sDeviceState.dpcOnly().componentName()));
        affiliationIds.add("affiliated");
        sDeviceState.dpcOnly().devicePolicyManager().setAffiliationIds(
                sDeviceState.dpc().componentName(),
                affiliationIds);

        try {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), true);

            sDeviceState.dpc().devicePolicyManager().retrievePreRebootSecurityLogs(
                    sDeviceState.dpc().componentName());
        } finally {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), false);
        }
    }

    @CanSetPolicyTest(policy = {GlobalSecurityLogging.class, SecurityLogging.class})
    @EnsureHasNoAdditionalUser
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#retrievePreRebootSecurityLogs")
    public void retrievePreRebootSecurityLogs_noAdditionalUser_doesNotThrowException() {
        ensureNoAdditionalUsers();

        try {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), true);

            sDeviceState.dpc().devicePolicyManager().retrievePreRebootSecurityLogs(
                    sDeviceState.dpc().componentName());
        } finally {
            sDeviceState.dpc().devicePolicyManager().setSecurityLoggingEnabled(
                    sDeviceState.dpc().componentName(), false);
        }
    }

    private void ensureNoAdditionalUsers() {
        // TODO(273474964): Move into infra
        try {
            TestApis.users().all().stream()
                    .filter(u -> !u.equals(TestApis.users().instrumented())
                            && !u.equals(TestApis.users().system())
                            && !u.equals(TestApis.users().current()))
                    .forEach(UserReference::remove);
        } catch (NeneException e) {
            // Happens when we can't remove a user
            throw new NeneException("Error when removing user. Instrumented user is "
                    + TestApis.users().instrumented() + ", current user is "
                    + TestApis.users().current() + ", system user is "
                    + TestApis.users().system(), e);
        }
    }

}
