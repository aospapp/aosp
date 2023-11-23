/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.server.wm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.app.WindowConfiguration;
import android.util.DisplayMetrics;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.android.compatibility.common.util.ShellUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests {@link android.view.Window#setCloseOnTouchOutside(boolean)} through exposed Activity API.
 */
@RunWith(AndroidJUnit4.class)
public class CloseOnOutsideTests {

    @Rule
    public final ActivityScenarioRule<CloseOnOutsideTestActivity> mScenarioRule =
            new ActivityScenarioRule<>(CloseOnOutsideTestActivity.class);

    private CloseOnOutsideTestActivity mTestActivity;

    @Before
    public void setup() {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_FULLSCREEN);
        mScenarioRule.getScenario().launch(CloseOnOutsideTestActivity.class, options.toBundle())
            .onActivity(activity -> {
                mTestActivity = activity;
            });
    }

    @Test
    public void withDefaults() {
        touchAndAssert(false /* shouldBeFinishing */);
    }

    @Test
    public void finishTrue() {
        mTestActivity.setFinishOnTouchOutside(true);
        touchAndAssert(true /* shouldBeFinishing */);
    }

    @Test
    public void finishFalse() {
        mTestActivity.setFinishOnTouchOutside(false);
        touchAndAssert(false /* shouldBeFinishing */);
    }

    // Tap the bottom right and check the Activity is finishing
    private void touchAndAssert(boolean shouldBeFinishing) {
        DisplayMetrics displayMetrics = mTestActivity.getResources().getDisplayMetrics();
        int width = (int) (displayMetrics.widthPixels * 0.875f);
        int height = (int) (displayMetrics.heightPixels * 0.875f);

        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        // To be safe, make sure nothing else is finishing the Activity
        instrumentation.runOnMainSync(() -> assertFalse(mTestActivity.isFinishing()));

        ShellUtils.runShellCommand("input tap %d %d", width, height);

        instrumentation.runOnMainSync(
                () -> assertEquals(shouldBeFinishing, mTestActivity.isFinishing()));
    }
}
