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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.server.wm.app.Components.HOME_ACTIVITY;
import static android.server.wm.app.Components.SECONDARY_HOME_ACTIVITY;
import static android.server.wm.app.Components.SINGLE_HOME_ACTIVITY;
import static android.server.wm.app.Components.SINGLE_SECONDARY_HOME_ACTIVITY;
import static android.server.wm.app.Components.TEST_LIVE_WALLPAPER_SERVICE;
import static android.server.wm.app.Components.TestLiveWallpaperKeys.COMPONENT;
import static android.server.wm.app.Components.TestLiveWallpaperKeys.ENGINE_DISPLAY_ID;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import static com.android.cts.mockime.ImeEventStreamTestUtils.editorMatcher;
import static com.android.cts.mockime.ImeEventStreamTestUtils.expectCommand;
import static com.android.cts.mockime.ImeEventStreamTestUtils.expectEvent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.server.wm.ActivityManagerState.ActivityDisplay;
import android.server.wm.TestJournalProvider.TestJournalContainer;
import android.server.wm.WindowManagerState.Display;
import android.server.wm.WindowManagerState.WindowState;
import android.text.TextUtils;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.test.filters.FlakyTest;

import com.android.compatibility.common.util.ImeAwareEditText;
import com.android.compatibility.common.util.SystemUtil;
import com.android.compatibility.common.util.TestUtils;
import com.android.cts.mockime.ImeCommand;
import com.android.cts.mockime.ImeEvent;
import com.android.cts.mockime.ImeEventStream;
import com.android.cts.mockime.ImeSettings;
import com.android.cts.mockime.MockImeSession;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Build/Install/Run:
 *     atest CtsWindowManagerDeviceTestCases:SystemDecorationMultiDisplayTests
 *
 * This tests that verify the following should not be run for OEM device verification:
 * Wallpaper added if display supports system decorations (and not added otherwise)
 * Navigation bar is added if display supports system decorations (and not added otherwise)
 * Secondary Home is shown if display supports system decorations (and not shown otherwise)
 * IME is shown if display supports system decorations (and not shown otherwise)
 */
@Presubmit
public class MultiDisplaySystemDecorationTests extends MultiDisplayTestBase {

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        assumeTrue(supportsMultiDisplay());
        assumeTrue(supportsSystemDecorsOnSecondaryDisplays());
    }

    // Wallpaper related tests
    /**
     * Test WallpaperService.Engine#getDisplayContext can work on secondary display.
     */
    @Test
    public void testWallpaperGetDisplayContext() throws Exception {
        try (final ChangeWallpaperSession wallpaperSession = new ChangeWallpaperSession();
             final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {

            TestJournalContainer.start();

            final ActivityDisplay newDisplay = virtualDisplaySession
                    .setSimulateDisplay(true).setShowSystemDecorations(true).createDisplay();

            wallpaperSession.setWallpaperComponent(TEST_LIVE_WALLPAPER_SERVICE);
            final String TARGET_ENGINE_DISPLAY_ID = ENGINE_DISPLAY_ID + newDisplay.mId;
            final TestJournalProvider.TestJournal journal = TestJournalContainer.get(COMPONENT);
            TestUtils.waitUntil("Waiting for wallpaper engine bounded", 5 /* timeoutSecond */,
                    () -> journal.extras.getBoolean(TARGET_ENGINE_DISPLAY_ID));
        }
    }

    /**
     * Tests that wallpaper shows on secondary displays.
     */
    @Test
    @FlakyTest(bugId = 131005232)
    public void testWallpaperShowOnSecondaryDisplays() throws Exception {
        try (final ChangeWallpaperSession wallpaperSession = new ChangeWallpaperSession();
             final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession();
             final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {

            final ActivityDisplay untrustedDisplay = externalDisplaySession
                    .setPublicDisplay(true).setShowSystemDecorations(true).createVirtualDisplay();

            final ActivityDisplay decoredSystemDisplay = virtualDisplaySession
                    .setSimulateDisplay(true).setShowSystemDecorations(true).createDisplay();

            final Bitmap tmpWallpaper = wallpaperSession.getTestBitmap();
            wallpaperSession.setImageWallpaper(tmpWallpaper);

            mAmWmState.waitForWithWmState(
                    (state) -> isWallpaperOnDisplay(state, decoredSystemDisplay.mId),
                    "Waiting for wallpaper window to show");

            assertTrue("Wallpaper must be displayed on system owned display with system decor flag",
                    isWallpaperOnDisplay(mAmWmState.getWmState(), decoredSystemDisplay.mId));

            assertFalse("Wallpaper must not be displayed on the untrusted display",
                    isWallpaperOnDisplay(mAmWmState.getWmState(), untrustedDisplay.mId));
        }
    }

    private class ChangeWallpaperSession implements AutoCloseable {
        private final WallpaperManager mWallpaperManager;
        private Bitmap mTestBitmap;

        public ChangeWallpaperSession() {
            mWallpaperManager = WallpaperManager.getInstance(mContext);
        }

        public Bitmap getTestBitmap() {
            if (mTestBitmap == null) {
                mTestBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
                final Canvas canvas = new Canvas(mTestBitmap);
                canvas.drawColor(Color.BLUE);
            }
            return mTestBitmap;
        }

        public void setImageWallpaper(Bitmap bitmap) throws Exception {
            SystemUtil.runWithShellPermissionIdentity(() ->
                    mWallpaperManager.setBitmap(bitmap));
        }

        public void setWallpaperComponent(ComponentName componentName) throws Exception {
            SystemUtil.runWithShellPermissionIdentity(() ->
                    mWallpaperManager.setWallpaperComponent(componentName));
        }

        @Override
        public void close() throws Exception {
            SystemUtil.runWithShellPermissionIdentity(mWallpaperManager::clearWallpaper);
            if (mTestBitmap != null) {
                mTestBitmap.recycle();
            }
        }
    }

    private boolean isWallpaperOnDisplay(WindowManagerState windowManagerState, int displayId) {
        return windowManagerState.getMatchingWindowType(TYPE_WALLPAPER).stream().anyMatch(
                w -> w.getDisplayId() == displayId);
    }

    // Navigation bar related tests
    // TODO(115978725): add runtime sys decor change test once we can do this.
    /**
     * Test that navigation bar should show on display with system decoration.
     */
    @Test
    public void testNavBarShowingOnDisplayWithDecor() throws Exception {
        try (final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession()) {
            final ActivityDisplay newDisplay = externalDisplaySession
                    .setPublicDisplay(true).setShowSystemDecorations(true).createVirtualDisplay();

            mAmWmState.waitAndAssertNavBarShownOnDisplay(newDisplay.mId);
        }
    }

    /**
     * Test that navigation bar should not show on display without system decoration.
     */
    @Test
    @FlakyTest(bugId = 131005232)
    public void testNavBarNotShowingOnDisplayWithoutDecor() throws Exception {
        try (final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession()) {
            // Wait navigation bar show on default display and record the states.
            mAmWmState.waitAndAssertNavBarShownOnDisplay(DEFAULT_DISPLAY);
            final List<WindowState> expected = mAmWmState.getWmState().getAllNavigationBarStates();

            externalDisplaySession.setPublicDisplay(true)
                    .setShowSystemDecorations(false).createVirtualDisplay();

            waitAndAssertNavBarStatesAreTheSame(expected);
        }
    }

    /**
     * Test that navigation bar should not show on private display even if the display
     * supports system decoration.
     */
    @Test
    @FlakyTest(bugId = 131005232)
    public void testNavBarNotShowingOnPrivateDisplay() throws Exception {
        try (final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession()) {
            // Wait navigation bar show on default display and record the states.
            mAmWmState.waitAndAssertNavBarShownOnDisplay(DEFAULT_DISPLAY);
            final List<WindowState> expected = mAmWmState.getWmState().getAllNavigationBarStates();

            externalDisplaySession.setPublicDisplay(false)
                    .setShowSystemDecorations(true).createVirtualDisplay();

            waitAndAssertNavBarStatesAreTheSame(expected);
        }
    }

    private void waitAndAssertNavBarStatesAreTheSame(List<WindowState> expected) throws Exception {
        // This is used to verify that we have nav bars shown on the same displays
        // as before the test.
        //
        // The strategy is:
        // Once a display with system ui decor support is created and a nav bar shows on the
        // display, go back to verify whether the nav bar states are unchanged to verify that no nav
        // bars were added to a display that was added before executing this method that shouldn't
        // have nav bars (i.e. private or without system ui decor).
        try (final ExternalDisplaySession secondDisplaySession = new ExternalDisplaySession()) {
            final ActivityDisplay supportsSysDecorDisplay = secondDisplaySession
                    .setPublicDisplay(true).setShowSystemDecorations(true).createVirtualDisplay();
            mAmWmState.waitAndAssertNavBarShownOnDisplay(supportsSysDecorDisplay.mId);
            // This display has finished his task. Just close it.
        }

        mAmWmState.computeState(true);
        final List<WindowState> result = mAmWmState.getWmState().getAllNavigationBarStates();

        assertEquals("The number of nav bars should be the same", expected.size(), result.size());

        // Nav bars should show on the same displays
        for (int i = 0; i < expected.size(); i++) {
            final int expectedDisplayId = expected.get(i).getDisplayId();
            mAmWmState.waitAndAssertNavBarShownOnDisplay(expectedDisplayId);
        }
    }

    // Secondary Home related tests
    /**
     * Tests launching a home activity on virtual display without system decoration support.
     */
    @Test
    public void testLaunchHomeActivityOnSecondaryDisplayWithoutDecorations() throws Exception {
        try (final HomeActivitySession homeSession =
                     new HomeActivitySession(SECONDARY_HOME_ACTIVITY);
             final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession()) {
            // Create new virtual display without system decoration support.
            final ActivityDisplay newDisplay = externalDisplaySession.createVirtualDisplay();

            // Secondary home activity can't be launched on the display without system decoration
            // support.
            assertEquals("No stacks on newly launched virtual display", 0,
                    newDisplay.mStacks.size());
        }
    }

    /**
     * Tests launching a single instance home activity on virtual display with system decoration
     * support.
     */
    @Test
    public void testLaunchSingleHomeActivityOnDisplayWithDecorations() throws Exception {
        try (final HomeActivitySession homeSession = new HomeActivitySession(SINGLE_HOME_ACTIVITY);
             final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession()) {
            // Create new virtual display with system decoration support.
            final ActivityDisplay newDisplay
                    = externalDisplaySession.setShowSystemDecorations(true).createVirtualDisplay();

            // If default home doesn't support multi-instance, default secondary home activity
            // should be automatically launched on the new display.
            waitAndAssertTopResumedActivity(getDefaultSecondaryHomeComponent(), newDisplay.mId,
                    "Activity launched on secondary display must be focused and on top");
            assertEquals("Top activity must be home type", ACTIVITY_TYPE_HOME,
                    mAmWmState.getAmState().getFrontStackActivityType(newDisplay.mId));
        }
    }

    /**
     * Tests launching a single instance home activity with SECONDARY_HOME on virtual display with
     * system decoration support.
     */
    @Test
    public void testLaunchSingleSecondaryHomeActivityOnDisplayWithDecorations() throws Exception {
        try (final HomeActivitySession homeSession =
                     new HomeActivitySession(SINGLE_SECONDARY_HOME_ACTIVITY);
             final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession()) {
            // Create new virtual display with system decoration support.
            final ActivityDisplay newDisplay
                    = externalDisplaySession.setShowSystemDecorations(true).createVirtualDisplay();

            // If provided secondary home doesn't support multi-instance, default secondary home
            // activity should be automatically launched on the new display.
            waitAndAssertTopResumedActivity(getDefaultSecondaryHomeComponent(), newDisplay.mId,
                    "Activity launched on secondary display must be focused and on top");
            assertEquals("Top activity must be home type", ACTIVITY_TYPE_HOME,
                    mAmWmState.getAmState().getFrontStackActivityType(newDisplay.mId));
        }
    }

    /**
     * Tests launching a multi-instance home activity on virtual display with system decoration
     * support.
     */
    @Test
    public void testLaunchHomeActivityOnDisplayWithDecorations() throws Exception {
        try (final HomeActivitySession homeSession = new HomeActivitySession(HOME_ACTIVITY);
             final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession()) {
            // Create new virtual display with system decoration support.
            final ActivityDisplay newDisplay
                    = externalDisplaySession.setShowSystemDecorations(true).createVirtualDisplay();

            // If default home doesn't have SECONDARY_HOME category, default secondary home
            // activity should be automatically launched on the new display.
            waitAndAssertTopResumedActivity(getDefaultSecondaryHomeComponent(), newDisplay.mId,
                    "Activity launched on secondary display must be focused and on top");
            assertEquals("Top activity must be home type", ACTIVITY_TYPE_HOME,
                    mAmWmState.getAmState().getFrontStackActivityType(newDisplay.mId));
        }
    }

    /**
     * Tests launching a multi-instance home activity with SECONDARY_HOME on virtual display with
     * system decoration support.
     */
    @Test
    public void testLaunchSecondaryHomeActivityOnDisplayWithDecorations() throws Exception {
        try (final HomeActivitySession homeSession =
                     new HomeActivitySession(SECONDARY_HOME_ACTIVITY);
             final ExternalDisplaySession externalDisplaySession = new ExternalDisplaySession()) {
            // Create new virtual display with system decoration support.
            final ActivityDisplay newDisplay
                    = externalDisplaySession.setShowSystemDecorations(true).createVirtualDisplay();

            // Provided secondary home activity should be automatically launched on the new
            // display.
            waitAndAssertTopResumedActivity(SECONDARY_HOME_ACTIVITY, newDisplay.mId,
                    "Activity launched on secondary display must be focused and on top");
            assertEquals("Top activity must be home type", ACTIVITY_TYPE_HOME,
                    mAmWmState.getAmState().getFrontStackActivityType(newDisplay.mId));
        }
    }

    // IME related tests
    @Test
    @FlakyTest(bugId = 131005232)
    public void testImeWindowCanSwitchToDifferentDisplays() throws Exception {
        try (final TestActivitySession<ImeTestActivity> imeTestActivitySession = new
                TestActivitySession<>();
             final TestActivitySession<ImeTestActivity2> imeTestActivitySession2 = new
                     TestActivitySession<>();
             final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession();

             // Leverage MockImeSession to ensure at least an IME exists as default.
             final MockImeSession mockImeSession = MockImeSession.create(
                     mContext, getInstrumentation().getUiAutomation(), new ImeSettings.Builder())) {

            // Create a virtual display and launch an activity on it.
            final ActivityDisplay newDisplay = virtualDisplaySession.setShowSystemDecorations(true)
                    .setSimulateDisplay(true).createDisplay();
            imeTestActivitySession.launchTestActivityOnDisplaySync(ImeTestActivity.class,
                    newDisplay.mId);

            // Make the activity to show soft input.
            final ImeEventStream stream = mockImeSession.openEventStream();
            imeTestActivitySession.runOnMainSyncAndWait(
                    imeTestActivitySession.getActivity()::showSoftInput);
            waitOrderedImeEventsThenAssertImeShown(stream, newDisplay.mId,
                    editorMatcher("onStartInput",
                            imeTestActivitySession.getActivity().mEditText.getPrivateImeOptions()),
                    event -> "showSoftInput".equals(event.getEventName()));

            // Assert the configuration of the IME window is the same as the configuration of the
            // virtual display.
            assertImeWindowAndDisplayConfiguration(mAmWmState.getImeWindowState(), newDisplay);

            // Launch another activity on the default display.
            imeTestActivitySession2.launchTestActivityOnDisplaySync(
                    ImeTestActivity2.class, DEFAULT_DISPLAY);

            // Make the activity to show soft input.
            imeTestActivitySession2.runOnMainSyncAndWait(
                    imeTestActivitySession2.getActivity()::showSoftInput);
            waitOrderedImeEventsThenAssertImeShown(stream, DEFAULT_DISPLAY,
                    editorMatcher("onStartInput",
                            imeTestActivitySession2.getActivity().mEditText.getPrivateImeOptions()),
                    event -> "showSoftInput".equals(event.getEventName()));

            // Assert the configuration of the IME window is the same as the configuration of the
            // default display.
            assertImeWindowAndDisplayConfiguration(mAmWmState.getImeWindowState(),
                    mAmWmState.getAmState().getDisplay(DEFAULT_DISPLAY));
        }
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testImeApiForBug118341760() throws Exception {
        final long TIMEOUT_START_INPUT = TimeUnit.SECONDS.toMillis(5);

        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession();
             final TestActivitySession<ImeTestActivityWithBrokenContextWrapper>
                     imeTestActivitySession = new TestActivitySession<>();

             // Leverage MockImeSession to ensure at least an IME exists as default.
             final MockImeSession mockImeSession = MockImeSession.create(
                     mContext, getInstrumentation().getUiAutomation(), new ImeSettings.Builder())) {

            // Create a virtual display and launch an activity on it.
            final ActivityDisplay newDisplay = virtualDisplaySession.setShowSystemDecorations(true)
                    .setSimulateDisplay(true).createDisplay();
            imeTestActivitySession.launchTestActivityOnDisplaySync(
                    ImeTestActivityWithBrokenContextWrapper.class, newDisplay.mId);

            final ImeTestActivityWithBrokenContextWrapper activity =
                    imeTestActivitySession.getActivity();
            final ImeEventStream stream = mockImeSession.openEventStream();
            final String privateImeOption = activity.getEditText().getPrivateImeOptions();
            expectEvent(stream, event -> {
                if (!TextUtils.equals("onStartInput", event.getEventName())) {
                    return false;
                }
                final EditorInfo editorInfo = event.getArguments().getParcelable("editorInfo");
                return TextUtils.equals(editorInfo.packageName, mContext.getPackageName())
                        && TextUtils.equals(editorInfo.privateImeOptions, privateImeOption);
            }, TIMEOUT_START_INPUT);

            imeTestActivitySession.runOnMainSyncAndWait(() -> {
                final InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
                assertTrue("InputMethodManager.isActive() should work",
                        imm.isActive(activity.getEditText()));
            });
        }
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testImeWindowCanSwitchWhenTopFocusedDisplayChange() throws Exception {
        // If config_perDisplayFocusEnabled, the focus will not move even if touching on
        // the Activity in the different display.
        assumeFalse(perDisplayFocusEnabled());

        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession();
             final TestActivitySession<ImeTestActivity> imeTestActivitySession = new
                     TestActivitySession<>();
             final TestActivitySession<ImeTestActivity2> imeTestActivitySession2 = new
                     TestActivitySession<>();
             // Leverage MockImeSession to ensure at least an IME exists as default.
             final MockImeSession mockImeSession1 = MockImeSession.create(
                     mContext, getInstrumentation().getUiAutomation(), new ImeSettings.Builder())) {

            // Create a virtual display and launch an activity on virtual & default display.
            final ActivityDisplay newDisplay = virtualDisplaySession.setShowSystemDecorations(true)
                    .setSimulateDisplay(true).createDisplay();
            imeTestActivitySession.launchTestActivityOnDisplaySync(ImeTestActivity.class,
                    DEFAULT_DISPLAY);
            imeTestActivitySession2.launchTestActivityOnDisplaySync(ImeTestActivity2.class,
                    newDisplay.mId);

            final Display defDisplay = mAmWmState.getWmState().getDisplay(DEFAULT_DISPLAY);
            final ImeEventStream stream = mockImeSession1.openEventStream();

            // Tap default display as top focused display & request focus on EditText to show
            // soft input.
            tapOnDisplayCenter(defDisplay.getDisplayId());
            imeTestActivitySession.runOnMainSyncAndWait(
                    imeTestActivitySession.getActivity()::showSoftInput);
            waitOrderedImeEventsThenAssertImeShown(stream, defDisplay.getDisplayId(),
                    editorMatcher("onStartInput",
                            imeTestActivitySession.getActivity().mEditText.getPrivateImeOptions()),
                    event -> "showSoftInput".equals(event.getEventName()));

            // Tap virtual display as top focused display & request focus on EditText to show
            // soft input.
            tapOnDisplayCenter(newDisplay.mId);
            imeTestActivitySession2.runOnMainSyncAndWait(
                    imeTestActivitySession2.getActivity()::showSoftInput);
            waitOrderedImeEventsThenAssertImeShown(stream, newDisplay.mId,
                    editorMatcher("onStartInput",
                            imeTestActivitySession2.getActivity().mEditText.getPrivateImeOptions()),
                    event -> "showSoftInput".equals(event.getEventName()));

            // Tap default display again to make sure the IME window will come back.
            tapOnDisplayCenter(defDisplay.getDisplayId());
            imeTestActivitySession.runOnMainSyncAndWait(
                    imeTestActivitySession.getActivity()::showSoftInput);
            waitOrderedImeEventsThenAssertImeShown(stream, defDisplay.getDisplayId(),
                    editorMatcher("onStartInput",
                            imeTestActivitySession.getActivity().mEditText.getPrivateImeOptions()),
                    event -> "showSoftInput".equals(event.getEventName()));
        }
    }

    /**
     * Test that the IME can be shown in a different display (actually the default display) than
     * the display on which the target IME application is shown.  Then test several basic operations
     * in {@link InputConnection}.
     */
    @Test
    public void testCrossDisplayBasicImeOperations() throws Exception {
        final long TIMEOUT = TimeUnit.SECONDS.toMillis(5);

        try (final VirtualDisplaySession virtualDisplaySession  = new VirtualDisplaySession();
             final TestActivitySession<ImeTestActivity>
                     imeTestActivitySession = new TestActivitySession<>();
             // Leverage MockImeSession to ensure at least a test Ime exists as default.
             final MockImeSession mockImeSession = MockImeSession.create(
                     mContext, getInstrumentation().getUiAutomation(), new ImeSettings.Builder())) {

            // Create a virtual display by app and assume the display should not show IME window.
            final ActivityDisplay newDisplay = virtualDisplaySession.setPublicDisplay(true)
                    .createDisplay();
            SystemUtil.runWithShellPermissionIdentity(
                    () -> assertFalse("Display should not support showing IME window",
                            mTargetContext.getSystemService(WindowManager.class)
                                    .shouldShowIme(newDisplay.mId)));

            // Launch Ime test activity in virtual display.
            imeTestActivitySession.launchTestActivityOnDisplaySync(ImeTestActivity.class,
                    newDisplay.mId);

            // Verify the activity to show soft input on the default display.
            final ImeEventStream stream = mockImeSession.openEventStream();
            final EditText editText = imeTestActivitySession.getActivity().mEditText;
            imeTestActivitySession.runOnMainSyncAndWait(
                    imeTestActivitySession.getActivity()::showSoftInput);
            waitOrderedImeEventsThenAssertImeShown(stream, DEFAULT_DISPLAY,
                    editorMatcher("onStartInput", editText.getPrivateImeOptions()),
                    event -> "showSoftInput".equals(event.getEventName()));

            // Commit text & make sure the input texts should be delivered to focused EditText on
            // virtual display.
            final String commitText = "test commit";
            expectCommand(stream, mockImeSession.callCommitText(commitText, 1), TIMEOUT);
            imeTestActivitySession.runOnMainAndAssertWithTimeout(
                    () -> TextUtils.equals(commitText, editText.getText()), TIMEOUT,
                    "The input text should be delivered");

            // Since the IME and the IME target app are running in different displays,
            // InputConnection#requestCursorUpdates() is not supported and it should return false.
            // See InputMethodServiceTest#testOnUpdateCursorAnchorInfo() for the normal scenario.
            final ImeCommand callCursorUpdates = mockImeSession.callRequestCursorUpdates(
                    InputConnection.CURSOR_UPDATE_IMMEDIATE);
            assertFalse(expectCommand(stream, callCursorUpdates, TIMEOUT).getReturnBooleanValue());
        }
    }

    public static class ImeTestActivity extends Activity {
        ImeAwareEditText mEditText;

        @Override
        protected void onCreate(Bundle icicle) {
            super.onCreate(icicle);
            mEditText = new ImeAwareEditText(this);
            // Set private IME option for editorMatcher to identify which TextView received
            // onStartInput event.
            mEditText.setPrivateImeOptions(
                    getClass().getName() + "/" + Long.toString(SystemClock.elapsedRealtimeNanos()));
            final LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.addView(mEditText);
            mEditText.requestFocus();
            setContentView(layout);
        }

        void showSoftInput() {
            mEditText.scheduleShowSoftInput();
        }
    }

    public static class ImeTestActivity2 extends ImeTestActivity { }

    public static final class ImeTestActivityWithBrokenContextWrapper extends Activity {
        private EditText mEditText;

        /**
         * Emulates the behavior of certain {@link ContextWrapper} subclasses we found in the wild.
         *
         * <p> Certain {@link ContextWrapper} subclass in the wild delegate method calls to
         * ApplicationContext except for {@link #getSystemService(String)}.</p>
         *
         **/
        private static final class Bug118341760ContextWrapper extends ContextWrapper {
            private final Context mOriginalContext;

            Bug118341760ContextWrapper(Context base) {
                super(base.getApplicationContext());
                mOriginalContext = base;
            }

            /**
             * Emulates the behavior of {@link ContextWrapper#getSystemService(String)} of certain
             * {@link ContextWrapper} subclasses we found in the wild.
             *
             * @param name The name of the desired service.
             * @return The service or {@link null} if the name does not exist.
             */
            @Override
            public Object getSystemService(String name) {
                return mOriginalContext.getSystemService(name);
            }
        }

        @Override
        protected void onCreate(Bundle icicle) {
            super.onCreate(icicle);
            mEditText = new EditText(new Bug118341760ContextWrapper(this));
            // Use SystemClock.elapsedRealtimeNanos()) as a unique ID of this edit text.
            mEditText.setPrivateImeOptions(Long.toString(SystemClock.elapsedRealtimeNanos()));
            final LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.addView(mEditText);
            mEditText.requestFocus();
            setContentView(layout);
        }

        EditText getEditText() {
            return mEditText;
        }
    }

    void assertImeWindowAndDisplayConfiguration(
            WindowManagerState.WindowState imeWinState, ActivityDisplay display) {
        final Configuration configurationForIme = imeWinState.mMergedOverrideConfiguration;
        final Configuration configurationForDisplay =  display.mMergedOverrideConfiguration;
        final int displayDensityDpiForIme = configurationForIme.densityDpi;
        final int displayDensityDpi = configurationForDisplay.densityDpi;
        final Rect displayBoundsForIme = configurationForIme.windowConfiguration.getBounds();
        final Rect displayBounds = configurationForDisplay.windowConfiguration.getBounds();

        assertEquals("Display density not the same", displayDensityDpi, displayDensityDpiForIme);
        assertEquals("Display bounds not the same", displayBounds, displayBoundsForIme);
    }

    void waitOrderedImeEventsThenAssertImeShown(ImeEventStream stream, int displayId,
            Predicate<ImeEvent>... conditions) throws Exception {
        for (Predicate<ImeEvent> condition : conditions) {
            expectEvent(stream, condition, TimeUnit.SECONDS.toMillis(5) /* eventTimeout */);
        }
        // Assert the IME is shown on the expected display.
        mAmWmState.waitAndAssertImeWindowShownOnDisplay(displayId);
    }
}
