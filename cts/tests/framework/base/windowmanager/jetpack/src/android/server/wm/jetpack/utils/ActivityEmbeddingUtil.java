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

package android.server.wm.jetpack.utils;

import static android.server.wm.jetpack.utils.ExtensionUtil.assumeExtensionSupportedDevice;
import static android.server.wm.jetpack.utils.ExtensionUtil.getWindowExtensions;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.getActivityBounds;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.getMaximumActivityBounds;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.getResumedActivityById;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.startActivityFromActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Looper;
import android.util.LayoutDirection;
import android.util.Log;
import android.util.Pair;
import android.view.WindowMetrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.window.extensions.WindowExtensions;
import androidx.window.extensions.embedding.ActivityEmbeddingComponent;
import androidx.window.extensions.embedding.EmbeddingRule;
import androidx.window.extensions.embedding.SplitInfo;
import androidx.window.extensions.embedding.SplitPairRule;
import androidx.window.extensions.embedding.SplitRule;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Utility class for activity embedding tests.
 */
public class ActivityEmbeddingUtil {

    public static final String TAG = "ActivityEmbeddingTests";
    public static final long WAIT_FOR_RESUMED_TIMEOUT_MS = 3000;
    public static final float DEFAULT_SPLIT_RATIO = 0.5f;

    @NonNull
    public static SplitPairRule createWildcardSplitPairRule(boolean shouldClearTop) {
        // Any activity be split with any activity
        final Predicate<Pair<Activity, Activity>> activityPairPredicate =
                activityActivityPair -> true;
        // Any activity can launch any split intent
        final Predicate<Pair<Activity, Intent>> activityIntentPredicate =
                activityIntentPair -> true;
        // Allow any parent bounds to show the split containers side by side
        Predicate<WindowMetrics> parentWindowMetricsPredicate = windowMetrics -> true;
        // Build the split pair rule
        return new SplitPairRule.Builder(activityPairPredicate,
                activityIntentPredicate, parentWindowMetricsPredicate).setSplitRatio(
                DEFAULT_SPLIT_RATIO).setShouldClearTop(shouldClearTop).build();
    }

    @NonNull
    public static SplitPairRule createWildcardSplitPairRule() {
        return createWildcardSplitPairRule(false /* shouldClearTop */);
    }

    public static Activity startActivityAndVerifySplit(@NonNull Activity activityLaunchingFrom,
            @NonNull Activity expectedPrimaryActivity, @NonNull Class secondActivityClass,
            @NonNull SplitPairRule splitPairRule, @NonNull String secondActivityId,
            int expectedCallbackCount,
            @NonNull TestValueCountConsumer<List<SplitInfo>> splitInfoConsumer) {
        // Set the expected callback count
        splitInfoConsumer.setCount(expectedCallbackCount);

        // Start second activity
        startActivityFromActivity(activityLaunchingFrom, secondActivityClass, secondActivityId);

        // Get updated split info
        List<SplitInfo> activeSplitStates = null;
        try {
            activeSplitStates = splitInfoConsumer.waitAndGet();
        } catch (InterruptedException e) {
            fail("startActivityAndVerifySplit() InterruptedException");
        }

        // Get second activity from split info
        Activity secondActivity = getSecondActivity(activeSplitStates, expectedPrimaryActivity,
                secondActivityId);
        assertNotNull(secondActivity);

        assertValidSplit(expectedPrimaryActivity, secondActivity, splitPairRule);

        // Return second activity for easy access in calling method
        return secondActivity;
    }

    public static Activity startActivityAndVerifySplit(@NonNull Activity primaryActivity,
            @NonNull Class secondActivityClass, @NonNull SplitPairRule splitPairRule,
            @NonNull String secondActivityId, int expectedCallbackCount,
            @NonNull TestValueCountConsumer<List<SplitInfo>> splitInfoConsumer) {
        return startActivityAndVerifySplit(primaryActivity /* activityLaunchingFrom */,
                primaryActivity, secondActivityClass, splitPairRule, secondActivityId,
                expectedCallbackCount, splitInfoConsumer);
    }

    public static Activity startActivityAndVerifySplit(@NonNull Activity primaryActivity,
            @NonNull Class secondActivityClass, @NonNull SplitPairRule splitPairRule,
            @NonNull String secondActivityId,
            @NonNull TestValueCountConsumer<List<SplitInfo>> splitInfoConsumer) {
        return startActivityAndVerifySplit(primaryActivity, secondActivityClass, splitPairRule,
                secondActivityId, 1 /* expectedCallbackCount */, splitInfoConsumer);
    }

    @Nullable
    public static Activity getSecondActivity(@Nullable List<SplitInfo> activeSplitStates,
            @NonNull Activity primaryActivity, @NonNull String secondaryClassId) {
        if (activeSplitStates == null) {
            Log.d(TAG, "Null split states");
            return null;
        }
        Log.d(TAG, "Active split states: " + activeSplitStates);
        for (SplitInfo splitInfo : activeSplitStates) {
            // Find the split info whose top activity in the primary container is the primary
            // activity we are looking for
            Activity primaryContainerTopActivity = getPrimaryStackTopActivity(splitInfo);
            if (primaryActivity.equals(primaryContainerTopActivity)) {
                Activity secondActivity = getSecondaryStackTopActivity(splitInfo);
                // See if this activity is the secondary activity we expect
                if (secondActivity != null && secondActivity instanceof TestActivityWithId
                        && secondaryClassId.equals(((TestActivityWithId) secondActivity).getId())) {
                    return secondActivity;
                }
            }
        }
        Log.d(TAG, "Second activity was not found: " + secondaryClassId);
        return null;
    }

    public static void assertValidSplit(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity, SplitRule splitRule) {
        waitForResumed(Arrays.asList(primaryActivity, secondaryActivity));

        // Compute the layout direction
        int layoutDir = splitRule.getLayoutDirection();
        if (layoutDir == LayoutDirection.LOCALE) {
            layoutDir = primaryActivity.getResources().getConfiguration().getLayoutDirection();
        }

        final float splitRatio = splitRule.getSplitRatio();
        final Rect parentBounds = getMaximumActivityBounds(primaryActivity);
        final Rect expectedPrimaryActivityBounds = new Rect();
        final Rect expectedSecondaryActivityBounds = new Rect();
        getExpectedPrimaryAndSecondaryBounds(layoutDir, splitRatio, parentBounds,
                expectedPrimaryActivityBounds, expectedSecondaryActivityBounds);
        assertEquals(expectedPrimaryActivityBounds, getActivityBounds(primaryActivity));
        assertEquals(expectedSecondaryActivityBounds, getActivityBounds(secondaryActivity));
    }

    public static void verifyFillsTask(Activity activity) {
        waitForResumed(Arrays.asList(activity));
        assertEquals(getMaximumActivityBounds(activity), getActivityBounds(activity));
    }

    public static boolean waitForResumed(
            @NonNull List<Activity> activityList) {
        final long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < WAIT_FOR_RESUMED_TIMEOUT_MS) {
            boolean allActivitiesResumed = true;
            for (Activity activity : activityList) {
                allActivitiesResumed &= WindowManagerJetpackTestBase.isActivityResumed(activity);
                if (!allActivitiesResumed) {
                    break;
                }
            }
            if (allActivitiesResumed) {
                return true;
            }
        }
        return false;
    }

    public static boolean waitForResumed(@NonNull String activityId) {
        final long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < WAIT_FOR_RESUMED_TIMEOUT_MS) {
            if (getResumedActivityById(activityId) != null) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static Activity getPrimaryStackTopActivity(SplitInfo splitInfo) {
        List<Activity> primaryActivityStack = splitInfo.getPrimaryActivityStack().getActivities();
        if (primaryActivityStack.isEmpty()) {
            return null;
        }
        return primaryActivityStack.get(primaryActivityStack.size() - 1);
    }

    @Nullable
    public static Activity getSecondaryStackTopActivity(SplitInfo splitInfo) {
        List<Activity> secondaryActivityStack = splitInfo.getSecondaryActivityStack()
                .getActivities();
        if (secondaryActivityStack.isEmpty()) {
            return null;
        }
        return secondaryActivityStack.get(secondaryActivityStack.size() - 1);
    }

    public static void getExpectedPrimaryAndSecondaryBounds(int layoutDir, float splitRatio,
            @NonNull Rect inParentBounds, @NonNull Rect outPrimaryActivityBounds,
            @NonNull Rect outSecondaryActivityBounds) {
        final int expectedPrimaryWidth = (int) (inParentBounds.width() * splitRatio);
        final int expectedSecondaryWidth = (int) (inParentBounds.width() * (1 - splitRatio));

        outPrimaryActivityBounds.set(inParentBounds);
        outSecondaryActivityBounds.set(inParentBounds);
        if (layoutDir == LayoutDirection.LTR) {
            /*******************|*********************
             * primary activity | secondary activity *
             *******************|*********************/
            outPrimaryActivityBounds.right = inParentBounds.left + expectedPrimaryWidth;
            outSecondaryActivityBounds.left = inParentBounds.right - expectedSecondaryWidth;
        } else {
            /*********************|*******************
             * secondary activity | primary activity *
             *********************|*******************/
            outPrimaryActivityBounds.left = inParentBounds.right - expectedPrimaryWidth;
            outSecondaryActivityBounds.right = inParentBounds.left + expectedSecondaryWidth;
        }
    }
}
