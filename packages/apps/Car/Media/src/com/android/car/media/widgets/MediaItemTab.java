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

package com.android.car.media.widgets;

import android.annotation.NonNull;
import android.content.Context;
import android.widget.ImageView;

import com.android.car.apps.common.widget.CarTabLayout;
import com.android.car.media.common.MediaItemMetadata;

/**
 * An entity representing a media item to be included in the tab bar at the top of the UI.
 */
public class MediaItemTab extends CarTabLayout.CarTab {
    private final MediaItemMetadata mItem;

    /**
     * Creates a new tab for the given media item.
     */
    public MediaItemTab(@NonNull Context context, @NonNull MediaItemMetadata item) {
        super(null, item.getTitle());
        mItem = item;
    }

    @Override
    protected void bindIcon(ImageView imageView) {
        MediaItemMetadata.updateImageView(imageView.getContext(), mItem, imageView, 0, false);
    }

    /**
     * Returns the item represented by this view
     */
    @NonNull
    public MediaItemMetadata getItem() {
        return mItem;
    }
}
