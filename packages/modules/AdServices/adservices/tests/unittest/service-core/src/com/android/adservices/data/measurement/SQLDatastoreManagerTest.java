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

package com.android.adservices.data.measurement;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.LogUtil;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;

public class SQLDatastoreManagerTest {

    protected static final Context CONTEXT = ApplicationProvider.getApplicationContext();
    @Mock private DatastoreManager.ThrowingCheckedFunction<Void> mFunction;
    @Mock private DatastoreManager.ThrowingCheckedConsumer mConsumer;
    private MockitoSession mMockitoSession;
    private SQLDatastoreManager mSQLDatastoreManager;

    @Before
    public void setUp() {
        mMockitoSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(LogUtil.class)
                        .initMocks(this)
                        .startMocking();
        mSQLDatastoreManager = new SQLDatastoreManager(CONTEXT);
    }

    @Test
    public void runInTransactionWithResult_throwsException_logsDbVersion()
            throws DatastoreException {
        // Setup
        doThrow(new IllegalArgumentException()).when(mFunction).apply(any());

        // Execution & assertion
        assertThrows(
                IllegalArgumentException.class,
                () -> mSQLDatastoreManager.runInTransactionWithResult(mFunction));
        ExtendedMockito.verify(
                () ->
                        LogUtil.w(
                                eq(
                                        "Underlying datastore version: "
                                                + MeasurementDbHelper.CURRENT_DATABASE_VERSION)));
    }

    @Test
    public void runInTransactionWithResult_throwsDataStoreException_logsDbVersion()
            throws DatastoreException {
        // Setup
        doThrow(new DatastoreException(null)).when(mFunction).apply(any());

        // Execution
        mSQLDatastoreManager.runInTransactionWithResult(mFunction);

        // Execution & assertion
        ExtendedMockito.verify(
                () ->
                        LogUtil.w(
                                eq(
                                        "Underlying datastore version: "
                                                + MeasurementDbHelper.CURRENT_DATABASE_VERSION)));
    }

    @Test
    public void runInTransaction_throwsException_logsDbVersion() throws DatastoreException {
        // Setup
        doThrow(new IllegalArgumentException()).when(mConsumer).accept(any());

        // Execution & assertion
        assertThrows(
                IllegalArgumentException.class,
                () -> mSQLDatastoreManager.runInTransaction(mConsumer));
        ExtendedMockito.verify(
                () ->
                        LogUtil.w(
                                eq(
                                        "Underlying datastore version: "
                                                + MeasurementDbHelper.CURRENT_DATABASE_VERSION)));
    }

    @Test
    public void runInTransaction_throwsDataStoreException_logsDbVersion()
            throws DatastoreException {
        // Setup
        doThrow(new DatastoreException(null)).when(mConsumer).accept(any());

        // Execution
        mSQLDatastoreManager.runInTransaction(mConsumer);

        // Execution & assertion
        ExtendedMockito.verify(
                () ->
                        LogUtil.w(
                                eq(
                                        "Underlying datastore version: "
                                                + MeasurementDbHelper.CURRENT_DATABASE_VERSION)));
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }
}
