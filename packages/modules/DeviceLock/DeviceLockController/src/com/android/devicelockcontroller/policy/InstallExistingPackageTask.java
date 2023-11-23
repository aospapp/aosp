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

package com.android.devicelockcontroller.policy;

import static android.app.PendingIntent.FLAG_MUTABLE;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.content.pm.ApplicationInfo.FLAG_INSTALLED;
import static android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE;
import static android.content.pm.PackageManager.INSTALL_REASON_UNKNOWN;

import static com.android.devicelockcontroller.common.DeviceLockConstants.EXTRA_KIOSK_PACKAGE;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.WorkerParameters;

import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import java.util.Locale;

/**
 * Install an existing package for a secondary user.
 */
public final class InstallExistingPackageTask extends AbstractTask {
    private static final String TAG = "InstallExistingPackageTask";

    @VisibleForTesting
    static final String ACTION_INSTALL_EXISTING_APP_COMPLETE =
            "com.android.devicelockcontroller.policy.ACTION_INSTALL_EXISTING_APP_COMPLETE";

    private final Context mContext;
    private final ListeningExecutorService mExecutorService;
    private final InstallExistingPackageCompleteBroadcastReceiver mBroadcastReceiver;
    private final PackageInstallerWrapper mPackageInstaller;
    private final PackageInstallPendingIntentProvider mPackageInstallPendingIntentProvider;
    private final String mPackageName;

    public InstallExistingPackageTask(Context context, WorkerParameters workerParameters,
            ListeningExecutorService executorService) {
        this(context, workerParameters, executorService,
                new InstallExistingPackageCompleteBroadcastReceiver(context),
                new PackageInstallerWrapper(context.getPackageManager().getPackageInstaller()),
                new PackageInstallPendingIntentProviderImpl(context));
    }

    @VisibleForTesting
    InstallExistingPackageTask(Context context, WorkerParameters workerParameters,
            ListeningExecutorService executorService,
            InstallExistingPackageCompleteBroadcastReceiver broadcastReceiver,
            PackageInstallerWrapper packageInstaller,
            PackageInstallPendingIntentProvider packageInstallPendingIntentProvider) {
        super(context, workerParameters);

        mContext = context;
        mExecutorService = executorService;
        mBroadcastReceiver = broadcastReceiver;
        mPackageInstaller = packageInstaller;
        mPackageInstallPendingIntentProvider = packageInstallPendingIntentProvider;
        mPackageName = workerParameters.getInputData().getString(EXTRA_KIOSK_PACKAGE);
    }

    private static boolean isPackageInstalled(Context context, String packageName) {
        final PackageManager pm = context.getPackageManager();
        try {
            // Requires permission QUERY_ALL_PACKAGES
            final PackageInfo packageInfo =
                    pm.getPackageInfo(packageName, PackageInfoFlags.of(0));
            return (packageInfo != null)
                    && ((packageInfo.applicationInfo.flags & FLAG_INSTALLED) != 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @NonNull
    @Override
    public ListenableFuture<Result> startWork() {
        return mExecutorService.submit(
                () -> {
                    LogUtil.i(TAG, "Starts to run");

                    if (TextUtils.isEmpty(mPackageName)) {
                        LogUtil.e(TAG, "The package name is null or empty");
                        return failure(ERROR_CODE_NO_PACKAGE_NAME);
                    }

                    if (isPackageInstalled(mContext, mPackageName)) {
                        LogUtil.i(TAG, "Package already installed");

                        return Result.success();
                    }

                    mContext.registerReceiver(mBroadcastReceiver,
                            new IntentFilter(ACTION_INSTALL_EXISTING_APP_COMPLETE),
                            Context.RECEIVER_NOT_EXPORTED);

                    final PendingIntent pendingIntent =
                            mPackageInstallPendingIntentProvider.get();
                    if (pendingIntent != null) {
                        mBroadcastReceiver.startPeriodicTask(mPackageName);
                        mPackageInstaller.installExistingPackage(mPackageName,
                                INSTALL_REASON_UNKNOWN, pendingIntent.getIntentSender());
                    } else {
                        LogUtil.e(TAG, "Unable to get pending intent");

                        return failure(ERROR_CODE_GET_PENDING_INTENT_FAILED);
                    }
                    return FluentFuture
                            .from(mBroadcastReceiver.getFuture())
                            .transform(success -> {
                                if (success == null || !success) {
                                    LogUtil.e(TAG, String.format(Locale.US,
                                            "InstallExistingPackageCompleteBroadcastReceiver "
                                                    + "returned result: %b", success));
                                    return failure(ERROR_CODE_INSTALLATION_FAILED);
                                } else {
                                    LogUtil.i(TAG,
                                            "InstallExistingPackageCompleteBroadcastReceiver "
                                                    + "returned result: true");
                                    return Result.success();
                                }
                            }, MoreExecutors.directExecutor()).get();
                });
    }

    /** Provides a pending intent for a given sessionId from PackageInstaller. */
    interface PackageInstallPendingIntentProvider {
        /**
         * Returns a pending intent for a given sessionId from PackageInstaller.
         */
        @Nullable
        PendingIntent get();
    }

    /** Default implementation which returns pending intent for package install. */
    static final class PackageInstallPendingIntentProviderImpl
            implements PackageInstallPendingIntentProvider {
        private final Context mContext;

        PackageInstallPendingIntentProviderImpl(Context context) {
            mContext = context;
        }

        @Nullable
        @Override
        public PendingIntent get() {
            return PendingIntent.getBroadcast(mContext, /* requestCode */ 0,
                    new Intent(ACTION_INSTALL_EXISTING_APP_COMPLETE)
                            .setPackage(mContext.getPackageName()),
                    FLAG_MUTABLE | FLAG_ONE_SHOT | FLAG_UPDATE_CURRENT);
        }
    }

    /**
     * A broadcast receiver which handles the broadcast intent when package installation is
     * complete.
     * The broadcast receiver will use the {@link PackageInstaller#EXTRA_STATUS} field to determine
     * if the installation is successful.
     * Note that installExistingPackage only sends the broadcast on success, and therefore we have
     * an alternative way of detecting failures using polling and a timeout.
     */
    static final class InstallExistingPackageCompleteBroadcastReceiver extends BroadcastReceiver {
        @VisibleForTesting
        final SettableFuture<Boolean> mFuture = SettableFuture.create();

        private Context mContext;
        private int mCounter = 0;
        private final Handler mHandler;
        private boolean mIsTaskRunning = false;
        private String mPackageName;
        private static final int ITERATIONS = 30;
        private static final int INTERVAL_MS = 1000;

        InstallExistingPackageCompleteBroadcastReceiver(Context context) {
            super();

            mContext = context;
            mHandler = new Handler(Looper.getMainLooper());
        }

        private final Runnable mPeriodicTask = new Runnable() {
            @Override
            public void run() {
                // Already set in onReceive.
                if (mFuture.isDone()) {
                    return;
                }

                executeTask();
                mCounter++;

                if (mCounter <= ITERATIONS && mIsTaskRunning) {
                    mHandler.postDelayed(mPeriodicTask, INTERVAL_MS);
                } else {
                    LogUtil.e(TAG, "Timed out waiting for package installation");
                    stopPeriodicTask();
                    mFuture.set(false);
                    mContext.unregisterReceiver(
                            InstallExistingPackageCompleteBroadcastReceiver.this);
                }
            }
        };

        private void executeTask() {
            if (isPackageInstalled(mContext, mPackageName)) {
                mFuture.set(true);
                mContext.unregisterReceiver(this);
            }
        }

        private void startPeriodicTask() {
            if (!mIsTaskRunning) {
                mIsTaskRunning = true;
                mCounter = 0;
                mHandler.postDelayed(mPeriodicTask, INTERVAL_MS);
            }
        }

        public void startPeriodicTask(String packageName) {
            mHandler.post(() -> {
                mPackageName = packageName;
                startPeriodicTask();
            });
        }

        private void stopPeriodicTask() {
            if (mIsTaskRunning) {
                mIsTaskRunning = false;
                mHandler.removeCallbacks(mPeriodicTask);
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            stopPeriodicTask();

            // Already set by the periodic task.
            if (mFuture.isDone()) {
                return;
            }

            final int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);

            context.unregisterReceiver(this);
            if (status == PackageInstaller.STATUS_SUCCESS) {
                LogUtil.i(TAG, "Package installation succeed");
                mFuture.set(true);
            } else {
                LogUtil.e(TAG, String.format(Locale.US,
                        "Package installation failed: status= %d, status message= %s",
                        status, intent.getStringExtra(EXTRA_STATUS_MESSAGE)));
                mFuture.set(false);
            }
        }

        ListenableFuture<Boolean> getFuture() {
            return mFuture;
        }
    }

    /**
     * Wrapper for {@link PackageInstaller}, used for testing purpose, especially for failure
     * testing.
     */
    static class PackageInstallerWrapper {
        private final PackageInstaller mPackageInstaller;

        PackageInstallerWrapper(PackageInstaller packageInstaller) {
            mPackageInstaller = packageInstaller;
        }

        void installExistingPackage(String packageName, int installReason,
                IntentSender statusReceiver) {
            mPackageInstaller.installExistingPackage(packageName, installReason, statusReceiver);
        }
    }
}
