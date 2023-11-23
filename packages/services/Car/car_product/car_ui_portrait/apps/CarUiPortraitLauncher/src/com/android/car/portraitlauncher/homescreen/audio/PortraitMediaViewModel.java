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

package com.android.car.portraitlauncher.homescreen.audio;

import static android.car.media.CarMediaIntents.EXTRA_MEDIA_COMPONENT;

import android.app.Application;
import android.car.media.CarMediaIntents;
import android.content.Intent;
import android.view.View;

import com.android.car.carlauncher.homescreen.audio.MediaViewModel;
import com.android.car.media.common.source.MediaSource;

/**
 * A portrait UI version of {@link MediaViewModel}
 */
public class PortraitMediaViewModel extends MediaViewModel {
    public final MediaIntentRouter mMediaIntentRouter;

    public PortraitMediaViewModel(Application application) {
        super(application);
        mMediaIntentRouter = MediaIntentRouter.getInstance();
    }

    @Override
    public void onClick(View v) {
        MediaSource mediaSource = getMediaSourceViewModel().getPrimaryMediaSource().getValue();
        Intent intent = new Intent(CarMediaIntents.ACTION_MEDIA_TEMPLATE);
        if (mediaSource != null) {
            intent.putExtra(EXTRA_MEDIA_COMPONENT,
                    mediaSource.getBrowseServiceComponentName().flattenToString());
        }
        mMediaIntentRouter.handleMediaIntent(intent);
    }
}
