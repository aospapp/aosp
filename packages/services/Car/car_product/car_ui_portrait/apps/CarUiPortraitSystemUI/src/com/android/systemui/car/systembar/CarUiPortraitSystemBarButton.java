/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.systemui.car.displayarea.DisplayAreaComponent.DISPLAY_AREA_VISIBILITY_CHANGED;
import static com.android.systemui.car.displayarea.DisplayAreaComponent.INTENT_EXTRA_IS_DISPLAY_AREA_VISIBLE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.AttributeSet;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/** A CarSystemBarButton that controls a display area. */
public class CarUiPortraitSystemBarButton extends CarSystemBarButton {

    public CarUiPortraitSystemBarButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        BroadcastReceiver displayAreaVisibilityReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean isDefaultDAVisible = intent.getBooleanExtra(
                        INTENT_EXTRA_IS_DISPLAY_AREA_VISIBLE, true);
                if (getSelected() && !isDefaultDAVisible) {
                    context.getMainExecutor().execute(() -> setSelected(/* selected= */ false));
                }
                setSelected(isDefaultDAVisible);
            }
        };
        LocalBroadcastManager.getInstance(context).registerReceiver(displayAreaVisibilityReceiver,
                new IntentFilter(DISPLAY_AREA_VISIBILITY_CHANGED));
    }
}
