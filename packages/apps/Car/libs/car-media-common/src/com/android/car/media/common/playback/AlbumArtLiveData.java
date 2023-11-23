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

package com.android.car.media.common.playback;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;

import com.android.car.media.common.MediaItemMetadata;

import java.util.concurrent.CompletableFuture;

/**
 * LiveData class for loading album art from a MediaItemMetadata. This type should not be used
 * directly; instances should be created via {@link #getAlbumArt(Context, int, int, boolean,
 * LiveData)}.
 */
public class AlbumArtLiveData extends MediatorLiveData<Bitmap> {

    /**
     * Returns a new LiveData that emits a Bitmap representation of the {@link MediaItemMetadata}'s
     * album art. While the MediaItemMetadata returns a {@link CompletableFuture}, this LiveData
     * only updates once the future has completed. If it completes exceptionally or the source emits
     * {@code null}, the LiveData's value is set to {@code null}.
     *
     * @see MediaItemMetadata#getAlbumArt(Context, int, int, boolean)
     */
    public static LiveData<Bitmap> getAlbumArt(Context context, int width, int height, boolean fit,
            LiveData<MediaItemMetadata> source) {
        return new AlbumArtLiveData(context, width, height, fit, source);
    }

    private final Context mContext;
    private final int mWidth;
    private final int mHeight;
    private final boolean mFit;
    private CompletableFuture<Bitmap> mFuture;

    private AlbumArtLiveData(Context context, int width, int height, boolean fit,
            LiveData<MediaItemMetadata> source) {
        mContext = context.getApplicationContext();
        mWidth = width;
        mHeight = height;
        mFit = fit;
        addSource(source, this::update);
    }

    private void update(MediaItemMetadata metadata) {
        if (mFuture != null && !mFuture.isDone()) {
            mFuture.cancel(true);
        }
        if (metadata == null) {
            setValue(null);
            mFuture = null;
        } else {
            mFuture = metadata.getAlbumArt(mContext, mWidth, mHeight, mFit);
            mFuture.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    postValue(null);
                } else {
                    postValue(result);
                }
            });
        }
    }
}
