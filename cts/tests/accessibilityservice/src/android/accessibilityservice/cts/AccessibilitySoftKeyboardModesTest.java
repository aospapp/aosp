/**
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.accessibilityservice.cts;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_AUTO;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_HIDDEN;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD;
import static android.accessibilityservice.cts.utils.CtsTestUtils.runIfNotNull;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.accessibility.cts.common.InstrumentedAccessibilityService;
import android.accessibilityservice.AccessibilityService.SoftKeyboardController;
import android.accessibilityservice.AccessibilityService.SoftKeyboardController.OnShowModeChangedListener;
import android.accessibilityservice.cts.activities.AccessibilityTestActivity;
import android.accessibilityservice.cts.utils.AsyncUtils;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.platform.test.annotations.AppModeFull;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test cases for testing the accessibility APIs for interacting with the soft keyboard show mode.
 */
@RunWith(AndroidJUnit4.class)
@AppModeFull
public class AccessibilitySoftKeyboardModesTest {
    private int mLastCallbackValue;

    private final Instrumentation mInstrumentation = InstrumentationRegistry.getInstrumentation();
    private InstrumentedAccessibilityService mService;
    private final Object mLock = new Object();
    private final OnShowModeChangedListener mListener = (c, showMode) -> {
        synchronized (mLock) {
            mLastCallbackValue = showMode;
            mLock.notifyAll();
        }
    };



    @Before
    public void setUp() throws Exception {
        mService = InstrumentedAccessibilityService.enableService(mInstrumentation,
                InstrumentedAccessibilityService.class);
    }

    @After
    public void tearDown() throws Exception {
        runIfNotNull(mService, service -> service.runOnServiceSync(service::disableSelf));
    }

    @Test
    public void testApiReturnValues_shouldChangeValueOnRequestAndSendCallback() throws Exception {
        final SoftKeyboardController controller = mService.getSoftKeyboardController();

        // Confirm that we start in the default state
        assertEquals(SHOW_MODE_AUTO, controller.getShowMode());

        controller.addOnShowModeChangedListener(mListener);
        assertCanSetAndGetShowModeAndCallbackHappens(SHOW_MODE_HIDDEN, mService);
        assertCanSetAndGetShowModeAndCallbackHappens(SHOW_MODE_IGNORE_HARD_KEYBOARD, mService);
        assertCanSetAndGetShowModeAndCallbackHappens(SHOW_MODE_AUTO, mService);

        // Make sure we can remove our listener.
        assertTrue(controller.removeOnShowModeChangedListener(mListener));
    }

    @Test
    public void secondServiceChangingTheShowMode_updatesModeAndNotifiesFirstService()
            throws Exception {

        final SoftKeyboardController controller = mService.getSoftKeyboardController();
        // Confirm that we start in the default state
        assertEquals(SHOW_MODE_AUTO, controller.getShowMode());

        final InstrumentedAccessibilityService secondService =
                StubAccessibilityButtonService.enableSelf(mInstrumentation);
        try {
            // Listen on the first service
            controller.addOnShowModeChangedListener(mListener);
            assertCanSetAndGetShowModeAndCallbackHappens(SHOW_MODE_HIDDEN, mService);

            // Change the mode on the second service
            assertCanSetAndGetShowModeAndCallbackHappens(SHOW_MODE_IGNORE_HARD_KEYBOARD,
                    secondService);
        } finally {
            secondService.runOnServiceSync(() -> secondService.disableSelf());
        }

        // Shutting down the second service, which was controlling the mode, should put us back
        // to the default
        waitForCallbackValueWithLock(SHOW_MODE_AUTO);
        final int showMode = mService.getOnService(() -> controller.getShowMode());
        assertEquals(SHOW_MODE_AUTO, showMode);
    }

    private void assertCanSetAndGetShowModeAndCallbackHappens(
            int mode, InstrumentedAccessibilityService service)
            throws Exception  {
        final SoftKeyboardController controller = service.getSoftKeyboardController();
        mLastCallbackValue = -1;
        final boolean setShowModeReturns =
                service.getOnService(() -> controller.setShowMode(mode));
        assertTrue(setShowModeReturns);
        waitForCallbackValueWithLock(mode);
        assertEquals(mode, controller.getShowMode());
    }

    private void waitForCallbackValueWithLock(int expectedValue) throws Exception {
        long timeoutTimeMillis = SystemClock.uptimeMillis() + AsyncUtils.DEFAULT_TIMEOUT_MS;

        while (SystemClock.uptimeMillis() < timeoutTimeMillis) {
            synchronized(mLock) {
                if (mLastCallbackValue == expectedValue) {
                    return;
                }
                try {
                    mLock.wait(timeoutTimeMillis - SystemClock.uptimeMillis());
                } catch (InterruptedException e) {
                    // Wait until timeout.
                }
            }
        }

        throw new IllegalStateException("last callback value <" + mLastCallbackValue
                + "> does not match expected value < " + expectedValue + ">");
    }

    /**
     * Activity for testing the AccessibilityService API for hiding and showing the soft keyboard.
     */
    public static class SoftKeyboardModesActivity extends AccessibilityTestActivity {
        public SoftKeyboardModesActivity() {
            super();
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.accessibility_soft_keyboard_modes_test);
        }
    }
}
