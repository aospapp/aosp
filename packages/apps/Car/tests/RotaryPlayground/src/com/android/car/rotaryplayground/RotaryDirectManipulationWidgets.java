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

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.NumberPicker;
import android.widget.TimePicker;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fragment that demos rotary interactions directly manipulating the state of UI widgets such as a
 * {@link android.widget.SeekBar}, {@link android.widget.DatePicker}, and
 * {@link android.widget.RadialTimePickerView}, and {@link DirectManipulationView} in an
 * application window.
 */
public class RotaryDirectManipulationWidgets extends Fragment {

    // TODO(agathaman): refactor a common class that takes in a fragment xml id and inflates it, to
    //  share between this and RotaryCards.

    private final DirectManipulationState mDirectManipulationMode = new DirectManipulationState();

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.rotary_direct_manipulation, container, false);

        DirectManipulationView dmv = view.findViewById(R.id.direct_manipulation_view);
        registerDirectManipulationHandler(dmv,
                new DirectManipulationHandler.Builder(mDirectManipulationMode)
                        .setNudgeHandler(new DirectManipulationView.NudgeHandler())
                        .setRotationHandler(new DirectManipulationView.RotationHandler())
                        .build());


        TimePicker spinnerTimePicker = view.findViewById(R.id.spinner_time_picker);
        registerDirectManipulationHandler(spinnerTimePicker,
                new DirectManipulationHandler.Builder(mDirectManipulationMode)
                        .setNudgeHandler(new TimePickerNudgeHandler())
                        .build());

        DirectManipulationHandler numberPickerListener =
                new DirectManipulationHandler.Builder(mDirectManipulationMode)
                        .setNudgeHandler(new NumberPickerNudgeHandler())
                        .setRotationHandler((v, motionEvent) -> {
                            float scroll = motionEvent.getAxisValue(MotionEvent.AXIS_SCROLL);
                            View focusedView = v.findFocus();
                            if (focusedView instanceof NumberPicker) {
                                NumberPicker numberPicker = (NumberPicker) focusedView;
                                numberPicker.setValue(numberPicker.getValue() + Math.round(scroll));
                                return true;
                            }
                            return false;
                        })
                        .build();

        List<NumberPicker> numberPickers = new ArrayList<>();
        getNumberPickerDescendants(numberPickers, spinnerTimePicker);
        for (int i = 0; i < numberPickers.size(); i++) {
            registerDirectManipulationHandler(numberPickers.get(i), numberPickerListener);
        }

        registerDirectManipulationHandler(view.findViewById(R.id.clock_time_picker),
                new DirectManipulationHandler.Builder(
                        mDirectManipulationMode)
                        // TODO(pardis): fix the behavior here. It does not nudge as expected.
                        .setNudgeHandler(new TimePickerNudgeHandler())
                        .setRotationHandler((v, motionEvent) -> {
                            // TODO(pardis): fix the behavior here. It does not scroll as intended.
                            float scroll = motionEvent.getAxisValue(MotionEvent.AXIS_SCROLL);
                            View focusedView = v.findFocus();
                            scrollView(focusedView, scroll);
                            return true;
                        })
                        .build());

        registerDirectManipulationHandler(
                view.findViewById(R.id.seek_bar),
                new DirectManipulationHandler.Builder(mDirectManipulationMode)
                        .setRotationHandler(new DelegateToA11yScrollRotationHandler())
                        .build());

        registerDirectManipulationHandler(
                view.findViewById(R.id.radial_time_picker),
                new DirectManipulationHandler.Builder(mDirectManipulationMode)
                        .setRotationHandler(new DelegateToA11yScrollRotationHandler())
                        .build());

        return view;
    }

    @Override
    public void onPause() {
        if (mDirectManipulationMode.isActive()) {
            // To ensure that the user doesn't get stuck in direct manipulation mode, disable direct
            // manipulation mode when the fragment is not interactive (e.g., a dialog shows up).
            mDirectManipulationMode.disable();
        }
        super.onPause();
    }

    /**
     * Register the given {@link DirectManipulationHandler} as both the
     * {@link View.OnKeyListener} and {@link View.OnGenericMotionListener} for the given
     * {@link View}.
     * <p>
     * Handles a {@link Nullable} {@link View} so that it can be used directly with the output of
     * methods such as {@code findViewById}.
     */
    private void registerDirectManipulationHandler(@Nullable View view,
            DirectManipulationHandler handler) {
        if (view == null) {
            return;
        }
        view.setOnKeyListener(handler);
        view.setOnGenericMotionListener(handler);
    }

    /**
     * A {@link View.OnGenericMotionListener} implementation that delegates handling the
     * {@link MotionEvent} to the {@link AccessibilityNodeInfo#ACTION_SCROLL_FORWARD}
     * or {@link AccessibilityNodeInfo#ACTION_SCROLL_BACKWARD} depending on the sign of the
     * {@link MotionEvent#AXIS_SCROLL} value.
     */
    private static class DelegateToA11yScrollRotationHandler
            implements View.OnGenericMotionListener {

        @Override
        public boolean onGenericMotion(View v, MotionEvent event) {
            scrollView(v, event.getAxisValue(MotionEvent.AXIS_SCROLL));
            return true;
        }
    }

    /**
     * A shortcut to "scrolling" a given {@link View} by delegating to A11y actions. Most useful
     * in scenarios that we do not have API access to the descendants of a {@link ViewGroup} but
     * also handy for other cases so we don't have to re-implement the behaviors if we already know
     * that suitable A11y actions exist and are implemented for the relevant views.
     */
    private static void scrollView(View view, float scroll) {
        for (int i = 0; i < Math.round(Math.abs(scroll)); i++) {
            view.performAccessibilityAction(
                    scroll > 0
                            ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                    /* arguments= */ null);
        }
    }

    /**
     * A {@link View.OnKeyListener} for handling Direct Manipulation rotary nudge behavior
     * for a {@link NumberPicker}.
     *
     * <p>
     * This handler expects that it is being used in Direct Manipulation mode, i.e. as a directional
     * delegate through a {@link DirectManipulationHandler} which can invoke it at the
     * appropriate times.
     * <p>
     * Only handles the following {@link KeyEvent}s and in the specified way below:
     *     <ul>
     *         <li>{@link KeyEvent#KEYCODE_DPAD_UP} - explicitly disabled
     *         <li>{@link KeyEvent#KEYCODE_DPAD_DOWN} - explicitly disabled
     *         <li>{@link KeyEvent#KEYCODE_DPAD_LEFT} - nudges left
     *         <li>{@link KeyEvent#KEYCODE_DPAD_RIGHT} - nudges right
     *     </ul>
     * <p>
     * This handler only allows nudging left and right to other {@link View} objects within the same
     * {@link TimePicker}.
     */
    private static class NumberPickerNudgeHandler implements View.OnKeyListener {

        private static final Map<Integer, Integer> KEYCODE_TO_DIRECTION_MAP;

        static {
            Map<Integer, Integer> map = new HashMap<>();
            map.put(KeyEvent.KEYCODE_DPAD_UP, View.FOCUS_UP);
            map.put(KeyEvent.KEYCODE_DPAD_DOWN, View.FOCUS_DOWN);
            map.put(KeyEvent.KEYCODE_DPAD_LEFT, View.FOCUS_LEFT);
            map.put(KeyEvent.KEYCODE_DPAD_RIGHT, View.FOCUS_RIGHT);
            KEYCODE_TO_DIRECTION_MAP = Collections.unmodifiableMap(map);
        }

        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            boolean isActionUp = event.getAction() == KeyEvent.ACTION_UP;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // Disable by consuming the event and not doing anything.
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (isActionUp) {
                        int direction = KEYCODE_TO_DIRECTION_MAP.get(keyCode);
                        View nextView = v.focusSearch(direction);
                        if (areInTheSameTimePicker(v, nextView)) {
                            nextView.requestFocus(direction);
                        }
                    }
                    return true;
                default:
                    return false;
            }
        }

        private static boolean areInTheSameTimePicker(@Nullable View view1, @Nullable View view2) {
            if (view1 == null || view2 == null) {
                return false;
            }
            TimePicker view1Ancestor = getTimePickerAncestor(view1);
            TimePicker view2Ancestor = getTimePickerAncestor(view2);
            return view1Ancestor == view2Ancestor;
        }

        /*
         * A generic version of this may come in handy as a library. Any {@link ViewGroup} view that
         * supports Direct Manipulation mode will need something like this to ensure nudge actions
         * don't result in navigating outside the parent {link ViewGroup} that is in Direct
         * Manipulation mode.
         */
        @Nullable
        private static TimePicker getTimePickerAncestor(@Nullable View view) {
            if (view instanceof TimePicker) {
                return (TimePicker) view;
            }
            ViewParent viewParent = view.getParent();
            if (viewParent instanceof View) {
                return getTimePickerAncestor((View) viewParent);
            }
            return null;
        }
    }

    /**
     * A {@link View.OnKeyListener} for handling Direct Manipulation rotary nudge behavior
     * for a {@link TimePicker}.
     * <p>
     * This handler expects that it is being used in Direct Manipulation mode, i.e. as a
     * directional delegate through a {@link DirectManipulationHandler} which can invoke it at the
     * appropriate times.
     * <p>
     * Only handles the following {@link KeyEvent}s and in the specified way below:
     *     <ul>
     *         <li>{@link KeyEvent#KEYCODE_DPAD_UP} - explicitly disabled
     *         <li>{@link KeyEvent#KEYCODE_DPAD_DOWN} - explicitly disabled
     *         <li>{@link KeyEvent#KEYCODE_DPAD_LEFT} - passes focus to a descendant view
     *         <li>{@link KeyEvent#KEYCODE_DPAD_RIGHT} - passes focus to a descendant view
     *     </ul>
     * <p>
     * When passing focus to a descendant, looks for all {@link NumberPicker} views and passes
     * focus to the first one found.
     * <p>
     * This handler expects that any descendant {@link NumberPicker} objects have registered
     * their own Direct Manipulation handlers via a {@link DirectManipulationHandler}.
     */
    private static class TimePickerNudgeHandler
            implements View.OnKeyListener {

        @Override
        public boolean onKey(View view, int keyCode, KeyEvent keyEvent) {
            if (!(view instanceof TimePicker)) {
                return false;
            }
            boolean isActionUp = keyEvent.getAction() == KeyEvent.ACTION_UP;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // TODO(pardis): if intending to reuse this for both time pickers,
                    //  then need to make sure it can distinguish between the two. For clock
                    //  we may need up and down.
                    // Disable by consuming the event and not doing anything.
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (isActionUp) {
                        TimePicker timePicker = (TimePicker) view;
                        List<NumberPicker> numberPickers = new ArrayList<>();
                        getNumberPickerDescendants(numberPickers, timePicker);
                        if (numberPickers.isEmpty()) {
                            return false;
                        }
                        timePicker.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                        numberPickers.get(0).requestFocus();
                    }
                    return true;
                default:
                    return false;
            }
        }

    }

    /*
     * We don't have API access to the inner {@link View}s of a {@link TimePicker}. We do know based
     * on {@code frameworks/base/core/res/res/layout/time_picker_legacy_material.xml} that a
     * {@link TimePicker} that is in spinner mode will be using {@link NumberPicker}s internally,
     * and that's what we rely on here.
     */
    private static void getNumberPickerDescendants(List<NumberPicker> numberPickers, ViewGroup v) {
        for (int i = 0; i < v.getChildCount(); i++) {
            View child = v.getChildAt(i);
            if (child instanceof NumberPicker) {
                numberPickers.add((NumberPicker) child);
            } else if (child instanceof ViewGroup) {
                getNumberPickerDescendants(numberPickers, (ViewGroup) child);
            }
        }
    }
}
