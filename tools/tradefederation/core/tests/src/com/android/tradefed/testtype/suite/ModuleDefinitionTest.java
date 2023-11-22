/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tradefed.testtype.suite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.ddmlib.IDevice;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceUnresponsiveException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.TestInvocation;
import com.android.tradefed.invoker.shard.token.TokenProperty;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.CollectingTestListener;
import com.android.tradefed.result.ILogSaver;
import com.android.tradefed.result.ILogSaverListener;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.LogSaverResultForwarder;
import com.android.tradefed.result.ResultForwarder;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestRunResult;
import com.android.tradefed.targetprep.BaseTargetPreparer;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.ITargetCleaner;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.targetprep.multi.IMultiTargetPreparer;
import com.android.tradefed.testtype.Abi;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestFilterReceiver;
import com.android.tradefed.testtype.suite.ITestSuite.RetryStrategy;
import com.android.tradefed.testtype.suite.module.BaseModuleController;
import com.android.tradefed.testtype.suite.module.IModuleController;
import com.android.tradefed.testtype.suite.module.TestFailureModuleController;

import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/** Unit tests for {@link ModuleDefinition} */
@RunWith(JUnit4.class)
public class ModuleDefinitionTest {

    private static final String MODULE_NAME = "fakeName";
    private static final String DEFAULT_DEVICE_NAME = "DEFAULT_DEVICE";
    private ModuleDefinition mModule;
    private List<IRemoteTest> mTestList;
    private ITestInterface mMockTest;
    private ITargetPreparer mMockPrep;
    private ITargetCleaner mMockCleaner;
    private List<ITargetPreparer> mTargetPrepList;
    private Map<String, List<ITargetPreparer>> mMapDeviceTargetPreparer;
    private List<IMultiTargetPreparer> mMultiTargetPrepList;
    private ITestInvocationListener mMockListener;
    private IBuildInfo mMockBuildInfo;
    private ITestDevice mMockDevice;
    // Extra mock for log saving testing
    private ILogSaver mMockLogSaver;
    private ILogSaverListener mMockLogSaverListener;

    private interface ITestInterface extends IRemoteTest, IBuildReceiver, IDeviceTest {}

    /** Test implementation that allows us to exercise different use cases * */
    private class TestObject implements ITestInterface {

        private ITestDevice mDevice;
        private String mRunName;
        private int mNumTest;
        private boolean mShouldThrow;
        private boolean mDeviceUnresponsive = false;
        private boolean mThrowError = false;

        public TestObject(String runName, int numTest, boolean shouldThrow) {
            mRunName = runName;
            mNumTest = numTest;
            mShouldThrow = shouldThrow;
        }

        public TestObject(
                String runName, int numTest, boolean shouldThrow, boolean deviceUnresponsive) {
            this(runName, numTest, shouldThrow);
            mDeviceUnresponsive = deviceUnresponsive;
        }

        public TestObject(
                String runName,
                int numTest,
                boolean shouldThrow,
                boolean deviceUnresponsive,
                boolean throwError) {
            this(runName, numTest, shouldThrow, deviceUnresponsive);
            mThrowError = throwError;
        }

        @Override
        public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
            listener.testRunStarted(mRunName, mNumTest);
            for (int i = 0; i < mNumTest; i++) {
                TestDescription test = new TestDescription(mRunName + "class", "test" + i);
                listener.testStarted(test);
                if (mShouldThrow && i == mNumTest / 2) {
                    throw new DeviceNotAvailableException("unavailable", "serial");
                }
                if (mDeviceUnresponsive) {
                    throw new DeviceUnresponsiveException("unresponsive", "serial");
                }
                if (mThrowError && i == mNumTest / 2) {
                    throw new AssertionError("assert error");
                }
                listener.testEnded(test, new HashMap<String, Metric>());
            }
            listener.testRunEnded(0, new HashMap<String, Metric>());
        }

        @Override
        public void setBuild(IBuildInfo buildInfo) {
            // ignore
        }

        @Override
        public void setDevice(ITestDevice device) {
            mDevice = device;
        }

        @Override
        public ITestDevice getDevice() {
            return mDevice;
        }
    }

    /** Test implementation that allows us to exercise different use cases * */
    private class MultiRunTestObject implements IRemoteTest, ITestFilterReceiver {

        private String mBaseRunName;
        private int mNumTest;
        private int mRepeatedRun;
        private int mFailedTest;
        private Set<String> mIncludeFilters;

        public MultiRunTestObject(
                String baseRunName, int numTest, int repeatedRun, int failedTest) {
            mBaseRunName = baseRunName;
            mNumTest = numTest;
            mRepeatedRun = repeatedRun;
            mFailedTest = failedTest;
            mIncludeFilters = new LinkedHashSet<>();
        }

        @Override
        public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
            // The runner generates several set of different runs.
            for (int j = 0; j < mRepeatedRun; j++) {
                String runName = mBaseRunName + j;
                if (mIncludeFilters.isEmpty()) {
                    listener.testRunStarted(runName, mNumTest);
                } else {
                    listener.testRunStarted(runName, mIncludeFilters.size() / mRepeatedRun);
                }
                for (int i = 0; i < mNumTest - mFailedTest; i++) {
                    // TODO: Store the list of expected test cases to verify against it.
                    TestDescription test = new TestDescription(runName + "class", "test" + i);
                    if (!mIncludeFilters.isEmpty() && !mIncludeFilters.contains(test.toString())) {
                        continue;
                    }
                    listener.testStarted(test);
                    listener.testEnded(test, new HashMap<String, Metric>());
                }
                for (int i = 0; i < mFailedTest; i++) {
                    TestDescription test = new TestDescription(runName + "class", "fail" + i);
                    if (!mIncludeFilters.isEmpty() && !mIncludeFilters.contains(test.toString())) {
                        continue;
                    }
                    listener.testStarted(test);
                    listener.testFailed(test, "I failed.");
                    listener.testEnded(test, new HashMap<String, Metric>());
                }
                listener.testRunEnded(0, new HashMap<String, Metric>());
            }
        }

        @Override
        public void addIncludeFilter(String filter) {
            mIncludeFilters.add(filter);
        }

        @Override
        public void addAllIncludeFilters(Set<String> filters) {
            mIncludeFilters.addAll(filters);
        }

        @Override
        public void addExcludeFilter(String filter) {}

        @Override
        public void addAllExcludeFilters(Set<String> filters) {}

        @Override
        public Set<String> getIncludeFilters() {
            return mIncludeFilters;
        }

        @Override
        public Set<String> getExcludeFilters() {
            return null;
        }

        @Override
        public void clearIncludeFilters() {
            mIncludeFilters.clear();
        }

        @Override
        public void clearExcludeFilters() {}
    }

    private class DirectFailureTestObject implements IRemoteTest {
        @Override
        public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
            throw new RuntimeException("early failure!");
        }
    }

    @Before
    public void setUp() {
        mMockLogSaver = EasyMock.createMock(ILogSaver.class);
        mMockLogSaverListener = EasyMock.createStrictMock(ILogSaverListener.class);

        mMockListener = EasyMock.createMock(ITestInvocationListener.class);
        mTestList = new ArrayList<>();
        mMockTest = EasyMock.createMock(ITestInterface.class);
        mTestList.add(mMockTest);
        mTargetPrepList = new ArrayList<>();
        mMockPrep = EasyMock.createMock(ITargetPreparer.class);
        mMockCleaner = EasyMock.createMock(ITargetCleaner.class);
        mTargetPrepList.add(mMockPrep);
        mTargetPrepList.add(mMockCleaner);
        mMapDeviceTargetPreparer = new LinkedHashMap<>();
        mMapDeviceTargetPreparer.put(DEFAULT_DEVICE_NAME, mTargetPrepList);

        mMultiTargetPrepList = new ArrayList<>();
        mMockBuildInfo = EasyMock.createMock(IBuildInfo.class);
        mMockDevice = EasyMock.createMock(ITestDevice.class);
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        mTestList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));

        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
    }

    /** Helper for replaying mocks. */
    private void replayMocks() {
        EasyMock.replay(mMockListener, mMockLogSaver, mMockLogSaverListener, mMockDevice);
        for (IRemoteTest test : mTestList) {
            EasyMock.replay(test);
        }
        for (ITargetPreparer prep : mTargetPrepList) {
            try {
                EasyMock.replay(prep);
            } catch (IllegalArgumentException e) {
                // ignore
            }
        }
    }

    /** Helper for verifying mocks. */
    private void verifyMocks() {
        EasyMock.verify(mMockListener, mMockLogSaver, mMockLogSaverListener, mMockDevice);
        for (IRemoteTest test : mTestList) {
            EasyMock.verify(test);
        }
        for (ITargetPreparer prep : mTargetPrepList) {
            try {
                EasyMock.verify(prep);
            } catch (IllegalArgumentException e) {
                // ignore
            }
        }
    }

    /**
     * Test that {@link ModuleDefinition#run(ITestInvocationListener)} is properly going through the
     * execution flow.
     */
    @Test
    public void testRun() throws Exception {
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        mMockTest.setBuild(EasyMock.eq(mMockBuildInfo));
        mMockTest.setDevice(EasyMock.eq(mMockDevice));
        mMockTest.run((ITestInvocationListener) EasyMock.anyObject());
        EasyMock.expect(mMockCleaner.isTearDownDisabled()).andStubReturn(false);
        mMockCleaner.tearDown(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo),
                EasyMock.isNull());
        mMockListener.testRunStarted(
                EasyMock.eq(MODULE_NAME), EasyMock.eq(0), EasyMock.eq(0), EasyMock.anyLong());
        mMockListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
        replayMocks();
        mModule.run(mMockListener);
        verifyMocks();
    }

    /**
     * If an exception is thrown during tear down, report it for the module if there was no other
     * errors.
     */
    @Test
    public void testRun_tearDownException() throws Exception {
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        mMockTest.setBuild(EasyMock.eq(mMockBuildInfo));
        mMockTest.setDevice(EasyMock.eq(mMockDevice));
        mMockTest.run((ITestInvocationListener) EasyMock.anyObject());
        EasyMock.expect(mMockCleaner.isTearDownDisabled()).andStubReturn(false);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        // Exception thrown during tear down do not bubble up to invocation.
        EasyMock.expectLastCall().andThrow(new RuntimeException("teardown failed"));
        mMockListener.testRunStarted(
                EasyMock.eq(MODULE_NAME), EasyMock.eq(0), EasyMock.eq(0), EasyMock.anyLong());
        mMockListener.testRunFailed("teardown failed");
        mMockListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
        replayMocks();
        mModule.run(mMockListener);
        verifyMocks();
    }

    /**
     * In case of multiple run failures happening, ensure we have some way to get them all
     * eventually.
     */
    @Test
    public void testRun_aggregateRunFailures() throws Exception {
        final int testCount = 4;
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(new TestObject("run1", testCount, false, true));
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        // no isTearDownDisabled() expected for setup
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isTearDownDisabled()).andStubReturn(false);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        // Exception thrown during tear down do not bubble up to invocation.
        EasyMock.expectLastCall().andThrow(new RuntimeException("teardown failed"));
        mMockListener.testRunStarted(MODULE_NAME, testCount);
        for (int i = 0; i < 1; i++) {
            mMockListener.testStarted((TestDescription) EasyMock.anyObject(), EasyMock.anyLong());
            mMockListener.testEnded(
                    (TestDescription) EasyMock.anyObject(),
                    EasyMock.anyLong(),
                    EasyMock.<HashMap<String, Metric>>anyObject());
        }
        mMockListener.testFailed(EasyMock.anyObject(), EasyMock.anyObject());
        String aggError =
                "unresponsive\n====Next Error====\n"
                        + "Module fakeName only ran 1 out of 4 expected tests.\n====Next "
                        + "Error====\nteardown failed";
        mMockListener.testRunFailed(aggError);
        mMockListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

        // There was a module failure so a bugreport should be captured.
        EasyMock.expect(mMockDevice.getIDevice()).andStubReturn(EasyMock.createMock(IDevice.class));
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn("SERIAL");
        EasyMock.expect(
                        mMockDevice.logBugreport(
                                EasyMock.eq("module-fakeName-failure-SERIAL-bugreport"),
                                EasyMock.anyObject()))
                .andReturn(true);

        replayMocks();
        CollectingTestListener errorChecker = new CollectingTestListener();
        // DeviceUnresponsive should not throw since it indicates that the device was recovered.
        mModule.run(new ResultForwarder(mMockListener, errorChecker));
        // Only one module
        assertEquals(1, mModule.getTestsResults().size());
        assertEquals(0, mModule.getTestsResults().get(0).getNumCompleteTests());
        verifyMocks();
        // Check that the error aggregates
        List<TestRunResult> res = errorChecker.getTestRunAttempts(MODULE_NAME);
        assertEquals(1, res.size());
        assertTrue(res.get(0).isRunFailure());
        assertEquals(aggError, res.get(0).getRunFailureMessage());
    }

    /** Test that Module definition properly parse tokens out of the configuration description. */
    @Test
    public void testParseTokens() throws Exception {
        Configuration config = new Configuration("", "");
        ConfigurationDescriptor descriptor = config.getConfigurationDescription();
        descriptor.addMetaData(ITestSuite.TOKEN_KEY, Arrays.asList("SIM_CARD"));
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        mTestList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        config);
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        replayMocks();
        assertEquals(1, mModule.getRequiredTokens().size());
        assertEquals(TokenProperty.SIM_CARD, mModule.getRequiredTokens().iterator().next());
        verifyMocks();
    }

    /**
     * Test that {@link ModuleDefinition#run(ITestInvocationListener)} is properly going through the
     * execution flow and skip target preparers if disabled.
     */
    @Test
    public void testRun_disabledPreparation() throws Exception {
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        // No setup and teardown expected from preparers.
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(true);
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(true);
        mMockTest.setBuild(EasyMock.eq(mMockBuildInfo));
        mMockTest.setDevice(EasyMock.eq(mMockDevice));
        mMockTest.run((ITestInvocationListener) EasyMock.anyObject());
        mMockListener.testRunStarted(
                EasyMock.eq(MODULE_NAME), EasyMock.eq(0), EasyMock.eq(0), EasyMock.anyLong());
        mMockListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
        replayMocks();
        mModule.run(mMockListener);
        verifyMocks();
    }

    /**
     * Test that {@link ModuleDefinition#run(ITestInvocationListener)} is properly going through the
     * execution flow and skip target cleanup if teardown is disabled.
     */
    @Test
    public void testRun_disabledTearDown() throws Exception {
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        // Setup expected from preparers.
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        mMockTest.setBuild(EasyMock.eq(mMockBuildInfo));
        mMockTest.setDevice(EasyMock.eq(mMockDevice));
        mMockTest.run((ITestInvocationListener) EasyMock.anyObject());
        EasyMock.expect(mMockCleaner.isTearDownDisabled()).andStubReturn(true);
        // But no teardown expected from Cleaner.
        mMockListener.testRunStarted(
                EasyMock.eq(MODULE_NAME), EasyMock.eq(0), EasyMock.eq(0), EasyMock.anyLong());
        mMockListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
        replayMocks();
        mModule.run(mMockListener);
        verifyMocks();
    }

    /**
     * Test that {@link ModuleDefinition#run(ITestInvocationListener)} properly propagate an early
     * preparation failure.
     */
    @Test
    public void testRun_failPreparation() throws Exception {
        final String exceptionMessage = "ouch I failed";
        mTargetPrepList.clear();
        mTargetPrepList.add(
                new BaseTargetPreparer() {
                    @Override
                    public void setUp(ITestDevice device, IBuildInfo buildInfo)
                            throws TargetSetupError, BuildError, DeviceNotAvailableException {
                        DeviceDescriptor nullDescriptor = null;
                        throw new TargetSetupError(exceptionMessage, nullDescriptor);
                    }
                });
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        mTestList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mMockCleaner.tearDown(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo),
                EasyMock.isNull());
        mMockListener.testRunStarted(EasyMock.eq(MODULE_NAME), EasyMock.eq(1));
        mMockListener.testRunFailed(EasyMock.contains(exceptionMessage));
        mMockListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
        replayMocks();
        mModule.run(mMockListener);
        verifyMocks();
    }

    /**
     * Test that {@link ModuleDefinition#run(ITestInvocationListener)} properly propagate an early
     * preparation failure, even for a runtime exception.
     */
    @Test
    public void testRun_failPreparation_runtime() throws Exception {
        final String exceptionMessage = "ouch I failed";
        mTargetPrepList.clear();
        mTargetPrepList.add(
                new BaseTargetPreparer() {
                    @Override
                    public void setUp(ITestDevice device, IBuildInfo buildInfo)
                            throws TargetSetupError, BuildError, DeviceNotAvailableException {
                        throw new RuntimeException(exceptionMessage);
                    }
                });
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        mTestList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        mMockListener.testRunStarted(EasyMock.eq(MODULE_NAME), EasyMock.eq(1));
        mMockListener.testRunFailed(EasyMock.contains(exceptionMessage));
        mMockListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
        replayMocks();
        mModule.run(mMockListener);
        verifyMocks();
    }

    @Test
    public void testRun_failPreparation_error() throws Exception {
        final String exceptionMessage = "ouch I failed";
        mTargetPrepList.clear();
        mTargetPrepList.add(
                new BaseTargetPreparer() {
                    @Override
                    public void setUp(ITestDevice device, IBuildInfo buildInfo)
                            throws TargetSetupError, BuildError, DeviceNotAvailableException {
                        // Throw AssertionError
                        Assert.assertNull(exceptionMessage);
                    }
                });
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        mTestList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        mMockListener.testRunStarted(EasyMock.eq(MODULE_NAME), EasyMock.eq(1));
        mMockListener.testRunFailed(EasyMock.contains(exceptionMessage));
        mMockListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
        replayMocks();
        mModule.run(mMockListener);
        verifyMocks();
    }

    /**
     * Test that {@link ModuleDefinition#run(ITestInvocationListener)} properly pass the results of
     * early failures to both main listener and module listeners.
     */
    @Test
    public void testRun_failPreparation_moduleListener() throws Exception {
        ITestInvocationListener mockModuleListener =
                EasyMock.createMock(ITestInvocationListener.class);
        final String exceptionMessage = "ouch I failed";
        mTargetPrepList.clear();
        mTargetPrepList.add(
                new BaseTargetPreparer() {
                    @Override
                    public void setUp(ITestDevice device, IBuildInfo buildInfo)
                            throws TargetSetupError, BuildError, DeviceNotAvailableException {
                        DeviceDescriptor nullDescriptor = null;
                        throw new TargetSetupError(exceptionMessage, nullDescriptor);
                    }
                });
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        mTestList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        mMockListener.testRunStarted(EasyMock.eq(MODULE_NAME), EasyMock.eq(1));
        mMockListener.testRunFailed(EasyMock.contains(exceptionMessage));
        mMockListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
        // Ensure that module listeners receive the callbacks too.
        mockModuleListener.testRunStarted(EasyMock.eq(MODULE_NAME), EasyMock.eq(1));
        mockModuleListener.testRunFailed(EasyMock.contains(exceptionMessage));
        mockModuleListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

        EasyMock.replay(mockModuleListener);
        replayMocks();
        mModule.run(mMockListener, Arrays.asList(mockModuleListener), null);
        verifyMocks();
        EasyMock.verify(mockModuleListener);
    }

    /** Test that {@link ModuleDefinition#run(ITestInvocationListener)} */
    @Test
    public void testRun_failPreparation_unresponsive() throws Exception {
        final String exceptionMessage = "ouch I failed";
        mTargetPrepList.clear();
        mTargetPrepList.add(
                new BaseTargetPreparer() {
                    @Override
                    public void setUp(ITestDevice device, IBuildInfo buildInfo)
                            throws TargetSetupError, BuildError, DeviceNotAvailableException {
                        throw new DeviceUnresponsiveException(exceptionMessage, "serial");
                    }
                });
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        mTestList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        mMockListener.testRunStarted(EasyMock.eq(MODULE_NAME), EasyMock.eq(1));
        mMockListener.testRunFailed(EasyMock.contains(exceptionMessage));
        mMockListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
        replayMocks();
        try {
            mModule.run(mMockListener);
            fail("Should have thrown an exception.");
        } catch (DeviceUnresponsiveException expected) {
            // The exception is still bubbled up.
            assertEquals(exceptionMessage, expected.getMessage());
        }
        verifyMocks();
    }

    /**
     * Test that {@link ModuleDefinition#run(ITestInvocationListener)} is properly going through the
     * execution flow with actual test callbacks.
     */
    @Test
    public void testRun_fullPass() throws Exception {
        final int testCount = 5;
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(new TestObject("run1", testCount, false));
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        // no isTearDownDisabled() expected for setup
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isTearDownDisabled()).andStubReturn(false);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        mMockListener.testRunStarted(
                EasyMock.eq(MODULE_NAME),
                EasyMock.eq(testCount),
                EasyMock.eq(0),
                EasyMock.anyLong());
        for (int i = 0; i < testCount; i++) {
            mMockListener.testStarted((TestDescription) EasyMock.anyObject(), EasyMock.anyLong());
            mMockListener.testEnded(
                    (TestDescription) EasyMock.anyObject(),
                    EasyMock.anyLong(),
                    EasyMock.<HashMap<String, Metric>>anyObject());
        }
        mMockListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
        replayMocks();
        mModule.run(mMockListener);
        verifyMocks();
    }

    /**
     * Test that {@link ModuleDefinition#run(ITestInvocationListener)} is properly going through the
     * execution flow with actual test callbacks.
     */
    @Test
    public void testRun_partialRun() throws Exception {
        final int testCount = 4;
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(new TestObject("run1", testCount, true));
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        // no isTearDownDisabled() expected for setup
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isTearDownDisabled()).andStubReturn(false);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice),
                EasyMock.eq(mMockBuildInfo),
                EasyMock.isA(DeviceNotAvailableException.class));
        mMockListener.testRunStarted(
                EasyMock.eq(MODULE_NAME),
                EasyMock.eq(testCount),
                EasyMock.eq(0),
                EasyMock.anyLong());
        for (int i = 0; i < 3; i++) {
            mMockListener.testStarted((TestDescription) EasyMock.anyObject(), EasyMock.anyLong());
            mMockListener.testEnded(
                    (TestDescription) EasyMock.anyObject(),
                    EasyMock.anyLong(),
                    EasyMock.<HashMap<String, Metric>>anyObject());
        }
        mMockListener.testFailed(EasyMock.anyObject(), EasyMock.anyObject());
        mMockListener.testRunFailed(EasyMock.anyObject());
        mMockListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
        // Recovery is disabled during tearDown
        EasyMock.expect(mMockDevice.getRecoveryMode()).andReturn(RecoveryMode.AVAILABLE);
        mMockDevice.setRecoveryMode(RecoveryMode.NONE);
        mMockDevice.setRecoveryMode(RecoveryMode.AVAILABLE);

        EasyMock.expect(mMockDevice.getSerialNumber()).andStubReturn("serial");
        replayMocks();
        try {
            mModule.run(mMockListener);
            fail("Should have thrown an exception.");
        } catch (DeviceNotAvailableException expected) {
            // expected
        }
        // Only one module
        assertEquals(1, mModule.getTestsResults().size());
        assertEquals(2, mModule.getTestsResults().get(0).getNumCompleteTests());
        verifyMocks();
    }

    @Test
    public void testRun_partialRun_error() throws Exception {
        final int testCount = 4;
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(new TestObject("run1", testCount, false, false, true));
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        // no isTearDownDisabled() expected for setup
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isTearDownDisabled()).andStubReturn(false);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        mMockListener.testRunStarted(
                EasyMock.eq(MODULE_NAME),
                EasyMock.eq(testCount),
                EasyMock.eq(0),
                EasyMock.anyLong());
        for (int i = 0; i < 3; i++) {
            mMockListener.testStarted((TestDescription) EasyMock.anyObject(), EasyMock.anyLong());
            mMockListener.testEnded(
                    (TestDescription) EasyMock.anyObject(),
                    EasyMock.anyLong(),
                    EasyMock.<HashMap<String, Metric>>anyObject());
        }
        mMockListener.testFailed(EasyMock.anyObject(), EasyMock.anyObject());
        mMockListener.testRunFailed(EasyMock.anyObject());
        mMockListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

        // Run failed
        EasyMock.expect(mMockDevice.getIDevice()).andReturn(EasyMock.createMock(IDevice.class));
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn("serial");
        EasyMock.expect(mMockDevice.logBugreport(EasyMock.anyObject(), EasyMock.anyObject()))
                .andReturn(true);

        replayMocks();
        mModule.run(mMockListener);
        // Only one module
        assertEquals(1, mModule.getTestsResults().size());
        assertEquals(2, mModule.getTestsResults().get(0).getNumCompleteTests());
        assertEquals("assert error", mModule.getTestsResults().get(0).getRunFailureMessage());
        verifyMocks();
    }

    /**
     * Test that when a module is created with some particular informations, the resulting {@link
     * IInvocationContext} of the module is properly populated.
     */
    @Test
    public void testAbiSetting() throws Exception {
        final int testCount = 5;
        IConfiguration config = new Configuration("", "");
        ConfigurationDescriptor descriptor = new ConfigurationDescriptor();
        descriptor.setAbi(new Abi("arm", "32"));
        descriptor.setModuleName(MODULE_NAME);
        config.setConfigurationObject(
                Configuration.CONFIGURATION_DESCRIPTION_TYPE_NAME, descriptor);
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(new TestObject("run1", testCount, false));
        mModule =
                new ModuleDefinition(
                        "arm32 " + MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        config);
        // Check that the invocation module created has expected informations
        IInvocationContext moduleContext = mModule.getModuleInvocationContext();
        assertEquals(
                MODULE_NAME,
                moduleContext.getAttributes().get(ModuleDefinition.MODULE_NAME).get(0));
        assertEquals("arm", moduleContext.getAttributes().get(ModuleDefinition.MODULE_ABI).get(0));
        assertEquals(
                "arm32 " + MODULE_NAME,
                moduleContext.getAttributes().get(ModuleDefinition.MODULE_ID).get(0));
    }

    /**
     * Test running a module when the configuration has a module controller object that force a full
     * bypass of the module.
     */
    @Test
    public void testModuleController_fullBypass() throws Exception {
        IConfiguration config = new Configuration("", "");
        BaseModuleController moduleConfig =
                new BaseModuleController() {
                    @Override
                    public RunStrategy shouldRun(IInvocationContext context) {
                        return RunStrategy.FULL_MODULE_BYPASS;
                    }
                };
        config.setConfigurationObject(ModuleDefinition.MODULE_CONTROLLER, moduleConfig);
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(
                new IRemoteTest() {
                    @Override
                    public void run(ITestInvocationListener listener)
                            throws DeviceNotAvailableException {
                        listener.testRunStarted("test", 1);
                        listener.testFailed(
                                new TestDescription("failedclass", "failedmethod"), "trace");
                    }
                });
        mTargetPrepList.clear();
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        config);
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        // module is completely skipped, no tests is recorded.
        replayMocks();
        mModule.run(mMockListener, null, null);
        verifyMocks();
    }

    /**
     * Test running a module when the configuration has a module controller object that force to
     * skip all the module test cases.
     */
    @Test
    public void testModuleController_skipTestCases() throws Exception {
        IConfiguration config = new Configuration("", "");
        BaseModuleController moduleConfig =
                new BaseModuleController() {
                    @Override
                    public RunStrategy shouldRun(IInvocationContext context) {
                        return RunStrategy.SKIP_MODULE_TESTCASES;
                    }
                };
        config.setConfigurationObject(ModuleDefinition.MODULE_CONTROLLER, moduleConfig);
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(
                new IRemoteTest() {
                    @Override
                    public void run(ITestInvocationListener listener)
                            throws DeviceNotAvailableException {
                        TestDescription tid = new TestDescription("class", "method");
                        listener.testRunStarted("test", 1);
                        listener.testStarted(tid);
                        listener.testFailed(tid, "I failed");
                        listener.testEnded(tid, new HashMap<String, Metric>());
                        listener.testRunEnded(0, new HashMap<String, Metric>());
                    }
                });
        mTargetPrepList.clear();
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        config);
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        // expect the module to run but tests to be ignored
        mMockListener.testRunStarted(
                EasyMock.anyObject(), EasyMock.anyInt(), EasyMock.eq(0), EasyMock.anyLong());
        mMockListener.testStarted(EasyMock.anyObject(), EasyMock.anyLong());
        mMockListener.testIgnored(EasyMock.anyObject());
        mMockListener.testEnded(
                EasyMock.anyObject(),
                EasyMock.anyLong(),
                (HashMap<String, Metric>) EasyMock.anyObject());
        mMockListener.testRunEnded(
                EasyMock.anyLong(), (HashMap<String, Metric>) EasyMock.anyObject());
        replayMocks();
        mModule.run(mMockListener, null, null);
        verifyMocks();
    }

    /** Test {@link IRemoteTest} that log a file during its run. */
    public class TestLogClass implements ITestInterface {

        @Override
        public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
            listener.testLog(
                    "testlogclass",
                    LogDataType.TEXT,
                    new ByteArrayInputStreamSource("".getBytes()));
        }

        @Override
        public void setBuild(IBuildInfo buildInfo) {}

        @Override
        public void setDevice(ITestDevice device) {}

        @Override
        public ITestDevice getDevice() {
            return null;
        }
    }

    /**
     * Test that the invocation level result_reporter receive the testLogSaved information from the
     * modules.
     *
     * <p>The {@link LogSaverResultForwarder} from the module is expected to log the file and ensure
     * that it passes the information to the {@link LogSaverResultForwarder} from the {@link
     * TestInvocation} in order for final result_reporter to know about logged files.
     */
    @Test
    public void testModule_LogSaverResultForwarder() throws Exception {
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(new TestLogClass());
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.setLogSaver(mMockLogSaver);
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        // no isTearDownDisabled() expected for setup
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isTearDownDisabled()).andStubReturn(false);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        mMockLogSaverListener.setLogSaver(mMockLogSaver);
        // The final reporter still receive the testLog signal
        mMockLogSaverListener.testLog(
                EasyMock.eq("testlogclass"), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());

        LogFile loggedFile = new LogFile("path", "url", LogDataType.TEXT);
        EasyMock.expect(
                        mMockLogSaver.saveLogData(
                                EasyMock.eq("testlogclass"),
                                EasyMock.eq(LogDataType.TEXT),
                                EasyMock.anyObject()))
                .andReturn(loggedFile);
        // mMockLogSaverListener should receive the testLogSaved call even from the module
        mMockLogSaverListener.testLogSaved(
                EasyMock.eq("testlogclass"),
                EasyMock.eq(LogDataType.TEXT),
                EasyMock.anyObject(),
                EasyMock.eq(loggedFile));
        mMockLogSaverListener.logAssociation("testlogclass", loggedFile);

        mMockLogSaverListener.testRunStarted(MODULE_NAME, 0);
        mMockLogSaverListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

        // Simulate how the invoker actually put the log saver
        replayMocks();
        LogSaverResultForwarder forwarder =
                new LogSaverResultForwarder(mMockLogSaver, Arrays.asList(mMockLogSaverListener));
        mModule.run(forwarder);
        verifyMocks();
    }

    /**
     * Test that the {@link IModuleController} object can override the behavior of the capture of
     * the failure.
     */
    @Test
    public void testOverrideModuleConfig() throws Exception {
        // failure listener with capture logcat on failure and screenshot on failure.
        List<ITestDevice> listDevice = new ArrayList<>();
        listDevice.add(mMockDevice);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn("Serial");
        TestFailureListener failureListener = new TestFailureListener(listDevice, true, false);
        failureListener.setLogger(mMockListener);
        IConfiguration config = new Configuration("", "");
        TestFailureModuleController moduleConfig = new TestFailureModuleController();
        OptionSetter setter = new OptionSetter(moduleConfig);
        // Module option should override the logcat on failure
        setter.setOptionValue("bugreportz-on-failure", "false");
        config.setConfigurationObject(ModuleDefinition.MODULE_CONTROLLER, moduleConfig);
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(
                new IRemoteTest() {
                    @Override
                    public void run(ITestInvocationListener listener)
                            throws DeviceNotAvailableException {
                        listener.testFailed(
                                new TestDescription("failedclass", "failedmethod"), "trace");
                    }
                });
        mTargetPrepList.clear();
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        config);
        mModule.setLogSaver(mMockLogSaver);
        mMockListener.testRunStarted(
                EasyMock.eq("fakeName"), EasyMock.eq(0), EasyMock.eq(0), EasyMock.anyLong());
        mMockListener.testRunEnded(
                EasyMock.anyLong(), (HashMap<String, Metric>) EasyMock.anyObject());
        replayMocks();
        mModule.run(mMockListener, null, failureListener);
        verifyMocks();
    }

    /** Test when the test yields a DeviceUnresponsive exception. */
    @Test
    public void testRun_partialRun_deviceUnresponsive() throws Exception {
        final int testCount = 4;
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(new TestObject("run1", testCount, false, true));
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        // no isTearDownDisabled() expected for setup
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isTearDownDisabled()).andStubReturn(false);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        mMockListener.testRunStarted(
                EasyMock.eq(MODULE_NAME),
                EasyMock.eq(testCount),
                EasyMock.eq(0),
                EasyMock.anyLong());
        for (int i = 0; i < 1; i++) {
            mMockListener.testStarted((TestDescription) EasyMock.anyObject(), EasyMock.anyLong());
            mMockListener.testEnded(
                    (TestDescription) EasyMock.anyObject(),
                    EasyMock.anyLong(),
                    EasyMock.<HashMap<String, Metric>>anyObject());
        }
        mMockListener.testFailed(EasyMock.anyObject(), EasyMock.anyObject());
        mMockListener.testRunFailed(
                "unresponsive"
                        + TestRunResult.ERROR_DIVIDER
                        + "Module fakeName only ran 1 out of 4 expected tests.");
        mMockListener.testRunEnded(
                EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

        // There was a module failure so a bugreport should be captured.
        EasyMock.expect(mMockDevice.getIDevice()).andStubReturn(EasyMock.createMock(IDevice.class));
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn("SERIAL");
        EasyMock.expect(
                        mMockDevice.logBugreport(
                                EasyMock.eq("module-fakeName-failure-SERIAL-bugreport"),
                                EasyMock.anyObject()))
                .andReturn(true);

        replayMocks();
        // DeviceUnresponsive should not throw since it indicates that the device was recovered.
        mModule.run(mMockListener);
        // Only one module
        assertEquals(1, mModule.getTestsResults().size());
        assertEquals(0, mModule.getTestsResults().get(0).getNumCompleteTests());
        verifyMocks();
    }

    /**
     * Test that when a module level listener is specified it receives the events before the
     * buffering and replay.
     */
    @Test
    public void testRun_moduleLevelListeners() throws Exception {
        mMockListener = EasyMock.createStrictMock(ITestInvocationListener.class);
        final int testCount = 5;
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(new TestObject("run1", testCount, false));
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.setLogSaver(mMockLogSaver);
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        // no isTearDownDisabled() expected for setup
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isTearDownDisabled()).andStubReturn(false);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());

        mMockLogSaverListener.setLogSaver(mMockLogSaver);

        mMockListener.testRunStarted("run1", testCount, 0);
        for (int i = 0; i < testCount; i++) {
            mMockListener.testStarted((TestDescription) EasyMock.anyObject(), EasyMock.anyLong());
            mMockListener.testEnded(
                    (TestDescription) EasyMock.anyObject(),
                    EasyMock.anyLong(),
                    (HashMap<String, Metric>) EasyMock.anyObject());
        }
        mMockListener.testRunEnded(
                EasyMock.anyLong(), (HashMap<String, Metric>) EasyMock.anyObject());

        mMockLogSaverListener.testRunStarted(MODULE_NAME, testCount);
        for (int i = 0; i < testCount; i++) {
            mMockLogSaverListener.testStarted(
                    (TestDescription) EasyMock.anyObject(), EasyMock.anyLong());
            mMockLogSaverListener.testEnded(
                    (TestDescription) EasyMock.anyObject(),
                    EasyMock.anyLong(),
                    (HashMap<String, Metric>) EasyMock.anyObject());
        }
        mMockLogSaverListener.testRunEnded(
                EasyMock.anyLong(), (HashMap<String, Metric>) EasyMock.anyObject());

        // Simulate how the invoker actually put the log saver
        replayMocks();
        LogSaverResultForwarder forwarder =
                new LogSaverResultForwarder(mMockLogSaver, Arrays.asList(mMockLogSaverListener));
        mModule.run(forwarder, Arrays.asList(mMockListener), null);
        verifyMocks();
    }

    /**
     * Test that {@link ModuleDefinition#run(ITestInvocationListener)} is properly going through the
     * execution flow and reports properly when the runner generates multiple runs.
     */
    @Test
    public void testMultiRun() throws Exception {
        final String runName = "baseRun";
        List<IRemoteTest> testList = new ArrayList<>();
        // The runner will generates 2 test runs with 2 test cases each.
        testList.add(new MultiRunTestObject(runName, 2, 2, 0));
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));

        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);

        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isTearDownDisabled()).andStubReturn(false);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        // We expect a total count on the run start so 4, all aggregated under the same run
        mMockListener.testRunStarted(
                EasyMock.eq(MODULE_NAME), EasyMock.eq(4), EasyMock.eq(0), EasyMock.anyLong());
        // The first set of test cases from the first test run.
        for (int i = 0; i < 2; i++) {
            TestDescription testId = new TestDescription(runName + "0class", "test" + i);
            mMockListener.testStarted(EasyMock.eq(testId), EasyMock.anyLong());
            mMockListener.testEnded(
                    EasyMock.eq(testId),
                    EasyMock.anyLong(),
                    (HashMap<String, Metric>) EasyMock.anyObject());
        }
        // The second set of test cases from the second test run
        for (int i = 0; i < 2; i++) {
            TestDescription testId = new TestDescription(runName + "1class", "test" + i);
            mMockListener.testStarted(EasyMock.eq(testId), EasyMock.anyLong());
            mMockListener.testEnded(
                    EasyMock.eq(testId),
                    EasyMock.anyLong(),
                    (HashMap<String, Metric>) EasyMock.anyObject());
        }
        mMockListener.testRunEnded(
                EasyMock.anyLong(), (HashMap<String, Metric>) EasyMock.anyObject());
        replayMocks();
        mModule.run(mMockListener);
        verifyMocks();
    }

    @Test
    public void testRun_earlyFailure() throws Exception {
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(new DirectFailureTestObject());
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);

        EasyMock.expect(mMockDevice.getIDevice()).andReturn(new StubDevice("fake"));
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        // no isTearDownDisabled() expected for setup
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isTearDownDisabled()).andStubReturn(false);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());

        mMockListener.testRunStarted(
                EasyMock.eq("fakeName"), EasyMock.eq(0), EasyMock.eq(0), EasyMock.anyLong());
        mMockListener.testRunFailed("early failure!");
        mMockListener.testRunEnded(
                EasyMock.anyLong(), (HashMap<String, Metric>) EasyMock.anyObject());

        replayMocks();
        mModule.run(mMockListener);
        verifyMocks();
    }

    /** Test retry and reporting all the different attempts. */
    @Test
    public void testMultiRun_multiAttempts() throws Exception {
        final String runName = "baseRun";
        List<IRemoteTest> testList = new ArrayList<>();
        // The runner will generates 2 test runs with 3 test cases each.
        testList.add(new MultiRunTestObject(runName, 3, 2, 1));
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.setRetryStrategy(RetryStrategy.ITERATIONS, false);
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);

        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isTearDownDisabled()).andStubReturn(false);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        // We expect a total count on the run start so 4, all aggregated under the same run
        for (int attempt = 0; attempt < 3; attempt++) {
            mMockListener.testRunStarted(
                    EasyMock.eq(MODULE_NAME),
                    EasyMock.eq(6),
                    EasyMock.eq(attempt),
                    EasyMock.anyLong());
            // The first set of test cases from the first test run.
            TestDescription testId0 = new TestDescription(runName + "0class", "test0");
            mMockListener.testStarted(EasyMock.eq(testId0), EasyMock.anyLong());
            mMockListener.testEnded(
                    EasyMock.eq(testId0),
                    EasyMock.anyLong(),
                    (HashMap<String, Metric>) EasyMock.anyObject());
            TestDescription testFail0 = new TestDescription(runName + "0class", "fail0");
            mMockListener.testStarted(EasyMock.eq(testFail0), EasyMock.anyLong());
            mMockListener.testFailed(EasyMock.eq(testFail0), EasyMock.anyObject());
            mMockListener.testEnded(
                    EasyMock.eq(testFail0),
                    EasyMock.anyLong(),
                    (HashMap<String, Metric>) EasyMock.anyObject());
            TestDescription testId1 = new TestDescription(runName + "0class", "test1");
            mMockListener.testStarted(EasyMock.eq(testId1), EasyMock.anyLong());
            mMockListener.testEnded(
                    EasyMock.eq(testId1),
                    EasyMock.anyLong(),
                    (HashMap<String, Metric>) EasyMock.anyObject());

            // The second set of test cases from the second test run
            TestDescription testId0_1 = new TestDescription(runName + "1class", "test0");
            mMockListener.testStarted(EasyMock.eq(testId0_1), EasyMock.anyLong());
            mMockListener.testEnded(
                    EasyMock.eq(testId0_1),
                    EasyMock.anyLong(),
                    (HashMap<String, Metric>) EasyMock.anyObject());
            TestDescription testFail0_1 = new TestDescription(runName + "1class", "fail0");
            mMockListener.testStarted(EasyMock.eq(testFail0_1), EasyMock.anyLong());
            mMockListener.testFailed(EasyMock.eq(testFail0_1), EasyMock.anyObject());
            mMockListener.testEnded(
                    EasyMock.eq(testFail0_1),
                    EasyMock.anyLong(),
                    (HashMap<String, Metric>) EasyMock.anyObject());
            TestDescription testId1_1 = new TestDescription(runName + "1class", "test1");
            mMockListener.testStarted(EasyMock.eq(testId1_1), EasyMock.anyLong());
            mMockListener.testEnded(
                    EasyMock.eq(testId1_1),
                    EasyMock.anyLong(),
                    (HashMap<String, Metric>) EasyMock.anyObject());

            mMockListener.testRunEnded(
                    EasyMock.anyLong(), (HashMap<String, Metric>) EasyMock.anyObject());
        }
        replayMocks();
        mModule.run(mMockListener, null, null, 3);
        verifyMocks();
    }

    /** Test retry and reporting all the different attempts when retrying failures. */
    @Test
    public void testMultiRun_multiAttempts_filter() throws Exception {
        final String runName = "baseRun";
        List<IRemoteTest> testList = new ArrayList<>();
        // The runner will generates 2 test runs with 3 test cases each. (2 passes and 1 fail)
        testList.add(new MultiRunTestObject(runName, 3, 2, 1));
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.setRetryStrategy(RetryStrategy.RETRY_ANY_FAILURE, false);

        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);

        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isTearDownDisabled()).andStubReturn(false);
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        // We expect a total count on the run start so 4, all aggregated under the same run
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt == 0) {
                mMockListener.testRunStarted(
                        EasyMock.eq(MODULE_NAME),
                        EasyMock.eq(6),
                        EasyMock.eq(attempt),
                        EasyMock.anyLong());
            } else {
                mMockListener.testRunStarted(
                        EasyMock.eq(MODULE_NAME),
                        EasyMock.eq(2),
                        EasyMock.eq(attempt),
                        EasyMock.anyLong());
            }
            // The first set of test cases from the first test run.
            if (attempt < 1) {
                TestDescription testId0 = new TestDescription(runName + "0class", "test0");
                mMockListener.testStarted(EasyMock.eq(testId0), EasyMock.anyLong());
                mMockListener.testEnded(
                        EasyMock.eq(testId0),
                        EasyMock.anyLong(),
                        EasyMock.<HashMap<String, Metric>>anyObject());
            }
            TestDescription testFail0 = new TestDescription(runName + "0class", "fail0");
            mMockListener.testStarted(EasyMock.eq(testFail0), EasyMock.anyLong());
            mMockListener.testFailed(EasyMock.eq(testFail0), EasyMock.anyObject());
            mMockListener.testEnded(
                    EasyMock.eq(testFail0),
                    EasyMock.anyLong(),
                    EasyMock.<HashMap<String, Metric>>anyObject());
            if (attempt < 1) {
                TestDescription testId1 = new TestDescription(runName + "0class", "test1");
                mMockListener.testStarted(EasyMock.eq(testId1), EasyMock.anyLong());
                mMockListener.testEnded(
                        EasyMock.eq(testId1),
                        EasyMock.anyLong(),
                        EasyMock.<HashMap<String, Metric>>anyObject());
            }

            // The second set of test cases from the second test run
            if (attempt < 1) {
                TestDescription testId0_1 = new TestDescription(runName + "1class", "test0");
                mMockListener.testStarted(EasyMock.eq(testId0_1), EasyMock.anyLong());
                mMockListener.testEnded(
                        EasyMock.eq(testId0_1),
                        EasyMock.anyLong(),
                        EasyMock.<HashMap<String, Metric>>anyObject());
            }
            TestDescription testFail0_1 = new TestDescription(runName + "1class", "fail0");
            mMockListener.testStarted(EasyMock.eq(testFail0_1), EasyMock.anyLong());
            mMockListener.testFailed(EasyMock.eq(testFail0_1), EasyMock.anyObject());
            mMockListener.testEnded(
                    EasyMock.eq(testFail0_1),
                    EasyMock.anyLong(),
                    EasyMock.<HashMap<String, Metric>>anyObject());
            if (attempt < 1) {
                TestDescription testId1_1 = new TestDescription(runName + "1class", "test1");
                mMockListener.testStarted(EasyMock.eq(testId1_1), EasyMock.anyLong());
                mMockListener.testEnded(
                        EasyMock.eq(testId1_1),
                        EasyMock.anyLong(),
                        EasyMock.<HashMap<String, Metric>>anyObject());
            }
            mMockListener.testRunEnded(
                    EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
        }
        replayMocks();
        mModule.run(mMockListener, null, null, 3);
        verifyMocks();
    }
}
