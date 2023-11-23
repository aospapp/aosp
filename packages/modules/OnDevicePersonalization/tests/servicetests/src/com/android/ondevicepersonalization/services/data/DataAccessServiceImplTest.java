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

package com.android.ondevicepersonalization.services.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.ondevicepersonalization.Bid;
import android.ondevicepersonalization.Constants;
import android.ondevicepersonalization.SlotResult;
import android.ondevicepersonalization.aidl.IDataAccessService;
import android.ondevicepersonalization.aidl.IDataAccessServiceCallback;
import android.os.Bundle;

import androidx.test.core.app.ApplicationProvider;

import com.android.ondevicepersonalization.services.data.events.EventUrlHelper;
import com.android.ondevicepersonalization.services.data.events.EventUrlPayload;
import com.android.ondevicepersonalization.services.data.vendor.LocalData;
import com.android.ondevicepersonalization.services.data.vendor.OnDevicePersonalizationLocalDataDao;
import com.android.ondevicepersonalization.services.data.vendor.OnDevicePersonalizationVendorDataDao;
import com.android.ondevicepersonalization.services.data.vendor.VendorData;
import com.android.ondevicepersonalization.services.util.PackageUtils;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@RunWith(JUnit4.class)
public class DataAccessServiceImplTest {
    private final Context mApplicationContext = ApplicationProvider.getApplicationContext();
    private long mTimeMillis = 1000;
    private EventUrlPayload mEventUrlPayload;
    private TestInjector mInjector = new TestInjector();
    private CountDownLatch mLatch = new CountDownLatch(1);
    private Bundle mResult;
    private int mErrorCode = 0;
    private boolean mOnSuccessCalled = false;
    private boolean mOnErrorCalled = false;
    private OnDevicePersonalizationLocalDataDao mLocalDao;
    private OnDevicePersonalizationVendorDataDao mVendorDao;


    @Before
    public void setup() throws Exception {
        mInjector = new TestInjector();
        mVendorDao =  mInjector.getVendorDataDao(mApplicationContext,
                mApplicationContext.getPackageName(),
                PackageUtils.getCertDigest(mApplicationContext,
                        mApplicationContext.getPackageName()));

        mLocalDao =  mInjector.getLocalDataDao(mApplicationContext,
                mApplicationContext.getPackageName(),
                PackageUtils.getCertDigest(mApplicationContext,
                        mApplicationContext.getPackageName()));
    }

    @Test
    public void testRemoteDataLookup() throws Exception {
        addTestData();
        Bundle params = new Bundle();
        params.putStringArray(Constants.EXTRA_LOOKUP_KEYS, new String[]{"key"});
        DataAccessServiceImpl serviceImpl = new DataAccessServiceImpl(
                mApplicationContext.getPackageName(), mApplicationContext,
                true, null, mInjector);
        IDataAccessService serviceProxy = IDataAccessService.Stub.asInterface(serviceImpl);
        serviceProxy.onRequest(
                Constants.DATA_ACCESS_OP_REMOTE_DATA_LOOKUP,
                params,
                new TestCallback());
        mLatch.await();
        assertNotNull(mResult);
        HashMap<String, byte[]> data = mResult.getSerializable(
                Constants.EXTRA_RESULT, HashMap.class);
        assertNotNull(data);
        assertNotNull(data.get("key"));
    }

    @Test
    public void testLocalDataLookup() throws Exception {
        addTestData();
        Bundle params = new Bundle();
        params.putStringArray(Constants.EXTRA_LOOKUP_KEYS, new String[]{"localkey"});
        DataAccessServiceImpl serviceImpl = new DataAccessServiceImpl(
                mApplicationContext.getPackageName(), mApplicationContext,
                true, null, mInjector);
        IDataAccessService serviceProxy = IDataAccessService.Stub.asInterface(serviceImpl);
        serviceProxy.onRequest(
                Constants.DATA_ACCESS_OP_LOCAL_DATA_LOOKUP,
                params,
                new TestCallback());
        mLatch.await();
        assertNotNull(mResult);
        HashMap<String, byte[]> data = mResult.getSerializable(
                Constants.EXTRA_RESULT, HashMap.class);
        assertNotNull(data);
        assertNotNull(data.get("localkey"));
    }

    @Test
    public void testRemoteDataKeyset() throws Exception {
        addTestData();
        Bundle params = new Bundle();
        DataAccessServiceImpl serviceImpl = new DataAccessServiceImpl(
                mApplicationContext.getPackageName(), mApplicationContext,
                true, null, mInjector);
        IDataAccessService serviceProxy = IDataAccessService.Stub.asInterface(serviceImpl);
        serviceProxy.onRequest(
                Constants.DATA_ACCESS_OP_REMOTE_DATA_KEYSET,
                params,
                new TestCallback());
        mLatch.await();
        assertNotNull(mResult);
        HashSet<String> resultSet =
                mResult.getSerializable(Constants.EXTRA_RESULT, HashSet.class);
        assertNotNull(resultSet);
        assertEquals(2, resultSet.size());
        assertTrue(resultSet.contains("key"));
        assertTrue(resultSet.contains("key2"));
    }

    @Test
    public void testLocalDataKeyset() throws Exception {
        addTestData();
        Bundle params = new Bundle();
        DataAccessServiceImpl serviceImpl = new DataAccessServiceImpl(
                mApplicationContext.getPackageName(), mApplicationContext,
                true, null, mInjector);
        IDataAccessService serviceProxy = IDataAccessService.Stub.asInterface(serviceImpl);
        serviceProxy.onRequest(
                Constants.DATA_ACCESS_OP_LOCAL_DATA_KEYSET,
                params,
                new TestCallback());
        mLatch.await();
        assertNotNull(mResult);
        HashSet<String> resultSet =
                mResult.getSerializable(Constants.EXTRA_RESULT, HashSet.class);
        assertNotNull(resultSet);
        assertEquals(2, resultSet.size());
        assertTrue(resultSet.contains("localkey"));
        assertTrue(resultSet.contains("localkey2"));
    }

    @Test
    public void testLocalDataPut() throws Exception {
        addTestData();
        Bundle params = new Bundle();
        params.putStringArray(Constants.EXTRA_LOOKUP_KEYS, new String[]{"localkey"});
        byte[] arr = new byte[100];
        params.putByteArray(Constants.EXTRA_VALUE, arr);
        DataAccessServiceImpl serviceImpl = new DataAccessServiceImpl(
                mApplicationContext.getPackageName(), mApplicationContext,
                true, null, mInjector);
        IDataAccessService serviceProxy = IDataAccessService.Stub.asInterface(serviceImpl);
        serviceProxy.onRequest(
                Constants.DATA_ACCESS_OP_LOCAL_DATA_PUT,
                params,
                new TestCallback());
        mLatch.await();
        assertNotNull(mResult);
        HashMap<String, byte[]> data = mResult.getSerializable(
                Constants.EXTRA_RESULT, HashMap.class);
        assertNotNull(data);
        // Contains previous value
        assertNotNull(data.get("localkey"));
        assertArrayEquals(mLocalDao.readSingleLocalDataRow("localkey"), arr);
    }

    @Test
    public void testLocalDataRemove() throws Exception {
        addTestData();
        Bundle params = new Bundle();
        params.putStringArray(Constants.EXTRA_LOOKUP_KEYS, new String[]{"localkey"});
        DataAccessServiceImpl serviceImpl = new DataAccessServiceImpl(
                mApplicationContext.getPackageName(), mApplicationContext,
                true, null, mInjector);
        IDataAccessService serviceProxy = IDataAccessService.Stub.asInterface(serviceImpl);
        serviceProxy.onRequest(
                Constants.DATA_ACCESS_OP_LOCAL_DATA_REMOVE,
                params,
                new TestCallback());
        mLatch.await();
        assertNotNull(mResult);
        HashMap<String, byte[]> data = mResult.getSerializable(
                Constants.EXTRA_RESULT, HashMap.class);
        assertNotNull(data);
        // Contains previous value
        assertNotNull(data.get("localkey"));
        assertNull(mLocalDao.readSingleLocalDataRow("localkey"));
    }

    @Test
    public void testGetEventUrl() throws Exception {
        Bundle params = new Bundle();
        params.putInt(Constants.EXTRA_EVENT_TYPE, 4);
        params.putString(Constants.EXTRA_BID_ID, "bid5");
        ArrayList<String> bidKeys = new ArrayList<String>();
        SlotResult slotResult =
                new SlotResult.Builder()
                    .setSlotKey("slot1")
                    .addRenderedBidKeys("bid5")
                    .addLoggedBids(
                        new Bid.Builder()
                            .setKey("bid5")
                            .build())
                    .build();
        DataAccessServiceImpl.EventUrlQueryData eventUrlData =
                new DataAccessServiceImpl.EventUrlQueryData(1357, slotResult);
        var serviceImpl = new DataAccessServiceImpl(
                mApplicationContext.getPackageName(), mApplicationContext,
                true, eventUrlData, mInjector);
        IDataAccessService serviceProxy = IDataAccessService.Stub.asInterface(serviceImpl);
        serviceProxy.onRequest(
                Constants.DATA_ACCESS_OP_GET_EVENT_URL,
                params,
                new TestCallback());
        mLatch.await();
        assertNotEquals(null, mResult);
        String eventUrl = mResult.getString(Constants.EXTRA_RESULT);
        assertNotEquals(null, eventUrl);
        EventUrlPayload payload = EventUrlHelper.getEventFromOdpEventUrl(eventUrl);
        assertNotEquals(null, payload);
        assertEquals(4, payload.getEvent().getType());
        assertEquals(1357, payload.getEvent().getQueryId());
        assertEquals(1000, payload.getEvent().getTimeMillis());
        assertEquals("slot1", payload.getEvent().getSlotId());
        assertEquals(mApplicationContext.getPackageName(),
                payload.getEvent().getServicePackageName());
        assertEquals("bid5", payload.getEvent().getBidId());
    }

    @Test
    public void testGetClickUrl() throws Exception {
        Bundle params = new Bundle();
        params.putInt(Constants.EXTRA_EVENT_TYPE, 4);
        params.putString(Constants.EXTRA_BID_ID, "bid5");
        params.putString(Constants.EXTRA_DESTINATION_URL, "http://example.com");
        ArrayList<String> bidKeys = new ArrayList<String>();
        SlotResult slotResult =
                new SlotResult.Builder()
                    .setSlotKey("slot1")
                    .addRenderedBidKeys("bid5")
                    .addLoggedBids(
                        new Bid.Builder()
                            .setKey("bid5")
                            .build())
                    .build();
        DataAccessServiceImpl.EventUrlQueryData eventUrlData =
                new DataAccessServiceImpl.EventUrlQueryData(1357, slotResult);
        var serviceImpl = new DataAccessServiceImpl(
                mApplicationContext.getPackageName(), mApplicationContext,
                true, eventUrlData, mInjector);
        IDataAccessService serviceProxy = IDataAccessService.Stub.asInterface(serviceImpl);
        serviceProxy.onRequest(
                Constants.DATA_ACCESS_OP_GET_EVENT_URL,
                params,
                new TestCallback());
        mLatch.await();
        assertNotEquals(null, mResult);
        String eventUrl = mResult.getString(Constants.EXTRA_RESULT);
        assertNotEquals(null, eventUrl);
        EventUrlPayload payload = EventUrlHelper.getEventFromOdpEventUrl(eventUrl);
        assertNotEquals(null, payload);
        assertEquals(4, payload.getEvent().getType());
        assertEquals(1357, payload.getEvent().getQueryId());
        assertEquals(1000, payload.getEvent().getTimeMillis());
        assertEquals("slot1", payload.getEvent().getSlotId());
        assertEquals(mApplicationContext.getPackageName(),
                payload.getEvent().getServicePackageName());
        assertEquals("bid5", payload.getEvent().getBidId());
        Uri uri = Uri.parse(eventUrl);
        assertEquals(uri.getQueryParameter(EventUrlHelper.URL_LANDING_PAGE_EVENT_KEY), "http://example.com");
    }

    @Test
    public void testLocalDataThrowsNotIncluded() {
        DataAccessServiceImpl serviceImpl = new DataAccessServiceImpl(
                mApplicationContext.getPackageName(), mApplicationContext,
                false, null, mInjector);
        IDataAccessService serviceProxy = IDataAccessService.Stub.asInterface(serviceImpl);
        Bundle params = new Bundle();
        params.putStringArray(Constants.EXTRA_LOOKUP_KEYS, new String[]{"localkey"});
        params.putByteArray(Constants.EXTRA_VALUE, new byte[100]);
        assertThrows(IllegalStateException.class, () -> serviceProxy.onRequest(
                Constants.DATA_ACCESS_OP_LOCAL_DATA_LOOKUP,
                params,
                new TestCallback()));
        assertThrows(IllegalStateException.class, () -> serviceProxy.onRequest(
                Constants.DATA_ACCESS_OP_LOCAL_DATA_KEYSET,
                params,
                new TestCallback()));
        assertThrows(IllegalStateException.class, () -> serviceProxy.onRequest(
                Constants.DATA_ACCESS_OP_LOCAL_DATA_PUT,
                params,
                new TestCallback()));
        assertThrows(IllegalStateException.class, () -> serviceProxy.onRequest(
                Constants.DATA_ACCESS_OP_LOCAL_DATA_REMOVE,
                params,
                new TestCallback()));

    }

    class TestCallback extends IDataAccessServiceCallback.Stub {
        @Override public void onSuccess(Bundle result) {
            mResult = result;
            mOnSuccessCalled = true;
            mLatch.countDown();
        }
        @Override public void onError(int errorCode) {
            mErrorCode = errorCode;
            mOnErrorCalled = true;
            mLatch.countDown();
        }
    }

    class TestInjector extends DataAccessServiceImpl.Injector {
        long getTimeMillis() {
            return mTimeMillis;
        }

        ListeningExecutorService getExecutor() {
            return MoreExecutors.newDirectExecutorService();
        }

        OnDevicePersonalizationVendorDataDao getVendorDataDao(
                Context context, String packageName, String certDigest
        ) {
            return OnDevicePersonalizationVendorDataDao.getInstanceForTest(
                    context, packageName, certDigest);
        }

        OnDevicePersonalizationLocalDataDao getLocalDataDao(
                Context context, String packageName, String certDigest
        ) {
            return OnDevicePersonalizationLocalDataDao.getInstanceForTest(
                    context, packageName, certDigest);
        }
    }

    private void addTestData() {
        List<VendorData> dataList = new ArrayList<>();
        dataList.add(new VendorData.Builder().setKey("key").setData(new byte[10]).build());
        dataList.add(new VendorData.Builder().setKey("key2").setData(new byte[10]).build());

        List<String> retainedKeys = new ArrayList<>();
        retainedKeys.add("key");
        retainedKeys.add("key2");
        mVendorDao.batchUpdateOrInsertVendorDataTransaction(dataList, retainedKeys,
                System.currentTimeMillis());

        mLocalDao.updateOrInsertLocalData(
                new LocalData.Builder().setKey("localkey").setData(new byte[10]).build());
        mLocalDao.updateOrInsertLocalData(
                new LocalData.Builder().setKey("localkey2").setData(new byte[10]).build());

    }

    @After
    public void cleanup() {
        OnDevicePersonalizationDbHelper dbHelper =
                OnDevicePersonalizationDbHelper.getInstanceForTest(mApplicationContext);
        dbHelper.getWritableDatabase().close();
        dbHelper.getReadableDatabase().close();
        dbHelper.close();
    }
}
