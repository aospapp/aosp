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

import android.annotation.NonNull;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appsearch.app.AppSearchBatchResult;
import androidx.appsearch.app.AppSearchSession;
import androidx.appsearch.app.GenericDocument;
import androidx.appsearch.app.GlobalSearchSession;
import androidx.appsearch.app.PackageIdentifier;
import androidx.appsearch.app.PutDocumentsRequest;
import androidx.appsearch.app.RemoveByDocumentIdRequest;
import androidx.appsearch.app.SearchResults;
import androidx.appsearch.app.SearchSpec;
import androidx.appsearch.app.SetSchemaRequest;
import androidx.appsearch.exceptions.AppSearchException;

import com.android.adservices.LogUtil;
import com.android.adservices.service.consent.ConsentConstants;
import com.android.internal.annotations.VisibleForTesting;

import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

/**
 * Base class for all data access objects for AppSearch. This class handles the common logic for
 * reading from and writing to AppSearch.
 */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
class AppSearchDao {
    // Timeout for AppSearch search query in milliseconds.
    private static final int TIMEOUT_MS = 500;

    /**
     * Iterate over the search results returned for the search query by AppSearch.
     *
     * @return future containing instance of the subclass type.
     * @param <T> the subclass of AppSearchDao that this Document is of type.
     */
    @VisibleForTesting
    static <T> ListenableFuture<T> iterateSearchResults(
            Class<T> cls, SearchResults searchResults, Executor executor) {
        return Futures.transform(
                searchResults.getNextPageAsync(),
                page -> {
                    if (page.isEmpty()) return null;

                    // Gets GenericDocument from SearchResult.
                    GenericDocument genericDocument = page.get(0).getGenericDocument();
                    String schemaType = genericDocument.getSchemaType();
                    T documentResult = null;

                    if (schemaType.equals(cls.getSimpleName())) {
                        try {
                            // Converts GenericDocument object to the type of object passed in cls.
                            documentResult = genericDocument.toDocumentClass(cls);
                        } catch (AppSearchException e) {
                            LogUtil.e("Failed to convert GenericDocument to " + cls.getName(), e);
                        }
                    }

                    return documentResult;
                },
                executor);
    }

    /**
     * Read the consent data from the provided GlobalSearchSession. This requires a query to be
     * specified. If the query is not specified, we do not perform a search since multiple rows will
     * be returned.
     *
     * @return the instance of subclass type that was read from AppSearch.
     */
    @Nullable
    protected static <T> T readConsentData(
            @NonNull Class<T> cls,
            @NonNull ListenableFuture<GlobalSearchSession> searchSession,
            @NonNull Executor executor,
            @NonNull String namespace,
            @NonNull String query) {
        return readData(
                cls,
                searchSession,
                executor,
                namespace,
                query,
                (session, spec) -> session.search(query, spec));
    }

    /**
     * Read the session data from the provided AppSearchSession. This requires a query to be
     * specified. If the query is not specified, we do not perform a search since multiple rows will
     * be returned.
     *
     * @return the instance of subclass type that was read from AppSearch.
     */
    @Nullable
    protected static <T> T readAppSearchSessionData(
            @NonNull Class<T> cls,
            @NonNull ListenableFuture<AppSearchSession> searchSession,
            @NonNull Executor executor,
            @NonNull String namespace,
            @NonNull String query) {
        return readData(
                cls,
                searchSession,
                executor,
                namespace,
                query,
                (session, spec) -> session.search(query, spec));
    }

    @Nullable
    private static <T, S> T readData(
            @NonNull Class<T> cls,
            @NonNull ListenableFuture<S> searchSession,
            @NonNull Executor executor,
            @NonNull String namespace,
            @NonNull String query,
            @NonNull BiFunction<S, SearchSpec, SearchResults> sessionQuery) {
        Objects.requireNonNull(cls);
        Objects.requireNonNull(searchSession);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(sessionQuery);

        // Namespace and Query cannot be empty.
        if (query == null || query.isEmpty() || namespace.isEmpty()) {
            return null;
        }

        try {
            SearchSpec searchSpec = new SearchSpec.Builder().addFilterNamespaces(namespace).build();
            ListenableFuture<SearchResults> searchFuture =
                    Futures.transform(
                            searchSession,
                            session -> sessionQuery.apply(session, searchSpec),
                            executor);
            FluentFuture<T> future =
                    FluentFuture.from(searchFuture)
                            .transformAsync(
                                    results -> iterateSearchResults(cls, results, executor),
                                    executor)
                            .transform(result -> ((T) result), executor);
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            LogUtil.e("getConsent() Appsearch lookup failed with: ", e);
        }
        return null;
    }

    /**
     * Write consent/session data to AppSearch. This requires knowing the packageIdentifier of the
     * package that needs to be allowed read access to the data. When we write the data on S- device
     * we specify the packageIdentifier as that of the T+ AdServices APK, which after OTA, needs
     * access to the data written before OTA. What is written is the subclass type of DAO.
     *
     * @return the result of the write.
     */
    FluentFuture<AppSearchBatchResult<String, Void>> writeData(
            @NonNull ListenableFuture<AppSearchSession> appSearchSession,
            @NonNull List<PackageIdentifier> packageIdentifiers,
            @NonNull Executor executor) {
        Objects.requireNonNull(appSearchSession);
        Objects.requireNonNull(packageIdentifiers);
        Objects.requireNonNull(executor);

        try {
            SetSchemaRequest.Builder setSchemaRequestBuilder = new SetSchemaRequest.Builder();
            setSchemaRequestBuilder.addDocumentClasses(getClass());
            for (PackageIdentifier packageIdentifier : packageIdentifiers) {
                setSchemaRequestBuilder.setSchemaTypeVisibilityForPackage(
                        getClass().getSimpleName(), true, packageIdentifier);
            }
            SetSchemaRequest setSchemaRequest = setSchemaRequestBuilder.build();
            PutDocumentsRequest putRequest =
                    new PutDocumentsRequest.Builder().addDocuments(this).build();
            FluentFuture<AppSearchBatchResult<String, Void>> putFuture =
                    FluentFuture.from(appSearchSession)
                            .transformAsync(
                                    session -> session.setSchemaAsync(setSchemaRequest), executor)
                            .transformAsync(
                                    setSchemaResponse -> {
                                        // If we get failures in schemaResponse then we cannot try
                                        // to write.
                                        if (!setSchemaResponse.getMigrationFailures().isEmpty()) {
                                            LogUtil.e(
                                                    "SetSchemaResponse migration failure: "
                                                            + setSchemaResponse
                                                                    .getMigrationFailures()
                                                                    .get(0));
                                            throw new RuntimeException(
                                                    ConsentConstants
                                                            .ERROR_MESSAGE_APPSEARCH_FAILURE);
                                        }
                                        // The database knows about this schemaType and write can
                                        // occur.
                                        return Futures.transformAsync(
                                                appSearchSession,
                                                session -> session.putAsync(putRequest),
                                                executor);
                                    },
                                    executor);
            return putFuture;
        } catch (AppSearchException e) {
            LogUtil.e("Cannot instantiate AppSearch database: " + e.getMessage());
        }
        return FluentFuture.from(
                Futures.immediateFailedFuture(
                        new RuntimeException(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE)));
    }

    /**
     * Delete a row from the database.
     *
     * @return the result of the delete.
     */
    protected static <T> FluentFuture<AppSearchBatchResult<String, Void>> deleteData(
            @NonNull Class<T> cls,
            @NonNull ListenableFuture<AppSearchSession> appSearchSession,
            @NonNull Executor executor,
            @NonNull String rowId,
            @NonNull String namespace) {
        Objects.requireNonNull(cls);
        Objects.requireNonNull(appSearchSession);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(rowId);
        Objects.requireNonNull(namespace);

        try {
            SetSchemaRequest setSchemaRequest =
                    new SetSchemaRequest.Builder().addDocumentClasses(cls).build();
            RemoveByDocumentIdRequest deleteRequest =
                    new RemoveByDocumentIdRequest.Builder(namespace).addIds(rowId).build();
            FluentFuture<AppSearchBatchResult<String, Void>> deleteFuture =
                    FluentFuture.from(appSearchSession)
                            .transformAsync(
                                    session -> session.setSchemaAsync(setSchemaRequest), executor)
                            .transformAsync(
                                    setSchemaResponse -> {
                                        // If we get failures in schemaResponse then we cannot try
                                        // to write.
                                        if (!setSchemaResponse.getMigrationFailures().isEmpty()) {
                                            LogUtil.e(
                                                    "SetSchemaResponse migration failure: "
                                                            + setSchemaResponse
                                                                    .getMigrationFailures()
                                                                    .get(0));
                                            throw new RuntimeException(
                                                    ConsentConstants
                                                            .ERROR_MESSAGE_APPSEARCH_FAILURE);
                                        }
                                        // The database knows about this schemaType and write can
                                        // occur.
                                        return Futures.transformAsync(
                                                appSearchSession,
                                                session -> session.removeAsync(deleteRequest),
                                                executor);
                                    },
                                    executor);
            return deleteFuture;
        } catch (AppSearchException e) {
            LogUtil.e("Cannot instantiate AppSearch database: " + e.getMessage());
        }
        return FluentFuture.from(
                Futures.immediateFailedFuture(
                        new RuntimeException(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE)));
    }
}
