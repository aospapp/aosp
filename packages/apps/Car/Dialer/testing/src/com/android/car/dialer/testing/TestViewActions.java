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

package com.android.car.dialer.testing;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.any;

import android.view.View;
import android.widget.TextView;

import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.matcher.ViewMatchers;

import org.hamcrest.Matcher;

/** Custom view actions as a workaround for clicking overlapping UI elements. */
public final class TestViewActions {

    /** A click action by calling the {@link View#performClick()} api. */
    public static ViewAction selfClick() {
        return new ViewAction() {

            @Override
            public Matcher<View> getConstraints() {
                return allOf(ViewMatchers.isDisplayingAtLeast(90), ViewMatchers.isClickable());
            }

            @Override
            public String getDescription() {
                return "Calling View#performClick() api";
            }

            @Override
            public void perform(UiController uiController, View view) {
                view.performClick();
            }
        };
    }

    /** A click action by calling the {@link View#performClick()} api. */
    public static ViewAction selfClickWithoutConstraints() {
        return new ViewAction() {

            @Override
            public Matcher<View> getConstraints() {
                return any(View.class);
            }

            @Override
            public String getDescription() {
                return "Calling View#performClick() api";
            }

            @Override
            public void perform(UiController uiController, View view) {
                view.performClick();
            }
        };
    }

    /** A click action by calling the {@link View#performLongClick()} api. */
    public static ViewAction selfLongClickWithoutConstraints() {
        return new ViewAction() {

            @Override
            public Matcher<View> getConstraints() {
                return any(View.class);
            }

            @Override
            public String getDescription() {
                return "Calling View#performLongClick() api";
            }

            @Override
            public void perform(UiController uiController, View view) {
                view.performLongClick();
            }
        };
    }

    /**
     * A type text action by calling the {@link android.widget.TextView#setText(CharSequence)}
     * api.
     */
    public static ViewAction setTextWithoutConstraints(CharSequence text) {
        return new ViewAction() {

            @Override
            public Matcher<View> getConstraints() {
                return ViewMatchers.isAssignableFrom(TextView.class);
            }

            @Override
            public String getDescription() {
                return "Calling TextView#setText(CharSequence) api";
            }

            @Override
            public void perform(UiController uiController, View view) {
                TextView textView = (TextView) view;
                textView.setText(text);
            }
        };
    }

    /** An action by calling {@link View#bringToFront()}. */
    public static ViewAction bringToFrontAction() {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return any(View.class);
            }

            @Override
            public String getDescription() {
                return "Bring the view to front.";
            }

            @Override
            public void perform(UiController uiController, View view) {
                view.bringToFront();
            }
        };
    }

    /** A wait action to loop the main thread for given time. */
    public static ViewAction waitAction(long millis) {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return any(View.class);
            }

            @Override
            public String getDescription() {
                return String.format("Wait for %d ms", millis);
            }

            @Override
            public void perform(UiController uiController, View view) {
                uiController.loopMainThreadForAtLeast(millis);
            }
        };
    }
}
