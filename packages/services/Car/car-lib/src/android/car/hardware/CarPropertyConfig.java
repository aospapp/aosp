/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.car.hardware;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.car.VehicleAreaType;
import android.car.VehicleAreaType.VehicleAreaTypeValue;
import android.car.VehiclePropertyIds;
import android.car.annotation.AddedInOrBefore;
import android.car.annotation.ApiRequirements;
import android.car.hardware.property.AreaIdConfig;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents general information about car property such as data type and min/max ranges for car
 * areas (if applicable). This class supposed to be immutable, parcelable and could be passed over.
 *
 * @param <T> refer to {@link Parcel#writeValue(java.lang.Object)} to get a list of all supported
 * types. The class should be visible to framework as default class loader is being used here.
 *
 */
public final class CarPropertyConfig<T> implements Parcelable {
    private final int mAccess;
    private final int mAreaType;
    private final int mChangeMode;
    private final ArrayList<Integer> mConfigArray;
    private final String mConfigString;
    private final float mMaxSampleRate;
    private final float mMinSampleRate;
    private final int mPropertyId;
    private final List<AreaIdConfig<T>> mAreaIdConfigs;
    private final SparseArray<AreaIdConfig<T>> mAreaIdToAreaIdConfig;
    private final Class<T> mType;

    private CarPropertyConfig(int access, int areaType, int changeMode,
            ArrayList<Integer> configArray, String configString,
            float maxSampleRate, float minSampleRate, int propertyId,
            List<AreaIdConfig<T>> areaIdConfigs, Class<T> type) {
        mAccess = access;
        mAreaType = areaType;
        mChangeMode = changeMode;
        mConfigArray = configArray;
        mConfigString = configString;
        mMaxSampleRate = maxSampleRate;
        mMinSampleRate = minSampleRate;
        mPropertyId = propertyId;
        mAreaIdConfigs = areaIdConfigs;
        mAreaIdToAreaIdConfig = generateAreaIdToAreaIdConfig(areaIdConfigs);
        mType = type;
    }

    /** @hide */
    @IntDef(prefix = {"VEHICLE_PROPERTY_ACCESS"}, value = {
        VEHICLE_PROPERTY_ACCESS_NONE,
        VEHICLE_PROPERTY_ACCESS_READ,
        VEHICLE_PROPERTY_ACCESS_WRITE,
        VEHICLE_PROPERTY_ACCESS_READ_WRITE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VehiclePropertyAccessType {}

    /** Property Access Unknown */
    @AddedInOrBefore(majorVersion = 33)
    public static final int VEHICLE_PROPERTY_ACCESS_NONE = 0;
    /** The property is readable */
    @AddedInOrBefore(majorVersion = 33)
    public static final int VEHICLE_PROPERTY_ACCESS_READ = 1;
    /** The property is writable */
    @AddedInOrBefore(majorVersion = 33)
    public static final int VEHICLE_PROPERTY_ACCESS_WRITE = 2;
    /** The property is readable and writable */
    @AddedInOrBefore(majorVersion = 33)
    public static final int VEHICLE_PROPERTY_ACCESS_READ_WRITE = 3;

    /** @hide */
    @IntDef(prefix = {"VEHICLE_PROPERTY_CHANGE_MODE"}, value = {
        VEHICLE_PROPERTY_CHANGE_MODE_STATIC,
        VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE,
        VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VehiclePropertyChangeModeType {}

    /** Properties of this type must never be changed. */
    @AddedInOrBefore(majorVersion = 33)
    public static final int VEHICLE_PROPERTY_CHANGE_MODE_STATIC = 0;
    /** Properties of this type must report when there is a change. */
    @AddedInOrBefore(majorVersion = 33)
    public static final int VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE = 1;
    /** Properties of this type change continuously. */
    @AddedInOrBefore(majorVersion = 33)
    public static final int VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS = 2;

    /**
     * Return the access type of the car property.
     * <p>The access type could be one of the following:
     * <ul>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_ACCESS_NONE}</li>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_ACCESS_READ}</li>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_ACCESS_WRITE}</li>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_ACCESS_READ_WRITE}</li>
     * </ul>
     *
     * @return the access type of the car property.
     */
    @AddedInOrBefore(majorVersion = 33)
    public @VehiclePropertyAccessType int getAccess() {
        return mAccess;
    }

    /**
     * Return the area type of the car property.
     * <p>The area type could be one of the following:
     * <ul>
     *   <li>{@link VehicleAreaType#VEHICLE_AREA_TYPE_GLOBAL}</li>
     *   <li>{@link VehicleAreaType#VEHICLE_AREA_TYPE_WINDOW}</li>
     *   <li>{@link VehicleAreaType#VEHICLE_AREA_TYPE_SEAT}</li>
     *   <li>{@link VehicleAreaType#VEHICLE_AREA_TYPE_DOOR}</li>
     *   <li>{@link VehicleAreaType#VEHICLE_AREA_TYPE_MIRROR}</li>
     *   <li>{@link VehicleAreaType#VEHICLE_AREA_TYPE_WHEEL}</li>
     * </ul>
     *
     * @return the area type of the car property.
     */
    @AddedInOrBefore(majorVersion = 33)
    public @VehicleAreaTypeValue int getAreaType() {
        return mAreaType;
    }

    /**
     * Return the change mode of the car property.
     *
     * <p>The change mode could be one of the following:
     * <ul>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_CHANGE_MODE_STATIC }</li>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE}</li>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS}</li>
     * </ul>
     *
     * @return the change mode of properties.
     */
    @AddedInOrBefore(majorVersion = 33)
    public @VehiclePropertyChangeModeType int getChangeMode() {
        return mChangeMode;
    }

    /**
     *
     * @return Additional configuration parameters. For different properties, configArrays have
     * different information.
     */
    @NonNull
    @AddedInOrBefore(majorVersion = 33)
    public List<Integer> getConfigArray() {
        return Collections.unmodifiableList(mConfigArray);
    }

    /**
     *
     * @return Some properties may require additional information passed over this
     * string. Most properties do not need to set this.
     * @hide
     */
    @AddedInOrBefore(majorVersion = 33)
    public String getConfigString() {
        return mConfigString;
    }

    /**
     *
     * @return Max sample rate in Hz. Must be defined for {@link
     * #VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS} return 0 if change mode is not continuous.
     */
    @AddedInOrBefore(majorVersion = 33)
    public float getMaxSampleRate() {
        return mMaxSampleRate;
    }

    /**
     *
     * @return Min sample rate in Hz.Must be defined for {@link
     * #VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS} return 0 if change mode is not continuous.
     */
    @AddedInOrBefore(majorVersion = 33)
    public float getMinSampleRate() {
        return mMinSampleRate;
    }

    /**
     * @return Property identifier, must be one of enums in
     *   {@link android.car.VehiclePropertyIds}.
     */
    @AddedInOrBefore(majorVersion = 33)
    public int getPropertyId() {
        return mPropertyId;
    }

    /**
     * Returns the value type of the vehicle property.
     * <p>The value type could be one of the following:
     * <ul>
     *   <li>Boolean</li>
     *   <li>Float</li>
     *   <li>Float[]</li>
     *   <li>Integer</li>
     *   <li>Integer[]</li>
     *   <li>Long</li>
     *   <li>Long[]</li>
     *   <li>String</li>
     *   <li>byte[]</li>
     *   <li>Object[]</li>
     * </ul>
     *
     * @return the value type of the vehicle property.
     */
    @NonNull
    @AddedInOrBefore(majorVersion = 33)
    public Class<T> getPropertyType() {
        return mType;
    }

    /**
     * @return list of {@link AreaIdConfig} instances for this property.
     */
    @NonNull
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public List<AreaIdConfig<T>> getAreaIdConfigs() {
        return Collections.unmodifiableList(mAreaIdConfigs);
    }

    /**
     * @return {@link AreaIdConfig} instance for passed {@code areaId}
     * @throws IllegalArgumentException if {@code areaId} is not supported for property
     */
    @NonNull
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public AreaIdConfig<T> getAreaIdConfig(int areaId) {
        if (!mAreaIdToAreaIdConfig.contains(areaId)) {
            throw new IllegalArgumentException("Area ID: " + Integer.toHexString(areaId)
                    + " is not supported for property ID: " + VehiclePropertyIds.toString(
                    mPropertyId));
        }
        return mAreaIdToAreaIdConfig.get(areaId);
    }

    /**
     * @return true if this property is area type {@link VehicleAreaType#VEHICLE_AREA_TYPE_GLOBAL}.
     */
    @AddedInOrBefore(majorVersion = 33)
    public boolean isGlobalProperty() {
        return mAreaType == VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL;
    }

    /**
     *
     * @return the number of areaIds for properties.
     * @hide
     */
    @AddedInOrBefore(majorVersion = 33)
    public int getAreaCount() {
        return mAreaIdConfigs.size();
    }

    /**
     * Returns a list of area IDs supported for the vehicle property.
     *
     * <p>An area represents a unique element of a {@link VehicleAreaType}. For instance, if the
     * {@link VehicleAreaType} is {@link VehicleAreaType#VEHICLE_AREA_TYPE_WINDOW}, then an example
     * area is {@link android.car.VehicleAreaWindow#WINDOW_FRONT_WINDSHIELD}.
     *
     * <p>An area ID is a combination of one or more areas, and is created by bitwise "OR"ing the
     * areas together. Areas from different {@link VehicleAreaType} values will not be mixed in a
     * single area ID. For example, a {@link android.car.VehicleAreaWindow} area cannot be combined
     * with a {@link android.car.VehicleAreaSeat} area in an area ID.
     *
     * <p>For properties that return {@link VehicleAreaType#VEHICLE_AREA_TYPE_GLOBAL} for {@link
     * #getAreaType()}, they only support a single area ID of {@code 0}.
     *
     * <p>Rules for mapping a non {@link VehicleAreaType#VEHICLE_AREA_TYPE_GLOBAL} property to area
     * IDs:
     * <ul>
     *  <li>A property is mapped to a set of area IDs that are impacted when the property value
     *  changes.
     *  <li>An area cannot be part of multiple area IDs, it will only be part of a single area ID.
     *  <li>When the property value changes in one of the areas in an area ID, then it will
     *  automatically change in all other areas in the area ID.
     *  <li>The property value will be independently controllable in any two different area IDs.
     * </ul>
     *
     * @return the array of supported area IDs.
     */
    @NonNull
    @AddedInOrBefore(majorVersion = 33)
    public int[] getAreaIds() {
        int[] areaIds = new int[mAreaIdConfigs.size()];
        for (int i = 0; i < areaIds.length; i++) {
            areaIds[i] = mAreaIdConfigs.get(i).getAreaId();
        }
        return areaIds;
    }

    /**
     * @return  the first areaId.
     * Throws {@link java.lang.IllegalStateException} if supported area count not equals to one.
     * @hide
     */
    @AddedInOrBefore(majorVersion = 33)
    public int getFirstAndOnlyAreaId() {
        if (mAreaIdConfigs.size() != 1) {
            throw new IllegalStateException("Expected one and only area in this property. PropId: "
                    + VehiclePropertyIds.toString(mPropertyId));
        }
        return mAreaIdConfigs.get(0).getAreaId();
    }

    /**
     *
     * @param areaId
     * @return true if areaId is existing.
     * @hide
     */
    @AddedInOrBefore(majorVersion = 33)
    public boolean hasArea(int areaId) {
        return mAreaIdToAreaIdConfig.indexOfKey(areaId) >= 0;
    }

    /**
     * @deprecated - use {@link #getAreaIdConfigs()} or {@link #getAreaIdConfig(int)} instead.
     *
     * @param areaId
     * @return Min value in given areaId. Null if not have min value in given area.
     */
    @Deprecated
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public T getMinValue(int areaId) {
        AreaIdConfig<T> areaIdConfig = mAreaIdToAreaIdConfig.get(areaId);
        return areaIdConfig == null ? null : areaIdConfig.getMinValue();
    }

    /**
     * @deprecated - use {@link #getAreaIdConfigs()} or {@link #getAreaIdConfig(int)} instead.
     *
     * @param areaId
     * @return Max value in given areaId. Null if not have max value in given area.
     */
    @Deprecated
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public T getMaxValue(int areaId) {
        AreaIdConfig<T> areaIdConfig = mAreaIdToAreaIdConfig.get(areaId);
        return areaIdConfig == null ? null : areaIdConfig.getMaxValue();
    }

    /**
     * @deprecated - use {@link #getAreaIdConfigs()} or {@link #getAreaIdConfig(int)} instead.
     *
     * @return Min value in areaId 0. Null if not have min value.
     */
    @Deprecated
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public T getMinValue() {
        AreaIdConfig<T> areaIdConfig = mAreaIdToAreaIdConfig.get(0);
        return areaIdConfig == null ? null : areaIdConfig.getMinValue();
    }

    /**
     * @deprecated - use {@link #getAreaIdConfigs()} or {@link #getAreaIdConfig(int)} instead.
     *
     * @return Max value in areaId 0. Null if not have max value.
     */
    @Deprecated
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public T getMaxValue() {
        AreaIdConfig<T> areaIdConfig = mAreaIdToAreaIdConfig.get(0);
        return areaIdConfig == null ? null : areaIdConfig.getMaxValue();
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    @AddedInOrBefore(majorVersion = 33)
    public int describeContents() {
        return 0;
    }

    @Override
    @AddedInOrBefore(majorVersion = 33)
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mAccess);
        dest.writeInt(mAreaType);
        dest.writeInt(mChangeMode);
        dest.writeInt(mConfigArray.size());
        for (int i = 0; i < mConfigArray.size(); i++) {
            dest.writeInt(mConfigArray.get(i));
        }
        dest.writeString(mConfigString);
        dest.writeFloat(mMaxSampleRate);
        dest.writeFloat(mMinSampleRate);
        dest.writeInt(mPropertyId);
        dest.writeList(mAreaIdConfigs);
        dest.writeString(mType.getName());
    }

    @SuppressWarnings("unchecked")
    private CarPropertyConfig(Parcel in) {
        mAccess = in.readInt();
        mAreaType = in.readInt();
        mChangeMode = in.readInt();
        int configArraySize = in.readInt();
        mConfigArray = new ArrayList<Integer>(configArraySize);
        for (int i = 0; i < configArraySize; i++) {
            mConfigArray.add(in.readInt());
        }
        mConfigString = in.readString();
        mMaxSampleRate = in.readFloat();
        mMinSampleRate = in.readFloat();
        mPropertyId = in.readInt();
        mAreaIdConfigs = in.readArrayList(getClass().getClassLoader());
        mAreaIdToAreaIdConfig = generateAreaIdToAreaIdConfig(mAreaIdConfigs);
        String className = in.readString();
        try {
            mType = (Class<T>) Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Class not found: " + className, e);
        }
    }

    @AddedInOrBefore(majorVersion = 33)
    public static final Creator<CarPropertyConfig> CREATOR = new Creator<CarPropertyConfig>() {
        @Override
        public CarPropertyConfig createFromParcel(Parcel in) {
            return new CarPropertyConfig(in);
        }

        @Override
        public CarPropertyConfig[] newArray(int size) {
            return new CarPropertyConfig[size];
        }
    };

    /** @hide */
    @Override
    public String toString() {
        return "CarPropertyConfig{"
                + "mPropertyId=" + VehiclePropertyIds.toString(mPropertyId)
                + ", mAccess=" + mAccess
                + ", mAreaType=" + mAreaType
                + ", mChangeMode=" + mChangeMode
                + ", mConfigArray=" + mConfigArray
                + ", mConfigString=" + mConfigString
                + ", mMaxSampleRate=" + mMaxSampleRate
                + ", mMinSampleRate=" + mMinSampleRate
                + ", mAreaIdConfigs =" + mAreaIdConfigs
                + ", mType=" + mType
                + '}';
    }

    /**
     * @deprecated This API is deprecated in favor of {@link
     * android.car.hardware.property.AreaIdConfig} which allows properties to specify which enum
     * values are supported. This API will be marked as {@code @removed} in the next API release and
     * then fully removed in two API releases.
     *
     * <p>Represents min/max value of car property.
     * @param <T> The property type
     * @hide
     */
    @Deprecated
    public static class AreaConfig<T> implements Parcelable {
        @Nullable private final T mMinValue;
        @Nullable private final T mMaxValue;

        @SuppressWarnings("unused")
        private AreaConfig(T minValue, T maxValue) {
            mMinValue = minValue;
            mMaxValue = maxValue;
        }

        @AddedInOrBefore(majorVersion = 33)
        public static final Parcelable.Creator<AreaConfig<Object>> CREATOR =
                getCreator(Object.class);

        private static <E> Parcelable.Creator<AreaConfig<E>> getCreator(final Class<E> clazz) {
            return new Creator<AreaConfig<E>>() {
                @Override
                public AreaConfig<E> createFromParcel(Parcel source) {
                    return new AreaConfig<>(source);
                }

                @Override @SuppressWarnings("unchecked")
                public AreaConfig<E>[] newArray(int size) {
                    return (AreaConfig<E>[]) Array.newInstance(clazz, size);
                }
            };
        }

        @SuppressWarnings("unchecked")
        private AreaConfig(Parcel in) {
            mMinValue = (T) in.readValue(getClass().getClassLoader());
            mMaxValue = (T) in.readValue(getClass().getClassLoader());
        }

        @AddedInOrBefore(majorVersion = 33)
        @Nullable public T getMinValue() {
            return mMinValue;
        }

        @AddedInOrBefore(majorVersion = 33)
        @Nullable public T getMaxValue() {
            return mMaxValue;
        }

        @Override
        @AddedInOrBefore(majorVersion = 33)
        public int describeContents() {
            return 0;
        }

        @Override
        @AddedInOrBefore(majorVersion = 33)
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeValue(mMinValue);
            dest.writeValue(mMaxValue);
        }

        @Override
        public String toString() {
            return "CarAreaConfig{"
                    + "mMinValue=" + mMinValue
                    + ", mMaxValue=" + mMaxValue
                    + '}';
        }
    }

    /**
     * Prepare an instance of {@link CarPropertyConfig}
     *
     * @return Builder<T>
     * @hide
     */
    @SystemApi
    @AddedInOrBefore(majorVersion = 33)
    public static <T> Builder<T> newBuilder(Class<T> type, int propertyId, int areaType,
                                            int areaCapacity) {
        return new Builder<>(areaType, propertyId, type);
    }


    /**
     * Prepare an instance of {@link CarPropertyConfig}
     *
     * @return Builder<T>
     * @hide
     */
    @AddedInOrBefore(majorVersion = 33)
    public static <T> Builder<T> newBuilder(Class<T> type, int propertyId, int areaType) {
        return new Builder<>(areaType, propertyId, type);
    }


    /**
     * @param <T>
     * @hide
     * */
    @SystemApi
    public static class Builder<T> {
        private int mAccess;
        private final int mAreaType;
        private int mChangeMode;
        private final ArrayList<Integer> mConfigArray = new ArrayList<>();
        private String mConfigString;
        private float mMaxSampleRate;
        private float mMinSampleRate;
        private final int mPropertyId;
        private final List<AreaIdConfig<T>> mAreaIdConfigs = new ArrayList<>();
        private final Class<T> mType;

        private Builder(int areaType, int propertyId, Class<T> type) {
            mAreaType = areaType;
            mPropertyId = propertyId;
            mType = type;
        }

        /**
         * @deprecated - use {@link #addAreaIdConfig(AreaIdConfig)} instead.
         *
         * Add supported areas parameter to {@link CarPropertyConfig}
         *
         * @return Builder<T>
         */
        @Deprecated
        @AddedInOrBefore(majorVersion = 33)
        public Builder<T> addAreas(int[] areaIds) {
            for (int areaId : areaIds) {
                mAreaIdConfigs.add(new AreaIdConfig.Builder<T>(areaId).build());
            }
            return this;
        }

        /**
         * @deprecated - use {@link #addAreaIdConfig(AreaIdConfig)} instead.
         *
         * Add {@code areaId} to {@link CarPropertyConfig}
         *
         * @return Builder<T>
         */
        @Deprecated
        @AddedInOrBefore(majorVersion = 33)
        public Builder<T> addArea(int areaId) {
            mAreaIdConfigs.add(new AreaIdConfig.Builder<T>(areaId).build());
            return this;
        }

        /**
         * @deprecated - use {@link #addAreaIdConfig(AreaIdConfig)} instead.
         *
         * Add {@code areaConfig} to {@link CarPropertyConfig}
         *
         * @return Builder<T>
         */
        @Deprecated
        @AddedInOrBefore(majorVersion = 33)
        public Builder<T> addAreaConfig(int areaId, T min, T max) {
            mAreaIdConfigs.add(new AreaIdConfig.Builder<T>(areaId).setMinValue(min).setMaxValue(
                    max).build());
            return this;
        }

        /**
         * Add {@link AreaIdConfig} to {@link CarPropertyConfig}.
         *
         * @return Builder<T>
         */
        @NonNull
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public Builder<T> addAreaIdConfig(@NonNull AreaIdConfig<T> areaIdConfig) {
            mAreaIdConfigs.add(areaIdConfig);
            return this;
        }


        /**
         * Set {@code access} parameter to {@link CarPropertyConfig}
         *
         * @return Builder<T>
         */
        @AddedInOrBefore(majorVersion = 33)
        public Builder<T> setAccess(int access) {
            mAccess = access;
            return this;
        }

        /**
         * Set {@code changeMode} parameter to {@link CarPropertyConfig}
         *
         * @return Builder<T>
         */
        @AddedInOrBefore(majorVersion = 33)
        public Builder<T> setChangeMode(int changeMode) {
            mChangeMode = changeMode;
            return this;
        }

        /**
         * Set {@code configArray} parameter to {@link CarPropertyConfig}
         *
         * @return Builder<T>
         */
        @AddedInOrBefore(majorVersion = 33)
        public Builder<T> setConfigArray(ArrayList<Integer> configArray) {
            mConfigArray.clear();
            mConfigArray.addAll(configArray);
            return this;
        }

        /**
         * Set {@code configString} parameter to {@link CarPropertyConfig}
         *
         * @return Builder<T>
         */
        @AddedInOrBefore(majorVersion = 33)
        public Builder<T> setConfigString(String configString) {
            mConfigString = configString;
            return this;
        }

        /**
         * Set {@code maxSampleRate} parameter to {@link CarPropertyConfig}
         *
         * @return Builder<T>
         */
        @AddedInOrBefore(majorVersion = 33)
        public Builder<T> setMaxSampleRate(float maxSampleRate) {
            mMaxSampleRate = maxSampleRate;
            return this;
        }

        /**
         * Set {@code minSampleRate} parameter to {@link CarPropertyConfig}
         *
         * @return Builder<T>
         */
        @AddedInOrBefore(majorVersion = 33)
        public Builder<T> setMinSampleRate(float minSampleRate) {
            mMinSampleRate = minSampleRate;
            return this;
        }

        /**
         * Builds a new {@link CarPropertyConfig}.
         */
        @AddedInOrBefore(majorVersion = 33)
        public CarPropertyConfig<T> build() {
            return new CarPropertyConfig<>(mAccess, mAreaType, mChangeMode, mConfigArray,
                                           mConfigString, mMaxSampleRate, mMinSampleRate,
                                           mPropertyId, mAreaIdConfigs, mType);
        }
    }

    private static <U> SparseArray<AreaIdConfig<U>> generateAreaIdToAreaIdConfig(
            List<AreaIdConfig<U>> areaIdConfigs) {
        SparseArray<AreaIdConfig<U>> areaIdToAreaIdConfig = new SparseArray<>(areaIdConfigs.size());
        for (int i = 0; i < areaIdConfigs.size(); i++) {
            AreaIdConfig<U> areaIdConfig = areaIdConfigs.get(i);
            areaIdToAreaIdConfig.put(areaIdConfig.getAreaId(), areaIdConfig);
        }
        return areaIdToAreaIdConfig;
    }
}
