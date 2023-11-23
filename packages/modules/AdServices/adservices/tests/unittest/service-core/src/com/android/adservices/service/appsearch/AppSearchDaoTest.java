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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;

import android.content.pm.Signature;

import androidx.appsearch.app.AppSearchBatchResult;
import androidx.appsearch.app.AppSearchResult;
import androidx.appsearch.app.AppSearchSession;
import androidx.appsearch.app.GenericDocument;
import androidx.appsearch.app.GlobalSearchSession;
import androidx.appsearch.app.PackageIdentifier;
import androidx.appsearch.app.SearchResult;
import androidx.appsearch.app.SearchResults;
import androidx.appsearch.app.SetSchemaRequest;
import androidx.appsearch.app.SetSchemaResponse;
import androidx.test.filters.SmallTest;

import com.android.adservices.concurrency.AdServicesExecutors;
import com.android.adservices.service.consent.ConsentConstants;

import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

@SmallTest
public class AppSearchDaoTest {
    @Mock SearchResults mSearchResults;
    @Mock List<SearchResult> mMockPage;
    @Mock GlobalSearchSession mGlobalSearchSession;
    @Mock AppSearchSession mAppSearchSession;
    private final Executor mExecutor = AdServicesExecutors.getBackgroundExecutor();

    private static final String ID = "1";
    private static final String NAMESPACE = "consent";
    private static final String API_TYPE = "CONSENT-TOPICS";
    private static final String CONSENT = "true";
    private static final String TEST = "test";
    private static final String SHA =
            "686d5c450e00ebe600f979300a29234644eade42f24ede07a073f2bc6b94a3a2";
    private static final PackageIdentifier PACKAGE_IDENTIFIER =
            new PackageIdentifier(
                    /* packageName= */ TEST, /* sha256= */ new Signature(SHA).toByteArray());

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testIterateSearchResults_emptyPage() throws Exception {
        when(mMockPage.isEmpty()).thenReturn(true);
        when(mSearchResults.getNextPageAsync()).thenReturn(Futures.immediateFuture(mMockPage));
        ListenableFuture<AppSearchConsentDao> result =
                AppSearchDao.iterateSearchResults(
                        AppSearchConsentDao.class, mSearchResults, mExecutor);
        assertThat(result.get()).isEqualTo(null);
    }

    @Test
    public void testIterateSearchResults() throws Exception {
        AppSearchConsentDao dao = new AppSearchConsentDao(ID, ID, NAMESPACE, API_TYPE, CONSENT);
        when(mMockPage.isEmpty()).thenReturn(false);
        when(mSearchResults.getNextPageAsync()).thenReturn(Futures.immediateFuture(mMockPage));
        GenericDocument document =
                new GenericDocument.Builder(NAMESPACE, ID, dao.getClass().getSimpleName())
                        .setPropertyString("userId", ID)
                        .setPropertyString("consent", CONSENT)
                        .setPropertyString("apiType", API_TYPE)
                        .build();
        SearchResult searchResult =
                new SearchResult.Builder(TEST, TEST).setGenericDocument(document).build();
        when(mMockPage.get(0)).thenReturn(searchResult);

        ListenableFuture<AppSearchConsentDao> result =
                AppSearchDao.iterateSearchResults(
                        AppSearchConsentDao.class, mSearchResults, mExecutor);
        assertThat(result.get()).isEqualTo(dao);
    }

    @Test
    public void testReadConsentData_emptyQuery() {
        AppSearchDao dao =
                AppSearchDao.readConsentData(
                        AppSearchConsentDao.class,
                        Futures.immediateFuture(mGlobalSearchSession),
                        mExecutor,
                        NAMESPACE,
                        null);
        assertThat(dao).isEqualTo(null);

        AppSearchDao dao2 =
                AppSearchDao.readConsentData(
                        AppSearchConsentDao.class,
                        Futures.immediateFuture(mGlobalSearchSession),
                        mExecutor,
                        NAMESPACE,
                        "");
        assertThat(dao2).isEqualTo(null);
    }

    @Test
    public void testReadConsentData() {
        when(mMockPage.isEmpty()).thenReturn(false);
        when(mSearchResults.getNextPageAsync()).thenReturn(Futures.immediateFuture(mMockPage));
        AppSearchConsentDao dao = new AppSearchConsentDao(ID, ID, NAMESPACE, API_TYPE, CONSENT);
        GenericDocument document =
                new GenericDocument.Builder(NAMESPACE, ID, dao.getClass().getSimpleName())
                        .setPropertyString("userId", ID)
                        .setPropertyString("consent", CONSENT)
                        .setPropertyString("apiType", API_TYPE)
                        .build();
        SearchResult searchResult =
                new SearchResult.Builder(TEST, TEST).setGenericDocument(document).build();
        when(mMockPage.get(0)).thenReturn(searchResult);
        when(mGlobalSearchSession.search(any(), any())).thenReturn(mSearchResults);
        AppSearchDao result =
                AppSearchDao.readConsentData(
                        AppSearchConsentDao.class,
                        Futures.immediateFuture(mGlobalSearchSession),
                        mExecutor,
                        NAMESPACE,
                        TEST);
        assertThat(result).isEqualTo(dao);
    }

    @Test
    public void testReadAppSearchData_emptyQuery() {
        AppSearchDao dao =
                AppSearchDao.readAppSearchSessionData(
                        AppSearchConsentDao.class,
                        Futures.immediateFuture(mAppSearchSession),
                        mExecutor,
                        NAMESPACE,
                        null);
        assertThat(dao).isEqualTo(null);

        AppSearchDao dao2 =
                AppSearchDao.readAppSearchSessionData(
                        AppSearchConsentDao.class,
                        Futures.immediateFuture(mAppSearchSession),
                        mExecutor,
                        NAMESPACE,
                        "");
        assertThat(dao2).isEqualTo(null);
    }

    @Test
    public void testReadAppSearchData() {
        when(mMockPage.isEmpty()).thenReturn(false);
        when(mSearchResults.getNextPageAsync()).thenReturn(Futures.immediateFuture(mMockPage));
        AppSearchConsentDao dao = new AppSearchConsentDao(ID, ID, NAMESPACE, API_TYPE, CONSENT);
        GenericDocument document =
                new GenericDocument.Builder(NAMESPACE, ID, dao.getClass().getSimpleName())
                        .setPropertyString("userId", ID)
                        .setPropertyString("consent", CONSENT)
                        .setPropertyString("apiType", API_TYPE)
                        .build();
        SearchResult searchResult =
                new SearchResult.Builder(TEST, TEST).setGenericDocument(document).build();
        when(mMockPage.get(0)).thenReturn(searchResult);
        when(mAppSearchSession.search(any(), any())).thenReturn(mSearchResults);
        AppSearchDao result =
                AppSearchDao.readAppSearchSessionData(
                        AppSearchConsentDao.class,
                        Futures.immediateFuture(mAppSearchSession),
                        mExecutor,
                        NAMESPACE,
                        TEST);
        assertThat(result).isEqualTo(dao);
    }

    @Test
    public void testWriteConsentData_failure() {
        AppSearchSession mockSession = Mockito.mock(AppSearchSession.class);
        verify(mockSession, atMost(1)).setSchemaAsync(any(SetSchemaRequest.class));

        SetSchemaResponse mockResponse = Mockito.mock(SetSchemaResponse.class);
        when(mockSession.setSchemaAsync(any(SetSchemaRequest.class)))
                .thenReturn(Futures.immediateFuture(mockResponse));

        AppSearchResult mockResult = Mockito.mock(AppSearchResult.class);
        SetSchemaResponse.MigrationFailure failure =
                new SetSchemaResponse.MigrationFailure(
                        /* namespace= */ TEST,
                        /* id= */ TEST,
                        /* schemaType= */ TEST,
                        /* appSearchResult= */ mockResult);
        when(mockResponse.getMigrationFailures()).thenReturn(List.of(failure));
        // We can't use the base class instance since writing will fail without the necessary
        // Document fields defined on the class, so we use a subclass instance.
        AppSearchConsentDao dao = new AppSearchConsentDao(ID, ID, NAMESPACE, API_TYPE, CONSENT);
        FluentFuture<AppSearchBatchResult<String, Void>> result =
                dao.writeData(
                        Futures.immediateFuture(mockSession),
                        List.of(PACKAGE_IDENTIFIER),
                        mExecutor);
        ExecutionException e = assertThrows(ExecutionException.class, () -> result.get());
        assertThat(e.getMessage())
                .isEqualTo(
                        "java.lang.RuntimeException: "
                                + ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
    }

    @Test
    public void testWriteConsentData() throws Exception {
        AppSearchSession mockSession = Mockito.mock(AppSearchSession.class);
        verify(mockSession, atMost(1)).setSchemaAsync(any(SetSchemaRequest.class));

        SetSchemaResponse mockResponse = Mockito.mock(SetSchemaResponse.class);
        when(mockSession.setSchemaAsync(any(SetSchemaRequest.class)))
                .thenReturn(Futures.immediateFuture(mockResponse));

        verify(mockResponse, atMost(1)).getMigrationFailures();
        when(mockResponse.getMigrationFailures()).thenReturn(List.of());
        // We can't use the base class instance since writing will fail without the necessary
        // Document fields defined on the class, so we use a subclass instance.
        AppSearchConsentDao dao = new AppSearchConsentDao(ID, ID, NAMESPACE, API_TYPE, CONSENT);
        AppSearchBatchResult<String, Void> result = Mockito.mock(AppSearchBatchResult.class);
        when(mockSession.putAsync(any())).thenReturn(Futures.immediateFuture(result));

        // Verify that no exception is thrown.
        FluentFuture future =
                dao.writeData(
                        Futures.immediateFuture(mockSession),
                        List.of(PACKAGE_IDENTIFIER),
                        mExecutor);
        assertThat(future.get()).isNotNull();
    }

    @Test
    public void testDeleteConsentData_failure() {
        AppSearchSession mockSession = Mockito.mock(AppSearchSession.class);
        verify(mockSession, atMost(1)).setSchemaAsync(any(SetSchemaRequest.class));

        SetSchemaResponse mockResponse = Mockito.mock(SetSchemaResponse.class);
        when(mockSession.setSchemaAsync(any(SetSchemaRequest.class)))
                .thenReturn(Futures.immediateFuture(mockResponse));

        AppSearchResult mockResult = Mockito.mock(AppSearchResult.class);
        SetSchemaResponse.MigrationFailure failure =
                new SetSchemaResponse.MigrationFailure(
                        /* namespace= */ TEST,
                        /* id= */ TEST,
                        /* schemaType= */ TEST,
                        /* appSearchResult= */ mockResult);
        when(mockResponse.getMigrationFailures()).thenReturn(List.of(failure));
        // We can't use the base class instance since writing will fail without the necessary
        // Document fields defined on the class, so we use a subclass instance.
        FluentFuture<AppSearchBatchResult<String, Void>> result =
                AppSearchDao.deleteData(
                        AppSearchConsentDao.class,
                        Futures.immediateFuture(mockSession),
                        mExecutor,
                        TEST,
                        NAMESPACE);
        ExecutionException e = assertThrows(ExecutionException.class, () -> result.get());
        assertThat(e.getMessage())
                .isEqualTo(
                        "java.lang.RuntimeException: "
                                + ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
    }

    @Test
    public void testDeleteConsentData() throws Exception {
        AppSearchSession mockSession = Mockito.mock(AppSearchSession.class);
        verify(mockSession, atMost(1)).setSchemaAsync(any(SetSchemaRequest.class));

        SetSchemaResponse mockResponse = Mockito.mock(SetSchemaResponse.class);
        when(mockSession.setSchemaAsync(any(SetSchemaRequest.class)))
                .thenReturn(Futures.immediateFuture(mockResponse));

        verify(mockResponse, atMost(1)).getMigrationFailures();
        when(mockResponse.getMigrationFailures()).thenReturn(List.of());
        // We can't use the base class instance since writing will fail without the necessary
        // Document fields defined on the class, so we use a subclass instance.
        AppSearchBatchResult<String, Void> result = Mockito.mock(AppSearchBatchResult.class);
        when(mockSession.removeAsync(any())).thenReturn(Futures.immediateFuture(result));

        // Verify that no exception is thrown.
        FluentFuture future =
                AppSearchDao.deleteData(
                        AppSearchConsentDao.class,
                        Futures.immediateFuture(mockSession),
                        mExecutor,
                        TEST,
                        NAMESPACE);
        assertThat(future.get()).isNotNull();
    }
}
