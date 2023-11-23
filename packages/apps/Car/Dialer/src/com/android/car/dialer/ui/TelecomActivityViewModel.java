/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.dialer.ui;

import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.content.Context;

import androidx.annotation.IntDef;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.android.car.dialer.livedata.BluetoothErrorStringLiveData;
import com.android.car.dialer.livedata.HfpDeviceListLiveData;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * View model for {@link TelecomActivity}.
 */
public class TelecomActivityViewModel extends AndroidViewModel {
    private static final String TAG = "CD.TelecomActivityViewModel";
    /**
     * A constant which indicates that there's no Bluetooth error.
     */

    private final Context mApplicationContext;
    private final LiveData<String> mErrorStringLiveData;
    private final LiveData<Boolean> mRefreshTabsLiveData;

    private final ToolbarTitleLiveData mToolbarTitleLiveData;
    private final MutableLiveData<Integer> mToolbarTitleMode;

    private BluetoothDevice mBluetoothDevice;

    /**
     * App state indicates if bluetooth is connected or it should just show the content fragments.
     */
    @IntDef({DialerAppState.DEFAULT, DialerAppState.BLUETOOTH_ERROR,
            DialerAppState.EMERGENCY_DIALPAD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DialerAppState {
        int DEFAULT = 0;
        int BLUETOOTH_ERROR = 1;
        int EMERGENCY_DIALPAD = 2;
    }

    public TelecomActivityViewModel(Application application) {
        super(application);
        mApplicationContext = application.getApplicationContext();

        mToolbarTitleMode = new MediatorLiveData<>();
        mToolbarTitleLiveData = new ToolbarTitleLiveData(mApplicationContext, mToolbarTitleMode);
        mErrorStringLiveData = BluetoothErrorStringLiveData.get(mApplicationContext);

        HfpDeviceListLiveData hfpDeviceListLiveData = new HfpDeviceListLiveData(getApplication());
        mRefreshTabsLiveData = Transformations.map(hfpDeviceListLiveData, (hfpDeviceList) -> {
            if (hfpDeviceList != null && !hfpDeviceList.isEmpty()) {
                if (!hfpDeviceList.contains(mBluetoothDevice)) {
                    mBluetoothDevice = hfpDeviceList.get(0);
                    return true;
                }
            } else {
                if (mBluetoothDevice != null) {
                    mBluetoothDevice = null;
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Returns the {@link LiveData} for the toolbar title, which provides the toolbar title
     * depending on the {@link com.android.car.dialer.R.attr#toolbarTitleMode}.
     */
    public LiveData<String> getToolbarTitle() {
        return mToolbarTitleLiveData;
    }

    /**
     * Returns the {@link MutableLiveData} of the toolbar title mode. The value should be set by the
     * {@link TelecomActivity}.
     */
    public MutableLiveData<Integer> getToolbarTitleMode() {
        return mToolbarTitleMode;
    }

    /**
     * Returns a LiveData which provides the warning string based on Bluetooth states. Returns
     * {@link BluetoothErrorStringLiveData#NO_BT_ERROR} if there's no error.
     */
    public LiveData<String> getErrorMessage() {
        return mErrorStringLiveData;
    }

    /**
     * Returns the live data which monitors whether to refresh Dialer.
     */
    public LiveData<Boolean> getRefreshTabsLiveData() {
        return mRefreshTabsLiveData;
    }
}
