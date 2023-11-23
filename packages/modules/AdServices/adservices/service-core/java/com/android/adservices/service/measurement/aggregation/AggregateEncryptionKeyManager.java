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

import android.net.Uri;

import com.android.adservices.LogUtil;
import com.android.adservices.data.measurement.DatastoreManager;
import com.android.adservices.service.AdServicesConfig;
import com.android.internal.annotations.VisibleForTesting;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/** A public key used to encrypt aggregatable reports. */
public final class AggregateEncryptionKeyManager {
    private final DatastoreManager mDatastoreManager;
    private final AggregateEncryptionKeyFetcher mAggregateEncryptionKeyFetcher;
    private final Clock mClock;
    private final Uri mAggregateEncryptionKeyCoordinatorUrl;

    public AggregateEncryptionKeyManager(DatastoreManager datastoreManager) {
        mDatastoreManager = datastoreManager;
        mAggregateEncryptionKeyFetcher = new AggregateEncryptionKeyFetcher();
        mClock = Clock.systemUTC();
        String encryptionKeyCoordinatorUrl =
                AdServicesConfig.getMeasurementAggregateEncryptionKeyCoordinatorUrl();
        if (encryptionKeyCoordinatorUrl != null) {
            mAggregateEncryptionKeyCoordinatorUrl = Uri.parse(encryptionKeyCoordinatorUrl);
        } else {
            mAggregateEncryptionKeyCoordinatorUrl = null;
        }
    }

    @VisibleForTesting
    AggregateEncryptionKeyManager(DatastoreManager datastoreManager,
            AggregateEncryptionKeyFetcher aggregateEncryptionKeyFetcher,
            Clock clock,
            Uri aggregateEncryptionKeyCoordinatorUrl) {
        mDatastoreManager = datastoreManager;
        mAggregateEncryptionKeyFetcher = aggregateEncryptionKeyFetcher;
        mClock = clock;
        mAggregateEncryptionKeyCoordinatorUrl = aggregateEncryptionKeyCoordinatorUrl;
    }

    /**
     * Retrieves a {@link List<AggregateEncryptionKey>} in which the size of the collection matches
     * the numKeys specified in the parameters. If no keys are found, the collection would be empty.
     */
    public List<AggregateEncryptionKey> getAggregateEncryptionKeys(int numKeys) {
        if (mAggregateEncryptionKeyCoordinatorUrl == null) {
            LogUtil.w("Fetching aggregate encryption keys failed, empty coordinator url.");
            return Collections.emptyList();
        }

        long eventTime = mClock.millis();

        Optional<List<AggregateEncryptionKey>> aggregateEncryptionKeysOptional =
                mDatastoreManager.runInTransactionWithResult((dao) ->
                        dao.getNonExpiredAggregateEncryptionKeys(eventTime));

        List<AggregateEncryptionKey> aggregateEncryptionKeys =
                aggregateEncryptionKeysOptional.orElse(new ArrayList<>());

        // If no non-expired keys are available (or the datastore retrieval failed), fetch them
        // over the network, insert them in the datastore and delete expired keys.
        if (aggregateEncryptionKeys.size() == 0) {
            Optional<List<AggregateEncryptionKey>> fetchResult =
                    mAggregateEncryptionKeyFetcher.fetch(
                            mAggregateEncryptionKeyCoordinatorUrl, eventTime);
            if (fetchResult.isPresent()) {
                aggregateEncryptionKeys = fetchResult.get();
                for (AggregateEncryptionKey aggregateEncryptionKey : aggregateEncryptionKeys) {
                    mDatastoreManager.runInTransaction((dao) ->
                            dao.insertAggregateEncryptionKey(aggregateEncryptionKey));
                }
                mDatastoreManager.runInTransaction((dao) ->
                        dao.deleteExpiredAggregateEncryptionKeys(eventTime));
            } else {
                LogUtil.d("Fetching aggregate encryption keys over the network failed.");
            }
        }

        return getRandomListOfKeys(aggregateEncryptionKeys, numKeys);
    }

    @VisibleForTesting
    List<AggregateEncryptionKey> getRandomListOfKeys(
            List<AggregateEncryptionKey> aggregateEncryptionKeys, int numKeys) {
        List<AggregateEncryptionKey> result = new ArrayList<>();
        Random random = new Random();
        int numAvailableKeys = aggregateEncryptionKeys.size();
        if (numAvailableKeys > 0) {
            for (int i = 0; i < numKeys; i++) {
                int index = random.nextInt(numAvailableKeys);
                result.add(aggregateEncryptionKeys.get(index));
            }
        }
        return result;
    }
}
