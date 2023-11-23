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

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.federatedcompute.services.data.fbs.SchedulingMode;
import com.android.federatedcompute.services.data.fbs.SchedulingReason;
import com.android.federatedcompute.services.data.fbs.TrainingConstraints;
import com.android.federatedcompute.services.data.fbs.TrainingIntervalOptions;

import com.google.flatbuffers.FlatBufferBuilder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FederatedTrainingTaskDaoTest {
    private static final String PACKAGE_NAME = "app_package_name";
    private static final String POPULATION_NAME = "population_name";
    private static final int JOB_ID = 123;
    private static final Long CREATION_TIME = 1233L;
    private static final Long LAST_SCHEDULE_TIME = 1230L;
    private static final Long LAST_RUN_START_TIME = 1200L;
    private static final Long LAST_RUN_END_TIME = 1210L;
    private static final Long EARLIEST_NEXT_RUN_TIME = 1290L;
    private static final byte[] INTERVAL_OPTIONS = createDefaultTrainingIntervalOptions();
    private static final byte[] TRAINING_CONSTRAINTS = createDefaultTrainingConstraints();

    private FederatedTrainingTaskDao mTrainingTaskDao;
    private Context mContext;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mTrainingTaskDao = FederatedTrainingTaskDao.getInstanceForTest(mContext);
    }

    @After
    public void cleanUp() throws Exception {
        FederatedTrainingTaskDbHelper dbHelper =
                FederatedTrainingTaskDbHelper.getInstanceForTest(mContext);
        dbHelper.getWritableDatabase().close();
        dbHelper.getReadableDatabase().close();
        dbHelper.close();
    }

    @Test
    public void findAndRemoveTaskByJobId_success() {
        FederatedTrainingTask task = createDefaultFederatedTrainingTask();
        mTrainingTaskDao.updateOrInsertFederatedTrainingTask(task);
        int jobId2 = 456;
        FederatedTrainingTask task2 =
                createDefaultFederatedTrainingTask().toBuilder().jobId(jobId2).build();
        mTrainingTaskDao.updateOrInsertFederatedTrainingTask(task2);
        assertThat(mTrainingTaskDao.getFederatedTrainingTask(null, null)).hasSize(2);

        FederatedTrainingTask removedTask = mTrainingTaskDao.findAndRemoveTaskByJobId(JOB_ID);

        assertThat(DataTestUtil.isEqualTask(removedTask, task)).isTrue();
        assertThat(mTrainingTaskDao.getFederatedTrainingTask(null, null)).hasSize(1);
    }

    @Test
    public void findAndRemoveTaskByJobId_nonExist() {
        FederatedTrainingTask removedTask = mTrainingTaskDao.findAndRemoveTaskByJobId(JOB_ID);

        assertThat(removedTask).isNull();
    }

    @Test
    public void findAndRemoveTaskByPopulationNameAndJobId_success() {
        FederatedTrainingTask task = createDefaultFederatedTrainingTask();
        mTrainingTaskDao.updateOrInsertFederatedTrainingTask(task);
        FederatedTrainingTask task2 =
                createDefaultFederatedTrainingTask().toBuilder()
                        .populationName(POPULATION_NAME + "_2")
                        .build();
        mTrainingTaskDao.updateOrInsertFederatedTrainingTask(task2);
        assertThat(mTrainingTaskDao.getFederatedTrainingTask(null, null)).hasSize(2);

        FederatedTrainingTask removedTask =
                mTrainingTaskDao.findAndRemoveTaskByPopulationAndJobId(POPULATION_NAME, JOB_ID);

        assertThat(DataTestUtil.isEqualTask(removedTask, task)).isTrue();
        assertThat(mTrainingTaskDao.getFederatedTrainingTask(null, null)).hasSize(1);
    }

    @Test
    public void findAndRemoveTaskByPopulationNameAndJobId_nonExist() {
        FederatedTrainingTask removedTask =
                mTrainingTaskDao.findAndRemoveTaskByPopulationAndJobId(POPULATION_NAME, JOB_ID);

        assertThat(removedTask).isNull();
    }

    @Test
    public void findAndRemoveTaskByPopulationName_success() {
        FederatedTrainingTask task = createDefaultFederatedTrainingTask();
        mTrainingTaskDao.updateOrInsertFederatedTrainingTask(task);
        FederatedTrainingTask task2 =
                createDefaultFederatedTrainingTask().toBuilder()
                        .populationName(POPULATION_NAME + "_2")
                        .build();
        mTrainingTaskDao.updateOrInsertFederatedTrainingTask(task2);
        assertThat(mTrainingTaskDao.getFederatedTrainingTask(null, null)).hasSize(2);

        FederatedTrainingTask removedTask =
                mTrainingTaskDao.findAndRemoveTaskByPopulationName(POPULATION_NAME);

        assertThat(DataTestUtil.isEqualTask(removedTask, task)).isTrue();
        assertThat(mTrainingTaskDao.getFederatedTrainingTask(null, null)).hasSize(1);
    }

    @Test
    public void findAndRemoveTaskByPopulationName_nonExist() {
        FederatedTrainingTask removedTask =
                mTrainingTaskDao.findAndRemoveTaskByPopulationName(POPULATION_NAME);

        assertThat(removedTask).isNull();
    }

    private static byte[] createDefaultTrainingConstraints() {
        FlatBufferBuilder builder = new FlatBufferBuilder();
        builder.finish(TrainingConstraints.createTrainingConstraints(builder, true, true, true));
        return builder.sizedByteArray();
    }

    private static byte[] createDefaultTrainingIntervalOptions() {
        FlatBufferBuilder builder = new FlatBufferBuilder();
        builder.finish(
                TrainingIntervalOptions.createTrainingIntervalOptions(
                        builder, SchedulingMode.ONE_TIME, 0));
        return builder.sizedByteArray();
    }

    private FederatedTrainingTask createDefaultFederatedTrainingTask() {
        return FederatedTrainingTask.builder()
                .appPackageName(PACKAGE_NAME)
                .jobId(JOB_ID)
                .populationName(POPULATION_NAME)
                .intervalOptions(INTERVAL_OPTIONS)
                .constraints(TRAINING_CONSTRAINTS)
                .creationTime(CREATION_TIME)
                .lastScheduledTime(LAST_SCHEDULE_TIME)
                .lastRunStartTime(LAST_RUN_START_TIME)
                .lastRunEndTime(LAST_RUN_END_TIME)
                .earliestNextRunTime(EARLIEST_NEXT_RUN_TIME)
                .schedulingReason(SchedulingReason.SCHEDULING_REASON_NEW_TASK)
                .build();
    }
}
