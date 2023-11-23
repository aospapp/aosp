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

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.Objects;

/**
 * A public key used to encrypt aggregatable reports.
 */
public final class AggregateEncryptionKey {
    private final String mId;
    private final String mKeyId;
    private final String mPublicKey;
    private final long mExpiry;

    /**
     * Create a new aggregate encryption key object.
     */
    private AggregateEncryptionKey(
            @Nullable String id,
            @NonNull String keyId,
            @NonNull String publicKey,
            @NonNull long expiry) {
        mId = id;
        mKeyId = keyId;
        mPublicKey = publicKey;
        mExpiry = expiry;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof AggregateEncryptionKey)) {
            return false;
        }
        AggregateEncryptionKey key = (AggregateEncryptionKey) obj;
        return Objects.equals(mKeyId, key.mKeyId)
                && Objects.equals(mPublicKey, key.mPublicKey)
                && mExpiry == key.mExpiry;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mKeyId, mPublicKey, mExpiry);
    }

    /**
     * Unique identifier for the {@link AggregateEncryptionKey}.
     */
    public String getId() {
        return mId;
    }

    /**
     * Key ID.
     */
    public String getKeyId() {
        return mKeyId;
    }

    /**
     * Public key.
     */
    public String getPublicKey() {
        return mPublicKey;
    }

    /**
     * Time when the key expires in milliseconds since Unix Epoch.
     */
    public long getExpiry() {
        return mExpiry;
    }

    /**
     * A builder for {@link AggregateEncryptionKey}.
     */
    public static final class Builder {
        private String mId;
        private String mKeyId;
        private String mPublicKey;
        private long mExpiry;

        public Builder() { }

        /**
         * See {@link AggregateEncryptionKey#getId()}.
         */
        public Builder setId(String id) {
            mId = id;
            return this;
        }

        /**
         * See {@link AggregateEncryptionKey#getKeyId}.
         */
        public @NonNull Builder setKeyId(@NonNull String keyId) {
            mKeyId = keyId;
            return this;
        }

        /**
         * See {@link AggregateEncryptionKey#getPublicKey}.
         */
        public @NonNull Builder setPublicKey(@NonNull String publicKey) {
            mPublicKey = publicKey;
            return this;
        }

        /**
         * See {@link AggregateEncryptionKey#getExpiry}.
         */
        public @NonNull Builder setExpiry(@NonNull long expiry) {
            mExpiry = expiry;
            return this;
        }

        /**
         * Build the AggregateEncryptionKey.
         */
        public @NonNull AggregateEncryptionKey build() {
            if (mKeyId == null || mPublicKey == null || mExpiry == 0) {
                throw new IllegalArgumentException("Uninitialized fields");
            }
            return new AggregateEncryptionKey(mId, mKeyId, mPublicKey, mExpiry);
        }
    }
}
