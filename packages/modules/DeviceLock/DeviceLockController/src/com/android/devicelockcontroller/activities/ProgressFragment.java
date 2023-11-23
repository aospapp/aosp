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
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.android.devicelockcontroller.R;

/**
 * A screen which always displays a progress bar.
 */
public final class ProgressFragment extends Fragment {

    /** The Bundle key for the resource id of the icon. */
    private static final String KEY_ICON_ID = "key_icon_id";

    /** The Bundle key for the resource id of the header text. */
    private static final String KEY_HEADER_TEXT_ID = "key_header_text_id";

    private static final String KEY_SUBHEADER_TEXT_ID = "key_subheader_text_id";

    @Nullable
    @Override
    public View onCreateView(
            LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_progress, container, false);

        ImageView headerIconImageView = v.findViewById(R.id.header_icon);
        checkNotNull(headerIconImageView);

        TextView headerTextView = v.findViewById(R.id.header_text);
        checkNotNull(headerTextView);

        TextView subheaderTextView = v.findViewById(R.id.subheader_text);
        checkNotNull(subheaderTextView);

        ProvisioningProgressViewModel provisioningProgressViewModel =
                new ViewModelProvider(requireActivity()).get(ProvisioningProgressViewModel.class);
        provisioningProgressViewModel.getProvisioningProgressLiveData().observe(
                getViewLifecycleOwner(), provisioningProgress -> {
                    if (provisioningProgress.mIconId != 0) {
                        headerIconImageView.setImageResource(provisioningProgress.mIconId);
                    }
                    if (provisioningProgress.mHeaderId != 0) {
                        headerTextView.setText(
                                requireContext().getString(provisioningProgress.mHeaderId,
                                        provisioningProgressViewModel
                                                .mProviderNameLiveData.getValue()));
                    }
                    if (provisioningProgress.mSubheaderId != 0) {
                        subheaderTextView.setText(
                                requireContext().getString(provisioningProgress.mSubheaderId));
                    }
                });

        return v;
    }

    static ProgressFragment create(int iconId, int headerTextId, int subheaderTextId) {
        ProgressFragment progressFragment = new ProgressFragment();
        Bundle bundle = new Bundle();
        if (iconId != 0) {
            bundle.putInt(KEY_ICON_ID, iconId);
        }
        if (headerTextId != 0) {
            bundle.putInt(KEY_HEADER_TEXT_ID, headerTextId);
        }
        if (subheaderTextId != 0) {
            bundle.putInt(KEY_SUBHEADER_TEXT_ID, subheaderTextId);
        }
        progressFragment.setArguments(bundle);
        return progressFragment;
    }

}
