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
package com.android.tradefed.result.proto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.proto.InvocationContext.Context;
import com.android.tradefed.result.proto.TestRecordProto.TestRecord;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.proto.TestRecordProtoUtil;

import com.google.protobuf.Any;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;

/** Unit tests for {@link FileProtoResultReporter}. */
@RunWith(JUnit4.class)
public class FileProtoResultReporterTest {

    private FileProtoResultReporter mReporter;
    private File mOutput;

    @Before
    public void setUp() throws Exception {
        mOutput = FileUtil.createTempFile("proto-file-reporter-test", ".pb");
        mReporter = new FileProtoResultReporter();
        mReporter.setFileOutput(mOutput);
    }

    @After
    public void tearDown() {
        FileUtil.deleteFile(mOutput);
    }

    @Test
    public void testWriteResults() throws Exception {
        assertEquals(0L, mOutput.length());
        IInvocationContext context = new InvocationContext();
        context.setConfigurationDescriptor(new ConfigurationDescriptor());
        context.addInvocationAttribute("test", "test");
        mReporter.invocationStarted(context);
        mReporter.invocationEnded(500L);

        // Something was outputted
        assertTrue(mOutput.length() != 0L);
        TestRecord record = TestRecordProtoUtil.readFromFile(mOutput);

        Any anyDescription = record.getDescription();
        assertTrue(anyDescription.is(Context.class));

        IInvocationContext endContext =
                InvocationContext.fromProto(anyDescription.unpack(Context.class));
        assertEquals("test", endContext.getAttributes().get("test").get(0));
    }
}
