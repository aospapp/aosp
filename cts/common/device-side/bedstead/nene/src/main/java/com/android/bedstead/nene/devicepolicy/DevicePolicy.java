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

package com.android.bedstead.nene.devicepolicy;

import static android.Manifest.permission.CREATE_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.app.role.RoleManager.ROLE_DEVICE_POLICY_MANAGEMENT;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.TIRAMISU;
import static android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE;

import static com.android.bedstead.nene.permissions.CommonPermissions.FORCE_DEVICE_POLICY_MANAGER_LOGS;
import static com.android.bedstead.nene.permissions.CommonPermissions.MANAGE_DEVICE_ADMINS;
import static com.android.bedstead.nene.permissions.CommonPermissions.MANAGE_PROFILE_AND_DEVICE_OWNERS;
import static com.android.bedstead.nene.permissions.CommonPermissions.MANAGE_ROLE_HOLDERS;
import static com.android.bedstead.nene.permissions.CommonPermissions.QUERY_ADMIN_POLICY;

import android.annotation.TargetApi;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.annotations.Experimental;
import com.android.bedstead.nene.exceptions.AdbException;
import com.android.bedstead.nene.exceptions.AdbParseException;
import com.android.bedstead.nene.exceptions.NeneException;
import com.android.bedstead.nene.logcat.BlockingLogcatListener;
import com.android.bedstead.nene.packages.ComponentReference;
import com.android.bedstead.nene.packages.Package;
import com.android.bedstead.nene.permissions.CommonPermissions;
import com.android.bedstead.nene.permissions.PermissionContext;
import com.android.bedstead.nene.roles.RoleContext;
import com.android.bedstead.nene.users.UserReference;
import com.android.bedstead.nene.utils.Poll;
import com.android.bedstead.nene.utils.Retry;
import com.android.bedstead.nene.utils.ShellCommand;
import com.android.bedstead.nene.utils.ShellCommandUtils;
import com.android.bedstead.nene.utils.Versions;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Test APIs related to device policy.
 */
public final class DevicePolicy {

    public static final DevicePolicy sInstance = new DevicePolicy();

    private static final String LOG_TAG = "DevicePolicy";

    private final AdbDevicePolicyParser mParser;

    private DeviceOwner mCachedDeviceOwner;
    private Map<UserReference, ProfileOwner> mCachedProfileOwners;

    private static final DevicePolicyManager sDevicePolicyManager =
            TestApis.context().instrumentedContext()
                    .getSystemService(DevicePolicyManager.class);

    private DevicePolicy() {
        mParser = AdbDevicePolicyParser.get(SDK_INT);
    }

    /**
     * Set the profile owner for a given {@link UserReference}.
     */
    public ProfileOwner setProfileOwner(UserReference user, ComponentName profileOwnerComponent) {
        if (user == null || profileOwnerComponent == null) {
            throw new NullPointerException();
        }

        ShellCommand.Builder command =
                ShellCommand.builderForUser(user, "dpm set-profile-owner")
                .addOperand(profileOwnerComponent.flattenToShortString())
                .validate(ShellCommandUtils::startsWithSuccess);

        // TODO(b/187925230): If it fails, we check for terminal failure states - and if not
        //  we retry because if the profile owner was recently removed, it can take some time
        //  to be allowed to set it again
        try {
            Retry.logic(command::execute)
                    .terminalException((ex) -> {

                        if (!Versions.meetsMinimumSdkVersionRequirement(Build.VERSION_CODES.S)) {
                            return false; // Just retry on old versions as we don't have stderr
                        }
                        if (ex instanceof AdbException) {
                            String error = ((AdbException) ex).error();
                            if (error != null && error.contains("is already set")) {
                                // This can happen for a while when it is being tidied up
                                return false;
                            }

                            if (error != null && error.contains("is being removed")) {
                                return false;
                            }
                        }

                        // Assume all other errors are terminal
                        return true;
                    })
                    .timeout(Duration.ofMinutes(5))
                    .run();
        } catch (Throwable e) {
            throw new NeneException("Could not set profile owner for user "
                    + user + " component " + profileOwnerComponent, e);
        }

        Poll.forValue("Profile Owner", () -> TestApis.devicePolicy().getProfileOwner(user))
                .toNotBeNull()
                .errorOnFail()
                .await();

        return new ProfileOwner(user,
                TestApis.packages().find(
                        profileOwnerComponent.getPackageName()), profileOwnerComponent);
    }

    /**
     * Get the organization owned profile owner for the device, if any, otherwise null.
     */
    public ProfileOwner getOrganizationOwnedProfileOwner() {
        for (UserReference user : TestApis.users().all()) {
            ProfileOwner profileOwner = getProfileOwner(user);
            if (profileOwner != null && profileOwner.isOrganizationOwned()) {
                return profileOwner;
            }
        }

        return null;
    }


    /**
     * Get the profile owner for the instrumented user.
     */
    public ProfileOwner getProfileOwner() {
        return getProfileOwner(TestApis.users().instrumented());
    }

    /**
     * Get the profile owner for a given {@link UserHandle}.
     */
    public ProfileOwner getProfileOwner(UserHandle user) {
        return getProfileOwner(UserReference.of(user));
    }

    /**
     * Get the profile owner for a given {@link UserReference}.
     */
    public ProfileOwner getProfileOwner(UserReference user) {
        if (user == null) {
            throw new NullPointerException();
        }
        fillCache();
        return mCachedProfileOwners.get(user);
    }

    /**
     * Set the device owner.
     */
    public DeviceOwner setDeviceOwner(ComponentName deviceOwnerComponent) {
        if (deviceOwnerComponent == null) {
            throw new NullPointerException();
        }

        if (!Versions.meetsMinimumSdkVersionRequirement(Build.VERSION_CODES.S)) {
            return setDeviceOwnerPreS(deviceOwnerComponent);
        } else if (!Versions.meetsMinimumSdkVersionRequirement(Versions.U)) {
            return setDeviceOwnerPreU(deviceOwnerComponent);
        }

        UserReference user = TestApis.users().system();

        try (PermissionContext p =
                     TestApis.permissions().withPermission(
                             MANAGE_PROFILE_AND_DEVICE_OWNERS, MANAGE_DEVICE_ADMINS,
                             INTERACT_ACROSS_USERS_FULL, INTERACT_ACROSS_USERS, CREATE_USERS)) {

            ShellCommand.Builder command = ShellCommand.builderForUser(
                            user, "dpm set-device-owner --device-owner-only")
                    .addOperand(deviceOwnerComponent.flattenToShortString())
                    .validate(ShellCommandUtils::startsWithSuccess);
            // TODO(b/187925230): If it fails, we check for terminal failure states - and if not
            //  we retry because if the DO/PO was recently removed, it can take some time
            //  to be allowed to set it again
            Retry.logic(command::execute)
                    .terminalException(
                            (e) -> checkForTerminalDeviceOwnerFailures(
                                    user,
                                    deviceOwnerComponent,
                                    /* allowAdditionalUsers= */ false,
                                    e))
                    .timeout(Duration.ofMinutes(5))
                    .run();
        } catch (Throwable e) {
            throw new NeneException("Error setting device owner.", e);
        }

        Package deviceOwnerPackage = TestApis.packages().find(
                deviceOwnerComponent.getPackageName());

        Poll.forValue("Device Owner", () -> TestApis.devicePolicy().getDeviceOwner())
                .toNotBeNull()
                .errorOnFail()
                .await();

        return new DeviceOwner(user, deviceOwnerPackage, deviceOwnerComponent);
    }

    /**
     * Set Device Owner without changing any other device state.
     *
     * <p>This is used instead of {@link DevicePolicyManager#setDeviceOwner(ComponentName)} directly
     * because on S_V2 and above, that method can also set profile owners and install packages in
     * some circumstances.
     */
    private void setDeviceOwnerOnly(DevicePolicyManager devicePolicyManager,
            ComponentName component, int deviceOwnerUserId) {
        if (Versions.meetsMinimumSdkVersionRequirement(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)) {
            devicePolicyManager.setDeviceOwnerOnly(component, deviceOwnerUserId);
        } else if (Versions.meetsMinimumSdkVersionRequirement(Build.VERSION_CODES.S_V2)) {
            try {
                DevicePolicyManager.class.getMethod(
                        "setDeviceOwnerOnly", ComponentName.class, String.class, int.class)
                        .invoke(devicePolicyManager, component, null, deviceOwnerUserId);
            } catch (IllegalAccessException | InvocationTargetException
                    | NoSuchMethodException e) {
                throw new NeneException("Error executing setDeviceOwnerOnly", e);
            }
        } else {
            try {
                DevicePolicyManager.class.getMethod(
                        "setDeviceOwner", ComponentName.class, String.class, int.class)
                        .invoke(devicePolicyManager, component, null, deviceOwnerUserId);
            } catch (IllegalAccessException | InvocationTargetException
                    | NoSuchMethodException e) {
                throw new NeneException("Error executing setDeviceOwner", e);
            }
        }
    }

    /**
     * Resets organization ID via @TestApi.
     * @param user whose organization ID to clear
     */
    public void clearOrganizationId(UserReference user) {
        try (PermissionContext p =
                     TestApis.permissions().withPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)) {
            devicePolicyManager(user).clearOrganizationId();
        }
    }

    private DevicePolicyManager devicePolicyManager(UserReference user) {
        return TestApis.context().instrumentedContextAsUser(user)
                .getSystemService(DevicePolicyManager.class);
    }

    private DeviceOwner setDeviceOwnerPreU(ComponentName deviceOwnerComponent) {
        UserReference user = TestApis.users().system();

        boolean dpmUserSetupComplete = user.getSetupComplete();
        Boolean currentUserSetupComplete = null;

        try {
            user.setSetupComplete(false);

            try (PermissionContext p =
                         TestApis.permissions().withPermission(
                                 MANAGE_PROFILE_AND_DEVICE_OWNERS, MANAGE_DEVICE_ADMINS,
                                 INTERACT_ACROSS_USERS_FULL, INTERACT_ACROSS_USERS, CREATE_USERS)) {

                // TODO(b/187925230): If it fails, we check for terminal failure states - and if not
                //  we retry because if the DO/PO was recently removed, it can take some time
                //  to be allowed to set it again
                Retry.logic(
                        () -> {
                            sDevicePolicyManager.setActiveAdmin(
                                    deviceOwnerComponent,
                                    /* refreshing= */ true,
                                    user.id());
                            setDeviceOwnerOnly(
                                    sDevicePolicyManager, deviceOwnerComponent, user.id());
                        })
                        .terminalException(
                                (e) -> checkForTerminalDeviceOwnerFailures(
                                        user,
                                        deviceOwnerComponent,
                                        /* allowAdditionalUsers= */ true,
                                        e))
                        .timeout(Duration.ofMinutes(5))
                        .run();
            } catch (Throwable e) {
                throw new NeneException("Error setting device owner", e);
            }
        } finally {
            user.setSetupComplete(dpmUserSetupComplete);
            if (currentUserSetupComplete != null) {
                TestApis.users().current().setSetupComplete(currentUserSetupComplete);
            }
        }

        Poll.forValue("Device Owner", () -> TestApis.devicePolicy().getDeviceOwner())
                .toNotBeNull()
                .errorOnFail()
                .await();

        return new DeviceOwner(user,
                TestApis.packages().find(deviceOwnerComponent.getPackageName()),
                deviceOwnerComponent);
    }

    private DeviceOwner setDeviceOwnerPreS(ComponentName deviceOwnerComponent) {
        UserReference user = TestApis.users().system();

        ShellCommand.Builder command = ShellCommand.builderForUser(
                user, "dpm set-device-owner")
                .addOperand(deviceOwnerComponent.flattenToShortString())
                .validate(ShellCommandUtils::startsWithSuccess);
        // TODO(b/187925230): If it fails, we check for terminal failure states - and if not
        //  we retry because if the device owner was recently removed, it can take some time
        //  to be allowed to set it again

        try {
            Retry.logic(command::execute)
                    .terminalException(
                            (e) ->
                                    checkForTerminalDeviceOwnerFailures(
                                            user,
                                            deviceOwnerComponent,
                                            /* allowAdditionalUsers= */ false,
                                            e))
                    .timeout(Duration.ofMinutes(5))
                    .run();
        } catch (Throwable e) {
            throw new NeneException("Error setting device owner", e);
        }

        return new DeviceOwner(user,
                TestApis.packages().find(
                        deviceOwnerComponent.getPackageName()), deviceOwnerComponent);
    }

    private boolean checkForTerminalDeviceOwnerFailures(
            UserReference user,
            ComponentName deviceOwnerComponent,
            boolean allowAdditionalUsers,
            Throwable e) {
        DeviceOwner deviceOwner = getDeviceOwner();
        if (deviceOwner != null) {
            // TODO(scottjonathan): Should we actually fail here if the component name is the
            //  same?

            throw new NeneException(
                    "Could not set device owner for user "
                            + user
                            + " as a device owner is already set: "
                            + deviceOwner,
                    e);
        }

        Package pkg = TestApis.packages().find(
                deviceOwnerComponent.getPackageName());
        if (!TestApis.packages().installedForUser(user).contains(pkg)) {
            throw new NeneException(
                    "Could not set device owner for user "
                            + user
                            + " as the package "
                            + pkg
                            + " is not installed",
                    e);
        }

        if (!componentCanBeSetAsDeviceAdmin(deviceOwnerComponent, user)) {
            throw new NeneException(
                    "Could not set device owner for user "
                            + user
                            + " as component "
                            + deviceOwnerComponent
                            + " is not valid",
                    e);
        }

        if (!allowAdditionalUsers && nonTestNonPrecreatedUsersExist()) {
            throw new NeneException(
                    "Could not set device owner for user "
                            + user
                            + " as there are already additional non-test on the device",
                    e);
        }
        // TODO(scottjonathan): Check accounts

        return false;
    }

    private boolean componentCanBeSetAsDeviceAdmin(ComponentName component, UserReference user) {
        PackageManager packageManager =
                TestApis.context().instrumentedContext().getPackageManager();
        Intent intent = new Intent("android.app.action.DEVICE_ADMIN_ENABLED");
        intent.setComponent(component);

        try (PermissionContext p =
                     TestApis.permissions().withPermission(INTERACT_ACROSS_USERS_FULL)) {
            List<ResolveInfo> r =
                    packageManager.queryBroadcastReceiversAsUser(
                            intent, /* flags= */ 0, user.userHandle());
            return (!r.isEmpty());
        }
    }

    /**
     * Get the device owner.
     */
    @Nullable
    public DeviceOwner getDeviceOwner() {
        fillCache();
        return mCachedDeviceOwner;
    }

    private void fillCache() {
        int retries = 5;
        while (true) {
            try {
                // TODO: Replace use of adb on supported versions of Android
                String devicePolicyDumpsysOutput =
                        ShellCommand.builder("dumpsys device_policy").execute();
                AdbDevicePolicyParser.ParseResult result = mParser.parse(devicePolicyDumpsysOutput);

                mCachedDeviceOwner = result.mDeviceOwner;
                mCachedProfileOwners = result.mProfileOwners;

                return;
            } catch (AdbParseException e) {
                if (e.adbOutput().contains("DUMP TIMEOUT") && retries-- > 0) {
                    // Sometimes this call times out - just retry
                    Log.e(LOG_TAG, "Dump timeout when filling cache, retrying", e);
                } else {
                    throw new NeneException("Error filling cache", e);
                }
            } catch (AdbException e) {
                throw new NeneException("Error filling cache", e);
            }
        }
    }

    /** See {@link android.app.admin.DevicePolicyManager#getPolicyExemptApps()}. */
    @Experimental
    public Set<String> getPolicyExemptApps() {
        try (PermissionContext p = TestApis.permissions().withPermission(MANAGE_DEVICE_ADMINS)) {
            return TestApis.context()
                    .instrumentedContext()
                    .getSystemService(DevicePolicyManager.class)
                    .getPolicyExemptApps();
        }
    }

    @Experimental
    public void forceNetworkLogs() {
        try (PermissionContext p = TestApis.permissions().withPermission(FORCE_DEVICE_POLICY_MANAGER_LOGS)) {
            long throttle = TestApis.context()
                    .instrumentedContext()
                    .getSystemService(DevicePolicyManager.class)
                    .forceNetworkLogs();

            if (throttle == -1) {
                throw new NeneException("Error forcing network logs: returned -1");
            }
            if (throttle == 0) {
                return;
            }
            try {
                Thread.sleep(throttle);
            } catch (InterruptedException e) {
                throw new NeneException("Error waiting for network log throttle", e);
            }

            forceNetworkLogs();
        }
    }

    /**
     * Sets the provided {@code packageName} as a device policy management role holder.
     */
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    @Experimental
    public RoleContext setDevicePolicyManagementRoleHolder(Package pkg, UserReference user) {
        Versions.requireMinimumVersion(TIRAMISU);

        if (!Versions.meetsMinimumSdkVersionRequirement(Versions.U)) {
            if (TestApis.users().all().size() > 1) {
                throw new NeneException("Could not set device policy management role holder as"
                        + " more than one user is on the device");
            }
        }

        if (nonTestNonPrecreatedUsersExist()) {
            throw new NeneException("Could not set device policy management role holder as"
                    + " non-test users already exist");
        }

        TestApis.roles().setBypassingRoleQualification(true);

        return pkg.setAsRoleHolder(ROLE_DEVICE_POLICY_MANAGEMENT, user);
    }

    private boolean nonTestNonPrecreatedUsersExist() {
        int expectedPrecreatedUsers = TestApis.users().isHeadlessSystemUserMode() ? 2 : 1;

        return TestApis.users().all().stream()
                .filter(u -> !u.isForTesting())
                .count() > expectedPrecreatedUsers;
    }

    /**
     * Unsets the provided {@code packageName} as a device policy management role holder.
     */
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    @Experimental
    public void unsetDevicePolicyManagementRoleHolder(Package pkg, UserReference user) {
        Versions.requireMinimumVersion(TIRAMISU);

        pkg.removeAsRoleHolder(ROLE_DEVICE_POLICY_MANAGEMENT, user);
    }

    /**
     * Returns true if the AutoTimeRequired policy is set to true for the given user.
     */
    @Experimental
    public boolean autoTimeRequired(UserReference user) {
        return TestApis.context().androidContextAsUser(user)
                .getSystemService(DevicePolicyManager.class)
                .getAutoTimeRequired();
    }

    /**
     * Returns true if the AutoTimeRequired policy is set to true for the instrumented user.
     */
    @Experimental
    public boolean autoTimeRequired() {
        return autoTimeRequired(TestApis.users().instrumented());
    }

    /**
     * See {@code DevicePolicyManager#isNewUserDisclaimerAcknowledged}.
     */
    @Experimental
    public boolean isNewUserDisclaimerAcknowledged(UserReference user) {
        try (PermissionContext p = TestApis.permissions().withPermission(
                CommonPermissions.INTERACT_ACROSS_USERS)) {
            return TestApis.context().androidContextAsUser(user).getSystemService(
                    DevicePolicyManager.class).isNewUserDisclaimerAcknowledged();
        }
    }

    /**
     * See {@code DevicePolicyManager#isNewUserDisclaimerAcknowledged}.
     */
    @Experimental
    public boolean isNewUserDisclaimerAcknowledged() {
        return isNewUserDisclaimerAcknowledged(TestApis.users().instrumented());
    }

    /**
     * Access APIs related to Device Policy resource overriding.
     */
    @TargetApi(TIRAMISU)
    public DevicePolicyResources resources() {
        Versions.requireMinimumVersion(TIRAMISU);
        return DevicePolicyResources.sInstance;
    }

    /**
     * Get active admins on the instrumented user.
     */
    public Set<ComponentReference> getActiveAdmins() {
        return getActiveAdmins(TestApis.users().instrumented());
    }

    /**
     * Get active admins on the given user.
     */
    public Set<ComponentReference> getActiveAdmins(UserReference user) {
        try (PermissionContext p =
                     TestApis.permissions().withPermission(INTERACT_ACROSS_USERS_FULL)) {
            List<ComponentName> activeAdmins = devicePolicyManager(user).getActiveAdmins();
            if (activeAdmins == null) {
                return Set.of();
            }
            return activeAdmins.stream().map(ComponentReference::new).collect(
                    Collectors.toSet());
        }
    }

    /**
     * See {@link DevicePolicyManager#resetShouldAllowBypassingDevicePolicyManagementRoleQualificationState}.
     */
    @TargetApi(UPSIDE_DOWN_CAKE)
    public void resetShouldAllowBypassingDevicePolicyManagementRoleQualificationState() {
        Versions.requireMinimumVersion(UPSIDE_DOWN_CAKE);

        try (PermissionContext p = TestApis.permissions().withPermission(MANAGE_ROLE_HOLDERS)) {
            devicePolicyManager(TestApis.users().instrumented())
                    .resetShouldAllowBypassingDevicePolicyManagementRoleQualificationState();
        }
    }

    /**
     * Set or check user restrictions.
     */
    public UserRestrictions userRestrictions(UserHandle user) {
        return userRestrictions(UserReference.of(user));
    }

    /**
     * Set or check user restrictions on the instrumented user
     */
    public UserRestrictions userRestrictions() {
        return new UserRestrictions(TestApis.users().instrumented());
    }

    /**
     * Set or check user restrictions.
     */
    public UserRestrictions userRestrictions(UserReference user) {
        return new UserRestrictions(user);
    }

    /**
     * OEM-Set default cross profile packages.
     */
    @Experimental
    public Set<Package> defaultCrossProfilePackages() {
        return sDevicePolicyManager.getDefaultCrossProfilePackages()
                .stream().map(i -> TestApis.packages().find(i))
                .collect(Collectors.toSet());
    }

    /**
     * True if there is a Device Owner who can grant sensor permissions.
     */
    @Experimental
    @SuppressWarnings("NewApi")
    public boolean canAdminGrantSensorsPermissions() {
        if (!Versions.meetsMinimumSdkVersionRequirement(31)) {
            return true;
        }
        return sDevicePolicyManager.canAdminGrantSensorsPermissions();
    }

    /**
     * See DevicePolicyManager#getUserProvisioningState().
     */
    @Experimental
    public int getUserProvisioningState() {
        return getUserProvisioningState(TestApis.users().instrumented());
    }

    /**
     * @see DevicePolicyManager#getUserProvisioningState().
     */
    @Experimental
    public int getUserProvisioningState(UserReference user) {
        try (PermissionContext p =
                     TestApis.permissions().withPermission(INTERACT_ACROSS_USERS)) {
            return devicePolicyManager(user).getUserProvisioningState();
        }
    }

    /**
     * Get password expiration timeout for the instrumented user.
     */
    @Experimental
    public long getPasswordExpirationTimeout() {
        return getPasswordExpirationTimeout(TestApis.users().instrumented());
    }

    /**
     * See {@link DevicePolicyManager#getPasswordExpirationTimeout}.
     */
    @Experimental
    public long getPasswordExpirationTimeout(UserReference user) {
        try (PermissionContext p =
                     TestApis.permissions().withPermission(INTERACT_ACROSS_USERS)) {
            return devicePolicyManager(user)
                    .getPasswordExpirationTimeout(/* componentName= */ null);
        }
    }

    /**
     * Get maximum time to lock for the instrumented user.
     */
    @Experimental
    public long getMaximumTimeToLock() {
        return getMaximumTimeToLock(TestApis.users().instrumented());
    }

    /**
     * See {@link DevicePolicyManager#getMaximumTimeToLock}.
     */
    @Experimental
    public long getMaximumTimeToLock(UserReference user) {
        try (PermissionContext p =
                     TestApis.permissions().withPermission(INTERACT_ACROSS_USERS)) {
            return devicePolicyManager(user)
                    .getMaximumTimeToLock(/* componentName= */ null);
        }
    }

    /**
     * Get strong auth timeout for the instrumented user.
     */
    @Experimental
    public long getRequiredStrongAuthTimeout() {
        return getRequiredStrongAuthTimeout(TestApis.users().instrumented());
    }

    /**
     * See {@link DevicePolicyManager#getRequiredStrongAuthTimeout}.
     */
    @Experimental
    public long getRequiredStrongAuthTimeout(UserReference user) {
        try (PermissionContext p =
                     TestApis.permissions().withPermission(INTERACT_ACROSS_USERS)) {
            return devicePolicyManager(user)
                    .getRequiredStrongAuthTimeout(/* componentName= */ null);
        }
    }

    // TODO: Consider wrapping keyguard disabled features with a bedstead concept instead of flags

    /**
     * Get keyguard disabled features for the instrumented user.
     */
    @Experimental
    public int getKeyguardDisabledFeatures() {
        return getKeyguardDisabledFeatures(TestApis.users().instrumented());
    }

    /**
     * See {@link DevicePolicyManager#getKeyguardDisabledFeatures}.
     */
    @Experimental
    public int getKeyguardDisabledFeatures(UserReference user) {
        try (PermissionContext p =
                     TestApis.permissions().withPermission(INTERACT_ACROSS_USERS)) {
            return devicePolicyManager(user)
                    .getKeyguardDisabledFeatures(/* componentName= */ null);
        }
    }

    /**
     * Get keyguard disabled features for the instrumented user.
     */
    @Experimental
    public Set<PersistableBundle> getTrustAgentConfiguration(ComponentName trustAgent) {
        return getTrustAgentConfiguration(trustAgent, TestApis.users().instrumented());
    }

    /**
     * See {@link DevicePolicyManager#getTrustAgentConfiguration}.
     */
    @Experimental
    public Set<PersistableBundle> getTrustAgentConfiguration(
            ComponentName trustAgent, UserReference user) {
        try (PermissionContext p =
                     TestApis.permissions().withPermission(INTERACT_ACROSS_USERS)) {
            List<PersistableBundle> configurations = devicePolicyManager(user)
                    .getTrustAgentConfiguration(/* componentName= */ null, trustAgent);
            return configurations == null ? Set.of() : Set.copyOf(configurations);
        }
    }

    /**
     * True if either this is the system user or the user is affiliated with a device owner on
     * the device.
     */
    @Experimental
    public boolean isAffiliated() {
        return isAffiliated(TestApis.users().instrumented());
    }

    // TODO(276248451): Make user handle aware so it'll work cross-user
    @Experimental
    private boolean isAffiliated(UserReference user) {
        return devicePolicyManager(user).isAffiliatedUser();
    }

    @Experimental
    public List<String> getPermittedInputMethods() {
        // TODO: Enable cross-user
        try (PermissionContext p = TestApis.permissions().withPermission(QUERY_ADMIN_POLICY)) {
            return sDevicePolicyManager.getPermittedInputMethodsForCurrentUser();
        }
    }

    /**
     * Recalculate the "hasIncompatibleAccounts" cache inside DevicePolicyManager.
     */
    @Experimental
    public void calculateHasIncompatibleAccounts() {
        if (!Versions.meetsMinimumSdkVersionRequirement(Versions.U)) {
            // Nothing to calculate pre-U
            return;
        }
        try (BlockingLogcatListener b =
                     TestApis.logcat().listen(
                             l -> l.contains("Finished calculating hasIncompatibleAccountsTask"))) {
            sDevicePolicyManager.calculateHasIncompatibleAccounts();
        }
    }

    /** See {@link DevicePolicyManager#getPermittedAccessibilityServices} */
    @Experimental
    public Set<Package> getPermittedAccessibilityServices() {
        return getPermittedAccessibilityServices(TestApis.users().instrumented());
    }

    /** See {@link DevicePolicyManager#getPermittedAccessibilityServices} */
    @Experimental
    public Set<Package> getPermittedAccessibilityServices(UserReference user) {
        try (PermissionContext p = TestApis.permissions().withPermission(INTERACT_ACROSS_USERS, QUERY_ADMIN_POLICY)) {
            List<String> services = sDevicePolicyManager.getPermittedAccessibilityServices(user.id());
            if (services == null) {
                return null;
            }
            return services.stream()
                    .map(packageName -> TestApis.packages().find(packageName))
                    .collect(Collectors.toSet());
        }
    }
}
