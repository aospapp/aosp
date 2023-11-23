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

package com.android.ondevicepersonalization.services.maintenance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.job.JobParameters;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.ondevicepersonalization.services.OnDevicePersonalizationExecutors;
import com.android.ondevicepersonalization.services.data.OnDevicePersonalizationDbHelper;
import com.android.ondevicepersonalization.services.data.vendor.OnDevicePersonalizationVendorDataDao;
import com.android.ondevicepersonalization.services.data.vendor.VendorData;
import com.android.ondevicepersonalization.services.util.PackageUtils;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RunWith(JUnit4.class)
public class OnDevicePersonalizationMaintenanceJobServiceTest {
    private static final String TEST_OWNER = "owner";
    private static final String TEST_CERT_DIGEST = "certDigest";
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private OnDevicePersonalizationVendorDataDao mTestDao;
    private OnDevicePersonalizationVendorDataDao mDao;
    private OnDevicePersonalizationMaintenanceJobService mSpyService;

    private static void addTestData(long timestamp, OnDevicePersonalizationVendorDataDao dao) {
        List<VendorData> dataList = new ArrayList<>();
        dataList.add(new VendorData.Builder().setKey("key").setData(new byte[10]).build());
        dataList.add(new VendorData.Builder().setKey("key2").setData(new byte[10]).build());
        List<String> retainedKeys = new ArrayList<>();
        retainedKeys.add("key");
        retainedKeys.add("key2");
        assertTrue(dao.batchUpdateOrInsertVendorDataTransaction(dataList, retainedKeys,
                timestamp));
    }

    @Before
    public void setup() throws Exception {
        mTestDao = OnDevicePersonalizationVendorDataDao.getInstanceForTest(mContext, TEST_OWNER,
                TEST_CERT_DIGEST);
        mDao = OnDevicePersonalizationVendorDataDao.getInstanceForTest(mContext,
                mContext.getPackageName(),
                PackageUtils.getCertDigest(mContext, mContext.getPackageName()));

        mSpyService = spy(new OnDevicePersonalizationMaintenanceJobService());
    }

    @Test
    public void onStartJobTest() {
        MockitoSession session = ExtendedMockito.mockitoSession().spyStatic(
                OnDevicePersonalizationExecutors.class).strictness(
                Strictness.LENIENT).startMocking();
        try {
            doNothing().when(mSpyService).jobFinished(any(), anyBoolean());
            doReturn(mContext.getPackageManager()).when(mSpyService).getPackageManager();
            ExtendedMockito.doReturn(MoreExecutors.newDirectExecutorService()).when(
                    OnDevicePersonalizationExecutors::getBackgroundExecutor);
            ExtendedMockito.doReturn(MoreExecutors.newDirectExecutorService()).when(
                    OnDevicePersonalizationExecutors::getLightweightExecutor);

            boolean result = mSpyService.onStartJob(mock(JobParameters.class));
            assertTrue(result);
            verify(mSpyService, times(1)).jobFinished(any(), eq(false));
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void onStopJobTest() {
        MockitoSession session = ExtendedMockito.mockitoSession().strictness(
                Strictness.LENIENT).startMocking();
        try {
            assertTrue(mSpyService.onStopJob(mock(JobParameters.class)));
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testVendorDataCleanup() throws Exception {
        addTestData(System.currentTimeMillis(), mTestDao);
        addTestData(System.currentTimeMillis(), mDao);
        OnDevicePersonalizationMaintenanceJobService.cleanupVendorData(mContext);
        List<Map.Entry<String, String>> vendors = OnDevicePersonalizationVendorDataDao.getVendors(
                mContext);
        assertEquals(1, vendors.size());
        assertEquals(new AbstractMap.SimpleEntry<>(mContext.getPackageName(),
                PackageUtils.getCertDigest(mContext, mContext.getPackageName())), vendors.get(0));
    }

    @After
    public void cleanup() {
        OnDevicePersonalizationDbHelper dbHelper =
                OnDevicePersonalizationDbHelper.getInstanceForTest(mContext);
        dbHelper.getWritableDatabase().close();
        dbHelper.getReadableDatabase().close();
        dbHelper.close();
    }
}
