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

package com.android.server.nearby.fastpair.pairinghandler;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.res.Resources;

import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.nearby.common.bluetooth.fastpair.FastPairConnection;
import com.android.server.nearby.common.locator.Locator;
import com.android.server.nearby.common.locator.LocatorContextWrapper;
import com.android.server.nearby.fastpair.HalfSheetResources;
import com.android.server.nearby.fastpair.cache.DiscoveryItem;
import com.android.server.nearby.fastpair.cache.FastPairCacheManager;
import com.android.server.nearby.fastpair.footprint.FootprintsDeviceManager;
import com.android.server.nearby.fastpair.halfsheet.FastPairHalfSheetManager;
import com.android.server.nearby.fastpair.notification.FastPairNotificationManager;
import com.android.server.nearby.fastpair.testing.FakeDiscoveryItems;

import com.google.protobuf.ByteString;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Clock;

import service.proto.Cache;
import service.proto.Rpcs;

public class NotificationPairingProgressHandlerTest {

    @Mock
    Locator mLocator;
    @Mock
    LocatorContextWrapper mContextWrapper;
    @Mock
    Clock mClock;
    @Mock
    FastPairCacheManager mFastPairCacheManager;
    @Mock
    FastPairConnection mFastPairConnection;
    @Mock
    FootprintsDeviceManager mFootprintsDeviceManager;
    @Mock
    android.bluetooth.BluetoothManager mBluetoothManager;
    @Mock
    NotificationManager mNotificationManager;
    @Mock
    Resources mResources;

    private static final String MAC_ADDRESS = "00:11:22:33:44:55";
    private static final byte[] ACCOUNT_KEY = new byte[]{0x01, 0x02};
    private static final int SUBSEQUENT_PAIR_START = 1310;
    private static final int SUBSEQUENT_PAIR_END = 1320;
    private static DiscoveryItem sDiscoveryItem;
    private static  NotificationPairingProgressHandler sNotificationPairingProgressHandler;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mContextWrapper.getSystemService(BluetoothManager.class))
                .thenReturn(mBluetoothManager);
        when(mContextWrapper.getLocator()).thenReturn(mLocator);
        when(mContextWrapper.getResources()).thenReturn(mResources);
        HalfSheetResources.setResourcesContextForTest(mContextWrapper);

        mLocator.overrideBindingForTest(FastPairCacheManager.class,
                mFastPairCacheManager);
        mLocator.overrideBindingForTest(Clock.class, mClock);
        sDiscoveryItem = FakeDiscoveryItems.newFastPairDiscoveryItem(mContextWrapper);
        sDiscoveryItem.setStoredItemForTest(
                sDiscoveryItem.getStoredItemForTest().toBuilder()
                        .setAuthenticationPublicKeySecp256R1(ByteString.copyFrom(ACCOUNT_KEY))
                        .setFastPairInformation(
                                Cache.FastPairInformation.newBuilder()
                                        .setDeviceType(Rpcs.DeviceType.HEADPHONES).build())
                        .build());
        sNotificationPairingProgressHandler = createProgressHandler(ACCOUNT_KEY, sDiscoveryItem);
    }

    @Test
    public void getPairEndEventCode() {
        assertThat(sNotificationPairingProgressHandler
                .getPairEndEventCode()).isEqualTo(SUBSEQUENT_PAIR_END);
    }

    @Test
    public void getPairStartEventCode() {
        assertThat(sNotificationPairingProgressHandler
                .getPairStartEventCode()).isEqualTo(SUBSEQUENT_PAIR_START);
    }

    @Test
    public void onReadyToPair() {
        sNotificationPairingProgressHandler.onReadyToPair();
    }

    @Test
    public void onPairedCallbackCalled() {
        sNotificationPairingProgressHandler.onPairedCallbackCalled(mFastPairConnection,
                    ACCOUNT_KEY, mFootprintsDeviceManager, MAC_ADDRESS);
    }

    @Test
    public void  onPairingFailed() {
        Throwable e = new Throwable("Pairing Failed");
        sNotificationPairingProgressHandler.onPairingFailed(e);
    }

    @Test
    public void onPairingSuccess() {
        sNotificationPairingProgressHandler.onPairingSuccess(sDiscoveryItem.getMacAddress());
    }

    private NotificationPairingProgressHandler createProgressHandler(
            @Nullable byte[] accountKey, DiscoveryItem fastPairItem) {
        FastPairHalfSheetManager fastPairHalfSheetManager =
                new FastPairHalfSheetManager(mContextWrapper);
        // Real context is needed
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        FastPairNotificationManager fastPairNotificationManager =
                new FastPairNotificationManager(context, 1234, mNotificationManager,
                        new HalfSheetResources(mContextWrapper));
        mLocator.overrideBindingForTest(FastPairHalfSheetManager.class, fastPairHalfSheetManager);
        mLocator.overrideBindingForTest(FastPairNotificationManager.class,
                fastPairNotificationManager);

        NotificationPairingProgressHandler mNotificationPairingProgressHandler =
                new NotificationPairingProgressHandler(
                        mContextWrapper,
                        fastPairItem,
                        fastPairItem.getAppPackageName(),
                        accountKey,
                        fastPairNotificationManager);
        return mNotificationPairingProgressHandler;
    }
}
