/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.federatedcompute.services.training;

import static android.federatedcompute.common.ClientConstants.RESULT_HANDLING_SERVICE_ACTION;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.federatedcompute.services.common.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public final class ResultHandlingServiceProviderImplTest {
    private ResultHandlingServiceProviderImpl mResultHandlingServiceProvider;
    private Context mContext = ApplicationProvider.getApplicationContext();
    private static final long TIMEOUT_SECS = 5L;

    private Intent mIntent;
    @Mock private Flags mMockFlags;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        String packageName = mContext.getPackageName();
        mResultHandlingServiceProvider =
                new ResultHandlingServiceProviderImpl(mContext, mMockFlags);
        mIntent = new Intent();
        mIntent.setAction(RESULT_HANDLING_SERVICE_ACTION).setPackage(packageName);
        mIntent.setData(new Uri.Builder().scheme("app").authority(packageName).build());
        when(mMockFlags.getResultHandlingBindServiceTimeoutSecs()).thenReturn(TIMEOUT_SECS);
    }

    @After
    public void cleanup() {
        mResultHandlingServiceProvider.unbindService();
    }

    @Test
    public void testBindService() {
        assertTrue(mResultHandlingServiceProvider.bindService(mIntent));
    }

    @Test
    public void testBindService_invalidIntent() {
        Intent intent = new Intent();
        intent.setAction(RESULT_HANDLING_SERVICE_ACTION).setPackage("android.foo");
        intent.setData(new Uri.Builder().scheme("app").build());
        assertFalse(mResultHandlingServiceProvider.bindService(intent));
    }

    @Test
    public void testGetResultHandlingService() throws Exception {
        mResultHandlingServiceProvider.bindService(mIntent);

        assertNotNull(mResultHandlingServiceProvider.getResultHandlingService());
    }

    @Test
    public void testUnbindService() throws Exception {
        assertTrue(mResultHandlingServiceProvider.bindService(mIntent));

        mResultHandlingServiceProvider.unbindService();
    }

    @Test
    public void testUnbindService_serviceNonExist() {
        mResultHandlingServiceProvider.unbindService();
    }
}
