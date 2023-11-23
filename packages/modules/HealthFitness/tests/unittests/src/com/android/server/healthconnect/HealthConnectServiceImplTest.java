/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.healthconnect;

import static android.health.connect.HealthConnectManager.DATA_DOWNLOAD_STARTED;

import static com.android.server.healthconnect.backuprestore.BackupRestore.DATA_DOWNLOAD_STATE_KEY;
import static com.android.server.healthconnect.backuprestore.BackupRestore.DATA_RESTORE_STATE_KEY;
import static com.android.server.healthconnect.backuprestore.BackupRestore.INTERNAL_RESTORE_STATE_STAGING_DONE;
import static com.android.server.healthconnect.backuprestore.BackupRestore.INTERNAL_RESTORE_STATE_STAGING_IN_PROGRESS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.health.connect.aidl.IDataStagingFinishedCallback;
import android.health.connect.restore.StageRemoteDataRequest;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.util.ArrayMap;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.LocalManagerRegistry;
import com.android.server.appop.AppOpsManagerLocal;
import com.android.server.healthconnect.migration.MigrationCleaner;
import com.android.server.healthconnect.migration.MigrationStateManager;
import com.android.server.healthconnect.migration.MigrationUiStateManager;
import com.android.server.healthconnect.permission.FirstGrantTimeManager;
import com.android.server.healthconnect.permission.HealthConnectPermissionHelper;
import com.android.server.healthconnect.storage.TransactionManager;
import com.android.server.healthconnect.storage.datatypehelpers.PreferenceHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;

/** Unit test class for {@link HealthConnectServiceImpl} */
@RunWith(AndroidJUnit4.class)
public class HealthConnectServiceImplTest {
    @Mock private TransactionManager mTransactionManager;
    @Mock private HealthConnectPermissionHelper mHealthConnectPermissionHelper;
    @Mock private MigrationCleaner mMigrationCleaner;
    @Mock private FirstGrantTimeManager mFirstGrantTimeManager;
    @Mock private MigrationStateManager mMigrationStateManager;
    @Mock private MigrationUiStateManager mMigrationUiStateManager;
    @Mock private Context mServiceContext;
    @Mock private PreferenceHelper mPreferenceHelper;
    @Mock private AppOpsManagerLocal mAppOpsManagerLocal;
    @Mock private PackageManager mPackageManager;

    private Context mContext;
    private HealthConnectServiceImpl mHealthConnectService;
    private MockitoSession mStaticMockSession;
    private UserHandle mUserHandle = UserHandle.of(UserHandle.myUserId());
    private File mMockDataDirectory;

    @Before
    public void setUp() throws Exception {
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(Environment.class)
                        .mockStatic(PreferenceHelper.class)
                        .mockStatic(LocalManagerRegistry.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mMockDataDirectory = mContext.getDir("mock_data", Context.MODE_PRIVATE);
        when(Environment.getDataDirectory()).thenReturn(mMockDataDirectory);
        when(PreferenceHelper.getInstance()).thenReturn(mPreferenceHelper);
        when(LocalManagerRegistry.getManager(AppOpsManagerLocal.class))
                .thenReturn(mAppOpsManagerLocal);
        when(mServiceContext.getPackageManager()).thenReturn(mPackageManager);
        when(mServiceContext.getUser()).thenReturn(mUserHandle);

        mHealthConnectService =
                new HealthConnectServiceImpl(
                        mTransactionManager,
                        mHealthConnectPermissionHelper,
                        mMigrationCleaner,
                        mFirstGrantTimeManager,
                        mMigrationStateManager,
                        mMigrationUiStateManager,
                        mServiceContext);
    }

    @After
    public void tearDown() {
        deleteDir(mMockDataDirectory);
        clearInvocations(mPreferenceHelper);
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testInstatiated_attachesMigrationCleanerToMigrationStateManager() {
        verify(mMigrationCleaner).attachTo(mMigrationStateManager);
    }

    @Test
    public void testStageRemoteData_withValidInput_allFilesStaged() throws Exception {
        File dataDir = mContext.getDataDir();
        File testRestoreFile1 = createAndGetNonEmptyFile(dataDir, "testRestoreFile1");
        File testRestoreFile2 = createAndGetNonEmptyFile(dataDir, "testRestoreFile2");

        assertThat(testRestoreFile1.exists()).isTrue();
        assertThat(testRestoreFile2.exists()).isTrue();

        Map<String, ParcelFileDescriptor> pfdsByFileName = new ArrayMap<>();
        pfdsByFileName.put(
                testRestoreFile1.getName(),
                ParcelFileDescriptor.open(testRestoreFile1, ParcelFileDescriptor.MODE_READ_ONLY));
        pfdsByFileName.put(
                testRestoreFile2.getName(),
                ParcelFileDescriptor.open(testRestoreFile2, ParcelFileDescriptor.MODE_READ_ONLY));

        final IDataStagingFinishedCallback callback = mock(IDataStagingFinishedCallback.class);
        mHealthConnectService.stageAllHealthConnectRemoteData(
                new StageRemoteDataRequest(pfdsByFileName), mUserHandle, callback);

        verify(callback, timeout(5000).times(1)).onResult();
        var stagedFileNames =
                mHealthConnectService.getStagedRemoteFileNames(mUserHandle.getIdentifier());
        assertThat(stagedFileNames.size()).isEqualTo(2);
        assertThat(stagedFileNames.contains(testRestoreFile1.getName())).isTrue();
        assertThat(stagedFileNames.contains(testRestoreFile2.getName())).isTrue();
    }

    @Test
    public void testStageRemoteData_withNotReadMode_onlyValidFilesStaged() throws Exception {
        File dataDir = mContext.getDataDir();
        File testRestoreFile1 = createAndGetNonEmptyFile(dataDir, "testRestoreFile1");
        File testRestoreFile2 = createAndGetNonEmptyFile(dataDir, "testRestoreFile2");

        assertThat(testRestoreFile1.exists()).isTrue();
        assertThat(testRestoreFile2.exists()).isTrue();

        Map<String, ParcelFileDescriptor> pfdsByFileName = new ArrayMap<>();
        pfdsByFileName.put(
                testRestoreFile1.getName(),
                ParcelFileDescriptor.open(testRestoreFile1, ParcelFileDescriptor.MODE_WRITE_ONLY));
        pfdsByFileName.put(
                testRestoreFile2.getName(),
                ParcelFileDescriptor.open(testRestoreFile2, ParcelFileDescriptor.MODE_READ_ONLY));

        final IDataStagingFinishedCallback callback = mock(IDataStagingFinishedCallback.class);
        mHealthConnectService.stageAllHealthConnectRemoteData(
                new StageRemoteDataRequest(pfdsByFileName), mUserHandle, callback);

        verify(callback, timeout(5000).times(1)).onError(any());
        var stagedFileNames =
                mHealthConnectService.getStagedRemoteFileNames(mUserHandle.getIdentifier());
        assertThat(stagedFileNames.size()).isEqualTo(1);
        assertThat(stagedFileNames.contains(testRestoreFile2.getName())).isTrue();
    }

    // Imitates the state when we are not actively staging but the disk reflects that.
    // Which means we were interrupted, and therefore we should stage.
    @Test
    public void testStageRemoteData_whenStagingProgress_doesStage() throws Exception {
        File dataDir = mContext.getDataDir();
        File testRestoreFile1 = createAndGetNonEmptyFile(dataDir, "testRestoreFile1");
        File testRestoreFile2 = createAndGetNonEmptyFile(dataDir, "testRestoreFile2");

        assertThat(testRestoreFile1.exists()).isTrue();
        assertThat(testRestoreFile2.exists()).isTrue();

        Map<String, ParcelFileDescriptor> pfdsByFileName = new ArrayMap<>();
        pfdsByFileName.put(
                testRestoreFile1.getName(),
                ParcelFileDescriptor.open(testRestoreFile1, ParcelFileDescriptor.MODE_READ_ONLY));
        pfdsByFileName.put(
                testRestoreFile2.getName(),
                ParcelFileDescriptor.open(testRestoreFile2, ParcelFileDescriptor.MODE_READ_ONLY));

        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_IN_PROGRESS));

        final IDataStagingFinishedCallback callback = mock(IDataStagingFinishedCallback.class);
        mHealthConnectService.stageAllHealthConnectRemoteData(
                new StageRemoteDataRequest(pfdsByFileName), mUserHandle, callback);

        verify(callback, timeout(5000)).onResult();
        var stagedFileNames =
                mHealthConnectService.getStagedRemoteFileNames(mUserHandle.getIdentifier());
        assertThat(stagedFileNames.size()).isEqualTo(2);
        assertThat(stagedFileNames.contains(testRestoreFile1.getName())).isTrue();
        assertThat(stagedFileNames.contains(testRestoreFile2.getName())).isTrue();
    }

    @Test
    public void testStageRemoteData_whenStagingDone_doesNotStage() throws Exception {
        File dataDir = mContext.getDataDir();
        File testRestoreFile1 = createAndGetNonEmptyFile(dataDir, "testRestoreFile1");
        File testRestoreFile2 = createAndGetNonEmptyFile(dataDir, "testRestoreFile2");

        assertThat(testRestoreFile1.exists()).isTrue();
        assertThat(testRestoreFile2.exists()).isTrue();

        Map<String, ParcelFileDescriptor> pfdsByFileName = new ArrayMap<>();
        pfdsByFileName.put(
                testRestoreFile1.getName(),
                ParcelFileDescriptor.open(testRestoreFile1, ParcelFileDescriptor.MODE_READ_ONLY));
        pfdsByFileName.put(
                testRestoreFile2.getName(),
                ParcelFileDescriptor.open(testRestoreFile2, ParcelFileDescriptor.MODE_READ_ONLY));

        when(mPreferenceHelper.getPreference(eq(DATA_RESTORE_STATE_KEY)))
                .thenReturn(String.valueOf(INTERNAL_RESTORE_STATE_STAGING_DONE));

        final IDataStagingFinishedCallback callback = mock(IDataStagingFinishedCallback.class);
        mHealthConnectService.stageAllHealthConnectRemoteData(
                new StageRemoteDataRequest(pfdsByFileName), mUserHandle, callback);

        verify(callback, timeout(5000)).onResult();
        var stagedFileNames =
                mHealthConnectService.getStagedRemoteFileNames(mUserHandle.getIdentifier());
        assertThat(stagedFileNames.size()).isEqualTo(0);
    }

    @Test
    public void testUpdateDataDownloadState_settingValidState_setsState() {
        mHealthConnectService.updateDataDownloadState(DATA_DOWNLOAD_STARTED);
        verify(mPreferenceHelper, times(1))
                .insertOrReplacePreference(
                        eq(DATA_DOWNLOAD_STATE_KEY), eq(String.valueOf(DATA_DOWNLOAD_STARTED)));
    }

    private static File createAndGetNonEmptyFile(File dir, String fileName) throws IOException {
        File file = new File(dir, fileName);
        FileWriter fileWriter = new FileWriter(file);
        fileWriter.write("Contents of file " + fileName);
        fileWriter.close();
        return file;
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (var file : files) {
                if (file.isDirectory()) {
                    deleteDir(file);
                } else {
                    file.delete();
                }
            }
        }
        assertThat(dir.delete()).isTrue();
    }
}
