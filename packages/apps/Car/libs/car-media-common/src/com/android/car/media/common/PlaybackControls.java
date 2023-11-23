package com.android.car.media.common;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;

import com.android.car.media.common.playback.PlaybackViewModel;

/**
 * Custom view that can be used to display playback controls. It accepts a {@link PlaybackViewModel}
 * as its data source, automatically reacting to changes in playback state.
 */
public interface PlaybackControls {
    /**
     * Sets the {@link PlaybackViewModel} to use as the view model for this view.
     */
    void setModel(@NonNull PlaybackViewModel model, @NonNull LifecycleOwner lifecycleOwner);
}
