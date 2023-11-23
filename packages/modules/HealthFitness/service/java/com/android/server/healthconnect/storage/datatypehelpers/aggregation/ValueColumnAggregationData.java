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

package com.android.server.healthconnect.storage.datatypehelpers.aggregation;

import static com.android.server.healthconnect.storage.request.AggregateParams.PriorityAggregationExtraParams.VALUE_TYPE_DOUBLE;
import static com.android.server.healthconnect.storage.request.AggregateParams.PriorityAggregationExtraParams.VALUE_TYPE_LONG;

import android.database.Cursor;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.healthconnect.storage.request.AggregateParams.PriorityAggregationExtraParams.ValueColumnType;
import com.android.server.healthconnect.storage.utils.StorageUtils;

/**
 * Represents priority aggregation data for one column.
 *
 * @hide
 */
public class ValueColumnAggregationData extends AggregationRecordData {
    private final String mValueColumnName;
    @ValueColumnType private final int mValueColumnType;
    private double mValue;

    public ValueColumnAggregationData(String valueColumnName, @ValueColumnType int type) {
        mValueColumnName = valueColumnName;
        mValueColumnType = type;
    }

    @Override
    double getResultOnInterval(long startTime, long endTime) {
        double intervalDuration = getEndTime() - getStartTime();
        double overlapDuration =
                Math.min(getEndTime(), endTime) - Math.max(getStartTime(), startTime);

        // Case: start time equals end time
        if (intervalDuration == 0) {
            return mValue;
        }

        if (intervalDuration < 0 || overlapDuration <= 0) {
            return 0;
        }

        return mValue * overlapDuration / intervalDuration;
    }

    @Override
    void populateSpecificAggregationData(Cursor cursor, boolean useLocalTime) {
        if (mValueColumnType == VALUE_TYPE_DOUBLE) {
            mValue = StorageUtils.getCursorDouble(cursor, mValueColumnName);
        } else if (mValueColumnType == VALUE_TYPE_LONG) {
            mValue = StorageUtils.getCursorLong(cursor, mValueColumnName);
        } else {
            throw new IllegalArgumentException("Unknown aggregation column type.");
        }
    }

    @VisibleForTesting
    AggregationRecordData setValue(double value) {
        mValue = value;
        return this;
    }
}
