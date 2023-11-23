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

import static com.google.common.base.Preconditions.checkNotNull;

import android.os.Bundle;
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
import androidx.recyclerview.widget.RecyclerView;

import com.android.devicelockcontroller.R;
import com.android.devicelockcontroller.policy.PolicyObjectsInterface;
import com.android.devicelockcontroller.policy.SetupController;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * A screen which lists the polies enforced on the device by the device provider.
 */
public final class DevicePoliciesFragment extends Fragment {

    private static final String TAG = "DevicePoliciesFragment";

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_device_policies, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        DevicePoliciesViewModel viewModel = new ViewModelProvider(this).get(
                DevicePoliciesViewModel.class);

        RecyclerView recyclerView = view.findViewById(R.id.recyclerview_device_policy_group);
        DevicePolicyGroupListAdapter adapter = new DevicePolicyGroupListAdapter();
        viewModel.mDevicePolicyGroupListLiveData.observe(getViewLifecycleOwner(),
                devicePolicyGroups -> {
                    adapter.setProviderName(viewModel.mProviderNameLiveData.getValue());
                    adapter.submitList(devicePolicyGroups);
                });
        checkNotNull(recyclerView);
        recyclerView.setAdapter(adapter);

        ImageView imageView = view.findViewById(R.id.header_icon);
        checkNotNull(imageView);
        viewModel.mHeaderDrawableIdLiveData.observe(getViewLifecycleOwner(),
                imageView::setImageResource);

        TextView headerTextView = view.findViewById(R.id.header_text);
        checkNotNull(headerTextView);
        viewModel.mProviderNameLiveData.observe(getViewLifecycleOwner(),
                providerName -> headerTextView.setText(
                        getString(viewModel.mHeaderTextIdLiveData.getValue(), providerName)));
        viewModel.mHeaderTextIdLiveData.observe(getViewLifecycleOwner(),
                textId -> headerTextView.setText(
                        getString(textId, viewModel.mProviderNameLiveData.getValue())));

        TextView footerTextView = view.findViewById(R.id.footer_text);
        checkNotNull(footerTextView);
        viewModel.mFooterTextIdLiveData.observe(getViewLifecycleOwner(), footerTextView::setText);

        SetupController setupController =
                ((PolicyObjectsInterface) getActivity().getApplicationContext())
                        .getSetupController();

        ProvisioningProgressViewModel provisioningProgressViewModel =
                new ViewModelProvider(requireActivity()).get(ProvisioningProgressViewModel.class);
        Button button = view.findViewById(R.id.button_next);
        checkNotNull(button);
        button.setOnClickListener(
                v -> {
                    provisioningProgressViewModel.setProvisioningProgress(
                            ProvisioningProgress.GETTING_DEVICE_READY);
                    Futures.addCallback(
                            setupController.startSetupFlow(getActivity()),
                            new FutureCallback<>() {
                                @Override
                                public void onSuccess(Void result) {
                                    LogUtil.i(TAG, "Setup flow has started installing kiosk app");
                                    provisioningProgressViewModel.setProvisioningProgress(
                                            ProvisioningProgress.INSTALLING_KIOSK_APP);
                                }

                                @Override
                                public void onFailure(Throwable t) {
                                    LogUtil.e(TAG, "Failed to start setup flow!", t);
                                    // TODO(b/279969959): show setup failure UI
                                }
                            }, MoreExecutors.directExecutor());
                });

        setupController.addListener(new SetupController.SetupUpdatesCallbacks() {
            @Override
            public void setupFailed(int reason) {
                LogUtil.e(TAG, "Failed to finish setup flow!");
                // TODO(b/279969959): show setup failure UI
            }

            @Override
            public void setupCompleted() {
                LogUtil.i(TAG, "Successfully finished setup flow!");
                provisioningProgressViewModel.setProvisioningProgress(
                        ProvisioningProgress.OPENING_KIOSK_APP);
            }
        });
    }
}
