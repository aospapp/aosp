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

import androidx.annotation.NonNull;

/** Location information. */
public class LocationInfo {
    // Time in milliseconds.
    public long timeMillis = 0;

    // Latitude.
    public double latitude = 0;

    // Longitude.
    public double longitude = 0;

    // Location provider values.
    public enum LocationProvider {
        UNKNOWN,
        GPS,
        NETWORK;

        /**
         * The converter from ordinal to enum.
         * @param source the ordinal
         * @return enum
         */
        public static LocationProvider fromInteger(int source) {
            switch (source) {
                case 1:
                    return GPS;
                case 2:
                    return NETWORK;
                default:
                    return UNKNOWN;
            }
        }
    };

    // Location provider.
    public LocationProvider provider = LocationProvider.UNKNOWN;

    // Whether the location source is precise.
    public boolean isPreciseLocation = false;

    public LocationInfo() { }

    // Deep copy constructor.
    public LocationInfo(@NonNull LocationInfo other) {
        this.timeMillis = other.timeMillis;
        this.latitude = other.latitude;
        this.longitude = other.longitude;
        this.provider = other.provider;
        this.isPreciseLocation = other.isPreciseLocation;
    }

    // Constructor for LocationInfo.
    public LocationInfo(@NonNull long timeMillis,
            @NonNull double latitude,
            @NonNull double longitude,
            @NonNull LocationProvider provider,
            @NonNull boolean isPrecise) {
        this.timeMillis = timeMillis;
        this.latitude = latitude;
        this.longitude = longitude;
        this.provider = provider;
        this.isPreciseLocation = isPrecise;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof LocationInfo)) {
            return false;
        }
        LocationInfo other = (LocationInfo) o;
        return this.latitude == other.latitude && this.longitude == other.longitude;
    }

    @Override
    public int hashCode() {
        int hash = 17;
        hash = hash * 31 + Double.valueOf(latitude).hashCode();
        hash = hash * 31 + Double.valueOf(longitude).hashCode();
        return hash;
    }
}
