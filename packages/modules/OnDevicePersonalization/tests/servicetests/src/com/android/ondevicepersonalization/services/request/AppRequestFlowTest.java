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

package com.android.ondevicepersonalization.services.request;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.ondevicepersonalization.aidl.IExecuteCallback;
import android.os.PersistableBundle;

import androidx.test.core.app.ApplicationProvider;

import com.android.ondevicepersonalization.services.data.OnDevicePersonalizationDbHelper;
import com.android.ondevicepersonalization.services.data.events.EventsDao;
import com.android.ondevicepersonalization.services.data.events.QueriesContract;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;
import java.util.concurrent.CountDownLatch;

@RunWith(JUnit4.class)
public class AppRequestFlowTest {
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final CountDownLatch mLatch = new CountDownLatch(1);
    private OnDevicePersonalizationDbHelper mDbHelper;

    private String mRenderedContent;
    private boolean mGenerateHtmlCalled;
    private String mGeneratedHtml;
    private boolean mDisplayHtmlCalled;
    private boolean mCallbackSuccess;
    private boolean mCallbackError;

    @Before
    public void setup() {
        mDbHelper = OnDevicePersonalizationDbHelper.getInstanceForTest(mContext);
        EventsDao.getInstanceForTest(mContext);
    }

    @After
    public void cleanup() {
        mDbHelper.getWritableDatabase().close();
        mDbHelper.getReadableDatabase().close();
        mDbHelper.close();
    }

    @Test
    public void testRunAppRequestFlow() throws Exception {
        AppRequestFlow appRequestFlow = new AppRequestFlow(
                "abc", mContext.getPackageName(), PersistableBundle.EMPTY,
                new TestCallback(), mContext, MoreExecutors.newDirectExecutorService());
        appRequestFlow.run();
        mLatch.await();
        assertTrue(mCallbackSuccess);
        assertEquals(1,
                mDbHelper.getReadableDatabase().query(QueriesContract.QueriesEntry.TABLE_NAME, null,
                        null, null, null, null, null).getCount());
    }

    class TestCallback extends IExecuteCallback.Stub {
        @Override public void onSuccess(List<String> tokens) {
            mCallbackSuccess = true;
            mLatch.countDown();
        }
        @Override public void onError(int errorCode) {
            mCallbackError = true;
            mLatch.countDown();
        }
    }
}
