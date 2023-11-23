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
package com.chassis.car.ui.plugin;

import android.content.res.Resources;
import android.util.TypedValue;

import androidx.annotation.DimenRes;

/**
 * Collection of utility methods
 */
public final class Utils {

    /** This is a utility class */
    private Utils() {
    }

    /**
     * Reads a float value from a dimens resource. This is necessary as {@link Resources#getFloat}
     * is not currently public.
     *
     * @param res   {@link Resources} to read values from
     * @param resId Id of the dimens resource to read
     */
    public static float getFloat(Resources res, @DimenRes int resId) {
        TypedValue outValue = new TypedValue();
        res.getValue(resId, outValue, true);
        return outValue.getFloat();
    }
}
