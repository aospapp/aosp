/**
 * Copyright (c) 2013, Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mail.utils;

import android.annotation.SuppressLint;
import android.support.v4.view.ViewCompat;
import android.view.View;

/**
 * Utility class to perform some common operations on views.
 */
public class ViewUtils {

    /**
     * Determines whether the given view has RTL layout. NOTE: do not call this
     * on a view until it has been measured. This value is not guaranteed to be
     * accurate until then.
     */
    public static boolean isViewRtl(View view) {
        return ViewCompat.getLayoutDirection(view) == ViewCompat.LAYOUT_DIRECTION_RTL;
    }

    /**
     * @return the start padding of the view. Prior to API 17, will return the left padding.
     */
    @SuppressLint("NewApi")
    public static int getPaddingStart(View view) {
        return Utils.isRunningJBMR1OrLater() ? view.getPaddingStart() : view.getPaddingLeft();
    }

    /**
     * @return the end padding of the view. Prior to API 17, will return the right padding.
     */
    @SuppressLint("NewApi")
    public static int getPaddingEnd(View view) {
        return Utils.isRunningJBMR1OrLater() ? view.getPaddingEnd() : view.getPaddingRight();
    }

    /**
     * Sets the text alignment of the view. Prior to API 17, will no-op.
     */
    @SuppressLint("NewApi")
    public static void setTextAlignment(View view, int textAlignment) {
        if (Utils.isRunningJBMR1OrLater()) {
            view.setTextAlignment(textAlignment);
        }
    }
}
