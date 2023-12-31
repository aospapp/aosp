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

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.car.systembar.CarSystemBarController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.settings.UserTracker;

import javax.inject.Inject;

/**
 * Instance of {@link HvacPanelOverlayViewMediator} which uses {@link
 * AutoDismissHvacPanelOverlayViewController}.
 */
@SysUISingleton
public class AutoDismissHvacPanelOverlayViewMediator extends HvacPanelOverlayViewMediator {

    @Inject
    public AutoDismissHvacPanelOverlayViewMediator(
            Context context,
            CarSystemBarController carSystemBarController,
            AutoDismissHvacPanelOverlayViewController hvacPanelOverlayViewController,
            BroadcastDispatcher broadcastDispatcher,
            UserTracker userTracker) {
        super(context, carSystemBarController, hvacPanelOverlayViewController, broadcastDispatcher,
                userTracker);
    }
}
