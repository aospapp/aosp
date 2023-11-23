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

package com.android.adservices.service.stats;

import android.annotation.NonNull;

import java.util.Objects;

/** class for measurement wipeout stats. */
public class MeasurementWipeoutStats {
    private int mCode;
    private int mWipeoutType;

    public MeasurementWipeoutStats() {}

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof MeasurementWipeoutStats)) {
            return false;
        }
        MeasurementWipeoutStats measurementWipeoutStats = (MeasurementWipeoutStats) obj;
        return mCode == measurementWipeoutStats.getCode()
                && mWipeoutType == measurementWipeoutStats.getWipeoutType();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCode, mWipeoutType);
    }

    public int getCode() {
        return mCode;
    }

    public int getWipeoutType() {
        return mWipeoutType;
    }

    /** Builder for {@link MeasurementWipeoutStats}. */
    public static final class Builder {
        private final MeasurementWipeoutStats mBuilding;

        public Builder() {
            mBuilding = new MeasurementWipeoutStats();
        }

        /** See {@link MeasurementWipeoutStats#getCode()} . */
        public @NonNull MeasurementWipeoutStats.Builder setCode(int code) {
            mBuilding.mCode = code;
            return this;
        }

        /** See {@link MeasurementWipeoutStats#getWipeoutType()} . */
        public @NonNull MeasurementWipeoutStats.Builder setWipeoutType(int wipeoutType) {
            mBuilding.mWipeoutType = wipeoutType;
            return this;
        }
        /** Build the {@link MeasurementWipeoutStats}. */
        public @NonNull MeasurementWipeoutStats build() {
            return mBuilding;
        }
    }
}
