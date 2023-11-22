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
package com.android.compatibility.common.tradefed.result.suite;

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.proto.ProtoResultReporter;
import com.android.tradefed.result.proto.TestRecordProto.TestRecord;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Proto reporter that will drop a {@link TestRecord} protobuf in the result directory.
 */
public class CompatibilityProtoResultReporter extends ProtoResultReporter {

    public static final String PROTO_FILE_NAME = "test-record.pb";

    private CompatibilityBuildHelper mBuildHelper;

    /** The directory containing the results */
    private File mResultDir = null;

    @Override
    public void processStartInvocation(
            TestRecord invocationStartRecord, IInvocationContext invocationContext) {
        if (mBuildHelper == null) {
            mBuildHelper = new CompatibilityBuildHelper(invocationContext.getBuildInfos().get(0));
        }
    }

    @Override
    public void processFinalProto(TestRecord finalRecord) {
        super.processFinalProto(finalRecord);

        mResultDir = getResultDirectory();
        File protoFile = new File(mResultDir, PROTO_FILE_NAME);
        try {
            finalRecord.writeDelimitedTo(new FileOutputStream(protoFile));
        } catch (IOException e) {
            CLog.e(e);
            throw new RuntimeException(e);
        }
    }

    private File getResultDirectory() {
        try {
            mResultDir = mBuildHelper.getResultDir();
            if (mResultDir != null) {
                mResultDir.mkdirs();
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        if (mResultDir == null) {
            throw new RuntimeException("Result Directory was not created");
        }
        if (!mResultDir.exists()) {
            throw new RuntimeException("Result Directory was not created: " +
                    mResultDir.getAbsolutePath());
        }
        CLog.d("Results Directory: %s", mResultDir.getAbsolutePath());
        return mResultDir;
    }
}