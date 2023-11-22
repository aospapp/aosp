/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.suite.checker.StatusCheckerResult.CheckStatus;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SystemServerStatusChecker} */
@RunWith(JUnit4.class)
public class SystemServerStatusCheckerTest {

    private SystemServerStatusChecker mChecker;
    private ITestDevice mMockDevice;

    @Before
    public void setUp() {
        mMockDevice = EasyMock.createMock(ITestDevice.class);
        mChecker =
                new SystemServerStatusChecker() {
                    @Override
                    protected long getCurrentTime() {
                        return 500L;
                    }
                };
    }

    /** Test that system checker pass if the pid of system checker does not change. */
    @Test
    public void testPidRemainUnchanged() throws Exception {
        EasyMock.expect(mMockDevice.executeShellCommand(EasyMock.eq("pidof system_server")))
                .andReturn("914")
                .times(2);
        EasyMock.replay(mMockDevice);
        assertEquals(CheckStatus.SUCCESS, mChecker.preExecutionCheck(mMockDevice).getStatus());
        assertEquals(CheckStatus.SUCCESS, mChecker.postExecutionCheck(mMockDevice).getStatus());
        EasyMock.verify(mMockDevice);
    }

    /** Test that system checker fail if the pid of system checker does change. */
    @Test
    public void testPidChanged() throws Exception {
        EasyMock.expect(mMockDevice.executeShellCommand(EasyMock.eq("pidof system_server")))
                .andReturn("914\n");
        EasyMock.expect(mMockDevice.executeShellCommand(EasyMock.eq("pidof system_server")))
                .andReturn("1024\n");
        EasyMock.expect(mMockDevice.getLastExpectedRebootTimeMillis()).andReturn(200L);
        EasyMock.replay(mMockDevice);
        assertEquals(CheckStatus.SUCCESS, mChecker.preExecutionCheck(mMockDevice).getStatus());
        StatusCheckerResult result = mChecker.postExecutionCheck(mMockDevice);
        assertEquals(CheckStatus.FAILED, result.getStatus());
        assertTrue(result.isBugreportNeeded());
        EasyMock.verify(mMockDevice);
    }

    /**
     * Test that if the pid changed but there was a Tradefed reboot, we still fail the checker just
     * in case, but don't collect a bugreport.
     */
    @Test
    public void testPidChanged_tfReboot() throws Exception {
        EasyMock.expect(mMockDevice.executeShellCommand(EasyMock.eq("pidof system_server")))
                .andReturn("914\n");
        EasyMock.expect(mMockDevice.executeShellCommand(EasyMock.eq("pidof system_server")))
                .andReturn("1024\n");
        // TF reboot was done
        EasyMock.expect(mMockDevice.getLastExpectedRebootTimeMillis()).andReturn(600L);
        EasyMock.replay(mMockDevice);
        assertEquals(CheckStatus.SUCCESS, mChecker.preExecutionCheck(mMockDevice).getStatus());
        StatusCheckerResult result = mChecker.postExecutionCheck(mMockDevice);
        assertEquals(CheckStatus.FAILED, result.getStatus());
        assertFalse(result.isBugreportNeeded());
        EasyMock.verify(mMockDevice);
    }

    /** Test that if the format of the pid is unexpected, we skip the system checker. */
    @Test
    public void testFailToGetPid() throws Exception {
        EasyMock.expect(mMockDevice.executeShellCommand(EasyMock.eq("pidof system_server")))
                .andReturn("not found\n");
        EasyMock.replay(mMockDevice);
        assertEquals(CheckStatus.SUCCESS, mChecker.preExecutionCheck(mMockDevice).getStatus());
        assertEquals(CheckStatus.SUCCESS, mChecker.postExecutionCheck(mMockDevice).getStatus());
        EasyMock.verify(mMockDevice);
    }

    /**
     * Test that if the pid output is null, we fail the current preExecution but skip post
     * execution.
     */
    @Test
    public void testPid_null() throws Exception {
        EasyMock.expect(mMockDevice.executeShellCommand(EasyMock.eq("pidof system_server")))
                .andReturn(null);
        EasyMock.replay(mMockDevice);
        assertEquals(CheckStatus.FAILED, mChecker.preExecutionCheck(mMockDevice).getStatus());
        assertEquals(CheckStatus.SUCCESS, mChecker.postExecutionCheck(mMockDevice).getStatus());
        EasyMock.verify(mMockDevice);
    }
}
