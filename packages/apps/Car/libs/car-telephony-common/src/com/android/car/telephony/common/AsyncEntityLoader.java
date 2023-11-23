/*
 * Copyright 2021 The Android Open Source Project
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
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContentResolverCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.CancellationSignal;
import androidx.core.os.OperationCanceledException;
import androidx.loader.content.AsyncTaskLoader;
import androidx.loader.content.Loader;

/**
 * An {@link AsyncTaskLoader} that queries database and convert {@link Cursor} to data entities.
 *
 * @param <T> type of data the cursor will be converted to.
 */
public class AsyncEntityLoader<T> extends AsyncTaskLoader<T> {

    /**
     * Caller of the {@link AsyncTaskLoader} will need to provide how to convert the loaded cursor
     * to the data it consumes.
     *
     * @param <T> The type of data the cursor will be converted to.
     */
    public interface EntityConverter<T> {
        /** Convert cursor to the desired type of data. This function is called on non-UI thread. */
        @WorkerThread
        T convertToEntity(Cursor cursor);
    }

    private static final String TAG = "CD.AsyncEntityLoader";

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final ForceLoadContentObserver mObserver;
    private final QueryParam.Provider mQueryParamProvider;
    private final EntityConverter<T> mEntityConverter;
    private final Loader.OnLoadCompleteListener<T> mOnLoadCompleteListener;

    private T mEntity;
    private CancellationSignal mCancellationSignal;

    public AsyncEntityLoader(
            @NonNull Context context,
            @NonNull QueryParam.Provider queryParamProvider,
            @NonNull EntityConverter<T> entityConverter,
            @NonNull Loader.OnLoadCompleteListener<T> onLoadCompleteListener) {
        super(context);
        mContext = context;
        mContentResolver = context.getContentResolver();
        mObserver = new ForceLoadContentObserver();
        mQueryParamProvider = queryParamProvider;

        mEntityConverter = entityConverter;
        mOnLoadCompleteListener = onLoadCompleteListener;
    }

    @Nullable
    @WorkerThread
    @Override
    public T loadInBackground() {
        synchronized (this) {
            if (isLoadInBackgroundCanceled()) {
                throw new OperationCanceledException();
            }
            mCancellationSignal = new CancellationSignal();
        }

        Cursor cursor = null;
        T entity = null;
        try {
            QueryParam queryParam = mQueryParamProvider.getQueryParam();

            if (queryParam == null || ContextCompat.checkSelfPermission(mContext,
                    queryParam.mPermission) != PackageManager.PERMISSION_GRANTED) {
                return null;
            }

            mContentResolver.registerContentObserver(queryParam.mUri, false, mObserver);
            cursor = ContentResolverCompat.query(mContentResolver,
                    queryParam.mUri, queryParam.mProjection, queryParam.mSelection,
                    queryParam.mSelectionArgs, queryParam.mOrderBy,
                    mCancellationSignal);
            if (cursor != null) {
                // Ensure the cursor window is filled.
                cursor.getCount();
                entity = mEntityConverter.convertToEntity(cursor);
                cursor.close();
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            synchronized (this) {
                mCancellationSignal = null;
            }
        }
        return entity;
    }

    @Override
    public void cancelLoadInBackground() {
        Log.d(TAG, "cancelLoadInBackground");
        mContentResolver.unregisterContentObserver(mObserver);
        synchronized (this) {
            if (mCancellationSignal != null) {
                mCancellationSignal.cancel();
            }
        }
    }

    @MainThread
    @Override
    public void deliverResult(@Nullable T entity) {
        if (isReset()) {
            return;
        }

        mEntity = entity;
        if (isStarted()) {
            Log.d(TAG, "deliverResult");
            super.deliverResult(entity);
        }
    }

    /**
     * Starts an asynchronous load of the contacts list data. When the result is ready the callbacks
     * will be called on the UI thread. If a previous load has been completed and is still valid
     * the result may be passed to the callbacks immediately.
     */
    @MainThread
    @Override
    protected void onStartLoading() {
        Log.d(TAG, "onStartLoading");
        registerListener(0, mOnLoadCompleteListener);

        if (mEntity != null) {
            deliverResult(mEntity);
        }
        if (takeContentChanged() || mEntity == null) {
            forceLoad();
        }
    }

    @MainThread
    @Override
    protected void onStopLoading() {
        Log.d(TAG, "onStopLoading");
        // Attempt to cancel the current load task if possible.
        cancelLoad();
    }

    @MainThread
    @Override
    protected void onReset() {
        Log.d(TAG, "onReset");

        // Ensure the loader is stopped.
        onStopLoading();

        mEntity = null;
        unregisterListener(mOnLoadCompleteListener);
    }
}
