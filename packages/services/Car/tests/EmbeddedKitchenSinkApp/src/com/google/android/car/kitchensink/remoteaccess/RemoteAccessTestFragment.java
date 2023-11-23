/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.google.android.car.kitchensink.remoteaccess;


import static android.preference.PreferenceManager.getDefaultSharedPreferences;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TableRow.LayoutParams;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class RemoteAccessTestFragment extends Fragment {

    private static final String TAG = KitchenSinkRemoteTaskService.class.getSimpleName();

    private SharedPreferences mSharedPref;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSharedPref = getDefaultSharedPreferences(getContext());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstance) {
        View v = inflater.inflate(R.layout.remote_access, container,
                /* attachToRoot= */ false);

        Button b = v.findViewById(R.id.refresh_remote_task_btn);
        b.setOnClickListener(this::refresh);
        return v;
    }

    @Override
    public void onStart() {
        super.onStart();
        refresh(getView());
    }

    private static class TaskInfo {
        public String taskTime;
        public String taskId;
        public String taskData;
        public int remainingTimeSec;
    }

    private void createNewTd(TableRow taskRow, String tag, String text) {
        TextView tdView = new TextView(getContext());
        tdView.setLayoutParams(new TableRow.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        tdView.setTag(tag);
        tdView.setText(text);
        taskRow.addView(tdView);
    }

    private void showTaskInfoList(List<TaskInfo> tasks) {
        TableLayout taskList = (TableLayout) getView().findViewById(R.id.remote_task_list);
        // Remove all rows except the first one.
        while (taskList.getChildCount() > 1) {
            taskList.removeView(taskList.getChildAt(taskList.getChildCount() - 1));
        }
        for (TaskInfo info : tasks) {
            TableRow taskRow;
            taskRow = new TableRow(getContext());
            int viewId = View.generateViewId();
            taskRow.setId(viewId);
            taskRow.setLayoutParams(new TableRow.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));
            createNewTd(taskRow, "taskTime", info.taskTime);
            createNewTd(taskRow, "taskId", info.taskId);
            createNewTd(taskRow, "taskData", info.taskData);
            createNewTd(taskRow, "remainingTimeSec", String.valueOf(info.remainingTimeSec));
            taskList.addView(taskRow);
        }
    }

    private void refresh(View v) {
        String taskListJson = mSharedPref.getString(KitchenSinkRemoteTaskService.PREF_KEY, "{}");
        System.out.println(taskListJson);
        List<TaskInfo> tasks = new ArrayList<>();
        try {
            JSONObject jsonObject = new JSONObject(taskListJson);
            JSONArray taskList = (JSONArray) jsonObject.get(KitchenSinkRemoteTaskService.PREF_KEY);
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = (JSONObject) taskList.get(i);
                TaskInfo taskInfo = new TaskInfo();
                taskInfo.taskTime = task.getString(KitchenSinkRemoteTaskService.TASK_TIME_KEY);
                taskInfo.taskId = task.getString(KitchenSinkRemoteTaskService.TASK_ID_KEY);
                taskInfo.taskData = task.getString(
                        KitchenSinkRemoteTaskService.TASK_DATA_KEY);
                taskInfo.remainingTimeSec = task.getInt(
                        KitchenSinkRemoteTaskService.TASK_DURATION_KEY);
                tasks.add(taskInfo);
            }
        } catch (JSONException e) {
            Log.e(TAG, "failed to parse task JSON: " + taskListJson, e);
        }
        showTaskInfoList(tasks);
    }
}
