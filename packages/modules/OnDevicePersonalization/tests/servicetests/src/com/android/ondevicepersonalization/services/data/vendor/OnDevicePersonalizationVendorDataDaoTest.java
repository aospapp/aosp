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

package com.android.ondevicepersonalization.services.data.vendor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import androidx.test.core.app.ApplicationProvider;

import com.android.ondevicepersonalization.services.data.OnDevicePersonalizationDbHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(JUnit4.class)
public class OnDevicePersonalizationVendorDataDaoTest {
    private static final String TEST_OWNER = "owner";
    private static final String TEST_CERT_DIGEST = "certDigest";
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private OnDevicePersonalizationVendorDataDao mDao;

    @Before
    public void setup() {
        mDao = OnDevicePersonalizationVendorDataDao.getInstanceForTest(mContext, TEST_OWNER,
                TEST_CERT_DIGEST);
    }

    @Test
    public void testBatchInsert() {
        long timestamp = System.currentTimeMillis();
        addTestData(timestamp);
        long timestampFromDB = mDao.getSyncToken();
        assertEquals(timestamp, timestampFromDB);

        Cursor cursor = mDao.readAllVendorData();
        assertEquals(2, cursor.getCount());
        cursor.close();

        List<VendorData> dataList = new ArrayList<>();
        dataList.add(new VendorData.Builder().setKey("key3").setData(new byte[10]).build());
        dataList.add(new VendorData.Builder().setKey("key4").setData(new byte[10]).build());

        List<String> retainedKeys = new ArrayList<>();
        retainedKeys.add("key2");
        retainedKeys.add("key3");
        retainedKeys.add("key4");
        assertTrue(mDao.batchUpdateOrInsertVendorDataTransaction(dataList, retainedKeys,
                timestamp));
        cursor = mDao.readAllVendorData();
        assertEquals(3, cursor.getCount());
        cursor.close();
    }

    @Test
    public void testGetAllVendorKeys() {
        addTestData(System.currentTimeMillis());
        Set<String> keys = mDao.readAllVendorDataKeys();
        Set<String> expectedKeys = new HashSet<>();
        expectedKeys.add("key");
        expectedKeys.add("key2");
        assertEquals(expectedKeys, keys);
    }

    @Test
    public void testFailReadSyncToken() {
        long timestampFromDB = mDao.getSyncToken();
        assertEquals(-1L, timestampFromDB);
    }

    @Test
    public void testGetVendors() {
        addTestData(System.currentTimeMillis());
        List<Map.Entry<String, String>> vendors = OnDevicePersonalizationVendorDataDao.getVendors(
                mContext);
        assertEquals(1, vendors.size());
        assertEquals(TEST_OWNER, vendors.get(0).getKey());
        assertEquals(TEST_CERT_DIGEST, vendors.get(0).getValue());
        assertEquals(new AbstractMap.SimpleEntry<>(TEST_OWNER, TEST_CERT_DIGEST), vendors.get(0));
    }

    @Test
    public void testGetNoVendors() {
        List<Map.Entry<String, String>> vendors = OnDevicePersonalizationVendorDataDao.getVendors(
                mContext);
        assertEquals(0, vendors.size());
    }

    @Test
    public void testDeleteVendor() {
        addTestData(System.currentTimeMillis());
        OnDevicePersonalizationVendorDataDao.deleteVendorData(mContext, TEST_OWNER,
                TEST_CERT_DIGEST);
        List<Map.Entry<String, String>> vendors = OnDevicePersonalizationVendorDataDao.getVendors(
                mContext);
        assertEquals(0, vendors.size());
        long timestampFromDB = mDao.getSyncToken();
        assertEquals(-1L, timestampFromDB);
    }

    @Test
    public void testGetInstance() {
        OnDevicePersonalizationVendorDataDao instance1Owner1 =
                OnDevicePersonalizationVendorDataDao.getInstance(mContext, "owner1",
                        TEST_CERT_DIGEST);
        OnDevicePersonalizationVendorDataDao instance2Owner1 =
                OnDevicePersonalizationVendorDataDao.getInstance(mContext, "owner1",
                        TEST_CERT_DIGEST);
        assertEquals(instance1Owner1, instance2Owner1);
        OnDevicePersonalizationVendorDataDao instance1Owner2 =
                OnDevicePersonalizationVendorDataDao.getInstance(mContext, "owner2",
                        TEST_CERT_DIGEST);
        assertNotEquals(instance1Owner1, instance1Owner2);
    }

    @After
    public void cleanup() {
        OnDevicePersonalizationDbHelper dbHelper =
                OnDevicePersonalizationDbHelper.getInstanceForTest(mContext);
        dbHelper.getWritableDatabase().close();
        dbHelper.getReadableDatabase().close();
        dbHelper.close();
    }

    private void addTestData(long timestamp) {
        List<VendorData> dataList = new ArrayList<>();
        dataList.add(new VendorData.Builder().setKey("key").setData(new byte[10]).build());
        dataList.add(new VendorData.Builder().setKey("key2").setData(new byte[10]).build());

        List<String> retainedKeys = new ArrayList<>();
        retainedKeys.add("key");
        retainedKeys.add("key2");
        assertTrue(mDao.batchUpdateOrInsertVendorDataTransaction(dataList, retainedKeys,
                timestamp));
    }
}
