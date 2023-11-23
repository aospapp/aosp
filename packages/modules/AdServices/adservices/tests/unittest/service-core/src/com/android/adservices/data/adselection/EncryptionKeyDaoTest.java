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

package com.android.adservices.data.adselection;

import static com.android.adservices.data.adselection.EncryptionKeyConstants.EncryptionKeyType.ENCRYPTION_KEY_TYPE_AUCTION;
import static com.android.adservices.data.adselection.EncryptionKeyConstants.EncryptionKeyType.ENCRYPTION_KEY_TYPE_JOIN;
import static com.android.adservices.data.adselection.EncryptionKeyConstants.EncryptionKeyType.ENCRYPTION_KEY_TYPE_QUERY;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public class EncryptionKeyDaoTest {
    private static final Context CONTEXT = ApplicationProvider.getApplicationContext();
    private static final Long EXPIRY_TTL_SECONDS = 1209600L;
    private static final DBEncryptionKey ENCRYPTION_KEY_AUCTION =
            DBEncryptionKey.builder()
                    .setKeyIdentifier("key_id_1")
                    .setPublicKey("public_key_1")
                    .setEncryptionKeyType(ENCRYPTION_KEY_TYPE_AUCTION)
                    .setExpiryTtlSeconds(EXPIRY_TTL_SECONDS)
                    .build();

    private static final DBEncryptionKey ENCRYPTION_KEY_JOIN =
            DBEncryptionKey.builder()
                    .setKeyIdentifier("key_id_2")
                    .setPublicKey("public_key_2")
                    .setEncryptionKeyType(ENCRYPTION_KEY_TYPE_JOIN)
                    .setExpiryTtlSeconds(EXPIRY_TTL_SECONDS)
                    .build();

    private static final DBEncryptionKey ENCRYPTION_KEY_QUERY =
            DBEncryptionKey.builder()
                    .setKeyIdentifier("key_id_3")
                    .setPublicKey("public_key_3")
                    .setEncryptionKeyType(ENCRYPTION_KEY_TYPE_QUERY)
                    .setExpiryTtlSeconds(EXPIRY_TTL_SECONDS)
                    .build();
    private static final DBEncryptionKey ENCRYPTION_KEY_AUCTION_TTL_5SECS =
            DBEncryptionKey.builder()
                    .setKeyIdentifier("key_id_4")
                    .setPublicKey("public_key_4")
                    .setEncryptionKeyType(ENCRYPTION_KEY_TYPE_AUCTION)
                    .setExpiryTtlSeconds(5L)
                    .build();

    private static final DBEncryptionKey ENCRYPTION_KEY_JOIN_TTL_5SECS =
            DBEncryptionKey.builder()
                    .setKeyIdentifier("key_id_5")
                    .setPublicKey("public_key_5")
                    .setEncryptionKeyType(ENCRYPTION_KEY_TYPE_JOIN)
                    .setExpiryTtlSeconds(5L)
                    .build();

    private static final DBEncryptionKey ENCRYPTION_KEY_QUERY_TTL_5SECS =
            DBEncryptionKey.builder()
                    .setKeyIdentifier("key_id_6")
                    .setPublicKey("public_key_6")
                    .setEncryptionKeyType(ENCRYPTION_KEY_TYPE_QUERY)
                    .setExpiryTtlSeconds(5L)
                    .build();

    private EncryptionKeyDao mEncryptionKeyDao;

    @Before
    public void setup() {
        mEncryptionKeyDao =
                Room.inMemoryDatabaseBuilder(CONTEXT, AdSelectionEncryptionDatabase.class)
                        .build()
                        .encryptionKeyDao();
    }

    @Test
    public void test_doesKeyOfTypeExists_returnsTrueWhenKeyExists() {
        mEncryptionKeyDao.insertAllKeys(
                ENCRYPTION_KEY_AUCTION, ENCRYPTION_KEY_JOIN, ENCRYPTION_KEY_QUERY);
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_AUCTION))
                .isNotNull();
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_JOIN))
                .isNotNull();
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_QUERY))
                .isNotNull();
    }

    @Test
    public void test_doesKeyOfTypeExists_returnsFalseWhenKeyAbsent() {
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_AUCTION))
                .isNull();
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_JOIN)).isNull();
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_QUERY)).isNull();
    }

    @Test
    public void test_getHighestExpiryKeyOfType_returnsEmptyMapWhenKeyAbsent() {
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_AUCTION))
                .isNull();
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_JOIN)).isNull();
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_QUERY)).isNull();
    }

    @Test
    public void test_getHighestExpiryKeyOfType_returnsFreshestKey() {
        mEncryptionKeyDao.insertAllKeys(
                ENCRYPTION_KEY_AUCTION,
                ENCRYPTION_KEY_JOIN,
                ENCRYPTION_KEY_QUERY,
                ENCRYPTION_KEY_AUCTION_TTL_5SECS,
                ENCRYPTION_KEY_JOIN_TTL_5SECS,
                ENCRYPTION_KEY_QUERY_TTL_5SECS);

        assertThat(
                        mEncryptionKeyDao
                                .getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_AUCTION)
                                .getKeyIdentifier())
                .isEqualTo(ENCRYPTION_KEY_AUCTION.getKeyIdentifier());
        assertThat(
                        mEncryptionKeyDao
                                .getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_QUERY)
                                .getKeyIdentifier())
                .isEqualTo(ENCRYPTION_KEY_QUERY.getKeyIdentifier());
        assertThat(
                        mEncryptionKeyDao
                                .getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_JOIN)
                                .getKeyIdentifier())
                .isEqualTo(ENCRYPTION_KEY_JOIN.getKeyIdentifier());
    }

    @Test
    public void test_getHighestExpiryActiveKeyOfType_returnsEmptyMapWhenKeyAbsent() {
        assertThat(
                        mEncryptionKeyDao.getLatestExpiryActiveKeyOfType(
                                ENCRYPTION_KEY_TYPE_AUCTION, Instant.now()))
                .isNull();
        assertThat(
                        mEncryptionKeyDao.getLatestExpiryActiveKeyOfType(
                                ENCRYPTION_KEY_TYPE_JOIN, Instant.now()))
                .isNull();
        assertThat(
                        mEncryptionKeyDao.getLatestExpiryActiveKeyOfType(
                                ENCRYPTION_KEY_TYPE_QUERY, Instant.now()))
                .isNull();
    }

    @Test
    public void test_getHighestExpiryActiveKeyOfType_returnsFreshestKey() {
        mEncryptionKeyDao.insertAllKeys(
                ENCRYPTION_KEY_AUCTION,
                ENCRYPTION_KEY_JOIN,
                ENCRYPTION_KEY_QUERY,
                ENCRYPTION_KEY_AUCTION_TTL_5SECS,
                ENCRYPTION_KEY_JOIN_TTL_5SECS,
                ENCRYPTION_KEY_QUERY_TTL_5SECS);

        Instant currentInstant = Instant.now().minusSeconds(3600L);
        assertThat(
                        mEncryptionKeyDao
                                .getLatestExpiryActiveKeyOfType(
                                        ENCRYPTION_KEY_TYPE_AUCTION, currentInstant)
                                .getKeyIdentifier())
                .isEqualTo(ENCRYPTION_KEY_AUCTION.getKeyIdentifier());
        assertThat(
                        mEncryptionKeyDao
                                .getLatestExpiryActiveKeyOfType(
                                        ENCRYPTION_KEY_TYPE_QUERY, currentInstant)
                                .getKeyIdentifier())
                .isEqualTo(ENCRYPTION_KEY_QUERY.getKeyIdentifier());
        assertThat(
                        mEncryptionKeyDao
                                .getLatestExpiryActiveKeyOfType(
                                        ENCRYPTION_KEY_TYPE_JOIN, currentInstant)
                                .getKeyIdentifier())
                .isEqualTo(ENCRYPTION_KEY_JOIN.getKeyIdentifier());
    }

    @Test
    public void test_getHighestExpiryNActiveKeyOfType_returnsEmptyMapWhenKeyAbsent() {
        assertThat(
                        mEncryptionKeyDao.getLatestExpiryNActiveKeysOfType(
                                ENCRYPTION_KEY_TYPE_AUCTION, Instant.now(), 2))
                .isEmpty();
        assertThat(
                        mEncryptionKeyDao.getLatestExpiryNActiveKeysOfType(
                                ENCRYPTION_KEY_TYPE_JOIN, Instant.now(), 2))
                .isEmpty();
        assertThat(
                        mEncryptionKeyDao.getLatestExpiryNActiveKeysOfType(
                                ENCRYPTION_KEY_TYPE_QUERY, Instant.now(), 2))
                .isEmpty();
    }

    @Test
    public void test_getHighestExpiryNActiveKeyOfType_returnsNFreshestKey() {
        mEncryptionKeyDao.insertAllKeys(
                ENCRYPTION_KEY_AUCTION,
                ENCRYPTION_KEY_JOIN,
                ENCRYPTION_KEY_QUERY,
                ENCRYPTION_KEY_AUCTION_TTL_5SECS,
                ENCRYPTION_KEY_JOIN_TTL_5SECS,
                ENCRYPTION_KEY_QUERY_TTL_5SECS);

        Instant currentInstant = Instant.now().minusSeconds(3600L);
        assertThat(
                        mEncryptionKeyDao
                                .getLatestExpiryNActiveKeysOfType(
                                        ENCRYPTION_KEY_TYPE_AUCTION, currentInstant, 2)
                                .stream()
                                .map(k -> k.getKeyIdentifier())
                                .collect(Collectors.toSet()))
                .containsExactlyElementsIn(
                        ImmutableList.of(
                                ENCRYPTION_KEY_AUCTION.getKeyIdentifier(),
                                ENCRYPTION_KEY_AUCTION_TTL_5SECS.getKeyIdentifier()));
        assertThat(
                        mEncryptionKeyDao
                                .getLatestExpiryNActiveKeysOfType(
                                        ENCRYPTION_KEY_TYPE_QUERY, currentInstant, 2)
                                .stream()
                                .map(k -> k.getKeyIdentifier())
                                .collect(Collectors.toSet()))
                .containsExactlyElementsIn(
                        ImmutableList.of(
                                ENCRYPTION_KEY_QUERY.getKeyIdentifier(),
                                ENCRYPTION_KEY_QUERY_TTL_5SECS.getKeyIdentifier()));
        assertThat(
                        mEncryptionKeyDao
                                .getLatestExpiryNActiveKeysOfType(
                                        ENCRYPTION_KEY_TYPE_JOIN, currentInstant, 2)
                                .stream()
                                .map(k -> k.getKeyIdentifier())
                                .collect(Collectors.toSet()))
                .containsExactlyElementsIn(
                        ImmutableList.of(
                                ENCRYPTION_KEY_JOIN.getKeyIdentifier(),
                                ENCRYPTION_KEY_JOIN_TTL_5SECS.getKeyIdentifier()));
    }

    @Test
    public void test_getExpiredKeysForType_noExpiredKeys_returnsEmpty() {
        assertThat(
                        mEncryptionKeyDao.getExpiredKeysForType(
                                ENCRYPTION_KEY_TYPE_AUCTION, Instant.now()))
                .isEmpty();
        assertThat(mEncryptionKeyDao.getExpiredKeysForType(ENCRYPTION_KEY_TYPE_JOIN, Instant.now()))
                .isEmpty();
        assertThat(
                        mEncryptionKeyDao.getExpiredKeysForType(
                                ENCRYPTION_KEY_TYPE_QUERY, Instant.now()))
                .isEmpty();
    }

    @Test
    public void test_getExpiredKeysForType_returnsExpiredKeys_success() {
        mEncryptionKeyDao.insertAllKeys(
                ENCRYPTION_KEY_AUCTION,
                ENCRYPTION_KEY_JOIN,
                ENCRYPTION_KEY_QUERY,
                ENCRYPTION_KEY_AUCTION_TTL_5SECS,
                ENCRYPTION_KEY_JOIN_TTL_5SECS,
                ENCRYPTION_KEY_QUERY_TTL_5SECS);

        Instant currentInstant = Instant.now().plusSeconds(5L);
        List<DBEncryptionKey> expiredAuctionKeys =
                mEncryptionKeyDao.getExpiredKeysForType(
                        ENCRYPTION_KEY_TYPE_AUCTION, currentInstant);
        assertThat(expiredAuctionKeys.size()).isEqualTo(1);
        assertThat(expiredAuctionKeys.stream().findFirst().get().getKeyIdentifier())
                .isEqualTo(ENCRYPTION_KEY_AUCTION_TTL_5SECS.getKeyIdentifier());

        List<DBEncryptionKey> expiredJoinKeys =
                mEncryptionKeyDao.getExpiredKeysForType(ENCRYPTION_KEY_TYPE_JOIN, currentInstant);
        assertThat(expiredJoinKeys.size()).isEqualTo(1);
        assertThat(expiredJoinKeys.stream().findFirst().get().getKeyIdentifier())
                .isEqualTo(ENCRYPTION_KEY_JOIN_TTL_5SECS.getKeyIdentifier());

        List<DBEncryptionKey> expiredQueryKeys =
                mEncryptionKeyDao.getExpiredKeysForType(ENCRYPTION_KEY_TYPE_QUERY, currentInstant);
        assertThat(expiredQueryKeys.size()).isEqualTo(1);
        assertThat(expiredQueryKeys.stream().findFirst().get().getKeyIdentifier())
                .isEqualTo(ENCRYPTION_KEY_QUERY_TTL_5SECS.getKeyIdentifier());
    }

    @Test
    public void test_getExpiredKeys_noExpiredKeys_returnsEmpty() {
        mEncryptionKeyDao.insertAllKeys(
                ENCRYPTION_KEY_AUCTION, ENCRYPTION_KEY_JOIN, ENCRYPTION_KEY_QUERY);
        assertThat(mEncryptionKeyDao.getExpiredKeys(Instant.now())).isEmpty();
    }

    @Test
    public void test_getExpiredKeys_returnsExpiredKeys() {
        mEncryptionKeyDao.insertAllKeys(
                ENCRYPTION_KEY_AUCTION,
                ENCRYPTION_KEY_JOIN,
                ENCRYPTION_KEY_QUERY,
                ENCRYPTION_KEY_AUCTION_TTL_5SECS,
                ENCRYPTION_KEY_JOIN_TTL_5SECS,
                ENCRYPTION_KEY_QUERY_TTL_5SECS);

        Instant currentInstant = Instant.now().plusSeconds(5L);
        List<DBEncryptionKey> expiredKeys = mEncryptionKeyDao.getExpiredKeys(currentInstant);

        assertThat(expiredKeys.size()).isEqualTo(3);
        assertThat(expiredKeys.stream().map(k -> k.getKeyIdentifier()).collect(Collectors.toSet()))
                .containsExactly(
                        ENCRYPTION_KEY_AUCTION_TTL_5SECS.getKeyIdentifier(),
                        ENCRYPTION_KEY_JOIN_TTL_5SECS.getKeyIdentifier(),
                        ENCRYPTION_KEY_QUERY_TTL_5SECS.getKeyIdentifier());
    }

    @Test
    public void test_deleteExpiredKeys_noExpiredKeys_returnsZero() {
        mEncryptionKeyDao.insertAllKeys(ENCRYPTION_KEY_AUCTION);
        assertThat(
                        mEncryptionKeyDao.deleteExpiredRowsByType(
                                ENCRYPTION_KEY_TYPE_AUCTION, Instant.now()))
                .isEqualTo(0);
    }

    @Test
    public void test_deleteExpiredKeys_deletesKeysSuccessfully() {
        mEncryptionKeyDao.insertAllKeys(ENCRYPTION_KEY_JOIN, ENCRYPTION_KEY_AUCTION_TTL_5SECS);

        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_AUCTION))
                .isNotNull();

        mEncryptionKeyDao.deleteExpiredRowsByType(
                ENCRYPTION_KEY_TYPE_AUCTION, Instant.now().plusSeconds(10L));

        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_AUCTION))
                .isNull();
    }

    @Test
    public void test_insertAllKeys_validKeys_success() {
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_AUCTION))
                .isNull();
        mEncryptionKeyDao.insertAllKeys(ENCRYPTION_KEY_AUCTION);
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_AUCTION))
                .isNotNull();
    }

    @Test
    public void test_deleteAllEncryptionKeys_success() {
        mEncryptionKeyDao.insertAllKeys(
                ENCRYPTION_KEY_AUCTION, ENCRYPTION_KEY_JOIN, ENCRYPTION_KEY_QUERY);
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_AUCTION))
                .isNotNull();
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_JOIN))
                .isNotNull();
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_QUERY))
                .isNotNull();

        mEncryptionKeyDao.deleteAllEncryptionKeys();

        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_AUCTION))
                .isNull();
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_JOIN)).isNull();
        assertThat(mEncryptionKeyDao.getLatestExpiryKeyOfType(ENCRYPTION_KEY_TYPE_QUERY)).isNull();
    }
}
