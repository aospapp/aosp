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

import static androidx.lifecycle.Transformations.map;

import static com.android.car.arch.common.LiveDataFunctions.dataOf;
import static com.android.car.arch.common.LiveDataFunctions.loadingSwitchMap;
import static com.android.car.arch.common.LiveDataFunctions.pair;
import static com.android.car.arch.common.LiveDataFunctions.split;

import android.app.Application;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.car.arch.common.FutureData;
import com.android.car.arch.common.switching.SwitchingLiveData;
import com.android.car.media.common.MediaConstants;
import com.android.car.media.common.MediaItemMetadata;

import java.util.List;

/**
 * Contains observable data needed for displaying playback and browse/search UI. Instances can be
 * obtained via {@link MediaBrowserViewModel.Factory}
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
class MediaBrowserViewModelImpl extends AndroidViewModel implements MediaBrowserViewModel {

    private final boolean mIsRoot;

    private final SwitchingLiveData<MediaBrowserCompat> mMediaBrowserSwitch =
            SwitchingLiveData.newInstance();

    final MutableLiveData<String> mCurrentBrowseId = dataOf(null);
    final MutableLiveData<String> mCurrentSearchQuery = dataOf(null);
    private final LiveData<MediaBrowserCompat> mConnectedMediaBrowser =
            map(mMediaBrowserSwitch.asLiveData(), MediaBrowserViewModelImpl::requireConnected);

    private final LiveData<FutureData<List<MediaItemMetadata>>> mSearchedMediaItems;
    private final LiveData<FutureData<List<MediaItemMetadata>>> mBrowsedMediaItems;
    private final LiveData<String> mPackageName;

    MediaBrowserViewModelImpl(@NonNull Application application, boolean isRoot) {
        super(application);

        mIsRoot = isRoot;

        mPackageName = map(mConnectedMediaBrowser,
                mediaBrowser -> {
                    if (mediaBrowser == null) return null;
                    return mediaBrowser.getServiceComponent().getPackageName();
                });

        mBrowsedMediaItems =
                loadingSwitchMap(pair(mConnectedMediaBrowser, mCurrentBrowseId),
                        split((mediaBrowser, browseId) -> {
                            if (mediaBrowser == null || (!mIsRoot && browseId == null)) {
                                return null;
                            }

                            String parentId = (mIsRoot) ? mediaBrowser.getRoot() : browseId;
                            return new BrowsedMediaItems(mediaBrowser, parentId);
                        }));
        mSearchedMediaItems =
                loadingSwitchMap(pair(mConnectedMediaBrowser, mCurrentSearchQuery),
                        split((mediaBrowser, query) ->
                                (mediaBrowser == null || TextUtils.isEmpty(query))
                                        ? null
                                        : new SearchedMediaItems(mediaBrowser, query)));
    }

    private static MediaBrowserCompat requireConnected(@Nullable MediaBrowserCompat mediaBrowser) {
        if (mediaBrowser != null && !mediaBrowser.isConnected()) {
            throw new IllegalStateException(
                    "Only connected MediaBrowsers may be provided to MediaBrowserViewModel.");
        }
        return mediaBrowser;
    }

    /**
     * Set the source {@link MediaBrowserCompat} to use for browsing. If {@code mediaBrowser} emits
     * non-null, the MediaBrowser emitted must already be in a connected state.
     */
    void setConnectedMediaBrowser(@Nullable LiveData<MediaBrowserCompat> mediaBrowser) {
        mMediaBrowserSwitch.setSource(mediaBrowser);
    }

    LiveData<? extends MediaBrowserCompat> getMediaBrowserSource() {
        return mMediaBrowserSwitch.getSource();
    }

    @Override
    public LiveData<String> getPackageName() {
        return mPackageName;
    }

    @Override
    public LiveData<FutureData<List<MediaItemMetadata>>> getBrowsedMediaItems() {
        return mBrowsedMediaItems;
    }

    @Override
    public LiveData<FutureData<List<MediaItemMetadata>>> getSearchedMediaItems() {
        return mSearchedMediaItems;
    }

    @SuppressWarnings("deprecation")
    @Override
    public LiveData<Boolean> supportsSearch() {
        return map(mConnectedMediaBrowser, mediaBrowserCompat -> {
            if (mediaBrowserCompat == null) {
                return false;
            }
            Bundle extras = mediaBrowserCompat.getExtras();
            if (extras == null) {
                return false;
            }
            if (extras.containsKey(MediaConstants.MEDIA_SEARCH_SUPPORTED)) {
                return extras.getBoolean(MediaConstants.MEDIA_SEARCH_SUPPORTED);
            }
            if (extras.containsKey(MediaConstants.MEDIA_SEARCH_SUPPORTED_PRERELEASE)) {
                return extras.getBoolean(MediaConstants.MEDIA_SEARCH_SUPPORTED_PRERELEASE);
            }
            return false;
        });
    }

    @SuppressWarnings("deprecation")
    @Override
    public LiveData<Integer> rootBrowsableHint() {
        return map(mConnectedMediaBrowser, mediaBrowserCompat -> {
            if (mediaBrowserCompat == null) {
                return 0;
            }
            Bundle extras = mediaBrowserCompat.getExtras();
            if (extras == null) {
                return 0;
            }
            if (extras.containsKey(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT)) {
                return extras.getInt(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT, 0);
            }
            if (extras.containsKey(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT_PRERELEASE)) {
                return extras.getInt(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT_PRERELEASE, 0);
            }
            return 0;
        });
    }

    @SuppressWarnings("deprecation")
    @Override
    public LiveData<Integer> rootPlayableHint() {
        return map(mConnectedMediaBrowser, mediaBrowserCompat -> {
            if (mediaBrowserCompat == null) {
                return 0;
            }
            Bundle extras = mediaBrowserCompat.getExtras();
            if (extras == null) {
                return 0;
            }
            if (extras.containsKey(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT)) {
                return extras.getInt(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT, 0);
            }
            if (extras.containsKey(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT_PRERELEASE)) {
                return extras.getInt(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT_PRERELEASE, 0);
            }
            return 0;
        });
    }
}
