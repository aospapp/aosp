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

package android.car.hardware.property;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.car.annotation.ApiRequirements;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Collections;
import java.util.List;

/**
 * Represents area ID specific configuration information for a vehicle property.
 *
 * @param <T> matches the type for the {@link android.car.hardware.CarPropertyConfig}.
 */
public final class AreaIdConfig<T> implements Parcelable {
    @ApiRequirements(
            minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @NonNull
    public static final Parcelable.Creator<AreaIdConfig<Object>> CREATOR = getCreator();

    private final int mAreaId;
    @Nullable private final T mMinValue;
    @Nullable private final T mMaxValue;
    private final List<T> mSupportedEnumValues;

    private AreaIdConfig(
            int areaId, @Nullable T minValue, @Nullable T maxValue, List<T> supportedEnumValues) {
        mAreaId = areaId;
        mMinValue = minValue;
        mMaxValue = maxValue;
        mSupportedEnumValues = supportedEnumValues;
    }

    @SuppressWarnings("unchecked")
    private AreaIdConfig(Parcel in) {
        mAreaId = in.readInt();
        mMinValue = (T) in.readValue(getClass().getClassLoader());
        mMaxValue = (T) in.readValue(getClass().getClassLoader());
        mSupportedEnumValues = in.readArrayList(getClass().getClassLoader());
    }

    private static <E> Parcelable.Creator<AreaIdConfig<E>> getCreator() {
        return new Creator<AreaIdConfig<E>>() {
            @Override
            public AreaIdConfig<E> createFromParcel(Parcel source) {
                return new AreaIdConfig<>(source);
            }

            @Override
            @SuppressWarnings("unchecked")
            public AreaIdConfig<E>[] newArray(int size) {
                AreaIdConfig<E>[] areaIdConfigs = new AreaIdConfig[size];
                for (int i = 0; i < size; i++) {
                    areaIdConfigs[i] = null;
                }
                return areaIdConfigs;
            }
        };
    }

    /**
     * @return area ID for this configuration.
     */
    @ApiRequirements(
            minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public int getAreaId() {
        return mAreaId;
    }

    /**
     * @return minimum value supported for the {@link #getAreaId()}. Will return {@code null} if no
     *     minimum value supported.
     */
    @Nullable
    @ApiRequirements(
            minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public T getMinValue() {
        return mMinValue;
    }

    /**
     * @return maximum value supported for the {@link #getAreaId()}. Will return {@code null} if no
     *     maximum value supported.
     */
    @Nullable
    @ApiRequirements(
            minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public T getMaxValue() {
        return mMaxValue;
    }

    /**
     * Returns the supported enum values for the {@link #getAreaId()}. If list is empty, the
     * property does not support an enum.
     */
    @NonNull
    @ApiRequirements(
            minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public List<T> getSupportedEnumValues() {
        return Collections.unmodifiableList(mSupportedEnumValues);
    }

    @Override
    @ApiRequirements(
            minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public int describeContents() {
        return 0;
    }

    @Override
    @ApiRequirements(
            minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAreaId);
        dest.writeValue(mMinValue);
        dest.writeValue(mMaxValue);
        dest.writeList(mSupportedEnumValues);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AreaIdConfig{").append("mAreaId=").append(mAreaId);
        if (mMinValue != null) {
            sb.append(", mMinValue=").append(mMinValue);
        }
        if (mMaxValue != null) {
            sb.append(", mMaxValue=").append(mMaxValue);
        }
        if (!mSupportedEnumValues.isEmpty()) {
            sb.append(", mSupportedEnumValues=").append(mSupportedEnumValues);
        }
        return sb.append("}").toString();
    }

    /**
     * @param <T> matches the type for the {@link android.car.hardware.CarPropertyConfig}.
     * @hide
     */
    @SystemApi
    public static final class Builder<T> {
        private final int mAreaId;
        private T mMinValue = null;
        private T mMaxValue = null;
        private List<T> mSupportedEnumValues = Collections.EMPTY_LIST;

        public Builder(int areaId) {
            mAreaId = areaId;
        }

        /** Set the min value for the {@link AreaIdConfig}. */
        @NonNull
        @ApiRequirements(
                minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public Builder<T> setMinValue(T minValue) {
            mMinValue = minValue;
            return this;
        }

        /** Set the max value for the {@link AreaIdConfig}. */
        @NonNull
        @ApiRequirements(
                minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public Builder<T> setMaxValue(T maxValue) {
            mMaxValue = maxValue;
            return this;
        }

        /** Set the supported enum values for the {@link AreaIdConfig}. */
        @NonNull
        @ApiRequirements(
                minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public Builder<T> setSupportedEnumValues(@NonNull List<T> supportedEnumValues) {
            mSupportedEnumValues = supportedEnumValues;
            return this;
        }

        /** Builds a new {@link android.car.hardware.property.AreaIdConfig}. */
        @NonNull
        @ApiRequirements(
                minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public AreaIdConfig<T> build() {
            return new AreaIdConfig<>(mAreaId, mMinValue, mMaxValue, mSupportedEnumValues);
        }
    }
}
