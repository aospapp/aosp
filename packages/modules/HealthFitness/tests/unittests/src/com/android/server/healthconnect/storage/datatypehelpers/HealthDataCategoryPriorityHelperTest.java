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

package com.android.server.healthconnect.storage.datatypehelpers;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.database.Cursor;
import android.health.connect.HealthDataCategory;

import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.healthconnect.storage.TransactionManager;
import com.android.server.healthconnect.storage.utils.StorageUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class HealthDataCategoryPriorityHelperTest {

    private static final long APP_PACKAGE_ID = 1;
    private static final long APP_PACKAGE_ID_2 = 2;
    private static final long APP_PACKAGE_ID_3 = 3;
    private static final long APP_PACKAGE_ID_4 = 4;

    private static final String APP_PACKAGE_NAME = "android.healthconnect.mocked.app";
    private static final String APP_PACKAGE_NAME_2 = "android.healthconnect.mocked.app2";
    private static final String APP_PACKAGE_NAME_3 = "android.healthconnect.mocked.app3";
    private static final String APP_PACKAGE_NAME_4 = "android.healthconnect.mocked.app4";
    private static final String APP_ID_PRIORITY_ORDER_COLUMN_NAME = "app_id_priority_order";
    private static final String HEALTH_DATA_CATEGORY_COLUMN_NAME = "health_data_category";
    private static final int APP_ID_PRIORITY_ORDER_COLUMN_INDEX = 2;
    private static final int HEALTH_DATA_CATEGORY_COLUMN_INDEX = 1;

    @Mock private Cursor mCursor;
    @Mock private TransactionManager mTransactionManager;
    @Mock private AppInfoHelper mAppInfoHelper;
    private HealthDataCategoryPriorityHelper mHealthDataCategoryPriorityHelper;
    private MockitoSession mStaticMockSession;

    @Before
    public void setUp() throws Exception {
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(TransactionManager.class)
                        .mockStatic(AppInfoHelper.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        MockitoAnnotations.initMocks(this);

        when(TransactionManager.getInitialisedInstance()).thenReturn(mTransactionManager);
        when(mTransactionManager.read(any())).thenReturn(mCursor);
        when(AppInfoHelper.getInstance()).thenReturn(mAppInfoHelper);
        when(mCursor.moveToNext()).thenReturn(true, false);
        when(mCursor.getColumnIndex(eq(HEALTH_DATA_CATEGORY_COLUMN_NAME)))
                .thenReturn(HEALTH_DATA_CATEGORY_COLUMN_INDEX);
        when(mCursor.getColumnIndex(eq(APP_ID_PRIORITY_ORDER_COLUMN_NAME)))
                .thenReturn(APP_ID_PRIORITY_ORDER_COLUMN_INDEX);

        mHealthDataCategoryPriorityHelper = HealthDataCategoryPriorityHelper.getInstance();
    }

    @After
    public void tearDown() throws Exception {
        mStaticMockSession.finishMocking();
        mHealthDataCategoryPriorityHelper.clearCache();
    }

    @Test
    public void testSetPriority_additionalPackagesDifferentOrder_newPackagesRemoved() {
        List<String> newPriorityOrder =
                List.of(
                        APP_PACKAGE_NAME_4,
                        APP_PACKAGE_NAME_3,
                        APP_PACKAGE_NAME_2,
                        APP_PACKAGE_NAME);

        List<Long> newPriorityOrderId = new ArrayList<>();
        newPriorityOrderId.add(APP_PACKAGE_ID_4);
        newPriorityOrderId.add(APP_PACKAGE_ID_3);
        newPriorityOrderId.add(APP_PACKAGE_ID_2);
        newPriorityOrderId.add(APP_PACKAGE_ID);

        when(mCursor.getInt(eq(HEALTH_DATA_CATEGORY_COLUMN_INDEX)))
                .thenReturn(HealthDataCategory.BODY_MEASUREMENTS);
        when(mCursor.getString(eq(APP_ID_PRIORITY_ORDER_COLUMN_INDEX)))
                .thenReturn(
                        StorageUtils.flattenLongList(List.of(APP_PACKAGE_ID, APP_PACKAGE_ID_2)));
        when(mAppInfoHelper.getAppInfoIds(eq(newPriorityOrder))).thenReturn(newPriorityOrderId);

        mHealthDataCategoryPriorityHelper.setPriorityOrder(
                HealthDataCategory.BODY_MEASUREMENTS, newPriorityOrder);

        verifyPriorityUpdate(List.of(APP_PACKAGE_ID_2, APP_PACKAGE_ID));
    }

    @Test
    public void testSetPriority_reducedPackagesDifferentOrder_oldPackagesAdded() {
        List<String> newPriorityOrder = List.of(APP_PACKAGE_NAME_2);

        List<Long> newPriorityOrderId = new ArrayList<>();
        newPriorityOrderId.add(APP_PACKAGE_ID_2);

        when(mCursor.getInt(eq(HEALTH_DATA_CATEGORY_COLUMN_INDEX)))
                .thenReturn(HealthDataCategory.BODY_MEASUREMENTS);
        when(mCursor.getString(eq(APP_ID_PRIORITY_ORDER_COLUMN_INDEX)))
                .thenReturn(
                        StorageUtils.flattenLongList(List.of(APP_PACKAGE_ID, APP_PACKAGE_ID_2)));
        when(mAppInfoHelper.getAppInfoIds(eq(newPriorityOrder))).thenReturn(newPriorityOrderId);

        mHealthDataCategoryPriorityHelper.setPriorityOrder(
                HealthDataCategory.BODY_MEASUREMENTS, newPriorityOrder);

        verifyPriorityUpdate(List.of(APP_PACKAGE_ID_2, APP_PACKAGE_ID));
    }

    @Test
    public void testSetPriority_samePackagesDifferentOrder_newPrioritySaved() {
        List<String> newPriorityOrder =
                List.of(
                        APP_PACKAGE_NAME_4,
                        APP_PACKAGE_NAME_3,
                        APP_PACKAGE_NAME_2,
                        APP_PACKAGE_NAME);

        List<Long> newPriorityOrderId = new ArrayList<>();
        newPriorityOrderId.add(APP_PACKAGE_ID_4);
        newPriorityOrderId.add(APP_PACKAGE_ID_3);
        newPriorityOrderId.add(APP_PACKAGE_ID_2);
        newPriorityOrderId.add(APP_PACKAGE_ID);

        when(mCursor.getInt(eq(HEALTH_DATA_CATEGORY_COLUMN_INDEX)))
                .thenReturn(HealthDataCategory.BODY_MEASUREMENTS);
        when(mCursor.getString(eq(APP_ID_PRIORITY_ORDER_COLUMN_INDEX)))
                .thenReturn(
                        StorageUtils.flattenLongList(
                                List.of(
                                        APP_PACKAGE_ID,
                                        APP_PACKAGE_ID_2,
                                        APP_PACKAGE_ID_3,
                                        APP_PACKAGE_ID_4)));
        when(mAppInfoHelper.getAppInfoIds(eq(newPriorityOrder))).thenReturn(newPriorityOrderId);

        mHealthDataCategoryPriorityHelper.setPriorityOrder(
                HealthDataCategory.BODY_MEASUREMENTS, newPriorityOrder);

        verifyPriorityUpdate(
                List.of(APP_PACKAGE_ID_4, APP_PACKAGE_ID_3, APP_PACKAGE_ID_2, APP_PACKAGE_ID));
    }

    private void verifyPriorityUpdate(List<Long> priorityOrder) {
        verify(mTransactionManager, times(1))
                .insertOrReplace(
                        argThat(
                                request ->
                                        request.getContentValues()
                                                .getAsString(APP_ID_PRIORITY_ORDER_COLUMN_NAME)
                                                .equalsIgnoreCase(
                                                        StorageUtils.flattenLongList(
                                                                priorityOrder))));

        assertThat(
                        mHealthDataCategoryPriorityHelper.getAppIdPriorityOrder(
                                HealthDataCategory.BODY_MEASUREMENTS))
                .isEqualTo(priorityOrder);
    }
}
