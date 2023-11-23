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

import static com.android.bedstead.harrier.UserType.ADDITIONAL_USER;
import static com.android.bedstead.nene.permissions.CommonPermissions.MANAGE_PROFILE_AND_DEVICE_OWNERS;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.util.Log;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.UserType;
import com.android.bedstead.harrier.annotations.EnsureHasAccount;
import com.android.bedstead.harrier.annotations.EnsureHasAccountAuthenticator;
import com.android.bedstead.harrier.annotations.EnsureHasAdditionalUser;
import com.android.bedstead.harrier.annotations.EnsureHasNoAccounts;
import com.android.bedstead.harrier.annotations.EnsureHasPermission;
import com.android.bedstead.harrier.annotations.Postsubmit;
import com.android.bedstead.harrier.annotations.RequireNotHeadlessSystemUserMode;
import com.android.bedstead.harrier.annotations.RequireRunOnSystemUser;
import com.android.bedstead.harrier.annotations.UserTest;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasDeviceOwner;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasNoDpc;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.accounts.AccountReference;
import com.android.bedstead.nene.devicepolicy.DeviceOwner;
import com.android.bedstead.nene.exceptions.AdbException;
import com.android.bedstead.nene.packages.ComponentReference;
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
public final class DeviceOwnerTest {
    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private static final Context sContext = TestApis.context().instrumentedContext();
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

    private static final String SET_DEVICE_OWNER_COMMAND = "dpm set-device-owner";

    private static final DevicePolicyManager sDevicePolicyManager =
            sContext.getSystemService(DevicePolicyManager.class);

    private static final String FEATURE_ALLOW =
            "android.account.DEVICE_OR_PROFILE_OWNER_ALLOWED";
    private static final String FEATURE_DISALLOW =
            "android.account.DEVICE_OR_PROFILE_OWNER_DISALLOWED";

    @Test
    @Postsubmit(reason = "new test")
    @EnsureHasDeviceOwner
    @RequireRunOnSystemUser
    public void setDeviceOwner_setsDeviceOwner() {
        assertThat(sDevicePolicyManager.isAdminActive(sDeviceState.dpc().componentName()))
                .isTrue();
        assertThat(sDevicePolicyManager.isDeviceOwnerApp(sDeviceState.dpc().packageName()))
                .isTrue();
        assertThat(sDevicePolicyManager.getDeviceOwner())
                .isEqualTo(sDeviceState.dpc().packageName());
    }

    @UserTest({UserType.SYSTEM_USER, UserType.SECONDARY_USER})
    @EnsureHasDeviceOwner
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Postsubmit(reason = "new test")
    public void getDeviceOwnerNameOnAnyUser_returnsDeviceOwnerName() {
        assertThat(sDevicePolicyManager.getDeviceOwnerNameOnAnyUser())
                .isEqualTo(sDeviceState.dpc().testApp().label());
    }

    @UserTest({UserType.SYSTEM_USER, UserType.SECONDARY_USER})
    @EnsureHasDeviceOwner
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Postsubmit(reason = "new test")
    public void getDeviceOwnerComponentOnAnyUser_returnsDeviceOwnerComponent() {
        assertThat(sDevicePolicyManager.getDeviceOwnerComponentOnAnyUser())
                .isEqualTo(sDeviceState.dpc().componentName());
    }

    // All via adb methods use an additional user as we assume if it works cross user it'll work
    // same user

    // We use ensureHasNoAccounts and then create the account during the test because we want
    // to ensure there is only one account (and bedstead currently doesn't support this)

    @EnsureHasAdditionalUser
    @EnsureHasNoDpc
    @EnsureHasNoAccounts(onUser = UserType.ANY)
    @Test
    public void setDeviceOwnerViaAdb_noAccounts_testOnly_sets() throws Exception {
        try (TestAppInstance dpcApp = TEST_ONLY_DPC.install(TestApis.users().system())) {
            try {
                ShellCommand
                        .builder(SET_DEVICE_OWNER_COMMAND)
                        .addOperand(TEST_ONLY_DPC_COMPONENT.flattenToString())
                        .validate(ShellCommandUtils::startsWithSuccess)
                        .execute();

                assertThat(TestApis.devicePolicy().getDeviceOwner()).isNotNull();
            } finally {
                DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
                if (deviceOwner != null) {
                    deviceOwner.remove();
                }
            }
        }
    }

    @EnsureHasAdditionalUser
    @EnsureHasNoDpc
    @EnsureHasNoAccounts(onUser = UserType.ANY)
    @RequireNotHeadlessSystemUserMode(reason = "No non-testonly dpc which supports headless")
    @Test
    public void setDeviceOwnerViaAdb_noAccounts_notTestOnly_sets() throws Exception {
        try (TestAppInstance dpcApp = NOT_TEST_ONLY_DPC.install(TestApis.users().system())) {
            try {
                ShellCommand
                        .builder(SET_DEVICE_OWNER_COMMAND)
                        .addOperand(NOT_TEST_ONLY_DPC_COMPONENT.flattenToString())
                        .validate(ShellCommandUtils::startsWithSuccess)
                        .execute();

                assertThat(TestApis.devicePolicy().getDeviceOwner()).isNotNull();
            } finally {
                DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
                if (deviceOwner != null) {
                    deviceOwner.remove();
                }
            }
        }
    }

    @EnsureHasAdditionalUser
    @EnsureHasNoDpc
    @EnsureHasNoAccounts(onUser = UserType.ANY)
    @EnsureHasAccount(features = {}, onUser = ADDITIONAL_USER)
    @Test
    public void setDeviceOwnerViaAdb_accountExistsWithNoFeatures_doesNotSet() {
        try (TestAppInstance dpcApp = TEST_ONLY_DPC.install(TestApis.users().system())) {
            try {
                assertThrows(AdbException.class, () ->
                        ShellCommand
                                .builder(SET_DEVICE_OWNER_COMMAND)
                                .addOperand(TEST_ONLY_DPC_COMPONENT.flattenToString())
                                .execute());
                assertThat(TestApis.devicePolicy().getDeviceOwner()).isNull();
            } finally {
                DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
                if (deviceOwner != null) {
                    deviceOwner.remove();
                }
            }
        } finally {
            // After attempting and failing to set the device owner, it will remain as an active
            // admin for a short while
            Poll.forValue("Active admins",
                    () -> TestApis.devicePolicy().getActiveAdmins(
                            TestApis.users().system()))
                    .toMeet(i -> !i.contains(TEST_ONLY_DPC_COMPONENT))
                    .errorOnFail("Expected active admins to not contain DPC")
                    .await();
        }
    }

    @EnsureHasAdditionalUser
    @EnsureHasNoDpc
    @EnsureHasNoAccounts(onUser = UserType.ANY)
    @EnsureHasAccount(features = FEATURE_DISALLOW, onUser = ADDITIONAL_USER)
    @Test
    public void setDeviceOwnerViaAdb_accountExistsWithDisallowFeature_doesNotSet() {
        try (TestAppInstance dpcApp = TEST_ONLY_DPC.install(TestApis.users().system())) {
            try {
                assertThrows(AdbException.class, () ->
                        ShellCommand
                                .builder(SET_DEVICE_OWNER_COMMAND)
                                .addOperand(TEST_ONLY_DPC_COMPONENT.flattenToString())
                                .execute());
                assertThat(TestApis.devicePolicy().getDeviceOwner()).isNull();
            } finally {
                DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
                if (deviceOwner != null) {
                    deviceOwner.remove();
                }
            }
        } finally {
            // After attempting and failing to set the device owner, it will remain as an active
            // admin for a short while
            Poll.forValue("Active admins",
                    () -> TestApis.devicePolicy().getActiveAdmins(
                            TestApis.users().system()))
                    .toMeet(i -> !i.contains(TEST_ONLY_DPC_COMPONENT))
                    .errorOnFail("Expected active admins to not contain DPC")
                    .await();
        }
    }

    @EnsureHasAdditionalUser
    @EnsureHasNoDpc
    @EnsureHasNoAccounts(onUser = UserType.ANY)
    @EnsureHasAccount(features = {FEATURE_ALLOW, FEATURE_DISALLOW}, onUser = ADDITIONAL_USER)
    @Test
    public void setDeviceOwnerViaAdb_accountExistsWithDisallowAndAllowFeatures_doesNotSet() {
        try (TestAppInstance dpcApp = TEST_ONLY_DPC.install(TestApis.users().system())) {
            try {
                assertThrows(AdbException.class, () ->
                        ShellCommand
                                .builder(SET_DEVICE_OWNER_COMMAND)
                                .addOperand(TEST_ONLY_DPC_COMPONENT.flattenToString())
                                .execute());
                assertThat(TestApis.devicePolicy().getDeviceOwner()).isNull();
            } finally {
                DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
                if (deviceOwner != null) {
                    deviceOwner.remove();
                }
            }
        } finally {
            // After attempting and failing to set the device owner, it will remain as an active
            // admin for a short while
            Poll.forValue("Active admins",
                    () -> TestApis.devicePolicy().getActiveAdmins(
                            TestApis.users().system()))
                    .toMeet(i -> !i.contains(TEST_ONLY_DPC_COMPONENT))
                    .errorOnFail("Expected active admins to not contain DPC")
                    .await();
        }
    }

    @EnsureHasAdditionalUser
    @EnsureHasNoDpc
    @EnsureHasNoAccounts(onUser = UserType.ANY)
    @EnsureHasAccount(features = FEATURE_ALLOW, onUser = ADDITIONAL_USER)
    @Test
    public void setDeviceOwnerViaAdb_accountExistsWithAllowFeature_testOnly_sets()
            throws Exception {
        try (TestAppInstance dpcApp = TEST_ONLY_DPC.install(TestApis.users().system())) {
            try {
                ShellCommand
                        .builder(SET_DEVICE_OWNER_COMMAND)
                        .addOperand(TEST_ONLY_DPC_COMPONENT.flattenToString())
                        .validate(ShellCommandUtils::startsWithSuccess)
                        .execute();

                assertThat(TestApis.devicePolicy().getDeviceOwner()).isNotNull();
            } finally {
                DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
                if (deviceOwner != null) {
                    deviceOwner.remove();
                }
            }
        }
    }

    @EnsureHasAdditionalUser
    @EnsureHasNoDpc
    @EnsureHasNoAccounts(onUser = UserType.ANY)
    @EnsureHasAccount(features = FEATURE_ALLOW, onUser = ADDITIONAL_USER)
    @Test
    public void setDeviceOwnerViaAdb_accountExistsWithAllowFeature_notTestOnly_doesNotSet() {
        try (TestAppInstance dpcApp = NOT_TEST_ONLY_DPC.install(TestApis.users().system())) {
            try {
                assertThrows(AdbException.class, () ->
                        ShellCommand
                                .builder(SET_DEVICE_OWNER_COMMAND)
                                .addOperand(NOT_TEST_ONLY_DPC_COMPONENT.flattenToString())
                                .validate(ShellCommandUtils::startsWithSuccess)
                                .execute());
                assertThat(TestApis.devicePolicy().getDeviceOwner()).isNull();
            } finally {
                DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
                if (deviceOwner != null) {
                    deviceOwner.remove();
                }
            }
        } finally {
            // After attempting and failing to set the device owner, it will remain as an active
            // admin for a short while
            Poll.forValue("Active admins",
                    () -> TestApis.devicePolicy().getActiveAdmins(
                            TestApis.users().system()))
                    .toMeet(i -> !i.contains(NOT_TEST_ONLY_DPC_COMPONENT))
                    .errorOnFail("Expected active admins to not contain DPC")
                    .await();
        }
    }
}
