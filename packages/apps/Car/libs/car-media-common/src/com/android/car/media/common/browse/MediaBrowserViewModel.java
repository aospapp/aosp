/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.car.media.common.browse;

import android.support.v4.media.MediaBrowserCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.arch.common.FutureData;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.source.MediaSourceViewModel;

import java.util.List;

/**
 * Contains observable data needed for displaying playback and browse UI. Instances can be obtained
 * via {@link MediaBrowserViewModel.Factory}
 */
public interface MediaBrowserViewModel {

    /**
     * Returns a LiveData that emits the current package name of the browser's service component.
     */
    LiveData<String> getPackageName();

    /**
     * Fetches the MediaItemMetadatas for the current browsed id, and the loading status of the
     * fetch operation.
     *
     * This LiveData will never emit {@code null}. If the data is loading, the data component of the
     * {@link FutureData} will be null
     * A MediaSource must be selected and its MediaBrowser connected, otherwise the FutureData will
     * always contain a {@code null} data value.
     *
     * @return a LiveData that emits a FutureData that contains the loading status and the
     * MediaItemMetadatas for the current browsed id
     */
    LiveData<FutureData<List<MediaItemMetadata>>> getBrowsedMediaItems();

    /**
     * Fetches the MediaItemMetadatas for the current search query, and the loading status of the
     * fetch operation.
     *
     * See {@link #getBrowsedMediaItems()}
     */
    LiveData<FutureData<List<MediaItemMetadata>>> getSearchedMediaItems();


    /**
     * Returns a LiveData that emits whether the media browser supports search. This wil never emit
     * {@code null}
     */
    LiveData<Boolean> supportsSearch();

    /**
     * Gets the content style display type of browsable elements in this media browser, set at the
     * browse root
     */
    LiveData<Integer> rootBrowsableHint();

    /**
     * Gets the content style display type of playable elements in this media browser, set at the
     * browse root
     */
    LiveData<Integer> rootPlayableHint();

    /**
     * A {@link MediaBrowserViewModel} whose selected browse ID may be changed.
     */
    interface WithMutableBrowseId extends MediaBrowserViewModel {

        /**
         * Set the current item to be browsed. If available, the list of items will be emitted by
         * {@link #getBrowsedMediaItems()}.
         */
        @UiThread
        void setCurrentBrowseId(@NonNull String browseId);

        /**
         * Set the current item to be searched for. If available, the list of items will be emitted
         * by {@link #getBrowsedMediaItems()}.
         */
        @UiThread
        void search(@Nullable String query);
    }

    /**
     * Creates and/or fetches {@link MediaBrowserViewModel} instances.
     */
    class Factory {

        private static final String KEY_BROWSER_ROOT =
                "com.android.car.media.common.browse.MediaBrowserViewModel.Factory.browserRoot";

        /**
         * Returns an initialized {@link MediaBrowserViewModel.WithMutableBrowseId} with the
         * provided connected media browser. The provided {@code mediaBrowser} does not need to be
         * from the same scope as {@code viewModelProvider}.
         */
        @NonNull
        public static MediaBrowserViewModel.WithMutableBrowseId getInstanceWithMediaBrowser(
                @NonNull String key,
                @NonNull ViewModelProvider viewModelProvider,
                @NonNull LiveData<MediaBrowserCompat> mediaBrowser) {
            MutableMediaBrowserViewModel viewModel =
                    viewModelProvider.get(key, MutableMediaBrowserViewModel.class);
            initMediaBrowser(mediaBrowser, viewModel);
            return viewModel;
        }

        /**
         * Fetch an initialized {@link MediaBrowserViewModel}. It will get its media browser from
         * the {@link MediaSourceViewModel} provided by {@code viewModelProvider}. It will already
         * be configured to browse the root of the browser.
         *
         * @param mediaSourceVM     the {@link MediaSourceViewModel} singleton.
         * @param viewModelProvider the ViewModelProvider to load ViewModels from.
         * @return an initialized MediaBrowserViewModel configured to browse the specified browseId.
         */
        @NonNull
        public static MediaBrowserViewModel getInstanceForBrowseRoot(
                MediaSourceViewModel mediaSourceVM, @NonNull ViewModelProvider viewModelProvider) {
            RootMediaBrowserViewModel viewModel =
                    viewModelProvider.get(KEY_BROWSER_ROOT, RootMediaBrowserViewModel.class);
            initMediaBrowser(mediaSourceVM.getConnectedMediaBrowser(), viewModel);
            return viewModel;
        }

        private static void initMediaBrowser(
                @NonNull LiveData<MediaBrowserCompat> connectedMediaBrowser,
                MediaBrowserViewModelImpl viewModel) {
            if (viewModel.getMediaBrowserSource() != connectedMediaBrowser) {
                viewModel.setConnectedMediaBrowser(connectedMediaBrowser);
            }
        }
    }
}
