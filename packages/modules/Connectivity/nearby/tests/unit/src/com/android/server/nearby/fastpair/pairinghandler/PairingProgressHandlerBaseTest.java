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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.res.Resources;

import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.nearby.common.bluetooth.fastpair.FastPairConnection;
import com.android.server.nearby.common.bluetooth.fastpair.Preferences;
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
public class PairingProgressHandlerBaseTest {

    @Mock
    Locator mLocator;
    @Mock
    LocatorContextWrapper mContextWrapper;
    @Mock
    Clock mClock;
    @Mock
    FastPairCacheManager mFastPairCacheManager;
    @Mock
    FootprintsDeviceManager mFootprintsDeviceManager;
    @Mock
    FastPairConnection mFastPairConnection;
    @Mock
    BluetoothManager mBluetoothManager;
    @Mock
    Resources mResources;
    @Mock
    NotificationManager mNotificationManager;

    private static final String MAC_ADDRESS = "00:11:22:33:44:55";
    private static final byte[] ACCOUNT_KEY = new byte[]{0x01, 0x02};
    private static final int PASSKEY = 1234;
    private static DiscoveryItem sDiscoveryItem;
    private static PairingProgressHandlerBase sPairingProgressHandlerBase;
    private static BluetoothDevice sBluetoothDevice;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mContextWrapper.getSystemService(BluetoothManager.class))
                .thenReturn(mBluetoothManager);
        when(mContextWrapper.getLocator()).thenReturn(mLocator);
        mLocator.overrideBindingForTest(FastPairCacheManager.class,
                mFastPairCacheManager);
        mLocator.overrideBindingForTest(Clock.class, mClock);
        when(mContextWrapper.getResources()).thenReturn(mResources);
        HalfSheetResources.setResourcesContextForTest(mContextWrapper);

        sBluetoothDevice =
                BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55");
        sDiscoveryItem = FakeDiscoveryItems.newFastPairDiscoveryItem(mContextWrapper);
        sDiscoveryItem.setStoredItemForTest(
                sDiscoveryItem.getStoredItemForTest().toBuilder()
                        .setAuthenticationPublicKeySecp256R1(ByteString.copyFrom(ACCOUNT_KEY))
                        .setFastPairInformation(
                                Cache.FastPairInformation.newBuilder()
                                        .setDeviceType(Rpcs.DeviceType.HEADPHONES).build())
                        .build());

        sPairingProgressHandlerBase =
                createProgressHandler(ACCOUNT_KEY, sDiscoveryItem, /* isRetroactivePair= */ false);
    }

    @Test
    public void createHandler_halfSheetSubsequentPairing_notificationPairingHandlerCreated() {
        sDiscoveryItem.setStoredItemForTest(
                sDiscoveryItem.getStoredItemForTest().toBuilder()
                        .setAuthenticationPublicKeySecp256R1(ByteString.copyFrom(ACCOUNT_KEY))
                        .setFastPairInformation(
                                Cache.FastPairInformation.newBuilder()
                                        .setDeviceType(Rpcs.DeviceType.HEADPHONES).build())
                        .build());

        PairingProgressHandlerBase progressHandler =
                createProgressHandler(ACCOUNT_KEY, sDiscoveryItem, /* isRetroactivePair= */ false);

        assertThat(progressHandler).isInstanceOf(NotificationPairingProgressHandler.class);
    }

    @Test
    public void createHandler_halfSheetInitialPairing_halfSheetPairingHandlerCreated() {
        // No account key
        sDiscoveryItem.setStoredItemForTest(
                sDiscoveryItem.getStoredItemForTest().toBuilder()
                        .setFastPairInformation(
                                Cache.FastPairInformation.newBuilder()
                                        .setDeviceType(Rpcs.DeviceType.HEADPHONES).build())
                        .build());

        PairingProgressHandlerBase progressHandler =
                createProgressHandler(null, sDiscoveryItem, /* isRetroactivePair= */ false);

        assertThat(progressHandler).isInstanceOf(HalfSheetPairingProgressHandler.class);
    }

    @Test
    public void onPairingStarted() {
        sPairingProgressHandlerBase.onPairingStarted();
    }

    @Test
    public void onWaitForScreenUnlock() {
        sPairingProgressHandlerBase.onWaitForScreenUnlock();
    }

    @Test
    public void  onScreenUnlocked() {
        sPairingProgressHandlerBase.onScreenUnlocked();
    }

    @Test
    public void onReadyToPair() {
        sPairingProgressHandlerBase.onReadyToPair();
    }

    @Test
    public void  onSetupPreferencesBuilder() {
        Preferences.Builder prefsBuilder =
                Preferences.builder()
                        .setEnableBrEdrHandover(false)
                        .setIgnoreDiscoveryError(true);
        sPairingProgressHandlerBase.onSetupPreferencesBuilder(prefsBuilder);
    }

    @Test
    public void  onPairingSetupCompleted() {
        sPairingProgressHandlerBase.onPairingSetupCompleted();
    }

    @Test
    public void onHandlePasskeyConfirmation() {
        sPairingProgressHandlerBase.onHandlePasskeyConfirmation(sBluetoothDevice, PASSKEY);
    }

    @Test
    public void getKeyForLocalCache() {
        FastPairConnection.SharedSecret sharedSecret =
                FastPairConnection.SharedSecret.create(ACCOUNT_KEY, sDiscoveryItem.getMacAddress());
        sPairingProgressHandlerBase
                .getKeyForLocalCache(ACCOUNT_KEY, mFastPairConnection, sharedSecret);
    }

    @Test
    public void onPairedCallbackCalled() {
        sPairingProgressHandlerBase.onPairedCallbackCalled(mFastPairConnection,
                ACCOUNT_KEY, mFootprintsDeviceManager, MAC_ADDRESS);
    }

    @Test
    public void onPairingFailed() {
        Throwable e = new Throwable("Pairing Failed");
        sPairingProgressHandlerBase.onPairingFailed(e);
    }

    @Test
    public void onPairingSuccess() {
        sPairingProgressHandlerBase.onPairingSuccess(sDiscoveryItem.getMacAddress());
    }

    @Test
    public void  optInFootprintsForInitialPairing() {
        sPairingProgressHandlerBase.optInFootprintsForInitialPairing(
                mFootprintsDeviceManager, sDiscoveryItem, ACCOUNT_KEY, null);
    }

    @Test
    public void skipWaitingScreenUnlock() {
        assertThat(sPairingProgressHandlerBase.skipWaitingScreenUnlock()).isFalse();
    }

    private PairingProgressHandlerBase createProgressHandler(
            @Nullable byte[] accountKey, DiscoveryItem fastPairItem, boolean isRetroactivePair) {

        FastPairHalfSheetManager fastPairHalfSheetManager =
                new FastPairHalfSheetManager(mContextWrapper);
        // Real context is needed
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        FastPairNotificationManager fastPairNotificationManager =
                new FastPairNotificationManager(context, 1234, mNotificationManager,
                        new HalfSheetResources(mContextWrapper));

        mLocator.overrideBindingForTest(FastPairNotificationManager.class,
                fastPairNotificationManager);
        mLocator.overrideBindingForTest(FastPairHalfSheetManager.class, fastPairHalfSheetManager);
        PairingProgressHandlerBase pairingProgressHandlerBase =
                PairingProgressHandlerBase.create(
                        mContextWrapper,
                        fastPairItem,
                        fastPairItem.getAppPackageName(),
                        accountKey,
                        mFootprintsDeviceManager,
                        fastPairNotificationManager,
                        fastPairHalfSheetManager,
                        isRetroactivePair);
        return pairingProgressHandlerBase;
    }
}
