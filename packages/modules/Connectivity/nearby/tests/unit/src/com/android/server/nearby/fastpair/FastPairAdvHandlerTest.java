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

package com.android.server.nearby.fastpair;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accounts.Account;
import android.content.Context;
import android.nearby.FastPairDevice;

import com.android.server.nearby.common.bloomfilter.BloomFilter;
import com.android.server.nearby.common.locator.LocatorContextWrapper;
import com.android.server.nearby.fastpair.cache.DiscoveryItem;
import com.android.server.nearby.fastpair.cache.FastPairCacheManager;
import com.android.server.nearby.fastpair.halfsheet.FastPairHalfSheetManager;
import com.android.server.nearby.fastpair.notification.FastPairNotificationManager;
import com.android.server.nearby.provider.FastPairDataProvider;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Clock;
import java.util.List;

import service.proto.Cache;
import service.proto.Data;
import service.proto.Rpcs;

public class FastPairAdvHandlerTest {
    @Mock
    private Context mContext;
    @Mock
    private FastPairDataProvider mFastPairDataProvider;
    @Mock
    private FastPairHalfSheetManager mFastPairHalfSheetManager;
    @Mock
    private FastPairNotificationManager mFastPairNotificationManager;
    @Mock
    private FastPairCacheManager mFastPairCacheManager;
    @Mock
    private FastPairController mFastPairController;
    @Mock
    private Data.FastPairDeviceWithAccountKey mFastPairDeviceWithAccountKey;
    @Mock
    private BloomFilter mBloomFilter;
    @Mock
    Cache.StoredFastPairItem mStoredFastPairItem;
    @Mock private Clock mClock;

    private final Account mAccount = new Account("test1@gmail.com", "com.google");
    private static final byte[] ACCOUNT_KEY =
            new byte[] {4, 65, 90, -26, -5, -38, -128, 40, -103, 101, 95, 55, 8, -42, -120, 78};
    private static final byte[] ACCOUNT_KEY_2 = new byte[] {0, 1, 2};
    private static final String BLUETOOTH_ADDRESS = "AA:BB:CC:DD";
    private static final String MODEL_ID = "MODEL_ID";
    private static final int CLOSE_RSSI = -80;
    private static final int FAR_AWAY_RSSI = -120;
    private static final int TX_POWER = -70;
    private static final byte[] INITIAL_BYTE_ARRAY = new byte[]{0x01, 0x02, 0x03};
    private static final byte[] SUBSEQUENT_DATA_BYTES = new byte[]{
            0, -112, -63, 32, 37, -20, 36, 0, -60, 0, -96, 17, -10, 51, -28, -28, 100};
    private static final byte[] SUBSEQUENT_DATA_BYTES_INVALID = new byte[]{
            0, -112, -63, 32, 37, -20, 48, 0, -60, 0, 90, 17, -10, 51, -28, -28, 100};
    private static final byte[] SALT = new byte[]{0x01};
    private static final Cache.StoredDiscoveryItem STORED_DISCOVERY_ITEM =
            Cache.StoredDiscoveryItem.newBuilder()
                    .setDeviceName("Device Name")
                    .setTxPower(TX_POWER)
                    .setMacAddress(BLUETOOTH_ADDRESS)
                    .build();

    LocatorContextWrapper mLocatorContextWrapper;
    FastPairAdvHandler mFastPairAdvHandler;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mLocatorContextWrapper = new LocatorContextWrapper(mContext);
        mLocatorContextWrapper.getLocator().overrideBindingForTest(
                FastPairHalfSheetManager.class, mFastPairHalfSheetManager
        );
        mLocatorContextWrapper.getLocator().overrideBindingForTest(
                FastPairNotificationManager.class, mFastPairNotificationManager
        );
        mLocatorContextWrapper.getLocator().overrideBindingForTest(
                FastPairCacheManager.class, mFastPairCacheManager
        );
        mLocatorContextWrapper.getLocator().overrideBindingForTest(
                FastPairController.class, mFastPairController);
        mLocatorContextWrapper.getLocator().overrideBindingForTest(Clock.class, mClock);

        when(mFastPairDataProvider.loadFastPairAntispoofKeyDeviceMetadata(any()))
                .thenReturn(Rpcs.GetObservedDeviceResponse.getDefaultInstance());
        when(mFastPairDataProvider.loadFastPairEligibleAccounts()).thenReturn(List.of(mAccount));
        when(mFastPairDataProvider.loadFastPairDeviceWithAccountKey(mAccount))
                .thenReturn(List.of(mFastPairDeviceWithAccountKey));
        when(mFastPairDataProvider.loadFastPairDeviceWithAccountKey(eq(mAccount), any()))
                .thenReturn(List.of(mFastPairDeviceWithAccountKey));
        when(mFastPairDeviceWithAccountKey.getAccountKey())
                .thenReturn(ByteString.copyFrom(ACCOUNT_KEY));
        when(mFastPairDeviceWithAccountKey.getDiscoveryItem())
                .thenReturn(STORED_DISCOVERY_ITEM);
        when(mStoredFastPairItem.getAccountKey())
                .thenReturn(ByteString.copyFrom(ACCOUNT_KEY_2), ByteString.copyFrom(ACCOUNT_KEY_2));
        when(mFastPairCacheManager.getAllSavedStoredFastPairItem())
                .thenReturn(List.of(mStoredFastPairItem));

        mFastPairAdvHandler = new FastPairAdvHandler(mLocatorContextWrapper, mFastPairDataProvider);
    }

    @Test
    public void testInitialBroadcast() {
        FastPairDevice fastPairDevice = new FastPairDevice.Builder()
                .setData(INITIAL_BYTE_ARRAY)
                .setBluetoothAddress(BLUETOOTH_ADDRESS)
                .setModelId(MODEL_ID)
                .setRssi(CLOSE_RSSI)
                .setTxPower(TX_POWER)
                .build();

        mFastPairAdvHandler.handleBroadcast(fastPairDevice);

        verify(mFastPairHalfSheetManager).showHalfSheet(any());
    }

    @Test
    public void testInitialBroadcast_farAway_notShowHalfSheet() {
        FastPairDevice fastPairDevice = new FastPairDevice.Builder()
                .setData(INITIAL_BYTE_ARRAY)
                .setBluetoothAddress(BLUETOOTH_ADDRESS)
                .setModelId(MODEL_ID)
                .setRssi(FAR_AWAY_RSSI)
                .setTxPower(TX_POWER)
                .build();

        mFastPairAdvHandler.handleBroadcast(fastPairDevice);

        verify(mFastPairHalfSheetManager, never()).showHalfSheet(any());
    }

    @Test
    public void testSubsequentBroadcast_showNotification() {
        FastPairDevice fastPairDevice = new FastPairDevice.Builder()
                .setData(SUBSEQUENT_DATA_BYTES)
                .setBluetoothAddress(BLUETOOTH_ADDRESS)
                .setModelId(MODEL_ID)
                .setRssi(CLOSE_RSSI)
                .setTxPower(TX_POWER)
                .build();
        mFastPairAdvHandler.handleBroadcast(fastPairDevice);

        DiscoveryItem discoveryItem =
                new DiscoveryItem(mLocatorContextWrapper, STORED_DISCOVERY_ITEM);
        verify(mFastPairNotificationManager).showDiscoveryNotification(eq(discoveryItem),
                eq(ACCOUNT_KEY));
        verify(mFastPairHalfSheetManager, never()).showHalfSheet(any());
    }

    @Test
    public void testSubsequentBroadcast_tooFar_notShowNotification() {
        FastPairDevice fastPairDevice = new FastPairDevice.Builder()
                .setData(SUBSEQUENT_DATA_BYTES)
                .setBluetoothAddress(BLUETOOTH_ADDRESS)
                .setModelId(MODEL_ID)
                .setRssi(FAR_AWAY_RSSI)
                .setTxPower(TX_POWER)
                .build();
        mFastPairAdvHandler.handleBroadcast(fastPairDevice);

        verify(mFastPairController, never()).pair(any(), any(), any());
        verify(mFastPairHalfSheetManager, never()).showHalfSheet(any());
    }

    @Test
    public void testSubsequentBroadcast_notRecognize_notShowNotification() {
        FastPairDevice fastPairDevice = new FastPairDevice.Builder()
                .setData(SUBSEQUENT_DATA_BYTES_INVALID)
                .setBluetoothAddress(BLUETOOTH_ADDRESS)
                .setModelId(MODEL_ID)
                .setRssi(FAR_AWAY_RSSI)
                .setTxPower(TX_POWER)
                .build();
        mFastPairAdvHandler.handleBroadcast(fastPairDevice);

        verify(mFastPairController, never()).pair(any(), any(), any());
        verify(mFastPairHalfSheetManager, never()).showHalfSheet(any());
    }

    @Test
    public void testSubsequentBroadcast_cached_notShowNotification() {
        when(mStoredFastPairItem.getAccountKey())
                .thenReturn(ByteString.copyFrom(ACCOUNT_KEY), ByteString.copyFrom(ACCOUNT_KEY));

        FastPairDevice fastPairDevice = new FastPairDevice.Builder()
                .setData(SUBSEQUENT_DATA_BYTES_INVALID)
                .setBluetoothAddress(BLUETOOTH_ADDRESS)
                .setModelId(MODEL_ID)
                .setRssi(FAR_AWAY_RSSI)
                .setTxPower(TX_POWER)
                .build();
        mFastPairAdvHandler.handleBroadcast(fastPairDevice);

        verify(mFastPairController, never()).pair(any(), any(), any());
        verify(mFastPairHalfSheetManager, never()).showHalfSheet(any());
    }

    @Test
    public void testFindRecognizedDevice_bloomFilterNotContains_notFound() {
        when(mFastPairDeviceWithAccountKey.getAccountKey())
                .thenReturn(ByteString.copyFrom(ACCOUNT_KEY), ByteString.copyFrom(ACCOUNT_KEY));
        when(mBloomFilter.possiblyContains(any(byte[].class))).thenReturn(false);

        assertThat(FastPairAdvHandler.findRecognizedDevice(
                ImmutableList.of(mFastPairDeviceWithAccountKey), mBloomFilter, SALT)).isNull();
    }

    @Test
    public void testFindRecognizedDevice_bloomFilterContains_found() {
        when(mFastPairDeviceWithAccountKey.getAccountKey())
                .thenReturn(ByteString.copyFrom(ACCOUNT_KEY), ByteString.copyFrom(ACCOUNT_KEY));
        when(mBloomFilter.possiblyContains(any(byte[].class))).thenReturn(true);

        assertThat(FastPairAdvHandler.findRecognizedDevice(
                ImmutableList.of(mFastPairDeviceWithAccountKey), mBloomFilter, SALT)).isNotNull();
    }

    @Test
    public void testFindRecognizedDeviceFromCachedItem_bloomFilterNotContains_notFound() {
        when(mStoredFastPairItem.getAccountKey())
                .thenReturn(ByteString.copyFrom(ACCOUNT_KEY), ByteString.copyFrom(ACCOUNT_KEY));
        when(mBloomFilter.possiblyContains(any(byte[].class))).thenReturn(false);

        assertThat(FastPairAdvHandler.findRecognizedDeviceFromCachedItem(
                ImmutableList.of(mStoredFastPairItem), mBloomFilter, SALT)).isNull();
    }

    @Test
    public void testFindRecognizedDeviceFromCachedItem_bloomFilterContains_found() {
        when(mStoredFastPairItem.getAccountKey())
                .thenReturn(ByteString.copyFrom(ACCOUNT_KEY), ByteString.copyFrom(ACCOUNT_KEY));
        when(mBloomFilter.possiblyContains(any(byte[].class))).thenReturn(true);

        assertThat(FastPairAdvHandler.findRecognizedDeviceFromCachedItem(
                ImmutableList.of(mStoredFastPairItem), mBloomFilter, SALT)).isNotNull();
    }

    @Test
    public void testGenerateBatteryData_correct() {
        byte[] data = new byte[]
                {0, -112, 96, 5, -125, 45, 35, 98, 98, 81, 13, 17, 3, 51, -28, -28, -28};
        assertThat(FastPairAdvHandler.generateBatteryData(data))
                .isEqualTo(new byte[]{51, -28, -28, -28});
    }
}
