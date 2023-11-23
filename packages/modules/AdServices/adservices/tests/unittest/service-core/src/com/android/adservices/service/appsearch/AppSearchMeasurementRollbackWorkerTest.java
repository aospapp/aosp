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

package com.android.adservices.service.appsearch;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doThrow;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.adservices.AdServicesManager;
import android.content.Context;

import androidx.appsearch.app.AppSearchBatchResult;
import androidx.appsearch.app.AppSearchSession;
import androidx.appsearch.platformstorage.PlatformStorage;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.adservices.concurrency.AdServicesExecutors;
import com.android.adservices.service.consent.ConsentConstants;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@SmallTest
public class AppSearchMeasurementRollbackWorkerTest {
    private static final String USERID = "user1";
    private static final long APEX_VERSION = 100L;
    private static final int FUTURE_TIMEOUT_MILLISECONDS = 3000;

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final Executor mExecutor = AdServicesExecutors.getBackgroundExecutor();
    private AppSearchMeasurementRollbackWorker mWorker;
    private MockitoSession mMockitoSession;

    @Mock private ListenableFuture<AppSearchSession> mAppSearchSession;

    @Before
    public void setup() {
        mMockitoSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(PlatformStorage.class)
                        .mockStatic(AppSearchDao.class)
                        .strictness(Strictness.LENIENT)
                        .initMocks(this)
                        .startMocking();

        ArgumentCaptor<PlatformStorage.SearchContext> cap =
                ArgumentCaptor.forClass(PlatformStorage.SearchContext.class);
        doReturn(mAppSearchSession)
                .when(() -> PlatformStorage.createSearchSessionAsync(cap.capture()));

        mWorker = AppSearchMeasurementRollbackWorker.getInstance(mContext, USERID);
        assertThat(cap.getValue().getDatabaseName()).isEqualTo("measurement_rollback");
    }

    @After
    public void teardown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void testWorkerCreation_invalidValues() {
        assertThrows(
                NullPointerException.class,
                () -> AppSearchMeasurementRollbackWorker.getInstance(null, USERID));
        assertThrows(
                NullPointerException.class,
                () -> AppSearchMeasurementRollbackWorker.getInstance(mContext, null));
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @Test
    public void testClearAdServicesDeletionOccurred() {
        FluentFuture mockResult =
                FluentFuture.from(
                        Futures.immediateFuture(
                                new AppSearchBatchResult.Builder<String, Void>().build()));
        doReturn(mockResult).when(() -> AppSearchDao.deleteData(any(), any(), any(), any(), any()));

        String mockRowId = "mock_row_id";
        mWorker.clearAdServicesDeletionOccurred(mockRowId);

        verify(
                () ->
                        AppSearchDao.deleteData(
                                eq(AppSearchMeasurementRollbackDao.class),
                                eq(mAppSearchSession),
                                any(),
                                eq(mockRowId),
                                eq(AppSearchMeasurementRollbackDao.NAMESPACE)));
    }

    @Test
    public void testClearAdServicesDeletionOccurred_throwsChecked() {
        Callable<Void> callable =
                () -> {
                    TimeUnit.MILLISECONDS.sleep(FUTURE_TIMEOUT_MILLISECONDS);
                    return null;
                };

        FluentFuture mockResult = FluentFuture.from(Futures.submit(callable, mExecutor));
        doReturn(mockResult).when(() -> AppSearchDao.deleteData(any(), any(), any(), any(), any()));

        RuntimeException e =
                assertThrows(
                        RuntimeException.class,
                        () -> mWorker.clearAdServicesDeletionOccurred("mock_row_id"));
        assertThat(e).hasMessageThat().contains(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
    }

    @Test
    public void testClearAdServicesDeletionOccurred_throwsUnchecked() {
        IllegalStateException exception = new IllegalStateException("test exception");
        doThrow(exception).when(() -> AppSearchDao.deleteData(any(), any(), any(), any(), any()));

        RuntimeException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> mWorker.clearAdServicesDeletionOccurred("mock_row_id"));
        assertThat(e)
                .hasMessageThat()
                .doesNotContain(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
    }

    @Test
    public void testClearAdServicesDeletionOccurred_nullInput() {
        assertThrows(
                NullPointerException.class, () -> mWorker.clearAdServicesDeletionOccurred(null));
    }

    @Test
    public void testGetAdServicesDeletionApexVersion_documentFound() {
        AppSearchMeasurementRollbackDao mockDao = mock(AppSearchMeasurementRollbackDao.class);
        doReturn(APEX_VERSION).when(mockDao).getApexVersion();
        doReturn(mockDao)
                .when(
                        () ->
                                AppSearchDao.readAppSearchSessionData(
                                        any(), any(), any(), any(), any()));

        AppSearchMeasurementRollbackDao dao =
                mWorker.getAdServicesDeletionRollbackMetadata(
                        AdServicesManager.MEASUREMENT_DELETION);
        assertThat(dao).isNotNull();
        assertThat(dao.getApexVersion()).isEqualTo(APEX_VERSION);
        verify(
                () ->
                        AppSearchDao.readAppSearchSessionData(
                                eq(AppSearchMeasurementRollbackDao.class),
                                eq(mAppSearchSession),
                                any(),
                                eq(AppSearchMeasurementRollbackDao.NAMESPACE),
                                eq(AppSearchMeasurementRollbackDao.getQuery(USERID))));
    }

    @Test
    public void testGetAdServicesDeletionApexVersion_documentNotFound() {
        doReturn(null)
                .when(
                        () ->
                                AppSearchDao.readAppSearchSessionData(
                                        any(), any(), any(), any(), any()));

        AppSearchMeasurementRollbackDao dao =
                mWorker.getAdServicesDeletionRollbackMetadata(
                        AdServicesManager.MEASUREMENT_DELETION);
        assertThat(dao).isNull();
        verify(
                () ->
                        AppSearchDao.readAppSearchSessionData(
                                eq(AppSearchMeasurementRollbackDao.class),
                                eq(mAppSearchSession),
                                any(),
                                eq(AppSearchMeasurementRollbackDao.NAMESPACE),
                                eq(AppSearchMeasurementRollbackDao.getQuery(USERID))));
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @Test
    public void testRecordAdServicesDeletionOccurred() {
        FluentFuture mockFuture = FluentFuture.from(Futures.immediateVoidFuture());
        AppSearchMeasurementRollbackDao dao = mock(AppSearchMeasurementRollbackDao.class);
        doReturn(mockFuture).when(dao).writeData(any(), any(), any());

        AppSearchMeasurementRollbackWorker spyWorker = spy(mWorker);
        doReturn(dao)
                .when(spyWorker)
                .createAppSearchMeasurementRollbackDao(
                        AdServicesManager.MEASUREMENT_DELETION, APEX_VERSION);

        spyWorker.recordAdServicesDeletionOccurred(
                AdServicesManager.MEASUREMENT_DELETION, APEX_VERSION);
        verify(dao).writeData(eq(mAppSearchSession), eq(List.of()), any());
        verifyNoMoreInteractions(dao);
    }

    @Test
    public void testRecordAdServicesDeletionOccurred_throwsChecked() {
        // The manager class waits for 2 seconds on the future.get() call before timing out. So
        // creating a future that takes longer than 2 sec to resolve, in order to create a
        // TimeoutException.
        Callable<Void> callable =
                () -> {
                    TimeUnit.MILLISECONDS.sleep(FUTURE_TIMEOUT_MILLISECONDS);
                    return null;
                };

        ListenableFuture<Void> future = Futures.submit(callable, mExecutor);
        FluentFuture mockFuture = FluentFuture.from(future);
        AppSearchMeasurementRollbackDao dao = mock(AppSearchMeasurementRollbackDao.class);
        doReturn(mockFuture).when(dao).writeData(any(), any(), any());

        AppSearchMeasurementRollbackWorker spyWorker = spy(mWorker);
        doReturn(dao)
                .when(spyWorker)
                .createAppSearchMeasurementRollbackDao(
                        AdServicesManager.MEASUREMENT_DELETION, APEX_VERSION);

        RuntimeException e =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                spyWorker.recordAdServicesDeletionOccurred(
                                        AdServicesManager.MEASUREMENT_DELETION, APEX_VERSION));
        assertThat(e).hasMessageThat().contains(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
    }

    @Test
    public void testRecordAdServicesDeletionOccurred_throwsUnchecked() {
        AppSearchMeasurementRollbackDao dao = mock(AppSearchMeasurementRollbackDao.class);
        IllegalStateException exceptionToThrow = new IllegalStateException("test exception");
        doThrow(exceptionToThrow).when(dao).writeData(any(), any(), any());

        AppSearchMeasurementRollbackWorker spyWorker = spy(mWorker);
        doReturn(dao)
                .when(spyWorker)
                .createAppSearchMeasurementRollbackDao(
                        AdServicesManager.MEASUREMENT_DELETION, APEX_VERSION);

        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                spyWorker.recordAdServicesDeletionOccurred(
                                        AdServicesManager.MEASUREMENT_DELETION, APEX_VERSION));
        assertThat(e)
                .hasMessageThat()
                .doesNotContain(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
    }

    @Test
    public void testCreateAppSearchMeasurementRollbackDao() {
        AppSearchMeasurementRollbackDao dao =
                mWorker.createAppSearchMeasurementRollbackDao(
                        AdServicesManager.MEASUREMENT_DELETION, APEX_VERSION);
        assertThat(dao.getApexVersion()).isEqualTo(APEX_VERSION);
        assertThat(dao.getNamespace()).isEqualTo(AppSearchMeasurementRollbackDao.NAMESPACE);
        assertThat(dao.getUserId()).isEqualTo(USERID);
        assertThat(dao.getId())
                .isEqualTo(
                        AppSearchMeasurementRollbackDao.getRowId(
                                USERID, AdServicesManager.MEASUREMENT_DELETION));
    }
}
