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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.concurrent.Executor;

@SmallTest
public class AppSearchUxStatesDaoTest {
    private static final String ID1 = "1";
    private static final String ID2 = "2";
    private static final String NAMESPACE = "uxstates";
    private MockitoSession mStaticMockSession;

    @Before
    public void setup() {
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(AppSearchDao.class)
                        .strictness(Strictness.WARN)
                        .initMocks(this)
                        .startMocking();
    }

    @After
    public void teardown() {
        if (mStaticMockSession != null) {
            mStaticMockSession.finishMocking();
        }
    }

    @Test
    public void testToString() {
        AppSearchUxStatesDao dao =
                new AppSearchUxStatesDao(ID1, ID2, NAMESPACE, false, false, false, false, false);
        assertThat(dao.toString())
                .isEqualTo(
                        "id="
                                + ID1
                                + "; userId="
                                + ID2
                                + "; namespace="
                                + NAMESPACE
                                + "; isEntryPointEnabled=false"
                                + "; isU18Account=false"
                                + "; isAdultAccount=false"
                                + "; isAdIdEnabled=false"
                                + "; wasU18NotificationDisplayed=false");
    }

    @Test
    public void testEquals() {
        AppSearchUxStatesDao dao1 =
                new AppSearchUxStatesDao(ID1, ID2, NAMESPACE, true, false, false, false, false);
        AppSearchUxStatesDao dao2 =
                new AppSearchUxStatesDao(ID1, ID2, NAMESPACE, true, false, false, false, false);
        AppSearchUxStatesDao dao3 =
                new AppSearchUxStatesDao(ID1, "foo", NAMESPACE, true, false, false, false, false);
        assertThat(dao1.equals(dao2)).isTrue();
        assertThat(dao1.equals(dao3)).isFalse();
        assertThat(dao2.equals(dao3)).isFalse();
    }

    @Test
    public void testGetQuery() {
        String expected = "userId:" + ID1;
        assertThat(AppSearchUxStatesDao.getQuery(ID1)).isEqualTo(expected);
    }

    @Test
    public void testGetRowId() {
        assertThat(AppSearchUxStatesDao.getRowId(ID1)).isEqualTo(ID1);
    }

    @Test
    public void isEntryPointEnabledTest_nullDao() {
        ListenableFuture mockSearchSession = Mockito.mock(ListenableFuture.class);
        Executor mockExecutor = Mockito.mock(Executor.class);
        ExtendedMockito.doReturn(null)
                .when(() -> AppSearchDao.readConsentData(any(), any(), any(), any(), any()));
        boolean result =
                AppSearchUxStatesDao.readIsEntryPointEnabled(mockSearchSession, mockExecutor, ID1);
        assertThat(result).isFalse();
    }

    @Test
    public void isEntryPointEnabledTest_trueBit() {
        ListenableFuture mockSearchSession = Mockito.mock(ListenableFuture.class);
        Executor mockExecutor = Mockito.mock(Executor.class);

        String query = "userId:" + ID1;
        AppSearchUxStatesDao dao = Mockito.mock(AppSearchUxStatesDao.class);
        Mockito.when(dao.isEntryPointEnabled()).thenReturn(false);
        ExtendedMockito.doReturn(dao)
                .when(() -> AppSearchDao.readConsentData(any(), any(), any(), any(), eq(query)));

        boolean result =
                AppSearchUxStatesDao.readIsEntryPointEnabled(mockSearchSession, mockExecutor, ID1);
        assertThat(result).isFalse();

        // Confirm that the right value is returned even when it is true.
        String query2 = "userId:" + ID2;
        AppSearchUxStatesDao dao2 = Mockito.mock(AppSearchUxStatesDao.class);
        Mockito.when(dao2.isEntryPointEnabled()).thenReturn(true);
        ExtendedMockito.doReturn(dao2)
                .when(() -> AppSearchDao.readConsentData(any(), any(), any(), any(), eq(query2)));
        boolean result2 =
                AppSearchUxStatesDao.readIsEntryPointEnabled(mockSearchSession, mockExecutor, ID2);
        assertThat(result2).isTrue();
    }

    @Test
    public void isAdultAccountTest_nullDao() {
        ListenableFuture mockSearchSession = Mockito.mock(ListenableFuture.class);
        Executor mockExecutor = Mockito.mock(Executor.class);
        ExtendedMockito.doReturn(null)
                .when(() -> AppSearchDao.readConsentData(any(), any(), any(), any(), any()));
        boolean result =
                AppSearchUxStatesDao.readIsAdultAccount(mockSearchSession, mockExecutor, ID1);
        assertThat(result).isFalse();
    }

    @Test
    public void isAdultAccountTest_trueBit() {
        ListenableFuture mockSearchSession = Mockito.mock(ListenableFuture.class);
        Executor mockExecutor = Mockito.mock(Executor.class);

        String query = "userId:" + ID1;
        AppSearchUxStatesDao dao = Mockito.mock(AppSearchUxStatesDao.class);
        Mockito.when(dao.isAdultAccount()).thenReturn(false);
        ExtendedMockito.doReturn(dao)
                .when(() -> AppSearchDao.readConsentData(any(), any(), any(), any(), eq(query)));

        boolean result =
                AppSearchUxStatesDao.readIsAdultAccount(mockSearchSession, mockExecutor, ID1);
        assertThat(result).isFalse();

        // Confirm that the right value is returned even when it is true.
        String query2 = "userId:" + ID2;
        AppSearchUxStatesDao dao2 = Mockito.mock(AppSearchUxStatesDao.class);
        Mockito.when(dao2.isAdultAccount()).thenReturn(true);
        ExtendedMockito.doReturn(dao2)
                .when(() -> AppSearchDao.readConsentData(any(), any(), any(), any(), eq(query2)));
        boolean result2 =
                AppSearchUxStatesDao.readIsAdultAccount(mockSearchSession, mockExecutor, ID2);
        assertThat(result2).isTrue();
    }

    @Test
    public void isU18AccountTest_nullDao() {
        ListenableFuture mockSearchSession = Mockito.mock(ListenableFuture.class);
        Executor mockExecutor = Mockito.mock(Executor.class);
        ExtendedMockito.doReturn(null)
                .when(() -> AppSearchDao.readConsentData(any(), any(), any(), any(), any()));
        boolean result =
                AppSearchUxStatesDao.readIsU18Account(mockSearchSession, mockExecutor, ID1);
        assertThat(result).isFalse();
    }

    @Test
    public void isU18AccountTest_trueBit() {
        ListenableFuture mockSearchSession = Mockito.mock(ListenableFuture.class);
        Executor mockExecutor = Mockito.mock(Executor.class);

        String query = "userId:" + ID1;
        AppSearchUxStatesDao dao = Mockito.mock(AppSearchUxStatesDao.class);
        Mockito.when(dao.isU18Account()).thenReturn(false);
        ExtendedMockito.doReturn(dao)
                .when(() -> AppSearchDao.readConsentData(any(), any(), any(), any(), eq(query)));

        boolean result =
                AppSearchUxStatesDao.readIsU18Account(mockSearchSession, mockExecutor, ID1);
        assertThat(result).isFalse();

        // Confirm that the right value is returned even when it is true.
        String query2 = "userId:" + ID2;
        AppSearchUxStatesDao dao2 = Mockito.mock(AppSearchUxStatesDao.class);
        Mockito.when(dao2.isU18Account()).thenReturn(true);
        ExtendedMockito.doReturn(dao2)
                .when(() -> AppSearchDao.readConsentData(any(), any(), any(), any(), eq(query2)));
        boolean result2 =
                AppSearchUxStatesDao.readIsU18Account(mockSearchSession, mockExecutor, ID2);
        assertThat(result2).isTrue();
    }

    @Test
    public void isAdIdEnabledTest_nullDao() {
        ListenableFuture mockSearchSession = Mockito.mock(ListenableFuture.class);
        Executor mockExecutor = Mockito.mock(Executor.class);
        ExtendedMockito.doReturn(null)
                .when(() -> AppSearchDao.readConsentData(any(), any(), any(), any(), any()));
        boolean result =
                AppSearchUxStatesDao.readIsAdIdEnabled(mockSearchSession, mockExecutor, ID1);
        assertThat(result).isFalse();
    }

    @Test
    public void isAdIdEnabledTest_trueBit() {
        ListenableFuture mockSearchSession = Mockito.mock(ListenableFuture.class);
        Executor mockExecutor = Mockito.mock(Executor.class);

        String query = "userId:" + ID1;
        AppSearchUxStatesDao dao = Mockito.mock(AppSearchUxStatesDao.class);
        Mockito.when(dao.isAdIdEnabled()).thenReturn(false);
        ExtendedMockito.doReturn(dao)
                .when(() -> AppSearchDao.readConsentData(any(), any(), any(), any(), eq(query)));

        boolean result =
                AppSearchUxStatesDao.readIsAdIdEnabled(mockSearchSession, mockExecutor, ID1);
        assertThat(result).isFalse();

        // Confirm that the right value is returned even when it is true.
        String query2 = "userId:" + ID2;
        AppSearchUxStatesDao dao2 = Mockito.mock(AppSearchUxStatesDao.class);
        Mockito.when(dao2.isAdIdEnabled()).thenReturn(true);
        ExtendedMockito.doReturn(dao2)
                .when(() -> AppSearchDao.readConsentData(any(), any(), any(), any(), eq(query2)));
        boolean result2 =
                AppSearchUxStatesDao.readIsAdIdEnabled(mockSearchSession, mockExecutor, ID2);
        assertThat(result2).isTrue();
    }

    @Test
    public void wasU18NotificationDisplayedTest_nullDao() {
        ListenableFuture mockSearchSession = Mockito.mock(ListenableFuture.class);
        Executor mockExecutor = Mockito.mock(Executor.class);
        ExtendedMockito.doReturn(null)
                .when(() -> AppSearchDao.readConsentData(any(), any(), any(), any(), any()));
        boolean result =
                AppSearchUxStatesDao.readIsU18NotificationDisplayed(
                        mockSearchSession, mockExecutor, ID1);
        assertThat(result).isFalse();
    }

    @Test
    public void wasU18NotificationDisplayedTest_trueBit() {
        ListenableFuture mockSearchSession = Mockito.mock(ListenableFuture.class);
        Executor mockExecutor = Mockito.mock(Executor.class);

        String query = "userId:" + ID1;
        AppSearchUxStatesDao dao = Mockito.mock(AppSearchUxStatesDao.class);
        Mockito.when(dao.wasU18NotificationDisplayed()).thenReturn(false);
        ExtendedMockito.doReturn(dao)
                .when(() -> AppSearchDao.readConsentData(any(), any(), any(), any(), eq(query)));

        boolean result =
                AppSearchUxStatesDao.readIsU18NotificationDisplayed(
                        mockSearchSession, mockExecutor, ID1);
        assertThat(result).isFalse();

        // Confirm that the right value is returned even when it is true.
        String query2 = "userId:" + ID2;
        AppSearchUxStatesDao dao2 = Mockito.mock(AppSearchUxStatesDao.class);
        Mockito.when(dao2.wasU18NotificationDisplayed()).thenReturn(true);
        ExtendedMockito.doReturn(dao2)
                .when(() -> AppSearchDao.readConsentData(any(), any(), any(), any(), eq(query2)));
        boolean result2 =
                AppSearchUxStatesDao.readIsU18NotificationDisplayed(
                        mockSearchSession, mockExecutor, ID2);
        assertThat(result2).isTrue();
    }
}
