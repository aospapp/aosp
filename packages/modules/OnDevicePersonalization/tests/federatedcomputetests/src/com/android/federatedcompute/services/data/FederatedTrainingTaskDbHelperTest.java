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

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FederatedTrainingTaskDbHelperTest {
    private Context mContext;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
    }

    @After
    public void cleanUp() throws Exception {
        FederatedTrainingTaskDbHelper.resetInstance();
    }

    @Test
    public void onCreate() {
        FederatedTrainingTaskDbHelper dbHelper =
                FederatedTrainingTaskDbHelper.getInstanceForTest(mContext);

        SQLiteDatabase db = dbHelper.getReadableDatabase();

        assertThat(db).isNotNull();
        assertThat(DatabaseUtils.queryNumEntries(db, FEDERATED_TRAINING_TASKS_TABLE)).isEqualTo(0);
    }
}
