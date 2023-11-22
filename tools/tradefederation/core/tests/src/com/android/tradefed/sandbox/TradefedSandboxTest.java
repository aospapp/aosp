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
package com.android.tradefed.sandbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;

/** Unit tests for {@link com.android.tradefed.sandbox.TradefedSandbox}. */
@RunWith(JUnit4.class)
public class TradefedSandboxTest {
    private static final String TF_JAR_DIR = "TF_JAR_DIR";
    private String mCachedProperty;
    private File mTmpFolder;

    private TradefedSandbox mSandbox;
    private ITestInvocationListener mMockListener;
    private IConfiguration mMockConfig;
    private IInvocationContext mMockContext;
    private IRunUtil mMockRunUtil;

    @Before
    public void setUp() throws Exception {
        mMockRunUtil = EasyMock.createMock(IRunUtil.class);
        mSandbox =
                new TradefedSandbox() {
                    @Override
                    IRunUtil createRunUtil() {
                        return mMockRunUtil;
                    }
                };
        mMockListener = EasyMock.createMock(ITestInvocationListener.class);
        mMockConfig = EasyMock.createMock(IConfiguration.class);
        mMockContext = new InvocationContext();

        mTmpFolder = FileUtil.createTempDir("tmp-tf-jar-dir");

        if (System.getProperty(TF_JAR_DIR) != null) {
            mCachedProperty = System.getProperty(TF_JAR_DIR);
        }
        System.setProperty(TF_JAR_DIR, mTmpFolder.getAbsolutePath());
    }

    @After
    public void tearDown() {
        if (mCachedProperty != null) {
            System.setProperty(TF_JAR_DIR, mCachedProperty);
        }
        FileUtil.recursiveDelete(mTmpFolder);
        mSandbox.tearDown();
    }

    /**
     * Test a case where the {@link
     * com.android.tradefed.sandbox.TradefedSandbox#prepareEnvironment(IInvocationContext,
     * IConfiguration, ITestInvocationListener)} succeed and does not have any exception.
     */
    @Test
    public void testPrepareEnvironment() throws Exception {
        mMockRunUtil.unsetEnvVariable(TradefedSandbox.TF_GLOBAL_CONFIG);
        CommandResult result = new CommandResult();
        result.setStatus(CommandStatus.SUCCESS);
        EasyMock.expect(
                        mMockRunUtil.runTimedCmd(
                                EasyMock.anyLong(),
                                EasyMock.eq("java"),
                                EasyMock.eq("-cp"),
                                EasyMock.anyObject(),
                                EasyMock.eq(SandboxConfigDump.class.getCanonicalName()),
                                EasyMock.eq("RUN_CONFIG"),
                                EasyMock.anyObject(),
                                EasyMock.eq("empty"),
                                EasyMock.eq("--arg"),
                                EasyMock.eq("1")))
                .andReturn(result);
        setPrepareConfigurationExpectations();
        EasyMock.replay(mMockConfig, mMockListener, mMockRunUtil);
        Exception res = mSandbox.prepareEnvironment(mMockContext, mMockConfig, mMockListener);
        EasyMock.verify(mMockConfig, mMockListener, mMockRunUtil);
        assertNull(res);
    }

    /**
     * Test a case where the {@link
     * com.android.tradefed.sandbox.TradefedSandbox#prepareEnvironment(IInvocationContext,
     * IConfiguration, ITestInvocationListener)} fails to dump the configuration, in that case the
     * std err from the dump utility is used for the exception.
     */
    @Test
    public void testPrepareEnvironment_dumpConfigFail() throws Exception {
        mMockRunUtil.unsetEnvVariable(TradefedSandbox.TF_GLOBAL_CONFIG);
        CommandResult result = new CommandResult();
        result.setStatus(CommandStatus.FAILED);
        result.setStderr("Ouch I failed.");
        EasyMock.expect(
                        mMockRunUtil.runTimedCmd(
                                EasyMock.anyLong(),
                                EasyMock.eq("java"),
                                EasyMock.eq("-cp"),
                                EasyMock.anyObject(),
                                EasyMock.eq(SandboxConfigDump.class.getCanonicalName()),
                                EasyMock.eq("RUN_CONFIG"),
                                EasyMock.anyObject(),
                                EasyMock.eq("empty"),
                                EasyMock.eq("--arg"),
                                EasyMock.eq("1")))
                .andReturn(result);
        setPrepareConfigurationExpectations();
        EasyMock.replay(mMockConfig, mMockListener, mMockRunUtil);
        Exception res = mSandbox.prepareEnvironment(mMockContext, mMockConfig, mMockListener);
        EasyMock.verify(mMockConfig, mMockListener, mMockRunUtil);
        assertNotNull(res);
        assertTrue(res instanceof ConfigurationException);
        assertEquals("Ouch I failed.", res.getMessage());
    }

    /**
     * Test a case where the {@link
     * com.android.tradefed.sandbox.TradefedSandbox#prepareEnvironment(IInvocationContext,
     * IConfiguration, ITestInvocationListener)} throws an exception because TF_JAR_DIR was not set.
     */
    @Test
    public void testPrepareEnvironment_noTfDirJar() throws Exception {
        mMockRunUtil.unsetEnvVariable(TradefedSandbox.TF_GLOBAL_CONFIG);
        EasyMock.expect(mMockConfig.getCommandLine()).andReturn("empty --arg 1");
        System.setProperty(TF_JAR_DIR, "");
        EasyMock.replay(mMockConfig, mMockListener, mMockRunUtil);
        Exception res = mSandbox.prepareEnvironment(mMockContext, mMockConfig, mMockListener);
        EasyMock.verify(mMockConfig, mMockListener, mMockRunUtil);
        assertNotNull(res);
        assertTrue(res instanceof ConfigurationException);
        assertEquals(
                "Could not read TF_JAR_DIR to get current Tradefed instance.", res.getMessage());
    }

    private void setPrepareConfigurationExpectations() throws Exception {
        EasyMock.expect(mMockConfig.getCommandLine()).andReturn("empty --arg 1").times(2);
    }
}
