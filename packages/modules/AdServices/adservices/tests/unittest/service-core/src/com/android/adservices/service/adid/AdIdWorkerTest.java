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

package com.android.adservices.service.adid;

import static android.adservices.common.AdServicesStatusUtils.STATUS_SUCCESS;

import android.adservices.adid.AdId;
import android.adservices.adid.GetAdIdResult;
import android.adservices.adid.IGetAdIdCallback;
import android.adservices.adid.IGetAdIdProviderCallback;
import android.annotation.NonNull;
import android.os.RemoteException;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.concurrent.CompletableFuture;

/** Unit test for {@link com.android.adservices.service.adid.AdIdWorker}. */
public class AdIdWorkerTest {

    private boolean mTestSuccess;

    @Test
    public void testGetAdIdOnResult() throws Exception {
        mTestSuccess = true;

        CompletableFuture<GetAdIdResult> future = new CompletableFuture<>();

        AdIdWorker spyWorker =
                Mockito.spy(AdIdWorker.getInstance(ApplicationProvider.getApplicationContext()));
        Mockito.doReturn(mInterface).when(spyWorker).getService();

        spyWorker.getAdId(
                "testPackageName",
                0,
                new IGetAdIdCallback.Stub() {
                    @Override
                    public void onResult(GetAdIdResult resultParcel) {
                        future.complete(resultParcel);
                    }

                    @Override
                    public void onError(int resultCode) {
                        // should never be called.
                        Assert.fail();
                    }
                });

        GetAdIdResult result = future.get();
        Assert.assertEquals(AdId.ZERO_OUT, result.getAdId());
        Assert.assertEquals(false, result.isLatEnabled());
    }

    @Test
    public void testGetAdIdOnError() throws Exception {
        mTestSuccess = false;
        CompletableFuture<Integer> future = new CompletableFuture<>();

        AdIdWorker spyWorker =
                Mockito.spy(AdIdWorker.getInstance(ApplicationProvider.getApplicationContext()));
        Mockito.doReturn(mInterface).when(spyWorker).getService();

        spyWorker.getAdId(
                "testPackageName",
                0,
                new IGetAdIdCallback.Stub() {
                    @Override
                    public void onResult(GetAdIdResult resultParcel) {
                        // should never be called.
                        Assert.fail();
                    }

                    @Override
                    public void onError(int resultCode) {
                        future.complete(resultCode);
                    }
                });

        int result = future.get();
        Assert.assertEquals(/* INTERNAL_STATE_ERROR */ 1, result);
    }

    private final android.adservices.adid.IAdIdProviderService mInterface =
            new android.adservices.adid.IAdIdProviderService.Stub() {
                @Override
                public void getAdIdProvider(
                        int appUID,
                        @NonNull String packageName,
                        @NonNull IGetAdIdProviderCallback resultCallback)
                        throws RemoteException {
                    try {
                        if (mTestSuccess) {
                            GetAdIdResult adIdInternal =
                                    new GetAdIdResult.Builder()
                                            .setStatusCode(STATUS_SUCCESS)
                                            .setErrorMessage("")
                                            .setAdId(AdId.ZERO_OUT)
                                            .setLatEnabled(/* DEFAULT_LAT */ false)
                                            .build();
                            resultCallback.onResult(adIdInternal);
                        } else {
                            throw new Exception("testOnError");
                        }
                    } catch (Throwable e) {
                        resultCallback.onError(e.getMessage());
                    }
                }
            };
}
