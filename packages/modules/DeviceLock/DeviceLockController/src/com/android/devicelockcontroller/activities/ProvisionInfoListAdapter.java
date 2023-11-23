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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;

import com.android.devicelockcontroller.R;

/**
 * An Adapter which provides the binding between {@link ProvisionInfo} and the
 * {@link androidx.recyclerview.widget.RecyclerView}.
 */
public final class ProvisionInfoListAdapter extends
        ListAdapter<ProvisionInfo, ProvisionInfoViewHolder> {

    private final ProvisionInfoViewModel mViewModel;
    private final LifecycleOwner mLifecycleOwner;

    ProvisionInfoListAdapter(ProvisionInfoViewModel viewModel, LifecycleOwner lifecycleOwner) {
        super(new DiffUtil.ItemCallback<>() {
            @Override
            public boolean areItemsTheSame(@NonNull ProvisionInfo oldItem,
                    @NonNull ProvisionInfo newItem) {
                return oldItem.equals(newItem);
            }

            @Override
            public boolean areContentsTheSame(@NonNull ProvisionInfo oldItem,
                    @NonNull ProvisionInfo newItem) {
                return oldItem.equals(newItem);
            }
        });
        mViewModel = viewModel;
        mLifecycleOwner = lifecycleOwner;
    }

    @NonNull
    @Override
    public ProvisionInfoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_provision_info, parent, false);
        return new ProvisionInfoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ProvisionInfoViewHolder provisionInfoViewHolder,
            int position) {
        String providerName = mViewModel.mProviderNameLiveData.getValue();
        if (TextUtils.isEmpty(providerName)) {
            mViewModel.mProviderNameLiveData.observe(mLifecycleOwner,
                    newValue -> notifyItemChanged(position));
            return;
        }

        // TODO(b/282248521): Refactor ProvisionInfoList, ProviderName, and TermsAndConditionsUrl
        //  Live Data.
        provisionInfoViewHolder.bind(getItem(position), providerName,
                mViewModel.mTermsAndConditionsUrlLiveData.getValue());
    }
}
