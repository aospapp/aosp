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
package com.android.tradefed.invoker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.ddmlib.IDevice;
import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IBuildInfo.BuildInfoProperties;
import com.android.tradefed.build.IBuildProvider;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.build.IDeviceBuildProvider;
import com.android.tradefed.command.CommandOptions;
import com.android.tradefed.command.CommandRunner.ExitCode;
import com.android.tradefed.command.FatalHostError;
import com.android.tradefed.command.ICommandOptions;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationDef;
import com.android.tradefed.config.DeviceConfigurationHolder;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationFactory;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.config.IGlobalConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceAllocationState;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.IDeviceRecovery;
import com.android.tradefed.device.INativeDevice;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.device.TcpDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.device.metric.BaseDeviceMetricCollector;
import com.android.tradefed.device.metric.DeviceMetricData;
import com.android.tradefed.device.metric.IMetricCollector;
import com.android.tradefed.guice.InvocationScope;
import com.android.tradefed.invoker.shard.IShardHelper;
import com.android.tradefed.invoker.shard.ShardHelper;
import com.android.tradefed.log.ILeveledLogOutput;
import com.android.tradefed.log.ILogRegistry;
import com.android.tradefed.metrics.proto.MetricMeasurement.Measurements;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric.Builder;
import com.android.tradefed.postprocessor.BasePostProcessor;
import com.android.tradefed.postprocessor.IPostProcessor;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.ILogSaver;
import com.android.tradefed.result.ILogSaverListener;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.ITestSummaryListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.InvocationStatus;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestSummary;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.ITargetCleaner;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IInvocationContextReceiver;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.IResumableTest;
import com.android.tradefed.testtype.IRetriableTest;
import com.android.tradefed.testtype.IShardableTest;
import com.android.tradefed.testtype.StubTest;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.SystemUtil.EnvVariable;
import com.android.tradefed.util.keystore.StubKeyStoreFactory;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Unit tests for {@link TestInvocation}. */
@SuppressWarnings("MustBeClosedChecker")
@RunWith(JUnit4.class)
public class TestInvocationTest {

    private static final String SERIAL = "serial";
    private static final Map<String, String> EMPTY_MAP = Collections.emptyMap();
    private static final String PATH = "path";
    private static final String URL = "url";
    private static final TestSummary mSummary = new TestSummary("http://www.url.com/report.txt");
    private static final InputStreamSource EMPTY_STREAM_SOURCE =
            new ByteArrayInputStreamSource(new byte[0]);
    private static final String LOGCAT_NAME_ERROR =
            TestInvocation.getDeviceLogName(TestInvocation.Stage.ERROR);
    private static final String LOGCAT_NAME_SETUP =
            TestInvocation.getDeviceLogName(TestInvocation.Stage.SETUP);
    private static final String LOGCAT_NAME_TEST =
            TestInvocation.getDeviceLogName(TestInvocation.Stage.TEST);
    private static final String LOGCAT_NAME_TEARDOWN =
            TestInvocation.getDeviceLogName(TestInvocation.Stage.TEARDOWN);
    /** The {@link TestInvocation} under test, with all dependencies mocked out */
    private TestInvocation mTestInvocation;

    private IConfiguration mStubConfiguration;
    private IConfiguration mStubMultiConfiguration;
    private IGlobalConfiguration mGlobalConfiguration;

    private IInvocationContext mStubInvocationMetadata;

    // The mock objects.
    private ITestDevice mMockDevice;
    private ITargetPreparer mMockPreparer;
    private IBuildProvider mMockBuildProvider;
    private IBuildInfo mMockBuildInfo;
    private ITestInvocationListener mMockTestListener;
    private ITestSummaryListener mMockSummaryListener;
    private ILeveledLogOutput mMockLogger;
    private ILogSaver mMockLogSaver;
    private IDeviceRecovery mMockRecovery;
    private Capture<List<TestSummary>> mUriCapture;
    private ILogRegistry mMockLogRegistry;
    private IConfigurationFactory mMockConfigFactory;
    private IRescheduler mockRescheduler;
    private DeviceDescriptor mFakeDescriptor;

    @Before
    public void setUp() throws Exception {

        mStubConfiguration = new Configuration("foo", "bar");
        mStubMultiConfiguration = new Configuration("foo", "bar");

        mGlobalConfiguration = EasyMock.createMock(IGlobalConfiguration.class);

        mMockDevice = EasyMock.createMock(ITestDevice.class);
        mMockRecovery = EasyMock.createMock(IDeviceRecovery.class);
        mMockPreparer = EasyMock.createMock(ITargetPreparer.class);
        mMockBuildProvider = EasyMock.createMock(IBuildProvider.class);

        // Use strict mocks here since the order of Listener calls is important
        mMockTestListener = EasyMock.createStrictMock(ITestInvocationListener.class);
        mMockSummaryListener = EasyMock.createStrictMock(ITestSummaryListener.class);
        mMockBuildInfo = EasyMock.createMock(IBuildInfo.class);
        mMockLogger = EasyMock.createMock(ILeveledLogOutput.class);
        mMockLogRegistry = EasyMock.createMock(ILogRegistry.class);
        mMockLogSaver = EasyMock.createMock(ILogSaver.class);
        mMockConfigFactory = EasyMock.createMock(IConfigurationFactory.class);
        mockRescheduler = EasyMock.createMock(IRescheduler.class);

        mStubConfiguration.setDeviceRecovery(mMockRecovery);
        mStubConfiguration.setTargetPreparer(mMockPreparer);
        mStubConfiguration.setBuildProvider(mMockBuildProvider);

        EasyMock.expect(mMockPreparer.isDisabled()).andStubReturn(false);
        EasyMock.expect(mMockPreparer.isTearDownDisabled()).andStubReturn(false);

        List<IDeviceConfiguration> deviceConfigs = new ArrayList<IDeviceConfiguration>();
        IDeviceConfiguration device1 =
                new DeviceConfigurationHolder(ConfigurationDef.DEFAULT_DEVICE_NAME);
        device1.addSpecificConfig(mMockRecovery);
        device1.addSpecificConfig(mMockPreparer);
        device1.addSpecificConfig(mMockBuildProvider);
        deviceConfigs.add(device1);
        mStubMultiConfiguration.setDeviceConfigList(deviceConfigs);

        mStubConfiguration.setLogSaver(mMockLogSaver);
        mStubMultiConfiguration.setLogSaver(mMockLogSaver);

        List<ITestInvocationListener> listenerList = new ArrayList<ITestInvocationListener>(1);
        listenerList.add(mMockTestListener);
        listenerList.add(mMockSummaryListener);
        mStubConfiguration.setTestInvocationListeners(listenerList);
        mStubMultiConfiguration.setTestInvocationListeners(listenerList);

        mStubConfiguration.setLogOutput(mMockLogger);
        mStubMultiConfiguration.setLogOutput(mMockLogger);
        EasyMock.expect(mMockDevice.getSerialNumber()).andStubReturn(SERIAL);
        EasyMock.expect(mMockDevice.getIDevice()).andStubReturn(null);
        EasyMock.expect(mMockDevice.getBattery()).andStubReturn(null);
        mMockDevice.setRecovery(mMockRecovery);
        mMockDevice.preInvocationSetup(
                (IBuildInfo) EasyMock.anyObject(), EasyMock.<List<IBuildInfo>>anyObject());
        EasyMock.expectLastCall().anyTimes();
        mMockDevice.postInvocationTearDown();
        EasyMock.expectLastCall().anyTimes();
        mFakeDescriptor = new DeviceDescriptor(SERIAL, false, DeviceAllocationState.Available,
                "unknown", "unknown", "unknown", "unknown", "unknown");
        EasyMock.expect(mMockDevice.getDeviceDescriptor()).andStubReturn(mFakeDescriptor);

        EasyMock.expect(mMockBuildInfo.getBuildId()).andStubReturn("1");
        EasyMock.expect(mMockBuildInfo.getBuildAttributes()).andStubReturn(EMPTY_MAP);
        EasyMock.expect(mMockBuildInfo.getBuildBranch()).andStubReturn("branch");
        EasyMock.expect(mMockBuildInfo.getBuildFlavor()).andStubReturn("flavor");
        EasyMock.expect(mMockBuildInfo.getProperties()).andStubReturn(new HashSet<>());
        EasyMock.expect(mMockBuildInfo.isTestResourceBuild()).andStubReturn(false);
        mMockBuildInfo.setTestResourceBuild(EasyMock.anyBoolean());
        EasyMock.expectLastCall().anyTimes();

        // always expect logger initialization and cleanup calls
        mMockLogRegistry.registerLogger(mMockLogger);
        mMockLogger.init();
        mMockLogger.closeLog();
        mMockLogRegistry.unregisterLogger();
        mUriCapture = new Capture<List<TestSummary>>();

        mStubInvocationMetadata = new InvocationContext();
        mStubInvocationMetadata.addAllocatedDevice(ConfigurationDef.DEFAULT_DEVICE_NAME,
                mMockDevice);
        mStubInvocationMetadata.addDeviceBuildInfo(ConfigurationDef.DEFAULT_DEVICE_NAME,
                mMockBuildInfo);

        // create the BaseTestInvocation to test
        mTestInvocation =
                new TestInvocation() {
                    @Override
                    ILogRegistry getLogRegistry() {
                        return mMockLogRegistry;
                    }

                    @Override
                    public IInvocationExecution createInvocationExec(RunMode mode) {
                        return new InvocationExecution() {
                            @Override
                            protected IShardHelper createShardHelper() {
                                return new ShardHelper() {
                                    @Override
                                    protected IGlobalConfiguration getGlobalConfiguration() {
                                        return mGlobalConfiguration;
                                    }
                                };
                            }

                            @Override
                            protected String getAdbVersion() {
                                return null;
                            }
                        };
                    }

                    @Override
                    protected void setExitCode(ExitCode code, Throwable stack) {
                        // empty on purpose
                    }

                    @Override
                    InvocationScope getInvocationScope() {
                        // Avoid re-entry in the current TF invocation scope for unit tests.
                        return new InvocationScope();
                    }
                };
    }

    /**
     * Test the normal case invoke scenario with a {@link IRemoteTest}.
     *
     * <p>Verifies that all external interfaces get notified as expected.
     */
    @Test
    public void testInvoke_RemoteTest() throws Throwable {
        IRemoteTest test = EasyMock.createMock(IRemoteTest.class);
        setupMockSuccessListeners();

        test.run((ITestInvocationListener)EasyMock.anyObject());
        setupNormalInvoke(test);
        EasyMock.replay(mockRescheduler);
        mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
        verifyMocks(test, mockRescheduler);
        verifySummaryListener();
    }

    /**
     * Test the normal case for multi invoke scenario with a {@link IRemoteTest}.
     *
     * <p>Verifies that all external interfaces get notified as expected.
     */
    @Test
    public void testInvokeMulti_RemoteTest() throws Throwable {
        IRemoteTest test = EasyMock.createMock(IRemoteTest.class);
        setupMockSuccessListeners();

        test.run((ITestInvocationListener)EasyMock.anyObject());
        setupNormalInvoke(test);
        EasyMock.replay(mockRescheduler);
        mTestInvocation.invoke(mStubInvocationMetadata, mStubMultiConfiguration, mockRescheduler);
        verifyMocks(test, mockRescheduler);
        verifySummaryListener();
    }

    /**
     * Test the normal case invoke scenario with an {@link ITestSummaryListener} masquerading as an
     * {@link ITestInvocationListener}.
     *
     * <p>Verifies that all external interfaces get notified as expected.
     */
    @Test
    public void testInvoke_twoSummary() throws Throwable {

        IRemoteTest test = EasyMock.createMock(IRemoteTest.class);
        setupMockSuccessListeners();

        test.run((ITestInvocationListener)EasyMock.anyObject());
        setupNormalInvoke(test);
        EasyMock.replay(mockRescheduler);
        mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
        verifyMocks(test, mockRescheduler);
        verifySummaryListener();
    }

    /**
     * Test the invoke scenario where build retrieve fails.
     *
     * <p>An invocation will be started in this scenario.
     */
    @Test
    public void testInvoke_buildFailed() throws Throwable {
        BuildRetrievalError exception =
                new BuildRetrievalError("testInvoke_buildFailed", null, mMockBuildInfo);
        EasyMock.expect(mMockBuildProvider.getBuild()).andThrow(exception);
        EasyMock.expect(mMockBuildInfo.getTestTag()).andStubReturn(null);

        setupMockFailureListeners(exception);
        setupInvoke();
        IRemoteTest test = EasyMock.createMock(IRemoteTest.class);
        CommandOptions cmdOptions = new CommandOptions();
        final String expectedTestTag = "TEST_TAG";
        mMockBuildInfo.setTestTag(expectedTestTag);
        cmdOptions.setTestTag(expectedTestTag);
        mStubConfiguration.setCommandOptions(cmdOptions);
        mStubConfiguration.setTest(test);
        EasyMock.expect(mMockLogger.getLog()).andReturn(EMPTY_STREAM_SOURCE);
        EasyMock.expect(mMockDevice.getLogcat()).andReturn(EMPTY_STREAM_SOURCE).times(2);
        mMockDevice.clearLogcat();
        EasyMock.expectLastCall().times(2);
        mMockLogRegistry.unregisterLogger();
        mMockLogRegistry.dumpToGlobalLog(mMockLogger);
        mMockLogger.closeLog();
        mMockBuildProvider.cleanUp(mMockBuildInfo);
        replayMocks(test, mockRescheduler);
        mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
        verifyMocks(test, mockRescheduler);
        // invocation test tag was updated.
        assertEquals(expectedTestTag, mStubInvocationMetadata.getTestTag());
    }

    /** Test the invoke scenario where there is no build to test. */
    @Test
    public void testInvoke_noBuild() throws Throwable {
        EasyMock.expect(mMockBuildProvider.getBuild()).andReturn(null);
        setupInvoke();
        setupMockFailureListenersAny(new BuildRetrievalError("No build found to test."), true);

        IRemoteTest test = EasyMock.createMock(IRemoteTest.class);
        mStubConfiguration.setTest(test);
        EasyMock.expect(mMockLogger.getLog()).andReturn(EMPTY_STREAM_SOURCE);
        EasyMock.expect(mMockDevice.getLogcat()).andReturn(EMPTY_STREAM_SOURCE).times(2);
        mMockDevice.clearLogcat();
        EasyMock.expectLastCall().times(2);
        Capture<IBuildInfo> captured = new Capture<>();
        mMockBuildProvider.cleanUp(EasyMock.capture(captured));
        mMockLogRegistry.unregisterLogger();
        mMockLogRegistry.dumpToGlobalLog(mMockLogger);
        mMockLogger.closeLog();
        replayMocks(test, mockRescheduler);
        mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
        verifyMocks(test);

        IBuildInfo stubBuild = captured.getValue();
        assertEquals(BuildInfo.UNKNOWN_BUILD_ID, stubBuild.getBuildId());
        stubBuild.cleanUp();
    }

    /** Test the invoke scenario where there is no build to test for a {@link IRetriableTest}. */
    @Test
    public void testInvoke_noBuildRetry() throws Throwable {
        EasyMock.expect(mMockBuildProvider.getBuild()).andReturn(null);

        IRetriableTest test = EasyMock.createMock(IRetriableTest.class);
        EasyMock.expect(test.isRetriable()).andReturn(Boolean.TRUE);

        EasyMock.expect(mockRescheduler.rescheduleCommand()).andReturn(EasyMock.anyBoolean());
        mStubConfiguration.setTest(test);
        mStubConfiguration.getCommandOptions().setLoopMode(false);
        mMockLogRegistry.dumpToGlobalLog(mMockLogger);
        EasyMock.expectLastCall().times(1);

        setupInvoke();
        setupMockFailureListenersAny(new BuildRetrievalError("No build found to test."), true);
        Capture<IBuildInfo> captured = new Capture<>();
        mMockBuildProvider.cleanUp(EasyMock.capture(captured));
        EasyMock.expect(mMockLogger.getLog()).andReturn(EMPTY_STREAM_SOURCE);
        EasyMock.expect(mMockDevice.getLogcat()).andReturn(EMPTY_STREAM_SOURCE).times(2);
        mMockDevice.clearLogcat();
        EasyMock.expectLastCall().times(2);
        mMockLogRegistry.unregisterLogger();
        mMockLogger.closeLog();

        replayMocks(test);
        EasyMock.replay(mockRescheduler);
        mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
        EasyMock.verify(mockRescheduler);
        verifyMocks(test);

        IBuildInfo stubBuild = captured.getValue();
        assertEquals(BuildInfo.UNKNOWN_BUILD_ID, stubBuild.getBuildId());
        stubBuild.cleanUp();
    }

    /**
     * Test the{@link TestInvocation#invoke(IInvocationContext, IConfiguration, IRescheduler,
     * ITestInvocationListener[])} scenario where the test is a {@link IDeviceTest}
     */
    @Test
    public void testInvoke_deviceTest() throws Throwable {
         DeviceConfigTest mockDeviceTest = EasyMock.createMock(DeviceConfigTest.class);
         mStubConfiguration.setTest(mockDeviceTest);
         mockDeviceTest.setDevice(mMockDevice);
         mockDeviceTest.run((ITestInvocationListener)EasyMock.anyObject());
         setupMockSuccessListeners();
         setupNormalInvoke(mockDeviceTest);
         EasyMock.replay(mockRescheduler);
         mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
         verifyMocks(mockDeviceTest, mockRescheduler);
         verifySummaryListener();
    }

    /**
     * Test the invoke scenario where test run throws {@link IllegalArgumentException}
     *
     * @throws Exception if unexpected error occurs
     */
    @Test
    public void testInvoke_testFail() throws Throwable {
        IllegalArgumentException exception = new IllegalArgumentException("testInvoke_testFail");
        IRemoteTest test = EasyMock.createMock(IRemoteTest.class);
        test.run((ITestInvocationListener)EasyMock.anyObject());
        EasyMock.expectLastCall().andThrow(exception);
        setupMockFailureListeners(exception);
        mMockBuildProvider.buildNotTested(mMockBuildInfo);
        setupNormalInvoke(test);
        EasyMock.replay(mockRescheduler);
        try {
            mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
            fail("IllegalArgumentException was not rethrown");
        } catch (IllegalArgumentException e) {
            // expected
        }
        verifyMocks(test, mockRescheduler);
        verifySummaryListener();
    }

    /**
     * Test the invoke scenario where test run throws {@link FatalHostError}
     *
     * @throws Exception if unexpected error occurs
     */
    @Test
    public void testInvoke_fatalError() throws Throwable {
        FatalHostError exception = new FatalHostError("testInvoke_fatalError");
        IRemoteTest test = EasyMock.createMock(IRemoteTest.class);
        test.run((ITestInvocationListener)EasyMock.anyObject());
        EasyMock.expectLastCall().andThrow(exception);
        setupMockFailureListeners(exception);
        mMockBuildProvider.buildNotTested(mMockBuildInfo);
        setupNormalInvoke(test);
        EasyMock.replay(mockRescheduler);
        try {
            mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
            fail("FatalHostError was not rethrown");
        } catch (FatalHostError e)  {
            // expected
        }
        verifyMocks(test, mockRescheduler);
        verifySummaryListener();
    }

    /**
     * Test the invoke scenario where test run throws {@link DeviceNotAvailableException}
     *
     * @throws Exception if unexpected error occurs
     */
    @Test
    public void testInvoke_deviceNotAvail() throws Throwable {
        DeviceNotAvailableException exception = new DeviceNotAvailableException("ERROR", SERIAL);
        IRemoteTest test = EasyMock.createMock(IRemoteTest.class);
        test.run((ITestInvocationListener)EasyMock.anyObject());
        EasyMock.expectLastCall().andThrow(exception);
        mMockDevice.setRecoveryMode(RecoveryMode.NONE);
        EasyMock.expectLastCall();
        setupMockFailureListeners(exception);
        mMockBuildProvider.buildNotTested(mMockBuildInfo);
        setupNormalInvoke(test);
        EasyMock.replay(mockRescheduler);
        try {
            mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
            fail("DeviceNotAvailableException not thrown");
        } catch (DeviceNotAvailableException e) {
            // expected
        }
        verifyMocks(test, mockRescheduler);
        verifySummaryListener();
    }

    /**
     * Test the invoke scenario where preparer throws {@link BuildError}
     *
     * @throws Exception if unexpected error occurs
     */
    @Test
    public void testInvoke_buildError() throws Throwable {
        BuildError exception = new BuildError("error", mFakeDescriptor);
        IRemoteTest test = EasyMock.createMock(IRemoteTest.class);
        mStubConfiguration.setTest(test);
        EasyMock.expect(mMockBuildProvider.getBuild()).andReturn(mMockBuildInfo);

        mMockPreparer.setUp(mMockDevice, mMockBuildInfo);
        EasyMock.expectLastCall().andThrow(exception);
        setupMockFailureListeners(exception);
        EasyMock.expect(mMockDevice.getBugreport()).andReturn(EMPTY_STREAM_SOURCE);
        setupInvokeWithBuild();
        replayMocks(test);
        EasyMock.replay(mockRescheduler);
        mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
        verifyMocks(test, mockRescheduler);
        verifySummaryListener();
    }

    /**
     * Test the invoke scenario for a {@link IResumableTest}.
     *
     * @throws Exception if unexpected error occurs
     */
    @Test
    public void testInvoke_resume() throws Throwable {
        IResumableTest resumableTest = EasyMock.createMock(IResumableTest.class);
        mStubConfiguration.setTest(resumableTest);
        ITestInvocationListener resumeListener = EasyMock.createStrictMock(
                ITestInvocationListener.class);
        mStubConfiguration.setTestInvocationListener(resumeListener);

        EasyMock.expect(mMockBuildProvider.getBuild()).andReturn(mMockBuildInfo);
        resumeListener.invocationStarted(mStubInvocationMetadata);
        EasyMock.expect(resumeListener.getSummary()).andReturn(null);
        mMockDevice.clearLastConnectedWifiNetwork();
        mMockDevice.setOptions((TestDeviceOptions)EasyMock.anyObject());
        mMockBuildInfo.setDeviceSerial(SERIAL);
        EasyMock.expect(mMockBuildInfo.getTestTag()).andStubReturn("");
        mMockDevice.startLogcat();
        mMockPreparer.setUp(mMockDevice, mMockBuildInfo);

        resumableTest.run((ITestInvocationListener) EasyMock.anyObject());
        EasyMock.expectLastCall().andThrow(new DeviceNotAvailableException("ERROR", SERIAL));
        EasyMock.expect(resumableTest.isResumable()).andReturn(Boolean.TRUE);
        mMockDevice.setRecoveryMode(RecoveryMode.NONE);
        EasyMock.expectLastCall();
        EasyMock.expect(mMockDevice.getLogcat()).andReturn(EMPTY_STREAM_SOURCE).times(3);
        mMockDevice.clearLogcat();
        EasyMock.expectLastCall().times(3);
        EasyMock.expect(mMockLogger.getLog()).andReturn(EMPTY_STREAM_SOURCE);
        EasyMock.expect(
                        mMockLogSaver.saveLogData(
                                EasyMock.startsWith(LOGCAT_NAME_SETUP),
                                EasyMock.eq(LogDataType.LOGCAT),
                                (InputStream) EasyMock.anyObject()))
                .andReturn(new LogFile(PATH, URL, LogDataType.TEXT));
        EasyMock.expect(
                        mMockLogSaver.saveLogData(
                                EasyMock.startsWith(LOGCAT_NAME_TEST),
                                EasyMock.eq(LogDataType.LOGCAT),
                                (InputStream) EasyMock.anyObject()))
                .andReturn(new LogFile(PATH, URL, LogDataType.TEXT));
        EasyMock.expect(
                        mMockLogSaver.saveLogData(
                                EasyMock.startsWith(LOGCAT_NAME_TEARDOWN),
                                EasyMock.eq(LogDataType.LOGCAT),
                                (InputStream) EasyMock.anyObject()))
                .andReturn(new LogFile(PATH, URL, LogDataType.TEXT));
        EasyMock.expect(
                        mMockLogSaver.saveLogData(
                                EasyMock.eq(TestInvocation.TRADEFED_LOG_NAME),
                                EasyMock.eq(LogDataType.TEXT),
                                (InputStream) EasyMock.anyObject()))
                .andReturn(new LogFile(PATH, URL, LogDataType.TEXT));
        resumeListener.testLog(
                EasyMock.startsWith(LOGCAT_NAME_SETUP),
                EasyMock.eq(LogDataType.LOGCAT),
                (InputStreamSource) EasyMock.anyObject());
        resumeListener.testLog(
                EasyMock.startsWith(LOGCAT_NAME_TEST),
                EasyMock.eq(LogDataType.LOGCAT),
                (InputStreamSource) EasyMock.anyObject());
        resumeListener.testLog(
                EasyMock.startsWith(LOGCAT_NAME_TEARDOWN),
                EasyMock.eq(LogDataType.LOGCAT),
                (InputStreamSource) EasyMock.anyObject());
        resumeListener.testLog(EasyMock.eq(TestInvocation.TRADEFED_LOG_NAME),
                EasyMock.eq(LogDataType.TEXT), (InputStreamSource)EasyMock.anyObject());

        // just return same build and logger for simplicity
        EasyMock.expect(mMockBuildInfo.clone()).andReturn(mMockBuildInfo);
        EasyMock.expect(mMockLogger.clone()).andReturn(mMockLogger);
        IRescheduler mockRescheduler = EasyMock.createMock(IRescheduler.class);
        Capture<IConfiguration> capturedConfig = new Capture<IConfiguration>();
        EasyMock.expect(mockRescheduler.scheduleConfig(EasyMock.capture(capturedConfig)))
                .andReturn(Boolean.TRUE);
        // When resuming the original build provider is still going to handle the clean up.
        mMockBuildProvider.cleanUp(mMockBuildInfo);
        EasyMock.expectLastCall().times(4);
        mMockDevice.clearLastConnectedWifiNetwork();
        mMockDevice.stopLogcat();

        mMockLogger.init();

        mMockLogSaver.invocationStarted(mStubInvocationMetadata);
        // now set resumed invocation expectations
        mMockDevice.clearLastConnectedWifiNetwork();
        mMockDevice.setOptions((TestDeviceOptions)EasyMock.anyObject());
        mMockBuildInfo.setDeviceSerial(SERIAL);
        mMockBuildInfo.setTestTag(EasyMock.eq("stub"));
        EasyMock.expectLastCall().times(2);
        mMockDevice.startLogcat();
        mMockPreparer.setUp(mMockDevice, mMockBuildInfo);
        mMockLogSaver.invocationStarted(mStubInvocationMetadata);
        mMockDevice.setRecovery(mMockRecovery);
        resumableTest.run((ITestInvocationListener)EasyMock.anyObject());
        EasyMock.expect(mMockDevice.getLogcat()).andReturn(EMPTY_STREAM_SOURCE).times(3);
        mMockDevice.clearLogcat();
        EasyMock.expectLastCall().times(3);
        EasyMock.expect(mMockLogger.getLog()).andReturn(EMPTY_STREAM_SOURCE);
        EasyMock.expect(
                        mMockLogSaver.saveLogData(
                                EasyMock.startsWith(LOGCAT_NAME_SETUP),
                                EasyMock.eq(LogDataType.LOGCAT),
                                (InputStream) EasyMock.anyObject()))
                .andReturn(new LogFile(PATH, URL, LogDataType.TEXT));
        EasyMock.expect(
                        mMockLogSaver.saveLogData(
                                EasyMock.startsWith(LOGCAT_NAME_TEST),
                                EasyMock.eq(LogDataType.LOGCAT),
                                (InputStream) EasyMock.anyObject()))
                .andReturn(new LogFile(PATH, URL, LogDataType.TEXT));
        EasyMock.expect(
                        mMockLogSaver.saveLogData(
                                EasyMock.startsWith(LOGCAT_NAME_TEARDOWN),
                                EasyMock.eq(LogDataType.LOGCAT),
                                (InputStream) EasyMock.anyObject()))
                .andReturn(new LogFile(PATH, URL, LogDataType.TEXT));
        EasyMock.expect(
                        mMockLogSaver.saveLogData(
                                EasyMock.eq(TestInvocation.TRADEFED_LOG_NAME),
                                EasyMock.eq(LogDataType.TEXT),
                                (InputStream) EasyMock.anyObject()))
                .andReturn(new LogFile(PATH, URL, LogDataType.TEXT));
        resumeListener.testLog(
                EasyMock.startsWith(LOGCAT_NAME_SETUP),
                EasyMock.eq(LogDataType.LOGCAT),
                (InputStreamSource) EasyMock.anyObject());
        resumeListener.testLog(
                EasyMock.startsWith(LOGCAT_NAME_TEST),
                EasyMock.eq(LogDataType.LOGCAT),
                (InputStreamSource) EasyMock.anyObject());
        resumeListener.testLog(
                EasyMock.startsWith(LOGCAT_NAME_TEARDOWN),
                EasyMock.eq(LogDataType.LOGCAT),
                (InputStreamSource) EasyMock.anyObject());
        resumeListener.testLog(
                EasyMock.eq(TestInvocation.TRADEFED_LOG_NAME),
                EasyMock.eq(LogDataType.TEXT),
                (InputStreamSource) EasyMock.anyObject());
        resumeListener.invocationEnded(EasyMock.anyLong());
        mMockLogSaver.invocationEnded(EasyMock.anyLong());
        EasyMock.expect(resumeListener.getSummary()).andReturn(null);
        mMockLogger.closeLog();
        EasyMock.expectLastCall().times(3);
        mMockDevice.clearLastConnectedWifiNetwork();
        mMockDevice.stopLogcat();
        EasyMock.replay(mockRescheduler, resumeListener, resumableTest, mMockPreparer,
                mMockBuildProvider, mMockLogger, mMockLogSaver, mMockDevice, mMockBuildInfo);

        try {
            mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
            fail("DeviceNotAvailableException not thrown");
        } catch (DeviceNotAvailableException e) {
            // expect
        }
        // now call again, and expect invocation to be resumed properly
        mTestInvocation.invoke(mStubInvocationMetadata, capturedConfig.getValue(), mockRescheduler);

        EasyMock.verify(mockRescheduler, resumeListener, resumableTest, mMockPreparer,
                mMockBuildProvider, mMockLogger, mMockLogSaver, mMockDevice, mMockBuildInfo);
    }

    /**
     * Test the invoke scenario for a {@link IRetriableTest}.
     *
     * @throws Exception if unexpected error occurs
     */
    @Test
    public void testInvoke_retry() throws Throwable {
        AssertionError exception = new AssertionError("testInvoke_retry");
        IRetriableTest test = EasyMock.createMock(IRetriableTest.class);
        test.run((ITestInvocationListener)EasyMock.anyObject());
        EasyMock.expectLastCall().andThrow(exception);
        EasyMock.expect(test.isRetriable()).andReturn(Boolean.TRUE);
        mStubConfiguration.getCommandOptions().setLoopMode(false);
        IRescheduler mockRescheduler = EasyMock.createMock(IRescheduler.class);
        EasyMock.expect(mockRescheduler.rescheduleCommand()).andReturn(EasyMock.anyBoolean());
        mMockBuildProvider.buildNotTested(mMockBuildInfo);
        setupMockFailureListeners(exception);
        setupNormalInvoke(test);
        EasyMock.replay(mockRescheduler);
        mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
        verifyMocks(test, mockRescheduler);
        verifySummaryListener();
    }

    /**
     * Test the {@link TestInvocation#invoke(IInvocationContext, IConfiguration, IRescheduler,
     * ITestInvocationListener[])} scenario when a {@link ITargetCleaner} is part of the config.
     */
    @Test
    public void testInvoke_tearDown() throws Throwable {
         IRemoteTest test = EasyMock.createNiceMock(IRemoteTest.class);
         ITargetCleaner mockCleaner = EasyMock.createMock(ITargetCleaner.class);
        EasyMock.expect(mockCleaner.isDisabled()).andReturn(false).times(2);
        EasyMock.expect(mockCleaner.isTearDownDisabled()).andReturn(false);
         mockCleaner.setUp(mMockDevice, mMockBuildInfo);
         mockCleaner.tearDown(mMockDevice, mMockBuildInfo, null);
         mStubConfiguration.getTargetPreparers().add(mockCleaner);
         setupMockSuccessListeners();
         setupNormalInvoke(test);
         EasyMock.replay(mockCleaner, mockRescheduler);
         mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
         verifyMocks(mockCleaner, mockRescheduler);
         verifySummaryListener();
    }

    /**
     * Test the {@link TestInvocation#invoke(IInvocationContext, IConfiguration, IRescheduler,
     * ITestInvocationListener[])} scenario when a {@link ITargetCleaner} is part of the config, and
     * the test throws a {@link DeviceNotAvailableException}.
     */
    @Test
    public void testInvoke_tearDown_deviceNotAvail() throws Throwable {
        DeviceNotAvailableException exception = new DeviceNotAvailableException("ERROR", SERIAL);
        IRemoteTest test = EasyMock.createMock(IRemoteTest.class);
        test.run((ITestInvocationListener)EasyMock.anyObject());
        EasyMock.expectLastCall().andThrow(exception);
        ITargetCleaner mockCleaner = EasyMock.createMock(ITargetCleaner.class);
        EasyMock.expect(mockCleaner.isDisabled()).andReturn(false).times(2);
        EasyMock.expect(mockCleaner.isTearDownDisabled()).andReturn(false);
        mockCleaner.setUp(mMockDevice, mMockBuildInfo);
        EasyMock.expectLastCall();
        mockCleaner.tearDown(mMockDevice, mMockBuildInfo, exception);
        EasyMock.expectLastCall();
        mMockDevice.setRecoveryMode(RecoveryMode.NONE);
        EasyMock.expectLastCall();
        EasyMock.replay(mockCleaner, mockRescheduler);
        mStubConfiguration.getTargetPreparers().add(mockCleaner);
        setupMockFailureListeners(exception);
        mMockBuildProvider.buildNotTested(mMockBuildInfo);
        setupNormalInvoke(test);
        try {
            mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
            fail("DeviceNotAvailableException not thrown");
        } catch (DeviceNotAvailableException e) {
            // expected
        }
        verifyMocks(mockCleaner, mockRescheduler);
        verifySummaryListener();
    }

    /**
     * Test the {@link TestInvocation#invoke(IInvocationContext, IConfiguration, IRescheduler,
     * ITestInvocationListener[])} scenario when a {@link ITargetCleaner} is part of the config, and
     * the test throws a {@link RuntimeException}.
     */
    @Test
    public void testInvoke_tearDown_runtime() throws Throwable {
        RuntimeException exception = new RuntimeException("testInvoke_tearDown_runtime");
        IRemoteTest test = EasyMock.createMock(IRemoteTest.class);
        test.run((ITestInvocationListener)EasyMock.anyObject());
        EasyMock.expectLastCall().andThrow(exception);
        ITargetCleaner mockCleaner = EasyMock.createMock(ITargetCleaner.class);
        EasyMock.expect(mockCleaner.isDisabled()).andReturn(false).times(2);
        EasyMock.expect(mockCleaner.isTearDownDisabled()).andReturn(false);
        mockCleaner.setUp(mMockDevice, mMockBuildInfo);
        // tearDown should be called
        mockCleaner.tearDown(mMockDevice, mMockBuildInfo, exception);
        mStubConfiguration.getTargetPreparers().add(mockCleaner);
        setupMockFailureListeners(exception);
        mMockBuildProvider.buildNotTested(mMockBuildInfo);
        setupNormalInvoke(test);
        EasyMock.replay(mockCleaner, mockRescheduler);
        try {
            mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
            fail("RuntimeException not thrown");
        } catch (RuntimeException e) {
            // expected
        }
        verifyMocks(mockCleaner, mockRescheduler);
        verifySummaryListener();
    }

    /**
     * Test the {@link TestInvocation#invoke(IInvocationContext, IConfiguration, IRescheduler,
     * ITestInvocationListener[])} scenario when there is {@link ITestInvocationListener} which
     * implements the {@link ILogSaverListener} interface.
     */
    @Test
    public void testInvoke_logFileSaved() throws Throwable {
        List<ITestInvocationListener> listenerList =
                mStubConfiguration.getTestInvocationListeners();
        ILogSaverListener logSaverListener = EasyMock.createMock(ILogSaverListener.class);
        listenerList.add(logSaverListener);
        mStubConfiguration.setTestInvocationListeners(listenerList);

        logSaverListener.setLogSaver(mMockLogSaver);
        logSaverListener.invocationStarted(mStubInvocationMetadata);
        logSaverListener.testLog(
                EasyMock.startsWith(LOGCAT_NAME_SETUP),
                EasyMock.eq(LogDataType.LOGCAT),
                (InputStreamSource) EasyMock.anyObject());
        logSaverListener.testLogSaved(
                EasyMock.startsWith(LOGCAT_NAME_SETUP),
                EasyMock.eq(LogDataType.LOGCAT),
                (InputStreamSource) EasyMock.anyObject(),
                (LogFile) EasyMock.anyObject());
        logSaverListener.logAssociation(
                EasyMock.startsWith(LOGCAT_NAME_SETUP), EasyMock.anyObject());
        logSaverListener.testLog(
                EasyMock.startsWith(LOGCAT_NAME_TEST),
                EasyMock.eq(LogDataType.LOGCAT),
                (InputStreamSource) EasyMock.anyObject());
        logSaverListener.testLogSaved(
                EasyMock.startsWith(LOGCAT_NAME_TEST),
                EasyMock.eq(LogDataType.LOGCAT),
                (InputStreamSource) EasyMock.anyObject(),
                (LogFile) EasyMock.anyObject());
        logSaverListener.logAssociation(
                EasyMock.startsWith(LOGCAT_NAME_TEST), EasyMock.anyObject());
        logSaverListener.testLog(
                EasyMock.startsWith(LOGCAT_NAME_TEARDOWN),
                EasyMock.eq(LogDataType.LOGCAT),
                (InputStreamSource) EasyMock.anyObject());
        logSaverListener.testLogSaved(
                EasyMock.startsWith(LOGCAT_NAME_TEARDOWN),
                EasyMock.eq(LogDataType.LOGCAT),
                (InputStreamSource) EasyMock.anyObject(),
                (LogFile) EasyMock.anyObject());
        logSaverListener.logAssociation(
                EasyMock.startsWith(LOGCAT_NAME_TEARDOWN), EasyMock.anyObject());
        logSaverListener.testLog(
                EasyMock.eq(TestInvocation.TRADEFED_LOG_NAME),
                EasyMock.eq(LogDataType.TEXT),
                (InputStreamSource) EasyMock.anyObject());
        logSaverListener.testLogSaved(
                EasyMock.eq(TestInvocation.TRADEFED_LOG_NAME),
                EasyMock.eq(LogDataType.TEXT),
                (InputStreamSource) EasyMock.anyObject(),
                (LogFile) EasyMock.anyObject());
        logSaverListener.logAssociation(
                EasyMock.eq(TestInvocation.TRADEFED_LOG_NAME), EasyMock.anyObject());
        logSaverListener.invocationEnded(EasyMock.anyLong());
        EasyMock.expect(logSaverListener.getSummary()).andReturn(mSummary).times(2);

        IRemoteTest test = EasyMock.createMock(IRemoteTest.class);
        setupMockSuccessListeners();
        test.run((ITestInvocationListener)EasyMock.anyObject());
        setupNormalInvoke(test);
        EasyMock.replay(logSaverListener, mockRescheduler);
        mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
        verifyMocks(test, logSaverListener, mockRescheduler);
        assertEquals(2, mUriCapture.getValue().size());
    }

    /** Test the test-tag is set when the IBuildInfo's test-tag is not. */
    @Test
    public void testInvoke_testtag() throws Throwable {
        String[] commandLine = {"run", "empty"};
        mStubConfiguration.setCommandLine(commandLine);
        mStubConfiguration.getCommandOptions().setTestTag("not-default");

        setupInvoke();
        EasyMock.expect(mMockDevice.getLogcat()).andReturn(EMPTY_STREAM_SOURCE).times(3);
        mMockDevice.clearLogcat();
        EasyMock.expectLastCall().times(3);
        EasyMock.expect(mMockLogger.getLog()).andReturn(EMPTY_STREAM_SOURCE);
        mMockBuildInfo.setDeviceSerial(SERIAL);
        mMockBuildProvider.cleanUp(mMockBuildInfo);
        EasyMock.expectLastCall().times(2);
        setupMockSuccessListeners();
        EasyMock.expect(mMockBuildProvider.getBuild()).andReturn(mMockBuildInfo);
        mMockBuildInfo.addBuildAttribute("command_line_args", "run empty");
        mMockPreparer.setUp(mMockDevice, mMockBuildInfo);
        // Default build is "stub" so we set the test-tag
        mMockBuildInfo.setTestTag(EasyMock.eq("not-default"));
        EasyMock.expectLastCall();
        EasyMock.expect(mMockBuildInfo.getTestTag()).andStubReturn("stub");
        mMockLogRegistry.unregisterLogger();
        mMockLogRegistry.dumpToGlobalLog(mMockLogger);
        mMockLogger.closeLog();
        replayMocks();
        mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
        verifyMocks();
    }

    /**
     * Test the test-tag of the IBuildInfo is not modified when the CommandOption default test-tag
     * is not modified.
     */
    @Test
    public void testInvoke_testtag_notset() throws Throwable {
        String[] commandLine = {"run", "empty"};
        mStubConfiguration.setCommandLine(commandLine);
        setupInvoke();
        EasyMock.expect(mMockDevice.getLogcat()).andReturn(EMPTY_STREAM_SOURCE).times(3);
        mMockDevice.clearLogcat();
        EasyMock.expectLastCall().times(3);
        EasyMock.expect(mMockLogger.getLog()).andReturn(EMPTY_STREAM_SOURCE);
        mMockBuildInfo.setDeviceSerial(SERIAL);
        mMockBuildProvider.cleanUp(mMockBuildInfo);
        EasyMock.expectLastCall().times(2);
        setupMockSuccessListeners();
        EasyMock.expect(mMockBuildProvider.getBuild()).andReturn(mMockBuildInfo);
        mMockBuildInfo.addBuildAttribute("command_line_args", "run empty");
        mMockPreparer.setUp(mMockDevice, mMockBuildInfo);
        EasyMock.expect(mMockBuildInfo.getTestTag()).andStubReturn("buildprovidertesttag");
        mMockLogRegistry.dumpToGlobalLog(mMockLogger);
        mMockLogRegistry.unregisterLogger();
        mMockLogger.closeLog();
        replayMocks();
        mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
        verifyMocks();
    }

    /**
     * Test the test-tag of the IBuildInfo is not set and Command Option is not set either. A
     * default 'stub' test-tag is set to ensure reporting is done.
     */
    @Test
    public void testInvoke_notesttag() throws Throwable {
        String[] commandLine = {"run", "empty"};
        mStubConfiguration.setCommandLine(commandLine);
        setupInvoke();
        EasyMock.expect(mMockDevice.getLogcat()).andReturn(EMPTY_STREAM_SOURCE).times(3);
        mMockDevice.clearLogcat();
        EasyMock.expectLastCall().times(3);
        EasyMock.expect(mMockLogger.getLog()).andReturn(EMPTY_STREAM_SOURCE);
        mMockBuildInfo.setDeviceSerial(SERIAL);
        mMockBuildProvider.cleanUp(mMockBuildInfo);
        EasyMock.expectLastCall().times(2);
        setupMockSuccessListeners();
        EasyMock.expect(mMockBuildProvider.getBuild()).andReturn(mMockBuildInfo);
        mMockBuildInfo.addBuildAttribute("command_line_args", "run empty");
        mMockPreparer.setUp(mMockDevice, mMockBuildInfo);
        EasyMock.expect(mMockBuildInfo.getTestTag()).andStubReturn(null);
        mMockBuildInfo.setTestTag(EasyMock.eq("stub"));
        EasyMock.expectLastCall();
        mMockLogRegistry.dumpToGlobalLog(mMockLogger);
        mMockLogRegistry.unregisterLogger();
        mMockLogger.closeLog();
        replayMocks();
        mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
        verifyMocks();
    }

    /**
     * Helper tests class to expose all the interfaces needed for the tests.
     */
    private interface IFakeBuildProvider extends IDeviceBuildProvider, IInvocationContextReceiver {
    }

    /**
     * Test the injection of test-tag from TestInvocation to the build provider via the {@link
     * IInvocationContextReceiver}.
     */
    @Test
    public void testInvoke_buildProviderNeedTestTag() throws Throwable {
        final String testTag = "THISISTHETAG";
        String[] commandLine = {"run", "empty"};
        mStubConfiguration.setCommandLine(commandLine);
        ICommandOptions commandOption = new CommandOptions();
        commandOption.setTestTag(testTag);
        IFakeBuildProvider mockProvider = EasyMock.createMock(IFakeBuildProvider.class);
        mStubConfiguration.setBuildProvider(mockProvider);
        mStubConfiguration.setCommandOptions(commandOption);
        setupInvoke();
        EasyMock.expect(mMockDevice.getLogcat()).andReturn(EMPTY_STREAM_SOURCE).times(3);
        mMockDevice.clearLogcat();
        EasyMock.expectLastCall().times(3);
        EasyMock.expect(mMockLogger.getLog()).andReturn(EMPTY_STREAM_SOURCE);
        mMockBuildInfo.setDeviceSerial(SERIAL);
        setupMockSuccessListeners();
        mMockBuildInfo.addBuildAttribute("command_line_args", "run empty");
        mMockPreparer.setUp(mMockDevice, mMockBuildInfo);
        EasyMock.expect(mMockBuildInfo.getTestTag()).andStubReturn(null);
        // Validate proper tag is set on the build.
        mMockBuildInfo.setTestTag(EasyMock.eq(testTag));
        mockProvider.setInvocationContext((IInvocationContext)EasyMock.anyObject());
        EasyMock.expect(mockProvider.getBuild(mMockDevice)).andReturn(mMockBuildInfo);
        mockProvider.cleanUp(mMockBuildInfo);
        EasyMock.expectLastCall().times(2);
        mMockLogRegistry.dumpToGlobalLog(mMockLogger);
        mMockLogRegistry.unregisterLogger();
        mMockLogger.closeLog();

        replayMocks(mockProvider);
        mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
        verifyMocks(mockProvider);
    }

    /**
     * Set up expected conditions for normal run up to the part where tests are run.
     *
     * @param test the {@link Test} to use.
     */
    private void setupNormalInvoke(IRemoteTest test) throws Throwable {
        setupInvokeWithBuild();
        mStubConfiguration.setTest(test);
        mStubMultiConfiguration.setTest(test);
        EasyMock.expect(mMockBuildProvider.getBuild()).andReturn(mMockBuildInfo);

        mMockPreparer.setUp(mMockDevice, mMockBuildInfo);
        replayMocks(test);
    }

    /**
     * Set up expected calls that occur on every invoke, regardless of result
     */
    private void setupInvoke() {
        mMockDevice.clearLastConnectedWifiNetwork();
        mMockDevice.setOptions((TestDeviceOptions)EasyMock.anyObject());
        mMockDevice.startLogcat();
        mMockDevice.clearLastConnectedWifiNetwork();
        mMockDevice.stopLogcat();
    }

    /**
     * Set up expected calls that occur on every invoke that gets a valid build
     */
    private void setupInvokeWithBuild() {
        setupInvoke();
        EasyMock.expect(mMockDevice.getLogcat()).andReturn(EMPTY_STREAM_SOURCE).times(3);
        mMockDevice.clearLogcat();
        EasyMock.expectLastCall().times(3);

        EasyMock.expect(mMockLogger.getLog()).andReturn(EMPTY_STREAM_SOURCE);
        mMockBuildInfo.setDeviceSerial(SERIAL);
        mMockBuildProvider.cleanUp(mMockBuildInfo);
        EasyMock.expectLastCall().anyTimes();
        mMockBuildInfo.setTestTag(EasyMock.eq("stub"));
        EasyMock.expectLastCall();
        EasyMock.expect(mMockBuildInfo.getTestTag()).andStubReturn("");

        mMockLogRegistry.unregisterLogger();
        mMockLogRegistry.dumpToGlobalLog(mMockLogger);
        mMockLogger.closeLog();
    }

    /**
     * Set up expected conditions for the test InvocationListener and SummaryListener
     *
     * <p>The order of calls for a single listener should be:
     *
     * <ol>
     *   <li>invocationStarted
     *   <li>testLog(LOGCAT_NAME_SETUP, ...) (if no build or retrieval error)
     *   <li>invocationFailed (if run failed)
     *   <li>testLog(LOGCAT_NAME_ERROR, ...) (if build retrieval error)
     *   <li>testLog(LOGCAT_NAME_TEST, ...) (otherwise)
     *   <li>testLog(build error bugreport, ...) (otherwise and if build error)
     *   <li>testLog(LOGCAT_NAME_TEARDOWN, ...) (otherwise)
     *   <li>testLog(TRADEFED_LOG_NAME, ...)
     *   <li>putSummary (for an ITestSummaryListener)
     *   <li>invocationEnded
     *   <li>getSummary (for an ITestInvocationListener)
     * </ol>
     *
     * However note that, across all listeners, any getSummary call will precede all putSummary
     * calls.
     */
    private void setupMockListeners(
            InvocationStatus status, Throwable throwable, boolean stubFailures) throws IOException {
        // invocationStarted
        mMockLogSaver.invocationStarted(mStubInvocationMetadata);
        mMockTestListener.invocationStarted(mStubInvocationMetadata);
        EasyMock.expect(mMockTestListener.getSummary()).andReturn(null);
        mMockSummaryListener.putEarlySummary(EasyMock.anyObject());
        mMockSummaryListener.invocationStarted(mStubInvocationMetadata);
        EasyMock.expect(mMockSummaryListener.getSummary()).andReturn(null);

        if (!(throwable instanceof BuildRetrievalError)) {
            EasyMock.expect(
                            mMockLogSaver.saveLogData(
                                    EasyMock.startsWith(LOGCAT_NAME_SETUP),
                                    EasyMock.eq(LogDataType.LOGCAT),
                                    (InputStream) EasyMock.anyObject()))
                    .andReturn(new LogFile(PATH, URL, LogDataType.TEXT));
            mMockTestListener.testLog(
                    EasyMock.startsWith(LOGCAT_NAME_SETUP),
                    EasyMock.eq(LogDataType.LOGCAT),
                    (InputStreamSource) EasyMock.anyObject());
            mMockSummaryListener.testLog(
                    EasyMock.startsWith(LOGCAT_NAME_SETUP),
                    EasyMock.eq(LogDataType.LOGCAT),
                    (InputStreamSource) EasyMock.anyObject());
        }

        // invocationFailed
        if (!status.equals(InvocationStatus.SUCCESS)) {
            if (stubFailures) {
                mMockTestListener.invocationFailed(EasyMock.anyObject());
                mMockSummaryListener.invocationFailed(EasyMock.anyObject());
            } else {
                mMockTestListener.invocationFailed(EasyMock.eq(throwable));
                mMockSummaryListener.invocationFailed(EasyMock.eq(throwable));
            }
        }

        if (throwable instanceof BuildRetrievalError) {
            // Handle logcat error listeners
            EasyMock.expect(
                            mMockLogSaver.saveLogData(
                                    EasyMock.startsWith(LOGCAT_NAME_ERROR),
                                    EasyMock.eq(LogDataType.LOGCAT),
                                    (InputStream) EasyMock.anyObject()))
                    .andReturn(new LogFile(PATH, URL, LogDataType.TEXT));
            mMockTestListener.testLog(
                    EasyMock.startsWith(LOGCAT_NAME_ERROR),
                    EasyMock.eq(LogDataType.LOGCAT),
                    (InputStreamSource) EasyMock.anyObject());
            mMockSummaryListener.testLog(
                    EasyMock.startsWith(LOGCAT_NAME_ERROR),
                    EasyMock.eq(LogDataType.LOGCAT),
                    (InputStreamSource) EasyMock.anyObject());
        } else {
            // Handle test logcat listeners
            EasyMock.expect(
                            mMockLogSaver.saveLogData(
                                    EasyMock.startsWith(LOGCAT_NAME_TEST),
                                    EasyMock.eq(LogDataType.LOGCAT),
                                    (InputStream) EasyMock.anyObject()))
                    .andReturn(new LogFile(PATH, URL, LogDataType.TEXT));
            mMockTestListener.testLog(
                    EasyMock.startsWith(LOGCAT_NAME_TEST),
                    EasyMock.eq(LogDataType.LOGCAT),
                    (InputStreamSource) EasyMock.anyObject());
            mMockSummaryListener.testLog(
                    EasyMock.startsWith(LOGCAT_NAME_TEST),
                    EasyMock.eq(LogDataType.LOGCAT),
                    (InputStreamSource) EasyMock.anyObject());
            // Handle build error bugreport listeners
            if (throwable instanceof BuildError) {
                EasyMock.expect(
                                mMockDevice.logBugreport(
                                        EasyMock.eq(
                                                TestInvocation.BUILD_ERROR_BUGREPORT_NAME
                                                        + "_"
                                                        + SERIAL),
                                        EasyMock.anyObject()))
                        .andReturn(true);
            }
            // Handle teardown logcat listeners
            EasyMock.expect(
                            mMockLogSaver.saveLogData(
                                    EasyMock.startsWith(LOGCAT_NAME_TEARDOWN),
                                    EasyMock.eq(LogDataType.LOGCAT),
                                    (InputStream) EasyMock.anyObject()))
                    .andReturn(new LogFile(PATH, URL, LogDataType.TEXT));
            mMockTestListener.testLog(
                    EasyMock.startsWith(LOGCAT_NAME_TEARDOWN),
                    EasyMock.eq(LogDataType.LOGCAT),
                    (InputStreamSource) EasyMock.anyObject());
            mMockSummaryListener.testLog(
                    EasyMock.startsWith(LOGCAT_NAME_TEARDOWN),
                    EasyMock.eq(LogDataType.LOGCAT),
                    (InputStreamSource) EasyMock.anyObject());
        }

        EasyMock.expect(
                        mMockLogSaver.saveLogData(
                                EasyMock.eq(TestInvocation.TRADEFED_LOG_NAME),
                                EasyMock.eq(LogDataType.TEXT),
                                (InputStream) EasyMock.anyObject()))
                .andReturn(new LogFile(PATH, URL, LogDataType.TEXT));
        mMockTestListener.testLog(EasyMock.eq(TestInvocation.TRADEFED_LOG_NAME),
                EasyMock.eq(LogDataType.TEXT), (InputStreamSource)EasyMock.anyObject());
        mMockSummaryListener.testLog(EasyMock.eq(TestInvocation.TRADEFED_LOG_NAME),
                EasyMock.eq(LogDataType.TEXT), (InputStreamSource)EasyMock.anyObject());

        // invocationEnded, getSummary (mMockTestListener)
        mMockTestListener.invocationEnded(EasyMock.anyLong());
        EasyMock.expect(mMockTestListener.getSummary()).andReturn(mSummary);

        // putSummary, invocationEnded (mMockSummaryListener)
        mMockSummaryListener.putSummary(EasyMock.capture(mUriCapture));
        mMockSummaryListener.invocationEnded(EasyMock.anyLong());
        mMockLogSaver.invocationEnded(EasyMock.anyLong());
    }

    /**
     * Test the {@link TestInvocation#invoke(IInvocationContext, IConfiguration, IRescheduler,
     * ITestInvocationListener[])} scenario with {@link IShardableTest}.
     */
    @Test
    public void testInvoke_shardableTest_legacy() throws Throwable {
        try {
            GlobalConfiguration.createGlobalConfiguration(new String[] {"empty"});
        } catch (IllegalStateException e) {
            // Avoid exception in case of multi-init
        }

        String command = "empty --test-tag t";
        String[] commandLine = {"empty", "--test-tag", "t"};
        int shardCount = 2;
        IShardableTest test = EasyMock.createMock(IShardableTest.class);
        List<IRemoteTest> shards = new ArrayList<>();
        IRemoteTest shard1 = EasyMock.createMock(IRemoteTest.class);
        IRemoteTest shard2 = EasyMock.createMock(IRemoteTest.class);
        shards.add(shard1);
        shards.add(shard2);
        EasyMock.expect(test.split()).andReturn(shards);
        mStubConfiguration.setTest(test);
        mStubConfiguration.setCommandLine(commandLine);
        mMockBuildProvider.cleanUp(mMockBuildInfo);
        // The keystore is cloned for each shard.
        EasyMock.expect(mGlobalConfiguration.getKeyStoreFactory())
                .andReturn(new StubKeyStoreFactory())
                .times(2);
        setupInvoke();
        mMockLogSaver.invocationStarted(mStubInvocationMetadata);
        mMockLogSaver.invocationEnded(0L);
        setupNShardInvocation(shardCount, command);
        // Ensure that the host_log gets logged after sharding.
        EasyMock.expect(mMockLogger.getLog()).andReturn(EMPTY_STREAM_SOURCE);
        String logName = "host_log_before_sharding";
        mMockTestListener.testLog(
                EasyMock.eq(logName), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());
        mMockSummaryListener.testLog(
                EasyMock.eq(logName), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());
        EasyMock.expect(
                        mMockLogSaver.saveLogData(
                                EasyMock.eq(logName),
                                EasyMock.eq(LogDataType.TEXT),
                                EasyMock.anyObject()))
                .andReturn(new LogFile(PATH, URL, LogDataType.TEXT));
        mMockLogRegistry.unregisterLogger();
        EasyMock.expectLastCall();
        mMockLogger.closeLog();
        EasyMock.expectLastCall();

        mMockLogRegistry.dumpToGlobalLog(mMockLogger);
        replayMocks(test, mockRescheduler, shard1, shard2, mGlobalConfiguration);
        mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
        verifyMocks(test, mockRescheduler, shard1, shard2, mGlobalConfiguration);
    }

    /**
     * Test that {@link TestInvocation#logDeviceBatteryLevel(IInvocationContext, String)} is not
     * adding battery information for placeholder device.
     */
    @Test
    public void testLogDeviceBatteryLevel_placeholderDevice() {
        final String fakeEvent = "event";
        IInvocationContext context = new InvocationContext();
        ITestDevice device1 = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(device1.getIDevice()).andReturn(new StubDevice("stub"));
        context.addAllocatedDevice("device1", device1);
        EasyMock.replay(device1);
        mTestInvocation.logDeviceBatteryLevel(context, fakeEvent);
        EasyMock.verify(device1);
        assertEquals(0, context.getAttributes().size());
    }

    /**
     * Test that {@link TestInvocation#logDeviceBatteryLevel(IInvocationContext, String)} is adding
     * battery information for physical real device.
     */
    @Test
    public void testLogDeviceBatteryLevel_physicalDevice() {
        final String fakeEvent = "event";
        IInvocationContext context = new InvocationContext();
        ITestDevice device1 = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(device1.getIDevice()).andReturn(EasyMock.createMock(IDevice.class));
        EasyMock.expect(device1.getSerialNumber()).andReturn("serial1");
        EasyMock.expect(device1.getBattery()).andReturn(50);
        context.addAllocatedDevice("device1", device1);
        context.addDeviceBuildInfo("device1", new BuildInfo());
        EasyMock.replay(device1);
        mTestInvocation.logDeviceBatteryLevel(context, fakeEvent);
        EasyMock.verify(device1);
        assertEquals(1, context.getBuildInfo("device1").getBuildAttributes().size());
        assertEquals(
                "50",
                context.getBuildInfo("device1")
                        .getBuildAttributes()
                        .get("serial1-battery-" + fakeEvent));
    }

    /**
     * Test that {@link TestInvocation#logDeviceBatteryLevel(IInvocationContext, String)} is adding
     * battery information for multiple physical real device.
     */
    @Test
    public void testLogDeviceBatteryLevel_physicalDevice_multi() {
        final String fakeEvent = "event";
        IInvocationContext context = new InvocationContext();
        ITestDevice device1 = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(device1.getIDevice()).andReturn(EasyMock.createMock(IDevice.class));
        EasyMock.expect(device1.getSerialNumber()).andReturn("serial1");
        EasyMock.expect(device1.getBattery()).andReturn(50);

        ITestDevice device2 = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(device2.getIDevice()).andReturn(EasyMock.createMock(IDevice.class));
        EasyMock.expect(device2.getSerialNumber()).andReturn("serial2");
        EasyMock.expect(device2.getBattery()).andReturn(55);

        context.addAllocatedDevice("device1", device1);
        context.addDeviceBuildInfo("device1", new BuildInfo());
        context.addAllocatedDevice("device2", device2);
        context.addDeviceBuildInfo("device2", new BuildInfo());
        EasyMock.replay(device1, device2);
        mTestInvocation.logDeviceBatteryLevel(context, fakeEvent);
        EasyMock.verify(device1, device2);
        assertEquals(1, context.getBuildInfo("device1").getBuildAttributes().size());
        assertEquals(1, context.getBuildInfo("device2").getBuildAttributes().size());
        assertEquals(
                "50",
                context.getBuildInfo("device1")
                        .getBuildAttributes()
                        .get("serial1-battery-" + fakeEvent));
        assertEquals(
                "55",
                context.getBuildInfo("device2")
                        .getBuildAttributes()
                        .get("serial2-battery-" + fakeEvent));
    }

    /**
     * Test that {@link TestInvocation#logDeviceBatteryLevel(IInvocationContext, String)} is adding
     * battery information for multiple physical real device, and ignore stub device if any.
     */
    @Test
    public void testLogDeviceBatteryLevel_physicalDevice_stub_multi() {
        final String fakeEvent = "event";
        IInvocationContext context = new InvocationContext();
        ITestDevice device1 = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(device1.getIDevice()).andReturn(EasyMock.createMock(IDevice.class));
        EasyMock.expect(device1.getSerialNumber()).andReturn("serial1");
        EasyMock.expect(device1.getBattery()).andReturn(50);

        ITestDevice device2 = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(device2.getIDevice()).andReturn(EasyMock.createMock(IDevice.class));
        EasyMock.expect(device2.getSerialNumber()).andReturn("serial2");
        EasyMock.expect(device2.getBattery()).andReturn(50);

        ITestDevice device3 = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(device3.getIDevice()).andReturn(new StubDevice("stub-3"));

        ITestDevice device4 = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(device4.getIDevice()).andReturn(new TcpDevice("tcp-4"));

        context.addAllocatedDevice("device1", device1);
        context.addDeviceBuildInfo("device1", new BuildInfo());
        context.addAllocatedDevice("device2", device2);
        context.addDeviceBuildInfo("device2", new BuildInfo());
        context.addAllocatedDevice("device3", device3);
        context.addAllocatedDevice("device4", device4);
        EasyMock.replay(device1, device2, device3, device4);
        mTestInvocation.logDeviceBatteryLevel(context, fakeEvent);
        EasyMock.verify(device1, device2, device3, device4);
        assertEquals(1, context.getBuildInfo("device1").getBuildAttributes().size());
        assertEquals(1, context.getBuildInfo("device2").getBuildAttributes().size());
        assertEquals(
                "50",
                context.getBuildInfo("device1")
                        .getBuildAttributes()
                        .get("serial1-battery-" + fakeEvent));
        assertEquals(
                "50",
                context.getBuildInfo("device2")
                        .getBuildAttributes()
                        .get("serial2-battery-" + fakeEvent));
    }

    /** Helper to set the expectation for N number of shards. */
    private void setupNShardInvocation(int numShard, String commandLine) throws Exception {
        mMockBuildInfo.setTestTag(EasyMock.eq("stub"));
        EasyMock.expectLastCall();
        EasyMock.expect(mMockBuildProvider.getBuild()).andReturn(mMockBuildInfo);
        EasyMock.expect(mMockBuildInfo.getTestTag()).andStubReturn("");
        mMockBuildInfo.addBuildAttribute("command_line_args", commandLine);
        mMockTestListener.invocationStarted((IInvocationContext)EasyMock.anyObject());
        EasyMock.expectLastCall();
        mMockSummaryListener.invocationStarted((IInvocationContext)EasyMock.anyObject());
        EasyMock.expectLastCall();
        EasyMock.expect(mMockBuildInfo.clone()).andReturn(mMockBuildInfo).times(numShard);
        EasyMock.expect(mockRescheduler.scheduleConfig(EasyMock.anyObject()))
                .andReturn(true).times(numShard);
        mMockBuildInfo.setDeviceSerial(SERIAL);
        EasyMock.expectLastCall();
        mMockBuildProvider.cleanUp(EasyMock.anyObject());
        EasyMock.expectLastCall();
    }

    private void setupMockSuccessListeners() throws IOException {
        setupMockListeners(InvocationStatus.SUCCESS, null, false);
    }

    private void setupMockFailureListeners(Throwable throwable) throws IOException {
        setupMockListeners(InvocationStatus.FAILED, throwable, false);
    }

    private void setupMockFailureListenersAny(Throwable throwable, boolean stubFailures)
            throws IOException {
        setupMockListeners(InvocationStatus.FAILED, throwable, stubFailures);
    }

    private void verifySummaryListener() {
        // Check that we captured the expected uris List
        List<TestSummary> summaries = mUriCapture.getValue();
        assertEquals(1, summaries.size());
        assertEquals(mSummary, summaries.get(0));
    }

    /**
     * Verify all mock objects received expected calls
     */
    private void verifyMocks(Object... mocks) {
        // note: intentionally exclude configuration from verification - don't care
        // what methods are called
        EasyMock.verify(mMockTestListener, mMockSummaryListener, mMockPreparer,
                mMockBuildProvider, mMockLogger, mMockBuildInfo, mMockLogRegistry,
                mMockLogSaver);
        if (mocks.length > 0) {
            EasyMock.verify(mocks);
        }
    }

    /**
     * Switch all mock objects into replay mode.
     */
    private void replayMocks(Object... mocks) {
        EasyMock.replay(mMockTestListener, mMockSummaryListener, mMockPreparer,
                mMockBuildProvider, mMockLogger, mMockBuildInfo, mMockLogRegistry,
                mMockLogSaver, mMockDevice, mMockConfigFactory);
        if (mocks.length > 0) {
            EasyMock.replay(mocks);
        }
    }

    /**
     * Interface for testing device config pass through.
     */
    private interface DeviceConfigTest extends IRemoteTest, IDeviceTest {

    }

    /**
     * Test {@link INativeDevice#preInvocationSetup(IBuildInfo, List)} is called when command option
     * skip-pre-device-setup is not set.
     */
    @Test
    public void testNotSkipPreDeviceSetup() throws Throwable {
        IInvocationContext context = new InvocationContext();
        ITestDevice device1 = EasyMock.createMock(ITestDevice.class);
        IDevice idevice = Mockito.mock(IDevice.class);
        context.addAllocatedDevice("DEFAULT_DEVICE", device1);
        IBuildInfo testResourceBuildInfo = new BuildInfo();
        testResourceBuildInfo.setTestResourceBuild(true);
        context.addDeviceBuildInfo("test-resource", testResourceBuildInfo);
        List<IBuildInfo> testResourceBuildInfos = new ArrayList<>();
        testResourceBuildInfos.add(testResourceBuildInfo);
        EasyMock.expect(device1.getSerialNumber()).andReturn("serial1").anyTimes();
        EasyMock.expect(device1.getIDevice()).andReturn(idevice).anyTimes();

        device1.preInvocationSetup(
                (IBuildInfo) EasyMock.anyObject(), EasyMock.eq(testResourceBuildInfos));
        EasyMock.expectLastCall().once();

        CommandOptions commandOption = new CommandOptions();
        OptionSetter setter = new OptionSetter(commandOption);
        setter.setOptionValue("skip-pre-device-setup", "false");
        mStubConfiguration.setCommandOptions(commandOption);
        // Not expect isTearDownDisabled.
        ITestInvocationListener listener = EasyMock.createStrictMock(ITestInvocationListener.class);
        EasyMock.replay(device1, listener, mMockPreparer);
        new InvocationExecution()
                .runDevicePreInvocationSetup(context, mStubConfiguration, listener);
        EasyMock.verify(device1, listener, mMockPreparer);

    }

    /**
     * Test {@link INativeDevice#preInvocationSetup(IBuildInfo info)} is not called when command
     * option skip-pre-device-setup is set.
     */
    @Test
    public void testSkipPreDeviceSetup() throws Throwable {
        IInvocationContext context = new InvocationContext();
        ITestDevice device1 = EasyMock.createMock(ITestDevice.class);
        IDevice idevice = Mockito.mock(IDevice.class);
        context.addAllocatedDevice("DEFAULT_DEVICE", device1);
        EasyMock.expect(device1.getSerialNumber()).andReturn("serial1").anyTimes();
        EasyMock.expect(device1.getIDevice()).andReturn(idevice).anyTimes();
        EasyMock.expect(device1.getLogcat()).andReturn(EMPTY_STREAM_SOURCE).times(1);
        device1.clearLogcat();
        EasyMock.expectLastCall().once();

        CommandOptions commandOption = new CommandOptions();
        OptionSetter setter = new OptionSetter(commandOption);
        setter.setOptionValue("skip-pre-device-setup", "true");
        mStubConfiguration.setCommandOptions(commandOption);

        EasyMock.expect(mMockPreparer.isDisabled()).andReturn(true);
        // Not expect isTearDownDisabled

        ITestInvocationListener listener = EasyMock.createStrictMock(ITestInvocationListener.class);
        listener.testLog(
                EasyMock.startsWith(LOGCAT_NAME_SETUP),
                EasyMock.eq(LogDataType.LOGCAT),
                (InputStreamSource) EasyMock.anyObject());

        EasyMock.replay(device1, listener, mMockPreparer);
        new InvocationExecution().doSetup(context, mStubConfiguration, listener);
        EasyMock.verify(device1, listener, mMockPreparer);
    }

    /**
     * Test when a {@link IDeviceBuildInfo} is passing through we do not attempt to add any external
     * directories when there is none coming from environment.
     */
    @Test
    public void testInvoke_deviceInfoBuild_noEnv() throws Throwable {
        mTestInvocation =
                new TestInvocation() {
                    @Override
                    ILogRegistry getLogRegistry() {
                        return mMockLogRegistry;
                    }

                    @Override
                    public IInvocationExecution createInvocationExec(RunMode mode) {
                        return new InvocationExecution() {
                            @Override
                            protected IShardHelper createShardHelper() {
                                return new ShardHelper();
                            }

                            @Override
                            File getExternalTestCasesDirs(EnvVariable envVar) {
                                // Return empty list to ensure we do not have any environment loaded
                                return null;
                            }

                            @Override
                            protected String getAdbVersion() {
                                return null;
                            }
                        };
                    }

                    @Override
                    protected void setExitCode(ExitCode code, Throwable stack) {
                        // empty on purpose
                    }

                    @Override
                    InvocationScope getInvocationScope() {
                        // Avoid re-entry in the current TF invocation scope for unit tests.
                        return new InvocationScope();
                    }
                };
        mMockBuildInfo = EasyMock.createMock(IDeviceBuildInfo.class);
        EasyMock.expect(mMockBuildInfo.getProperties()).andStubReturn(new HashSet<>());
        EasyMock.expect(mMockBuildInfo.isTestResourceBuild()).andStubReturn(false);
        mMockBuildInfo.setTestResourceBuild(EasyMock.anyBoolean());
        EasyMock.expectLastCall().anyTimes();

        IRemoteTest test = EasyMock.createNiceMock(IRemoteTest.class);
        ITargetCleaner mockCleaner = EasyMock.createMock(ITargetCleaner.class);
        EasyMock.expect(mockCleaner.isDisabled()).andReturn(false).times(2);
        EasyMock.expect(mockCleaner.isTearDownDisabled()).andReturn(false);
        mockCleaner.setUp(mMockDevice, mMockBuildInfo);
        mockCleaner.tearDown(mMockDevice, mMockBuildInfo, null);
        mStubConfiguration.getTargetPreparers().add(mockCleaner);

        File tmpTestsDir = FileUtil.createTempDir("invocation-tests-dir");
        try {
            EasyMock.expect(((IDeviceBuildInfo) mMockBuildInfo).getTestsDir())
                    .andReturn(tmpTestsDir);
            setupMockSuccessListeners();
            setupNormalInvoke(test);
            EasyMock.replay(mockCleaner, mockRescheduler);
            mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
            verifyMocks(mockCleaner, mockRescheduler);
            verifySummaryListener();
        } finally {
            FileUtil.recursiveDelete(tmpTestsDir);
        }
    }

    /**
     * Test when a {@link IDeviceBuildInfo} is passing through we attempt to add the external
     * directories to it when they are available.
     */
    @Test
    public void testInvoke_deviceInfoBuild_withEnv() throws Throwable {
        File tmpTestsDir = FileUtil.createTempDir("invocation-tests-dir");
        File tmpExternalTestsDir = FileUtil.createTempDir("external-tf-dir");
        File tmpTestsFile = FileUtil.createTempFile("testsfile", "txt", tmpExternalTestsDir);
        try {
            mTestInvocation =
                    new TestInvocation() {
                        @Override
                        ILogRegistry getLogRegistry() {
                            return mMockLogRegistry;
                        }

                        @Override
                        public IInvocationExecution createInvocationExec(RunMode mode) {
                            return new InvocationExecution() {
                                @Override
                                protected IShardHelper createShardHelper() {
                                    return new ShardHelper();
                                }

                                @Override
                                File getExternalTestCasesDirs(EnvVariable envVar) {
                                    if (EnvVariable.ANDROID_TARGET_OUT_TESTCASES.equals(envVar)) {
                                        return tmpExternalTestsDir;
                                    }
                                    return null;
                                }

                                @Override
                                protected String getAdbVersion() {
                                    return null;
                                }
                            };
                        }

                        @Override
                        protected void setExitCode(ExitCode code, Throwable stack) {
                            // empty on purpose
                        }

                        @Override
                        InvocationScope getInvocationScope() {
                            // Avoid re-entry in the current TF invocation scope for unit tests.
                            return new InvocationScope();
                        }
                    };
            mMockBuildInfo = EasyMock.createMock(IDeviceBuildInfo.class);
            IRemoteTest test = EasyMock.createNiceMock(IRemoteTest.class);
            ITargetCleaner mockCleaner = EasyMock.createMock(ITargetCleaner.class);
            EasyMock.expect(mockCleaner.isDisabled()).andReturn(false).times(2);
            EasyMock.expect(mockCleaner.isTearDownDisabled()).andReturn(false);
            mockCleaner.setUp(mMockDevice, mMockBuildInfo);
            mockCleaner.tearDown(mMockDevice, mMockBuildInfo, null);
            mStubConfiguration.getTargetPreparers().add(mockCleaner);

            mMockBuildInfo.setFile(
                    EasyMock.contains(BuildInfoFileKey.TARGET_LINKED_DIR.getFileKey()),
                    EasyMock.anyObject(),
                    EasyMock.eq("v1"));
            EasyMock.expect(((IDeviceBuildInfo) mMockBuildInfo).getTestsDir())
                    .andReturn(tmpTestsDir);
            EasyMock.expect(mMockBuildInfo.getProperties()).andStubReturn(new HashSet<>());
            EasyMock.expect(mMockBuildInfo.isTestResourceBuild()).andStubReturn(false);
            mMockBuildInfo.setTestResourceBuild(EasyMock.anyBoolean());
            EasyMock.expectLastCall().anyTimes();

            setupMockSuccessListeners();
            setupNormalInvoke(test);
            EasyMock.replay(mockCleaner, mockRescheduler);
            mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
            verifyMocks(mockCleaner, mockRescheduler);
            verifySummaryListener();
            // Check that the external directory was copied in the testsDir.
            assertTrue(tmpTestsDir.listFiles().length == 1);
            // external-tf-dir - the symlink is the original file name + randomized sequence
            assertTrue(
                    tmpTestsDir
                            .listFiles()[0]
                            .getName()
                            .startsWith(BuildInfoFileKey.TARGET_LINKED_DIR.getFileKey()));
            // testsfile.txt
            assertTrue(tmpTestsDir.listFiles()[0].listFiles().length == 1);
            assertEquals(
                    tmpTestsFile.getName(), tmpTestsDir.listFiles()[0].listFiles()[0].getName());
        } finally {
            FileUtil.recursiveDelete(tmpTestsDir);
            FileUtil.recursiveDelete(tmpExternalTestsDir);
        }
    }

    /**
     * Test when a {@link IDeviceBuildInfo} is passing through we do not attempt to add the external
     * directories to it, since {@link BuildInfoProperties} is set to skip the linking.
     */
    @Test
    public void testInvoke_deviceInfoBuild_withEnv_andSkipProperty() throws Throwable {
        File tmpTestsDir = FileUtil.createTempDir("invocation-tests-dir");
        File tmpExternalTestsDir = FileUtil.createTempDir("external-tf-dir");
        FileUtil.createTempFile("testsfile", "txt", tmpExternalTestsDir);
        try {
            mTestInvocation =
                    new TestInvocation() {
                        @Override
                        ILogRegistry getLogRegistry() {
                            return mMockLogRegistry;
                        }

                        @Override
                        public IInvocationExecution createInvocationExec(RunMode mode) {
                            return new InvocationExecution() {
                                @Override
                                protected IShardHelper createShardHelper() {
                                    return new ShardHelper();
                                }

                                @Override
                                File getExternalTestCasesDirs(EnvVariable envVar) {
                                    return tmpExternalTestsDir;
                                }

                                @Override
                                protected String getAdbVersion() {
                                    return null;
                                }
                            };
                        }

                        @Override
                        protected void setExitCode(ExitCode code, Throwable stack) {
                            // empty on purpose
                        }

                        @Override
                        InvocationScope getInvocationScope() {
                            // Avoid re-entry in the current TF invocation scope for unit tests.
                            return new InvocationScope();
                        }
                    };
            mMockBuildInfo = EasyMock.createMock(IDeviceBuildInfo.class);
            IRemoteTest test = EasyMock.createNiceMock(IRemoteTest.class);
            ITargetCleaner mockCleaner = EasyMock.createMock(ITargetCleaner.class);
            EasyMock.expect(mockCleaner.isDisabled()).andReturn(false).times(2);
            EasyMock.expect(mockCleaner.isTearDownDisabled()).andReturn(false);
            mockCleaner.setUp(mMockDevice, mMockBuildInfo);
            mockCleaner.tearDown(mMockDevice, mMockBuildInfo, null);
            mStubConfiguration.getTargetPreparers().add(mockCleaner);

            Set<BuildInfoProperties> prop = new HashSet<>();
            prop.add(BuildInfoProperties.DO_NOT_LINK_TESTS_DIR);
            EasyMock.expect(mMockBuildInfo.getProperties()).andStubReturn(prop);
            EasyMock.expect(mMockBuildInfo.isTestResourceBuild()).andStubReturn(false);
            mMockBuildInfo.setTestResourceBuild(EasyMock.anyBoolean());
            EasyMock.expectLastCall().anyTimes();

            setupMockSuccessListeners();
            setupNormalInvoke(test);
            EasyMock.replay(mockCleaner, mockRescheduler);
            mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
            verifyMocks(mockCleaner, mockRescheduler);
            verifySummaryListener();
            // Check that the external directory was NOT copied in the testsDir.
            assertTrue(tmpTestsDir.listFiles().length == 0);
        } finally {
            FileUtil.recursiveDelete(tmpTestsDir);
            FileUtil.recursiveDelete(tmpExternalTestsDir);
        }
    }

    public static class TestableCollector extends BaseDeviceMetricCollector {

        @Option(name = "name")
        private String mName;

        public TestableCollector() {}

        public TestableCollector(String name) {
            mName = name;
        }

        @Override
        public void onTestRunEnd(
                DeviceMetricData runData, final Map<String, Metric> currentRunMetrics) {
            runData.addMetric(
                    mName,
                    Metric.newBuilder()
                            .setMeasurements(
                                    Measurements.newBuilder().setSingleString(mName).build()));
        }
    }

    /**
     * Test that when {@link IMetricCollector} are used, they wrap and call in sequence the listener
     * so all metrics end up on the final receiver.
     */
    @Test
    public void testMetricCollectionChain() throws Throwable {
        IConfiguration configuration = new Configuration("test", "description");
        StubTest test = new StubTest();
        OptionSetter setter = new OptionSetter(test);
        setter.setOptionValue("run-a-test", "true");
        configuration.setTest(test);

        List<IMetricCollector> collectors = new ArrayList<>();
        collectors.add(new TestableCollector("collector1"));
        collectors.add(new TestableCollector("collector2"));
        collectors.add(new TestableCollector("collector3"));
        collectors.add(new TestableCollector("collector4"));
        configuration.setDeviceMetricCollectors(collectors);

        mMockTestListener.testRunStarted("TestStub", 1);
        TestDescription testId = new TestDescription("StubTest", "StubMethod");
        mMockTestListener.testStarted(EasyMock.eq(testId), EasyMock.anyLong());
        mMockTestListener.testEnded(
                EasyMock.eq(testId),
                EasyMock.anyLong(),
                EasyMock.eq(new HashMap<String, Metric>()));
        Capture<HashMap<String, Metric>> captured = new Capture<>();
        mMockTestListener.testRunEnded(EasyMock.anyLong(), EasyMock.capture(captured));
        EasyMock.replay(mMockTestListener);
        new InvocationExecution()
                .runTests(mStubInvocationMetadata, configuration, mMockTestListener);
        EasyMock.verify(mMockTestListener);
        // The collectors are called in sequence
        List<String> listKeys = new ArrayList<>(captured.getValue().keySet());
        assertEquals(4, listKeys.size());
        assertEquals("collector4", listKeys.get(0));
        assertEquals("collector3", listKeys.get(1));
        assertEquals("collector2", listKeys.get(2));
        assertEquals("collector1", listKeys.get(3));
    }

    /**
     * Test that when a device collector is disabled, it will not be part of the initialization and
     * the collector chain.
     */
    @Test
    public void testMetricCollectionChain_disabled() throws Throwable {
        IConfiguration configuration = new Configuration("test", "description");
        StubTest test = new StubTest();
        OptionSetter setter = new OptionSetter(test);
        setter.setOptionValue("run-a-test", "true");
        configuration.setTest(test);

        List<IMetricCollector> collectors = new ArrayList<>();
        collectors.add(new TestableCollector("collector1"));
        collectors.add(new TestableCollector("collector2"));
        // Collector 3 is disabled
        TestableCollector col3 = new TestableCollector("collector3");
        col3.setDisable(true);
        collectors.add(col3);
        collectors.add(new TestableCollector("collector4"));
        configuration.setDeviceMetricCollectors(collectors);

        mMockTestListener.testRunStarted("TestStub", 1);
        TestDescription testId = new TestDescription("StubTest", "StubMethod");
        mMockTestListener.testStarted(EasyMock.eq(testId), EasyMock.anyLong());
        mMockTestListener.testEnded(
                EasyMock.eq(testId),
                EasyMock.anyLong(),
                EasyMock.eq(new HashMap<String, Metric>()));
        Capture<HashMap<String, Metric>> captured = new Capture<>();
        mMockTestListener.testRunEnded(EasyMock.anyLong(), EasyMock.capture(captured));
        EasyMock.replay(mMockTestListener);
        new InvocationExecution()
                .runTests(mStubInvocationMetadata, configuration, mMockTestListener);
        EasyMock.verify(mMockTestListener);
        // The collectors are called in sequence
        List<String> listKeys = new ArrayList<>(captured.getValue().keySet());
        assertEquals(3, listKeys.size());
        assertEquals("collector4", listKeys.get(0));
        assertEquals("collector2", listKeys.get(1));
        assertEquals("collector1", listKeys.get(2));
    }

    public static class TestableProcessor extends BasePostProcessor {

        @Option(name = "name")
        private String mName;

        public TestableProcessor() {}

        public TestableProcessor(String name) {
            mName = name;
        }

        @Override
        public Map<String, Builder> processRunMetrics(HashMap<String, Metric> rawMetrics) {
            Map<String, Builder> post = new LinkedHashMap<>();
            post.put(mName, Metric.newBuilder());
            return post;
        }
    }

    /** Ensure post processors are called in order. */
    @Test
    public void testProcessorCollectionChain() throws Throwable {
        mMockTestListener = EasyMock.createMock(ITestInvocationListener.class);
        List<ITestInvocationListener> listenerList = new ArrayList<ITestInvocationListener>(1);
        listenerList.add(mMockTestListener);
        mStubConfiguration.setTestInvocationListeners(listenerList);

        List<IPostProcessor> processors = new ArrayList<>();
        processors.add(new TestableProcessor("processor1"));
        processors.add(new TestableProcessor("processor2"));
        processors.add(new TestableProcessor("processor3"));
        processors.add(new TestableProcessor("processor4"));
        mStubConfiguration.setPostProcessors(processors);

        mMockTestListener.testRunStarted("TestStub", 1);
        TestDescription testId = new TestDescription("StubTest", "StubMethod");
        mMockTestListener.testStarted(EasyMock.eq(testId), EasyMock.anyLong());
        mMockTestListener.testEnded(
                EasyMock.eq(testId),
                EasyMock.anyLong(),
                EasyMock.eq(new HashMap<String, Metric>()));
        Capture<HashMap<String, Metric>> captured = new Capture<>();
        mMockTestListener.testRunEnded(EasyMock.anyLong(), EasyMock.capture(captured));

        setupMockSuccessListeners();
        setupInvokeWithBuild();
        StubTest test = new StubTest();
        OptionSetter setter = new OptionSetter(test);
        setter.setOptionValue("run-a-test", "true");
        mStubConfiguration.setTest(test);
        EasyMock.expect(mMockBuildProvider.getBuild()).andReturn(mMockBuildInfo);

        mMockPreparer.setUp(mMockDevice, mMockBuildInfo);

        EasyMock.reset(mMockSummaryListener);
        replayMocks();
        EasyMock.replay(mockRescheduler);
        mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
        verifyMocks(mockRescheduler);

        // The post processors are called in sequence
        List<String> listKeys = new ArrayList<>(captured.getValue().keySet());
        assertEquals(4, listKeys.size());
        assertEquals("processor4", listKeys.get(0));
        assertEquals("processor3", listKeys.get(1));
        assertEquals("processor2", listKeys.get(2));
        assertEquals("processor1", listKeys.get(3));
    }

    /** Ensure post processors are called in order and that ILogSaver is set properly */
    @Test
    public void testProcessorCollectionChain_logSaver() throws Throwable {
        TestLogSaverListener mockLogSaverListener = new TestLogSaverListener();
        mMockTestListener = EasyMock.createMock(ITestInvocationListener.class);
        List<ITestInvocationListener> listenerList = new ArrayList<ITestInvocationListener>(1);
        listenerList.add(mMockTestListener);
        listenerList.add(mockLogSaverListener);
        mStubConfiguration.setTestInvocationListeners(listenerList);

        List<IPostProcessor> processors = new ArrayList<>();
        processors.add(new TestableProcessor("processor1"));
        processors.add(new TestableProcessor("processor2"));
        processors.add(new TestableProcessor("processor3"));
        processors.add(new TestableProcessor("processor4"));
        mStubConfiguration.setPostProcessors(processors);

        mMockTestListener.testRunStarted("TestStub", 1);
        TestDescription testId = new TestDescription("StubTest", "StubMethod");
        mMockTestListener.testStarted(EasyMock.eq(testId), EasyMock.anyLong());
        mMockTestListener.testEnded(
                EasyMock.eq(testId),
                EasyMock.anyLong(),
                EasyMock.eq(new HashMap<String, Metric>()));
        Capture<HashMap<String, Metric>> captured = new Capture<>();
        mMockTestListener.testRunEnded(EasyMock.anyLong(), EasyMock.capture(captured));

        setupMockSuccessListeners();
        setupInvokeWithBuild();
        StubTest test = new StubTest();
        OptionSetter setter = new OptionSetter(test);
        setter.setOptionValue("run-a-test", "true");
        mStubConfiguration.setTest(test);
        EasyMock.expect(mMockBuildProvider.getBuild()).andReturn(mMockBuildInfo);

        mMockPreparer.setUp(mMockDevice, mMockBuildInfo);

        EasyMock.reset(mMockSummaryListener);
        replayMocks();
        EasyMock.replay(mockRescheduler);
        mTestInvocation.invoke(mStubInvocationMetadata, mStubConfiguration, mockRescheduler);
        verifyMocks(mockRescheduler);

        // The post processors are called in sequence
        List<String> listKeys = new ArrayList<>(captured.getValue().keySet());
        assertEquals(4, listKeys.size());
        assertEquals("processor4", listKeys.get(0));
        assertEquals("processor3", listKeys.get(1));
        assertEquals("processor2", listKeys.get(2));
        assertEquals("processor1", listKeys.get(3));

        assertTrue(mockLogSaverListener.mWasLoggerSet);
    }

    private class TestLogSaverListener implements ILogSaverListener {

        public boolean mWasLoggerSet = false;

        @Override
        public void setLogSaver(ILogSaver logSaver) {
            mWasLoggerSet = true;
        }
    }
}
