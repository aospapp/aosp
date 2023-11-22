/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.storagemanager.deletionhelper;

import android.content.Context;
import android.support.v7.preference.CheckBoxPreference;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceViewHolder;
import android.text.format.Formatter;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Switch;
import android.widget.TextView;
import com.android.storagemanager.deletionhelper.AppStateUsageStatsBridge.UsageStatsState;
import com.android.storagemanager.R;

import com.android.settingslib.applications.ApplicationsState;
import com.android.settingslib.applications.ApplicationsState.AppEntry;

/**
 * Preference item for an app with a switch to signify if it should be uninstalled.
 * This shows the name and icon of the app along with the days since its last use.
 */
public class AppDeletionPreference extends NestedCheckboxPreference {
    private AppEntry mEntry;
    private Context mContext;

    public AppDeletionPreference(Context context, AppEntry item) {
        super(context);
        mEntry = item;
        mContext = context;
        setIcon(item.icon);
        setTitle(item.label);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.setDividerAllowedAbove(false);
    }

    /**
     * Returns the package name for the app that these preference represents.
     */
    public String getPackageName() {
        synchronized(mEntry) {
            return mEntry.info.packageName;
        }
    }

    public void updateSummary() {
        if (mEntry.extraInfo == null) return;
        if (mEntry.size == ApplicationsState.SIZE_UNKNOWN ||
                mEntry.size == ApplicationsState.SIZE_INVALID) {
            return;
        }

        UsageStatsState extraData = (UsageStatsState) mEntry.extraInfo;
        String fileSize = Formatter.formatFileSize(mContext, mEntry.size);
        if (extraData.daysSinceLastUse == AppStateUsageStatsBridge.NEVER_USED) {
            setSummary(mContext.getString(R.string.deletion_helper_app_summary_never_used,
                    fileSize));
        } else if (extraData.daysSinceLastUse == AppStateUsageStatsBridge.UNKNOWN_LAST_USE) {
            setSummary(mContext.getString(R.string.deletion_helper_app_summary_unknown_used,
                    fileSize));
        } else {
            setSummary(mContext.getString(R.string.deletion_helper_app_summary,
                    fileSize,
                    extraData.daysSinceLastUse));
        }
    }

}
