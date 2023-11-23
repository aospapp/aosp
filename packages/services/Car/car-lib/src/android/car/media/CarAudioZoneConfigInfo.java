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

package android.car.media;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;
import static com.android.car.internal.util.VersionUtils.assertPlatformVersionAtLeastU;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.car.annotation.ApiRequirements;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * Class to encapsulate car audio zone configuration information.
 *
 * @hide
 */
@SystemApi
@ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
        minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
public final class CarAudioZoneConfigInfo implements Parcelable {

    private final String mName;
    private final int mZoneId;
    private final int mConfigId;

    /**
     * Constructor of car audio zone configuration info
     *
     * @param name Name for car audio zone configuration info
     * @param zoneId Id of car audio zone
     * @param configId Id of car audio zone configuration info
     *
     * @hide
     */
    public CarAudioZoneConfigInfo(String name, int zoneId, int configId) {
        mName = Objects.requireNonNull(name, "Zone configuration name can not be null");
        mZoneId = zoneId;
        mConfigId = configId;
    }

    /**
     * Creates zone configuration info from parcel
     *
     * @hide
     */
    @VisibleForTesting
    public CarAudioZoneConfigInfo(Parcel in) {
        mName = in.readString();
        mZoneId = in.readInt();
        mConfigId = in.readInt();
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @NonNull
    public static final Creator<CarAudioZoneConfigInfo> CREATOR = new Creator<>() {
        @Override
        @NonNull
        public CarAudioZoneConfigInfo createFromParcel(@NonNull Parcel in) {
            return new CarAudioZoneConfigInfo(in);
        }

        @Override
        @NonNull
        public CarAudioZoneConfigInfo[] newArray(int size) {
            return new CarAudioZoneConfigInfo[size];
        }
    };

    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    @Override
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public int describeContents() {
        return 0;
    }

    /**
     * Returns the car audio zone configuration name
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @NonNull
    public String getName() {
        assertPlatformVersionAtLeastU();
        return mName;
    }

    /**
     * Returns the car audio zone id
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public int getZoneId() {
        assertPlatformVersionAtLeastU();
        return mZoneId;
    }

    /**
     * Returns the car audio zone configuration id
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public int getConfigId() {
        assertPlatformVersionAtLeastU();
        return mConfigId;
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    @Override
    public String toString() {
        return new StringBuilder().append("CarAudioZoneConfigInfo { .name = ").append(mName)
                .append(", zone id = ").append(mZoneId).append(" config id = ").append(mConfigId)
                .append(" }").toString();
    }

    @Override
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeInt(mZoneId);
        dest.writeInt(mConfigId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof CarAudioZoneConfigInfo)) {
            return false;
        }

        CarAudioZoneConfigInfo that = (CarAudioZoneConfigInfo) o;

        return mName.equals(that.mName) && mZoneId == that.mZoneId
                && mConfigId == that.mConfigId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mName, mZoneId, mConfigId);
    }
}
