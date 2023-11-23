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

import android.adservices.common.FrequencyCapFilters;
import android.annotation.NonNull;

import com.android.adservices.LoggerFactory;
import com.android.adservices.data.adselection.AdSelectionEntryDao;
import com.android.adservices.data.adselection.DBAdSelectionHistogramInfo;
import com.android.adservices.data.adselection.FrequencyCapDao;

import com.google.common.base.Preconditions;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Implementation for an {@link AdCounterHistogramUpdater} which actually updates the histograms for
 * given ad events.
 */
public class AdCounterHistogramUpdaterImpl implements AdCounterHistogramUpdater {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();
    private final AdSelectionEntryDao mAdSelectionEntryDao;
    private final FrequencyCapDao mFrequencyCapDao;
    private final int mAbsoluteMaxHistogramEventCount;
    private final int mLowerMaxHistogramEventCount;

    public AdCounterHistogramUpdaterImpl(
            @NonNull AdSelectionEntryDao adSelectionEntryDao,
            @NonNull FrequencyCapDao frequencyCapDao,
            int absoluteMaxHistogramEventCount,
            int lowerMaxHistogramEventCount) {
        Objects.requireNonNull(adSelectionEntryDao);
        Objects.requireNonNull(frequencyCapDao);
        Preconditions.checkArgument(absoluteMaxHistogramEventCount > 0);
        Preconditions.checkArgument(lowerMaxHistogramEventCount > 0);
        Preconditions.checkArgument(absoluteMaxHistogramEventCount > lowerMaxHistogramEventCount);

        mAdSelectionEntryDao = adSelectionEntryDao;
        mFrequencyCapDao = frequencyCapDao;
        mAbsoluteMaxHistogramEventCount = absoluteMaxHistogramEventCount;
        mLowerMaxHistogramEventCount = lowerMaxHistogramEventCount;
    }

    @Override
    public void updateWinHistogram(@NonNull AdScoringOutcome outcome) {
        // TODO(b/274171719): Implement win-type event updates
    }

    @Override
    public void updateNonWinHistogram(
            long adSelectionId,
            @NonNull String callerPackageName,
            @FrequencyCapFilters.AdEventType int adEventType,
            @NonNull Instant eventTimestamp) {
        Objects.requireNonNull(callerPackageName);
        Preconditions.checkArgument(
                adEventType != FrequencyCapFilters.AD_EVENT_TYPE_WIN
                        && adEventType != FrequencyCapFilters.AD_EVENT_TYPE_INVALID);
        Objects.requireNonNull(eventTimestamp);

        DBAdSelectionHistogramInfo histogramInfo =
                mAdSelectionEntryDao.getAdSelectionHistogramInfo(adSelectionId, callerPackageName);
        if (histogramInfo == null) {
            sLogger.v(
                    "No ad selection with ID %s and caller package name %s found",
                    adSelectionId, callerPackageName);
            return;
        }

        Set<String> adCounterKeys = histogramInfo.getAdCounterKeys();
        if (adCounterKeys == null || adCounterKeys.isEmpty()) {
            sLogger.v(
                    "No ad counter keys associated with ad selection with ID %s and caller package"
                            + " name %s",
                    adSelectionId, callerPackageName);
            return;
        }

        HistogramEvent.Builder eventBuilder =
                HistogramEvent.builder()
                        .setAdEventType(adEventType)
                        .setBuyer(histogramInfo.getBuyer())
                        .setTimestamp(eventTimestamp);

        sLogger.v("Inserting %d histogram events", adCounterKeys.size());
        for (String key : adCounterKeys) {
            // TODO(b/276528814): Insert in bulk instead of in multiple transactions
            //  and handle eviction only once
            mFrequencyCapDao.insertHistogramEvent(
                    eventBuilder.setAdCounterKey(key).build(),
                    mAbsoluteMaxHistogramEventCount,
                    mLowerMaxHistogramEventCount);
        }
    }
}
