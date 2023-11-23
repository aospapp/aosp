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
package com.android.tradefed.config.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.gcs.GCSDownloaderHelper;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.Option;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;

/** Unit tests for {@link GcsRemoteFileResolver}. */
@RunWith(JUnit4.class)
public class GcsRemoteFileResolverTest {

    private GcsRemoteFileResolver mResolver;
    private GCSDownloaderHelper mMockHelper;

    @Before
    public void setUp() {
        mMockHelper = Mockito.mock(GCSDownloaderHelper.class);
        mResolver =
                new GcsRemoteFileResolver() {
                    @Override
                    protected GCSDownloaderHelper getDownloader() {
                        return mMockHelper;
                    }
                };
    }

    @Test
    public void testResolve() throws Exception {
        mResolver.resolveRemoteFiles(new File("gs:/fake/file"), Mockito.mock(Option.class));

        Mockito.verify(mMockHelper).fetchTestResource("gs:/fake/file");
    }

    @Test
    public void testResolve_error() throws Exception {
        Mockito.doThrow(new BuildRetrievalError("download failure"))
                .when(mMockHelper)
                .fetchTestResource("gs:/fake/file");

        try {
            mResolver.resolveRemoteFiles(new File("gs:/fake/file"), Mockito.mock(Option.class));
            fail("Should have thrown an exception.");
        } catch (ConfigurationException expected) {
            assertEquals(
                    "Failed to download gs:/fake/file due to: download failure",
                    expected.getMessage());
        }

        Mockito.verify(mMockHelper).fetchTestResource("gs:/fake/file");
    }
}
