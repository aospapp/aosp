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

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.PROVISION_DEMO_DEVICE;
import static android.app.admin.DevicePolicyManager.ACTION_MANAGED_PROFILE_PROVISIONED;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_LOCALE;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_LOCAL_TIME;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ROLE_HOLDER_EXTRAS_BUNDLE;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_TIME_ZONE;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_TRIGGER;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_WIFI_PASSWORD;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_WIFI_PROXY_PORT;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_WIFI_SECURITY_TYPE;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_WIFI_SSID;
import static android.app.admin.DevicePolicyManager.MIME_TYPE_PROVISIONING_NFC;
import static android.app.admin.DevicePolicyManager.PROVISIONING_TRIGGER_NFC;
import static android.app.admin.DevicePolicyManager.STATE_USER_SETUP_FINALIZED;
import static android.app.admin.ProvisioningException.ERROR_PRE_CONDITION_FAILED;
import static android.content.Intent.EXTRA_USER;
import static android.content.pm.PackageManager.FEATURE_DEVICE_ADMIN;
import static android.content.pm.PackageManager.FEATURE_MANAGED_USERS;
import static android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED;
import static android.nfc.NfcAdapter.EXTRA_NDEF_MESSAGES;

import static com.android.bedstead.nene.appops.AppOpsMode.ALLOWED;
import static com.android.bedstead.nene.permissions.CommonPermissions.INTERACT_ACROSS_PROFILES;
import static com.android.bedstead.nene.permissions.CommonPermissions.INTERACT_ACROSS_USERS;
import static com.android.bedstead.nene.permissions.CommonPermissions.MANAGE_PROFILE_AND_DEVICE_OWNERS;
import static com.android.bedstead.nene.permissions.CommonPermissions.MANAGE_USERS;
import static com.android.bedstead.nene.userrestrictions.CommonUserRestrictions.DISALLOW_ADD_MANAGED_PROFILE;
import static com.android.bedstead.nene.userrestrictions.CommonUserRestrictions.DISALLOW_ADD_USER;
import static com.android.bedstead.nene.users.UserType.MANAGED_PROFILE_TYPE_NAME;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.accounts.Account;
import android.app.admin.DevicePolicyManager;
import android.app.admin.FullyManagedDeviceProvisioningParams;
import android.app.admin.ManagedProfileProvisioningParams;
import android.app.admin.ProvisioningException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.BaseBundle;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.bedstead.deviceadminapp.DeviceAdminApp;
import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.EnsureDoesNotHavePermission;
import com.android.bedstead.harrier.annotations.EnsureHasAccount;
import com.android.bedstead.harrier.annotations.EnsureHasAdditionalUser;
import com.android.bedstead.harrier.annotations.EnsureHasNoWorkProfile;
import com.android.bedstead.harrier.annotations.EnsureHasPermission;
import com.android.bedstead.harrier.annotations.EnsureHasSecondaryUser;
import com.android.bedstead.harrier.annotations.EnsureHasWorkProfile;
import com.android.bedstead.harrier.annotations.EnsureIsNotDemoDevice;
import com.android.bedstead.harrier.annotations.PermissionTest;
import com.android.bedstead.harrier.annotations.Postsubmit;
import com.android.bedstead.harrier.annotations.RequireDoesNotHaveFeature;
import com.android.bedstead.harrier.annotations.RequireFeature;
import com.android.bedstead.harrier.annotations.RequireHeadlessSystemUserMode;
import com.android.bedstead.harrier.annotations.RequireNotHeadlessSystemUserMode;
import com.android.bedstead.harrier.annotations.RequireNotWatch;
import com.android.bedstead.harrier.annotations.RequireRunOnAdditionalUser;
import com.android.bedstead.harrier.annotations.RequireRunOnInitialUser;
import com.android.bedstead.harrier.annotations.RequireRunOnSecondaryUser;
import com.android.bedstead.harrier.annotations.RequireRunOnWorkProfile;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasDeviceOwner;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasNoDeviceOwner;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasNoDpc;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasNoProfileOwner;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasProfileOwner;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.devicepolicy.DeviceOwner;
import com.android.bedstead.nene.devicepolicy.ProfileOwner;
import com.android.bedstead.nene.packages.ComponentReference;
import com.android.bedstead.nene.packages.Package;
import com.android.bedstead.nene.permissions.PermissionContext;
import com.android.bedstead.nene.users.UserReference;
import com.android.bedstead.remotedpc.RemoteDpc;
import com.android.bedstead.testapp.TestApp;
import com.android.bedstead.testapp.TestAppInstance;
import com.android.compatibility.common.util.ApiTest;
import com.android.eventlib.events.broadcastreceivers.BroadcastReceivedEvent;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

@RunWith(BedsteadJUnit4.class)
public final class ProvisioningTest {

    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private static final Context sContext = TestApis.context().instrumentedContext();
    private static final DevicePolicyManager sDevicePolicyManager =
            sContext.getSystemService(DevicePolicyManager.class);

    private static final Exception CAUSE = new Exception();
    private static final int PROVISIONING_ERROR = ProvisioningException.ERROR_PRE_CONDITION_FAILED;
    private static final String MESSAGE = "test failure message";

    private static final String NFC_INTENT_COMPONENT_NAME =
            "com.test.dpc/com.test.dpc.DeviceAdminReceiver";
    private static final String NFC_INTENT_PACKAGE_NAME =
            "com.test.dpc.DeviceAdminReceiver";
    private static final String NFC_INTENT_LOCALE = "en_US";
    private static final String NFC_INTENT_TIMEZONE = "America/New_York";
    private static final String NFC_INTENT_WIFI_SSID = "\"" + "TestWifiSsid" + "\"";
    private static final String NFC_INTENT_WIFI_SECURITY_TYPE = "";
    private static final String NFC_INTENT_WIFI_PASSWORD = "";
    private static final String NFC_INTENT_BAD_ACTION = "badAction";
    private static final String NFC_INTENT_BAD_MIME = "badMime";
    private static final String NFC_INTENT_PROVISIONING_SAMPLE = "NFC provisioning sample";
    private static final Intent NFC_INTENT_NO_NDEF_RECORD = new Intent(ACTION_NDEF_DISCOVERED);
    private static final HashMap<String, String> NFC_DATA_VALID = createNfcIntentData();
    private static final HashMap<String, String> NFC_DATA_EMPTY = new HashMap<>();
    private static final Map<String, String> NFC_DATA_WITH_COMPONENT_NAME =
            Map.of(EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, NFC_INTENT_COMPONENT_NAME);
    private static final Bundle EXPECTED_BUNDLE_WITH_COMPONENT_NAME =
            createExpectedBundleWithComponentName();
    private static final Map<String, String> NFC_DATA_WITH_ADMIN_PACKAGE_NAME =
            Map.of(EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME, NFC_INTENT_PACKAGE_NAME);
    private static final Bundle EXPECTED_BUNDLE_WITH_PACKAGE_NAME =
            createExpectedBundleWithPackageName();
    private static final long NFC_INTENT_LOCAL_TIME = 123456;
    private static final int NFC_INTENT_WIFI_PROXY_PORT = 1234;
    private static final String PROFILE_OWNER_NAME = "testDeviceAdmin";
    private static final String DEVICE_OWNER_NAME = "testDeviceAdmin";

    private static final ComponentName DEVICE_ADMIN_COMPONENT_NAME =
            DeviceAdminApp.deviceAdminComponentName(sContext);

    private static final ComponentReference DEVICE_ADMIN_COMPONENT =
            TestApis.packages().component(DEVICE_ADMIN_COMPONENT_NAME);

    private static final ManagedProfileProvisioningParams MANAGED_PROFILE_PARAMS =
            createManagedProfileProvisioningParamsBuilder().build();

    private static final PersistableBundle ADMIN_EXTRAS_BUNDLE = createAdminExtrasBundle();
    private static final PersistableBundle ROLE_HOLDER_EXTRAS_BUNDLE =
            createRoleHolderExtrasBundle();
    private static final String TEST_KEY = "test_key";
    private static final String TEST_VALUE = "test_value";

    private static final String EXISTING_ACCOUNT_TYPE =
            "com.android.bedstead.testapp.AccountManagementApp.account.type";
    private static final Account ACCOUNT_WITH_EXISTING_TYPE =
            new Account("user0", EXISTING_ACCOUNT_TYPE);

    private static final TestApp sNoHeadlessSupportTestApp =
            sDeviceState.testApps().query()
                    .wherePackageName().isEqualTo("com.android.bedstead.testapp.DeviceAdminTestApp")
                    .get();


    @Test
    public void provisioningException_constructor_works() {
        ProvisioningException exception =
                new ProvisioningException(CAUSE, PROVISIONING_ERROR, MESSAGE);

        assertThat(exception.getCause()).isEqualTo(CAUSE);
        assertThat(exception.getProvisioningError()).isEqualTo(PROVISIONING_ERROR);
        assertThat(exception.getMessage()).isEqualTo(MESSAGE);
    }

    @Test
    public void provisioningException_constructor_noErrorMessage_nullByDefault() {
        ProvisioningException exception = new ProvisioningException(CAUSE, PROVISIONING_ERROR);

        assertThat(exception.getMessage()).isNull();
    }

    // TODO: Get rid of the setup, and replace with a @EnsureDpcDownloaded annotation on the
    //  appropriate methods
    @Before
    public void setUp() {
        try (PermissionContext p = TestApis.permissions()
                .withPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)) {
            sDevicePolicyManager.setDpcDownloaded(false);
        }
    }

    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @RequireFeature(FEATURE_MANAGED_USERS)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createAndProvisionManagedProfile")
    public void createAndProvisionManagedProfile_createsManagedProfile() throws Exception {
        try (UserReference profile = UserReference.of(
                sDevicePolicyManager.createAndProvisionManagedProfile(MANAGED_PROFILE_PARAMS))) {

            assertThat(profile.type()).isEqualTo(
                    TestApis.users().supportedType(MANAGED_PROFILE_TYPE_NAME));
        }
    }

    @RequireRunOnInitialUser
    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @RequireFeature(FEATURE_MANAGED_USERS)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createAndProvisionManagedProfile")
    public void createAndProvisionManagedProfile_onInitialUser_createsManagedProfile()
            throws Exception {
        try (UserReference profile = UserReference.of(
                sDevicePolicyManager.createAndProvisionManagedProfile(MANAGED_PROFILE_PARAMS))) {

            assertThat(profile.exists()).isTrue();
        }
    }

    @EnsureHasNoWorkProfile
    @EnsureHasAdditionalUser
    @RequireRunOnAdditionalUser
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @RequireFeature(FEATURE_MANAGED_USERS)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createAndProvisionManagedProfile")
    public void createAndProvisionManagedProfile_notOnInitialUser_preconditionFails() {
        ProvisioningException exception = assertThrows(ProvisioningException.class, () ->
                sDevicePolicyManager.createAndProvisionManagedProfile(MANAGED_PROFILE_PARAMS));
        assertThat(exception.getProvisioningError()).isEqualTo(ERROR_PRE_CONDITION_FAILED);
    }

    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @RequireFeature(FEATURE_MANAGED_USERS)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createAndProvisionManagedProfile")
    public void createAndProvisionManagedProfile_setsActiveAdmin() throws Exception {
        try (UserReference profile = UserReference.of(
                sDevicePolicyManager.createAndProvisionManagedProfile(MANAGED_PROFILE_PARAMS))) {

            assertThat(TestApis.devicePolicy().getActiveAdmins(profile)).hasSize(1);
            assertThat(TestApis.devicePolicy().getActiveAdmins(profile).iterator().next())
                    .isEqualTo(DEVICE_ADMIN_COMPONENT);
        }
    }

    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @RequireFeature(FEATURE_MANAGED_USERS)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createAndProvisionManagedProfile")
    public void createAndProvisionManagedProfile_setsProfileOwner() throws Exception {
        try (UserReference profile = UserReference.of(
                sDevicePolicyManager.createAndProvisionManagedProfile(MANAGED_PROFILE_PARAMS))) {

            assertThat(TestApis.devicePolicy().getProfileOwner(profile).pkg().packageName())
                    .isEqualTo(sContext.getPackageName());
        }
    }

    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @RequireFeature(FEATURE_MANAGED_USERS)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasAccount
    @Test
    @Ignore // TODO(265135960): I think this isn't copying because the authenticator isn't in
    // the work profile
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createAndProvisionManagedProfile")
    public void createAndProvisionManagedProfile_copiesAccountToProfile() throws Exception {
        ManagedProfileProvisioningParams params =
                createManagedProfileProvisioningParamsBuilder()
                        .setAccountToMigrate(sDeviceState.account().account())
                        .build();
        try (UserReference profile = UserReference.of(
                sDevicePolicyManager.createAndProvisionManagedProfile(params))) {
            assertThat(TestApis.accounts().all(profile)).contains(sDeviceState.account());
        }
    }

    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @RequireFeature(FEATURE_MANAGED_USERS)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasAccount
    @Test
    @Ignore // TODO(265135960): I think this isn't copying because the authenticator isn't in
    // the work profile
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createAndProvisionManagedProfile")
    public void createAndProvisionManagedProfile_removesAccountFromParentByDefault()
            throws Exception {
        ManagedProfileProvisioningParams params =
                createManagedProfileProvisioningParamsBuilder()
                        .setAccountToMigrate(sDeviceState.account().account())
                        .build();
        try (UserReference profile = UserReference.of(
                sDevicePolicyManager.createAndProvisionManagedProfile(params))) {
            assertThat(sDeviceState.accounts().allAccounts())
                    .doesNotContain(sDeviceState.account());
        }
    }

    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @RequireFeature(FEATURE_MANAGED_USERS)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasAccount
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createAndProvisionManagedProfile")
    public void createAndProvisionManagedProfile_keepsAccountInParentIfRequested()
            throws Exception {
        ManagedProfileProvisioningParams params =
                createManagedProfileProvisioningParamsBuilder()
                        .setAccountToMigrate(sDeviceState.account().account())
                        .setKeepingAccountOnMigration(true)
                        .build();
        try (UserReference profile = UserReference.of(
                sDevicePolicyManager.createAndProvisionManagedProfile(params))) {
            assertThat(sDeviceState.accounts().allAccounts())
                    .contains(sDeviceState.account());
        }
    }

    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @RequireFeature(FEATURE_MANAGED_USERS)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createAndProvisionManagedProfile")
    public void createAndProvisionManagedProfile_removesNonRequiredAppsFromProfile()
            throws Exception {
        try (PermissionContext p =
                     TestApis.permissions().withPermission(INTERACT_ACROSS_USERS_FULL);
             UserReference profile =
                     UserReference.of(
                             sDevicePolicyManager.createAndProvisionManagedProfile(
                                     MANAGED_PROFILE_PARAMS))) {
            Set<String> nonRequiredApps = sDevicePolicyManager.getDisallowedSystemApps(
                    DEVICE_ADMIN_COMPONENT_NAME,
                    sContext.getUserId(),
                    DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE);

            Collection<Package> nonRequiredAppsInProfile =
                    TestApis.packages().installedForUser(profile);
            nonRequiredAppsInProfile.retainAll(nonRequiredApps.stream().map(
                    t -> TestApis.packages().find(t)).collect(Collectors.toSet()));
            assertThat(nonRequiredAppsInProfile).isEmpty();
        }
    }

    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @RequireFeature(FEATURE_MANAGED_USERS)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createAndProvisionManagedProfile")
    public void createAndProvisionManagedProfile_setsCrossProfilePackages()
            throws Exception {
        try (UserReference profile = UserReference.of(
                sDevicePolicyManager.createAndProvisionManagedProfile(MANAGED_PROFILE_PARAMS))) {
            Set<Package> defaultPackages =
                    TestApis.devicePolicy().defaultCrossProfilePackages().stream()
                            .filter(Package::canConfigureInteractAcrossProfiles)
                            .filter(Package::isInstalled)
                            .collect(Collectors.toSet());

            for(Package crossProfilePackage : defaultPackages) {
                assertWithMessage("Checking crossprofilepackage : "
                        + crossProfilePackage + " on parent").that(
                        crossProfilePackage.appOps()
                                .get(INTERACT_ACROSS_PROFILES)).isEqualTo(ALLOWED);
                assertWithMessage("Checking crossprofilepackage : "
                        + crossProfilePackage + " on profile").that(
                        crossProfilePackage.appOps(profile)
                                .get(INTERACT_ACROSS_PROFILES)).isEqualTo(ALLOWED);
            }
        }
    }

    @EnsureHasWorkProfile
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @RequireFeature(FEATURE_MANAGED_USERS)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Postsubmit(reason = "new test")
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createAndProvisionManagedProfile")
    public void createAndProvisionManagedProfile_withExistingProfile_preconditionFails() {
        ProvisioningException exception = assertThrows(ProvisioningException.class, () ->
                sDevicePolicyManager.createAndProvisionManagedProfile(MANAGED_PROFILE_PARAMS));
        assertThat(exception.getProvisioningError()).isEqualTo(ERROR_PRE_CONDITION_FAILED);
    }

    private static ManagedProfileProvisioningParams.Builder createManagedProfileProvisioningParamsBuilder() {
        return new ManagedProfileProvisioningParams.Builder(
                DEVICE_ADMIN_COMPONENT_NAME,
                PROFILE_OWNER_NAME);
    }

    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#provisionFullyManagedDevice")
    public void provisionFullyManagedDevice_setsDeviceOwner() throws Exception {
        boolean setupComplete = TestApis.users().system().getSetupComplete();
        TestApis.users().system().setSetupComplete(false);
        try {

            FullyManagedDeviceProvisioningParams params =
                    createDefaultManagedDeviceProvisioningParamsBuilder().build();
            sDevicePolicyManager.provisionFullyManagedDevice(params);


            assertThat(TestApis.devicePolicy().getDeviceOwner().pkg().packageName())
                    .isEqualTo(sContext.getPackageName());
        } finally {
            DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
            if (deviceOwner != null) {
                deviceOwner.remove();
            }
            TestApis.users().system().setSetupComplete(setupComplete);
        }
    }

    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @RequireHeadlessSystemUserMode(reason = "Testing headless-specific functionality")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#provisionFullyManagedDevice")
    public void provisionFullyManagedDevice_headless_setsProfileOwnerOnInitialUser()
            throws Exception {
        boolean systemSetupComplete = TestApis.users().system().getSetupComplete();
        TestApis.users().system().setSetupComplete(false);
        try {
            FullyManagedDeviceProvisioningParams params =
                    createDefaultManagedDeviceProvisioningParamsBuilder().build();
            sDevicePolicyManager.provisionFullyManagedDevice(params);

            assertThat(TestApis.devicePolicy().getProfileOwner()).isNotNull();
        } finally {
            DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
            if (deviceOwner != null) {
                deviceOwner.remove();
            }
            ProfileOwner profileOwner = TestApis.devicePolicy().getProfileOwner();
            if (profileOwner != null) {
                profileOwner.remove();
            }
            TestApis.users().system().setSetupComplete(systemSetupComplete);
        }
    }

    @Postsubmit(reason = "New test")
    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @RequireHeadlessSystemUserMode(reason = "Testing headless-specific functionality")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#provisionFullyManagedDevice")
    public void provisionFullyManagedDevice_headless_dpcDoesNotDeclareHeadlessCompatibility_throwsException()
            throws Exception {
        try (TestAppInstance testApp = sNoHeadlessSupportTestApp.install()) {
            FullyManagedDeviceProvisioningParams params =
                    new FullyManagedDeviceProvisioningParams.Builder(
                            new ComponentName(testApp.packageName(), testApp.packageName() + ".DeviceAdminReceiver"),
                            DEVICE_OWNER_NAME)
                            .build();

            ProvisioningException exception = assertThrows(ProvisioningException.class, () ->
                    sDevicePolicyManager.provisionFullyManagedDevice(params));
            assertThat(exception.getProvisioningError()).isEqualTo(
                    ERROR_PRE_CONDITION_FAILED);
        }
    }

    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#provisionFullyManagedDevice")
    public void provisionFullyManagedDevice_disallowAddUserIsSet()
            throws Exception {
        boolean systemSetupComplete = TestApis.users().system().getSetupComplete();
        TestApis.users().system().setSetupComplete(false);
        try {
            FullyManagedDeviceProvisioningParams params =
                    createDefaultManagedDeviceProvisioningParamsBuilder().build();
            sDevicePolicyManager.provisionFullyManagedDevice(params);

            assertThat(TestApis.devicePolicy().userRestrictions().isSet(DISALLOW_ADD_USER))
                    .isTrue();
        } finally {
            DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
            if (deviceOwner != null) {
                deviceOwner.remove();
            }
            ProfileOwner profileOwner = TestApis.devicePolicy().getProfileOwner();
            if (profileOwner != null) {
                profileOwner.remove();
            }
            TestApis.users().system().setSetupComplete(systemSetupComplete);
        }
    }

    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#provisionFullyManagedDevice")
    public void provisionFullyManagedDevice_disallowAddManagedProfileIsSet()
            throws Exception {
        boolean systemSetupComplete = TestApis.users().system().getSetupComplete();
        TestApis.users().system().setSetupComplete(false);
        try {
            FullyManagedDeviceProvisioningParams params =
                    createDefaultManagedDeviceProvisioningParamsBuilder().build();
            sDevicePolicyManager.provisionFullyManagedDevice(params);

            assertThat(
                    TestApis.devicePolicy().userRestrictions().isSet(DISALLOW_ADD_MANAGED_PROFILE))
                    .isTrue();
        } finally {
            DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
            if (deviceOwner != null) {
                deviceOwner.remove();
            }
            ProfileOwner profileOwner = TestApis.devicePolicy().getProfileOwner();
            if (profileOwner != null) {
                profileOwner.remove();
            }
            TestApis.users().system().setSetupComplete(systemSetupComplete);
        }
    }

    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#provisionFullyManagedDevice")
    public void provisionFullyManagedDevice_canControlSensorPermissionGrantsByDefault()
            throws Exception {
        boolean setupComplete = TestApis.users().system().getSetupComplete();
        TestApis.users().system().setSetupComplete(false);
        try {
            FullyManagedDeviceProvisioningParams params =
                    createDefaultManagedDeviceProvisioningParamsBuilder().build();
            sDevicePolicyManager.provisionFullyManagedDevice(params);

            assertThat(TestApis.devicePolicy().canAdminGrantSensorsPermissions()).isTrue();
        } finally {
            DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
            if (deviceOwner != null) {
                deviceOwner.remove();
            }
            TestApis.users().system().setSetupComplete(setupComplete);
        }
    }

    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Postsubmit(reason = "b/181993922 automatically marked flaky")
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#provisionFullyManagedDevice")
    public void provisionFullyManagedDevice_canOptOutOfControllingSensorPermissionGrants()
            throws Exception {
        boolean setupComplete = TestApis.users().system().getSetupComplete();
        TestApis.users().system().setSetupComplete(false);
        try {
            FullyManagedDeviceProvisioningParams params =
                    createDefaultManagedDeviceProvisioningParamsBuilder()
                            .setCanDeviceOwnerGrantSensorsPermissions(false)
                            .build();
            sDevicePolicyManager.provisionFullyManagedDevice(params);

            assertThat(TestApis.devicePolicy().canAdminGrantSensorsPermissions()).isFalse();
        } finally {
            DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
            if (deviceOwner != null) {
                deviceOwner.remove();
            }
            TestApis.users().system().setSetupComplete(setupComplete);
        }
    }

    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#provisionFullyManagedDevice")
    public void provisionFullyManagedDevice_leavesAllSystemAppsEnabledWhenRequested()
            throws Exception {
        boolean setupComplete = TestApis.users().system().getSetupComplete();
        TestApis.users().system().setSetupComplete(false);
        try {
            Set<Package> systemAppsBeforeProvisioning = TestApis.packages().systemApps();

            FullyManagedDeviceProvisioningParams params =
                    createDefaultManagedDeviceProvisioningParamsBuilder()
                            .setLeaveAllSystemAppsEnabled(true)
                            .build();
            sDevicePolicyManager.provisionFullyManagedDevice(params);

            Set<Package> systemAppsAfterProvisioning = TestApis.packages().systemApps();
            assertThat(systemAppsAfterProvisioning).isEqualTo(systemAppsBeforeProvisioning);
        } finally {
            DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
            if (deviceOwner != null) {
                deviceOwner.remove();
            }
            TestApis.users().system().setSetupComplete(setupComplete);
        }
    }

    @Postsubmit(reason = "New test")
    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureIsNotDemoDevice
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#provisionFullyManagedDevice")
    public void provisionFullyManagedDevice_setsDeviceAsDemoDeviceWhenRequested()
            throws Exception {
        boolean setupComplete = TestApis.users().system().getSetupComplete();
        TestApis.users().system().setSetupComplete(false);
        try {
            FullyManagedDeviceProvisioningParams params =
                    createDefaultManagedDeviceProvisioningParamsBuilder()
                            .setDemoDevice(true)
                            .build();
            sDevicePolicyManager.provisionFullyManagedDevice(params);

            assertThat(TestApis.settings().global().getInt(Settings.Global.DEVICE_DEMO_MODE, 0))
                    .isEqualTo(1);
        } finally {
            DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
            if (deviceOwner != null) {
                deviceOwner.remove();
            }
            TestApis.users().system().setSetupComplete(setupComplete);
            TestApis.settings().global().putInt(Settings.Global.DEVICE_DEMO_MODE, 0);
        }
    }

    @EnsureHasAdditionalUser
    @PermissionTest({INTERACT_ACROSS_USERS})
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    public void getUserProvisioningState_differentUser_validPermission_doesNotThrow() {
        TestApis.context().androidContextAsUser(sDeviceState.additionalUser())
                .getSystemService(DevicePolicyManager.class).getUserProvisioningState();
    }

    @EnsureHasAdditionalUser
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @EnsureDoesNotHavePermission({MANAGE_USERS, INTERACT_ACROSS_USERS})
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    public void getUserProvisioningState_differentUser_noPermission_throwsException() {
        assertThrows(SecurityException.class,
                () -> TestApis.context().androidContextAsUser(sDeviceState.additionalUser())
                        .getSystemService(DevicePolicyManager.class).getUserProvisioningState());
    }

    @Postsubmit(reason = "New test")
    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#provisionFullyManagedDevice")
    public void provisionFullyManagedDevice_setsProvisioningStateWhenDemoDeviceIsRequested()
            throws Exception {
        boolean setupComplete = TestApis.users().system().getSetupComplete();
        TestApis.users().system().setSetupComplete(false);
        try {
            FullyManagedDeviceProvisioningParams params =
                    createDefaultManagedDeviceProvisioningParamsBuilder()
                            .setDemoDevice(true)
                            .build();
            sDevicePolicyManager.provisionFullyManagedDevice(params);

            assertThat(TestApis.devicePolicy().getUserProvisioningState(TestApis.users().system()))
                    .isEqualTo(STATE_USER_SETUP_FINALIZED);
        } finally {
            DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
            if (deviceOwner != null) {
                deviceOwner.remove();
            }
            TestApis.users().system().setSetupComplete(setupComplete);
            TestApis.settings().global().putInt(Settings.Global.DEVICE_DEMO_MODE, 0);
        }
    }

    @Postsubmit(reason = "New test")
    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @EnsureHasPermission(PROVISION_DEMO_DEVICE)
    @EnsureDoesNotHavePermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#provisionFullyManagedDevice")
    public void provisionFullyManagedDevice_withProvisionDemoDevicePermission_throwsSecurityException()
            throws Exception {
        FullyManagedDeviceProvisioningParams params =
                createDefaultManagedDeviceProvisioningParamsBuilder()
                        .build();

        assertThrows(SecurityException.class, () ->
                sDevicePolicyManager.provisionFullyManagedDevice(params));
    }

    @Postsubmit(reason = "New test")
    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @EnsureHasPermission(PROVISION_DEMO_DEVICE)
    @EnsureDoesNotHavePermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#provisionFullyManagedDevice")
    public void provisionFullyManagedDevice_withProvisionDemoDevicePermissionForDemoDevice_doesNotThrowException()
            throws Exception {
        boolean setupComplete = TestApis.users().system().getSetupComplete();
        TestApis.users().system().setSetupComplete(false);
        try {
            FullyManagedDeviceProvisioningParams params =
                    createDefaultManagedDeviceProvisioningParamsBuilder()
                            .setDemoDevice(true)
                            .build();

            sDevicePolicyManager.provisionFullyManagedDevice(params);
        } finally {
            DeviceOwner deviceOwner = TestApis.devicePolicy().getDeviceOwner();
            if (deviceOwner != null) {
                deviceOwner.remove();
            }
            TestApis.users().system().setSetupComplete(setupComplete);
            TestApis.settings().global().putInt(Settings.Global.DEVICE_DEMO_MODE, 0);
        }
    }

    @Postsubmit(reason = "New test")
    @EnsureHasNoDpc
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @EnsureDoesNotHavePermission({
            PROVISION_DEMO_DEVICE,
            MANAGE_PROFILE_AND_DEVICE_OWNERS})
    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#provisionFullyManagedDevice")
    public void provisionFullyManagedDevice_withoutRequiredPermissionsForDemoDevice_throwsSecurityException()
            throws Exception {
        FullyManagedDeviceProvisioningParams params =
                createDefaultManagedDeviceProvisioningParamsBuilder()
                        .setDemoDevice(true)
                        .build();

        assertThrows(SecurityException.class, () ->
                sDevicePolicyManager.provisionFullyManagedDevice(params));
    }

    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createProvisioningIntentFromNfcIntent")
    public void createProvisioningIntentFromNfcIntent_validNfcIntent_returnsValidIntent()
            throws IOException {
        Intent nfcIntent = createNfcIntentFromMap(NFC_DATA_VALID);

        Intent provisioningIntent =
                sDevicePolicyManager.createProvisioningIntentFromNfcIntent(nfcIntent);

        assertThat(provisioningIntent).isNotNull();
        assertThat(provisioningIntent.getAction())
                .isEqualTo(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE);
        assertBundlesEqual(provisioningIntent.getExtras(), createExpectedValidBundle());
    }

    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createProvisioningIntentFromNfcIntent")
    public void createProvisioningIntentFromNfcIntent_noComponentNorPackage_returnsNull()
            throws IOException {
        Intent nfcIntent = createNfcIntentFromMap(NFC_DATA_EMPTY);

        Intent provisioningIntent =
                sDevicePolicyManager.createProvisioningIntentFromNfcIntent(nfcIntent);

        assertThat(provisioningIntent).isNull();
    }

    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createProvisioningIntentFromNfcIntent")
    public void createProvisioningIntentFromNfcIntent_withComponent_returnsValidIntent()
            throws IOException {
        Intent nfcIntent = createNfcIntentFromMap(NFC_DATA_WITH_COMPONENT_NAME);

        Intent provisioningIntent =
                sDevicePolicyManager.createProvisioningIntentFromNfcIntent(nfcIntent);

        assertThat(provisioningIntent).isNotNull();
        assertThat(provisioningIntent.getAction())
                .isEqualTo(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE);
        assertBundlesEqual(provisioningIntent.getExtras(), EXPECTED_BUNDLE_WITH_COMPONENT_NAME);
    }

    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createProvisioningIntentFromNfcIntent")
    public void createProvisioningIntentFromNfcIntent_withPackage_returnsValidIntent()
            throws IOException {
        Intent nfcIntent = createNfcIntentFromMap(NFC_DATA_WITH_ADMIN_PACKAGE_NAME);

        Intent provisioningIntent =
                sDevicePolicyManager.createProvisioningIntentFromNfcIntent(nfcIntent);

        assertThat(provisioningIntent).isNotNull();
        assertThat(provisioningIntent.getAction())
                .isEqualTo(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE);
        assertBundlesEqual(provisioningIntent.getExtras(), EXPECTED_BUNDLE_WITH_PACKAGE_NAME);
    }

    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createProvisioningIntentFromNfcIntent")
    public void createProvisioningIntentFromNfcIntent_badIntentAction_returnsNull()
            throws IOException {
        Intent nfcIntent = createNfcIntentWithAction(NFC_INTENT_BAD_ACTION);

        Intent provisioningIntent =
                sDevicePolicyManager.createProvisioningIntentFromNfcIntent(nfcIntent);

        assertThat(provisioningIntent).isNull();
    }

    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createProvisioningIntentFromNfcIntent")
    public void createProvisioningIntentFromNfcIntent_badMimeType_returnsNull()
            throws IOException {
        Intent nfcIntent = createNfcIntentWithMimeType(NFC_INTENT_BAD_MIME);

        Intent provisioningIntent =
                sDevicePolicyManager.createProvisioningIntentFromNfcIntent(nfcIntent);

        assertThat(provisioningIntent).isNull();
    }

    @Test
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#createProvisioningIntentFromNfcIntent")
    public void createProvisioningIntentFromNfcIntent_doesNotIncludeNdefRecord_returnsNull() {
        Intent provisioningIntent = sDevicePolicyManager
                .createProvisioningIntentFromNfcIntent(NFC_INTENT_NO_NDEF_RECORD);

        assertThat(provisioningIntent).isNull();
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureDoesNotHavePermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#checkProvisioningPrecondition")
    public void checkProvisioningPreCondition_withoutRequiredPermission_throwsSecurityException() {
        assertThrows(SecurityException.class, () ->
                sDevicePolicyManager.checkProvisioningPrecondition(
                        DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                        DEVICE_ADMIN_COMPONENT_NAME.getPackageName()));
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#checkProvisioningPrecondition")
    public void checkProvisioningPreCondition_withRequiredPermission_doesNotThrowSecurityException() {
        sDevicePolicyManager.checkProvisioningPrecondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DEVICE_ADMIN_COMPONENT_NAME.getPackageName());

        // Doesn't throw exception.
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @RequireFeature(FEATURE_MANAGED_USERS)
    @RequireDoesNotHaveFeature(FEATURE_DEVICE_ADMIN)
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#checkProvisioningPrecondition")
    public void checkProvisioningPreCondition_withoutDeviceAdminFeature_returnsDeviceAdminNotSupported() {
        assertThat(
                sDevicePolicyManager.checkProvisioningPrecondition(
                        DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                        DEVICE_ADMIN_COMPONENT_NAME.getPackageName()))
                .isEqualTo(DevicePolicyManager.STATUS_DEVICE_ADMIN_NOT_SUPPORTED);
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasNoDpc
    @RequireFeature(FEATURE_MANAGED_USERS)
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#checkProvisioningPrecondition")
    public void checkProvisioningPreCondition_actionPO_returnsOk() {
        assertThat(
                sDevicePolicyManager.checkProvisioningPrecondition(
                        DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                        DEVICE_ADMIN_COMPONENT_NAME.getPackageName()))
                .isEqualTo(DevicePolicyManager.STATUS_OK);
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @RequireDoesNotHaveFeature(FEATURE_MANAGED_USERS)
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#checkProvisioningPrecondition")
    public void checkProvisioningPreCondition_actionPO_withoutManagedUserFeature_returnsManagedUsersNotSupported() {
        assertThat(
                sDevicePolicyManager.checkProvisioningPrecondition(
                        DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                        DEVICE_ADMIN_COMPONENT_NAME.getPackageName()))
                .isEqualTo(DevicePolicyManager.STATUS_MANAGED_USERS_NOT_SUPPORTED);
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasProfileOwner
    @RequireRunOnSecondaryUser
    @RequireFeature(FEATURE_MANAGED_USERS)
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#checkProvisioningPrecondition")
    public void checkProvisioningPreCondition_actionPO_onManagedUser_returnsHasProfileOwner() {
        assertThat(
                sDevicePolicyManager.checkProvisioningPrecondition(
                        DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                        DEVICE_ADMIN_COMPONENT_NAME.getPackageName()))
                .isEqualTo(DevicePolicyManager.STATUS_USER_HAS_PROFILE_OWNER);
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @RequireRunOnWorkProfile
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#checkProvisioningPrecondition")
    public void checkProvisioningPreCondition_actionPO_onManagedProfile_returnsHasProfileOwner() {
        assertThat(
                sDevicePolicyManager.checkProvisioningPrecondition(
                        DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                        DEVICE_ADMIN_COMPONENT_NAME.getPackageName()))
                .isEqualTo(DevicePolicyManager.STATUS_USER_HAS_PROFILE_OWNER);
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasWorkProfile
    @EnsureHasNoProfileOwner
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#checkProvisioningPrecondition")
    public void checkProvisioningPreCondition_actionPO_withWorkProfile_returnsCanNotAddManagedProfile() {
        assertThat(
                sDevicePolicyManager.checkProvisioningPrecondition(
                        DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                        DEVICE_ADMIN_COMPONENT_NAME.getPackageName()))
                .isEqualTo(DevicePolicyManager.STATUS_CANNOT_ADD_MANAGED_PROFILE);
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasNoDpc
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#checkProvisioningPrecondition")
    public void checkProvisioningPreCondition_actionDO_afterSetupComplete_returnsUserSetupComplete() {
        boolean setupComplete = TestApis.users().system().getSetupComplete();
        TestApis.users().system().setSetupComplete(true);

        try {
            assertThat(
                    sDevicePolicyManager.checkProvisioningPrecondition(
                            DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                            DEVICE_ADMIN_COMPONENT_NAME.getPackageName()))
                    .isEqualTo(DevicePolicyManager.STATUS_USER_SETUP_COMPLETED);

        } finally {
            TestApis.users().system().setSetupComplete(setupComplete);
        }
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasNoDpc
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#checkProvisioningPrecondition")
    public void checkProvisioningPreCondition_actionDO_returnsOk() {
        boolean setupComplete = TestApis.users().system().getSetupComplete();
        TestApis.users().system().setSetupComplete(false);

        try {
            assertThat(
                    sDevicePolicyManager.checkProvisioningPrecondition(
                            DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                            DEVICE_ADMIN_COMPONENT_NAME.getPackageName()))
                    .isEqualTo(DevicePolicyManager.STATUS_OK);

        } finally {
            TestApis.users().system().setSetupComplete(setupComplete);
        }
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasNoDpc
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#checkProvisioningPrecondition")
    public void checkProvisioningPreCondition_actionDO_setupComplete_returnsUserSetupCompleted() {
        boolean setupComplete = TestApis.users().current().getSetupComplete();
        TestApis.users().current().setSetupComplete(true);

        try {
            assertThat(
                    sDevicePolicyManager.checkProvisioningPrecondition(
                            DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                            DEVICE_ADMIN_COMPONENT_NAME.getPackageName()))
                    .isEqualTo(DevicePolicyManager.STATUS_USER_SETUP_COMPLETED);

        } finally {
            TestApis.users().current().setSetupComplete(setupComplete);
        }
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasDeviceOwner
    @RequireNotWatch(reason = "Watches will fail because they're already paired")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#checkProvisioningPrecondition")
    public void checkProvisioningPreCondition_actionDO_onManagedDevice_returnsHasDeviceOwner() {
        boolean setupComplete = TestApis.users().current().getSetupComplete();
        TestApis.users().current().setSetupComplete(false);

        try {
            assertThat(
                    sDevicePolicyManager.checkProvisioningPrecondition(
                            DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                            DEVICE_ADMIN_COMPONENT_NAME.getPackageName()))
                    .isEqualTo(DevicePolicyManager.STATUS_HAS_DEVICE_OWNER);

        } finally {
            TestApis.users().current().setSetupComplete(setupComplete);
        }
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @RequireRunOnSecondaryUser
    @EnsureHasNoProfileOwner
    @RequireNotHeadlessSystemUserMode(reason = "TODO(b/242189747): Remove or give reason")
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#checkProvisioningPrecondition")
    public void checkProvisioningPreCondition_actionDO_onNonSystemUser_returnsNotSystemUser() {
        boolean setupComplete = TestApis.users().current().getSetupComplete();
        TestApis.users().current().setSetupComplete(false);

        try {
            assertThat(
                    sDevicePolicyManager.checkProvisioningPrecondition(
                            DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                            DEVICE_ADMIN_COMPONENT_NAME.getPackageName()))
                    .isEqualTo(DevicePolicyManager.STATUS_NOT_SYSTEM_USER);

        } finally {
            TestApis.users().current().setSetupComplete(setupComplete);
        }
    }

    // TODO(b/208843126): add more CTS coverage for setUserProvisioningState
    @Postsubmit(reason = "New test")
    @Test
    @EnsureDoesNotHavePermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#setUserProvisioningState")
    public void setUserProvisioningState_withoutRequiredPermission_throwsSecurityException() {
        assertThrows(SecurityException.class, () ->
                sDevicePolicyManager.setUserProvisioningState(
                        DevicePolicyManager.STATE_USER_UNMANAGED,
                        TestApis.users().current().userHandle()));
    }

    @Ignore("b/284786466")
    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @RequireRunOnWorkProfile
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#setUserProvisioningState")
    public void setUserProvisioningState_withRequiredPermission_doesNotThrowSecurityException() {
        sDevicePolicyManager.setUserProvisioningState(
                DevicePolicyManager.STATE_USER_SETUP_COMPLETE,
                TestApis.users().current().userHandle());

        // Doesn't throw exception.
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasNoDpc
    @RequireFeature(FEATURE_MANAGED_USERS)
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#setUserProvisioningState")
    public void setUserProvisioningState_unmanagedDevice_stateUserSetupIncomplete_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class, () ->
                sDevicePolicyManager.setUserProvisioningState(
                        DevicePolicyManager.STATE_USER_SETUP_INCOMPLETE,
                        TestApis.users().current().userHandle()));
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasNoDpc
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#setUserProvisioningState")
    public void setUserProvisioningState_unmanagedDevice_stateUserSetupComplete_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class, () ->
                sDevicePolicyManager.setUserProvisioningState(
                        DevicePolicyManager.STATE_USER_SETUP_COMPLETE,
                        TestApis.users().current().userHandle()));

    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasNoDpc
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#setUserProvisioningState")
    public void setUserProvisioningState_unmanagedDevice_stateUserSetupFinalized_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class, () ->
                sDevicePolicyManager.setUserProvisioningState(
                        STATE_USER_SETUP_FINALIZED,
                        TestApis.users().current().userHandle()));
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasNoDpc
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#setUserProvisioningState")
    public void setUserProvisioningState_unmanagedDevice_stateUserProfileComplete_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class, () ->
                sDevicePolicyManager.setUserProvisioningState(
                        DevicePolicyManager.STATE_USER_PROFILE_COMPLETE,
                        TestApis.users().current().userHandle()));
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasNoDpc
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#setUserProvisioningState")
    public void setUserProvisioningState_unmanagedDevice_stateUserProfileFinalized_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class, () ->
                sDevicePolicyManager.setUserProvisioningState(
                        DevicePolicyManager.STATE_USER_PROFILE_FINALIZED,
                        TestApis.users().current().userHandle()));
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasNoDpc
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#setUserProvisioningState")
    public void setUserProvisioningState_settingToSameState_throwIllegalStateException() {
        assertThrows(IllegalStateException.class, () ->
                sDevicePolicyManager.setUserProvisioningState(
                        DevicePolicyManager.STATE_USER_UNMANAGED,
                        TestApis.users().current().userHandle()));
    }

    @Test
    @ApiTest(apis = "android.app.admin.ManagedProfileProvisioningParams#setAdminExtras")
    public void setAdminExtras_managedProfileParams_works() {
        ManagedProfileProvisioningParams params =
                createManagedProfileProvisioningParamsBuilder()
                        .setAdminExtras(ADMIN_EXTRAS_BUNDLE)
                        .build();

        assertBundlesEqual(params.getAdminExtras(), ADMIN_EXTRAS_BUNDLE);
    }

    @Test
    @ApiTest(apis = "android.app.admin.ManagedProfileProvisioningParams#setAdminExtras")
    public void setAdminExtras_managedProfileParams_modifyBundle_internalBundleNotModified() {
        PersistableBundle adminExtrasBundle = new PersistableBundle(ADMIN_EXTRAS_BUNDLE);
        ManagedProfileProvisioningParams params =
                createManagedProfileProvisioningParamsBuilder()
                        .setAdminExtras(adminExtrasBundle)
                        .build();

        adminExtrasBundle.putString(TEST_KEY, TEST_VALUE);

        assertBundlesEqual(params.getAdminExtras(), ADMIN_EXTRAS_BUNDLE);
    }

    @Test
    @ApiTest(apis = "android.app.admin.ManagedProfileProvisioningParams#getAdminExtras")
    public void getAdminExtras_managedProfileParams_modifyResult_internalBundleNotModified() {
        PersistableBundle adminExtrasBundle = new PersistableBundle(ADMIN_EXTRAS_BUNDLE);
        ManagedProfileProvisioningParams params =
                createManagedProfileProvisioningParamsBuilder()
                        .setAdminExtras(adminExtrasBundle)
                        .build();

        params.getAdminExtras().putString(TEST_KEY, TEST_VALUE);

        assertBundlesEqual(params.getAdminExtras(), ADMIN_EXTRAS_BUNDLE);
    }

    @Test
    @ApiTest(apis = "android.app.admin.ManagedProfileProvisioningParams#setAdminExtras")
    public void setAdminExtras_managedProfileParams_emptyBundle_works() {
        ManagedProfileProvisioningParams params =
                createManagedProfileProvisioningParamsBuilder()
                        .setAdminExtras(new PersistableBundle())
                        .build();

        assertThat(params.getAdminExtras().isEmpty()).isTrue();
    }

    @Test
    @ApiTest(apis = "android.app.admin.ManagedProfileProvisioningParams#setAdminExtras")
    public void setAdminExtras_managedProfileParams_nullBundle_works() {
        ManagedProfileProvisioningParams params =
                createManagedProfileProvisioningParamsBuilder()
                        .setAdminExtras(null)
                        .build();

        assertThat(params.getAdminExtras().isEmpty()).isTrue();
    }

    @Test
    @ApiTest(apis = "android.app.admin.ManagedProfileProvisioningParams#setAdminExtras")
    public void setAdminExtras_fullyManagedParams_works() {
        FullyManagedDeviceProvisioningParams params =
                createDefaultManagedDeviceProvisioningParamsBuilder()
                        .setAdminExtras(ADMIN_EXTRAS_BUNDLE)
                        .build();

        assertBundlesEqual(params.getAdminExtras(), ADMIN_EXTRAS_BUNDLE);
    }

    @Test
    @ApiTest(apis = "android.app.admin.ManagedProfileProvisioningParams#setAdminExtras")
    public void setAdminExtras_fullyManagedParams_modifyBundle_internalBundleNotModified() {
        PersistableBundle adminExtrasBundle = new PersistableBundle(ADMIN_EXTRAS_BUNDLE);
        FullyManagedDeviceProvisioningParams params =
                createDefaultManagedDeviceProvisioningParamsBuilder()
                        .setAdminExtras(adminExtrasBundle)
                        .build();

        adminExtrasBundle.putString(TEST_KEY, TEST_VALUE);

        assertBundlesEqual(params.getAdminExtras(), ADMIN_EXTRAS_BUNDLE);
    }

    @Test
    @ApiTest(apis = "android.app.admin.ManagedProfileProvisioningParams#getAdminExtras")
    public void getAdminExtras_fullyManagedParams_modifyResult_internalBundleNotModified() {
        PersistableBundle adminExtrasBundle = new PersistableBundle(ADMIN_EXTRAS_BUNDLE);
        FullyManagedDeviceProvisioningParams params =
                createDefaultManagedDeviceProvisioningParamsBuilder()
                        .setAdminExtras(adminExtrasBundle)
                        .build();

        params.getAdminExtras().putString(TEST_KEY, TEST_VALUE);

        assertBundlesEqual(params.getAdminExtras(), ADMIN_EXTRAS_BUNDLE);
    }

    @Test
    @ApiTest(apis = "android.app.admin.ManagedProfileProvisioningParams#setAdminExtras")
    public void setAdminExtras_fullyManagedParams_emptyBundle_works() {
        FullyManagedDeviceProvisioningParams params =
                createDefaultManagedDeviceProvisioningParamsBuilder()
                        .setAdminExtras(new PersistableBundle())
                        .build();

        assertThat(params.getAdminExtras().isEmpty()).isTrue();
    }

    @Test
    @ApiTest(apis = "android.app.admin.ManagedProfileProvisioningParams#setAdminExtras")
    public void setAdminExtras_fullyManagedParams_nullBundle_works() {
        FullyManagedDeviceProvisioningParams params =
                createDefaultManagedDeviceProvisioningParamsBuilder()
                        .setAdminExtras(null)
                        .build();

        assertThat(params.getAdminExtras().isEmpty()).isTrue();
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureDoesNotHavePermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#setDpcDownloaded")
    public void setDpcDownloaded_withoutRequiredPermission_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> sDevicePolicyManager.setDpcDownloaded(true));
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public void setDpcDownloaded_withRequiredPermission_doesNotThrowSecurityException() {
        sDevicePolicyManager.setDpcDownloaded(true);

        // Doesn't throw exception
    }

    @Postsubmit(reason = "New test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public void isDpcDownloaded_returnsResultOfSetDpcDownloaded() {
        sDevicePolicyManager.setDpcDownloaded(true);

        assertThat(sDevicePolicyManager.isDpcDownloaded()).isTrue();
    }

    @Postsubmit(reason = "new test")
    @Test
    @EnsureHasWorkProfile
    @EnsureDoesNotHavePermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public void finalizeWorkProfileProvisioning_withoutPermission_throwsException() {
        assertThrows(SecurityException.class, () ->
                sDevicePolicyManager.finalizeWorkProfileProvisioning(
                        sDeviceState.workProfile().userHandle(),
                        /* migratedAccount= */ null));
    }

    @Postsubmit(reason = "new test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public void finalizeWorkProfileProvisioning_nullManagedProfileUser_throwsException() {
        assertThrows(NullPointerException.class, () ->
                sDevicePolicyManager.finalizeWorkProfileProvisioning(
                        /* managedProfileUser= */ null,
                        /* migratedAccount= */ null));
    }

    @Postsubmit(reason = "new test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public void finalizeWorkProfileProvisioning_nonExistingManagedProfileUser_throwsException() {
        assertThrows(IllegalStateException.class, () ->
                sDevicePolicyManager.finalizeWorkProfileProvisioning(
                        /* managedProfileUser= */ TestApis.users().nonExisting().userHandle(),
                        /* migratedAccount= */ null));
    }

    @Postsubmit(reason = "new test")
    @Test
    @EnsureHasSecondaryUser
    @EnsureHasNoDpc
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @RequireFeature(FEATURE_DEVICE_ADMIN)
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#finalizeWorkProfileProvisioning")
    public void finalizeWorkProfileProvisioning_managedUser_throwsException() {
        RemoteDpc dpc = RemoteDpc.setAsProfileOwner(sDeviceState.secondaryUser());
        try {
            assertThrows(IllegalStateException.class, () ->
                    sDevicePolicyManager.finalizeWorkProfileProvisioning(
                            /* managedProfileUser= */ sDeviceState.secondaryUser().userHandle(),
                            /* migratedAccount= */ null));
        } finally {
            dpc.remove();
        }
    }

    @Postsubmit(reason = "new test")
    @Test
    @EnsureHasWorkProfile
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public void finalizeWorkProfileProvisioning_managedProfileUserWithoutProfileOwner_throwsException() {
        RemoteDpc dpc = sDeviceState.profileOwner(sDeviceState.workProfile());
        try {
            dpc.remove();
            assertThrows(IllegalStateException.class, () ->
                    sDevicePolicyManager.finalizeWorkProfileProvisioning(
                            /* managedProfileUser= */ sDeviceState.workProfile().userHandle(),
                            /* migratedAccount= */ null));
        } finally {
            RemoteDpc.setAsProfileOwner(sDeviceState.workProfile());
        }
    }

    @Postsubmit(reason = "new test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasNoProfileOwner
    @EnsureHasNoDeviceOwner
    @EnsureHasWorkProfile
    public void finalizeWorkProfileProvisioning_valid_sendsBroadcast() {
        try (TestAppInstance personalInstance = RemoteDpc.forDevicePolicyController(
                TestApis.devicePolicy().getProfileOwner(
                        sDeviceState.workProfile())).testApp().install()) {
            // We know that RemoteDPC is the Profile Owner - we need the same package on the
            // personal side to receive the broadcast
            personalInstance.registerReceiver(new IntentFilter(ACTION_MANAGED_PROFILE_PROVISIONED));
            sDevicePolicyManager.finalizeWorkProfileProvisioning(
                    /* managedProfileUser= */ sDeviceState.workProfile().userHandle(),
                    /* migratedAccount= */ null);

            BroadcastReceivedEvent event = personalInstance.events().broadcastReceived()
                    .whereIntent().action().isEqualTo(ACTION_MANAGED_PROFILE_PROVISIONED)
                    .waitForEvent();
            assertThat((UserHandle) event.intent().getParcelableExtra(EXTRA_USER))
                    .isEqualTo(sDeviceState.workProfile().userHandle());

        }
    }

    @Postsubmit(reason = "new test")
    @Test
    @EnsureHasPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @EnsureHasNoProfileOwner
    @EnsureHasNoDeviceOwner
    @EnsureHasWorkProfile
    public void finalizeWorkProfileProvisioning_withAccount_broadcastIncludesAccount() {
        try (TestAppInstance personalInstance = RemoteDpc.forDevicePolicyController(
                TestApis.devicePolicy().getProfileOwner(
                        sDeviceState.workProfile())).testApp().install()) {
            // We know that RemoteDPC is the Profile Owner - we need the same package on the
            // personal side to receive the broadcast
            personalInstance.registerReceiver(new IntentFilter(ACTION_MANAGED_PROFILE_PROVISIONED));

            sDevicePolicyManager.finalizeWorkProfileProvisioning(
                    /* managedProfileUser= */ sDeviceState.workProfile().userHandle(),
                    /* migratedAccount= */ ACCOUNT_WITH_EXISTING_TYPE);

            BroadcastReceivedEvent event = personalInstance.events().broadcastReceived()
                    .whereIntent().action().isEqualTo(ACTION_MANAGED_PROFILE_PROVISIONED)
                    .waitForEvent();
            assertThat((Account) event.intent()
                    .getParcelableExtra(EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE))
                    .isEqualTo(ACCOUNT_WITH_EXISTING_TYPE);

        }
    }

    private Intent createNfcIntentFromMap(Map<String, String> input)
            throws IOException {
        return createNfcIntent(input, ACTION_NDEF_DISCOVERED, MIME_TYPE_PROVISIONING_NFC);
    }

    private Intent createNfcIntentWithAction(String action)
            throws IOException {
        return createNfcIntent(NFC_DATA_VALID, action, MIME_TYPE_PROVISIONING_NFC);
    }

    private Intent createNfcIntentWithMimeType(String mime)
            throws IOException {
        return createNfcIntent(NFC_DATA_VALID, ACTION_NDEF_DISCOVERED, mime);
    }

    private static HashMap<String, String> createNfcIntentData() {
        HashMap<String, String> nfcIntentInput = new HashMap<>();
        nfcIntentInput.put(EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                NFC_INTENT_COMPONENT_NAME);
        nfcIntentInput.put(EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME, NFC_INTENT_PACKAGE_NAME);
        nfcIntentInput.put(EXTRA_PROVISIONING_LOCALE, NFC_INTENT_LOCALE);
        nfcIntentInput.put(EXTRA_PROVISIONING_TIME_ZONE, NFC_INTENT_TIMEZONE);
        nfcIntentInput.put(EXTRA_PROVISIONING_WIFI_SSID, NFC_INTENT_WIFI_SSID);
        nfcIntentInput.put(EXTRA_PROVISIONING_WIFI_SECURITY_TYPE, NFC_INTENT_WIFI_SECURITY_TYPE);
        nfcIntentInput.put(EXTRA_PROVISIONING_WIFI_PASSWORD, NFC_INTENT_WIFI_PASSWORD);
        nfcIntentInput.put(EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                createAdminExtrasProperties());
        nfcIntentInput.put(EXTRA_PROVISIONING_ROLE_HOLDER_EXTRAS_BUNDLE,
                createRoleHolderExtrasProperties());
        nfcIntentInput.put(EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS, "true");
        nfcIntentInput.put(EXTRA_PROVISIONING_LOCAL_TIME, String.valueOf(NFC_INTENT_LOCAL_TIME));
        nfcIntentInput.put(EXTRA_PROVISIONING_WIFI_PROXY_PORT,
                String.valueOf(NFC_INTENT_WIFI_PROXY_PORT));
        return nfcIntentInput;
    }

    private Intent createNfcIntent(Map<String, String> input, String action, String mime)
            throws IOException {
        Intent nfcIntent = new Intent(action);
        Parcelable[] nfcMessages =
                new Parcelable[]{createNdefMessage(input, mime)};
        nfcIntent.putExtra(EXTRA_NDEF_MESSAGES, nfcMessages);

        return nfcIntent;
    }

    private NdefMessage createNdefMessage(Map<String, String> provisioningValues, String mime)
            throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        Properties properties = new Properties();
        // Store all the values into the Properties object
        for (Map.Entry<String, String> e : provisioningValues.entrySet()) {
            properties.put(e.getKey(), e.getValue());
        }

        properties.store(stream, NFC_INTENT_PROVISIONING_SAMPLE);
        NdefRecord record = NdefRecord.createMime(mime, stream.toByteArray());

        return new NdefMessage(new NdefRecord[]{record});
    }

    private static Bundle createExpectedValidBundle() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                ComponentName.unflattenFromString(NFC_INTENT_COMPONENT_NAME));
        bundle.putString(EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME, NFC_INTENT_PACKAGE_NAME);
        bundle.putString(EXTRA_PROVISIONING_LOCALE, NFC_INTENT_LOCALE);
        bundle.putString(EXTRA_PROVISIONING_TIME_ZONE, NFC_INTENT_TIMEZONE);
        bundle.putString(EXTRA_PROVISIONING_WIFI_SSID, NFC_INTENT_WIFI_SSID);
        bundle.putString(EXTRA_PROVISIONING_WIFI_SECURITY_TYPE, NFC_INTENT_WIFI_SECURITY_TYPE);
        bundle.putString(EXTRA_PROVISIONING_WIFI_PASSWORD, NFC_INTENT_WIFI_PASSWORD);
        bundle.putParcelable(EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, ADMIN_EXTRAS_BUNDLE);
        bundle.putParcelable(EXTRA_PROVISIONING_ROLE_HOLDER_EXTRAS_BUNDLE,
                ROLE_HOLDER_EXTRAS_BUNDLE);
        bundle.putBoolean(EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS, true);
        bundle.putLong(EXTRA_PROVISIONING_LOCAL_TIME, NFC_INTENT_LOCAL_TIME);
        bundle.putInt(EXTRA_PROVISIONING_WIFI_PROXY_PORT, NFC_INTENT_WIFI_PROXY_PORT);
        bundle.putInt(EXTRA_PROVISIONING_TRIGGER, PROVISIONING_TRIGGER_NFC);
        return bundle;
    }

    private static String createRoleHolderExtrasProperties() {
        return "role-holder-extras-key=role holder extras value\n";
    }

    private static Bundle createExpectedBundleWithComponentName() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                ComponentName.unflattenFromString(NFC_INTENT_COMPONENT_NAME));
        bundle.putInt(EXTRA_PROVISIONING_TRIGGER, PROVISIONING_TRIGGER_NFC);
        return bundle;
    }

    private static Bundle createExpectedBundleWithPackageName() {
        Bundle bundle = new Bundle();
        bundle.putString(EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME, NFC_INTENT_PACKAGE_NAME);
        bundle.putInt(EXTRA_PROVISIONING_TRIGGER, PROVISIONING_TRIGGER_NFC);
        return bundle;
    }

    FullyManagedDeviceProvisioningParams.Builder createDefaultManagedDeviceProvisioningParamsBuilder() {
        return new FullyManagedDeviceProvisioningParams.Builder(
                DEVICE_ADMIN_COMPONENT_NAME,
                DEVICE_OWNER_NAME)
                // Don't remove system apps during provisioning until the testing
                // infrastructure supports restoring uninstalled apps.
                .setLeaveAllSystemAppsEnabled(true);
    }

    private static PersistableBundle createAdminExtrasBundle() {
        PersistableBundle result = new PersistableBundle();
        result.putString("admin-extras-key", "admin extras value");
        return result;
    }

    private static String createAdminExtrasProperties() {
        return "admin-extras-key=admin extras value\n";
    }

    private static PersistableBundle createRoleHolderExtrasBundle() {
        PersistableBundle result = new PersistableBundle();
        result.putString("role-holder-extras-key", "role holder extras value");
        return result;
    }

    private static void assertBundlesEqual(BaseBundle bundle1, BaseBundle bundle2) {
        if (bundle1 != null) {
            assertWithMessage("Intent bundles are not equal")
                    .that(bundle2).isNotNull();
            assertWithMessage("Intent bundles are not equal")
                    .that(bundle1.keySet().size()).isEqualTo(bundle2.keySet().size());
            for (String key : bundle1.keySet()) {
                if (bundle1.get(key) != null && bundle1.get(key) instanceof PersistableBundle) {
                    assertWithMessage("Intent bundles are not equal")
                            .that(bundle2.containsKey(key)).isTrue();
                    assertWithMessage("Intent bundles are not equal")
                            .that(bundle2.get(key)).isInstanceOf(PersistableBundle.class);
                    assertBundlesEqual(
                            (PersistableBundle) bundle1.get(key),
                            (PersistableBundle) bundle2.get(key));
                } else {
                    assertWithMessage("Intent bundles are not equal")
                            .that(bundle1.get(key))
                            .isEqualTo(bundle2.get(key));
                }
            }
        } else {
            assertWithMessage("Intent bundles are not equal").that(bundle2).isNull();
        }
    }
}
