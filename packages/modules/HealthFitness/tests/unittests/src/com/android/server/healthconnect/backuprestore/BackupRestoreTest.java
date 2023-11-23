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

package com.android.server.healthconnect.backuprestore;

import static android.health.connect.HealthConnectDataState.RESTORE_ERROR_FETCHING_DATA;
import static android.health.connect.HealthConnectDataState.RESTORE_ERROR_UNKNOWN;
import static android.health.connect.HealthConnectDataState.RESTORE_ERROR_VERSION_DIFF;
import static android.health.connect.HealthConnectManager.DATA_DOWNLOAD_FAILED;
import static android.health.connect.HealthConnectManager.DATA_DOWNLOAD_RETRY;
import static android.health.connect.HealthConnectManager.DATA_DOWNLOAD_STARTED;

import static com.android.server.healthconnect.backuprestore.BackupRestore.BackupRestoreJobService.BACKUP_RESTORE_JOBS_NAMESPACE;
import static com.android.server.healthconnect.backuprestore.BackupRestore.BackupRestoreJobService.EXTRA_JOB_NAME_KEY;
import static com.android.server.healthconnect.backuprestore.BackupRestore.DATA_DOWNLOAD_STATE_KEY;
import static com.android.server.healthconnect.backuprestore.BackupRestore.DATA_DOWNLOAD_TIMEOUT_CANCELLED_KEY;
import static com.android.server.healthconnect.backuprestore.BackupRestore.DATA_DOWNLOAD_TIMEOUT_KEY;
import static com.android.server.healthconnect.backuprestore.BackupRestore.DATA_MERGING_RETRY_KEY;
import static com.android.server.healthconnect.backuprestore.BackupRestore.DATA_MERGING_TIMEOUT_CANCELLED_KEY;
import static com.android.server.healthconnect.backuprestore.BackupRestore.DATA_MERGING_TIMEOUT_KEY;
import static com.android.server.healthconnect.backuprestore.BackupRestore.DATA_RESTORE_ERROR_KEY;
import static com.android.server.healthconnect.backuprestore.BackupRestore.DATA_RESTORE_STATE_KEY;
import static com.android.server.healthconnect.backuprestore.BackupRestore.DATA_STAGING_TIMEOUT_CANCELLED_KEY;
import static com.android.server.healthconnect.backuprestore.BackupRestore.DATA_STAGING_TIMEOUT_KEY;
import static com.android.server.healthconnect.backuprestore.BackupRestore.INTERNAL_RESTORE_STATE_MERGING_DONE;
import static com.android.server.healthconnect.backuprestore.BackupRestore.INTERNAL_RESTORE_STATE_MERGING_IN_PROGRESS;
import static com.android.server.healthconnect.backuprestore.BackupRestore.INTERNAL_RESTORE_STATE_STAGING_DONE;
import static com.android.server.healthconnect.backuprestore.BackupRestore.INTERNAL_RESTORE_STATE_STAGING_IN_PROGRESS;
import static com.android.server.healthconnect.backuprestore.BackupRestore.INTERNAL_RESTORE_STATE_WAITING_FOR_STAGING;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.health.connect.HealthConnectManager;
import android.health.connect.restore.BackupFileNamesSet;
import android.health.connect.restore.StageRemoteDataRequest;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.ArrayMap;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.healthconnect.migration.MigrationStateManager;
import com.android.server.healthconnect.permission.FirstGrantTimeManager;
import com.android.server.healthconnect.permission.GrantTimeXmlHelper;
import com.android.server.healthconnect.storage.HealthConnectDatabase;
import com.android.server.healthconnect.storage.TransactionManager;
import com.android.server.healthconnect.storage.datatypehelpers.PreferenceHelper;
import com.android.server.healthconnect.utils.FilesUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/** Unit test for class {@link BackupRestore} */
@RunWith(AndroidJUnit4.class)
public class BackupRestoreTest {
    private static final String DATABASE_NAME = "healthconnect.db";
    private static final String GRANT_TIME_FILE_NAME = "health-permissions-first-grant-times.xml";
    @Mock Context mServiceContext;
    @Mock private TransactionManager mTransactionManager;
    @Mock private PreferenceHelper mPreferenceHelper;
    @Mock private FirstGrantTimeManager mFirstGrantTimeManager;
    @Mock private MigrationStateManager mMigrationStateManager;
    @Mock private Context mContext;
    @Mock private JobScheduler mJobScheduler;
    @Captor ArgumentCaptor<JobInfo> mJobInfoArgumentCaptor;
    private BackupRestore mBackupRestore;
    private MockitoSession mStaticMockSession;
    private UserHandle mUserHandle = UserHandle.of(UserHandle.myUserId());
    private File mMockDataDirectory;
    private File mMockBackedDataDirectory;

    @Before
    public void setUp() throws Exception {
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(Environment.class)
                        .mockStatic(PreferenceHelper.class)
                        .mockStatic(TransactionManager.class)
                        .mockStatic(BackupRestore.BackupRestoreJobService.class)
                        .mockStatic(GrantTimeXmlHelper.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mMockDataDirectory = mContext.getDir("mock_data", Context.MODE_PRIVATE);
        mMockBackedDataDirectory = mContext.getDir("mock_backed_data", Context.MODE_PRIVATE);
        when(Environment.getDataDirectory()).thenReturn(mMockBackedDataDirectory);
        when(PreferenceHelper.getInstance()).thenReturn(mPreferenceHelper);
        when(TransactionManager.getInitialisedInstance()).thenReturn(mTransactionManager);
        when(mJobScheduler.forNamespace(BACKUP_RESTORE_JOBS_NAMESPACE)).thenReturn(mJobScheduler);
        when(mServiceContext.getUser()).thenReturn(mUserHandle);
        when(mServiceContext.getSystemService(JobScheduler.class)).thenReturn(mJobScheduler);

        mBackupRestore =
                new BackupRestore(mFirstGrantTimeManager, mMigrationStateManager, mServiceContext);
    }

    @After
    public void tearDown() {
        FilesUtil.deleteDir(mMockDataDirectory);
        FilesUtil.deleteDir(mMockBackedDataDirectory);
        clearInvocations(mPreferenceHelper);
        clearInvocations(mTransactionManager);
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testGetAllBackupFileNames_forDeviceToDevice_returnsAllFileNames() throws Exception {
        File dbFile = createAndGetNonEmptyFile(mMockDataDirectory, DATABASE_NAME);
        File grantTimeFile = createAndGetNonEmptyFile(mMockDataDirectory, GRANT_TIME_FILE_NAME);
        when(mTransactionManager.getDatabasePath()).thenReturn(dbFile);
        when(mFirstGrantTimeManager.getFile(mUserHandle)).thenReturn(grantTimeFile);

        BackupFileNamesSet backupFileNamesSet = mBackupRestore.getAllBackupFileNames(true);

        assertThat(backupFileNamesSet).isNotNull();
        assertThat(backupFileNamesSet.getFileNames()).hasSize(2);
        assertThat(backupFileNamesSet.getFileNames()).contains(DATABASE_NAME);
        assertThat(backupFileNamesSet.getFileNames()).contains(GRANT_TIME_FILE_NAME);
    }

    @Test
    public void testGetAllBackupFileNames_forNonDeviceToDevice_returnsSmallFileNames()
            throws Exception {
        File dbFile = createAndGetNonEmptyFile(mMockDataDirectory, DATABASE_NAME);
        File grantTimeFile = createAndGetNonEmptyFile(mMockDataDirectory, GRANT_TIME_FILE_NAME);
        when(mTransactionManager.getDatabasePath()).thenReturn(dbFile);
        when(mFirstGrantTimeManager.getFile(mUserHandle)).thenReturn(grantTimeFile);

        BackupFileNamesSet backupFileNamesSet = mBackupRestore.getAllBackupFileNames(false);

        assertThat(backupFileNamesSet).isNotNull();
        assertThat(backupFileNamesSet.getFileNames()).hasSize(1);
        assertThat(backupFileNamesSet.getFileNames()).contains(GRANT_TIME_FILE_NAME);
    }

    @Test
    public void testGetAllBackupData_forDeviceToDevice_copiesAllData() throws Exception {
        File dbFileToBackup = createAndGetEmptyFile(mMockDataDirectory, DATABASE_NAME);
        File grantTimeFileToBackup =
                createAndGetEmptyFile(mMockDataDirectory, GRANT_TIME_FILE_NAME);
        File dbFileBacked = createAndGetEmptyFile(mMockBackedDataDirectory, DATABASE_NAME);
        File grantTimeFileBacked =
                createAndGetEmptyFile(mMockBackedDataDirectory, GRANT_TIME_FILE_NAME);

        when(mTransactionManager.getDatabasePath()).thenReturn(dbFileToBackup);
        when(mFirstGrantTimeManager.getFile(mUserHandle)).thenReturn(grantTimeFileToBackup);

        Map<String, ParcelFileDescriptor> pfdsByFileName = new ArrayMap<>();
        pfdsByFileName.put(
                dbFileBacked.getName(),
                ParcelFileDescriptor.open(dbFileBacked, ParcelFileDescriptor.MODE_READ_ONLY));
        pfdsByFileName.put(
                grantTimeFileBacked.getName(),
                ParcelFileDescriptor.open(
                        grantTimeFileBacked, ParcelFileDescriptor.MODE_READ_ONLY));

        mBackupRestore.getAllDataForBackup(new StageRemoteDataRequest(pfdsByFileName), mUserHandle);

        assertThat(dbFileBacked.length()).isEqualTo(dbFileToBackup.length());
        assertThat(grantTimeFileBacked.length()).isEqualTo(dbFileToBackup.length());
    }

    @Test
    public void testSetDataDownloadState_downloadStarted_schedulesDownloadTimeoutJob() {
        @HealthConnectManager.DataDownloadState int testDownloadStateSet = DATA_DOWNLOAD_STARTED;
        when(mPreferenceHelper.getPreference(eq(DATA_DOWNLOAD_STATE_KEY)))
                .thenReturn(String.valueOf(testDownloadStateSet));
        when(mPreferenceHelper.getPreference(eq(DATA_DOWNLOAD_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));

        mBackupRestore.updateDataDownloadState(testDownloadStateSet);
        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_DOWNLOAD_STATE_KEY), eq(String.valueOf(testDownloadStateSet)));
        ExtendedMockito.verify(
                () ->
                        BackupRestore.BackupRestoreJobService.schedule(
                                eq(mServiceContext),
                                mJobInfoArgumentCaptor.capture(),
                                eq(mBackupRestore)));
        JobInfo jobInfo = mJobInfoArgumentCaptor.getValue();
        assertThat(jobInfo.getExtras().getString(EXTRA_JOB_NAME_KEY))
                .isEqualTo(DATA_DOWNLOAD_TIMEOUT_KEY);
    }

    @Test
    public void testSetDataDownloadState_downloadRetry_schedulesDownloadTimeoutJob() {
        @HealthConnectManager.DataDownloadState int testDownloadStateSet = DATA_DOWNLOAD_RETRY;
        when(mPreferenceHelper.getPreference(eq(DATA_DOWNLOAD_STATE_KEY)))
                .thenReturn(String.valueOf(testDownloadStateSet));
        when(mPreferenceHelper.getPreference(eq(DATA_DOWNLOAD_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));

        mBackupRestore.updateDataDownloadState(testDownloadStateSet);
        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_DOWNLOAD_STATE_KEY), eq(String.valueOf(testDownloadStateSet)));
        ExtendedMockito.verify(
                () ->
                        BackupRestore.BackupRestoreJobService.schedule(
                                eq(mServiceContext),
                                mJobInfoArgumentCaptor.capture(),
                                eq(mBackupRestore)));
        JobInfo jobInfo = mJobInfoArgumentCaptor.getValue();
        assertThat(jobInfo.getExtras().getString(EXTRA_JOB_NAME_KEY))
                .isEqualTo(DATA_DOWNLOAD_TIMEOUT_KEY);
    }

    @Test
    public void testSetInternalRestoreState_waitingForStaging_schedulesStagingTimeoutJob() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_WAITING_FOR_STAGING));
        when(mPreferenceHelper.getPreference(eq(DATA_STAGING_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));

        // forcing the state because we want to this state to set even when it's already set.
        mBackupRestore.setInternalRestoreState(INTERNAL_RESTORE_STATE_WAITING_FOR_STAGING, true);
        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_RESTORE_STATE_KEY),
                        eq(String.valueOf(INTERNAL_RESTORE_STATE_WAITING_FOR_STAGING)));
        ExtendedMockito.verify(
                () ->
                        BackupRestore.BackupRestoreJobService.schedule(
                                eq(mServiceContext),
                                mJobInfoArgumentCaptor.capture(),
                                eq(mBackupRestore)));
        JobInfo jobInfo = mJobInfoArgumentCaptor.getValue();
        assertThat(jobInfo.getExtras().getString(EXTRA_JOB_NAME_KEY))
                .isEqualTo(DATA_STAGING_TIMEOUT_KEY);
    }

    @Test
    public void testSetInternalRestoreState_stagingInProgress_schedulesStagingTimeoutJob() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_IN_PROGRESS));
        when(mPreferenceHelper.getPreference(eq(DATA_STAGING_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));

        // forcing the state because we want to this state to set even when it's already set.
        mBackupRestore.setInternalRestoreState(INTERNAL_RESTORE_STATE_STAGING_IN_PROGRESS, true);
        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_RESTORE_STATE_KEY),
                        eq(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_IN_PROGRESS)));
        ExtendedMockito.verify(
                () ->
                        BackupRestore.BackupRestoreJobService.schedule(
                                eq(mServiceContext),
                                mJobInfoArgumentCaptor.capture(),
                                eq(mBackupRestore)));
        JobInfo jobInfo = mJobInfoArgumentCaptor.getValue();
        assertThat(jobInfo.getExtras().getString(EXTRA_JOB_NAME_KEY))
                .isEqualTo(DATA_STAGING_TIMEOUT_KEY);
    }

    @Test
    public void testSetInternalRestoreState_mergingInProgress_schedulesMergingTimeoutJob() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_MERGING_IN_PROGRESS));
        when(mPreferenceHelper.getPreference(eq(DATA_MERGING_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));

        // forcing the state because we want to this state to set even when it's already set.
        mBackupRestore.setInternalRestoreState(INTERNAL_RESTORE_STATE_MERGING_IN_PROGRESS, true);
        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_RESTORE_STATE_KEY),
                        eq(String.valueOf(INTERNAL_RESTORE_STATE_MERGING_IN_PROGRESS)));
        ExtendedMockito.verify(
                () ->
                        BackupRestore.BackupRestoreJobService.schedule(
                                eq(mServiceContext),
                                mJobInfoArgumentCaptor.capture(),
                                eq(mBackupRestore)));
        JobInfo jobInfo = mJobInfoArgumentCaptor.getValue();
        assertThat(jobInfo.getExtras().getString(EXTRA_JOB_NAME_KEY))
                .isEqualTo(DATA_MERGING_TIMEOUT_KEY);
    }

    @Test
    public void testScheduleAllPendingJobs_downloadStarted_schedulesDownloadTimeoutJob() {
        @HealthConnectManager.DataDownloadState int testDownloadStateSet = DATA_DOWNLOAD_STARTED;
        when(mPreferenceHelper.getPreference(eq(DATA_DOWNLOAD_STATE_KEY)))
                .thenReturn(String.valueOf(testDownloadStateSet));
        when(mPreferenceHelper.getPreference(eq(DATA_DOWNLOAD_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));

        mBackupRestore.scheduleAllJobs();
        ExtendedMockito.verify(
                () ->
                        BackupRestore.BackupRestoreJobService.schedule(
                                eq(mServiceContext),
                                mJobInfoArgumentCaptor.capture(),
                                eq(mBackupRestore)));
        JobInfo jobInfo = mJobInfoArgumentCaptor.getValue();
        assertThat(jobInfo.getExtras().getString(EXTRA_JOB_NAME_KEY))
                .isEqualTo(DATA_DOWNLOAD_TIMEOUT_KEY);
    }

    @Test
    public void testScheduleAllPendingJobs_downloadRetry_schedulesDownloadTimeoutJob() {
        @HealthConnectManager.DataDownloadState int testDownloadStateSet = DATA_DOWNLOAD_RETRY;
        when(mPreferenceHelper.getPreference(eq(DATA_DOWNLOAD_STATE_KEY)))
                .thenReturn(String.valueOf(testDownloadStateSet));
        when(mPreferenceHelper.getPreference(eq(DATA_DOWNLOAD_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));

        mBackupRestore.scheduleAllJobs();
        ExtendedMockito.verify(
                () ->
                        BackupRestore.BackupRestoreJobService.schedule(
                                eq(mServiceContext),
                                mJobInfoArgumentCaptor.capture(),
                                eq(mBackupRestore)));
        JobInfo jobInfo = mJobInfoArgumentCaptor.getValue();
        assertThat(jobInfo.getExtras().getString(EXTRA_JOB_NAME_KEY))
                .isEqualTo(DATA_DOWNLOAD_TIMEOUT_KEY);
    }

    @Test
    public void testScheduleAllPendingJobs_waitingForStaging_schedulesStagingTimeoutJob() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_WAITING_FOR_STAGING));
        when(mPreferenceHelper.getPreference(eq(DATA_STAGING_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));

        mBackupRestore.scheduleAllJobs();
        ExtendedMockito.verify(
                () ->
                        BackupRestore.BackupRestoreJobService.schedule(
                                eq(mServiceContext),
                                mJobInfoArgumentCaptor.capture(),
                                eq(mBackupRestore)));
        JobInfo jobInfo = mJobInfoArgumentCaptor.getValue();
        assertThat(jobInfo.getExtras().getString(EXTRA_JOB_NAME_KEY))
                .isEqualTo(DATA_STAGING_TIMEOUT_KEY);
    }

    @Test
    public void testScheduleAllPendingJobs_stagingInProgress_schedulesStagingTimeoutJob() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_IN_PROGRESS));
        when(mPreferenceHelper.getPreference(eq(DATA_STAGING_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));

        mBackupRestore.scheduleAllJobs();
        ExtendedMockito.verify(
                () ->
                        BackupRestore.BackupRestoreJobService.schedule(
                                eq(mServiceContext),
                                mJobInfoArgumentCaptor.capture(),
                                eq(mBackupRestore)));
        JobInfo jobInfo = mJobInfoArgumentCaptor.getValue();
        assertThat(jobInfo.getExtras().getString(EXTRA_JOB_NAME_KEY))
                .isEqualTo(DATA_STAGING_TIMEOUT_KEY);
    }

    @Test
    public void testScheduleAllPendingJobs_mergingInProgress_schedulesMergingTimeoutJob() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_MERGING_IN_PROGRESS));
        when(mPreferenceHelper.getPreference(eq(DATA_MERGING_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));

        mBackupRestore.scheduleAllJobs();
        ExtendedMockito.verify(
                () ->
                        BackupRestore.BackupRestoreJobService.schedule(
                                eq(mServiceContext),
                                mJobInfoArgumentCaptor.capture(),
                                eq(mBackupRestore)));
        JobInfo jobInfo = mJobInfoArgumentCaptor.getValue();
        assertThat(jobInfo.getExtras().getString(EXTRA_JOB_NAME_KEY))
                .isEqualTo(DATA_MERGING_TIMEOUT_KEY);
    }

    @Test
    public void testScheduleAllTimeoutJobs_stagingDone_triggersMergingJob() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_DONE));
        when(mPreferenceHelper.getPreference(eq(DATA_MERGING_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));
        when(mTransactionManager.getDatabaseVersion()).thenReturn(1);

        BackupRestore spyBackupRestore = spy(mBackupRestore);
        HealthConnectDatabase mockHealthDb = mock(HealthConnectDatabase.class);
        SQLiteDatabase mockDb = mock(SQLiteDatabase.class);
        when(mockDb.getVersion()).thenReturn(1);
        when(mockHealthDb.getReadableDatabase()).thenReturn(mockDb);
        doReturn(mockHealthDb).when(spyBackupRestore).getStagedDatabase();

        spyBackupRestore.scheduleAllJobs();
        verify(mPreferenceHelper, timeout(2000))
                .insertOrReplacePreference(
                        eq(DATA_RESTORE_STATE_KEY),
                        eq(String.valueOf(INTERNAL_RESTORE_STATE_MERGING_DONE)));
    }

    @Test
    public void testScheduleAllTimeoutJobs_stagingDoneAndMigration_schedulesRetryMergingJob() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_DONE));
        when(mPreferenceHelper.getPreference(eq(DATA_MERGING_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));
        when(mMigrationStateManager.isMigrationInProgress()).thenReturn(true);

        mBackupRestore.scheduleAllJobs();
        ExtendedMockito.verify(
                () ->
                        BackupRestore.BackupRestoreJobService.schedule(
                                eq(mServiceContext),
                                mJobInfoArgumentCaptor.capture(),
                                eq(mBackupRestore)),
                timeout(2000));
        JobInfo jobInfo = mJobInfoArgumentCaptor.getValue();
        assertThat(jobInfo.getExtras().getString(EXTRA_JOB_NAME_KEY))
                .isEqualTo(DATA_MERGING_RETRY_KEY);
    }

    @Test
    public void testCancelAllJobs_cancelsAllJobs() {
        mBackupRestore.cancelAllJobs();
        ExtendedMockito.verify(
                () ->
                        BackupRestore.BackupRestoreJobService.cancelAllJobs(eq(mServiceContext)));
    }

    @Test
    public void testOnStartJob_forDownloadStartedJob_executesDownloadJob() {
        when(mPreferenceHelper.getPreference(eq(DATA_DOWNLOAD_STATE_KEY)))
                .thenReturn(String.valueOf(DATA_DOWNLOAD_STARTED));
        when(mPreferenceHelper.getPreference(eq(DATA_DOWNLOAD_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));

        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_JOB_NAME_KEY, DATA_DOWNLOAD_TIMEOUT_KEY);
        mBackupRestore.handleJob(extras);

        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_DOWNLOAD_STATE_KEY), eq(String.valueOf(DATA_DOWNLOAD_FAILED)));
        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_RESTORE_ERROR_KEY),
                        eq(String.valueOf(RESTORE_ERROR_FETCHING_DATA)));
        verify(mPreferenceHelper).insertOrReplacePreference(eq(DATA_DOWNLOAD_TIMEOUT_KEY), eq(""));
        verify(mPreferenceHelper)
                .insertOrReplacePreference(eq(DATA_DOWNLOAD_TIMEOUT_CANCELLED_KEY), eq(""));
    }

    @Test
    public void testOnStartJob_forDownloadRetryJob_executesDownloadJob() {
        when(mPreferenceHelper.getPreference(eq(DATA_DOWNLOAD_STATE_KEY)))
                .thenReturn(String.valueOf(DATA_DOWNLOAD_RETRY));
        when(mPreferenceHelper.getPreference(eq(DATA_DOWNLOAD_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));

        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_JOB_NAME_KEY, DATA_DOWNLOAD_TIMEOUT_KEY);
        mBackupRestore.handleJob(extras);

        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_DOWNLOAD_STATE_KEY), eq(String.valueOf(DATA_DOWNLOAD_FAILED)));
        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_RESTORE_ERROR_KEY),
                        eq(String.valueOf(RESTORE_ERROR_FETCHING_DATA)));
        verify(mPreferenceHelper).insertOrReplacePreference(eq(DATA_DOWNLOAD_TIMEOUT_KEY), eq(""));
        verify(mPreferenceHelper)
                .insertOrReplacePreference(eq(DATA_DOWNLOAD_TIMEOUT_CANCELLED_KEY), eq(""));
    }

    @Test
    public void testOnStartJob_forWaitingStagingJob_executesStagingJob() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_WAITING_FOR_STAGING));
        when(mPreferenceHelper.getPreference(eq(DATA_STAGING_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));

        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_JOB_NAME_KEY, DATA_STAGING_TIMEOUT_KEY);
        mBackupRestore.handleJob(extras);

        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_RESTORE_STATE_KEY),
                        eq(String.valueOf(INTERNAL_RESTORE_STATE_MERGING_DONE)));
        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_RESTORE_ERROR_KEY), eq(String.valueOf(RESTORE_ERROR_UNKNOWN)));
        verify(mPreferenceHelper).insertOrReplacePreference(eq(DATA_STAGING_TIMEOUT_KEY), eq(""));
        verify(mPreferenceHelper)
                .insertOrReplacePreference(eq(DATA_STAGING_TIMEOUT_CANCELLED_KEY), eq(""));
    }

    @Test
    public void testOnStartJob_forStagingProgressJob_executesStagingJob() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_IN_PROGRESS));
        when(mPreferenceHelper.getPreference(eq(DATA_STAGING_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));

        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_JOB_NAME_KEY, DATA_STAGING_TIMEOUT_KEY);
        mBackupRestore.handleJob(extras);

        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_RESTORE_STATE_KEY),
                        eq(String.valueOf(INTERNAL_RESTORE_STATE_MERGING_DONE)));
        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_RESTORE_ERROR_KEY), eq(String.valueOf(RESTORE_ERROR_UNKNOWN)));
        verify(mPreferenceHelper).insertOrReplacePreference(eq(DATA_STAGING_TIMEOUT_KEY), eq(""));
        verify(mPreferenceHelper)
                .insertOrReplacePreference(eq(DATA_STAGING_TIMEOUT_CANCELLED_KEY), eq(""));
    }

    @Test
    public void testOnStartJob_forMergingProgressJob_executesMergingJob() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_MERGING_IN_PROGRESS));
        when(mPreferenceHelper.getPreference(eq(DATA_MERGING_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));

        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_JOB_NAME_KEY, DATA_MERGING_TIMEOUT_KEY);
        mBackupRestore.handleJob(extras);

        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_RESTORE_STATE_KEY),
                        eq(String.valueOf(INTERNAL_RESTORE_STATE_MERGING_DONE)));
        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_RESTORE_ERROR_KEY), eq(String.valueOf(RESTORE_ERROR_UNKNOWN)));
        verify(mPreferenceHelper).insertOrReplacePreference(eq(DATA_MERGING_TIMEOUT_KEY), eq(""));
        verify(mPreferenceHelper)
                .insertOrReplacePreference(eq(DATA_MERGING_TIMEOUT_CANCELLED_KEY), eq(""));
    }

    @Test
    public void testMerge_mergingOfGrantTimesIsInvoked() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_DONE));
        when(mTransactionManager.getDatabaseVersion()).thenReturn(1);

        BackupRestore spyBackupRestore = spy(mBackupRestore);
        HealthConnectDatabase mockHealthDb = mock(HealthConnectDatabase.class);
        SQLiteDatabase mockDb = mock(SQLiteDatabase.class);
        when(mockDb.getVersion()).thenReturn(1);
        when(mockHealthDb.getReadableDatabase()).thenReturn(mockDb);
        doReturn(mockHealthDb).when(spyBackupRestore).getStagedDatabase();

        spyBackupRestore.merge();
        verify(mFirstGrantTimeManager).applyAndStageBackupDataForUser(eq(mUserHandle), any());
    }

    @Test
    public void testMerge_mergingOfGrantTimes_parsesRestoredGrantTimes() {
        ArgumentCaptor<File> restoredGrantTimeFileCaptor = ArgumentCaptor.forClass(File.class);

        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_DONE));
        when(mTransactionManager.getDatabaseVersion()).thenReturn(1);

        BackupRestore spyBackupRestore = spy(mBackupRestore);
        HealthConnectDatabase mockHealthDb = mock(HealthConnectDatabase.class);
        SQLiteDatabase mockDb = mock(SQLiteDatabase.class);
        when(mockDb.getVersion()).thenReturn(1);
        when(mockHealthDb.getReadableDatabase()).thenReturn(mockDb);
        doReturn(mockHealthDb).when(spyBackupRestore).getStagedDatabase();

        spyBackupRestore.merge();
        ExtendedMockito.verify(
                () -> GrantTimeXmlHelper.parseGrantTime(restoredGrantTimeFileCaptor.capture()));
        assertThat(restoredGrantTimeFileCaptor.getValue()).isNotNull();
        assertThat(restoredGrantTimeFileCaptor.getValue().getName())
                .isEqualTo(GRANT_TIME_FILE_NAME);
    }

    @Test
    public void testMerge_whenErrorVersionDiff_mergingOfGrantTimesIsNotInvoked() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_DONE));
        when(mTransactionManager.getDatabaseVersion()).thenReturn(1);

        BackupRestore spyBackupRestore = spy(mBackupRestore);
        HealthConnectDatabase mockHealthDb = mock(HealthConnectDatabase.class);
        SQLiteDatabase mockDb = mock(SQLiteDatabase.class);
        when(mockDb.getVersion()).thenReturn(2);
        when(mockHealthDb.getReadableDatabase()).thenReturn(mockDb);
        doReturn(mockHealthDb).when(spyBackupRestore).getStagedDatabase();

        spyBackupRestore.merge();
        verify(mFirstGrantTimeManager, never())
                .applyAndStageBackupDataForUser(eq(mUserHandle), any());
    }

    @Test
    public void testMerge_whenModuleVersionBehind_setsVersionDiffError() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_DONE));
        when(mTransactionManager.getDatabaseVersion()).thenReturn(1);

        BackupRestore spyBackupRestore = spy(mBackupRestore);
        HealthConnectDatabase mockHealthDb = mock(HealthConnectDatabase.class);
        SQLiteDatabase mockDb = mock(SQLiteDatabase.class);
        when(mockDb.getVersion()).thenReturn(2);
        when(mockHealthDb.getReadableDatabase()).thenReturn(mockDb);
        doReturn(mockHealthDb).when(spyBackupRestore).getStagedDatabase();

        spyBackupRestore.merge();
        verify(mPreferenceHelper)
                .insertOrReplacePreference(
                        eq(DATA_RESTORE_ERROR_KEY), eq(String.valueOf(RESTORE_ERROR_VERSION_DIFF)));
    }

    @Test
    public void testMerge_whenMigrationInProgress_doesNotMergeGrantTimes() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_DONE));
        when(mTransactionManager.getDatabaseVersion()).thenReturn(1);

        when(mMigrationStateManager.isMigrationInProgress()).thenReturn(true);

        mBackupRestore.merge();
        verify(mFirstGrantTimeManager, never())
                .applyAndStageBackupDataForUser(eq(mUserHandle), any());
    }

    @Test
    public void testMerge_whenMigrationInProgress_schedulesRetryMergingJob() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_DONE));
        when(mPreferenceHelper.getPreference(eq(DATA_MERGING_TIMEOUT_KEY)))
                .thenReturn(String.valueOf(Instant.now().toEpochMilli()));
        when(mTransactionManager.getDatabaseVersion()).thenReturn(1);

        when(mMigrationStateManager.isMigrationInProgress()).thenReturn(true);

        mBackupRestore.merge();
        ExtendedMockito.verify(
                () ->
                        BackupRestore.BackupRestoreJobService.schedule(
                                eq(mServiceContext),
                                mJobInfoArgumentCaptor.capture(),
                                eq(mBackupRestore)),
                timeout(2000));
        JobInfo jobInfo = mJobInfoArgumentCaptor.getValue();
        assertThat(jobInfo.getExtras().getString(EXTRA_JOB_NAME_KEY))
                .isEqualTo(DATA_MERGING_RETRY_KEY);
    }

    @Test
    public void testShouldAttemptMerging_whenInStagingDone_returnsTrue() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_DONE));

        boolean result = mBackupRestore.shouldAttemptMerging();
        assertThat(result).isTrue();
    }

    @Test
    public void testShouldAttemptMerging_whenInMergingProgress_returnsTrue() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_MERGING_IN_PROGRESS));

        boolean result = mBackupRestore.shouldAttemptMerging();
        assertThat(result).isTrue();
    }

    @Test
    public void testShouldAttemptMerging_whenInMergingDone_returnsFalse() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_MERGING_DONE));

        boolean result = mBackupRestore.shouldAttemptMerging();
        assertThat(result).isFalse();
    }

    @Test
    public void testShouldAttemptMerging_whenInStagingProgress_returnsFalse() {
        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_IN_PROGRESS));

        boolean result = mBackupRestore.shouldAttemptMerging();
        assertThat(result).isFalse();
    }

    private static File createAndGetNonEmptyFile(File dir, String fileName) throws IOException {
        File file = new File(dir, fileName);
        FileWriter fileWriter = new FileWriter(file);
        fileWriter.write("Contents of file " + fileName);
        fileWriter.close();
        return file;
    }

    private static File createAndGetEmptyFile(File dir, String fileName) throws IOException {
        File file = new File(dir, fileName);
        file.createNewFile();
        return file;
    }
}
