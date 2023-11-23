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

import com.android.adservices.LoggerFactory;
import com.android.adservices.data.adselection.AppInstallDao;
import com.android.adservices.data.adselection.FrequencyCapDao;
import com.android.adservices.service.Flags;
import com.android.adservices.service.common.BinderFlagReader;

import java.time.Clock;

/** Factory for implementations of the ad filtering feature interfaces. */
public final class AdFilteringFeatureFactory {

    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();
    private final boolean mIsFledgeAdSelectionFilteringEnabled;
    private final AppInstallDao mAppInstallDao;
    private final FrequencyCapDao mFrequencyCapDao;

    public AdFilteringFeatureFactory(
            AppInstallDao appInstallDao, FrequencyCapDao frequencyCapDao, Flags flags) {
        mIsFledgeAdSelectionFilteringEnabled =
                BinderFlagReader.readFlag(flags::getFledgeAdSelectionFilteringEnabled);

        mAppInstallDao = appInstallDao;
        mFrequencyCapDao = frequencyCapDao;
        sLogger.v(
                "Initializing AdFilteringFeatureFactory with filtering %s",
                mIsFledgeAdSelectionFilteringEnabled ? "enabled" : "disabled");
    }

    /**
     * Returns the correct {@link AdFilterer} implementation to use based on the given {@link
     * Flags}.
     *
     * @return an instance of {@link AdFiltererImpl} if ad selection filtering is enabled and an
     *     instance of {@link AdFiltererNoOpImpl} otherwise
     */
    public AdFilterer getAdFilterer() {
        if (mIsFledgeAdSelectionFilteringEnabled) {
            return new AdFiltererImpl(mAppInstallDao, mFrequencyCapDao, Clock.systemUTC());
        } else {
            return new AdFiltererNoOpImpl();
        }
    }

    /**
     * Gets the {@link AdCounterKeyCopier} implementation to use, dependent on whether the ad
     * filtering features is enabled.
     *
     * @return an {@link AdCounterKeyCopierImpl} instance if the ad filtering feature is enabled, or
     *     an {@link AdCounterKeyCopierNoOpImpl} instance otherwise
     */
    public AdCounterKeyCopier getAdCounterKeyCopier() {
        if (mIsFledgeAdSelectionFilteringEnabled) {
            return new AdCounterKeyCopierImpl();
        } else {
            return new AdCounterKeyCopierNoOpImpl();
        }
    }
}
