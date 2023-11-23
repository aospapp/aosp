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

import static android.app.ActivityManager.LOCK_TASK_MODE_LOCKED;
import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.admin.DevicePolicyIdentifiers.LOCK_TASK_POLICY;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_HOME;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_NONE;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO;
import static android.app.admin.TargetUser.LOCAL_USER_ID;
import static android.content.Intent.ACTION_DIAL;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.FEATURE_TELEPHONY;
import static android.devicepolicy.cts.utils.PolicyEngineUtils.FINANCED_DEVICE_CONTROLLER_ROLE;

import static com.android.bedstead.harrier.annotations.enterprise.MostImportantCoexistenceTest.LESS_IMPORTANT;
import static com.android.bedstead.harrier.annotations.enterprise.MostImportantCoexistenceTest.MORE_IMPORTANT;
import static com.android.bedstead.metricsrecorder.truth.MetricQueryBuilderSubject.assertThat;
import static com.android.eventlib.truth.EventLogsSubject.assertThat;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;
import static org.testng.Assert.assertThrows;

import android.app.ActivityOptions;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DpcAuthority;
import android.app.admin.LockTaskPolicy;
import android.app.admin.NoArgsPolicyKey;
import android.app.admin.PolicyState;
import android.app.admin.PolicyUpdateResult;
import android.app.admin.RoleAuthority;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.devicepolicy.cts.utils.PolicyEngineUtils;
import android.devicepolicy.cts.utils.PolicySetResultUtils;
import android.os.Bundle;
import android.os.SystemClock;
import android.stats.devicepolicy.EventId;
import android.telecom.TelecomManager;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.IntTestParameter;
import com.android.bedstead.harrier.annotations.Postsubmit;
import com.android.bedstead.harrier.annotations.RequireFeature;
import com.android.bedstead.harrier.annotations.enterprise.CanSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.CannotSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasDeviceOwner;
import com.android.bedstead.harrier.annotations.enterprise.MostImportantCoexistenceTest;
import com.android.bedstead.harrier.annotations.enterprise.PolicyAppliesTest;
import com.android.bedstead.harrier.annotations.enterprise.PolicyDoesNotApplyTest;
import com.android.bedstead.harrier.annotations.enterprise.RequireHasPolicyExemptApps;
import com.android.bedstead.harrier.policies.LockTask;
import com.android.bedstead.harrier.policies.LockTaskFinance;
import com.android.bedstead.metricsrecorder.EnterpriseMetricsRecorder;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.activities.Activity;
import com.android.bedstead.nene.packages.ComponentReference;
import com.android.bedstead.nene.utils.Poll;
import com.android.bedstead.testapp.TestApp;
import com.android.bedstead.testapp.TestAppActivity;
import com.android.bedstead.testapp.TestAppActivityReference;
import com.android.bedstead.testapp.TestAppInstance;
import com.android.compatibility.common.util.ApiTest;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;

@RunWith(BedsteadJUnit4.class)
public final class LockTaskTest {

    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();
    private static final String PACKAGE_NAME = "com.android.package.test";
    private static final DevicePolicyManager sLocalDevicePolicyManager =
            TestApis.context().instrumentedContext().getSystemService(DevicePolicyManager.class);

    /**
     * Option to launch activities in fullscreen. This is needed to properly use lock task mode on
     * freeform windowing devices. See b/273644378 for more context.
     */
    private static final ActivityOptions LAUNCH_FULLSCREEN_OPTIONS = ActivityOptions.makeBasic();
    static {
        LAUNCH_FULLSCREEN_OPTIONS.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
    }

    @IntTestParameter({
            LOCK_TASK_FEATURE_SYSTEM_INFO,
            LOCK_TASK_FEATURE_HOME,
            LOCK_TASK_FEATURE_GLOBAL_ACTIONS,
            LOCK_TASK_FEATURE_KEYGUARD})
    @Retention(RetentionPolicy.RUNTIME)
    private @interface IndividuallySettableFlagTestParameter {
    }

    @IntTestParameter({
            LOCK_TASK_FEATURE_SYSTEM_INFO,
            LOCK_TASK_FEATURE_OVERVIEW,
            LOCK_TASK_FEATURE_NOTIFICATIONS,
            LOCK_TASK_FEATURE_GLOBAL_ACTIONS,
            LOCK_TASK_FEATURE_KEYGUARD})
    @Retention(RetentionPolicy.RUNTIME)
    private @interface SettableWithHomeFlagTestParameter {
    }

    private static final TestApp sLockTaskTestApp = sDeviceState.testApps().query()
            .wherePackageName().isEqualTo("com.android.bedstead.testapp.LockTaskApp")
            .get(); // TODO(scottjonathan): filter by containing activity not by package name
    private static final TestApp sTestApp =
            sDeviceState.testApps().query().whereActivities().isNotEmpty().get();

    private static final TestApp sSecondTestApp =
            sDeviceState.testApps().query().whereActivities().isNotEmpty().get();

    private static final int LOCK_TASK_FEATURE = LOCK_TASK_FEATURE_HOME;

    private static final int SECOND_LOCK_TASK_FEATURE = LOCK_TASK_FEATURE_GLOBAL_ACTIONS;

    private static final ComponentReference BLOCKED_ACTIVITY_COMPONENT =
            TestApis.packages().component(new ComponentName(
                    "android", "com.android.internal.app.BlockedAppActivity"));

    private static final String ACTION_EMERGENCY_DIAL = "com.android.phone.EmergencyDialer.DIAL";

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void setLockTaskPackages_lockTaskPackagesIsSet() {
        sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                sDeviceState.dpc().componentName(), new String[]{PACKAGE_NAME});

        try {
            assertThat(sDeviceState.dpc().devicePolicyManager()
                    .getLockTaskPackages(sDeviceState.dpc().componentName())).asList()
                    .containsExactly(PACKAGE_NAME);
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    // b/278061827 This currently fails for permission based access
    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void startLockTask_recordsMetric() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        try (EnterpriseMetricsRecorder metrics = EnterpriseMetricsRecorder.create();
             TestAppInstance testApp = sTestApp.install()) {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{sTestApp.packageName()});
            Activity<TestAppActivity> activity = testApp.activities().any().start();

            try {
                activity.startLockTask();

                assertThat(metrics.query()
                        .whereType().isEqualTo(EventId.SET_LOCKTASK_MODE_ENABLED_VALUE)
                        .whereAdminPackageName().isEqualTo(sDeviceState.dpc().packageName())
                        .whereBoolean().isTrue()
                        .whereStrings().contains(sTestApp.packageName())
                ).wasLogged();
            } finally {
                activity.stopLockTask();
            }
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @CannotSetPolicyTest(policy = LockTaskFinance.class)
    public void getLockTaskPackages_policyIsNotAllowedToBeFetched_throwsException() {
        assertThrows(SecurityException.class,
                () -> sDeviceState.dpc().devicePolicyManager()
                        .getLockTaskPackages(sDeviceState.dpc().componentName()));
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void setLockTaskPackages_empty_lockTaskPackagesIsSet() {
        sDeviceState.dpc().devicePolicyManager()
                .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});

        try {
            assertThat(sDeviceState.dpc().devicePolicyManager()
                    .getLockTaskPackages(sDeviceState.dpc().componentName())).asList()
                    .isEmpty();
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    @RequireHasPolicyExemptApps
    public void setLockTaskPackages_includesPolicyExemptApp_lockTaskPackagesIsSet() {
        Set<String> policyExemptApps = TestApis.devicePolicy().getPolicyExemptApps();
        String policyExemptApp = policyExemptApps.iterator().next();

        sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                sDeviceState.dpc().componentName(), new String[]{policyExemptApp});

        try {
            assertThat(sDeviceState.dpc().devicePolicyManager()
                    .getLockTaskPackages(sDeviceState.dpc().componentName())).asList()
                    .containsExactly(policyExemptApp);
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @CannotSetPolicyTest(policy = LockTaskFinance.class)
    public void setLockTaskPackages_policyIsNotAllowedToBeSet_throwsException() {
        assertThrows(SecurityException.class,
                () -> sDeviceState.dpc().devicePolicyManager()
                        .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{}));
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void isLockTaskPermitted_lockTaskPackageIsSet_returnsTrue() {
        sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                sDeviceState.dpc().componentName(), new String[]{PACKAGE_NAME});

        try {
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(PACKAGE_NAME)).isTrue();
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyDoesNotApplyTest(policy = LockTaskFinance.class)
    // TODO(scottjonathan): Confirm expected behaviour here
    public void isLockTaskPermitted_lockTaskPackageIsSet_policyDoesntApply_returnsFalse() {
        sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                sDeviceState.dpc().componentName(), new String[]{PACKAGE_NAME});

        try {
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(PACKAGE_NAME)).isFalse();
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void isLockTaskPermitted_lockTaskPackageIsNotSet_returnsFalse() {
        sDeviceState.dpc().devicePolicyManager()
                .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});

        try {
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(PACKAGE_NAME)).isFalse();
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    @RequireHasPolicyExemptApps
    public void isLockTaskPermitted_includesPolicyExemptApps() {
        Set<String> policyExemptApps = TestApis.devicePolicy().getPolicyExemptApps();

        try {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});

            for (String app : policyExemptApps) {
                assertWithMessage("isLockTaskPermitted(%s)", app)
                        .that(sLocalDevicePolicyManager.isLockTaskPermitted(app)).isTrue();
            }
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    // TODO: Remove the breaking requirement to set features before packages...

    @CanSetPolicyTest(policy = LockTask.class)
    // TODO(b/188893663): Support additional parameterization for cases like this
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void setLockTaskFeatures_individuallySettableFlag_setsFeature(
            @IndividuallySettableFlagTestParameter int flag) {
        try {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{sTestApp.packageName()});
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskFeatures(sDeviceState.dpc().componentName(), flag);

            assertThat(sDeviceState.dpc().devicePolicyManager()
                    .getLockTaskFeatures(sDeviceState.dpc().componentName()))
                    .isEqualTo(flag);
        } finally {
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
        }
    }


    @CanSetPolicyTest(policy = LockTask.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void setLockTaskFeatures_overviewFeature_throwsException() {
        // Overview can only be used in combination with home
        try {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(),
                    new String[]{sTestApp.packageName()});
            assertThrows(IllegalArgumentException.class, () -> {
                sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                        sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_OVERVIEW);
            });
        } finally {
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{});
        }
    }

    @CanSetPolicyTest(policy = LockTask.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void setLockTaskFeatures_notificationsFeature_throwsException() {
        // Notifications can only be used in combination with home
        try {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{sTestApp.packageName()});
            assertThrows(IllegalArgumentException.class, () -> {
                sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                        sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NOTIFICATIONS);
            });
        } finally {
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                            sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
        }
    }

    @CanSetPolicyTest(policy = LockTask.class)
    // TODO(b/188893663): Support additional parameterization for cases like this
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void setLockTaskFeatures_multipleFeatures_setsFeatures(
            @SettableWithHomeFlagTestParameter int flag) {
        try {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{sTestApp.packageName()});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_HOME | flag);

            assertThat(sDeviceState.dpc().devicePolicyManager()
                    .getLockTaskFeatures(sDeviceState.dpc().componentName()))
                    .isEqualTo(LOCK_TASK_FEATURE_HOME | flag);
        } finally {
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{});
        }
    }

    @CannotSetPolicyTest(policy = LockTask.class)
    public void setLockTaskFeatures_policyIsNotAllowedToBeSet_throwsException() {
        assertThrows(SecurityException.class, () ->
                sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                        sDeviceState.dpc().componentName(),
                        LOCK_TASK_FEATURE_OVERVIEW | LOCK_TASK_FEATURE_HOME));
    }

    @CannotSetPolicyTest(policy = LockTaskFinance.class)
    public void getLockTaskFeatures_policyIsNotAllowedToBeFetched_throwsException() {
        assertThrows(SecurityException.class, () ->
                sDeviceState.dpc().devicePolicyManager()
                        .getLockTaskFeatures(sDeviceState.dpc().componentName()));
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void startLockTask_includedInLockTaskPackages_taskIsLocked() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                sDeviceState.dpc().componentName(), new String[]{sTestApp.packageName()});
        try (TestAppInstance testApp = sTestApp.install()) {
            Activity<TestAppActivity> activity = testApp.activities().any().start();

            activity.startLockTask();

            try {
                assertThat(TestApis.activities().foregroundActivity()).isEqualTo(
                        activity.activity().component());
                assertThat(TestApis.activities().getLockTaskModeState()).isEqualTo(
                        LOCK_TASK_MODE_LOCKED);
            } finally {
                activity.stopLockTask();
            }
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void startLockTask_notIncludedInLockTaskPackages_taskIsNotLocked() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        sDeviceState.dpc().devicePolicyManager()
                .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
        try (TestAppInstance testApp = sTestApp.install()) {
            Activity<TestAppActivity> activity = testApp.activities().any().start();

            activity.activity().startLockTask();

            try {
                assertThat(TestApis.activities().foregroundActivity()).isEqualTo(
                        activity.activity().component());
                assertThat(TestApis.activities().getLockTaskModeState()).isNotEqualTo(
                        LOCK_TASK_MODE_LOCKED);
            } finally {
                activity.stopLockTask();
            }
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyDoesNotApplyTest(policy = LockTaskFinance.class)
    public void startLockTask_includedInLockTaskPackages_policyShouldNotApply_taskIsNotLocked() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                sDeviceState.dpc().componentName(), new String[]{sTestApp.packageName()});
        try (TestAppInstance testApp = sTestApp.install()) {
            Activity<TestAppActivity> activity = testApp.activities().any().start();

            activity.activity().startLockTask();

            try {
                assertThat(TestApis.activities().foregroundActivity()).isEqualTo(
                        activity.activity().component());
                assertThat(TestApis.activities().getLockTaskModeState()).isNotEqualTo(
                        LOCK_TASK_MODE_LOCKED);
            } finally {
                activity.stopLockTask();
            }
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void finish_isLocked_doesNotFinish() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                sDeviceState.dpc().componentName(), new String[]{sTestApp.packageName()});
        try (TestAppInstance testApp = sTestApp.install()) {
            Activity<TestAppActivity> activity = testApp.activities().any().start();
            activity.startLockTask();

            activity.activity().finish();

            try {
                // We don't actually watch for the Destroyed event because that'd be waiting for a
                // non occurrence of an event which is slow
                assertThat(TestApis.activities().foregroundActivity()).isEqualTo(
                        activity.activity().component());
                assertThat(TestApis.activities().getLockTaskModeState()).isEqualTo(
                        LOCK_TASK_MODE_LOCKED);
            } finally {
                activity.stopLockTask();
            }
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void finish_hasStoppedLockTask_doesFinish() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                sDeviceState.dpc().componentName(), new String[]{sTestApp.packageName()});
        try (TestAppInstance testApp = sTestApp.install()) {
            Activity<TestAppActivity> activity = testApp.activities().any().start();
            activity.startLockTask();
            activity.stopLockTask();

            activity.activity().finish();

            assertThat(activity.activity().events().activityDestroyed()).eventOccurred();
            assertThat(TestApis.activities().foregroundActivity()).isNotEqualTo(
                    activity.activity().component());
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void setLockTaskPackages_removingCurrentlyLockedTask_taskFinishes() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                        sDeviceState.dpc().componentName(), new String[]{sTestApp.packageName()});
        try (TestAppInstance testApp = sTestApp.install()) {
            Activity<TestAppActivity> activity = testApp.activities().any().start();
            activity.startLockTask();

            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});

            assertThat(activity.activity().events().activityDestroyed()).eventOccurred();
            assertThat(TestApis.activities().foregroundActivity()).isNotEqualTo(
                    activity.activity().component());
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void setLockTaskPackages_removingCurrentlyLockedTask_otherLockedTasksRemainLocked() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        sDeviceState.dpc().devicePolicyManager()
                .setLockTaskPackages(sDeviceState.dpc().componentName(),
                        new String[]{sTestApp.packageName(), sSecondTestApp.packageName()});
        try (TestAppInstance testApp = sTestApp.install();
             TestAppInstance testApp2 = sSecondTestApp.install()) {
            Activity<TestAppActivity> activity =
                    testApp.activities().any().start(LAUNCH_FULLSCREEN_OPTIONS.toBundle());
            activity.startLockTask();
            Activity<TestAppActivity> activity2 =
                    testApp2.activities().any().start(LAUNCH_FULLSCREEN_OPTIONS.toBundle());
            activity2.startLockTask();

            try {
                sDeviceState.dpc().devicePolicyManager()
                        .setLockTaskPackages(sDeviceState.dpc().componentName(),
                                new String[]{sTestApp.packageName()});

                assertThat(activity2.activity().events().activityDestroyed()).eventOccurred();
                assertThat(TestApis.activities().getLockTaskModeState()).isEqualTo(
                        LOCK_TASK_MODE_LOCKED);
                assertThat(TestApis.activities().foregroundActivity()).isEqualTo(
                        activity.activity().component());
            } finally {
                activity.stopLockTask();
            }
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void startActivity_withinSameTask_startsActivity() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        try (TestAppInstance testApp = sTestApp.install();
             TestAppInstance testApp2 = sSecondTestApp.install()) {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(),
                    new String[]{sTestApp.packageName()});
            Activity<TestAppActivity> firstActivity = testApp.activities().any().start();
            TestAppActivityReference secondActivity = testApp2.activities().any();
            Intent secondActivityIntent = new Intent();
            // TODO(scottjonathan): Add filter to ensure no taskAffinity or launchMode which would
            //  stop launching in same task
            secondActivityIntent.setComponent(secondActivity.component().componentName());

            firstActivity.startActivity(secondActivityIntent);

            assertThat(secondActivity.events().activityStarted()).eventOccurred();
            assertThat(TestApis.activities().foregroundActivity())
                    .isEqualTo(secondActivity.component());
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyAppliesTest(policy = LockTask.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void startActivity_withinSameTask_blockStartInTask_doesNotStart() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        try (TestAppInstance testApp = sTestApp.install();
             TestAppInstance testApp2 = sSecondTestApp.install()) {
            try {
                sDeviceState.dpc().devicePolicyManager()
                        .setLockTaskPackages(sDeviceState.dpc().componentName(),
                                new String[]{sTestApp.packageName()});
                sDeviceState.dpc().devicePolicyManager()
                        .setLockTaskFeatures(sDeviceState.dpc().componentName(),
                                LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK);
                Activity<TestAppActivity> firstActivity = testApp.activities().any().start();
                firstActivity.startLockTask();
                TestAppActivityReference secondActivity = testApp2.activities().any();
                Intent secondActivityIntent = new Intent();
                secondActivityIntent.setComponent(secondActivity.component().componentName());

                firstActivity.activity().startActivity(secondActivityIntent);

                Poll.forValue("Foreground activity",
                        () -> TestApis.activities().foregroundActivity())
                        .toBeEqualTo(BLOCKED_ACTIVITY_COMPONENT)
                        .errorOnFail()
                        .await();
            } finally {
                sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                        sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
                sDeviceState.dpc().devicePolicyManager()
                        .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            }
        }
    }

    @PolicyAppliesTest(policy = LockTask.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void startActivity_inNewTask_blockStartInTask_doesNotStart() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        try (TestAppInstance testApp = sTestApp.install();
             TestAppInstance testApp2 = sSecondTestApp.install()) {
            try {
                sDeviceState.dpc().devicePolicyManager()
                        .setLockTaskPackages(sDeviceState.dpc().componentName(),
                                new String[]{sTestApp.packageName()});
                sDeviceState.dpc().devicePolicyManager()
                        .setLockTaskFeatures(
                                sDeviceState.dpc().componentName(),
                                LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK);
                Activity<TestAppActivity> firstActivity = testApp.activities().any().start();
                firstActivity.startLockTask();
                TestAppActivityReference secondActivity = testApp2.activities().any();
                Intent secondActivityIntent = new Intent();
                secondActivityIntent.setComponent(secondActivity.component().componentName());
                secondActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                firstActivity.activity().startActivity(secondActivityIntent);

                Poll.forValue("Foreground activity",
                        () -> TestApis.activities().foregroundActivity())
                        .toBeEqualTo(BLOCKED_ACTIVITY_COMPONENT)
                        .errorOnFail()
                        .await();
            } finally {
                sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                        sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
                sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                        sDeviceState.dpc().componentName(), new String[]{});
            }
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void startActivity_fromPermittedPackage_newTask_starts() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        try (TestAppInstance testApp = sTestApp.install();
             TestAppInstance testApp2 = sSecondTestApp.install()) {
            try {
                sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                        sDeviceState.dpc().componentName(),
                        new String[]{sTestApp.packageName(), sSecondTestApp.packageName()});
                Activity<TestAppActivity> firstActivity = testApp.activities().any().start();
                firstActivity.startLockTask();
                TestAppActivityReference secondActivity = testApp2.activities().any();
                Intent secondActivityIntent = new Intent();
                secondActivityIntent.setComponent(secondActivity.component().componentName());
                secondActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                firstActivity.startActivity(secondActivityIntent);

                assertThat(TestApis.activities().foregroundActivity())
                        .isEqualTo(secondActivity.component());
            } finally {
                sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                        sDeviceState.dpc().componentName(), new String[]{});
                sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                        sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
            }
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void startActivity_fromNonPermittedPackage_newTask_doesNotStart() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        try (TestAppInstance testApp = sTestApp.install();
             TestAppInstance testApp2 = sSecondTestApp.install()) {
            try {
                sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                        sDeviceState.dpc().componentName(), new String[]{sTestApp.packageName()});
                Activity<TestAppActivity> firstActivity = testApp.activities().any().start();
                firstActivity.startLockTask();
                TestAppActivityReference secondActivity = testApp2.activities().any();
                Intent secondActivityIntent = new Intent();
                secondActivityIntent.setComponent(secondActivity.component().componentName());
                secondActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                firstActivity.activity().startActivity(secondActivityIntent);

                assertThat(TestApis.activities().foregroundActivity())
                        .isEqualTo(firstActivity.activity().component());
            } finally {
                sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                        sDeviceState.dpc().componentName(), new String[]{});
                sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                        sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
            }
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void startActivity_lockTaskEnabledOption_startsInLockTaskMode() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        try (TestAppInstance testApp = sTestApp.install()) {
            try {
                sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                        sDeviceState.dpc().componentName(), new String[]{sTestApp.packageName()});
                Bundle options = ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle();
                Activity<TestAppActivity> activity = testApp.activities().any().start(options);

                try {
                    assertThat(TestApis.activities().foregroundActivity()).isEqualTo(
                            activity.activity().component());
                    assertThat(TestApis.activities().getLockTaskModeState()).isEqualTo(
                            LOCK_TASK_MODE_LOCKED);
                } finally {
                    activity.stopLockTask();
                }
            } finally {
                sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                        sDeviceState.dpc().componentName(), new String[]{});
                sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                        sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
            }
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void startActivity_lockTaskEnabledOption_notAllowedPackage_throwsException() {
        try (TestAppInstance testApp = sTestApp.install()) {
            try {
                sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                        sDeviceState.dpc().componentName(), new String[]{});
                Bundle options = ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle();

                assertThrows(SecurityException.class, () -> {
                    testApp.activities().any().start(options);
                });
            } finally {
                sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                        sDeviceState.dpc().componentName(), new String[]{});
                sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                        sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
            }
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void startActivity_ifWhitelistedActivity_startsInLockTaskMode() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        try (TestAppInstance testApp = sLockTaskTestApp.install()) {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(),
                    new String[]{sLockTaskTestApp.packageName()});
            Activity<TestAppActivity> activity = testApp.activities().query()
                    .whereActivity().activityClass().simpleName()
                    .isEqualTo("ifwhitelistedactivity")
                    // TODO(scottjonathan): filter for lock task mode - currently we can't check
                    //  this so we just get a fixed package which contains a fixed activity
                    .get().start();

            try {
                assertThat(TestApis.activities().foregroundActivity()).isEqualTo(
                        activity.activity().component());
                assertThat(TestApis.activities().getLockTaskModeState()).isEqualTo(
                        LOCK_TASK_MODE_LOCKED);
            } finally {
                activity.stopLockTask();
            }
        } finally {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void startActivity_ifWhitelistedActivity_notWhitelisted_startsNotInLockTaskMode() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        try (TestAppInstance testApp = sLockTaskTestApp.install()) {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{});
            Activity<TestAppActivity> activity = testApp.activities().query()
                    .whereActivity().activityClass().simpleName()
                    .isEqualTo("ifwhitelistedactivity")
                    // TODO(scottjonathan): filter for lock task mode - currently we can't check
                    //  this so we just get a fixed package which contains a fixed activity
                    .get().start();

            try {
                assertThat(TestApis.activities().foregroundActivity()).isEqualTo(
                        activity.activity().component());
                assertThat(TestApis.activities().getLockTaskModeState()).isEqualTo(
                        LOCK_TASK_MODE_NONE);
            } finally {
                activity.stopLockTask();
            }
        } finally {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void finish_ifWhitelistedActivity_doesNotFinish() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        try (TestAppInstance testApp = sLockTaskTestApp.install()) {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(),
                    new String[]{sLockTaskTestApp.packageName()});
            Activity<TestAppActivity> activity = testApp.activities().query()
                    .whereActivity().activityClass().simpleName()
                    .isEqualTo("ifwhitelistedactivity")
                    // TODO(scottjonathan): filter for lock task mode - currently we can't check
                    //  this so we just get a fixed package which contains a fixed activity
                    .get().start();

            activity.activity().finish();

            try {
                // We don't actually watch for the Destroyed event because that'd be waiting for a
                // non occurrence of an event which is slow
                assertThat(TestApis.activities().foregroundActivity()).isEqualTo(
                        activity.activity().component());
                assertThat(TestApis.activities().getLockTaskModeState()).isEqualTo(
                        LOCK_TASK_MODE_LOCKED);
            } finally {
                activity.stopLockTask();
            }
        } finally {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void setLockTaskPackages_removingExistingIfWhitelistedActivity_stopsTask() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        try (TestAppInstance testApp = sLockTaskTestApp.install()) {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(),
                    new String[]{sLockTaskTestApp.packageName()});
            Activity<TestAppActivity> activity = testApp.activities().query()
                    .whereActivity().activityClass().simpleName()
                    .isEqualTo("ifwhitelistedactivity")
                    // TODO(scottjonathan): filter for lock task mode - currently we can't check
                    //  this so we just get a fixed package which contains a fixed activity
                    .get().start();

            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{});

            assertThat(activity.activity().events().activityDestroyed()).eventOccurred();
            assertThat(TestApis.activities().foregroundActivity()).isNotEqualTo(
                    activity.activity().component());
        } finally {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyAppliesTest(policy = LockTaskFinance.class)
    @RequireFeature(FEATURE_TELEPHONY)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    // Tests that the default dialer doesn't crash or otherwise misbehave in lock task mode
    public void launchDefaultDialerInLockTaskMode_launches() {
        TelecomManager telecomManager =
                TestApis.context().instrumentedContext().getSystemService(TelecomManager.class);
        String dialerPackage = telecomManager.getSystemDialerPackage();
        try {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{dialerPackage});

            Bundle options = ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle();
            Intent intent = new Intent(ACTION_DIAL);
            intent.setPackage(dialerPackage);
            intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);

            TestApis.context().instrumentedContext().startActivity(intent, options);

            Poll.forValue("Foreground package",
                    () -> TestApis.activities().foregroundActivity().pkg())
                    .toMeet(pkg -> pkg != null && pkg.packageName().equals(dialerPackage))
                    .errorOnFail()
                    .await();

            Poll.forValue("Lock task mode state",
                    () -> TestApis.activities().getLockTaskModeState())
                    .toBeEqualTo(LOCK_TASK_MODE_LOCKED)
                    .errorOnFail()
                    .await();
        } finally {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @PolicyAppliesTest(policy = LockTask.class)
    @RequireFeature(FEATURE_TELEPHONY)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void launchEmergencyDialerInLockTaskMode_notWhitelisted_noKeyguardFeature_doesNotLaunch() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        String emergencyDialerPackageName = getEmergencyDialerPackageName();
        assumeFalse(emergencyDialerPackageName == null);
        try (TestAppInstance testApp = sLockTaskTestApp.install()) {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(),
                    new String[]{sLockTaskTestApp.packageName()});
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskFeatures(sDeviceState.dpc().componentName(), 0);
            Activity<TestAppActivity> activity = testApp.activities().any().start();

            try {
                activity.startLockTask();
                Intent intent = new Intent(ACTION_EMERGENCY_DIAL);
                intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);

                activity.activity().startActivity(intent);

                if (TestApis.activities().foregroundActivity() != null) {
                    assertThat(TestApis.activities().foregroundActivity().pkg().packageName())
                            .isNotEqualTo(emergencyDialerPackageName);
                }
            } finally {
                activity.stopLockTask();
            }
        } finally {
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{});
        }
    }

    @PolicyAppliesTest(policy = LockTask.class)
    @RequireFeature(FEATURE_TELEPHONY)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    public void launchEmergencyDialerInLockTaskMode_notWhitelisted_keyguardFeature_launches() {
        assumeTrue("Test requires showing activities",
                TestApis.users().instrumented().canShowActivities());

        String emergencyDialerPackageName = getEmergencyDialerPackageName();
        assumeFalse(emergencyDialerPackageName == null);
        try (TestAppInstance testApp = sLockTaskTestApp.install()) {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(),
                    new String[]{sLockTaskTestApp.packageName()});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_KEYGUARD);
            Activity<TestAppActivity> activity = testApp.activities().any().start();
            try {
                activity.startLockTask();
                Intent intent = new Intent(ACTION_EMERGENCY_DIAL);
                intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);

                activity.startActivity(intent);

                assertThat(TestApis.activities().foregroundActivity().pkg())
                        .isEqualTo(TestApis.packages().find(emergencyDialerPackageName));
                assertThat(TestApis.activities().getLockTaskModeState()).isEqualTo(
                        LOCK_TASK_MODE_LOCKED);
            } finally {
                activity.stopLockTask();
            }
        } finally {
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{});
        }
    }

    @Test
    @Postsubmit(reason = "new test")
    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setLockTaskPackages",
            "android.app.admin.DevicePolicyManager#getDevicePolicyState"})
    @PolicyAppliesTest(policy = LockTask.class)
    public void getDevicePolicyState_setLockTaskPackagesAndFeatures_returnsPolicy() {
        try {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{PACKAGE_NAME});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE);

            PolicyState<LockTaskPolicy> policyState = PolicyEngineUtils.getLockTaskPolicyState(
                    new NoArgsPolicyKey(LOCK_TASK_POLICY),
                    TestApis.users().instrumented().userHandle());

            assertThat(policyState.getCurrentResolvedPolicy().getPackages())
                    .containsExactly(PACKAGE_NAME);
            assertThat(policyState.getCurrentResolvedPolicy().getFlags())
                    .isEqualTo(LOCK_TASK_FEATURE);
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @Test
    @Postsubmit(reason = "new test")
    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setLockTaskPackages"})
    // TODO: enable after adding the broadcast receiver to relevant test apps.
//    @PolicyAppliesTest(policy = LockTask.class)
    @EnsureHasDeviceOwner(isPrimary = true)
    public void policyUpdateReceiver_setLockTaskPackages_receivedPolicySetBroadcast() {
        try {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{PACKAGE_NAME});

            PolicySetResultUtils.assertPolicySetResultReceived(
                    sDeviceState, LOCK_TASK_POLICY, PolicyUpdateResult.RESULT_POLICY_SET,
                    LOCAL_USER_ID, new Bundle());
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @Test
    @Postsubmit(reason = "new test")
    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setLockTaskPackages",
            "android.app.admin.DevicePolicyManager#getDevicePolicyState"})
    @CanSetPolicyTest(policy = LockTask.class, singleTestOnly = true)
    public void getDevicePolicyState_setLockTaskPackages_returnsCorrectResolutionMechanism() {
        try {
            sDeviceState.dpc().devicePolicyManager().setLockTaskPackages(
                    sDeviceState.dpc().componentName(), new String[]{PACKAGE_NAME});

            PolicyState<LockTaskPolicy> policyState = PolicyEngineUtils.getLockTaskPolicyState(
                    new NoArgsPolicyKey(LOCK_TASK_POLICY),
                    sDeviceState.dpc().user().userHandle());

            assertThat(PolicyEngineUtils.getTopPriorityMechanism(policyState)
                    .getHighestToLowestPriorityAuthorities()).isEqualTo(
                            List.of(
                                    new RoleAuthority(Set.of(FINANCED_DEVICE_CONTROLLER_ROLE)),
                                    DpcAuthority.DPC_AUTHORITY));
        } finally {
            sDeviceState.dpc().devicePolicyManager()
                    .setLockTaskPackages(sDeviceState.dpc().componentName(), new String[]{});
            sDeviceState.dpc().devicePolicyManager().setLockTaskFeatures(
                    sDeviceState.dpc().componentName(), LOCK_TASK_FEATURE_NONE);
        }
    }

    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setLockTaskPackages",
            "android.app.admin.DevicePolicyManager#isLockTaskPermitted"})
    @MostImportantCoexistenceTest(policy = LockTask.class)
    public void setLockTaskPackages_sameValues_applied() {
        try {
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sTestApp.packageName()});
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sTestApp.packageName()});

            PolicyState<LockTaskPolicy> policyState = PolicyEngineUtils.getLockTaskPolicyState(
                    new NoArgsPolicyKey(LOCK_TASK_POLICY),
                    TestApis.users().instrumented().userHandle());
            assertThat(policyState.getCurrentResolvedPolicy().getPackages())
                    .containsExactly(sTestApp.packageName());
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(sTestApp.packageName()))
                    .isTrue();

        } finally {
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null, new String[]{});
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null, new String[]{});
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);
        }
    }

    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setLockTaskPackages",
            "android.app.admin.DevicePolicyManager#isLockTaskPermitted"})
    @MostImportantCoexistenceTest(policy = LockTask.class)
    public void setLockTaskPackages_differentValues_moreImportantApplied() {
        try {
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sTestApp.packageName()});
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sSecondTestApp.packageName()});

            PolicyState<LockTaskPolicy> policyState = PolicyEngineUtils.getLockTaskPolicyState(
                    new NoArgsPolicyKey(LOCK_TASK_POLICY),
                    TestApis.users().instrumented().userHandle());
            assertThat(policyState.getCurrentResolvedPolicy().getPackages())
                    .containsExactly(sTestApp.packageName());
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(sTestApp.packageName()))
                    .isTrue();
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(sSecondTestApp.packageName()))
                    .isFalse();

        } finally {
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null, new String[]{});
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null, new String[]{});
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);
        }
    }

    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setLockTaskPackages",
            "android.app.admin.DevicePolicyManager#isLockTaskPermitted"})
    @MostImportantCoexistenceTest(policy = LockTask.class)
    public void setLockTaskPackages_differentValuesReverseOrder_moreImportantApplied() {
        try {
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sSecondTestApp.packageName()});
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sTestApp.packageName()});

            PolicyState<LockTaskPolicy> policyState = PolicyEngineUtils.getLockTaskPolicyState(
                    new NoArgsPolicyKey(LOCK_TASK_POLICY),
                    TestApis.users().instrumented().userHandle());
            assertThat(policyState.getCurrentResolvedPolicy().getPackages())
                    .containsExactly(sTestApp.packageName());
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(sTestApp.packageName()))
                    .isTrue();
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(sSecondTestApp.packageName()))
                    .isFalse();

        } finally {
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null, new String[]{});
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null, new String[]{});
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);
        }
    }

    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setLockTaskPackages",
            "android.app.admin.DevicePolicyManager#isLockTaskPermitted"})
    @MostImportantCoexistenceTest(policy = LockTask.class)
    public void setLockTaskPackages_bothSetThenBothReset_nothingApplied() {
        try {
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sTestApp.packageName()});
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sSecondTestApp.packageName()});
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{});
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{});
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);

            PolicyState<LockTaskPolicy> policyState = PolicyEngineUtils.getLockTaskPolicyState(
                    new NoArgsPolicyKey(LOCK_TASK_POLICY),
                    TestApis.users().instrumented().userHandle());
            assertThat(policyState).isNull();
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(sTestApp.packageName()))
                    .isFalse();
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(sSecondTestApp.packageName()))
                    .isFalse();

        } finally {
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null, new String[]{});
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null, new String[]{});
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);
        }
    }

    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setLockTaskPackages",
            "android.app.admin.DevicePolicyManager#isLockTaskPermitted"})
    @MostImportantCoexistenceTest(policy = LockTask.class)
    public void setLockTaskPackages_bothSetThenMoreImportantResets_lessImportantApplied() {
        try {
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sTestApp.packageName()});
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sSecondTestApp.packageName()});
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{});
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);

            PolicyState<LockTaskPolicy> policyState = PolicyEngineUtils.getLockTaskPolicyState(
                    new NoArgsPolicyKey(LOCK_TASK_POLICY),
                    TestApis.users().instrumented().userHandle());
            assertThat(policyState.getCurrentResolvedPolicy().getPackages())
                    .containsExactly(sSecondTestApp.packageName());
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(sTestApp.packageName()))
                    .isFalse();
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(sSecondTestApp.packageName()))
                    .isTrue();

        } finally {
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null, new String[]{});
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null, new String[]{});
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);
        }
    }

    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setLockTaskPackages",
            "android.app.admin.DevicePolicyManager#isLockTaskPermitted"})
    @MostImportantCoexistenceTest(policy = LockTask.class)
    public void setLockTaskPackages_bothSetSameValueThenMoreImportantResets_lessImportantApplied() {
        try {
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sTestApp.packageName()});
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sTestApp.packageName()});
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{});
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);

            PolicyState<LockTaskPolicy> policyState = PolicyEngineUtils.getLockTaskPolicyState(
                    new NoArgsPolicyKey(LOCK_TASK_POLICY),
                    TestApis.users().instrumented().userHandle());
            assertThat(policyState.getCurrentResolvedPolicy().getPackages())
                    .containsExactly(sTestApp.packageName());
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(sTestApp.packageName()))
                    .isTrue();

        } finally {
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null, new String[]{});
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null, new String[]{});
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);
        }
    }

    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setLockTaskPackages",
            "android.app.admin.DevicePolicyManager#isLockTaskPermitted"})
    @MostImportantCoexistenceTest(policy = LockTask.class)
    public void setLockTaskPackages_moreImportantSetsFeaturesOnly_moreImportantApplies() {
        try {
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE);
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sSecondTestApp.packageName()});
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    SECOND_LOCK_TASK_FEATURE);

            PolicyState<LockTaskPolicy> policyState = PolicyEngineUtils.getLockTaskPolicyState(
                    new NoArgsPolicyKey(LOCK_TASK_POLICY),
                    TestApis.users().instrumented().userHandle());
            assertThat(policyState.getCurrentResolvedPolicy().getPackages())
                    .isEmpty();
            assertThat(policyState.getCurrentResolvedPolicy().getFlags())
                    .isEqualTo(LOCK_TASK_FEATURE);
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(sSecondTestApp.packageName()))
                    .isFalse();

        } finally {
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null, new String[]{});
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null, new String[]{});
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                    /* componentName= */ null,
                    LOCK_TASK_FEATURE_NONE);
        }
    }

    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setLockTaskPackages",
            "android.app.admin.DevicePolicyManager#isLockTaskPermitted"})
    @MostImportantCoexistenceTest(policy = LockTask.class)
    public void setLockTaskPackages_setByDPCAndPermission_DPCRemoved_stillEnforced() {
        try {
            sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sTestApp.packageName()});
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sSecondTestApp.packageName()});

            // Remove DPC
            sDeviceState.dpc().devicePolicyManager().clearDeviceOwnerApp(
                    sDeviceState.dpc().packageName());

            PolicyState<LockTaskPolicy> policyState = PolicyEngineUtils.getLockTaskPolicyState(
                    new NoArgsPolicyKey(LOCK_TASK_POLICY),
                    TestApis.users().instrumented().userHandle());
            assertThat(policyState.getCurrentResolvedPolicy().getPackages())
                    .containsExactly(sSecondTestApp.packageName());
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(sTestApp.packageName()))
                    .isFalse();
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(sSecondTestApp.packageName()))
                    .isTrue();

        } finally {
            try {
                sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                        /* componentName= */ null, new String[]{});
                sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                        /* componentName= */ null,
                        LOCK_TASK_FEATURE_NONE);
            } catch (Exception e) {
                // expected if app was uninstalled
            }
            try {
                sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                        /* componentName= */ null, new String[]{});
                sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                        /* componentName= */ null,
                        LOCK_TASK_FEATURE_NONE);
            } catch (Exception e) {
                // expected if app was uninstalled
            }
        }
    }

    @ApiTest(apis = {"android.app.admin.DevicePolicyManager#setLockTaskPackages",
            "android.app.admin.DevicePolicyManager#isLockTaskPermitted"})
    @MostImportantCoexistenceTest(policy = LockTask.class)
    public void setLockTaskPackages_setByPermission_appRemoved_notEnforced() {
        try {
            sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                    /* componentName= */ null,
                    new String[]{sTestApp.packageName()});

            // uninstall app
            sDeviceState.testApp(LESS_IMPORTANT).uninstall();
            SystemClock.sleep(500);

            PolicyState<LockTaskPolicy> policyState = PolicyEngineUtils.getLockTaskPolicyState(
                    new NoArgsPolicyKey(LOCK_TASK_POLICY),
                    TestApis.users().instrumented().userHandle());
            assertThat(policyState).isNull();
            assertThat(sLocalDevicePolicyManager.isLockTaskPermitted(sTestApp.packageName()))
                    .isFalse();
        } finally {
            try {
                sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                        /* componentName= */ null, new String[]{});
                sDeviceState.testApp(MORE_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                        /* componentName= */ null,
                        LOCK_TASK_FEATURE_NONE);
            } catch (Exception e) {
                // expected if app was uninstalled
            }
            try {
                sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskPackages(
                        /* componentName= */ null, new String[]{});
                sDeviceState.testApp(LESS_IMPORTANT).devicePolicyManager().setLockTaskFeatures(
                        /* componentName= */ null,
                        LOCK_TASK_FEATURE_NONE);
            } catch (Exception e) {
                // expected if app was uninstalled
            }
        }
    }

    private String getEmergencyDialerPackageName() {
        PackageManager packageManager =
                TestApis.context().instrumentedContext().getPackageManager();
        Intent intent = new Intent(ACTION_EMERGENCY_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ResolveInfo dialerInfo =
                packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        return (dialerInfo != null) ? dialerInfo.activityInfo.packageName : null;
    }
}
