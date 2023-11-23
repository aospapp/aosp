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
package com.android.tradefed.invoker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IBuildProvider;
import com.android.tradefed.build.StubBuildProvider;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.DeviceConfigurationHolder;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.metric.AutoLogCollector;
import com.android.tradefed.device.metric.BaseDeviceMetricCollector;
import com.android.tradefed.device.metric.IMetricCollector;
import com.android.tradefed.device.metric.IMetricCollectorReceiver;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.result.CollectingTestListener;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.ITestLoggerReceiver;
import com.android.tradefed.targetprep.IHostCleaner;
import com.android.tradefed.targetprep.ITargetCleaner;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.multi.IMultiTargetPreparer;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.util.IDisableable;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link InvocationExecution}. Tests for each individual interface of
 * InvocationExecution, integration tests for orders or calls should be in {@link
 * TestInvocationTest}.
 */
@RunWith(JUnit4.class)
public class InvocationExecutionTest {
    private InvocationExecution mExec;
    private IInvocationContext mContext;
    private IConfiguration mConfig;
    private ITestInvocationListener mMockListener;
    private ITestDevice mMockDevice;

    @Before
    public void setUp() {
        mExec = new InvocationExecution();
        mContext = new InvocationContext();
        mConfig = new Configuration("test", "test");
        mMockListener = mock(ITestInvocationListener.class);
        mMockDevice = EasyMock.createMock(ITestDevice.class);
        // Reset the counters
        TestBaseMetricCollector.sTotalInit = 0;
        RemoteTestCollector.sTotalInit = 0;
    }

    /** Test class for a target preparer class that also do host cleaner. */
    public interface ITargetHostCleaner extends ITargetPreparer, IHostCleaner {}

    /**
     * Test that {@link InvocationExecution#doCleanUp(IInvocationContext, IConfiguration,
     * Throwable)} properly use {@link IDisableable} to let an object run.
     */
    @Test
    public void testCleanUp() throws Exception {
        DeviceConfigurationHolder holder = new DeviceConfigurationHolder("default");
        ITargetHostCleaner cleaner = EasyMock.createMock(ITargetHostCleaner.class);
        holder.addSpecificConfig(cleaner);
        mConfig.setDeviceConfig(holder);
        mContext.addAllocatedDevice("default", EasyMock.createMock(ITestDevice.class));
        EasyMock.expect(cleaner.isDisabled()).andReturn(false);
        EasyMock.expect(cleaner.isTearDownDisabled()).andReturn(false);
        cleaner.cleanUp(null, null);
        EasyMock.replay(cleaner);
        mExec.doCleanUp(mContext, mConfig, null);
        EasyMock.verify(cleaner);
    }

    /**
     * Test that {@link InvocationExecution#doCleanUp(IInvocationContext, IConfiguration,
     * Throwable)} properly use {@link IDisableable} to prevent an object from running.
     */
    @Test
    public void testCleanUp_disabled() throws Exception {
        DeviceConfigurationHolder holder = new DeviceConfigurationHolder("default");
        ITargetHostCleaner cleaner = EasyMock.createMock(ITargetHostCleaner.class);
        holder.addSpecificConfig(cleaner);
        mConfig.setDeviceConfig(holder);
        mContext.addAllocatedDevice("default", EasyMock.createMock(ITestDevice.class));
        EasyMock.expect(cleaner.isDisabled()).andReturn(true);
        // cleaner.isTearDownDisabled not expected, because isDisabled true stops || execution.
        // cleanUp call is not expected
        EasyMock.replay(cleaner);
        mExec.doCleanUp(mContext, mConfig, null);
        EasyMock.verify(cleaner);
    }

    /**
     * Test that {@link InvocationExecution#doCleanUp(IInvocationContext, IConfiguration,
     * Throwable)} properly use {@link IDisableable} isTearDownDisabled to prevent cleanup step.
     */
    @Test
    public void testCleanUp_tearDownDisabled() throws Exception {
        DeviceConfigurationHolder holder = new DeviceConfigurationHolder("default");
        ITargetHostCleaner cleaner = EasyMock.createMock(ITargetHostCleaner.class);
        holder.addSpecificConfig(cleaner);
        mConfig.setDeviceConfig(holder);
        mContext.addAllocatedDevice("default", EasyMock.createMock(ITestDevice.class));
        EasyMock.expect(cleaner.isDisabled()).andReturn(false);
        EasyMock.expect(cleaner.isTearDownDisabled()).andReturn(true);
        // cleanUp call is not expected
        EasyMock.replay(cleaner);
        mExec.doCleanUp(mContext, mConfig, null);
        EasyMock.verify(cleaner);
    }

    /**
     * Test {@link IRemoteTest} that also implements {@link IMetricCollectorReceiver} to test the
     * init behavior.
     */
    private static class RemoteTestCollector implements IRemoteTest, IMetricCollectorReceiver {

        private List<IMetricCollector> mCollectors;
        private static int sTotalInit = 0;

        @Override
        public void setMetricCollectors(List<IMetricCollector> collectors) {
            mCollectors = collectors;
        }

        @Override
        public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
            for (IMetricCollector collector : mCollectors) {
                collector.init(new InvocationContext(), new ITestInvocationListener() {});
                sTotalInit++;
            }
        }
    }

    public static class TestBaseMetricCollector extends BaseDeviceMetricCollector {
        public static int sTotalInit = 0;
        private boolean mFirstInit = true;

        @Override
        public ITestInvocationListener init(
                IInvocationContext context, ITestInvocationListener listener) {
            if (mFirstInit) {
                sTotalInit++;
                mFirstInit = false;
            } else {
                fail("Init should only be called once per instance.");
            }
            return super.init(context, listener);
        }
    }

    /**
     * Test that the collectors always run with the right context no matter where they are
     * (re)initialized.
     */
    @Test
    public void testRun_metricCollectors() throws Throwable {
        List<IRemoteTest> tests = new ArrayList<>();
        // First add an IMetricCollectorReceiver
        tests.add(new RemoteTestCollector());
        // Then a regular non IMetricCollectorReceiver
        tests.add(
                new IRemoteTest() {
                    @Override
                    public void run(ITestInvocationListener listener)
                            throws DeviceNotAvailableException {}
                });
        mConfig.setTests(tests);
        List<IMetricCollector> collectors = new ArrayList<>();
        collectors.add(new TestBaseMetricCollector());
        mConfig.setDeviceMetricCollectors(collectors);
        mExec.runTests(mContext, mConfig, mMockListener);
        // Init was called twice in total on the class, but only once per instance.
        assertEquals(2, TestBaseMetricCollector.sTotalInit);
    }

    /** Test that auto collectors are properly added. */
    @Test
    public void testRun_metricCollectors_auto() throws Throwable {
        List<IRemoteTest> tests = new ArrayList<>();
        // First add an IMetricCollectorReceiver
        RemoteTestCollector remoteTest = new RemoteTestCollector();
        tests.add(remoteTest);
        mConfig.setTests(tests);
        List<IMetricCollector> collectors = new ArrayList<>();
        mConfig.setDeviceMetricCollectors(collectors);

        Set<AutoLogCollector> specialCollectors = new HashSet<>();
        specialCollectors.add(AutoLogCollector.SCREENSHOT_ON_FAILURE);
        mConfig.getCommandOptions().setAutoLogCollectors(specialCollectors);

        mExec.runTests(mContext, mConfig, mMockListener);
        // Init was called twice in total on the class, but only once per instance.
        assertEquals(1, RemoteTestCollector.sTotalInit);
    }

    /**
     * Test the ordering of multi_pre_target_preparer/target_preparer/multi_target_preparer during
     * setup and tearDown.
     */
    @Test
    public void testDoSetup() throws Throwable {
        IMultiTargetPreparer stub1 = mock(IMultiTargetPreparer.class);
        IMultiTargetPreparer stub2 = mock(IMultiTargetPreparer.class);
        IMultiTargetPreparer stub3 = mock(IMultiTargetPreparer.class);
        IMultiTargetPreparer stub4 = mock(IMultiTargetPreparer.class);
        mConfig.setMultiPreTargetPreparers(Arrays.asList(stub1, stub2));
        mConfig.setMultiTargetPreparers(Arrays.asList(stub3, stub4));

        ITargetCleaner cleaner = mock(ITargetCleaner.class);
        IDeviceConfiguration holder = new DeviceConfigurationHolder("default");
        holder.addSpecificConfig(cleaner);
        mConfig.setDeviceConfig(holder);
        mContext.addAllocatedDevice("default", mock(ITestDevice.class));

        mExec.doSetup(mContext, mConfig, mMockListener);
        mExec.doTeardown(mContext, mConfig, null, null);

        // Pre multi preparers are always called before.
        InOrder inOrder = Mockito.inOrder(stub1, stub2, stub3, stub4, cleaner);
        inOrder.verify(stub1).isDisabled();
        inOrder.verify(stub1).setUp(mContext);
        inOrder.verify(stub2).isDisabled();
        inOrder.verify(stub2).setUp(mContext);

        inOrder.verify(cleaner).setUp(Mockito.any(), Mockito.any());

        inOrder.verify(stub3).isDisabled();
        inOrder.verify(stub3).setUp(mContext);
        inOrder.verify(stub4).isDisabled();
        inOrder.verify(stub4).setUp(mContext);

        // tear down
        inOrder.verify(stub4).isDisabled();
        inOrder.verify(stub4).tearDown(mContext, null);
        inOrder.verify(stub3).isDisabled();
        inOrder.verify(stub3).tearDown(mContext, null);

        inOrder.verify(cleaner).tearDown(Mockito.any(), Mockito.any(), Mockito.any());

        inOrder.verify(stub2).isDisabled();
        inOrder.verify(stub2).tearDown(mContext, null);
        inOrder.verify(stub1).isDisabled();
        inOrder.verify(stub1).tearDown(mContext, null);
    }

    /** Ensure that during tear down the original exception is kept. */
    @Test
    public void testDoTearDown() throws Throwable {
        IMultiTargetPreparer stub1 = mock(IMultiTargetPreparer.class);
        IMultiTargetPreparer stub2 = mock(IMultiTargetPreparer.class);
        IMultiTargetPreparer stub3 = mock(IMultiTargetPreparer.class);
        IMultiTargetPreparer stub4 = mock(IMultiTargetPreparer.class);
        mConfig.setMultiPreTargetPreparers(Arrays.asList(stub1, stub2));
        mConfig.setMultiTargetPreparers(Arrays.asList(stub3, stub4));

        ITargetCleaner cleaner = mock(ITargetCleaner.class);
        IDeviceConfiguration holder = new DeviceConfigurationHolder("default");
        holder.addSpecificConfig(cleaner);
        mConfig.setDeviceConfig(holder);
        mContext.addAllocatedDevice("default", mock(ITestDevice.class));
        // Ensure that the original error is the one passed around.
        Throwable exception = new Throwable("Original error");
        mExec.doTeardown(mContext, mConfig, null, exception);

        InOrder inOrder = Mockito.inOrder(stub1, stub2, stub3, stub4, cleaner);

        // tear down
        inOrder.verify(stub4).isDisabled();
        inOrder.verify(stub4).tearDown(mContext, exception);
        inOrder.verify(stub3).isDisabled();
        inOrder.verify(stub3).tearDown(mContext, exception);

        inOrder.verify(cleaner).tearDown(Mockito.any(), Mockito.any(), Mockito.any());

        inOrder.verify(stub2).isDisabled();
        inOrder.verify(stub2).tearDown(mContext, exception);
        inOrder.verify(stub1).isDisabled();
        inOrder.verify(stub1).tearDown(mContext, exception);
    }

    /** Interface to test a preparer receiving the logger. */
    private interface ILoggerMultiTargetPreparer
            extends IMultiTargetPreparer, ITestLoggerReceiver {}

    /** Ensure that during tear down the original exception is kept and logger is received */
    @Test
    public void testDoTearDown_logger() throws Throwable {
        ILoggerMultiTargetPreparer stub1 = mock(ILoggerMultiTargetPreparer.class);
        IMultiTargetPreparer stub2 = mock(IMultiTargetPreparer.class);
        IMultiTargetPreparer stub3 = mock(IMultiTargetPreparer.class);
        IMultiTargetPreparer stub4 = mock(IMultiTargetPreparer.class);
        mConfig.setMultiPreTargetPreparers(Arrays.asList(stub1, stub2));
        mConfig.setMultiTargetPreparers(Arrays.asList(stub3, stub4));

        ITargetCleaner cleaner = mock(ITargetCleaner.class);
        IDeviceConfiguration holder = new DeviceConfigurationHolder("default");
        holder.addSpecificConfig(cleaner);
        mConfig.setDeviceConfig(holder);
        mContext.addAllocatedDevice("default", mock(ITestDevice.class));
        // Ensure that the original error is the one passed around.
        Throwable exception = new Throwable("Original error");
        ITestLogger logger = new CollectingTestListener();
        mExec.doTeardown(mContext, mConfig, logger, exception);

        InOrder inOrder = Mockito.inOrder(stub1, stub2, stub3, stub4, cleaner);

        // tear down
        inOrder.verify(stub4).isDisabled();
        inOrder.verify(stub4).tearDown(mContext, exception);
        inOrder.verify(stub3).isDisabled();
        inOrder.verify(stub3).tearDown(mContext, exception);

        inOrder.verify(cleaner).tearDown(Mockito.any(), Mockito.any(), Mockito.any());

        inOrder.verify(stub2).isDisabled();
        inOrder.verify(stub2).tearDown(mContext, exception);
        inOrder.verify(stub1).isDisabled();
        inOrder.verify(stub1).setTestLogger(logger);
        inOrder.verify(stub1).tearDown(mContext, exception);
    }

    /** Ensure that the full tear down is attempted before throwning an exception. */
    @Test
    public void testDoTearDown_exception() throws Throwable {
        IMultiTargetPreparer stub1 = mock(IMultiTargetPreparer.class);
        IMultiTargetPreparer stub2 = mock(IMultiTargetPreparer.class);
        IMultiTargetPreparer stub3 = mock(IMultiTargetPreparer.class);
        IMultiTargetPreparer stub4 = mock(IMultiTargetPreparer.class);
        mConfig.setMultiPreTargetPreparers(Arrays.asList(stub1, stub2));
        mConfig.setMultiTargetPreparers(Arrays.asList(stub3, stub4));

        ITargetCleaner cleaner = mock(ITargetCleaner.class);
        IDeviceConfiguration holder = new DeviceConfigurationHolder("default");
        holder.addSpecificConfig(cleaner);
        mConfig.setDeviceConfig(holder);
        mContext.addAllocatedDevice("default", mock(ITestDevice.class));

        // Ensure that the original error is the one passed around.
        Throwable exception = new Throwable("Original error");
        doThrow(new RuntimeException("Oups I failed")).when(stub3).tearDown(mContext, exception);

        try {
            mExec.doTeardown(mContext, mConfig, null, exception);
            fail("Should have thrown an exception");
        } catch (RuntimeException expected) {
            // Expected
        }
        // Ensure that even in case of exception, the full tear down goes through before throwing.
        InOrder inOrder = Mockito.inOrder(stub1, stub2, stub3, stub4, cleaner);
        // tear down
        inOrder.verify(stub4).isDisabled();
        inOrder.verify(stub4).tearDown(mContext, exception);
        inOrder.verify(stub3).isDisabled();
        inOrder.verify(stub3).tearDown(mContext, exception);

        inOrder.verify(cleaner).tearDown(Mockito.any(), Mockito.any(), Mockito.any());

        inOrder.verify(stub2).isDisabled();
        inOrder.verify(stub2).tearDown(mContext, exception);
        inOrder.verify(stub1).isDisabled();
        inOrder.verify(stub1).tearDown(mContext, exception);
    }

    /** Ensure we create the shared folder from the resource build. */
    @Test
    public void testFetchBuild_createSharedFolder() throws Throwable {
        EasyMock.expect(mMockDevice.getSerialNumber()).andStubReturn("serial");
        mMockDevice.setRecovery(EasyMock.anyObject());
        EasyMock.expectLastCall().times(2);

        List<IDeviceConfiguration> listDeviceConfig = new ArrayList<>();
        DeviceConfigurationHolder holder = new DeviceConfigurationHolder("device1");
        IBuildProvider provider = new StubBuildProvider();
        holder.addSpecificConfig(provider);
        mContext.addAllocatedDevice("device1", mMockDevice);
        listDeviceConfig.add(holder);

        DeviceConfigurationHolder holder2 = new DeviceConfigurationHolder("device2", true);
        IBuildProvider provider2 = new StubBuildProvider();
        holder2.addSpecificConfig(provider2);
        mContext.addAllocatedDevice("device2", mMockDevice);
        listDeviceConfig.add(holder2);

        mConfig.setDeviceConfigList(listDeviceConfig);
        // Download
        EasyMock.replay(mMockDevice);
        assertTrue(mExec.fetchBuild(mContext, mConfig, null, mMockListener));
        EasyMock.verify(mMockDevice);

        List<IBuildInfo> builds = mContext.getBuildInfos();
        try {
            assertEquals(2, builds.size());
            IBuildInfo realBuild = builds.get(0);
            File shared = realBuild.getFile(BuildInfoFileKey.SHARED_RESOURCE_DIR);
            assertNotNull(shared);

            IBuildInfo fakeBuild = builds.get(1);
            assertNull(fakeBuild.getFile(BuildInfoFileKey.SHARED_RESOURCE_DIR));
        } finally {
            for (IBuildInfo info : builds) {
                info.cleanUp();
            }
        }
    }
}
