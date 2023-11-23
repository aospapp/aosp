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
import android.util.Pair;

import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.android.devicelockcontroller.storage.SetupParametersClient;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * Generic abstract ViewModel for device enrollment info data.
 */
abstract class DeviceEnrollmentInfoViewModel extends ViewModel {

    private static final String TAG = "DeviceEnrollmentInfoViewModel";
    private static final String PROVIDER_NAME_PLACEHOLDER = "";
    private static final int TEXT_ID_PLACEHOLDER = -1;
    final MutableLiveData<Integer> mHeaderDrawableIdLiveData;
    final MutableLiveData<Integer> mHeaderTextIdLiveData;
    final MutableLiveData<String> mProviderNameLiveData;
    final MediatorLiveData<Pair<Integer, String>> mHeaderTextLiveData;
    final MutableLiveData<Integer> mBodyTextIdLiveData;
    final MediatorLiveData<Pair<Integer, String>> mBodyTextLiveData;

    DeviceEnrollmentInfoViewModel() {
        mHeaderDrawableIdLiveData = new MutableLiveData<>();
        mHeaderTextIdLiveData = new MutableLiveData<>();

        mProviderNameLiveData = new MutableLiveData<>();
        mHeaderTextLiveData = new MediatorLiveData<>();
        mHeaderTextLiveData.addSource(mHeaderTextIdLiveData,
                id -> {
                    Pair<Integer, String> oldValue = mHeaderTextLiveData.getValue();
                    mHeaderTextLiveData.setValue(oldValue == null
                            ? new Pair<>(id, PROVIDER_NAME_PLACEHOLDER)
                            : new Pair<>(id, oldValue.second));
                });
        mHeaderTextLiveData.addSource(mProviderNameLiveData,
                providerName -> {
                    Pair<Integer, String> oldValue = mHeaderTextLiveData.getValue();
                    mHeaderTextLiveData.setValue(oldValue == null
                            ? new Pair<>(TEXT_ID_PLACEHOLDER, providerName)
                            : new Pair<>(oldValue.first, providerName));

                });
        mBodyTextIdLiveData = new MutableLiveData<>();
        mBodyTextLiveData = new MediatorLiveData<>();
        mBodyTextLiveData.addSource(mBodyTextIdLiveData,
                id -> {
                    Pair<Integer, String> oldValue = mBodyTextLiveData.getValue();
                    mBodyTextLiveData.setValue(oldValue == null
                            ? new Pair<>(id, PROVIDER_NAME_PLACEHOLDER)
                            : new Pair<>(id, oldValue.second));
                });
        mBodyTextLiveData.addSource(mProviderNameLiveData,
                providerName -> {
                    Pair<Integer, String> oldValue = mBodyTextLiveData.getValue();
                    mBodyTextLiveData.setValue(oldValue == null
                            ? new Pair<>(TEXT_ID_PLACEHOLDER, providerName)
                            : new Pair<>(oldValue.first, providerName));
                });
        Futures.addCallback(SetupParametersClient.getInstance().getKioskAppProviderName(),
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
    }
}
