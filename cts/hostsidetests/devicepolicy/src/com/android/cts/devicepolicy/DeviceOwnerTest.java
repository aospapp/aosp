/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.cts.devicepolicy;

import static com.android.cts.devicepolicy.metrics.DevicePolicyEventLogVerifier.assertMetricsLogged;

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.cts.devicepolicy.metrics.DevicePolicyEventWrapper;
import com.android.tradefed.device.DeviceNotAvailableException;

import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import android.stats.devicepolicy.EventId;

/**
 * Set of tests for Device Owner use cases.
 */
public class DeviceOwnerTest extends BaseDevicePolicyTest {

    private static final String DEVICE_OWNER_PKG = "com.android.cts.deviceowner";
    private static final String DEVICE_OWNER_APK = "CtsDeviceOwnerApp.apk";

    private static final String MANAGED_PROFILE_PKG = "com.android.cts.managedprofile";
    private static final String MANAGED_PROFILE_APK = "CtsManagedProfileApp.apk";
    private static final String MANAGED_PROFILE_ADMIN =
            MANAGED_PROFILE_PKG + ".BaseManagedProfileTest$BasicAdminReceiver";

    private static final String FEATURE_BACKUP = "android.software.backup";

    private static final String INTENT_RECEIVER_PKG = "com.android.cts.intent.receiver";
    private static final String INTENT_RECEIVER_APK = "CtsIntentReceiverApp.apk";

    private static final String WIFI_CONFIG_CREATOR_PKG =
            "com.android.cts.deviceowner.wificonfigcreator";
    private static final String WIFI_CONFIG_CREATOR_APK = "CtsWifiConfigCreator.apk";

    private static final String ADMIN_RECEIVER_TEST_CLASS =
            DEVICE_OWNER_PKG + ".BasicAdminReceiver";
    private static final String DEVICE_OWNER_COMPONENT = DEVICE_OWNER_PKG + "/"
            + ADMIN_RECEIVER_TEST_CLASS;

    private static final String TEST_APP_APK = "CtsEmptyTestApp.apk";
    private static final String TEST_APP_PKG = "android.packageinstaller.emptytestapp.cts";
    private static final String TEST_APP_LOCATION = "/data/local/tmp/cts/packageinstaller/";

    private static final String ARG_SECURITY_LOGGING_BATCH_NUMBER = "batchNumber";
    private static final int SECURITY_EVENTS_BATCH_SIZE = 100;

    private static final String ARG_NETWORK_LOGGING_BATCH_COUNT = "batchCount";
    private static final String TEST_UPDATE_LOCATION = "/data/local/tmp/cts/deviceowner";

    private static final String LAUNCHER_TESTS_NO_LAUNCHABLE_ACTIVITY_APK =
            "CtsNoLaunchableActivityApp.apk";

    /**
     * Copied from {@link android.app.admin.DevicePolicyManager
     * .InstallSystemUpdateCallback#UPDATE_ERROR_UPDATE_FILE_INVALID}
     */
    private static final int UPDATE_ERROR_UPDATE_FILE_INVALID = 3;

    /**
     * Copied from {@link android.app.admin.DevicePolicyManager
     * .InstallSystemUpdateCallback#UPDATE_ERROR_UNKNOWN}
     */
    private static final int UPDATE_ERROR_UNKNOWN = 1;

    private static final int TYPE_NONE = 0;

    /**
     * Copied from {@link android.app.admin.SystemUpdatePolicy}
     */
    private static final int TYPE_INSTALL_AUTOMATIC = 1;
    private static final int TYPE_INSTALL_WINDOWED = 2;
    private static final int TYPE_POSTPONE = 3;

    /**
     * Copied from {@link android.provider.Settings}
     */
    private static final String SETTINGS_SECURE = "secure";
    private static final String LOCATION_MODE = "location_mode";
    private static final String LOCATION_MODE_HIGH_ACCURACY = "3";

    /** CreateAndManageUser is available and an additional user can be created. */
    private boolean mHasCreateAndManageUserFeature;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (mHasFeature) {
            installAppAsUser(DEVICE_OWNER_APK, mPrimaryUserId);
            if (!setDeviceOwner(DEVICE_OWNER_COMPONENT, mPrimaryUserId,
                    /*expectFailure*/ false)) {
                removeAdmin(DEVICE_OWNER_COMPONENT, mPrimaryUserId);
                getDevice().uninstallPackage(DEVICE_OWNER_PKG);
                fail("Failed to set device owner");
            }

            getDevice().executeShellCommand(" mkdir " + TEST_UPDATE_LOCATION);
        }
        mHasCreateAndManageUserFeature = mHasFeature && canCreateAdditionalUsers(1)
                && hasDeviceFeature("android.software.managed_users");
    }

    @Override
    protected void tearDown() throws Exception {
        if (mHasFeature) {
            assertTrue("Failed to remove device owner.",
                    removeAdmin(DEVICE_OWNER_COMPONENT, mPrimaryUserId));
            getDevice().uninstallPackage(DEVICE_OWNER_PKG);
            switchUser(USER_SYSTEM);
            removeTestUsers();
            getDevice().executeShellCommand(" rm -r " + TEST_UPDATE_LOCATION);
        }

        super.tearDown();
    }

    public void testDeviceOwnerSetup() throws Exception {
        executeDeviceOwnerTest("DeviceOwnerSetupTest");
    }

    public void testLockScreenInfo() throws Exception {
        if (!mHasFeature) {
            return;
        }
        executeDeviceOwnerTest("LockScreenInfoTest");
        assertMetricsLogged(getDevice(), () -> {
            executeDeviceTestMethod(".LockScreenInfoTest", "testSetAndGetLockInfo");
        }, new DevicePolicyEventWrapper.Builder(EventId.SET_DEVICE_OWNER_LOCK_SCREEN_INFO_VALUE)
                .setAdminPackageName(DEVICE_OWNER_PKG)
                .build());
    }

    public void testWifi() throws Exception {
        if (!mHasFeature || !hasDeviceFeature("android.hardware.wifi")) {
            return;
        }
        executeDeviceOwnerTest("WifiTest");
        assertMetricsLogged(getDevice(), () -> {
            executeDeviceTestMethod(".WifiTest", "testGetWifiMacAddress");
        }, new DevicePolicyEventWrapper.Builder(EventId.GET_WIFI_MAC_ADDRESS_VALUE)
                .setAdminPackageName(DEVICE_OWNER_PKG)
                .build());
    }

    public void testRemoteBugreportWithTwoUsers() throws Exception {
        if (!mHasFeature || !canCreateAdditionalUsers(1)) {
            return;
        }
        final int userId = createUser();
        try {
            executeDeviceTestMethod(".RemoteBugreportTest",
                    "testRequestBugreportThrowsSecurityException");
        } finally {
            removeUser(userId);
        }
    }

    public void testCreateAndManageUser_LowStorage() throws Exception {
        if (!mHasCreateAndManageUserFeature) {
            return;
        }

        try {
            // Force low storage
            getDevice().setSetting("global", "sys_storage_threshold_percentage", "100");
            getDevice().setSetting("global", "sys_storage_threshold_max_bytes",
                    String.valueOf(Long.MAX_VALUE));

            // The next createAndManageUser should return USER_OPERATION_ERROR_LOW_STORAGE.
            executeDeviceTestMethod(".CreateAndManageUserTest",
                    "testCreateAndManageUser_LowStorage");
        } finally {
            getDevice().executeShellCommand(
                    "settings delete global sys_storage_threshold_percentage");
            getDevice().executeShellCommand(
                    "settings delete global sys_storage_threshold_max_bytes");
        }
    }

    public void testCreateAndManageUser_MaxUsers() throws Exception {
        if (!mHasCreateAndManageUserFeature) {
            return;
        }

        int maxUsers = getDevice().getMaxNumberOfUsersSupported();
        // Primary user is already there, so we can create up to maxUsers -1.
        for (int i = 0; i < maxUsers - 1; i++) {
            executeDeviceTestMethod(".CreateAndManageUserTest",
                    "testCreateAndManageUser");
        }
        // The next createAndManageUser should return USER_OPERATION_ERROR_MAX_USERS.
        executeDeviceTestMethod(".CreateAndManageUserTest",
                "testCreateAndManageUser_MaxUsers");
    }

    /**
     * Test creating an user using the DevicePolicyManager's createAndManageUser.
     * {@link android.app.admin.DevicePolicyManager#getSecondaryUsers} is tested.
     */
    public void testCreateAndManageUser_GetSecondaryUsers() throws Exception {
        if (!mHasCreateAndManageUserFeature) {
            return;
        }

        executeDeviceTestMethod(".CreateAndManageUserTest",
                "testCreateAndManageUser_GetSecondaryUsers");
    }

    /**
     * Test creating an user using the DevicePolicyManager's createAndManageUser method and switch
     * to the user.
     * {@link android.app.admin.DevicePolicyManager#switchUser} is tested.
     */
    public void testCreateAndManageUser_SwitchUser() throws Exception {
        if (!mHasCreateAndManageUserFeature || !canStartAdditionalUsers(1)) {
            return;
        }

        executeDeviceTestMethod(".CreateAndManageUserTest",
                "testCreateAndManageUser_SwitchUser");
    }

    /**
     * Test creating an user using the DevicePolicyManager's createAndManageUser method and switch
     * to the user to test stop user while target user is in foreground.
     * {@link android.app.admin.DevicePolicyManager#stopUser} is tested.
     */
    public void testCreateAndManageUser_CannotStopCurrentUser() throws Exception {
        if (!mHasCreateAndManageUserFeature || !canStartAdditionalUsers(1)) {
            return;
        }

        executeDeviceTestMethod(".CreateAndManageUserTest",
                "testCreateAndManageUser_CannotStopCurrentUser");
    }

    /**
     * Test creating an user using the DevicePolicyManager's createAndManageUser method and start
     * the user in background to test APIs on that user.
     * {@link android.app.admin.DevicePolicyManager#startUserInBackground} is tested.
     */
    public void testCreateAndManageUser_StartInBackground() throws Exception {
        if (!mHasCreateAndManageUserFeature || !canStartAdditionalUsers(1)) {
            return;
        }

        executeDeviceTestMethod(".CreateAndManageUserTest",
                "testCreateAndManageUser_StartInBackground");
    }

    /**
     * Test creating an user using the DevicePolicyManager's createAndManageUser method and start
     * the user in background to test APIs on that user.
     * {@link android.app.admin.DevicePolicyManager#startUserInBackground} is tested.
     */
    public void testCreateAndManageUser_StartInBackground_MaxRunningUsers() throws Exception {
        if (!mHasCreateAndManageUserFeature) {
            return;
        }

        int maxRunningUsers = getDevice().getMaxNumberOfRunningUsersSupported();
        // Primary user is already running, so we can start up to maxRunningUsers -1.
        for (int i = 0; i < maxRunningUsers - 1; i++) {
            executeDeviceTestMethod(".CreateAndManageUserTest",
                    "testCreateAndManageUser_StartInBackground");
        }
        // The next startUserInBackground should return USER_OPERATION_ERROR_MAX_RUNNING_USERS.
        executeDeviceTestMethod(".CreateAndManageUserTest",
                "testCreateAndManageUser_StartInBackground_MaxRunningUsers");
    }

    /**
     * Test creating an user using the DevicePolicyManager's createAndManageUser method and start
     * the user in background to test APIs on that user.
     * {@link android.app.admin.DevicePolicyManager#stopUser} is tested.
     */
    public void testCreateAndManageUser_StopUser() throws Exception {
        if (!mHasCreateAndManageUserFeature || !canStartAdditionalUsers(1)) {
            return;
        }

        executeDeviceTestMethod(".CreateAndManageUserTest",
                "testCreateAndManageUser_StopUser");
        assertNewUserStopped();
    }

    /**
     * Test creating an ephemeral user using the DevicePolicyManager's createAndManageUser method
     * and start the user in background, user is then stopped. The user should be removed
     * automatically even when DISALLOW_REMOVE_USER is set.
     */
    public void testCreateAndManageUser_StopEphemeralUser_DisallowRemoveUser() throws Exception {
        if (!mHasCreateAndManageUserFeature || !canStartAdditionalUsers(1)) {
            return;
        }

        executeDeviceTestMethod(".CreateAndManageUserTest",
                "testCreateAndManageUser_StopEphemeralUser_DisallowRemoveUser");
        assertEquals(0, getUsersCreatedByTests().size());
    }

    /**
     * Test creating an user using the DevicePolicyManager's createAndManageUser method, affiliate
     * the user and start the user in background to test APIs on that user.
     * {@link android.app.admin.DevicePolicyManager#logoutUser} is tested.
     */
    public void testCreateAndManageUser_LogoutUser() throws Exception {
        if (!mHasCreateAndManageUserFeature || !canStartAdditionalUsers(1)) {
            return;
        }

        executeDeviceTestMethod(".CreateAndManageUserTest",
                "testCreateAndManageUser_LogoutUser");
        assertNewUserStopped();
    }

    /**
     * Test creating an user using the DevicePolicyManager's createAndManageUser method, affiliate
     * the user and start the user in background to test APIs on that user.
     * {@link android.app.admin.DevicePolicyManager#isAffiliatedUser} is tested.
     */
    public void testCreateAndManageUser_Affiliated() throws Exception {
        if (!mHasCreateAndManageUserFeature || !canStartAdditionalUsers(1)) {
            return;
        }

        executeDeviceTestMethod(".CreateAndManageUserTest",
                "testCreateAndManageUser_Affiliated");
    }

    /**
     * Test creating an ephemeral user using the DevicePolicyManager's createAndManageUser method,
     * affiliate the user and start the user in background to test APIs on that user.
     * {@link android.app.admin.DevicePolicyManager#isEphemeralUser} is tested.
     */
    public void testCreateAndManageUser_Ephemeral() throws Exception {
        if (!mHasCreateAndManageUserFeature || !canStartAdditionalUsers(1)) {
            return;
        }

        executeDeviceTestMethod(".CreateAndManageUserTest",
                "testCreateAndManageUser_Ephemeral");

        List<Integer> newUsers = getUsersCreatedByTests();
        assertEquals(1, newUsers.size());
        int newUserId = newUsers.get(0);

        // Get the flags of the new user and check the user is ephemeral.
        int flags = getUserFlags(newUserId);
        assertEquals("Ephemeral flag must be set", FLAG_EPHEMERAL, flags & FLAG_EPHEMERAL);
    }

    /**
     * Test creating an user using the DevicePolicyManager's createAndManageUser method, affiliate
     * the user and start the user in background to test APIs on that user.
     * {@link android.app.admin.DevicePolicyManager#LEAVE_ALL_SYSTEM_APPS_ENABLED} is tested.
     */
    public void testCreateAndManageUser_LeaveAllSystemApps() throws Exception {
        if (!mHasCreateAndManageUserFeature || !canStartAdditionalUsers(1)) {
            return;
        }

        executeDeviceTestMethod(".CreateAndManageUserTest",
                "testCreateAndManageUser_LeaveAllSystemApps");
    }

    public void testCreateAndManageUser_SkipSetupWizard() throws Exception {
        if (mHasCreateAndManageUserFeature) {
            executeDeviceTestMethod(".CreateAndManageUserTest",
                    "testCreateAndManageUser_SkipSetupWizard");
       }
    }

    public void testCreateAndManageUser_AddRestrictionSet() throws Exception {
        if (mHasCreateAndManageUserFeature) {
            executeDeviceTestMethod(".CreateAndManageUserTest",
                    "testCreateAndManageUser_AddRestrictionSet");
        }
    }

    public void testCreateAndManageUser_RemoveRestrictionSet() throws Exception {
        if (mHasCreateAndManageUserFeature) {
            executeDeviceTestMethod(".CreateAndManageUserTest",
                    "testCreateAndManageUser_RemoveRestrictionSet");
        }
    }

    public void testUserAddedOrRemovedBroadcasts() throws Exception {
        if (mHasCreateAndManageUserFeature) {
            executeDeviceTestMethod(".CreateAndManageUserTest",
                    "testUserAddedOrRemovedBroadcasts");
        }
    }

    public void testUserSession() throws Exception {
        if (!mHasFeature) {
            return;
        }
        executeDeviceOwnerTest("UserSessionTest");
    }

    public void testSecurityLoggingWithTwoUsers() throws Exception {
        if (!mHasFeature || !canCreateAdditionalUsers(1)) {
            return;
        }

        final int userId = createUser();
        try {
            // The feature can be enabled, but in a "paused" state. Attempting to retrieve logs
            // should throw security exception.
            executeDeviceTestMethod(".SecurityLoggingTest", "testEnablingSecurityLogging");
            executeDeviceTestMethod(".SecurityLoggingTest",
                    "testRetrievingSecurityLogsThrowsSecurityException");
            executeDeviceTestMethod(".SecurityLoggingTest",
                    "testRetrievingPreviousSecurityLogsThrowsSecurityException");
        } finally {
            removeUser(userId);
            executeDeviceTestMethod(".SecurityLoggingTest", "testDisablingSecurityLogging");
        }
    }

    public void testSecurityLoggingWithSingleUser() throws Exception {
        if (!mHasFeature) {
            return;
        }
        // Backup stay awake setting because testGenerateLogs() will turn it off.
        final String stayAwake = getDevice().getSetting("global", "stay_on_while_plugged_in");
        try {
            // Turn logging on.
            executeDeviceTestMethod(".SecurityLoggingTest", "testEnablingSecurityLogging");
            // Reboot to ensure ro.device_owner is set to true in logd and logging is on.
            rebootAndWaitUntilReady();

            // Generate various types of events on device side and check that they are logged.
            executeDeviceTestMethod(".SecurityLoggingTest", "testGenerateLogs");
            getDevice().executeShellCommand("dpm force-security-logs");
            executeDeviceTestMethod(".SecurityLoggingTest", "testVerifyGeneratedLogs");

            // Reboot the device, so the security event ids are reset.
            rebootAndWaitUntilReady();

            // Verify event ids are consistent across a consecutive batch.
            for (int batchNumber = 0; batchNumber < 3; batchNumber++) {
                generateDummySecurityLogs();
                getDevice().executeShellCommand("dpm force-security-logs");
                executeDeviceTestMethod(".SecurityLoggingTest", "testVerifyLogIds",
                        Collections.singletonMap(ARG_SECURITY_LOGGING_BATCH_NUMBER,
                                Integer.toString(batchNumber)));
            }

            // Immediately attempting to fetch events again should fail.
            executeDeviceTestMethod(".SecurityLoggingTest",
                    "testSecurityLoggingRetrievalRateLimited");
            // Turn logging off.
            executeDeviceTestMethod(".SecurityLoggingTest", "testDisablingSecurityLogging");
        } finally {
            // Restore stay awake setting.
            if (stayAwake != null) {
                getDevice().setSetting("global", "stay_on_while_plugged_in", stayAwake);
            }
        }
    }

    public void testSecurityLoggingEnabledLogged() throws Exception {
        if (!mHasFeature) {
            return;
        }
        assertMetricsLogged(getDevice(), () -> {
            executeDeviceTestMethod(".SecurityLoggingTest", "testEnablingSecurityLogging");
            executeDeviceTestMethod(".SecurityLoggingTest", "testDisablingSecurityLogging");
        }, new DevicePolicyEventWrapper.Builder(EventId.SET_SECURITY_LOGGING_ENABLED_VALUE)
                .setAdminPackageName(DEVICE_OWNER_PKG)
                .setBoolean(true)
                .build(),
            new DevicePolicyEventWrapper.Builder(EventId.SET_SECURITY_LOGGING_ENABLED_VALUE)
                    .setAdminPackageName(DEVICE_OWNER_PKG)
                    .setBoolean(false)
                    .build());

    }

    private void generateDummySecurityLogs() throws DeviceNotAvailableException {
        // Trigger security events of type TAG_ADB_SHELL_CMD.
        for (int i = 0; i < SECURITY_EVENTS_BATCH_SIZE; i++) {
            getDevice().executeShellCommand("echo just_testing_" + i);
        }
    }

    public void testNetworkLoggingWithTwoUsers() throws Exception {
        if (!mHasFeature || !canCreateAdditionalUsers(1)) {
            return;
        }

        final int userId = createUser();
        try {
            // The feature can be enabled, but in a "paused" state. Attempting to retrieve logs
            // should throw security exception.
            executeDeviceTestMethod(".NetworkLoggingTest",
                    "testRetrievingNetworkLogsThrowsSecurityException");
        } finally {
            removeUser(userId);
        }
    }

    public void testNetworkLoggingWithSingleUser() throws Exception {
        if (!mHasFeature) {
            return;
        }
        executeDeviceTestMethod(".NetworkLoggingTest", "testProvidingWrongBatchTokenReturnsNull");
        executeDeviceTestMethod(".NetworkLoggingTest", "testNetworkLoggingAndRetrieval",
                Collections.singletonMap(ARG_NETWORK_LOGGING_BATCH_COUNT, Integer.toString(1)));
    }

    public void testNetworkLogging_multipleBatches() throws Exception {
        if (!mHasFeature) {
            return;
        }
        executeDeviceTestMethod(".NetworkLoggingTest", "testNetworkLoggingAndRetrieval",
                Collections.singletonMap(ARG_NETWORK_LOGGING_BATCH_COUNT, Integer.toString(2)));
    }

    public void testNetworkLogging_rebootResetsId() throws Exception {
        if (!mHasFeature) {
            return;
        }
        // First batch: retrieve and verify the events.
        executeDeviceTestMethod(".NetworkLoggingTest", "testNetworkLoggingAndRetrieval",
                Collections.singletonMap(ARG_NETWORK_LOGGING_BATCH_COUNT, Integer.toString(1)));
        // Reboot the device, so the security event IDs are re-set.
        rebootAndWaitUntilReady();
        // Make sure BOOT_COMPLETED is completed before proceeding.
        waitForBroadcastIdle();
        // First batch after reboot: retrieve and verify the events.
        executeDeviceTestMethod(".NetworkLoggingTest", "testNetworkLoggingAndRetrieval",
                Collections.singletonMap(ARG_NETWORK_LOGGING_BATCH_COUNT, Integer.toString(1)));
    }


    public void testSetAffiliationId_IllegalArgumentException() throws Exception {
        if (!mHasFeature) {
            return;
        }
        executeDeviceTestMethod(".AffiliationTest", "testSetAffiliationId_null");
        executeDeviceTestMethod(".AffiliationTest", "testSetAffiliationId_containsEmptyString");
    }

    public void testLockTask_deviceOwnerUser() throws Exception {
        if (!mHasFeature) {
            return;
        }
        try {
            installAppAsUser(INTENT_RECEIVER_APK, mPrimaryUserId);
            executeDeviceOwnerTest("LockTaskTest");
            assertMetricsLogged(getDevice(), () -> {
                runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".LockTaskTest", "testStartLockTask",
                        mPrimaryUserId);
            }, new DevicePolicyEventWrapper.Builder(EventId.SET_LOCKTASK_MODE_ENABLED_VALUE)
                    .setAdminPackageName(DEVICE_OWNER_PKG)
                    .setBoolean(true)
                    .setStrings(DEVICE_OWNER_PKG)
                    .build());
        } catch (AssertionError ex) {
            // STOPSHIP(b/32771855), remove this once we fixed the bug.
            executeShellCommand("dumpsys activity activities");
            executeShellCommand("dumpsys window -a");
            executeShellCommand("dumpsys activity service com.android.systemui");
            throw ex;
        } finally {
            getDevice().uninstallPackage(INTENT_RECEIVER_PKG);
        }
    }

    public void testLockTaskAfterReboot_deviceOwnerUser() throws Exception {
        if (!mHasFeature) {
            return;
        }

        try {
            // Just start kiosk mode
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".LockTaskHostDrivenTest", "startLockTask",
                    mPrimaryUserId);

            // Reboot while in kiosk mode and then unlock the device
            rebootAndWaitUntilReady();

            // Check that kiosk mode is working and can't be interrupted
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".LockTaskHostDrivenTest",
                    "testLockTaskIsActiveAndCantBeInterrupted", mPrimaryUserId);
        } finally {
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".LockTaskHostDrivenTest",
                    "clearDefaultHomeIntentReceiver", mPrimaryUserId);
        }
    }

    public void testLockTaskAfterReboot_tryOpeningSettings_deviceOwnerUser() throws Exception {
        if (!mHasFeature) {
            return;
        }

        try {
            // Just start kiosk mode
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".LockTaskHostDrivenTest", "startLockTask",
                    mPrimaryUserId);

            // Reboot while in kiosk mode and then unlock the device
            rebootAndWaitUntilReady();

            // Try to open settings via adb
            executeShellCommand("am start -a android.settings.SETTINGS");

            // Check again
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".LockTaskHostDrivenTest",
                    "testLockTaskIsActiveAndCantBeInterrupted", mPrimaryUserId);
        } finally {
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".LockTaskHostDrivenTest",
                    "clearDefaultHomeIntentReceiver", mPrimaryUserId);
        }
    }

    public void testLockTask_unaffiliatedUser() throws Exception {
        if (!mHasFeature || !canCreateAdditionalUsers(1)) {
            return;
        }

        final int userId = createUser();
        installAppAsUser(DEVICE_OWNER_APK, userId);
        setProfileOwnerOrFail(DEVICE_OWNER_COMPONENT, userId);

        runDeviceTestsAsUser(
                DEVICE_OWNER_PKG,
                ".AffiliationTest",
                "testLockTaskMethodsThrowExceptionIfUnaffiliated",
                userId);

        runDeviceTestsAsUser(
                DEVICE_OWNER_PKG, ".AffiliationTest", "testSetAffiliationId1", mPrimaryUserId);
        runDeviceTestsAsUser(
                DEVICE_OWNER_PKG, ".AffiliationTest", "testSetAffiliationId1", userId);
        runDeviceTestsAsUser(
                DEVICE_OWNER_PKG,
                ".AffiliationTest",
                "testSetLockTaskPackagesClearedIfUserBecomesUnaffiliated",
                userId);
    }

    public void testLockTask_affiliatedSecondaryUser() throws Exception {
        if (!mHasFeature || !canCreateAdditionalUsers(1)) {
            return;
        }
        final int userId = createAffiliatedSecondaryUser();
        executeAffiliatedProfileOwnerTest("LockTaskTest", userId);
    }

    public void testSystemUpdatePolicy() throws Exception {
        if (!mHasFeature) {
            return;
        }
        executeDeviceOwnerTest("SystemUpdatePolicyTest");
    }

    public void testSetSystemUpdatePolicyLogged() throws Exception {
        if (!mHasFeature) {
            return;
        }
        assertMetricsLogged(getDevice(), () -> {
            executeDeviceTestMethod(".SystemUpdatePolicyTest", "testSetAutomaticInstallPolicy");
        }, new DevicePolicyEventWrapper.Builder(EventId.SET_SYSTEM_UPDATE_POLICY_VALUE)
                    .setAdminPackageName(DEVICE_OWNER_PKG)
                    .setInt(TYPE_INSTALL_AUTOMATIC)
                    .build());
        assertMetricsLogged(getDevice(), () -> {
            executeDeviceTestMethod(".SystemUpdatePolicyTest", "testSetWindowedInstallPolicy");
        }, new DevicePolicyEventWrapper.Builder(EventId.SET_SYSTEM_UPDATE_POLICY_VALUE)
                    .setAdminPackageName(DEVICE_OWNER_PKG)
                    .setInt(TYPE_INSTALL_WINDOWED)
                    .build());
        assertMetricsLogged(getDevice(), () -> {
            executeDeviceTestMethod(".SystemUpdatePolicyTest", "testSetPostponeInstallPolicy");
        }, new DevicePolicyEventWrapper.Builder(EventId.SET_SYSTEM_UPDATE_POLICY_VALUE)
                    .setAdminPackageName(DEVICE_OWNER_PKG)
                    .setInt(TYPE_POSTPONE)
                    .build());
        assertMetricsLogged(getDevice(), () -> {
            executeDeviceTestMethod(".SystemUpdatePolicyTest", "testSetEmptytInstallPolicy");
        }, new DevicePolicyEventWrapper.Builder(EventId.SET_SYSTEM_UPDATE_POLICY_VALUE)
                    .setAdminPackageName(DEVICE_OWNER_PKG)
                    .setInt(TYPE_NONE)
                    .build());
    }

    public void testWifiConfigLockdown() throws Exception {
        final boolean hasWifi = hasDeviceFeature("android.hardware.wifi");
        if (hasWifi && mHasFeature) {
            String oldLocationSetting = getDevice().getSetting(SETTINGS_SECURE, LOCATION_MODE);
            try {
                installAppAsUser(WIFI_CONFIG_CREATOR_APK, mPrimaryUserId);
                getDevice().setSetting(SETTINGS_SECURE, LOCATION_MODE, LOCATION_MODE_HIGH_ACCURACY);
                executeDeviceOwnerTest("WifiConfigLockdownTest");
            } finally {
                getDevice().uninstallPackage(WIFI_CONFIG_CREATOR_PKG);
                getDevice().setSetting(SETTINGS_SECURE, LOCATION_MODE, oldLocationSetting);
            }
        }
    }

    /**
     * Execute WifiSetHttpProxyTest as device owner.
     */
    public void testWifiSetHttpProxyTest() throws Exception {
        final boolean hasWifi = hasDeviceFeature("android.hardware.wifi");
        if (hasWifi && mHasFeature) {
            executeDeviceOwnerTest("WifiSetHttpProxyTest");
        }
    }

    public void testCannotSetDeviceOwnerAgain() throws Exception {
        if (!mHasFeature) {
            return;
        }
        // verify that we can't set the same admin receiver as device owner again
        assertFalse(setDeviceOwner(
                DEVICE_OWNER_PKG + "/" + ADMIN_RECEIVER_TEST_CLASS, mPrimaryUserId,
                /*expectFailure*/ true));

        // verify that we can't set a different admin receiver as device owner
        try {
            installAppAsUser(MANAGED_PROFILE_APK, mPrimaryUserId);
            assertFalse(setDeviceOwner(
                    MANAGED_PROFILE_PKG + "/" + MANAGED_PROFILE_ADMIN, mPrimaryUserId,
                    /*expectFailure*/ true));
        } finally {
            // Remove the device owner in case the test fails.
            removeAdmin(MANAGED_PROFILE_PKG + "/" + MANAGED_PROFILE_ADMIN, mPrimaryUserId);
            getDevice().uninstallPackage(MANAGED_PROFILE_PKG);
        }
    }

    // Execute HardwarePropertiesManagerTest as a device owner.
    public void testHardwarePropertiesManagerAsDeviceOwner() throws Exception {
        if (!mHasFeature) {
            return;
        }

        executeDeviceTestMethod(".HardwarePropertiesManagerTest", "testHardwarePropertiesManager");
    }

    // Execute VrTemperatureTest as a device owner.
    public void testVrTemperaturesAsDeviceOwner() throws Exception {
        if (!mHasFeature) {
            return;
        }

        executeDeviceTestMethod(".VrTemperatureTest", "testVrTemperatures");
    }

    public void testIsManagedDeviceProvisioningAllowed() throws Exception {
        if (!mHasFeature) {
            return;
        }
        // This case runs when DO is provisioned
        // mHasFeature == true and provisioned, can't provision DO again.
        executeDeviceTestMethod(".PreDeviceOwnerTest", "testIsProvisioningAllowedFalse");
    }

    /**
     * Can provision Managed Profile when DO is set by default if they are the same admin.
     */
    public void testIsManagedProfileProvisioningAllowed_deviceOwnerIsSet() throws Exception {
        if (!mHasFeature) {
            return;
        }
        if (!hasDeviceFeature("android.software.managed_users")) {
            return;
        }
        executeDeviceTestMethod(".PreDeviceOwnerTest",
                "testIsProvisioningAllowedTrueForManagedProfileAction");
    }

    public void testAdminActionBookkeeping() throws Exception {
        if (!mHasFeature) {
            return;
        }
        executeDeviceOwnerTest("AdminActionBookkeepingTest");
        assertMetricsLogged(getDevice(), () -> {
            executeDeviceTestMethod(".AdminActionBookkeepingTest", "testRetrieveSecurityLogs");
        }, new DevicePolicyEventWrapper.Builder(EventId.RETRIEVE_SECURITY_LOGS_VALUE)
                    .setAdminPackageName(DEVICE_OWNER_PKG)
                    .build(),
            new DevicePolicyEventWrapper.Builder(EventId.RETRIEVE_PRE_REBOOT_SECURITY_LOGS_VALUE)
                    .setAdminPackageName(DEVICE_OWNER_PKG)
                    .build());

        // Requesting a bug report (in AdminActionBookkeepingTest#testRequestBugreport) leaves a
        // state where future bug report requests will fail
        // TODO(b/130210665): replace this with use of NotificationListenerService to dismiss the
        // bug report request
        rebootAndWaitUntilReady();

        assertMetricsLogged(getDevice(), () -> {
            executeDeviceTestMethod(".AdminActionBookkeepingTest", "testRequestBugreport");
        }, new DevicePolicyEventWrapper.Builder(EventId.REQUEST_BUGREPORT_VALUE)
                .setAdminPackageName(DEVICE_OWNER_PKG)
                .build());
        // Requesting a bug report (in AdminActionBookkeepingTest#testRequestBugreport) leaves a
        // state where future bug report requests will fail
        // TODO(b/130210665): replace this with use of NotificationListenerService to dismiss the
        // bug report request
        rebootAndWaitUntilReady();
    }

    public void testBluetoothRestriction() throws Exception {
        if (!mHasFeature) {
            return;
        }
        executeDeviceOwnerTest("BluetoothRestrictionTest");
    }

    public void testSetTime() throws Exception {
        if (!mHasFeature) {
            return;
        }
        executeDeviceOwnerTest("SetTimeTest");
    }

    public void testDeviceOwnerProvisioning() throws Exception {
        if (!mHasFeature) {
            return;
        }
        executeDeviceOwnerTest("DeviceOwnerProvisioningTest");
    }

    public void testDisallowFactoryReset() throws Exception {
        if (!mHasFeature) {
            return;
        }
        int adminVersion = 24;
        changeUserRestrictionOrFail("no_factory_reset", true, mPrimaryUserId,
                DEVICE_OWNER_PKG);
        try {
            installAppAsUser(DeviceAdminHelper.getDeviceAdminApkFileName(adminVersion),
                    mPrimaryUserId);
            setDeviceAdmin(DeviceAdminHelper.getAdminReceiverComponent(adminVersion),
                    mPrimaryUserId);
            runDeviceTestsAsUser(
                    DeviceAdminHelper.getDeviceAdminApkPackage(adminVersion),
                    DeviceAdminHelper.getDeviceAdminJavaPackage() + ".WipeDataTest",
                    "testWipeDataThrowsSecurityException", mPrimaryUserId);
        } finally {
            removeAdmin(DeviceAdminHelper.getAdminReceiverComponent(adminVersion), mPrimaryUserId);
            getDevice().uninstallPackage(DeviceAdminHelper.getDeviceAdminApkPackage(adminVersion));
        }
    }

    public void testBackupServiceEnabling() throws Exception {
        final boolean hasBackupService = getDevice().hasFeature(FEATURE_BACKUP);
        // The backup service cannot be enabled if the backup feature is not supported.
        if (!mHasFeature || !hasBackupService) {
            return;
        }
        executeDeviceTestMethod(".BackupServicePoliciesTest",
                "testEnablingAndDisablingBackupService");
    }

    public void testDeviceOwnerCanGetDeviceIdentifiers() throws Exception {
        // The Device Owner should have access to all device identifiers.
        if (!mHasFeature) {
            return;
        }
        executeDeviceTestMethod(".DeviceIdentifiersTest",
                "testDeviceOwnerCanGetDeviceIdentifiersWithPermission");
    }

    public void testDeviceOwnerCannotGetDeviceIdentifiersWithoutPermission() throws Exception {
        // The Device Owner must have the READ_PHONE_STATE permission to get access to the device
        // identifiers.
        if (!mHasFeature) {
            return;
        }
        // Revoke the READ_PHONE_STATE permission to ensure the device owner cannot access device
        // identifiers without consent.
        getDevice().executeShellCommand(
                "pm revoke " + DEVICE_OWNER_PKG + " android.permission.READ_PHONE_STATE");
        executeDeviceTestMethod(".DeviceIdentifiersTest",
                "testDeviceOwnerCannotGetDeviceIdentifiersWithoutPermission");
    }

    public void testPackageInstallCache() throws Exception {
        if (!mHasFeature) {
            return;
        }
        CompatibilityBuildHelper buildHelper = new CompatibilityBuildHelper(mCtsBuild);
        final File apk = buildHelper.getTestFile(TEST_APP_APK);
        try {
            getDevice().uninstallPackage(TEST_APP_PKG);
            assertTrue(getDevice().pushFile(apk, TEST_APP_LOCATION + apk.getName()));

            // Install the package in primary user
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".PackageInstallTest",
                    "testPackageInstall", mPrimaryUserId);

            assertMetricsLogged(getDevice(), () -> {
                runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".PackageInstallTest",
                        "testKeepPackageCache", mPrimaryUserId);
            }, new DevicePolicyEventWrapper.Builder(EventId.SET_KEEP_UNINSTALLED_PACKAGES_VALUE)
                    .setAdminPackageName(DEVICE_OWNER_PKG)
                    .setBoolean(false)
                    .setStrings(TEST_APP_PKG)
                    .build());

            // Remove the package in primary user
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".PackageInstallTest",
                    "testPackageUninstall", mPrimaryUserId);

            assertMetricsLogged(getDevice(), () -> {
                // Should be able to enable the cached package in primary user
                runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".PackageInstallTest",
                        "testInstallExistingPackage", mPrimaryUserId);
            }, new DevicePolicyEventWrapper.Builder(EventId.INSTALL_EXISTING_PACKAGE_VALUE)
                    .setAdminPackageName(DEVICE_OWNER_PKG)
                    .setBoolean(false)
                    .setStrings(TEST_APP_PKG)
                    .build());
        } finally {
            String command = "rm " + TEST_APP_LOCATION + apk.getName();
            getDevice().executeShellCommand(command);
            getDevice().uninstallPackage(TEST_APP_PKG);
        }
    }

    public void testPackageInstallCache_multiUser() throws Exception {
        if (!mHasFeature || !canCreateAdditionalUsers(1)) {
            return;
        }
        final int userId = createAffiliatedSecondaryUser();
        CompatibilityBuildHelper buildHelper = new CompatibilityBuildHelper(mCtsBuild);
        final File apk = buildHelper.getTestFile(TEST_APP_APK);
        try {
            getDevice().uninstallPackage(TEST_APP_PKG);
            assertTrue(getDevice().pushFile(apk, TEST_APP_LOCATION + apk.getName()));

            // Install the package in primary user
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".PackageInstallTest",
                    "testPackageInstall", mPrimaryUserId);

            // Should be able to enable the package in secondary user
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".PackageInstallTest",
                    "testInstallExistingPackage", userId);

            // Remove the package in both user
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".PackageInstallTest",
                    "testPackageUninstall", mPrimaryUserId);
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".PackageInstallTest",
                    "testPackageUninstall", userId);

            // Install the package in secondary user
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".PackageInstallTest",
                    "testPackageInstall", userId);

            // Should be able to enable the package in primary user
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".PackageInstallTest",
                    "testInstallExistingPackage", mPrimaryUserId);

            // Keep the package in cache
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".PackageInstallTest",
                    "testKeepPackageCache", mPrimaryUserId);

            // Remove the package in both user
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".PackageInstallTest",
                    "testPackageUninstall", mPrimaryUserId);
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".PackageInstallTest",
                    "testPackageUninstall", userId);

            // Should be able to enable the cached package in both users
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".PackageInstallTest",
                    "testInstallExistingPackage", userId);
            runDeviceTestsAsUser(DEVICE_OWNER_PKG, ".PackageInstallTest",
                    "testInstallExistingPackage", mPrimaryUserId);
        } finally {
            String command = "rm " + TEST_APP_LOCATION + apk.getName();
            getDevice().executeShellCommand(command);
            getDevice().uninstallPackage(TEST_APP_PKG);
        }
    }

    public void testAirplaneModeRestriction() throws Exception {
        if (!mHasFeature) {
            return;
        }
        executeDeviceOwnerTest("AirplaneModeRestrictionTest");
    }

    public void testOverrideApn() throws Exception {
        if (!mHasFeature || !hasDeviceFeature("android.hardware.telephony")) {
            return;
        }
        executeDeviceOwnerTest("OverrideApnTest");
    }

    public void testPrivateDnsPolicy() throws Exception {
        if (!mHasFeature) {
            return;
        }
        executeDeviceOwnerTest("PrivateDnsPolicyTest");
    }

    public void testInstallUpdate() throws Exception {
        if (!mHasFeature) {
            return;
        }

        pushUpdateFileToDevice("notZip.zi");
        pushUpdateFileToDevice("empty.zip");
        pushUpdateFileToDevice("wrongPayload.zip");
        pushUpdateFileToDevice("wrongHash.zip");
        pushUpdateFileToDevice("wrongSize.zip");
        executeDeviceOwnerTest("InstallUpdateTest");
    }

    public void testInstallUpdateLogged() throws Exception {
        if (!mHasFeature) {
            return;
        }
        pushUpdateFileToDevice("wrongHash.zip");
        assertMetricsLogged(getDevice(), () -> {
            executeDeviceTestMethod(".InstallUpdateTest", "testInstallUpdate_failWrongHash");
        }, new DevicePolicyEventWrapper.Builder(EventId.INSTALL_SYSTEM_UPDATE_VALUE)
                    .setAdminPackageName(DEVICE_OWNER_PKG)
                    .setBoolean(isDeviceAb())
                    .build(),
            new DevicePolicyEventWrapper.Builder(EventId.INSTALL_SYSTEM_UPDATE_ERROR_VALUE)
                    .setInt(isDeviceAb()
                            ? UPDATE_ERROR_UPDATE_FILE_INVALID
                            : UPDATE_ERROR_UNKNOWN)
                    .build());
    }

    private boolean isDeviceAb() throws DeviceNotAvailableException {
        final String result = getDevice().executeShellCommand("getprop ro.build.ab_update").trim();
        return "true".equalsIgnoreCase(result);
    }

    private void pushUpdateFileToDevice(String fileName)
            throws IOException, DeviceNotAvailableException {
        File file = File.createTempFile(
                fileName.split("\\.")[0], "." + fileName.split("\\.")[1]);
        try (OutputStream outputStream = new FileOutputStream(file)) {
            InputStream inputStream = getClass().getResourceAsStream("/" + fileName);
            ByteStreams.copy(inputStream, outputStream);
        }

        getDevice().pushFile(file, TEST_UPDATE_LOCATION + "/" + fileName);
        file.delete();
    }

    public void testSetKeyguardDisabledLogged() throws Exception {
        if (!mHasFeature) {
            return;
        }
        assertMetricsLogged(getDevice(), () -> {
            executeDeviceTestMethod(".DevicePolicyLoggingTest", "testSetKeyguardDisabledLogged");
        }, new DevicePolicyEventWrapper.Builder(EventId.SET_KEYGUARD_DISABLED_VALUE)
                .setAdminPackageName(DEVICE_OWNER_PKG)
                .build());
    }

    public void testSetStatusBarDisabledLogged() throws Exception {
        if (!mHasFeature) {
            return;
        }
        assertMetricsLogged(getDevice(), () -> {
            executeDeviceTestMethod(".DevicePolicyLoggingTest", "testSetStatusBarDisabledLogged");
        }, new DevicePolicyEventWrapper.Builder(EventId.SET_STATUS_BAR_DISABLED_VALUE)
                    .setAdminPackageName(DEVICE_OWNER_PKG)
                    .setBoolean(true)
                    .build(),
            new DevicePolicyEventWrapper.Builder(EventId.SET_STATUS_BAR_DISABLED_VALUE)
                    .setAdminPackageName(DEVICE_OWNER_PKG)
                    .setBoolean(true)
                    .build());
    }

    public void testNoHiddenActivityFoundTest() throws Exception {
        if (!mHasFeature) {
            return;
        }
        try {
            // Install app to primary user
            installAppAsUser(BaseLauncherAppsTest.LAUNCHER_TESTS_APK, mPrimaryUserId);
            installAppAsUser(BaseLauncherAppsTest.LAUNCHER_TESTS_SUPPORT_APK, mPrimaryUserId);
            installAppAsUser(LAUNCHER_TESTS_NO_LAUNCHABLE_ACTIVITY_APK, mPrimaryUserId);

            // Run test to check if launcher api shows hidden app
            String mSerialNumber = Integer.toString(getUserSerialNumber(USER_SYSTEM));
            runDeviceTestsAsUser(BaseLauncherAppsTest.LAUNCHER_TESTS_PKG,
                    BaseLauncherAppsTest.LAUNCHER_TESTS_CLASS,
                    "testDoPoNoTestAppInjectedActivityFound",
                    mPrimaryUserId, Collections.singletonMap(BaseLauncherAppsTest.PARAM_TEST_USER,
                            mSerialNumber));
        } finally {
            getDevice().uninstallPackage(LAUNCHER_TESTS_NO_LAUNCHABLE_ACTIVITY_APK);
            getDevice().uninstallPackage(BaseLauncherAppsTest.LAUNCHER_TESTS_SUPPORT_APK);
            getDevice().uninstallPackage(BaseLauncherAppsTest.LAUNCHER_TESTS_APK);
        }
    }

    private void executeDeviceOwnerTest(String testClassName) throws Exception {
        if (!mHasFeature) {
            return;
        }
        String testClass = DEVICE_OWNER_PKG + "." + testClassName;
        runDeviceTestsAsUser(DEVICE_OWNER_PKG, testClass, mPrimaryUserId);
    }

    private void executeAffiliatedProfileOwnerTest(String testClassName, int userId)
            throws Exception {
        if (!mHasFeature) {
            return;
        }
        String testClass = DEVICE_OWNER_PKG + "." + testClassName;
        runDeviceTestsAsUser(DEVICE_OWNER_PKG, testClass, userId);
    }

    private void executeDeviceTestMethod(String className, String testName) throws Exception {
        if (!mHasFeature) {
            return;
        }
        runDeviceTestsAsUser(DEVICE_OWNER_PKG, className, testName,
                /* deviceOwnerUserId */ mPrimaryUserId);
    }

    private int createAffiliatedSecondaryUser() throws Exception {
        final int userId = createUser();
        installAppAsUser(INTENT_RECEIVER_APK, userId);
        installAppAsUser(DEVICE_OWNER_APK, userId);
        setProfileOwnerOrFail(DEVICE_OWNER_COMPONENT, userId);

        switchUser(userId);
        waitForBroadcastIdle();
        wakeupAndDismissKeyguard();

        // Setting the same affiliation ids on both users and running the lock task tests.
        runDeviceTestsAsUser(
                DEVICE_OWNER_PKG, ".AffiliationTest", "testSetAffiliationId1", mPrimaryUserId);
        runDeviceTestsAsUser(
                DEVICE_OWNER_PKG, ".AffiliationTest", "testSetAffiliationId1", userId);
        return userId;
    }

    private void executeDeviceTestMethod(String className, String testName,
            Map<String, String> params) throws Exception {
        if (!mHasFeature) {
            return;
        }
        runDeviceTestsAsUser(DEVICE_OWNER_PKG, className, testName,
                /* deviceOwnerUserId */ mPrimaryUserId, params);
    }

    private void assertNewUserStopped() throws Exception {
        List<Integer> newUsers = getUsersCreatedByTests();
        assertEquals(1, newUsers.size());
        int newUserId = newUsers.get(0);

        assertFalse(getDevice().isUserRunning(newUserId));
    }
}
