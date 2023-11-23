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

package android.healthconnect.cts.nopermission;

import static android.health.connect.datatypes.HeartRateRecord.BPM_MAX;
import static android.health.connect.datatypes.HeartRateRecord.BPM_MIN;

import static com.google.common.truth.Truth.assertThat;

import android.health.connect.AggregateRecordsRequest;
import android.health.connect.HealthConnectException;
import android.health.connect.LocalTimeRangeFilter;
import android.health.connect.ReadRecordsRequestUsingFilters;
import android.health.connect.TimeInstantRangeFilter;
import android.health.connect.changelog.ChangeLogTokenRequest;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.Record;
import android.healthconnect.cts.TestUtils;
import android.platform.test.annotations.AppModeFull;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** These test run under an environment which has no HC permissions */
@AppModeFull(reason = "HealthConnectManager is not accessible to instant apps")
@RunWith(AndroidJUnit4.class)
public class HealthConnectManagerNoPermissionsGrantedTest {
    @Test
    public void testInsertNotAllowed() throws InterruptedException {
        for (Record testRecord : TestUtils.getTestRecords()) {
            try {
                TestUtils.insertRecords(Collections.singletonList(testRecord));
                Assert.fail("Insert must be not allowed without right HC permission");
            } catch (HealthConnectException healthConnectException) {
                assertThat(healthConnectException.getErrorCode())
                        .isEqualTo(HealthConnectException.ERROR_SECURITY);
            }
        }
    }

    @Test
    public void testUpdateNotAllowed() throws InterruptedException {
        for (Record testRecord : TestUtils.getTestRecords()) {
            try {
                TestUtils.updateRecords(Collections.singletonList(testRecord));
                Assert.fail("Update must be not allowed without right HC permission");
            } catch (HealthConnectException healthConnectException) {
                assertThat(healthConnectException.getErrorCode())
                        .isEqualTo(HealthConnectException.ERROR_SECURITY);
            }
        }
    }

    @Test
    public void testDeleteUsingIdNotAllowed() throws InterruptedException {
        for (Record testRecord : TestUtils.getTestRecords()) {
            try {
                TestUtils.deleteRecords(Collections.singletonList(testRecord));
                Assert.fail("Delete using ids must be not allowed without right HC permission");
            } catch (HealthConnectException healthConnectException) {
                assertThat(healthConnectException.getErrorCode())
                        .isEqualTo(HealthConnectException.ERROR_SECURITY);
            }
        }
    }

    @Test
    public void testDeleteUsingFilterNotAllowed() throws InterruptedException {
        for (Record testRecord : TestUtils.getTestRecords()) {
            try {
                TestUtils.verifyDeleteRecords(
                        testRecord.getClass(),
                        new TimeInstantRangeFilter.Builder()
                                .setStartTime(Instant.now())
                                .setEndTime(Instant.now().plusMillis(1000))
                                .build());
                Assert.fail("Delete using filters must be not allowed without right HC permission");
            } catch (HealthConnectException healthConnectException) {
                assertThat(healthConnectException.getErrorCode())
                        .isEqualTo(HealthConnectException.ERROR_SECURITY);
            }
        }
    }

    @Test
    public void testChangeLogsTokenNotAllowed() throws InterruptedException {
        for (Record testRecord : TestUtils.getTestRecords()) {
            try {
                TestUtils.getChangeLogToken(
                        new ChangeLogTokenRequest.Builder()
                                .addRecordType(testRecord.getClass())
                                .build());
                Assert.fail(
                        "Getting change log token must be not allowed without right HC permission");
            } catch (HealthConnectException healthConnectException) {
                assertThat(healthConnectException.getErrorCode())
                        .isEqualTo(HealthConnectException.ERROR_SECURITY);
            }
        }
    }

    @Test
    public void testReadNotAllowed() throws InterruptedException {
        for (Record testRecord : TestUtils.getTestRecords()) {
            try {
                TestUtils.readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(testRecord.getClass())
                                .build());
                Assert.fail("Read records must be not allowed without right HC permission");
            } catch (HealthConnectException healthConnectException) {
                assertThat(healthConnectException.getErrorCode())
                        .isEqualTo(HealthConnectException.ERROR_SECURITY);
            }
        }
    }

    @Test
    public void testAggregateNotAllowed() throws InterruptedException {
        try {
            List<Record> records =
                    Arrays.asList(
                            TestUtils.getHeartRateRecord(71),
                            TestUtils.getHeartRateRecord(72),
                            TestUtils.getHeartRateRecord(73));
            TestUtils.getAggregateResponse(
                    new AggregateRecordsRequest.Builder<Long>(
                                    new TimeInstantRangeFilter.Builder()
                                            .setStartTime(Instant.ofEpochMilli(0))
                                            .setEndTime(Instant.now())
                                            .build())
                            .addAggregationType(BPM_MAX)
                            .addAggregationType(BPM_MIN)
                            .addDataOriginsFilter(
                                    new DataOrigin.Builder().setPackageName("abc").build())
                            .build(),
                    records);
            Assert.fail("Get Aggregations must be not allowed without right HC permission");
        } catch (HealthConnectException healthConnectException) {
            assertThat(healthConnectException.getErrorCode())
                    .isEqualTo(HealthConnectException.ERROR_SECURITY);
        }
    }

    @Test
    public void testAggregateGroupByDurationNotAllowed() throws InterruptedException {
        try {
            Instant start = Instant.now().minusMillis(500);
            Instant end = Instant.now().plusMillis(2500);
            TestUtils.getAggregateResponseGroupByDuration(
                    new AggregateRecordsRequest.Builder<Long>(
                                    new TimeInstantRangeFilter.Builder()
                                            .setStartTime(start)
                                            .setEndTime(end)
                                            .build())
                            .addAggregationType(BPM_MAX)
                            .addAggregationType(BPM_MIN)
                            .build(),
                    Duration.ofSeconds(1));
            Assert.fail(
                    "Aggregations group by duration must be not allowed without right HC"
                            + " permission");
        } catch (HealthConnectException healthConnectException) {
            assertThat(healthConnectException.getErrorCode())
                    .isEqualTo(HealthConnectException.ERROR_SECURITY);
        }
    }

    @Test
    public void testAggregateGroupByPeriodNotAllowed() throws InterruptedException {
        try {
            Instant start = Instant.now().minus(3, ChronoUnit.DAYS);
            Instant end = start.plus(3, ChronoUnit.DAYS);
            TestUtils.getAggregateResponseGroupByPeriod(
                    new AggregateRecordsRequest.Builder<Long>(
                                    new LocalTimeRangeFilter.Builder()
                                            .setStartTime(
                                                    LocalDateTime.ofInstant(start, ZoneOffset.UTC))
                                            .setEndTime(
                                                    LocalDateTime.ofInstant(end, ZoneOffset.UTC))
                                            .build())
                            .addAggregationType(BPM_MAX)
                            .addAggregationType(BPM_MIN)
                            .build(),
                    Period.ofDays(1));
            Assert.fail(
                    "Aggregation group by period must be not allowed without right HC permission");
        } catch (HealthConnectException healthConnectException) {
            assertThat(healthConnectException.getErrorCode())
                    .isEqualTo(HealthConnectException.ERROR_SECURITY);
        }
    }
}
