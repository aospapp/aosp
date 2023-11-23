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

package com.android.car.media.common.source;


import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_BROWSE;

import android.app.Application;
import android.car.media.CarMediaIntents;
import android.content.ComponentName;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;

import java.util.Objects;


/**
 * This class helps different Media "Hosts" (CarMediaApp, CarRadioApp...) pretend they are a single
 * app, by automatically launching the intent needed to see a newly activated media service in its
 * proper host. For example when the user is displaying the Radio and activates by voice the local
 * media player, that service becomes visible in the CarMediaApp. <p/>
 *
 * An activity should only use a single instance of this class, and should call
 * {@link #setLaunchedMediaSource} when it is resumed with a new intent.
 */
public class MediaTrampolineHelper implements LifecycleObserver {

    private static final String TAG = "MediaTrampolineHlp";
    private final FragmentActivity mActivity;
    private final MediaSourceViewModel mMediaSourceVM;


    private MediaSource mLaunchedSource;
    private boolean mIsResumed;

    public MediaTrampolineHelper(@NonNull FragmentActivity activity) {
        mActivity = activity;
        Application app = mActivity.getApplication();
        mMediaSourceVM = MediaSourceViewModel.get(app, MEDIA_SOURCE_MODE_BROWSE);
        mActivity.getLifecycle().addObserver(this);

        mMediaSourceVM.getPrimaryMediaSource().observe(mActivity, source -> {
            if (mIsResumed) {
                Log.i(TAG, "New media source while resumed: " + source);
                maybeTrampoline(source);
            }
        });
    }

    /**
     * Sets the media source that should be 1) recorded as primary, and 2) used as baseline when
     * the helper detects a new media source and decides to trampoline to it.
     * Should be called when the activity is resumed with a new intent (and the intent should be
     * flagged as consumed).
     */
    public void setLaunchedMediaSource(@Nullable ComponentName launchedSourceComp) {
        if (launchedSourceComp != null) {
            mLaunchedSource = MediaSource.create(mActivity, launchedSourceComp);
        }

        if (mLaunchedSource != null) {
            // Since the activity is being explicitly launched, make sure its source is primary.
            mMediaSourceVM.setPrimaryMediaSource(mLaunchedSource, MEDIA_SOURCE_MODE_BROWSE);
        } else {
            // Might happen if there's no media source at all on the system ?!
            Log.e(TAG, "Null mLaunchedSource!!!");
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            MediaSource currentSource = mMediaSourceVM.getPrimaryMediaSource().getValue();
            Log.d(TAG, "Launched: " + mLaunchedSource + " current: " + currentSource);
        }
    }

    /***/
    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    public void onResume() {
        mIsResumed = true;
        maybeTrampoline(mMediaSourceVM.getPrimaryMediaSource().getValue());
    }

    /***/
    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    public void onPause() {
        mIsResumed = false;
    }

    private void maybeTrampoline(MediaSource latestSource) {
        if (!Objects.equals(mLaunchedSource, latestSource)) {
            // Always go through the trampoline activity to keep the dispatching logic there.

            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "Launching: " + latestSource + " old: " + mLaunchedSource);
            }

            Intent intent = new Intent(CarMediaIntents.ACTION_MEDIA_TEMPLATE);
            if (latestSource != null) {
                ComponentName component = latestSource.getBrowseServiceComponentName();
                intent.putExtra(CarMediaIntents.EXTRA_MEDIA_COMPONENT, component.flattenToString());
            } else if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, "latestSource is null in onResume");
            }
            mActivity.startActivity(intent);
        }
    }
}
