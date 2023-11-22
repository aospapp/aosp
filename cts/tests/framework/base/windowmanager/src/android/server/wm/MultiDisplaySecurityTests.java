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

import static android.server.wm.ComponentNameUtils.getActivityName;
import static android.server.wm.StateLogger.logAlways;
import static android.server.wm.app.Components.DISPLAY_ACCESS_CHECK_EMBEDDING_ACTIVITY;
import static android.server.wm.app.Components.LAUNCHING_ACTIVITY;
import static android.server.wm.app.Components.LAUNCH_BROADCAST_RECEIVER;
import static android.server.wm.app.Components.LaunchBroadcastReceiver.ACTION_TEST_ACTIVITY_START;
import static android.server.wm.app.Components.LaunchBroadcastReceiver.EXTRA_COMPONENT_NAME;
import static android.server.wm.app.Components.LaunchBroadcastReceiver.EXTRA_TARGET_DISPLAY;
import static android.server.wm.app.Components.LaunchBroadcastReceiver.LAUNCH_BROADCAST_ACTION;
import static android.server.wm.app.Components.TEST_ACTIVITY;
import static android.server.wm.app.Components.VIRTUAL_DISPLAY_ACTIVITY;
import static android.server.wm.second.Components.EMBEDDING_ACTIVITY;
import static android.server.wm.second.Components.EmbeddingActivity.ACTION_EMBEDDING_TEST_ACTIVITY_START;
import static android.server.wm.second.Components.EmbeddingActivity.EXTRA_EMBEDDING_COMPONENT_NAME;
import static android.server.wm.second.Components.EmbeddingActivity.EXTRA_EMBEDDING_TARGET_DISPLAY;
import static android.server.wm.second.Components.SECOND_ACTIVITY;
import static android.server.wm.second.Components.SECOND_LAUNCH_BROADCAST_ACTION;
import static android.server.wm.second.Components.SECOND_LAUNCH_BROADCAST_RECEIVER;
import static android.server.wm.second.Components.SECOND_NO_EMBEDDING_ACTIVITY;
import static android.server.wm.second.Components.SecondActivity.EXTRA_DISPLAY_ACCESS_CHECK;
import static android.server.wm.third.Components.THIRD_ACTIVITY;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.server.wm.ActivityManagerState.ActivityDisplay;
import android.server.wm.ActivityManagerState.ActivityStack;
import android.server.wm.CommandSession.ActivitySession;
import android.util.SparseArray;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.test.filters.FlakyTest;

import com.android.compatibility.common.util.SystemUtil;
import com.android.compatibility.common.util.TestUtils;

import org.junit.Before;
import org.junit.Test;


/**
 * Build/Install/Run:
 *     atest CtsWindowManagerDeviceTestCases:MultiDisplaySecurityTests
 *
 * Tests if be allowed to launch an activity on multi-display environment.
 */
@Presubmit
public class MultiDisplaySecurityTests extends MultiDisplayTestBase {

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        assumeTrue(supportsMultiDisplay());
    }

    /**
     * Tests launching an activity on a virtual display without special permission must be allowed
     * for activities with same UID.
     */
    @Test
    public void testLaunchWithoutPermissionOnVirtualDisplayByOwner() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();

            // Try to launch an activity and check it security exception was triggered.
            getLaunchActivityBuilder()
                    .setUseBroadcastReceiver(LAUNCH_BROADCAST_RECEIVER, LAUNCH_BROADCAST_ACTION)
                    .setDisplayId(newDisplay.mId)
                    .setTargetActivity(TEST_ACTIVITY)
                    .execute();

            mAmWmState.waitForValidState(TEST_ACTIVITY);

            final int externalFocusedStackId = mAmWmState.getAmState().getFocusedStackId();
            final ActivityStack focusedStack =
                    mAmWmState.getAmState().getStackById(externalFocusedStackId);
            assertEquals("Focused stack must be on secondary display", newDisplay.mId,
                    focusedStack.mDisplayId);

            mAmWmState.assertFocusedActivity("Focus must be on newly launched app",
                    TEST_ACTIVITY);
            assertEquals("Activity launched by owner must be on external display",
                    externalFocusedStackId, mAmWmState.getAmState().getFocusedStackId());
        }
    }

    /**
     * Tests launching an activity on a virtual display without special permission must not be
     * allowed.
     */
    @Test
    public void testLaunchWithoutPermissionOnVirtualDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();

            separateTestJournal();

            // Try to launch an activity and check it security exception was triggered.
            getLaunchActivityBuilder()
                    .setUseBroadcastReceiver(SECOND_LAUNCH_BROADCAST_RECEIVER,
                            SECOND_LAUNCH_BROADCAST_ACTION)
                    .setDisplayId(newDisplay.mId)
                    .setTargetActivity(TEST_ACTIVITY)
                    .execute();

            assertSecurityExceptionFromActivityLauncher();

            mAmWmState.computeState(TEST_ACTIVITY);
            assertFalse("Restricted activity must not be launched",
                    mAmWmState.getAmState().containsActivity(TEST_ACTIVITY));
        }
    }

    /**
     * Tests launching an activity on virtual display and then launching another activity that
     * doesn't allow embedding - it should fail with security exception.
     */
    @Test
    public void testConsequentLaunchActivityFromVirtualDisplayNoEmbedding() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();

            // Launch activity on new secondary display.
            launchActivityOnDisplay(LAUNCHING_ACTIVITY, newDisplay.mId);

            waitAndAssertTopResumedActivity(LAUNCHING_ACTIVITY, newDisplay.mId,
                    "Activity launched on secondary display must be resumed");

            separateTestJournal();

            // Launch second activity from app on secondary display specifying same display id.
            getLaunchActivityBuilder()
                    .setTargetActivity(SECOND_NO_EMBEDDING_ACTIVITY)
                    .setDisplayId(newDisplay.mId)
                    .execute();

            assertSecurityExceptionFromActivityLauncher();
        }
    }

    /**
     * Tests
     * {@link android.app.ActivityManager#isActivityStartAllowedOnDisplay(Context, int, Intent)}
     * for simulated display. It is owned by system and is public, so should be accessible.
     */
    @Test
    public void testCanAccessSystemOwnedDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay = virtualDisplaySession.setSimulateDisplay(true)
                    .createDisplay();

            final ActivityManager activityManager =
                    (ActivityManager) mTargetContext.getSystemService(Context.ACTIVITY_SERVICE);
            final Intent intent = new Intent(Intent.ACTION_VIEW).setComponent(TEST_ACTIVITY);

            assertTrue(activityManager.isActivityStartAllowedOnDisplay(mTargetContext,
                    newDisplay.mId, intent));
        }
    }

    /**
     * Tests
     * {@link android.app.ActivityManager#isActivityStartAllowedOnDisplay(Context, int, Intent)}
     * for a public virtual display and an activity that doesn't support embedding from shell.
     */
    @Test
    public void testCanAccessPublicVirtualDisplayWithInternalPermission() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay = virtualDisplaySession.setPublicDisplay(true)
                    .createDisplay();

            final ActivityManager activityManager =
                    (ActivityManager) mTargetContext.getSystemService(Context.ACTIVITY_SERVICE);
            final Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setComponent(SECOND_NO_EMBEDDING_ACTIVITY);

            SystemUtil.runWithShellPermissionIdentity(() ->
                    assertTrue(activityManager.isActivityStartAllowedOnDisplay(mTargetContext,
                            newDisplay.mId, intent)), "android.permission.INTERNAL_SYSTEM_WINDOW");
        }
    }

    /**
     * Tests
     * {@link android.app.ActivityManager#isActivityStartAllowedOnDisplay(Context, int, Intent)}
     * for a private virtual display and an activity that doesn't support embedding from shell.
     */
    @Test
    public void testCanAccessPrivateVirtualDisplayWithInternalPermission() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay = virtualDisplaySession.setPublicDisplay(false)
                    .createDisplay();

            final ActivityManager activityManager =
                    (ActivityManager) mTargetContext.getSystemService(Context.ACTIVITY_SERVICE);
            final Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setComponent(SECOND_NO_EMBEDDING_ACTIVITY);

            SystemUtil.runWithShellPermissionIdentity(() ->
                    assertTrue(activityManager.isActivityStartAllowedOnDisplay(mTargetContext,
                            newDisplay.mId, intent)), "android.permission.INTERNAL_SYSTEM_WINDOW");
        }
    }

    /**
     * Tests
     * {@link android.app.ActivityManager#isActivityStartAllowedOnDisplay(Context, int, Intent)}
     * for a public virtual display, an activity that supports embedding but the launching entity
     * does not have required permission to embed an activity from other app.
     */
    @Test
    public void testCantAccessPublicVirtualDisplayNoEmbeddingPermission() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay = virtualDisplaySession.setPublicDisplay(true)
                    .createDisplay();

            final ActivityManager activityManager =
                    (ActivityManager) mTargetContext.getSystemService(Context.ACTIVITY_SERVICE);
            final Intent intent = new Intent(Intent.ACTION_VIEW).setComponent(SECOND_ACTIVITY);

            assertFalse(activityManager.isActivityStartAllowedOnDisplay(mTargetContext,
                    newDisplay.mId, intent));
        }
    }

    /**
     * Tests
     * {@link android.app.ActivityManager#isActivityStartAllowedOnDisplay(Context, int, Intent)}
     * for a public virtual display and an activity that does not support embedding.
     */
    @Test
    public void testCantAccessPublicVirtualDisplayActivityEmbeddingNotAllowed() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay = virtualDisplaySession.setPublicDisplay(true)
                    .createDisplay();

            final ActivityManager activityManager =
                    (ActivityManager) mTargetContext.getSystemService(Context.ACTIVITY_SERVICE);
            final Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setComponent(SECOND_NO_EMBEDDING_ACTIVITY);

            SystemUtil.runWithShellPermissionIdentity(() ->
                    assertFalse(activityManager.isActivityStartAllowedOnDisplay(mTargetContext,
                            newDisplay.mId, intent)), "android.permission.ACTIVITY_EMBEDDING");
        }
    }

    /**
     * Tests
     * {@link android.app.ActivityManager#isActivityStartAllowedOnDisplay(Context, int, Intent)}
     * for a public virtual display and an activity that supports embedding.
     */
    @Test
    public void testCanAccessPublicVirtualDisplayActivityEmbeddingAllowed() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay = virtualDisplaySession.setPublicDisplay(true)
                    .createDisplay();

            final ActivityManager activityManager =
                    (ActivityManager) mTargetContext.getSystemService(Context.ACTIVITY_SERVICE);
            final Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setComponent(SECOND_ACTIVITY);

            SystemUtil.runWithShellPermissionIdentity(() ->
                    assertTrue(activityManager.isActivityStartAllowedOnDisplay(mTargetContext,
                            newDisplay.mId, intent)), "android.permission.ACTIVITY_EMBEDDING");
        }
    }

    /**
     * Tests
     * {@link android.app.ActivityManager#isActivityStartAllowedOnDisplay(Context, int, Intent)}
     * for a private virtual display.
     */
    @Test
    public void testCantAccessPrivateVirtualDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay = virtualDisplaySession.setPublicDisplay(false)
                    .createDisplay();

            final ActivityManager activityManager =
                    (ActivityManager) mTargetContext.getSystemService(Context.ACTIVITY_SERVICE);
            final Intent intent = new Intent(Intent.ACTION_VIEW).setComponent(SECOND_ACTIVITY);

            assertFalse(activityManager.isActivityStartAllowedOnDisplay(mTargetContext,
                    newDisplay.mId, intent));
        }
    }

    /**
     * Tests
     * {@link android.app.ActivityManager#isActivityStartAllowedOnDisplay(Context, int, Intent)}
     * for a private virtual display to check the start of its own activity.
     */
    @Test
    public void testCanAccessPrivateVirtualDisplayByOwner() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay = virtualDisplaySession.setPublicDisplay(false)
                    .createDisplay();

            // Check the embedding call
            separateTestJournal();
            mContext.sendBroadcast(new Intent(ACTION_TEST_ACTIVITY_START)
                    .setPackage(LAUNCH_BROADCAST_RECEIVER.getPackageName())
                    .setFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(EXTRA_COMPONENT_NAME, TEST_ACTIVITY)
                    .putExtra(EXTRA_TARGET_DISPLAY, newDisplay.mId));

            assertActivityStartCheckResult(true);
        }
    }

    /**
     * Tests
     * {@link android.app.ActivityManager#isActivityStartAllowedOnDisplay(Context, int, Intent)}
     * for a private virtual display by UID present on that display and target activity that allows
     * embedding.
     */
    @Test
    public void testCanAccessPrivateVirtualDisplayByUidPresentOnDisplayActivityEmbeddingAllowed()
            throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay = virtualDisplaySession.setPublicDisplay(false)
                    .createDisplay();
            // Launch a test activity into the target display
            launchActivityOnDisplay(EMBEDDING_ACTIVITY, newDisplay.mId);

            // Check the embedding call
            separateTestJournal();
            mContext.sendBroadcast(new Intent(ACTION_EMBEDDING_TEST_ACTIVITY_START)
                    .setFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(EXTRA_EMBEDDING_COMPONENT_NAME, SECOND_ACTIVITY)
                    .putExtra(EXTRA_EMBEDDING_TARGET_DISPLAY, newDisplay.mId));

            assertActivityStartCheckResult(true);
        }
    }

    /**
     * Tests
     * {@link android.app.ActivityManager#isActivityStartAllowedOnDisplay(Context, int, Intent)}
     * for a private virtual display by UID present on that display and target activity that does
     * not allow embedding.
     */
    @Test
    public void testCanAccessPrivateVirtualDisplayByUidPresentOnDisplayActivityEmbeddingNotAllowed()
            throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay = virtualDisplaySession.setPublicDisplay(false)
                    .createDisplay();
            // Launch a test activity into the target display
            launchActivityOnDisplay(EMBEDDING_ACTIVITY, newDisplay.mId);

            // Check the embedding call
            separateTestJournal();
            mContext.sendBroadcast(new Intent(ACTION_EMBEDDING_TEST_ACTIVITY_START)
                    .setFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(EXTRA_EMBEDDING_COMPONENT_NAME, SECOND_NO_EMBEDDING_ACTIVITY)
                    .putExtra(EXTRA_EMBEDDING_TARGET_DISPLAY, newDisplay.mId));

            assertActivityStartCheckResult(false);
        }
    }

    private void assertActivityStartCheckResult(boolean expected) {
        final String component = ActivityLauncher.TAG;
        for (int retry = 1; retry <= 5; retry++) {
            final Bundle extras = TestJournalProvider.TestJournalContainer.get(component).extras;
            if (extras.containsKey(ActivityLauncher.KEY_IS_ACTIVITY_START_ALLOWED_ON_DISPLAY)) {
                assertEquals("Activity start check must match", expected, extras
                        .getBoolean(ActivityLauncher.KEY_IS_ACTIVITY_START_ALLOWED_ON_DISPLAY));
                return;
            }

            logAlways("***Waiting for activity start check for " + component
                    + " ... retry=" + retry);
            SystemClock.sleep(500);
        }
        fail("Expected activity start check from " + component + " not found");
    }

    @Test
    public void testDisplayHasAccess_UIDCanPresentOnPrivateDisplay() throws Exception {
        try (final VirtualDisplayLauncher virtualDisplayLauncher = new VirtualDisplayLauncher()) {
            // Create a virtual private display.
            final ActivityDisplay newDisplay = virtualDisplayLauncher
                    .setPublicDisplay(false)
                    .createDisplay();
            // Launch an embeddable activity into the private display.
            // Assert that the UID can present on display.
            final ActivitySession session1 = virtualDisplayLauncher.launchActivityOnDisplay(
                    DISPLAY_ACCESS_CHECK_EMBEDDING_ACTIVITY, newDisplay);
            assertEquals("Activity which the UID should accessible on private display",
                    isUidAccesibleOnDisplay(session1), true);

            // Launch another embeddable activity with a different UID, verify that it will be
            // able to access the display where it was put.
            // Note that set withShellPermission as true in launchActivityOnDisplay is to
            // make sure ACTIVITY_EMBEDDING can be granted by shell.
            final ActivitySession session2 = virtualDisplayLauncher.launchActivityOnDisplay(
                    SECOND_ACTIVITY, newDisplay,
                    bundle -> bundle.putBoolean(EXTRA_DISPLAY_ACCESS_CHECK, true),
                    true /* withShellPermission */, true /* waitForLaunch */);

            // Verify SECOND_ACTIVITY's UID has access to this virtual private display.
            assertEquals("Second activity which the UID should accessible on private display",
                    isUidAccesibleOnDisplay(session2), true);
        }
    }

    @Test
    public void testDisplayHasAccess_NoAccessWhenUIDNotPresentOnPrivateDisplay() throws Exception {
        try (final VirtualDisplayLauncher virtualDisplayLauncher = new VirtualDisplayLauncher()) {
            // Create a virtual private display.
            final ActivityDisplay newDisplay = virtualDisplayLauncher
                    .setPublicDisplay(false)
                    .createDisplay();
            // Launch an embeddable activity into the private display.
            // Assume that the UID can access on display.
            final ActivitySession session1 = virtualDisplayLauncher.launchActivityOnDisplay(
                    DISPLAY_ACCESS_CHECK_EMBEDDING_ACTIVITY, newDisplay);
            assertEquals("Activity which the UID should accessible on private display",
                    isUidAccesibleOnDisplay(session1), true);

            // Verify SECOND_NO_EMBEDDING_ACTIVITY's UID can't access this virtual private display
            // since there is no entity with this UID on this display.
            // Note that set withShellPermission as false in launchActivityOnDisplay is to
            // prevent activity can launch when INTERNAL_SYSTEM_WINDOW granted by shell case.
            separateTestJournal();
            final ActivitySession session2 = virtualDisplayLauncher.launchActivityOnDisplay(
                    SECOND_NO_EMBEDDING_ACTIVITY, newDisplay, null /* extrasConsumer */,
                    false /* withShellPermission */, false /* waitForLaunch */);
            assertEquals("Second activity which the UID should not accessible on private display",
                    isUidAccesibleOnDisplay(session2), false);
        }
    }

    @Test
    public void testDisplayHasAccess_ExceptionWhenAddViewWithoutPresentOnPrivateDisplay()
            throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create a virtual private display.
            final ActivityDisplay newDisplay = virtualDisplaySession
                    .setPublicDisplay(false)
                    .createDisplay();
            try {
                final Display display = mContext.getSystemService(DisplayManager.class).getDisplay(
                        newDisplay.mId);
                final Context newDisplayContext = mContext.createDisplayContext(display);
                newDisplayContext.getSystemService(WindowManager.class).addView(new View(mContext),
                        new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
            } catch (IllegalArgumentException e) {
                // Exception happened when createDisplayContext with invalid display.
                return;
            }
            fail("UID should not have access to private display without present entities.");
        }
    }

    private boolean isUidAccesibleOnDisplay(ActivitySession session) {
        boolean result = false;
        try {
            result = session.isUidAccesibleOnDisplay();
        } catch (RuntimeException e) {
            // Catch the exception while waiting reply (i.e. timeout)
        }
        return result;
    }

    /** Test that shell is allowed to launch on secondary displays. */
    @Test
    public void testPermissionLaunchFromShell() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();
            mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true /* visible */);
            mAmWmState.assertFocusedActivity("Virtual display activity must be on top",
                    VIRTUAL_DISPLAY_ACTIVITY);
            final int defaultDisplayFocusedStackId = mAmWmState.getAmState().getFocusedStackId();
            ActivityStack frontStack = mAmWmState.getAmState().getStackById(
                    defaultDisplayFocusedStackId);
            assertEquals("Top stack must remain on primary display",
                    DEFAULT_DISPLAY, frontStack.mDisplayId);

            // Launch activity on new secondary display.
            launchActivityOnDisplay(TEST_ACTIVITY, newDisplay.mId);

            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Front activity must be on secondary display");
            mAmWmState.assertResumedActivities("Both displays must have resumed activities",
                    new SparseArray<ComponentName>(){{
                        put(DEFAULT_DISPLAY, VIRTUAL_DISPLAY_ACTIVITY);
                        put(newDisplay.mId, TEST_ACTIVITY);
                    }}
            );

            // Launch other activity with different uid and check it is launched on dynamic stack on
            // secondary display.
            final String startCmd = "am start -n " + getActivityName(SECOND_ACTIVITY)
                    + " --display " + newDisplay.mId;
            executeShellCommand(startCmd);

            waitAndAssertTopResumedActivity(SECOND_ACTIVITY, newDisplay.mId,
                    "Focus must be on newly launched app");
            mAmWmState.assertResumedActivities("Both displays must have resumed activities",
                    new SparseArray<ComponentName>(){{
                        put(DEFAULT_DISPLAY, VIRTUAL_DISPLAY_ACTIVITY);
                        put(newDisplay.mId, SECOND_ACTIVITY);
                    }}
            );
        }
    }

    /** Test that launching from app that is on external display is allowed. */
    @Test
    public void testPermissionLaunchFromAppOnSecondary() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new simulated display.
            final ActivityDisplay newDisplay = virtualDisplaySession.setSimulateDisplay(true)
                    .createDisplay();

            // Launch activity with different uid on secondary display.
            final String startCmd = "am start -n " + getActivityName(SECOND_ACTIVITY)
                    + " --display " + newDisplay.mId;
            executeShellCommand(startCmd);

            waitAndAssertTopResumedActivity(SECOND_ACTIVITY, newDisplay.mId,
                    "Top activity must be the newly launched one");

            // Launch another activity with third different uid from app on secondary display and
            // check it is launched on secondary display.
            getLaunchActivityBuilder()
                    .setUseBroadcastReceiver(SECOND_LAUNCH_BROADCAST_RECEIVER,
                            SECOND_LAUNCH_BROADCAST_ACTION)
                    .setDisplayId(newDisplay.mId)
                    .setTargetActivity(THIRD_ACTIVITY)
                    .execute();

            waitAndAssertTopResumedActivity(THIRD_ACTIVITY, newDisplay.mId,
                    "Top activity must be the newly launched one");
        }
    }

    /** Tests that an activity can launch an activity from a different UID into its own task. */
    @Test
    public void testPermissionLaunchMultiUidTask() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay newDisplay = virtualDisplaySession.setSimulateDisplay(true)
                    .createDisplay();

            launchActivityOnDisplay(LAUNCHING_ACTIVITY, newDisplay.mId);
            mAmWmState.computeState(LAUNCHING_ACTIVITY);

            // Check that the first activity is launched onto the secondary display
            final int frontStackId = mAmWmState.getAmState().getFrontStackId(newDisplay.mId);
            ActivityStack frontStack = mAmWmState.getAmState().getStackById(
                    frontStackId);
            assertEquals("Activity launched on secondary display must be resumed",
                    getActivityName(LAUNCHING_ACTIVITY),
                    frontStack.mResumedActivity);
            mAmWmState.assertFocusedStack("Top stack must be on secondary display",
                    frontStackId);

            // Launch an activity from a different UID into the first activity's task
            getLaunchActivityBuilder().setTargetActivity(SECOND_ACTIVITY).execute();

            waitAndAssertTopResumedActivity(SECOND_ACTIVITY, newDisplay.mId,
                    "Top activity must be the newly launched one");
            frontStack = mAmWmState.getAmState().getStackById(frontStackId);
            assertEquals("Secondary display must contain 1 task", 1, frontStack.getTasks().size());
        }
    }

    /**
     * Test that launching from display owner is allowed even when the the display owner
     * doesn't have anything on the display.
     */
    @Test
    public void testPermissionLaunchFromOwner() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();
            mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true /* visible */);
            mAmWmState.assertFocusedActivity("Virtual display activity must be focused",
                    VIRTUAL_DISPLAY_ACTIVITY);
            final int defaultDisplayFocusedStackId = mAmWmState.getAmState().getFocusedStackId();
            ActivityStack frontStack =
                    mAmWmState.getAmState().getStackById(defaultDisplayFocusedStackId);
            assertEquals("Top stack must remain on primary display",
                    DEFAULT_DISPLAY, frontStack.mDisplayId);

            // Launch other activity with different uid on secondary display.
            final String startCmd = "am start -n " + getActivityName(SECOND_ACTIVITY)
                    + " --display " + newDisplay.mId;
            executeShellCommand(startCmd);

            waitAndAssertTopResumedActivity(SECOND_ACTIVITY, newDisplay.mId,
                    "Top activity must be the newly launched one");

            // Check that owner uid can launch its own activity on secondary display.
            getLaunchActivityBuilder()
                    .setUseBroadcastReceiver(LAUNCH_BROADCAST_RECEIVER, LAUNCH_BROADCAST_ACTION)
                    .setNewTask(true)
                    .setMultipleTask(true)
                    .setDisplayId(newDisplay.mId)
                    .execute();

            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Top activity must be the newly launched one");
        }
    }

    /**
     * Test that launching from app that is not present on external display and doesn't own it to
     * that external display is not allowed.
     */
    @Test
    public void testPermissionLaunchFromDifferentApp() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Create new virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.createDisplay();
            mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true /* visible */);
            mAmWmState.assertFocusedActivity("Virtual display activity must be focused",
                    VIRTUAL_DISPLAY_ACTIVITY);
            final int defaultDisplayFocusedStackId = mAmWmState.getAmState().getFocusedStackId();
            ActivityStack frontStack = mAmWmState.getAmState().getStackById(
                    defaultDisplayFocusedStackId);
            assertEquals("Top stack must remain on primary display",
                    DEFAULT_DISPLAY, frontStack.mDisplayId);

            // Launch activity on new secondary display.
            launchActivityOnDisplay(TEST_ACTIVITY, newDisplay.mId);
            waitAndAssertTopResumedActivity(TEST_ACTIVITY, newDisplay.mId,
                    "Top activity must be the newly launched one");

            separateTestJournal();

            // Launch other activity with different uid and check security exception is triggered.
            getLaunchActivityBuilder()
                    .setUseBroadcastReceiver(SECOND_LAUNCH_BROADCAST_RECEIVER,
                            SECOND_LAUNCH_BROADCAST_ACTION)
                    .setDisplayId(newDisplay.mId)
                    .setTargetActivity(THIRD_ACTIVITY)
                    .execute();

            assertSecurityExceptionFromActivityLauncher();

            mAmWmState.waitForValidState(TEST_ACTIVITY);
            mAmWmState.assertFocusedActivity("Top activity must be the first one launched",
                    TEST_ACTIVITY);
        }
    }

    private void assertSecurityExceptionFromActivityLauncher() {
        final String component = ActivityLauncher.TAG;
        for (int retry = 1; retry <= 5; retry++) {
            if (ActivityLauncher.hasCaughtSecurityException()) {
                return;
            }

            logAlways("***Waiting for SecurityException from " + component + " ... retry=" + retry);
            SystemClock.sleep(500);
        }
        fail("Expected exception from " + component + " not found");
    }

    /**
     * Test that only private virtual display can show content with insecure keyguard.
     */
    @Test
    public void testFlagShowWithInsecureKeyguardOnPublicVirtualDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // Try to create new show-with-insecure-keyguard public virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession
                    .setPublicDisplay(true)
                    .setCanShowWithInsecureKeyguard(true)
                    .setMustBeCreated(false)
                    .createDisplay();

            // Check that the display is not created.
            assertNull(newDisplay);
        }
    }

    /**
     * Test setting system decoration flag and show IME flag without sufficient permissions.
     */
    @Test
    @FlakyTest(bugId = 130284250)
    public void testSettingFlagWithoutInternalSystemPermission() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // The reason to use a trusted display is that we can guarantee the security exception
            // is coming from lacking internal system permission.
            final ActivityDisplay trustedDisplay = virtualDisplaySession
                    .setSimulateDisplay(true).createDisplay();
            final WindowManager wm = mTargetContext.getSystemService(WindowManager.class);

            // Verify setting system decorations flag without internal system permission.
            try {
                wm.setShouldShowSystemDecors(trustedDisplay.mId, true);

                // Unexpected result, restore flag to avoid affecting other tests.
                wm.setShouldShowSystemDecors(trustedDisplay.mId, false);
                TestUtils.waitUntil("Waiting for system decoration flag to be set",
                        5 /* timeoutSecond */,
                        () -> !wm.shouldShowSystemDecors(trustedDisplay.mId));
                fail("Should not allow setting system decoration flag without internal system "
                        + "permission");
            } catch (SecurityException e) {
                // Expected security exception.
            }

            // Verify setting show IME flag without internal system permission.
            try {
                wm.setShouldShowIme(trustedDisplay.mId, true);

                // Unexpected result, restore flag to avoid affecting other tests.
                wm.setShouldShowIme(trustedDisplay.mId, false);
                TestUtils.waitUntil("Waiting for show IME flag to be set",
                        5 /* timeoutSecond */,
                        () -> !wm.shouldShowIme(trustedDisplay.mId));
                fail("Should not allow setting show IME flag without internal system permission");
            } catch (SecurityException e) {
                // Expected security exception.
            }
        }
    }

    /**
     * Test getting system decoration flag and show IME flag without sufficient permissions.
     */
    @Test
    @FlakyTest(bugId = 130284250)
    public void testGettingFlagWithoutInternalSystemPermission() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            // The reason to use a trusted display is that we can guarantee the security exception
            // is coming from lacking internal system permission.
            final ActivityDisplay trustedDisplay = virtualDisplaySession
                    .setSimulateDisplay(true).createDisplay();
            final WindowManager wm = mTargetContext.getSystemService(WindowManager.class);

            // Verify getting system decorations flag without internal system permission.
            try {
                wm.shouldShowSystemDecors(trustedDisplay.mId);
                fail("Only allow internal system to get system decoration flag");
            } catch (SecurityException e) {
                // Expected security exception.
            }

            // Verify getting show IME flag without internal system permission.
            try {
                wm.shouldShowIme(trustedDisplay.mId);
                fail("Only allow internal system to get show IME flag");
            } catch (SecurityException e) {
                // Expected security exception.
            }
        }
    }

    /**
     * Test setting system decoration flag and show IME flag to the untrusted display.
     */
    @Test
    @FlakyTest(bugId = 130284250)
    public void testSettingFlagToUntrustedDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay untrustedDisplay = virtualDisplaySession.createDisplay();
            final WindowManager wm = mTargetContext.getSystemService(WindowManager.class);

            // Verify setting system decoration flag to an untrusted display.
            getInstrumentation().getUiAutomation().adoptShellPermissionIdentity();
            try {
                wm.setShouldShowSystemDecors(untrustedDisplay.mId, true);

                // Unexpected result, restore flag to avoid affecting other tests.
                wm.setShouldShowSystemDecors(untrustedDisplay.mId, false);
                TestUtils.waitUntil("Waiting for system decoration flag to be set",
                        5 /* timeoutSecond */,
                        () -> !wm.shouldShowSystemDecors(untrustedDisplay.mId));
                fail("Should not allow setting system decoration flag to the untrusted virtual "
                        + "display");
            } catch (SecurityException e) {
                // Expected security exception.
            } finally {
                getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
            }

            // Verify setting show IME flag to an untrusted display.
            getInstrumentation().getUiAutomation().adoptShellPermissionIdentity();
            try {
                wm.setShouldShowIme(untrustedDisplay.mId, true);

                // Unexpected result, restore flag to avoid affecting other tests.
                wm.setShouldShowIme(untrustedDisplay.mId, false);
                TestUtils.waitUntil("Waiting for show IME flag to be set",
                        5 /* timeoutSecond */,
                        () -> !wm.shouldShowIme(untrustedDisplay.mId));
                fail("Should not allow setting show IME flag to the untrusted virtual display");
            } catch (SecurityException e) {
                // Expected security exception.
            } finally {
                getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
            }
        }
    }

    /**
     * Test getting system decoration flag and show IME flag from the untrusted display.
     */
    @Test
    @FlakyTest(bugId = 130284250)
    public void testGettingFlagFromUntrustedDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay untrustedDisplay = virtualDisplaySession.createDisplay();
            final WindowManager wm = mTargetContext.getSystemService(WindowManager.class);

            // Verify getting system decoration flag from an untrusted display.
            SystemUtil.runWithShellPermissionIdentity(() -> assertFalse(
                    "Display should not support showing system decorations",
                    wm.shouldShowSystemDecors(untrustedDisplay.mId)));

            // Verify getting show IME flag from an untrusted display.
            SystemUtil.runWithShellPermissionIdentity(() -> assertFalse(
                    "Display should not support showing IME window",
                    wm.shouldShowIme(untrustedDisplay.mId)));
        }
    }

    /**
     * Test setting system decoration flag and show IME flag to the trusted display.
     */
    @Test
    @FlakyTest(bugId = 130284250)
    public void testSettingFlagToTrustedDisplay() throws Exception {
        try (final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            final ActivityDisplay trustedDisplay = virtualDisplaySession
                    .setSimulateDisplay(true).createDisplay();
            final WindowManager wm = mTargetContext.getSystemService(WindowManager.class);

            // Verify setting system decoration flag to a trusted display.
            SystemUtil.runWithShellPermissionIdentity(() -> {
                // Assume the display should not support system decorations by default.
                assertFalse(wm.shouldShowSystemDecors(trustedDisplay.mId));

                try {
                    wm.setShouldShowSystemDecors(trustedDisplay.mId, true);
                    TestUtils.waitUntil("Waiting for system decoration flag to be set",
                            5 /* timeoutSecond */,
                            () -> wm.shouldShowSystemDecors(trustedDisplay.mId));

                    assertTrue(wm.shouldShowSystemDecors(trustedDisplay.mId));
                } finally {
                    // Restore flag to avoid affecting other tests.
                    wm.setShouldShowSystemDecors(trustedDisplay.mId, false);
                    TestUtils.waitUntil("Waiting for system decoration flag to be set",
                            5 /* timeoutSecond */,
                            () -> !wm.shouldShowSystemDecors(trustedDisplay.mId));
                }
            });

            // Verify setting show IME flag to a trusted display.
            SystemUtil.runWithShellPermissionIdentity(() -> {
                // Assume the display should not show IME window by default.
                assertFalse(wm.shouldShowIme(trustedDisplay.mId));

                try {
                    wm.setShouldShowIme(trustedDisplay.mId, true);
                    TestUtils.waitUntil("Waiting for show IME flag to be set",
                            5 /* timeoutSecond */,
                            () -> wm.shouldShowIme(trustedDisplay.mId));

                    assertTrue(wm.shouldShowIme(trustedDisplay.mId));
                } finally {
                    // Restore flag to avoid affecting other tests.
                    wm.setShouldShowIme(trustedDisplay.mId, false);
                    TestUtils.waitUntil("Waiting for show IME flag to be set",
                            5 /* timeoutSecond */,
                            () -> !wm.shouldShowIme(trustedDisplay.mId));
                }
            });
        }
    }
}
