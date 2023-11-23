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

package com.android.tradefed.targetprep;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link SwitchUserTargetPreparer}. */
@RunWith(JUnit4.class)
public class SwitchUserTargetPreparerTest {
    private static final int USER_SYSTEM = 0; // From the UserHandle class.

    @Mock private ITestDevice mMockDevice;

    private SwitchUserTargetPreparer mSwitchUserTargetPreparer;
    private OptionSetter mOptionSetter;

    @Before
    public void setUp() throws ConfigurationException {
        MockitoAnnotations.initMocks(this);
        mSwitchUserTargetPreparer = new SwitchUserTargetPreparer();
        mOptionSetter = new OptionSetter(mSwitchUserTargetPreparer);
    }

    @Test
    public void testSetUpRunAsPrimary_ifAlreadyInPrimary_switchToPrimary()
            throws DeviceNotAvailableException, TargetSetupError, ConfigurationException {
        // setup
        mockUsers(/* primaryUserId= */ 11, /* currentUserId= */ 11);
        mOptionSetter.setOptionValue("user-type", "primary");

        // act
        mSwitchUserTargetPreparer.setUp(mMockDevice, /* buildInfo= */ null);

        // assert
        verify(mMockDevice, times(1)).switchUser(11);
    }

    @Test
    public void testSetUpRunAsSystem_ifAlreadyInSystem_switchToSystem()
            throws DeviceNotAvailableException, TargetSetupError, ConfigurationException {
        // setup
        mockUsers(/* primaryUserId= */ 11, /* currentUserId= */ USER_SYSTEM);
        mOptionSetter.setOptionValue("user-type", "system");

        // act
        mSwitchUserTargetPreparer.setUp(mMockDevice, /* buildInfo= */ null);

        // assert
        verify(mMockDevice, times(1)).switchUser(0);
    }

    @Test
    public void testSetUpRunAsPrimary_ifNotInPrimary_switchToPrimary()
            throws DeviceNotAvailableException, TargetSetupError, ConfigurationException {
        // setup
        mockUsers(/* primaryUserId= */ 10, /* currentUserId= */ 11);
        mOptionSetter.setOptionValue("user-type", "primary");

        // act
        mSwitchUserTargetPreparer.setUp(mMockDevice, /* buildInfo= */ null);

        // assert it switches to primary in setUp
        verify(mMockDevice, times(1)).switchUser(10);
    }

    @Test
    public void testSetUpRunAsSystem_ifNotInSystem_switchToSystem()
            throws DeviceNotAvailableException, TargetSetupError, ConfigurationException {
        // setup
        mockUsers(/* primaryUserId= */ 10, /* currentUserId= */ 11);
        mOptionSetter.setOptionValue("user-type", "system");

        // act
        mSwitchUserTargetPreparer.setUp(mMockDevice, /* buildInfo= */ null);

        // assert it switches to primary in setUp
        verify(mMockDevice, times(1)).switchUser(USER_SYSTEM);
    }

    @Test
    public void testTearDown_ifStartedInSecondary_switchesBackToSecondary()
            throws DeviceNotAvailableException, TargetSetupError, ConfigurationException {
        // setup
        mockUsers(/* primaryUserId= */ 0, /* currentUserId= */ 10);
        mOptionSetter.setOptionValue("user-type", "primary");

        // first switches to primary
        mSwitchUserTargetPreparer.setUp(mMockDevice, /* buildInfo= */ null);
        verify(mMockDevice, times(1)).switchUser(0);

        // then switches back to secondary
        mSwitchUserTargetPreparer.tearDown(mMockDevice, /* buildInfo= */ null, null);
        verify(mMockDevice, times(1)).switchUser(10);
    }

    @Test
    public void testSetUp_ifNoSwitchToSpecified_noUserSwitch()
            throws DeviceNotAvailableException, TargetSetupError {
        // setup
        mockUsers(/* primaryUserId= */ 0, /* currentUserId= */ 10);

        // act
        mSwitchUserTargetPreparer.setUp(mMockDevice, /* buildInfo= */ null);

        // assert
        verify(mMockDevice, never()).switchUser(anyInt());
    }

    @Test
    public void testSetUp_ifSwitchFails_throwsTargetSetupError()
            throws DeviceNotAvailableException, ConfigurationException {
        // setup
        mockUsers(/* primaryUserId= */ 0, /* currentUserId= */ 11);
        mOptionSetter.setOptionValue("user-type", "primary");
        when(mMockDevice.switchUser(0)).thenReturn(false);

        // act
        try {
            mSwitchUserTargetPreparer.setUp(mMockDevice, /* buildInfo= */ null);
            fail("Should have thrown TargetSetupError exception.");
        } catch (TargetSetupError e) {
            // do nothing
        }
    }

    private void mockUsers(int primaryUserId, int currentUserId)
            throws DeviceNotAvailableException {
        when(mMockDevice.getCurrentUser()).thenReturn(currentUserId);
        when(mMockDevice.getPrimaryUserId()).thenReturn(primaryUserId);
        when(mMockDevice.switchUser(anyInt())).thenReturn(true);
    }
}
