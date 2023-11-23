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

package com.android.adservices.service.appsetid;

import static android.adservices.common.AdServicesStatusUtils.STATUS_SUCCESS;

import android.adservices.appsetid.GetAppSetIdResult;
import android.adservices.appsetid.IGetAppSetIdCallback;
import android.adservices.appsetid.IGetAppSetIdProviderCallback;
import android.annotation.NonNull;
import android.os.RemoteException;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.concurrent.CompletableFuture;

/** Unit test for {@link com.android.adservices.service.adid.AppSetIdWorker}. */
public class AppSetIdWorkerTest {

    private boolean mTestSuccess;

    private static final String DEFAULT_APP_SET_ID = "00000000-0000-0000-0000-000000000000";

    @Test
    public void testGetAppSetIdOnResult() throws Exception {
        mTestSuccess = true;

        CompletableFuture<GetAppSetIdResult> future = new CompletableFuture<>();

        AppSetIdWorker spyWorker =
                Mockito.spy(
                        AppSetIdWorker.getInstance(ApplicationProvider.getApplicationContext()));
        Mockito.doReturn(mInterface).when(spyWorker).getService();

        spyWorker.getAppSetId(
                "testPackageName",
                0,
                new IGetAppSetIdCallback.Stub() {
                    @Override
                    public void onResult(GetAppSetIdResult resultParcel) {
                        future.complete(resultParcel);
                    }

                    @Override
                    public void onError(int resultCode) {
                        // should never be called.
                        Assert.fail();
                    }
                });

        GetAppSetIdResult result = future.get();
        Assert.assertEquals(DEFAULT_APP_SET_ID, result.getAppSetId());
        Assert.assertEquals(1, result.getAppSetIdScope());
    }

    @Test
    public void testGetAppSetIdOnError() throws Exception {
        mTestSuccess = false;

        CompletableFuture<Integer> future = new CompletableFuture<>();

        AppSetIdWorker spyWorker =
                Mockito.spy(
                        AppSetIdWorker.getInstance(ApplicationProvider.getApplicationContext()));
        Mockito.doReturn(mInterface).when(spyWorker).getService();

        spyWorker.getAppSetId(
                "testPackageName",
                0,
                new IGetAppSetIdCallback.Stub() {
                    @Override
                    public void onResult(GetAppSetIdResult resultParcel) {
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

    private final android.adservices.appsetid.IAppSetIdProviderService mInterface =
            new android.adservices.appsetid.IAppSetIdProviderService.Stub() {
                @Override
                public void getAppSetId(
                        int appUID,
                        @NonNull String packageName,
                        @NonNull IGetAppSetIdProviderCallback resultCallback)
                        throws RemoteException {
                    try {
                        if (mTestSuccess) {
                            GetAppSetIdResult appSetIdInternal =
                                    new GetAppSetIdResult.Builder()
                                            .setStatusCode(STATUS_SUCCESS)
                                            .setErrorMessage("")
                                            .setAppSetId(DEFAULT_APP_SET_ID)
                                            .setAppSetIdScope(/* DEFAULT_SCOPE */ 1)
                                            .build();
                            resultCallback.onResult(appSetIdInternal);
                        } else {
                            throw new Exception("testOnError");
                        }
                    } catch (Throwable e) {
                        resultCallback.onError(e.getMessage());
                    }
                }
            };
}
