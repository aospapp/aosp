/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.car.dialer.ui.calllog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.dialer.R;
import com.android.car.dialer.log.L;
import com.android.car.dialer.ui.common.entity.UiCallLog;
import com.android.car.telephony.common.Contact;

import java.util.ArrayList;
import java.util.List;

/** Adapter for call history list. */
public class CallLogAdapter extends RecyclerView.Adapter<CallLogViewHolder> {

    private static final String TAG = "CD.CallLogAdapter";

    public interface OnShowContactDetailListener {
        void onShowContactDetail(Contact contact);
    }

    private List<UiCallLog> mUiCallLogs = new ArrayList<>();
    private Context mContext;
    private CallLogAdapter.OnShowContactDetailListener mOnShowContactDetailListener;

    public CallLogAdapter(Context context,
            CallLogAdapter.OnShowContactDetailListener onShowContactDetailListener) {
        mContext = context;
        mOnShowContactDetailListener = onShowContactDetailListener;
    }

    public void setUiCallLogs(@NonNull List<UiCallLog> uiCallLogs) {
        L.d(TAG, "setUiCallLogs: %d", uiCallLogs.size());
        mUiCallLogs.clear();
        mUiCallLogs.addAll(uiCallLogs);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CallLogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View rootView = LayoutInflater.from(mContext)
                .inflate(R.layout.call_history_list_item, parent, false);
        return new CallLogViewHolder(rootView, mOnShowContactDetailListener);
    }

    @Override
    public void onBindViewHolder(@NonNull CallLogViewHolder holder, int position) {
        holder.onBind(mUiCallLogs.get(position));
    }

    @Override
    public void onViewRecycled(@NonNull CallLogViewHolder holder) {
        holder.onRecycle();
    }

    @Override
    public int getItemCount() {
        return mUiCallLogs.size();
    }
}

