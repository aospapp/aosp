/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.bedstead.harrier.UserType.ANY;
import static com.android.bedstead.harrier.UserType.INITIAL_USER;
import static com.android.bedstead.nene.users.UserType.MANAGED_PROFILE_TYPE_NAME;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.EnsureHasAccount;
import com.android.bedstead.harrier.annotations.EnsureHasAccountAuthenticator;
import com.android.bedstead.harrier.annotations.EnsureHasNoAccounts;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasNoDpc;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.accounts.AccountReference;
import com.android.bedstead.nene.devicepolicy.ProfileOwner;
import com.android.bedstead.nene.exceptions.AdbException;
import com.android.bedstead.nene.packages.ComponentReference;
import com.android.bedstead.nene.users.UserReference;
import com.android.bedstead.nene.utils.Poll;
import com.android.bedstead.nene.utils.ShellCommand;
import com.android.bedstead.nene.utils.ShellCommandUtils;
import com.android.bedstead.testapp.TestApp;
import com.android.bedstead.testapp.TestAppInstance;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(BedsteadJUnit4.class)
public final class ProfileOwnerTest {

    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private static final TestApp TEST_ONLY_DPC = sDeviceState.testApps()
            .query().whereIsDeviceAdmin().isTrue()
            .whereTestOnly().isTrue()
            .get();

    // TODO: We should be able to query for the receiver rather than hardcoding
    private static final ComponentReference TEST_ONLY_DPC_COMPONENT =
            TEST_ONLY_DPC.pkg().component(
                    TEST_ONLY_DPC.packageName() + ".DeviceAdminReceiver");

    private static final TestApp NOT_TEST_ONLY_DPC = sDeviceState.testApps()
            .query().whereIsDeviceAdmin().isTrue()
            .whereTestOnly().isFalse()
            .get();

    private static final ComponentReference NOT_TEST_ONLY_DPC_COMPONENT =
            NOT_TEST_ONLY_DPC.pkg().component(
                    NOT_TEST_ONLY_DPC.packageName() + ".DeviceAdminReceiver");

    private static final String SET_PROFILE_OWNER_COMMAND = "dpm set-profile-owner";

    private static final String FEATURE_ALLOW =
            "android.account.DEVICE_OR_PROFILE_OWNER_ALLOWED";
    private static final String FEATURE_DISALLOW =
            "android.account.DEVICE_OR_PROFILE_OWNER_DISALLOWED";

    @EnsureHasNoDpc
    @EnsureHasNoAccounts(onUser = ANY)
    @Test
    public void setProfileOwnerViaAdb_noAccounts_testOnly_sets() throws Exception {
        try (TestAppInstance dpcApp = TEST_ONLY_DPC.install()) {
            try {
                ShellCommand
                        .builderForUser(TestApis.users().instrumented(), SET_PROFILE_OWNER_COMMAND)
                        .addOperand(TEST_ONLY_DPC_COMPONENT.flattenToString())
                        .validate(ShellCommandUtils::startsWithSuccess)
                        .execute();

                assertThat(TestApis.devicePolicy().getProfileOwner()).isNotNull();
            } finally {
                ProfileOwner profileOwner = TestApis.devicePolicy().getProfileOwner();
                if (profileOwner != null) {
                    profileOwner.remove();
                }
            }
        }
    }

    @EnsureHasNoDpc
    @EnsureHasNoAccounts(onUser = ANY)
    @Test
    public void setProfileOwnerViaAdb_noAccounts_notTestOnly_sets() throws Exception {
        try (TestAppInstance dpcApp = NOT_TEST_ONLY_DPC.install()) {
            try {
                ShellCommand
                        .builderForUser(TestApis.users().instrumented(), SET_PROFILE_OWNER_COMMAND)
                        .addOperand(NOT_TEST_ONLY_DPC_COMPONENT.flattenToString())
                        .validate(ShellCommandUtils::startsWithSuccess)
                        .execute();

                assertThat(TestApis.devicePolicy().getProfileOwner()).isNotNull();
            } finally {
                ProfileOwner profileOwner = TestApis.devicePolicy().getProfileOwner();
                if (profileOwner != null) {
                    profileOwner.remove();
                }
            }
        }
    }

    @EnsureHasNoDpc
    @EnsureHasNoAccounts(onUser = ANY)
    @EnsureHasAccount(features = FEATURE_DISALLOW, onUser = INITIAL_USER)
    @Test
    public void setProfileOwnerViaAdb_invalidAccountOnParent_sets() throws Exception {
        try (UserReference profile = TestApis.users().createUser()
                     .parent(TestApis.users().instrumented())
                     .type(MANAGED_PROFILE_TYPE_NAME)
                     .createAndStart()) {
            TestAppInstance dpcApp = NOT_TEST_ONLY_DPC.install(profile);

            ShellCommand
                    .builderForUser(profile, SET_PROFILE_OWNER_COMMAND)
                    .addOperand(NOT_TEST_ONLY_DPC_COMPONENT.flattenToString())
                    .validate(ShellCommandUtils::startsWithSuccess)
                    .execute();

            assertThat(TestApis.devicePolicy().getProfileOwner(profile)).isNotNull();
        }
    }

    @EnsureHasNoDpc
    @EnsureHasNoAccounts(onUser = ANY)
    @EnsureHasAccount(features = {}, onUser = INITIAL_USER)
    @Test
    public void setProfileOwnerViaAdb_accountExistsWithNoFeatures_doesNotSet() {
        try (TestAppInstance dpcApp = TEST_ONLY_DPC.install()) {
            try {
                assertThrows(AdbException.class, () ->
                        ShellCommand
                                .builderForUser(TestApis.users().instrumented(),
                                        SET_PROFILE_OWNER_COMMAND)
                                .addOperand(TEST_ONLY_DPC_COMPONENT.flattenToString())
                                .execute());
                assertThat(TestApis.devicePolicy().getDeviceOwner()).isNull();
            } finally {
                ProfileOwner profileOwner = TestApis.devicePolicy().getProfileOwner();
                if (profileOwner != null) {
                    profileOwner.remove();
                }
            }
        } finally {
            // After attempting and failing to set the profiel owner, it will remain as an active
            // admin for a short while
            Poll.forValue("Active admins",
                    () -> TestApis.devicePolicy().getActiveAdmins())
                    .toMeet(i -> !i.contains(TEST_ONLY_DPC_COMPONENT))
                    .errorOnFail("Expected active admins to not contain DPC")
                    .await();
        }
    }

    @EnsureHasNoDpc
    @EnsureHasNoAccounts(onUser = ANY)
    @EnsureHasAccount(features = FEATURE_DISALLOW, onUser = INITIAL_USER)
    @Test
    public void setProfileOwnerViaAdb_accountExistsWithDisallowFeature_doesNotSet() {
        try (TestAppInstance dpcApp = TEST_ONLY_DPC.install()) {
            try {
                assertThrows(AdbException.class, () ->
                        ShellCommand
                                .builderForUser(TestApis.users().instrumented(),
                                        SET_PROFILE_OWNER_COMMAND)
                                .addOperand(TEST_ONLY_DPC_COMPONENT.flattenToString())
                                .execute());
                assertThat(TestApis.devicePolicy().getProfileOwner()).isNull();
            } finally {
                ProfileOwner profileOwner = TestApis.devicePolicy().getProfileOwner();
                if (profileOwner != null) {
                    profileOwner.remove();
                }
            }
        } finally {
            // After attempting and failing to set the profile owner, it will remain as an active
            // admin for a short while
            Poll.forValue("Active admins",
                    () -> TestApis.devicePolicy().getActiveAdmins())
                    .toMeet(i -> !i.contains(TEST_ONLY_DPC_COMPONENT))
                    .errorOnFail("Expected active admins to not contain DPC")
                    .await();
        }
    }

    @EnsureHasNoDpc
    @EnsureHasNoAccounts(onUser = ANY)
    @EnsureHasAccount(features = {FEATURE_ALLOW, FEATURE_DISALLOW}, onUser = INITIAL_USER)
    @Test
    public void setProfileOwnerViaAdb_accountExistsWithDisallowAndAllowFeatures_doesNotSet() {
        try (TestAppInstance dpcApp = TEST_ONLY_DPC.install()) {
            try {
                assertThrows(AdbException.class, () ->
                        ShellCommand
                                .builderForUser(TestApis.users().instrumented(),
                                        SET_PROFILE_OWNER_COMMAND)
                                .addOperand(TEST_ONLY_DPC_COMPONENT.flattenToString())
                                .execute());
                assertThat(TestApis.devicePolicy().getProfileOwner()).isNull();
            } finally {
                ProfileOwner profileOwner = TestApis.devicePolicy().getProfileOwner();
                if (profileOwner != null) {
                    profileOwner.remove();
                }
            }
        } finally {
            // After attempting and failing to set the profile owner, it will remain as an active
            // admin for a short while
            Poll.forValue("Active admins",
                    () -> TestApis.devicePolicy().getActiveAdmins())
                    .toMeet(i -> !i.contains(TEST_ONLY_DPC_COMPONENT))
                    .errorOnFail("Expected active admins to not contain DPC")
                    .await();
        }
    }

    @EnsureHasNoDpc
    @EnsureHasNoAccounts(onUser = ANY)
    @EnsureHasAccount(features = FEATURE_ALLOW, onUser = INITIAL_USER)
    @Test
    public void setProfileOwnerViaAdb_accountExistsWithAllowFeature_testOnly_sets() throws Exception {
        try (TestAppInstance dpcApp = TEST_ONLY_DPC.install()) {
            try {
                ShellCommand
                        .builderForUser(TestApis.users().instrumented(), SET_PROFILE_OWNER_COMMAND)
                        .addOperand(TEST_ONLY_DPC_COMPONENT.flattenToString())
                        .validate(ShellCommandUtils::startsWithSuccess)
                        .execute();

                assertThat(TestApis.devicePolicy().getProfileOwner()).isNotNull();
            } finally {
                ProfileOwner profileOwner = TestApis.devicePolicy().getProfileOwner();
                if (profileOwner != null) {
                    profileOwner.remove();
                }
            }
        }
    }

    @EnsureHasNoDpc
    @EnsureHasNoAccounts(onUser = ANY)
    @EnsureHasAccount(features = FEATURE_ALLOW, onUser = INITIAL_USER)
    @Test
    public void setProfileOwnerViaAdb_accountExistsWithAllowFeature_notTestOnly_doesNotSet() {
        try (TestAppInstance dpcApp = NOT_TEST_ONLY_DPC.install()) {
            try {
                assertThrows(AdbException.class, () ->
                        ShellCommand
                                .builderForUser(TestApis.users().instrumented(),
                                        SET_PROFILE_OWNER_COMMAND)
                                .addOperand(NOT_TEST_ONLY_DPC_COMPONENT.flattenToString())
                                .execute());
                assertThat(TestApis.devicePolicy().getProfileOwner()).isNull();
            } finally {
                ProfileOwner profileOwner = TestApis.devicePolicy().getProfileOwner();
                if (profileOwner != null) {
                    profileOwner.remove();
                }
            }
        } finally {
            // After attempting and failing to set the profile owner, it will remain as an active
            // admin for a short while
            Poll.forValue("Active admins",
                    () -> TestApis.devicePolicy().getActiveAdmins())
                    .toMeet(i -> !i.contains(NOT_TEST_ONLY_DPC_COMPONENT))
                    .errorOnFail("Expected active admins to not contain DPC")
                    .await();
        }
    }
}
