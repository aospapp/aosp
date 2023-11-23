/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.media.widgets;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.apps.common.CommonFlags;
import com.android.car.apps.common.imaging.ImageBinder;
import com.android.car.media.R;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.ui.toolbar.Tab;

import java.util.function.Consumer;

/**
 * A {@link ImageBinder} that exposes a downloads images from a {@link MediaItemMetadata}
 * into a toolbar tab.
 *
 * The resulting tab can be retrieved from the {@link #getToolbarTab} method.
 * There are two listeners that must be supplied in the constructor: a selected
 * listener and an update listener. The selected listener is called when the
 * tab is selected. The update listener is called when the tab has changed in
 * some way, and the updated version needs to be retrieved from {@link #getToolbarTab}
 * again.
 *
 * @param <T> The type of {@link ImageBinder.ImageRef} to use.
 */
public class TabBinder<T extends ImageBinder.ImageRef> extends ImageBinder<T> {
    private final MediaItemMetadata mMediaItemMetadata;
    private final Context mContext;

    private Consumer<TabBinder<T>> mUpdateListener;
    private Tab mTab;

    protected TabBinder(
            @NonNull Context context,
            @NonNull Size maxImageSize,
            @NonNull MediaItemMetadata item,
            @NonNull Consumer<TabBinder<T>> selectedListener) {
        super(PlaceholderType.NONE, maxImageSize);
        mContext = context;
        mMediaItemMetadata = item;
        CharSequence title = mMediaItemMetadata.getTitle();
        mTab = Tab.builder()
                .setText(title == null ? null : title.toString())
                .setSelectedListener(tab -> selectedListener.accept(this))
                .build();
    }

    @Override
    protected void setDrawable(@Nullable Drawable drawable) {
        boolean shouldUseToolbarTint = true;
        CommonFlags flags = CommonFlags.getInstance(mContext);
        if (flags.shouldFlagImproperImageRefs()) {
            if (drawable instanceof BitmapDrawable) {
                int tint = mContext.getColor(
                        R.color.improper_image_refs_tint_color);
                drawable.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_ATOP));
                shouldUseToolbarTint = false;
            }
        }

        mTab = mTab.copy()
                .setIcon(drawable)
                .setTinted(shouldUseToolbarTint)
                .build();
        if (mUpdateListener != null) {
            mUpdateListener.accept(this);
        }
    }

    public void setUpdateListener(Consumer<TabBinder<T>> updateListener) {
        mUpdateListener = updateListener;
    }

    public MediaItemMetadata getMediaItemMetadata() {
        return mMediaItemMetadata;
    }

    public Tab getToolbarTab() {
        return mTab;
    }
}
