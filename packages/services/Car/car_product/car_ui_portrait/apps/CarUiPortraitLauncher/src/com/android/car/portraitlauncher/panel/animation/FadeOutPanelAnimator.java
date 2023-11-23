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

package com.android.car.portraitlauncher.panel.animation;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * A {@code PanelAnimator} to animate the panel into the open state using the fade-in animation.
 */
public class FadeOutPanelAnimator extends PanelAnimator {
    private static final Interpolator INTERPOLATOR = new PathInterpolator(0.05f, 0, 1, 1);
    private static final long OVERLAY_FADE_IN_DURATION = 300;
    private static final long PANEL_FADE_OUT_DURATION = 300;

    private static final float INITIAL_SCALE = 1;
    private static final float FINAL_SCALE = 0.9f;

    private static final float FADE_OUT_ALPHA = 0;
    private static final float FADE_IN_ALPHA = 1;

    private final View mOverlay;
    private final View mTaskView;
    private final Rect mBounds;
    private ViewPropertyAnimator mOverlayAnimator;
    private ViewPropertyAnimator mTaskViewAnimator;

    /**
     * A {@code PanelAnimator} to animate the panel into the open state using the fade-in animation.
     *
     * @param panel The panel that should animate
     * @param overlay The overlay view that covers the {@code TaskView}. Used to visually fade out
     *                the {@code TaskView}.
     * @param taskView The task view of the panel.
     * @param toBounds The final bounds of the panel within its parent
     */
    public FadeOutPanelAnimator(ViewGroup panel, View overlay, View taskView,
            Rect toBounds) {
        super(panel);
        mOverlay = overlay;
        mTaskView = taskView;
        mBounds = toBounds;
    }

    @Override
    public void animate(Runnable endAction) {
        mOverlay.setVisibility(VISIBLE);
        mOverlay.setAlpha(FADE_OUT_ALPHA);
        // First fade in the overlay and then fade-out the whole panel.
        // This is necessary since we cannot fade the task views.
        mOverlayAnimator = mOverlay.animate().alpha(FADE_IN_ALPHA)
                .setDuration(OVERLAY_FADE_IN_DURATION)
                .withEndAction(() -> {
                    mPanel.animate().alpha(FADE_OUT_ALPHA).setDuration(PANEL_FADE_OUT_DURATION)
                            .withEndAction(() -> {
                                mOverlay.setVisibility(GONE);
                                mTaskView.setVisibility(VISIBLE);
                                mTaskView.setScaleX(INITIAL_SCALE);
                                mTaskView.setScaleY(INITIAL_SCALE);
                                mPanel.setAlpha(FADE_IN_ALPHA);
                                endAction.run();
                            });
                });
        // Scale the task view and hide it at the end.
        mTaskViewAnimator = mTaskView.animate().scaleX(FINAL_SCALE).scaleY(FINAL_SCALE)
                .setDuration(OVERLAY_FADE_IN_DURATION).setInterpolator(INTERPOLATOR)
                .withEndAction(() -> {
                    // Restore the initial scale
                    mTaskView.setScaleX(INITIAL_SCALE);
                    mTaskView.setScaleY(INITIAL_SCALE);
                    mTaskView.setVisibility(GONE);
                });
    }

    @Override
    public void cancel() {
        if (mOverlayAnimator != null) {
            mOverlayAnimator.cancel();
        }

        if (mTaskViewAnimator != null) {
            mTaskViewAnimator.cancel();
        }
        mOverlay.setVisibility(GONE);
        mTaskView.setVisibility(VISIBLE);
        mTaskView.setScaleX(INITIAL_SCALE);
        mTaskView.setScaleY(INITIAL_SCALE);
        mPanel.setAlpha(FADE_IN_ALPHA);
        updateBounds(mBounds);
    }
}
