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

package com.android.car.carlauncher;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.content.pm.CarPackageManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.service.media.MediaBrowserService;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Util class that contains helper method used by app launcher classes.
 */
class AppLauncherUtils {

    private static final String TAG = "AppLauncherUtils";

    private AppLauncherUtils() {
    }

    /**
     * Comparator for {@link AppMetaData} that sorts the list
     * by the "displayName" property in ascending order.
     */
    static final Comparator<AppMetaData> ALPHABETICAL_COMPARATOR = Comparator
            .comparing(AppMetaData::getDisplayName, String::compareToIgnoreCase);

    /**
     * Helper method that launches the app given the app's AppMetaData.
     *
     * @param app the requesting app's AppMetaData
     */
    static void launchApp(Context context, AppMetaData app) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(context.getDisplayId());
        context.startActivity(app.getMainLaunchIntent(), options.toBundle());
    }

    /** Bundles application and services info. */
    static class LauncherAppsInfo {
        /** Map of all apps' metadata keyed by package name. */
        private final Map<String, AppMetaData> mApplications;

        /** Map of all the media services keyed by package name. */
        private final Map<String, ResolveInfo> mMediaServices;

        LauncherAppsInfo(@NonNull Map<String, AppMetaData> apps,
                @NonNull Map<String, ResolveInfo> mediaServices) {
            mApplications = apps;
            mMediaServices = mediaServices;
        }

        /** Returns true if all maps are empty. */
        boolean isEmpty() {
            return mApplications.isEmpty() && mMediaServices.isEmpty();
        }

        /** Returns whether the given package name is a media service. */
        boolean isMediaService(String packageName) {
            return mMediaServices.containsKey(packageName);
        }

        /** Returns the {@link AppMetaData} for the given package name. */
        @Nullable
        AppMetaData getAppMetaData(String packageName) {
            return mApplications.get(packageName);
        }

        /** Returns a new list of the applications' {@link AppMetaData}. */
        @NonNull
        List<AppMetaData> getApplicationsList() {
            return new ArrayList<>(mApplications.values());
        }
    }

    private final static LauncherAppsInfo EMPTY_APPS_INFO = new LauncherAppsInfo(
            Collections.emptyMap(), Collections.emptyMap());

    /**
     * Gets all the apps that we want to see in the launcher in unsorted order. Includes media
     * services without launcher activities.
     *
     * @param blackList         A (possibly empty) list of apps to hide
     * @param launcherApps      The {@link LauncherApps} system service
     * @param carPackageManager The {@link CarPackageManager} system service
     * @param packageManager    The {@link PackageManager} system service
     * @return a new {@link LauncherAppsInfo}
     */
    @NonNull
    static LauncherAppsInfo getAllLauncherApps(
            @NonNull Set<String> blackList,
            LauncherApps launcherApps,
            CarPackageManager carPackageManager,
            PackageManager packageManager) {

        if (launcherApps == null || carPackageManager == null || packageManager == null) {
            return EMPTY_APPS_INFO;
        }

        List<ResolveInfo> mediaServices = packageManager.queryIntentServices(
                new Intent(MediaBrowserService.SERVICE_INTERFACE),
                PackageManager.GET_RESOLVED_FILTER);
        List<LauncherActivityInfo> availableActivities =
                launcherApps.getActivityList(null, Process.myUserHandle());

        Map<String, AppMetaData> apps = new HashMap<>(
                mediaServices.size() + availableActivities.size());
        Map<String, ResolveInfo> mediaServicesMap = new HashMap<>(mediaServices.size());

        // Process media services
        for (ResolveInfo info : mediaServices) {
            String packageName = info.serviceInfo.packageName;
            mediaServicesMap.put(packageName, info);
            if (shouldAdd(packageName, apps, blackList)) {
                final boolean isDistractionOptimized = true;

                Intent intent = new Intent(Car.CAR_INTENT_ACTION_MEDIA_TEMPLATE);
                intent.putExtra(Car.CAR_EXTRA_MEDIA_PACKAGE, packageName);

                AppMetaData appMetaData = new AppMetaData(
                        info.serviceInfo.loadLabel(packageManager),
                        packageName,
                        info.serviceInfo.loadIcon(packageManager),
                        isDistractionOptimized,
                        intent,
                        packageManager.getLaunchIntentForPackage(packageName));
                apps.put(packageName, appMetaData);
            }
        }

        // Process activities
        for (LauncherActivityInfo info : availableActivities) {
            String packageName = info.getComponentName().getPackageName();
            if (shouldAdd(packageName, apps, blackList)) {
                boolean isDistractionOptimized =
                        isActivityDistractionOptimized(carPackageManager, packageName,
                                info.getName());

                AppMetaData appMetaData = new AppMetaData(
                        info.getLabel(),
                        packageName,
                        info.getBadgedIcon(0),
                        isDistractionOptimized,
                        packageManager.getLaunchIntentForPackage(packageName),
                        null);
                apps.put(packageName, appMetaData);
            }
        }

        return new LauncherAppsInfo(apps, mediaServicesMap);
    }

    private static boolean shouldAdd(String packageName, Map<String, AppMetaData> apps,
            @NonNull Set<String> blackList) {
        return !apps.containsKey(packageName) && !blackList.contains(packageName);
    }

    /**
     * Gets if an activity is distraction optimized.
     *
     * @param carPackageManager The {@link CarPackageManager} system service
     * @param packageName       The package name of the app
     * @param activityName      The requested activity name
     * @return true if the supplied activity is distraction optimized
     */
    static boolean isActivityDistractionOptimized(
            CarPackageManager carPackageManager, String packageName, String activityName) {
        boolean isDistractionOptimized = false;
        // try getting distraction optimization info
        try {
            if (carPackageManager != null) {
                isDistractionOptimized =
                        carPackageManager.isActivityDistractionOptimized(packageName, activityName);
            }
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car not connected when getting DO info", e);
        }
        return isDistractionOptimized;
    }
}
