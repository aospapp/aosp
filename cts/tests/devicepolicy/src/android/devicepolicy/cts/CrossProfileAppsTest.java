/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.bedstead.harrier.DeviceState.UserType.PRIMARY_USER;
import static com.android.bedstead.harrier.DeviceState.UserType.WORK_PROFILE;
import static com.android.bedstead.harrier.OptionalBoolean.FALSE;
import static com.android.bedstead.harrier.OptionalBoolean.TRUE;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.testng.Assert.assertThrows;

import android.app.admin.RemoteDevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.CrossProfileApps;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.EnsureHasNoWorkProfile;
import com.android.bedstead.harrier.annotations.EnsureHasSecondaryUser;
import com.android.bedstead.harrier.annotations.EnsureHasWorkProfile;
import com.android.bedstead.harrier.annotations.RequireRunOnPrimaryUser;
import com.android.bedstead.harrier.annotations.RequireRunOnSecondaryUser;
import com.android.bedstead.harrier.annotations.RequireRunOnWorkProfile;
import com.android.bedstead.testapp.TestApp;
import com.android.bedstead.testapp.TestAppInstance;
import com.android.bedstead.testapp.TestAppProvider;

import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RunWith(BedsteadJUnit4.class)
public final class CrossProfileAppsTest {

    private static final String ID_USER_TEXTVIEW =
            "com.android.cts.devicepolicy:id/user_textview";
    private static final long TIMEOUT_WAIT_UI = TimeUnit.SECONDS.toMillis(10);
    private static final Context sContext = ApplicationProvider.getApplicationContext();
    private static final CrossProfileApps sCrossProfileApps =
            sContext.getSystemService(CrossProfileApps.class);
    private static final UserManager sUserManager = sContext.getSystemService(UserManager.class);

    @ClassRule @Rule
    public static final DeviceState sDeviceState = new DeviceState();
    private static final TestAppProvider sTestAppProvider = new TestAppProvider();

    private static final TestApp sCrossProfileTestApp = sTestAppProvider.query()
            .wherePermissions().contains("android.permission.INTERACT_ACROSS_PROFILES").get();
    private static final TestApp sNonCrossProfileTestApp = sTestAppProvider.query()
            .wherePermissions().doesNotContain("android.permission.INTERACT_ACROSS_PROFILES").get();

    @Test
    @RequireRunOnPrimaryUser
    public void getTargetUserProfiles_callingFromPrimaryUser_doesNotContainPrimaryUser() {
        List<UserHandle> targetProfiles = sCrossProfileApps.getTargetUserProfiles();

        assertThat(targetProfiles).doesNotContain(sDeviceState.primaryUser().userHandle());
    }
    @Test
    @RequireRunOnPrimaryUser
    @EnsureHasSecondaryUser
    public void getTargetUserProfiles_callingFromPrimaryUser_doesNotContainSecondaryUser() {
        List<UserHandle> targetProfiles = sCrossProfileApps.getTargetUserProfiles();

        assertThat(targetProfiles).doesNotContain(sDeviceState.secondaryUser().userHandle());
    }

    @Test
    @RequireRunOnWorkProfile(installInstrumentedAppInParent = TRUE)
    public void getTargetUserProfiles_callingFromWorkProfile_containsPrimaryUser() {
        List<UserHandle> targetProfiles = sCrossProfileApps.getTargetUserProfiles();

        assertThat(targetProfiles).contains(sDeviceState.primaryUser().userHandle());
    }

    @Test
    @RequireRunOnPrimaryUser
    @EnsureHasWorkProfile
    public void getTargetUserProfiles_callingFromPrimaryUser_containsWorkProfile() {
        List<UserHandle> targetProfiles = sCrossProfileApps.getTargetUserProfiles();

        assertThat(targetProfiles).contains(sDeviceState.workProfile().userHandle());
    }

    @Test
    @RequireRunOnPrimaryUser
    @EnsureHasWorkProfile(installInstrumentedApp = FALSE)
    public void getTargetUserProfiles_callingFromPrimaryUser_appNotInstalledInWorkProfile_doesNotContainWorkProfile() {
        List<UserHandle> targetProfiles = sCrossProfileApps.getTargetUserProfiles();

        assertThat(targetProfiles).doesNotContain(sDeviceState.workProfile().userHandle());
    }

    @Test
    @RequireRunOnSecondaryUser
    @EnsureHasWorkProfile(forUser = PRIMARY_USER)
    public void getTargetUserProfiles_callingFromSecondaryUser_doesNotContainWorkProfile() {
        List<UserHandle> targetProfiles = sCrossProfileApps.getTargetUserProfiles();

        assertThat(targetProfiles).doesNotContain(
                sDeviceState.workProfile(/* forUser= */ PRIMARY_USER).userHandle());
    }

    @Test
    @RequireRunOnWorkProfile(installInstrumentedAppInParent = TRUE)
    @Ignore // TODO(scottjonathan): Replace use of UIAutomator
    public void startMainActivity_callingFromWorkProfile_targetIsPrimaryUser_launches() {
        sCrossProfileApps.startMainActivity(
                new ComponentName(sContext, MainActivity.class),
                sDeviceState.workProfile().userHandle());

        assertMainActivityLaunchedForUser(sDeviceState.primaryUser().userHandle());
    }

    @Test
    @RequireRunOnPrimaryUser
    @EnsureHasWorkProfile
    @Ignore // TODO(scottjonathan): Replace use of UIAutomator
    public void startMainActivity_callingFromPrimaryUser_targetIsWorkProfile_launches() {
        sCrossProfileApps.startMainActivity(
                new ComponentName(sContext, MainActivity.class),
                sDeviceState.workProfile().userHandle());

        assertMainActivityLaunchedForUser(sDeviceState.workProfile().userHandle());
    }

    private void assertMainActivityLaunchedForUser(UserHandle user) {
        // TODO(scottjonathan): Replace this with a standard event log or similar to avoid UI
        // Look for the text view to verify that MainActivity is started.
        UiObject2 textView = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .wait(
                        Until.findObject(By.res(ID_USER_TEXTVIEW)),
                        TIMEOUT_WAIT_UI);
        assertWithMessage("Failed to start activity in target user")
                .that(textView).isNotNull();
        // Look for the text in textview, it should be the serial number of target user.
        assertWithMessage("Activity is started in wrong user")
                .that(textView.getText())
                .isEqualTo(String.valueOf(sUserManager.getSerialNumberForUser(user)));
    }

    @Test
    public void startMainActivity_activityNotExported_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> {
            sCrossProfileApps.startMainActivity(
                    new ComponentName(sContext, NonExportedActivity.class),
                    sDeviceState.primaryUser().userHandle());
        });
    }

    @Test
    public void startMainActivity_activityNotMain_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> {
            sCrossProfileApps.startMainActivity(
                    new ComponentName(sContext, NonMainActivity.class),
                    sDeviceState.primaryUser().userHandle());
        });
    }

    @Test
    @Ignore // TODO(scottjonathan): This requires another app to be installed which can be launched
    public void startMainActivity_activityIncorrectPackage_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> {

        });
    }

    @Test
    @RequireRunOnPrimaryUser
    public void
            startMainActivity_callingFromPrimaryUser_targetIsPrimaryUser_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> {
            sCrossProfileApps.startMainActivity(
                    new ComponentName(sContext, MainActivity.class),
                    sDeviceState.primaryUser().userHandle());
        });
    }

    @Test
    @RequireRunOnPrimaryUser
    @EnsureHasSecondaryUser
    public void
    startMainActivity_callingFromPrimaryUser_targetIsSecondaryUser_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> {
            sCrossProfileApps.startMainActivity(
                    new ComponentName(sContext, MainActivity.class),
                    sDeviceState.secondaryUser().userHandle());
        });
    }

    @Test
    @RequireRunOnSecondaryUser
    @EnsureHasWorkProfile(forUser = PRIMARY_USER)
    public void
    startMainActivity_callingFromSecondaryUser_targetIsWorkProfile_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> {
            sCrossProfileApps.startMainActivity(
                    new ComponentName(sContext, MainActivity.class),
                    sDeviceState.workProfile(/* forUser= */ PRIMARY_USER).userHandle());
        });
    }

    @Test
    @RequireRunOnPrimaryUser
    public void getProfileSwitchingLabel_callingFromPrimaryUser_targetIsPrimaryUser_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> {
            sCrossProfileApps.getProfileSwitchingLabel(sDeviceState.primaryUser().userHandle());
        });
    }

    @Test
    @RequireRunOnPrimaryUser
    @EnsureHasSecondaryUser
    public void getProfileSwitchingLabel_callingFromPrimaryUser_targetIsSecondaryUser_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> {
            sCrossProfileApps.getProfileSwitchingLabel(sDeviceState.primaryUser().userHandle());
        });
    }

    @Test
    @RequireRunOnSecondaryUser
    @EnsureHasWorkProfile(forUser = PRIMARY_USER)
    public void getProfileSwitchingLabel_callingFromSecondaryUser_targetIsWorkProfile_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> {
            sCrossProfileApps.getProfileSwitchingLabel(
                    sDeviceState.workProfile(/* forUser= */ PRIMARY_USER).userHandle());
        });
    }

    @Test
    @RequireRunOnWorkProfile(installInstrumentedAppInParent = TRUE)
    public void getProfileSwitchingLabel_callingFromWorProfile_targetIsPrimaryUser_notNull() {
        assertThat(sCrossProfileApps.getProfileSwitchingLabel(
                sDeviceState.primaryUser().userHandle())).isNotNull();
    }

    @Test
    @RequireRunOnPrimaryUser
    @EnsureHasWorkProfile
    public void getProfileSwitchingLabel_callingFromPrimaryUser_targetIsWorkProfile_notNull() {
        assertThat(sCrossProfileApps.getProfileSwitchingLabel(
                sDeviceState.workProfile().userHandle())).isNotNull();
    }

    @Test
    @RequireRunOnPrimaryUser
    public void getProfileSwitchingLabelIconDrawable_callingFromPrimaryUser_targetIsPrimaryUser_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> {
            sCrossProfileApps.getProfileSwitchingIconDrawable(
                    sDeviceState.primaryUser().userHandle());
        });
    }

    @Test
    @RequireRunOnPrimaryUser
    @EnsureHasSecondaryUser
    public void getProfileSwitchingLabelIconDrawable_callingFromPrimaryUser_targetIsSecondaryUser_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> {
            sCrossProfileApps.getProfileSwitchingIconDrawable(
                    sDeviceState.secondaryUser().userHandle());
        });
    }

    @Test
    @RequireRunOnSecondaryUser
    @EnsureHasWorkProfile(forUser = PRIMARY_USER)
    public void getProfileSwitchingLabelIconDrawable_callingFromSecondaryUser_targetIsWorkProfile_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> {
            sCrossProfileApps.getProfileSwitchingIconDrawable(
                    sDeviceState.workProfile(/* forUser= */ PRIMARY_USER).userHandle());
        });
    }

    @Test
    @RequireRunOnWorkProfile(installInstrumentedAppInParent = TRUE)
    public void getProfileSwitchingIconDrawable_callingFromWorkProfile_targetIsPrimaryUser_notNull() {
        assertThat(sCrossProfileApps.getProfileSwitchingIconDrawable(
                sDeviceState.primaryUser().userHandle())).isNotNull();
    }

    @Test
    @RequireRunOnPrimaryUser
    @EnsureHasWorkProfile
    public void getProfileSwitchingIconDrawable_callingFromPrimaryUser_targetIsWorkProfile_notNull() {
        assertThat(sCrossProfileApps.getProfileSwitchingIconDrawable(
                sDeviceState.workProfile().userHandle())).isNotNull();
    }

    @Ignore("b/199122256 investigate install failure")
    @Test
    @EnsureHasWorkProfile
    @RequireRunOnPrimaryUser
    public void canRequestInteractAcrossProfiles_fromPersonalProfile_returnsTrue()
            throws Exception {
        RemoteDevicePolicyManager profileOwner = sDeviceState.profileOwner(WORK_PROFILE)
                .devicePolicyManager();
        try (TestAppInstance personalApp = sCrossProfileTestApp.install(
                sDeviceState.primaryUser());
             TestAppInstance workApp = sCrossProfileTestApp.install(
                sDeviceState.workProfile())) {
            profileOwner.setCrossProfilePackages(
                    sDeviceState.profileOwner(WORK_PROFILE).componentName(),
                    Set.of(sCrossProfileTestApp.packageName()));

            assertThat(personalApp.crossProfileApps().canRequestInteractAcrossProfiles()).isTrue();
        }
    }

    @Ignore("b/199122256 investigate install failure")
    @Test
    @EnsureHasWorkProfile
    @RequireRunOnPrimaryUser
    public void canRequestInteractAcrossProfiles_fromWorkProfile_returnsTrue()
            throws Exception {
        RemoteDevicePolicyManager profileOwner = sDeviceState.profileOwner(WORK_PROFILE)
                .devicePolicyManager();
        try (TestAppInstance personalApp = sCrossProfileTestApp.install(
                sDeviceState.primaryUser());
             TestAppInstance workApp = sCrossProfileTestApp.install(
                sDeviceState.workProfile())) {
            profileOwner.setCrossProfilePackages(
                    sDeviceState.profileOwner(WORK_PROFILE).componentName(),
                    Set.of(sCrossProfileTestApp.packageName()));

            assertThat(workApp.crossProfileApps().canRequestInteractAcrossProfiles()).isTrue();
        }
    }

    @Test
    @EnsureHasNoWorkProfile
    @RequireRunOnPrimaryUser
    public void canRequestInteractAcrossProfiles_noOtherProfiles_returnsFalse()
            throws Exception {
        try (TestAppInstance personalApp = sCrossProfileTestApp.install(
                sDeviceState.primaryUser())) {

            assertThat(personalApp.crossProfileApps().canRequestInteractAcrossProfiles()).isFalse();
        }
    }

    @Ignore("b/199122256 investigate install failure")
    @Test
    @EnsureHasWorkProfile
    @RequireRunOnPrimaryUser
    public void canRequestInteractAcrossProfiles_packageNotInAllowList_returnsTrue()
            throws Exception {
        RemoteDevicePolicyManager profileOwner = sDeviceState.profileOwner(WORK_PROFILE)
                .devicePolicyManager();
        try (TestAppInstance personalApp = sCrossProfileTestApp.install(
                sDeviceState.primaryUser());
             TestAppInstance workApp = sCrossProfileTestApp.install(
                sDeviceState.workProfile())) {
            profileOwner.setCrossProfilePackages(
                    sDeviceState.profileOwner(WORK_PROFILE).componentName(),
                    Collections.emptySet());

            assertThat(personalApp.crossProfileApps().canRequestInteractAcrossProfiles()).isTrue();
        }
    }

    @Ignore("b/199122256 investigate install failure")
    @Test
    @EnsureHasWorkProfile
    @RequireRunOnPrimaryUser
    public void canRequestInteractAcrossProfiles_packageNotInstalledInPersonalProfile_returnsTrue()
            throws Exception {
        RemoteDevicePolicyManager profileOwner = sDeviceState.profileOwner(WORK_PROFILE)
                .devicePolicyManager();
        try (TestAppInstance workApp = sCrossProfileTestApp.install(
                sDeviceState.workProfile())) {
            profileOwner.setCrossProfilePackages(
                    sDeviceState.profileOwner(WORK_PROFILE).componentName(),
                    Set.of(sCrossProfileTestApp.packageName()));

            assertThat(workApp.crossProfileApps().canRequestInteractAcrossProfiles()).isTrue();
        }
    }

    @Test
    @EnsureHasWorkProfile
    @RequireRunOnPrimaryUser
    public void canRequestInteractAcrossProfiles_packageNotInstalledInWorkProfile_returnsTrue()
            throws Exception {
        RemoteDevicePolicyManager profileOwner = sDeviceState.profileOwner(WORK_PROFILE)
                .devicePolicyManager();
        try (TestAppInstance personalApp = sCrossProfileTestApp.install(
                sDeviceState.primaryUser())) {
            profileOwner.setCrossProfilePackages(
                    sDeviceState.profileOwner(WORK_PROFILE).componentName(),
                    Set.of(sCrossProfileTestApp.packageName()));

            assertThat(personalApp.crossProfileApps().canRequestInteractAcrossProfiles()).isTrue();
        }
    }

    @Ignore("b/199122256 investigate install failure")
    @Test
    @EnsureHasWorkProfile
    @RequireRunOnPrimaryUser
    public void canRequestInteractAcrossProfiles_permissionNotRequested_returnsFalse()
            throws Exception {
        RemoteDevicePolicyManager profileOwner = sDeviceState.profileOwner(WORK_PROFILE)
                .devicePolicyManager();
        try (TestAppInstance personalApp = sNonCrossProfileTestApp.install(
                sDeviceState.primaryUser());
             TestAppInstance workApp = sNonCrossProfileTestApp.install(
                sDeviceState.workProfile())) {
            profileOwner.setCrossProfilePackages(
                    sDeviceState.profileOwner(WORK_PROFILE).componentName(),
                    Set.of(sCrossProfileTestApp.packageName()));

            assertThat(personalApp.crossProfileApps().canRequestInteractAcrossProfiles()).isFalse();
        }
    }

    // TODO(b/199148889): add require INTERACT_ACROSS_PROFILE permission for the dpc.
    @Test
    @EnsureHasWorkProfile
    @RequireRunOnPrimaryUser
    public void canRequestInteractAcrossProfiles_profileOwner_returnsFalse()
            throws Exception {
        RemoteDevicePolicyManager profileOwner = sDeviceState.profileOwner(WORK_PROFILE)
                .devicePolicyManager();
        profileOwner.setCrossProfilePackages(
                sDeviceState.profileOwner(WORK_PROFILE).componentName(),
                Set.of(sDeviceState.profileOwner(WORK_PROFILE).componentName().getPackageName()));

        assertThat(
                sDeviceState.profileOwner(WORK_PROFILE).crossProfileApps()
                        .canRequestInteractAcrossProfiles()).isFalse();
    }
}