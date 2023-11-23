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
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.TaskStackListener;
import android.car.Car;
import android.car.app.CarActivityManager;
import android.car.app.CarTaskViewController;
import android.car.app.CarTaskViewControllerCallback;
import android.car.app.ControlledRemoteCarTaskView;
import android.car.app.ControlledRemoteCarTaskViewCallback;
import android.car.app.ControlledRemoteCarTaskViewConfig;
import android.car.user.CarUserManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.collection.ArraySet;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.carlauncher.homescreen.HomeCardModule;
import com.android.car.carlauncher.taskstack.TaskStackChangeListeners;
import com.android.car.internal.common.UserHelperLite;
import com.android.wm.shell.taskview.TaskView;

import com.google.common.annotations.VisibleForTesting;

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
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private ActivityManager mActivityManager;
    private TaskViewManager mTaskViewManager;

    private CarTaskView mTaskView;
    private ControlledRemoteCarTaskView mRemoteCarTaskView;
    private int mCarLauncherTaskId = INVALID_TASK_ID;
    private Set<HomeCardModule> mHomeCardModules;

    /** Set to {@code true} once we've logged that the Activity is fully drawn. */
    private boolean mIsReadyLogged;
    private boolean mUseSmallCanvasOptimizedMap;
    private boolean mUseRemoteCarTaskView;

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) {}

        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
            if (DEBUG) {
                Log.d(TAG, "onActivityRestartAttempt: taskId=" + task.taskId
                        + ", homeTaskVisible=" + homeTaskVisible + ", wasVisible=" + wasVisible);
            }
            if (!mUseSmallCanvasOptimizedMap
                    && !homeTaskVisible
                    && getTaskViewTaskId() == task.taskId) {
                // The embedded map component received an intent, therefore forcibly bringing the
                // launcher to the foreground.
                bringToForeground();
                return;
            }
        }
    };

    @VisibleForTesting
    void setCarUserManager(CarUserManager carUserManager) {
        if (mTaskViewManager == null) {
            Log.w(TAG, "Task view manager is null, cannot set CarUserManager");
            return;
        }
        mTaskViewManager.setCarUserManager(carUserManager);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (DEBUG) {
            Log.d(TAG, "onCreate(" + getUserId() + ") displayId=" + getDisplayId());
        }
        // Since MUMD is introduced, CarLauncher can be called in the main display of visible users.
        // In ideal shape, CarLauncher should handle both driver and passengers together.
        // But, in the mean time, we have separate launchers for driver and passengers, so
        // CarLauncher needs to reroute the request to Passenger launcher if it is invoked from
        // the main display of passengers (not driver).
        // For MUPAND, PassengerLauncher should be the default launcher.
        // For non-main displays, ATM will invoke SECONDARY_HOME Intent, so the secondary launcher
        // should handle them.
        UserManager um = getSystemService(UserManager.class);
        boolean isPassengerDisplay = getDisplayId() != Display.DEFAULT_DISPLAY
                || um.isVisibleBackgroundUsersOnDefaultDisplaySupported();
        if (isPassengerDisplay) {
            String passengerLauncherName = getString(R.string.config_passengerLauncherComponent);
            Intent passengerHomeIntent;
            if (!passengerLauncherName.isEmpty()) {
                ComponentName component = ComponentName.unflattenFromString(passengerLauncherName);
                if (component == null) {
                    throw new IllegalStateException(
                            "Invalid passengerLauncher name=" + passengerLauncherName);
                }
                passengerHomeIntent = new Intent(Intent.ACTION_MAIN)
                        .setComponent(component);
            } else {
                // No passenger launcher is specified, then use AppsGrid as a fallback.
                passengerHomeIntent = CarLauncherUtils.getAppsGridIntent();
            }
            ActivityOptions options = ActivityOptions
                    // No animation for the trampoline.
                    .makeCustomAnimation(this, /* enterResId=*/ 0, /* exitResId= */ 0)
                    .setLaunchDisplayId(getDisplayId());
            startActivity(passengerHomeIntent, options.toBundle());
            finish();
            return;
        }

        mUseSmallCanvasOptimizedMap =
                CarLauncherUtils.isSmallCanvasOptimizedMapIntentConfigured(this);
        mUseRemoteCarTaskView = getResources().getBoolean(R.bool.config_useRemoteCarTaskView);

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
                    if (mUseRemoteCarTaskView) {
                        setupRemoteCarTaskView(mapsCard);
                    } else {
                        setUpTaskView(mapsCard);
                    }
                }
            }
        }
        initializeCards();
    }

    private void setupRemoteCarTaskView(ViewGroup parent) {
        Car.createCar(/* context= */ this, /* handler= */ null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (car, ready) -> {
                    if (!ready) {
                        Log.w(TAG, "CarService is not ready.");
                        return;
                    }
                    CarActivityManager carAM = (CarActivityManager) car.getCarManager(
                            Car.CAR_ACTIVITY_SERVICE);

                    carAM.getCarTaskViewController(
                            this,
                            getMainExecutor(),
                            new CarTaskViewControllerCallback() {
                                @Override
                                public void onConnected(
                                        CarTaskViewController carTaskViewController) {
                                    carTaskViewController.createControlledRemoteCarTaskView(
                                            new ControlledRemoteCarTaskViewConfig.Builder()
                                                    .setActivityIntent(getMapsIntent())
                                                    .setShouldAutoRestartOnTaskRemoval(true)
                                                    .build(),
                                            getMainExecutor(),
                                            new ControlledRemoteCarTaskViewCallback() {
                                                @Override
                                                public void onTaskViewCreated(
                                                        ControlledRemoteCarTaskView taskView) {
                                                    mRemoteCarTaskView = taskView;
                                                    parent.addView(taskView);
                                                }

                                                @Override
                                                public void onTaskViewInitialized() {
                                                    maybeLogReady();
                                                }
                                            });
                                }

                                @Override
                                public void onDisconnected(
                                        CarTaskViewController carTaskViewController) {
                                    Log.d(TAG, "onDisconnected");
                                    parent.removeAllViews();
                                }
                            });
                });
    }

    private void setUpTaskView(ViewGroup parent) {
        Set<String> taskViewPackages = new ArraySet<>(getResources().getStringArray(
                R.array.config_taskViewPackages));
        mTaskViewManager = new TaskViewManager(this, getMainThreadHandler());

        mTaskViewManager.createControlledCarTaskView(
                getMainExecutor(),
                ControlledCarTaskViewConfig.builder()
                        .setActivityIntent(getMapsIntent())
                        // TODO(b/263876526): Enable auto restart after ensuring no CTS failure.
                        .setAutoRestartOnCrash(false)
                        .build(),
                new ControlledCarTaskViewCallbacks() {
                    @Override
                    public void onTaskViewCreated(CarTaskView taskView) {
                        parent.addView(taskView);
                        mTaskView = taskView;
                    }

                    @Override
                    public void onTaskViewReady() {
                        maybeLogReady();
                    }

                    @Override
                    public Set<String> getDependingPackageNames() {
                        return taskViewPackages;
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeLogReady();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskStackListener);
        release();
    }

    private boolean isTaskViewInitialized() {
        return (mTaskView != null && mTaskView.isInitialized())
                || (mRemoteCarTaskView != null && mRemoteCarTaskView.isInitialized());
    }

    private int getTaskViewTaskId() {
        if (mTaskView != null) {
            return mTaskView.getTaskId();
        }
        if (mRemoteCarTaskView != null) {
            return mRemoteCarTaskView.getTaskInfo() == null
                    ? INVALID_TASK_ID : mRemoteCarTaskView.getTaskInfo().taskId;
        }
        return INVALID_TASK_ID;
    }

    private void release() {
        mTaskView = null;
        mRemoteCarTaskView = null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
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
        boolean taskViewInitialized = isTaskViewInitialized();
        if (DEBUG) {
            Log.d(TAG, "maybeLogReady(" + getUserId() + "): mapsReady="
                    + taskViewInitialized + ", started=" + isResumed + ", alreadyLogged: "
                    + mIsReadyLogged);
        }
        if (taskViewInitialized && isResumed) {
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

    private Intent getMapsIntent() {
        Intent mapIntent = mUseSmallCanvasOptimizedMap
                ? CarLauncherUtils.getSmallCanvasOptimizedMapIntent(this)
                : CarLauncherUtils.getMapsIntent(this);
        // Don't want to show this Activity in Recents.
        mapIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        return mapIntent;
    }
}
