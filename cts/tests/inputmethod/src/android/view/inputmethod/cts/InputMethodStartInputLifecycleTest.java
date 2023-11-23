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

package android.view.inputmethod.cts;

import static android.view.View.SCREEN_STATE_OFF;
import static android.view.View.SCREEN_STATE_ON;
import static android.view.View.VISIBLE;

import static com.android.cts.mockime.ImeEventStreamTestUtils.editorMatcher;
import static com.android.cts.mockime.ImeEventStreamTestUtils.expectCommand;
import static com.android.cts.mockime.ImeEventStreamTestUtils.expectEvent;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.cts.util.DisableScreenDozeRule;
import android.view.inputmethod.cts.util.EndToEndImeTestBase;
import android.view.inputmethod.cts.util.TestActivity;
import android.view.inputmethod.cts.util.TestUtils;
import android.view.inputmethod.cts.util.UnlockScreenRule;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.CtsTouchUtils;
import com.android.cts.mockime.ImeCommand;
import com.android.cts.mockime.ImeEvent;
import com.android.cts.mockime.ImeEventStream;
import com.android.cts.mockime.ImeSettings;
import com.android.cts.mockime.MockImeSession;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class InputMethodStartInputLifecycleTest extends EndToEndImeTestBase {
    @Rule
    public final DisableScreenDozeRule mDisableScreenDozeRule = new DisableScreenDozeRule();
    @Rule
    public final UnlockScreenRule mUnlockScreenRule = new UnlockScreenRule();

    private static final long TIMEOUT = TimeUnit.SECONDS.toMillis(5);

    @Test
    public void testInputConnectionStateWhenScreenStateChanges() throws Exception {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final InputMethodManager imManager = context.getSystemService(InputMethodManager.class);
        assumeTrue(context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_INPUT_METHODS));
        final AtomicReference<EditText> focusedEditTextRef = new AtomicReference<>();

        try (MockImeSession imeSession = MockImeSession.create(
                context, instrumentation.getUiAutomation(), new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = InputMethodManagerTest.class.getName() + "/"
                    + SystemClock.elapsedRealtimeNanos();
            final AtomicInteger screenStateCallbackRef = new AtomicInteger(-1);
            TestActivity.startSync(activity -> {
                final LinearLayout layout = new LinearLayout(activity);
                layout.setOrientation(LinearLayout.VERTICAL);

                final EditText focusedEditText = new EditText(activity) {
                    @Override
                    public void onScreenStateChanged(int screenState) {
                        super.onScreenStateChanged(screenState);
                        screenStateCallbackRef.set(screenState);
                    }
                };
                focusedEditText.setPrivateImeOptions(marker);
                focusedEditText.setHint("editText");
                layout.addView(focusedEditText);
                focusedEditText.requestFocus();
                focusedEditTextRef.set(focusedEditText);

                final EditText nonFocusedEditText = new EditText(activity);
                layout.addView(nonFocusedEditText);

                return layout;
            });

            // Expected onStartInput when TestActivity launched.
            final EditText editText = focusedEditTextRef.get();
            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);

            // Expected text commit will not work when turnScreenOff.
            TestUtils.turnScreenOff();
            TestUtils.waitOnMainUntil(() -> screenStateCallbackRef.get() == SCREEN_STATE_OFF
                            && editText.getWindowVisibility() != VISIBLE, TIMEOUT);
            expectEvent(stream, onFinishInputMatcher(), TIMEOUT);
            final ImeCommand commit = imeSession.callCommitText("Hi!", 1);
            expectCommand(stream, commit, TIMEOUT);
            TestUtils.waitOnMainUntil(() -> !TextUtils.equals(editText.getText(), "Hi!"), TIMEOUT,
                    "InputMethodService#commitText should not work after screen off");

            // Expected text commit will work when turnScreenOn.
            TestUtils.turnScreenOn();
            TestUtils.unlockScreen();
            TestUtils.waitOnMainUntil(() -> screenStateCallbackRef.get() == SCREEN_STATE_ON
                            && editText.getWindowVisibility() == VISIBLE, TIMEOUT);
            CtsTouchUtils.emulateTapOnViewCenter(instrumentation, null, editText);
            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            assertTrue(TestUtils.getOnMainSync(
                    () -> imManager.isActive(editText) && imManager.isAcceptingText()));
            final ImeCommand commit1 = imeSession.callCommitText("Hello!", 1);
            expectCommand(stream, commit1, TIMEOUT);
            TestUtils.waitOnMainUntil(() -> TextUtils.equals(editText.getText(), "Hello!"), TIMEOUT,
                    "InputMethodService#commitText should work after screen on");
        }
    }

    private static Predicate<ImeEvent> onFinishInputMatcher() {
        return event -> TextUtils.equals("onFinishInput", event.getEventName());
    }
}
