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

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.devicepolicy.cts.utils.TestArtifactUtils.dumpWindowHierarchy;
import static android.location.LocationManager.FUSED_PROVIDER;

import static com.android.bedstead.nene.permissions.CommonPermissions.ACCESS_BACKGROUND_LOCATION;
import static com.android.bedstead.nene.permissions.CommonPermissions.ACCESS_COARSE_LOCATION;
import static com.android.bedstead.nene.permissions.CommonPermissions.ACCESS_FINE_LOCATION;
import static com.android.bedstead.nene.permissions.CommonPermissions.ACTIVITY_RECOGNITION;
import static com.android.bedstead.nene.permissions.CommonPermissions.CAMERA;
import static com.android.bedstead.nene.permissions.CommonPermissions.INTERACT_ACROSS_USERS_FULL;
import static com.android.bedstead.nene.permissions.CommonPermissions.MANAGE_PROFILE_AND_DEVICE_OWNERS;
import static com.android.bedstead.nene.permissions.CommonPermissions.NEARBY_WIFI_DEVICES;
import static com.android.bedstead.nene.permissions.CommonPermissions.RECORD_AUDIO;
import static com.android.queryable.queries.ActivityQuery.activity;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assume.assumeFalse;

import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;

import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.UserType;
import com.android.bedstead.harrier.annotations.EnsureHasPermission;
import com.android.bedstead.harrier.annotations.EnsureHasWorkProfile;
import com.android.bedstead.harrier.annotations.Postsubmit;
import com.android.bedstead.harrier.annotations.RequireRunOnWorkProfile;
import com.android.bedstead.harrier.annotations.StringTestParameter;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasDevicePolicyManagerRoleHolder;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.appops.AppOpsMode;
import com.android.bedstead.nene.location.LocationProvider;
import com.android.bedstead.nene.packages.Package;
import com.android.bedstead.nene.permissions.PermissionContext;
import com.android.bedstead.nene.users.UserReference;
import com.android.bedstead.nene.utils.Poll;
import com.android.bedstead.nene.utils.ShellCommand;
import com.android.bedstead.testapp.TestApp;
import com.android.bedstead.testapp.TestAppActivityReference;
import com.android.bedstead.testapp.TestAppInstance;

import org.junit.After;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests to ensure apps are properly restricted when the user is in quiet mode.
 */
@RunWith(BedsteadJUnit4.class)
public class QuietModeTest {
    private static final double TEST_LATITUDE = 51.5;
    private static final double TEST_LONGITUDE = -0.1;
    private static final float TEST_ACCURACY = 14.0f;

    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();
    private static final Context sContext = TestApis.context().instrumentedContext();

    private static final TestApp sTestApp = sDeviceState.testApps().query()
            .whereActivities()
            .contains(activity().where().exported().isTrue())
            .get();
    private static final String UNSUSPENDABLE_PKG_NAME = TestApis.context().instrumentedContext()
            .getPackageManager().getPermissionControllerPackageName();

    private boolean mKeepProfilesRunningOverridden = false;

    @After
    public void cleanupKeepProfilesRunningOverride() {
        if (mKeepProfilesRunningOverridden) {
            overrideKeepProfilesRunning(false);
            mKeepProfilesRunningOverridden = false;
        }
    }

    @Postsubmit(reason = "new test")
    @EnsureHasWorkProfile
    @EnsureHasPermission(INTERACT_ACROSS_USERS_FULL)
    @Test
    public void startActivityInQuietProfile_quietModeDialogShown() throws Exception {
        UserReference workProfile = sDeviceState.workProfile();
        String titleText = sContext.getString(R.string.test_string_1);
        try (TestAppInstance instance = sTestApp.install(workProfile)) {
            // Override "Turn on work apps" dialog title to avoid depending on a particular string.
            TestApis.devicePolicy().resources().strings().set(
                    "Core.UNLAUNCHABLE_APP_WORK_PAUSED_TITLE", R.string.test_string_1);

            TestAppActivityReference activityReference =
                    instance.activities().query().whereActivity().exported().isTrue().get();

            workProfile.setQuietMode(true);

            Intent intent = new Intent()
                    .addFlags(FLAG_ACTIVITY_NEW_TASK)
                    .setComponent(activityReference.component().componentName());
            sContext.startActivityAsUser(intent, new Bundle(), workProfile.userHandle());

            UiObject2 dialogTitle = TestApis.ui().device().wait(
                    Until.findObject(By.text(titleText)), 5000 /* 5s */);
            assertWithMessage("Work mode dialog not shown").that(dialogTitle).isNotNull();
        } catch (AssertionError e) {
            dumpWindowHierarchy("startActivityInQuietProfile_quietModeDialogShown");
            throw e;
        } finally {
            TestApis.devicePolicy().resources().strings()
                    .reset("Core.UNLAUNCHABLE_APP_WORK_PAUSED_TITLE");
            workProfile.setQuietMode(false);
        }
    }

    @Postsubmit(reason = "new test")
    @EnsureHasWorkProfile(dpcIsPrimary = true)
    @EnsureHasPermission(INTERACT_ACROSS_USERS_FULL)
    @Test
    public void quietMode_profileKeptRunning() throws Exception {
        ensureKeepProfilesRunningEnabled();

        UserReference workProfile = sDeviceState.workProfile();
        try {
            workProfile.setQuietMode(true);

            // If user is being stopped, let it finish.
            TestApis.broadcasts().waitForBroadcastBarrier("Ensure asynchronous work done");

            assertThat(workProfile.isRunning()).isTrue();
            // This requires running code in the profile user and will throw an exception if the
            // user is stopped.
            assertThat(sDeviceState.dpc().userManager().isManagedProfile()).isTrue();
            // The DPC should be suspended in quiet mode if the profile is kept running.
            assertThat(sContext.getPackageManager().isPackageSuspendedForUser(
                    sDeviceState.dpc().packageName(), workProfile.id())).isTrue();
        } finally {
            workProfile.setQuietMode(false);
        }
    }

    @Postsubmit(reason = "new test")
    @EnsureHasWorkProfile(dpcIsPrimary = true)
    @EnsureHasPermission(INTERACT_ACROSS_USERS_FULL)
    @Test
    public void quietMode_profileStopped() throws Exception {
        assumeFalse("Keep profiles available feature is enabled", keepProfilesRunningEnabled());
        UserReference workProfile = sDeviceState.workProfile();

        workProfile.setQuietMode(true);
        try {
            // Profile should be stopped.
            Poll.forValue("profile running", workProfile::isRunning).toBeEqualTo(false)
                    .errorOnFail().await();

            // The DPC shouldn't be suspended.
            assertThat(sDeviceState.dpc().testApp().pkg().isSuspended(workProfile)).isFalse();
        } finally {
            workProfile.setQuietMode(false);
        }
    }

    @Postsubmit(reason = "new test")
    @RequireRunOnWorkProfile
    @EnsureHasPermission({ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, ACCESS_BACKGROUND_LOCATION})
    @Test
    public void quietMode_noLocationAccess() throws Exception {
        ensureKeepProfilesRunningEnabled();

        UserReference workProfile = sDeviceState.workProfile();

        try (LocationProvider provider = TestApis.location().addLocationProvider(FUSED_PROVIDER)) {
            provider.setLocation(TEST_LATITUDE, TEST_LONGITUDE, TEST_ACCURACY);
            LocationManager lm = sContext.getSystemService(LocationManager.class);

            // Location should be accessible pre quiet mode.
            Location loc = lm.getLastKnownLocation(FUSED_PROVIDER);
            assertWithMessage("Location not available").that(loc).isNotNull();
            assertWithMessage("Unexpected latitude")
                    .that(loc.getLatitude()).isEqualTo(TEST_LATITUDE);
            assertWithMessage("Unexpected longitude")
                    .that(loc.getLongitude()).isEqualTo(TEST_LONGITUDE);

            workProfile.setQuietMode(true);

            loc = lm.getLastKnownLocation(FUSED_PROVIDER);
            assertWithMessage("Location still available in quiet mode").that(loc).isNull();
        } finally {
            workProfile.setQuietMode(false);
        }
    }

    @Postsubmit(reason = "new test")
    @RequireRunOnWorkProfile
    @EnsureHasPermission({ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, ACCESS_BACKGROUND_LOCATION,
            ACTIVITY_RECOGNITION, NEARBY_WIFI_DEVICES, RECORD_AUDIO, CAMERA})
    @Test
    public void quietMode_sensitiveAppOpsNotAllowed(@StringTestParameter({
            AppOpsManager.OPSTR_COARSE_LOCATION,
            AppOpsManager.OPSTR_FINE_LOCATION,
            AppOpsManager.OPSTR_GPS,
            AppOpsManager.OPSTR_BODY_SENSORS,
            AppOpsManager.OPSTR_ACTIVITY_RECOGNITION,
            AppOpsManager.OPSTR_BLUETOOTH_SCAN,
            AppOpsManager.OPSTR_NEARBY_WIFI_DEVICES,
            AppOpsManager.OPSTR_RECORD_AUDIO,
            AppOpsManager.OPSTR_CAMERA,
    }) String opStr) throws Exception {

        ensureKeepProfilesRunningEnabled();

        assertWithMessage("App op " + opStr + " isn't allowed")
                .that(TestApis.packages().instrumented().appOps().get(opStr))
                .isEqualTo(AppOpsMode.ALLOWED);

        UserReference workProfile = sDeviceState.workProfile();
        workProfile.setQuietMode(true);
        try {
            assertWithMessage("App op " + opStr + " is allowed")
                    .that(TestApis.packages().instrumented().appOps().get(opStr))
                    .isEqualTo(AppOpsMode.IGNORED);
        } finally {
            workProfile.setQuietMode(false);
        }
    }

    /** Admin suspends package while in quiet mode - should remain suspended after leaving. */
    @Postsubmit(reason = "new test")
    @RequireRunOnWorkProfile
    @Test
    public void quietMode_suspendedApp_remainsSuspended() throws Exception {
        ensureKeepProfilesRunningEnabled();

        UserReference workProfile = sDeviceState.workProfile();

        try (TestAppInstance instance = sTestApp.install(workProfile)) {
            String[] pkgs = new String[]{sTestApp.packageName()};

            workProfile.setQuietMode(true);
            try {
                assertWithMessage("Failed to suspend package while in quiet mode")
                        .that(sDeviceState.dpc().devicePolicyManager().setPackagesSuspended(
                                sDeviceState.dpc().componentName(), pkgs, true).length)
                        .isEqualTo(0);
            } finally {
                workProfile.setQuietMode(false);
            }

            assertWithMessage("Admin-suspended package unsuspended after leaving quiet mode")
                    .that(sTestApp.pkg().isSuspended(workProfile)).isTrue();
        }
    }

    /** Package already suspended by admin should not get unsuspended after toggling quiet mode. */
    @Postsubmit(reason = "new test")
    @RequireRunOnWorkProfile
    @Test
    public void quietMode_alreadySuspendedApp_remainsSuspended() throws Exception {
        ensureKeepProfilesRunningEnabled();

        UserReference workProfile = sDeviceState.workProfile();

        try (TestAppInstance instance = sTestApp.install(workProfile)) {
            String[] pkgs = new String[]{sTestApp.packageName()};
            sDeviceState.dpc().devicePolicyManager()
                    .setPackagesSuspended(sDeviceState.dpc().componentName(), pkgs, true);

            workProfile.setQuietMode(true);
            workProfile.setQuietMode(false);

            assertWithMessage("Admin-suspended package unsuspended after leaving quiet mode")
                    .that(sTestApp.pkg().isSuspended(workProfile)).isTrue();
        }
    }

    /** Verifies that package not suspendable by admin is still suspended in quiet mode */
    @Postsubmit(reason = "new test")
    @RequireRunOnWorkProfile
    @Test
    public void quietMode_unsuspendablePackageGetsSuspended() throws Exception {
        ensureKeepProfilesRunningEnabled();

        UserReference workProfile = sDeviceState.workProfile();
        Package permController = Package.of(TestApis.context().instrumentedContext()
                .getPackageManager().getPermissionControllerPackageName());

        String[] pkgs = new String[]{permController.packageName()};

        workProfile.setQuietMode(true);
        try {
            assertWithMessage("Unsuspendable not suspended in quiet mode")
                    .that(permController.isSuspended(workProfile)).isTrue();
        } finally {
            workProfile.setQuietMode(false);
        }
    }

    /** Verifies that unsuspendable for admin package remains such in quiet mode */
    @Postsubmit(reason = "new test")
    @RequireRunOnWorkProfile
    @Test
    public void quietMode_adminCannotSuspendUnsuspendablePackage() throws Exception {
        ensureKeepProfilesRunningEnabled();

        UserReference workProfile = sDeviceState.workProfile();
        Package permController = Package.of(UNSUSPENDABLE_PKG_NAME);

        String[] pkgs = new String[]{permController.packageName()};

        workProfile.setQuietMode(true);
        try {
            assertWithMessage("Was able to suspend unsuspendable package")
                    .that(sDeviceState.dpc().devicePolicyManager().setPackagesSuspended(
                            sDeviceState.dpc().componentName(), pkgs, true))
                    .asList().containsExactly(permController.packageName());
        } finally {
            workProfile.setQuietMode(false);
        }

        assertWithMessage("Unsuspendable package still suspended after leaving quiet mode")
                .that(permController.isSuspended(workProfile)).isFalse();
    }

    /** Verifies that profile owner package gets suspended in quiet mode */
    @Postsubmit(reason = "new test")
    @RequireRunOnWorkProfile
    @Test
    public void quietMode_dpcPackageGetsSuspended() throws Exception {
        ensureKeepProfilesRunningEnabled();

        UserReference workProfile = sDeviceState.workProfile();
        Package dpcPackage = Package.of(sDeviceState.dpc().packageName());

        workProfile.setQuietMode(true);
        try {
            assertWithMessage("Profile owner package was not suspended in quiet mode")
                    .that(dpcPackage.isSuspended(workProfile)).isTrue();
        } finally {
            workProfile.setQuietMode(false);
        }

        assertWithMessage("Unsuspendable package still suspended after leaving quiet mode")
                .that(dpcPackage.isSuspended(workProfile)).isFalse();
    }

    /** Verifies that profile owner package gets suspended in quiet mode */
    @Postsubmit(reason = "new test")
    @RequireRunOnWorkProfile
    @EnsureHasDevicePolicyManagerRoleHolder(onUser = UserType.WORK_PROFILE)
    @Test
    public void quietMode_mgmtRoleHolderPackageNotSuspended() throws Exception {
        ensureKeepProfilesRunningEnabled();

        UserReference workProfile = sDeviceState.workProfile();
        Package roleHolderPackage = Package.of(sDeviceState.dpmRoleHolder().packageName());

        workProfile.setQuietMode(true);
        try {

            assertWithMessage("Mgmt role holder package was suspended by admin in quiet mode")
                    .that(roleHolderPackage.isSuspended(workProfile)).isFalse();
        } finally {
            workProfile.setQuietMode(false);
        }
    }

    /** Verifies that admin cannot unsuspend unsuspendable package while in quiet mode. */
    @Postsubmit(reason = "new test")
    @RequireRunOnWorkProfile
    @Test
    public void quietMode_adminCannotUnsuspendUnsuspendablePackage() throws Exception {
        ensureKeepProfilesRunningEnabled();

        UserReference workProfile = sDeviceState.workProfile();
        Package permController = Package.of(UNSUSPENDABLE_PKG_NAME);

        String[] pkgs = new String[]{permController.packageName()};

        workProfile.setQuietMode(true);
        try {
            // When admin tries to unsuspend an unsuspendable package, the existing behavior is to
            // ignore it as if it succeeded - verify it is observed in quiet mode as well
            assertWithMessage("Unexpected failure to unsuspend unsuspendable package")
                    .that(sDeviceState.dpc().devicePolicyManager().setPackagesSuspended(
                            sDeviceState.dpc().componentName(), pkgs, false))
                    .asList().isEmpty();
            assertWithMessage("Unsuspendable package was unsuspended by admin in quiet mode")
                    .that(permController.isSuspended(workProfile)).isTrue();
        } finally {
            workProfile.setQuietMode(false);
        }

        assertWithMessage("Unsuspendable package still suspended after leaving quiet mode")
                .that(permController.isSuspended(workProfile)).isFalse();
    }

    private void overrideKeepProfilesRunning(boolean enabled) {
        try (PermissionContext p = TestApis.permissions()
                .withPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)) {
            sContext.getSystemService(DevicePolicyManager.class)
                    .setOverrideKeepProfilesRunning(enabled);
        }
    }

    private void ensureKeepProfilesRunningEnabled() throws Exception {
        if (keepProfilesRunningEnabled()) {
            return;
        }
        mKeepProfilesRunningOverridden = true;
        overrideKeepProfilesRunning(true);
    }

    private boolean keepProfilesRunningEnabled() throws Exception {
        return ShellCommand.builder("dumpsys device_policy").execute()
                .contains("Keep profiles running: true");
    }
}
