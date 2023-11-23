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
package com.android.customization.model.grid;

import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.customization.model.CustomizationManager;
import com.android.customization.module.ThemesUserEventLogger;

import java.util.List;

/**
 * {@link CustomizationManager} for interfacing with the launcher to handle {@link GridOption}s.
 */
public class GridOptionsManager implements CustomizationManager<GridOption> {

    private final LauncherGridOptionsProvider mProvider;
    private final ThemesUserEventLogger mEventLogger;

    public GridOptionsManager(LauncherGridOptionsProvider provider, ThemesUserEventLogger logger) {
        mProvider = provider;
        mEventLogger = logger;
    }

    @Override
    public boolean isAvailable() {
        return mProvider.areGridsAvailable();
    }

    @Override
    public void apply(GridOption option, Callback callback) {
        int updated = mProvider.applyGrid(option.name);
        if (updated == 1) {
            mEventLogger.logGridApplied(option);
            callback.onSuccess();
        } else {
            callback.onError(null);
        }
    }

    @Override
    public void fetchOptions(OptionsFetchedListener<GridOption> callback, boolean reload) {
        new FetchTask(mProvider, callback).execute();
    }

    /** See if using surface view to render grid options */
    public boolean usesSurfaceView() {
        return mProvider.usesSurfaceView();
    }

    /** Call through content provider API to render preview */
    public void renderPreview(Bundle bundle, String gridName) {
        mProvider.renderPreview(gridName, bundle);
    }

    private static class FetchTask extends AsyncTask<Void, Void, Pair<List<GridOption>, String>> {
        private final LauncherGridOptionsProvider mProvider;
        @Nullable private final OptionsFetchedListener<GridOption> mCallback;

        private FetchTask(@NonNull LauncherGridOptionsProvider provider,
                @Nullable OptionsFetchedListener<GridOption> callback) {
            mCallback = callback;
            mProvider = provider;
        }

        @Override
        protected Pair<List<GridOption>, String> doInBackground(Void[] params) {
            return mProvider.fetch(false);
        }

        @Override
        protected void onPostExecute(Pair<List<GridOption>, String> gridOptionsResult) {
            if (mCallback != null) {
                if (gridOptionsResult != null && gridOptionsResult.first != null
                        && !gridOptionsResult.first.isEmpty()) {
                    mCallback.onOptionsLoaded(gridOptionsResult.first);
                } else {
                    mCallback.onError(null);
                }
            }
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            if (mCallback != null) {
                mCallback.onError(null);
            }
        }
    }
}
