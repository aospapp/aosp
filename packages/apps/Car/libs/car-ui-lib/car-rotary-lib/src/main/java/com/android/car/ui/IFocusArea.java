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

package com.android.car.ui;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * An interface used for rotary controller navigation. When a class implements this interface and
 * extends a subclass of {@link android.view.ViewGroup}, the class can be used as a navigation
 * block for the rotary controller. For example, the {@link FocusArea} class, which extends
 * {@link android.widget.LinearLayout}.
 * <p>
 * The {@link com.android.car.rotary.RotaryService} looks for instances of IFocusArea in
 * the view hierarchy when handling rotate and nudge actions. When receiving a rotation event
 * ({@link android.car.input.RotaryEvent}), RotaryService will move the focus to another
 * {@link View} that can take focus within the same IFocusArea. When receiving a nudge event
 * ({@link android.view.KeyEvent#KEYCODE_SYSTEM_NAVIGATION_UP},
 * {@link android.view.KeyEvent#KEYCODE_SYSTEM_NAVIGATION_DOWN},
 * {@link android.view.KeyEvent#KEYCODE_SYSTEM_NAVIGATION_LEFT}, or
 * {@link android.view.KeyEvent#KEYCODE_SYSTEM_NAVIGATION_RIGHT}),
 * RotaryService will move the focus to another view that can take focus in another (typically
 * adjacent) IFocusArea.
 * <p>
 * If enabled, IFocusArea can draw highlights when one of its descendants has focus and it's not in
 * touch mode.
 * <p>
 * DO NOT nest an IFocusArea inside another IFocusArea because it will result in undefined
 * navigation behavior.
 */
public interface IFocusArea {

    /** Gets the {@link FocusAreaHelper} of this IFocusArea. */
    @NonNull
    FocusAreaHelper getHelper();

    /** Gets the default focus view in this IFocusArea. */
    @Nullable
    View getDefaultFocusView();

    /** Sets the default focus view in this IFocusArea. */
    void setDefaultFocus(@NonNull View defaultFocus);

    /**
     * Sets the padding (in pixels) of the IFocusArea highlight.
     * <p>
     * It doesn't affect other values, such as the paddings on its child views.
     */
    void setHighlightPadding(int left, int top, int right, int bottom);

    /**
     * Sets the offset (in pixels) of the IFocusArea's perceived bounds.
     * <p>
     * It only affects the perceived bounds for the purposes of finding the nudge target. It doesn't
     * affect the IFocusArea's view bounds or highlight bounds. The offset should only be used when
     * IFocusAreas are overlapping and nudge interaction is ambiguous.
     */
    void setBoundsOffset(int left, int top, int right, int bottom);

    /** Sets whether wrap-around is enabled for this IFocusArea. */
    void setWrapAround(boolean wrapAround);

    /**
     * Sets the nudge shortcut for the given {@code direction}. Removes the nudge shortcut if
     * {@code view} is {@code null}.
     */
    void setNudgeShortcut(int direction, @Nullable View view);

    /**
     * Sets the nudge target IFocusArea for the given {@code direction}. Removes the
     * existing target if {@code target} is {@code null}.
     */
    void setNudgeTargetFocusArea(int direction, @Nullable IFocusArea target);

    /**
     * Sets whether to focus on the default focus view when nudging to the IFocusArea, even if there
     * was another view in the IFocusArea focused before.
     */
    void setDefaultFocusOverridesHistory(boolean override);
}
