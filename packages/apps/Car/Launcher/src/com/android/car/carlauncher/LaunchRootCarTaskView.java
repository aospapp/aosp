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

package com.android.car.carlauncher;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.car.carlauncher.TaskViewManager.DBG;

import android.app.Activity;
import android.app.ActivityManager;
import android.car.app.CarActivityManager;
import android.util.Log;
import android.view.SurfaceControl;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.taskview.TaskViewTransitions;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link CarTaskView} that can act as a default app container. A default app container is the
 * container where all apps open by default.
 */
final class LaunchRootCarTaskView extends CarTaskView {
    private static final String TAG = LaunchRootCarTaskView.class.getSimpleName();

    private final Executor mCallbackExecutor;
    private final LaunchRootCarTaskViewCallbacks mCallbacks;
    private final ShellTaskOrganizer mShellTaskOrganizer;
    private final SyncTransactionQueue mSyncQueue;
    // Linked hash map is used to keep the tasks ordered as per the actual stack inside the root
    // task. Whenever a task becomes visible, it is bumped to the top of the stack.
    private final LinkedHashMap<Integer, ActivityManager.RunningTaskInfo> mLaunchRootStack =
            new LinkedHashMap<>();
    private final AtomicReference<CarActivityManager> mCarActivityManagerRef;

    private ActivityManager.RunningTaskInfo mLaunchRootTask;

    private final ShellTaskOrganizer.TaskListener mRootTaskListener =
            new ShellTaskOrganizer.TaskListener() {
                @Override
                public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo,
                        SurfaceControl leash) {
                    // The first call to onTaskAppeared() is always for the root-task.
                    if (mLaunchRootTask == null && !taskInfo.hasParentTask()) {
                        setRootTaskAsLaunchRoot(taskInfo);
                        LaunchRootCarTaskView.this.dispatchTaskAppeared(taskInfo, leash);
                        mCallbackExecutor.execute(() -> mCallbacks.onTaskViewReady());
                        if (DBG) {
                            Log.d(TAG, "got onTaskAppeared for the launch root task. Not "
                                    + "forwarding this to car activity manager");
                        }
                        return;
                    }

                    if (DBG) {
                        Log.d(TAG, "launchRootCarTaskView onTaskAppeared " + taskInfo.taskId
                                + " - " + taskInfo.baseActivity);
                    }

                    // TODO(b/228077499): Fix for the case when a task is started in the
                    // launch-root-task right after the initialization of launch-root-task, it
                    // remains blank.
                    mSyncQueue.runInSync(t -> t.show(leash));

                    CarActivityManager carAm = mCarActivityManagerRef.get();
                    if (carAm != null) {
                        carAm.onTaskAppeared(taskInfo);
                        mLaunchRootStack.put(taskInfo.taskId, taskInfo);
                    } else {
                        Log.w(TAG, "CarActivityManager is null, skip onTaskAppeared: TaskInfo"
                                + " = " + taskInfo);
                    }
                }

                @Override
                public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
                    if (mLaunchRootTask != null
                            && mLaunchRootTask.taskId == taskInfo.taskId) {
                        LaunchRootCarTaskView.this.dispatchTaskInfoChanged(taskInfo);
                        if (DBG) {
                            Log.d(TAG, "got onTaskInfoChanged for the launch root task. Not "
                                    + "forwarding this to car activity manager");
                        }
                        return;
                    }
                    if (DBG) {
                        Log.d(TAG, "launchRootCarTaskView onTaskInfoChanged "
                                + taskInfo.taskId + " - " + taskInfo.baseActivity);
                    }

                    // Uncontrolled apps by default launch in the launch root so nothing needs to
                    // be done here for them.
                    CarActivityManager carAm = mCarActivityManagerRef.get();
                    if (carAm != null) {
                        carAm.onTaskInfoChanged(taskInfo);
                        if (taskInfo.isVisible && mLaunchRootStack.containsKey(taskInfo.taskId)) {
                            // Remove the task and insert again so that it jumps to the end of
                            // the queue.
                            mLaunchRootStack.remove(taskInfo.taskId);
                            mLaunchRootStack.put(taskInfo.taskId, taskInfo);
                        }
                    } else {
                        Log.w(TAG, "CarActivityManager is null, skip onTaskInfoChanged: TaskInfo"
                                + " = " + taskInfo);
                    }
                }

                @Override
                public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
                    if (DBG) {
                        Log.d(TAG, "launchRootCarTaskView onTaskVanished " + taskInfo.taskId
                                + " - " + taskInfo.baseActivity);
                    }
                    if (mLaunchRootTask != null
                            && mLaunchRootTask.taskId == taskInfo.taskId) {
                        LaunchRootCarTaskView.this.dispatchTaskVanished(taskInfo);
                        if (DBG) {
                            Log.d(TAG, "got onTaskVanished for the launch root task. Not "
                                    + "forwarding this to car activity manager");
                        }
                        return;
                    }

                    CarActivityManager carAm = mCarActivityManagerRef.get();
                    if (carAm != null) {
                        carAm.onTaskVanished(taskInfo);
                        if (mLaunchRootStack.containsKey(taskInfo.taskId)) {
                            mLaunchRootStack.remove(taskInfo.taskId);
                        }
                    } else {
                        Log.w(TAG, "CarActivityManager is null, skip onTaskAppeared: TaskInfo"
                                + " = " + taskInfo);
                    }
                }

                @Override
                public void onBackPressedOnTaskRoot(ActivityManager.RunningTaskInfo taskInfo) {
                    if (mLaunchRootStack.size() == 1) {
                        Log.d(TAG, "Cannot remove last task from launch root.");
                        return;
                    }
                    if (mLaunchRootStack.size() == 0) {
                        Log.d(TAG, "Launch root is empty, do nothing.");
                        return;
                    }

                    ActivityManager.RunningTaskInfo topTask = getTopTaskInLaunchRootTask();
                    WindowContainerTransaction wct = new WindowContainerTransaction();
                    // removeTask() will trigger onTaskVanished which will remove the task locally
                    // from mLaunchRootStack
                    wct.removeTask(topTask.token);
                    mSyncQueue.queue(wct);
                }
            };

    public LaunchRootCarTaskView(Activity context,
            ShellTaskOrganizer organizer,
            TaskViewTransitions taskViewTransitions,
            SyncTransactionQueue syncQueue,
            Executor callbackExecutor,
            LaunchRootCarTaskViewCallbacks callbacks,
            AtomicReference<CarActivityManager> carActivityManager) {
        super(context, organizer, taskViewTransitions, syncQueue);
        mCallbacks = callbacks;
        mCallbackExecutor = callbackExecutor;
        mShellTaskOrganizer = organizer;
        mSyncQueue = syncQueue;
        mCarActivityManagerRef = carActivityManager;

        mCallbackExecutor.execute(() -> mCallbacks.onTaskViewCreated(this));
    }

    @Override
    protected void onCarTaskViewInitialized() {
        super.onCarTaskViewInitialized();
        mShellTaskOrganizer.getExecutor().execute(() -> {
            // removeWithTaskOrganizer should be true to signal the system that this root task is
            // inside a TaskView and should not be animated by the core.
            mShellTaskOrganizer.createRootTask(DEFAULT_DISPLAY,
                    WINDOWING_MODE_MULTI_WINDOW,
                    mRootTaskListener, /* removeWithTaskOrganizer= */ true);
        });
    }

    @Override
    public void release() {
        super.release();
        clearLaunchRootTask();
    }

    private void clearLaunchRootTask() {
        if (mLaunchRootTask == null) {
            Log.w(TAG, "Unable to clear launch root task because it is not created.");
            return;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setLaunchRoot(mLaunchRootTask.token, null, null);
        mSyncQueue.queue(wct);
        // Should run on shell's executor
        mShellTaskOrganizer.deleteRootTask(mLaunchRootTask.token);
        mLaunchRootTask = null;
    }

    private void setRootTaskAsLaunchRoot(ActivityManager.RunningTaskInfo taskInfo) {
        mLaunchRootTask = taskInfo;
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setLaunchRoot(taskInfo.token,
                        new int[]{WINDOWING_MODE_FULLSCREEN, WINDOWING_MODE_UNDEFINED},
                        new int[]{ACTIVITY_TYPE_STANDARD, ACTIVITY_TYPE_RECENTS})
                .reorder(taskInfo.token, true);
        mSyncQueue.queue(wct);
    }

    public int getRootTaskCount() {
        return mLaunchRootStack.size();
    }

    /**
     * Returns the {@link android.app.ActivityManager.RunningTaskInfo} of the top task inside the
     * launch root car task view.
     */
    @VisibleForTesting
    public ActivityManager.RunningTaskInfo getTopTaskInLaunchRootTask() {
        if (mLaunchRootStack.isEmpty()) {
            return null;
        }
        ActivityManager.RunningTaskInfo topTask = null;
        Iterator<ActivityManager.RunningTaskInfo> iterator = mLaunchRootStack.values().iterator();
        while (iterator.hasNext()) {
            topTask = iterator.next();
        }
        return topTask;
    }
}
