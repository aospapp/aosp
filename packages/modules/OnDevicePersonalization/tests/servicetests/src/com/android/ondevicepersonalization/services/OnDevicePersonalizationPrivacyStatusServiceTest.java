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

package com.android.ondevicepersonalization.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.ondevicepersonalization.aidl.IPrivacyStatusServiceCallback;
import android.os.IBinder;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.rule.ServiceTestRule;

import com.android.ondevicepersonalization.services.data.user.PrivacySignal;
import com.android.ondevicepersonalization.services.data.user.RawUserData;
import com.android.ondevicepersonalization.services.data.user.UserDataCollector;
import com.android.ondevicepersonalization.services.data.user.UserDataDao;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;

@RunWith(JUnit4.class)
public class OnDevicePersonalizationPrivacyStatusServiceTest {
    @Rule
    public final ServiceTestRule serviceRule = new ServiceTestRule();
    private Context mContext = ApplicationProvider.getApplicationContext();
    private OnDevicePersonalizationPrivacyStatusServiceDelegate mBinder;
    private PrivacySignal mPrivacySignal;
    private RawUserData mUserData;
    private UserDataCollector mUserDataCollector;
    private UserDataDao mUserDataDao;

    @Before
    public void setup() throws Exception {
        mBinder = new OnDevicePersonalizationPrivacyStatusServiceDelegate(mContext);
        mPrivacySignal = PrivacySignal.getInstance();
        mUserData = RawUserData.getInstance();
        mUserDataCollector = UserDataCollector.getInstanceForTest(mContext);
        mUserDataDao = UserDataDao.getInstanceForTest(mContext);
    }

    @Test
    public void testSetKidStatusChanged() throws Exception {
        assertTrue(mPrivacySignal.isKidStatusEnabled());

        populateUserData();
        assertNotEquals(0, mUserData.timeMillis);
        assertTrue(mUserDataCollector.isInitialized());

        CountDownLatch latch = new CountDownLatch(1);
        mBinder.setKidStatus(false, new IPrivacyStatusServiceCallback() {
            @Override
            public void onSuccess() {
                latch.countDown();
            }

            @Override
            public void onFailure(int errorCode) {
                Assert.fail();
            }

            @Override
            public IBinder asBinder() {
                return null;
            }
        });

        latch.await();

        assertFalse(mPrivacySignal.isKidStatusEnabled());

        assertEquals(0, mUserData.timeMillis);
        assertFalse(mUserDataCollector.isInitialized());
        assertEquals(0, mUserData.timeMillis);
        Cursor appUsageCursor = mUserDataDao.readAppUsageInLastXDays(30);
        assertNotNull(appUsageCursor);
        assertEquals(0, appUsageCursor.getCount());
        Cursor locationCursor = mUserDataDao.readLocationInLastXDays(30);
        assertNotNull(locationCursor);
        assertEquals(0, locationCursor.getCount());
    }

    @Test
    public void testSetKidStatusIfCallbackMissing() throws Exception {
        assertThrows(NullPointerException.class, () -> {
            mBinder.setKidStatus(false, null);
        });
    }

    @Test
    public void testSetKidStatusNoOps() throws Exception {
        mPrivacySignal.setKidStatusEnabled(false);

        populateUserData();
        assertNotEquals(0, mUserData.timeMillis);
        long timeMillis = mUserData.timeMillis;
        assertTrue(mUserDataCollector.isInitialized());
        Cursor appUsageCursor = mUserDataDao.readAppUsageInLastXDays(30);
        Cursor locationCursor = mUserDataDao.readLocationInLastXDays(30);
        assertNotNull(appUsageCursor);
        assertNotNull(locationCursor);
        int appUsageCount = appUsageCursor.getCount();
        int locationCount = locationCursor.getCount();
        assertTrue(appUsageCount > 0);
        assertTrue(locationCount > 0);

        CountDownLatch latch = new CountDownLatch(1);
        mBinder.setKidStatus(false, new IPrivacyStatusServiceCallback() {
            @Override
            public void onSuccess() {
                latch.countDown();
            }

            @Override
            public void onFailure(int errorCode) {
                Assert.fail();
            }

            @Override
            public IBinder asBinder() {
                return null;
            }
        });

        latch.await();

        assertFalse(mPrivacySignal.isKidStatusEnabled());
        // Adult data should not be roll-back'ed
        assertEquals(timeMillis, mUserData.timeMillis);
        assertTrue(mUserDataCollector.isInitialized());
        Cursor newAppUsageCursor = mUserDataDao.readAppUsageInLastXDays(30);
        Cursor newLocationCursor = mUserDataDao.readLocationInLastXDays(30);
        assertNotNull(newAppUsageCursor);
        assertNotNull(newLocationCursor);
        assertEquals(appUsageCount, newAppUsageCursor.getCount());
        assertEquals(locationCount, newLocationCursor.getCount());
    }

    @Test
    public void testWithBoundService() throws TimeoutException {
        Intent serviceIntent = new Intent(mContext,
                OnDevicePersonalizationPrivacyStatusServiceImpl.class);
        IBinder binder = serviceRule.bindService(serviceIntent);
        assertTrue(binder instanceof OnDevicePersonalizationPrivacyStatusServiceDelegate);
    }

    @After
    public void tearDown() throws Exception {
        mPrivacySignal.setKidStatusEnabled(true);
        mUserDataCollector.clearUserData(mUserData);
        mUserDataCollector.clearMetadata();
        mUserDataCollector.clearDatabase();
    }

    private void populateUserData() {
        mUserDataCollector.updateUserData(mUserData);
        // Populate the database in case that no records are collected by UserDataCollector.
        long currentTimeMillis = System.currentTimeMillis();
        mUserDataDao.insertAppUsageStatsData("testApp", 0, currentTimeMillis, 0);
        mUserDataDao.insertLocationHistoryData(currentTimeMillis,
                "111.11111", "-222.22222", 1, true);
    }
}
