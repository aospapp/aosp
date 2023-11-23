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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import com.android.server.nearby.common.bluetooth.fastpair.FastPairConnection;
import com.android.server.nearby.common.eventloop.EventLoop;
import com.android.server.nearby.common.locator.Locator;
import com.android.server.nearby.common.locator.LocatorContextWrapper;
import com.android.server.nearby.fastpair.cache.DiscoveryItem;
import com.android.server.nearby.fastpair.cache.FastPairCacheManager;
import com.android.server.nearby.fastpair.footprint.FootprintsDeviceManager;
import com.android.server.nearby.fastpair.halfsheet.FastPairHalfSheetManager;
import com.android.server.nearby.fastpair.testing.FakeDiscoveryItems;

import com.google.protobuf.ByteString;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Clock;

import service.proto.Cache;
import service.proto.Rpcs;

public class HalfSheetPairingProgressHandlerTest {
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
    EventLoop mEventLoop;

    private static final String MAC_ADDRESS = "00:11:22:33:44:55";
    private static final byte[] ACCOUNT_KEY = new byte[]{0x01, 0x02};
    private static final int SUBSEQUENT_PAIR_START = 1310;
    private static final int SUBSEQUENT_PAIR_END = 1320;
    private static final int PASSKEY = 1234;
    private static HalfSheetPairingProgressHandler sHalfSheetPairingProgressHandler;
    private static DiscoveryItem sDiscoveryItem;
    private static BluetoothDevice sBluetoothDevice;
    private static FastPairHalfSheetManager sFastPairHalfSheetManager;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mContextWrapper.getLocator()).thenReturn(mLocator);
        mLocator.overrideBindingForTest(FastPairCacheManager.class, mFastPairCacheManager);
        mLocator.overrideBindingForTest(Clock.class, mClock);
        sFastPairHalfSheetManager = new FastPairHalfSheetManager(mContextWrapper);
        mLocator.bind(FastPairHalfSheetManager.class, sFastPairHalfSheetManager);
        when(mLocator.get(FastPairHalfSheetManager.class)).thenReturn(sFastPairHalfSheetManager);
        when(mLocator.get(EventLoop.class)).thenReturn(mEventLoop);
        sDiscoveryItem = FakeDiscoveryItems.newFastPairDiscoveryItem(mContextWrapper);
        sDiscoveryItem.setStoredItemForTest(
                sDiscoveryItem.getStoredItemForTest().toBuilder()
                        .setAuthenticationPublicKeySecp256R1(ByteString.copyFrom(ACCOUNT_KEY))
                        .setMacAddress(MAC_ADDRESS)
                        .setFastPairInformation(
                                Cache.FastPairInformation.newBuilder()
                                        .setDeviceType(Rpcs.DeviceType.HEADPHONES).build())
                        .build());
        sHalfSheetPairingProgressHandler =
                new HalfSheetPairingProgressHandler(mContextWrapper, sDiscoveryItem,
                        sDiscoveryItem.getAppPackageName(), ACCOUNT_KEY);

        sBluetoothDevice =
                BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55");
    }

    @Test
    public void getPairEndEventCode() {
        assertThat(sHalfSheetPairingProgressHandler
                .getPairEndEventCode()).isEqualTo(SUBSEQUENT_PAIR_END);
    }

    @Test
    public void getPairStartEventCode() {
        assertThat(sHalfSheetPairingProgressHandler
                .getPairStartEventCode()).isEqualTo(SUBSEQUENT_PAIR_START);
    }

    @Test
    public void testOnHandlePasskeyConfirmation() {
        sHalfSheetPairingProgressHandler.onHandlePasskeyConfirmation(sBluetoothDevice, PASSKEY);
    }

    @Test
    public void testOnPairedCallbackCalled() {
        sHalfSheetPairingProgressHandler.onPairedCallbackCalled(mFastPairConnection, ACCOUNT_KEY,
                mFootprintsDeviceManager, MAC_ADDRESS);
    }

    @Test
    public void testonPairingFailed() {
        Throwable e = new Throwable("onPairingFailed");
        sHalfSheetPairingProgressHandler.onPairingFailed(e);
    }

    @Test
    public void testonPairingStarted() {
        sHalfSheetPairingProgressHandler.onPairingStarted();
        assertThat(sFastPairHalfSheetManager.isActivePairing()).isTrue();
    }

    @Test
    public void testonPairingSuccess() {
        sHalfSheetPairingProgressHandler.onPairingSuccess(MAC_ADDRESS);
    }
}
