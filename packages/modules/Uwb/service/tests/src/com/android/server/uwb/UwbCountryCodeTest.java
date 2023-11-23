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

package com.android.server.uwb;

import static com.android.server.uwb.data.UwbUciConstants.STATUS_CODE_OK;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyDouble;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.ActiveCountryCodeChangedCallback;
import android.os.Handler;
import android.os.test.TestLooper;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.server.uwb.data.UwbUciConstants;
import com.android.server.uwb.jni.NativeUwbManager;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.uwb.UwbCountryCode}.
 */
@SmallTest
public class UwbCountryCodeTest {
    private static final String TEST_COUNTRY_CODE = "US";
    private static final String TEST_COUNTRY_CODE_OTHER = "JP";
    private static final int TEST_SUBSCRIPTION_ID = 0;
    private static final int TEST_SLOT_IDX = 0;
    private static final int TEST_SUBSCRIPTION_ID_OTHER = 1;
    private static final int TEST_SLOT_IDX_OTHER = 1;

    @Mock Context mContext;
    @Mock TelephonyManager mTelephonyManager;
    @Mock SubscriptionManager mSubscriptionManager;
    @Mock LocationManager mLocationManager;
    @Mock Geocoder mGeocoder;
    @Mock WifiManager mWifiManager;
    @Mock NativeUwbManager mNativeUwbManager;
    @Mock UwbInjector mUwbInjector;
    @Mock PackageManager mPackageManager;
    @Mock Location mLocation;
    private TestLooper mTestLooper;
    private UwbCountryCode mUwbCountryCode;

    @Captor
    private ArgumentCaptor<BroadcastReceiver> mTelephonyCountryCodeReceiverCaptor;
    @Captor
    private ArgumentCaptor<ActiveCountryCodeChangedCallback> mWifiCountryCodeReceiverCaptor;
    @Captor
    private ArgumentCaptor<LocationListener> mLocationListenerCaptor;
    @Captor
    private ArgumentCaptor<Geocoder.GeocodeListener> mGeocodeListenerCaptor;

    /**
     * Setup test.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestLooper = new TestLooper();

        when(mContext.createContext(any())).thenReturn(mContext);
        when(mContext.getSystemService(TelephonyManager.class))
                .thenReturn(mTelephonyManager);
        when(mContext.getSystemService(SubscriptionManager.class))
                .thenReturn(mSubscriptionManager);
        when(mContext.getSystemService(WifiManager.class))
                .thenReturn(mWifiManager);
        when(mContext.getSystemService(LocationManager.class))
                .thenReturn(mLocationManager);
        when(mSubscriptionManager.getActiveSubscriptionInfoList()).thenReturn(List.of(
                new SubscriptionInfo(
                TEST_SUBSCRIPTION_ID, "", TEST_SLOT_IDX, "", "", 0, 0, "", 0, null, "", "", "",
                        true /* isEmbedded */, null, "", 25, false, null, false, 0, 0, 0, null,
                        null, true, 0),
                new SubscriptionInfo(
                        TEST_SUBSCRIPTION_ID_OTHER, "", TEST_SLOT_IDX_OTHER, "", "", 0, 0, "", 0,
                        null, "", "", "", true /* isEmbedded */, null, "", 25, false, null, false,
                        0, 0, 0, null, null, true, 0)
        ));
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mLocation.getLatitude()).thenReturn(0.0);
        when(mLocation.getLongitude()).thenReturn(0.0);
        when(mUwbInjector.makeGeocoder()).thenReturn(mGeocoder);
        when(mUwbInjector.isGeocoderPresent()).thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)).thenReturn(true);
        when(mNativeUwbManager.setCountryCode(any())).thenReturn(
                (byte) STATUS_CODE_OK);
        mUwbCountryCode = new UwbCountryCode(
                mContext, mNativeUwbManager, new Handler(mTestLooper.getLooper()), mUwbInjector);
    }

    @Test
    public void testSetDefaultCountryCodeWhenNoCountryCodeAvailable() {
        mUwbCountryCode.initialize();
        verify(mNativeUwbManager).setCountryCode(
                UwbCountryCode.DEFAULT_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testInitializeCountryCodeFromTelephony() {
        when(mTelephonyManager.getNetworkCountryIso(anyInt())).thenReturn(TEST_COUNTRY_CODE);
        mUwbCountryCode.initialize();
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testSkipWhenExceptionThrownInInitializeCountryCodeFromTelephony() {
        doThrow(new IllegalArgumentException()).when(mTelephonyManager).getNetworkCountryIso(
                anyInt());
        mUwbCountryCode.initialize();
        verify(mNativeUwbManager, never()).setCountryCode(any());
    }

    @Test
    public void testInitializeCountryCodeFromTelephonyVerifyListener() {
        UwbCountryCode.CountryCodeChangedListener listener = mock(
                UwbCountryCode.CountryCodeChangedListener.class);
        mUwbCountryCode.addListener(listener);
        when(mTelephonyManager.getNetworkCountryIso(anyInt())).thenReturn(TEST_COUNTRY_CODE);
        mUwbCountryCode.initialize();
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
        verify(listener).onCountryCodeChanged(TEST_COUNTRY_CODE);
    }

    @Test
    public void testSetCountryCodeFromTelephony() {
        when(mTelephonyManager.getNetworkCountryIso(anyInt())).thenReturn(TEST_COUNTRY_CODE);
        mUwbCountryCode.initialize();
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
        clearInvocations(mNativeUwbManager);

        assertEquals(Pair.create(STATUS_CODE_OK, TEST_COUNTRY_CODE),
                mUwbCountryCode.setCountryCode(false));
        // already set.
        verify(mNativeUwbManager, never()).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testSetCountryCodeFromLocation() {
        when(mLocationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER))
                .thenReturn(mLocation);
        mUwbCountryCode.initialize();
        verify(mGeocoder).getFromLocation(
                anyDouble(), anyDouble(), anyInt(), mGeocodeListenerCaptor.capture());
        Address mockAddress = mock(Address.class);
        when(mockAddress.getCountryCode()).thenReturn(TEST_COUNTRY_CODE);
        List<Address> addresses = List.of(mockAddress);
        mGeocodeListenerCaptor.getValue().onGeocode(addresses);
        mTestLooper.dispatchAll();
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
        clearInvocations(mNativeUwbManager);

        assertEquals(Pair.create(STATUS_CODE_OK, TEST_COUNTRY_CODE),
                mUwbCountryCode.setCountryCode(false));
        // already set.
        verify(mNativeUwbManager, never()).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testSetCountryCodeWithForceUpdateFromTelephony() {
        when(mTelephonyManager.getNetworkCountryIso(anyInt())).thenReturn(TEST_COUNTRY_CODE);
        mUwbCountryCode.initialize();
        clearInvocations(mNativeUwbManager);

        assertEquals(Pair.create(STATUS_CODE_OK, TEST_COUNTRY_CODE),
                mUwbCountryCode.setCountryCode(true));
        // set again
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testSetCountryCodeFromOemWhenTelephonyAndWifiNotAvailable() {
        when(mTelephonyManager.getNetworkCountryIso(anyInt())).thenReturn(TEST_COUNTRY_CODE);
        mUwbCountryCode.initialize();
        clearInvocations(mNativeUwbManager);

        assertEquals(Pair.create(STATUS_CODE_OK, TEST_COUNTRY_CODE),
                mUwbCountryCode.setCountryCode(false));
        // already set.
        verify(mNativeUwbManager, never()).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testSetCountryCode_statusError() {
        when(mTelephonyManager.getNetworkCountryIso(anyInt())).thenReturn(TEST_COUNTRY_CODE);
        mUwbCountryCode.initialize();
        clearInvocations(mNativeUwbManager);

        when(mNativeUwbManager.setCountryCode(any())).thenReturn(
                (byte) UwbUciConstants.STATUS_CODE_FAILED);
        assertEquals(Pair.create(UwbUciConstants.STATUS_CODE_FAILED, TEST_COUNTRY_CODE),
                mUwbCountryCode.setCountryCode(true));
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testChangeInTelephonyCountryCode() {
        mUwbCountryCode.initialize();
        verify(mContext).registerReceiver(
                mTelephonyCountryCodeReceiverCaptor.capture(), any(), any(), any());
        Intent intent = new Intent(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED)
                .putExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY, TEST_COUNTRY_CODE)
                        .putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, TEST_SLOT_IDX);
        mTelephonyCountryCodeReceiverCaptor.getValue().onReceive(mock(Context.class), intent);
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testChangeInWifiCountryCode() {
        mUwbCountryCode.initialize();
        verify(mWifiManager).registerActiveCountryCodeChangedCallback(
                any(), mWifiCountryCodeReceiverCaptor.capture());
        mWifiCountryCodeReceiverCaptor.getValue().onActiveCountryCodeChanged(TEST_COUNTRY_CODE);
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testChangeInLocationCountryCode() {
        mUwbCountryCode.initialize();
        verify(mLocationManager).requestLocationUpdates(
                anyString(), anyLong(), anyFloat(), mLocationListenerCaptor.capture());
        mLocationListenerCaptor.getValue().onLocationChanged(mLocation);
        verify(mGeocoder).getFromLocation(
                anyDouble(), anyDouble(), anyInt(), mGeocodeListenerCaptor.capture());
        Address mockAddress = mock(Address.class);
        when(mockAddress.getCountryCode()).thenReturn(TEST_COUNTRY_CODE);
        List<Address> addresses = List.of(mockAddress);
        mGeocodeListenerCaptor.getValue().onGeocode(addresses);
        mTestLooper.dispatchAll();
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testChangeInTelephonyCountryCodeWhenWifiAndLocationCountryCodeAvailable() {
        mUwbCountryCode.initialize();
        verify(mWifiManager).registerActiveCountryCodeChangedCallback(
                any(), mWifiCountryCodeReceiverCaptor.capture());
        mWifiCountryCodeReceiverCaptor.getValue().onActiveCountryCodeChanged(TEST_COUNTRY_CODE);
        verify(mContext).registerReceiver(
                mTelephonyCountryCodeReceiverCaptor.capture(), any(), any(), any());
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
        verify(mLocationManager).requestLocationUpdates(
                anyString(), anyLong(), anyFloat(), mLocationListenerCaptor.capture());
        mLocationListenerCaptor.getValue().onLocationChanged(mLocation);
        verify(mGeocoder).getFromLocation(
                anyDouble(), anyDouble(), anyInt(), mGeocodeListenerCaptor.capture());
        Address mockAddress = mock(Address.class);
        when(mockAddress.getCountryCode()).thenReturn(TEST_COUNTRY_CODE);
        List<Address> addresses = List.of(mockAddress);
        mGeocodeListenerCaptor.getValue().onGeocode(addresses);
        mTestLooper.dispatchAll();

        Intent intent = new Intent(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED)
                .putExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY, TEST_COUNTRY_CODE_OTHER)
                .putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, TEST_SLOT_IDX);
        mTelephonyCountryCodeReceiverCaptor.getValue().onReceive(mock(Context.class), intent);
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE_OTHER.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testUseLastKnownTelephonyCountryCodeWhenWifiAndTelephonyCountryCodeNotAvailable() {
        mUwbCountryCode.initialize();
        verify(mWifiManager).registerActiveCountryCodeChangedCallback(
                any(), mWifiCountryCodeReceiverCaptor.capture());
        mWifiCountryCodeReceiverCaptor.getValue().onActiveCountryCodeChanged("");
        verify(mContext).registerReceiver(
                mTelephonyCountryCodeReceiverCaptor.capture(), any(), any(), any());
        verify(mNativeUwbManager).setCountryCode(
                UwbCountryCode.DEFAULT_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));

        Intent intent = new Intent(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED)
                .putExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY, "")
                .putExtra(UwbCountryCode.EXTRA_LAST_KNOWN_NETWORK_COUNTRY, TEST_COUNTRY_CODE)
                .putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, TEST_SLOT_IDX);
        mTelephonyCountryCodeReceiverCaptor.getValue().onReceive(mock(Context.class), intent);
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testChangeInTelephonyCountryCodeWhenMoreThanOneSimWifiCountryCodeAvailable() {
        mUwbCountryCode.initialize();
        verify(mWifiManager).registerActiveCountryCodeChangedCallback(
                any(), mWifiCountryCodeReceiverCaptor.capture());
        mWifiCountryCodeReceiverCaptor.getValue().onActiveCountryCodeChanged(TEST_COUNTRY_CODE);
        verify(mContext).registerReceiver(
                mTelephonyCountryCodeReceiverCaptor.capture(), any(), any(), any());
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));

        Intent intent = new Intent(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED)
                .putExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY, TEST_COUNTRY_CODE_OTHER)
                .putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, TEST_SLOT_IDX);
        mTelephonyCountryCodeReceiverCaptor.getValue().onReceive(mock(Context.class), intent);
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE_OTHER.getBytes(StandardCharsets.UTF_8));

        intent = new Intent(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED)
                .putExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY, TEST_COUNTRY_CODE_OTHER)
                .putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, TEST_SLOT_IDX_OTHER);
        mTelephonyCountryCodeReceiverCaptor.getValue().onReceive(mock(Context.class), intent);
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE_OTHER.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testForceOverrideCodeWhenTelephonyAndWifiAvailable() {
        when(mTelephonyManager.getNetworkCountryIso(anyInt())).thenReturn(TEST_COUNTRY_CODE);
        mUwbCountryCode.initialize();

        verify(mWifiManager).registerActiveCountryCodeChangedCallback(
                any(), mWifiCountryCodeReceiverCaptor.capture());
        mWifiCountryCodeReceiverCaptor.getValue().onActiveCountryCodeChanged(TEST_COUNTRY_CODE);
        clearInvocations(mNativeUwbManager);

        mUwbCountryCode.setOverrideCountryCode(TEST_COUNTRY_CODE_OTHER);
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE_OTHER.getBytes(StandardCharsets.UTF_8));
        clearInvocations(mNativeUwbManager);

        mUwbCountryCode.clearOverrideCountryCode();
        verify(mNativeUwbManager).setCountryCode(
                TEST_COUNTRY_CODE.getBytes(StandardCharsets.UTF_8));
    }
}
