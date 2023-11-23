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

package com.android.car.ui.preference;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import java.util.function.Consumer;

/**
 * An interface for preferences that can be clicked while disabled.
 *
 * These preference will appear disabled but will still perform an action when clicked.
 */
@SuppressWarnings("AndroidJdkLibsChecker")
public interface ClickableWhileDisabledPreference {
    /** Sets this preference as clickable while disabled or not */
    void setClickableWhileDisabled(boolean clickableWhileDisabled);

    /** Returns if this preference is currently clickable while disabled. */
    boolean isClickableWhileDisabled();

    /** Sets a listener to be called if the preference is clicked while it is disabled */
    void setDisabledClickListener(@Nullable Consumer<Preference> listener);

    /** Gets the listener to be called if the preference is clicked while it is disabled */
    @Nullable
    Consumer<Preference> getDisabledClickListener();
}
