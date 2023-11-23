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

package com.android.ondevicepersonalization.services.data.user;

/**
 * A singleton class that stores all privacy signals of the user in memory.
 * TODO (b/272075982): make it as a part of ODP flags.
 */
public final class PrivacySignal {
    public static PrivacySignal sPrivacySignal = null;
    private boolean mKidStatusEnabled;
    private boolean mLimitedAdsTrackingEnabled;

    private PrivacySignal() {
        // Assume the more privacy-safe option until updated.
        mKidStatusEnabled = true;
        mLimitedAdsTrackingEnabled = true;
    }

    /** Returns an instance of PrivacySignal. */
    public static PrivacySignal getInstance() {
        synchronized (PrivacySignal.class) {
            if (sPrivacySignal == null) {
                sPrivacySignal = new PrivacySignal();
            }
            return sPrivacySignal;
        }
    }

    public void setKidStatusEnabled(boolean kidStatusEnabled) {
        mKidStatusEnabled = kidStatusEnabled;
    }

    public boolean isKidStatusEnabled() {
        return mKidStatusEnabled;
    }

    public void setLimitedAdsTrackingEnabled(boolean limitedAdsTrackingEnabled) {
        mLimitedAdsTrackingEnabled = limitedAdsTrackingEnabled;
    }

    public boolean isLimitedAdsTrackingEnabled() {
        return mLimitedAdsTrackingEnabled;
    }
}
