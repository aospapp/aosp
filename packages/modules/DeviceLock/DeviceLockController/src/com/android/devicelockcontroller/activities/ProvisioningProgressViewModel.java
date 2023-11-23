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

package com.android.devicelockcontroller.activities;

import android.text.TextUtils;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;

import com.android.devicelockcontroller.storage.SetupParametersClient;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * A {@link ViewModel} which provides {@link ProvisioningProgress} to the
 * {@link ProvisioningActivity}.
 */
public final class ProvisioningProgressViewModel extends ViewModel {

    private static final String TAG = "ProvisioningProgressViewModel";

    final MutableLiveData<String> mProviderNameLiveData;
    private final MediatorLiveData<ProvisioningProgress> mProvisioningProgressLiveData;
    private ProvisioningProgress mProvisioningProgress;

    public ProvisioningProgressViewModel() {
        mProviderNameLiveData = new MutableLiveData<>();
        Futures.addCallback(
                SetupParametersClient.getInstance().getKioskAppProviderName(),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(String providerName) {
                        if (TextUtils.isEmpty(providerName)) {
                            LogUtil.e(TAG, "Device provider name is empty, should not reach here.");
                            return;
                        }
                        mProviderNameLiveData.postValue(providerName);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        LogUtil.e(TAG, "Failed to get Kiosk app provider name", t);
                    }
                }, MoreExecutors.directExecutor());

        mProvisioningProgressLiveData = new MediatorLiveData<>();
        Observer<String> observer = unused -> {
            LogUtil.d(TAG, "The upstream ProviderNameLiveData is complete");
            if (mProvisioningProgress != null) {
                LogUtil.d(TAG, "Sending ProvisioningProgress to observers.");
                mProvisioningProgressLiveData.postValue(mProvisioningProgress);
            }
        };
        mProvisioningProgressLiveData.addSource(mProviderNameLiveData, observer);
    }

    /**
     * Returns the {@link LiveData} which provides the latest {@link ProvisioningProgress}.
     *
     * <p>Note, the caller of this method MUST NOT update the LiveData directly, use
     * {@link #setProvisioningProgress} instead.
     */
    public LiveData<ProvisioningProgress> getProvisioningProgressLiveData() {
        return mProvisioningProgressLiveData;
    }

    /**
     * Set the {@link ProvisioningProgress} to the given state.
     *
     * <p>This method is thread-safe and can be called from any thread.
     */
    public void setProvisioningProgress(ProvisioningProgress provisioningProgress) {
        if (mProviderNameLiveData.getValue() != null) {
            LogUtil.d(TAG, "Updating ProvisioningProgress");
            mProvisioningProgressLiveData.postValue(provisioningProgress);
        } else {
            LogUtil.d(TAG,
                    "The upstream ProviderNameLiveData is not ready yet, hold on until it "
                            + "completes");
            mProvisioningProgress = provisioningProgress;
        }
    }

}
