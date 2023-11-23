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


import static com.android.queryable.queries.ActivityQuery.activity;
import static com.android.queryable.queries.IntentFilterQuery.intentFilter;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;
import static org.testng.Assert.assertThrows;

import android.app.admin.RemoteDevicePolicyManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.Postsubmit;
import com.android.bedstead.harrier.annotations.enterprise.CanSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.PolicyDoesNotApplyTest;
import com.android.bedstead.harrier.policies.DefaultDialerApplication;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.remotedpc.RemotePolicyManager;
import com.android.bedstead.testapp.TestApp;
import com.android.bedstead.testapp.TestAppInstance;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.runner.RunWith;

@RunWith(BedsteadJUnit4.class)
public final class DefaultDialerApplicationTest {
    @ClassRule
    @Rule
    public static DeviceState sDeviceState = new DeviceState();

    private static final Context sContext = TestApis.context().instrumentedContext();
    private static final TestApp sDialerApp = sDeviceState.testApps()
            .query()
            .whereActivities().contains(
                    activity().where().intentFilters().contains(
                            intentFilter().where().actions().contains(Intent.ACTION_DIAL)))
            .get();
    private static final String FAKE_DIALER_APP_NAME = "FakeDialerAppName";
    private ComponentName mAdmin;
    private RemoteDevicePolicyManager mDpm;
    private TelephonyManager mTelephonyManager;
    private RoleManager mRoleManager;

    @Before
    public void setUp() {
        RemotePolicyManager dpc = sDeviceState.dpc();
        mAdmin = dpc.componentName();
        mDpm = dpc.devicePolicyManager();
        mTelephonyManager = sContext.getSystemService(TelephonyManager.class);
        mRoleManager = sContext.getSystemService(RoleManager.class);
    }

    // TODO(b/198588696): Add support is @RequireVoiceCapable and @RequireNotVoiceCapable
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = DefaultDialerApplication.class)
    public void setDefaultDialerApplication_works() {
        assumeTrue(mTelephonyManager.isVoiceCapable()
                || (mRoleManager != null && mRoleManager.isRoleAvailable(RoleManager.ROLE_DIALER)));
        String previousDialerAppName = getDefaultDialerPackage();
        try (TestAppInstance dialerApp = sDialerApp.install()) {
            mDpm.setDefaultDialerApplication(dialerApp.packageName());

            assertThat(getDefaultDialerPackage()).isEqualTo(dialerApp.packageName());
        } finally {
            mDpm.setDefaultDialerApplication(previousDialerAppName);
        }
    }

    // TODO(b/198588696): Add support is @RequireVoiceCapable and @RequireNotVoiceCapable
    @Postsubmit(reason = "new test")
    @PolicyDoesNotApplyTest(policy = DefaultDialerApplication.class)
    public void setDefaultDialerApplication_unchanged() {
        assumeTrue(mTelephonyManager.isVoiceCapable()
                || (mRoleManager != null && mRoleManager.isRoleAvailable(RoleManager.ROLE_DIALER)));
        String previousDialerAppInTest = getDefaultDialerPackage();
        String previousDialerAppInDpc = sDeviceState.dpc().telecomManager()
                .getDefaultDialerPackage();
        try (TestAppInstance dialerApp = sDialerApp.install(sDeviceState.dpc().user())) {
            mDpm.setDefaultDialerApplication(dialerApp.packageName());
            // Make sure the default dialer in the test user is unchanged.
            assertThat(getDefaultDialerPackage()).isEqualTo(previousDialerAppInTest);
        } finally {
            mDpm.setDefaultDialerApplication(previousDialerAppInDpc);
        }
    }

    // TODO(b/198588696): Add support is @RequireVoiceCapable and @RequireNotVoiceCapable
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = DefaultDialerApplication.class)
    public void setDefaultDialerApplication_dialerPackageDoesNotExist_unchanged() {
        assumeTrue(mTelephonyManager.isVoiceCapable()
                || (mRoleManager != null && mRoleManager.isRoleAvailable(RoleManager.ROLE_DIALER)));
        String previousDialerAppName = getDefaultDialerPackage();

        try {
            assertThrows(IllegalArgumentException.class, () ->
                    mDpm.setDefaultDialerApplication(FAKE_DIALER_APP_NAME));
            assertThat(getDefaultDialerPackage()).isEqualTo(previousDialerAppName);
        } finally {
            mDpm.setDefaultDialerApplication(previousDialerAppName);
        }
    }

    // TODO(b/198588696): Add support is @RequireVoiceCapable and @RequireNotVoiceCapable
    @Postsubmit(reason = "new test")
    @CanSetPolicyTest(policy = DefaultDialerApplication.class)
    public void setDefaultDialerApplication_notVoiceCapable_unchanged() {
        assumeTrue(!mTelephonyManager.isVoiceCapable()
                && (mRoleManager == null
                        || !mRoleManager.isRoleAvailable(RoleManager.ROLE_DIALER)));
        String previousDialerAppName = getDefaultDialerPackage();
        try (TestAppInstance dialerApp = sDialerApp.install()) {
            mDpm.setDefaultDialerApplication(dialerApp.packageName());

            assertThat(getDefaultDialerPackage()).isEqualTo(previousDialerAppName);
        } finally {
            mDpm.setDefaultDialerApplication(previousDialerAppName);
        }
    }

    private String getDefaultDialerPackage() {
        return sContext.getSystemService(TelecomManager.class).getDefaultDialerPackage();
    }
}
