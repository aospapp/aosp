/*
 * Copyright (C) 2010 The Android Open Source Project
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tradefed.build.DeviceBuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.device.DeviceAllocationState;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceUnresponsiveException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.host.IHostOptions;
import com.android.tradefed.targetprep.IDeviceFlasher.UserDataFlashOption;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.RunUtil;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.Semaphore;

/** Unit tests for {@link DeviceFlashPreparer}. */
@RunWith(JUnit4.class)
public class DeviceFlashPreparerTest {

    private IDeviceFlasher mMockFlasher;
    private DeviceFlashPreparer mDeviceFlashPreparer;
    private ITestDevice mMockDevice;
    private IDeviceBuildInfo mMockBuildInfo;
    private IHostOptions mMockHostOptions;
    private File mTmpDir;

    @Before
    public void setUp() throws Exception {
        mMockFlasher = EasyMock.createMock(IDeviceFlasher.class);
        mMockDevice = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn("foo").anyTimes();
        EasyMock.expect(mMockDevice.getOptions()).andReturn(new TestDeviceOptions()).anyTimes();
        mMockBuildInfo = new DeviceBuildInfo("0", "");
        mMockBuildInfo.setDeviceImageFile(new File("foo"), "0");
        mMockBuildInfo.setBuildFlavor("flavor");
        mMockHostOptions = EasyMock.createMock(IHostOptions.class);
        mDeviceFlashPreparer = new DeviceFlashPreparer() {
            @Override
            protected IDeviceFlasher createFlasher(ITestDevice device) {
                return mMockFlasher;
            }

            @Override
            int getDeviceBootPollTimeMs() {
                return 100;
            }

            @Override
            IHostOptions getHostOptions() {
                return mMockHostOptions;
            }
        };
        // Reset default settings
        mDeviceFlashPreparer.setConcurrentFlashSettings(null, null, true);
        mDeviceFlashPreparer.setDeviceBootTime(100);
        // expect this call
        mMockFlasher.setUserDataFlashOption(UserDataFlashOption.FLASH);
        mTmpDir = FileUtil.createTempDir("tmp");
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.recursiveDelete(mTmpDir);
    }

    /** Simple normal case test for {@link DeviceFlashPreparer#setUp(ITestDevice, IBuildInfo)}. */
    @Test
    public void testSetup() throws Exception {
        doSetupExpectations();
        EasyMock.replay(mMockFlasher, mMockDevice);
        mDeviceFlashPreparer.setUp(mMockDevice, mMockBuildInfo);
        EasyMock.verify(mMockFlasher, mMockDevice);
    }

    /**
     * Set EasyMock expectations for a normal setup call
     */
    private void doSetupExpectations() throws TargetSetupError, DeviceNotAvailableException {
        mMockDevice.setRecoveryMode(RecoveryMode.ONLINE);
        mMockFlasher.overrideDeviceOptions(mMockDevice);
        mMockFlasher.setForceSystemFlash(false);
        mMockFlasher.setDataWipeSkipList(Arrays.asList(new String[]{}));
        mMockFlasher.flash(mMockDevice, mMockBuildInfo);
        mMockFlasher.setWipeTimeout(EasyMock.anyLong());
        mMockDevice.waitForDeviceOnline();
        EasyMock.expect(mMockDevice.enableAdbRoot()).andStubReturn(Boolean.TRUE);
        mMockDevice.setDate(null);
        EasyMock.expect(mMockDevice.getBuildId()).andReturn(mMockBuildInfo.getBuildId());
        EasyMock.expect(mMockDevice.getBuildFlavor()).andReturn(mMockBuildInfo.getBuildFlavor());
        EasyMock.expect(mMockDevice.isEncryptionSupported()).andStubReturn(Boolean.TRUE);
        EasyMock.expect(mMockDevice.isDeviceEncrypted()).andStubReturn(Boolean.FALSE);
        mMockDevice.clearLogcat();
        mMockDevice.waitForDeviceAvailable(EasyMock.anyLong());
        mMockDevice.setRecoveryMode(RecoveryMode.AVAILABLE);
        mMockDevice.postBootSetup();
    }

    /**
     * Test {@link DeviceFlashPreparer#setUp(ITestDevice, IBuildInfo)} when a non IDeviceBuildInfo
     * type is provided.
     */
    @Test
    public void testSetUp_nonDevice() throws Exception {
        try {
            mDeviceFlashPreparer.setUp(mMockDevice, EasyMock.createMock(IBuildInfo.class));
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    /** Test {@link DeviceFlashPreparer#setUp(ITestDevice, IBuildInfo)} when build does not boot. */
    @Test
    public void testSetup_buildError() throws Exception {
        mMockDevice.setRecoveryMode(RecoveryMode.ONLINE);
        mMockFlasher.overrideDeviceOptions(mMockDevice);
        mMockFlasher.setForceSystemFlash(false);
        mMockFlasher.setDataWipeSkipList(Arrays.asList(new String[]{}));
        mMockFlasher.flash(mMockDevice, mMockBuildInfo);
        mMockFlasher.setWipeTimeout(EasyMock.anyLong());
        mMockDevice.waitForDeviceOnline();
        EasyMock.expect(mMockDevice.enableAdbRoot()).andStubReturn(Boolean.TRUE);
        mMockDevice.setDate(null);
        EasyMock.expect(mMockDevice.getBuildId()).andReturn(mMockBuildInfo.getBuildId());
        EasyMock.expect(mMockDevice.getBuildFlavor()).andReturn(mMockBuildInfo.getBuildFlavor());
        EasyMock.expect(mMockDevice.isEncryptionSupported()).andStubReturn(Boolean.TRUE);
        EasyMock.expect(mMockDevice.isDeviceEncrypted()).andStubReturn(Boolean.FALSE);
        mMockDevice.clearLogcat();
        mMockDevice.waitForDeviceAvailable(EasyMock.anyLong());
        EasyMock.expectLastCall().andThrow(new DeviceUnresponsiveException("foo", "fakeserial"));
        mMockDevice.setRecoveryMode(RecoveryMode.AVAILABLE);
        EasyMock.expect(mMockDevice.getDeviceDescriptor()).andReturn(
                new DeviceDescriptor("SERIAL", false, DeviceAllocationState.Available, "unknown",
                        "unknown", "unknown", "unknown", "unknown"));
        EasyMock.replay(mMockFlasher, mMockDevice);
        try {
            mDeviceFlashPreparer.setUp(mMockDevice, mMockBuildInfo);
            fail("DeviceFlashPreparerTest not thrown");
        } catch (BuildError e) {
            // expected; use the general version to make absolutely sure that
            // DeviceFailedToBootError properly masquerades as a BuildError.
            assertTrue(e instanceof DeviceFailedToBootError);
        }
        EasyMock.verify(mMockFlasher, mMockDevice);
    }

    /** Ensure that the flasher instance limiting machinery is working as expected. */
    @Test
    public void testFlashLimit() throws Exception {
        final DeviceFlashPreparer dfp = mDeviceFlashPreparer;
        try {
            Thread waiter = new Thread() {
                @Override
                public void run() {
                    dfp.takeFlashingPermit();
                    dfp.returnFlashingPermit();
                }
            };
            EasyMock.expect(mMockHostOptions.getConcurrentFlasherLimit()).andReturn(1).anyTimes();
            EasyMock.replay(mMockHostOptions);
            dfp.setConcurrentFlashSettings(1, new Semaphore(1), true);
            // take the permit; the next attempt to take the permit should block
            dfp.takeFlashingPermit();
            assertFalse(dfp.getConcurrentFlashLock().hasQueuedThreads());

            waiter.start();
            RunUtil.getDefault().sleep(100);  // Thread start should take <100ms
            assertTrue("Invalid state: waiter thread is not alive", waiter.isAlive());
            assertTrue("No queued threads", dfp.getConcurrentFlashLock().hasQueuedThreads());

            dfp.returnFlashingPermit();
            RunUtil.getDefault().sleep(100);  // Thread start should take <100ms
            assertFalse("Unexpected queued threads",
                    dfp.getConcurrentFlashLock().hasQueuedThreads());

            waiter.join(1000);
            assertFalse("waiter thread has not returned", waiter.isAlive());
        } finally {
            // Attempt to reset concurrent flash settings to defaults
            dfp.setConcurrentFlashSettings(null, null, true);
        }
    }

    /** Ensure that the flasher limiting respects {@link IHostOptions}. */
    @Test
    public void testFlashLimit_withHostOptions() throws Exception {
        final DeviceFlashPreparer dfp = mDeviceFlashPreparer;
        try {
            Thread waiter = new Thread() {
                @Override
                public void run() {
                    dfp.takeFlashingPermit();
                    dfp.returnFlashingPermit();
                }
            };
            EasyMock.expect(mMockHostOptions.getConcurrentFlasherLimit()).andReturn(1).anyTimes();
            EasyMock.replay(mMockHostOptions);
            // take the permit; the next attempt to take the permit should block
            dfp.takeFlashingPermit();
            assertFalse(dfp.getConcurrentFlashLock().hasQueuedThreads());

            waiter.start();
            RunUtil.getDefault().sleep(100);  // Thread start should take <100ms
            assertTrue("Invalid state: waiter thread is not alive", waiter.isAlive());
            assertTrue("No queued threads", dfp.getConcurrentFlashLock().hasQueuedThreads());

            dfp.returnFlashingPermit();
            RunUtil.getDefault().sleep(100);  // Thread start should take <100ms
            assertFalse("Unexpected queued threads",
                    dfp.getConcurrentFlashLock().hasQueuedThreads());

            waiter.join(1000);
            assertFalse("waiter thread has not returned", waiter.isAlive());
            EasyMock.verify(mMockHostOptions);
        } finally {
            // Attempt to reset concurrent flash settings to defaults
            dfp.setConcurrentFlashSettings(null, null, true);
        }
    }

    /** Ensure that the flasher instance limiting machinery is working as expected. */
    @Test
    public void testUnlimitedFlashLimit() throws Exception {
        final DeviceFlashPreparer dfp = mDeviceFlashPreparer;
        try {
            Thread waiter = new Thread() {
                @Override
                public void run() {
                    dfp.takeFlashingPermit();
                    dfp.returnFlashingPermit();
                }
            };
            dfp.setConcurrentFlashSettings(null, null, true);
            // take a permit; the next attempt to take the permit should proceed without blocking
            dfp.takeFlashingPermit();
            assertNull("Flash lock is non-null", dfp.getConcurrentFlashLock());

            waiter.start();
            RunUtil.getDefault().sleep(100);  // Thread start should take <100ms
            Thread.State waiterState = waiter.getState();
            assertTrue("Invalid state: waiter thread hasn't started",
                    waiter.isAlive() || Thread.State.TERMINATED.equals(waiterState));
            assertNull("Flash lock is non-null", dfp.getConcurrentFlashLock());

            dfp.returnFlashingPermit();
            RunUtil.getDefault().sleep(100);  // Thread start should take <100ms
            assertNull("Flash lock is non-null", dfp.getConcurrentFlashLock());

            waiter.join(1000);
            assertFalse("waiter thread has not returned", waiter.isAlive());
        } finally {
            // Attempt to reset concurrent flash settings to defaults
            dfp.setConcurrentFlashSettings(null, null, true);
        }
    }
}
