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

package androidx.core.uwb.backend.impl.internal;

/** Uwb feature support flags */
public class UwbFeatureFlags {
    private final boolean mSkipRangingCapabilitiesCheck;
    private final boolean mAzimuthSupport;
    private final boolean mElevationSupport;
    private final boolean mReversedMacAddress;

    private UwbFeatureFlags(boolean skipRangingCapabilitiesCheck, boolean azimuthSupport,
            boolean elevationSupport, boolean reversedMacAddress) {
        mSkipRangingCapabilitiesCheck = skipRangingCapabilitiesCheck;
        mAzimuthSupport = azimuthSupport;
        mElevationSupport = elevationSupport;
        mReversedMacAddress = reversedMacAddress;
    }

    public boolean skipRangingCapabilitiesCheck() {
        return mSkipRangingCapabilitiesCheck;
    }

    public boolean hasAzimuthSupport() {
        return mAzimuthSupport;
    }

    public boolean hasElevationSupport() {
        return mElevationSupport;
    }

    public boolean isReversedMacAddress() {
        return mReversedMacAddress;
    }

    /** Builder */
    public static class Builder {
        private boolean mSkipRangingCapabilitiesCheck = false;
        private boolean mAzimuthSupport = false;
        private boolean mElevationSupport = false;
        private boolean mReversedMacAddress = false;

        public UwbFeatureFlags.Builder setSkipRangingCapabilitiesCheck(
                boolean skipRangingCapabilitiesCheck) {
            mSkipRangingCapabilitiesCheck = skipRangingCapabilitiesCheck;
            return this;
        }

        public UwbFeatureFlags.Builder setAzimuthSupport(boolean azimuthSupport) {
            mAzimuthSupport = azimuthSupport;
            return this;
        }

        public UwbFeatureFlags.Builder setElevationSupport(boolean elevationSupport) {
            mElevationSupport = elevationSupport;
            return this;
        }

        public UwbFeatureFlags.Builder setReversedMacAddress(boolean reversedMacAddress) {
            mReversedMacAddress = reversedMacAddress;
            return this;
        }

        public UwbFeatureFlags build() {
            return new UwbFeatureFlags(
                    mSkipRangingCapabilitiesCheck,
                    mAzimuthSupport,
                    mElevationSupport,
                    mReversedMacAddress);
        }
    }
}
