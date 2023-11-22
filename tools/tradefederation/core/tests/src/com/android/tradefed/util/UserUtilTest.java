/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tradefed.util;

import com.android.tradefed.util.UserUtil.UserType;

import java.util.Arrays;
import java.util.ArrayList;

import static org.junit.Assert.fail;

import com.android.tradefed.device.ITestDevice;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/** Unit tests for {@link UserChecker} */
@RunWith(JUnit4.class)
public class UserUtilTest {
    @Test
    public void testSwitchToUserSystemSuccess() throws Exception {
        int currentUser = 12;

        ITestDevice device = mock(ITestDevice.class);
        when(device.switchUser(UserUtil.USER_SYSTEM)).thenReturn(true);

        UserUtil.switchToUserType(device, UserType.SYSTEM);
        verify(device, times(1)).switchUser(UserUtil.USER_SYSTEM);
    }

    @Test
    public void testSwitchToUserSystemFail() throws Exception {
        int currentUser = 12;

        ITestDevice device = mock(ITestDevice.class);
        when(device.switchUser(UserUtil.USER_SYSTEM)).thenReturn(false);

        try {
            UserUtil.switchToUserType(device, UserType.SYSTEM);
            fail();
        } catch (UserUtil.UserSwitchFailedException _expected) {
        }
        verify(device, times(1)).switchUser(UserUtil.USER_SYSTEM);
    }

    @Test
    public void testSwitchToSecondaryUserCurrent() throws Exception {
        int currentUser = 10;

        ITestDevice device = mock(ITestDevice.class);
        when(device.getCurrentUser()).thenReturn(currentUser);
        when(device.isUserSecondary(currentUser)).thenReturn(true);

        UserUtil.switchToUserType(device, UserUtil.UserType.SECONDARY);
        verify(device, never()).switchUser(currentUser);
    }

    @Test
    public void testSwitchToSecondaryUserExists() throws Exception {
        ITestDevice device = mock(ITestDevice.class);
        when(device.getCurrentUser()).thenReturn(0);
        mockListUsers(device, new Integer[] {0, 10});
        when(device.isUserSecondary(10)).thenReturn(true);
        when(device.switchUser(10)).thenReturn(true);

        UserUtil.switchToUserType(device, UserUtil.UserType.SECONDARY);
        verify(device, times(1)).switchUser(10);
    }

    @Test
    /** Validate that invalid user types will be skipped as secondaries. */
    public void testSwitchToSecondaryUserWithInvalid() throws Exception {
        ITestDevice device = mock(ITestDevice.class);
        when(device.getCurrentUser()).thenReturn(0);
        mockListUsers(device, new Integer[] {0, 10, 11, 12});
        when(device.isUserSecondary(10)).thenReturn(false);
        when(device.isUserSecondary(11)).thenReturn(false);
        when(device.isUserSecondary(12)).thenReturn(true);
        when(device.switchUser(12)).thenReturn(true);

        UserUtil.switchToUserType(device, UserUtil.UserType.SECONDARY);
        verify(device, times(1)).switchUser(12);
    }

    @Test
    public void testSwitchToPrimaryUserNonSystem() throws Exception {
        ITestDevice device = mock(ITestDevice.class);
        when(device.getCurrentUser()).thenReturn(0);
        when(device.getPrimaryUserId()).thenReturn(10);
        when(device.switchUser(10)).thenReturn(true);

        UserUtil.switchToUserType(device, UserUtil.UserType.PRIMARY);
        verify(device, times(1)).switchUser(10);
    }

    // Helpers

    private void mockListUsers(ITestDevice device, Integer[] userIds) throws Exception {
        when(device.listUsers()).thenReturn(new ArrayList<Integer>(Arrays.asList(userIds)));
    }
}
