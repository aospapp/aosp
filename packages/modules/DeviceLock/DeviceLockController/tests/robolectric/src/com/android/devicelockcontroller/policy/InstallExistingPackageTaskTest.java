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

import static android.content.pm.PackageInstaller.EXTRA_STATUS;
import static android.content.pm.PackageInstaller.STATUS_FAILURE;
import static android.content.pm.PackageInstaller.STATUS_SUCCESS;
import static android.util.Log.VERBOSE;

import static androidx.work.WorkInfo.State.FAILED;
import static androidx.work.WorkInfo.State.SUCCEEDED;

import static com.android.devicelockcontroller.common.DeviceLockConstants.EXTRA_KIOSK_PACKAGE;
import static com.android.devicelockcontroller.policy.AbstractTask.ERROR_CODE_GET_PENDING_INTENT_FAILED;
import static com.android.devicelockcontroller.policy.AbstractTask.ERROR_CODE_INSTALLATION_FAILED;
import static com.android.devicelockcontroller.policy.AbstractTask.ERROR_CODE_NO_PACKAGE_NAME;
import static com.android.devicelockcontroller.policy.AbstractTask.TASK_RESULT_ERROR_CODE_KEY;
import static com.android.devicelockcontroller.policy.InstallExistingPackageTask.ACTION_INSTALL_EXISTING_APP_COMPLETE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.Configuration;
import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.android.devicelockcontroller.policy.InstallExistingPackageTask.InstallExistingPackageCompleteBroadcastReceiver;
import com.android.devicelockcontroller.policy.InstallExistingPackageTask.PackageInstallPendingIntentProvider;
import com.android.devicelockcontroller.policy.InstallExistingPackageTask.PackageInstallPendingIntentProviderImpl;
import com.android.devicelockcontroller.policy.InstallExistingPackageTask.PackageInstallerWrapper;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowContextWrapper;

import java.util.concurrent.ExecutionException;

@RunWith(RobolectricTestRunner.class)
public final class InstallExistingPackageTaskTest {
    @Rule
    public final MockitoRule mMocks = MockitoJUnit.rule();
    @Mock
    private PackageInstallerWrapper mMockPackageInstaller;
    @Mock
    private PackageInstallPendingIntentProvider mMockPackageInstallPendingIntentProvider;

    private Context mContext;
    private WorkManager mWorkManager;
    private final InstallExistingPackageCompleteBroadcastReceiver mFakeBroadcastReceiver =
            new InstallExistingPackageCompleteBroadcastReceiver(mContext);
    private ShadowApplication mShadowApplication;

    @Before
    public void setup() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mShadowApplication = Shadows.shadowOf((Application) mContext);

        when(mMockPackageInstallPendingIntentProvider.get()).thenReturn(
                new PackageInstallPendingIntentProviderImpl(mContext).get());

        final Configuration config =
                new Configuration.Builder()
                        .setMinimumLoggingLevel(VERBOSE)
                        .setWorkerFactory(
                                new WorkerFactory() {
                                    @Override
                                    public ListenableWorker createWorker(
                                            Context context, String workerClassName,
                                            WorkerParameters workerParameters) {
                                        return new InstallExistingPackageTask(
                                                context,
                                                workerParameters,
                                                MoreExecutors.newDirectExecutorService(),
                                                mFakeBroadcastReceiver,
                                                mMockPackageInstaller,
                                                mMockPackageInstallPendingIntentProvider);
                                    }
                                })
                        .build();
        WorkManagerTestInitHelper.initializeTestWorkManager(mContext, config);
        mWorkManager = WorkManager.getInstance(mContext);
    }

    @Test
    public void testInstallExistingPackageCompleteBroadcastReceiver_StatusSuccess()
            throws ExecutionException, InterruptedException {
        // WHEN register the receiver and send an installation success broadcast
        registerReceiverWithStatus(STATUS_SUCCESS);

        // THEN the receiver should be unregistered
        assertThat(mShadowApplication.getRegisteredReceivers()).isEmpty();
        assertThat(mFakeBroadcastReceiver.getFuture().isDone()).isTrue();
        assertThat(mFakeBroadcastReceiver.getFuture().get()).isTrue();
    }


    /*
     * The broadcast should never return anything other than STATUS_SUCCESS in response
     * to installExistingPackage().
     * Here we test what would happen if we receive something unexpected.
     */
    @Test
    public void testInstallExistingPackageCompleteBroadcastReceiver_StatusFailure()
            throws ExecutionException, InterruptedException {
        // WHEN register the receiver and send an installation failure broadcast
        registerReceiverWithStatus(STATUS_FAILURE);

        // THEN the receiver should be unregistered
        assertThat(mShadowApplication.getRegisteredReceivers()).isEmpty();
        assertThat(mFakeBroadcastReceiver.getFuture().isDone()).isTrue();
        assertThat(mFakeBroadcastReceiver.getFuture().get()).isFalse();
    }

    @Test
    public void testInstallExistingPackage_PackageNameIsNull() {
        // GIVEN the package name is null
        final WorkInfo workInfo = buildTaskAndRun(mWorkManager, /* packageName */ null);

        // THEN task failed
        assertThat(workInfo.getState()).isEqualTo(FAILED);
        assertThat(workInfo.getOutputData().getInt(TASK_RESULT_ERROR_CODE_KEY,
                /* defaultValue */ -1)).isEqualTo(ERROR_CODE_NO_PACKAGE_NAME);
    }

    @Test
    public void testInstallExistingPackage_PackageNameIsEmpty() {
        // GIVEN the file location is empty
        final WorkInfo workInfo = buildTaskAndRun(mWorkManager, /* packageName */ "");

        // THEN task failed
        assertThat(workInfo.getState()).isEqualTo(FAILED);
        assertThat(workInfo.getOutputData().getInt(TASK_RESULT_ERROR_CODE_KEY,
                /* defaultValue */ -1)).isEqualTo(ERROR_CODE_NO_PACKAGE_NAME);
    }


    @Test
    public void testInstall_Succeed() {
        // GIVEN
        mShadowApplication.clearRegisteredReceivers();
        // GIVEN the installation completes with success
        mFakeBroadcastReceiver.mFuture.set(true);

        // WHEN
        final WorkInfo workInfo = buildTaskAndRun(mWorkManager);

        // THEN
        verifyPackageInstalled();
        assertThat(workInfo.getState()).isEqualTo(SUCCEEDED);
    }

    @Test
    public void testInstall_BroadcastReceiverReturnedFalse_Failed() {
        // GIVEN
        mShadowApplication.clearRegisteredReceivers();
        // GIVEN the installation completes with failure
        mFakeBroadcastReceiver.mFuture.set(false);

        // WHEN
        final WorkInfo workInfo = buildTaskAndRun(mWorkManager);

        // THEN
        verifyPackageInstalled();
        assertThat(workInfo.getState()).isEqualTo(FAILED);
        assertThat(workInfo.getOutputData().getInt(TASK_RESULT_ERROR_CODE_KEY,
                /* defaultValue */ -1)).isEqualTo(ERROR_CODE_INSTALLATION_FAILED);
    }

    @Test
    public void testInstall_withNullPendingIntent_fails() {
        when(mMockPackageInstallPendingIntentProvider.get()).thenReturn(null);

        // WHEN run install package task
        final WorkInfo workInfo = buildTaskAndRun(mWorkManager);

        // THEN
        assertThat(workInfo.getState()).isEqualTo(FAILED);
        assertThat(workInfo.getOutputData().getInt(TASK_RESULT_ERROR_CODE_KEY,
                /* defaultValue */ -1)).isEqualTo(ERROR_CODE_GET_PENDING_INTENT_FAILED);
    }

    private void verifyPackageInstalled() {
        // THEN a BroadcastReceiver should be registered
        assertThat(mShadowApplication.getRegisteredReceivers()).hasSize(1);
        assertThat(mShadowApplication.getRegisteredReceivers().get(0).getBroadcastReceiver())
                .isEqualTo(mFakeBroadcastReceiver);
        verify(mMockPackageInstaller).installExistingPackage(anyString(), anyInt(),
                any(IntentSender.class));
    }

    private WorkInfo buildTaskAndRun(WorkManager workManager, String packageName) {
        // GIVEN
        final Data data = new Data.Builder().putString(EXTRA_KIOSK_PACKAGE, packageName).build();


        // WHEN
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(InstallExistingPackageTask.class).setInputData(data)
                        .build();
        workManager.enqueue(request);

        try {
            return workManager.getWorkInfoById(request.getId()).get();
        } catch (ExecutionException | InterruptedException e) {
            throw new AssertionError("Exception", e);
        }
    }

    private WorkInfo buildTaskAndRun(WorkManager workManager) {
        final String kioskPackageName = "com.example.kiosk";
        return buildTaskAndRun(workManager, kioskPackageName);
    }

    private void registerReceiverWithStatus(int status) {
        // GIVEN clear BroadcastReceivers and Broadcast intents
        mShadowApplication.clearRegisteredReceivers();
        final ShadowContextWrapper shadowContextWrapper =
                Shadows.shadowOf((ContextWrapper) mContext);
        shadowContextWrapper.clearBroadcastIntents();

        // WHEN
        mContext.registerReceiver(mFakeBroadcastReceiver,
                new IntentFilter(ACTION_INSTALL_EXISTING_APP_COMPLETE));

        // THEN
        assertThat(mShadowApplication.getRegisteredReceivers()).hasSize(1);
        assertThat(mShadowApplication.getRegisteredReceivers().get(0).getBroadcastReceiver())
                .isEqualTo(mFakeBroadcastReceiver);

        // WHEN send intent with status
        final Intent intent = new Intent(ACTION_INSTALL_EXISTING_APP_COMPLETE);
        intent.putExtra(EXTRA_STATUS, status);
        mContext.sendBroadcast(intent);

        Shadows.shadowOf(Looper.getMainLooper()).idle();
        // THEN make sure broadcast intent has correct contents
        assertThat(shadowContextWrapper.getBroadcastIntents()).hasSize(1);
        final Intent receivedIntent = shadowContextWrapper.getBroadcastIntents().get(0);
        assertThat(receivedIntent.getAction()).isEqualTo(ACTION_INSTALL_EXISTING_APP_COMPLETE);
        assertThat(receivedIntent.getExtras().getInt(EXTRA_STATUS)).isEqualTo(status);
    }
}
