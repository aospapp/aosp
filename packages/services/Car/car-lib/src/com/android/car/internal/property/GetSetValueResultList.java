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
import android.os.Parcel;

import com.android.car.internal.LargeParcelableBase;

import java.util.ArrayList;
import java.util.List;

// TODO(b/281564033): Generalize list for large parcelables
/**
 * @hide
 */
public final class GetSetValueResultList extends LargeParcelableBase {
    @Nullable
    private List<GetSetValueResult> mGetSetValueResultList;

    public GetSetValueResultList(@NonNull List<GetSetValueResult> getSetValueResultsList) {
        mGetSetValueResultList = getSetValueResultsList;
    }

    private GetSetValueResultList(@NonNull Parcel source) {
        super(source);
    }

    @Override
    protected void serialize(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mGetSetValueResultList);
    }

    @Override
    protected void serializeNullPayload(@NonNull Parcel dest) {
        dest.writeTypedList(null);
    }

    @Override
    protected void deserialize(@NonNull Parcel src) {
        mGetSetValueResultList = src.createTypedArrayList(GetSetValueResult.CREATOR);
    }

    public List<GetSetValueResult> getList() {
        return mGetSetValueResultList;
    }

    public static final @NonNull Creator<GetSetValueResultList> CREATOR =
            new Creator<GetSetValueResultList>() {
        @Override
        public GetSetValueResultList createFromParcel(@Nullable Parcel in) {
            if (in == null) {
                return new GetSetValueResultList(
                        new ArrayList<GetSetValueResult>());
            }
            return new GetSetValueResultList(in);
        }

        @Override
        public GetSetValueResultList[] newArray(int size) {
            return new GetSetValueResultList[size];
        }
    };
}
