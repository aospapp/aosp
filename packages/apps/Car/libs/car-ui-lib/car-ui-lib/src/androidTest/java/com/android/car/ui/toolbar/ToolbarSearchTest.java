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
package com.android.car.ui.toolbar;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isRoot;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.car.ui.actions.ViewActions.waitForNoMatchingView;
import static com.android.car.ui.actions.ViewActions.waitForView;
import static com.android.car.ui.core.CarUi.MIN_TARGET_API;
import static com.android.car.ui.matchers.ViewMatchers.doesNotExistOrIsNotDisplayed;
import static com.android.car.ui.matchers.ViewMatchers.withDrawable;

import static junit.framework.TestCase.fail;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.annotation.TargetApi;
import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.widget.EditText;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.ui.core.CarUi;
import com.android.car.ui.pluginsupport.PluginFactorySingleton;
import com.android.car.ui.test.R;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Unit test for the search functionality in {@link ToolbarController}.
 */
@SuppressWarnings("AndroidJdkLibsChecker")
@RunWith(Parameterized.class)
@TargetApi(MIN_TARGET_API)
public class ToolbarSearchTest {
    @Parameterized.Parameters
    public static Object[][] data() {
        // It's important to do no plugin first, so that the plugin will
        // still be enabled when this test finishes
        return new Object[][]{
                new Object[]{false, SearchMode.SEARCH},
                new Object[]{false, SearchMode.EDIT},
                new Object[]{true, SearchMode.SEARCH},
                new Object[]{true, SearchMode.EDIT},
        };
    }

    private final SearchMode mSearchMode;
    private final boolean mIsPluginEnabled;

    public ToolbarSearchTest(boolean pluginEnabled, SearchMode searchMode) {
        PluginFactorySingleton.setPluginEnabledForTesting(pluginEnabled);
        mSearchMode = searchMode;
        mIsPluginEnabled = pluginEnabled;
    }

    @Rule
    public final ActivityScenarioRule<ToolbarTestActivity> mScenarioRule =
            new ActivityScenarioRule<>(ToolbarTestActivity.class);

    @Test
    public void test_setSearchQueryBeforeSearchMode_doesNothing() {
        runWithToolbar(toolbar -> toolbar.setSearchQuery("Hello, world!"));
        onView(withText("Hello, world!")).check(doesNotExistOrIsNotDisplayed());
    }

    @Test
    public void test_setSearchQueryAfterSearchMode_showsQuery() {
        runWithToolbar(toolbar -> {
            toolbar.setSearchMode(mSearchMode);
            toolbar.setSearchQuery("Hello, world!");
        });
        onView(withText("Hello, world!")).check(matches(isDisplayed()));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void test_registerSearchListeners_callsListeners() {
        Consumer<String> searchListener = mock(Consumer.class);
        Runnable searchCompletedListener = mock(Runnable.class);
        runWithToolbar(toolbar -> {
            toolbar.setSearchMode(mSearchMode);
            toolbar.registerSearchListener(searchListener);
            toolbar.registerSearchCompletedListener(searchCompletedListener);
        });
        onView(isAssignableFrom(EditText.class)).perform(typeText("hello\n"));

        verify(searchListener).accept("h");
        verify(searchListener).accept("he");
        verify(searchListener).accept("hel");
        verify(searchListener).accept("hell");
        verify(searchListener).accept("hello");
        verify(searchCompletedListener).run();
    }

    @Test
    public void test_setSearchIcon_showsIcon() {
        runWithToolbar(toolbar -> {
            toolbar.setSearchMode(mSearchMode);
            toolbar.setLogo(R.drawable.ic_launcher);
            toolbar.setSearchIcon(R.drawable.ic_settings_gear);
        });

        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        onView(withDrawable(context, R.drawable.ic_launcher)).check(matches(isDisplayed()));
        if (mSearchMode == SearchMode.SEARCH) {
            onView(withDrawable(context, R.drawable.ic_settings_gear))
                    .check(matches(isDisplayed()));
        } else {
            onView(withDrawable(context, R.drawable.ic_settings_gear))
                    .check(doesNotExistOrIsNotDisplayed());
        }
    }

    @Test
    public void test_setSearchIconTo0_removesIcon() {
        runWithToolbar(toolbar -> {
            toolbar.setSearchMode(mSearchMode);
            toolbar.setSearchIcon(R.drawable.ic_settings_gear);
            toolbar.setSearchIcon(0);
        });

        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        onView(withDrawable(context, R.drawable.ic_settings_gear))
                .check(doesNotExistOrIsNotDisplayed());
    }

    @Test
    public void test_setSearchModeDisabled_hidesSearchBox() {
        runWithToolbar(toolbar -> toolbar.setSearchMode(mSearchMode));
        onView(isAssignableFrom(EditText.class)).check(matches(isDisplayed()));

        runWithToolbar(toolbar -> toolbar.setSearchMode(SearchMode.DISABLED));
        onView(isAssignableFrom(EditText.class)).check(doesNotExistOrIsNotDisplayed());
    }

    @Test
    public void test_setSearchHint_isDisplayed() {
        runWithToolbar((toolbar) -> {
            toolbar.setSearchHint("Test search hint");
            toolbar.setSearchMode(mSearchMode);
        });

        onView(withHint("Test search hint")).check(matches(isDisplayed()));

        runWithToolbar((toolbar) -> {
            toolbar.setSearchHint(R.string.test_string_test_title);
            toolbar.setSearchMode(mSearchMode);
        });

        onView(withHint("Test title!")).check(matches(isDisplayed()));
    }

    @Test
    public void test_setSearchHint_uxRestricted() {
        // Rely on test_setSearchHint_uxRestricted_injectedEvents for plugin testing
        if (mIsPluginEnabled) {
            return;
        }

        runWithToolbar((toolbar) -> {
            toolbar.setSearchHint("Test search hint");
            toolbar.setSearchMode(mSearchMode);
        });

        onView(withHint("Test search hint")).check(matches(isDisplayed()));

        Context context = InstrumentationRegistry.getInstrumentation().getContext();

        mScenarioRule.getScenario().onActivity(activity -> {
            CarUxRestrictions keyboardRestriction = new CarUxRestrictions.Builder(true,
                    CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD, 0).build();
            CarUxRestrictionsUtil.getInstance(activity).setUxRestrictions(keyboardRestriction);
        });

        onView(withHint(context.getString(R.string.car_ui_restricted_while_driving))).check(
                matches(isDisplayed()));

        mScenarioRule.getScenario().onActivity(activity -> {
            CarUxRestrictions fullRestriction = new CarUxRestrictions.Builder(true,
                    CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED, 0).build();
            CarUxRestrictionsUtil.getInstance(activity).setUxRestrictions(fullRestriction);
        });

        onView(withHint(context.getString(R.string.car_ui_restricted_while_driving))).check(
                matches(isDisplayed()));

        mScenarioRule.getScenario().onActivity(activity -> {
            CarUxRestrictions baselineRestriction = new CarUxRestrictions.Builder(true,
                    CarUxRestrictions.UX_RESTRICTIONS_BASELINE, 0).build();
            CarUxRestrictionsUtil.getInstance(activity).setUxRestrictions(baselineRestriction);
        });

        onView(withHint("Test search hint")).check(matches(isDisplayed()));
    }

    @Test
    public void test_setSearchHint_uxRestricted_injectedEvents() {
        try {
            runWithToolbar((toolbar) -> {
                toolbar.setSearchHint("Test search hint");
                toolbar.setSearchMode(mSearchMode);
            });

            onView(withHint("Test search hint")).check(matches(isDisplayed()));

            injectDrivingState();
            onView(isRoot()).perform(waitForNoMatchingView(withHint("Test search hint"), 1500));

            runWithToolbar((toolbar) -> toolbar.setSearchHint("New hint"));
            onView(isRoot()).perform(waitForNoMatchingView(withHint("New hint"), 1500));

            injectParkedState();
            onView(isRoot()).perform(waitForView(withHint("New hint"), 1500));
        } finally {
            // Always return to parked state after tests to ensure future tests are not
            // influenced by driving state.
            injectParkedState();
        }
    }

    @Test
    public void test_setSearchHint_uxRestricted_injectedEvents_startRestricted() {
        injectDrivingState();

        try {
            runWithToolbar((toolbar) -> {
                toolbar.setSearchHint("Test search hint");
                toolbar.setSearchMode(mSearchMode);
            });

            onView(isRoot()).perform(waitForNoMatchingView(withHint("Test search hint"), 1500));

            injectParkedState();
            onView(isRoot()).perform(waitForView(withHint("Test search hint"), 1500));
        } finally {
            // Always return to parked state after tests to ensure future tests are not
            // influenced by driving state.
            injectParkedState();
        }
    }

    private void runWithToolbar(Consumer<ToolbarController> toRun) {
        mScenarioRule.getScenario().onActivity(activity -> {
            ToolbarController toolbar = CarUi.requireToolbar(activity);
            toRun.accept(toolbar);
        });
    }

    private void injectDrivingState() {
        Runtime runtime = Runtime.getRuntime();
        try {
            // Set gear to Drive
            runtime.exec("cmd car_service inject-vhal-event 0x11400400 8");
            // Set speed to 30 meters per second
            runtime.exec("cmd car_service inject-vhal-event 0x11600207 30 -t 2000");
            // Remove parking break
            runtime.exec("cmd car_service inject-vhal-event 0x11200402 false");
        } catch (IOException e) {
            fail(e.getLocalizedMessage());
        }
    }

    private void injectParkedState() {
        Runtime runtime = Runtime.getRuntime();
        try {
            // Set speed to 0
            runtime.exec("cmd car_service inject-vhal-event 0x11600207 0");
            // Set gear to Parked
            runtime.exec("cmd car_service inject-vhal-event 0x11400400 4");
            // Set parking break
            runtime.exec("cmd car_service inject-vhal-event 0x11200402 true");
        } catch (IOException e) {
            fail(e.getLocalizedMessage());
        }
    }
}
