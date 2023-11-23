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
package com.android.bluetooth.avrcpcontroller;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.media.AudioManager;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.test.filters.MediumTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService.BrowseResult;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class AvrcpControllerServiceTest {
    private static final String REMOTE_DEVICE_ADDRESS = "00:00:00:00:00:00";
    private static final byte[] REMOTE_DEVICE_ADDRESS_AS_ARRAY = new byte[]{0, 0, 0, 0, 0, 0};

    private AvrcpControllerService mService = null;
    private BluetoothAdapter mAdapter = null;

    @Rule public final ServiceTestRule mServiceRule = new ServiceTestRule();
    @Rule
    public final ServiceTestRule mBluetoothBrowserMediaServiceTestRule = new ServiceTestRule();

    @Mock private AdapterService mAdapterService;
    @Mock private AvrcpControllerStateMachine mStateMachine;

    private BluetoothDevice mRemoteDevice;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        TestUtils.setAdapterService(mAdapterService);
        doReturn(true, false).when(mAdapterService).isStartedProfile(anyString());
        TestUtils.startService(mServiceRule, AvrcpControllerService.class);
        mService = AvrcpControllerService.getAvrcpControllerService();
        assertThat(mService).isNotNull();
        // Try getting the Bluetooth adapter
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        assertThat(mAdapter).isNotNull();
        mRemoteDevice = mAdapter.getRemoteDevice(REMOTE_DEVICE_ADDRESS);
        mService.mDeviceStateMap.put(mRemoteDevice, mStateMachine);
        final Intent bluetoothBrowserMediaServiceStartIntent =
                TestUtils.prepareIntentToStartBluetoothBrowserMediaService();
        mBluetoothBrowserMediaServiceTestRule.startService(bluetoothBrowserMediaServiceStartIntent);
    }

    @After
    public void tearDown() throws Exception {
        TestUtils.stopService(mServiceRule, AvrcpControllerService.class);
        mService = AvrcpControllerService.getAvrcpControllerService();
        assertThat(mService).isNull();
        TestUtils.clearAdapterService(mAdapterService);
    }

    @Test
    public void initialize() {
        assertThat(AvrcpControllerService.getAvrcpControllerService()).isNotNull();
    }

    @Test
    public void disconnect_whenDisconnected_returnsFalse() {
        when(mStateMachine.getState()).thenReturn(BluetoothProfile.STATE_DISCONNECTED);

        assertThat(mService.disconnect(mRemoteDevice)).isFalse();
    }

    @Test
    public void disconnect_whenDisconnected_returnsTrue() {
        when(mStateMachine.getState()).thenReturn(BluetoothProfile.STATE_CONNECTED);

        assertThat(mService.disconnect(mRemoteDevice)).isTrue();
        verify(mStateMachine).disconnect();
    }

    @Test
    public void removeStateMachine() {
        when(mStateMachine.getDevice()).thenReturn(mRemoteDevice);

        mService.removeStateMachine(mStateMachine);

        assertThat(mService.mDeviceStateMap).doesNotContainKey(mRemoteDevice);
    }

    @Test
    public void getConnectedDevices() {
        when(mAdapterService.getBondedDevices()).thenReturn(
                new BluetoothDevice[]{mRemoteDevice});
        when(mStateMachine.getState()).thenReturn(BluetoothProfile.STATE_CONNECTED);

        assertThat(mService.getConnectedDevices()).contains(mRemoteDevice);
    }

    @Test
    public void setActiveDevice_whenA2dpSinkServiceIsNotInitailized_returnsFalse() {
        assertThat(mService.setActiveDevice(mRemoteDevice)).isFalse();

        assertThat(mService.getActiveDevice()).isNull();
    }

    @Test
    public void getCurrentMetadataIfNoCoverArt_doesNotCrash() {
        mService.getCurrentMetadataIfNoCoverArt(mRemoteDevice);
    }

    @Test
    public void refreshContents() {
        BrowseTree.BrowseNode node = mock(BrowseTree.BrowseNode.class);
        when(node.getDevice()).thenReturn(mRemoteDevice);

        mService.refreshContents(node);

        verify(mStateMachine).requestContents(node);
    }

    @Test
    public void playItem() {
        String parentMediaId = "test_parent_media_id";
        BrowseTree.BrowseNode node = mock(BrowseTree.BrowseNode.class);
        when(mStateMachine.findNode(parentMediaId)).thenReturn(node);

        mService.playItem(parentMediaId);

        verify(mStateMachine).playItem(node);
    }

    @Test
    public void getContents() {
        String parentMediaId = "test_parent_media_id";
        BrowseTree.BrowseNode node = mock(BrowseTree.BrowseNode.class);
        when(mStateMachine.findNode(parentMediaId)).thenReturn(node);

        mService.getContents(parentMediaId);

        verify(node, atLeastOnce()).getContents();
    }

    /**
     * Pre-conditions: No node in BrowseTree for specified media ID
     * Test: Call AvrcpControllerService.getContents()
     * Expected Output: BrowseResult object with status ERROR_MEDIA_ID_INVALID
     */
    @Test
    public void testGetContentsNoNode_returnInvalidMediaIdStatus() {
        String parentMediaId = "test_parent_media_id";
        when(mStateMachine.findNode(parentMediaId)).thenReturn(null);
        BrowseResult result = mService.getContents(parentMediaId);

        assertThat(result.getStatus()).isEqualTo(BrowseResult.ERROR_MEDIA_ID_INVALID);
    }

    /**
     * Pre-conditions: No device is connected - parent media ID is at the root of the BrowseTree
     * Test: Call AvrcpControllerService.getContents()
     * Expected Output: BrowseResult object with status NO_DEVICE_CONNECTED
     */
    @Test
    public void getContentsNoDeviceConnected_returnNoDeviceConnectedStatus() {
        String parentMediaId = BrowseTree.ROOT;
        BrowseResult result = mService.getContents(parentMediaId);

        assertThat(result.getStatus()).isEqualTo(BrowseResult.NO_DEVICE_CONNECTED);
    }

    /**
     * Pre-conditions: At least one device is connected
     * Test: Call AvrcpControllerService.getContents()
     * Expected Output: BrowseResult object with status SUCCESS
     */
    @Test
    public void getContentsOneDeviceConnected_returnSuccessStatus() {
        String parentMediaId = BrowseTree.ROOT;
        mService.sBrowseTree.onConnected(mRemoteDevice);
        BrowseResult result = mService.getContents(parentMediaId);

        assertThat(result.getStatus()).isEqualTo(BrowseResult.SUCCESS);
    }

    /**
     * Pre-conditions: Node for specified media ID is not cached
     * Test: {@link BrowseTree.BrowseNode#getContents} returns {@code null} when the node has no
     * children/items and the node is not cached.
     * When {@link AvrcpControllerService#getContents} receives a node that is not cached,
     * it should interpret the status as `DOWNLOAD_PENDING`.
     * Expected Output: BrowseResult object with status DOWNLOAD_PENDING; verify that a download
     * request has been sent by checking if mStateMachine.requestContents() is called
     */
    @Test
    public void getContentsNodeNotCached_returnDownloadPendingStatus() {
        String parentMediaId = "test_parent_media_id";
        BrowseTree.BrowseNode node = mock(BrowseTree.BrowseNode.class);
        when(mStateMachine.findNode(parentMediaId)).thenReturn(node);
        when(node.isCached()).thenReturn(false);
        when(node.getDevice()).thenReturn(mRemoteDevice);
        when(node.getID()).thenReturn(parentMediaId);

        BrowseResult result = mService.getContents(parentMediaId);

        verify(mStateMachine, times(1)).requestContents(eq(node));
        assertThat(result.getStatus()).isEqualTo(BrowseResult.DOWNLOAD_PENDING);
    }

    /**
     * Pre-conditions: Parent media ID that is not BrowseTree.ROOT; isCached returns true
     * Test: Call AvrcpControllerService.getContents()
     * Expected Output: BrowseResult object with status SUCCESS
     */
    @Test
    public void getContentsNoErrorConditions_returnsSuccessStatus() {
        String parentMediaId = "test_parent_media_id";
        BrowseTree.BrowseNode node = mock(BrowseTree.BrowseNode.class);
        when(mStateMachine.findNode(parentMediaId)).thenReturn(node);
        when(node.getContents()).thenReturn(new ArrayList(0));
        when(node.isCached()).thenReturn(true);

        BrowseResult result = mService.getContents(parentMediaId);

        assertThat(result.getStatus()).isEqualTo(BrowseResult.SUCCESS);
    }

    @Test
    public void createFromNativeMediaItem() {
        long uid = 1;
        int type = 2;
        int[] attrIds = new int[] { 0x01 }; // MEDIA_ATTRIBUTE_TITLE}
        String[] attrVals = new String[] {"test_title"};

        AvrcpItem item = mService.createFromNativeMediaItem(
                REMOTE_DEVICE_ADDRESS_AS_ARRAY, uid, type, "unused_name", attrIds, attrVals);

        assertThat(item.getDevice().getAddress()).isEqualTo(REMOTE_DEVICE_ADDRESS);
        assertThat(item.getItemType()).isEqualTo(AvrcpItem.TYPE_MEDIA);
        assertThat(item.getType()).isEqualTo(type);
        assertThat(item.getUid()).isEqualTo(uid);
        assertThat(item.getUuid()).isNotNull(); // Random uuid
        assertThat(item.getTitle()).isEqualTo(attrVals[0]);
        assertThat(item.isPlayable()).isTrue();
    }

    @Test
    public void createFromNativeFolderItem() {
        long uid = 1;
        int type = 2;
        String folderName = "test_folder_name";
        int playable = 0x01; // Playable folder

        AvrcpItem item = mService.createFromNativeFolderItem(
                REMOTE_DEVICE_ADDRESS_AS_ARRAY, uid, type, folderName, playable);

        assertThat(item.getDevice().getAddress()).isEqualTo(REMOTE_DEVICE_ADDRESS);
        assertThat(item.getItemType()).isEqualTo(AvrcpItem.TYPE_FOLDER);
        assertThat(item.getType()).isEqualTo(type);
        assertThat(item.getUid()).isEqualTo(uid);
        assertThat(item.getUuid()).isNotNull(); // Random uuid
        assertThat(item.getDisplayableName()).isEqualTo(folderName);
        assertThat(item.isPlayable()).isTrue();
    }

    @Test
    public void createFromNativePlayerItem() {
        int playerId = 1;
        String name = "test_name";
        byte[] transportFlags = new byte[] {1, 0, 0, 0, 0, 0, 0, 0};
        int playStatus = AvrcpControllerService.JNI_PLAY_STATUS_REV_SEEK;
        int playerType = AvrcpPlayer.TYPE_AUDIO; // No getter exists

        AvrcpPlayer player = mService.createFromNativePlayerItem(
                REMOTE_DEVICE_ADDRESS_AS_ARRAY, playerId, name, transportFlags,
                playStatus, playerType);

        assertThat(player.getDevice().getAddress()).isEqualTo(REMOTE_DEVICE_ADDRESS);
        assertThat(player.getId()).isEqualTo(playerId);
        assertThat(player.supportsFeature(0)).isTrue();
        assertThat(player.getName()).isEqualTo(name);
        assertThat(player.getPlayStatus()).isEqualTo(PlaybackStateCompat.STATE_REWINDING);
    }

    @Test
    public void handleChangeFolderRsp() {
        int count = 1;

        mService.handleChangeFolderRsp(REMOTE_DEVICE_ADDRESS_AS_ARRAY, count);

        verify(mStateMachine).sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_FOLDER_PATH, count);
    }

    @Test
    public void handleSetBrowsedPlayerRsp() {
        int items = 3;
        int depth = 5;

        mService.handleSetBrowsedPlayerRsp(REMOTE_DEVICE_ADDRESS_AS_ARRAY, items, depth);

        verify(mStateMachine).sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_SET_BROWSED_PLAYER, items, depth);
    }

    @Test
    public void handleSetAddressedPlayerRsp() {
        int status = 1;

        mService.handleSetAddressedPlayerRsp(REMOTE_DEVICE_ADDRESS_AS_ARRAY, status);

        verify(mStateMachine).sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_SET_ADDRESSED_PLAYER);
    }

    @Test
    public void handleAddressedPlayerChanged() {
        int id = 1;

        mService.handleAddressedPlayerChanged(REMOTE_DEVICE_ADDRESS_AS_ARRAY, id);

        verify(mStateMachine).sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED, id);
    }

    @Test
    public void handleNowPlayingContentChanged() {
        mService.handleNowPlayingContentChanged(REMOTE_DEVICE_ADDRESS_AS_ARRAY);

        verify(mStateMachine).nowPlayingContentChanged();
    }

    @Test
    public void JniApisWithNoBehaviors_doNotCrash() {
        mService.handlePassthroughRsp(1, 2, new byte[0]);
        mService.handleGroupNavigationRsp(1, 2);
        mService.getRcFeatures(new byte[0], 1);
        mService.setPlayerAppSettingRsp(new byte[0], (byte) 0);
    }

    @Test
    public void onConnectionStateChanged_connectCase() {
        boolean remoteControlConnected = true;
        boolean browsingConnected = true; // Calls connect when any of them is true.

        mService.onConnectionStateChanged(remoteControlConnected, browsingConnected,
                REMOTE_DEVICE_ADDRESS_AS_ARRAY);

        ArgumentCaptor<StackEvent> captor = ArgumentCaptor.forClass(StackEvent.class);
        verify(mStateMachine).connect(captor.capture());
        StackEvent event = captor.getValue();
        assertThat(event.mType).isEqualTo(StackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        assertThat(event.mRemoteControlConnected).isEqualTo(remoteControlConnected);
        assertThat(event.mBrowsingConnected).isEqualTo(browsingConnected);
        assertThat(BluetoothMediaBrowserService.isActive()).isFalse();
    }

    @Test
    public void onConnectionStateChanged_disconnectCase() {
        boolean remoteControlConnected = false;
        boolean browsingConnected = false; // Calls disconnect when both of them are false.

        mService.onConnectionStateChanged(
                remoteControlConnected, browsingConnected, REMOTE_DEVICE_ADDRESS_AS_ARRAY);
        assertThat(BluetoothMediaBrowserService.isActive()).isFalse();
        verify(mStateMachine).disconnect();
    }

    @Test
    public void getRcPsm() {
        int psm = 1;

        mService.getRcPsm(REMOTE_DEVICE_ADDRESS_AS_ARRAY, psm);

        verify(mStateMachine).sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_RECEIVED_COVER_ART_PSM, psm);
    }

    @Test
    public void handleRegisterNotificationAbsVol() {
        byte label = 1;

        mService.handleRegisterNotificationAbsVol(REMOTE_DEVICE_ADDRESS_AS_ARRAY, label);

        verify(mStateMachine).sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION);
    }

    @Test
    public void handleSetAbsVolume() {
        byte absVol = 15;
        byte label = 1;

        mService.handleSetAbsVolume(REMOTE_DEVICE_ADDRESS_AS_ARRAY, absVol, label);

        verify(mStateMachine).sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_SET_ABS_VOL_CMD, absVol);
    }

    @Test
    public void onTrackChanged() {
        byte numAttrs = 0;
        int[] attrs = new int[0];
        String[] attrVals = new String[0];

        mService.onTrackChanged(REMOTE_DEVICE_ADDRESS_AS_ARRAY, numAttrs, attrs, attrVals);

        ArgumentCaptor<AvrcpItem> captor = ArgumentCaptor.forClass(AvrcpItem.class);
        verify(mStateMachine).sendMessage(
                eq(AvrcpControllerStateMachine.MESSAGE_PROCESS_TRACK_CHANGED), captor.capture());
        AvrcpItem item = captor.getValue();
        assertThat(item.getDevice().getAddress()).isEqualTo(REMOTE_DEVICE_ADDRESS);
        assertThat(item.getItemType()).isEqualTo(AvrcpItem.TYPE_MEDIA);
        assertThat(item.getUuid()).isNotNull(); // Random uuid
    }

    @Test
    public void onPlayPositionChanged() {
        int songLen = 100;
        int currSongPos = 33;

        mService.onPlayPositionChanged(REMOTE_DEVICE_ADDRESS_AS_ARRAY, songLen, currSongPos);

        verify(mStateMachine).sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_PLAY_POS_CHANGED, songLen, currSongPos);
    }

    @Test
    public void onPlayStatusChanged() {
        byte status = AvrcpControllerService.JNI_PLAY_STATUS_REV_SEEK;

        mService.onPlayStatusChanged(REMOTE_DEVICE_ADDRESS_AS_ARRAY, status);

        verify(mStateMachine).sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_PLAY_STATUS_CHANGED,
                PlaybackStateCompat.STATE_REWINDING);
    }

    @Test
    public void onPlayerAppSettingChanged() {
        byte[] playerAttribRsp = new byte[] {PlayerApplicationSettings.REPEAT_STATUS,
                PlayerApplicationSettings.JNI_REPEAT_STATUS_ALL_TRACK_REPEAT};

        mService.onPlayerAppSettingChanged(REMOTE_DEVICE_ADDRESS_AS_ARRAY, playerAttribRsp, 2);

        verify(mStateMachine).sendMessage(
                eq(AvrcpControllerStateMachine.MESSAGE_PROCESS_CURRENT_APPLICATION_SETTINGS),
                any(PlayerApplicationSettings.class));
    }

    @Test
    public void onAvailablePlayerChanged() {
        mService.onAvailablePlayerChanged(REMOTE_DEVICE_ADDRESS_AS_ARRAY);

        verify(mStateMachine).sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED);
    }

    @Test
    public void handleGetFolderItemsRsp() {
        int status = 2;
        AvrcpItem[] items = new AvrcpItem[] {mock(AvrcpItem.class)};

        mService.handleGetFolderItemsRsp(REMOTE_DEVICE_ADDRESS_AS_ARRAY, status, items);

        verify(mStateMachine).sendMessage(
                eq(AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS),
                eq(new ArrayList<>(Arrays.asList(items))));
    }

    @Test
    public void handleGetPlayerItemsRsp() {
        AvrcpPlayer[] items = new AvrcpPlayer[] {mock(AvrcpPlayer.class)};

        mService.handleGetPlayerItemsRsp(REMOTE_DEVICE_ADDRESS_AS_ARRAY, items);

        verify(mStateMachine).sendMessage(
                eq(AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_PLAYER_ITEMS),
                eq(new ArrayList<>(Arrays.asList(items))));
    }

    @Test
    public void dump_doesNotCrash() {
        mService.getRcPsm(REMOTE_DEVICE_ADDRESS_AS_ARRAY, 1);
        mService.dump(new StringBuilder());
    }

    @Test
    public void testOnFocusChange_audioGainDeviceActive_sessionActivated() {
        mService.onAudioFocusStateChanged(AudioManager.AUDIOFOCUS_GAIN);
        assertThat(BluetoothMediaBrowserService.isActive()).isTrue();
    }

    @Test
    public void testOnFocusChange_audioLoss_sessionDeactivated() {
        mService.onAudioFocusStateChanged(AudioManager.AUDIOFOCUS_LOSS);
        assertThat(BluetoothMediaBrowserService.isActive()).isFalse();
    }
}
