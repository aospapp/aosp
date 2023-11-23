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

import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.result.LogDataType;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import java.io.File;
import java.util.function.Predicate;

/**
 * Unit tests for {@link CmdUtil}.
 */
@RunWith(JUnit4.class)
public class OutputUtilTest {
    OutputUtil mOutputUtil = null;

    @Mock private ITestLogger mLogger;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLogger = EasyMock.createMock(ITestLogger.class);
        mOutputUtil = new OutputUtil(mLogger);
    }

    /**
     * Tests whether addVtsRunnerOutputFile correctly handles file types.
     * @throws Exception
     */
    @Test
    public void test_addVtsRunnerOutputFile_unknown() throws Exception {
        verify_addVtsRunnerOutputFile("a", LogDataType.UNKNOWN);
    }

    /**
     * Tests whether addVtsRunnerOutputFile correctly handles file types.
     * @throws Exception
     */
    @Test
    public void test_addVtsRunnerOutputFile_ZIP() throws Exception {
        verify_addVtsRunnerOutputFile("a.ZIP", LogDataType.ZIP);
    }

    /**
     * Tests whether addVtsRunnerOutputFile correctly handles file types.
     * @throws Exception
     */
    @Test
    public void test_addVtsRunnerOutputFile_HTML() throws Exception {
        verify_addVtsRunnerOutputFile("a.HTML", LogDataType.HTML);
    }

    /**
     * Tests whether addVtsRunnerOutputFile correctly handles file types.
     * @throws Exception
     */
    @Test
    public void test_addVtsRunnerOutputFile_LOGCAT() throws Exception {
        verify_addVtsRunnerOutputFile("LOGCAT.txt", LogDataType.LOGCAT);
    }

    /**
     * Tests whether addVtsRunnerOutputFile correctly handles file types.
     * @throws Exception
     */
    @Test
    public void test_addVtsRunnerOutputFile_BUGREPORTZ() throws Exception {
        verify_addVtsRunnerOutputFile("bugreport.zip", LogDataType.BUGREPORTZ);
    }

    /**
     * Tests whether addVtsRunnerOutputFile correctly handles file types.
     * @throws Exception
     */
    @Test
    public void test_addVtsRunnerOutputFile_BUGREPORT() throws Exception {
        verify_addVtsRunnerOutputFile("bugreport.txt", LogDataType.BUGREPORT);
    }

    /**
     * Tests whether addVtsRunnerOutputFile correctly handles file types.
     * @throws Exception
     */
    @Test
    public void test_addVtsRunnerOutputFile_JPEG() throws Exception {
        verify_addVtsRunnerOutputFile("a.jpg", LogDataType.JPEG);
    }

    /**
     * Tests whether addVtsRunnerOutputFile correctly handles file types.
     * @throws Exception
     */
    @Test
    public void test_addVtsRunnerOutputFile_TAR_GZ() throws Exception {
        verify_addVtsRunnerOutputFile("a.tar.gz", LogDataType.TAR_GZ);
    }

    /**
     * Tests whether addVtsRunnerOutputFile correctly handles file types.
     * @throws Exception
     */
    @Test
    public void test_addVtsRunnerOutputFile_PNG() throws Exception {
        verify_addVtsRunnerOutputFile("a.PNG", LogDataType.PNG);
    }

    /**
     * Tests whether addVtsRunnerOutputFile correctly handles file types.
     * @throws Exception
     */
    @Test
    public void test_addVtsRunnerOutputFile_zip() throws Exception {
        verify_addVtsRunnerOutputFile("a.zip", LogDataType.ZIP);
    }

    private void verify_addVtsRunnerOutputFile(String filename, LogDataType type) {
        File log = new File(filename);
        mLogger.testLog(EasyMock.contains(log.getName()), EasyMock.eq(type), EasyMock.anyObject());
        EasyMock.expectLastCall();
        EasyMock.replay(mLogger);
        mOutputUtil.addVtsRunnerOutputFile(log);
        EasyMock.verify(mLogger);
    }
}
