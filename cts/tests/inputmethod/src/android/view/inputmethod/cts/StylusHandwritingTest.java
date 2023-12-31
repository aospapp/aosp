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

package android.view.inputmethod.cts;

import static android.provider.Settings.Secure.STYLUS_HANDWRITING_DEFAULT_VALUE;
import static android.provider.Settings.Secure.STYLUS_HANDWRITING_ENABLED;
import static android.view.inputmethod.InputMethodInfo.ACTION_STYLUS_HANDWRITING_SETTINGS;

import static com.android.cts.mockime.ImeEventStreamTestUtils.editorMatcher;
import static com.android.cts.mockime.ImeEventStreamTestUtils.expectBindInput;
import static com.android.cts.mockime.ImeEventStreamTestUtils.expectCommand;
import static com.android.cts.mockime.ImeEventStreamTestUtils.expectEvent;
import static com.android.cts.mockime.ImeEventStreamTestUtils.notExpectEvent;
import static com.android.cts.mockime.ImeEventStreamTestUtils.withDescription;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.input.InputManager;
import android.inputmethodservice.InputMethodService;
import android.os.Process;
import android.os.SystemClock;
import android.platform.test.annotations.AppModeFull;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Pair;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.cts.util.EndToEndImeTestBase;
import android.view.inputmethod.cts.util.MockTestActivityUtil;
import android.view.inputmethod.cts.util.NoOpInputConnection;
import android.view.inputmethod.cts.util.TestActivity;
import android.view.inputmethod.cts.util.TestActivity2;
import android.view.inputmethod.cts.util.TestUtils;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.test.filters.FlakyTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.ApiTest;
import com.android.compatibility.common.util.CommonTestUtils;
import com.android.compatibility.common.util.GestureNavSwitchHelper;
import com.android.compatibility.common.util.SystemUtil;
import com.android.cts.mockime.ImeEvent;
import com.android.cts.mockime.ImeEventStream;
import com.android.cts.mockime.ImeSettings;
import com.android.cts.mockime.MockImeSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * IMF and end-to-end Stylus handwriting tests.
 */
public class StylusHandwritingTest extends EndToEndImeTestBase {
    private static final long TIMEOUT_IN_SECONDS = 5;
    private static final long TIMEOUT = TimeUnit.SECONDS.toMillis(TIMEOUT_IN_SECONDS);
    private static final long TIMEOUT_6_S = TimeUnit.SECONDS.toMillis(6);
    private static final long TIMEOUT_1_S = TimeUnit.SECONDS.toMillis(1);
    private static final long NOT_EXPECT_TIMEOUT_IN_SECONDS = 3;
    private static final long NOT_EXPECT_TIMEOUT =
            TimeUnit.SECONDS.toMillis(NOT_EXPECT_TIMEOUT_IN_SECONDS);
    private static final int SETTING_VALUE_ON = 1;
    private static final int SETTING_VALUE_OFF = 0;
    private static final String TEST_MARKER_PREFIX =
            "android.view.inputmethod.cts.StylusHandwritingTest";
    private static final int HANDWRITING_BOUNDS_OFFSET_PX = 20;
    // A timeout greater than HandwritingModeController#HANDWRITING_DELEGATION_IDLE_TIMEOUT_MS.
    private static final long DELEGATION_AFTER_IDLE_TIMEOUT_MS = 3100;

    private Context mContext;
    private int mHwInitialState;
    private boolean mShouldRestoreInitialHwState;

    private static final GestureNavSwitchHelper sGestureNavRule = new GestureNavSwitchHelper();

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeFalse(mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_LEANBACK_ONLY));
        assumeFalse(mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE));

        mHwInitialState = Settings.Secure.getInt(mContext.getContentResolver(),
                STYLUS_HANDWRITING_ENABLED, STYLUS_HANDWRITING_DEFAULT_VALUE);
        if (mHwInitialState != SETTING_VALUE_ON) {
            SystemUtil.runWithShellPermissionIdentity(() -> {
                Settings.Secure.putInt(mContext.getContentResolver(),
                        STYLUS_HANDWRITING_ENABLED, SETTING_VALUE_ON);
            }, Manifest.permission.WRITE_SECURE_SETTINGS);
            mShouldRestoreInitialHwState = true;
        }
    }

    @After
    public void tearDown() {
        MockTestActivityUtil.forceStopPackage();
        if (mShouldRestoreInitialHwState) {
            mShouldRestoreInitialHwState = false;
            SystemUtil.runWithShellPermissionIdentity(() -> {
                Settings.Secure.putInt(mContext.getContentResolver(),
                        STYLUS_HANDWRITING_ENABLED, mHwInitialState);
            }, Manifest.permission.WRITE_SECURE_SETTINGS);
        }
    }

    /**
     * Verify current IME has {@link InputMethodInfo} for stylus handwriting, settings.
     */
    @Test
    @ApiTest(apis = {"android.view.inputmethod.InputMethodInfo#supportsStylusHandwriting",
            "android.view.inputmethod.InputMethodInfo#ACTION_STYLUS_HANDWRITING_SETTINGS",
            "android.view.inputmethod.InputMethodInfo#createStylusHandwritingSettingsActivityIntent"
    })
    public void testHandwritingInfo() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            InputMethodInfo info = imeSession.getInputMethodInfo();
            assertTrue(info.supportsStylusHandwriting());
            // TODO(b/217957587): migrate CtsMockInputMethodLib to android_library and use
            //  string resource.
            Intent stylusSettingsIntent = info.createStylusHandwritingSettingsActivityIntent();
            assertEquals(ACTION_STYLUS_HANDWRITING_SETTINGS, stylusSettingsIntent.getAction());
            assertEquals("handwriting_settings",
                    stylusSettingsIntent.getComponent().getClassName());
        }
    }

    @Test
    public void testIsStylusHandwritingAvailable_prefDisabled() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            imeSession.openEventStream();

            // Disable pref
            SystemUtil.runWithShellPermissionIdentity(() -> {
                Settings.Secure.putInt(mContext.getContentResolver(),
                        STYLUS_HANDWRITING_ENABLED, SETTING_VALUE_OFF);
            }, Manifest.permission.WRITE_SECURE_SETTINGS);
            mShouldRestoreInitialHwState = true;

            launchTestActivity(getTestMarker());
            assertFalse(
                    "should return false for isStylusHandwritingAvailable() when pref is disabled",
                    mContext.getSystemService(
                            InputMethodManager.class).isStylusHandwritingAvailable());
        }
    }

    @Test
    public void testIsStylusHandwritingAvailable() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            imeSession.openEventStream();

            launchTestActivity(getTestMarker());
            assertTrue("Mock IME should return true for isStylusHandwritingAvailable() ",
                    mContext.getSystemService(
                            InputMethodManager.class).isStylusHandwritingAvailable());
        }
    }

    /**
     * Test to verify that we dont init handwriting on devices that dont have any supported stylus.
     */
    @Test
    public void testHandwritingNoInitOnDeviceWithNoStylus() {
        assumeTrue("Skipping test on devices that do not have stylus support",
                hasSupportedStylus());
        final InputMethodManager imm = mContext.getSystemService(InputMethodManager.class);
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();
            final String marker = getTestMarker();
            final EditText editText = launchTestActivity(marker);
            imm.startStylusHandwriting(editText);
            // Handwriting should not start since there are no stylus devices registered.
            notExpectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", marker),
                    NOT_EXPECT_TIMEOUT);
        } catch (Exception e) {
        }
    }

    @Test
    public void testHandwritingDoesNotStartWhenNoStylusDown() throws Exception {
        final InputMethodManager imm = mContext.getSystemService(InputMethodManager.class);
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final EditText editText = launchTestActivity(marker);

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();
            imm.startStylusHandwriting(editText);

            // Handwriting should not start
            notExpectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", marker),
                    NOT_EXPECT_TIMEOUT);

            verifyStylusHandwritingWindowIsNotShown(stream, imeSession);
        }
    }

    @Test
    public void testHandwritingStartAndFinish() throws Exception {
        final InputMethodManager imm = mContext.getSystemService(InputMethodManager.class);
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final EditText editText = launchTestActivity(marker);

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();
            // Touch down with a stylus
            final int startX = editText.getWidth() / 2;
            final int startY = editText.getHeight() / 2;
            TestUtils.injectStylusDownEvent(editText, startX, startY);


            imm.startStylusHandwriting(editText);
            // keyboard shouldn't show up.
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            // Handwriting should start
            expectEvent(
                    stream,
                    editorMatcher("onPrepareStylusHandwriting", marker),
                    TIMEOUT);
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", marker),
                    TIMEOUT);

            verifyStylusHandwritingWindowIsShown(stream, imeSession);

            // Release the stylus pointer
            TestUtils.injectStylusUpEvent(editText, startX, startY);

            // Verify calling finishStylusHandwriting() calls onFinishStylusHandwriting().
            imeSession.callFinishStylusHandwriting();
            expectEvent(
                    stream,
                    editorMatcher("onFinishStylusHandwriting", marker),
                    TIMEOUT);
        }
    }

    /**
     * Call {@link InputMethodManager#startStylusHandwriting(View)} and inject Stylus touch events
     * on screen. Make sure {@link InputMethodService#onStylusHandwritingMotionEvent(MotionEvent)}
     * receives those events via Spy window surface.
     * @throws Exception
     */
    @Test
    public void testHandwritingStylusEvents_onStylusHandwritingMotionEvent() throws Exception {
        testHandwritingStylusEvents(false /* verifyOnInkView */);
    }

    /**
     * Call {@link InputMethodManager#startStylusHandwriting(View)} and inject Stylus touch events
     * on screen. Make sure Inking view receives those events via Spy window surface.
     * @throws Exception
     */
    @Test
    public void testHandwritingStylusEvents_dispatchToInkView() throws Exception {
        testHandwritingStylusEvents(false /* verifyOnInkView */);
    }

    private void verifyStylusHandwritingWindowIsShown(ImeEventStream stream,
            MockImeSession imeSession) throws InterruptedException, TimeoutException {
        CommonTestUtils.waitUntil("Stylus handwriting window should be shown", TIMEOUT_IN_SECONDS,
                () -> expectCommand(
                        stream, imeSession.callGetStylusHandwritingWindowVisibility(), TIMEOUT)
                .getReturnBooleanValue());
    }

    private void verifyStylusHandwritingWindowIsNotShown(ImeEventStream stream,
            MockImeSession imeSession) throws InterruptedException, TimeoutException {
        CommonTestUtils.waitUntil("Stylus handwriting window should not be shown",
                NOT_EXPECT_TIMEOUT_IN_SECONDS,
                () -> !expectCommand(
                        stream, imeSession.callGetStylusHandwritingWindowVisibility(), TIMEOUT)
                .getReturnBooleanValue());
    }

    private void testHandwritingStylusEvents(boolean verifyOnInkView) throws Exception {
        final InputMethodManager imm = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getSystemService(InputMethodManager.class);
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final EditText editText = launchTestActivity(marker);

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();
            final List<MotionEvent> injectedEvents = new ArrayList<>();
            // Touch down with a stylus
            final int startX = editText.getWidth() / 2;
            final int startY = editText.getHeight() / 2;
            injectedEvents.add(TestUtils.injectStylusDownEvent(editText, startX, startY));
            imm.startStylusHandwriting(editText);

            // Handwriting should start
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", marker),
                    TIMEOUT);

            verifyStylusHandwritingWindowIsShown(stream, imeSession);

            if (verifyOnInkView) {
                // Set IME stylus Ink view
                assertTrue(expectCommand(
                        stream,
                        imeSession.callSetStylusHandwritingInkView(),
                        TIMEOUT).getReturnBooleanValue());
            }

            final int touchSlop = getTouchSlop();

            final int endX = startX + 2 * touchSlop;
            final int endY = startY;
            final int number = 5;

            injectedEvents.addAll(
                    TestUtils.injectStylusMoveEvents(editText, startX, startY, endX, endY, number));
            injectedEvents.add(TestUtils.injectStylusUpEvent(editText, endX, endY));

            expectEvent(
                    stream, event -> "onStylusMotionEvent".equals(event.getEventName()), TIMEOUT);

            // get Stylus events from Ink view, splitting any batched events.
            final ArrayList<MotionEvent> capturedBatchedEvents = expectCommand(
                    stream, imeSession.callGetStylusHandwritingEvents(), TIMEOUT)
                    .getReturnParcelableArrayListValue();
            assertNotNull(capturedBatchedEvents);
            final ArrayList<MotionEvent> capturedEvents =  new ArrayList<>();
            capturedBatchedEvents.forEach(
                    e -> capturedEvents.addAll(TestUtils.splitBatchedMotionEvent(e)));

            // captured events should be same as injected.
            assertEquals(injectedEvents.size(), capturedEvents.size());

            // Verify MotionEvents as well.
            // Note: we cannot just use equals() since some MotionEvent fields can change after
            // dispatch.
            Iterator<MotionEvent> capturedIt = capturedEvents.iterator();
            Iterator<MotionEvent> injectedIt = injectedEvents.iterator();
            while (injectedIt.hasNext() && capturedIt.hasNext()) {
                MotionEvent injected = injectedIt.next();
                MotionEvent captured = capturedIt.next();
                assertEquals("X should be same for MotionEvent", injected.getX(), captured.getX(),
                        5.0f);
                assertEquals("Y should be same for MotionEvent", injected.getY(), captured.getY(),
                        5.0f);
                assertEquals("Action should be same for MotionEvent",
                        injected.getAction(), captured.getAction());
            }
        }
    }

    @FlakyTest(bugId = 210039666)
    @Test
    /**
     * Inject Stylus events on top of focused editor and verify Handwriting is started and InkWindow
     * is displayed.
     */
    public void testHandwritingEndToEnd() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final EditText editText = launchTestActivity(marker);

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();
            final int touchSlop = getTouchSlop();
            final int startX = editText.getWidth() / 2;
            final int startY = editText.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY;
            final int number = 5;
            TestUtils.injectStylusDownEvent(editText, startX, startY);
            TestUtils.injectStylusMoveEvents(editText, startX, startY,
                    endX, endY, number);
            // Handwriting should already be initiated before ACTION_UP.
            // keyboard shouldn't show up.
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);
            // Handwriting should start
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", marker),
                    TIMEOUT);

            verifyStylusHandwritingWindowIsShown(stream, imeSession);

            TestUtils.injectStylusUpEvent(editText, endX, endY);
        }
    }

    @FlakyTest(bugId = 222840964)
    @Test
    /**
     * Inject Stylus events on top of focused editor and verify Handwriting can be initiated
     * multiple times.
     */
    public void testHandwritingInitMultipleTimes() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final EditText editText = launchTestActivity(marker);

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            final int touchSlop = getTouchSlop();
            final int startX = editText.getWidth() / 2;
            final int startY = editText.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY;
            final int number = 5;

            // Try to init handwriting for multiple times.
            for (int i = 0; i < 3; ++i) {
                addVirtualStylusIdForTestSession();
                TestUtils.injectStylusDownEvent(editText, startX, startY);
                TestUtils.injectStylusMoveEvents(editText, startX, startY,
                        endX, endY, number);
                // Handwriting should already be initiated before ACTION_UP.
                // keyboard shouldn't show up.
                notExpectEvent(
                        stream,
                        editorMatcher("onStartInputView", marker),
                        NOT_EXPECT_TIMEOUT);
                // Handwriting should start
                expectEvent(
                        stream,
                        editorMatcher("onStartStylusHandwriting", marker),
                        TIMEOUT);

                verifyStylusHandwritingWindowIsShown(stream, imeSession);

                TestUtils.injectStylusUpEvent(editText, endX, endY);

                imeSession.callFinishStylusHandwriting();
                expectEvent(
                        stream,
                        editorMatcher("onFinishStylusHandwriting", marker),
                        TIMEOUT);
            }
        }
    }

    @Test
    /**
     * Inject Stylus events on top of focused editor's handwriting bounds and verify
     * Handwriting is started and InkWindow is displayed.
     */
    public void testHandwritingInOffsetHandwritingBounds() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final EditText editText = launchTestActivity(marker);

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();
            final int touchSlop = getTouchSlop();
            final int startX = editText.getWidth() / 2;
            final int startY = -HANDWRITING_BOUNDS_OFFSET_PX / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY;
            final int number = 5;
            TestUtils.injectStylusDownEvent(editText, startX, startY);
            TestUtils.injectStylusMoveEvents(editText, startX, startY,
                    endX, endY, number);
            // Handwriting should already be initiated before ACTION_UP.
            // keyboard shouldn't show up.
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);
            // Handwriting should start
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", marker),
                    TIMEOUT);

            verifyStylusHandwritingWindowIsShown(stream, imeSession);

            TestUtils.injectStylusUpEvent(editText, endX, endY);
        }
    }

    /**
     * Inject Stylus events on top of focused editor and verify Handwriting is started and then
     * inject events on navbar to swipe to home and make sure motionEvents are consumed by
     * Handwriting window.
     */
    @Test
    public void testStylusSession_stylusWouldNotTriggerNavbarGestures() throws Exception {
        assumeTrue(sGestureNavRule.isGestureMode());

        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final EditText editText = launchTestActivity(marker);

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();
            final int touchSlop = getTouchSlop();
            final int startX = editText.getWidth() / 2;
            final int startY = editText.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY;
            final int number = 5;
            TestUtils.injectStylusDownEvent(editText, startX, startY);
            TestUtils.injectStylusMoveEvents(editText, startX, startY, endX, endY, number);
            // Handwriting should already be initiated before ACTION_UP.
            // keyboard shouldn't show up.
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);
            // Handwriting should start
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", marker),
                    TIMEOUT);
            TestUtils.injectStylusUpEvent(editText, endX, endY);
            // Inject stylus swipe up on navbar.
            TestUtils.injectNavBarToHomeGestureEvents(
                    ((Activity) editText.getContext()), MotionEvent.TOOL_TYPE_STYLUS);

            // Handwriting is finished if navigation gesture is executed.
            // Make sure handwriting isn't finished.
            notExpectEvent(
                    stream,
                    editorMatcher("onFinishStylusHandwriting", marker),
                    TIMEOUT_1_S);
        }
    }

    /**
     * Inject Stylus events on top of focused editor and verify Handwriting is started and then
     * inject finger touch events on navbar to swipe to home and make sure user can swipe to home.
     */
    @Test
    public void testStylusSession_fingerTriggersNavbarGestures() throws Exception {
        assumeTrue(sGestureNavRule.isGestureMode());

        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final EditText editText = launchTestActivity(marker);

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();
            final int touchSlop = getTouchSlop();
            final int startX = editText.getWidth() / 2;
            final int startY = editText.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY;
            final int number = 5;
            TestUtils.injectStylusDownEvent(editText, startX, startY);
            TestUtils.injectStylusMoveEvents(editText, startX, startY, endX, endY, number);
            // Handwriting should already be initiated before ACTION_UP.
            // keyboard shouldn't show up.
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);
            // Handwriting should start
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", marker),
                    TIMEOUT);
            TestUtils.injectStylusUpEvent(editText, endX, endY);
            // Inject finger swipe up on navbar.
            TestUtils.injectNavBarToHomeGestureEvents(
                    ((Activity) editText.getContext()), MotionEvent.TOOL_TYPE_FINGER);

            // Handwriting is finished if navigation gesture is executed.
            // Make sure handwriting is finished to ensure swipe to home works.
            expectEvent(
                    stream,
                    editorMatcher("onFinishStylusHandwriting", marker),
                    // BlastSyncEngine has a 5s timeout when launcher fails to sync its
                    // transaction, exceeding it avoids flakes when that happens.
                    TIMEOUT_6_S);
        }
    }

    @Test
    /**
     * Inject stylus events to a focused EditText that disables autoHandwriting.
     * {@link InputMethodManager#startStylusHandwriting(View)} should not be called.
     */
    public void testAutoHandwritingDisabled() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final EditText editText = launchTestActivity(marker);
            editText.setAutoHandwritingEnabled(false);

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();
            TestUtils.injectStylusEvents(editText);

            // TODO(215439842): check that keyboard is not shown.
            // Handwriting should not start
            notExpectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", marker),
                    NOT_EXPECT_TIMEOUT);

            verifyStylusHandwritingWindowIsNotShown(stream, imeSession);
        }
    }

    @Test
    /**
     * Inject stylus events out of a focused editor's view bound.
     * {@link InputMethodManager#startStylusHandwriting(View)} should not be called for this editor.
     */
    public void testAutoHandwritingOutOfBound() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final EditText editText = launchTestActivity(marker);
            editText.setAutoHandwritingEnabled(false);

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();
            // Inject stylus events out of the editor boundary.
            TestUtils.injectStylusEvents(editText, editText.getWidth() / 2,
                    -HANDWRITING_BOUNDS_OFFSET_PX - 50);
            // keyboard shouldn't show up.
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);
            // Handwriting should not start
            notExpectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", marker),
                    NOT_EXPECT_TIMEOUT);

            verifyStylusHandwritingWindowIsNotShown(stream, imeSession);
        }
    }

    @Test
    /**
     * Inject Stylus events on top of an unfocused editor and verify Handwriting is started and
     * InkWindow is displayed.
     */
    public void testHandwriting_unfocusedEditText() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String focusedMarker = getTestMarker();
            final String unfocusedMarker = getTestMarker();
            final Pair<EditText, EditText> editTextPair =
                    launchTestActivity(focusedMarker, unfocusedMarker);
            final EditText unfocusedEditText = editTextPair.second;

            expectEvent(stream, editorMatcher("onStartInput", focusedMarker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", focusedMarker),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();
            final int touchSlop = getTouchSlop();
            final int startX = unfocusedEditText.getWidth() / 2;
            final int startY = 2 * touchSlop;
            // (endX, endY) is out of bound to avoid that unfocusedEditText is focused due to the
            // stylus touch.
            final int endX = startX;
            final int endY = unfocusedEditText.getHeight() + 2 * touchSlop;
            final int number = 5;

            TestUtils.injectStylusDownEvent(unfocusedEditText, startX, startY);
            TestUtils.injectStylusMoveEvents(unfocusedEditText, startX, startY,
                    endX, endY, number);
            // Handwriting should already be initiated before ACTION_UP.
            // unfocusedEditor is focused and triggers onStartInput.
            expectEvent(stream, editorMatcher("onStartInput", unfocusedMarker), TIMEOUT);
            // keyboard shouldn't show up.
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", unfocusedMarker),
                    NOT_EXPECT_TIMEOUT);
            // Handwriting should start on the unfocused EditText.
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", unfocusedMarker),
                    TIMEOUT);

            verifyStylusHandwritingWindowIsShown(stream, imeSession);

            TestUtils.injectStylusUpEvent(unfocusedEditText, endX, endY);
        }
    }


    @Test
    /**
     * Inject Stylus events on top of an unfocused editor which disabled the autoHandwriting and
     * verify Handwriting is not started and InkWindow is not displayed.
     */
    public void testHandwriting_unfocusedEditText_autoHandwritingDisabled() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String focusedMarker = getTestMarker();
            final String unfocusedMarker = getTestMarker();
            final Pair<EditText, EditText> editTextPair =
                    launchTestActivity(focusedMarker, unfocusedMarker);
            final EditText unfocusedEditText = editTextPair.second;
            unfocusedEditText.setAutoHandwritingEnabled(false);

            expectEvent(stream, editorMatcher("onStartInput", focusedMarker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", focusedMarker),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();
            final int touchSlop = getTouchSlop();
            final int startX = unfocusedEditText.getWidth() / 2;
            final int startY = 2 * touchSlop;
            // (endX, endY) is out of bound to avoid that unfocusedEditText is focused due to the
            // stylus touch.
            final int endX = startX;
            final int endY = -2 * touchSlop;
            final int number = 5;
            TestUtils.injectStylusDownEvent(unfocusedEditText, startX, startY);
            TestUtils.injectStylusMoveEvents(unfocusedEditText, startX, startY,
                    endX, endY, number);
            TestUtils.injectStylusUpEvent(unfocusedEditText, endX, endY);

            // unfocusedEditor opts out autoHandwriting, so it won't trigger onStartInput.
            notExpectEvent(stream, editorMatcher("onStartInput", unfocusedMarker), TIMEOUT);
            // keyboard shouldn't show up.
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", unfocusedMarker),
                    NOT_EXPECT_TIMEOUT);
            // Handwriting should not start
            notExpectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", unfocusedMarker),
                    NOT_EXPECT_TIMEOUT);

            verifyStylusHandwritingWindowIsNotShown(stream, imeSession);
        }
    }

    /**
     * Inject stylus top on an editor and verify stylus source is detected with
     * {@link InputMethodService#onUpdateEditorToolType(int)} lifecycle method.
     */
    @Test
    public void testOnViewClicked_withStylusTap() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final String marker2 = getTestMarker();
            final Pair<EditText, EditText> pair = launchTestActivity(marker, marker2);
            final EditText focusedEditText = pair.first;
            final EditText unfocusedEditText = pair.second;

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            final int startX = focusedEditText.getWidth() / 2;
            final int startY = focusedEditText.getHeight() / 2;

            // Tap with stylus on focused editor
            TestUtils.injectStylusDownEvent(focusedEditText, startX, startY);
            MotionEvent event = TestUtils.injectStylusUpEvent(focusedEditText, startX, startY);
            int toolType = event.getToolType(event.getActionIndex());

            expectEvent(
                    stream,
                    onUpdateEditorToolTypeMatcher(toolType),
                    TIMEOUT);

            // Tap with stylus on unfocused editor
            TestUtils.injectStylusDownEvent(unfocusedEditText, startX, startY);
            event = TestUtils.injectStylusUpEvent(unfocusedEditText, startX, startY);
            expectEvent(stream, onStartInputMatcher(toolType, marker2), TIMEOUT);
            expectEvent(
                    stream,
                    onUpdateEditorToolTypeMatcher(event.getToolType(event.getActionIndex())),
                    TIMEOUT);
        }
    }

    /**
     * Inject finger top on an editor and verify stylus source is detected with
     * {@link InputMethodService#onUpdateEditorToolType(int)} lifecycle method.
     */
    @Test
    public void testOnViewClicked_withFingerTap() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final String marker2 = getTestMarker();
            final Pair<EditText, EditText> pair = launchTestActivity(marker, marker2);
            final EditText focusedEditText = pair.first;
            final EditText unfocusedEditText = pair.second;

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            TestUtils.injectFingerEventOnViewCenter(focusedEditText, MotionEvent.ACTION_DOWN);
            MotionEvent upEvent =
                    TestUtils.injectFingerEventOnViewCenter(focusedEditText, MotionEvent.ACTION_UP);
            int toolTypeFinger = upEvent.getToolType(upEvent.getActionIndex());
            assertEquals(
                    "tool type finger must match", MotionEvent.TOOL_TYPE_FINGER, toolTypeFinger);
            expectEvent(
                    stream,
                    onUpdateEditorToolTypeMatcher(toolTypeFinger),
                    TIMEOUT);

            // tap on unfocused editor
            TestUtils.injectFingerEventOnViewCenter(unfocusedEditText, MotionEvent.ACTION_DOWN);
            upEvent = TestUtils.injectFingerEventOnViewCenter(
                    unfocusedEditText, MotionEvent.ACTION_UP);
            toolTypeFinger = upEvent.getToolType(upEvent.getActionIndex());
            assertEquals(
                    "tool type finger must match", MotionEvent.TOOL_TYPE_FINGER, toolTypeFinger);
            expectEvent(stream, onStartInputMatcher(toolTypeFinger, marker2), TIMEOUT);
            expectEvent(
                    stream,
                    onUpdateEditorToolTypeMatcher(MotionEvent.TOOL_TYPE_FINGER),
                    TIMEOUT);
        }
    }

    /**
     * Inject stylus handwriting event on an editor and verify stylus source is detected with
     * {@link InputMethodService#onUpdateEditorToolType(int)} on next startInput().
     */
    @Test
    public void testOnViewClicked_withStylusHandwriting() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            addVirtualStylusIdForTestSession();

            final String focusedMarker = getTestMarker();
            final String unFocusedMarker = getTestMarker();
            final Pair<EditText, EditText> pair =
                    launchTestActivity(focusedMarker, unFocusedMarker);
            final EditText focusedEditText = pair.first;
            final EditText unfocusedEditText = pair.second;

            expectEvent(stream, editorMatcher("onStartInput", focusedMarker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", focusedMarker),
                    NOT_EXPECT_TIMEOUT);

            // Finger tap on editor and verify onUpdateEditorToolType
            TestUtils.injectFingerEventOnViewCenter(focusedEditText, MotionEvent.ACTION_DOWN);
            MotionEvent upEvent =
                    TestUtils.injectFingerEventOnViewCenter(focusedEditText, MotionEvent.ACTION_UP);
            int toolTypeFinger = upEvent.getToolType(upEvent.getActionIndex());
            assertEquals(
                    "tool type finger must match", MotionEvent.TOOL_TYPE_FINGER, toolTypeFinger);
            expectEvent(
                    stream,
                    onUpdateEditorToolTypeMatcher(toolTypeFinger),
                    TIMEOUT);

            // Start handwriting on same focused editor
            final int touchSlop = getTouchSlop();
            int startX = focusedEditText.getWidth() / 2;
            int startY = focusedEditText.getHeight() / 2;
            int endX = startX + 2 * touchSlop;
            int endY = startY + 2 * touchSlop;
            final int number = 5;
            TestUtils.injectStylusDownEvent(focusedEditText, startX, startY);
            TestUtils.injectStylusMoveEvents(focusedEditText, startX, startY,
                    endX, endY, number);
            // Handwriting should start.
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", focusedMarker),
                    TIMEOUT);
            TestUtils.injectStylusUpEvent(focusedEditText, endX, endY);
            imeSession.callFinishStylusHandwriting();
            expectEvent(
                    stream,
                    editorMatcher("onFinishStylusHandwriting", focusedMarker),
                    TIMEOUT_1_S);

            addVirtualStylusIdForTestSession();
            // Now start handwriting on unfocused editor and verify toolType is available in
            // EditorInfo
            startX = unfocusedEditText.getWidth() / 2;
            startY = unfocusedEditText.getHeight() / 2;
            endX = startX + 2 * touchSlop;
            endY = startY + 2 * touchSlop;

            TestUtils.injectStylusDownEvent(unfocusedEditText, startX, startY);
            TestUtils.injectStylusMoveEvents(unfocusedEditText, startX, startY,
                    endX, endY, number);
            expectEvent(stream, editorMatcher("onStartInput", unFocusedMarker), TIMEOUT);

            // toolType should be updated on next stylus handwriting start
            expectEvent(stream, onStartStylusHandwritingMatcher(
                    MotionEvent.TOOL_TYPE_STYLUS, unFocusedMarker), TIMEOUT);

            TestUtils.injectStylusUpEvent(unfocusedEditText, endX, endY);
        }
    }

    private static Predicate<ImeEvent> onStartInputMatcher(int toolType, String marker) {
        Predicate<ImeEvent> matcher = event -> {
            if (!TextUtils.equals("onStartInput", event.getEventName())) {
                return false;
            }
            EditorInfo info = event.getArguments().getParcelable("editorInfo");
            return info.getInitialToolType() == toolType
                    && TextUtils.equals(marker, info.privateImeOptions);
        };
        return withDescription(
                "onStartInput(initialToolType=" + toolType + ",marker=" + marker + ")", matcher);
    }

    private static Predicate<ImeEvent> onStartStylusHandwritingMatcher(
            int toolType, String marker) {
        Predicate<ImeEvent> matcher = event -> {
            if (!TextUtils.equals("onStartStylusHandwriting", event.getEventName())) {
                return false;
            }
            EditorInfo info = event.getArguments().getParcelable("editorInfo");
            return info.getInitialToolType() == toolType
                    && TextUtils.equals(marker, info.privateImeOptions);
        };
        return withDescription(
                "onStartStylusHandwriting(initialToolType=" + toolType
                        + ", marker=" + marker + ")", matcher);
    }

    private static Predicate<ImeEvent> onUpdateEditorToolTypeMatcher(int expectedToolType) {
        Predicate<ImeEvent> matcher = event -> {
            if (!TextUtils.equals("onUpdateEditorToolType", event.getEventName())) {
                return false;
            }
            final int actualToolType = event.getArguments().getInt("toolType");
            return actualToolType == expectedToolType;
        };
        return withDescription("onUpdateEditorToolType(toolType=" + expectedToolType + ")",
                matcher);
    }

    /**
     * Inject stylus events on top of a focused custom editor and verify handwriting is started and
     * stylus handwriting window is displayed.
     */
    @Test
    public void testHandwriting_focusedCustomEditor() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String focusedMarker = getTestMarker();
            final String unfocusedMarker = getTestMarker();
            final Pair<CustomEditorView, CustomEditorView> customEditorPair =
                    launchTestActivityWithCustomEditors(focusedMarker, unfocusedMarker);
            final CustomEditorView focusedCustomEditor = customEditorPair.first;

            expectEvent(stream, editorMatcher("onStartInput", focusedMarker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", focusedMarker),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();
            final int touchSlop = getTouchSlop();
            final int startX = focusedCustomEditor.getWidth() / 2;
            final int startY = focusedCustomEditor.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY + 2 * touchSlop;
            final int number = 5;
            TestUtils.injectStylusDownEvent(focusedCustomEditor, startX, startY);
            TestUtils.injectStylusMoveEvents(focusedCustomEditor, startX, startY,
                    endX, endY, number);
            // Handwriting should already be initiated before ACTION_UP.
            // Keyboard shouldn't show up.
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", focusedMarker),
                    NOT_EXPECT_TIMEOUT);
            // Handwriting should start.
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", focusedMarker),
                    TIMEOUT);

            verifyStylusHandwritingWindowIsShown(stream, imeSession);

            // Verify that stylus move events are swallowed by the handwriting initiator once
            // handwriting has been initiated and not dispatched to the view tree.
            assertThat(focusedCustomEditor.mStylusMoveEventCount).isLessThan(number);

            TestUtils.injectStylusUpEvent(focusedCustomEditor, endX, endY);
        }
    }

    /**
     * Inject stylus events on top of a handwriting initiation delegate view and verify handwriting
     * is started on the delegator editor and stylus handwriting window is displayed.
     */
    @Test
    public void testHandwriting_delegate() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String editTextMarker = getTestMarker();
            final View delegateView =
                    launchTestActivityWithDelegate(
                            editTextMarker, null /* delegateLatch */, 0 /* delegateDelayMs */);
            expectBindInput(stream, Process.myPid(), TIMEOUT);
            addVirtualStylusIdForTestSession();

            final int touchSlop = getTouchSlop();
            final int startX = delegateView.getWidth() / 2;
            final int startY = delegateView.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY + 2 * touchSlop;
            final int number = 5;
            TestUtils.injectStylusDownEvent(delegateView, startX, startY);
            TestUtils.injectStylusMoveEvents(delegateView, startX, startY, endX, endY, number);
            // The handwriting initiator should trigger the delegate view's callback which creates
            // the EditText and requests focus, which should then initiate handwriting for the
            // EditText.
            // Handwriting should already be initiated before ACTION_UP.
            // Keyboard shouldn't show up.
            notExpectEvent(
                    stream, editorMatcher("onStartInputView", editTextMarker), NOT_EXPECT_TIMEOUT);
            // Handwriting should start.
            expectEvent(stream, editorMatcher("onStartStylusHandwriting", editTextMarker), TIMEOUT);

            verifyStylusHandwritingWindowIsShown(stream, imeSession);

            TestUtils.injectStylusUpEvent(delegateView, endX, endY);
        }
    }

    /**
     * Inject stylus events on top of a handwriting initiation delegator view and verify handwriting
     * is started on the delegate editor, even though delegate took a little time to
     * acceptStylusHandwriting().
     */
    @Test
    public void testHandwriting_delegateDelayed() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String editTextMarker = getTestMarker();
            final CountDownLatch latch = new CountDownLatch(1);
            // Use a delegate that executes after 1 second delay.
            final View delegatorView =
                    launchTestActivityWithDelegate(editTextMarker, latch, TIMEOUT_1_S);
            expectBindInput(stream, Process.myPid(), TIMEOUT);
            addVirtualStylusIdForTestSession();

            final int touchSlop = getTouchSlop();
            final int startX = delegatorView.getWidth() / 2;
            final int startY = delegatorView.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY + 2 * touchSlop;
            final int number = 5;
            TestUtils.injectStylusDownEvent(delegatorView, startX, startY);
            TestUtils.injectStylusMoveEvents(delegatorView, startX, startY, endX, endY, number);
            // Wait until delegate makes request.
            latch.await(DELEGATION_AFTER_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            // Keyboard shouldn't show up.
            notExpectEvent(
                    stream, editorMatcher("onStartInputView", editTextMarker), NOT_EXPECT_TIMEOUT);
            // Handwriting should start since delegation was delayed (but still before timeout).
            expectEvent(
                    stream, editorMatcher("onStartStylusHandwriting", editTextMarker), TIMEOUT);
            verifyStylusHandwritingWindowIsShown(stream, imeSession);

            TestUtils.injectStylusUpEvent(delegatorView, endX, endY);
        }
    }

    /**
     * Inject stylus events on top of a handwriting initiation delegator view and verify handwriting
     * is not started on the delegate editor after delegate idle-timeout.
     */
    @Test
    public void testHandwriting_delegateAfterTimeout() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String editTextMarker = getTestMarker();
            final CountDownLatch latch = new CountDownLatch(1);
            // Use a delegate that executes after idle-timeout.
            final View delegatorView =
                    launchTestActivityWithDelegate(
                            editTextMarker, latch, DELEGATION_AFTER_IDLE_TIMEOUT_MS);
            expectBindInput(stream, Process.myPid(), TIMEOUT);
            addVirtualStylusIdForTestSession();

            final int touchSlop = getTouchSlop();
            final int startX = delegatorView.getWidth() / 2;
            final int startY = delegatorView.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY + 2 * touchSlop;
            final int number = 5;
            TestUtils.injectStylusDownEvent(delegatorView, startX, startY);
            TestUtils.injectStylusMoveEvents(delegatorView, startX, startY, endX, endY, number);
            // Wait until delegate makes request.
            latch.await(DELEGATION_AFTER_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            // Keyboard shouldn't show up.
            notExpectEvent(
                    stream, editorMatcher("onStartInputView", editTextMarker), NOT_EXPECT_TIMEOUT);
            // Handwriting should *not* start since delegation was idle timed-out.
            notExpectEvent(
                    stream, editorMatcher("onStartStylusHandwriting", editTextMarker), TIMEOUT);
            verifyStylusHandwritingWindowIsNotShown(stream, imeSession);

            TestUtils.injectStylusUpEvent(delegatorView, endX, endY);
        }
    }

    /**
     * Inject stylus events on top of a handwriting initiation delegate view and verify handwriting
     * is started on the delegator editor [in different package] and stylus handwriting is
     * started.
     * TODO(b/210039666): support instant apps for this test.
     */
    @AppModeFull(reason = "Launching external activity from this test is not yet supported.")
    @Test
    public void testHandwriting_delegateToDifferentPackage() throws Exception {
        testHandwriting_delegateToDifferentPackage(true /* setAllowedDelegatorPackage */);
    }

    /**
     * Inject stylus events on top of a handwriting initiation delegate view and verify handwriting
     * is not started on the delegator editor [in different package] because allowed package wasn't
     * set.
     * TODO(b/210039666): support instant apps for this test.
     */
    @AppModeFull(reason = "Launching external activity from this test is not yet supported.")
    @Test
    public void testHandwriting_delegateToDifferentPackage_fail() throws Exception {
        testHandwriting_delegateToDifferentPackage(false /* setAllowedDelegatorPackage */);
    }

    private void testHandwriting_delegateToDifferentPackage(boolean setAllowedDelegatorPackage)
            throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String editTextMarker = getTestMarker();
            final View delegateView =
                    launchTestActivityInExternalPackage(editTextMarker, setAllowedDelegatorPackage);
            expectBindInput(stream, Process.myPid(), TIMEOUT);
            addVirtualStylusIdForTestSession();

            final int touchSlop = getTouchSlop();
            final int startX = delegateView.getWidth() / 2;
            final int startY = delegateView.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY + 2 * touchSlop;
            final int number = 5;
            TestUtils.injectStylusDownEvent(delegateView, startX, startY);
            TestUtils.injectStylusMoveEvents(delegateView, startX, startY, endX, endY, number);

            // Keyboard shouldn't show up.
            notExpectEvent(
                    stream, editorMatcher("onStartInputView", editTextMarker),
                    NOT_EXPECT_TIMEOUT);
            if (setAllowedDelegatorPackage) {
                expectEvent(
                        stream, editorMatcher("onStartStylusHandwriting", editTextMarker), TIMEOUT);
                verifyStylusHandwritingWindowIsShown(stream, imeSession);
            } else {
                notExpectEvent(
                        stream, editorMatcher("onStartStylusHandwriting", editTextMarker),
                        NOT_EXPECT_TIMEOUT);
            }


        }
    }

    /**
     * Verify that system times-out Handwriting session after given timeout.
     */
    @Test
    public void testHandwritingSessionIdleTimeout() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final EditText editText = launchTestActivity(marker);

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();
            // update handwriting session timeout
            assertTrue(expectCommand(
                    stream,
                    imeSession.callSetStylusHandwritingTimeout(100 /* timeoutMs */),
                    TIMEOUT).getReturnBooleanValue());

            final int touchSlop = getTouchSlop();
            final int startX = editText.getWidth() / 2;
            final int startY = editText.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY;
            final int number = 5;
            TestUtils.injectStylusDownEvent(editText, startX, startY);
            TestUtils.injectStylusMoveEvents(editText, startX, startY,
                    endX, endY, number);
            // Handwriting should already be initiated before ACTION_UP.
            // keyboard shouldn't show up.
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);
            // Handwriting should start
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", marker),
                    TIMEOUT);

            // Handwriting should finish soon.
            expectEvent(
                    stream,
                    editorMatcher("onFinishStylusHandwriting", marker),
                    TIMEOUT_1_S);

            // test setting extremely large timeout and verify we limit it to
            // STYLUS_HANDWRITING_IDLE_TIMEOUT_MS
            assertTrue(expectCommand(
                    stream, imeSession.callSetStylusHandwritingTimeout(
                            InputMethodService.getStylusHandwritingIdleTimeoutMax().toMillis()
                                    * 10),
                    TIMEOUT).getReturnBooleanValue());
            assertEquals("Stylus handwriting timeout must be equal to max value.",
                    InputMethodService.getStylusHandwritingIdleTimeoutMax().toMillis(),
                    expectCommand(
                            stream, imeSession.callGetStylusHandwritingTimeout(), TIMEOUT)
                                    .getReturnLongValue());
        }
    }

    /**
     * Verify that when system has no stylus, there is no handwriting window.
     */
    @Test
    @ApiTest(apis = {"android.view.inputmethod.InputMethodManager#startStylusHandwriting",
            "android.inputmethodservice.InputMethodService#onStartStylusHandwriting",
            "android.inputmethodservice.InputMethodService#onFinishStylusHandwriting"})
    public void testNoStylusNoHandwritingWindow() throws Exception {
        // skip this test if device already has stylus.
        assumeFalse("Skipping test on devices that have stylus connected.",
                hasSupportedStylus());

        final InputMethodManager imm = mContext.getSystemService(InputMethodManager.class);
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final EditText editText = launchTestActivity(marker);

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            // Verify there is no handwriting window before stylus is added.
            assertFalse(expectCommand(
                    stream, imeSession.callHasStylusHandwritingWindow(), TIMEOUT_1_S)
                    .getReturnBooleanValue());

            addVirtualStylusIdForTestSession();

            final int touchSlop = getTouchSlop();
            final int startX = editText.getWidth() / 2;
            final int startY = editText.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY;
            final int number = 5;
            TestUtils.injectStylusDownEvent(editText, startX, startY);
            TestUtils.injectStylusMoveEvents(editText, startX, startY,
                    endX, endY, number);
            // Ensure stylus handwriting session start before it is finished with ACTION_UP. This
            // ensures system had enough time to initialize Ink window.
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", marker),
                    TIMEOUT);
            TestUtils.injectStylusUpEvent(editText, endX, endY);

            verifyStylusHandwritingWindowIsShown(stream, imeSession);

            // Finish handwriting to remove test stylus id.
            imeSession.callFinishStylusHandwriting();
            expectEvent(
                    stream,
                    editorMatcher("onFinishStylusHandwriting", marker),
                    TIMEOUT_1_S);

            // Verify no handwriting window after stylus is removed from device.
            assertFalse(expectCommand(
                    stream, imeSession.callHasStylusHandwritingWindow(), TIMEOUT_1_S)
                    .getReturnBooleanValue());

        }
    }

    /**
     * Verifies that in split-screen multi-window mode, unfocused activity can start handwriting
     */
    @Test
    @ApiTest(apis = {"android.view.inputmethod.InputMethodManager#startStylusHandwriting",
            "android.inputmethodservice.InputMethodService#onStartStylusHandwriting",
            "android.inputmethodservice.InputMethodService#onFinishStylusHandwriting"})
    public void testMultiWindow_unfocusedWindowCanStartHandwriting() throws Exception {
        assumeTrue(TestUtils.supportsSplitScreenMultiWindow());

        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();
            final String primaryMarker = getTestMarker();
            final String secondaryMarker = getTestMarker();

            // Launch an editor activity to be on the split primary task.
            final TestActivity splitPrimaryActivity = TestActivity.startSync(activity -> {
                final LinearLayout layout = new LinearLayout(activity);
                layout.setOrientation(LinearLayout.VERTICAL);
                final EditText editText = new EditText(activity);
                layout.addView(editText);
                editText.setHint("focused editText");
                editText.setPrivateImeOptions(primaryMarker);
                editText.requestFocus();
                return layout;
            });
            expectEvent(stream, editorMatcher("onStartInput", primaryMarker), TIMEOUT);
            notExpectEvent(stream, editorMatcher("onStartInputView", primaryMarker),
                    NOT_EXPECT_TIMEOUT);

            TestUtils.waitOnMainUntil(() -> splitPrimaryActivity.hasWindowFocus(), TIMEOUT);

            // Launch another activity to be on the split secondary task, expect stylus gesture on
            // it can steal focus from primary and start handwriting.
            final AtomicReference<EditText> editTextRef = new AtomicReference<>();
            final TestActivity splitSecondaryActivity = new TestActivity.Starter()
                    .asMultipleTask()
                    .withAdditionalFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                    .startSync(splitPrimaryActivity, activity -> {
                        final LinearLayout layout = new LinearLayout(activity);
                        layout.setOrientation(LinearLayout.VERTICAL);
                        final EditText editText = new EditText(activity);
                        editTextRef.set(editText);
                        layout.addView(editText);
                        editText.setHint("unfocused editText");
                        editText.setPrivateImeOptions(secondaryMarker);
                        return layout;
                    }, TestActivity2.class);
            notExpectEvent(stream, event -> "onStartInputView".equals(event.getEventName()),
                    NOT_EXPECT_TIMEOUT);
            TestUtils.waitOnMainUntil(() -> splitSecondaryActivity.hasWindowFocus(), TIMEOUT);
            TestUtils.waitOnMainUntil(() -> !splitPrimaryActivity.hasWindowFocus(), TIMEOUT_1_S);

            addVirtualStylusIdForTestSession();

            final EditText editText = editTextRef.get();
            final int touchSlop = getTouchSlop();
            final int startX = editText.getWidth() / 2;
            final int startY = editText.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY;
            final int number = 5;
            TestUtils.injectStylusDownEvent(editText, startX, startY);
            TestUtils.injectStylusMoveEvents(editText, startX, startY,
                    endX, endY, number);
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", secondaryMarker),
                    TIMEOUT);
            TestUtils.injectStylusUpEvent(editText, endX, endY);

            verifyStylusHandwritingWindowIsShown(stream, imeSession);

            // Finish handwriting to remove test stylus id.
            imeSession.callFinishStylusHandwriting();
            expectEvent(
                    stream,
                    editorMatcher("onFinishStylusHandwriting", secondaryMarker),
                    TIMEOUT_1_S);
        }
    }

    /**
     * Verifies that in split-screen multi-window mode, an unfocused window can't steal ongoing
     * handwriting session.
     */
    @Test
    @ApiTest(apis = {"android.view.inputmethod.InputMethodManager#startStylusHandwriting",
            "android.inputmethodservice.InputMethodService#onStartStylusHandwriting",
            "android.inputmethodservice.InputMethodService#onFinishStylusHandwriting"})
    public void testMultiWindow_unfocusedWindowCannotStealOngoingHandwriting() throws Exception {
        assumeTrue(TestUtils.supportsSplitScreenMultiWindow());

        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();
            final String primaryMarker = getTestMarker();
            final String secondaryMarker = getTestMarker();

            // Launch an editor activity to be on the split primary task.
            final AtomicReference<EditText> editTextPrimaryRef = new AtomicReference<>();
            final TestActivity splitPrimaryActivity = TestActivity.startSync(activity -> {
                final LinearLayout layout = new LinearLayout(activity);
                layout.setOrientation(LinearLayout.VERTICAL);
                final EditText editText = new EditText(activity);
                layout.addView(editText);
                editTextPrimaryRef.set(editText);
                editText.setHint("focused editText");
                editText.setPrivateImeOptions(primaryMarker);
                return layout;
            });
            notExpectEvent(stream,
                    editorMatcher("onStartInput", primaryMarker), NOT_EXPECT_TIMEOUT);
            notExpectEvent(stream,
                    editorMatcher("onStartInputView", primaryMarker), NOT_EXPECT_TIMEOUT);

            TestUtils.waitOnMainUntil(() -> splitPrimaryActivity.hasWindowFocus(), TIMEOUT);

            // Launch another activity to be on the split secondary task, expect stylus gesture on
            // it can steal focus from primary and start handwriting.
            final AtomicReference<EditText> editTextSecondaryRef = new AtomicReference<>();
            new TestActivity.Starter()
                    .asMultipleTask()
                    .withAdditionalFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                    .startSync(splitPrimaryActivity, activity -> {
                        final LinearLayout layout = new LinearLayout(activity);
                        layout.setOrientation(LinearLayout.VERTICAL);
                        final EditText editText = new EditText(activity);
                        editTextSecondaryRef.set(editText);
                        layout.addView(editText);
                        editText.setHint("unfocused editText");
                        editText.setPrivateImeOptions(secondaryMarker);
                        return layout;
                    }, TestActivity2.class);
            notExpectEvent(stream, event -> "onStartInputView".equals(event.getEventName()),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();

            // Inject events on primary to start handwriting.
            final EditText editTextPrimary = editTextPrimaryRef.get();
            final int touchSlop = getTouchSlop();
            int startX = editTextPrimary.getWidth() / 2;
            int startY = editTextPrimary.getHeight() / 2;
            int endX = startX + 2 * touchSlop;
            int endY = startY;
            final int number = 5;
            TestUtils.injectStylusDownEvent(editTextPrimary, startX, startY);
            TestUtils.injectStylusMoveEvents(editTextPrimary, startX, startY,
                    endX, endY, number);
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", primaryMarker),
                    TIMEOUT);
            TestUtils.injectStylusUpEvent(editTextPrimary, endX, endY);

            TestUtils.waitOnMainUntil(() -> splitPrimaryActivity.hasWindowFocus(), TIMEOUT_1_S);

            // Inject events on secondary shouldn't start handwriting on secondary
            // (since primary is already ongoing).
            final EditText editTextSecondary = editTextSecondaryRef.get();
            startX = editTextSecondary.getWidth() / 2;
            startY = editTextSecondary.getHeight() / 2;
            endX = startX + 2 * touchSlop;
            endY = startY;
            TestUtils.injectStylusDownEvent(editTextSecondary, startX, startY);
            TestUtils.injectStylusMoveEvents(editTextSecondary, startX, startY,
                    endX, endY, number);

            notExpectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", secondaryMarker),
                    TIMEOUT);
            TestUtils.injectStylusUpEvent(editTextSecondary, endX, endY);

            TestUtils.waitOnMainUntil(() -> splitPrimaryActivity.hasWindowFocus(), TIMEOUT_1_S);

            // Finish handwriting to remove test stylus id.
            imeSession.callFinishStylusHandwriting();
            expectEvent(
                    stream,
                    editorMatcher("onFinishStylusHandwriting", primaryMarker),
                    TIMEOUT_1_S);
        }
    }

    /**
     * Verify that once stylus hasn't been used for more than idle-timeout, there is no handwriting
     * window.
     */
    @Test
    @ApiTest(apis = {"android.view.inputmethod.InputMethodManager#startStylusHandwriting",
            "android.inputmethodservice.InputMethodService#onStartStylusHandwriting",
            "android.inputmethodservice.InputMethodService#onFinishStylusHandwriting"})
    public void testNoHandwritingWindow_afterIdleTimeout() throws Exception {
        // skip this test if device doesn't have stylus.
        assumeTrue("Skipping test on devices that don't stylus connected.",
                hasSupportedStylus());

        final InputMethodManager imm = mContext.getSystemService(InputMethodManager.class);
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final EditText editText = launchTestActivity(marker);

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            SystemUtil.runWithShellPermissionIdentity(() ->
                    imm.setStylusWindowIdleTimeoutForTest(TIMEOUT));

            final int touchSlop = getTouchSlop();
            final int startX = editText.getWidth() / 2;
            final int startY = editText.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY + 2 * touchSlop;
            final int number = 5;
            TestUtils.injectStylusDownEvent(editText, startX, startY);
            TestUtils.injectStylusMoveEvents(editText, startX, startY, endX, endY, number);
            // Handwriting should already be initiated before ACTION_UP.
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", marker),
                    TIMEOUT);
            TestUtils.injectStylusUpEvent(editText, endX, endY);

            verifyStylusHandwritingWindowIsShown(stream, imeSession);

            // Finish handwriting to remove test stylus id.
            imeSession.callFinishStylusHandwriting();
            expectEvent(
                    stream,
                    editorMatcher("onFinishStylusHandwriting", marker),
                    TIMEOUT_1_S);

            // Verify handwriting window is removed after stylus handwriting idle-timeout.
            TestUtils.waitOnMainUntil(() -> {
                try {
                    // wait until callHasStylusHandwritingWindow returns false
                    return !expectCommand(stream, imeSession.callHasStylusHandwritingWindow(),
                                    TIMEOUT).getReturnBooleanValue();
                } catch (TimeoutException e) {
                    e.printStackTrace();
                }
                // handwriting window is still around.
                return true;
            }, TIMEOUT);

            // reset idle-timeout
            SystemUtil.runWithShellPermissionIdentity(() ->
                    imm.setStylusWindowIdleTimeoutForTest(0));
        }
    }

    /**
     * Verify that Ink window is around before timeout
     */
    @Test
    @ApiTest(apis = {"android.view.inputmethod.InputMethodManager#startStylusHandwriting",
            "android.inputmethodservice.InputMethodService#onStartStylusHandwriting",
            "android.inputmethodservice.InputMethodService#onFinishStylusHandwriting"})
    public void testHandwritingWindow_beforeTimeout() throws Exception {
        // skip this test if device doesn't have stylus.
        assumeTrue("Skipping test on devices that don't stylus connected.",
                hasSupportedStylus());

        final InputMethodManager imm = mContext.getSystemService(InputMethodManager.class);
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String marker = getTestMarker();
            final EditText editText = launchTestActivity(marker);

            expectEvent(stream, editorMatcher("onStartInput", marker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", marker),
                    NOT_EXPECT_TIMEOUT);

            SystemUtil.runWithShellPermissionIdentity(() ->
                    imm.setStylusWindowIdleTimeoutForTest(TIMEOUT));

            final int touchSlop = getTouchSlop();
            final int startX = editText.getWidth() / 2;
            final int startY = editText.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY + 2 * touchSlop;
            final int number = 5;
            TestUtils.injectStylusDownEvent(editText, startX, startY);
            TestUtils.injectStylusMoveEvents(editText, startX, startY, endX, endY, number);
            // Handwriting should start
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", marker),
                    TIMEOUT);

            TestUtils.injectStylusUpEvent(editText, endX, endY);

            verifyStylusHandwritingWindowIsShown(stream, imeSession);

            // Finish handwriting to remove test stylus id.
            imeSession.callFinishStylusHandwriting();
            expectEvent(
                    stream,
                    editorMatcher("onFinishStylusHandwriting", marker),
                    TIMEOUT_1_S);

            // Just any stylus events to delay idle-timeout
            TestUtils.injectStylusDownEvent(editText, 0, 0);
            TestUtils.injectStylusUpEvent(editText, 0, 0);

            // Verify handwriting window is still around as stylus was used recently.
            assertTrue(expectCommand(
                    stream, imeSession.callHasStylusHandwritingWindow(), TIMEOUT_1_S)
                    .getReturnBooleanValue());

            // Reset idle-timeout
            SystemUtil.runWithShellPermissionIdentity(() ->
                    imm.setStylusWindowIdleTimeoutForTest(0));
        }
    }

    /**
     * Inject stylus events on top of an unfocused custom editor and verify handwriting is started
     * and stylus handwriting window is displayed.
     */
    @Test
    public void testHandwriting_unfocusedCustomEditor() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String focusedMarker = getTestMarker();
            final String unfocusedMarker = getTestMarker();
            final Pair<CustomEditorView, CustomEditorView> customEditorPair =
                    launchTestActivityWithCustomEditors(focusedMarker, unfocusedMarker);
            final CustomEditorView unfocusedCustomEditor = customEditorPair.second;

            expectEvent(stream, editorMatcher("onStartInput", focusedMarker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", focusedMarker),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();
            final int touchSlop = getTouchSlop();
            final int startX = unfocusedCustomEditor.getWidth() / 2;
            final int startY = unfocusedCustomEditor.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY + 2 * touchSlop;
            final int number = 5;
            TestUtils.injectStylusDownEvent(unfocusedCustomEditor, startX, startY);
            TestUtils.injectStylusMoveEvents(unfocusedCustomEditor, startX, startY,
                    endX, endY, number);
            // Handwriting should already be initiated before ACTION_UP.
            // unfocusedCustomEditor is focused and triggers onStartInput.
            expectEvent(stream, editorMatcher("onStartInput", unfocusedMarker), TIMEOUT);
            // Keyboard shouldn't show up.
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", unfocusedMarker),
                    NOT_EXPECT_TIMEOUT);
            // Handwriting should start.
            expectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", unfocusedMarker),
                    TIMEOUT);

            verifyStylusHandwritingWindowIsShown(stream, imeSession);

            // Verify that stylus move events are swallowed by the handwriting initiator once
            // handwriting has been initiated and not dispatched to the view tree.
            assertThat(unfocusedCustomEditor.mStylusMoveEventCount).isLessThan(number);

            TestUtils.injectStylusUpEvent(unfocusedCustomEditor, endX, endY);
        }
    }

    /**
     * Inject stylus events on top of a focused custom editor that disables auto handwriting.
     *
     * @link InputMethodManager#startStylusHandwriting(View)} should not be called.
     */
    @Test
    public void testAutoHandwritingDisabled_customEditor() throws Exception {
        try (MockImeSession imeSession = MockImeSession.create(
                InstrumentationRegistry.getInstrumentation().getContext(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                new ImeSettings.Builder())) {
            final ImeEventStream stream = imeSession.openEventStream();

            final String focusedMarker = getTestMarker();
            final String unfocusedMarker = getTestMarker();
            final Pair<CustomEditorView, CustomEditorView> customEditorPair =
                    launchTestActivityWithCustomEditors(focusedMarker, unfocusedMarker);
            final CustomEditorView focusedCustomEditor = customEditorPair.first;
            focusedCustomEditor.setAutoHandwritingEnabled(false);

            expectEvent(stream, editorMatcher("onStartInput", focusedMarker), TIMEOUT);
            notExpectEvent(
                    stream,
                    editorMatcher("onStartInputView", focusedMarker),
                    NOT_EXPECT_TIMEOUT);

            addVirtualStylusIdForTestSession();
            final int touchSlop = getTouchSlop();
            final int startX = focusedCustomEditor.getWidth() / 2;
            final int startY = focusedCustomEditor.getHeight() / 2;
            final int endX = startX + 2 * touchSlop;
            final int endY = startY + 2 * touchSlop;
            final int number = 5;
            TestUtils.injectStylusDownEvent(focusedCustomEditor, startX, startY);
            TestUtils.injectStylusMoveEvents(focusedCustomEditor, startX, startY,
                    endX, endY, number);
            // Handwriting should not start
            notExpectEvent(
                    stream,
                    editorMatcher("onStartStylusHandwriting", focusedMarker),
                    NOT_EXPECT_TIMEOUT);

            // Verify that all stylus move events are dispatched to the view tree.
            assertThat(focusedCustomEditor.mStylusMoveEventCount).isEqualTo(number);

            TestUtils.injectStylusUpEvent(focusedCustomEditor, endX, endY);
        }
    }

    private EditText launchTestActivity(@NonNull String marker) {
        return launchTestActivity(marker, getTestMarker()).first;
    }

    private static String getTestMarker() {
        return TEST_MARKER_PREFIX + "/" + SystemClock.elapsedRealtimeNanos();
    }

    private static int getTouchSlop() {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        return ViewConfiguration.get(context).getScaledTouchSlop();
    }

    private Pair<EditText, EditText> launchTestActivity(@NonNull String focusedMarker,
            @NonNull String nonFocusedMarker) {
        final AtomicReference<EditText> focusedEditTextRef = new AtomicReference<>();
        final AtomicReference<EditText> nonFocusedEditTextRef = new AtomicReference<>();
        TestActivity.startSync(activity -> {
            final LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            // Adding some top padding tests that inject stylus event out of the view boundary.
            layout.setPadding(0, 100, 0, 0);

            final EditText focusedEditText = new EditText(activity);
            focusedEditText.setHint("focused editText");
            focusedEditText.setPrivateImeOptions(focusedMarker);
            focusedEditText.requestFocus();
            focusedEditText.setAutoHandwritingEnabled(true);
            focusedEditText.setHandwritingBoundsOffsets(
                    HANDWRITING_BOUNDS_OFFSET_PX,
                    HANDWRITING_BOUNDS_OFFSET_PX,
                    HANDWRITING_BOUNDS_OFFSET_PX,
                    HANDWRITING_BOUNDS_OFFSET_PX);
            focusedEditTextRef.set(focusedEditText);
            layout.addView(focusedEditText);

            final EditText nonFocusedEditText = new EditText(activity);
            nonFocusedEditText.setPrivateImeOptions(nonFocusedMarker);
            nonFocusedEditText.setHint("target editText");
            nonFocusedEditText.setAutoHandwritingEnabled(true);
            nonFocusedEditTextRef.set(nonFocusedEditText);
            nonFocusedEditText.setHandwritingBoundsOffsets(
                    HANDWRITING_BOUNDS_OFFSET_PX,
                    HANDWRITING_BOUNDS_OFFSET_PX,
                    HANDWRITING_BOUNDS_OFFSET_PX,
                    HANDWRITING_BOUNDS_OFFSET_PX);
            layout.addView(nonFocusedEditText);
            return layout;
        });
        return new Pair<>(focusedEditTextRef.get(), nonFocusedEditTextRef.get());
    }

    private Pair<CustomEditorView, CustomEditorView> launchTestActivityWithCustomEditors(
            @NonNull String focusedMarker, @NonNull String unfocusedMarker) {
        final AtomicReference<CustomEditorView> focusedCustomEditorRef = new AtomicReference<>();
        final AtomicReference<CustomEditorView> unfocusedCustomEditorRef = new AtomicReference<>();
        TestActivity.startSync(activity -> {
            final LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            // Add some top padding for tests that inject stylus event out of the view boundary.
            layout.setPadding(0, 100, 0, 0);

            final CustomEditorView focusedCustomEditor =
                    new CustomEditorView(activity, focusedMarker, Color.RED);
            focusedCustomEditor.setAutoHandwritingEnabled(true);
            focusedCustomEditor.requestFocus();
            focusedCustomEditorRef.set(focusedCustomEditor);
            layout.addView(focusedCustomEditor);

            final CustomEditorView unfocusedCustomEditor =
                    new CustomEditorView(activity, unfocusedMarker, Color.BLUE);
            unfocusedCustomEditor.setAutoHandwritingEnabled(true);
            unfocusedCustomEditorRef.set(unfocusedCustomEditor);
            layout.addView(unfocusedCustomEditor);

            return layout;
        });
        return new Pair<>(focusedCustomEditorRef.get(), unfocusedCustomEditorRef.get());
    }

    private View launchTestActivityWithDelegate(
            @NonNull String editTextMarker, CountDownLatch delegateLatch, long delegateDelayMs) {
        final AtomicReference<View> delegatorViewRef = new AtomicReference<>();
        TestActivity.startSync(activity -> {
            final LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);

            final View delegatorView = new View(activity);
            delegatorViewRef.set(delegatorView);
            delegatorView.setBackgroundColor(Color.GREEN);
            delegatorView.setHandwritingDelegatorCallback(
                    () -> {
                        final EditText editText = new EditText(activity);
                        editText.setIsHandwritingDelegate(true);
                        editText.setPrivateImeOptions(editTextMarker);
                        editText.setHint("editText");
                        layout.addView(editText);
                        editText.postDelayed(() -> {
                            editText.requestFocus();
                            if (delegateLatch != null) {
                                delegateLatch.countDown();
                            }
                        }, delegateDelayMs);
                    });

            LinearLayout.LayoutParams layoutParams =
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40);
            // Add space so that stylus motion on the delegate view is not within the edit text's
            // extended handwriting bounds.
            layoutParams.bottomMargin = 200;
            layout.addView(delegatorView, layoutParams);
            return layout;
        });
        return delegatorViewRef.get();
    }

    private View launchTestActivityInExternalPackage(
            @NonNull final String editTextMarker, final boolean setAllowedDelegatorPackage) {
        final AtomicReference<View> delegateViewRef = new AtomicReference<>();
        TestActivity.startSync(activity -> {
            final LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);

            final View delegatorView = new View(activity);
            delegateViewRef.set(delegatorView);
            delegatorView.setBackgroundColor(Color.GREEN);

            delegatorView.setHandwritingDelegatorCallback(()-> {
                // launch activity in a different package.
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setComponent(new ComponentName(
                        "android.view.inputmethod.ctstestapp",
                        "android.view.inputmethod.ctstestapp.MainActivity"));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra(MockTestActivityUtil.EXTRA_KEY_PRIVATE_IME_OPTIONS, editTextMarker);
                intent.putExtra(MockTestActivityUtil.EXTRA_HANDWRITING_DELEGATE, true);
                activity.startActivity(intent);
            });
            if (setAllowedDelegatorPackage) {
                delegatorView.setAllowedHandwritingDelegatePackage(
                        "android.view.inputmethod.ctstestapp");
            }
            layout.addView(
                    delegatorView,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40));
            return layout;
        });
        return delegateViewRef.get();
    }

    private boolean hasSupportedStylus() {
        final InputManager im = mContext.getSystemService(InputManager.class);
        for (int id : im.getInputDeviceIds()) {
            InputDevice inputDevice = im.getInputDevice(id);
            if (inputDevice != null && isStylusDevice(inputDevice)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStylusDevice(InputDevice inputDevice) {
        return inputDevice.supportsSource(InputDevice.SOURCE_STYLUS)
                || inputDevice.supportsSource(InputDevice.SOURCE_BLUETOOTH_STYLUS);
    }

    private void addVirtualStylusIdForTestSession() {
        SystemUtil.runWithShellPermissionIdentity(() -> {
            mContext.getSystemService(InputMethodManager.class)
                    .addVirtualStylusIdForTestSession();
        }, Manifest.permission.TEST_INPUT_METHOD);
    }

    private static final class CustomEditorView extends View {
        private final String mMarker;
        private int mStylusMoveEventCount = 0;

        private CustomEditorView(Context context, @NonNull String marker,
                @ColorInt int backgroundColor) {
            super(context);
            mMarker = marker;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setBackgroundColor(backgroundColor);
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return true;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT;
            outAttrs.privateImeOptions = mMarker;
            return new NoOpInputConnection();
        }

        @Override
        public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // This View needs a valid size to be focusable.
            setMeasuredDimension(300, 100);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_STYLUS) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    // Return true to receive ACTION_MOVE events.
                    return true;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    mStylusMoveEventCount++;
                }
            }
            return super.onTouchEvent(event);
        }
    }
}
