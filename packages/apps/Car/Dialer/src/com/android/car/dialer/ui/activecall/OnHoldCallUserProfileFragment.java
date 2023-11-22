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

package com.android.car.dialer.ui.activecall;

import android.net.Uri;
import android.os.Bundle;
import android.telecom.Call;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProviders;

import com.android.car.dialer.R;
import com.android.car.dialer.ui.view.ContactAvatarOutputlineProvider;
import com.android.car.telephony.common.CallDetail;
import com.android.car.telephony.common.TelecomUtils;

/**
 * A fragment that displays information about onhold call.
 */
public class OnHoldCallUserProfileFragment extends Fragment {

    private TextView mTitle;
    private ImageView mAvatarView;
    private ImageView mSwapCallsButton;
    private LiveData<Call> mPrimaryCallLiveData;
    private LiveData<Call> mSecondaryCallLiveData;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View fragmentView = inflater.inflate(R.layout.onhold_user_profile, container, false);

        mTitle = fragmentView.findViewById(R.id.title);
        mAvatarView = fragmentView.findViewById(R.id.icon);
        mAvatarView.setOutlineProvider(ContactAvatarOutputlineProvider.get());

        mSwapCallsButton = fragmentView.findViewById(R.id.swap_calls_button);
        mSwapCallsButton.setOnClickListener(v -> swapCalls());

        InCallViewModel inCallViewModel = ViewModelProviders.of(getActivity()).get(
                InCallViewModel.class);
        inCallViewModel.getSecondaryCallDetail().observe(this, this::updateProfile);
        mPrimaryCallLiveData = inCallViewModel.getPrimaryCall();
        mSecondaryCallLiveData = inCallViewModel.getSecondaryCall();

        return fragmentView;
    }

    private void updateProfile(@Nullable CallDetail callDetail) {
        if (callDetail == null) {
            return;
        }

        String number = callDetail.getNumber();
        Pair<String, Uri> displayNameAndAvatarUri = TelecomUtils.getDisplayNameAndAvatarUri(
                getContext(), number);

        mTitle.setText(displayNameAndAvatarUri.first);
        TelecomUtils.setContactBitmapAsync(getContext(), mAvatarView,
                displayNameAndAvatarUri.second, displayNameAndAvatarUri.first);
    }

    private void swapCalls() {
        // Unholds onhold call
        if (mSecondaryCallLiveData.getValue() != null) {
            mSecondaryCallLiveData.getValue().unhold();
        }

        // hold primary call
        if (mPrimaryCallLiveData.getValue().getState() != Call.STATE_HOLDING) {
            mPrimaryCallLiveData.getValue().hold();
        }
    }
}
