/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.car.carlauncher;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.car.drivingstate.CarUxRestrictionsManager;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;


/**
 * Programmatic tests for AppGridActivity and AppGridScrollBar
 */
@RunWith(AndroidJUnit4.class)
public class AppGridActivityTest {
    private ActivityScenario<AppGridActivity> mActivityScenario;
    private CarUxRestrictionsManager mCarUxRestrictionsManager;
    private PageIndicator mPageIndicator;

    @After
    public void tearDown() {
        if (mActivityScenario != null) {
            mActivityScenario.close();
        }
    }

    @Test
    public void onCreate_appGridRecyclerView_isVisible() {
        mActivityScenario = ActivityScenario.launch(AppGridActivity.class);
        onView(withId(R.id.apps_grid)).check(matches(isDisplayed()));
        onView(withId(R.id.page_indicator_container)).check(matches(isDisplayed()));
    }

    @Test
    public void onResume_ScrollStateIsUpdated() {
        mActivityScenario = ActivityScenario.launch(AppGridActivity.class);
        mActivityScenario.onActivity(activity -> {
            mPageIndicator = mock(PageIndicator.class);
            activity.setPageIndicator(mPageIndicator);
        });
        mActivityScenario.moveToState(Lifecycle.State.RESUMED);
        onView(withId(R.id.apps_grid)).check(matches(isDisplayed()));
        onView(withId(R.id.page_indicator_container)).check(matches(isDisplayed()));
        verify(mPageIndicator, times(1)).updatePageCount(anyInt());

    }

    @Test
    public void onStop_CarUxRestrictionsManager_unregisterListener() {
        mActivityScenario = ActivityScenario.launch(AppGridActivity.class);
        mActivityScenario.onActivity(activity -> {
            mCarUxRestrictionsManager = mock(CarUxRestrictionsManager.class);
            activity.setCarUxRestrictionsManager(mCarUxRestrictionsManager);
        });
        mActivityScenario.moveToState(Lifecycle.State.DESTROYED);
        verify(mCarUxRestrictionsManager, times(1)).unregisterListener();
    }
}
