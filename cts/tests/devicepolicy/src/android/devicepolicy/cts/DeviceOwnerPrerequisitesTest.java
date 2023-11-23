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

import static com.android.queryable.queries.ServiceQuery.service;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.Postsubmit;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasNoDeviceOwner;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasNoProfileOwner;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.exceptions.AdbException;
import com.android.bedstead.nene.utils.Poll;
import com.android.bedstead.nene.utils.ShellCommand;
import com.android.bedstead.remotedpc.RemoteDpc;
import com.android.bedstead.testapp.TestApp;
import com.android.bedstead.testapp.TestAppInstance;
import com.android.bedstead.testapp.TestAppProvider;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(BedsteadJUnit4.class)
public final class DeviceOwnerPrerequisitesTest {
    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private static final Context sContext = TestApis.context().instrumentedContext();
    private static final TestAppProvider sTestAppProvider = new TestAppProvider();
    private static final TestApp sAccountManagementApp = sTestAppProvider
            .query()
            // TODO(b/198417584): Support Querying XML resources in TestApp.
            // TODO(b/198590265) Filter for the correct account type.
            .whereServices().contains(
                    service().serviceClass().className()
                            .isEqualTo("com.android.bedstead.testapp.AccountManagementApp"
                                    + ".TestAppAccountAuthenticatorService"))
            .get();
    private static final TestApp sDpcApp = sTestAppProvider
            .query()
            .wherePackageName().isEqualTo(RemoteDpc.DPC_COMPONENT_NAME.getPackageName())
            .get();

    private static final String EXISTING_ACCOUNT_TYPE =
            "com.android.bedstead.testapp.AccountManagementApp.account.type";
    private static final String SET_DEVICE_OWNER_COMMAND = "dpm set-device-owner";
    private static final Account ACCOUNT_WITH_EXISTING_TYPE
            = new Account("user0", EXISTING_ACCOUNT_TYPE);
    private static final String TEST_PASSWORD = "password";

    private AccountManager mAccountManager;

    @Before
    public void setUp() {
        mAccountManager = sContext.getSystemService(AccountManager.class);
    }

    @Test
    @Postsubmit(reason = "new test with sleep")
    @EnsureHasNoDeviceOwner
    @EnsureHasNoProfileOwner
    public void setDeviceOwnerViaAdb_deviceHasAccount_fails()
            throws InterruptedException {
        try (TestAppInstance accountAuthenticatorApp =
                     sAccountManagementApp.install(TestApis.users().instrumented());
             TestAppInstance dpcApp = sDpcApp.install(TestApis.users().instrumented())) {
            addAccount();

            assertThrows(AdbException.class, () ->
                    ShellCommand
                            .builderForUser(
                                    TestApis.users().instrumented(), SET_DEVICE_OWNER_COMMAND)
                            .addOperand(RemoteDpc.DPC_COMPONENT_NAME.flattenToString())
                            .execute());
            assertThat(TestApis.devicePolicy().getDeviceOwner()).isNull();
            DevicePolicyManager dpm = TestApis.context().instrumentedContext()
                    .getSystemService(DevicePolicyManager.class);
            // After attempting and failing to set the device owner, it will remain as an active
            // admin for a short while
            Poll.forValue("Active admins", dpm::getActiveAdmins)
                    .toMeet(i -> i == null || !i.contains(RemoteDpc.DPC_COMPONENT_NAME))
                    .errorOnFail("Expected active admins to not contain RemoteDPC")
                    .await();
        }
    }

    /**
     * Blocks until an account is added.
     */
    private void addAccount() {
        Poll.forValue("account created success", this::addAccountOnce)
                .toBeEqualTo(true)
                .errorOnFail()
                .await();
    }

    private boolean addAccountOnce() {
        return mAccountManager.addAccountExplicitly(
                ACCOUNT_WITH_EXISTING_TYPE,
                TEST_PASSWORD,
                /* userdata= */ null);
    }
}
