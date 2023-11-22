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
package com.android.tradefed.build;

import com.android.tradefed.build.gcs.GCSDownloaderHelper;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.util.FileUtil;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;

/** Unit test for {@link GCSTestResourceProvider}. */
@RunWith(JUnit4.class)
public class GCSTestResourceProviderTest {

    private static final String TEST_RESOURCE1 = "gs://b/this/is/a/file1.txt";
    private static final String TEST_RESOURCE2 = "gs://b/this/is/a/file2.txt";

    private File mRoot;
    private GCSTestResourceProvider mTestResourceProvider;

    @Before
    public void setUp() throws Exception {
        mRoot = FileUtil.createTempDir(GCSTestResourceProviderTest.class.getSimpleName());
        mTestResourceProvider =
                new GCSTestResourceProvider() {
                    @Override
                    GCSDownloaderHelper getHelper() {
                        return new GCSDownloaderHelper() {
                            @Override
                            public File fetchTestResource(String gsPath)
                                    throws BuildRetrievalError {
                                try {
                                    File f = FileUtil.createTempFile("test-gcs-file", "txt");
                                    FileUtil.writeToFile(gsPath, f);
                                    return f;
                                } catch (IOException e) {
                                    throw new BuildRetrievalError(e.getMessage(), e);
                                }
                            }
                        };
                    }
                };
        OptionSetter setter = new OptionSetter(mTestResourceProvider);
        setter.setOptionValue("test-resource", "key1", TEST_RESOURCE1);
        setter.setOptionValue("test-resource", "key2", TEST_RESOURCE2);
    }

    @After
    public void tearDown() {
        FileUtil.recursiveDelete(mRoot);
    }

    @Test
    public void testGetBuild() throws Exception {
        IBuildInfo buildInfo = mTestResourceProvider.getBuild();
        File file1 = buildInfo.getFile("key1");
        File file2 = buildInfo.getFile("key2");
        Assert.assertEquals(TEST_RESOURCE1, FileUtil.readStringFromFile(file1));
        Assert.assertEquals(TEST_RESOURCE2, FileUtil.readStringFromFile(file2));
        mTestResourceProvider.cleanUp(buildInfo);
        Assert.assertFalse(file1.exists());
        Assert.assertFalse(file2.exists());
    }
}
