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

import com.android.tradefed.config.Option;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.proto.TestRecordProto.TestRecord;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Proto reporter that dumps the {@link TestRecord} into a file. */
public class FileProtoResultReporter extends ProtoResultReporter {

    @Option(
        name = "proto-output-file",
        description = "File where the proto output will be saved",
        mandatory = true
    )
    private File mOutputFile;

    @Override
    public void processFinalProto(TestRecord finalRecord) {
        try {
            finalRecord.writeDelimitedTo(new FileOutputStream(mOutputFile));
        } catch (IOException e) {
            CLog.e(e);
            throw new RuntimeException(e);
        }
    }

    /** Sets the file where to output the result. */
    public void setFileOutput(File output) {
        mOutputFile = output;
    }
}
