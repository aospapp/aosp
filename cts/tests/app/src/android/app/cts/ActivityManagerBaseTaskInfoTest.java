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
package android.app.cts;

import static android.content.Intent.ACTION_MAIN;

import android.app.ActivityManager;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.content.Intent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ActivityManagerBaseTaskInfoTest {

    protected void fillTaskInfo(TaskInfo taskInfo) {
        taskInfo.taskId = Integer.MAX_VALUE;
        taskInfo.isRunning = true;
        taskInfo.baseIntent = new Intent(ACTION_MAIN);
        ComponentName cn = new ComponentName("android.app.cts",
                ActivityManagerBaseTaskInfoTest.class.getSimpleName());
        taskInfo.baseActivity = cn.clone();
        taskInfo.topActivity = cn.clone();
        taskInfo.origActivity = cn.clone();
        taskInfo.numActivities = Integer.MAX_VALUE;
        taskInfo.taskDescription = new ActivityManager.TaskDescription("Test");
    }

    protected void verifyTaskInfo(TaskInfo taskInfo, TaskInfo sourceTaskInfo) {
        assertEquals(sourceTaskInfo.taskId, taskInfo.taskId);

        assertEquals(sourceTaskInfo.isRunning, taskInfo.isRunning);
        assertTrue(sourceTaskInfo.baseIntent.filterEquals(taskInfo.baseIntent));
        assertEquals(sourceTaskInfo.baseActivity, taskInfo.baseActivity);
        assertEquals(sourceTaskInfo.topActivity, taskInfo.topActivity);
        assertEquals(sourceTaskInfo.origActivity, taskInfo.origActivity);
        assertEquals(sourceTaskInfo.numActivities, taskInfo.numActivities);
        assertEquals(sourceTaskInfo.taskDescription.getLabel(),
                taskInfo.taskDescription.getLabel());
    }
}
