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

package com.android.systemui.car.qc;

import static android.os.UserManager.SWITCHABILITY_STATUS_OK;
import static android.os.UserManager.SWITCHABILITY_STATUS_USER_SWITCH_DISALLOWED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.car.user.CarUserManager;
import android.car.user.UserCreationResult;
import android.car.user.UserSwitchResult;
import android.car.util.concurrent.AsyncFuture;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.UserManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.car.qc.QCItem;
import com.android.car.qc.QCList;
import com.android.car.qc.QCRow;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarSystemUiTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class ProfileSwitcherTest extends SysuiTestCase {

    private ProfileSwitcher mProfileSwitcher;
    private List<UserInfo> mAliveUsers = new ArrayList<>();

    @Mock
    private UserManager mUserManager;
    @Mock
    private CarUserManager mCarUserManager;

    @Before
    public void setUp() throws ExecutionException, InterruptedException, TimeoutException {
        MockitoAnnotations.initMocks(this);

        when(mUserManager.getAliveUsers()).thenReturn(mAliveUsers);
        when(mUserManager.getUserSwitchability(any())).thenReturn(SWITCHABILITY_STATUS_OK);

        AsyncFuture<UserSwitchResult> switchResultFuture = mock(AsyncFuture.class);
        UserSwitchResult switchResult = mock(UserSwitchResult.class);
        when(switchResult.isSuccess()).thenReturn(true);
        when(switchResultFuture.get(anyLong(), any())).thenReturn(switchResult);
        when(mCarUserManager.switchUser(anyInt())).thenReturn(switchResultFuture);

        mProfileSwitcher = new ProfileSwitcher(mContext, mUserManager, mCarUserManager);
    }

    @Test
    public void switchNotAllowed_returnsOnlyCurrentUser() {
        when(mUserManager.getUserSwitchability(any()))
                .thenReturn(SWITCHABILITY_STATUS_USER_SWITCH_DISALLOWED);
        UserInfo currentUser = generateUser(ActivityManager.getCurrentUser(),
                "Current User", /* supportsSwitch= */ true, /* isGuest= */ false);
        mAliveUsers.add(currentUser);
        when(mUserManager.getUserInfo(ActivityManager.getCurrentUser())).thenReturn(currentUser);
        UserInfo otherUser = generateUser(1001, "Other User", /* supportsSwitch= */ true,
                /* isGuest= */ false);
        mAliveUsers.add(otherUser);
        QCList list = getQCList();
        assertThat(list.getRows().size()).isEqualTo(1);
        assertThat(list.getRows().get(0).getTitle()).isEqualTo("Current User");
    }

    @Test
    public void switchAllowed_usersSwitchable_returnsAllRows() {
        UserInfo user1 = generateUser(1000, "User1", /* supportsSwitch= */ true,
                /* isGuest= */ false);
        UserInfo user2 = generateUser(1001, "User2", /* supportsSwitch= */ true,
                /* isGuest= */ false);
        mAliveUsers.add(user1);
        mAliveUsers.add(user2);
        QCList list = getQCList();
        // Expect four rows - one for each user, one for the guest user, and one for add user
        assertThat(list.getRows().size()).isEqualTo(4);
        assertThat(list.getRows().get(0).getTitle()).isEqualTo("User1");
        assertThat(list.getRows().get(1).getTitle()).isEqualTo("User2");
        assertThat(list.getRows().get(2).getTitle()).isEqualTo(
                mContext.getString(R.string.car_guest));
        assertThat(list.getRows().get(3).getTitle()).isEqualTo(
                mContext.getString(R.string.car_add_user));
    }

    @Test
    public void switchAllowed_userNotSwitchable_returnsValidRows() {
        UserInfo user1 = generateUser(1000, "User1", /* supportsSwitch= */ true,
                /* isGuest= */ false);
        UserInfo user2 = generateUser(1001, "User2", /* supportsSwitch= */ false,
                /* isGuest= */ false);
        mAliveUsers.add(user1);
        mAliveUsers.add(user2);
        QCList list = getQCList();
        // Expect three rows - one for the valid user, one for the guest user, and one for add user
        assertThat(list.getRows().size()).isEqualTo(3);
        assertThat(list.getRows().get(0).getTitle()).isEqualTo("User1");
        assertThat(list.getRows().get(1).getTitle()).isEqualTo(
                mContext.getString(R.string.car_guest));
        assertThat(list.getRows().get(2).getTitle()).isEqualTo(
                mContext.getString(R.string.car_add_user));
    }

    @Test
    public void switchAllowed_userGuest_returnsValidRows() {
        UserInfo user1 = generateUser(1000, "User1", /* supportsSwitch= */ true,
                /* isGuest= */ false);
        UserInfo user2 = generateUser(1001, "User2", /* supportsSwitch= */ true,
                /* isGuest= */ true);
        mAliveUsers.add(user1);
        mAliveUsers.add(user2);
        QCList list = getQCList();
        // Expect three rows - one for the valid user, one for the guest user, and one for add user
        assertThat(list.getRows().size()).isEqualTo(3);
        assertThat(list.getRows().get(0).getTitle()).isEqualTo("User1");
        assertThat(list.getRows().get(1).getTitle()).isEqualTo(
                mContext.getString(R.string.car_guest));
        assertThat(list.getRows().get(2).getTitle()).isEqualTo(
                mContext.getString(R.string.car_add_user));
    }

    @Test
    public void onUserPressed_triggersSwitch() {
        int currentUserId = 1000;
        int otherUserId = 1001;
        UserInfo user1 = generateUser(currentUserId, "User1", /* supportsSwitch= */ true,
                /* isGuest= */ false);
        UserInfo user2 = generateUser(otherUserId, "User2", /* supportsSwitch= */ true,
                /* isGuest= */ false);
        mAliveUsers.add(user1);
        mAliveUsers.add(user2);
        QCList list = getQCList();
        // Expect four rows - one for each user, one for the guest user, and one for add user
        assertThat(list.getRows().size()).isEqualTo(4);
        QCRow otherUserRow = list.getRows().get(1);
        otherUserRow.getActionHandler().onAction(otherUserRow, mContext, new Intent());
        verify(mCarUserManager).switchUser(otherUserId);
    }

    @Test
    public void onGuestPressed_createsAndSwitches()
            throws ExecutionException, InterruptedException, TimeoutException {
        int currentUserId = 1000;
        int guestUserId = 1001;
        AsyncFuture<UserCreationResult> createResultFuture = mock(AsyncFuture.class);
        when(createResultFuture.get(anyLong(), any())).thenReturn(null);
        when(mCarUserManager.createGuest(any())).thenReturn(createResultFuture);

        UserInfo guestUserInfo = mock(UserInfo.class);
        guestUserInfo.id = guestUserId;
        when(mUserManager.findCurrentGuestUser()).thenReturn(guestUserInfo);

        UserInfo user1 = generateUser(currentUserId, "User1", /* supportsSwitch= */ true,
                /* isGuest= */ false);
        mAliveUsers.add(user1);
        QCList list = getQCList();
        // Expect 3 rows - one for the user, one for the guest user, and one for add user
        assertThat(list.getRows().size()).isEqualTo(3);
        QCRow guestRow = list.getRows().get(1);
        guestRow.getActionHandler().onAction(guestRow, mContext, new Intent());
        verify(mCarUserManager).createGuest(any());
        verify(mCarUserManager).switchUser(guestUserId);
    }

    private QCList getQCList() {
        QCItem item = mProfileSwitcher.getQCItem();
        assertThat(item).isNotNull();
        assertThat(item instanceof QCList).isTrue();
        return (QCList) item;
    }

    private UserInfo generateUser(int id, String name, boolean supportsSwitch, boolean isGuest) {
        UserInfo info = mock(UserInfo.class);
        info.id = id;
        info.name = name;
        when(info.supportsSwitchToByUser()).thenReturn(supportsSwitch);
        when(info.isGuest()).thenReturn(isGuest);
        return info;
    }
}
