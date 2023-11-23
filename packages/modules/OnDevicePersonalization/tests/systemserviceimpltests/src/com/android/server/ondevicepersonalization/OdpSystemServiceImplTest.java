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

package com.android.server.ondevicepersonalization;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.ondevicepersonalization.IOnDevicePersonalizationSystemService;
import android.app.ondevicepersonalization.IOnDevicePersonalizationSystemServiceCallback;
import android.content.Context;
import android.os.Bundle;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.CountDownLatch;

@RunWith(JUnit4.class)
public class OdpSystemServiceImplTest {
    private final Context mContext = ApplicationProvider.getApplicationContext();
    boolean mOnResultCalled = false;
    CountDownLatch mLatch = new CountDownLatch(1);

    @Test
    public void testSystemServerService() throws Exception {
        OnDevicePersonalizationSystemService serviceImpl =
                new OnDevicePersonalizationSystemService(mContext);
        IOnDevicePersonalizationSystemService service =
                IOnDevicePersonalizationSystemService.Stub.asInterface(serviceImpl);
        assertNotEquals(null, service);
        service.onRequest(
                new Bundle(),
                new IOnDevicePersonalizationSystemServiceCallback.Stub() {
                    @Override public void onResult(Bundle result) {
                        mOnResultCalled = true;
                        mLatch.countDown();
                    }
                });
        mLatch.await();
        assertTrue(mOnResultCalled);
    }
}
