/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.mediav2.common.cts;

import android.os.Environment;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;

import java.io.File;

/**
 * Return the primary shared/external storage directory used by the tests
 */
public class WorkDirBase {
    private static final String MEDIA_PATH_INSTR_ARG_KEY = "media-path";

    private static File getTopDir() {
        Assert.assertEquals(Environment.getExternalStorageState(), Environment.MEDIA_MOUNTED);
        return Environment.getExternalStorageDirectory();
    }

    private static String getTopDirString() {
        return getTopDir().getAbsolutePath() + File.separator;
    }

    protected static final String getMediaDirString(String defaultFolderName) {
        android.os.Bundle bundle = InstrumentationRegistry.getArguments();
        String mediaDirString = bundle.getString(MEDIA_PATH_INSTR_ARG_KEY);
        if (mediaDirString == null) {
            return getTopDirString() + "test/" + defaultFolderName + "/";
        }
        // user has specified the mediaDirString via instrumentation-arg
        if (mediaDirString.endsWith(File.separator)) {
            return mediaDirString;
        }
        return mediaDirString + File.separator;
    }
}
