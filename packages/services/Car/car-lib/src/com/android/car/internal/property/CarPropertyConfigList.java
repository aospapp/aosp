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

package com.android.car.internal.property;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.hardware.CarPropertyConfig;
import android.os.Parcel;

import com.android.car.internal.LargeParcelableBase;

import java.util.List;

/**
 * @hide
 */
public final class CarPropertyConfigList extends LargeParcelableBase {

    private @Nullable List<CarPropertyConfig> mCarPropertyConfigList;

    private CarPropertyConfigList(@Nullable Parcel in) {
        super(in);
    }

    public CarPropertyConfigList(@Nullable List<CarPropertyConfig> carPropertyConfigs) {
        mCarPropertyConfigList = carPropertyConfigs;
    }

    public List<CarPropertyConfig> getConfigs() {
        return mCarPropertyConfigList;
    }

    @Override
    public void serialize(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mCarPropertyConfigList);
    }

    @Override
    public void serializeNullPayload(@NonNull Parcel dest) {
        dest.writeTypedList(null);
    }

    @Override
    public void deserialize(@NonNull Parcel src) {
        mCarPropertyConfigList = src.createTypedArrayList(CarPropertyConfig.CREATOR);
    }

    public static final @NonNull Creator<CarPropertyConfigList> CREATOR =
            new Creator<CarPropertyConfigList>() {
        @Override
        public CarPropertyConfigList createFromParcel(@Nullable Parcel in) {
            return new CarPropertyConfigList(in);
        }

        @Override
        public CarPropertyConfigList[] newArray(int size) {
            return new CarPropertyConfigList[size];
        }
    };
}
