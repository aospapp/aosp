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

package com.android.car.ui.widget;

import static com.android.car.ui.core.CarUi.MIN_TARGET_API;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import com.android.car.ui.CarUiLayoutInflaterFactory;
import com.android.car.ui.CarUiText;
import com.android.car.ui.pluginsupport.PluginFactorySingleton;

import java.util.List;

/**
 * This is aa definition for a {@link TextView} extension that supports rendering {@link
 * CarUiText}.
 */
@SuppressLint("AppCompatCustomView")
@TargetApi(MIN_TARGET_API)
public abstract class CarUiTextView extends AppCompatTextView {
    /**
     * Creates a CarUiTextView.
     *
     * Most of the time, you should prefer creating a CarUiTextView with a {@code <CarUiTextView>}
     * tag in your layout file. This is only for if you need to create a CarUiTextView in java code.
     * The CarUiTextView xml tag is enabled by the usage of {@link CarUiLayoutInflaterFactory}.
     */
    public static CarUiTextView create(@NonNull Context context) {
        return CarUiTextView.create(context, null);
    }

    /**
     * Creates a CarUiTextView.
     *
     * Most of the time, you should prefer creating a CarUiTextView with a {@code <CarUiTextView>}
     * tag in your layout file. This is only for if you need to create a CarUiTextView in java code.
     * The CarUiTextView xml tag is enabled by the usage of {@link CarUiLayoutInflaterFactory}.
     */
    public static CarUiTextView create(@NonNull Context context, @Nullable AttributeSet attrs) {
        return PluginFactorySingleton.get(context).createTextView(context, attrs);
    }

    public CarUiTextView(Context context) {
        this(context, null);
    }

    public CarUiTextView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.textViewStyle);
    }

    public CarUiTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * Set text to display.
     *
     * @param textList list of text to display. Each {@link CarUiText} in the list will be rendered
     *                 on a new line, separated by a line break
     */
    public abstract void setText(@NonNull List<CarUiText> textList);

    /**
     * Set text to display.
     */
    public abstract void setText(@NonNull CarUiText text);
}
