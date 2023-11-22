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
 * limitations under the License
 */

package android.server.wm;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.server.wm.UiDeviceUtils.pressHomeButton;
import static android.server.wm.UiDeviceUtils.pressUnlockButton;
import static android.server.wm.UiDeviceUtils.pressWakeupButton;
import static android.view.Display.DEFAULT_DISPLAY;

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;

import org.junit.Before;

import javax.annotation.concurrent.GuardedBy;

public class WindowManagerTestBase {
    static final long TIMEOUT_WINDOW_FOCUS_CHANGED = 1000; // milliseconds

    @Before
    public void setupBase() {
        pressWakeupButton();
        pressUnlockButton();
        pressHomeButton();
    }

    static <T extends FocusableActivity> T startActivity(Class<T> cls) throws InterruptedException {
        return startActivity(cls, DEFAULT_DISPLAY);
    }

    static <T extends FocusableActivity> T startActivity(Class<T> cls, int displayId)
            throws InterruptedException {
        final Bundle options = (displayId == DEFAULT_DISPLAY
                ? null : ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
        final T activity = (T) getInstrumentation().startActivitySync(
                new Intent(getInstrumentation().getTargetContext(), cls)
                        .addFlags(FLAG_ACTIVITY_NEW_TASK), options);
        activity.waitAndAssertWindowFocusState(true /* hasFocus */);
        return activity;
    }

    static class FocusableActivity extends Activity {
        private final Object mLockWindowFocus = new Object();

        @GuardedBy("mLockWindowFocus")
        private boolean mHasWindowFocus;

        final String getLogTag() {
            return ComponentNameUtils.getLogTag(getComponentName());
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            synchronized (mLockWindowFocus) {
                mHasWindowFocus = hasFocus;
                mLockWindowFocus.notify();
            }
        }

        void assertWindowFocusState(boolean hasFocus) {
            synchronized (mLockWindowFocus) {
                assertEquals(getLogTag() + " must" + (hasFocus ? "" : " not")
                        + " have window focus.", hasFocus, mHasWindowFocus);
            }
        }

        void waitAndAssertWindowFocusState(boolean hasFocus) throws InterruptedException {
            synchronized (mLockWindowFocus) {
                if (mHasWindowFocus != hasFocus) {
                    mLockWindowFocus.wait(TIMEOUT_WINDOW_FOCUS_CHANGED);
                }
            }
            assertWindowFocusState(hasFocus);
        }
    }
}
