/*
 * Copyright (C) 2022 The Android Open Source Project.
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

package com.android.car.cartelemetryapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ConfigListAdaptor extends RecyclerView.Adapter<ConfigListAdaptor.ViewHolder> {
    private List<ConfigData> mConfigs;
    private Callback mCallback;

    public interface Callback {
        void onCheckedChanged(ConfigData config, boolean isChecked);
        void onInfoButtonClicked(ConfigData config);
        void onClearButtonClicked(ConfigData config);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final CheckBox mCheckBox;
        private final TextView mConfigText;
        private final TextView mOnReadyText;
        private final TextView mSentBytesText;
        private final TextView mErrorsCountText;
        private final Button mInfoButton;
        private final Button mClearButton;

        public ViewHolder(View view) {
            super(view);
            mCheckBox = view.findViewById(R.id.checkbox);
            mConfigText = view.findViewById(R.id.config_name_text);
            mOnReadyText = view.findViewById(R.id.on_ready_times_text);
            mSentBytesText = view.findViewById(R.id.sent_bytes_text);
            mErrorsCountText = view.findViewById(R.id.error_count_text);
            mInfoButton = view.findViewById(R.id.show_info_button);
            mClearButton = view.findViewById(R.id.clear_info_button);
        }

        public CheckBox getCheckBox() {
            return mCheckBox;
        }

        public TextView getConfigText() {
            return mConfigText;
        }

        public TextView getOnReadyText() {
            return mOnReadyText;
        }

        public TextView getSentBytesText() {
            return mSentBytesText;
        }

        public TextView getErrorsCountText() {
            return mErrorsCountText;
        }

        public Button getInfoButton() {
            return mInfoButton;
        }

        public Button getClearButton() {
            return mClearButton;
        }
    }

    public ConfigListAdaptor(
            List<ConfigData> configs,
            Callback callback) {
        mConfigs = configs;
        mCallback = callback;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.list_item, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        int pos = holder.getAbsoluteAdapterPosition();
        ConfigData config = mConfigs.get(pos);
        holder.getConfigText().setText(config.getName());
        holder.getCheckBox().setOnCheckedChangeListener((buttonView, isChecked) -> {
            mCallback.onCheckedChanged(config, isChecked);
        });
        holder.getCheckBox().setChecked(config.selected);
        holder.getOnReadyText().setText(String.valueOf(config.onReadyTimes));
        String bytesSentStr = String.valueOf(config.sentBytes) + " B";
        holder.getSentBytesText().setText(bytesSentStr);
        holder.getErrorsCountText().setText(String.valueOf(config.errorCount));
        holder.getInfoButton().setOnClickListener(v -> {
            mCallback.onInfoButtonClicked(config);
        });
        holder.getClearButton().setOnClickListener(v -> {
            mCallback.onClearButtonClicked(config);
        });
    }

    @Override
    public int getItemCount() {
        return mConfigs.size();
    }
}
