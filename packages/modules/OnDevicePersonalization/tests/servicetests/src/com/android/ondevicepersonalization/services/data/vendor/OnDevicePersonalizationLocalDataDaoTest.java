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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.ondevicepersonalization.services.data.OnDevicePersonalizationDbHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

@RunWith(JUnit4.class)
public class OnDevicePersonalizationLocalDataDaoTest {
    private static final String TEST_OWNER = "owner";
    private static final String TEST_CERT_DIGEST = "certDigest";
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private OnDevicePersonalizationLocalDataDao mLocalDao;

    private OnDevicePersonalizationVendorDataDao mVendorDao;

    @Before
    public void setup() {
        mLocalDao = OnDevicePersonalizationLocalDataDao.getInstanceForTest(mContext, TEST_OWNER,
                TEST_CERT_DIGEST);
        mVendorDao = OnDevicePersonalizationVendorDataDao.getInstanceForTest(mContext, TEST_OWNER,
                TEST_CERT_DIGEST);
    }

    @Test
    public void testBasicDaoOperations() {
        mVendorDao.batchUpdateOrInsertVendorDataTransaction(new ArrayList<>(), new ArrayList<>(),
                System.currentTimeMillis());

        byte[] data = new byte[10];
        LocalData localData = new LocalData.Builder().setKey("key").setData(data).build();
        boolean insertResult = mLocalDao.updateOrInsertLocalData(localData);
        assertTrue(insertResult);
        assertArrayEquals(data, mLocalDao.readSingleLocalDataRow("key"));
        assertEquals(null, mLocalDao.readSingleLocalDataRow("nonExistentKey"));
        assertFalse(mLocalDao.deleteLocalDataRow("nonExistentKey"));
        assertTrue(mLocalDao.deleteLocalDataRow("key"));
        assertEquals(null, mLocalDao.readSingleLocalDataRow("key"));
    }

    @Test
    public void testReadAllLocalDataKeys() {
        mVendorDao.batchUpdateOrInsertVendorDataTransaction(new ArrayList<>(), new ArrayList<>(),
                System.currentTimeMillis());

        byte[] data = new byte[10];
        LocalData localData = new LocalData.Builder().setKey("key").setData(data).build();
        mLocalDao.updateOrInsertLocalData(localData);
        localData = new LocalData.Builder().setKey("key2").setData(data).build();
        mLocalDao.updateOrInsertLocalData(localData);
        Set<String> keys = mLocalDao.readAllLocalDataKeys();
        Set<String> expectedKeys = new HashSet<>();
        expectedKeys.add("key");
        expectedKeys.add("key2");
        assertEquals(expectedKeys, keys);
    }

    @Test
    public void testInsertUncreatedTable() {
        byte[] data = new byte[10];
        LocalData localData = new LocalData.Builder().setKey("key").setData(data).build();
        boolean insertResult = mLocalDao.updateOrInsertLocalData(localData);
        assertFalse(insertResult);
    }

    @Test
    public void testReadUncreatedTable() {
        assertEquals(null, mLocalDao.readSingleLocalDataRow("key"));
    }

    @Test
    public void testGetInstance() {
        OnDevicePersonalizationLocalDataDao instance1Owner1 =
                OnDevicePersonalizationLocalDataDao.getInstance(mContext, "owner1",
                        TEST_CERT_DIGEST);
        OnDevicePersonalizationLocalDataDao instance2Owner1 =
                OnDevicePersonalizationLocalDataDao.getInstance(mContext, "owner1",
                        TEST_CERT_DIGEST);
        assertEquals(instance1Owner1, instance2Owner1);
        OnDevicePersonalizationLocalDataDao instance1Owner2 =
                OnDevicePersonalizationLocalDataDao.getInstance(mContext, "owner2",
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
}
