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
package com.android.tradefed.testtype.suite;

import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Unit tests for {@link ITestSuite} when used with multiple devices. */
@RunWith(JUnit4.class)
public class ITestSuiteMultiTest {

    private static final String EMPTY_CONFIG = "empty";
    private static final String TEST_CONFIG_NAME = "test";

    private ITestSuite mTestSuite;
    private ITestInvocationListener mMockListener;
    private IInvocationContext mContext;
    private ITestDevice mMockDevice1;
    private IBuildInfo mMockBuildInfo1;
    private ITestDevice mMockDevice2;
    private IBuildInfo mMockBuildInfo2;

    static class TestSuiteMultiDeviceImpl extends ITestSuite {
        private int mNumTests = 1;

        public TestSuiteMultiDeviceImpl() {}

        public TestSuiteMultiDeviceImpl(int numTests) {
            mNumTests = numTests;
        }

        @Override
        public LinkedHashMap<String, IConfiguration> loadTests() {
            LinkedHashMap<String, IConfiguration> testConfig = new LinkedHashMap<>();
            try {
                for (int i = 1; i < mNumTests; i++) {
                    IConfiguration extraConfig =
                            ConfigurationFactory.getInstance()
                                    .createConfigurationFromArgs(new String[] {EMPTY_CONFIG});
                    MultiDeviceStubTest test = new MultiDeviceStubTest();
                    test.setExceptedDevice(2);
                    extraConfig.setTest(test);
                    testConfig.put(TEST_CONFIG_NAME + i, extraConfig);
                }
            } catch (ConfigurationException e) {
                CLog.e(e);
                throw new RuntimeException(e);
            }
            return testConfig;
        }
    }

    @Before
    public void setUp() {
        mTestSuite = new TestSuiteMultiDeviceImpl(2);
        mMockListener = EasyMock.createMock(ITestInvocationListener.class);
        // 2 devices and 2 builds
        mMockDevice1 = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(mMockDevice1.getSerialNumber()).andStubReturn("SERIAL1");
        mMockBuildInfo1 = EasyMock.createMock(IBuildInfo.class);
        mMockDevice2 = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(mMockDevice2.getSerialNumber()).andStubReturn("SERIAL2");
        mMockBuildInfo2 = EasyMock.createMock(IBuildInfo.class);
    }

    /**
     * Test that a multi-devices test will execute through without hitting issues since all
     * structures are properly injected.
     */
    @Test
    public void testMultiDeviceITestSuite() throws Exception {
        mTestSuite.setDevice(mMockDevice1);
        mTestSuite.setBuild(mMockBuildInfo1);

        mContext = new InvocationContext();
        mContext.addAllocatedDevice("device1", mMockDevice1);
        mContext.addDeviceBuildInfo("device1", mMockBuildInfo1);
        mContext.addAllocatedDevice("device2", mMockDevice2);
        mContext.addDeviceBuildInfo("device2", mMockBuildInfo2);
        mTestSuite.setInvocationContext(mContext);

        mTestSuite.setSystemStatusChecker(new ArrayList<>());
        mMockListener.testModuleStarted(EasyMock.anyObject());
        mMockListener.testRunStarted("test1", 2);
        TestIdentifier test1 =
                new TestIdentifier(MultiDeviceStubTest.class.getSimpleName(), "test0");
        mMockListener.testStarted(test1, 0l);
        mMockListener.testEnded(test1, 5l, Collections.emptyMap());
        TestIdentifier test2 =
                new TestIdentifier(MultiDeviceStubTest.class.getSimpleName(), "test1");
        mMockListener.testStarted(test2, 0l);
        mMockListener.testEnded(test2, 5l, Collections.emptyMap());
        mMockListener.testRunEnded(EasyMock.anyLong(), EasyMock.anyObject());
        mMockListener.testModuleEnded();
        EasyMock.replay(
                mMockListener, mMockBuildInfo1, mMockBuildInfo2, mMockDevice1, mMockDevice2);
        mTestSuite.run(mMockListener);
        EasyMock.verify(
                mMockListener, mMockBuildInfo1, mMockBuildInfo2, mMockDevice1, mMockDevice2);
    }
}
