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

import android.content.Context;
import android.content.Intent;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.android.devicelockcontroller.R;
import com.android.devicelockcontroller.util.LogUtil;

/**
 * A {@link androidx.recyclerview.widget.RecyclerView.ViewHolder} class which describes the item
 * views used in the {@link RecyclerView}
 */
public final class ProvisionInfoViewHolder extends RecyclerView.ViewHolder {

    private static final String TAG = "ProvisionInfoViewHolder";

    private final TextView mTextView;

    public ProvisionInfoViewHolder(@NonNull View itemView) {
        super(itemView);
        mTextView = itemView.findViewById(R.id.text_view_item_provision_info);
    }

    void bind(ProvisionInfo provisionInfo, String providerName,
            @Nullable String termsAndConditionsUrl) {
        Context context = itemView.getContext();
        if (TextUtils.isEmpty(providerName)) {
            LogUtil.e(TAG, "Device provider name is empty, should not reach here.");
            return;
        }

        // The Terms and Conditions URL is known at runtime and required for the string used for the
        // Device Subsidy program.
        if (provisionInfo.getTextId() == R.string.restrict_device_if_dont_make_payment) {
            if (TextUtils.isEmpty(termsAndConditionsUrl)) {
                LogUtil.e(TAG, "Terms and Conditions URL is empty, should not reach here.");
                return;
            }

            SpannableString spannedTextView = new SpannableString(Html.fromHtml(
                    String.format(
                            context.getString(R.string.restrict_device_if_dont_make_payment),
                            providerName, termsAndConditionsUrl),
                    Html.FROM_HTML_MODE_LEGACY));
            URLSpan[] spans = spannedTextView.getSpans(0, spannedTextView.length(),
                    URLSpan.class);

            for (URLSpan span : spans) {
                int start = spannedTextView.getSpanStart(span);
                int end = spannedTextView.getSpanEnd(span);

                ClickableSpan clickableSpan = new CustomClickableSpan(span.getURL());
                spannedTextView.removeSpan(span);
                spannedTextView.setSpan(clickableSpan, start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            mTextView.setText(spannedTextView);
            mTextView.setMovementMethod(LinkMovementMethod.getInstance());
        } else {
            mTextView.setText(context.getString(provisionInfo.getTextId(), providerName));
        }

        mTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(provisionInfo.getDrawableId(),
                /* top=*/ 0,
                /* end=*/ 0,
                /* bottom=*/ 0);
    }

    private static final class CustomClickableSpan extends ClickableSpan {

        final String mUrl;

        CustomClickableSpan(String url) {
            mUrl = url;
        }

        @Override
        public void onClick(@NonNull View view) {
            Intent webIntent = new Intent(view.getContext(), HelpActivity.class);
            webIntent.putExtra(HelpActivity.EXTRA_URL_PARAM, mUrl);
            view.getContext().startActivity(webIntent);
        }
    }
}
