/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.content.Intent;
import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.dialer.livedata.BluetoothErrorStringLiveData;
import com.android.car.dialer.ui.warning.NoHfpFragment;

/**
 * An activity to host {@link NoHfpFragment} and
 * {@link com.android.car.dialer.ui.dialpad.DialpadFragment#newEmergencyDialpad()}.
 */
public class NoHfpActivity extends FragmentActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BluetoothErrorStringLiveData errorStringLiveData = (new ViewModelProvider(this))
                .get(NoHfpActivityViewModel.class)
                .getBluetoothErrorStringLiveData();

        errorStringLiveData.observe(this, (String error) -> {
            if (BluetoothErrorStringLiveData.NO_BT_ERROR.equals(error)) {
                startActivity(new Intent(this, TelecomActivity.class));
                finish();
            } else {
                Fragment currentFragment = getSupportFragmentManager()
                        .findFragmentById(android.R.id.content);
                if (currentFragment instanceof NoHfpFragment) {
                    ((NoHfpFragment) currentFragment).setErrorMessage(error);
                }
            }
        });

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(android.R.id.content,
                            NoHfpFragment.newInstance(errorStringLiveData.getValue()))
                    .commit();
        }
    }
}
