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

package com.android.car.portraitlauncher.panel;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.RelativeLayout;

import com.android.car.portraitlauncher.R;

/** The grip bar used to drag a TaskViewPanel */
public class GripBarView extends RelativeLayout {

    public GripBarView(Context context) {
        this(context, null);
    }

    public GripBarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GripBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public GripBarView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /** Refreshes the view according to the given {@code theme}. */
    public void refresh(Resources.Theme theme) {
        Drawable background = getResources().getDrawable(R.drawable.grip_bar_background, theme);
        setBackground(background);
    }
}
