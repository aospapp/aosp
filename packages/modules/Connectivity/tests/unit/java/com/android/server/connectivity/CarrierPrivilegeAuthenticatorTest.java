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

package com.android.server.connectivity;

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.telephony.TelephonyManager.ACTION_MULTI_SIM_CONFIG_CHANGED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.NetworkCapabilities;
import android.net.TelephonyNetworkSpecifier;
import android.os.Build;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.net.module.util.CollectionUtils;
import com.android.networkstack.apishim.TelephonyManagerShimImpl;
import com.android.networkstack.apishim.common.TelephonyManagerShim.CarrierPrivilegesListenerShim;
import com.android.networkstack.apishim.common.UnsupportedApiLevelException;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.Map;

/**
 * Tests for CarrierPrivilegeAuthenticatorTest.
 *
 * Build, install and run with:
 *  atest FrameworksNetTests:CarrierPrivilegeAuthenticatorTest
 */
@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class CarrierPrivilegeAuthenticatorTest {
    private static final int SUBSCRIPTION_COUNT = 2;
    private static final int TEST_SUBSCRIPTION_ID = 1;

    @NonNull private final Context mContext;
    @NonNull private final TelephonyManager mTelephonyManager;
    @NonNull private final TelephonyManagerShimImpl mTelephonyManagerShim;
    @NonNull private final PackageManager mPackageManager;
    @NonNull private TestCarrierPrivilegeAuthenticator mCarrierPrivilegeAuthenticator;
    private final int mCarrierConfigPkgUid = 12345;
    private final String mTestPkg = "com.android.server.connectivity.test";

    public class TestCarrierPrivilegeAuthenticator extends CarrierPrivilegeAuthenticator {
        TestCarrierPrivilegeAuthenticator(@NonNull final Context c,
                @NonNull final TelephonyManager t) {
            super(c, t, mTelephonyManagerShim);
        }
        @Override
        protected int getSlotIndex(int subId) {
            if (SubscriptionManager.DEFAULT_SUBSCRIPTION_ID == subId) return TEST_SUBSCRIPTION_ID;
            return subId;
        }
    }

    public CarrierPrivilegeAuthenticatorTest() {
        mContext = mock(Context.class);
        mTelephonyManager = mock(TelephonyManager.class);
        mTelephonyManagerShim = mock(TelephonyManagerShimImpl.class);
        mPackageManager = mock(PackageManager.class);
    }

    @Before
    public void setUp() throws Exception {
        doReturn(SUBSCRIPTION_COUNT).when(mTelephonyManager).getActiveModemCount();
        doReturn(mTestPkg).when(mTelephonyManagerShim)
                .getCarrierServicePackageNameForLogicalSlot(anyInt());
        doReturn(mPackageManager).when(mContext).getPackageManager();
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = mCarrierConfigPkgUid;
        doReturn(applicationInfo).when(mPackageManager).getApplicationInfo(eq(mTestPkg), anyInt());
        mCarrierPrivilegeAuthenticator =
                new TestCarrierPrivilegeAuthenticator(mContext, mTelephonyManager);
    }

    private IntentFilter getIntentFilter() {
        final ArgumentCaptor<IntentFilter> captor = ArgumentCaptor.forClass(IntentFilter.class);
        verify(mContext).registerReceiver(any(), captor.capture(), any(), any());
        return captor.getValue();
    }

    private Map<Integer, CarrierPrivilegesListenerShim> getCarrierPrivilegesListeners() {
        final ArgumentCaptor<Integer> slotCaptor = ArgumentCaptor.forClass(Integer.class);
        final ArgumentCaptor<CarrierPrivilegesListenerShim> listenerCaptor =
                ArgumentCaptor.forClass(CarrierPrivilegesListenerShim.class);
        try {
            verify(mTelephonyManagerShim, atLeastOnce()).addCarrierPrivilegesListener(
                    slotCaptor.capture(), any(), listenerCaptor.capture());
        } catch (UnsupportedApiLevelException e) {
        }
        final Map<Integer, CarrierPrivilegesListenerShim> result =
                CollectionUtils.assoc(slotCaptor.getAllValues(), listenerCaptor.getAllValues());
        clearInvocations(mTelephonyManagerShim);
        return result;
    }

    private Intent buildTestMultiSimConfigBroadcastIntent() {
        return new Intent(ACTION_MULTI_SIM_CONFIG_CHANGED);
    }
    @Test
    public void testConstructor() throws Exception {
        verify(mContext).registerReceiver(
                        eq(mCarrierPrivilegeAuthenticator),
                        any(IntentFilter.class),
                        any(),
                        any());
        final IntentFilter filter = getIntentFilter();
        assertEquals(1, filter.countActions());
        assertTrue(filter.hasAction(ACTION_MULTI_SIM_CONFIG_CHANGED));

        // Two listeners originally registered, one for slot 0 and one for slot 1
        final Map<Integer, CarrierPrivilegesListenerShim> initialListeners =
                getCarrierPrivilegesListeners();
        assertNotNull(initialListeners.get(0));
        assertNotNull(initialListeners.get(1));
        assertEquals(2, initialListeners.size());

        final NetworkCapabilities.Builder ncBuilder = new NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .setNetworkSpecifier(new TelephonyNetworkSpecifier(0));

        assertTrue(mCarrierPrivilegeAuthenticator.hasCarrierPrivilegeForNetworkCapabilities(
                mCarrierConfigPkgUid, ncBuilder.build()));
        assertFalse(mCarrierPrivilegeAuthenticator.hasCarrierPrivilegeForNetworkCapabilities(
                mCarrierConfigPkgUid + 1, ncBuilder.build()));
    }

    @Test
    public void testMultiSimConfigChanged() throws Exception {
        // Two listeners originally registered, one for slot 0 and one for slot 1
        final Map<Integer, CarrierPrivilegesListenerShim> initialListeners =
                getCarrierPrivilegesListeners();
        assertNotNull(initialListeners.get(0));
        assertNotNull(initialListeners.get(1));
        assertEquals(2, initialListeners.size());

        doReturn(1).when(mTelephonyManager).getActiveModemCount();
        mCarrierPrivilegeAuthenticator.onReceive(
                mContext, buildTestMultiSimConfigBroadcastIntent());
        // Check all listeners have been removed
        for (CarrierPrivilegesListenerShim listener : initialListeners.values()) {
            verify(mTelephonyManagerShim).removeCarrierPrivilegesListener(eq(listener));
        }

        // Expect a new CarrierPrivilegesListener to have been registered for slot 0, and none other
        final Map<Integer, CarrierPrivilegesListenerShim> newListeners =
                getCarrierPrivilegesListeners();
        assertNotNull(newListeners.get(0));
        assertEquals(1, newListeners.size());

        final TelephonyNetworkSpecifier specifier = new TelephonyNetworkSpecifier(0);
        final NetworkCapabilities nc = new NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .setNetworkSpecifier(specifier)
                .build();
        assertTrue(mCarrierPrivilegeAuthenticator.hasCarrierPrivilegeForNetworkCapabilities(
                mCarrierConfigPkgUid, nc));
        assertFalse(mCarrierPrivilegeAuthenticator.hasCarrierPrivilegeForNetworkCapabilities(
                mCarrierConfigPkgUid + 1, nc));
    }

    @Test
    public void testOnCarrierPrivilegesChanged() throws Exception {
        final CarrierPrivilegesListenerShim listener = getCarrierPrivilegesListeners().get(0);

        final TelephonyNetworkSpecifier specifier = new TelephonyNetworkSpecifier(0);
        final NetworkCapabilities nc = new NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .setNetworkSpecifier(specifier)
                .build();

        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = mCarrierConfigPkgUid + 1;
        doReturn(applicationInfo).when(mPackageManager).getApplicationInfo(eq(mTestPkg), anyInt());
        listener.onCarrierPrivilegesChanged(Collections.emptyList(), new int[] {});

        assertFalse(mCarrierPrivilegeAuthenticator.hasCarrierPrivilegeForNetworkCapabilities(
                mCarrierConfigPkgUid, nc));
        assertTrue(mCarrierPrivilegeAuthenticator.hasCarrierPrivilegeForNetworkCapabilities(
                mCarrierConfigPkgUid + 1, nc));
    }

    @Test
    public void testDefaultSubscription() throws Exception {
        final NetworkCapabilities.Builder ncBuilder = new NetworkCapabilities.Builder();
        ncBuilder.addTransportType(TRANSPORT_CELLULAR);
        assertFalse(mCarrierPrivilegeAuthenticator.hasCarrierPrivilegeForNetworkCapabilities(
                mCarrierConfigPkgUid, ncBuilder.build()));

        ncBuilder.setNetworkSpecifier(new TelephonyNetworkSpecifier(0));
        assertTrue(mCarrierPrivilegeAuthenticator.hasCarrierPrivilegeForNetworkCapabilities(
                mCarrierConfigPkgUid, ncBuilder.build()));

        // The builder for NetworkCapabilities doesn't allow removing the transport as long as a
        // specifier is set, so unset it first. TODO : fix the builder
        ncBuilder.setNetworkSpecifier(null);
        ncBuilder.removeTransportType(TRANSPORT_CELLULAR);
        ncBuilder.addTransportType(TRANSPORT_WIFI);
        ncBuilder.setNetworkSpecifier(new TelephonyNetworkSpecifier(0));
        assertFalse(mCarrierPrivilegeAuthenticator.hasCarrierPrivilegeForNetworkCapabilities(
                mCarrierConfigPkgUid, ncBuilder.build()));
    }
}
