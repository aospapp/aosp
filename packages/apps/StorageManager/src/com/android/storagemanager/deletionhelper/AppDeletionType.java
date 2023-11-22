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

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.ArraySet;
import android.util.Log;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.settingslib.applications.ApplicationsState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * AppDeletionType provides a list of apps which have not been used for a while on the system.
 * It also provides the functionality to clear out these apps.
 */
public class AppDeletionType implements DeletionType, ApplicationsState.Callbacks,
        AppStateBaseBridge.Callback {
    public static final String EXTRA_CHECKED_SET = "checkedSet";
    private static final String TAG = "AppDeletionType";

    private FreeableChangedListener mListener;
    private AppListener mAppListener;
    private List<ApplicationsState.AppEntry> mAppEntries;
    private ApplicationsState mState;
    private ApplicationsState.Session mSession;
    private HashSet<String> mCheckedApplications;
    private AppStateUsageStatsBridge mDataUsageBridge;

    public AppDeletionType(Application app, HashSet<String> checkedApplications) {
        mState = ApplicationsState.getInstance(app);
        mSession = mState.newSession(this);
        if (checkedApplications != null) {
            mCheckedApplications = checkedApplications;
        } else {
            mCheckedApplications = new HashSet<>();
        }
        mDataUsageBridge = new AppStateUsageStatsBridge(app.getApplicationContext(), mState, this);
    }

    @Override
    public void registerFreeableChangedListener(FreeableChangedListener listener) {
        mListener = listener;
    }

    @Override
    public void onResume() {
        mSession.resume();
        mDataUsageBridge.resume();
    }

    @Override
    public void onPause() {
        mSession.pause();
        mDataUsageBridge.pause();
    }

    @Override
    public void onSaveInstanceStateBundle(Bundle savedInstanceState) {
        savedInstanceState.putSerializable(EXTRA_CHECKED_SET, mCheckedApplications);
    }

    @Override
    public void clearFreeableData(Activity activity) {
        ArraySet<String> apps = new ArraySet<>();
        for (ApplicationsState.AppEntry entry : mAppEntries) {
            final String packageName;
            synchronized (entry) {
                packageName = entry.info.packageName;
            }
            if (mCheckedApplications.contains(packageName)) {
                apps.add(packageName);
            }
        }
        // TODO: If needed, add an action on the callback.
        PackageDeletionTask task = new PackageDeletionTask(activity.getPackageManager(), apps,
                new PackageDeletionTask.Callback() {
                    @Override
                    public void onSuccess() {
                    }

                    @Override
                    public void onError() {
                        Log.e(TAG, "An error occurred while uninstalling packages.");
                        MetricsLogger.action(activity,
                                MetricsEvent.ACTION_DELETION_HELPER_APPS_DELETION_FAIL);
                    }
                });

        task.run();
    }

    @Override
    public void onRunningStateChanged(boolean running) {

    }

    @Override
    public void onPackageListChanged() {
    }

    @Override
    public void onRebuildComplete(ArrayList<ApplicationsState.AppEntry> apps) {
        if (apps == null) {
            return;
        }
        mAppEntries = apps;
        for (ApplicationsState.AppEntry app : mAppEntries) {
            synchronized (app) {
                mState.ensureIcon(app);
            }
        }
        if (mAppListener != null) {
            mAppListener.onAppRebuild(mAppEntries);
        }
        maybeNotifyListener();
    }

    @Override
    public void onPackageIconChanged() {
    }

    @Override
    public void onPackageSizeChanged(String packageName) {
        rebuild();
    }

    @Override
    public void onAllSizesComputed() {
        rebuild();
    }

    @Override
    public void onLauncherInfoChanged() {

    }

    @Override
    public void onLoadEntriesCompleted() {
        rebuild();
    }

    @Override
    public void onExtraInfoUpdated() {
        rebuild();
    }

    /**
     * Registers a preference group view to notify when the app list changes.
     */
    public void registerView(AppDeletionPreferenceGroup preference) {
        mAppListener = preference;
    }

    /**
     * Set a package to be checked for deletion, if the apps are cleared.
     * @param packageName The name of the package to potentially delete.
     * @param isChecked Whether or not the package should be deleted.
     */
    public void setChecked(String packageName, boolean isChecked) {
        if (isChecked) {
            mCheckedApplications.add(packageName);
        } else {
            mCheckedApplications.remove(packageName);
        }
        maybeNotifyListener();
    }

    /**
     * Returns an amount of clearable app data.
     * @param countUnchecked If unchecked applications should be counted for size purposes.
     */
    public long getTotalAppsFreeableSpace(boolean countUnchecked) {
        long freeableSpace = 0;
        if (mAppEntries != null) {
            for (int i = 0; i < mAppEntries.size(); i++) {
                final ApplicationsState.AppEntry entry = mAppEntries.get(i);
                long entrySize = entry.size;
                final String packageName;
                synchronized (entry) {
                    packageName = entry.info.packageName;
                }
                // If the entrySize is negative, it is either an unknown size or an error occurred.
                if ((countUnchecked ||
                        mCheckedApplications.contains(packageName)) && entrySize > 0) {
                    freeableSpace += entrySize;
                }
            }
        }

        return freeableSpace;
    }

    /**
     * Returns a number of eligible, clearable apps.
     */
    public int getEligibleApps() {
        if (mAppEntries == null) {
            return 0;
        }
        return mAppEntries.size();
    }

    /**
     * Returns if a given package is slated for deletion.
     * @param packageName The name of the package to check.
     */
    public boolean isChecked(String packageName) {
        return mCheckedApplications.contains(packageName);
    }

    private void rebuild() {
        mSession.rebuild(AppStateUsageStatsBridge.FILTER_USAGE_STATS,
                ApplicationsState.SIZE_COMPARATOR);
    }

    private void maybeNotifyListener() {
        if (mListener != null) {
            mListener.onFreeableChanged(mAppEntries.size(), getTotalAppsFreeableSpace(true));
        }
    }

    /**
     * An interface for listening for when the app list has been rebuilt.
     */
    public interface AppListener {
        /**
         * Callback to be called once the app list is rebuilt.
         * @param apps A list of eligible, clearable AppEntries.
         */
        void onAppRebuild(List<ApplicationsState.AppEntry> apps);
    }
}
