/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.invoker.sandbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IBuildProvider;
import com.android.tradefed.build.StubBuildProvider;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationDef;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationFactory;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInvocation.Stage;
import com.android.tradefed.sandbox.SandboxOptions;
import com.android.tradefed.targetprep.ITargetCleaner;
import com.android.tradefed.targetprep.TargetSetupError;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/** Unit tests for {@link ParentSandboxInvocationExecution}. */
@RunWith(JUnit4.class)
public class ParentSandboxInvocationExecutionTest {

    private ParentSandboxInvocationExecution mParentSandbox;
    private IConfiguration mConfig;
    private IInvocationContext mContext;
    private IConfigurationFactory mMockFactory;
    private SandboxOptions mOptions;
    private ITargetCleaner mMockPreparer;
    private ITestDevice mMockDevice;

    @Before
    public void setUp() {
        mMockFactory = Mockito.mock(IConfigurationFactory.class);
        mMockPreparer = Mockito.mock(ITargetCleaner.class);
        mMockDevice = Mockito.mock(ITestDevice.class);

        mParentSandbox =
                new ParentSandboxInvocationExecution() {
                    @Override
                    protected IConfigurationFactory getFactory() {
                        return mMockFactory;
                    }

                    @Override
                    protected String getAdbVersion() {
                        return "0";
                    }
                };
        mContext = new InvocationContext();
        mContext.addAllocatedDevice(ConfigurationDef.DEFAULT_DEVICE_NAME, mMockDevice);
        mContext.addDeviceBuildInfo(ConfigurationDef.DEFAULT_DEVICE_NAME, new BuildInfo());
        mConfig = new Configuration("test", "test");
        mOptions = new SandboxOptions();
    }

    @Test
    public void testDefaultSkipSetup_tearDown() throws Throwable {
        mParentSandbox.doSetup(mContext, mConfig, null);
        mParentSandbox.doTeardown(mContext, mConfig, null, null);
        mParentSandbox.doCleanUp(mContext, mConfig, null);

        verify(mMockFactory, times(0)).createConfigurationFromArgs(Mockito.any());
        verify(mMockDevice, times(0)).getIDevice();
    }

    @Test
    public void testParentConfig() throws Throwable {
        mConfig.setConfigurationObject(Configuration.SANBOX_OPTIONS_TYPE_NAME, mOptions);
        OptionSetter setter = new OptionSetter(mOptions);
        setter.setOptionValue(SandboxOptions.PARENT_PREPARER_CONFIG, "parent-config");

        IConfiguration configParent = new Configuration("test1", "test1");
        configParent.setTargetPreparer(mMockPreparer);
        doReturn(configParent)
                .when(mMockFactory)
                .createConfigurationFromArgs(new String[] {"parent-config"});

        mParentSandbox.doSetup(mContext, mConfig, null);
        mParentSandbox.doTeardown(mContext, mConfig, null, null);
        mParentSandbox.doCleanUp(mContext, mConfig, null);

        verify(mMockFactory, times(1)).createConfigurationFromArgs(Mockito.any());
        verify(mMockPreparer, times(1)).setUp(Mockito.any(), Mockito.any());
        verify(mMockPreparer, times(1)).tearDown(Mockito.any(), Mockito.any(), Mockito.any());
        verify(mMockDevice, times(0)).getIDevice();
    }

    @Test
    public void testParentConfig_errorStage() throws Throwable {
        mConfig.setConfigurationObject(Configuration.SANBOX_OPTIONS_TYPE_NAME, mOptions);
        OptionSetter setter = new OptionSetter(mOptions);
        setter.setOptionValue(SandboxOptions.PARENT_PREPARER_CONFIG, "parent-config");

        IConfiguration configParent = new Configuration("test1", "test1");
        configParent.setTargetPreparer(mMockPreparer);
        doReturn(configParent)
                .when(mMockFactory)
                .createConfigurationFromArgs(new String[] {"parent-config"});

        doReturn(new StubDevice("stub")).when(mMockDevice).getIDevice();

        mParentSandbox.doSetup(mContext, mConfig, null);
        mParentSandbox.doTeardown(mContext, mConfig, null, null);
        mParentSandbox.doCleanUp(mContext, mConfig, null);
        mParentSandbox.reportLogs(
                mMockDevice, configParent.getTestInvocationListeners().get(0), Stage.ERROR);

        verify(mMockFactory, times(1)).createConfigurationFromArgs(Mockito.any());
        verify(mMockPreparer, times(1)).setUp(Mockito.any(), Mockito.any());
        verify(mMockPreparer, times(1)).tearDown(Mockito.any(), Mockito.any(), Mockito.any());
        // Ensure we reported the logs for the device during ERROR stage.
        verify(mMockDevice, times(1)).getIDevice();
    }

    @Test
    public void testParentConfig_exception() throws Throwable {
        mConfig.setConfigurationObject(Configuration.SANBOX_OPTIONS_TYPE_NAME, mOptions);
        OptionSetter setter = new OptionSetter(mOptions);
        setter.setOptionValue(SandboxOptions.PARENT_PREPARER_CONFIG, "parent-config");
        doThrow(new ConfigurationException("test error"))
                .when(mMockFactory)
                .createConfigurationFromArgs(new String[] {"parent-config"});

        try {
            mParentSandbox.doSetup(mContext, mConfig, null);
            fail("Should have thrown an exception.");
        } catch (TargetSetupError expected) {
            // Expected.
            assertEquals(
                    "Check your --parent-preparer-config option: test error null",
                    expected.getMessage());
        }
        verify(mMockDevice, times(0)).getIDevice();
    }

    /**
     * If the context already contains BuildInfo we are in sandbox-test-mode and should not download
     * again.
     */
    @Test
    public void testParentSandbox_testMode() throws Throwable {
        IBuildProvider stubProvider = new StubBuildProvider();
        OptionSetter setter = new OptionSetter(stubProvider);
        setter.setOptionValue("throw-build-error", "true");
        mConfig.getDeviceConfig().get(0).addSpecificConfig(stubProvider);

        assertTrue(mParentSandbox.fetchBuild(mContext, mConfig, null, null));
    }

    /**
     * Test that in regular sandbox mode, the fetchBuild is called as always in the parent sandbox.
     */
    @Test
    public void testParentSandbox_NotTestMode() throws Throwable {
        IBuildProvider stubProvider = new StubBuildProvider();
        OptionSetter setter = new OptionSetter(stubProvider);
        setter.setOptionValue("throw-build-error", "true");
        mConfig.getDeviceConfig().get(0).addSpecificConfig(stubProvider);

        mContext = new InvocationContext();
        mContext.addAllocatedDevice(ConfigurationDef.DEFAULT_DEVICE_NAME, mMockDevice);
        try {
            mParentSandbox.fetchBuild(mContext, mConfig, null, null);
            fail("Should have thrown an exception.");
        } catch (BuildRetrievalError expected) {
            assertEquals("stub failed to get build.", expected.getMessage());
        }
    }
}
