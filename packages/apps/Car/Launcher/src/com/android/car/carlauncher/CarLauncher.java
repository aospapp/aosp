/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.carlauncher;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.TaskStackListener;
import android.car.Car;
import android.car.app.CarActivityManager;
import android.car.user.CarUserManager;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.car.user.UserLifecycleEventFilter;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.collection.ArraySet;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.carlauncher.homescreen.HomeCardModule;
import com.android.car.carlauncher.taskstack.TaskStackChangeListeners;
import com.android.car.internal.common.UserHelperLite;
import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.TaskView;
import com.android.wm.shell.common.HandlerExecutor;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Basic Launcher for Android Automotive which demonstrates the use of {@link TaskView} to host
 * maps content and uses a Model-View-Presenter structure to display content in cards.
 *
 * <p>Implementations of the Launcher that use the given layout of the main activity
 * (car_launcher.xml) can customize the home screen cards by providing their own
 * {@link HomeCardModule} for R.id.top_card or R.id.bottom_card. Otherwise, implementations that
 * use their own layout should define their own activity rather than using this one.
 *
 * <p>Note: On some devices, the TaskView may render with a width, height, and/or aspect
 * ratio that does not meet Android compatibility definitions. Developers should work with content
 * owners to ensure content renders correctly when extending or emulating this class.
 */
public class CarLauncher extends FragmentActivity {
    public static final String TAG = "CarLauncher";
    private static final boolean DEBUG = false;
    private static final String SCHEME_PACKAGE = "package";

    private final AtomicReference<CarActivityManager> mCarActivityManagerRef =
            new AtomicReference<>();

    private ActivityManager mActivityManager;
    private UserManager mUserManager;
    private CarUserManager mCarUserManager;
    private TaskViewManager mTaskViewManager;

    private TaskView mTaskView;
    private boolean mTaskViewReady;
    // Tracking this to check if the task in TaskView has crashed in the background.
    private int mTaskViewTaskId = INVALID_TASK_ID;
    private int mCarLauncherTaskId = INVALID_TASK_ID;
    private Set<HomeCardModule> mHomeCardModules;

    /** Set to {@code true} once we've logged that the Activity is fully drawn. */
    private boolean mIsReadyLogged;

    private boolean mUseSmallCanvasOptimizedMap;

    // The callback methods in {@code mTaskViewListener} are running under MainThread.
    private final TaskView.Listener mTaskViewListener = new TaskView.Listener() {
        @Override
        public void onInitialized() {
            if (DEBUG) Log.d(TAG, "onInitialized(" + getUserId() + ")");
            mTaskViewReady = true;
            startMapsInTaskView();
            maybeLogReady();
        }

        @Override
        public void onReleased() {
            if (DEBUG) Log.d(TAG, "onReleased(" + getUserId() + ")");
            mTaskViewReady = false;
        }

        @Override
        public void onTaskCreated(int taskId, ComponentName name) {
            if (DEBUG) Log.d(TAG, "onTaskCreated: taskId=" + taskId);
            mTaskViewTaskId = taskId;
            if (isResumed()) {
                maybeBringEmbeddedTaskToForeground();
            }
        }

        @Override
        public void onTaskRemovalStarted(int taskId) {
            if (DEBUG) Log.d(TAG, "onTaskRemovalStarted: taskId=" + taskId);
            mTaskViewTaskId = INVALID_TASK_ID;
            // Don't restart the crashed Maps automatically, because it hinders lots of MultiXXX
            // CTS tests which cleans up all tasks but Home, then monitor Activity state
            // changes. If it restarts Maps, which causes unexpected Activity state changes.
        }
    };

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) {
            boolean launcherFocused = taskId == mCarLauncherTaskId && focused;
            if (DEBUG) {
                Log.d(TAG, "onTaskFocusChanged: taskId=" + taskId
                        + ", launcherFocused=" + launcherFocused
                        + ", mTaskViewTaskId=" + mTaskViewTaskId);
            }
            if (!launcherFocused) {
                return;
            }
            if (mTaskViewTaskId == INVALID_TASK_ID) {
                // If the task in TaskView is crashed during CarLauncher is background,
                // We'd like to restart it when CarLauncher becomes foreground and focused.
                startMapsInTaskView();
            }
        }

        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
            if (DEBUG) {
                Log.d(TAG, "onActivityRestartAttempt: taskId=" + task.taskId
                        + ", homeTaskVisible=" + homeTaskVisible + ", wasVisible=" + wasVisible);
            }
            if (!mUseSmallCanvasOptimizedMap
                    && !homeTaskVisible
                    && mTaskViewTaskId == task.taskId) {
                // The embedded map component received an intent, therefore forcibly bringing the
                // launcher to the foreground.
                bringToForeground();
                return;
            }
            if (homeTaskVisible && mCarLauncherTaskId == task.taskId
                    && mTaskViewTaskId == INVALID_TASK_ID) {
                // Interprets Home Intent while CarLauncher is foreground and Maps is crashed
                // as restarting Maps.
                startMapsInTaskView();
            }
        }
    };

    private final UserLifecycleListener mUserLifecycleListener = event -> {
        if (DEBUG) {
            Log.d(TAG, "UserLifecycleListener.onEvent: For User " + getUserId()
                    + ", received an event " + event);
        }
        // When user-unlocked, if Maps isn't launched yet, then try to start it.
        if (event.getEventType() == USER_LIFECYCLE_EVENT_TYPE_UNLOCKED
                && getUserId() == event.getUserId()
                && mTaskViewTaskId == INVALID_TASK_ID) {
            startMapsInTaskView();
            return;
        }

        // When user-switching, onDestroy in the previous user's CarLauncher isn't called.
        // So tries to release the resource explicitly.
        if (event.getEventType() == USER_LIFECYCLE_EVENT_TYPE_SWITCHING
                && getUserId() == event.getPreviousUserId()) {
            release();
            return;
        }
    };

    private Set<String> mTaskViewPackages;
    private final BroadcastReceiver mPackageBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.d(TAG, "onReceive: intent=" + intent);
            String packageName = intent.getData().getSchemeSpecificPart();
            boolean started = getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
            if (started  // Don't start Maps in STOPPED, because it'll be started onRestart.
                    && mTaskViewTaskId == INVALID_TASK_ID
                    && mTaskViewPackages.contains(packageName)) {
                startMapsInTaskView();
            }
        }
    };

    @VisibleForTesting
    void setCarUserManager(CarUserManager carUserManager) {
        mCarUserManager = carUserManager;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (CarLauncherUtils.isCustomDisplayPolicyDefined(this)) {
            Intent controlBarIntent = new Intent(this, ControlBarActivity.class);
            controlBarIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(controlBarIntent);
            startActivity(
                    CarLauncherUtils.getMapsIntent(this).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            // Register health check monitor for maps.
            finish();
            return;
        }

        mUseSmallCanvasOptimizedMap =
                CarLauncherUtils.isSmallCanvasOptimizedMapIntentConfigured(this);

        Car.createCar(/* context= */ this, /* handler= */ null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (car, ready) -> {
                    if (!ready) {
                        Log.w(TAG, "CarService looks crashed");
                        mCarActivityManagerRef.set(null);
                        return;
                    }
                    setCarUserManager((CarUserManager) car.getCarManager(Car.CAR_USER_SERVICE));
                    UserLifecycleEventFilter filter = new UserLifecycleEventFilter.Builder()
                            .addEventType(USER_LIFECYCLE_EVENT_TYPE_UNLOCKED)
                            .addEventType(USER_LIFECYCLE_EVENT_TYPE_SWITCHING).build();
                    mCarUserManager.addListener(getMainExecutor(), filter, mUserLifecycleListener);
                    CarActivityManager carAM = (CarActivityManager) car.getCarManager(
                            Car.CAR_ACTIVITY_SERVICE);
                    mCarActivityManagerRef.set(carAM);
                    carAM.registerTaskMonitor();
                });

        mActivityManager = getSystemService(ActivityManager.class);
        mUserManager = getSystemService(UserManager.class);
        mCarLauncherTaskId = getTaskId();
        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackListener);

        // Setting as trusted overlay to let touches pass through.
        getWindow().addPrivateFlags(PRIVATE_FLAG_TRUSTED_OVERLAY);
        // To pass touches to the underneath task.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);

        // Don't show the maps panel in multi window mode.
        // NOTE: CTS tests for split screen are not compatible with activity views on the default
        // activity of the launcher
        if (isInMultiWindowMode() || isInPictureInPictureMode()) {
            setContentView(R.layout.car_launcher_multiwindow);
        } else {
            setContentView(R.layout.car_launcher);
            // We don't want to show Map card unnecessarily for the headless user 0.
            if (!UserHelperLite.isHeadlessSystemUser(getUserId())) {
                ViewGroup mapsCard = findViewById(R.id.maps_card);
                if (mapsCard != null) {
                    setUpTaskView(mapsCard);
                }
            }
        }
        initializeCards();

        mTaskViewPackages = new ArraySet<>(getResources().getStringArray(
                R.array.config_taskViewPackages));
        IntentFilter packageIntentFilter = new IntentFilter(Intent.ACTION_PACKAGE_REPLACED);
        packageIntentFilter.addDataScheme(SCHEME_PACKAGE);
        registerReceiver(mPackageBroadcastReceiver, packageIntentFilter);
    }

    private void setUpTaskView(ViewGroup parent) {
        mTaskViewManager = new TaskViewManager(this,
                new HandlerExecutor(getMainThreadHandler()), mCarActivityManagerRef);
        mTaskViewManager.createTaskView(taskView -> {
            taskView.setListener(getMainExecutor(), mTaskViewListener);
            parent.addView(taskView);
            mTaskView = taskView;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeLogReady();
        if (DEBUG) {
            Log.d(TAG, "onResume(" + getUserId() + "): mTaskViewTaskId=" + mTaskViewTaskId);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (CarLauncherUtils.isCustomDisplayPolicyDefined(this)) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "onDestroy(" + getUserId() + "): mTaskViewTaskId=" + mTaskViewTaskId);
        }
        unregisterReceiver(mPackageBroadcastReceiver);
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskStackListener);
        if (mCarUserManager != null) {
            mCarUserManager.removeListener(mUserLifecycleListener);
        }
        release();
    }

    private void release() {
        if (mTaskView != null && mTaskViewReady) {
            mTaskView.release();
            mTaskView = null;
        }
        if (mTaskViewManager != null) {
            mTaskViewManager.release();
        }
        CarActivityManager carAM = mCarActivityManagerRef.get();
        if (carAM != null) {
            carAM.unregisterTaskMonitor();
            mCarActivityManagerRef.set(null);
        }
    }

    private void startMapsInTaskView() {
        if (mTaskView == null || !mTaskViewReady) {
            if (DEBUG) Log.d(TAG, "Can't start Maps due to TaskView isn't ready.");
            return;
        }
        if (!mUserManager.isUserUnlocked()) {
            if (DEBUG) Log.d(TAG, "Can't start Maps due to the user isn't unlocked.");
            return;
        }
        // If we happen to be be resurfaced into a multi display mode we skip launching content
        // in the activity view as we will get recreated anyway.
        if (isInMultiWindowMode() || isInPictureInPictureMode()) {
            if (DEBUG) Log.d(TAG, "Can't start Maps due to CarLauncher isn't in a correct mode");
            return;
        }
        // Don't start Maps when the display is off for ActivityVisibilityTests.
        if (getDisplay().getState() != Display.STATE_ON) {
            if (DEBUG) Log.d(TAG, "Can't start Maps due to the display is off");
            return;
        }
        try {
            ActivityOptions options = ActivityOptions.makeCustomAnimation(this,
                    /* enterResId= */ 0, /* exitResId= */ 0);
            Intent mapIntent = mUseSmallCanvasOptimizedMap
                    ? CarLauncherUtils.getSmallCanvasOptimizedMapIntent(this)
                    : CarLauncherUtils.getMapsIntent(this);
            Rect launchBounds = new Rect();
            mTaskView.getBoundsOnScreen(launchBounds);
            mTaskView.startActivity(
                    PendingIntent.getActivity(this, /* requestCode= */ 0,
                            mapIntent,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT),
                    /* fillInIntent= */ null, options, launchBounds);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Maps activity not found", e);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (CarLauncherUtils.isCustomDisplayPolicyDefined(this)) {
            return;
        }
        initializeCards();
    }

    private void initializeCards() {
        if (mHomeCardModules == null) {
            mHomeCardModules = new ArraySet<>();
            for (String providerClassName : getResources().getStringArray(
                    R.array.config_homeCardModuleClasses)) {
                try {
                    long reflectionStartTime = System.currentTimeMillis();
                    HomeCardModule cardModule = (HomeCardModule) Class.forName(
                            providerClassName).newInstance();
                    cardModule.setViewModelProvider(new ViewModelProvider( /* owner= */this));
                    mHomeCardModules.add(cardModule);
                    if (DEBUG) {
                        long reflectionTime = System.currentTimeMillis() - reflectionStartTime;
                        Log.d(TAG, "Initialization of HomeCardModule class " + providerClassName
                                + " took " + reflectionTime + " ms");
                    }
                } catch (IllegalAccessException | InstantiationException |
                        ClassNotFoundException e) {
                    Log.w(TAG, "Unable to create HomeCardProvider class " + providerClassName, e);
                }
            }
        }
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        for (HomeCardModule cardModule : mHomeCardModules) {
            transaction.replace(cardModule.getCardResId(), cardModule.getCardView());
        }
        transaction.commitNow();
    }

    /** Logs that the Activity is ready. Used for startup time diagnostics. */
    private void maybeLogReady() {
        boolean isResumed = isResumed();
        if (DEBUG) {
            Log.d(TAG, "maybeLogReady(" + getUserId() + "): activityReady=" + mTaskViewReady
                    + ", started=" + isResumed + ", alreadyLogged: " + mIsReadyLogged);
        }
        if (mTaskViewReady && isResumed) {
            // We should report every time - the Android framework will take care of logging just
            // when it's effectively drawn for the first time, but....
            reportFullyDrawn();
            if (!mIsReadyLogged) {
                // ... we want to manually check that the Log.i below (which is useful to show
                // the user id) is only logged once (otherwise it would be logged every time the
                // user taps Home)
                Log.i(TAG, "Launcher for user " + getUserId() + " is ready");
                mIsReadyLogged = true;
            }
        }
    }

    /** Brings embedded task to front, if the task view is created and the task is launched. */
    private void maybeBringEmbeddedTaskToForeground() {
        if (mTaskViewTaskId != INVALID_TASK_ID) {
            // The task in TaskView should be in top to make it visible.
            // NOTE: Tried setTaskAlwaysOnTop before, the flag has some side effect to hinder
            // AccessibilityService from finding the correct window geometry: b/197247311
            mActivityManager.moveTaskToFront(mTaskViewTaskId, /* flags= */ 0);
        }
    }

    /** Brings the Car Launcher to the foreground. */
    private void bringToForeground() {
        if (mCarLauncherTaskId != INVALID_TASK_ID) {
            mActivityManager.moveTaskToFront(mCarLauncherTaskId,  /* flags= */ 0);
        }
    }
}
