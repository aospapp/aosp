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

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import static com.android.car.carlauncher.CarLauncher.TAG;
import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_FULLSCREEN;

import android.annotation.UiContext;
import android.app.ActivityTaskManager;
import android.app.TaskInfo;
import android.car.app.CarActivityManager;
import android.content.Context;
import android.util.Slog;
import android.window.TaskAppearedInfo;

import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.TaskView;
import com.android.wm.shell.common.HandlerExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.fullscreen.FullscreenTaskListener;
import com.android.wm.shell.startingsurface.StartingWindowController;
import com.android.wm.shell.startingsurface.phone.PhoneStartingWindowTypeAlgorithm;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class TaskViewManager {
    private static final boolean DBG = false;

    private final Context mContext;
    private final HandlerExecutor mExecutor;
    private final SyncTransactionQueue mSyncQueue;
    private final ShellTaskOrganizer mTaskOrganizer;

    public TaskViewManager(@UiContext Context context, HandlerExecutor handlerExecutor,
            AtomicReference<CarActivityManager> carActivityManagerRef) {
        mContext = context;
        mExecutor = handlerExecutor;
        mTaskOrganizer = new ShellTaskOrganizer(mExecutor, mContext);
        TransactionPool transactionPool = new TransactionPool();
        mSyncQueue = new SyncTransactionQueue(transactionPool, mExecutor);
        initTaskOrganizer(carActivityManagerRef, transactionPool);
        if (DBG) Slog.d(TAG, "TaskViewManager.create");
    }

    private void initTaskOrganizer(AtomicReference<CarActivityManager> carActivityManagerRef,
            TransactionPool transactionPool) {
        FullscreenTaskListener fullscreenTaskListener = new CarFullscreenTaskMonitorListener(
                carActivityManagerRef, mSyncQueue, Optional.empty());
        mTaskOrganizer.addListenerForType(fullscreenTaskListener, TASK_LISTENER_TYPE_FULLSCREEN);
        StartingWindowController startingController =
                new StartingWindowController(mContext, mExecutor,
                        new PhoneStartingWindowTypeAlgorithm(), new IconProvider(mContext),
                        transactionPool);
        mTaskOrganizer.initStartingWindow(startingController);
        List<TaskAppearedInfo> taskAppearedInfos = mTaskOrganizer.registerOrganizer();
        cleanUpExistingTaskViewTasks(taskAppearedInfos);
    }

    void release() {
        if (DBG) Slog.d(TAG, "TaskViewManager.release");
        mTaskOrganizer.unregisterOrganizer();
    }

    void createTaskView(Consumer<TaskView> onCreate) {
        CarTaskView taskView = new CarTaskView(mContext, mTaskOrganizer, mSyncQueue);
        mExecutor.execute(() -> {
            onCreate.accept(taskView);
        });
    }

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
}
