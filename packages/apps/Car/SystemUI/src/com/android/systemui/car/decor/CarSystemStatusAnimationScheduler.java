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

package com.android.systemui.car.decor;

import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.events.PrivacyEvent;
import com.android.systemui.statusbar.events.StatusEvent;
import com.android.systemui.statusbar.events.SystemEventChipAnimationController;
import com.android.systemui.statusbar.events.SystemEventCoordinator;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerImpl;
import com.android.systemui.statusbar.window.StatusBarWindowController;
import com.android.systemui.util.time.SystemClock;

import javax.inject.Inject;

import kotlinx.coroutines.CoroutineScope;

/**
 * Subclass of {@link SystemStatusAnimationScheduler}. This class will handle the privacy events on
 * its own.
 */
@SysUISingleton
public class CarSystemStatusAnimationScheduler extends SystemStatusAnimationSchedulerImpl {
    private static final String TAG = "CarAnimationScheduler";

    @Inject
    public CarSystemStatusAnimationScheduler(
            @NonNull SystemEventCoordinator coordinator,
            @NonNull SystemEventChipAnimationController chipAnimationController,
            @NonNull StatusBarWindowController statusBarWindowController,
            @NonNull DumpManager dumpManager,
            @NonNull SystemClock systemClock,
            @Application @NonNull CoroutineScope coroutineScope) {
        super(coordinator, chipAnimationController, statusBarWindowController, dumpManager,
                systemClock, coroutineScope);
    }

    @Override
    public void onStatusEvent(StatusEvent event) {
        if (!(event instanceof PrivacyEvent)) {
            super.onStatusEvent(event);
            return;
        }

        // Ignore any updates until the system is up and running
        if (isTooEarly() || !isImmersiveIndicatorEnabled()) {
            return;
        }

        if (!Looper.getMainLooper().isCurrentThread()) {
            Log.w(TAG, "Current thread is not the main thread.");
            return;
        }

        if (event.getContentDescription() == null || event.getContentDescription().isEmpty()) {
            Log.w(TAG, "ContentDescription for privacy event is empty");
        }
        getListeners().stream().forEach(l -> {
            if (l != null) {
                setHasPersistentDot(true);
                l.onSystemStatusAnimationTransitionToPersistentDot(event.getContentDescription());
            }
        });
    }
}
