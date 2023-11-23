/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.carlauncher.displayarea;

import android.app.ActivityManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import com.android.car.carlauncher.CarLauncherUtils;
import com.android.car.carlauncher.taskstack.TaskStackChangeListeners;
import com.android.wm.shell.common.HandlerExecutor;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Component to monitor the health of the maps background display area. It will not reattempt
 * recovering the maps activity if the health check is requested within 10s of the previous request.
 */
public class CarDisplayAreaHealthMonitor {

    private static final String TAG = "DAHealthMonitor";
    private static final boolean DEBUG = Build.IS_DEBUGGABLE;
    private static final long MINIMUM_CHECK_INTERVAL_MS = 10000;

    private static CarDisplayAreaHealthMonitor sInstance;

    /** Returns a singleton instance of the health monitor. */
    public static synchronized CarDisplayAreaHealthMonitor getInstance(Context context,
            CarDisplayAreaOrganizer organizer) {
        if (sInstance == null) {
            sInstance = new CarDisplayAreaHealthMonitor(context, Clock.systemUTC(),
                    new HandlerExecutor(
                            Handler.getMain()), () -> organizer.startMapsInBackGroundDisplayArea());
        }

        return sInstance;
    }

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) {
            if (DEBUG) {
                Log.d(TAG, "onTaskCreated");
            }
            healthCheckIfNeeded();
        }

        @Override
        public void onTaskRemoved(int taskId) {
            if (DEBUG) {
                Log.d(TAG, "onTaskRemoved");
            }
            healthCheckIfNeeded();
        }

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
            if (DEBUG) {
                Log.d(TAG, "onTaskMovedToFront");
            }
            healthCheckIfNeeded();
        }

        @Override
        public void onTaskStackChanged() {
            if (DEBUG) {
                Log.d(TAG, "onTaskStackChanged");
            }
            healthCheckIfNeeded();
        }
    };

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final ActivityManager mActivityManager;
    private final Clock mClock;
    private final Executor mExecutor;
    private final Runnable mRecoverRunnable;

    private long mLastCheckMs;

    public CarDisplayAreaHealthMonitor(Context context, Clock clock, Executor executor,
            Runnable recover) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mActivityManager = context.getSystemService(ActivityManager.class);
        mClock = clock;
        mExecutor = executor;
        mRecoverRunnable = recover;
    }

    /** Registers the task stack listener to retrigger health check if needed. */
    public void register() {
        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackListener);
        if (DEBUG) {
            Log.d(TAG, "Health monitor registered: " + ActivityManager.getCurrentUser());
        }
    }

    /** Unregisters the task stack listener to retrigger health check. */
    public void unregister() {
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskStackListener);
        if (DEBUG) {
            Log.d(TAG, "Health monitor unregistered: " + ActivityManager.getCurrentUser());
        }
    }

    private void healthCheck() {
        Intent intent = CarLauncherUtils.getMapsIntent(mContext);
        ComponentName componentName = intent.resolveActivity(mPackageManager);
        if (componentName == null) {
            Log.e(TAG, "No maps activity to run");
            return;
        }

        boolean mapsIsRunning = false;
        List<ActivityManager.RunningAppProcessInfo> procInfos =
                mActivityManager.getRunningAppProcesses();
        if (procInfos != null) {
            for (ActivityManager.RunningAppProcessInfo processInfo : procInfos) {
                if (processInfo.processName.equals(componentName.getPackageName())) {
                    mapsIsRunning = true;
                }
            }
        }

        if (!mapsIsRunning) {
            mExecutor.execute(mRecoverRunnable);
            if (DEBUG) {
                Log.d(TAG, "Retriggering maps");
            }
        }
    }

    private void healthCheckIfNeeded() {
        long currentTimestampMs = mClock.millis();
        long timeSinceLastCheck = currentTimestampMs - mLastCheckMs;
        if (timeSinceLastCheck > MINIMUM_CHECK_INTERVAL_MS) {
            healthCheck();
            mLastCheckMs = currentTimestampMs;
        } else {
            if (DEBUG) {
                Log.d(TAG, "Less than " + MINIMUM_CHECK_INTERVAL_MS + "ms have passed");
            }
        }
    }
}
