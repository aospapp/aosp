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

package com.android.systemui.car.hvac;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;

import com.android.systemui.R;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.window.OverlayViewGlobalStateController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.wm.shell.animation.FlingAnimationUtils;

import javax.inject.Inject;

/**
 *  An extension of {@link HvacPanelOverlayViewController} which auto dismisses the panel if there
 *  is no activity for some configured amount of time.
 */
@SysUISingleton
public class AutoDismissHvacPanelOverlayViewController extends HvacPanelOverlayViewController {

    private final Resources mResources;
    private final Handler mHandler;

    private HvacPanelView mHvacPanelView;
    private int mAutoDismissDurationMs;

    private final Runnable mAutoDismiss = () -> {
        if (isPanelExpanded()) {
            toggle();
        }
    };

    @Inject
    public AutoDismissHvacPanelOverlayViewController(Context context,
            @Main Resources resources,
            HvacController hvacController,
            OverlayViewGlobalStateController overlayViewGlobalStateController,
            FlingAnimationUtils.Builder flingAnimationUtilsBuilder,
            CarDeviceProvisionedController carDeviceProvisionedController,
            @Main Handler handler) {
        super(context, resources, hvacController, overlayViewGlobalStateController,
                flingAnimationUtilsBuilder, carDeviceProvisionedController);
        mResources = resources;
        mHandler = handler;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mAutoDismissDurationMs = mResources.getInteger(R.integer.config_hvacAutoDismissDurationMs);

        mHvacPanelView = getLayout().findViewById(R.id.hvac_panel);
        mHvacPanelView.setMotionEventHandler(event -> {
            if (!isPanelExpanded()) {
                return;
            }

            mHandler.removeCallbacks(mAutoDismiss);
            mHandler.postDelayed(mAutoDismiss, mAutoDismissDurationMs);
        });
    }

    @Override
    protected void onAnimateExpandPanel() {
        super.onAnimateExpandPanel();

        mHandler.postDelayed(mAutoDismiss, mAutoDismissDurationMs);
    }

    @Override
    protected void onAnimateCollapsePanel() {
        super.onAnimateCollapsePanel();

        mHandler.removeCallbacks(mAutoDismiss);
    }
}
