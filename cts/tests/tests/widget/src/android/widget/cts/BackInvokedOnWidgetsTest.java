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

package android.widget.cts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.graphics.Color;
import android.support.test.uiautomator.UiDevice;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.PopupWindow;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.GestureNavRule;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class BackInvokedOnWidgetsTest {

    public static final int TIMEOUT = 1000;
    public static final String PACKAGE_NAME = "android.widget.cts";
    @ClassRule
    public static GestureNavRule rule = new GestureNavRule();

    @Rule
    public ActivityScenarioRule<BackInvokedOnWidgetsActivity> scenarioRule =
            new ActivityScenarioRule<>(BackInvokedOnWidgetsActivity.class);
    private Instrumentation mInstrumentation;
    private UiDevice mUiDevice;

    @Before
    public void setUp() {
        rule.assumeGestureNavigationMode();
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mUiDevice = UiDevice.getInstance(mInstrumentation);
    }

    @Test
    public void popupWindowDismissedOnBackGesture() {
        PopupWindow[] popupWindow = new PopupWindow[1];
        scenarioRule.getScenario().onActivity(activity -> {
            FrameLayout contentView = new FrameLayout(activity);
            contentView.setBackgroundColor(Color.RED);
            PopupWindow popup = new PopupWindow(contentView, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);

            // Ensure the window can get the focus by marking the views as focusable
            popup.setFocusable(true);
            contentView.setFocusable(true);
            popup.showAtLocation(activity.getContentView(), Gravity.FILL, 0, 0);
            popupWindow[0] = popup;
        });

        mUiDevice.waitForWindowUpdate(PACKAGE_NAME, TIMEOUT);
        assertTrue("PopupWindow should be visible", popupWindow[0].isShowing());
        doBackGesture();
        scenarioRule.getScenario().onActivity(
                activity -> assertTrue("Activity should still be visible",
                        activity.getContentView().isVisibleToUser()));
        assertFalse("PopupWindow should not be visible", popupWindow[0].isShowing());
    }

    /**
     * Do a back gesture. (Swipe)
     */
    private void doBackGesture() {
        int midHeight = mUiDevice.getDisplayHeight() / 2;
        int midWidth = mUiDevice.getDisplayWidth() / 2;
        mUiDevice.swipe(0, midHeight, midWidth, midHeight, 100);
        mUiDevice.waitForIdle();
    }
}
