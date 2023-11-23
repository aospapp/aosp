/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.server.wm.UiDeviceUtils.pressBackButton;
import static android.server.wm.app.Components.DISMISS_KEYGUARD_ACTIVITY;
import static android.server.wm.app.Components.DISMISS_KEYGUARD_METHOD_ACTIVITY;
import static android.server.wm.app.Components.PIP_ACTIVITY;
import static android.server.wm.app.Components.PipActivity.ACTION_ENTER_PIP;
import static android.server.wm.app.Components.PipActivity.EXTRA_DISMISS_KEYGUARD;
import static android.server.wm.app.Components.PipActivity.EXTRA_ENTER_PIP;
import static android.server.wm.app.Components.PipActivity.EXTRA_SHOW_OVER_KEYGUARD;
import static android.server.wm.app.Components.SHOW_WHEN_LOCKED_ACTIVITY;
import static android.server.wm.app.Components.SHOW_WHEN_LOCKED_ATTR_IME_ACTIVITY;
import static android.server.wm.app.Components.TURN_SCREEN_ON_ATTR_DISMISS_KEYGUARD_ACTIVITY;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
import static androidx.test.InstrumentationRegistry.getInstrumentation;
import static com.android.cts.mockime.ImeEventStreamTestUtils.expectEvent;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.widget.EditText;
import android.widget.LinearLayout;
import com.android.cts.mockime.ImeEventStream;
import com.android.cts.mockime.ImeSettings;
import com.android.cts.mockime.MockImeSession;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;

/**
 * Build/Install/Run:
 *     atest CtsWindowManagerDeviceTestCases:KeyguardLockedTests
 */
@Presubmit
public class KeyguardLockedTests extends KeyguardTestBase {
    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        assumeTrue(supportsSecureLock());
    }

    @Test
    public void testLockAndUnlock() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.setLockCredential()
                    .gotoKeyguard();
            assertTrue(mKeyguardManager.isKeyguardLocked());
            assertTrue(mKeyguardManager.isDeviceLocked());
            assertTrue(mKeyguardManager.isDeviceSecure());
            assertTrue(mKeyguardManager.isKeyguardSecure());
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            lockScreenSession.unlockDevice()
                    .enterAndConfirmLockCredential();
            mAmWmState.waitForKeyguardGone();
            mAmWmState.assertKeyguardGone();
            assertFalse(mKeyguardManager.isDeviceLocked());
            assertFalse(mKeyguardManager.isKeyguardLocked());
        }
    }

    @Test
    public void testDisableKeyguard_thenSettingCredential_reenablesKeyguard_b119322269() {
        final KeyguardManager.KeyguardLock keyguardLock = mContext.getSystemService(
                KeyguardManager.class).newKeyguardLock("KeyguardLockedTests");

        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.gotoKeyguard();
            keyguardLock.disableKeyguard();

            lockScreenSession.setLockCredential();
            mAmWmState.waitForKeyguardShowingAndNotOccluded();
            mAmWmState.assertKeyguardShowingAndNotOccluded();
        } finally {
            keyguardLock.reenableKeyguard();
        }
    }

    @Test
    public void testDismissKeyguard() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.setLockCredential()
                    .gotoKeyguard();
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            launchActivity(DISMISS_KEYGUARD_ACTIVITY);
            lockScreenSession.enterAndConfirmLockCredential();
            mAmWmState.waitForKeyguardGone();
            mAmWmState.assertKeyguardGone();
            mAmWmState.assertVisibility(DISMISS_KEYGUARD_ACTIVITY, true);
        }
    }

    @Test
    public void testDismissKeyguard_whileOccluded() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.setLockCredential()
                    .gotoKeyguard();
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            launchActivity(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.computeState(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);
            launchActivity(DISMISS_KEYGUARD_ACTIVITY);
            lockScreenSession.enterAndConfirmLockCredential();
            mAmWmState.waitForKeyguardGone();
            mAmWmState.assertKeyguardGone();
            mAmWmState.computeState(DISMISS_KEYGUARD_ACTIVITY);
            boolean isDismissTranslucent =
                    mAmWmState.getAmState().isActivityTranslucent(DISMISS_KEYGUARD_ACTIVITY);
            mAmWmState.assertVisibility(DISMISS_KEYGUARD_ACTIVITY, true);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, isDismissTranslucent);
        }
    }

    @Test
    public void testDismissKeyguard_fromShowWhenLocked_notAllowed() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.setLockCredential()
                    .gotoKeyguard();
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            launchActivity(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.computeState(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);
            mBroadcastActionTrigger.dismissKeyguardByFlag();
            lockScreenSession.enterAndConfirmLockCredential();

            // Make sure we stay on Keyguard.
            mAmWmState.assertKeyguardShowingAndOccluded();
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);
        }
    }

    @Test
    public void testDismissKeyguardActivity_method() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.setLockCredential();
            separateTestJournal();
            lockScreenSession.gotoKeyguard();
            mAmWmState.computeState(true);
            assertTrue(mAmWmState.getAmState().getKeyguardControllerState().keyguardShowing);
            launchActivity(DISMISS_KEYGUARD_METHOD_ACTIVITY);
            lockScreenSession.enterAndConfirmLockCredential();
            mAmWmState.waitForKeyguardGone();
            mAmWmState.computeState(DISMISS_KEYGUARD_METHOD_ACTIVITY);
            mAmWmState.assertVisibility(DISMISS_KEYGUARD_METHOD_ACTIVITY, true);
            assertFalse(mAmWmState.getAmState().getKeyguardControllerState().keyguardShowing);
            assertOnDismissSucceeded(DISMISS_KEYGUARD_METHOD_ACTIVITY);
        }
    }

    @Test
    public void testDismissKeyguardActivity_method_cancelled() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.setLockCredential();
            separateTestJournal();
            lockScreenSession.gotoKeyguard();
            mAmWmState.computeState(true);
            assertTrue(mAmWmState.getAmState().getKeyguardControllerState().keyguardShowing);
            launchActivity(DISMISS_KEYGUARD_METHOD_ACTIVITY);
            pressBackButton();
            assertOnDismissCancelled(DISMISS_KEYGUARD_METHOD_ACTIVITY);
            mAmWmState.computeState(true);
            mAmWmState.assertVisibility(DISMISS_KEYGUARD_METHOD_ACTIVITY, false);
            assertTrue(mAmWmState.getAmState().getKeyguardControllerState().keyguardShowing);
        }
    }

    @Test
    public void testDismissKeyguardAttrActivity_method_turnScreenOn_withSecureKeyguard()
            throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.setLockCredential().sleepDevice();

            mAmWmState.computeState(true);
            assertTrue(mAmWmState.getAmState().getKeyguardControllerState().keyguardShowing);
            launchActivity(TURN_SCREEN_ON_ATTR_DISMISS_KEYGUARD_ACTIVITY);
            mAmWmState.waitForKeyguardShowingAndNotOccluded();
            mAmWmState.assertVisibility(TURN_SCREEN_ON_ATTR_DISMISS_KEYGUARD_ACTIVITY, false);
            assertTrue(mAmWmState.getAmState().getKeyguardControllerState().keyguardShowing);
            assertTrue(isDisplayOn(DEFAULT_DISPLAY));
        }
    }

    @Test
    public void testEnterPipOverKeyguard() throws Exception {
        assumeTrue(supportsPip());

        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.setLockCredential();

            // Show the PiP activity in fullscreen
            launchActivity(PIP_ACTIVITY, EXTRA_SHOW_OVER_KEYGUARD, "true");

            // Lock the screen and ensure that the PiP activity showing over the LockScreen.
            lockScreenSession.gotoKeyguard(PIP_ACTIVITY);
            mAmWmState.waitForKeyguardShowingAndOccluded();
            mAmWmState.assertKeyguardShowingAndOccluded();

            // Request that the PiP activity enter picture-in-picture mode (ensure it does not)
            mBroadcastActionTrigger.doAction(ACTION_ENTER_PIP);
            waitForEnterPip(PIP_ACTIVITY);
            mAmWmState.assertDoesNotContainStack("Must not contain pinned stack.",
                    WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD);

            // Enter the credentials and ensure that the activity actually entered picture-in
            // -picture
            lockScreenSession.enterAndConfirmLockCredential();
            mAmWmState.waitForKeyguardGone();
            mAmWmState.assertKeyguardGone();
            waitForEnterPip(PIP_ACTIVITY);
            mAmWmState.assertContainsStack("Must contain pinned stack.", WINDOWING_MODE_PINNED,
                    ACTIVITY_TYPE_STANDARD);
        }
    }

    @Test
    public void testShowWhenLockedActivityAndPipActivity() throws Exception {
        assumeTrue(supportsPip());

        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.setLockCredential();

            // Show an activity in PIP
            launchActivity(PIP_ACTIVITY, EXTRA_ENTER_PIP, "true");
            waitForEnterPip(PIP_ACTIVITY);
            mAmWmState.assertContainsStack("Must contain pinned stack.", WINDOWING_MODE_PINNED,
                    ACTIVITY_TYPE_STANDARD);
            mAmWmState.assertVisibility(PIP_ACTIVITY, true);

            // Show an activity that will keep above the keyguard
            launchActivity(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.computeState(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);

            // Lock the screen and ensure that the fullscreen activity showing over the lockscreen
            // is visible, but not the PiP activity
            lockScreenSession.gotoKeyguard(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.computeState(true);
            mAmWmState.assertKeyguardShowingAndOccluded();
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);
            mAmWmState.assertVisibility(PIP_ACTIVITY, false);
        }
    }

    @Test
    public void testShowWhenLockedPipActivity() throws Exception {
        assumeTrue(supportsPip());

        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.setLockCredential();

            // Show an activity in PIP
            launchActivity(PIP_ACTIVITY, EXTRA_ENTER_PIP, "true",
                    EXTRA_SHOW_OVER_KEYGUARD, "true");
            waitForEnterPip(PIP_ACTIVITY);
            mAmWmState.assertContainsStack("Must contain pinned stack.", WINDOWING_MODE_PINNED,
                    ACTIVITY_TYPE_STANDARD);
            mAmWmState.assertVisibility(PIP_ACTIVITY, true);

            // Lock the screen and ensure the PiP activity is not visible on the lockscreen even
            // though it's marked as showing over the lockscreen itself
            lockScreenSession.gotoKeyguard();
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            mAmWmState.assertVisibility(PIP_ACTIVITY, false);
        }
    }

    @Test
    public void testDismissKeyguardPipActivity() throws Exception {
        assumeTrue(supportsPip());

        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            // Show an activity in PIP
            launchActivity(PIP_ACTIVITY, EXTRA_ENTER_PIP, "true", EXTRA_DISMISS_KEYGUARD, "true");
            waitForEnterPip(PIP_ACTIVITY);
            mAmWmState.assertContainsStack("Must contain pinned stack.", WINDOWING_MODE_PINNED,
                ACTIVITY_TYPE_STANDARD);
            mAmWmState.assertVisibility(PIP_ACTIVITY, true);

            // Lock the screen and ensure the PiP activity is not visible on the lockscreen even
            // though it's marked as dismiss keyguard.
            lockScreenSession.gotoKeyguard();
            mAmWmState.computeState(true);
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            mAmWmState.assertVisibility(PIP_ACTIVITY, false);
        }
    }

    @Test
    public void testShowWhenLockedAttrImeActivityAndShowSoftInput() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession();
             // Leverage MockImeSession to ensure at least an IME exists as default.
             final MockImeSession mockImeSession = MockImeSession.create(mContext,
                     getInstrumentation().getUiAutomation(), new ImeSettings.Builder())) {
            lockScreenSession.setLockCredential().gotoKeyguard();
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            launchActivity(SHOW_WHEN_LOCKED_ATTR_IME_ACTIVITY);
            mAmWmState.computeState(SHOW_WHEN_LOCKED_ATTR_IME_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ATTR_IME_ACTIVITY, true);

            // Make sure the activity has been called showSoftInput & IME window is visible.
            final ImeEventStream stream = mockImeSession.openEventStream();
            expectEvent(stream, event -> "showSoftInput".equals(event.getEventName()),
                    TimeUnit.SECONDS.toMillis(5) /* eventTimeout */);
            // Assert the IME is shown on the expected display.
            mAmWmState.waitAndAssertImeWindowShownOnDisplay(DEFAULT_DISPLAY);
        }
    }

    @Test
    public void testShowWhenLockedImeActivityAndShowSoftInput() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession();
             final TestActivitySession<ShowWhenLockedImeActivity> imeTestActivitySession = new
                     TestActivitySession<>();
             // Leverage MockImeSession to ensure at least an IME exists as default.
             final MockImeSession mockImeSession = MockImeSession.create(mContext,
                     getInstrumentation().getUiAutomation(), new ImeSettings.Builder())) {
            lockScreenSession.setLockCredential().gotoKeyguard();
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            imeTestActivitySession.launchTestActivityOnDisplaySync(ShowWhenLockedImeActivity.class,
                    DEFAULT_DISPLAY);

            // Make sure the activity has been called showSoftInput & IME window is visible.
            final ImeEventStream stream = mockImeSession.openEventStream();
            expectEvent(stream, event -> "showSoftInput".equals(event.getEventName()),
                    TimeUnit.SECONDS.toMillis(5) /* eventTimeout */);
            // Assert the IME is shown on the expected display.
            mAmWmState.waitAndAssertImeWindowShownOnDisplay(DEFAULT_DISPLAY);
        }
    }

    public static class ShowWhenLockedImeActivity extends Activity {

        @Override
        protected void onCreate(Bundle icicle) {
            super.onCreate(icicle);
            final EditText editText = new EditText(this);
            // Set private IME option for editorMatcher to identify which TextView received
            // onStartInput event.
            editText.setPrivateImeOptions(
                    getClass().getName() + "/" + Long.toString(SystemClock.elapsedRealtimeNanos()));
            final LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.addView(editText);
            setContentView(layout);

            // Set showWhenLocked as true & request focus for showing soft input.
            setShowWhenLocked(true);
            getWindow().setSoftInputMode(SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            editText.requestFocus();
        }
    }

    /**
     * Waits until the given activity has entered picture-in-picture mode (allowing for the
     * subsequent animation to start).
     */
    private void waitForEnterPip(ComponentName activityName) {
        mAmWmState.waitForValidState(new WaitForValidActivityState.Builder(activityName)
                .setWindowingMode(WINDOWING_MODE_PINNED)
                .setActivityType(ACTIVITY_TYPE_STANDARD)
                .build());
    }
}
