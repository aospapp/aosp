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

package com.android.adservices.service.stats;

import android.annotation.NonNull;

import java.util.Objects;

/** Class for UI Stats. */
public class UIStats {
    private int mCode;
    private int mRegion;
    private int mAction;
    private int mDefaultConsent;
    private int mDefaultAdIdState;
    private int mPrivacySandboxFeatureType;

    public UIStats() {}

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof UIStats)) {
            return false;
        }
        UIStats uiStats = (UIStats) obj;
        return mCode == uiStats.getCode()
                && mRegion == uiStats.getRegion()
                && mAction == uiStats.getAction()
                && mDefaultConsent == uiStats.getDefaultConsent()
                && mDefaultAdIdState == uiStats.getDefaultAdIdState()
                && mPrivacySandboxFeatureType == uiStats.getPrivacySandboxFeatureType();
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mCode,
                mRegion,
                mAction,
                mDefaultConsent,
                mDefaultAdIdState,
                mPrivacySandboxFeatureType);
    }

    @NonNull
    public int getCode() {
        return mCode;
    }

    @NonNull
    public int getRegion() {
        return mRegion;
    }

    @NonNull
    public int getAction() {
        return mAction;
    }

    @NonNull
    public int getDefaultConsent() {
        return mDefaultConsent;
    }

    @NonNull
    public int getDefaultAdIdState() {
        return mDefaultAdIdState;
    }

    @NonNull
    public int getPrivacySandboxFeatureType() {
        return mPrivacySandboxFeatureType;
    }

    public void setRegion(int region) {
        mRegion = region;
    }

    public void setAction(int action) {
        mAction = action;
    }

    public void setDefaultConsent(int defaultConsent) {
        mDefaultConsent = defaultConsent;
    }

    public void setDefaultAdIdState(int defaultAdIdState) {
        mDefaultAdIdState = defaultAdIdState;
    }

    @Override
    public String toString() {
        return "UIStats{"
                + "mCode="
                + mCode
                + ", mRegion="
                + mRegion
                + ", mAction="
                + mAction
                + ", mDefaultConsent="
                + mDefaultConsent
                + ", mDefaultAdIdState="
                + mDefaultAdIdState
                + ", mPrivacySandboxFeatureType"
                + mPrivacySandboxFeatureType
                + '}';
    }

    /** Builder for {@link UIStats}. */
    public static final class Builder {
        private final UIStats mBuilding;

        public Builder() {
            mBuilding = new UIStats();
        }

        /** See {@link UIStats#getCode()} . */
        public @NonNull UIStats.Builder setCode(int code) {
            mBuilding.mCode = code;
            return this;
        }

        /** See {@link UIStats#getRegion()} . */
        public @NonNull UIStats.Builder setRegion(int region) {
            mBuilding.mRegion = region;
            return this;
        }

        /** See {@link UIStats#getAction()} . */
        public @NonNull UIStats.Builder setAction(int action) {
            mBuilding.mAction = action;
            return this;
        }

        /** See {@link UIStats#getAction()} . */
        public @NonNull UIStats.Builder setDefaultConsent(int defaultConsent) {
            mBuilding.mDefaultConsent = defaultConsent;
            return this;
        }

        /** See {@link UIStats#getAction()} . */
        public @NonNull UIStats.Builder setDefaultAdIdState(int defaultAdIdState) {
            mBuilding.mDefaultAdIdState = defaultAdIdState;
            return this;
        }

        /** See {@link UIStats#getAction()} . */
        public @NonNull UIStats.Builder setPrivacySandboxFeatureType(
                int privacySandboxFeatureType) {
            mBuilding.mPrivacySandboxFeatureType = privacySandboxFeatureType;
            return this;
        }
        /** Build the {@link UIStats}. */
        public @NonNull UIStats build() {
            return mBuilding;
        }
    }
}
