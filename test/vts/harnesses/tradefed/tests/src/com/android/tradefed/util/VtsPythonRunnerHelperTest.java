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
package com.android.tradefed.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.build.IFolderBuildInfo;
import com.android.tradefed.log.LogUtil.CLog;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;

/**
 * Unit tests for {@link VtsPythonRunnerHelper}.
 */
@RunWith(JUnit4.class)
public class VtsPythonRunnerHelperTest {
    private static final String[] mPythonCmd = {"python"};
    private static final long mTestTimeout = 1000 * 5;

    private ProcessHelper mProcessHelper = null;
    private VtsPythonRunnerHelper mVtsPythonRunnerHelper = null;
    private String mVirtualenvPath = "virtualenv_path_" + System.currentTimeMillis();

    @Before
    public void setUp() throws Exception {
        IFolderBuildInfo buildInfo = EasyMock.createNiceMock(IFolderBuildInfo.class);
        EasyMock.replay(buildInfo);
        mVtsPythonRunnerHelper = new VtsPythonRunnerHelper(new File(mVirtualenvPath), null) {
            @Override
            protected ProcessHelper createProcessHelper(String[] cmd) {
                return mProcessHelper;
            }
        };
    }

    /**
     * Create a process helper which mocks status of a running process.
     */
    private static ProcessHelper createMockProcessHelper(
            CommandStatus status, boolean interrupted, boolean keepRunning) {
        Process process;
        try {
            process = new ProcessBuilder("true").start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new ProcessHelper(process) {
            @Override
            public CommandStatus waitForProcess(long timeoutMsecs) throws RunInterruptedException {
                if (interrupted) {
                    throw new RunInterruptedException();
                }
                return status;
            }

            @Override
            public boolean isRunning() {
                return keepRunning;
            }
        };
    }

    private static ProcessHelper createMockProcessHelper(
            CommandStatus status, boolean interrupted) {
        return createMockProcessHelper(status, interrupted, /*keepRunning=*/false);
    }

    private static ProcessHelper createMockProcessHelper(CommandStatus status) {
        return createMockProcessHelper(status, /*interrupted=*/false, /*keepRunning=*/false);
    }

    /**
     * Create a mock runUtil with returns the expected results.
     */
    private IRunUtil createMockRunUtil() {
        IRunUtil runUtil = new RunUtil() {
            private String path = null;

            @Override
            public void setEnvVariable(String key, String value) {
                super.setEnvVariable(key, value);
                if (key.equals("PATH")) {
                    path = value;
                }
            }

            @Override
            public CommandResult runTimedCmd(final long timeout, final String... command) {
                CommandResult cmdRes = new CommandResult(CommandStatus.SUCCESS);
                String out = "";
                if (command.length == 2 && command[0].equals("which")
                        && command[1].equals("python")) {
                    if (path != null) {
                        out = path.split(":")[0] + "/python";
                    } else {
                        out = "/usr/bin/python";
                    }
                }
                cmdRes.setStdout(out);
                return cmdRes;
            }
        };
        return runUtil;
    }

    @Test
    public void testProcessRunSuccess() {
        CommandResult commandResult = new CommandResult();
        mProcessHelper = createMockProcessHelper(CommandStatus.SUCCESS);
        String interruptMessage =
                mVtsPythonRunnerHelper.runPythonRunner(mPythonCmd, commandResult, mTestTimeout);
        assertEquals(interruptMessage, null);
        assertEquals(commandResult.getStatus(), CommandStatus.SUCCESS);
    }

    @Test
    public void testProcessRunFailed() {
        CommandResult commandResult = new CommandResult();
        mProcessHelper = createMockProcessHelper(CommandStatus.FAILED);
        String interruptMessage =
                mVtsPythonRunnerHelper.runPythonRunner(mPythonCmd, commandResult, mTestTimeout);
        assertEquals(interruptMessage, null);
        assertEquals(commandResult.getStatus(), CommandStatus.FAILED);
    }

    @Test
    public void testProcessRunTimeout() {
        CommandResult commandResult = new CommandResult();
        mProcessHelper = createMockProcessHelper(CommandStatus.TIMED_OUT);
        String interruptMessage =
                mVtsPythonRunnerHelper.runPythonRunner(mPythonCmd, commandResult, mTestTimeout);
        assertEquals(interruptMessage, null);
        assertEquals(commandResult.getStatus(), CommandStatus.TIMED_OUT);
    }

    @Test
    public void testProcessRunInterrupted() {
        CommandResult commandResult = new CommandResult();
        mProcessHelper = createMockProcessHelper(null, /*interrupted=*/true);
        String interruptMessage =
                mVtsPythonRunnerHelper.runPythonRunner(mPythonCmd, commandResult, mTestTimeout);
        assertNotEquals(interruptMessage, null);
        assertEquals(commandResult.getStatus(), CommandStatus.TIMED_OUT);
    }

    @Test
    public void testActivateVirtualEnvNotExist() {
        IRunUtil runUtil = createMockRunUtil();
        assertEquals(null, VtsPythonRunnerHelper.getPythonBinDir(mVirtualenvPath));
        VtsPythonRunnerHelper.activateVirtualenv(runUtil, mVirtualenvPath);
        String pythonBinary = runUtil.runTimedCmd(1000, "which", "python").getStdout();
        assertEquals(pythonBinary, "/usr/bin/python");
    }

    @Test
    public void testActivateVirtualEnvExist() {
        IRunUtil runUtil = createMockRunUtil();
        String binDirName = EnvUtil.isOnWindows() ? "Scripts" : "bin";
        File envDir = new File(mVirtualenvPath);
        File binDir = new File(mVirtualenvPath, binDirName);
        try {
            CLog.d("%s", envDir.mkdir());
            CLog.d("%s", binDir.mkdir());
            assertTrue(binDir.exists());
            assertEquals(binDir.getAbsolutePath(),
                    VtsPythonRunnerHelper.getPythonBinDir(mVirtualenvPath));
            VtsPythonRunnerHelper.activateVirtualenv(runUtil, mVirtualenvPath);
            String pythonBinary = runUtil.runTimedCmd(1000, "which", "python").getStdout();
            assertEquals(pythonBinary, new File(binDir, "python").getAbsolutePath());
        } finally {
            FileUtil.recursiveDelete(envDir);
            FileUtil.recursiveDelete(binDir);
        }
    }

}
