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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.NativeDevice;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/** Unit tests for {@link CreateUserPreparer}. */
@RunWith(JUnit4.class)
public class CreateUserPreparerTest {

    private CreateUserPreparer mPreparer;
    private ITestDevice mMockDevice;

    @Before
    public void setUp() {
        mMockDevice = Mockito.mock(ITestDevice.class);
        mPreparer = new CreateUserPreparer();
    }

    @Test
    public void testSetUp_tearDown() throws Exception {
        doReturn(10).when(mMockDevice).getCurrentUser();
        doReturn(5).when(mMockDevice).createUser(Mockito.any());
        doReturn(true).when(mMockDevice).switchUser(5);
        mPreparer.setUp(mMockDevice, null);

        doReturn(true).when(mMockDevice).removeUser(5);
        doReturn(true).when(mMockDevice).switchUser(10);
        mPreparer.tearDown(mMockDevice, null, null);
    }

    @Test
    public void testSetUp_tearDown_noCurrent() throws Exception {
        doReturn(NativeDevice.INVALID_USER_ID).when(mMockDevice).getCurrentUser();
        try {
            mPreparer.setUp(mMockDevice, null);
            fail("Should have thrown an exception.");
        } catch (TargetSetupError expected) {
            // Expected
        }

        mPreparer.tearDown(mMockDevice, null, null);
        verify(mMockDevice, never()).removeUser(Mockito.anyInt());
        verify(mMockDevice, never()).switchUser(Mockito.anyInt());
    }

    @Test
    public void testSetUp_failed() throws Exception {
        doThrow(new IllegalStateException("failed to create"))
                .when(mMockDevice)
                .createUser(Mockito.any());

        try {
            mPreparer.setUp(mMockDevice, null);
            fail("Should have thrown an exception.");
        } catch (TargetSetupError expected) {
            // Expected
        }
    }

    @Test
    public void testTearDown_only() throws Exception {
        mPreparer.tearDown(mMockDevice, null, null);

        verify(mMockDevice, never()).removeUser(Mockito.anyInt());
    }
}
