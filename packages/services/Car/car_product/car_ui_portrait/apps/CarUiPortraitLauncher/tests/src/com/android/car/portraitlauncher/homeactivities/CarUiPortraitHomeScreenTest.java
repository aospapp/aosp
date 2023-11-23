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

package com.android.car.portraitlauncher.homeactivities;

import static org.junit.Assert.assertEquals;

import android.graphics.Rect;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.car.portraitlauncher.homeactivities.test.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CarUiPortraitHomeScreenTest {
    @Rule
    public ActivityScenarioRule<TestActivity> mActivityRule = new ActivityScenarioRule<>(
            TestActivity.class);
    private TestActivity mActivity;

    @Before
    public void setUp() {
        ActivityScenario<TestActivity> scenario = mActivityRule.getScenario();
        scenario.onActivity(activity -> mActivity = activity);
    }

    @Test
    public void launchNonImmersiveActivity() {
        View v = mActivity.findViewById(R.id.test_container);
        int[] location = new int[2];
        v.getLocationOnScreen(location);
        Rect windowBound =  mActivity.getWindowManager().getCurrentWindowMetrics().getBounds();

        assertEquals(v.getHeight(), v.getWidth() + 1);
        assertEquals(windowBound.left, location[0]);
        assertEquals(windowBound.right, v.getWidth());
        assertEquals(windowBound.top, location[1]);
        assertEquals(windowBound.bottom, windowBound.top + v.getHeight());
    }
}
