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

package com.android.car.rotaryplayground;

import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.ui.utils.DirectManipulationHelper;

/**
 * Keeps track of the state of "direct manipulation" Rotary mode for this application window by
 * tracking a reference to the {@link View} from which the user first enters into "direct
 * manipulation" mode.
 *
 * <p>See {@link DirectManipulationHandler} for a definition of "direct manipulation".
 */
public class DirectManipulationState {


    /** Background color of a view when it's in direct manipulation mode. */
    private static final int BACKGROUND_COLOR_IN_DIRECT_MANIPULATION_MODE = Color.BLUE;

    /** Background color of a view when it's not in direct manipulation mode. */
    private static final int BACKGROUND_COLOR_NOT_IN_DIRECT_MANIPULATION_MODE = Color.TRANSPARENT;

    /** The view that is in direct manipulation mode, or null if none. */
    @Nullable private View mViewInDirectManipulationMode;

    private void setStartingView(@Nullable View view) {
        mViewInDirectManipulationMode = view;
    }

    /**
     * Returns true if Direct Manipulation mode is active, false otherwise.
     */
    public boolean isActive() {
        return mViewInDirectManipulationMode != null;
    }

    /**
     * Enables Direct Manipulation mode, and keeps track of {@code view} as the starting point
     * of this transition.
     * <p>
     * We generally want to give some kind of visual indication that this change has happened. In
     * this example we change the background color of {@code view}.
     *
     * @param view - the {@link View} from which we entered into Direct Manipulation mode.
     */
    public void enable(@NonNull View view) {
        /*
         * A more robust approach would be to fetch the current background color from
         * the view object and store it back onto the View itself using the {@link
         * View#setTag(int, java.lang.Object)} API. This could then be fetched back
         * and used to restore the background color without needing to keep a constant
         * reference to the color here which could fall out of sync with the xml files.
         */
        view.setBackgroundColor(BACKGROUND_COLOR_IN_DIRECT_MANIPULATION_MODE);
        DirectManipulationHelper.enableDirectManipulationMode(view, /* enable= */ true);
        setStartingView(view);
    }

    /**
     * Disables Direct Manipulation mode and restores any visual indicators for the {@link View}
     * from which we entered into Direct Manipulation mode.
     */
    public void disable() {
        mViewInDirectManipulationMode.setBackgroundColor(
                BACKGROUND_COLOR_NOT_IN_DIRECT_MANIPULATION_MODE);
        DirectManipulationHelper.enableDirectManipulationMode(
                mViewInDirectManipulationMode, /* enable= */ false);
        // For ViewGroup objects, restore descendant focusability to FOCUS_BLOCK_DESCENDANTS so
        // during non-Direct Manipulation mode, aka, general rotary navigation, we don't go
        // through the individual inner UI elements.
        if (mViewInDirectManipulationMode instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) mViewInDirectManipulationMode;
            viewGroup.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        setStartingView(null);
    }
}
