/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.storagemanager.deletionhelper;

import android.content.Context;
import android.support.v7.preference.Preference;
import android.text.format.Formatter;
import android.util.AttributeSet;
import android.view.View;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.settingslib.applications.ApplicationsState;
import com.android.storagemanager.deletionhelper.AppStateUsageStatsBridge.UsageStatsState;
import com.android.storagemanager.PreferenceListCache;
import com.android.storagemanager.R;

import java.util.List;

/**
 * AppDeletionPreferenceGroup is a collapsible checkbox preference group which contains many
 * apps to be cleared in the Deletion Helper.
 */
public class AppDeletionPreferenceGroup extends CollapsibleCheckboxPreferenceGroup
        implements AppDeletionType.AppListener, Preference.OnPreferenceChangeListener {
    private AppDeletionType mBackend;

    public AppDeletionPreferenceGroup(Context context) {
        this(context, null);
    }

    public AppDeletionPreferenceGroup(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOnPreferenceChangeListener(this);
        updateText();
    }

    @Override
    public void onAppRebuild(List<ApplicationsState.AppEntry> apps) {
        int entryCount = apps.size();
        int currentUserId = getContext().getUserId();
        PreferenceListCache cache = new PreferenceListCache(this);
        for (int i = 0; i < entryCount; i++) {
            ApplicationsState.AppEntry entry = apps.get(i);

            // If it's a different user's app, skip it.
            UsageStatsState extraData = (UsageStatsState) entry.extraInfo;
            if (extraData.userId != currentUserId) {
                continue;
            }

            final String packageName;
            synchronized (entry) {
                packageName = entry.info.packageName;
            }
            AppDeletionPreference preference =
                    (AppDeletionPreference) cache.getCachedPreference(packageName);
            if (preference == null) {
                preference = new AppDeletionPreference(getContext(), entry);
                preference.setKey(packageName);
                preference.setOnPreferenceChangeListener(this);
            }

            addPreference(preference);
            preference.setChecked(mBackend.isChecked(packageName));
            preference.setOrder(i);
            preference.updateSummary();
        }
        cache.removeCachedPrefs();
        updateText();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        boolean isChecked = (boolean) newValue;

        // If we have no AppDeletionType, we have no apps to toggle.
        if (mBackend == null) {
            return true;
        }

        if (preference == this) {
            for (int i = 0; i < getPreferenceCount(); i++) {
                AppDeletionPreference p = (AppDeletionPreference) getPreference(i);
                p.setOnPreferenceChangeListener(null);
                p.setChecked(isChecked);
                mBackend.setChecked(p.getPackageName(), isChecked);
                p.setOnPreferenceChangeListener(this);
            }
            updateText();
            MetricsLogger.action(getContext(), MetricsEvent.ACTION_DELETION_SELECTION_ALL_APPS,
                    isChecked);
            return true;
        }

        // If a single preference changed, we need to toggle just itself.
        AppDeletionPreference p = (AppDeletionPreference) preference;
        mBackend.setChecked(p.getPackageName(), isChecked);
        logAppToggle(isChecked, p.getPackageName());
        updateText();
        return true;
    }

    @Override
    public void onClick() {
        super.onClick();
        MetricsLogger.action(getContext(), MetricsEvent.ACTION_DELETION_APPS_COLLAPSED,
                isCollapsed());
    }

    /**
     * Initializes the PreferenceGroup with a source of apps to list.
     * @param type The AppDeletionType which provides the app list.
     */
    public void setDeletionType(AppDeletionType type) {
        mBackend = type;
    }

    private void updateText() {
        int eligibleApps = 0;
        long freeableBytes = 0;
        if (mBackend != null) {
            eligibleApps = mBackend.getEligibleApps();
            freeableBytes = mBackend.getTotalAppsFreeableSpace(true);
        }
        Context app = getContext();
        setTitle(app.getString(R.string.deletion_helper_apps_group_title, eligibleApps));
        setSummary(app.getString(R.string.deletion_helper_apps_group_summary,
                Formatter.formatFileSize(app, freeableBytes),
                AppStateUsageStatsBridge.UNUSED_DAYS_DELETION_THRESHOLD));
    }

    private void logAppToggle(boolean checked, String packageName) {
        if (checked) {
            MetricsLogger.action(getContext(), MetricsEvent.ACTION_DELETION_SELECTION_APP_ON,
                    packageName);
        } else {
            MetricsLogger.action(getContext(), MetricsEvent.ACTION_DELETION_SELECTION_APP_OFF,
                    packageName);
        }
    }
}
