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

package com.android.car.dialer.ui.activecall;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.apps.common.util.ViewUtils;
import com.android.car.dialer.R;
import com.android.car.dialer.ui.view.ContactAvatarOutputlineProvider;
import com.android.car.telephony.common.CallDetail;
import com.android.car.telephony.common.TelecomUtils;

/**
 * View holder for a user profile of a conference
 */
public class ConferenceProfileViewHolder extends RecyclerView.ViewHolder {

    private ImageView mAvatar;
    private TextView mTitle;
    private TextView mNumber;
    @Nullable
    private TextView mLabel;
    private Context mContext;

    ConferenceProfileViewHolder(View v) {
        super(v);

        mAvatar = v.findViewById(R.id.user_profile_avatar);
        mAvatar.setOutlineProvider(ContactAvatarOutputlineProvider.get());
        mTitle = v.findViewById(R.id.user_profile_title);
        mNumber = v.findViewById(R.id.user_profile_phone_number);
        mLabel = v.findViewById(R.id.user_profile_phone_label);
        mContext = v.getContext();
    }

    /**
     * Binds call details to the profile views
     */
    public void bind(CallDetail callDetail) {
        String number = callDetail.getNumber();
        TelecomUtils.getPhoneNumberInfo(mContext, number)
                .thenAcceptAsync((info) -> {
                    if (mContext == null) {
                        return;
                    }

                    mAvatar.setImageDrawable(TelecomUtils.createLetterTile(mContext, null, null));
                    mTitle.setText(info.getDisplayName());

                    String readableNumber = TelecomUtils.getReadableNumber(mContext, number);
                    if (TextUtils.equals(info.getDisplayName(), readableNumber)) {
                        ViewUtils.setVisible(mLabel, false);
                        mNumber.setVisibility(View.GONE);
                    } else {
                        String phoneNumberLabel = info.getTypeLabel();
                        String bidiWrappedNumber;
                        if (!phoneNumberLabel.isEmpty()) {
                            bidiWrappedNumber = " ";
                            ViewUtils.setText(mLabel, phoneNumberLabel);
                            ViewUtils.setVisible(mLabel, true);
                        } else {
                            bidiWrappedNumber = "";
                            ViewUtils.setVisible(mLabel, false);
                        }
                        bidiWrappedNumber += TelecomUtils.getBidiWrappedNumber(readableNumber);
                        mNumber.setText(bidiWrappedNumber);
                        mNumber.setVisibility(View.VISIBLE);
                    }

                    TelecomUtils.setContactBitmapAsync(mContext, mAvatar,
                            info.getAvatarUri(), info.getInitials(), info.getDisplayName());

                }, mContext.getMainExecutor());
    }
}
