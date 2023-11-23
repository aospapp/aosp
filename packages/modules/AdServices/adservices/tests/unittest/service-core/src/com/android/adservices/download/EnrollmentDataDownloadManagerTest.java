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

package com.android.adservices.download;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.enrollment.EnrollmentData;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import com.google.android.libraries.mobiledatadownload.MobileDataDownload;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.common.util.concurrent.Futures;
import com.google.mobiledatadownload.ClientConfigProto.ClientFile;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

public class EnrollmentDataDownloadManagerTest {
    private static final Context sContext = ApplicationProvider.getApplicationContext();
    private static final String TEST_ENROLLMENT_DATA_FILE_PATH =
            "enrollment/adtech_enrollment_data.csv";
    private MockitoSession mSession = null;
    private EnrollmentDataDownloadManager mEnrollmentDataDownloadManager;

    @Mock private SynchronousFileStorage mMockFileStorage;

    @Mock private EnrollmentDao mMockEnrollmentDao;

    @Mock private ClientFileGroup mMockFileGroup;

    @Mock private ClientFile mMockFile;

    @Mock private MobileDataDownload mMockMdd;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(FlagsFactory.class)
                        .mockStatic(MobileDataDownloadFactory.class)
                        .mockStatic(EnrollmentDao.class)
                        .initMocks(this)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
    }

    @After
    public void cleanup() {
        if (mSession != null) {
            mSession.finishMocking();
        }
    }

    @Test
    public void testGetInstance() {
        ExtendedMockito.doReturn(FlagsFactory.getFlagsForTest()).when(FlagsFactory::getFlags);
        EnrollmentDataDownloadManager firstInstance =
                EnrollmentDataDownloadManager.getInstance(sContext);
        EnrollmentDataDownloadManager secondInstance =
                EnrollmentDataDownloadManager.getInstance(sContext);

        assertThat(firstInstance).isNotNull();
        assertThat(secondInstance).isNotNull();
        assertThat(firstInstance).isEqualTo(secondInstance);
    }

    @Test
    public void testReadFileAndInsertIntoDatabaseSuccess()
            throws IOException, ExecutionException, InterruptedException {
        ExtendedMockito.doReturn(mMockFileStorage)
                .when(() -> (MobileDataDownloadFactory.getFileStorage(any())));
        ExtendedMockito.doReturn(mMockMdd)
                .when(() -> (MobileDataDownloadFactory.getMdd(any(), any())));
        ExtendedMockito.doReturn(mMockEnrollmentDao).when(() -> (EnrollmentDao.getInstance(any())));
        when(mMockFileStorage.open(any(), any()))
                .thenReturn(sContext.getAssets().open(TEST_ENROLLMENT_DATA_FILE_PATH));
        mEnrollmentDataDownloadManager =
                new EnrollmentDataDownloadManager(sContext, FlagsFactory.getFlagsForTest());

        when(mMockMdd.getFileGroup(any())).thenReturn(Futures.immediateFuture(mMockFileGroup));
        when(mMockFileGroup.getFileList()).thenReturn(Collections.singletonList(mMockFile));
        when(mMockFile.getFileId()).thenReturn("adtech_enrollment_data.csv");
        when(mMockFile.getFileUri()).thenReturn("adtech_enrollment_data.csv");

        ArgumentCaptor<EnrollmentData> captor = ArgumentCaptor.forClass(EnrollmentData.class);

        doReturn(true).when(mMockEnrollmentDao).insert(captor.capture());

        assertThat(mEnrollmentDataDownloadManager.readAndInsertEnrolmentDataFromMdd().get())
                .isEqualTo(EnrollmentDataDownloadManager.DownloadStatus.SUCCESS);

        verify(mMockEnrollmentDao, times(5)).insert(any());

        // Verify no duplicate inserts after enrollment data is saved before.
        assertThat(mEnrollmentDataDownloadManager.readAndInsertEnrolmentDataFromMdd().get())
                .isEqualTo(EnrollmentDataDownloadManager.DownloadStatus.SKIP);
        verifyZeroInteractions(mMockEnrollmentDao);
    }

    @Test
    public void testReadFileAndInsertIntoDatabaseFileGroupNull()
            throws ExecutionException, InterruptedException {
        ExtendedMockito.doReturn(mMockFileStorage)
                .when(() -> (MobileDataDownloadFactory.getFileStorage(any())));
        ExtendedMockito.doReturn(mMockMdd)
                .when(() -> (MobileDataDownloadFactory.getMdd(any(), any())));
        ExtendedMockito.doReturn(mMockEnrollmentDao).when(() -> (EnrollmentDao.getInstance(any())));

        mEnrollmentDataDownloadManager =
                new EnrollmentDataDownloadManager(sContext, FlagsFactory.getFlagsForTest());

        when(mMockMdd.getFileGroup(any())).thenReturn(Futures.immediateFuture(null));

        assertThat(mEnrollmentDataDownloadManager.readAndInsertEnrolmentDataFromMdd().get())
                .isEqualTo(EnrollmentDataDownloadManager.DownloadStatus.NO_FILE_AVAILABLE);

        verify(mMockEnrollmentDao, times(0)).insert(any());
    }

    @Test
    public void testReadFileAndInsertIntoDatabaseEnrollmentDataFileIdMissing()
            throws ExecutionException, InterruptedException, IOException {
        ExtendedMockito.doReturn(mMockFileStorage)
                .when(() -> (MobileDataDownloadFactory.getFileStorage(any())));
        ExtendedMockito.doReturn(mMockMdd)
                .when(() -> (MobileDataDownloadFactory.getMdd(any(), any())));
        ExtendedMockito.doReturn(mMockEnrollmentDao).when(() -> (EnrollmentDao.getInstance(any())));
        when(mMockFileStorage.open(any(), any()))
                .thenReturn(sContext.getAssets().open(TEST_ENROLLMENT_DATA_FILE_PATH));
        mEnrollmentDataDownloadManager =
                new EnrollmentDataDownloadManager(sContext, FlagsFactory.getFlagsForTest());

        when(mMockMdd.getFileGroup(any())).thenReturn(Futures.immediateFuture(mMockFileGroup));
        when(mMockFileGroup.getFileList()).thenReturn(Collections.singletonList(mMockFile));
        when(mMockFile.getFileId()).thenReturn("wrong_file_id.csv");

        assertThat(mEnrollmentDataDownloadManager.readAndInsertEnrolmentDataFromMdd().get())
                .isEqualTo(EnrollmentDataDownloadManager.DownloadStatus.NO_FILE_AVAILABLE);

        verify(mMockEnrollmentDao, times(0)).insert(any());
    }

    @Test
    public void testReadFileAndInsertIntoDatabaseExecutionException()
            throws ExecutionException, InterruptedException, IOException {
        ExtendedMockito.doReturn(mMockFileStorage)
                .when(() -> (MobileDataDownloadFactory.getFileStorage(any())));
        ExtendedMockito.doReturn(mMockMdd)
                .when(() -> (MobileDataDownloadFactory.getMdd(any(), any())));
        ExtendedMockito.doReturn(mMockEnrollmentDao).when(() -> (EnrollmentDao.getInstance(any())));
        when(mMockFileStorage.open(any(), any()))
                .thenReturn(sContext.getAssets().open(TEST_ENROLLMENT_DATA_FILE_PATH));
        mEnrollmentDataDownloadManager =
                new EnrollmentDataDownloadManager(sContext, FlagsFactory.getFlagsForTest());

        when(mMockMdd.getFileGroup(any()))
                .thenReturn(Futures.immediateFailedFuture(new CancellationException()));
        when(mMockFileGroup.getFileList()).thenReturn(Collections.singletonList(mMockFile));
        when(mMockFile.getFileId()).thenReturn("adtech_enrollment_data.csv");

        assertThat(mEnrollmentDataDownloadManager.readAndInsertEnrolmentDataFromMdd().get())
                .isEqualTo(EnrollmentDataDownloadManager.DownloadStatus.NO_FILE_AVAILABLE);

        verify(mMockEnrollmentDao, times(0)).insert(any());
    }

    @Test
    public void testReadFileAndInsertIntoDatabaseParsingFailed()
            throws IOException, ExecutionException, InterruptedException {
        ExtendedMockito.doReturn(mMockFileStorage)
                .when(() -> (MobileDataDownloadFactory.getFileStorage(any())));
        ExtendedMockito.doReturn(mMockMdd)
                .when(() -> (MobileDataDownloadFactory.getMdd(any(), any())));
        ExtendedMockito.doReturn(mMockEnrollmentDao).when(() -> (EnrollmentDao.getInstance(any())));
        when(mMockFileStorage.open(any(), any())).thenThrow(new IOException());
        mEnrollmentDataDownloadManager =
                new EnrollmentDataDownloadManager(sContext, FlagsFactory.getFlagsForTest());

        when(mMockMdd.getFileGroup(any())).thenReturn(Futures.immediateFuture(mMockFileGroup));
        when(mMockFileGroup.getFileList()).thenReturn(Collections.singletonList(mMockFile));
        when(mMockFile.getFileId()).thenReturn("adtech_enrollment_data.csv");
        when(mMockFile.getFileUri()).thenReturn("adtech_enrollment_data.csv");

        ArgumentCaptor<EnrollmentData> captor = ArgumentCaptor.forClass(EnrollmentData.class);

        doReturn(true).when(mMockEnrollmentDao).insert(captor.capture());

        assertThat(mEnrollmentDataDownloadManager.readAndInsertEnrolmentDataFromMdd().get())
                .isEqualTo(EnrollmentDataDownloadManager.DownloadStatus.PARSING_FAILED);

        verify(mMockEnrollmentDao, times(0)).insert(any());
    }
}
