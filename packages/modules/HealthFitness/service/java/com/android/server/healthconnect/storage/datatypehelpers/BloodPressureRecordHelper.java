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
package com.android.server.healthconnect.storage.datatypehelpers;

import static com.android.server.healthconnect.storage.utils.StorageUtils.REAL;
import static com.android.server.healthconnect.storage.utils.StorageUtils.TEXT_NOT_NULL;
import static com.android.server.healthconnect.storage.utils.StorageUtils.getCursorDouble;
import static com.android.server.healthconnect.storage.utils.StorageUtils.getCursorInt;

import android.annotation.NonNull;
import android.content.ContentValues;
import android.database.Cursor;
import android.health.connect.datatypes.RecordTypeIdentifier;
import android.health.connect.internal.datatypes.BloodPressureRecordInternal;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;
import java.util.List;

/**
 * Helper class for BloodPressureRecord.
 *
 * @hide
 */
public final class BloodPressureRecordHelper
        extends InstantRecordHelper<BloodPressureRecordInternal> {

    @VisibleForTesting
    public static final String BLOOD_PRESSURE_RECORD_TABLE_NAME = "blood_pressure_record_table";

    private static final String MEASUREMENT_LOCATION_COLUMN_NAME = "measurement_location";
    private static final String SYSTOLIC_COLUMN_NAME = "systolic";
    private static final String DIASTOLIC_COLUMN_NAME = "diastolic";
    private static final String BODY_POSITION_COLUMN_NAME = "body_position";

    public BloodPressureRecordHelper() {
        super(RecordTypeIdentifier.RECORD_TYPE_BLOOD_PRESSURE);
    }

    @Override
    @NonNull
    public String getMainTableName() {
        return BLOOD_PRESSURE_RECORD_TABLE_NAME;
    }

    @Override
    void populateSpecificRecordValue(
            @NonNull Cursor cursor, @NonNull BloodPressureRecordInternal bloodPressureRecord) {
        bloodPressureRecord.setMeasurementLocation(
                getCursorInt(cursor, MEASUREMENT_LOCATION_COLUMN_NAME));
        bloodPressureRecord.setSystolic(getCursorDouble(cursor, SYSTOLIC_COLUMN_NAME));
        bloodPressureRecord.setDiastolic(getCursorDouble(cursor, DIASTOLIC_COLUMN_NAME));
        bloodPressureRecord.setBodyPosition(getCursorInt(cursor, BODY_POSITION_COLUMN_NAME));
    }

    @Override
    void populateSpecificContentValues(
            @NonNull ContentValues contentValues,
            @NonNull BloodPressureRecordInternal bloodPressureRecord) {
        contentValues.put(
                MEASUREMENT_LOCATION_COLUMN_NAME, bloodPressureRecord.getMeasurementLocation());
        contentValues.put(SYSTOLIC_COLUMN_NAME, bloodPressureRecord.getSystolic());
        contentValues.put(DIASTOLIC_COLUMN_NAME, bloodPressureRecord.getDiastolic());
        contentValues.put(BODY_POSITION_COLUMN_NAME, bloodPressureRecord.getBodyPosition());
    }

    @Override
    @NonNull
    protected List<Pair<String, String>> getInstantRecordColumnInfo() {
        return Arrays.asList(
                new Pair<>(MEASUREMENT_LOCATION_COLUMN_NAME, TEXT_NOT_NULL),
                new Pair<>(SYSTOLIC_COLUMN_NAME, REAL),
                new Pair<>(DIASTOLIC_COLUMN_NAME, REAL),
                new Pair<>(BODY_POSITION_COLUMN_NAME, TEXT_NOT_NULL));
    }
}
