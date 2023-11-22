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
package com.android.tradefed.suite.checker;

import com.android.tradefed.suite.checker.UserChecker.DeviceUserState;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.suite.checker.StatusCheckerResult.CheckStatus;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Unit tests for {@link UserChecker} */
@RunWith(JUnit4.class)
public class UserCheckerTest {
    @Test
    public void testNoWarningsIsSuccess() throws Exception {
        UserChecker checker = new UserChecker();

        ITestDevice preDevice =
                mockDeviceUserState(
                        /* userIds=        */ new Integer[] {0},
                        /* runningUsers= */ new Integer[] {0},
                        /* currentUser=  */ 0);
        assertEquals(CheckStatus.SUCCESS, checker.preExecutionCheck(preDevice).getStatus());

        ITestDevice postDevice =
                mockDeviceUserState(
                        /* userIds=        */ new Integer[] {0},
                        /* runningUsers= */ new Integer[] {0},
                        /* currentUser=  */ 0);
        assertEquals(CheckStatus.SUCCESS, checker.postExecutionCheck(postDevice).getStatus());
    }

    @Test
    /** Returns FAILED in the precessense of errors */
    public void testAllErrorsIsFailed() throws Exception {
        UserChecker checker = new UserChecker();

        ITestDevice preDevice =
                mockDeviceUserState(
                        /* userIds=        */ new Integer[] {0, 10, 11},
                        /* runningUsers= */ new Integer[] {0, 10},
                        /* currentUser=  */ 10);
        assertEquals(CheckStatus.SUCCESS, checker.preExecutionCheck(preDevice).getStatus());

        // User12 created, User11 deleted, User10 stopped, currentUser changed
        ITestDevice postDevice =
                mockDeviceUserState(
                        /* userIds=        */ new Integer[] {0, 10, 12},
                        /* runningUsers= */ new Integer[] {0},
                        /* currentUser=  */ 0);
        assertEquals(CheckStatus.FAILED, checker.postExecutionCheck(postDevice).getStatus());
    }

    @Test
    public void testSwitchToExistingOrCreateUserType() throws Exception {
        UserChecker checker = new UserChecker();
        OptionSetter mOptionSetter = new OptionSetter(checker);
        mOptionSetter.setOptionValue("user-type", "secondary");
        ITestDevice device =
                mockDeviceUserState(
                        /* userIds=        */ new Integer[] {0},
                        /* runningUsers= */ new Integer[] {0},
                        /* currentUser=  */ 0);

        when(device.getCurrentUser()).thenReturn(0);
        mockListUsers(device, new Integer[] {0});
        when(device.createUserNoThrow(UserChecker.DEFAULT_NAME)).thenReturn(10);
        when(device.getCurrentUser()).thenReturn(0);
        mockListUsers(device, new Integer[] {0, 10});
        when(device.isUserSecondary(10)).thenReturn(true);
        when(device.switchUser(10)).thenReturn(true);

        StatusCheckerResult result = checker.preExecutionCheck(device);
        assertEquals(CheckStatus.SUCCESS, result.getStatus());
        verify(device, times(1)).switchUser(10);
    }

    @Test
    public void testSwitchToSecondaryUserCreateNewFail() throws Exception {
        UserChecker checker = new UserChecker();
        OptionSetter mOptionSetter = new OptionSetter(checker);
        mOptionSetter.setOptionValue("user-type", "secondary");
        ITestDevice device =
                mockDeviceUserState(
                        /* userIds=        */ new Integer[] {0},
                        /* runningUsers= */ new Integer[] {0},
                        /* currentUser=  */ 0);

        when(device.getCurrentUser()).thenReturn(0);
        mockListUsers(device, new Integer[] {0});
        when(device.createUserNoThrow(UserChecker.DEFAULT_NAME)).thenReturn(-1);

        StatusCheckerResult result = checker.preExecutionCheck(device);
        assertEquals(CheckStatus.FAILED, result.getStatus());
        verify(device, times(1)).createUserNoThrow(UserChecker.DEFAULT_NAME);
    }

    @Test
    public void testFindRemovedUsers() throws Exception {
        DeviceUserState preState =
                getMockedUserState(
                        /* userIds=        */ new Integer[] {0, 10},
                        /* runningUsers= */ new Integer[] {0, 10},
                        /* currentUser=  */ 0);
        DeviceUserState postState =
                getMockedUserState(
                        /* userIds=        */ new Integer[] {0},
                        /* runningUsers= */ new Integer[] {0},
                        /* currentUser=  */ 0);

        assertArrayEquals(new Integer[] {10}, preState.findRemovedUsers(postState).toArray());
    }

    @Test
    public void testFindAddedUsers() throws Exception {
        DeviceUserState preState =
                getMockedUserState(
                        /* userIds=        */ new Integer[] {0},
                        /* runningUsers= */ new Integer[] {0},
                        /* currentUser=  */ 0);
        DeviceUserState postState =
                getMockedUserState(
                        /* userIds=        */ new Integer[] {0, 10},
                        /* runningUsers= */ new Integer[] {0},
                        /* currentUser=  */ 0);

        assertArrayEquals(new Integer[] {10}, preState.findAddedUsers(postState).toArray());
    }

    @Test
    public void testCurrentUserChanged() throws Exception {
        DeviceUserState preState =
                getMockedUserState(
                        /* userIds=        */ new Integer[] {0, 10},
                        /* runningUsers= */ new Integer[] {0, 10},
                        /* currentUser=  */ 10);
        DeviceUserState postState =
                getMockedUserState(
                        /* userIds=        */ new Integer[] {0, 10},
                        /* runningUsers= */ new Integer[] {0, 10},
                        /* currentUser=  */ 0);

        assertEquals(true, preState.currentUserChanged(postState));
    }

    @Test
    public void testfindStartedUsers() throws Exception {
        DeviceUserState preState =
                getMockedUserState(
                        /* userIds=        */ new Integer[] {0, 10},
                        /* runningUsers= */ new Integer[] {0},
                        /* currentUser=  */ 0);
        DeviceUserState postState =
                getMockedUserState(
                        /* userIds=        */ new Integer[] {0, 10},
                        /* runningUsers= */ new Integer[] {0, 10},
                        /* currentUser=  */ 0);

        assertArrayEquals(new Integer[] {10}, preState.findStartedUsers(postState).toArray());
        assertArrayEquals(new Integer[] {}, preState.findStoppedUsers(postState).toArray());
    }

    @Test
    public void testFindStopedUsers() throws Exception {
        DeviceUserState preState =
                getMockedUserState(
                        /* userIds=        */ new Integer[] {0, 10},
                        /* runningUsers= */ new Integer[] {0, 10},
                        /* currentUser=  */ 0);
        DeviceUserState postState =
                getMockedUserState(
                        /* userIds=        */ new Integer[] {0, 10},
                        /* runningUsers= */ new Integer[] {0},
                        /* currentUser=  */ 0);

        assertArrayEquals(new Integer[] {}, preState.findStartedUsers(postState).toArray());
        assertArrayEquals(new Integer[] {10}, preState.findStoppedUsers(postState).toArray());
    }

    // TEST HELPERS

    /** Return an instantiated DeviceUserState which was mocked. */
    private DeviceUserState getMockedUserState(
            Integer[] userIds, Integer[] runningUsers, int currentUser) throws Exception {
        ITestDevice device = mockDeviceUserState(userIds, runningUsers, currentUser);
        return new UserChecker.DeviceUserState(device);
    }

    /** Return a device with the user state calls mocked. */
    private ITestDevice mockDeviceUserState(
            Integer[] userIds, Integer[] runningUsers, int currentUser) throws Exception {
        HashSet<Integer> runningUsersSet = new HashSet<Integer>(Arrays.asList(runningUsers));
        ITestDevice device = mock(ITestDevice.class);
        when(device.getCurrentUser()).thenReturn(currentUser);
        mockListUsers(device, userIds);
        for (int userId : userIds) {
            when(device.isUserRunning(userId)).thenReturn(runningUsersSet.contains(userId));
        }

        return device;
    }

    private void mockListUsers(ITestDevice device, Integer[] userIds) throws Exception {
        when(device.listUsers()).thenReturn(new ArrayList<Integer>(Arrays.asList(userIds)));
    }
}
