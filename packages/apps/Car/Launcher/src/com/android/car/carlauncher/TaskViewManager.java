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

package com.android.car.carlauncher;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED;

import static com.android.car.carlauncher.CarLauncher.TAG;
import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_FULLSCREEN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.Application.ActivityLifecycleCallbacks;
import android.app.TaskInfo;
import android.app.TaskStackListener;
import android.car.Car;
import android.car.app.CarActivityManager;
import android.car.user.CarUserManager;
import android.car.user.UserLifecycleEventFilter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserManager;
import android.util.Log;
import android.util.Slog;
import android.view.WindowManagerGlobal;
import android.window.TaskAppearedInfo;
import android.window.WindowContainerTransaction;

import com.android.car.carlauncher.taskstack.TaskStackChangeListeners;
import com.android.internal.annotations.VisibleForTesting;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.HandlerExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.fullscreen.FullscreenTaskListener;
import com.android.wm.shell.startingsurface.StartingWindowController;
import com.android.wm.shell.startingsurface.phone.PhoneStartingWindowTypeAlgorithm;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.taskview.TaskViewTransitions;
import com.android.wm.shell.transition.Transitions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;


/**
 * A manager for creating {@link ControlledCarTaskView}, {@link LaunchRootCarTaskView} &
 * {@link SemiControlledCarTaskView}.
 */
public final class TaskViewManager {
    static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String SCHEME_PACKAGE = "package";

    private final AtomicReference<CarActivityManager> mCarActivityManagerRef =
            new AtomicReference<>();
    @ShellMainThread
    private final HandlerExecutor mShellExecutor;
    private final SyncTransactionQueue mSyncQueue;
    private final Transitions mTransitions;
    private final TaskViewTransitions mTaskViewTransitions;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final int mHostTaskId;

    // All TaskView are bound to the Host Activity if it exists.
    @ShellMainThread
    private final List<ControlledCarTaskView> mControlledTaskViews = new ArrayList<>();
    @ShellMainThread
    private final List<SemiControlledCarTaskView> mSemiControlledTaskViews = new ArrayList<>();
    @ShellMainThread
    private LaunchRootCarTaskView mLaunchRootCarTaskView = null;

    private TaskViewInputInterceptor mTaskViewInputInterceptor;
    private CarUserManager mCarUserManager;
    private Activity mContext;
    private Car mCar;

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) {
            boolean hostFocused = taskId == mHostTaskId && focused;
            if (DBG) {
                Log.d(TAG, "onTaskFocusChanged: taskId=" + taskId
                        + ", hostFocused=" + hostFocused);
            }
            if (!hostFocused) {
                return;
            }

            for (int i = mControlledTaskViews.size() - 1; i >= 0; --i) {
                ControlledCarTaskView taskView = mControlledTaskViews.get(i);
                if (taskView.getTaskId() == INVALID_TASK_ID) {
                    // If the task in TaskView is crashed when host is in background,
                    // We'd like to restart it when host becomes foreground and focused.
                    taskView.startActivity();
                }
            }
        }

        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
            if (DBG) {
                Log.d(TAG, "onActivityRestartAttempt: taskId=" + task.taskId
                        + ", homeTaskVisible=" + homeTaskVisible + ", wasVisible=" + wasVisible);
            }
            if (mHostTaskId != task.taskId) {
                return;
            }
            showEmbeddedTasks();
        }
    };

    private final CarUserManager.UserLifecycleListener mUserLifecycleListener = event -> {
        if (DBG) {
            Log.d(TAG, "UserLifecycleListener.onEvent: For User "
                    + mContext.getUserId()
                    + ", received an event " + event);
        }

        // When user-unlocked, if task isn't launched yet, then try to start it.
        if (event.getEventType() == USER_LIFECYCLE_EVENT_TYPE_UNLOCKED
                && mContext.getUserId() == event.getUserId()) {
            for (int i = mControlledTaskViews.size() - 1; i >= 0; --i) {
                ControlledCarTaskView taskView = mControlledTaskViews.get(i);
                if (taskView.getTaskId() == INVALID_TASK_ID) {
                    taskView.startActivity();
                }
            }
        }

        // When user-switching, onDestroy in the previous user's Host app isn't called.
        // So try to release the resource explicitly.
        if (event.getEventType() == USER_LIFECYCLE_EVENT_TYPE_SWITCHING
                && mContext.getUserId() == event.getPreviousUserId()) {
            release();
        }
    };

    private final BroadcastReceiver mPackageBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DBG) Log.d(TAG, "onReceive: intent=" + intent);

            if (!isHostVisible()) {
                return;
            }

            String packageName = intent.getData().getSchemeSpecificPart();
            for (int i = mControlledTaskViews.size() - 1; i >= 0; --i) {
                ControlledCarTaskView taskView = mControlledTaskViews.get(i);
                if (taskView.getTaskId() == INVALID_TASK_ID
                        && taskView.getDependingPackageNames().contains(packageName)) {
                    taskView.startActivity();
                }
            }
        }
    };

    public TaskViewManager(Activity context, Handler mainHandler) {
        this(context, mainHandler, new HandlerExecutor(mainHandler));
    }

    private TaskViewManager(Activity context, Handler mainHandler,
            HandlerExecutor handlerExecutor) {
        this(context, mainHandler, handlerExecutor, new ShellTaskOrganizer(handlerExecutor),
                new TransactionPool(), new ShellCommandHandler(), new ShellInit(handlerExecutor));
    }

    private TaskViewManager(Activity context, Handler mainHandler, HandlerExecutor handlerExecutor,
            ShellTaskOrganizer taskOrganizer, TransactionPool transactionPool,
            ShellCommandHandler shellCommandHandler, ShellInit shellinit) {
        this(context, mainHandler, handlerExecutor, taskOrganizer,
                transactionPool,
                shellinit,
                new ShellController(context, shellinit, shellCommandHandler, handlerExecutor),
                new DisplayController(context,
                        WindowManagerGlobal.getWindowManagerService(), shellinit, handlerExecutor)
        );
    }

    private TaskViewManager(Activity context, Handler mainHandler, HandlerExecutor handlerExecutor,
            ShellTaskOrganizer taskOrganizer, TransactionPool transactionPool, ShellInit shellinit,
            ShellController shellController, DisplayController dc) {
        this(context, handlerExecutor, taskOrganizer,
                new SyncTransactionQueue(transactionPool, handlerExecutor),
                new Transitions(context, shellinit, shellController, taskOrganizer,
                        transactionPool, dc, handlerExecutor, mainHandler, handlerExecutor),
                shellinit,
                shellController,
                new StartingWindowController(context, shellinit,
                        shellController,
                        taskOrganizer,
                        handlerExecutor,
                        new PhoneStartingWindowTypeAlgorithm(),
                        new IconProvider(context),
                        transactionPool));
    }

    @VisibleForTesting
    TaskViewManager(Activity context, HandlerExecutor handlerExecutor,
            ShellTaskOrganizer shellTaskOrganizer, SyncTransactionQueue syncQueue,
            Transitions transitions, ShellInit shellInit, ShellController shellController,
            StartingWindowController startingWindowController) {
        if (DBG) Slog.d(TAG, "TaskViewManager(): " + context);
        mContext = context;
        mShellExecutor = handlerExecutor;
        mTaskOrganizer = shellTaskOrganizer;
        mHostTaskId = mContext.getTaskId();
        mSyncQueue = syncQueue;
        mTransitions = transitions;
        mTaskViewTransitions = new TaskViewTransitions(mTransitions);
        mTaskViewInputInterceptor = new TaskViewInputInterceptor(context, this);

        initCar();
        shellInit.init();
        initTaskOrganizer(mCarActivityManagerRef);
        mContext.registerActivityLifecycleCallbacks(mActivityLifecycleCallbacks);
    }

    private void initCar() {
        mCar = Car.createCar(/* context= */ mContext, /* handler= */ null,
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
                    mCarUserManager.addListener(mContext.getMainExecutor(), filter,
                            mUserLifecycleListener);
                    CarActivityManager carAM = (CarActivityManager) car.getCarManager(
                            Car.CAR_ACTIVITY_SERVICE);
                    mCarActivityManagerRef.set(carAM);

                    carAM.registerTaskMonitor();
                });

        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackListener);

        IntentFilter packageIntentFilter = new IntentFilter(Intent.ACTION_PACKAGE_REPLACED);
        packageIntentFilter.addDataScheme(SCHEME_PACKAGE);
        mContext.registerReceiver(mPackageBroadcastReceiver, packageIntentFilter);
    }

    // TODO(b/239958124A): Remove this method when unit tests for TaskViewManager have been added.
    /**
     * This method only exists for the container activity to set mock car user manager in tests.
     */
    void setCarUserManager(CarUserManager carUserManager) {
        mCarUserManager = carUserManager;
    }

    private Transitions initTransitions(ShellInit shellInit, TransactionPool txPool,
            ShellController shellController, Handler mainHandler) {
        DisplayController dc = new DisplayController(mContext,
                WindowManagerGlobal.getWindowManagerService(), shellInit, mShellExecutor);
        return new Transitions(mContext, shellInit, shellController, mTaskOrganizer,
                txPool, dc, mShellExecutor, mainHandler, mShellExecutor);
    }

    private void initTaskOrganizer(AtomicReference<CarActivityManager> carActivityManagerRef) {
        FullscreenTaskListener fullscreenTaskListener = new CarFullscreenTaskMonitorListener(
                carActivityManagerRef, mSyncQueue);
        mTaskOrganizer.addListenerForType(fullscreenTaskListener, TASK_LISTENER_TYPE_FULLSCREEN);
        List<TaskAppearedInfo> taskAppearedInfos = mTaskOrganizer.registerOrganizer();
        cleanUpExistingTaskViewTasks(taskAppearedInfos);
    }

    /**
     * Creates a {@link ControlledCarTaskView}.
     *
     * @param callbackExecutor the executor which the {@link ControlledCarTaskViewCallbacks} will
     *                         be executed on.
     * @param controlledCarTaskViewConfig the configuration for the underlying
     * {@link ControlledCarTaskView}.
     * @param taskViewCallbacks the callbacks for the underlying TaskView.
     */
    public void createControlledCarTaskView(
            Executor callbackExecutor,
            ControlledCarTaskViewConfig controlledCarTaskViewConfig,
            ControlledCarTaskViewCallbacks taskViewCallbacks) {
        mShellExecutor.execute(() -> {
            ControlledCarTaskView taskView = new ControlledCarTaskView(mContext, mTaskOrganizer,
                    mTaskViewTransitions, mSyncQueue, callbackExecutor, controlledCarTaskViewConfig,
                    taskViewCallbacks, mContext.getSystemService(UserManager.class), this);
            mControlledTaskViews.add(taskView);

            if (controlledCarTaskViewConfig.mCaptureGestures
                    || controlledCarTaskViewConfig.mCaptureLongPress) {
                mTaskViewInputInterceptor.init();
            }
        });

    }

    /**
     * Creates a {@link LaunchRootCarTaskView}.
     *
     * @param callbackExecutor the executor which the {@link LaunchRootCarTaskViewCallbacks} will be
     *                         executed on.
     * @param taskViewCallbacks the callbacks for the underlying TaskView.
     */
    public void createLaunchRootTaskView(Executor callbackExecutor,
            LaunchRootCarTaskViewCallbacks taskViewCallbacks) {
        mShellExecutor.execute(() -> {
            if (mLaunchRootCarTaskView != null) {
                throw new IllegalStateException("Cannot create more than one launch root task");
            }
            mLaunchRootCarTaskView = new LaunchRootCarTaskView(mContext, mTaskOrganizer,
                    mTaskViewTransitions, mSyncQueue,
                    callbackExecutor, taskViewCallbacks, mCarActivityManagerRef);
        });
    }

    /**
     * Creates a {@link SemiControlledCarTaskView}.
     *
     * @param callbackExecutor the executor which the {@link SemiControlledCarTaskViewCallbacks}
     *                         will be executed on.
     * @param allowListedActivities the list of activities that will always be started in this
     *                              taskview.
     * @param taskViewCallbacks the callbacks for the underlying TaskView.
     */
    public void createSemiControlledTaskView(Executor callbackExecutor,
            List<ComponentName> allowListedActivities,
            SemiControlledCarTaskViewCallbacks taskViewCallbacks) {
        mShellExecutor.execute(() -> {
            SemiControlledCarTaskView taskView = new SemiControlledCarTaskView(mContext,
                    mTaskOrganizer, mTaskViewTransitions, mSyncQueue,
                    callbackExecutor, allowListedActivities, taskViewCallbacks,
                    mCarActivityManagerRef);
            mSemiControlledTaskViews.add(taskView);
        });
    }

    /**
     * Releases {@link TaskViewManager} and unregisters the underlying {@link ShellTaskOrganizer}.
     * It also removes all TaskViews which are created by this {@link TaskViewManager}.
     */
    private void release() {
        mShellExecutor.execute(() -> {
            if (DBG) Slog.d(TAG, "TaskViewManager.release");

            if (mCarUserManager != null) {
                mCarUserManager.removeListener(mUserLifecycleListener);
            }
            TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskStackListener);
            mContext.unregisterReceiver(mPackageBroadcastReceiver);

            CarActivityManager carAM = mCarActivityManagerRef.get();
            if (carAM != null) {
                carAM.unregisterTaskMonitor();
                mCarActivityManagerRef.set(null);
            }

            for (int i = mControlledTaskViews.size() - 1; i >= 0; --i) {
                mControlledTaskViews.get(i).release();
            }
            mControlledTaskViews.clear();

            for (int i = mSemiControlledTaskViews.size() - 1; i >= 0; --i) {
                mSemiControlledTaskViews.get(i).release();
            }
            mSemiControlledTaskViews.clear();

            if (mLaunchRootCarTaskView != null) {
                mLaunchRootCarTaskView.release();
                mLaunchRootCarTaskView = null;
            }

            mContext.unregisterActivityLifecycleCallbacks(mActivityLifecycleCallbacks);
            mTaskOrganizer.unregisterOrganizer();
            mTaskViewInputInterceptor.release();

            if (mCar != null) {
                mCar.disconnect();
            }
        });
    }

    /**
     * Shows all the embedded tasks. If the tasks are
     */
    public void showEmbeddedTasks() {
        WindowContainerTransaction wct = new WindowContainerTransaction();
        for (int i = mControlledTaskViews.size() - 1; i >= 0; --i) {
            // showEmbeddedTasks() will restart the crashed tasks too.
            mControlledTaskViews.get(i).showEmbeddedTask(wct);
        }
        if (mLaunchRootCarTaskView != null) {
            mLaunchRootCarTaskView.showEmbeddedTask(wct);
        }
        for (int i = mSemiControlledTaskViews.size() - 1; i >= 0; --i) {
            mSemiControlledTaskViews.get(i).showEmbeddedTask(wct);
        }
        mSyncQueue.queue(wct);
    }

    /**
     * @return {@code true} if the host activity is in resumed or started state, {@code false}
     * otherwise.
     */
    boolean isHostVisible() {
        // This code relies on Activity#isVisibleForAutofill() instead of maintaining a custom
        // activity state.
        return mContext.isVisibleForAutofill();
    }

    private final ActivityLifecycleCallbacks mActivityLifecycleCallbacks =
            new ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(@NonNull Activity activity,
                        @Nullable Bundle savedInstanceState) {}

                @Override
                public void onActivityStarted(@NonNull Activity activity) {}

                @Override
                public void onActivityResumed(@NonNull Activity activity) {}

                @Override
                public void onActivityPaused(@NonNull Activity activity) {}

                @Override
                public void onActivityStopped(@NonNull Activity activity) {}

                @Override
                public void onActivitySaveInstanceState(@NonNull Activity activity,
                        @NonNull Bundle outState) {}

                @Override
                public void onActivityDestroyed(@NonNull Activity activity) {
                    release();
                }
            };

    private static void cleanUpExistingTaskViewTasks(List<TaskAppearedInfo> taskAppearedInfos) {
        ActivityTaskManager atm = ActivityTaskManager.getInstance();
        for (TaskAppearedInfo taskAppearedInfo : taskAppearedInfos) {
            TaskInfo taskInfo = taskAppearedInfo.getTaskInfo();
            // Only TaskView tasks have WINDOWING_MODE_MULTI_WINDOW.
            if (taskInfo.getWindowingMode() == WINDOWING_MODE_MULTI_WINDOW) {
                if (DBG) Slog.d(TAG, "Found the dangling task, removing: " + taskInfo.taskId);
                atm.removeTask(taskInfo.taskId);
            }
        }
    }

    @VisibleForTesting
    List<ControlledCarTaskView> getControlledTaskViews() {
        return mControlledTaskViews;
    }

    @VisibleForTesting
    LaunchRootCarTaskView getLaunchRootCarTaskView() {
        return mLaunchRootCarTaskView;
    }

    @VisibleForTesting
    List<SemiControlledCarTaskView> getSemiControlledTaskViews() {
        return mSemiControlledTaskViews;
    }

    @VisibleForTesting
    BroadcastReceiver getPackageBroadcastReceiver() {
        return mPackageBroadcastReceiver;
    }

    @VisibleForTesting
    /** Only meant for testing, should not be used by real code. */
    void setTaskViewInputInterceptor(TaskViewInputInterceptor taskViewInputInterceptor) {
        mTaskViewInputInterceptor = taskViewInputInterceptor;
    }

    public int getRootTaskCount() {
        return mLaunchRootCarTaskView != null ? mLaunchRootCarTaskView.getRootTaskCount() : 0;
    }

    /**
     * Returns the {@link android.app.ActivityManager.RunningTaskInfo} of the top task inside the
     * launch root car task view.
     */
    @VisibleForTesting
    public ActivityManager.RunningTaskInfo getTopTaskInLaunchRootTask() {
        return mLaunchRootCarTaskView != null
                ? mLaunchRootCarTaskView.getTopTaskInLaunchRootTask() : null;
    }
}
