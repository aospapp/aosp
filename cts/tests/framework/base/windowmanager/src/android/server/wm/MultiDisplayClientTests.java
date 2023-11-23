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
import static android.server.wm.CommandSession.ActivityCallback.ON_CONFIGURATION_CHANGED;
import static android.server.wm.CommandSession.ActivityCallback.ON_RESUME;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.cts.mockime.ImeEventStreamTestUtils.expectCommand;
import static com.android.cts.mockime.ImeEventStreamTestUtils.expectEvent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.platform.test.annotations.Presubmit;
import android.os.Bundle;
import android.view.Display;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.test.filters.FlakyTest;
import androidx.test.rule.ActivityTestRule;

import com.android.cts.mockime.ImeEventStream;
import com.android.cts.mockime.MockImeSession;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

/**
 * Build/Install/Run:
 *     atest CtsActivityManagerDeviceTestCases:MultiDisplayClientTests
 */
@Presubmit
public class MultiDisplayClientTests extends MultiDisplayTestBase {

    private static final long TIMEOUT = TimeUnit.SECONDS.toMillis(5); // 5 seconds
    private static final String EXTRA_SHOW_IME = "show_ime";

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        assumeTrue(supportsMultiDisplay());
    }

    @Test
    @FlakyTest(bugId = 130260102, detail = "Promote to presubmit once proved stable")
    public void testDisplayIdUpdateOnMove_RelaunchActivity() throws Exception {
        testDisplayIdUpdateOnMove(ClientTestActivity.class, false /* handlesConfigChange */);
    }

    @Test
    @FlakyTest(bugId = 130260102, detail = "Promote to presubmit once proved stable")
    public void testDisplayIdUpdateOnMove_NoRelaunchActivity() throws Exception {
        testDisplayIdUpdateOnMove(NoRelaunchActivity.class, true /* handlesConfigChange */);
    }

    private void testDisplayIdUpdateOnMove(Class<? extends Activity> activityClass,
            boolean handlesConfigChange) throws Exception {
        final ActivityTestRule activityTestRule = new ActivityTestRule(
                activityClass, true /* initialTouchMode */, false /* launchActivity */);

        // Launch activity display.
        separateTestJournal();
        Activity activity = activityTestRule.launchActivity(new Intent());
        final ComponentName activityName = activity.getComponentName();
        waitAndAssertResume(activityName);

        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new simulated display
            final ActivityManagerState.ActivityDisplay newDisplay =
                    virtualDisplaySession.setSimulateDisplay(true).createDisplay();

            // Move the activity to the new secondary display.
            separateTestJournal();
            final ActivityOptions launchOptions = ActivityOptions.makeBasic();
            launchOptions.setLaunchDisplayId(newDisplay.mId);
            final Intent newDisplayIntent = new Intent(mContext, activityClass);
            newDisplayIntent.setFlags(FLAG_ACTIVITY_NEW_TASK);
            getInstrumentation().getTargetContext().startActivity(newDisplayIntent,
                    launchOptions.toBundle());
            waitAndAssertTopResumedActivity(activityName, newDisplay.mId,
                    "Activity moved to secondary display must be focused");

            if (handlesConfigChange) {
                // Wait for activity to receive the configuration change after move
                waitAndAssertConfigurationChange(activityName);
            } else {
                // Activity will be re-created, wait for resumed state
                waitAndAssertResume(activityName);
                activity = activityTestRule.getActivity();
            }
            final String message = "Display id must be updated";
            assertEquals(message, newDisplay.mId, activity.getDisplayId());
            assertEquals(message, newDisplay.mId, activity.getDisplay().getDisplayId());
            final WindowManager wm = activity.getWindowManager();
            assertEquals(message, newDisplay.mId, wm.getDefaultDisplay().getDisplayId());
        }
    }

    private void waitAndAssertConfigurationChange(ComponentName activityName) {
        mAmWmState.waitForWithAmState((state) ->
                        getCallbackCount(activityName, ON_CONFIGURATION_CHANGED) == 1,
                "waitForConfigurationChange");
        assertEquals("Must receive a single configuration change", 1,
                getCallbackCount(activityName, ON_CONFIGURATION_CHANGED));
    }

    private void waitAndAssertResume(ComponentName activityName) {
        mAmWmState.waitForWithAmState((state) ->
                getCallbackCount(activityName, ON_RESUME) == 1, "waitForResume");
        assertEquals("Must be resumed once", 1, getCallbackCount(activityName, ON_RESUME));
    }

    private int getCallbackCount(ComponentName activityName,
            CommandSession.ActivityCallback callback) {
        final ActivityLifecycleCounts lifecycles = new ActivityLifecycleCounts(activityName);
        return lifecycles.getCount(callback);
    }

    @Test
    @FlakyTest(bugId = 130379901, detail = "Promote to presubmit once proved stable")
    public void testDisplayIdUpdateWhenImeMove_RelaunchActivity() throws Exception {
        try (final TestActivitySession<ClientTestActivity> session = new TestActivitySession<>()) {
            testDisplayIdUpdateWhenImeMove(ClientTestActivity.class);
        }
    }

    @Test
    @FlakyTest(bugId = 130379901, detail = "Promote to presubmit once proved stable")
    public void testDisplayIdUpdateWhenImeMove_NoRelaunchActivity() throws Exception {
        try (final TestActivitySession<NoRelaunchActivity> session = new TestActivitySession<>()) {
            testDisplayIdUpdateWhenImeMove(NoRelaunchActivity.class);
        }
    }

    private void testDisplayIdUpdateWhenImeMove(Class<? extends ImeTestActivity> activityClass)
            throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession();
            final MockImeSession mockImeSession = MockImeSession.create(mContext)) {

            assertImeShownAndMatchesDisplayId(
                    activityClass, mockImeSession, DEFAULT_DISPLAY);

            final ActivityManagerState.ActivityDisplay newDisplay = virtualDisplaySession
                    .setSimulateDisplay(true).setShowSystemDecorations(true).createDisplay();

            // Launch activity on the secondary display and make IME show.
            assertImeShownAndMatchesDisplayId(
                    activityClass, mockImeSession, newDisplay.mId);
        }
    }

    private  void assertImeShownAndMatchesDisplayId(Class<? extends ImeTestActivity> activityClass,
            MockImeSession imeSession, int targetDisplayId) throws Exception {
        final ImeEventStream stream = imeSession.openEventStream();

        final Intent intent = new Intent(mContext, activityClass)
                .putExtra(EXTRA_SHOW_IME, true).setFlags(FLAG_ACTIVITY_NEW_TASK);
        separateTestJournal();
        final ActivityOptions launchOptions = ActivityOptions.makeBasic();
        launchOptions.setLaunchDisplayId(targetDisplayId);
        getInstrumentation().getTargetContext().startActivity(intent, launchOptions.toBundle());


        // Verify if IME is showed on the target display.
        expectEvent(stream, event -> "showSoftInput".equals(event.getEventName()), TIMEOUT);
        mAmWmState.waitAndAssertImeWindowShownOnDisplay(targetDisplayId);

        final int displayId = expectCommand(stream, imeSession.callGetDisplayId(), TIMEOUT)
                .getReturnIntegerValue();
        assertEquals("Display ID must match", targetDisplayId, displayId);
    }

    @Test
    @FlakyTest(bugId = 130379901, detail = "Promote to presubmit once proved stable")
    public void testInputMethodManagerDisplayId() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create a simulated display.
            final ActivityManagerState.ActivityDisplay newDisplay = virtualDisplaySession
                    .setSimulateDisplay(true).createDisplay();

            final Display display = mContext.getSystemService(DisplayManager.class)
                    .getDisplay(newDisplay.mId);
            final Context newDisplayContext = mContext.createDisplayContext(display);
            final InputMethodManager imm =
                    newDisplayContext.getSystemService(InputMethodManager.class);

            assertEquals(newDisplay.mId, imm.getDisplayId());
        }
    }

    public static class ClientTestActivity extends ImeTestActivity { }

    public static class NoRelaunchActivity extends ImeTestActivity { }

    public static class ImeTestActivity extends CommandSession.BasicTestActivity {
        private EditText mEditText;
        private boolean mShouldShowIme;

        @Override
        protected void onCreate(Bundle icicle) {
            super.onCreate(icicle);
            mShouldShowIme = getIntent().hasExtra(EXTRA_SHOW_IME);
            if (mShouldShowIme) {
                mEditText = new EditText(this);
                final LinearLayout layout = new LinearLayout(this);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.addView(mEditText);
                setContentView(layout);
            }
        }

        @Override
        protected void onResume() {
            super.onResume();
            if (mShouldShowIme) {
                getWindow().setSoftInputMode(SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                mEditText.requestFocus();
            }
        }
    }
}
