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

package android.healthconnect.cts.device;

import static android.health.connect.HealthPermissions.WRITE_EXERCISE_ROUTE;
import static android.healthconnect.cts.device.HealthConnectDeviceTest.APP_A_WITH_READ_WRITE_PERMS;
import static android.healthconnect.cts.lib.TestUtils.READ_RECORDS_SIZE;
import static android.healthconnect.cts.lib.TestUtils.SUCCESS;
import static android.healthconnect.cts.lib.TestUtils.deleteAllStagedRemoteData;
import static android.healthconnect.cts.lib.TestUtils.insertRecordAs;
import static android.healthconnect.cts.lib.TestUtils.insertSessionNoRouteAs;
import static android.healthconnect.cts.lib.TestUtils.readRecords;
import static android.healthconnect.cts.lib.TestUtils.readRecordsAs;
import static android.healthconnect.cts.lib.TestUtils.updateRouteAs;

import static com.android.compatibility.common.util.FeatureUtil.AUTOMOTIVE_FEATURE;
import static com.android.compatibility.common.util.FeatureUtil.hasSystemFeature;

import static com.google.common.truth.Truth.assertThat;

import android.app.UiAutomation;
import android.health.connect.HealthConnectException;
import android.health.connect.ReadRecordsRequestUsingFilters;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.os.Bundle;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ExerciseRouteAccessTest {

    private UiAutomation mAutomation;

    @Before
    public void setUp() {
        mAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        Assume.assumeFalse(hasSystemFeature(AUTOMOTIVE_FEATURE));

        mAutomation.grantRuntimePermission(
                APP_A_WITH_READ_WRITE_PERMS.getPackageName(), WRITE_EXERCISE_ROUTE);
    }

    @After
    public void tearDown() {
        deleteAllStagedRemoteData();

        mAutomation.grantRuntimePermission(
                APP_A_WITH_READ_WRITE_PERMS.getPackageName(), WRITE_EXERCISE_ROUTE);
    }

    @Test
    public void testRouteRead_cannotAccessOtherAppRoute() throws Exception {
        assertThat(insertRecordAs(APP_A_WITH_READ_WRITE_PERMS).getBoolean(SUCCESS)).isTrue();

        List<ExerciseSessionRecord> records =
                readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(ExerciseSessionRecord.class)
                                .build());

        assertThat(records).isNotNull();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).hasRoute()).isTrue();
        assertThat(records.get(0).getRoute()).isNull();
    }

    @Test
    public void testRouteInsert_cannotInsertRouteWithoutPerm() throws Exception {
        mAutomation.revokeRuntimePermission(
                APP_A_WITH_READ_WRITE_PERMS.getPackageName(), WRITE_EXERCISE_ROUTE);

        try {
            insertRecordAs(APP_A_WITH_READ_WRITE_PERMS);
            Assert.fail("Should have thrown an Security Exception!");
        } catch (HealthConnectException e) {
            assertThat(e.getErrorCode()).isEqualTo(HealthConnectException.ERROR_SECURITY);
        } finally {
            mAutomation.grantRuntimePermission(
                    APP_A_WITH_READ_WRITE_PERMS.getPackageName(), WRITE_EXERCISE_ROUTE);
        }
    }

    @Test
    public void testRouteUpdate_updateRouteWithPerm_noRouteAfterUpdate() throws Exception {
        assertThat(insertRecordAs(APP_A_WITH_READ_WRITE_PERMS).getBoolean(SUCCESS)).isTrue();
        List<ExerciseSessionRecord> records =
                readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(ExerciseSessionRecord.class)
                                .build());
        assertThat(records).isNotNull();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).hasRoute()).isTrue();

        assertThat(updateRouteAs(APP_A_WITH_READ_WRITE_PERMS).getBoolean(SUCCESS)).isTrue();

        records =
                readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(ExerciseSessionRecord.class)
                                .build());
        assertThat(records).isNotNull();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).hasRoute()).isFalse();

        // Check that the route has been actually deleted, so no exceptions from incorrect record
        // state.
        Bundle bundle =
                readRecordsAs(
                        APP_A_WITH_READ_WRITE_PERMS,
                        new ArrayList<>(List.of(ExerciseSessionRecord.class.getName())));
        assertThat(bundle.getBoolean(SUCCESS)).isTrue();
        assertThat(bundle.getInt(READ_RECORDS_SIZE)).isEqualTo(1);
    }

    @Test
    public void testRouteUpdate_updateRouteWithoutPerm_hasRouteAfterUpdate() throws Exception {
        assertThat(insertRecordAs(APP_A_WITH_READ_WRITE_PERMS).getBoolean(SUCCESS)).isTrue();
        mAutomation.revokeRuntimePermission(
                APP_A_WITH_READ_WRITE_PERMS.getPackageName(), WRITE_EXERCISE_ROUTE);

        updateRouteAs(APP_A_WITH_READ_WRITE_PERMS);

        List<ExerciseSessionRecord> records =
                readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(ExerciseSessionRecord.class)
                                .build());
        assertThat(records).isNotNull();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).hasRoute()).isTrue();
    }

    @Test
    public void testRouteUpsert_insertRecordNoRouteWithoutRoutePerm_hasRouteAfterInsert()
            throws Exception {
        assertThat(insertRecordAs(APP_A_WITH_READ_WRITE_PERMS).getBoolean(SUCCESS)).isTrue();
        mAutomation.revokeRuntimePermission(
                APP_A_WITH_READ_WRITE_PERMS.getPackageName(), WRITE_EXERCISE_ROUTE);

        insertSessionNoRouteAs(APP_A_WITH_READ_WRITE_PERMS);

        List<ExerciseSessionRecord> records =
                readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(ExerciseSessionRecord.class)
                                .build());
        assertThat(records).isNotNull();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).hasRoute()).isTrue();
    }

    @Test
    public void testRouteUpsert_insertRecordNoRouteWithRoutePerm_noRouteAfterInsert()
            throws Exception {
        assertThat(insertRecordAs(APP_A_WITH_READ_WRITE_PERMS).getBoolean(SUCCESS)).isTrue();
        insertSessionNoRouteAs(APP_A_WITH_READ_WRITE_PERMS);

        List<ExerciseSessionRecord> records =
                readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(ExerciseSessionRecord.class)
                                .build());
        assertThat(records).isNotNull();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).hasRoute()).isFalse();
    }
}
