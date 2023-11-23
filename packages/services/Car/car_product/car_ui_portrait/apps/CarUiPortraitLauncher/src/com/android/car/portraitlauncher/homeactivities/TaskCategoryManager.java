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

package com.android.car.portraitlauncher.homeactivities;

import android.app.ActivityManager;
import android.app.TaskInfo;
import android.car.media.CarMediaIntents;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;

import com.android.car.carlauncher.AppGridActivity;
import com.android.car.portraitlauncher.R;

import java.util.List;
import java.util.Set;

/**
 * Manages the task categories for {@link CarUiPortraitHomeScreen}, check which category a task
 * belongs to.
 */
class TaskCategoryManager {
    public static final String TAG = TaskCategoryManager.class.getSimpleName();
    private static final boolean DBG = Build.IS_DEBUGGABLE;
    private final ComponentName mBlankActivityComponent;
    private final ComponentName mAppGridActivityComponent;
    private final ComponentName mNotificationActivityComponent;
    private final ComponentName mRecentsActivityComponent;
    private final ArraySet<ComponentName> mIgnoreOpeningRootTaskViewComponentsSet;
    private final Set<ComponentName> mFullScreenActivities;
    private final Set<ComponentName> mBackgroundActivities;

    private ComponentName mCurrentBackgroundApp;

    private final Context mContext;

    TaskCategoryManager(Context context) {
        mContext = context;

        mFullScreenActivities = convertToComponentNames(mContext.getResources()
                .getStringArray(R.array.config_fullScreenActivities));
        mBackgroundActivities = convertToComponentNames(mContext.getResources()
                .getStringArray(R.array.config_backgroundActivities));
        mIgnoreOpeningRootTaskViewComponentsSet = convertToComponentNames(mContext.getResources()
                .getStringArray(R.array.config_ignoreOpeningForegroundDA));
        mAppGridActivityComponent = new ComponentName(context, AppGridActivity.class);
        mBlankActivityComponent = new ComponentName(context, BlankActivity.class);
        mNotificationActivityComponent = ComponentName.unflattenFromString(
                mContext.getResources().getString(R.string.config_notificationActivity));
        mRecentsActivityComponent = ComponentName.unflattenFromString(mContext.getResources()
                .getString(com.android.internal.R.string.config_recentsComponentName));

        updateVoicePlateActivityMap();
    }

    void updateVoicePlateActivityMap() {
        Context currentUserContext = mContext.createContextAsUser(
                UserHandle.of(ActivityManager.getCurrentUser()), /* flags= */ 0);

        Intent voiceIntent = new Intent(Intent.ACTION_VOICE_ASSIST, /* uri= */ null);
        List<ResolveInfo> result = currentUserContext.getPackageManager().queryIntentActivities(
                voiceIntent, PackageManager.MATCH_ALL);

        for (ResolveInfo info : result) {
            if (mFullScreenActivities.add(info.activityInfo.getComponentName())) {
                logIfDebuggable("adding the following component to show on fullscreen: "
                        + info.activityInfo.getComponentName());
            }
        }
    }

    boolean isBackgroundApp(TaskInfo taskInfo) {
        return mBackgroundActivities.contains(taskInfo.baseActivity);
    }

    boolean isBackgroundApp(ComponentName componentName) {
        return mBackgroundActivities.contains(componentName);
    }

    boolean isCurrentBackgroundApp(ComponentName componentName) {
        return mCurrentBackgroundApp != null && mCurrentBackgroundApp.equals(componentName);
    }

    ComponentName getCurrentBackgroundApp() {
        return mCurrentBackgroundApp;
    }

    void setCurrentBackgroundApp(ComponentName componentName) {
        mCurrentBackgroundApp = componentName;
    }

    boolean isBlankActivity(ActivityManager.RunningTaskInfo taskInfo) {
        return mBlankActivityComponent.equals(taskInfo.baseActivity);
    }

    boolean isAppGridActivity(TaskInfo taskInfo) {
        return mAppGridActivityComponent.equals(taskInfo.baseActivity);
    }

    public Set<ComponentName> getFullScreenActivities() {
        return mFullScreenActivities;
    }

    boolean isFullScreenActivity(TaskInfo taskInfo) {
        return mFullScreenActivities.contains(taskInfo.baseActivity);
    }

    boolean isNotificationActivity(TaskInfo taskInfo) {
        return mNotificationActivityComponent.equals(taskInfo.baseActivity);
    }

    boolean isRecentsActivity(TaskInfo taskInfo) {
        return mRecentsActivityComponent.equals(taskInfo.baseActivity);
    }

    boolean shouldIgnoreOpeningForegroundDA(TaskInfo taskInfo) {
        return taskInfo.baseIntent != null && mIgnoreOpeningRootTaskViewComponentsSet.contains(
                taskInfo.baseIntent.getComponent());
    }

    static boolean isHomeIntent(TaskInfo taskInfo) {
        return taskInfo.baseIntent != null
                && taskInfo.baseIntent.getCategories() != null
                && taskInfo.baseIntent.getCategories().contains(Intent.CATEGORY_HOME);
    }

    private static void logIfDebuggable(String message) {
        if (DBG) {
            Log.d(TAG, message);
        }
    }

    /**
     * @return {@code true} if current task in panel was launched using media intent.
     */
    public static boolean isMediaApp(TaskInfo taskInfo) {
        return taskInfo != null && taskInfo.baseIntent != null
                && CarMediaIntents.ACTION_MEDIA_TEMPLATE.equals(taskInfo.baseIntent.getAction());
    }

    private static ArraySet<ComponentName> convertToComponentNames(String[] componentStrings) {
        ArraySet<ComponentName> componentNames = new ArraySet<>(componentStrings.length);
        for (int i = componentStrings.length - 1; i >= 0; i--) {
            componentNames.add(ComponentName.unflattenFromString(componentStrings[i]));
        }
        return componentNames;
    }
}
