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

import android.adservices.adselection.ReportInteractionRequest;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.android.adservices.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * Data Access Object interface for access to the local AdSelection data storage.
 *
 * <p>Annotation will generate Room based SQLite Dao implementation.
 */
@Dao
public abstract class AdSelectionEntryDao {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();
    /**
     * Add a new successful ad selection entry into the table ad_selection.
     *
     * @param adSelection is the AdSelection to add to the table ad_selection if the ad_selection_id
     *     not exists.
     */
    // TODO(b/230568647): retry adSelectionId generation in case of collision
    @Insert(onConflict = OnConflictStrategy.ABORT)
    public abstract void persistAdSelection(DBAdSelection adSelection);

    /**
     * Write a buyer decision logic entry into the table buyer_decision_logic.
     *
     * @param buyerDecisionLogic is the BuyerDecisionLogic to write to table buyer_decision_logic.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void persistBuyerDecisionLogic(DBBuyerDecisionLogic buyerDecisionLogic);

    /**
     * Add an ad selection override into the table ad_selection_overrides
     *
     * @param adSelectionOverride is the AdSelectionOverride to add to table ad_selection_overrides.
     *     If a {@link DBAdSelectionOverride} object with the {@code adSelectionConfigId} already
     *     exists, this will replace the existing object.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void persistAdSelectionOverride(DBAdSelectionOverride adSelectionOverride);

    /**
     * Add an ad selection override for Buyers' decision logic
     *
     * @param buyersDecisionLogicOverride is an override for the ad_selection_buyer_logic_overrides
     *     If a {@link DBBuyerDecisionOverride} object with the {@code adSelectionConfigId} already
     *     exists, this will replace the existing object.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void persistBuyersDecisionLogicOverride(
            List<DBBuyerDecisionOverride> buyersDecisionLogicOverride);

    /**
     * Adds a list of registered ad interactions to the table registered_ad_interactions
     *
     * <p>This method is not meant to be used on its own, since it doesn't take into account the
     * maximum size of {@code registered_ad_interactions}. Use {@link
     * #safelyInsertRegisteredAdInteractions(long, List, long, long, int)} instead.
     *
     * @param registeredAdInteractions is the list of {@link DBRegisteredAdInteraction} objects to
     *     write to the table registered_ad_interactions.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract void persistDBRegisteredAdInteractions(
            List<DBRegisteredAdInteraction> registeredAdInteractions);

    /**
     * Checks if there is a row in the ad selection data with the unique key ad_selection_id
     *
     * @param adSelectionId which is the key to query the corresponding ad selection data.
     * @return true if row exists, false otherwise
     */
    @Query(
            "SELECT EXISTS(SELECT 1 FROM ad_selection WHERE ad_selection_id = :adSelectionId LIMIT"
                    + " 1)")
    public abstract boolean doesAdSelectionIdExist(long adSelectionId);

    /**
     * Checks if there is a row in the buyer decision logic data with the unique key
     * bidding_logic_uri
     *
     * @param biddingLogicUri which is the key to query the corresponding buyer decision logic data.
     * @return true if row exists, false otherwise
     */
    @Query(
            "SELECT EXISTS(SELECT 1 FROM buyer_decision_logic WHERE bidding_logic_uri ="
                    + " :biddingLogicUri LIMIT 1)")
    public abstract boolean doesBuyerDecisionLogicExist(Uri biddingLogicUri);

    /**
     * Checks if there is a row in the ad selection override data with the unique key
     * ad_selection_config_id
     *
     * @param adSelectionConfigId which is the key to query the corresponding ad selection override
     *     data.
     * @return true if row exists, false otherwise
     */
    @Query(
            "SELECT EXISTS(SELECT 1 FROM ad_selection_overrides WHERE ad_selection_config_id ="
                    + " :adSelectionConfigId AND app_package_name = :appPackageName LIMIT 1)")
    public abstract boolean doesAdSelectionOverrideExistForPackageName(
            String adSelectionConfigId, String appPackageName);

    /**
     * Checks if there is a row in the registered_ad_interactions table that matches the primary key
     * combination of adSelectionId, interactionKey, and destination
     *
     * @param adSelectionId serves as the primary key denoting the ad selection process this entry
     *     id associated with
     * @param interactionKey the interaction key
     * @param destination denotes buyer, seller, etc.
     */
    @Query(
            "SELECT EXISTS(SELECT 1 FROM registered_ad_interactions WHERE ad_selection_id ="
                    + " :adSelectionId AND interaction_key = :interactionKey AND destination"
                    + " = :destination LIMIT 1)")
    public abstract boolean doesRegisteredAdInteractionExist(
            long adSelectionId,
            String interactionKey,
            @ReportInteractionRequest.ReportingDestination int destination);

    /**
     * Get the ad selection entry by its unique key ad_selection_id.
     *
     * @param adSelectionId which is the key to query the corresponding ad selection entry.
     * @return an {@link DBAdSelectionEntry} if exists.
     */
    @Query(
            "SELECT ad_selection.ad_selection_id as ad_selection_id,"
                + " ad_selection.custom_audience_signals_owner as custom_audience_signals_owner,"
                + " ad_selection.custom_audience_signals_buyer as custom_audience_signals_buyer,"
                + " ad_selection.custom_audience_signals_name as custom_audience_signals_name,"
                + " ad_selection.custom_audience_signals_activation_time as"
                + " custom_audience_signals_activation_time,"
                + " ad_selection.custom_audience_signals_expiration_time as"
                + " custom_audience_signals_expiration_time,"
                + " ad_selection.custom_audience_signals_user_bidding_signals as"
                + " custom_audience_signals_user_bidding_signals, ad_selection.contextual_signals"
                + " as contextual_signals,ad_selection.winning_ad_render_uri as"
                + " winning_ad_render_uri,ad_selection.winning_ad_bid as"
                + " winning_ad_bid,ad_selection.creation_timestamp as"
                + " creation_timestamp,buyer_decision_logic.buyer_decision_logic_js as"
                + " buyer_decision_logic_js, ad_selection.bidding_logic_uri as bidding_logic_uri"
                + " FROM ad_selection LEFT JOIN buyer_decision_logic ON"
                + " ad_selection.bidding_logic_uri = buyer_decision_logic.bidding_logic_uri WHERE"
                + " ad_selection.ad_selection_id = :adSelectionId")
    public abstract DBAdSelectionEntry getAdSelectionEntityById(long adSelectionId);

    /**
     * Get the ad selection entries with a batch of ad_selection_ids.
     *
     * @param adSelectionIds are the list of keys to query the corresponding ad selection entries.
     * @return ad selection entries if exists.
     */
    @Query(
            "SELECT ad_selection.ad_selection_id AS"
                + " ad_selection_id,ad_selection.custom_audience_signals_owner as"
                + " custom_audience_signals_owner, ad_selection.custom_audience_signals_buyer as"
                + " custom_audience_signals_buyer, ad_selection.custom_audience_signals_name as"
                + " custom_audience_signals_name,"
                + " ad_selection.custom_audience_signals_activation_time as"
                + " custom_audience_signals_activation_time,"
                + " ad_selection.custom_audience_signals_expiration_time as"
                + " custom_audience_signals_expiration_time,"
                + " ad_selection.custom_audience_signals_user_bidding_signals as"
                + " custom_audience_signals_user_bidding_signals, ad_selection.contextual_signals"
                + " AS contextual_signals,ad_selection.winning_ad_render_uri AS"
                + " winning_ad_render_uri,ad_selection.winning_ad_bid AS winning_ad_bid,"
                + " ad_selection.creation_timestamp as creation_timestamp,"
                + " buyer_decision_logic.buyer_decision_logic_js AS buyer_decision_logic_js,"
                + " ad_selection.bidding_logic_uri AS bidding_logic_uri FROM ad_selection LEFT"
                + " JOIN buyer_decision_logic ON ad_selection.bidding_logic_uri ="
                + " buyer_decision_logic.bidding_logic_uri WHERE ad_selection.ad_selection_id IN"
                + " (:adSelectionIds) ")
    public abstract List<DBAdSelectionEntry> getAdSelectionEntities(List<Long> adSelectionIds);

    /**
     * Get the ad selection entries with a batch of ad_selection_ids.
     *
     * @param adSelectionIds are the list of keys to query the corresponding ad selection entries.
     * @return ad selection entries if exists.
     */
    @Query(
            "SELECT ad_selection.ad_selection_id AS"
                + " ad_selection_id,ad_selection.custom_audience_signals_owner as"
                + " custom_audience_signals_owner, ad_selection.custom_audience_signals_buyer as"
                + " custom_audience_signals_buyer, ad_selection.custom_audience_signals_name as"
                + " custom_audience_signals_name,"
                + " ad_selection.custom_audience_signals_activation_time as"
                + " custom_audience_signals_activation_time,"
                + " ad_selection.custom_audience_signals_expiration_time as"
                + " custom_audience_signals_expiration_time,"
                + " ad_selection.custom_audience_signals_user_bidding_signals as"
                + " custom_audience_signals_user_bidding_signals, ad_selection.contextual_signals"
                + " AS contextual_signals,ad_selection.winning_ad_render_uri AS"
                + " winning_ad_render_uri,ad_selection.winning_ad_bid AS winning_ad_bid,"
                + " ad_selection.creation_timestamp as creation_timestamp,"
                + " buyer_decision_logic.buyer_decision_logic_js AS buyer_decision_logic_js,"
                + " ad_selection.bidding_logic_uri AS bidding_logic_uri FROM ad_selection LEFT"
                + " JOIN buyer_decision_logic ON ad_selection.bidding_logic_uri ="
                + " buyer_decision_logic.bidding_logic_uri WHERE ad_selection.ad_selection_id IN"
                + " (:adSelectionIds) AND ad_selection.caller_package_name = :callerPackageName")
    public abstract List<DBAdSelectionEntry> getAdSelectionEntities(
            List<Long> adSelectionIds, String callerPackageName);

    /**
     * Get ad selection JS override by its unique key and the package name of the app that created
     * the override.
     *
     * @return ad selection override result if exists.
     */
    @Query(
            "SELECT decision_logic FROM ad_selection_overrides WHERE ad_selection_config_id ="
                    + " :adSelectionConfigId AND app_package_name = :appPackageName")
    @Nullable
    public abstract String getDecisionLogicOverride(
            String adSelectionConfigId, String appPackageName);

    /**
     * Get ad selection trusted scoring signals override by its unique key and the package name of
     * the app that created the override.
     *
     * @return ad selection override result if exists.
     */
    @Query(
            "SELECT trusted_scoring_signals FROM ad_selection_overrides WHERE"
                    + " ad_selection_config_id = :adSelectionConfigId AND app_package_name ="
                    + " :appPackageName")
    @Nullable
    public abstract String getTrustedScoringSignalsOverride(
            String adSelectionConfigId, String appPackageName);

    /**
     * Get ad selection buyer decision logic override by its unique key and the package name of the
     * app that created the override.
     *
     * @return ad selection override result if exists.
     */
    @Query(
            "SELECT * FROM ad_selection_buyer_logic_overrides WHERE"
                    + " ad_selection_config_id = :adSelectionConfigId AND app_package_name ="
                    + " :appPackageName")
    @Nullable
    public abstract List<DBBuyerDecisionOverride> getBuyersDecisionLogicOverride(
            String adSelectionConfigId, String appPackageName);

    /**
     * Gets the interaction reporting uri that was registered with the primary key combination of
     * {@code adSelectionId}, {@code interactionKey}, and {@code destination}.
     *
     * @return interaction reporting uri if exists.
     */
    @Query(
            "SELECT interaction_reporting_uri FROM registered_ad_interactions WHERE"
                    + " ad_selection_id = :adSelectionId AND interaction_key = :interactionKey AND"
                    + " destination = :destination")
    @Nullable
    public abstract Uri getRegisteredAdInteractionUri(
            long adSelectionId,
            String interactionKey,
            @ReportInteractionRequest.ReportingDestination int destination);

    /**
     * Gets the {@link DBAdSelectionHistogramInfo} representing the histogram information associated
     * with a given ad selection.
     *
     * @return a {@link DBAdSelectionHistogramInfo} containing the histogram info associated with
     *     the ad selection, or {@code null} if no match is found
     */
    @Query(
            "SELECT custom_audience_signals_buyer, ad_counter_keys FROM ad_selection "
                    + "WHERE ad_selection_id = :adSelectionId "
                    + "AND caller_package_name = :callerPackageName")
    @Nullable
    public abstract DBAdSelectionHistogramInfo getAdSelectionHistogramInfo(
            long adSelectionId, @NonNull String callerPackageName);

    /**
     * Clean up expired adSelection entries if it is older than the given timestamp. If
     * creation_timestamp < expirationTime, the ad selection entry will be removed from the
     * ad_selection table.
     *
     * @param expirationTime is the cutoff time to expire the AdSelectionEntry.
     */
    @Query("DELETE FROM ad_selection WHERE creation_timestamp < :expirationTime")
    public abstract void removeExpiredAdSelection(Instant expirationTime);

    /**
     * Clean up selected ad selection data entry data in batch by their ad_selection_ids.
     *
     * @param adSelectionIds is the list of adSelectionIds to identify the data entries to be
     *     removed from ad_selection and buyer_decision_logic tables.
     */
    @Query("DELETE FROM ad_selection WHERE ad_selection_id IN (:adSelectionIds)")
    public abstract void removeAdSelectionEntriesByIds(List<Long> adSelectionIds);

    /**
     * Clean up selected ad selection override data by its {@code adSelectionConfigId}
     *
     * @param adSelectionConfigId is the {@code adSelectionConfigId} to identify the data entries to
     *     be removed from the ad_selection_overrides table.
     */
    @Query(
            "DELETE FROM ad_selection_overrides WHERE ad_selection_config_id = :adSelectionConfigId"
                    + " AND app_package_name = :appPackageName")
    public abstract void removeAdSelectionOverrideByIdAndPackageName(
            String adSelectionConfigId, String appPackageName);

    /**
     * Clean up buyer decision logic override data by its {@code adSelectionConfigId}
     *
     * @param adSelectionConfigId is the {@code adSelectionConfigId} to identify the data entries to
     *     be removed from the ad_selection_overrides table.
     */
    @Query(
            "DELETE FROM ad_selection_buyer_logic_overrides WHERE ad_selection_config_id = "
                    + ":adSelectionConfigId AND app_package_name = :appPackageName")
    public abstract void removeBuyerDecisionLogicOverrideByIdAndPackageName(
            String adSelectionConfigId, String appPackageName);

    /**
     * Clean up buyer_decision_logic entries in batch if the bidding_logic_uri no longer exists in
     * the table ad_selection.
     */
    @Query(
            "DELETE FROM buyer_decision_logic WHERE bidding_logic_uri NOT IN "
                    + "( SELECT DISTINCT bidding_logic_uri "
                    + "FROM ad_selection "
                    + "WHERE bidding_logic_uri is NOT NULL)")
    public abstract void removeExpiredBuyerDecisionLogic();

    /** Clean up all ad selection override data */
    @Query("DELETE FROM ad_selection_overrides WHERE  app_package_name = :appPackageName")
    public abstract void removeAllAdSelectionOverrides(String appPackageName);

    /** Clean up all buyers' decision logic data */
    @Query(
            "DELETE FROM ad_selection_buyer_logic_overrides WHERE  app_package_name ="
                    + " :appPackageName")
    public abstract void removeAllBuyerDecisionOverrides(String appPackageName);

    /**
     * Checks if there is a row in the ad selection data with the unique combination of
     * ad_selection_id and caller_package_name
     *
     * @param adSelectionId which is the key to query the corresponding ad selection data.
     * @param callerPackageName the caller's package name, to be verified against the
     *     calling_package_name that exists in the ad_selection_entry
     * @return true if row exists, false otherwise
     */
    @Query(
            "SELECT EXISTS(SELECT 1 FROM ad_selection WHERE ad_selection_id = :adSelectionId"
                    + " AND caller_package_name = :callerPackageName LIMIT"
                    + " 1)")
    public abstract boolean doesAdSelectionMatchingCallerPackageNameExist(
            long adSelectionId, String callerPackageName);

    /**
     * Add an ad selection from outcomes override into the table
     * ad_selection_from_outcomes_overrides
     *
     * @param adSelectionFromOutcomesOverride is the AdSelectionFromOutcomesOverride to add to table
     *     ad_selection_overrides. If a {@link DBAdSelectionFromOutcomesOverride} object with the
     *     {@code adSelectionConfigFromOutcomesId} already exists, this will replace the existing
     *     object.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void persistAdSelectionFromOutcomesOverride(
            DBAdSelectionFromOutcomesOverride adSelectionFromOutcomesOverride);

    /**
     * Checks if there is a row in the ad selection override data with the unique key
     * ad_selection_from_outcomes_config_id
     *
     * @param adSelectionFromOutcomesConfigId which is the key to query the corresponding ad
     *     selection override data.
     * @return true if row exists, false otherwise
     */
    @Query(
            "SELECT EXISTS(SELECT 1 FROM ad_selection_from_outcomes_overrides WHERE "
                    + "ad_selection_from_outcomes_config_id = "
                    + ":adSelectionFromOutcomesConfigId AND app_package_name = :appPackageName "
                    + "LIMIT 1)")
    public abstract boolean doesAdSelectionFromOutcomesOverrideExistForPackageName(
            String adSelectionFromOutcomesConfigId, String appPackageName);

    /**
     * Get ad selection from outcomes selection logic JS override by its unique key and the package
     * name of the app that created the override.
     *
     * @return ad selection override result if exists.
     */
    @Query(
            "SELECT selection_logic_js FROM ad_selection_from_outcomes_overrides WHERE "
                    + "ad_selection_from_outcomes_config_id = :adSelectionFromOutcomesConfigId "
                    + "AND app_package_name = :appPackageName")
    @Nullable
    public abstract String getSelectionLogicOverride(
            String adSelectionFromOutcomesConfigId, String appPackageName);

    /**
     * Get ad selection from outcomes signals override by its unique key and the package name of the
     * app that created the override.
     *
     * @return ad selection from outcomes override result if exists.
     */
    @Query(
            "SELECT selection_signals FROM ad_selection_from_outcomes_overrides WHERE"
                    + " ad_selection_from_outcomes_config_id = :adSelectionFromOutcomesConfigId "
                    + "AND app_package_name = :appPackageName")
    @Nullable
    public abstract String getSelectionSignalsOverride(
            String adSelectionFromOutcomesConfigId, String appPackageName);

    /**
     * Clean up selected ad selection from outcomes override data by its {@code
     * adSelectionFromOutcomesConfigId}
     *
     * @param adSelectionFromOutcomesConfigId is to identify the data entries to be removed from the
     *     ad_selection_overrides table.
     */
    @Query(
            "DELETE FROM ad_selection_from_outcomes_overrides WHERE "
                    + "ad_selection_from_outcomes_config_id = :adSelectionFromOutcomesConfigId AND "
                    + "app_package_name = :appPackageName")
    public abstract void removeAdSelectionFromOutcomesOverrideByIdAndPackageName(
            String adSelectionFromOutcomesConfigId, String appPackageName);

    /** Clean up all ad selection from outcomes override data */
    @Query(
            "DELETE FROM ad_selection_from_outcomes_overrides WHERE app_package_name = "
                    + ":appPackageName")
    public abstract void removeAllAdSelectionFromOutcomesOverrides(String appPackageName);

    /**
     * Clean up registered_ad_interaction entries in batch if the {@code adSelectionId} no longer
     * exists in the table ad_selection.
     */
    @Query(
            "DELETE FROM registered_ad_interactions WHERE ad_selection_id NOT IN "
                    + "( SELECT DISTINCT ad_selection_id "
                    + "FROM ad_selection "
                    + "WHERE ad_selection_id is NOT NULL)")
    public abstract void removeExpiredRegisteredAdInteractions();

    /** Returns total size of the {@code registered_ad_interaction} table. */
    @Query("SELECT COUNT(*) FROM registered_ad_interactions")
    public abstract long getTotalNumRegisteredAdInteractions();

    /**
     * Returns total number of the {@code registered_ad_interaction}s that match a given {@code
     * adSelectionId} and {@code reportingDestination}.
     */
    @Query(
            "SELECT COUNT(*) FROM registered_ad_interactions WHERE ad_selection_id ="
                    + " :adSelectionId AND destination = :reportingDestination")
    public abstract long getNumRegisteredAdInteractionsPerAdSelectionAndDestination(
            long adSelectionId,
            @ReportInteractionRequest.ReportingDestination int reportingDestination);

    /**
     * Inserts a list of {@link DBRegisteredAdInteraction}s into the database, enforcing these
     * limitations:
     *
     * <p>We will not allow the total size of the {@code registered_ad_interaction} to exceed {@code
     * maxTotalNumRegisteredInteractions}
     *
     * <p>We will not allow the number of registered ad interactions {@code adSelectionId} and
     * {@code reportingDestination} to exceed {@code maxPerDestinationNumRegisteredInteractions}.
     *
     * <p>This transaction is separate in order to minimize the critical region while locking the
     * database.
     */
    @Transaction
    public void safelyInsertRegisteredAdInteractions(
            long adSelectionId,
            @NonNull List<DBRegisteredAdInteraction> registeredAdInteractions,
            long maxTotalNumRegisteredInteractions,
            long maxPerDestinationNumRegisteredInteractions,
            int reportingDestination) {
        long currentNumRegisteredInteractions = getTotalNumRegisteredAdInteractions();

        if (currentNumRegisteredInteractions >= maxTotalNumRegisteredInteractions) {
            sLogger.v("Registered Ad Interaction max table size reached! Skipping entire list.");
            return;
        }

        long currentNumRegisteredInteractionsPerDestination =
                getNumRegisteredAdInteractionsPerAdSelectionAndDestination(
                        adSelectionId, reportingDestination);

        if (currentNumRegisteredInteractionsPerDestination
                >= maxPerDestinationNumRegisteredInteractions) {
            sLogger.v(
                    "Maximum number of Registered Ad Interactions for this adSelectionId and"
                            + " reportingDestination reached! Skipping entire list.");
            return;
        }

        long numAvailableRowsInTable =
                Math.max(0, maxTotalNumRegisteredInteractions - currentNumRegisteredInteractions);

        long numAvailableRowsInTablePerAdSelectionIdAndDestination =
                Math.max(
                        0,
                        maxPerDestinationNumRegisteredInteractions
                                - currentNumRegisteredInteractionsPerDestination);

        int numEntriesToCommit =
                (int)
                        Math.min(
                                numAvailableRowsInTablePerAdSelectionIdAndDestination,
                                Math.min(registeredAdInteractions.size(), numAvailableRowsInTable));
        List<DBRegisteredAdInteraction> registeredAdInteractionsToCommit =
                registeredAdInteractions.subList(0, numEntriesToCommit);

        persistDBRegisteredAdInteractions(registeredAdInteractionsToCommit);
    }
}
