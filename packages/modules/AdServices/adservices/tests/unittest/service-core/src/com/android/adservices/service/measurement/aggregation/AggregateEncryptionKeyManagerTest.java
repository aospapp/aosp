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
package com.android.adservices.service.measurement.aggregation;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.Uri;

import androidx.test.filters.SmallTest;

import com.android.adservices.data.measurement.DatastoreManager;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.stubbing.Answer;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.net.ssl.HttpsURLConnection;

/**
 * Unit tests for {@link AggregateEncryptionKeyFetcher}
 */
@SmallTest
public final class AggregateEncryptionKeyManagerTest {
    private static final int NUM_KEYS_REQUESTED = 5;
    private static final Uri MEASUREMENT_AGGREGATE_ENCRYPTION_KEY_COORDINATOR_URL =
            Uri.parse("https://not-going-to-be-visited.test");

    @Mock DatastoreManager mDatastoreManager;
    @Spy AggregateEncryptionKeyFetcher mFetcher;
    @Mock HttpsURLConnection mUrlConnection;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testNeverCallsAggregateEncryptionKeyFetcher() throws Exception {
        // Mock the datastore to return the expected seed key list; we are testing that the fetcher
        // is not called.
        doAnswer((Answer<Optional<List<AggregateEncryptionKey>>>)
                invocation -> Optional.of(getExpectedKeys()))
                        .when(mDatastoreManager).runInTransactionWithResult(any());
        AggregateEncryptionKeyManager aggregateEncryptionKeyManager =
                new AggregateEncryptionKeyManager(mDatastoreManager, mFetcher, Clock.systemUTC(),
                        MEASUREMENT_AGGREGATE_ENCRYPTION_KEY_COORDINATOR_URL);
        List<AggregateEncryptionKey> providedKeys =
                aggregateEncryptionKeyManager.getAggregateEncryptionKeys(NUM_KEYS_REQUESTED);
        assertTrue("aggregationEncryptionKeyManager.getAggregateEncryptionKeys returned "
                + "unexpected results:" + AggregateEncryptionKeyTestUtil.prettify(providedKeys),
                AggregateEncryptionKeyTestUtil.isSuperset(getExpectedKeys(), providedKeys)
                        && providedKeys.size() == NUM_KEYS_REQUESTED);
        verify(mFetcher, never()).fetch(any(Uri.class), anyLong());
    }

    @Test
    public void testReturnsEmptyListWhenFetchFails() throws Exception {
        // Mock the datastore to return an empty list.
        doAnswer((Answer<Optional<List<AggregateEncryptionKey>>>)
                invocation -> Optional.of(new ArrayList<>()))
                        .when(mDatastoreManager).runInTransactionWithResult(any());
        // Mock the fetcher as failing.
        doReturn(mUrlConnection).when(mFetcher).openUrl(any());
        when(mUrlConnection.getResponseCode()).thenReturn(400);
        AggregateEncryptionKeyManager aggregateEncryptionKeyManager =
                new AggregateEncryptionKeyManager(mDatastoreManager, mFetcher, Clock.systemUTC(),
                        MEASUREMENT_AGGREGATE_ENCRYPTION_KEY_COORDINATOR_URL);
        List<AggregateEncryptionKey> providedKeys =
                aggregateEncryptionKeyManager.getAggregateEncryptionKeys(NUM_KEYS_REQUESTED);
        assertTrue("aggregationEncryptionKeyManager.getAggregateEncryptionKeys returned "
                + "unexpected results:" + AggregateEncryptionKeyTestUtil.prettify(providedKeys),
                        providedKeys.isEmpty());
    }

    private static List<AggregateEncryptionKey> getExpectedKeys() {
        List<AggregateEncryptionKey> result = new ArrayList<>();
        result.add(new AggregateEncryptionKey.Builder()
                .setKeyId(AggregateEncryptionKeyTestUtil.DEFAULT_KEY_1.KEY_ID)
                .setPublicKey(AggregateEncryptionKeyTestUtil.DEFAULT_KEY_1.PUBLIC_KEY)
                .setExpiry(AggregateEncryptionKeyTestUtil.DEFAULT_EXPIRY)
                .build());
        result.add(new AggregateEncryptionKey.Builder()
                .setKeyId(AggregateEncryptionKeyTestUtil.DEFAULT_KEY_2.KEY_ID)
                .setPublicKey(AggregateEncryptionKeyTestUtil.DEFAULT_KEY_2.PUBLIC_KEY)
                .setExpiry(AggregateEncryptionKeyTestUtil.DEFAULT_EXPIRY)
                .build());
        return result;
    }
}
