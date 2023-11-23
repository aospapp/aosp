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

package com.android.adservices.data.adselection;

import static android.adservices.adselection.AdSelectionOutcome.UNSET_AD_SELECTION_ID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;

import androidx.room.ColumnInfo;
import androidx.room.Embedded;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.android.internal.util.Preconditions;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * This POJO represents the AdSelection data in the ad_selection table entity.
 *
 * @hide
 */
@Entity(
        tableName = "ad_selection",
        indices = {@Index(value = {"bidding_logic_uri"})})
public final class DBAdSelection {
    @ColumnInfo(name = "ad_selection_id")
    @PrimaryKey
    private final long mAdSelectionId;

    @Embedded(prefix = "custom_audience_signals_")
    @Nullable
    private final CustomAudienceSignals mCustomAudienceSignals;

    @ColumnInfo(name = "contextual_signals")
    @NonNull
    private final String mContextualSignals;

    @ColumnInfo(name = "bidding_logic_uri")
    @Nullable
    private final Uri mBiddingLogicUri;

    @ColumnInfo(name = "winning_ad_render_uri")
    @NonNull
    private final Uri mWinningAdRenderUri;

    @ColumnInfo(name = "winning_ad_bid")
    private final double mWinningAdBid;

    @ColumnInfo(name = "creation_timestamp")
    @NonNull
    private final Instant mCreationTimestamp;

    @ColumnInfo(name = "caller_package_name")
    @NonNull
    private final String mCallerPackageName;

    @ColumnInfo(name = "ad_counter_keys")
    @Nullable
    private final Set<String> mAdCounterKeys;

    public DBAdSelection(
            long adSelectionId,
            @Nullable CustomAudienceSignals customAudienceSignals,
            @NonNull String contextualSignals,
            @Nullable Uri biddingLogicUri,
            @NonNull Uri winningAdRenderUri,
            double winningAdBid,
            @NonNull Instant creationTimestamp,
            @NonNull String callerPackageName,
            @Nullable Set<String> adCounterKeys) {
        this.mAdSelectionId = adSelectionId;
        this.mCustomAudienceSignals = customAudienceSignals;
        this.mContextualSignals = contextualSignals;
        this.mBiddingLogicUri = biddingLogicUri;
        this.mWinningAdRenderUri = winningAdRenderUri;
        this.mWinningAdBid = winningAdBid;
        this.mCreationTimestamp = creationTimestamp;
        this.mCallerPackageName = callerPackageName;
        this.mAdCounterKeys = adCounterKeys;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o instanceof DBAdSelection) {
            DBAdSelection adSelection = (DBAdSelection) o;

            return mAdSelectionId == adSelection.mAdSelectionId
                    && Objects.equals(mCustomAudienceSignals, adSelection.mCustomAudienceSignals)
                    && mContextualSignals.equals(adSelection.mContextualSignals)
                    && Objects.equals(mBiddingLogicUri, adSelection.mBiddingLogicUri)
                    && Objects.equals(mWinningAdRenderUri, adSelection.mWinningAdRenderUri)
                    && mWinningAdBid == adSelection.mWinningAdBid
                    && Objects.equals(mCreationTimestamp, adSelection.mCreationTimestamp)
                    && mCallerPackageName.equals(adSelection.mCallerPackageName)
                    && Objects.equals(mAdCounterKeys, adSelection.mAdCounterKeys);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mAdSelectionId,
                mCustomAudienceSignals,
                mContextualSignals,
                mBiddingLogicUri,
                mWinningAdRenderUri,
                mWinningAdBid,
                mCreationTimestamp,
                mCallerPackageName,
                mAdCounterKeys);
    }

    /**
     * @return the unique ad selection identifier for this ad selection.
     */
    public long getAdSelectionId() {
        return mAdSelectionId;
    }

    /**
     * @return the custom audience signals used to select this winning ad if remarketing ads, o.w.
     *     return null.
     */
    @Nullable
    public CustomAudienceSignals getCustomAudienceSignals() {
        return mCustomAudienceSignals;
    }

    /**
     * @return the contextual signals i.e. application name used in this ad selection.
     */
    @NonNull
    public String getContextualSignals() {
        return mContextualSignals;
    }

    /**
     * @return the biddingLogicUri that is used to fetch the generateBid() and the reportResults().
     */
    @Nullable
    public Uri getBiddingLogicUri() {
        return mBiddingLogicUri;
    }

    /** @return the rendering URI of the winning ad in this ad selection. */
    @NonNull
    public Uri getWinningAdRenderUri() {
        return mWinningAdRenderUri;
    }

    /**
     * @return the bid generated for the winning ad in this ad selection.
     */
    public double getWinningAdBid() {
        return mWinningAdBid;
    }

    /**
     * @return the creation time of this ad selection in the local storage.
     */
    @NonNull
    public Instant getCreationTimestamp() {
        return mCreationTimestamp;
    }

    /** @return the caller's package name for this ad selection. */
    @NonNull
    public String getCallerPackageName() {
        return mCallerPackageName;
    }

    /** @return the winning ad's set of counter keys */
    @Nullable
    public Set<String> getAdCounterKeys() {
        return mAdCounterKeys;
    }

    /** Builder for {@link DBAdSelection} object. */
    public static final class Builder {
        private long mAdSelectionId = UNSET_AD_SELECTION_ID;
        private CustomAudienceSignals mCustomAudienceSignals;
        private String mContextualSignals;
        private Uri mBiddingLogicUri;
        private Uri mWinningAdRenderUri;
        private double mWinningAdBid;
        private Instant mCreationTimestamp;
        private String mCallerPackageName;
        private Set<String> mAdCounterKeys;

        public Builder() {}

        /** Sets the ad selection id. */
        @NonNull
        public DBAdSelection.Builder setAdSelectionId(long adSelectionId) {
            Preconditions.checkArgument(
                    adSelectionId != UNSET_AD_SELECTION_ID, "Ad selection Id should not be zero.");
            this.mAdSelectionId = adSelectionId;
            return this;
        }

        /** Sets the custom audience signals. */
        @NonNull
        public DBAdSelection.Builder setCustomAudienceSignals(
                @Nullable CustomAudienceSignals customAudienceSignals) {
            this.mCustomAudienceSignals = customAudienceSignals;
            return this;
        }

        /** Sets the contextual signals with this ad selection. */
        @NonNull
        public DBAdSelection.Builder setContextualSignals(@NonNull String contextualSignals) {
            Objects.requireNonNull(contextualSignals);
            this.mContextualSignals = contextualSignals;
            return this;
        }

        /**
         * Sets the buyer-provided biddingLogicUri that is used to fetch the generateBid() and
         * reportResults() javascript.
         */
        @NonNull
        public DBAdSelection.Builder setBiddingLogicUri(@Nullable Uri biddingLogicUri) {
            this.mBiddingLogicUri = biddingLogicUri;
            return this;
        }

        /** Sets the winning ad's rendering URI for this AdSelection. */
        @NonNull
        public DBAdSelection.Builder setWinningAdRenderUri(@NonNull Uri mWinningAdRenderUri) {
            Objects.requireNonNull(mWinningAdRenderUri);
            this.mWinningAdRenderUri = mWinningAdRenderUri;
            return this;
        }

        /** Sets the winning ad's bid for this AdSelection. */
        @NonNull
        public DBAdSelection.Builder setWinningAdBid(double winningAdBid) {
            Preconditions.checkArgument(
                    winningAdBid > 0, "A winning ad should not have non-positive bid.");
            this.mWinningAdBid = winningAdBid;
            return this;
        }

        /** Sets the creation time of this ad selection in the table. */
        @NonNull
        public DBAdSelection.Builder setCreationTimestamp(@NonNull Instant creationTimestamp) {
            Objects.requireNonNull(creationTimestamp);
            this.mCreationTimestamp = creationTimestamp;
            return this;
        }

        /** Sets the app package name of the calling sdk in this ad selection. */
        @NonNull
        public DBAdSelection.Builder setCallerPackageName(@NonNull String callerPackageName) {
            Objects.requireNonNull(callerPackageName);
            this.mCallerPackageName = callerPackageName;
            return this;
        }

        /**
         * Sets the winning ad's set of counter keys, which are used to update ad counter histograms
         * for frequency cap filtering.
         */
        @NonNull
        public Builder setAdCounterKeys(@NonNull Set<String> adCounterKeys) {
            if (adCounterKeys == null || adCounterKeys.isEmpty()) {
                mAdCounterKeys = null;
            } else {
                mAdCounterKeys = adCounterKeys;
            }
            return this;
        }

        /**
         * Builds an {@link DBAdSelection} instance.
         *
         * @throws NullPointerException if any non-null params are null.
         * @throws IllegalArgumentException if adSelectionId is zero , the bid is non-positive, or
         *     if exactly {@code mCustomAudienceSignals} or {@code mBuyerDecisionLogicJs} is null
         */
        @NonNull
        public DBAdSelection build() {
            Preconditions.checkArgument(
                    mAdSelectionId != UNSET_AD_SELECTION_ID, "Ad selection Id should not be zero.");
            Preconditions.checkArgument(
                    mWinningAdBid > 0, "A winning ad should not have non-positive bid.");
            Preconditions.checkArgument(
                    mBiddingLogicUri != null, "Buyer decision logic uri should not be null.");
            Objects.requireNonNull(mContextualSignals);
            Objects.requireNonNull(mWinningAdRenderUri);
            Objects.requireNonNull(mCreationTimestamp);
            Objects.requireNonNull(mCallerPackageName);

            return new DBAdSelection(
                    mAdSelectionId,
                    mCustomAudienceSignals,
                    mContextualSignals,
                    mBiddingLogicUri,
                    mWinningAdRenderUri,
                    mWinningAdBid,
                    mCreationTimestamp,
                    mCallerPackageName,
                    mAdCounterKeys);
        }
    }
}
