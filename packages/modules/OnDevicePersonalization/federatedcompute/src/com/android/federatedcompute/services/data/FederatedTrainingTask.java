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

package com.android.federatedcompute.services.data;

import static com.android.federatedcompute.services.data.FederatedTraningTaskContract.FEDERATED_TRAINING_TASKS_TABLE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.android.federatedcompute.services.data.FederatedTraningTaskContract.FederatedTrainingTaskColumns;
import com.android.federatedcompute.services.data.fbs.TrainingConstraints;
import com.android.federatedcompute.services.data.fbs.TrainingIntervalOptions;

import com.google.auto.value.AutoValue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Contains the details of a training task. */
@AutoValue
public abstract class FederatedTrainingTask {
    private static final String TAG = "FederatedTrainingTask";

    /** @return client app package name */
    public abstract String appPackageName();

    /**
     * @return the ID to use for the JobScheduler job that will run the training for this session.
     */
    public abstract int jobId();

    /** @return the population name to uniquely identify the training job by. */
    public abstract String populationName();

    /**
     * @return the byte array of training interval including scheduling mode and minimum latency.
     *     The byte array is constructed from TrainingConstraints flatbuffer.
     */
    @Nullable
    @SuppressWarnings("mutable")
    public abstract byte[] intervalOptions();

    /** @return the training interval including scheduling mode and minimum latency. */
    @Nullable
    public final TrainingIntervalOptions getTrainingIntervalOptions() {
        if (intervalOptions() == null) {
            return null;
        }
        return TrainingIntervalOptions.getRootAsTrainingIntervalOptions(
                ByteBuffer.wrap(intervalOptions()));
    }

    /** @return the time the task was originally created. */
    public abstract Long creationTime();

    /** @return the time the task was last scheduled. */
    public abstract Long lastScheduledTime();

    /** @return the start time of the task's last run. */
    @Nullable
    public abstract Long lastRunStartTime();

    /** @return the end time of the task's last run. */
    @Nullable
    public abstract Long lastRunEndTime();

    /** @return the earliest time to run the task by. */
    public abstract Long earliestNextRunTime();

    /**
     * @return the byte array of training constraints that should apply to this task. The byte array
     *     is constructed from TrainingConstraints flatbuffer.
     */
    @SuppressWarnings("mutable")
    public abstract byte[] constraints();

    /** @return the training constraints that should apply to this task. */
    public final TrainingConstraints getTrainingConstraints() {
        return TrainingConstraints.getRootAsTrainingConstraints(ByteBuffer.wrap(constraints()));
    }

    /** @return the reason to schedule the task. */
    public abstract int schedulingReason();

    /** Builder for {@link FederatedTrainingTask} */
    @AutoValue.Builder
    public abstract static class Builder {
        /** Set client application package name. */
        public abstract Builder appPackageName(String appPackageName);

        /** Set job scheduler Id. */
        public abstract Builder jobId(int jobId);

        /** Set population name which uniquely identify the job. */
        public abstract Builder populationName(String populationName);

        /** Set the training interval including scheduling mode and minimum latency. */
        @SuppressWarnings("mutable")
        public abstract Builder intervalOptions(@Nullable byte[] intervalOptions);

        /** Set the time the task was originally created. */
        public abstract Builder creationTime(Long creationTime);

        /** Set the time the task was last scheduled. */
        public abstract Builder lastScheduledTime(Long lastScheduledTime);

        /** Set the start time of the task's last run. */
        public abstract Builder lastRunStartTime(@Nullable Long lastRunStartTime);

        /** Set the end time of the task's last run. */
        public abstract Builder lastRunEndTime(@Nullable Long lastRunEndTime);

        /** Set the earliest time to run the task by. */
        public abstract Builder earliestNextRunTime(Long earliestNextRunTime);

        /** Set the training constraints that should apply to this task. */
        @SuppressWarnings("mutable")
        public abstract Builder constraints(byte[] constraints);

        /** Set the reason to schedule the task. */
        public abstract Builder schedulingReason(int schedulingReason);

        /** Build a federated training task instance. */
        @NonNull
        public abstract FederatedTrainingTask build();
    }

    /** @return a builder of federated training task. */
    public abstract Builder toBuilder();

    /** @return a generic builder. */
    @NonNull
    public static Builder builder() {
        return new AutoValue_FederatedTrainingTask.Builder();
    }

    boolean addToDatabase(SQLiteDatabase db) {
        ContentValues values = new ContentValues();
        values.put(FederatedTrainingTaskColumns.APP_PACKAGE_NAME, appPackageName());
        values.put(FederatedTrainingTaskColumns.JOB_SCHEDULER_JOB_ID, jobId());

        values.put(FederatedTrainingTaskColumns.POPULATION_NAME, populationName());
        if (intervalOptions() != null) {
            values.put(FederatedTrainingTaskColumns.INTERVAL_OPTIONS, intervalOptions());
        }

        values.put(FederatedTrainingTaskColumns.CREATION_TIME, creationTime());
        values.put(FederatedTrainingTaskColumns.LAST_SCHEDULED_TIME, lastScheduledTime());
        if (lastRunStartTime() != null) {
            values.put(FederatedTrainingTaskColumns.LAST_RUN_START_TIME, lastRunStartTime());
        }
        if (lastRunEndTime() != null) {
            values.put(FederatedTrainingTaskColumns.LAST_RUN_END_TIME, lastRunEndTime());
        }
        values.put(FederatedTrainingTaskColumns.EARLIEST_NEXT_RUN_TIME, earliestNextRunTime());
        values.put(FederatedTrainingTaskColumns.CONSTRAINTS, constraints());
        values.put(FederatedTrainingTaskColumns.SCHEDULING_REASON, schedulingReason());
        long jobId =
                db.insertWithOnConflict(
                        FEDERATED_TRAINING_TASKS_TABLE,
                        "",
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE);
        return jobId != -1;
    }

    static List<FederatedTrainingTask> readFederatedTrainingTasksFromDatabase(
            SQLiteDatabase db, String selection, String[] selectionArgs) {
        List<FederatedTrainingTask> taskList = new ArrayList<>();
        String[] selectColumns = {
            FederatedTrainingTaskColumns.APP_PACKAGE_NAME,
            FederatedTrainingTaskColumns.JOB_SCHEDULER_JOB_ID,
            FederatedTrainingTaskColumns.POPULATION_NAME,
            FederatedTrainingTaskColumns.INTERVAL_OPTIONS,
            FederatedTrainingTaskColumns.CREATION_TIME,
            FederatedTrainingTaskColumns.LAST_SCHEDULED_TIME,
            FederatedTrainingTaskColumns.LAST_RUN_START_TIME,
            FederatedTrainingTaskColumns.LAST_RUN_END_TIME,
            FederatedTrainingTaskColumns.EARLIEST_NEXT_RUN_TIME,
            FederatedTrainingTaskColumns.CONSTRAINTS,
            FederatedTrainingTaskColumns.SCHEDULING_REASON,
        };
        Cursor cursor = null;
        try {
            cursor =
                    db.query(
                            FEDERATED_TRAINING_TASKS_TABLE,
                            selectColumns,
                            selection,
                            selectionArgs,
                            null,
                            null
                            /* groupBy= */ ,
                            null
                            /* having= */ ,
                            null
                            /* orderBy= */);
            while (cursor.moveToNext()) {
                FederatedTrainingTask.Builder trainingTaskBuilder =
                        FederatedTrainingTask.builder()
                                .appPackageName(
                                        cursor.getString(
                                                cursor.getColumnIndexOrThrow(
                                                        FederatedTrainingTaskColumns
                                                                .APP_PACKAGE_NAME)))
                                .jobId(
                                        cursor.getInt(
                                                cursor.getColumnIndexOrThrow(
                                                        FederatedTrainingTaskColumns
                                                                .JOB_SCHEDULER_JOB_ID)))
                                .populationName(
                                        cursor.getString(
                                                cursor.getColumnIndexOrThrow(
                                                        FederatedTrainingTaskColumns
                                                                .POPULATION_NAME)))
                                .creationTime(
                                        cursor.getLong(
                                                cursor.getColumnIndexOrThrow(
                                                        FederatedTrainingTaskColumns
                                                                .CREATION_TIME)))
                                .lastScheduledTime(
                                        cursor.getLong(
                                                cursor.getColumnIndexOrThrow(
                                                        FederatedTrainingTaskColumns
                                                                .LAST_SCHEDULED_TIME)))
                                .lastRunStartTime(
                                        cursor.getLong(
                                                cursor.getColumnIndexOrThrow(
                                                        FederatedTrainingTaskColumns
                                                                .LAST_RUN_START_TIME)))
                                .lastRunEndTime(
                                        cursor.getLong(
                                                cursor.getColumnIndexOrThrow(
                                                        FederatedTrainingTaskColumns
                                                                .LAST_RUN_END_TIME)))
                                .earliestNextRunTime(
                                        cursor.getLong(
                                                cursor.getColumnIndexOrThrow(
                                                        FederatedTrainingTaskColumns
                                                                .EARLIEST_NEXT_RUN_TIME)));
                int schedulingReason =
                        cursor.getInt(
                                cursor.getColumnIndexOrThrow(
                                        FederatedTrainingTaskColumns.SCHEDULING_REASON));
                if (!cursor.isNull(schedulingReason)) {
                    trainingTaskBuilder.schedulingReason(schedulingReason);
                }
                byte[] intervalOptions =
                        cursor.getBlob(
                                cursor.getColumnIndexOrThrow(
                                        FederatedTrainingTaskColumns.INTERVAL_OPTIONS));
                if (intervalOptions != null) {
                    trainingTaskBuilder.intervalOptions(intervalOptions);
                }
                byte[] constraints =
                        cursor.getBlob(
                                cursor.getColumnIndexOrThrow(
                                        FederatedTrainingTaskColumns.CONSTRAINTS));
                trainingTaskBuilder.constraints(constraints);
                taskList.add(trainingTaskBuilder.build());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return taskList;
    }
}
