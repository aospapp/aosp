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

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.window.extensions.layout.FoldingFeature;
import androidx.window.sidecar.SidecarDeviceState;

import org.junit.Before;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Base class for all tests in the module. */
public class WindowManagerJetpackTestBase {

    public static final String ACTIVITY_ID_LABEL = "ActivityID";

    public Instrumentation mInstrumentation;
    public Context mContext;
    public Application mApplication;

    private final static Set<Activity> sResumedActivities = new HashSet<>();

    @Before
    public void setUp() {
        mInstrumentation = getInstrumentation();
        assertNotNull(mInstrumentation);
        mContext = getApplicationContext();
        assertNotNull(mContext);
        mApplication = (Application) mContext.getApplicationContext();
        assertNotNull(mApplication);
        // Register activity lifecycle callbacks to know which activities are resumed
        registerActivityLifecycleCallbacks();
    }

    public Activity startActivityNewTask(Class activityClass) {
        final Intent intent = new Intent(mContext, activityClass);
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
        final Activity activity = mInstrumentation.startActivitySync(intent);
        return activity;
    }

    /**
     * Starts an instance of {@param activityToLaunchClass} from {@param activityToLaunchFrom}
     * and returns the activity ID from the newly launched class.
     */
    public static <T extends Activity> void startActivityFromActivity(Activity activityToLaunchFrom,
            Class<T> activityToLaunchClass, String newActivityId) {
        Intent intent = new Intent(activityToLaunchFrom, activityToLaunchClass);
        intent.putExtra(ACTIVITY_ID_LABEL, newActivityId);
        activityToLaunchFrom.startActivity(intent);
    }

    public static IBinder getActivityWindowToken(Activity activity) {
        return activity.getWindow().getAttributes().token;
    }

    public static void assertHasNonNegativeDimensions(@NonNull Rect rect) {
        assertFalse(rect.width() < 0 || rect.height() < 0);
    }

    public static void assertNotBothDimensionsZero(@NonNull Rect rect) {
        assertFalse(rect.width() == 0 && rect.height() == 0);
    }

    public static Rect getActivityBounds(Activity activity) {
        return activity.getWindowManager().getCurrentWindowMetrics().getBounds();
    }

    public static Rect getMaximumActivityBounds(Activity activity) {
        return activity.getWindowManager().getMaximumWindowMetrics().getBounds();
    }

    public static void setActivityOrientationActivityHandlesOrientationChanges(
            TestActivity activity, int orientation) {
        // Make sure that the provided orientation is a fixed orientation
        assertTrue(orientation == ORIENTATION_PORTRAIT || orientation == ORIENTATION_LANDSCAPE);
        // Do nothing if the orientation already matches
        if (activity.getResources().getConfiguration().orientation == orientation) {
            return;
        }
        activity.resetLayoutCounter();
        // Change the orientation
        activity.setRequestedOrientation(orientation == ORIENTATION_PORTRAIT
                ? SCREEN_ORIENTATION_PORTRAIT : SCREEN_ORIENTATION_LANDSCAPE);
        // Wait for the activity to layout, which will happen after the orientation change
        assertTrue(activity.waitForLayout());
        // Check that orientation matches
        assertEquals(orientation, activity.getResources().getConfiguration().orientation);
    }

    public static void setActivityOrientationActivityDoesNotHandleOrientationChanges(
            TestActivity activity, int orientation) {
        // Make sure that the provided orientation is a fixed orientation
        assertTrue(orientation == ORIENTATION_PORTRAIT || orientation == ORIENTATION_LANDSCAPE);
        // Do nothing if the orientation already matches
        if (activity.getResources().getConfiguration().orientation == orientation) {
            return;
        }
        TestActivity.resetResumeCounter();
        // Change the orientation
        activity.setRequestedOrientation(orientation == ORIENTATION_PORTRAIT
                ? SCREEN_ORIENTATION_PORTRAIT : SCREEN_ORIENTATION_LANDSCAPE);
        // The activity will relaunch because it does not handle the orientation change, so wait
        // for the activity to be resumed again
        assertTrue(activity.waitForOnResume());
        // Check that orientation matches
        assertEquals(orientation, activity.getResources().getConfiguration().orientation);
    }

    /**
     * Returns whether the display rotates to respect activity orientation, which will be false if
     * both portrait activities and landscape activities have the same maximum bounds. If the
     * display rotates for orientation, then the maximum portrait bounds will be a rotated version
     * of the maximum landscape bounds.
     */
    public static boolean doesDisplayRotateForOrientation(@NonNull Rect portraitMaximumBounds,
            @NonNull Rect landscapeMaximumBounds) {
        return !portraitMaximumBounds.equals(landscapeMaximumBounds);
    }

    public static boolean areExtensionAndSidecarDeviceStateEqual(int extensionDeviceState,
            int sidecarDeviceStatePosture) {
        return (extensionDeviceState == FoldingFeature.STATE_FLAT
                && sidecarDeviceStatePosture == SidecarDeviceState.POSTURE_OPENED)
                || (extensionDeviceState == FoldingFeature.STATE_HALF_OPENED
                && sidecarDeviceStatePosture == SidecarDeviceState.POSTURE_HALF_OPENED);
    }

    private void registerActivityLifecycleCallbacks() {
        mApplication.registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityCreated(@NonNull Activity activity,
                            @Nullable Bundle savedInstanceState) {
                    }

                    @Override
                    public void onActivityStarted(@NonNull Activity activity) {
                    }

                    @Override
                    public void onActivityResumed(@NonNull Activity activity) {
                        synchronized (sResumedActivities) {
                            sResumedActivities.add(activity);
                        }
                    }

                    @Override
                    public void onActivityPaused(@NonNull Activity activity) {
                        synchronized (sResumedActivities) {
                            sResumedActivities.remove(activity);
                        }
                    }

                    @Override
                    public void onActivityStopped(@NonNull Activity activity) {
                    }

                    @Override
                    public void onActivitySaveInstanceState(@NonNull Activity activity,
                            @NonNull Bundle outState) {
                    }

                    @Override
                    public void onActivityDestroyed(@NonNull Activity activity) {
                    }
        });
    }

    public static boolean isActivityResumed(Activity activity) {
        synchronized (sResumedActivities) {
            return sResumedActivities.contains(activity);
        }
    }

    @Nullable
    public static TestActivityWithId getResumedActivityById(@NonNull String activityId) {
        synchronized (sResumedActivities) {
            for (Activity activity : sResumedActivities) {
                if (activity instanceof TestActivityWithId
                        && activityId.equals(((TestActivityWithId) activity).getId())) {
                    return (TestActivityWithId) activity;
                }
            }
            return null;
        }
    }
}
