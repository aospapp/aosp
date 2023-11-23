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

import android.app.ActivityManager;
import android.content.Context;
import android.view.SurfaceControl;
import android.view.View;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.TaskView;
import com.android.wm.shell.common.SyncTransactionQueue;

/**
 * CarLauncher version of {@link TaskView} which solves some CarLauncehr specific issues.
 */
public class CarTaskView extends TaskView {
    private WindowContainerToken mTaskToken;
    private final SyncTransactionQueue mSyncQueue;

    public CarTaskView(Context context, ShellTaskOrganizer organizer,
            SyncTransactionQueue syncQueue) {
        super(context, organizer, /* taskViewTransitions= */ null, syncQueue);
        mSyncQueue = syncQueue;
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        mTaskToken = taskInfo.token;
        super.onTaskAppeared(taskInfo, leash);
    }

    /**
     * Called when the visibility of the window containing the view has changed.
     * @param visibility The new visibility of the window.
     */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE && mTaskToken != null) {
            WindowContainerTransaction wct = new WindowContainerTransaction();
            // Clears the hidden flag to make it TopFocusedRootTask: b/228092608
            wct.setHidden(mTaskToken, /* hidden= */false);
            // Moves the embedded task to the top to make it resumed: b/225388469
            wct.reorder(mTaskToken, /* onTop= */ true);
            mSyncQueue.queue(wct);
        }
    }
}
