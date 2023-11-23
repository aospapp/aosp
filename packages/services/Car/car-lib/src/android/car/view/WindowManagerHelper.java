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

package android.car.view;

import static com.android.car.internal.util.VersionUtils.assertPlatformVersionAtLeastU;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.car.annotation.ApiRequirements;
import android.view.WindowManager;

/**
 * Provide access to {@code android.view.WindowManager} calls.
 *
 * @hide
 */
@SystemApi
public final class WindowManagerHelper {
    private WindowManagerHelper() {
        throw new UnsupportedOperationException();
    }

    /**
     * See {@link WindowManager.LayoutParams#setTrustedOverlay()}}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @RequiresPermission(android.Manifest.permission.INTERNAL_SYSTEM_WINDOW)
    public static void setTrustedOverlay(@NonNull WindowManager.LayoutParams p) {
        assertPlatformVersionAtLeastU();
        android.car.builtin.window.WindowManagerHelper.setTrustedOverlay(p);
    }

    /**
     * See {@link WindowManager.LayoutParams#INPUT_FEATURE_SPY}}.
     * Requires the {@link android.Manifest.permission#MONITOR_INPUT} permission.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static void setInputFeatureSpy(@NonNull WindowManager.LayoutParams p) {
        assertPlatformVersionAtLeastU();
        android.car.builtin.window.WindowManagerHelper.setInputFeatureSpy(p);
    }
}
