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

package com.android.systemui.car.systembar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.AttributeSet;

/** A CarSystemBarButton that controls a display area. */
public class DisplayAreaButton extends CarSystemBarButton {

    // TODO(b/194334719): Remove when display area logic is moved into systemui
    private static final String DISPLAY_AREA_VISIBILITY_CHANGED =
            "com.android.car.carlauncher.displayarea.DISPLAY_AREA_VISIBILITY_CHANGED";
    private static final String INTENT_EXTRA_IS_DISPLAY_AREA_VISIBLE =
            "EXTRA_IS_DISPLAY_AREA_VISIBLE";

    /**
     * A broadcast receiver to listen when the display area is closed via swipe.
     * When the display area logic is moved from launcher into system ui, the DisplayAreaButton
     * should be notified of changes in the panel's visibility directly, rather than using a
     * broadcast.
     */
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean isDisplayAreaVisible = intent.getBooleanExtra(
                    INTENT_EXTRA_IS_DISPLAY_AREA_VISIBLE, /* defaultValue= */ true);
            if (getSelected() && !isDisplayAreaVisible) {
                context.getMainExecutor().execute(() -> setSelected(/* selected= */ false));
            }
        }
    };

    public DisplayAreaButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        context.registerReceiver(mBroadcastReceiver,
                new IntentFilter(DISPLAY_AREA_VISIBILITY_CHANGED));
    }
}
