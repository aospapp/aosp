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

package com.android.adservices.service.common;

import android.annotation.NonNull;
import android.content.Context;

import com.android.adservices.LoggerFactory;
import com.android.adservices.data.adselection.AdSelectionDatabase;
import com.android.adservices.data.adselection.AdSelectionEntryDao;
import com.android.adservices.service.FlagsFactory;
import com.android.internal.annotations.VisibleForTesting;

import java.time.Clock;
import java.time.Instant;

/** Utility class to perform Fledge maintenance tasks */
public class FledgeMaintenanceTasksWorker {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();
    @NonNull private AdSelectionEntryDao mAdSelectionEntryDao;

    @VisibleForTesting
    public FledgeMaintenanceTasksWorker(AdSelectionEntryDao adSelectionEntryDao) {
        mAdSelectionEntryDao = adSelectionEntryDao;
    }

    private FledgeMaintenanceTasksWorker(Context context) {
        mAdSelectionEntryDao = AdSelectionDatabase.getInstance(context).adSelectionEntryDao();
    }

    /** Creates a new instance of {@link FledgeMaintenanceTasksWorker}. */
    public static FledgeMaintenanceTasksWorker create(@NonNull Context context) {
        return new FledgeMaintenanceTasksWorker(context);
    }

    /**
     * Clears all entries in the {@code ad_selection} table that are older than {@code
     * expirationTime}. Then, clears all expired entries in the {@code buyer_decision_logic} as well
     * as the {@code registered_ad_interactions} table.
     */
    public void clearExpiredAdSelectionData() {
        Instant expirationTime =
                Clock.systemUTC()
                        .instant()
                        .minusSeconds(FlagsFactory.getFlags().getAdSelectionExpirationWindowS());
        sLogger.v("Clearing expired Ad Selection data");
        mAdSelectionEntryDao.removeExpiredAdSelection(expirationTime);

        sLogger.v("Clearing expired Buyer Decision Logic data ");
        mAdSelectionEntryDao.removeExpiredBuyerDecisionLogic();

        sLogger.v("Clearing expired Registered Ad Interaction data ");
        mAdSelectionEntryDao.removeExpiredRegisteredAdInteractions();
    }
}
