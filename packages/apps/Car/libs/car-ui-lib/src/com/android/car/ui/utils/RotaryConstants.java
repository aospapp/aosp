/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.ui.utils;

/** Constants for the rotary controller. */
public final class RotaryConstants {
    /**
     * Content description indicating that the rotary controller should scroll this view
     * horizontally.
     */
    public static final String ROTARY_HORIZONTALLY_SCROLLABLE =
            "android.rotary.HORIZONTALLY_SCROLLABLE";

    /**
     * Content description indicating that the rotary controller should scroll this view
     * vertically.
     */
    public static final String ROTARY_VERTICALLY_SCROLLABLE =
            "android.rotary.VERTICALLY_SCROLLABLE";

    /** Prevent instantiation. */
    private RotaryConstants() {}
}
