/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.telephony.common;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;

/**
 * Asynchronously queries a {@link ContentResolver} for a given query and observes the loaded data
 * for changes, reloading if necessary.
 *
 * @param <T> The type of data held by this instance
 */
public abstract class AsyncQueryLiveData<T> extends LiveData<T> {

    private static final String TAG = "CD.AsyncQueryLiveData";
    private AsyncEntityLoader<T> mAsyncEntityLoader;

    public AsyncQueryLiveData(Context context, QueryParam.Provider provider) {
        mAsyncEntityLoader = new AsyncEntityLoader<>(context, provider,
                this::convertToEntity, (loader, entity) -> setValue(entity));
    }

    @Override
    protected void onActive() {
        super.onActive();
        mAsyncEntityLoader.startLoading();
    }

    @Override
    protected void onInactive() {
        super.onInactive();
        mAsyncEntityLoader.reset();
    }

    /**
     * Override this function to convert the loaded data. This function is called on non-UI thread.
     */
    @WorkerThread
    protected abstract T convertToEntity(@NonNull Cursor cursor);
}
