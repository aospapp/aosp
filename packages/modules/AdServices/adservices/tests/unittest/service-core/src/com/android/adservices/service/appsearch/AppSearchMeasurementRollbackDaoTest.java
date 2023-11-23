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

import static com.android.adservices.service.appsearch.AppSearchMeasurementRollbackDao.getRowId;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.app.adservices.AdServicesManager;

import androidx.appsearch.app.AppSearchSession;
import androidx.test.filters.SmallTest;

import com.android.adservices.concurrency.AdServicesExecutors;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;

import java.util.concurrent.Executor;

@SmallTest
public class AppSearchMeasurementRollbackDaoTest {
    private static final String USER1 = "ID1";
    private static final String USER2 = "ID2";
    private static final String NAMESPACE = "test_namespace";
    private static final long APEX_VERSION = 100L;

    private final Executor mExecutor = AdServicesExecutors.getBackgroundExecutor();
    @Mock private ListenableFuture<AppSearchSession> mAppSearchSession;

    @Test
    public void testGetProperties() {
        AppSearchMeasurementRollbackDao dao =
                new AppSearchMeasurementRollbackDao(USER1, NAMESPACE, USER2, APEX_VERSION);
        assertThat(dao.getId()).isEqualTo(USER1);
        assertThat(dao.getNamespace()).isEqualTo(NAMESPACE);
        assertThat(dao.getUserId()).isEqualTo(USER2);
        assertThat(dao.getApexVersion()).isEqualTo(APEX_VERSION);
    }

    @SuppressWarnings("TruthIncompatibleType")
    @Test
    public void testEquals() {
        AppSearchMeasurementRollbackDao dao1 =
                new AppSearchMeasurementRollbackDao(USER1, NAMESPACE, USER2, APEX_VERSION);
        AppSearchMeasurementRollbackDao dao2 =
                new AppSearchMeasurementRollbackDao(USER2, NAMESPACE, USER1, APEX_VERSION);
        AppSearchMeasurementRollbackDao dao3 =
                new AppSearchMeasurementRollbackDao(USER1, NAMESPACE, USER2, APEX_VERSION);
        AppSearchConsentDao dao4 =
                new AppSearchConsentDao(USER1, USER1, NAMESPACE, "API_TYPE", "true");

        // Not using assertThat(dao1).isEqualTo(dao1) because that causes errorprone failures.
        // Tried to use EqualsTester, but adding guava-android-testlib to the bp file resulted in
        // lots of test failures.
        assertThat(dao1.equals(dao1)).isTrue();
        assertThat(dao1).isNotEqualTo(dao2);
        assertThat(dao1).isEqualTo(dao3);
        assertThat(dao1).isNotEqualTo(dao4);
        assertThat(dao2).isNotEqualTo(dao3);
    }

    @Test
    public void testHashCode() {
        AppSearchMeasurementRollbackDao dao1 =
                new AppSearchMeasurementRollbackDao(USER1, NAMESPACE, USER2, APEX_VERSION);
        AppSearchMeasurementRollbackDao dao2 =
                new AppSearchMeasurementRollbackDao(USER2, NAMESPACE, USER1, APEX_VERSION);
        AppSearchMeasurementRollbackDao dao3 =
                new AppSearchMeasurementRollbackDao(USER1, NAMESPACE, USER2, APEX_VERSION);
        AppSearchMeasurementRollbackDao dao4 =
                new AppSearchMeasurementRollbackDao(USER1, NAMESPACE, USER2, APEX_VERSION * 2);
        assertThat(dao1.hashCode()).isNotEqualTo(dao2.hashCode());
        assertThat(dao1.hashCode()).isEqualTo(dao3.hashCode());
        assertThat(dao1.hashCode()).isNotEqualTo(dao4.hashCode());
    }

    @Test
    public void testToString() {
        AppSearchMeasurementRollbackDao dao1 =
                new AppSearchMeasurementRollbackDao(USER1, NAMESPACE, USER2, APEX_VERSION);
        final String expected =
                String.format(
                        "id=%s; userId=%s; namespace=%s; apexVersion=%d",
                        USER1, USER2, NAMESPACE, APEX_VERSION);
        assertThat(dao1.toString()).isEqualTo(expected);
    }

    @Test
    public void testGetRowId() {
        final String expected = String.format("Measurement_Rollback_%s_0", USER1);
        assertThat(getRowId(USER1, AdServicesManager.MEASUREMENT_DELETION)).isEqualTo(expected);

        assertThrows(
                NullPointerException.class,
                () -> getRowId(null, AdServicesManager.MEASUREMENT_DELETION));
    }

    @Test
    public void testGetQuery() {
        String expected = "userId:" + USER1;
        assertThat(AppSearchMeasurementRollbackDao.getQuery(USER1)).isEqualTo(expected);
    }

    @Test
    public void testReadDocument_invalidInputs() {
        MockitoSession mockitoSession =
                ExtendedMockito.mockitoSession().initMocks(this).startMocking();
        try {
            assertThrows(
                    NullPointerException.class,
                    () -> AppSearchMeasurementRollbackDao.readDocument(null, mExecutor, USER1));
            assertThrows(
                    NullPointerException.class,
                    () ->
                            AppSearchMeasurementRollbackDao.readDocument(
                                    mAppSearchSession, null, USER1));
            assertThrows(
                    NullPointerException.class,
                    () ->
                            AppSearchMeasurementRollbackDao.readDocument(
                                    mAppSearchSession, mExecutor, null));
            assertThat(
                            AppSearchMeasurementRollbackDao.readDocument(
                                    mAppSearchSession, mExecutor, ""))
                    .isNull();
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @Test
    public void testReadDocument() {
        MockitoSession mockitoSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(AppSearchDao.class)
                        .initMocks(this)
                        .startMocking();
        try {
            AppSearchMeasurementRollbackDao mockDao =
                    Mockito.mock(AppSearchMeasurementRollbackDao.class);
            doReturn(mockDao)
                    .when(
                            () ->
                                    AppSearchDao.readAppSearchSessionData(
                                            any(), any(), any(), any(), any()));

            AppSearchMeasurementRollbackDao returned =
                    AppSearchMeasurementRollbackDao.readDocument(
                            mAppSearchSession, mExecutor, USER1);
            assertThat(returned).isEqualTo(mockDao);
            verify(
                    () ->
                            AppSearchDao.readAppSearchSessionData(
                                    eq(AppSearchMeasurementRollbackDao.class),
                                    eq(mAppSearchSession),
                                    eq(mExecutor),
                                    eq(AppSearchMeasurementRollbackDao.NAMESPACE),
                                    eq(AppSearchMeasurementRollbackDao.getQuery(USER1))));
        } finally {
            mockitoSession.finishMocking();
        }
    }
}
