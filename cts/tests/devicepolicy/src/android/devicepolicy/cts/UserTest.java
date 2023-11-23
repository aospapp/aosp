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

import static android.os.UserManager.SWITCHABILITY_STATUS_USER_SWITCH_DISALLOWED;

import static com.android.bedstead.nene.permissions.CommonPermissions.CREATE_USERS;
import static com.android.bedstead.nene.permissions.CommonPermissions.INTERACT_ACROSS_USERS;
import static com.android.bedstead.nene.types.OptionalBoolean.ANY;
import static com.android.bedstead.nene.userrestrictions.CommonUserRestrictions.DISALLOW_REMOVE_USER;
import static com.android.bedstead.nene.userrestrictions.CommonUserRestrictions.DISALLOW_USER_SWITCH;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.os.UserManager;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.UserType;
import com.android.bedstead.harrier.annotations.EnsureDoesNotHaveUserRestriction;
import com.android.bedstead.harrier.annotations.EnsureHasAdditionalUser;
import com.android.bedstead.harrier.annotations.EnsureHasPermission;
import com.android.bedstead.harrier.annotations.EnsureHasUserRestriction;
import com.android.bedstead.harrier.annotations.Postsubmit;
import com.android.bedstead.harrier.annotations.RequireRunOnAdditionalUser;
import com.android.bedstead.harrier.annotations.RequireRunOnInitialUser;
import com.android.bedstead.harrier.annotations.RequireRunOnSystemUser;
import com.android.bedstead.harrier.annotations.enterprise.CannotSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.PolicyAppliesTest;
import com.android.bedstead.harrier.annotations.enterprise.PolicyDoesNotApplyTest;
import com.android.bedstead.harrier.policies.DisallowRemoveUser;
import com.android.bedstead.harrier.policies.DisallowUserSwitch;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.users.UserReference;
import com.android.compatibility.common.util.ApiTest;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(BedsteadJUnit4.class)
public final class UserTest {

    @ClassRule @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private static final UserManager sLocalUserManager =
            TestApis.context().instrumentedContext().getSystemService(UserManager.class);

    @CannotSetPolicyTest(policy = DisallowRemoveUser.class, includeNonDeviceAdminStates = false)
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.os.UserManager#DISALLOW_REMOVE_USER")
    public void setUserRestriction_disallowRemoveUser_cannotSet_throwsException() {
        assertThrows(SecurityException.class,
                () -> sDeviceState.dpc().devicePolicyManager().addUserRestriction(
                        sDeviceState.dpc().componentName(), DISALLOW_REMOVE_USER));
    }

    @PolicyAppliesTest(policy = DisallowRemoveUser.class)
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.os.UserManager#DISALLOW_REMOVE_USER")
    public void setUserRestriction_disallowRemoveUser_isSet() {
        try {
            sDeviceState.dpc().devicePolicyManager().addUserRestriction(
                    sDeviceState.dpc().componentName(), DISALLOW_REMOVE_USER);

            assertThat(TestApis.devicePolicy().userRestrictions().isSet(DISALLOW_REMOVE_USER))
                    .isTrue();
        } finally {
            sDeviceState.dpc().devicePolicyManager().clearUserRestriction(
                    sDeviceState.dpc().componentName(), DISALLOW_REMOVE_USER);
        }
    }

    @PolicyDoesNotApplyTest(policy = DisallowRemoveUser.class)
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.os.UserManager#DISALLOW_REMOVE_USER")
    public void setUserRestriction_disallowRemoveUser_isNotSet() {
        try {
            sDeviceState.dpc().devicePolicyManager().addUserRestriction(
                    sDeviceState.dpc().componentName(), DISALLOW_REMOVE_USER);

            assertThat(TestApis.devicePolicy().userRestrictions().isSet(DISALLOW_REMOVE_USER))
                    .isFalse();
        } finally {

            sDeviceState.dpc().devicePolicyManager().clearUserRestriction(
                    sDeviceState.dpc().componentName(), DISALLOW_REMOVE_USER);
        }
    }

    @EnsureHasAdditionalUser
    @EnsureDoesNotHaveUserRestriction(value = DISALLOW_REMOVE_USER, onUser = UserType.ADMIN_USER)
    @Test
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.os.UserManager#DISALLOW_REMOVE_USER")
    @EnsureHasPermission(CREATE_USERS)
    @RequireRunOnSystemUser(switchedToUser = ANY)
    public void removeUser_disallowRemoveUserIsNotSet_isRemoved() throws Exception {
        UserReference additionalUser = sDeviceState.additionalUser();

        boolean result = sLocalUserManager.removeUser(additionalUser.userHandle());

        assertThat(result).isTrue();
        assertThat(additionalUser.exists()).isFalse();
    }

    @EnsureHasAdditionalUser
    @EnsureHasUserRestriction(value = DISALLOW_REMOVE_USER, onUser = UserType.ADMIN_USER)
    @Test
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.os.UserManager#DISALLOW_REMOVE_USER")
    @EnsureHasPermission(CREATE_USERS)
    @RequireRunOnSystemUser(switchedToUser = ANY)
    public void removeUser_disallowRemoveUserIsSetOnAdminUser_returnsFalse() {
        UserReference additionalUser = sDeviceState.additionalUser();

        boolean result = sLocalUserManager.removeUser(additionalUser.userHandle());

        assertThat(result).isFalse();
        assertThat(additionalUser.exists()).isTrue();
    }

    @EnsureHasUserRestriction(value = DISALLOW_REMOVE_USER)
    @Test
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.os.UserManager#DISALLOW_REMOVE_USER")
    @EnsureHasPermission(CREATE_USERS)
    @RequireRunOnAdditionalUser
    public void removeUser_ownUser_disallowRemoveUserIsSet_returnsFalse() {
        boolean result = sLocalUserManager.removeUser(TestApis.users().instrumented().userHandle());

        assertThat(result).isFalse();
    }

    @CannotSetPolicyTest(policy = DisallowUserSwitch.class, includeNonDeviceAdminStates = false)
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.os.UserManager#DISALLOW_USER_SWITCH")
    public void setUserRestriction_disallowUserSwitch_cannotSet_throwsException() {
        assertThrows(SecurityException.class,
                () -> sDeviceState.dpc().devicePolicyManager().addUserRestriction(
                        sDeviceState.dpc().componentName(), DISALLOW_USER_SWITCH));
    }

    @PolicyAppliesTest(policy = DisallowUserSwitch.class)
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.os.UserManager#DISALLOW_USER_SWITCH")
    public void setUserRestriction_disallowUserSwitch_isSet() {
        try {
            sDeviceState.dpc().devicePolicyManager().addUserRestriction(
                    sDeviceState.dpc().componentName(), DISALLOW_USER_SWITCH);

            assertThat(TestApis.devicePolicy().userRestrictions().isSet(DISALLOW_USER_SWITCH))
                    .isTrue();
        } finally {
            sDeviceState.dpc().devicePolicyManager().clearUserRestriction(
                    sDeviceState.dpc().componentName(), DISALLOW_USER_SWITCH);
        }
    }

    @PolicyDoesNotApplyTest(policy = DisallowUserSwitch.class)
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.os.UserManager#DISALLOW_USER_SWITCH")
    public void setUserRestriction_disallowUserSwitch_isNotSet() {
        try {
            sDeviceState.dpc().devicePolicyManager().addUserRestriction(
                    sDeviceState.dpc().componentName(), DISALLOW_USER_SWITCH);

            assertThat(TestApis.devicePolicy().userRestrictions().isSet(DISALLOW_USER_SWITCH))
                    .isFalse();
        } finally {

            sDeviceState.dpc().devicePolicyManager().clearUserRestriction(
                    sDeviceState.dpc().componentName(), DISALLOW_USER_SWITCH);
        }
    }

    @EnsureDoesNotHaveUserRestriction(value = DISALLOW_USER_SWITCH, onUser = UserType.ADMIN_USER)
    @Test
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.os.UserManager#DISALLOW_USER_SWITCH")
    @EnsureHasPermission(INTERACT_ACROSS_USERS)
    @RequireRunOnInitialUser
    public void getUserSwitchability_disallowUserSwitchIsNotSet_isNotDisallowed() throws Exception {
        assertThat(sLocalUserManager.getUserSwitchability())
                .isNotEqualTo(SWITCHABILITY_STATUS_USER_SWITCH_DISALLOWED);
    }

    @EnsureHasUserRestriction(value = DISALLOW_USER_SWITCH, onUser = UserType.ADMIN_USER)
    @Test
    @Postsubmit(reason = "new test")
    @ApiTest(apis = "android.os.UserManager#DISALLOW_USER_SWITCH")
    @EnsureHasPermission(INTERACT_ACROSS_USERS)
    @RequireRunOnInitialUser
    public void getUserSwitchability_disallowUserSwitchIsSet_isDisallowed() throws Exception {
        assertThat(sLocalUserManager.getUserSwitchability())
                .isEqualTo(SWITCHABILITY_STATUS_USER_SWITCH_DISALLOWED);
    }
}
