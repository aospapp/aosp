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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;

import androidx.room.ColumnInfo;
import androidx.room.Embedded;
import androidx.room.PrimaryKey;

import com.android.internal.util.Preconditions;

import java.time.Instant;
import java.util.Objects;

/**
 * This POJO represents the ad_selection_entry data that combines the data fields joined from the
 * ad_selection and buyer_decision_logic entities.
 */
public final class DBAdSelectionEntry {
    private static final int UNSET = 0;

    @ColumnInfo(name = "ad_selection_id")
    @PrimaryKey
    private final long mAdSelectionId;

    @Embedded(prefix = "custom_audience_signals_")
    @Nullable
    private final CustomAudienceSignals mCustomAudienceSignals;

    @ColumnInfo(name = "contextual_signals")
    @NonNull
    private final String mContextualSignals;

    @ColumnInfo(name = "winning_ad_render_uri")
    @NonNull
    private final Uri mWinningAdRenderUri;

    @ColumnInfo(name = "winning_ad_bid")
    private final double mWinningAdBid;

    @ColumnInfo(name = "creation_timestamp")
    @NonNull
    private final Instant mCreationTimestamp;

    @ColumnInfo(name = "buyer_decision_logic_js")
    @Nullable
    private final String mBuyerDecisionLogicJs;

    @ColumnInfo(name = "bidding_logic_uri")
    @NonNull
    private final Uri mBiddingLogicUri;

    public DBAdSelectionEntry(
            long adSelectionId,
            @Nullable CustomAudienceSignals customAudienceSignals,
            @NonNull String contextualSignals,
            @NonNull Uri winningAdRenderUri,
            double winningAdBid,
            @NonNull Instant creationTimestamp,
            @Nullable String buyerDecisionLogicJs,
            @NonNull Uri biddingLogicUri) {
        this.mAdSelectionId = adSelectionId;
        this.mCustomAudienceSignals = customAudienceSignals;
        this.mContextualSignals = contextualSignals;
        this.mWinningAdRenderUri = winningAdRenderUri;
        this.mWinningAdBid = winningAdBid;
        this.mCreationTimestamp = creationTimestamp;
        this.mBuyerDecisionLogicJs = buyerDecisionLogicJs;
        this.mBiddingLogicUri = biddingLogicUri;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o instanceof DBAdSelectionEntry) {
            DBAdSelectionEntry adSelectionEntry = (DBAdSelectionEntry) o;

            return mAdSelectionId == adSelectionEntry.mAdSelectionId
                    && Objects.equals(
                            mCustomAudienceSignals, adSelectionEntry.mCustomAudienceSignals)
                    && mContextualSignals.equals(adSelectionEntry.mContextualSignals)
                    && Objects.equals(mWinningAdRenderUri, adSelectionEntry.mWinningAdRenderUri)
                    && mWinningAdBid == adSelectionEntry.mWinningAdBid
                    && Objects.equals(mCreationTimestamp, adSelectionEntry.mCreationTimestamp)
                    && Objects.equals(mBuyerDecisionLogicJs, adSelectionEntry.mBuyerDecisionLogicJs)
                    && Objects.equals(mBiddingLogicUri, adSelectionEntry.mBiddingLogicUri);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mAdSelectionId,
                mCustomAudienceSignals,
                mContextualSignals,
                mWinningAdRenderUri,
                mWinningAdBid,
                mCreationTimestamp,
                mBuyerDecisionLogicJs,
                mBiddingLogicUri);
    }

    /**
     * @return the unique ad selection identifier for this ad_selection_entry.
     */
    public long getAdSelectionId() {
        return mAdSelectionId;
    }

    /**
     * @return the custom audience signals used to select this winning ad if remarketing ads,
     *     otherwise return null.
     */
    @Nullable
    public CustomAudienceSignals getCustomAudienceSignals() {
        return mCustomAudienceSignals;
    }

    /**
     * @return the contextual signals, for instance application name used in this
     *     ad_selection_entry.
     */
    @NonNull
    public String getContextualSignals() {
        return mContextualSignals;
    }

    /** @return the rendering URI of the winning ad in this ad_selection_entry. */
    @NonNull
    public Uri getWinningAdRenderUri() {
        return mWinningAdRenderUri;
    }

    /**
     * @return the bid generated for the winning ad in this ad_selection_entry.
     */
    public double getWinningAdBid() {
        return mWinningAdBid;
    }

    /**
     * @return the creation time of this ad_selection_entry in the local storage.
     */
    @NonNull
    public Instant getCreationTimestamp() {
        return mCreationTimestamp;
    }

    /**
     * @return the buyer-provided generateBid() and reportWin() javascript.
     */
    @Nullable
    public String getBuyerDecisionLogicJs() {
        return mBuyerDecisionLogicJs;
    }

    /** @return the buyer-provided uri for buyer-side logic. */
    @NonNull
    public Uri getBiddingLogicUri() {
        return mBiddingLogicUri;
    }

    /** Builder for {@link DBAdSelectionEntry} object. */
    public static final class Builder {
        private long mAdSelectionId = UNSET;
        private CustomAudienceSignals mCustomAudienceSignals;
        private String mContextualSignals;
        private Uri mWinningAdRenderUri;
        private double mWinningAdBid;
        private Instant mCreationTimestamp;
        private String mBuyerDecisionLogicJs;
        private Uri mBiddingLogicUri;

        public Builder() {}

        /** Sets the ad selection id. */
        @NonNull
        public DBAdSelectionEntry.Builder setAdSelectionId(long adSelectionId) {
            Preconditions.checkArgument(
                    adSelectionId != UNSET, "Ad selection Id should not be zero.");
            this.mAdSelectionId = adSelectionId;
            return this;
        }

        /** Sets the custom audience signals. */
        @NonNull
        public DBAdSelectionEntry.Builder setCustomAudienceSignals(
                @Nullable CustomAudienceSignals customAudienceSignals) {
            this.mCustomAudienceSignals = customAudienceSignals;
            return this;
        }

        /** Sets the contextual signals with this ad_selection_entry. */
        @NonNull
        public DBAdSelectionEntry.Builder setContextualSignals(@NonNull String contextualSignals) {
            Objects.requireNonNull(contextualSignals);
            this.mContextualSignals = contextualSignals;
            return this;
        }

        /** Sets the winning ad's rendering URI for this ad_selection_entry. */
        @NonNull
        public DBAdSelectionEntry.Builder setWinningAdRenderUri(@NonNull Uri mWinningAdRenderUri) {
            Objects.requireNonNull(mWinningAdRenderUri);
            this.mWinningAdRenderUri = mWinningAdRenderUri;
            return this;
        }

        /** Sets the winning ad's bid for this ad_selection_entry. */
        @NonNull
        public DBAdSelectionEntry.Builder setWinningAdBid(double winningAdBid) {
            Preconditions.checkArgument(
                    winningAdBid > 0, "A winning ad should not have non-positive bid.");
            this.mWinningAdBid = winningAdBid;
            return this;
        }

        /** Sets the creation time of this ad_selection_entry in the table. */
        @NonNull
        public DBAdSelectionEntry.Builder setCreationTimestamp(@NonNull Instant creationTimestamp) {
            Objects.requireNonNull(creationTimestamp);
            this.mCreationTimestamp = creationTimestamp;
            return this;
        }

        /** Sets the buyer_decision_logic_js of this ad_selection_entry. */
        @NonNull
        public DBAdSelectionEntry.Builder setBuyerDecisionLogicJs(
                @Nullable String buyerDecisionLogicJs) {
            this.mBuyerDecisionLogicJs = buyerDecisionLogicJs;
            return this;
        }

        /** Sets the buyer_decision_logic_js of this ad_selection_entry. */
        @NonNull
        public DBAdSelectionEntry.Builder setBiddingLogicUri(@NonNull Uri biddingLogicUri) {
            Objects.requireNonNull(biddingLogicUri);
            this.mBiddingLogicUri = biddingLogicUri;
            return this;
        }

        /**
         * Builds an {@link DBAdSelectionEntry} instance.
         *
         * @throws NullPointerException if any non-null params are null.
         * @throws IllegalArgumentException if adSelectionId is zero or bid is non-positive, or if
         *     exactly {@code mCustomAudienceSignals} or {@code mBuyerDecisionLogicJs} is null
         */
        @NonNull
        public DBAdSelectionEntry build() {
            Preconditions.checkArgument(
                    mAdSelectionId != UNSET, "Ad selection Id should not be zero.");
            Preconditions.checkArgument(
                    mWinningAdBid > 0, "A winning ad should not have non-positive bid.");
            boolean oneNull =
                    Objects.isNull(mCustomAudienceSignals) ^ Objects.isNull(mBuyerDecisionLogicJs);
            Preconditions.checkArgument(
                    !oneNull, "Buyer fields must both be null in case of contextual ad.");
            Objects.requireNonNull(mContextualSignals);
            Objects.requireNonNull(mWinningAdRenderUri);
            Objects.requireNonNull(mCreationTimestamp);
            Objects.requireNonNull(mBiddingLogicUri);

            return new DBAdSelectionEntry(
                    mAdSelectionId,
                    mCustomAudienceSignals,
                    mContextualSignals,
                    mWinningAdRenderUri,
                    mWinningAdBid,
                    mCreationTimestamp,
                    mBuyerDecisionLogicJs,
                    mBiddingLogicUri);
        }
    }
}
