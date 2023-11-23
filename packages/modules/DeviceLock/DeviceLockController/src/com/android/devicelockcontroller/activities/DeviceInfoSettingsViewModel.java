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

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.android.devicelockcontroller.R;
import com.android.devicelockcontroller.storage.SetupParametersClient;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.Arrays;
import java.util.List;

/**
 * ViewModel class which provides data for the {@link DeviceInfoSettingsFragment}.
 */
public final class DeviceInfoSettingsViewModel extends ViewModel {

    private static final String TAG = "DeviceInfoSettingsViewModel";

    // Preferences that need to be updated with provider name
    private static final List<Pair<Integer, Integer>> PREFERENCE_KEY_TITLE_PAIRS = Arrays.asList(
            new Pair<>(R.string.settings_intro_preference_key,
                    R.string.settings_intro),
            new Pair<>(R.string.settings_credit_provider_capabilities_category_preference_key,
                    R.string.settings_credit_provider_capabilities_category),
            new Pair<>(R.string.settings_allowlisted_apps_preference_key,
                    R.string.settings_allowlisted_apps),
            new Pair<>(R.string.settings_restrictions_removed_preference_key,
                    R.string.settings_restrictions_removed),
            new Pair<>(R.string.settings_uninstall_kiosk_app_preference_key,
                    R.string.settings_uninstall_kiosk_app));

    final List<Pair<Integer, Integer>> mPreferenceKeyTitlePairs;

    final MutableLiveData<String> mProviderNameLiveData;

    public DeviceInfoSettingsViewModel() {
        mPreferenceKeyTitlePairs = PREFERENCE_KEY_TITLE_PAIRS;
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
    }


}
