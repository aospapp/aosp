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

import android.graphics.Point;
import android.graphics.Rect;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * A {@code PanelAnimator} to animate the panel into the full screen state.
 *
 * This animator resizes the panel into the given bounds using the full-screen panel animation
 * parameters.
 */
public class FullScreenPanelAnimator extends PanelAnimator {
    private static final Interpolator INTERPOLATOR = new PathInterpolator(0.05f, 0.7f, 0.1f, 1);
    private static final long DURATION = 400;

    private final Rect mBounds;
    private final Point mInitialOffset;
    private Animation mAnimation;

    /**
     * A {@code PanelAnimator} to animate the panel into the full screen state.
     *
     * @param panel The panel that should animate
     * @param bounds The final bounds of the panel within its parent
     * @param initialOffset The initial top left corner of the panel in its parent.
     */
    public FullScreenPanelAnimator(ViewGroup panel, Rect bounds, Point initialOffset) {
        super(panel);
        mBounds = bounds;
        mInitialOffset = initialOffset;
    }

    @Override
    public void animate(Runnable endAction) {
        // To reduce the visual glitches, resize the panel before starting the animation.
        Rect bounds = new Rect(mBounds);
        bounds.offset(mInitialOffset.x, mInitialOffset.y);
        updateBounds(bounds);

        mAnimation = new BoundsAnimation(mPanel, mBounds, endAction);
        mAnimation.setInterpolator(INTERPOLATOR);
        mAnimation.setDuration(DURATION);
        mPanel.post(() -> mPanel.startAnimation(mAnimation));
    }

    @Override
    public void cancel() {
        if (mAnimation != null) {
            mAnimation.cancel();
        }
    }
}
