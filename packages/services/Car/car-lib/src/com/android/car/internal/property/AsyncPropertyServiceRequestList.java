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

/**
 * @hide
 */
public class AsyncPropertyServiceRequestList extends LargeParcelableBase {
    @Nullable
    private List<AsyncPropertyServiceRequest> mAsyncPropertyServiceRequestList;

    public AsyncPropertyServiceRequestList(
            @NonNull List<AsyncPropertyServiceRequest> asyncPropertyServiceRequestList) {
        mAsyncPropertyServiceRequestList = asyncPropertyServiceRequestList;
    }

    private AsyncPropertyServiceRequestList(@NonNull Parcel in) {
        super(in);
    }

    @Override
    protected void serialize(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mAsyncPropertyServiceRequestList);
    }

    @Override
    protected void serializeNullPayload(@NonNull Parcel dest) {
        dest.writeTypedList(/* val= */ null);
    }

    @Override
    protected void deserialize(@NonNull Parcel src) {
        mAsyncPropertyServiceRequestList = src.createTypedArrayList(
                AsyncPropertyServiceRequest.CREATOR);
    }

    public List<AsyncPropertyServiceRequest> getList() {
        return mAsyncPropertyServiceRequestList;
    }

    public static final @NonNull Creator<AsyncPropertyServiceRequestList> CREATOR =
            new Creator<AsyncPropertyServiceRequestList>() {
        @Override
        public AsyncPropertyServiceRequestList createFromParcel(@Nullable Parcel in) {
            if (in == null) {
                return new AsyncPropertyServiceRequestList(
                        new ArrayList<AsyncPropertyServiceRequest>());
            }
            return new AsyncPropertyServiceRequestList(in);
        }

        @Override
        public AsyncPropertyServiceRequestList[] newArray(int size) {
            return new AsyncPropertyServiceRequestList[size];
        }
    };
}
