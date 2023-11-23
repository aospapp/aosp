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
package com.android.car.ui.recyclerview;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import com.android.car.ui.R;

/**
 * @deprecated Only maintained for legacy reasons.
 * Historically this class used to contain the CarUiRecyclerView, and ScrollBar.
 * This class can't be removed because OEMs might have overlaid car_ui_recycler_view.xml with their
 * RROs.
 * Now this class is only an FrameLayout that contains a {@link RecyclerView} to maintain backwards
 * compatibility.
 */
@Deprecated
public class CarUiRecyclerViewContainer extends FrameLayout {

    public CarUiRecyclerViewContainer(Context context) {
        this(context, null);
    }

    public CarUiRecyclerViewContainer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CarUiRecyclerViewContainer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        // We have to inflate this from layout because some of the attributes e.g.
        // android:scrollbars can't be set from code.
        inflate(context, R.layout.car_ui_recycler_view_only, this);
    }
}
