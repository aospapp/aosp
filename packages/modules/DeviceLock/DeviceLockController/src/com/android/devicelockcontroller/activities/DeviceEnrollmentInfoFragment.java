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

import static com.android.devicelockcontroller.common.DeviceLockConstants.ACTION_START_DEVICE_FINANCING_DEFERRED_PROVISIONING;
import static com.android.devicelockcontroller.common.DeviceLockConstants.ACTION_START_DEVICE_FINANCING_ENROLLMENT;
import static com.android.devicelockcontroller.common.DeviceLockConstants.ACTION_START_DEVICE_SUBSIDY_DEFERRED_PROVISIONING;
import static com.android.devicelockcontroller.common.DeviceLockConstants.ACTION_START_DEVICE_SUBSIDY_ENROLLMENT;

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.android.devicelockcontroller.R;
import com.android.devicelockcontroller.util.LogUtil;

import java.util.Objects;

/**
 * A fragment which tells the user the details of the device enrollment.
 */
public final class DeviceEnrollmentInfoFragment extends Fragment {

    private static final String TAG = "DeviceEnrollmentInfoFragment";

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_device_enrollment_info, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        DeviceEnrollmentInfoViewModel viewModel;
        switch (Objects.requireNonNull(getActivity()).getIntent().getAction()) {
            case ACTION_START_DEVICE_FINANCING_ENROLLMENT:
                viewModel = new ViewModelProvider(this).get(
                        DeviceFinancingEnrollmentInfoViewModel.class);
                break;
            case ACTION_START_DEVICE_SUBSIDY_ENROLLMENT:
                viewModel = new ViewModelProvider(this).get(
                        DeviceSubsidyEnrollmentInfoViewModel.class);
                break;
            default:
                LogUtil.e(TAG, "Unknown action is received, exiting");
                return;
        }
        ImageView imageView = view.findViewById(R.id.header_icon);
        checkNotNull(imageView);
        viewModel.mHeaderDrawableIdLiveData.observe(getViewLifecycleOwner(),
                imageView::setImageResource);

        TextView headerTextView = view.findViewById(R.id.header_text);
        checkNotNull(headerTextView);
        viewModel.mHeaderTextLiveData.observe(getViewLifecycleOwner(),
                pair -> {
                    if (pair.first > 0 && !TextUtils.isEmpty(pair.second)) {
                        headerTextView.setText(getString(pair.first, pair.second));
                    }
                });

        TextView bodyTextView = view.findViewById(R.id.body_text);
        checkNotNull(bodyTextView);
        viewModel.mBodyTextLiveData.observe(getViewLifecycleOwner(),
                pair -> {
                    if (pair.first > 0 && !TextUtils.isEmpty(pair.second)) {
                        bodyTextView.setText(getString(pair.first, pair.second));
                    }
                });

        Button button = view.findViewById(R.id.button_ok);
        checkNotNull(button);
        button.setOnClickListener(
                v -> {
                    // TODO(b/279445733): Ideally OK button should finish the activity, and the
                    //  LandingActivity should be pop up automatically when enrollment is due. Hook
                    //  them together for now to facilitate UI / A11Y verification test.
                    Intent intent = new Intent();
                    if (viewModel instanceof DeviceFinancingEnrollmentInfoViewModel) {
                        intent.setAction(ACTION_START_DEVICE_FINANCING_DEFERRED_PROVISIONING);
                    } else {
                        intent.setAction(ACTION_START_DEVICE_SUBSIDY_DEFERRED_PROVISIONING);
                    }
                    intent.setComponent(
                            new ComponentName(requireContext(), LandingActivity.class));
                    startActivity(intent);
                });
    }
}
