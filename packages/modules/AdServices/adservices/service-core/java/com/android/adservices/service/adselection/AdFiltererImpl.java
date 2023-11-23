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

package com.android.adservices.service.adselection;

import android.adservices.adselection.AdWithBid;
import android.adservices.adselection.ContextualAds;
import android.adservices.common.AdTechIdentifier;
import android.adservices.common.FrequencyCapFilters;
import android.adservices.common.KeyedFrequencyCap;
import android.annotation.NonNull;

import com.android.adservices.LoggerFactory;
import com.android.adservices.data.adselection.AppInstallDao;
import com.android.adservices.data.adselection.FrequencyCapDao;
import com.android.adservices.data.common.DBAdData;
import com.android.adservices.data.customaudience.DBCustomAudience;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Holds filters to remove ads from the selectAds auction. */
public final class AdFiltererImpl implements AdFilterer {

    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();
    @NonNull private final Clock mClock;
    @NonNull private final AppInstallDao mAppInstallDao;
    @NonNull private final FrequencyCapDao mFrequencyCapDao;

    public AdFiltererImpl(
            @NonNull AppInstallDao appInstallDao,
            @NonNull FrequencyCapDao frequencyCapDao,
            @NonNull Clock clock) {
        Objects.requireNonNull(appInstallDao);
        Objects.requireNonNull(frequencyCapDao);
        Objects.requireNonNull(clock);
        mAppInstallDao = appInstallDao;
        mFrequencyCapDao = frequencyCapDao;
        mClock = clock;
    }

    /**
     * Takes a list of CAs and returns an identical list with any ads that should be filtered
     * removed.
     *
     * <p>Note that some of the copying to the new list is shallow, so the original list should not
     * be re-used after the method is called.
     *
     * @param cas A list of CAs to filter ads for.
     * @return A list of cas identical to the cas input, but with any ads that should be filtered
     *     removed.
     */
    @Override
    public List<DBCustomAudience> filterCustomAudiences(List<DBCustomAudience> cas) {
        List<DBCustomAudience> toReturn = new ArrayList<>();
        Instant currentTime = mClock.instant();
        sLogger.v("Applying filters to %d CAs with current time %s.", cas.size(), currentTime);
        int totalAds = 0;
        int remainingAds = 0;
        for (DBCustomAudience ca : cas) {
            List<DBAdData> filteredAds = new ArrayList<>();
            totalAds += ca.getAds().size();
            for (DBAdData ad : ca.getAds()) {
                if (doesAdPassFilters(
                        ad, ca.getBuyer(), ca.getOwner(), ca.getName(), currentTime)) {
                    filteredAds.add(ad);
                }
            }
            if (!filteredAds.isEmpty()) {
                toReturn.add(new DBCustomAudience.Builder(ca).setAds(filteredAds).build());
                remainingAds += filteredAds.size();
            }
        }
        sLogger.v(
                "Filtering finished. %d CAs of the original %d remain. "
                        + "%d Ads of the original %d remain.",
                toReturn.size(), cas.size(), remainingAds, totalAds);
        return toReturn;
    }

    /**
     * Takes in a {@link ContextualAds} object and filters out ads from it that should not be in the
     * auction
     *
     * @param contextualAds An object containing contextual ads corresponding to a buyer
     * @return A list of object identical to the input, but without any ads that should be filtered
     */
    @Override
    public ContextualAds filterContextualAds(ContextualAds contextualAds) {
        List<AdWithBid> toReturn = new ArrayList<>();
        Instant currentTime = mClock.instant();
        sLogger.v(
                "Applying filters to %d contextual ads with current time %s.",
                contextualAds.getAdsWithBid().size(), currentTime);
        for (AdWithBid ad : contextualAds.getAdsWithBid()) {
            DBAdData dbAdData =
                    new DBAdData(
                            ad.getAdData().getRenderUri(),
                            ad.getAdData().getMetadata(),
                            ad.getAdData().getAdCounterKeys(),
                            ad.getAdData().getAdFilters());
            if (doesAdPassFilters(dbAdData, contextualAds.getBuyer(), null, null, currentTime)) {
                toReturn.add(ad);
            }
        }
        sLogger.v(
                "Filtering finished. %d contextual ads of the original %d remain.",
                toReturn.size(), contextualAds.getAdsWithBid().size());
        return new ContextualAds.Builder()
                .setAdsWithBid(toReturn)
                .setDecisionLogicUri(contextualAds.getDecisionLogicUri())
                .setBuyer(contextualAds.getBuyer())
                .build();
    }

    private boolean doesAdPassFilters(
            DBAdData ad,
            AdTechIdentifier buyer,
            String customAudienceOwner,
            String customAudienceName,
            Instant currentTime) {
        if (ad.getAdFilters() == null) {
            return true;
        }
        return doesAdPassAppInstallFilters(ad, buyer)
                && doesAdPassFrequencyCapFilters(
                        ad, buyer, customAudienceOwner, customAudienceName, currentTime);
    }

    private boolean doesAdPassAppInstallFilters(DBAdData ad, AdTechIdentifier buyer) {
        /* This could potentially be optimized by grouping the ads by package name before running
         * the queries, but unless the DB cache is playing poorly with these queries there might
         * not be a major performance improvement.
         */
        if (ad.getAdFilters().getAppInstallFilters() == null) {
            return true;
        }
        for (String packageName : ad.getAdFilters().getAppInstallFilters().getPackageNames()) {
            if (mAppInstallDao.canBuyerFilterPackage(buyer, packageName)) {
                return false;
            }
        }
        return true;
    }

    private boolean doesAdPassFrequencyCapFilters(
            DBAdData ad,
            AdTechIdentifier buyer,
            String customAudienceOwner,
            String customAudienceName,
            Instant currentTime) {
        if (ad.getAdFilters().getFrequencyCapFilters() == null) {
            return true;
        }

        FrequencyCapFilters filters = ad.getAdFilters().getFrequencyCapFilters();

        // TODO(b/265205439): Compare the performance of loading the histograms once for each custom
        //  audience and buyer versus querying for every filter

        // Contextual ads cannot filter on win-typed events
        boolean adIsFromCustomAudience =
                (customAudienceOwner != null) && (customAudienceName != null);
        if (adIsFromCustomAudience
                && !filters.getKeyedFrequencyCapsForWinEvents().isEmpty()
                && !doesAdPassFrequencyCapFiltersForWinType(
                        filters.getKeyedFrequencyCapsForWinEvents(),
                        buyer,
                        customAudienceOwner,
                        customAudienceName,
                        currentTime)) {
            return false;
        }

        if (!filters.getKeyedFrequencyCapsForImpressionEvents().isEmpty()
                && !doesAdPassFrequencyCapFiltersForNonWinType(
                        filters.getKeyedFrequencyCapsForImpressionEvents(),
                        FrequencyCapFilters.AD_EVENT_TYPE_IMPRESSION,
                        buyer,
                        currentTime)) {
            return false;
        }

        if (!filters.getKeyedFrequencyCapsForViewEvents().isEmpty()
                && !doesAdPassFrequencyCapFiltersForNonWinType(
                        filters.getKeyedFrequencyCapsForViewEvents(),
                        FrequencyCapFilters.AD_EVENT_TYPE_VIEW,
                        buyer,
                        currentTime)) {
            return false;
        }

        if (!filters.getKeyedFrequencyCapsForClickEvents().isEmpty()
                && !doesAdPassFrequencyCapFiltersForNonWinType(
                        filters.getKeyedFrequencyCapsForClickEvents(),
                        FrequencyCapFilters.AD_EVENT_TYPE_CLICK,
                        buyer,
                        currentTime)) {
            return false;
        }

        return true;
    }

    private boolean doesAdPassFrequencyCapFiltersForWinType(
            Set<KeyedFrequencyCap> keyedFrequencyCaps,
            AdTechIdentifier buyer,
            String customAudienceOwner,
            String customAudienceName,
            Instant currentTime) {
        for (KeyedFrequencyCap frequencyCap : keyedFrequencyCaps) {
            Instant intervalStartTime =
                    currentTime.minusMillis(frequencyCap.getInterval().toMillis());
            int numEventsSinceStartTime =
                    mFrequencyCapDao.getNumEventsForCustomAudienceAfterTime(
                            frequencyCap.getAdCounterKey(),
                            buyer,
                            customAudienceOwner,
                            customAudienceName,
                            FrequencyCapFilters.AD_EVENT_TYPE_WIN,
                            intervalStartTime);

            if (numEventsSinceStartTime > frequencyCap.getMaxCount()) {
                return false;
            }
        }

        return true;
    }

    private boolean doesAdPassFrequencyCapFiltersForNonWinType(
            Set<KeyedFrequencyCap> keyedFrequencyCaps,
            int adEventType,
            AdTechIdentifier buyer,
            Instant currentTime) {
        for (KeyedFrequencyCap frequencyCap : keyedFrequencyCaps) {
            Instant intervalStartTime =
                    currentTime.minusMillis(frequencyCap.getInterval().toMillis());
            int numEventsSinceStartTime =
                    mFrequencyCapDao.getNumEventsForBuyerAfterTime(
                            frequencyCap.getAdCounterKey(), buyer, adEventType, intervalStartTime);

            if (numEventsSinceStartTime > frequencyCap.getMaxCount()) {
                return false;
            }
        }

        return true;
    }
}
