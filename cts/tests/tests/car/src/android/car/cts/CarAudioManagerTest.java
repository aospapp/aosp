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

package android.car.cts;

import static android.car.Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_AUDIO_MIRRORING;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_DYNAMIC_ROUTING;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_VOLUME_GROUP_EVENTS;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_VOLUME_GROUP_MUTING;
import static android.car.media.CarAudioManager.CarVolumeCallback;
import static android.car.media.CarAudioManager.INVALID_AUDIO_ZONE;
import static android.car.media.CarAudioManager.INVALID_REQUEST_ID;
import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;
import static android.car.test.mocks.JavaMockitoHelper.await;
import static android.car.test.mocks.JavaMockitoHelper.silentAwait;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.UiAutomation;
import android.car.Car;
import android.car.CarOccupantZoneManager;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.PlatformVersion;
import android.car.media.AudioZonesMirrorStatusCallback;
import android.car.media.CarAudioManager;
import android.car.media.CarAudioZoneConfigInfo;
import android.car.media.CarVolumeGroupEvent;
import android.car.media.CarVolumeGroupEventCallback;
import android.car.media.CarVolumeGroupInfo;
import android.car.media.MediaAudioRequestStatusCallback;
import android.car.media.PrimaryZoneMediaAudioRequestCallback;
import android.car.media.SwitchAudioZoneConfigCallback;
import android.car.test.PermissionsCheckerRule;
import android.car.test.PermissionsCheckerRule.EnsureHasPermission;
import android.os.SystemClock;
import android.platform.test.annotations.AppModeFull;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.ApiTest;
import com.android.compatibility.common.util.ShellUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = "Instant Apps cannot get car related permissions")
public final class CarAudioManagerTest extends AbstractCarTestCase {

    private static final String TAG = CarAudioManagerTest.class.getSimpleName();

    private static final long WAIT_TIMEOUT_MS = 5_000;

    private static final Pattern ZONE_PATTERN = Pattern.compile(
            "CarAudioZone\\(.*:(\\d?)\\) isPrimary\\? (.*?)\n.*Current Config Id: (\\d?)");
    private static final Pattern VOLUME_GROUP_PATTERN = Pattern.compile(
            "CarVolumeGroup\\((\\d?)\\)\n.*Name\\((.*?)\\)\n.*Zone Id\\((\\d?)\\)\n"
                    + ".*Configuration Id\\((\\d?)\\)");
    private static final Pattern ZONE_CONFIG_PATTERN = Pattern.compile(
            "CarAudioZoneConfig\\((.*?):(\\d?)\\) of zone (\\d?) isDefault\\? (.*?)");

    private static final Pattern PRIMARY_ZONE_MEDIA_REQUEST_APPROVERS_PATTERN =
            Pattern.compile("Media request callbacks\\[(\\d+)\\]:");

    @Rule
    public final PermissionsCheckerRule mPermissionsCheckerRule = new PermissionsCheckerRule();

    private static final UiAutomation UI_AUTOMATION =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();

    private CarAudioManager mCarAudioManager;
    private SyncCarVolumeCallback mCallback;
    private int mZoneId = -1;
    private int mVolumeGroupId = -1;
    private CarOccupantZoneManager mCarOccupantZoneManager;
    private TestPrimaryZoneMediaAudioRequestStatusCallback mRequestCallback;
    private long mMediaRequestId = INVALID_REQUEST_ID;
    private String mCarAudioServiceDump;
    private TestAudioZonesMirrorStatusCallback mAudioZonesMirrorCallback;
    private long mMirrorRequestId = INVALID_REQUEST_ID;
    private TestCarVolumeGroupEventCallback mEventCallback;

    @Before
    public void setUp() throws Exception {
        mCarAudioManager = getCar().getCarManager(CarAudioManager.class);
        mCarOccupantZoneManager = getCar().getCarManager(CarOccupantZoneManager.class);
        // TODO(b/271918489): dump CarAudioService as protobuf
        mCarAudioServiceDump = ShellUtils.runShellCommand(
                "dumpsys car_service --services CarAudioService");
    }

    @After
    public void cleanUp() {
        if (mCallback != null) {
            // Unregistering the last callback requires PERMISSION_CAR_CONTROL_AUDIO_VOLUME
            runWithCarControlAudioVolumePermission(
                    () -> mCarAudioManager.unregisterCarVolumeCallback(mCallback));
        }

        if (mMediaRequestId != INVALID_REQUEST_ID) {
            Log.w(TAG, "Cancelling media request " +  mMediaRequestId);
            mCarAudioManager.cancelMediaAudioOnPrimaryZone(mMediaRequestId);
        }

        if (mRequestCallback != null) {
            Log.w(TAG, "Releasing media request callback");
            mCarAudioManager.clearPrimaryZoneMediaAudioRequestCallback();
        }

        if (mAudioZonesMirrorCallback != null) {
            Log.i(TAG, "Releasing audio mirror request callback");
            mCarAudioManager.clearAudioZonesMirrorStatusCallback();
        }

        if (mMirrorRequestId != INVALID_REQUEST_ID) {
            Log.i(TAG, "Disabling audio mirror for request: " + mMirrorRequestId);
            mCarAudioManager.disableAudioMirror(mMirrorRequestId);
        }

        if (mEventCallback != null && Car.getPlatformVersion().isAtLeast(
                PlatformVersion.VERSION_CODES.UPSIDE_DOWN_CAKE_0)) {
            runWithCarControlAudioVolumePermission(
                    () -> mCarAudioManager.unregisterCarVolumeGroupEventCallback(mEventCallback));
        }
    }

    @Test
    @ApiTest(apis = {"android.car.media.CarAudioManager#isAudioFeatureEnabled(int)"})
    public void isAudioFeatureEnabled_withVolumeGroupMuteFeature_succeeds() {
        boolean volumeGroupMutingEnabled = mCarAudioManager.isAudioFeatureEnabled(
                        AUDIO_FEATURE_VOLUME_GROUP_MUTING);

        assertThat(volumeGroupMutingEnabled).isAnyOf(true, false);
    }

    @Test
    @ApiTest(apis = {"android.car.media.CarAudioManager#isAudioFeatureEnabled(int)"})
    public void isAudioFeatureEnabled_withDynamicRoutingFeature_succeeds() {
        boolean dynamicRoutingEnabled = mCarAudioManager.isAudioFeatureEnabled(
                        AUDIO_FEATURE_DYNAMIC_ROUTING);

        assertThat(dynamicRoutingEnabled).isAnyOf(true, false);
    }

    @Test
    @ApiTest(apis = {"android.car.media.CarAudioManager#isAudioFeatureEnabled(int)"})
    public void isAudioFeatureEnabled_withVolumeGroupEventsFeature_succeeds() {
        boolean volumeGroupEventsEnabled = mCarAudioManager.isAudioFeatureEnabled(
                AUDIO_FEATURE_VOLUME_GROUP_EVENTS);

        assertWithMessage("Car volume group events feature").that(volumeGroupEventsEnabled)
                .isAnyOf(true, false);
    }

    @Test
    @ApiTest(apis = {"android.car.media.CarAudioManager#isAudioFeatureEnabled(int)"})
    public void isAudioFeatureEnabled_withNonAudioFeature_fails() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioManager.isAudioFeatureEnabled(-1));

        assertThat(exception).hasMessageThat().contains("Unknown Audio Feature");
    }

    @Test
    @ApiTest(apis = {"android.car.media.CarAudioManager#isAudioFeatureEnabled(int)",
            "android.car.media.CarAudioManager#AUDIO_FEATURE_AUDIO_MIRRORING"})
    public void isAudioFeatureEnabled_withAudioMirrorFeature_succeeds() {
        boolean audioMirroringEnabled = mCarAudioManager.isAudioFeatureEnabled(
                AUDIO_FEATURE_AUDIO_MIRRORING);

        assertThat(audioMirroringEnabled).isAnyOf(true, false);
    }

    @Test
    @ApiTest(apis = {
            "android.car.media.CarAudioManager#registerCarVolumeCallback(CarVolumeCallback)"})
    public void registerCarVolumeCallback_nullCallback_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> mCarAudioManager.registerCarVolumeCallback(null));
    }

    @Test
    @ApiTest(apis = {
            "android.car.media.CarAudioManager#registerCarVolumeCallback(CarVolumeCallback)"})
    public void registerCarVolumeCallback_nonNullCallback_throwsPermissionError() {
        mCallback = new SyncCarVolumeCallback();

        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.registerCarVolumeCallback(mCallback));

        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @ApiTest(apis = {
            "android.car.media.CarAudioManager#registerCarVolumeCallback(CarVolumeCallback)"})
    public void registerCarVolumeCallback_onGroupVolumeChanged() throws Exception {
        assumeDynamicRoutingIsEnabled();
        mCallback = new SyncCarVolumeCallback();

        mCarAudioManager.registerCarVolumeCallback(mCallback);

        injectVolumeDownKeyEvent();
        assertWithMessage("CarVolumeCallback#onGroupVolumeChanged should be called")
                .that(mCallback.receivedGroupVolumeChanged())
                .isTrue();
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @ApiTest(apis = {
            "android.car.media.CarAudioManager#registerCarVolumeCallback(CarVolumeCallback)"})
    public void registerCarVolumeCallback_onMasterMuteChanged() throws Exception {
        assumeDynamicRoutingIsEnabled();
        assumeVolumeGroupMutingIsDisabled();
        mCallback = new SyncCarVolumeCallback();

        mCarAudioManager.registerCarVolumeCallback(mCallback);

        injectVolumeMuteKeyEvent();
        try {
            assertWithMessage("CarVolumeCallback#onMasterMuteChanged should be called")
                .that(mCallback.receivedMasterMuteChanged())
                .isTrue();
        } finally {
            injectVolumeMuteKeyEvent();
        }
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @ApiTest(apis = {
            "android.car.media.CarAudioManager#registerCarVolumeCallback(CarVolumeCallback)"})
    public void registerCarVolumeCallback_onGroupMuteChanged() throws Exception {
        assumeDynamicRoutingIsEnabled();
        assumeVolumeGroupMutingIsEnabled();
        mCallback = new SyncCarVolumeCallback();

        readFirstZoneAndVolumeGroup();
        mCarAudioManager.registerCarVolumeCallback(mCallback);
        setVolumeGroupMute(mZoneId, mVolumeGroupId, /* mute= */ true);
        setVolumeGroupMute(mZoneId, mVolumeGroupId, /* mute= */ false);

        assertWithMessage("CarVolumeCallback#onGroupMuteChanged should be called")
            .that(mCallback.receivedGroupMuteChanged()).isTrue();
        assertWithMessage("CarVolumeCallback#onGroupMuteChanged wrong zoneId")
            .that(mCallback.zoneId).isEqualTo(mZoneId);
        assertWithMessage("CarVolumeCallback#onGroupMuteChanged wrong groupId")
            .that(mCallback.groupId).isEqualTo(mVolumeGroupId);
    }

    private void readFirstZoneAndVolumeGroup() {
        Matcher matchZone = ZONE_PATTERN.matcher(mCarAudioServiceDump);
        assertWithMessage("No CarAudioZone in dump").that(matchZone.find()).isTrue();
        mZoneId = Integer.parseInt(matchZone.group(1));
        int currentConfigId = Integer.parseInt(matchZone.group(3));
        readFirstVolumeGroup(mZoneId, currentConfigId);
    }

    private void readFirstVolumeGroup(int zoneId, int currentConfigId) {
        Matcher matchGroup = VOLUME_GROUP_PATTERN.matcher(mCarAudioServiceDump);
        boolean findVolumeGroup = false;
        while (matchGroup.find()) {
            if (Integer.parseInt(matchGroup.group(3)) == zoneId
                    && Integer.parseInt(matchGroup.group(4)) == currentConfigId) {
                mVolumeGroupId = Integer.parseInt(matchGroup.group(1));
                findVolumeGroup = true;
                break;
            }
        }
        assertWithMessage("No CarVolumeGroup in dump").that(findVolumeGroup).isTrue();
    }

    private void setVolumeGroupMute(int zoneId, int groupId, boolean mute) {
        ShellUtils.runShellCommand("cmd car_service set-mute-car-volume-group %d %d %s",
            zoneId, groupId, mute ? "mute" : "unmute");
    }

    @Test
    @ApiTest(apis = {
            "android.car.media.CarAudioManager#unregisterCarVolumeCallback(CarVolumeCallback)"})
    public void unregisterCarVolumeCallback_nullCallback_throws() {
        assertThrows(NullPointerException.class,
                () -> mCarAudioManager.unregisterCarVolumeCallback(null));
    }

    @Test
    @ApiTest(apis = {
            "android.car.media.CarAudioManager#unregisterCarVolumeCallback(CarVolumeCallback)"})
    public void unregisterCarVolumeCallback_unregisteredCallback_doesNotReceiveCallback()
            throws Exception {
        mCallback = new SyncCarVolumeCallback();

        mCarAudioManager.unregisterCarVolumeCallback(mCallback);

        assertWithMessage("CarVolumeCallback#onGroupVolumeChanged should not be called")
                .that(mCallback.receivedGroupVolumeChanged())
                .isFalse();
    }

    @Test
    @ApiTest(apis = {
            "android.car.media.CarAudioManager#unregisterCarVolumeCallback(CarVolumeCallback)"})
    public void unregisterCarVolumeCallback_withoutPermission_throws() {
        mCallback = new SyncCarVolumeCallback();
        runWithCarControlAudioVolumePermission(
                () -> mCarAudioManager.registerCarVolumeCallback(mCallback));

        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.unregisterCarVolumeCallback(mCallback));

        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    @ApiTest(apis = {
            "android.car.media.CarAudioManager#unregisterCarVolumeCallback(CarVolumeCallback)"})
    public void unregisterCarVolumeCallback_withoutPermission_receivesCallback() {
        assumeDynamicRoutingIsEnabled();
        mCallback = new SyncCarVolumeCallback();
        runWithCarControlAudioVolumePermission(
                () -> mCarAudioManager.registerCarVolumeCallback(mCallback));

        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.unregisterCarVolumeCallback(mCallback));

        injectVolumeDownKeyEvent();
        assertWithMessage("Car group volume change after unregister security exception")
                .that(mCallback.receivedGroupVolumeChanged()).isTrue();
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @ApiTest(apis = {
            "android.car.media.CarAudioManager#unregisterCarVolumeCallback(CarVolumeCallback)"})
    public void unregisterCarVolumeCallback_noLongerReceivesCallback() throws Exception {
        assumeDynamicRoutingIsEnabled();
        SyncCarVolumeCallback callback = new SyncCarVolumeCallback();
        mCarAudioManager.registerCarVolumeCallback(callback);
        mCarAudioManager.unregisterCarVolumeCallback(callback);

        injectVolumeDownKeyEvent();

        assertWithMessage("CarVolumeCallback#onGroupVolumeChanged should not be called")
                .that(callback.receivedGroupVolumeChanged())
                .isFalse();
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @ApiTest(apis = {"android.car.media.CarAudioManager#getVolumeGroupInfo(int, int)"})
    public void getVolumeGroupInfo() {
        assumeDynamicRoutingIsEnabled();
        int groupCount = mCarAudioManager.getVolumeGroupCount(PRIMARY_AUDIO_ZONE);

        for (int index = 0; index < groupCount; index++) {
            int minIndex = mCarAudioManager.getGroupMinVolume(PRIMARY_AUDIO_ZONE, index);
            int maxIndex = mCarAudioManager.getGroupMaxVolume(PRIMARY_AUDIO_ZONE, index);
            CarVolumeGroupInfo info =
                    mCarAudioManager.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE, index);
            expectWithMessage("Car volume group id for info %s and group %s", info, index)
                    .that(info.getId()).isEqualTo(index);
            expectWithMessage("Car volume group info zone for info %s and group %s",
                    info, index).that(info.getZoneId()).isEqualTo(PRIMARY_AUDIO_ZONE);
            expectWithMessage("Car volume group info max index for info %s and group %s",
                    info, index).that(info.getMaxVolumeGainIndex()).isEqualTo(maxIndex);
            expectWithMessage("Car volume group info min index for info %s and group %s",
                    info, index).that(info.getMinVolumeGainIndex()).isEqualTo(minIndex);
        }
    }

    @Test
    @ApiTest(apis = {"android.car.media.CarAudioManager#getVolumeGroupInfo(int, int)"})
    public void getVolumeGroupInfo_withoutPermission() {
        Exception exception = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE, /* groupId= */ 0));

        assertWithMessage("Car volume group info without permission exception")
                .that(exception).hasMessageThat().contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @ApiTest(apis = {"android.car.media.CarAudioManager#getVolumeGroupInfosForZone(int)"})
    public void getVolumeGroupInfosForZone() {
        assumeDynamicRoutingIsEnabled();
        int groupCount = mCarAudioManager.getVolumeGroupCount(PRIMARY_AUDIO_ZONE);

        List<CarVolumeGroupInfo> infos =
                mCarAudioManager.getVolumeGroupInfosForZone(PRIMARY_AUDIO_ZONE);

        expectWithMessage("Car volume group infos for primary zone")
                .that(infos).hasSize(groupCount);
        for (int index = 0; index < groupCount; index++) {
            CarVolumeGroupInfo info =
                    mCarAudioManager.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE, index);
            expectWithMessage("Car volume group infos for info %s and group %s", info, index)
                    .that(infos).contains(info);
        }
    }

    @Test
    @ApiTest(apis = {"android.car.media.CarAudioManager#getVolumeGroupInfosForZone(int)"})
    public void getVolumeGroupInfosForZone_withoutPermission() {
        Exception exception = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getVolumeGroupInfosForZone(PRIMARY_AUDIO_ZONE));

        assertWithMessage("Car volume groups info without permission exception")
                .that(exception).hasMessageThat().contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#getAudioAttributesForVolumeGroup(CarVolumeGroupInfo)"})
    public void getAudioAttributesForVolumeGroup() {
        assumeDynamicRoutingIsEnabled();
        CarVolumeGroupInfo info =
                mCarAudioManager.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE, /* groupId= */ 0);

        expectWithMessage("Car volume audio attributes")
                .that(mCarAudioManager.getAudioAttributesForVolumeGroup(info))
                .isNotEmpty();
    }

    @Test
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#getAudioAttributesForVolumeGroup(CarVolumeGroupInfo)"})
    public void getAudioAttributesForVolumeGroup_withoutPermission() {
        assumeDynamicRoutingIsEnabled();
        CarVolumeGroupInfo info;

        UI_AUTOMATION.adoptShellPermissionIdentity(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        try {
            info =  mCarAudioManager.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE, /* groupId= */ 0);
        } finally {
            UI_AUTOMATION.dropShellPermissionIdentity();
        }

        Exception exception = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getAudioAttributesForVolumeGroup(info));

        assertWithMessage("Car volume group audio attributes without permission exception")
                .that(exception).hasMessageThat().contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#isMediaAudioAllowedInPrimaryZone(OccupantZoneInfo)"})
    public void isMediaAudioAllowedInPrimaryZone_byDefault() {
        int passengerAudioZoneId = assumePassengerWithValidAudioZone();
        OccupantZoneInfo info =
                        mCarOccupantZoneManager.getOccupantForAudioZoneId(passengerAudioZoneId);

        assertWithMessage("Default media allowed status")
                .that(mCarAudioManager.isMediaAudioAllowedInPrimaryZone(info)).isFalse();
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#isMediaAudioAllowedInPrimaryZone(OccupantZoneInfo)"})
    public void isMediaAudioAllowedInPrimaryZone_afterAllowed() throws Exception {
        int passengerAudioZoneId = assumePassengerWithValidAudioZone();
        setupMediaAudioRequestCallback();
        OccupantZoneInfo info =
                mCarOccupantZoneManager.getOccupantForAudioZoneId(passengerAudioZoneId);
        requestToPlayMediaInPrimaryZone(info);
        mRequestCallback.receivedMediaRequest();
        mCarAudioManager.allowMediaAudioOnPrimaryZone(mMediaRequestId, /* allow= */ true);

        boolean approved = mCarAudioManager.isMediaAudioAllowedInPrimaryZone(info);

        assertWithMessage("Approved media allowed status")
                .that(approved).isTrue();
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#isMediaAudioAllowedInPrimaryZone(OccupantZoneInfo)"})
    public void isMediaAudioAllowedInPrimaryZone_afterRejected() throws Exception {
        int passengerAudioZoneId = assumePassengerWithValidAudioZone();
        setupMediaAudioRequestCallback();
        OccupantZoneInfo info =
                mCarOccupantZoneManager.getOccupantForAudioZoneId(passengerAudioZoneId);
        requestToPlayMediaInPrimaryZone(info);
        mRequestCallback.receivedMediaRequest();
        mCarAudioManager.allowMediaAudioOnPrimaryZone(mMediaRequestId, /* allow= */ false);

        boolean approved = mCarAudioManager.isMediaAudioAllowedInPrimaryZone(info);

        assertWithMessage("Unapproved media allowed status")
                .that(approved).isFalse();
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#setPrimaryZoneMediaAudioRequestCallback(Executor,"
            + "PrimaryZoneMediaAudioRequestCallback)"})
    public void setPrimaryZoneMediaAudioRequestCallback() {
        assumePassengerWithValidAudioZone();
        Executor executor = Executors.newFixedThreadPool(1);
        TestPrimaryZoneMediaAudioRequestStatusCallback
                requestCallback = new TestPrimaryZoneMediaAudioRequestStatusCallback();

        boolean registered =
                mCarAudioManager.setPrimaryZoneMediaAudioRequestCallback(executor, requestCallback);

        assertWithMessage("Set status of media request callback")
                .that(registered).isTrue();
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#requestMediaAudioOnPrimaryZone(OccupantZoneInfo, Executor, "
            + "MediaAudioRequestStatusCallback)"})
    public void requestMediaAudioOnPrimaryZone() throws Exception {
        assumeDynamicRoutingIsEnabled();
        int passengerAudioZoneId = assumePassengerWithValidAudioZone();
        setupMediaAudioRequestCallback();
        OccupantZoneInfo info =
                mCarOccupantZoneManager.getOccupantForAudioZoneId(passengerAudioZoneId);
        Executor callbackExecutor = Executors.newFixedThreadPool(1);
        TestMediaAudioRequestStatusCallback callback = new TestMediaAudioRequestStatusCallback();

        mMediaRequestId =
                mCarAudioManager.requestMediaAudioOnPrimaryZone(info, callbackExecutor, callback);

        mRequestCallback.receivedMediaRequest();
        assertWithMessage("Received request id").that(mRequestCallback.mRequestId)
                .isEqualTo(mMediaRequestId);
        assertWithMessage("Received occupant info").that(mRequestCallback.mOccupantZoneInfo)
                .isEqualTo(info);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#requestMediaAudioOnPrimaryZone(OccupantZoneInfo,"
            + "Executor, MediaAudioRequestStatusCallback)"})
    public void requestMediaAudioOnPrimaryZone_withoutApprover() throws Exception {
        assumeDynamicRoutingIsEnabled();
        assumeNoPrimaryZoneAudioMediaApprovers();
        int passengerAudioZoneId = assumePassengerWithValidAudioZone();
        OccupantZoneInfo info =
                mCarOccupantZoneManager.getOccupantForAudioZoneId(passengerAudioZoneId);
        Executor callbackExecutor = Executors.newFixedThreadPool(1);
        TestMediaAudioRequestStatusCallback callback = new TestMediaAudioRequestStatusCallback();

        mMediaRequestId =
                mCarAudioManager.requestMediaAudioOnPrimaryZone(info, callbackExecutor, callback);

        callback.receivedApproval();
        assertWithMessage("Request id for rejected request")
                .that(callback.mRequestId).isEqualTo(mMediaRequestId);
        assertWithMessage("Rejected request status").that(callback.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_REJECTED);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#allowMediaAudioOnPrimaryZone(long, boolean)"})
    public void allowMediaAudioOnPrimaryZone() throws Exception {
        int passengerAudioZoneId = assumePassengerWithValidAudioZone();
        setupMediaAudioRequestCallback();
        OccupantZoneInfo info =
                mCarOccupantZoneManager.getOccupantForAudioZoneId(passengerAudioZoneId);
        TestMediaAudioRequestStatusCallback callback =
                requestToPlayMediaInPrimaryZone(info);
        mRequestCallback.receivedMediaRequest();

        boolean succeeded = mCarAudioManager.allowMediaAudioOnPrimaryZone(mMediaRequestId,
                /* allow= */ true);

        callback.receivedApproval();
        assertWithMessage("Approved results for request in primary zone")
                .that(succeeded).isTrue();
        assertWithMessage("Approved request id in primary zone")
                .that(callback.mRequestId).isEqualTo(mMediaRequestId);
        assertWithMessage("Approved request occupant in primary zone")
                .that(callback.mOccupantZoneInfo).isEqualTo(info);
        assertWithMessage("Audio status in primary zone").that(callback.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_APPROVED);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#allowMediaAudioOnPrimaryZone(long, boolean)"})
    public void allowMediaAudioOnPrimaryZone_withReject() throws Exception {
        int passengerAudioZoneId = assumePassengerWithValidAudioZone();
        setupMediaAudioRequestCallback();
        OccupantZoneInfo info =
                mCarOccupantZoneManager.getOccupantForAudioZoneId(passengerAudioZoneId);
        TestMediaAudioRequestStatusCallback callback = requestToPlayMediaInPrimaryZone(info);
        mRequestCallback.receivedMediaRequest();

        boolean succeeded = mCarAudioManager.allowMediaAudioOnPrimaryZone(mMediaRequestId,
                /* allow= */ false);

        callback.receivedApproval();
        long tempRequestId = mMediaRequestId;
        mMediaRequestId = INVALID_REQUEST_ID;
        assertWithMessage("Unapproved results for request in primary zone")
                .that(succeeded).isTrue();
        assertWithMessage("Unapproved request id in primary zone")
                .that(callback.mRequestId).isEqualTo(tempRequestId);
        assertWithMessage("Unapproved audio status in primary zone").that(callback.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_REJECTED);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#allowMediaAudioOnPrimaryZone(long, boolean)"})
    public void allowMediaAudioOnPrimaryZone_withInvalidRequestId() throws Exception {
        assumePassengerWithValidAudioZone();
        setupMediaAudioRequestCallback();

        boolean succeeded = mCarAudioManager
                        .allowMediaAudioOnPrimaryZone(INVALID_REQUEST_ID, /* allow= */ true);

        assertWithMessage("Invalid request id allowed results")
                .that(succeeded).isFalse();
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#cancelMediaAudioOnPrimaryZone(long)"})
    public void cancelMediaAudioOnPrimaryZone() throws Exception {
        int passengerAudioZoneId = assumePassengerWithValidAudioZone();
        setupMediaAudioRequestCallback();
        OccupantZoneInfo info =
                mCarOccupantZoneManager.getOccupantForAudioZoneId(passengerAudioZoneId);
        TestMediaAudioRequestStatusCallback callback = requestToPlayMediaInPrimaryZone(info);
        mRequestCallback.receivedMediaRequest();

        mCarAudioManager.cancelMediaAudioOnPrimaryZone(mMediaRequestId);

        mMediaRequestId = INVALID_REQUEST_ID;
        callback.receivedApproval();
        assertWithMessage("Cancelled audio status in primary zone")
                .that(callback.mStatus).isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_CANCELLED);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#resetMediaAudioOnPrimaryZone(OccupantZoneInfo)"})
    public void resetMediaAudioOnPrimaryZone() throws Exception {
        int passengerAudioZoneId = assumePassengerWithValidAudioZone();
        setupMediaAudioRequestCallback();
        OccupantZoneInfo info =
                mCarOccupantZoneManager.getOccupantForAudioZoneId(passengerAudioZoneId);
        TestMediaAudioRequestStatusCallback callback = requestToPlayMediaInPrimaryZone(info);
        mRequestCallback.receivedMediaRequest();
        mCarAudioManager.allowMediaAudioOnPrimaryZone(mMediaRequestId, /* allow= */ true);
        callback.receivedApproval();
        callback.reset();

        mCarAudioManager.resetMediaAudioOnPrimaryZone(info);

        long tempRequestId = mMediaRequestId;
        mMediaRequestId = INVALID_REQUEST_ID;
        callback.receivedApproval();
        assertWithMessage("Reset request id in primary zone")
                .that(callback.mRequestId).isEqualTo(tempRequestId);
        assertWithMessage("Reset audio status in primary zone")
                .that(callback.mStatus).isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_STOPPED);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager#getCurrentAudioZoneConfigInfo(int)"})
    public void getCurrentAudioZoneConfigInfo() {
        assumeDynamicRoutingIsEnabled();
        List<TestZoneConfigInfo> zoneConfigs = assumeSecondaryZoneConfigs();

        CarAudioZoneConfigInfo currentZoneConfigInfo =
                mCarAudioManager.getCurrentAudioZoneConfigInfo(mZoneId);

        assertWithMessage("Current zone config info")
                .that(TestZoneConfigInfo.getZoneConfigFromInfo(currentZoneConfigInfo))
                .isIn(zoneConfigs);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager#getCurrentAudioZoneConfigInfo(int)"})
    public void getCurrentAudioZoneConfigInfo_withInvalidZoneId_fails() {
        assumeDynamicRoutingIsEnabled();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioManager.getCurrentAudioZoneConfigInfo(INVALID_AUDIO_ZONE));

        assertThat(exception).hasMessageThat().contains("Invalid audio zone Id");
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager#getAudioZoneConfigInfos(int)"})
    public void getAudioZoneConfigInfos_forPrimaryZone_onlyOneConfigExists() {
        assumeDynamicRoutingIsEnabled();
        TestZoneConfigInfo primaryZoneConfigFromDump = parsePrimaryZoneConfigs();

        List<CarAudioZoneConfigInfo> zoneConfigInfos =
                mCarAudioManager.getAudioZoneConfigInfos(PRIMARY_AUDIO_ZONE);

        assertWithMessage("Primary audio zone config")
                .that(TestZoneConfigInfo.getZoneConfigListFromInfoList(zoneConfigInfos))
                .containsExactly(primaryZoneConfigFromDump);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager#getAudioZoneConfigInfos(int)"})
    public void getAudioZoneConfigInfos_forSecondaryZone() {
        assumeDynamicRoutingIsEnabled();
        List<TestZoneConfigInfo> zoneConfigs = assumeSecondaryZoneConfigs();

        List<CarAudioZoneConfigInfo> zoneConfigInfosFromDump =
                mCarAudioManager.getAudioZoneConfigInfos(mZoneId);

        assertWithMessage("All zone config infos")
                .that(TestZoneConfigInfo.getZoneConfigListFromInfoList(zoneConfigInfosFromDump))
                .containsExactlyElementsIn(zoneConfigs);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager#getAudioZoneConfigInfos(int)"})
    public void getAudioZoneConfigInfos_withInvalidZoneId_fails() {
        assumeDynamicRoutingIsEnabled();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioManager.getAudioZoneConfigInfos(INVALID_AUDIO_ZONE));

        assertThat(exception).hasMessageThat().contains("Invalid audio zone Id");
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager#switchAudioZoneToConfig("
            + "CarAudioZoneConfigInfo, Executor, SwitchAudioZoneConfigCallback)"})
    public void switchAudioZoneToConfig() throws Exception {
        assumeDynamicRoutingIsEnabled();
        assumeSecondaryZoneConfigs();
        CarAudioZoneConfigInfo zoneConfigInfoSaved =
                mCarAudioManager.getCurrentAudioZoneConfigInfo(mZoneId);
        CarAudioZoneConfigInfo zoneConfigInfoSwitchedTo = getNonCurrentZoneConfig(mZoneId);
        Executor callbackExecutor = Executors.newFixedThreadPool(1);
        TestSwitchAudioZoneConfigCallback callback = new TestSwitchAudioZoneConfigCallback();

        mCarAudioManager.switchAudioZoneToConfig(zoneConfigInfoSwitchedTo, callbackExecutor,
                callback);

        callback.receivedApproval();
        assertWithMessage("Zone configuration switching status")
                .that(callback.mIsSuccessful).isTrue();
        assertWithMessage("Updated zone configuration")
                .that(callback.mZoneConfigInfo).isEqualTo(zoneConfigInfoSwitchedTo);
        callback.reset();
        mCarAudioManager.switchAudioZoneToConfig(zoneConfigInfoSaved, callbackExecutor, callback);
        callback.receivedApproval();
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager#switchAudioZoneToConfig("
            + "CarAudioZoneConfigInfo, Executor, SwitchAudioZoneConfigCallback)"})
    public void switchAudioZoneToConfig_withNullConfig_fails() throws Exception {
        assumeDynamicRoutingIsEnabled();
        Executor callbackExecutor = Executors.newFixedThreadPool(1);
        TestSwitchAudioZoneConfigCallback callback = new TestSwitchAudioZoneConfigCallback();

        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> mCarAudioManager.switchAudioZoneToConfig(/* zoneConfig= */ null,
                        callbackExecutor, callback));

        assertThat(exception).hasMessageThat().contains("Audio zone configuration can not be null");
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager#switchAudioZoneToConfig("
            + "CarAudioZoneConfigInfo, Executor, SwitchAudioZoneConfigCallback)"})
    public void switchAudioZoneToConfig_withNullExecutor_fails() throws Exception {
        assumeDynamicRoutingIsEnabled();
        assumeSecondaryZoneConfigs();
        CarAudioZoneConfigInfo zoneConfigInfoSwitchedTo = getNonCurrentZoneConfig(mZoneId);
        TestSwitchAudioZoneConfigCallback callback = new TestSwitchAudioZoneConfigCallback();

        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> mCarAudioManager.switchAudioZoneToConfig(zoneConfigInfoSwitchedTo,
                        /* executor= */ null, callback));

        assertThat(exception).hasMessageThat().contains("Executor can not be null");
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager#switchAudioZoneToConfig("
            + "CarAudioZoneConfigInfo, Executor, SwitchAudioZoneConfigCallback)"})
    public void switchAudioZoneToConfig_withNullCallback_fails() throws Exception {
        assumeDynamicRoutingIsEnabled();
        assumeSecondaryZoneConfigs();
        CarAudioZoneConfigInfo zoneConfigInfoSwitchedTo = getNonCurrentZoneConfig(mZoneId);
        Executor callbackExecutor = Executors.newFixedThreadPool(1);

        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> mCarAudioManager.switchAudioZoneToConfig(zoneConfigInfoSwitchedTo,
                        callbackExecutor, /* callback= */ null));

        assertThat(exception).hasMessageThat().contains("callback can not be null");
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#setAudioZoneMirrorStatusCallback(Executor, AudioZonesMirrorStatusCallback)"})
    public void setAudioZoneMirrorStatusCallback() {
        assumeAudioMirrorEnabled();
        assumePassengersForAudioMirror();
        Executor executor = Executors.newFixedThreadPool(1);
        TestAudioZonesMirrorStatusCallback callback;
        boolean registered = false;

        try {
            callback = new TestAudioZonesMirrorStatusCallback();
            registered = mCarAudioManager.setAudioZoneMirrorStatusCallback(executor, callback);
        } finally {
            if (registered) {
                mCarAudioManager.clearAudioZonesMirrorStatusCallback();
            }
        }

        assertWithMessage("Audio zone mirror status callback registered status")
                .that(registered).isTrue();
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager#enableMirrorForAudioZones(List)"})
    public void enableMirrorForAudioZones() throws Exception {
        assumeAudioMirrorEnabled();
        List<Integer> audioZones = assumePassengersForAudioMirror();
        setupAudioMirrorStatusCallback();

        mMirrorRequestId = mCarAudioManager.enableMirrorForAudioZones(audioZones);

        mAudioZonesMirrorCallback.waitForCallback();
        assertWithMessage("Enabled mirror for audio zone status")
                .that(mAudioZonesMirrorCallback.mStatus).isEqualTo(
                        CarAudioManager.AUDIO_REQUEST_STATUS_APPROVED);
        assertWithMessage("Enabled mirror audio zones")
                .that(mAudioZonesMirrorCallback.mAudioZones).containsExactlyElementsIn(audioZones);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager#enableMirrorForAudioZones(List)"})
    public void enableMirrorForAudioZones_withNullList() throws Exception {
        assumeAudioMirrorEnabled();
        setupAudioMirrorStatusCallback();

        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                mMirrorRequestId = mCarAudioManager
                        .enableMirrorForAudioZones(/* audioZonesToMirror= */ null));

        assertWithMessage("Null enable mirror audio zones exception")
                .that(thrown).hasMessageThat().contains("Audio zones to mirror");
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager#disableAudioMirrorForZone(int)"})
    public void disableAudioMirrorForZone() throws Exception {
        assumeAudioMirrorEnabled();
        List<Integer> audioZones = assumePassengersForAudioMirror();
        int zoneToDisable = audioZones.get(0);
        setupAudioMirrorStatusCallback();
        mMirrorRequestId = mCarAudioManager.enableMirrorForAudioZones(audioZones);
        mAudioZonesMirrorCallback.waitForCallback();
        mAudioZonesMirrorCallback.reset();

        mCarAudioManager.disableAudioMirrorForZone(zoneToDisable);

        mMirrorRequestId = INVALID_REQUEST_ID;
        mAudioZonesMirrorCallback.waitForCallback();
        assertWithMessage("Disable mirror status for audio zone %s", zoneToDisable)
                .that(mAudioZonesMirrorCallback.mStatus).isEqualTo(
                        CarAudioManager.AUDIO_REQUEST_STATUS_STOPPED);
        assertWithMessage("Disable mirror zones for audio zone %s", zoneToDisable)
                .that(mAudioZonesMirrorCallback.mAudioZones).containsExactlyElementsIn(audioZones);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager#disableAudioMirror(long)"})
    public void disableAudioMirror() throws Exception {
        assumeAudioMirrorEnabled();
        List<Integer> audioZones = assumePassengersForAudioMirror();
        setupAudioMirrorStatusCallback();
        mMirrorRequestId = mCarAudioManager.enableMirrorForAudioZones(audioZones);
        mAudioZonesMirrorCallback.waitForCallback();
        mAudioZonesMirrorCallback.reset();

        mCarAudioManager.disableAudioMirror(mMirrorRequestId);

        mMirrorRequestId = INVALID_REQUEST_ID;
        mAudioZonesMirrorCallback.waitForCallback();
        assertWithMessage("Disable mirror status for audio zones")
                .that(mAudioZonesMirrorCallback.mStatus).isEqualTo(
                        CarAudioManager.AUDIO_REQUEST_STATUS_STOPPED);
        assertWithMessage("Disable mirror zones for audio zones")
                .that(mAudioZonesMirrorCallback.mAudioZones).containsExactlyElementsIn(audioZones);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @ApiTest(apis = {"android.car.media.CarAudioManager#getMirrorAudioZonesForAudioZone(int)"})
    public void getMirrorAudioZonesForAudioZone() throws Exception {
        assumeAudioMirrorEnabled();
        List<Integer> audioZones = assumePassengersForAudioMirror();
        int zoneToQuery = audioZones.get(0);
        setupAudioMirrorStatusCallback();
        mMirrorRequestId = mCarAudioManager.enableMirrorForAudioZones(audioZones);
        mAudioZonesMirrorCallback.waitForCallback();

        List<Integer> queriedZones = mCarAudioManager.getMirrorAudioZonesForAudioZone(zoneToQuery);

        mAudioZonesMirrorCallback.waitForCallback();
        assertWithMessage("Queried audio zones").that(queriedZones)
                .containsExactlyElementsIn(audioZones);
    }

    private TestMediaAudioRequestStatusCallback requestToPlayMediaInPrimaryZone(
            OccupantZoneInfo info) {
        Executor callbackExecutor = Executors.newFixedThreadPool(1);
        TestMediaAudioRequestStatusCallback callback = new TestMediaAudioRequestStatusCallback();
        mMediaRequestId =
                mCarAudioManager.requestMediaAudioOnPrimaryZone(info, callbackExecutor, callback);
        return callback;
    }

    private void setupMediaAudioRequestCallback() {
        Executor requestExecutor = Executors.newFixedThreadPool(1);
        mRequestCallback = new TestPrimaryZoneMediaAudioRequestStatusCallback();
        mCarAudioManager.setPrimaryZoneMediaAudioRequestCallback(requestExecutor, mRequestCallback);
    }

    private void setupAudioMirrorStatusCallback() {
        Executor executor = Executors.newFixedThreadPool(1);
        mAudioZonesMirrorCallback = new TestAudioZonesMirrorStatusCallback();
        mCarAudioManager.setAudioZoneMirrorStatusCallback(executor, mAudioZonesMirrorCallback);
    }

    private int assumePassengerWithValidAudioZone() {
        List<Integer> audioZonesWithPassengers = assumePassengersWithValidAudioZones(
                /* count= */ 1, "Audio share to primary zone");

        return audioZonesWithPassengers.get(0);
    }

    private List<Integer> assumePassengersWithValidAudioZones(int minimumCount, String message) {
        List<Integer> audioZonesWithPassengers = getAvailablePassengerAudioZone();
        assumeTrue(message + ": Need at least " + minimumCount
                        + " passenger(s) with valid audio zone id",
                audioZonesWithPassengers.size() >= minimumCount);

        return audioZonesWithPassengers;
    }

    private List<Integer> assumePassengersForAudioMirror() {
        List<Integer> audioZonesWithPassengers = assumePassengersWithValidAudioZones(/* count= */ 2,
                "Passenger audio mirror");

        return audioZonesWithPassengers.subList(/* fromIndex= */ 0, /* toIndex= */ 2);
    }

    private List<Integer> getAvailablePassengerAudioZone() {
        return mCarOccupantZoneManager.getAllOccupantZones().stream()
                .filter(occupant -> mCarOccupantZoneManager.getUserForOccupant(occupant)
                        != CarOccupantZoneManager.INVALID_USER_ID)
                .map(occupant -> mCarOccupantZoneManager.getAudioZoneIdForOccupant(occupant))
                .filter(audioZoneId -> audioZoneId != INVALID_AUDIO_ZONE
                        && audioZoneId != PRIMARY_AUDIO_ZONE)
                .collect(Collectors.toList());
    }

    private List<TestZoneConfigInfo> assumeSecondaryZoneConfigs() {
        List<Integer> audioZonesWithPassengers = getAvailablePassengerAudioZone();
        assumeFalse("Requires a zone with a passenger/user", audioZonesWithPassengers.isEmpty());
        SparseArray<List<TestZoneConfigInfo>> zoneConfigs = parseAudioZoneConfigs();
        List<TestZoneConfigInfo> secondaryZoneConfigs = null;
        for (int index = 0; index < audioZonesWithPassengers.size(); index++) {
            int zoneId = audioZonesWithPassengers.get(index);
            if (zoneConfigs.contains(zoneId) && zoneConfigs.get(zoneId).size() > 1) {
                mZoneId = zoneId;
                secondaryZoneConfigs = zoneConfigs.get(zoneId);
                break;
            }
        }
        assumeTrue("Secondary zones requires multiple zone configurations",
                secondaryZoneConfigs != null);
        return secondaryZoneConfigs;
    }

    private TestZoneConfigInfo parsePrimaryZoneConfigs() {
        SparseArray<List<TestZoneConfigInfo>> zoneConfigs = parseAudioZoneConfigs();
        List<TestZoneConfigInfo> primaryZoneConfigs = zoneConfigs.get(PRIMARY_AUDIO_ZONE);
        assertWithMessage("Dumped primary audio zone configuration")
                .that(primaryZoneConfigs).isNotNull();
        return primaryZoneConfigs.get(0);
    }

    private SparseArray<List<TestZoneConfigInfo>> parseAudioZoneConfigs() {
        SparseArray<List<TestZoneConfigInfo>> zoneConfigs = new SparseArray<>();
        Matcher zoneConfigMatcher = ZONE_CONFIG_PATTERN.matcher(mCarAudioServiceDump);
        while (zoneConfigMatcher.find()) {
            int zoneId = Integer.parseInt(zoneConfigMatcher.group(3));
            int zoneConfigId = Integer.parseInt(zoneConfigMatcher.group(2));
            String configName = zoneConfigMatcher.group(1);
            if (!zoneConfigs.contains(zoneId)) {
                zoneConfigs.put(zoneId, new ArrayList<>());
            }
            zoneConfigs.get(zoneId).add(new TestZoneConfigInfo(zoneId, zoneConfigId, configName));
        }
        return zoneConfigs;
    }

    private CarAudioZoneConfigInfo getNonCurrentZoneConfig(int zoneId) {
        List<CarAudioZoneConfigInfo> zoneConfigInfos =
                mCarAudioManager.getAudioZoneConfigInfos(zoneId);
        CarAudioZoneConfigInfo currentZoneConfigInfo =
                mCarAudioManager.getCurrentAudioZoneConfigInfo(zoneId);

        CarAudioZoneConfigInfo differentZoneConfig = null;
        for (int index = 0; index < zoneConfigInfos.size(); index++) {
            if (!currentZoneConfigInfo.equals(zoneConfigInfos.get(index))) {
                differentZoneConfig = zoneConfigInfos.get(index);
                break;
            }
        }

        return differentZoneConfig;
    }

    @Test
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#registerCarVolumeGroupEventCallback(Executor, CarVolumeGroupEventCallback)"})
    public void registerCarVolumeGroupEventCallback_nullCallback_throwsNPE() {
        Executor executor = Executors.newFixedThreadPool(1);

        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> mCarAudioManager.registerCarVolumeGroupEventCallback(executor,
                        /* callback= */ null));

        assertWithMessage("Register car volume group event with null callback exception")
                .that(exception).hasMessageThat()
                .contains("Car volume event callback can not be null");
    }

    @Test
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#registerCarVolumeGroupEventCallback(Executor, CarVolumeGroupEventCallback)"})
    public void registerCarVolumeGroupEventCallback_nullExecutor_throwsNPE() {
        mEventCallback = new TestCarVolumeGroupEventCallback();

        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> mCarAudioManager.registerCarVolumeGroupEventCallback(/* executor= */ null,
                        mEventCallback));

        mEventCallback = null;
        assertWithMessage("Register car volume group event with null executor exception")
                .that(exception).hasMessageThat().contains("Executor can not be null");
    }

    @Test
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#registerCarVolumeGroupEventCallback(Executor, CarVolumeGroupEventCallback)"})
    public void registerCarVolumeGroupEventCallback_nonNullInputs_throwsPermissionError() {
        Executor executor = Executors.newFixedThreadPool(1);
        mEventCallback = new TestCarVolumeGroupEventCallback();

        Exception exception = assertThrows(SecurityException.class,
                () -> mCarAudioManager.registerCarVolumeGroupEventCallback(executor,
                        mEventCallback));

        mEventCallback = null;
        assertWithMessage("Register car volume group event callback without permission exception")
                .that(exception).hasMessageThat().contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#registerCarVolumeGroupEventCallback(Executor, CarVolumeGroupEventCallback)"})
    public void registerCarVolumeGroupEventCallback_volumeGroupEventsDisabled() throws Exception {
        assumeDynamicRoutingIsEnabled();
        assumeVolumeGroupEventsIsDisabled();
        Executor executor = Executors.newFixedThreadPool(1);
        mEventCallback = new TestCarVolumeGroupEventCallback();

        Exception exception = assertThrows(IllegalStateException.class,
                () -> mCarAudioManager.registerCarVolumeGroupEventCallback(executor,
                        mEventCallback));

        mEventCallback = null;
        assertWithMessage("Register car volume group event with feature disabled")
                .that(exception).hasMessageThat().contains("Car Volume Group Event is required");
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @ApiTest(apis = {
            "android.car.media.CarAudioManager#registerCarVolumeGroupEventCallback(Executor, "
                    + "CarVolumeGroupEventCallback)"})
    public void registerCarVolumeGroupEventCallback_onVolumeGroupEvent() throws Exception {
        assumeDynamicRoutingIsEnabled();
        assumeVolumeGroupEventsIsEnabled();
        Executor executor = Executors.newFixedThreadPool(1);
        mEventCallback = new TestCarVolumeGroupEventCallback();

        boolean status = mCarAudioManager.registerCarVolumeGroupEventCallback(executor,
                mEventCallback);

        injectVolumeUpKeyEvent();
        assertWithMessage("Car volume group event callback")
                .that(mEventCallback.receivedVolumeGroupEvents()).isTrue();
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#registerCarVolumeGroupEventCallback(Executor, CarVolumeGroupEventCallback)"})
    public void registerCarVolumeGroupEventCallback_registerCarVolumeCallback_onVolumeGroupEvent()
            throws Exception {
        assumeDynamicRoutingIsEnabled();
        assumeVolumeGroupEventsIsEnabled();
        Executor executor = Executors.newFixedThreadPool(1);
        mEventCallback = new TestCarVolumeGroupEventCallback();
        mCallback = new SyncCarVolumeCallback();
        mCarAudioManager.registerCarVolumeGroupEventCallback(executor, mEventCallback);

        mCarAudioManager.registerCarVolumeCallback(mCallback);

        injectVolumeDownKeyEvent();
        assertWithMessage("Car volume group event for registered callback")
            .that(mEventCallback.receivedVolumeGroupEvents()).isTrue();
        assertWithMessage("Car group volume changed for deprioritized callback")
            .that(mCallback.receivedGroupVolumeChanged()).isFalse();
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#registerCarVolumeGroupEventCallback(Executor, CarVolumeGroupEventCallback)",
            "android.car.media.CarAudioManager"
                    + "#unregisterCarVolumeGroupEventCallback(CarVolumeGroupEventCallback)"})
    public void unregisterCarVolumeGroupEventCallback_onGroupVolumeChanged()
            throws Exception {
        assumeDynamicRoutingIsEnabled();
        assumeVolumeGroupEventsIsEnabled();
        Executor executor = Executors.newFixedThreadPool(1);
        mEventCallback = new TestCarVolumeGroupEventCallback();
        mCallback = new SyncCarVolumeCallback();
        mCarAudioManager.registerCarVolumeGroupEventCallback(executor, mEventCallback);
        mCarAudioManager.registerCarVolumeCallback(mCallback);

        mCarAudioManager.unregisterCarVolumeGroupEventCallback(mEventCallback);

        injectVolumeUpKeyEvent();
        assertWithMessage("Car volume group event for unregistered callback")
            .that(mEventCallback.receivedVolumeGroupEvents()).isFalse();
        assertWithMessage("Car group volume changed for reprioritized callback")
            .that(mCallback.receivedGroupVolumeChanged()).isTrue();
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#registerCarVolumeGroupEventCallback(Executor, CarVolumeGroupEventCallback)",
            "android.car.media.CarAudioManager"
                    + "#unregisterCarVolumeGroupEventCallback(CarVolumeGroupEventCallback)"})
    public void reRegisterCarVolumeGroupEventCallback_eventCallbackReprioritized()
            throws Exception {
        assumeDynamicRoutingIsEnabled();
        assumeVolumeGroupEventsIsEnabled();
        Executor executor = Executors.newFixedThreadPool(1);
        mEventCallback = new TestCarVolumeGroupEventCallback();
        mCallback = new SyncCarVolumeCallback();
        mCarAudioManager.registerCarVolumeGroupEventCallback(executor, mEventCallback);
        mCarAudioManager.registerCarVolumeCallback(mCallback);
        mCarAudioManager.unregisterCarVolumeGroupEventCallback(mEventCallback);

        mCarAudioManager.registerCarVolumeGroupEventCallback(executor, mEventCallback);

        injectVolumeDownKeyEvent();
        assertWithMessage("Car volume group event for re-registered callback")
            .that(mEventCallback.receivedVolumeGroupEvents()).isTrue();
        assertWithMessage("Car group volume changed for deprioritized callback")
            .that(mCallback.receivedGroupVolumeChanged()).isFalse();
    }

    @Test
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#unregisterCarVolumeGroupEventCallback(CarVolumeGroupEventCallback)"})
    public void unregisterCarVolumeGroupEventCallback_nullCallback_throwsNPE() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> mCarAudioManager.unregisterCarVolumeGroupEventCallback(/* callback= */ null));

        assertWithMessage("Unregister car volume group event with null callback exception")
                .that(exception).hasMessageThat()
                .contains("Car volume event callback can not be null");
    }

    @Test
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#unregisterCarVolumeGroupEventCallback(CarVolumeGroupEventCallback)"})
    public void unregisterCarVolumeGroupEventCallback_withoutPermission_throws()
            throws Exception {
        assumeDynamicRoutingIsEnabled();
        assumeVolumeGroupEventsIsEnabled();
        Executor executor = Executors.newFixedThreadPool(1);
        mEventCallback = new TestCarVolumeGroupEventCallback();
        runWithCarControlAudioVolumePermission(
                () -> mCarAudioManager.registerCarVolumeGroupEventCallback(executor,
                        mEventCallback));

        Exception exception = assertThrows(SecurityException.class,
                () -> mCarAudioManager.unregisterCarVolumeGroupEventCallback(mEventCallback));

        assertWithMessage("Unregister car volume group event callback without permission exception")
                .that(exception).hasMessageThat().contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

    }

    @Test
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#unregisterCarVolumeGroupEventCallback(CarVolumeGroupEventCallback)"})
    public void unregisterCarVolumeGroupEventCallback_withoutPermission_receivesCallback()
            throws Exception {
        assumeDynamicRoutingIsEnabled();
        assumeVolumeGroupEventsIsEnabled();
        Executor executor = Executors.newFixedThreadPool(1);
        mEventCallback = new TestCarVolumeGroupEventCallback();
        runWithCarControlAudioVolumePermission(
                () -> mCarAudioManager.registerCarVolumeGroupEventCallback(executor,
                        mEventCallback));

        Exception exception = assertThrows(SecurityException.class,
                () -> mCarAudioManager.unregisterCarVolumeGroupEventCallback(mEventCallback));

        injectVolumeDownKeyEvent();
        assertWithMessage("Car volume group event after unregister security exception")
                .that(mEventCallback.receivedVolumeGroupEvents()).isTrue();
    }

    @Test
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @ApiTest(apis = {"android.car.media.CarAudioManager"
            + "#registerCarVolumeGroupEventCallback(Executor, CarVolumeGroupEventCallback)",
            "android.car.media.CarAudioManager"
                    + "#unregisterCarVolumeGroupEventCallback(CarVolumeGroupEventCallback)"})
    public void unregisterCarVolumeGroupEventCallback_noLongerReceivesEventCallback()
            throws Exception {
        assumeDynamicRoutingIsEnabled();
        assumeVolumeGroupEventsIsEnabled();
        Executor executor = Executors.newFixedThreadPool(1);
        mEventCallback = new TestCarVolumeGroupEventCallback();
        mCarAudioManager.registerCarVolumeGroupEventCallback(executor, mEventCallback);

        mCarAudioManager.unregisterCarVolumeGroupEventCallback(mEventCallback);

        injectVolumeUpKeyEvent();
        assertWithMessage("Car volume group event for unregistered callback")
            .that(mEventCallback.receivedVolumeGroupEvents()).isFalse();
    }

    private int getNumberOfPrimaryZoneAudioMediaCallbacks() {
        Matcher matchCount = PRIMARY_ZONE_MEDIA_REQUEST_APPROVERS_PATTERN
                .matcher(mCarAudioServiceDump);
        assertWithMessage("No Car Audio Media in dump").that(matchCount.find()).isTrue();
        return Integer.parseInt(matchCount.group(1));
    }

    private void assumeNoPrimaryZoneAudioMediaApprovers() {
        assumeTrue("Primary zone audio media approvers must be empty",
                getNumberOfPrimaryZoneAudioMediaCallbacks() == 0);
    }

    private void assumeDynamicRoutingIsEnabled() {
        assumeTrue("Requires dynamic audio routing",
                mCarAudioManager.isAudioFeatureEnabled(AUDIO_FEATURE_DYNAMIC_ROUTING));
    }

    private void assumeVolumeGroupMutingIsEnabled() {
        assumeTrue("Requires volume group muting",
                mCarAudioManager.isAudioFeatureEnabled(AUDIO_FEATURE_VOLUME_GROUP_MUTING));
    }

    private void assumeVolumeGroupMutingIsDisabled() {
        assumeFalse("Requires volume group muting disabled",
                mCarAudioManager.isAudioFeatureEnabled(AUDIO_FEATURE_VOLUME_GROUP_MUTING));
    }

    private void assumeAudioMirrorEnabled() {
        assumeTrue("Requires audio mirroring",
                mCarAudioManager.isAudioFeatureEnabled(AUDIO_FEATURE_AUDIO_MIRRORING));
    }

    private void assumeVolumeGroupEventsIsEnabled() {
        assumeTrue(mCarAudioManager.isAudioFeatureEnabled(AUDIO_FEATURE_VOLUME_GROUP_EVENTS));
    }

    private void assumeVolumeGroupEventsIsDisabled() {
        assumeFalse(mCarAudioManager.isAudioFeatureEnabled(AUDIO_FEATURE_VOLUME_GROUP_EVENTS));
    }

    private void runWithCarControlAudioVolumePermission(Runnable runnable) {
        UI_AUTOMATION.adoptShellPermissionIdentity(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        try {
            runnable.run();
        } finally {
            UI_AUTOMATION.dropShellPermissionIdentity();
        }
    }

    private void injectKeyEvent(int keyCode) {
        long downTime = SystemClock.uptimeMillis();
        KeyEvent volumeDown = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN,
            keyCode, 0);
        UI_AUTOMATION.injectInputEvent(volumeDown, true);
    }

    private void injectVolumeDownKeyEvent() {
        injectKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN);
    }

    private void injectVolumeUpKeyEvent() {
        injectKeyEvent(KeyEvent.KEYCODE_VOLUME_UP);
    }

    private void injectVolumeMuteKeyEvent() {
        injectKeyEvent(KeyEvent.KEYCODE_VOLUME_MUTE);
    }

    private static final class TestZoneConfigInfo {
        private final int mZoneId;
        private final int mConfigId;
        private final String mConfigName;

        TestZoneConfigInfo(int zoneId, int configId, String configName) {
            mZoneId = zoneId;
            mConfigId = configId;
            mConfigName = configName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof TestZoneConfigInfo)) {
                return false;
            }

            TestZoneConfigInfo that = (TestZoneConfigInfo) o;

            return mZoneId == that.mZoneId && mConfigId == that.mConfigId
                    && mConfigName.equals(that.mConfigName);
        }

        static TestZoneConfigInfo getZoneConfigFromInfo(CarAudioZoneConfigInfo configInfo) {
            return new TestZoneConfigInfo(configInfo.getZoneId(), configInfo.getConfigId(),
                    configInfo.getName());
        }

        static List<TestZoneConfigInfo> getZoneConfigListFromInfoList(
                List<CarAudioZoneConfigInfo> configInfo) {
            List<TestZoneConfigInfo> zoneConfigs = new ArrayList<>(configInfo.size());
            Log.e(TAG, "getZoneConfigListFromInfoList " + configInfo.size());
            for (int index = 0; index < configInfo.size(); index++) {
                zoneConfigs.add(getZoneConfigFromInfo(configInfo.get(index)));
            }
            return zoneConfigs;
        }
    }

    private static final class SyncCarVolumeCallback extends CarVolumeCallback {
        private final CountDownLatch mGroupVolumeChangeLatch = new CountDownLatch(1);
        private final CountDownLatch mGroupMuteChangeLatch = new CountDownLatch(1);
        private final CountDownLatch mMasterMuteChangeLatch = new CountDownLatch(1);

        public int zoneId;
        public int groupId;

        boolean receivedGroupVolumeChanged() {
            return silentAwait(mGroupVolumeChangeLatch, WAIT_TIMEOUT_MS);
        }

        boolean receivedGroupMuteChanged() {
            return silentAwait(mGroupMuteChangeLatch, WAIT_TIMEOUT_MS);
        }

        boolean receivedMasterMuteChanged() {
            return silentAwait(mMasterMuteChangeLatch, WAIT_TIMEOUT_MS);
        }

        @Override
        public void onGroupVolumeChanged(int zoneId, int groupId, int flags) {
            Log.v(TAG, "onGroupVolumeChanged");
            mGroupVolumeChangeLatch.countDown();
        }

        @Override
        public void onMasterMuteChanged(int zoneId, int flags) {
            Log.v(TAG, "onMasterMuteChanged");
            mMasterMuteChangeLatch.countDown();
        }

        @Override
        public void onGroupMuteChanged(int zoneId, int groupId, int flags) {
            Log.v(TAG, "onGroupMuteChanged");
            this.zoneId = zoneId;
            this.groupId = groupId;
            mGroupMuteChangeLatch.countDown();
        }
    }

    private static final class TestPrimaryZoneMediaAudioRequestStatusCallback implements
            PrimaryZoneMediaAudioRequestCallback {

        private final CountDownLatch mRequestAudioLatch = new CountDownLatch(1);
        private long mRequestId;
        private OccupantZoneInfo mOccupantZoneInfo;

        @Override
        public void onRequestMediaOnPrimaryZone(OccupantZoneInfo info, long requestId) {
            mOccupantZoneInfo = info;
            mRequestId = requestId;
            Log.i(TAG, "onRequestMediaOnPrimaryZone info " + info + " request id " + requestId);
            mRequestAudioLatch.countDown();
        }

        @Override
        public void onMediaAudioRequestStatusChanged(OccupantZoneInfo info,
                long requestId, int status) {
        }

        void receivedMediaRequest() throws InterruptedException {
            await(mRequestAudioLatch, WAIT_TIMEOUT_MS);
        }
    }

    private static final class TestMediaAudioRequestStatusCallback implements
            MediaAudioRequestStatusCallback {

        private CountDownLatch mRequestAudioLatch = new CountDownLatch(1);
        private long mRequestId;
        private OccupantZoneInfo mOccupantZoneInfo;
        private int mStatus;

        void receivedApproval() throws InterruptedException {
            await(mRequestAudioLatch, WAIT_TIMEOUT_MS);
        }

        void reset() {
            mRequestAudioLatch = new CountDownLatch(1);
            mRequestId = INVALID_REQUEST_ID;
            mOccupantZoneInfo = null;
            mStatus = 0;
        }

        @Override
        public void onMediaAudioRequestStatusChanged(OccupantZoneInfo info,
                long requestId, int status) {
            mOccupantZoneInfo = info;
            mRequestId = requestId;
            mStatus = status;
            Log.i(TAG, "onMediaAudioRequestStatusChanged info " + info + " request id "
                    + requestId + " status " + status);
            mRequestAudioLatch.countDown();
        }
    }

    private static final class TestSwitchAudioZoneConfigCallback implements
            SwitchAudioZoneConfigCallback {

        private CountDownLatch mRequestAudioLatch = new CountDownLatch(1);
        private CarAudioZoneConfigInfo mZoneConfigInfo;
        private boolean mIsSuccessful;

        void receivedApproval() throws InterruptedException {
            await(mRequestAudioLatch, WAIT_TIMEOUT_MS);
        }

        void reset() {
            mRequestAudioLatch = new CountDownLatch(1);
            mZoneConfigInfo = null;
            mIsSuccessful = false;
        }

        @Override
        public void onAudioZoneConfigSwitched(CarAudioZoneConfigInfo zoneConfigInfo,
                boolean isSuccessful) {
            mZoneConfigInfo = zoneConfigInfo;
            mIsSuccessful = isSuccessful;
            Log.i(TAG, "onAudioZoneConfigSwitched zoneConfig " + zoneConfigInfo + " is successful? "
                    + isSuccessful);
            mRequestAudioLatch.countDown();
        }
    }

    private static final class TestAudioZonesMirrorStatusCallback implements
            AudioZonesMirrorStatusCallback {

        private CountDownLatch mRequestAudioLatch = new CountDownLatch(1);
        public List<Integer> mAudioZones;
        public int mStatus;

        @Override
        public void onAudioZonesMirrorStatusChanged(List<Integer> mirroredAudioZones, int status) {
            mAudioZones = mirroredAudioZones;
            mStatus = status;
            Log.i(TAG, "onAudioZonesMirrorStatusChanged: audio zones " + mirroredAudioZones
                    + " status " + status);
            mRequestAudioLatch.countDown();
        }

        private void waitForCallback() throws InterruptedException {
            await(mRequestAudioLatch, WAIT_TIMEOUT_MS);
        }

        public void reset() {
            mRequestAudioLatch = new CountDownLatch(1);
        }
    }

    private static final class TestCarVolumeGroupEventCallback implements
            CarVolumeGroupEventCallback {
        private CountDownLatch mVolumeGroupEventLatch = new CountDownLatch(1);
        List<CarVolumeGroupEvent> mEvents;

        boolean receivedVolumeGroupEvents() throws InterruptedException {
            return silentAwait(mVolumeGroupEventLatch, WAIT_TIMEOUT_MS);
        }

        @Override
        public void onVolumeGroupEvent(List<CarVolumeGroupEvent> volumeGroupEvents) {
            mEvents = volumeGroupEvents;
            Log.v(TAG, "onVolumeGroupEvent events " + volumeGroupEvents);
            mVolumeGroupEventLatch.countDown();
        }
    }
}
