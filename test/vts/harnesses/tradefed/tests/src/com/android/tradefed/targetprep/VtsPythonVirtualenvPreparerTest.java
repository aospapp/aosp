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

package com.android.tradefed.targetprep;

import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import java.io.File;
import java.io.IOException;

/**
 * Unit tests for {@link VtsPythonVirtualenvPreparer}.</p>
 * TODO: add tests to cover a full end-to-end scenario.
 */
@RunWith(JUnit4.class)
public class VtsPythonVirtualenvPreparerTest {
    private MockPythonVirtualenvPreparer mPreparer;
    private IRunUtil mMockRunUtil;
    private MockFile mFile;
    @Mock private IBuildInfo mBuildInfo;

    class MockFile extends File {
        public boolean createNewFileSuccess = true;

        /**
         * @param name
         */
        public MockFile(String name) {
            super(name);
        }

        @Override
        public boolean createNewFile() throws IOException {
            if (!createNewFileSuccess) {
                throw new IOException();
            };

            return true;
        }
    }

    class MockPythonVirtualenvPreparer extends VtsPythonVirtualenvPreparer {
        public int mock_createVirtualenv = -1;
        public int mock_checkHostReuseVirtualenv = -1;
        public int mock_checkTestPlanLevelVirtualenv = -1;
        public int mock_createVirtualenv_waitForOtherProcessToCreateVirtualEnv = -1;

        @Override
        protected IRunUtil getRunUtil() {
            return mMockRunUtil;
        }

        @Override
        protected File getVirtualenvCreationMarkFile() {
            return mFile;
        }

        @Override
        protected boolean createVirtualenv() throws IOException {
            switch (mock_createVirtualenv) {
                case 0:
                    return false;
                case 1:
                    return true;
                case 2:
                    throw new IOException("");
                default:
                    return super.createVirtualenv();
            }
        }

        @Override
        protected boolean checkHostReuseVirtualenv(IBuildInfo buildInfo) throws IOException {
            switch (mock_checkHostReuseVirtualenv) {
                case 0:
                    return false;
                case 1:
                    return true;
                case 2:
                    throw new IOException("");
                default:
                    return super.checkHostReuseVirtualenv(buildInfo);
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        protected boolean checkTestPlanLevelVirtualenv(IBuildInfo buildInfo)
                throws TargetSetupError {
            switch (mock_checkTestPlanLevelVirtualenv) {
                case 0:
                    return false;
                case 1:
                    return true;
                case 2:
                    throw new TargetSetupError("");
                default:
                    return super.checkTestPlanLevelVirtualenv(buildInfo);
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        protected boolean createVirtualenv_waitForOtherProcessToCreateVirtualEnv() {
            switch (mock_createVirtualenv_waitForOtherProcessToCreateVirtualEnv) {
                case 0:
                    return false;
                case 1:
                    return true;
                default:
                    return super.createVirtualenv_waitForOtherProcessToCreateVirtualEnv();
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        mMockRunUtil = EasyMock.createMock(IRunUtil.class);
        mFile = new MockFile("");

        mPreparer = new MockPythonVirtualenvPreparer();
        VtsPythonVirtualenvPreparer.PIP_RETRY = 0;
        mPreparer.mVenvDir = new File("");
        mPreparer.mDepModules.add("enum");
    }

    /**
     * Test that the installation of dependencies and requirements file is as expected.
     */
    @Test
    public void testInstallDeps_reqFile_success() throws Exception {
        File requirementFile = FileUtil.createTempFile("reqfile", ".txt");
        try {
            mPreparer.setRequirementsFile(requirementFile);
            CommandResult result = new CommandResult(CommandStatus.SUCCESS);
            result.setStdout("output");
            result.setStderr("std err");
            // First check that the install requirements was attempted.
            expect(mMockRunUtil.runTimedCmd(anyLong(), EasyMock.eq(mPreparer.getPipPath()),
                           EasyMock.eq("install"), EasyMock.eq("-r"),
                           EasyMock.eq(requirementFile.getAbsolutePath())))
                    .andReturn(result);
            // Check that all default modules are installed
            addDefaultModuleExpectations(mMockRunUtil, result);
            EasyMock.replay(mMockRunUtil);
            mPreparer.installDeps();
            EasyMock.verify(mMockRunUtil);
        } finally {
            FileUtil.deleteFile(requirementFile);
        }
    }

    /**
     * Test that if an extra dependency module is required, we install it too.
     */
    @Test
    public void testInstallDeps_depModule_success() throws Exception {
        mPreparer.addDepModule("blahblah");
        CommandResult result = new CommandResult(CommandStatus.SUCCESS);
        result.setStdout("output");
        result.setStderr("std err");
        addDefaultModuleExpectations(mMockRunUtil, result);
        // The non default module provided is also attempted to be installed.
        expect(mMockRunUtil.runTimedCmd(anyLong(), EasyMock.eq(mPreparer.getPipPath()),
                       EasyMock.eq("install"), EasyMock.eq("blahblah")))
                .andReturn(result);
        mMockRunUtil.sleep(VtsPythonVirtualenvPreparer.PIP_INSTALL_DELAY);
        EasyMock.replay(mMockRunUtil);
        mPreparer.installDeps();
        EasyMock.verify(mMockRunUtil);
    }

    /**
     * Tests the value of PIP_INSTALL_DELAY is at least 1 second.
     */
    @Test
    public void test_PIP_INSTALL_DELAY_minimum_value() {
        assertTrue(VtsPythonVirtualenvPreparer.PIP_INSTALL_DELAY >= 1000);
    }

    /**
     * Test that an installation failure of the requirements file throws a {@link TargetSetupError}.
     */
    @Test
    public void testInstallDeps_reqFile_failure() throws Exception {
        File requirementFile = FileUtil.createTempFile("reqfile", ".txt");
        try {
            mPreparer.setRequirementsFile(requirementFile);
            CommandResult result = new CommandResult(CommandStatus.TIMED_OUT);
            result.setStdout("output");
            result.setStderr("std err");
            expect(mMockRunUtil.runTimedCmd(anyLong(), EasyMock.eq(mPreparer.getPipPath()),
                           EasyMock.eq("install"), EasyMock.eq("-r"),
                           EasyMock.eq(requirementFile.getAbsolutePath())))
                    .andReturn(result);
            EasyMock.replay(mMockRunUtil);
            IBuildInfo buildInfo = new BuildInfo();
            try {
                mPreparer.installDeps();
                fail("installDeps succeeded despite a failed command");
            } catch (TargetSetupError e) {
                assertTrue(buildInfo.getFile("PYTHONPATH") == null);
            }
            EasyMock.verify(mMockRunUtil);
        } finally {
            FileUtil.deleteFile(requirementFile);
        }
    }

    /**
     * Test that an installation failure of the dep module throws a {@link TargetSetupError}.
     */
    @Test
    public void testInstallDeps_depModule_failure() throws Exception {
        CommandResult result = new CommandResult(CommandStatus.TIMED_OUT);
        result.setStdout("output");
        result.setStderr("std err");
        expect(mMockRunUtil.runTimedCmd(
                       anyLong(), EasyMock.eq(mPreparer.getPipPath()), EasyMock.eq("list")))
                .andReturn(result);
        expect(mMockRunUtil.runTimedCmd(anyLong(), EasyMock.eq(mPreparer.getPipPath()),
                       EasyMock.eq("install"), EasyMock.eq("enum")))
                .andReturn(result);
        // If installing the dependency failed, an upgrade is attempted:
        expect(mMockRunUtil.runTimedCmd(anyLong(), EasyMock.eq(mPreparer.getPipPath()),
                       EasyMock.eq("install"), EasyMock.eq("--upgrade"), EasyMock.eq("enum")))
                .andReturn(result);
        EasyMock.replay(mMockRunUtil);
        IBuildInfo buildInfo = new BuildInfo();
        try {
            mPreparer.installDeps();
            mPreparer.addPathToBuild(buildInfo);
            fail("installDeps succeeded despite a failed command");
        } catch (TargetSetupError e) {
            assertTrue(buildInfo.getFile("PYTHONPATH") == null);
        }
        EasyMock.verify(mMockRunUtil);
    }

    private void addDefaultModuleExpectations(IRunUtil mockRunUtil, CommandResult result) {
        expect(mockRunUtil.runTimedCmd(
                       anyLong(), EasyMock.eq(mPreparer.getPipPath()), EasyMock.eq("list")))
                .andReturn(result);
        expect(mockRunUtil.runTimedCmd(anyLong(), EasyMock.eq(mPreparer.getPipPath()),
                       EasyMock.eq("install"), EasyMock.eq("enum")))
                .andReturn(result);
        mMockRunUtil.sleep(VtsPythonVirtualenvPreparer.PIP_INSTALL_DELAY);
    }

    /**
     * Tests the functionality of createVirtualenv.
     * @throws IOException
     */
    @Test
    public void test_initVirtualenv_creationSuccess() throws IOException {
        // Create virutalenv dir command success
        CommandResult result = new CommandResult();
        result.setStatus(CommandStatus.SUCCESS);
        expect(mMockRunUtil.runTimedCmd(EasyMock.anyInt(), EasyMock.eq("virtualenv"),
                       EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject()))
                .andReturn(result);
        EasyMock.replay(mMockRunUtil);

        // Create completion mark file success
        mFile.createNewFileSuccess = true;

        assertTrue(mPreparer.createVirtualenv());
    }

    /**
     * Tests the functionality of createVirtualenv.
     * @throws IOException
     */
    @Test
    public void test_initVirtualenv_creationSuccess_completionFail() {
        // Create virutalenv dir command success
        CommandResult result = new CommandResult();
        result.setStatus(CommandStatus.SUCCESS);
        expect(mMockRunUtil.runTimedCmd(EasyMock.anyInt(), EasyMock.eq("virtualenv"),
                       EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject()))
                .andReturn(result);
        EasyMock.replay(mMockRunUtil);

        // Create completion mark file success
        mFile.createNewFileSuccess = false;

        try {
            mPreparer.createVirtualenv();
            assertTrue("IOException is expected", false);
        } catch (IOException e) {
            // Expected. test pass
        }
    }

    /**
     * Tests the functionality of createVirtualenv.
     * @throws IOException
     */
    @Test
    public void test_initVirtualenv_creationFail_Errno26_waitSucceed() throws IOException {
        // Create virutalenv dir command success
        CommandResult result = new CommandResult();
        result.setStatus(CommandStatus.FAILED);
        result.setStderr("...Errno 26...");
        expect(mMockRunUtil.runTimedCmd(EasyMock.anyInt(), EasyMock.eq("virtualenv"),
                       EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject()))
                .andReturn(result);
        EasyMock.replay(mMockRunUtil);

        // Wait succeed
        mPreparer.mock_createVirtualenv_waitForOtherProcessToCreateVirtualEnv = 1;

        assertTrue(mPreparer.createVirtualenv());
    }

    /**
     * Tests the functionality of createVirtualenv.
     * @throws IOException
     */
    @Test
    public void test_initVirtualenv_creationFail_Errno26_waitFailed() throws IOException {
        // Create virutalenv dir command success
        CommandResult result = new CommandResult();
        result.setStatus(CommandStatus.FAILED);
        result.setStderr("...Errno 26...");
        expect(mMockRunUtil.runTimedCmd(EasyMock.anyInt(), EasyMock.eq("virtualenv"),
                       EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject()))
                .andReturn(result);
        EasyMock.replay(mMockRunUtil);

        // Wait failed
        mPreparer.mock_createVirtualenv_waitForOtherProcessToCreateVirtualEnv = 0;

        assertTrue(!mPreparer.createVirtualenv());
    }

    /**
     * Tests the functionality of createVirtualenv.
     * @throws IOException
     */
    @Test
    public void test_initVirtualenv_creationFail_noErrno26() throws IOException {
        // Create virutalenv dir command success
        CommandResult result = new CommandResult();
        result.setStatus(CommandStatus.FAILED);
        result.setStderr("...");
        expect(mMockRunUtil.runTimedCmd(EasyMock.anyInt(), EasyMock.eq("virtualenv"),
                       EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject()))
                .andReturn(result);
        EasyMock.replay(mMockRunUtil);

        assertTrue(!mPreparer.createVirtualenv());
    }
}