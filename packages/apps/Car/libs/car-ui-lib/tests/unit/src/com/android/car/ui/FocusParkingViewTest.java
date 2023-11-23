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

package com.android.car.ui;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.rule.ActivityTestRule;

import com.android.car.ui.tests.unit.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Unit test for {@link FocusParkingView}. */
public class FocusParkingViewTest {

    private static final long WAIT_TIME_MS = 3000;

    @Rule
    public ActivityTestRule<FocusParkingViewTestActivity> mActivityRule =
            new ActivityTestRule<>(FocusParkingViewTestActivity.class);

    private FocusParkingViewTestActivity mActivity;

    @Before
    public void setUp() {
        mActivity = mActivityRule.getActivity();
    }

    @Test
    public void testFocusParkingViewCanTakeFocus() throws Exception {
        FocusParkingView focusParkingView = mActivity.findViewById(R.id.focus_parking);

        CountDownLatch latch = new CountDownLatch(1);
        focusParkingView.post(() -> {
            focusParkingView.requestFocus();
            focusParkingView.post(() -> {
                latch.countDown();
            });
        });
        latch.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS);

        assertThat(focusParkingView.isFocused()).isTrue();
    }
    @Test
    public void testFocusParkingViewFocusedWhenWindowLostFocus() throws Exception {
        FocusParkingView focusParkingView = mActivity.findViewById(R.id.focus_parking);
        assertThat(focusParkingView.isFocused()).isFalse();

        CountDownLatch latch = new CountDownLatch(1);
        focusParkingView.post(() -> {
            focusParkingView.onWindowFocusChanged(false);
            focusParkingView.post(() -> {
                latch.countDown();
            });
        });
        latch.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS);

        assertThat(focusParkingView.isFocused()).isTrue();
    }
}
