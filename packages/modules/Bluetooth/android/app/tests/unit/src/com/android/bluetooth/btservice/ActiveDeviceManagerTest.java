/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.bluetooth.btservice;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapClient;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.Utils;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.hearingaid.HearingAidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.le_audio.LeAudioService;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class ActiveDeviceManagerTest {
    private BluetoothAdapter mAdapter;
    private Context mContext;
    private BluetoothDevice mA2dpDevice;
    private BluetoothDevice mHeadsetDevice;
    private BluetoothDevice mA2dpHeadsetDevice;
    private BluetoothDevice mHearingAidDevice;
    private BluetoothDevice mLeAudioDevice;
    private BluetoothDevice mLeAudioDevice2;
    private BluetoothDevice mLeHearingAidDevice;
    private BluetoothDevice mSecondaryAudioDevice;
    private BluetoothDevice mDualModeAudioDevice;
    private ArrayList<BluetoothDevice> mDeviceConnectionStack;
    private BluetoothDevice mMostRecentDevice;
    private ActiveDeviceManager mActiveDeviceManager;
    private long mHearingAidHiSyncId = 1010;

    private static final int TIMEOUT_MS = 1_000;
    private static final int A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS =
            ActiveDeviceManager.A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS + 2_000;
    private boolean mOriginalDualModeAudioState;

    @Mock private AdapterService mAdapterService;
    @Mock private ServiceFactory mServiceFactory;
    @Mock private A2dpService mA2dpService;
    @Mock private HeadsetService mHeadsetService;
    @Mock private HearingAidService mHearingAidService;
    @Mock private LeAudioService mLeAudioService;
    @Mock private AudioManager mAudioManager;
    @Mock private DatabaseManager mDatabaseManager;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        // Set up mocks and test assets
        MockitoAnnotations.initMocks(this);
        TestUtils.setAdapterService(mAdapterService);

        when(mAdapterService.getSystemService(Context.AUDIO_SERVICE)).thenReturn(mAudioManager);
        when(mAdapterService.getSystemServiceName(AudioManager.class))
                .thenReturn(Context.AUDIO_SERVICE);
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mServiceFactory.getA2dpService()).thenReturn(mA2dpService);
        when(mServiceFactory.getHeadsetService()).thenReturn(mHeadsetService);
        when(mServiceFactory.getHearingAidService()).thenReturn(mHearingAidService);
        when(mServiceFactory.getLeAudioService()).thenReturn(mLeAudioService);

        mActiveDeviceManager = new ActiveDeviceManager(mAdapterService, mServiceFactory);
        mActiveDeviceManager.start();
        mAdapter = BluetoothAdapter.getDefaultAdapter();

        // Get devices for testing
        mA2dpDevice = TestUtils.getTestDevice(mAdapter, 0);
        mHeadsetDevice = TestUtils.getTestDevice(mAdapter, 1);
        mA2dpHeadsetDevice = TestUtils.getTestDevice(mAdapter, 2);
        mHearingAidDevice = TestUtils.getTestDevice(mAdapter, 3);
        mLeAudioDevice = TestUtils.getTestDevice(mAdapter, 4);
        mLeHearingAidDevice = TestUtils.getTestDevice(mAdapter, 5);
        mSecondaryAudioDevice = TestUtils.getTestDevice(mAdapter, 6);
        mDualModeAudioDevice = TestUtils.getTestDevice(mAdapter, 7);
        mLeAudioDevice2 = TestUtils.getTestDevice(mAdapter, 8);
        mDeviceConnectionStack = new ArrayList<>();
        mMostRecentDevice = null;
        mOriginalDualModeAudioState = Utils.isDualModeAudioEnabled();

        when(mA2dpService.setActiveDevice(any())).thenReturn(true);
        when(mHeadsetService.getHfpCallAudioPolicy(any())).thenReturn(
                new BluetoothSinkAudioPolicy.Builder().build());
        when(mHeadsetService.setActiveDevice(any())).thenReturn(true);
        when(mHearingAidService.setActiveDevice(any())).thenReturn(true);
        when(mLeAudioService.setActiveDevice(any())).thenReturn(true);
        when(mLeAudioService.removeActiveDevice(anyBoolean())).thenReturn(true);

        List<BluetoothDevice> connectedHearingAidDevices = new ArrayList<>();
        connectedHearingAidDevices.add(mHearingAidDevice);
        when(mHearingAidService.getHiSyncId(mHearingAidDevice)).thenReturn(mHearingAidHiSyncId);
        when(mHearingAidService.getConnectedPeerDevices(mHearingAidHiSyncId))
                .thenReturn(connectedHearingAidDevices);

        when(mA2dpService.getFallbackDevice()).thenAnswer(invocation -> {
            if (!mDeviceConnectionStack.isEmpty() && Objects.equals(mA2dpDevice,
                    mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1))) {
                return mA2dpDevice;
            }
            return null;
        });
        when(mHeadsetService.getFallbackDevice()).thenAnswer(invocation -> {
            if (!mDeviceConnectionStack.isEmpty() && Objects.equals(mHeadsetDevice,
                    mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1))) {
                return mHeadsetDevice;
            }
            return null;
        });
        when(mDatabaseManager.getMostRecentlyConnectedDevicesInList(any())).thenAnswer(
                invocation -> {
                    List<BluetoothDevice> devices = invocation.getArgument(0);
                    if (devices == null || devices.size() == 0) {
                        return null;
                    } else if (devices.contains(mLeHearingAidDevice)) {
                        return mLeHearingAidDevice;
                    } else if (devices.contains(mHearingAidDevice)) {
                        return mHearingAidDevice;
                    } else if (mMostRecentDevice != null && devices.contains(mMostRecentDevice)) {
                        return mMostRecentDevice;
                    } else {
                        return devices.get(0);
                    }
                }
        );
    }

    @After
    public void tearDown() throws Exception {
        mActiveDeviceManager.cleanup();
        TestUtils.clearAdapterService(mAdapterService);
        Utils.setDualModeAudioStateForTesting(mOriginalDualModeAudioState);
    }

    @Test
    public void testSetUpAndTearDown() {}

    /**
     * One A2DP is connected.
     */
    @Test
    public void onlyA2dpConnected_setA2dpActive() {
        a2dpConnected(mA2dpDevice, false);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice);
    }

    @Test
    public void a2dpHeadsetConnected_setA2dpActiveShouldBeCalledAfterHeadsetConnected() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);

        a2dpConnected(mA2dpHeadsetDevice, true);
        verify(mA2dpService, after(TIMEOUT_MS).never()).setActiveDevice(mA2dpHeadsetDevice);
        headsetConnected(mA2dpHeadsetDevice, true);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpHeadsetDevice);
    }

    /**
     * Two A2DP are connected. Should set the second one active.
     */
    @Test
    public void secondA2dpConnected_setSecondA2dpActive() {
        a2dpConnected(mA2dpDevice, false);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice);

        a2dpConnected(mSecondaryAudioDevice, false);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mSecondaryAudioDevice);
    }

    /**
     * One A2DP is connected and disconnected later. Should then set active device to null.
     */
    @Test
    public void lastA2dpDisconnected_clearA2dpActive() {
        a2dpConnected(mA2dpDevice, false);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice);

        a2dpDisconnected(mA2dpDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).removeActiveDevice(true);
    }

    /**
     * Two A2DP are connected and active device is explicitly set.
     */
    @Test
    public void a2dpActiveDeviceSelected_setActive() {
        a2dpConnected(mA2dpDevice, false);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice);

        a2dpConnected(mSecondaryAudioDevice, false);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mSecondaryAudioDevice);

        a2dpActiveDeviceChanged(mA2dpDevice);
        // Don't call mA2dpService.setActiveDevice()
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mA2dpService, times(1)).setActiveDevice(mA2dpDevice);
        Assert.assertEquals(mA2dpDevice, mActiveDeviceManager.getA2dpActiveDevice());
    }

    /**
     * Two A2DP devices are connected and the current active is then disconnected.
     * Should then set active device to fallback device.
     */
    @Test
    public void a2dpSecondDeviceDisconnected_fallbackDeviceActive() {
        a2dpConnected(mA2dpDevice, false);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice);

        a2dpConnected(mSecondaryAudioDevice, false);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mSecondaryAudioDevice);

        Mockito.clearInvocations(mA2dpService);
        a2dpDisconnected(mSecondaryAudioDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice);
    }

    /**
     * One Headset is connected.
     */
    @Test
    public void onlyHeadsetConnected_setHeadsetActive() {
        headsetConnected(mHeadsetDevice, false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);
    }

    /**
     * Two Headset are connected. Should set the second one active.
     */
    @Test
    public void secondHeadsetConnected_setSecondHeadsetActive() {
        headsetConnected(mHeadsetDevice, false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);

        headsetConnected(mSecondaryAudioDevice, false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mSecondaryAudioDevice);
    }

    /**
     * One Headset is connected and disconnected later. Should then set active device to null.
     */
    @Test
    public void lastHeadsetDisconnected_clearHeadsetActive() {
        headsetConnected(mHeadsetDevice, false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);

        headsetDisconnected(mHeadsetDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(isNull());
    }

    /**
     * Two Headset are connected and active device is explicitly set.
     */
    @Test
    public void headsetActiveDeviceSelected_setActive() {
        headsetConnected(mHeadsetDevice, false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);

        headsetConnected(mSecondaryAudioDevice, false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mSecondaryAudioDevice);

        headsetActiveDeviceChanged(mHeadsetDevice);
        // Don't call mHeadsetService.setActiveDevice()
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mHeadsetService, times(1)).setActiveDevice(mHeadsetDevice);
        Assert.assertEquals(mHeadsetDevice, mActiveDeviceManager.getHfpActiveDevice());
    }

    /**
     * Two Headsets are connected and the current active is then disconnected.
     * Should then set active device to fallback device.
     */
    @Test
    public void headsetSecondDeviceDisconnected_fallbackDeviceActive() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);

        headsetConnected(mHeadsetDevice, false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);

        headsetConnected(mSecondaryAudioDevice, false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mSecondaryAudioDevice);

        Mockito.clearInvocations(mHeadsetService);
        headsetDisconnected(mSecondaryAudioDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);
    }

    @Test
    public void a2dpConnectedButHeadsetNotConnected_setA2dpActive() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);

        a2dpConnected(mA2dpHeadsetDevice, true);
        verify(mA2dpService, timeout(A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS))
                .setActiveDevice(mA2dpHeadsetDevice);
    }


    @Test
    public void headsetConnectedButA2dpNotConnected_setHeadsetActive() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        headsetConnected(mA2dpHeadsetDevice, true);
        verify(mHeadsetService, timeout(A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS))
                .setActiveDevice(mA2dpHeadsetDevice);
    }

    /**
     * A headset device with connecting audio policy set to NOT ALLOWED.
     */
    @Test
    public void notAllowedConnectingPolicyHeadsetConnected_noSetActiveDevice() {
        // setting connecting policy to NOT ALLOWED
        when(mHeadsetService.getHfpCallAudioPolicy(mHeadsetDevice))
                .thenReturn(new BluetoothSinkAudioPolicy.Builder()
                        .setCallEstablishPolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                        .setActiveDevicePolicyAfterConnection(
                                BluetoothSinkAudioPolicy.POLICY_NOT_ALLOWED)
                        .setInBandRingtonePolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                        .build());

        headsetConnected(mHeadsetDevice, false);
        verify(mHeadsetService, never()).setActiveDevice(mHeadsetDevice);
    }

    @Test
    public void twoHearingAidDevicesConnected_WithTheSameHiSyncId() {
        Assume.assumeTrue("Ignore test when HearingAidService is not enabled",
                HearingAidService.isEnabled());

        List<BluetoothDevice> connectedHearingAidDevices = new ArrayList<>();
        connectedHearingAidDevices.add(mHearingAidDevice);
        connectedHearingAidDevices.add(mSecondaryAudioDevice);
        when(mHearingAidService.getHiSyncId(mSecondaryAudioDevice))
                .thenReturn(mHearingAidHiSyncId);
        when(mHearingAidService.getConnectedPeerDevices(mHearingAidHiSyncId))
                .thenReturn(connectedHearingAidDevices);

        hearingAidConnected(mHearingAidDevice);
        hearingAidConnected(mSecondaryAudioDevice);
        verify(mHearingAidService, timeout(TIMEOUT_MS)).setActiveDevice(mHearingAidDevice);
        verify(mHearingAidService, never()).setActiveDevice(mSecondaryAudioDevice);
    }

    /**
     * A combo (A2DP + Headset) device is connected. Then a Hearing Aid is connected.
     */
    @Test
    public void hearingAidActive_clearA2dpAndHeadsetActive() {
        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        verify(mA2dpService, timeout(TIMEOUT_MS).atLeastOnce()).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS).atLeastOnce())
                .setActiveDevice(mA2dpHeadsetDevice);

        hearingAidActiveDeviceChanged(mHearingAidDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).removeActiveDevice(false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(null);
    }

    /**
     * A Hearing Aid is connected. Then a combo (A2DP + Headset) device is connected.
     */
    @Test
    public void hearingAidActive_dontSetA2dpAndHeadsetActive() {
        hearingAidActiveDeviceChanged(mHearingAidDevice);
        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);

        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mA2dpService, never()).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService, never()).setActiveDevice(mA2dpHeadsetDevice);
    }

    /**
     * A Hearing Aid is connected. Then an A2DP active device is explicitly set.
     */
    @Test
    public void hearingAidActive_setA2dpActiveExplicitly() {
        when(mHearingAidService.removeActiveDevice(anyBoolean())).thenReturn(true);

        hearingAidActiveDeviceChanged(mHearingAidDevice);
        a2dpConnected(mA2dpDevice, false);
        a2dpActiveDeviceChanged(mA2dpDevice);

        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mHearingAidService).removeActiveDevice(false);
        // Don't call mA2dpService.setActiveDevice()
        verify(mA2dpService, never()).setActiveDevice(mA2dpDevice);
        Assert.assertEquals(mA2dpDevice, mActiveDeviceManager.getA2dpActiveDevice());
        Assert.assertTrue(mActiveDeviceManager.getHearingAidActiveDevices().isEmpty());
    }

    /**
     * A Hearing Aid is connected. Then a Headset active device is explicitly set.
     */
    @Test
    public void hearingAidActive_setHeadsetActiveExplicitly() {
        when(mHearingAidService.removeActiveDevice(anyBoolean())).thenReturn(true);

        hearingAidActiveDeviceChanged(mHearingAidDevice);
        headsetConnected(mHeadsetDevice, false);
        headsetActiveDeviceChanged(mHeadsetDevice);

        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mHearingAidService).removeActiveDevice(false);
        // Don't call mHeadsetService.setActiveDevice()
        verify(mHeadsetService, never()).setActiveDevice(mHeadsetDevice);
        Assert.assertEquals(mHeadsetDevice, mActiveDeviceManager.getHfpActiveDevice());
        Assert.assertTrue(mActiveDeviceManager.getHearingAidActiveDevices().isEmpty());
    }

    /**
     * One LE Audio is connected.
     */
    @Test
    public void onlyLeAudioConnected_setHeadsetActive() {
        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice);
    }

    /**
     * Two LE Audio are connected. Should set the second one active.
     */
    @Test
    public void secondLeAudioConnected_setSecondLeAudioActive() {
        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mSecondaryAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mSecondaryAudioDevice);
    }

    /**
     * One LE Audio  is connected and disconnected later. Should then set active device to null.
     */
    @Test
    public void lastLeAudioDisconnected_clearLeAudioActive() {
        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice);

        leAudioDisconnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).removeActiveDevice(false);
    }

    /**
     * Two LE Audio are connected and active device is explicitly set.
     */
    @Test
    public void leAudioActiveDeviceSelected_setActive() {
        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mSecondaryAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mSecondaryAudioDevice);

        Mockito.clearInvocations(mLeAudioService);
        leAudioActiveDeviceChanged(mLeAudioDevice);
        // Don't call mLeAudioService.setActiveDevice()
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mLeAudioService, never()).setActiveDevice(any(BluetoothDevice.class));
        Assert.assertEquals(mLeAudioDevice, mActiveDeviceManager.getLeAudioActiveDevice());
    }

    /**
     * Two LE Audio are connected and the current active is then disconnected.
     * Should then set active device to fallback device.
     */
    @Test
    public void leAudioSecondDeviceDisconnected_fallbackDeviceActive() {
        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mSecondaryAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mSecondaryAudioDevice);

        Mockito.clearInvocations(mLeAudioService);
        leAudioDisconnected(mSecondaryAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice);
    }

    /**
     * A combo (A2DP + Headset) device is connected. Then an LE Audio is connected.
     */
    @Test
    public void leAudioActive_clearA2dpAndHeadsetActive() {
        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        verify(mA2dpService, timeout(TIMEOUT_MS).atLeastOnce()).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS).atLeastOnce())
                .setActiveDevice(mA2dpHeadsetDevice);

        leAudioActiveDeviceChanged(mLeAudioDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).removeActiveDevice(false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(isNull());
    }

    /**
     * An LE Audio is connected. Then a combo (A2DP + Headset) device is connected.
     */
    @Test
    public void leAudioActive_setA2dpAndHeadsetActive() {
        leAudioActiveDeviceChanged(mLeAudioDevice);
        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);

        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mA2dpService, atLeastOnce()).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService, atLeastOnce()).setActiveDevice(mA2dpHeadsetDevice);
    }

    /**
     * An LE Audio is connected. Then an A2DP active device is explicitly set.
     */
    @Test
    public void leAudioActive_setA2dpActiveExplicitly() {
        leAudioActiveDeviceChanged(mLeAudioDevice);
        a2dpConnected(mA2dpDevice, false);
        a2dpActiveDeviceChanged(mA2dpDevice);

        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mLeAudioService).removeActiveDevice(true);
        verify(mA2dpService).setActiveDevice(mA2dpDevice);
        Assert.assertEquals(mA2dpDevice, mActiveDeviceManager.getA2dpActiveDevice());
        Assert.assertNull(mActiveDeviceManager.getLeAudioActiveDevice());
    }

    /**
     * An LE Audio is connected. Then a Headset active device is explicitly set.
     */
    @Test
    public void leAudioActive_setHeadsetActiveExplicitly() {
        leAudioActiveDeviceChanged(mLeAudioDevice);
        headsetConnected(mHeadsetDevice, false);
        headsetActiveDeviceChanged(mHeadsetDevice);

        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mLeAudioService).removeActiveDevice(true);
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);
        Assert.assertEquals(mHeadsetDevice, mActiveDeviceManager.getHfpActiveDevice());
        Assert.assertNull(mActiveDeviceManager.getLeAudioActiveDevice());
    }

    /**
     * An LE Audio connected. An A2DP connected. The A2DP disconnected.
     * Then the LE Audio should be the active one.
     */
    @Test
    public void leAudioAndA2dpConnectedThenA2dpDisconnected_fallbackToLeAudio() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice);

        a2dpConnected(mA2dpDevice, false);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice);

        Mockito.clearInvocations(mLeAudioService);
        a2dpDisconnected(mA2dpDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS).atLeast(1)).removeActiveDevice(false);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice);
    }

    /**
     * An LE Audio set connected. The not active bud disconnected.
     * Then the active device should not change and hasFallback should be set to false.
     */
    @Test
    public void leAudioSetConnectedThenNotActiveOneDisconnected_noFallback() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice2);

        Mockito.clearInvocations(mLeAudioService);

        leAudioDisconnected(mLeAudioDevice);

        verify(mLeAudioService, never()).removeActiveDevice(false);
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice2);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).deviceDisconnected(mLeAudioDevice, false);
    }

    /**
     * An LE Audio set connected. The active bud disconnected. Set active device
     * returns false indicating an issue (the other bud is also disconnected).
     * Then the active device should be removed and hasFallback should be set to false.
     */
    @Test
    public void leAudioSetConnectedThenActiveOneDisconnected_noFallback() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice2);

        Mockito.clearInvocations(mLeAudioService);

        // Return false to indicate an issue when setting new active device
        // (e.g. the other device disconnected as well).
        when(mLeAudioService.setActiveDevice(any())).thenReturn(false);

        leAudioDisconnected(mLeAudioDevice2);

        verify(mLeAudioService, timeout(TIMEOUT_MS)).removeActiveDevice(false);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).deviceDisconnected(mLeAudioDevice2, false);
    }

    /**
     * An LE Audio set connected. The active bud disconnected. Set active device
     * returns true indicating the other bud is going to be the active device.
     * Then the active device should change and hasFallback should be set to true.
     */
    @Test
    public void leAudioSetConnectedThenActiveOneDisconnected_hasFallback() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice2);

        Mockito.clearInvocations(mLeAudioService);

        leAudioDisconnected(mLeAudioDevice2);

        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).deviceDisconnected(mLeAudioDevice2, true);
    }

    /**
     * An A2DP connected. An LE Audio connected. The LE Audio disconnected.
     * Then the A2DP should be the active one.
     */
    @Test
    public void a2dpAndLeAudioConnectedThenLeAudioDisconnected_fallbackToA2dp() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        a2dpConnected(mA2dpDevice, false);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice);

        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice);

        Mockito.clearInvocations(mA2dpService);
        leAudioDisconnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS).atLeast(1)).removeActiveDevice(true);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice);
    }

    /**
     * Two Hearing Aid are connected and the current active is then disconnected.
     * Should then set active device to fallback device.
     */
    @Test
    public void hearingAidSecondDeviceDisconnected_fallbackDeviceActive() {
        hearingAidConnected(mHearingAidDevice);
        verify(mHearingAidService, timeout(TIMEOUT_MS)).setActiveDevice(mHearingAidDevice);

        List<BluetoothDevice> connectedHearingAidDevices = new ArrayList<>();
        connectedHearingAidDevices.add(mSecondaryAudioDevice);
        when(mHearingAidService.getHiSyncId(mSecondaryAudioDevice))
                .thenReturn(mHearingAidHiSyncId + 1);
        when(mHearingAidService.getConnectedPeerDevices(mHearingAidHiSyncId + 1))
                .thenReturn(connectedHearingAidDevices);

        hearingAidConnected(mSecondaryAudioDevice);
        verify(mHearingAidService, timeout(TIMEOUT_MS)).setActiveDevice(mSecondaryAudioDevice);

        Mockito.clearInvocations(mHearingAidService);
        hearingAidDisconnected(mSecondaryAudioDevice);
        verify(mHearingAidService, timeout(TIMEOUT_MS)).setActiveDevice(mHearingAidDevice);
    }

    /**
     * Hearing aid is connected, but active device is different BT.
     * When the active device is disconnected, the hearing aid should be the active one.
     */
    @Test
    public void activeDeviceDisconnected_fallbackToHearingAid() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);
        when(mA2dpService.setActiveDevice(any())).thenReturn(true);
        when(mLeAudioService.setActiveDevice(any())).thenReturn(true);
        when(mHearingAidService.setActiveDevice(any())).thenReturn(true);
        when(mHearingAidService.removeActiveDevice(anyBoolean())).thenReturn(true);

        hearingAidConnected(mHearingAidDevice);
        verify(mHearingAidService, timeout(TIMEOUT_MS)).setActiveDevice(mHearingAidDevice);

        leAudioConnected(mLeAudioDevice);
        a2dpConnected(mA2dpDevice, false);

        a2dpActiveDeviceChanged(mA2dpDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());

        verify(mHearingAidService).removeActiveDevice(false);
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
        verify(mA2dpService, never()).setActiveDevice(mA2dpDevice);

        a2dpDisconnected(mA2dpDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS).atLeast(1)).removeActiveDevice(false);
        verify(mHearingAidService, timeout(TIMEOUT_MS).times(2))
                .setActiveDevice(mHearingAidDevice);
    }

    /**
     * One LE Hearing Aid is connected.
     */
    @Test
    public void onlyLeHearingAidConnected_setLeAudioActive() {
        leHearingAidConnected(mLeHearingAidDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mLeAudioService, never()).setActiveDevice(mLeHearingAidDevice);

        leAudioConnected(mLeHearingAidDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeHearingAidDevice);
    }

    /**
     * LE audio is connected after LE Hearing Aid device.
     * Keep LE hearing Aid active.
     */
    @Test
    public void leAudioConnectedAfterLeHearingAid_setLeAudioActiveShouldNotBeCalled() {
        leHearingAidConnected(mLeHearingAidDevice);
        leAudioConnected(mLeHearingAidDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeHearingAidDevice);

        leAudioConnected(mLeAudioDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
    }

    /**
     * Test connect/disconnect of devices.
     * Hearing Aid, LE Hearing Aid, A2DP connected, then LE hearing Aid and hearing aid
     * disconnected.
     */
    @Test
    public void activeDeviceChange_withHearingAidLeHearingAidAndA2dpDevices() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);
        when(mHearingAidService.removeActiveDevice(anyBoolean())).thenReturn(true);

        hearingAidConnected(mHearingAidDevice);
        verify(mHearingAidService, timeout(TIMEOUT_MS)).setActiveDevice(mHearingAidDevice);

        leHearingAidConnected(mLeHearingAidDevice);
        leAudioConnected(mLeHearingAidDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeHearingAidDevice);

        a2dpConnected(mA2dpDevice, false);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mA2dpService, never()).setActiveDevice(mA2dpDevice);

        Mockito.clearInvocations(mHearingAidService, mA2dpService);
        leHearingAidDisconnected(mLeHearingAidDevice);
        leAudioDisconnected(mLeHearingAidDevice);
        verify(mHearingAidService, timeout(TIMEOUT_MS)).setActiveDevice(mHearingAidDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).removeActiveDevice(false);

        hearingAidDisconnected(mHearingAidDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice);
    }

    /**
     * Verifies that we mutually exclude classic audio profiles (A2DP & HFP) and LE Audio when the
     * dual mode feature is disabled.
     */
    @Test
    public void dualModeAudioDeviceConnected_withDualModeFeatureDisabled() {
        // Turn off the dual mode audio flag
        Utils.setDualModeAudioStateForTesting(false);

        // Ensure we remove the LEA active device when classic audio profiles are made active
        a2dpConnected(mDualModeAudioDevice, true);
        headsetConnected(mDualModeAudioDevice, true);
        verify(mA2dpService, timeout(TIMEOUT_MS).atLeastOnce())
                .setActiveDevice(mDualModeAudioDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS).atLeastOnce())
                .setActiveDevice(mDualModeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS).atLeastOnce()).removeActiveDevice(true);
        Assert.assertEquals(mDualModeAudioDevice, mActiveDeviceManager.getA2dpActiveDevice());
        Assert.assertEquals(mDualModeAudioDevice, mActiveDeviceManager.getHfpActiveDevice());

        // Ensure we make classic audio profiles inactive when LEA is made active
        leAudioConnected(mDualModeAudioDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).removeActiveDevice(false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(isNull());
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mDualModeAudioDevice);
        Assert.assertEquals(mDualModeAudioDevice, mActiveDeviceManager.getLeAudioActiveDevice());
    }

    /**
     * Verifies that we connect and make active both classic audio profiles (A2DP & HFP) and LE
     * Audio when the dual mode feature is enabled.
     */
    @Test
    public void dualModeAudioDeviceConnected_withDualModeFeatureEnabled() {
        // Turn on the dual mode audio flag
        Utils.setDualModeAudioStateForTesting(true);
        reset(mLeAudioService);
        when(mAdapterService.isAllSupportedClassicAudioProfilesActive(mDualModeAudioDevice))
                .thenReturn(false);

        leAudioConnected(mDualModeAudioDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        // Verify setting LEA active fails when all supported classic audio profiles are not active
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mDualModeAudioDevice);
        Assert.assertNull(mActiveDeviceManager.getLeAudioActiveDevice());
        Assert.assertNull(mActiveDeviceManager.getA2dpActiveDevice());
        Assert.assertNull(mActiveDeviceManager.getHfpActiveDevice());

        when(mLeAudioService.setActiveDevice(any())).thenReturn(true);
        when(mLeAudioService.removeActiveDevice(anyBoolean())).thenReturn(true);

        // Ensure we make LEA active after all supported classic profiles are active
        a2dpActiveDeviceChanged(mDualModeAudioDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        when(mAdapterService.isAllSupportedClassicAudioProfilesActive(mDualModeAudioDevice))
                .thenReturn(true);
        headsetActiveDeviceChanged(mDualModeAudioDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mLeAudioService, times(2)).setActiveDevice(mDualModeAudioDevice);
        Assert.assertEquals(mDualModeAudioDevice, mActiveDeviceManager.getA2dpActiveDevice());
        Assert.assertEquals(mDualModeAudioDevice, mActiveDeviceManager.getHfpActiveDevice());
        Assert.assertEquals(mDualModeAudioDevice, mActiveDeviceManager.getLeAudioActiveDevice());

        // Verify LEA made inactive when a supported classic audio profile is made inactive
        a2dpActiveDeviceChanged(null);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        Assert.assertEquals(null, mActiveDeviceManager.getA2dpActiveDevice());
        Assert.assertEquals(null, mActiveDeviceManager.getLeAudioActiveDevice());
    }

    /**
     * Verifies that other profiles do not have their active device cleared when we fail to make
     * a newly connected device active.
     */
    @Test
    public void setActiveDeviceFailsUponConnection() {
        Utils.setDualModeAudioStateForTesting(false);
        when(mHeadsetService.setActiveDevice(any())).thenReturn(false);
        when(mA2dpService.setActiveDevice(any())).thenReturn(false);
        when(mHearingAidService.setActiveDevice(any())).thenReturn(false);
        when(mLeAudioService.setActiveDevice(any())).thenReturn(false);

        leAudioConnected(mDualModeAudioDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mDualModeAudioDevice);

        leAudioActiveDeviceChanged(mDualModeAudioDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mA2dpService, times(1)).removeActiveDevice(anyBoolean());
        verify(mHeadsetService, times(1)).setActiveDevice(null);
        verify(mHearingAidService, times(1)).removeActiveDevice(anyBoolean());

        a2dpConnected(mA2dpDevice, false);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice);
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());

        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mA2dpService, timeout(TIMEOUT_MS).atLeastOnce()).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS).atLeastOnce())
                .setActiveDevice(mA2dpHeadsetDevice);
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());

        headsetConnected(mHeadsetDevice, false);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());

        hearingAidConnected(mHearingAidDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mHearingAidService, timeout(TIMEOUT_MS)).setActiveDevice(mHearingAidDevice);
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());
        verify(mA2dpService, times(1)).removeActiveDevice(anyBoolean());
        verify(mHeadsetService, times(1)).setActiveDevice(null);

        leAudioConnected(mLeHearingAidDevice);
        leHearingAidConnected(mLeHearingAidDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mLeAudioService, times(2)).setActiveDevice(mLeHearingAidDevice);
        verify(mA2dpService, times(1)).removeActiveDevice(anyBoolean());
        verify(mHeadsetService, times(1)).setActiveDevice(null);
        verify(mHearingAidService, times(1)).removeActiveDevice(anyBoolean());
    }

    /**
     * A wired audio device is connected. Then all active devices are set to null.
     */
    @Test
    public void wiredAudioDeviceConnected_setAllActiveDevicesNull() {
        a2dpConnected(mA2dpDevice, false);
        headsetConnected(mHeadsetDevice, false);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);

        mActiveDeviceManager.wiredAudioDeviceConnected();
        verify(mA2dpService, timeout(TIMEOUT_MS)).removeActiveDevice(false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(isNull());
        verify(mHearingAidService, timeout(TIMEOUT_MS)).removeActiveDevice(false);
    }

    /**
     * Helper to indicate A2dp connected for a device.
     */
    private void a2dpConnected(BluetoothDevice device, boolean supportHfp) {
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.HEADSET))
                .thenReturn(supportHfp ? BluetoothProfile.CONNECTION_POLICY_ALLOWED
                        : BluetoothProfile.CONNECTION_POLICY_UNKNOWN);

        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate A2dp disconnected for a device.
     */
    private void a2dpDisconnected(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mMostRecentDevice = (mDeviceConnectionStack.size() > 0)
                ? mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1) : null;

        Intent intent = new Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate A2dp active device changed for a device.
     */
    private void a2dpActiveDeviceChanged(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate Headset connected for a device.
     */
    private void headsetConnected(BluetoothDevice device, boolean supportA2dp) {
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.A2DP))
                .thenReturn(supportA2dp ? BluetoothProfile.CONNECTION_POLICY_ALLOWED
                        : BluetoothProfile.CONNECTION_POLICY_UNKNOWN);

        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate Headset disconnected for a device.
     */
    private void headsetDisconnected(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mMostRecentDevice = (mDeviceConnectionStack.size() > 0)
                ? mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1) : null;

        Intent intent = new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate Headset active device changed for a device.
     */
    private void headsetActiveDeviceChanged(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate Hearing Aid connected for a device.
     */
    private void hearingAidConnected(BluetoothDevice device) {
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate Hearing Aid disconnected for a device.
     */
    private void hearingAidDisconnected(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mMostRecentDevice = (mDeviceConnectionStack.size() > 0)
                ? mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1) : null;

        Intent intent = new Intent(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate Hearing Aid active device changed for a device.
     */
    private void hearingAidActiveDeviceChanged(BluetoothDevice device) {
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
        mDeviceConnectionStack.remove(device);
        mDeviceConnectionStack.add(device);
    }

    /**
     * Helper to indicate LE Audio connected for a device.
     */
    private void leAudioConnected(BluetoothDevice device) {
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate LE Audio disconnected for a device.
     */
    private void leAudioDisconnected(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mMostRecentDevice = (mDeviceConnectionStack.size() > 0)
                ? mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1) : null;

        Intent intent = new Intent(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate LE Audio active device changed for a device.
     */
    private void leAudioActiveDeviceChanged(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate LE Hearing Aid connected for a device.
     */
    private void leHearingAidConnected(BluetoothDevice device) {
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothHapClient.ACTION_HAP_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate LE Hearing Aid disconnected for a device.
     */
    private void leHearingAidDisconnected(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mMostRecentDevice = (mDeviceConnectionStack.size() > 0)
                ? mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1) : null;

        Intent intent = new Intent(BluetoothHapClient.ACTION_HAP_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate LE Audio Hearing Aid device changed for a device.
     */
    private void leHearingAidActiveDeviceChanged(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothHapClient.ACTION_HAP_DEVICE_AVAILABLE);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }
}
