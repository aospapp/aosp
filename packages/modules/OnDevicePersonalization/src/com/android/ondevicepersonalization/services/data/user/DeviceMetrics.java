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

package com.android.ondevicepersonalization.services.data.user;

import android.content.res.Configuration;

/** Constant device metrics values. */
public class DeviceMetrics {
    // Device manufacturer
    public Make make = Make.UNKNOWN;

    // Device model
    public Model model = Model.UNKNOWN;

    // Screen height of the device in dp units
    public int screenHeight = Configuration.SCREEN_HEIGHT_DP_UNDEFINED;

    // Screen weight of the device in dp units
    public int screenWidth = Configuration.SCREEN_WIDTH_DP_UNDEFINED;

    // Device x dpi;
    public float xdpi = 0;

    // Device y dpi;
    public float ydpi = 0;

    // Dveice pixel ratio.
    public float pxRatio = 0;
}
