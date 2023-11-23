/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.nearby.fastpair.cache;

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.database.sqlite.SQLiteException;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

public class FastPairDbHelperTest {

    Context mContext;
    FastPairDbHelper mFastPairDbHelper;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mFastPairDbHelper = new FastPairDbHelper(mContext);
    }

    @After
    public void teardown() {
        mFastPairDbHelper.close();
    }

    @Test
    public void testUpgrade_notCrash() {
        mFastPairDbHelper
                .onUpgrade(mFastPairDbHelper.getWritableDatabase(), 1, 2);
    }

    @Test
    public void testDowngrade_throwsException()  {
        assertThrows(
                SQLiteException.class,
                () -> mFastPairDbHelper.onDowngrade(
                        mFastPairDbHelper.getWritableDatabase(), 2, 1));
    }
}
