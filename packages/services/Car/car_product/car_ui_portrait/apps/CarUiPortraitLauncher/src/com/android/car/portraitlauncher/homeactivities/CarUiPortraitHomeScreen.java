/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.car.portraitlauncher.homeactivities;

import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;

import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_APP_GRID_VISIBILITY_CHANGE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_COLLAPSE_NOTIFICATION;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_COLLAPSE_RECENTS;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_FG_TASK_VIEW_READY;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_HIDE_SYSTEM_BAR_FOR_IMMERSIVE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_IMMERSIVE_MODE_CHANGE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_IMMERSIVE_MODE_REQUESTED;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_NOTIFICATIONS_VISIBILITY_CHANGE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_RECENTS_VISIBILITY_CHANGE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_REGISTER_CLIENT;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_SUW_IN_PROGRESS;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_SYSUI_STARTED;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_UNREGISTER_CLIENT;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.IActivityManager;
import android.app.TaskInfo;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerGlobal;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.carlauncher.CarLauncher;
import com.android.car.carlauncher.CarLauncherUtils;
import com.android.car.carlauncher.CarTaskView;
import com.android.car.carlauncher.ControlledCarTaskViewCallbacks;
import com.android.car.carlauncher.ControlledCarTaskViewConfig;
import com.android.car.carlauncher.LaunchRootCarTaskViewCallbacks;
import com.android.car.carlauncher.SemiControlledCarTaskViewCallbacks;
import com.android.car.carlauncher.TaskViewManager;
import com.android.car.carlauncher.homescreen.HomeCardModule;
import com.android.car.carlauncher.taskstack.TaskStackChangeListeners;
import com.android.car.caruiportrait.common.service.CarUiPortraitService;
import com.android.car.portraitlauncher.R;
import com.android.car.portraitlauncher.common.IntentHandler;
import com.android.car.portraitlauncher.homescreen.audio.MediaIntentRouter;
import com.android.car.portraitlauncher.panel.TaskViewPanel;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * This home screen has maps running in the background hosted in a TaskView. At the bottom, there
 * is a control bar view that will display information regarding media/dialer. Any other
 * application other than assistant voice activity will be launched in the rootTaskView that is
 * displayed in the lower half of the screen.
 *
 * —--------------------------------------------------------------------
 * |                              Status Bar                            |
 * —--------------------------------------------------------------------
 * |                                                                    |
 * |                                                                    |
 * |                                                                    |
 * |                            Navigation app                          |
 * |                        (BackgroundTask View)                       |
 * |                                                                    |
 * |                                                                    |
 * |                                                                    |
 * |                                                                    |
 * —--------------------------------------------------------------------
 * |                                                                    |
 * |                                                                    |
 * |                                                                    |
 * |                      App Space (Root Task View Panel)              |
 * |                      (This layer is above maps)                    |
 * |                                                                    |
 * |                                                                    |
 * |                                                                    |
 * |                                                                    |
 * —--------------------------------------------------------------------
 * |                          Control Bar                               |
 * |                                                                    |
 * —--------------------------------------------------------------------
 * |                             Nav Bar                                |
 * —--------------------------------------------------------------------
 *
 * In total this Activity has 3 TaskViews.
 * Background Task view:
 * - It only contains maps app.
 * - Maps app is manually started in this taskview.
 *
 * Root Task View (Part of Root Task View Panel):
 * - It acts as the default container. Which means all the apps will run inside it by default.
 *
 * Note: Root Task View Panel always overlap over the Background TaskView.
 *
 * Fullscreen Task View
 * - Used for voice assist applications
 */
public final class CarUiPortraitHomeScreen extends FragmentActivity {
    public static final String TAG = CarUiPortraitHomeScreen.class.getSimpleName();
    private static final boolean DBG = Build.IS_DEBUGGABLE;
    private static final String SAVED_BACKGROUND_APP_COMPONENT_NAME =
            "SAVED_BACKGROUND_APP_COMPONENT_NAME";
    private static final IActivityManager sActivityManager = ActivityManager.getService();

    private int mCurrentBackgroundTaskId;
    private int mStatusBarHeight;
    private FrameLayout mContainer;
    private View mControlBarView;
    private TaskViewManager mTaskViewManager;
    // All the TaskViews & corresponding helper instance variables.
    private CarTaskView mBackgroundTaskView;
    private CarTaskView mFullScreenTaskView;
    private boolean mIsBackgroundTaskViewReady;
    private boolean mIsFullScreenTaskViewReady;
    private int mNavBarHeight;
    private boolean mIsSUWInProgress;
    private TaskCategoryManager mTaskCategoryManager;
    private boolean mIsNotificationCenterOnTop;
    private boolean mIsRecentsOnTop;
    private TaskInfoCache mTaskInfoCache;
    private TaskViewPanel mAppGridTaskViewPanel;
    private TaskViewPanel mRootTaskViewPanel;

    private InputManagerGlobal mInputManagerGlobal;

    /** Messenger for communicating with {@link CarUiPortraitService}. */
    private Messenger mService = null;
    /** Flag indicating whether or not {@link CarUiPortraitService} is bounded. */
    private boolean mIsBound;

    /**
     * All messages from {@link CarUiPortraitService} are received in this handler.
     */
    private final Messenger mMessenger = new Messenger(new IncomingHandler());

    /** Holds any messages fired before service connection is establish. */
    private final List<Message> mMessageCache = new ArrayList<>();

    private CarUiPortraitDriveStateController mCarUiPortraitDriveStateController;

    private final IntentHandler mMediaIntentHandler = new IntentHandler() {
        @Override
        public void handleIntent(Intent intent) {
            if (TaskCategoryManager.isMediaApp(mRootTaskViewPanel.getCurrentTask())) {
                mRootTaskViewPanel.closePanel();
                return;
            }

            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);

            startActivity(intent, options.toBundle());
        }
    };

    /**
     * Class for interacting with the main interface of the {@link CarUiPortraitService}.
     */
    private final ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            // Communicating with our service through an IDL interface, so get a client-side
            // representation of that from the raw service object.
            mService = new Messenger(service);

            // Register to the service.
            try {
                Message msg = Message.obtain(null, MSG_REGISTER_CLIENT);
                msg.replyTo = mMessenger;
                mService.send(msg);
            } catch (RemoteException e) {
                // In this case the service has crashed before we could even
                // do anything with it; we can count on soon being
                // disconnected (and then reconnected if it can be restarted)
                // so there is no need to do anything here.
                Log.w(TAG, "can't connect to CarUiPortraitService: ", e);
            }
            for (Message msg : mMessageCache) {
                notifySystemUI(msg);
            }
            mMessageCache.clear();
        }

        public void onServiceDisconnected(ComponentName className) {
            mService = null;
        }
    };

    // This listener lets us know when actives are added and removed from any of the display regions
    // we care about, so we can trigger the opening and closing of the app containers as needed.
    private final TaskStackListener mTaskStackListener = new TaskStackListener() {

        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) throws RemoteException {
            if (componentName != null) {
                logIfDebuggable("On task created, task = " + taskId
                        + " componentName " + componentName);
            }

            // Re-launch the CarUiPortraitHomeScreen if coming background app is not current
            // background app.
            if (mTaskCategoryManager.isBackgroundApp(componentName)) {
                mCurrentBackgroundTaskId = taskId;
                if (!mTaskCategoryManager.isCurrentBackgroundApp(componentName)) {
                    logIfDebuggable(
                            "Re-create home screen on background app switch to " + componentName);
                    mTaskCategoryManager.setCurrentBackgroundApp(componentName);
                    recreate();
                }
            }
        }

        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) {
            super.onTaskFocusChanged(taskId, focused);
            boolean hostFocused = taskId == CarUiPortraitHomeScreen.this.getTaskId() && focused;
            if (hostFocused && mTaskViewManager != null) {
                mTaskViewManager.showEmbeddedTasks();
            }
        }

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo)
                throws RemoteException {
            logIfDebuggable("On task moved to front, task = " + taskInfo.taskId + ", cmp = "
                    + taskInfo.baseActivity);
            if (!mRootTaskViewPanel.isReady()) {
                logIfDebuggable("Root Task View is not ready yet.");
                if (!TaskCategoryManager.isHomeIntent(taskInfo)
                        && !mTaskCategoryManager.isBackgroundApp(taskInfo)
                        && !mTaskCategoryManager.isAppGridActivity(taskInfo)) {

                    cacheTask(taskInfo);
                }
                return;
            }

            mIsNotificationCenterOnTop = mTaskCategoryManager.isNotificationActivity(taskInfo);
            mIsRecentsOnTop = mTaskCategoryManager.isRecentsActivity(taskInfo);
            // Close the panel if the top application is a blank activity.
            // This is to prevent showing a blank panel to the user if an app crashes and reveals
            // the blank activity underneath.
            if (mTaskCategoryManager.isBlankActivity(taskInfo)) {
                setFocusToBackgroundApp();
                return;
            }

            if (mTaskCategoryManager.isBackgroundApp(taskInfo)) {
                return;
            }

            if (shouldTaskShowOnRootTaskView(taskInfo)) {
                logIfDebuggable("Opening in root task view: ");
                mRootTaskViewPanel.setCurrentTask(taskInfo);

                if (mAppGridTaskViewPanel.isOpen()) {
                    // Animate the root task view to expand on top of the app grid task view.
                    mRootTaskViewPanel.expandPanel();
                } else {
                    // Open the root task view.
                    mRootTaskViewPanel.openPanel(/* animate = */ true);
                }
            } else {
                logIfDebuggable("Not showing task in rootTaskView");
            }
        }

        /**
         * Called whenever IActivityManager.startActivity is called on an activity that is already
         * running, but the task is either brought to the front or a new Intent is delivered to it.
         *
         * @param taskInfo information about the task the activity was relaunched into
         * @param homeTaskVisible whether or not the home task is visible
         * @param clearedTask whether or not the launch activity also cleared the task as a part of
         * starting
         * @param wasVisible whether the activity was visible before the restart attempt
         */
        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo taskInfo,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible)
                throws RemoteException {
            super.onActivityRestartAttempt(taskInfo, homeTaskVisible, clearedTask, wasVisible);

            logIfDebuggable("On Activity restart attempt, task = " + taskInfo.taskId + ", cmp ="
                    + taskInfo.baseActivity);
            if (taskInfo.baseIntent == null || taskInfo.baseIntent.getComponent() == null) {
                return;
            }

            if (!wasVisible) {
                return;
            }

            if (mTaskCategoryManager.isBackgroundApp(taskInfo)
                    || mTaskCategoryManager.isBlankActivity(taskInfo)) {
                return;
            }

            logIfDebuggable("Update UI state on app restart attempt");
            if (mTaskCategoryManager.isAppGridActivity(taskInfo)) {
                if (mRootTaskViewPanel.isAnimating()) {
                    mRootTaskViewPanel.closePanel(/* animated = */ false);
                }

                // If the new task is an app grid then toggle the app grid panel:
                // 1 - Close the app grid panel if it is open.
                // 2 - Open the app grid panel if it is closed:
                //    a) If the root task view is open on top of the app grid then use a fade
                //       animation to hide the root task view panel and show the app grid panel.
                //    b) Otherwise, simply open the app grid panel.
                if (mAppGridTaskViewPanel.isOpen()) {
                    mAppGridTaskViewPanel.closePanel();
                } else if (mRootTaskViewPanel.isOpen()) {
                    mAppGridTaskViewPanel.fadeInPanel();
                    mRootTaskViewPanel.fadeOutPanel();
                } else {
                    mAppGridTaskViewPanel.openPanel();
                }
            } else if (shouldTaskShowOnRootTaskView(taskInfo)) {
                if (mAppGridTaskViewPanel.isAnimating() && mAppGridTaskViewPanel.isOpen()) {
                    mAppGridTaskViewPanel.openPanel(/* animated = */ false);
                }

                // If the new task should be launched in the root task view panel:
                // 1 - Close the root task view panel if it is open and the task is notification
                //    center. Make sure the app grid panel is closed already in case we are
                //    interrupting a running animation.
                // 2 - Open the root task view panel if it is closed:
                //    a) If the app grid panel is already open then use an expand animation
                //       to open the root task view on top of the app grid task view.
                //    b) Otherwise, simply open the app grid panel.
                if (mRootTaskViewPanel.isOpen()
                        && mTaskCategoryManager.isNotificationActivity(taskInfo)) {
                    if (mAppGridTaskViewPanel.isOpen()) {
                        mAppGridTaskViewPanel.closePanel(/* animated = */ false);
                    }
                    mRootTaskViewPanel.closePanel();
                } else {
                    if (mAppGridTaskViewPanel.isOpen()) {
                        mRootTaskViewPanel.expandPanel();
                    } else {
                        mRootTaskViewPanel.openPanel();
                    }
                }
            }
        }
    };

    private void setFocusToBackgroundApp() {
        try {
            sActivityManager.setFocusedRootTask(mCurrentBackgroundTaskId);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to set focus on background app: ", e);
        }
    }

    /**
     * Only resize the size of rootTaskView when SUW is in progress. This is to resize the height of
     * rootTaskView after status bar hide on SUW start.
     */
    private final View.OnLayoutChangeListener mHomeScreenLayoutChangeListener =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int newHeight = bottom - top;
                int oldHeight = oldBottom - oldTop;
                if (oldHeight == newHeight) {
                    return;
                }
                logIfDebuggable("container height change from " + oldHeight + " to " + newHeight);
                if (mIsSUWInProgress) {
                    mRootTaskViewPanel.openFullScreenPanel(/* animated = */ false,
                            /* showToolBar = */ false, /* bottomAdjustment= */ 0);
                }
            };

    private static void logIfDebuggable(String message) {
        if (DBG) {
            Log.d(TAG, message);
        }
    }

    private final View.OnLayoutChangeListener mControlBarOnLayoutChangeListener =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (bottom == oldBottom && top == oldTop) {
                    return;
                }

                logIfDebuggable("Control Bar layout changed!");
                updateTaskViewInsets();
                updateBackgroundTaskViewInsets();
                updateObscuredTouchRegion();
            };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getApplicationContext().getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE) {
            Intent launcherIntent = new Intent(this, CarLauncher.class);
            launcherIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launcherIntent);
            finish();
            return;
        }

        setContentView(R.layout.car_ui_portrait_launcher);

        mTaskCategoryManager = new TaskCategoryManager(getApplicationContext());
        if (savedInstanceState != null) {
            String savedBackgroundAppName = savedInstanceState.getString(
                    SAVED_BACKGROUND_APP_COMPONENT_NAME);
            if (savedBackgroundAppName != null) {
                mTaskCategoryManager.setCurrentBackgroundApp(
                        ComponentName.unflattenFromString(savedBackgroundAppName));
            }
        }

        mTaskInfoCache = new TaskInfoCache(getApplicationContext());

        // Make the window fullscreen as GENERIC_OVERLAYS are supplied to the background task view
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Activity is running fullscreen to allow background task to bleed behind status bar
        mNavBarHeight = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_height);
        logIfDebuggable("Navbar height: " + mNavBarHeight);
        mContainer = findViewById(R.id.container);
        mContainer.addOnLayoutChangeListener(mHomeScreenLayoutChangeListener);
        setHomeScreenBottomPadding(mNavBarHeight);

        mAppGridTaskViewPanel = findViewById(R.id.app_grid_panel);

        mRootTaskViewPanel = findViewById(R.id.application_panel);

        mStatusBarHeight = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);

        mControlBarView = findViewById(R.id.control_bar_area);
        mControlBarView.addOnLayoutChangeListener(mControlBarOnLayoutChangeListener);

        // Setting as trusted overlay to let touches pass through.
        getWindow().addPrivateFlags(PRIVATE_FLAG_TRUSTED_OVERLAY);
        // To pass touches to the underneath task.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);

        mContainer.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    boolean widthChanged = (right - left) != (oldRight - oldLeft);
                    boolean heightChanged = (bottom - top) != (oldBottom - oldTop);

                    if (widthChanged || heightChanged) {
                        onContainerDimensionsChanged();
                    }
                });

        // If we happen to be resurfaced into a multi display mode we skip launching content
        // in the activity view as we will get recreated anyway.
        if (isInMultiWindowMode() || isInPictureInPictureMode()) {
            return;
        }

        if (mTaskViewManager == null) {
            mTaskViewManager = new TaskViewManager(this, getMainThreadHandler());
        }

        mCarUiPortraitDriveStateController = new CarUiPortraitDriveStateController(
                getApplicationContext());

        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackListener);

        initializeCards();
        doBindService();

        setUpRootTaskView();

        MediaIntentRouter.getInstance().registerMediaIntentHandler(mMediaIntentHandler);

        mInputManagerGlobal = InputManagerGlobal.getInstance();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // This is done to handle the case where 'close' is tapped on ActivityBlockingActivity and
        // it navigates to the home app. It assumes that the currently display task will be
        // replaced with the home.
        // In this case, ABA is actually displayed inside launch-root-task. By closing the
        // root task view panel we make sure the app goes to the background.
        mRootTaskViewPanel.closePanel();
        // Close app grid to account for home key event
        mAppGridTaskViewPanel.closePanel();
    }

    private void initializeCards() {
        Set<HomeCardModule> homeCardModules = new androidx.collection.ArraySet<>();
        for (String providerClassName : getResources().getStringArray(
                R.array.config_homeCardModuleClasses)) {
            try {
                long reflectionStartTime = System.currentTimeMillis();
                HomeCardModule cardModule = (HomeCardModule) Class.forName(
                        providerClassName).getDeclaredConstructor().newInstance();
                cardModule.setViewModelProvider(new ViewModelProvider(/* owner= */ this));
                homeCardModules.add(cardModule);
                if (DBG) {
                    long reflectionTime = System.currentTimeMillis() - reflectionStartTime;
                    logIfDebuggable(
                            "Initialization of HomeCardModule class " + providerClassName
                                    + " took " + reflectionTime + " ms");
                }
            } catch (IllegalAccessException | InstantiationException
                     | ClassNotFoundException | InvocationTargetException
                     | NoSuchMethodException e) {
                Log.w(TAG, "Unable to create HomeCardProvider class " + providerClassName, e);
            }
        }
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        for (HomeCardModule cardModule : homeCardModules) {
            transaction.replace(cardModule.getCardResId(), cardModule.getCardView());
        }
        transaction.commitNow();
    }

    private void collapseNotificationPanel() {
        if (mIsNotificationCenterOnTop) {
            mRootTaskViewPanel.closePanel();
        }
    }

    private void collapseRecentsPanel() {
        if (mIsRecentsOnTop) {
            mRootTaskViewPanel.closePanel();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        initializeCards();

        mRootTaskViewPanel.post(() -> mRootTaskViewPanel.refresh(getTheme()));
        mAppGridTaskViewPanel.post(() -> mAppGridTaskViewPanel.refresh(getTheme()));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mTaskCategoryManager.getCurrentBackgroundApp() == null) {
            return;
        }
        outState.putString(SAVED_BACKGROUND_APP_COMPONENT_NAME,
                mTaskCategoryManager.getCurrentBackgroundApp().flattenToString());
    }

    @Override
    protected void onDestroy() {
        mTaskViewManager = null;
        mRootTaskViewPanel.onDestroy();
        mBackgroundTaskView = null;
        mFullScreenTaskView = null;
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskStackListener);
        doUnbindService();
        super.onDestroy();
    }

    private boolean shouldTaskShowOnRootTaskView(TaskInfo taskInfo) {
        if (taskInfo.baseIntent == null || taskInfo.baseIntent.getComponent() == null) {
            logIfDebuggable("Should not show on root task view since base intent is null");
            return false;
        }

        // fullscreen activities will open in a separate task view which will show on top most
        // z-layer, that should not change the state of the root task view.
        if (mTaskCategoryManager.isFullScreenActivity(taskInfo)) {
            logIfDebuggable("Should not show on root task view since task is full screen activity");
            return false;
        }

        if (mTaskCategoryManager.isAppGridActivity(taskInfo)) {
            logIfDebuggable("Don't open app grid activity on the root task view");
            return false;
        }

        if (mTaskCategoryManager.isBackgroundApp(taskInfo)) {
            logIfDebuggable("Should not show on root task view since task is background activity");
            // we don't want to change the state of the root task view when background
            // task are launched or brought to front.
            return false;
        }

        // Any task that does NOT meet all the below criteria should be ignored.
        // 1. displayAreaFeatureId should be FEATURE_DEFAULT_TASK_CONTAINER
        // 2. should be visible
        // 3. for the current user ONLY. System user launches some tasks on cluster that should
        //    not affect the state of the foreground DA
        // 4. any task that is manually defined to be ignored
        return taskInfo.displayAreaFeatureId == FEATURE_DEFAULT_TASK_CONTAINER
                && taskInfo.userId == ActivityManager.getCurrentUser()
                && !mTaskCategoryManager.shouldIgnoreOpeningForegroundDA(taskInfo);
    }

    private void cacheTask(ActivityManager.RunningTaskInfo taskInfo) {
        logIfDebuggable("Caching the task: " + taskInfo.taskId);
        if (mTaskInfoCache.cancelTask(taskInfo)) {
            boolean cached = mTaskInfoCache.cacheTask(taskInfo);
            logIfDebuggable("Task " + taskInfo.taskId + " is cached = " + cached);
        }
    }

    private void setHomeScreenBottomPadding(int bottomPadding) {
        // Set padding instead of margin so the bottom area shows background of
        // car_ui_portrait_launcher during immersive mode without nav bar, and panel states are
        // calculated correctly.
        mContainer.setPadding(/* left= */ 0, /* top= */ 0, /* right= */0, bottomPadding);
    }

    // TODO(b/275633095): Add test to verify the region is set correctly in each mode
    private void updateObscuredTouchRegion() {
        if (mBackgroundTaskView == null) {
            return;
        }
        Rect controlBarRect = new Rect();
        mControlBarView.getBoundsOnScreen(controlBarRect);

        Rect appPanelGripBarRect = new Rect();
        mAppGridTaskViewPanel.getGripBarBounds(appPanelGripBarRect);

        Rect rootPanelGripBarRect = new Rect();
        mRootTaskViewPanel.getGripBarBounds(rootPanelGripBarRect);

        Rect navigationBarRect = new Rect(controlBarRect.left, controlBarRect.bottom,
                controlBarRect.right, mContainer.getMeasuredHeight());

        Rect statusBarRect = new Rect(controlBarRect.left, /* top= */ 0, controlBarRect.right,
                mStatusBarHeight);

        Region obscuredTouchRegion = new Region();
        obscuredTouchRegion.union(controlBarRect);
        obscuredTouchRegion.union(navigationBarRect);

        mRootTaskViewPanel.setObscuredTouchRegion(obscuredTouchRegion);
        mAppGridTaskViewPanel.setObscuredTouchRegion(obscuredTouchRegion);
        obscuredTouchRegion.union(appPanelGripBarRect);
        obscuredTouchRegion.union(rootPanelGripBarRect);
        obscuredTouchRegion.union(statusBarRect);
        mBackgroundTaskView.setObscuredTouchRegion(obscuredTouchRegion);
        mFullScreenTaskView.setObscuredTouchRegion(obscuredTouchRegion);
    }

    private void updateBackgroundTaskViewInsets() {
        if (mBackgroundTaskView == null) {
            return;
        }

        int bottomOverlap = Math.min(mControlBarView.getTop(),
                mRootTaskViewPanel.getTop());
        bottomOverlap = Math.min(bottomOverlap, mAppGridTaskViewPanel.getTop());

        Rect appAreaBounds = new Rect();
        mBackgroundTaskView.getBoundsOnScreen(appAreaBounds);

        Rect bottomInsets = new Rect(appAreaBounds.left, bottomOverlap,
                appAreaBounds.right, appAreaBounds.bottom);
        Rect topInsets = new Rect(appAreaBounds.left, appAreaBounds.top, appAreaBounds.right,
                mStatusBarHeight);

        logIfDebuggable("Applying inset to backgroundTaskView bottom: " + bottomInsets + " top: "
                + topInsets);
        mBackgroundTaskView.addInsets(
                /* index= */ 0, WindowInsets.Type.systemOverlays(), bottomInsets);
        mBackgroundTaskView.addInsets(
                /* index= */ 1, WindowInsets.Type.systemOverlays(), topInsets);
    }

    private void updateFullScreenTaskViewInsets() {
        if (mFullScreenTaskView == null) {
            return;
        }

        Rect appAreaBounds = new Rect();
        mFullScreenTaskView.getBoundsOnScreen(appAreaBounds);

        Rect bottomInsets = new Rect(appAreaBounds.left, appAreaBounds.height(),
                appAreaBounds.right, appAreaBounds.bottom);

        Rect topInsets = new Rect(appAreaBounds.left, appAreaBounds.top, appAreaBounds.right,
                mStatusBarHeight);

        logIfDebuggable("Applying inset to fullScreenTaskView bottom: " + bottomInsets + " top: "
                + topInsets);
        mFullScreenTaskView.addInsets(
                /* index= */ 0, WindowInsets.Type.systemOverlays(), bottomInsets);
        mFullScreenTaskView.addInsets(
                /* index= */ 1, WindowInsets.Type.systemOverlays(), topInsets);
    }

    private void updateTaskViewInsets() {
        Insets insets = Insets.of(/* left= */ 0, /* top= */ 0, /* right= */ 0,
                mControlBarView.getHeight() + mNavBarHeight);

        if (mRootTaskViewPanel != null) {
            mRootTaskViewPanel.setInsets(insets);
        }
        if (mAppGridTaskViewPanel != null) {
            mAppGridTaskViewPanel.setInsets(insets);
        }
    }

    private void onContainerDimensionsChanged() {
        if (mRootTaskViewPanel != null) {
            mRootTaskViewPanel.onParentDimensionChanged();
        }

        if (mAppGridTaskViewPanel != null) {
            mAppGridTaskViewPanel.onParentDimensionChanged();
        }

        updateTaskViewInsets();

        if (mBackgroundTaskView != null) {
            mBackgroundTaskView.post(() -> mBackgroundTaskView.onLocationChanged());
        }
    }

    private void setUpBackgroundTaskView() {
        ViewGroup parent = findViewById(R.id.background_app_area);

        Intent backgroundIntent = mTaskCategoryManager.getCurrentBackgroundApp() == null
                ? CarLauncherUtils.getMapsIntent(getApplicationContext())
                : (new Intent()).setComponent(mTaskCategoryManager.getCurrentBackgroundApp());

        mTaskViewManager.createControlledCarTaskView(getMainExecutor(),
                ControlledCarTaskViewConfig.builder()
                        .setActivityIntent(backgroundIntent)
                        .setAutoRestartOnCrash(/* autoRestartOnCrash- */ true)
                        .build(),
                new ControlledCarTaskViewCallbacks() {
                    @Override
                    public void onTaskViewCreated(CarTaskView taskView) {
                        logIfDebuggable("Background Task View is created with component = "
                                + backgroundIntent.getComponent());
                        taskView.setZOrderOnTop(false);
                        mBackgroundTaskView = taskView;
                        parent.addView(mBackgroundTaskView);
                        // Set the background app here to avoid recreating
                        // CarUiPortraitHomeScreen in onTaskCreated
                        mTaskCategoryManager.setCurrentBackgroundApp(
                                backgroundIntent.getComponent());
                    }

                    @Override
                    public void onTaskViewReady() {
                        logIfDebuggable("Background Task View is ready");
                        mIsBackgroundTaskViewReady = true;
                        onTaskViewReadinessUpdated();
                        updateBackgroundTaskViewInsets();
                    }
                }
        );
    }

    private void setControlBarVisibility(boolean isVisible, boolean animate) {
        float translationY = isVisible ? 0 : mContainer.getHeight() - mControlBarView.getTop();
        if (animate) {
            mControlBarView.animate().translationY(translationY).withEndAction(() -> {
                updateObscuredTouchRegion();
            });
        } else {
            mControlBarView.setTranslationY(translationY);
            updateObscuredTouchRegion();
        }

    }

    private void setUpAppGridTaskView() {
        mAppGridTaskViewPanel.setTag("AppGridPanel");
        mTaskViewManager.createControlledCarTaskView(getMainExecutor(),
                ControlledCarTaskViewConfig.builder()
                        .setActivityIntent(CarLauncherUtils.getAppsGridIntent())
                        .setAutoRestartOnCrash(/* autoRestartOnCrash= */ true)
                        .build(),
                new ControlledCarTaskViewCallbacks() {
                    @Override
                    public void onTaskViewCreated(CarTaskView taskView) {
                        taskView.setZOrderOnTop(false);
                        mAppGridTaskViewPanel.setTaskView(taskView);
                    }

                    @Override
                    public void onTaskViewReady() {
                        logIfDebuggable("App grid Task View is ready");
                        mAppGridTaskViewPanel.setReady(true);
                        onTaskViewReadinessUpdated();
                    }
                }
        );

        mAppGridTaskViewPanel.setOnStateChangeListener(new TaskViewPanel.OnStateChangeListener() {
            @Override
            public void onStateChangeStart(TaskViewPanel.State oldState,
                    TaskViewPanel.State newState, boolean animated) {
                boolean isVisible = newState.isVisible();
                notifySystemUI(MSG_APP_GRID_VISIBILITY_CHANGE, boolToInt(isVisible));
            }

            @Override
            public void onStateChangeEnd(TaskViewPanel.State oldState,
                    TaskViewPanel.State newState, boolean animated) {
                updateObscuredTouchRegion();
                updateBackgroundTaskViewInsets();
                if (!newState.isVisible() && !mRootTaskViewPanel.isVisible()) {
                    setFocusToBackgroundApp();
                }
            }
        });
    }

    private boolean isAllTaskViewsReady() {
        return mRootTaskViewPanel.isReady() && mAppGridTaskViewPanel.isReady()
                && mIsBackgroundTaskViewReady && mIsFullScreenTaskViewReady;
    }

    private void onTaskViewReadinessUpdated() {
        if (!isAllTaskViewsReady()) {
            return;
        }
        logIfDebuggable("All task views are ready");
        updateObscuredTouchRegion();
        updateBackgroundTaskViewInsets();
        notifySystemUI(MSG_FG_TASK_VIEW_READY, boolToInt(true));
        Rect controlBarBounds = new Rect();
        mControlBarView.getBoundsOnScreen(controlBarBounds);
        boolean isControlBarVisible = mControlBarView.getVisibility() == View.VISIBLE;
        logIfDebuggable("Control bar:"
                + "( visible: " + isControlBarVisible
                + ", bounds:" + controlBarBounds
                + ")");
    }

    private void setUpRootTaskView() {
        mRootTaskViewPanel.setTag("RootPanel");
        mRootTaskViewPanel.setTaskViewBackgroundColor(Color.BLACK);
        mRootTaskViewPanel.setOnStateChangeListener(new TaskViewPanel.OnStateChangeListener() {
            @Override
            public void onStateChangeStart(TaskViewPanel.State oldState,
                    TaskViewPanel.State newState, boolean animated) {
                boolean isFullScreen = newState.isFullScreen();
                if (isFullScreen) {
                    setHomeScreenBottomPadding(mIsSUWInProgress ? 0 : mNavBarHeight);
                    if (!mIsSUWInProgress) {
                        notifySystemUI(MSG_HIDE_SYSTEM_BAR_FOR_IMMERSIVE, boolToInt(isFullScreen));
                    }
                } else {
                    setControlBarVisibility(/* isVisible= */ true, animated);
                }

                boolean isVisible = newState.isVisible();
                // Deselect the app grid button as soon as we know the root task view panel will
                // be shown on top.
                if (isVisible) {
                    notifySystemUI(MSG_APP_GRID_VISIBILITY_CHANGE, boolToInt(false));
                }

                // Update the notification button's selection state.
                if (mIsNotificationCenterOnTop && isVisible) {
                    notifySystemUI(MSG_NOTIFICATIONS_VISIBILITY_CHANGE, boolToInt(true));
                } else {
                    notifySystemUI(MSG_NOTIFICATIONS_VISIBILITY_CHANGE, boolToInt(false));
                }

                // Update the Recents button's selection state.
                if (mIsRecentsOnTop && isVisible) {
                    notifySystemUI(MSG_RECENTS_VISIBILITY_CHANGE, boolToInt(true));
                } else {
                    notifySystemUI(MSG_RECENTS_VISIBILITY_CHANGE, boolToInt(false));
                }
            }

            @Override
            public void onStateChangeEnd(TaskViewPanel.State oldState,
                    TaskViewPanel.State newState, boolean animated) {
                updateObscuredTouchRegion();

                // Hide the control bar after the animation if in full screen.
                if (newState.isFullScreen()) {
                    setControlBarVisibility(/* isVisible= */ false, animated);
                } else {
                    // Adjust the bottom margin to count for the nav bar.
                    setHomeScreenBottomPadding(mNavBarHeight);
                    // Update the background task view insets to make sure their content is not
                    // covered with our panels. We only need to do this when we are not in
                    // fullscreen.
                    updateBackgroundTaskViewInsets();
                    // Show the nav bar if not showing Setup Wizard
                    if (!mIsSUWInProgress) {
                        notifySystemUI(MSG_HIDE_SYSTEM_BAR_FOR_IMMERSIVE,
                                boolToInt(newState.isFullScreen()));
                    }
                }

                // Hide the app grid task view behind the root task view.
                if (newState.isVisible()) {
                    mAppGridTaskViewPanel.closePanel(/* animated = */ false);
                } else {
                    // Launch a blank activity to move the top activity to background.
                    startActivity(BlankActivity.createIntent(getApplicationContext()));
                }
            }
        });

        mTaskViewManager.createLaunchRootTaskView(getMainExecutor(),
                new LaunchRootCarTaskViewCallbacks() {
                    @Override
                    public void onTaskViewCreated(CarTaskView taskView) {
                        logIfDebuggable("Root Task View is created");
                        taskView.setZOrderMediaOverlay(true);
                        mRootTaskViewPanel.setTaskView(taskView);
                        mRootTaskViewPanel.setToolBarCallback(() -> sendVirtualBackPress());
                    }

                    @Override
                    public void onTaskViewReady() {
                        logIfDebuggable("Root Task View is ready");
                        mRootTaskViewPanel.setReady(true);
                        mTaskInfoCache.startCachedTasks();
                        onTaskViewReadinessUpdated();

                        setUpBackgroundTaskView();
                        setUpAppGridTaskView();
                        setUpFullScreenTaskView();
                    }
                });
    }

    /**
     * Send both action down and up to be qualified as a back press. Set time for key events, so
     * they are not staled.
     */
    private void sendVirtualBackPress() {
        long downEventTime = SystemClock.uptimeMillis();
        long upEventTime = downEventTime + 1;

        final KeyEvent keydown = new KeyEvent(downEventTime, downEventTime, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_BACK, /* repeat= */ 0, /* metaState= */ 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, /* scancode= */ 0, KeyEvent.FLAG_FROM_SYSTEM);
        final KeyEvent keyup = new KeyEvent(upEventTime, upEventTime, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_BACK, /* repeat= */ 0, /* metaState= */ 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, /* scancode= */ 0, KeyEvent.FLAG_FROM_SYSTEM);

        mInputManagerGlobal.injectInputEvent(keydown, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        mInputManagerGlobal.injectInputEvent(keyup, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    private void setUpFullScreenTaskView() {
        ViewGroup parent = findViewById(R.id.fullscreen_container);
        mTaskViewManager.createSemiControlledTaskView(getMainExecutor(),
                mTaskCategoryManager.getFullScreenActivities().stream().toList(),
                new SemiControlledCarTaskViewCallbacks() {
                    @Override
                    public void onTaskViewCreated(CarTaskView taskView) {
                        logIfDebuggable("FullScreen Task View is created");
                        mFullScreenTaskView = taskView;
                        mFullScreenTaskView.setZOrderOnTop(true);
                        parent.addView(mFullScreenTaskView);
                    }

                    @Override
                    public void onTaskViewReady() {
                        logIfDebuggable("FullScreen Task View is ready");
                        mIsFullScreenTaskViewReady = true;
                        onTaskViewReadinessUpdated();
                        updateFullScreenTaskViewInsets();
                    }
                });
    }

    private void onImmersiveModeRequested(boolean requested, boolean animate) {
        logIfDebuggable("onImmersiveModeRequested = " + requested);
        if (requested && (!mCarUiPortraitDriveStateController.isDrivingStateMoving()
                || mIsSUWInProgress)) {
            int bottomAdjustment = mIsSUWInProgress ? 0 : mNavBarHeight;
            mRootTaskViewPanel.openFullScreenPanel(animate, !mIsSUWInProgress, bottomAdjustment);
        } else {
            if (mTaskViewManager.getRootTaskCount() > 0) {
                mRootTaskViewPanel.openPanel(animate);
            } else {
                // Don't animate if there is no task in the panel.
                mRootTaskViewPanel.closePanel(/* animated = */ false);
            }
        }
    }

    /**
     * Handler of incoming messages from service.
     */
    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SYSUI_STARTED:
                    // This is to ensure that Homescreen is created each time the sysUI is ready.
                    // This is needed to re-register TaskOrganizer after SysUI b/274834061
                    recreate();
                    break;
                case MSG_IMMERSIVE_MODE_REQUESTED:
                    onImmersiveModeRequested(intToBool(msg.arg1), /* animate = */ true);
                    break;
                case MSG_SUW_IN_PROGRESS:
                    mIsSUWInProgress = intToBool(msg.arg1);
                    logIfDebuggable("Get intent about the SUW is " + mIsSUWInProgress);
                    onImmersiveModeRequested(mIsSUWInProgress, /* animate = */ false);
                    break;
                case MSG_IMMERSIVE_MODE_CHANGE:
                    boolean hideNavBar = intToBool(msg.arg1);
                    mRootTaskViewPanel.setToolBarViewVisibility(hideNavBar);
                    break;
                case MSG_COLLAPSE_NOTIFICATION:
                    collapseNotificationPanel();
                    break;
                case MSG_COLLAPSE_RECENTS:
                    collapseRecentsPanel();
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    void doBindService() {
        // Establish a connection with {@link CarUiPortraitService}. We use an explicit class
        // name because there is no reason to be able to let other applications replace our
        // component.
        bindService(new Intent(this, CarUiPortraitService.class), mConnection,
                Context.BIND_AUTO_CREATE);
        mIsBound = true;
    }

    void doUnbindService() {
        if (mIsBound) {
            if (mService != null) {
                try {
                    Message msg = Message.obtain(null, MSG_UNREGISTER_CLIENT);
                    msg.replyTo = mMessenger;
                    mService.send(msg);
                } catch (RemoteException e) {
                    Log.w(TAG, "can't unregister to CarUiPortraitService: ", e);
                }
            }

            // Detach our existing connection.
            unbindService(mConnection);
            mIsBound = false;
        }
    }

    private void notifySystemUI(int key, int value) {
        Message msg = Message.obtain(null, key, value, 0);
        notifySystemUI(msg);
    }

    private void notifySystemUI(Message msg) {
        try {
            if (mService != null) {
                mService.send(msg);
            } else {
                logIfDebuggable("Service is not connected yet! Caching the message:" + msg);
                mMessageCache.add(msg);
            }
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private static int boolToInt(Boolean b) {
        return b ? 1 : 0;
    }

    private static boolean intToBool(int val) {
        return val == 1;
    }
}
