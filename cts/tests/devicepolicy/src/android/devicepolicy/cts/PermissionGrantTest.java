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

import static android.Manifest.permission.ACCESS_BACKGROUND_LOCATION;
import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACTIVITY_RECOGNITION;
import static android.Manifest.permission.BODY_SENSORS;
import static android.Manifest.permission.CAMERA;
import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.READ_CALENDAR;
import static android.Manifest.permission.READ_CONTACTS;
import static android.Manifest.permission.READ_SMS;
import static android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
import static android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED;
import static android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED;

import static com.android.bedstead.nene.notifications.NotificationListenerQuerySubject.assertThat;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.testng.Assert.assertThrows;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.AfterClass;
import com.android.bedstead.harrier.annotations.BeforeClass;
import com.android.bedstead.harrier.annotations.NotificationsTest;
import com.android.bedstead.harrier.annotations.enterprise.CanSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.CannotSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.NegativePolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.PositivePolicyTest;
import com.android.bedstead.harrier.policies.SetPermissionGrantState;
import com.android.bedstead.harrier.policies.SetSensorPermissionGranted;
import com.android.bedstead.harrier.policies.SetSmsPermissionGranted;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.notifications.NotificationListener;
import com.android.bedstead.testapp.TestApp;
import com.android.bedstead.testapp.TestAppInstance;
import com.android.bedstead.testapp.TestAppProvider;

import com.google.common.collect.ImmutableSet;

import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(BedsteadJUnit4.class)
public final class PermissionGrantTest {

    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    // From PermissionController ADMIN_AUTO_GRANTED_PERMISSIONS_ALERTING_NOTIFICATION_CHANNEL_ID
    private static final String AUTO_GRANTED_PERMISSIONS_CHANNEL_ID =
            "alerting auto granted permissions";
    private static final String PERMISSION_CONTROLLER_PACKAGE_NAME =
            TestApis.context().instrumentedContext().getPackageManager()
                    .getPermissionControllerPackageName();

    private static final String GRANTABLE_PERMISSION = READ_CALENDAR;

    private static final String DEVELOPMENT_PERMISSION = INTERACT_ACROSS_USERS;

    private static final ImmutableSet<String> SENSOR_PERMISSIONS = ImmutableSet.of(
            ACCESS_FINE_LOCATION,
            ACCESS_BACKGROUND_LOCATION,
            ACCESS_COARSE_LOCATION,
            CAMERA,
            ACTIVITY_RECOGNITION,
            BODY_SENSORS);

    private static final ImmutableSet<String> LOCATION_PERMISSIONS = ImmutableSet.of(
            ACCESS_FINE_LOCATION,
            ACCESS_BACKGROUND_LOCATION,
            ACCESS_COARSE_LOCATION);

    private static final ImmutableSet<String> DENYABLE_PERMISSIONS = ImmutableSet.<String>builder()
            .add(GRANTABLE_PERMISSION)
            .add(READ_SMS) // All DPCs can deny sms permission
            .addAll(SENSOR_PERMISSIONS) // All DPCs can deny sensor permissions
            .build();

    private static final String NON_EXISTING_PACKAGE_NAME = "non.existing.package";
    private static final String NOT_DECLARED_PERMISSION = "not.declared.permission";

    private static final TestAppProvider sTestAppProvider = new TestAppProvider();
    private static final TestApp sTestApp = sTestAppProvider.query()
            .wherePermissions().contains(
                    READ_SMS,
                    CAMERA,
                    ACTIVITY_RECOGNITION,
                    BODY_SENSORS,
                    READ_CONTACTS,
                    ACCESS_FINE_LOCATION,
                    ACCESS_BACKGROUND_LOCATION,
                    ACCESS_COARSE_LOCATION
            ).wherePermissions().doesNotContain(
                    NOT_DECLARED_PERMISSION
            ).get();
    private static TestAppInstance sTestAppInstance;

    @BeforeClass
    public static void setupClass() {
        sTestAppInstance = sTestApp.install(TestApis.users().instrumented());
    }

    @AfterClass
    public static void teardownClass() {
        sTestAppInstance.uninstall();
    }

    @Test
    @NegativePolicyTest(policy = SetSmsPermissionGranted.class)
    public void getPermissionGrantState_smsPermission_notAbleToSetState_alsoCantReadState() {
        int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                .getPermissionGrantState(sDeviceState.dpc().componentName(),
                        sTestApp.packageName(), READ_SMS);
        try {
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    READ_SMS, PERMISSION_GRANT_STATE_GRANTED);

            sTestApp.pkg().grantPermission(TestApis.users().instrumented(), READ_SMS);
            // TODO(b/204041462): Replace granting the permission here with the user pressing the
            //  "deny" button on the permission

            assertWithMessage("Should not be able to read permission grant state but can")
                    .that(sDeviceState.dpc().devicePolicyManager().getPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            READ_SMS))
                    .isEqualTo(PERMISSION_GRANT_STATE_DEFAULT);
        } finally {
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    READ_SMS, existingGrantState);
            sTestApp.pkg().denyPermission(TestApis.users().instrumented(), READ_SMS);
        }
    }

    @Test
    @NegativePolicyTest(policy = SetSensorPermissionGranted.class)
    public void getPermissionGrantState_sensorPermission_notAbleToSetState_alsoCantReadState() {
        // TODO(b/188893663): Replace with parameterization
        for (String permission : SENSOR_PERMISSIONS) {
            int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                    .getPermissionGrantState(sDeviceState.dpc().componentName(),
                            sTestApp.packageName(), permission);
            try {
                sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        permission, PERMISSION_GRANT_STATE_GRANTED);

                sTestApp.pkg().grantPermission(TestApis.users().instrumented(), permission);
                // TODO(b/204041462): Replace granting the permission here with the user pressing the
                //  "deny" button on the permission

                assertWithMessage("Should not be able to read permission grant state but can")
                        .that(sDeviceState.dpc().devicePolicyManager().getPermissionGrantState(
                                sDeviceState.dpc().componentName(), sTestApp.packageName(),
                                permission))
                        .isEqualTo(PERMISSION_GRANT_STATE_DEFAULT);
            } finally {
                sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        permission, existingGrantState);
                sTestApp.pkg().denyPermission(TestApis.users().instrumented(), permission);
            }
        }
    }

    @Test
    @CanSetPolicyTest(policy = SetPermissionGrantState.class)
    public void denyPermission_setsGrantState() {
        // TODO(b/188893663): Replace with parameterization
        for (String permission : DENYABLE_PERMISSIONS) {
            int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                    .getPermissionGrantState(sDeviceState.dpc().componentName(),
                            sTestApp.packageName(), permission);

            try {
                boolean wasSet = sDeviceState.dpc().devicePolicyManager()
                        .setPermissionGrantState(
                                sDeviceState.dpc().componentName(), sTestApp.packageName(),
                                permission, PERMISSION_GRANT_STATE_DENIED);

                assertWithMessage("setPermissionGrantState did not return true")
                        .that(wasSet).isTrue();
                assertWithMessage("Permission grant state should be set to denied but was not")
                        .that(sDeviceState.dpc().devicePolicyManager().getPermissionGrantState(
                                sDeviceState.dpc().componentName(), sTestApp.packageName(),
                                permission))
                        .isEqualTo(PERMISSION_GRANT_STATE_DENIED);
            } finally {
                sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        permission, existingGrantState);
            }
        }
    }

    @Test
    @CanSetPolicyTest(policy = SetPermissionGrantState.class)
    public void grantPermission_setsGrantState() {
        int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                .getPermissionGrantState(sDeviceState.dpc().componentName(),
                        sTestApp.packageName(), GRANTABLE_PERMISSION);

        try {
            boolean wasSet = sDeviceState.dpc().devicePolicyManager()
                    .setPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            GRANTABLE_PERMISSION, PERMISSION_GRANT_STATE_GRANTED);

            assertWithMessage("setPermissionGrantState did not return true")
                    .that(wasSet).isTrue();
            assertWithMessage("Permission grant state should be set to granted but was not")
                    .that(sDeviceState.dpc().devicePolicyManager().getPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            GRANTABLE_PERMISSION))
                    .isEqualTo(PERMISSION_GRANT_STATE_GRANTED);
        } finally {
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    GRANTABLE_PERMISSION, existingGrantState);
        }
    }

    @Test
    @PositivePolicyTest(policy = SetPermissionGrantState.class)
    public void denyPermission_permissionIsDenied() {
        // TODO(b/188893663): Replace with parameterization
        for (String permission : DENYABLE_PERMISSIONS) {
            int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                    .getPermissionGrantState(sDeviceState.dpc().componentName(),
                            sTestApp.packageName(), permission);
            try {
                sTestApp.pkg().grantPermission(TestApis.users().instrumented(), permission);
                sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        permission, PERMISSION_GRANT_STATE_DENIED);

                assertWithMessage("Permission should not be granted but was").that(
                        sTestApp.pkg().hasPermission(permission)).isFalse();

                // TODO(b/204041462): Test that the app cannot request the permission
            } finally {
                sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        permission, existingGrantState);
                sTestApp.pkg().denyPermission(TestApis.users().instrumented(), permission);
            }
        }
    }

    @Test
    @PositivePolicyTest(policy = SetPermissionGrantState.class)
    public void grantPermission_permissionIsGranted() {
        int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                .getPermissionGrantState(sDeviceState.dpc().componentName(),
                        sTestApp.packageName(), GRANTABLE_PERMISSION);
        try {
            sTestApp.pkg().denyPermission(TestApis.users().instrumented(),
                    GRANTABLE_PERMISSION);
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    GRANTABLE_PERMISSION, PERMISSION_GRANT_STATE_GRANTED);

            assertWithMessage("Permission should be granted but was not").that(
                    sTestApp.pkg().hasPermission(GRANTABLE_PERMISSION)).isTrue();
        } finally {
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    GRANTABLE_PERMISSION, existingGrantState);
            sTestApp.pkg().denyPermission(TestApis.users().instrumented(),
                    GRANTABLE_PERMISSION);
        }
    }

    @Test
    @NegativePolicyTest(policy = SetPermissionGrantState.class)
    public void denyPermission_doesNotApply_permissionIsNotDenied() {
        // TODO(b/188893663): Replace with parameterization
        for (String permission : DENYABLE_PERMISSIONS) {
            try {
                sTestApp.pkg().grantPermission(TestApis.users().instrumented(), permission);

                sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        permission, PERMISSION_GRANT_STATE_DENIED);

                assertWithMessage("Permission should not be denied but was").that(
                        sTestApp.pkg().hasPermission(permission)).isTrue();
            } finally {
                sTestApp.pkg().denyPermission(TestApis.users().instrumented(), permission);
            }
        }
    }

    @Test
    @NegativePolicyTest(policy = SetPermissionGrantState.class)
    public void grantPermission_doesNotApply_permissionIsNotGranted() {
        // TODO(b/188893663): Replace with parameterization
        for (String permission : DENYABLE_PERMISSIONS) {
            try {
                sTestApp.pkg().denyPermission(TestApis.users().instrumented(), permission);

                sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        permission, PERMISSION_GRANT_STATE_GRANTED);

                assertWithMessage("Permission should not be granted but was").that(
                        sTestApp.pkg().hasPermission(permission)).isFalse();
            } finally {
                sTestApp.pkg().denyPermission(TestApis.users().instrumented(), permission);
            }
        }
    }

    // TODO(b/204041462): Add test that the user can manually grant sensor permissions

    @Test
    @CanSetPolicyTest(policy = SetPermissionGrantState.class)
    public void grantDevelopmentPermission_cannotGrant() {
        int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                .getPermissionGrantState(sDeviceState.dpc().componentName(),
                        sTestApp.packageName(), DEVELOPMENT_PERMISSION);
        try {
            boolean wasSet =
                    sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            DEVELOPMENT_PERMISSION, PERMISSION_GRANT_STATE_GRANTED);

            assertWithMessage("setPermissionGrantState did not return false")
                    .that(wasSet).isFalse();
            assertWithMessage("Permission grant state should not be set to granted but was")
                    .that(sDeviceState.dpc().devicePolicyManager().getPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            DEVELOPMENT_PERMISSION))
                    .isNotEqualTo(PERMISSION_GRANT_STATE_GRANTED);
            assertWithMessage("Permission should not be granted but was")
                    .that(sTestApp.pkg().hasPermission(
                    DEVELOPMENT_PERMISSION)).isFalse();
        } finally {
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    DEVELOPMENT_PERMISSION, existingGrantState);
        }
    }

    @Test
    @CanSetPolicyTest(policy = SetPermissionGrantState.class)
    public void denyDevelopmentPermission_cannotDeny() {
        int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                .getPermissionGrantState(sDeviceState.dpc().componentName(),
                        sTestApp.packageName(), DEVELOPMENT_PERMISSION);
        try {
            boolean wasSet =
                    sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            DEVELOPMENT_PERMISSION, PERMISSION_GRANT_STATE_DENIED);

            assertWithMessage("setPermissionGrantState did not return false")
                    .that(wasSet).isFalse();
            assertWithMessage("Permission grant state should not be set to granted but was")
                    .that(sDeviceState.dpc().devicePolicyManager().getPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            DEVELOPMENT_PERMISSION))
                    .isNotEqualTo(PERMISSION_GRANT_STATE_DENIED);
        } finally {
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    DEVELOPMENT_PERMISSION, existingGrantState);
        }
    }

    @Test
    @CanSetPolicyTest(policy = SetPermissionGrantState.class)
    public void setDevelopmentPermissionToDefault_cannotSet() {
        int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                .getPermissionGrantState(sDeviceState.dpc().componentName(),
                        sTestApp.packageName(), DEVELOPMENT_PERMISSION);
        try {
            boolean wasSet =
                    sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            DEVELOPMENT_PERMISSION, PERMISSION_GRANT_STATE_DEFAULT);

            assertWithMessage("setPermissionGrantState did not return false")
                    .that(wasSet).isFalse();
        } finally {
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    DEVELOPMENT_PERMISSION, existingGrantState);
        }
    }

    @Test
    @CanSetPolicyTest(policy = SetSmsPermissionGranted.class)
    public void grantSmsPermission_setsGrantState() {
        int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                .getPermissionGrantState(sDeviceState.dpc().componentName(),
                        sTestApp.packageName(), READ_SMS);
        try {
            boolean wasSet =
                    sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            READ_SMS, PERMISSION_GRANT_STATE_GRANTED);

            assertWithMessage("setPermissionGrantState did not return true")
                    .that(wasSet).isTrue();
            assertWithMessage("Permission grant state should be set to granted but was not")
                    .that(sDeviceState.dpc().devicePolicyManager().getPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            READ_SMS))
                    .isEqualTo(PERMISSION_GRANT_STATE_GRANTED);
        } finally {
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    READ_SMS, existingGrantState);
        }
    }

    @Test
    @CanSetPolicyTest(policy = SetSensorPermissionGranted.class)
    @Ignore("TODO(198280344): Re-enable when we can set sensor permissions using device owner")
    public void grantSensorPermission_setsGrantState() {
        // TODO(b/188893663): Replace with parameterization
        for (String permission : SENSOR_PERMISSIONS) {
            int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                    .getPermissionGrantState(sDeviceState.dpc().componentName(),
                            sTestApp.packageName(), permission);
            try {
                boolean wasSet =
                        sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                                sDeviceState.dpc().componentName(), sTestApp.packageName(),
                                permission, PERMISSION_GRANT_STATE_GRANTED);

                assertWithMessage("setPermissionGrantState did not return true")
                        .that(wasSet).isTrue();
                assertWithMessage("Permission grant state should be set to granted but was not")
                        .that(sDeviceState.dpc().devicePolicyManager().getPermissionGrantState(
                                sDeviceState.dpc().componentName(), sTestApp.packageName(),
                                permission))
                        .isEqualTo(PERMISSION_GRANT_STATE_GRANTED);
            } finally {
                sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        permission, existingGrantState);
            }
        }
    }

    @Test
    @PositivePolicyTest(policy = SetSmsPermissionGranted.class)
    public void grantSmsPermission_permissionIsGranted() {
        // TODO(b/188893663): Replace with parameterization
        int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                .getPermissionGrantState(sDeviceState.dpc().componentName(),
                        sTestApp.packageName(), READ_SMS);
        try {
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    READ_SMS, PERMISSION_GRANT_STATE_GRANTED);

            assertWithMessage("Permission should be granted but was not").that(
                    sTestApp.pkg().hasPermission(READ_SMS)).isTrue();
        } finally {
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    READ_SMS, existingGrantState);
        }
    }

    @Test
    @PositivePolicyTest(policy = SetSensorPermissionGranted.class)
    @Ignore("TODO(198280344): Re-enable when we can set sensor permissions using device owner")
    public void grantSensorPermission_permissionIsGranted() {
        // TODO(b/188893663): Replace with parameterization
        for (String permission : SENSOR_PERMISSIONS) {
            int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                    .getPermissionGrantState(sDeviceState.dpc().componentName(),
                            sTestApp.packageName(), permission);
            try {
                sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        permission, PERMISSION_GRANT_STATE_GRANTED);

                assertWithMessage("Permission should be granted but was not").that(
                        sTestApp.pkg().hasPermission(permission)).isTrue();
            } finally {
                sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        permission, existingGrantState);
            }
        }
    }

    @Test
    @NegativePolicyTest(policy = SetSmsPermissionGranted.class)
    public void grantSmsPermission_doesNotApplyToUser_permissionIsNotGranted() {
        int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                .getPermissionGrantState(sDeviceState.dpc().componentName(),
                        sTestApp.packageName(), READ_SMS);
        try {
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    READ_SMS, PERMISSION_GRANT_STATE_GRANTED);

            assertWithMessage("Permission should not be granted but was").that(
                    sTestApp.pkg().hasPermission(READ_SMS)).isFalse();
        } finally {
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    READ_SMS, existingGrantState);
        }
    }

    @Test
    @NegativePolicyTest(policy = SetSensorPermissionGranted.class)
    @Ignore("TODO(198280344): Re-enable when we can set sensor permissions using device owner")
    public void grantSensorPermission_doesNotApplyToUser_permissionIsNotGranted() {
        // TODO(b/188893663): Replace with parameterization
        for (String permission : SENSOR_PERMISSIONS) {
            int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                    .getPermissionGrantState(sDeviceState.dpc().componentName(),
                            sTestApp.packageName(), permission);
            try {
                sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        permission, PERMISSION_GRANT_STATE_GRANTED);

                assertWithMessage("Permission should not be granted but was").that(
                        sTestApp.pkg().hasPermission(permission)).isFalse();
            } finally {
                sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        permission, existingGrantState);
            }
        }
    }

    @Test
    @CannotSetPolicyTest(policy = SetSmsPermissionGranted.class, includeNonDeviceAdminStates = false)
    public void grantSmsPermission_cannotBeApplied_returnsTrueButDoesNotSetGrantState() {
        int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                .getPermissionGrantState(sDeviceState.dpc().componentName(),
                        sTestApp.packageName(), READ_SMS);
        try {
            boolean wasSet =
                    sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            READ_SMS, PERMISSION_GRANT_STATE_GRANTED);

            assertWithMessage("setPermissionGrantState did not return true")
                    .that(wasSet).isTrue();
            assertWithMessage("Permission grant state should not be set to granted but was")
                    .that(sDeviceState.dpc().devicePolicyManager().getPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            READ_SMS))
                    .isNotEqualTo(PERMISSION_GRANT_STATE_GRANTED);
        } finally {
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    READ_SMS, existingGrantState);
        }
    }

    @Test
    @CannotSetPolicyTest(policy = SetSmsPermissionGranted.class, includeDeviceAdminStates = false)
    public void grantSmsPermission_nonDeviceAdmin_throwsException() {
        assertThrows(SecurityException.class,
                () -> sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        READ_SMS, PERMISSION_GRANT_STATE_GRANTED));
    }

    @Test
    @CannotSetPolicyTest(policy = SetSensorPermissionGranted.class)
    public void grantSensorPermission_cannotBeApplied_returnsTrueButDoesNotSetGrantState() {
        // TODO(b/188893663): Replace with parameterization
        for (String permission : SENSOR_PERMISSIONS) {
            int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                    .getPermissionGrantState(sDeviceState.dpc().componentName(),
                            sTestApp.packageName(), permission);
            try {
                boolean wasSet =
                        sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                                sDeviceState.dpc().componentName(), sTestApp.packageName(),
                                permission, PERMISSION_GRANT_STATE_GRANTED);

                assertWithMessage("setPermissionGrantState did not return true")
                        .that(wasSet).isTrue();
                assertWithMessage("Permission grant state should not be set to granted but was")
                        .that(sDeviceState.dpc().devicePolicyManager().getPermissionGrantState(
                                sDeviceState.dpc().componentName(), sTestApp.packageName(),
                                permission))
                        .isNotEqualTo(PERMISSION_GRANT_STATE_GRANTED);
            } finally {
                sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        permission, existingGrantState);
            }
        }
    }

    @Test
    @PositivePolicyTest(policy = SetSensorPermissionGranted.class)
    @NotificationsTest
    @Ignore("TODO(198280344): Re-enable when we can set sensor permissions using device owner")
    public void grantLocationPermission_userNotified() throws Exception {
        // TODO(b/188893663): Replace with parameterization
        for (String permission : LOCATION_PERMISSIONS) {
            int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                    .getPermissionGrantState(sDeviceState.dpc().componentName(),
                            sTestApp.packageName(), permission);
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    permission, PERMISSION_GRANT_STATE_DEFAULT);
            try (NotificationListener notifications = TestApis.notifications().createListener()) {
                sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        permission, PERMISSION_GRANT_STATE_GRANTED);

                assertThat(notifications.query()
                        .wherePackageName().isEqualTo(PERMISSION_CONTROLLER_PACKAGE_NAME)
                        .whereNotification().channelId().isEqualTo(
                                AUTO_GRANTED_PERMISSIONS_CHANNEL_ID)
                ).wasPosted();
            } finally {
                sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        permission, existingGrantState);
            }
        }
    }

    @Test
    @CanSetPolicyTest(policy = SetPermissionGrantState.class)
    public void setPermissionGrantState_permissionIsNotDeclared_doesNotSetGrantState() {
        boolean wasSet = sDeviceState.dpc().devicePolicyManager()
                .setPermissionGrantState(sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        NOT_DECLARED_PERMISSION, PERMISSION_GRANT_STATE_DENIED);

        assertWithMessage("setPermissionGrantState did not return false")
                .that(wasSet).isFalse();
        assertWithMessage("Permission grant state should not be changed but was")
                .that(sDeviceState.dpc().devicePolicyManager().getPermissionGrantState(
                        sDeviceState.dpc().componentName(), sTestApp.packageName(),
                        NOT_DECLARED_PERMISSION))
                .isEqualTo(PERMISSION_GRANT_STATE_DEFAULT);
    }

    @Test
    @CanSetPolicyTest(policy = SetPermissionGrantState.class)
    public void setPermissionGrantState_appIsNotInstalled_doesNotSetGrantState() {
        boolean wasSet = sDeviceState.dpc().devicePolicyManager()
                .setPermissionGrantState(
                        sDeviceState.dpc().componentName(), NON_EXISTING_PACKAGE_NAME,
                        GRANTABLE_PERMISSION, PERMISSION_GRANT_STATE_DENIED);

        assertWithMessage("setPermissionGrantState did not return false")
                .that(wasSet).isFalse();
        assertWithMessage("Permission grant state should not be changed but was")
                .that(sDeviceState.dpc().devicePolicyManager().getPermissionGrantState(
                        sDeviceState.dpc().componentName(), NON_EXISTING_PACKAGE_NAME,
                        NOT_DECLARED_PERMISSION))
                .isEqualTo(PERMISSION_GRANT_STATE_DEFAULT);
    }

    @Test
    @CanSetPolicyTest(policy = SetPermissionGrantState.class)
    public void setPermissionGrantStateDefault_wasPreviouslyGranted_permissionStaysGranted() {
        int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                .getPermissionGrantState(sDeviceState.dpc().componentName(),
                        sTestApp.packageName(), GRANTABLE_PERMISSION);

        try {
            sDeviceState.dpc().devicePolicyManager()
                    .setPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            GRANTABLE_PERMISSION, PERMISSION_GRANT_STATE_GRANTED);
            boolean wasSet = sDeviceState.dpc().devicePolicyManager()
                    .setPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            GRANTABLE_PERMISSION, PERMISSION_GRANT_STATE_DEFAULT);

            assertWithMessage("setPermissionGrantState did not return true")
                    .that(wasSet).isTrue();
            assertWithMessage("Permission grant state should be set to default but was not")
                    .that(sDeviceState.dpc().devicePolicyManager().getPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            GRANTABLE_PERMISSION))
                    .isEqualTo(PERMISSION_GRANT_STATE_DEFAULT);
            assertWithMessage("Permission should be granted but was not").that(
                    sTestApp.pkg().hasPermission(GRANTABLE_PERMISSION)).isTrue();
        } finally {
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    GRANTABLE_PERMISSION, existingGrantState);
        }
    }

    @Test
    @CanSetPolicyTest(policy = SetPermissionGrantState.class)
    public void setPermissionGrantStateDefault_wasPreviouslyDenied_permissionStaysDenied() {
        int existingGrantState = sDeviceState.dpc().devicePolicyManager()
                .getPermissionGrantState(sDeviceState.dpc().componentName(),
                        sTestApp.packageName(), GRANTABLE_PERMISSION);

        try {
            sDeviceState.dpc().devicePolicyManager()
                    .setPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            GRANTABLE_PERMISSION, PERMISSION_GRANT_STATE_DENIED);
            boolean wasSet = sDeviceState.dpc().devicePolicyManager()
                    .setPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            GRANTABLE_PERMISSION, PERMISSION_GRANT_STATE_DEFAULT);

            assertWithMessage("setPermissionGrantState did not return true")
                    .that(wasSet).isTrue();
            assertWithMessage("Permission grant state should be set to default but was not")
                    .that(sDeviceState.dpc().devicePolicyManager().getPermissionGrantState(
                            sDeviceState.dpc().componentName(), sTestApp.packageName(),
                            GRANTABLE_PERMISSION))
                    .isEqualTo(PERMISSION_GRANT_STATE_DEFAULT);
            assertWithMessage("Permission should be denied but was not").that(
                    sTestApp.pkg().hasPermission(GRANTABLE_PERMISSION)).isFalse();
        } finally {
            sDeviceState.dpc().devicePolicyManager().setPermissionGrantState(
                    sDeviceState.dpc().componentName(), sTestApp.packageName(),
                    GRANTABLE_PERMISSION, existingGrantState);
        }
    }
}
