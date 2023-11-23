/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.ons;

import android.content.Intent;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.AvailableNetworkInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.IOns;
import com.android.internal.telephony.ISetOpportunisticDataCallback;
import com.android.internal.telephony.IUpdateAvailableNetworksCallback;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

import androidx.test.InstrumentationRegistry;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

@RunWith(AndroidJUnit4.class)
public class OpportunisticNetworkServiceTest extends ONSBaseTest {
    private String TAG = "ONSTest";
    private String pkgForDebug;
    private int mResult;
    private IOns iOpportunisticNetworkService;

    @Rule
    public final ServiceTestRule mServiceRule = new ServiceTestRule();

    @Before
    public void setUp() throws Exception {
        super.setUp("ONSTest");
        pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        Intent serviceIntent =
                new Intent(InstrumentationRegistry.getTargetContext(),
                        OpportunisticNetworkService.class);
        mServiceRule.startService(serviceIntent);
        iOpportunisticNetworkService = getIOns();
    }

    @Test
    public void testCheckEnable() {
        boolean isEnable = true;
        try {
            iOpportunisticNetworkService.setEnable(false, pkgForDebug);
            isEnable = iOpportunisticNetworkService.isEnabled(pkgForDebug);
        } catch (RemoteException ex) {
            Log.e(TAG, "RemoteException", ex);
        }
        assertEquals(false, isEnable);
    }

    @Test
    public void testSetPreferredDataSubscriptionId() {
        mResult = -1;
        ISetOpportunisticDataCallback callbackStub = new ISetOpportunisticDataCallback.Stub() {
            @Override
            public void onComplete(int result) {
                Log.d(TAG, "callbackStub, mResult end:" + result);
                mResult = result;
            }
        };

        try {
            iOpportunisticNetworkService.setPreferredDataSubscriptionId(5, false, callbackStub,
                    pkgForDebug);
        } catch (RemoteException ex) {
            Log.e(TAG, "RemoteException", ex);
        }
        assertEquals(TelephonyManager.SET_OPPORTUNISTIC_SUB_INACTIVE_SUBSCRIPTION, mResult);
    }

    @Test
    public void testGetPreferredDataSubscriptionId() {
        assertNotNull(iOpportunisticNetworkService);
        mResult = -1;
        try {
            mResult = iOpportunisticNetworkService.getPreferredDataSubscriptionId(pkgForDebug);
            Log.d(TAG, "testGetPreferredDataSubscriptionId: " + mResult);
            assertNotNull(mResult);
        } catch (RemoteException ex) {
            Log.e(TAG, "RemoteException", ex);
        }
    }

    @Test
    public void testUpdateAvailableNetworksWithInvalidArguments() {
        mResult = -1;
        IUpdateAvailableNetworksCallback mCallback = new IUpdateAvailableNetworksCallback.Stub() {
            @Override
            public void onComplete(int result) {
                Log.d(TAG, "mResult end:" + result);
                mResult = result;
            }
        };

        ArrayList<String> mccMncs = new ArrayList<>();
        mccMncs.add("310210");
        AvailableNetworkInfo availableNetworkInfo = new AvailableNetworkInfo(1, 1, mccMncs,
                new ArrayList<Integer>());
        ArrayList<AvailableNetworkInfo> availableNetworkInfos = new ArrayList<>();
        availableNetworkInfos.add(availableNetworkInfo);

        try {
            iOpportunisticNetworkService.updateAvailableNetworks(availableNetworkInfos, mCallback,
                    pkgForDebug);
        } catch (RemoteException ex) {
            Log.e(TAG, "RemoteException", ex);
        }
        assertEquals(TelephonyManager.UPDATE_AVAILABLE_NETWORKS_INVALID_ARGUMENTS, mResult);
    }

    @Test
    public void testUpdateAvailableNetworksWithSuccess() {
        mResult = -1;
        IUpdateAvailableNetworksCallback mCallback = new IUpdateAvailableNetworksCallback.Stub() {
            @Override
            public void onComplete(int result) {
                Log.d(TAG, "mResult end:" + result);
                mResult = result;
            }
        };
        ArrayList<AvailableNetworkInfo> availableNetworkInfos = new ArrayList<>();
        try {
            iOpportunisticNetworkService.setEnable(false, pkgForDebug);
            iOpportunisticNetworkService.updateAvailableNetworks(availableNetworkInfos, mCallback,
                    pkgForDebug);
        } catch (RemoteException ex) {
            Log.e(TAG, "RemoteException", ex);
        }
        assertEquals(TelephonyManager.UPDATE_AVAILABLE_NETWORKS_SUCCESS, mResult);
    }

    private IOns getIOns() {
        return IOns.Stub.asInterface(ServiceManager.getService("ions"));
    }
}
