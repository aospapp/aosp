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

import android.os.Bundle;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.android.devicelockcontroller.R;

/**
 * The activity displayed when provisioning is in progress.
 */
public final class ProvisioningActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.provisioning_activity);

        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
        }
        ProvisioningProgressViewModel viewModel = new ViewModelProvider(this).get(
                ProvisioningProgressViewModel.class);
        viewModel.getProvisioningProgressLiveData().observe(this, progress -> {
            ProgressFragment progressFragment = new ProgressFragment();
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, progressFragment)
                    .commit();
        });

        getSupportFragmentManager()
                .beginTransaction()
                .add(R.id.fragment_container, new DevicePoliciesFragment())
                .commit();
    }
}
