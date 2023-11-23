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

package android.federatedcompute.common;

import static android.federatedcompute.common.TrainingInterval.SCHEDULING_MODE_ONE_TIME;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link TrainingOptions} */
@RunWith(AndroidJUnit4.class)
public final class TrainingOptionsTest {
    private String mTestPopulation;
    private int mTestJobId;
    private TrainingInterval mOneOffInterval;

    @Before
    public void setUp() {
        mTestPopulation = "population";
        mTestJobId = 10086;
        mOneOffInterval =
                new TrainingInterval.Builder().setSchedulingMode(SCHEDULING_MODE_ONE_TIME).build();
    }

    @Test
    public void testFederatedTask() {
        TrainingOptions options =
                new TrainingOptions.Builder()
                        .setPopulationName(mTestPopulation)
                        .setJobSchedulerJobId(mTestJobId)
                        .setTrainingInterval(mOneOffInterval)
                        .build();
        assertThat(options.getPopulationName()).isEqualTo(mTestPopulation);
        assertThat(options.getJobSchedulerJobId()).isEqualTo(mTestJobId);
        assertThat(options.getTrainingInterval()).isEqualTo(mOneOffInterval);
    }

    @Test
    public void testNullPopulation() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TrainingOptions.Builder()
                                .setPopulationName(null)
                                .setJobSchedulerJobId(mTestJobId)
                                .setTrainingInterval(mOneOffInterval)
                                .build());
    }

    @Test
    public void testEmptyPopulation() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TrainingOptions.Builder()
                                .setPopulationName("")
                                .setJobSchedulerJobId(mTestJobId)
                                .setTrainingInterval(mOneOffInterval)
                                .build());
    }

    @Test
    public void testJobIdCannotBeZero() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TrainingOptions.Builder()
                                .setPopulationName(mTestPopulation)
                                .setJobSchedulerJobId(0)
                                .setTrainingInterval(mOneOffInterval)
                                .build());
    }

    @Test
    public void testNullTrainingIntervalIsAllowed() {
        TrainingOptions options =
                new TrainingOptions.Builder()
                        .setPopulationName(mTestPopulation)
                        .setJobSchedulerJobId(mTestJobId)
                        .setTrainingInterval(null)
                        .build();
        assertThat(options.getTrainingInterval()).isNull();
    }

    @Test
    public void testParcelValidInterval() {
        TrainingOptions options =
                new TrainingOptions.Builder()
                        .setPopulationName(mTestPopulation)
                        .setJobSchedulerJobId(mTestJobId)
                        .setTrainingInterval(null)
                        .build();

        Parcel p = Parcel.obtain();
        options.writeToParcel(p, 0);
        p.setDataPosition(0);
        TrainingOptions fromParcel = TrainingOptions.CREATOR.createFromParcel(p);

        assertThat(options).isEqualTo(fromParcel);
    }
}
