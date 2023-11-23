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
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_FULLSCREEN;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.app.TaskInfo;
import android.app.TaskStackListener;
import android.car.Car;
import android.car.user.CarUserManager;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.window.TaskAppearedInfo;

import androidx.annotation.NonNull;
import androidx.collection.ArraySet;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.carlauncher.displayarea.CarDisplayAreaController;
import com.android.car.carlauncher.displayarea.CarDisplayAreaOrganizer;
import com.android.car.carlauncher.displayarea.CarFullscreenTaskListener;
import com.android.car.carlauncher.homescreen.HomeCardModule;
import com.android.car.carlauncher.taskstack.TaskStackChangeListeners;
import com.android.car.internal.common.UserHelperLite;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.TaskView;
import com.android.wm.shell.common.HandlerExecutor;
import com.android.wm.shell.startingsurface.StartingWindowController;
import com.android.wm.shell.startingsurface.phone.PhoneStartingWindowTypeAlgorithm;

import java.util.List;
import java.util.Set;

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

    private ActivityManager mActivityManager;
    private CarUserManager mCarUserManager;
    private ShellTaskOrganizer mShellTaskOrganizer;
    private TaskViewManager mTaskViewManager;

    private TaskView mTaskView;
    private boolean mTaskViewReady;
    // Tracking this to check if the task in TaskView has crashed in the background.
    private int mTaskViewTaskId = INVALID_TASK_ID;
    private boolean mIsResumed;
    private boolean mFocused;
    private int mCarLauncherTaskId = INVALID_TASK_ID;
    private Set<HomeCardModule> mHomeCardModules;

    /** Set to {@code true} once we've logged that the Activity is fully drawn. */
    private boolean mIsReadyLogged;

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
        }

        @Override
        public void onTaskRemovalStarted(int taskId) {
            if (DEBUG) Log.d(TAG, "onTaskRemovalStarted: taskId=" + taskId);
            mTaskViewTaskId = INVALID_TASK_ID;
        }
    };

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) {
            mFocused = taskId == mCarLauncherTaskId && focused;
            if (DEBUG) {
                Log.d(TAG, "onTaskFocusChanged: mFocused=" + mFocused
                        + ", mTaskViewTaskId=" + mTaskViewTaskId);
            }
            if (mFocused && mTaskViewTaskId == INVALID_TASK_ID) {
                // If the task in TaskView is crashed during CarLauncher is background,
                // We'd like to restart it when CarLauncher becomes foreground and focused.
                startMapsInTaskView();
            }
        }

        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
            if (!homeTaskVisible && mTaskViewTaskId == task.taskId) {
                // The embedded map component received an intent, therefore forcibly bringing the
                // launcher to the foreground.
                bringToForeground();
            }
        }
    };

    private final UserLifecycleListener mUserLifecyleListener = new UserLifecycleListener() {
        @Override
        public void onEvent(@NonNull CarUserManager.UserLifecycleEvent event) {
            if (event.getEventType() == USER_LIFECYCLE_EVENT_TYPE_SWITCHING) {
                // When user-switching, onDestroy in the previous user's CarLauncher isn't called.
                // So tries to release the resource explicitly.
                release();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If policy provider is defined then AppGridActivity should be launched.
        // TODO: update this code flow. Maybe have some kind of configurable activity.
        if (CarLauncherUtils.isCustomDisplayPolicyDefined(this)) {
            CarLauncherApplication application = (CarLauncherApplication) getApplication();

            mShellTaskOrganizer = new ShellTaskOrganizer(
                    application.getShellExecutor(), this);
            CarFullscreenTaskListener fullscreenTaskListener = new CarFullscreenTaskListener(
                    this, application.getSyncTransactionQueue(),
                    CarDisplayAreaController.getInstance());
            mShellTaskOrganizer.addListenerForType(
                    fullscreenTaskListener, TASK_LISTENER_TYPE_FULLSCREEN);
            StartingWindowController startingController =
                    new StartingWindowController(this, application.getShellExecutor(),
                            new PhoneStartingWindowTypeAlgorithm(), new IconProvider(this),
                            application.getTransactionPool());
            mShellTaskOrganizer.initStartingWindow(startingController);
            List<TaskAppearedInfo> taskAppearedInfos = mShellTaskOrganizer.registerOrganizer();
            try {
                cleanUpExistingTaskViewTasks(taskAppearedInfos);
            } catch (Exception ex) {
                Log.w(TAG, "some of the tasks couldn't be cleaned up: ", ex);
            }
            CarDisplayAreaController carDisplayAreaController =
                    CarDisplayAreaController.getInstance();
            CarDisplayAreaOrganizer org = carDisplayAreaController.getOrganizer();
            org.startControlBarInDisplayArea();
            org.startMapsInBackGroundDisplayArea();
            return;
        }

        Car.createCar(getApplicationContext(), /* handler= */ null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (car, ready) -> {
                    if (!ready) return;
                    mCarUserManager = (CarUserManager) car.getCarManager(Car.CAR_USER_SERVICE);
                    mCarUserManager.addListener(getMainExecutor(), mUserLifecyleListener);
                });

        mActivityManager = getSystemService(ActivityManager.class);
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
    }

    private static void cleanUpExistingTaskViewTasks(List<TaskAppearedInfo> taskAppearedInfos) {
        ActivityTaskManager atm = ActivityTaskManager.getInstance();
        for (TaskAppearedInfo taskAppearedInfo : taskAppearedInfos) {
            TaskInfo taskInfo = taskAppearedInfo.getTaskInfo();
            try {
                atm.removeTask(taskInfo.taskId);
            } catch (Exception e) {
                if (DEBUG) {
                    Log.d(TAG, "failed to remove task likely b/c it no longer exists " + taskInfo);
                }
            }
        }
    }

    private void setUpTaskView(ViewGroup parent) {
        mTaskViewManager = new TaskViewManager(this,
                new HandlerExecutor(getMainThreadHandler()));
        mTaskViewManager.createTaskView(taskView -> {
            taskView.setListener(getMainExecutor(), mTaskViewListener);
            parent.addView(taskView);
            mTaskView = taskView;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIsResumed = true;
        maybeLogReady();
        if (DEBUG) {
            Log.d(TAG, "onResume: mFocused=" + mFocused + ", mTaskViewTaskId=" + mTaskViewTaskId);
        }
        if (!mTaskViewReady) return;
        if (mTaskViewTaskId != INVALID_TASK_ID) {
            // The task in TaskView should be in top to make it visible.
            // NOTE: Tried setTaskAlwaysOnTop before, the flag has some side effect to hinder
            // AccessibilityService from finding the correct window geometry: b/197247311
            mActivityManager.moveTaskToFront(mTaskViewTaskId, /* flags= */ 0);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsResumed = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskStackListener);
        release();
    }

    private void release() {
        if (mShellTaskOrganizer != null) {
            mShellTaskOrganizer.unregisterOrganizer();
        }
        if (mTaskView != null && mTaskViewReady) {
            mTaskView.release();
            mTaskView = null;
        }
        if (mTaskViewManager != null) {
            mTaskViewManager.release();
        }
    }

    private void startMapsInTaskView() {
        if (mTaskView == null || !mTaskViewReady) {
            return;
        }
        // If we happen to be be resurfaced into a multi display mode we skip launching content
        // in the activity view as we will get recreated anyway.
        if (isInMultiWindowMode() || isInPictureInPictureMode()) {
            return;
        }
        // Don't start Maps when the display is off for ActivityVisibilityTests.
        if (getDisplay().getState() != Display.STATE_ON) {
            return;
        }
        try {
            ActivityOptions options = ActivityOptions.makeCustomAnimation(this,
                    /* enterResId= */ 0, /* exitResId= */ 0);
            mTaskView.startActivity(
                    PendingIntent.getActivity(this, /* requestCode= */ 0,
                            CarLauncherUtils.getMapsIntent(this),
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT),
                    /* fillInIntent= */ null, options, null /* launchBounds */);
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
        if (DEBUG) {
            Log.d(TAG, "maybeLogReady(" + getUserId() + "): activityReady=" + mTaskViewReady
                    + ", started=" + mIsResumed + ", alreadyLogged: " + mIsReadyLogged);
        }
        if (mTaskViewReady && mIsResumed) {
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

    /** Brings the Car Launcher to the foreground. */
    private void bringToForeground() {
        if (mCarLauncherTaskId != INVALID_TASK_ID) {
            mActivityManager.moveTaskToFront(mCarLauncherTaskId,  /* flags= */ 0);
        }
    }
}
