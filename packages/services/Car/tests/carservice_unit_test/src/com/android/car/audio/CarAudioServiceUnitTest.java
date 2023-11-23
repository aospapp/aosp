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

package com.android.car.audio;

import static android.car.Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS;
import static android.car.media.CarAudioManager.INVALID_AUDIO_ZONE;
import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;
import static android.car.test.mocks.AndroidMockitoHelper.mockContextCheckCallingOrSelfPermission;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC;
import static android.media.AudioDeviceInfo.TYPE_FM_TUNER;
import static android.media.AudioManager.AUDIOFOCUS_GAIN;
import static android.media.AudioManager.AUDIOFOCUS_LOSS;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
import static android.media.AudioManager.SUCCESS;
import static android.os.Build.VERSION.SDK_INT;

import static com.android.car.R.bool.audioPersistMasterMuteState;
import static com.android.car.R.bool.audioUseCarVolumeGroupMuting;
import static com.android.car.R.bool.audioUseDynamicRouting;
import static com.android.car.R.bool.audioUseHalDuckingSignals;
import static com.android.car.R.integer.audioVolumeAdjustmentContextsVersion;
import static com.android.car.R.integer.audioVolumeKeyEventTimeoutMs;
import static com.android.car.audio.GainBuilder.DEFAULT_GAIN;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.builtin.media.AudioManagerHelper;
import android.car.builtin.media.AudioManagerHelper.AudioPatchInfo;
import android.car.media.CarAudioPatchHandle;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.automotive.audiocontrol.IAudioControl;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusInfo;
import android.media.AudioGain;
import android.media.AudioManager;
import android.media.IAudioService;
import android.media.audiopolicy.AudioPolicy;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

import com.android.car.CarLocalServices;
import com.android.car.CarOccupantZoneService;
import com.android.car.R;
import com.android.car.audio.hal.AudioControlFactory;
import com.android.car.audio.hal.AudioControlWrapperAidl;
import com.android.car.test.utils.TemporaryFile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.InputStream;

public final class CarAudioServiceUnitTest extends AbstractExtendedMockitoTestCase {
    private static final String TAG = CarAudioServiceUnitTest.class.getSimpleName();
    private static final int VOLUME_KEY_EVENT_TIMEOUT_MS = 3000;
    private static final int AUDIO_CONTEXT_PRIORITY_LIST_VERSION_ONE = 1;
    private static final int AUDIO_CONTEXT_PRIORITY_LIST_VERSION_TWO = 2;
    private static final String MEDIA_TEST_DEVICE = "media_bus_device";
    private static final String NAVIGATION_TEST_DEVICE = "navigation_bus_device";
    private static final String CALL_TEST_DEVICE = "call_bus_device";
    private static final String NOTIFICATION_TEST_DEVICE = "notification_bus_device";
    private static final String VOICE_TEST_DEVICE = "voice_bus_device";
    private static final String RING_TEST_DEVICE = "ring_bus_device";
    private static final String ALARM_TEST_DEVICE = "alarm_bus_device";
    private static final String SYSTEM_BUS_DEVICE = "system_bus_device";
    private static final String SECONDARY_TEST_DEVICE = "secondary_zone_bus";
    private static final String PRIMARY_ZONE_MICROPHONE_ADDRESS = "Built-In Mic";
    private static final String PRIMARY_ZONE_FM_TUNER_ADDRESS = "FM Tuner";
    // From the car audio configuration file in /res/raw/car_audio_configuration.xml
    private static final int SECONDARY_ZONE_ID = 1;
    private static final int OUT_OF_RANGE_ZONE = SECONDARY_ZONE_ID + 1;
    private static final int PRIMARY_ZONE_VOLUME_GROUP_COUNT = 4;
    private static final int SECONDARY_ZONE_VOLUME_GROUP_COUNT = 1;

    private static final String PROPERTY_RO_ENABLE_AUDIO_PATCH =
            "ro.android.car.audio.enableaudiopatch";

    private static final int MEDIA_APP_UID = 1086753;
    private static final String MEDIA_CLIENT_ID = "media-client-id";
    private static final String MEDIA_PACKAGE_NAME = "com.android.car.audio";
    private static final int MEDIA_EMPTY_FLAG = 0;
    private static final String REGISTRATION_ID = "meh";

    private CarAudioService mCarAudioService;
    @Mock
    private Context mMockContext;
    @Mock
    private TelephonyManager mMockTelephonyManager;
    @Mock
    private AudioManager mAudioManager;
    @Mock
    private Resources mMockResources;
    @Mock
    private ContentResolver mMockContentResolver;
    @Mock
    IBinder mBinder;
    @Mock
    IAudioControl mAudioControl;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private CarOccupantZoneService mMockOccupantZoneService;
    @Mock
    private IAudioService mMockAudioService;

    private boolean mPersistMasterMute = true;
    private boolean mUseDynamicRouting = true;
    private boolean mUseHalAudioDucking = true;
    private boolean mUserCarVolumeGroupMuting = true;

    private TemporaryFile mTemporaryAudioConfigurationFile;
    private TemporaryFile mTemporaryAudioConfigurationWithoutZoneMappingFile;
    private Context mContext;
    private AudioControlWrapperAidl mAudioControlWrapperAidl;
    private AudioDeviceInfo mTunerDevice;
    private AudioDeviceInfo mMediaOutputDevice;

    public CarAudioServiceUnitTest() {
        super(CarAudioService.TAG);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session
                .spyStatic(AudioManagerHelper.class)
                .spyStatic(AudioControlWrapperAidl.class)
                .spyStatic(CarLocalServices.class)
                .spyStatic(AudioControlFactory.class)
                .spyStatic(SystemProperties.class)
                .spyStatic(ServiceManager.class);
    }

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();

        try (InputStream configurationStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration)) {
            mTemporaryAudioConfigurationFile = new TemporaryFile("xml");
            mTemporaryAudioConfigurationFile.write(new String(configurationStream.readAllBytes()));
            Log.i(TAG, "Temporary Car Audio Configuration File Location: "
                    + mTemporaryAudioConfigurationFile.getPath());
        }

        try (InputStream configurationStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_without_zone_mapping)) {
            mTemporaryAudioConfigurationWithoutZoneMappingFile = new TemporaryFile("xml");
            mTemporaryAudioConfigurationWithoutZoneMappingFile
                    .write(new String(configurationStream.readAllBytes()));
            Log.i(TAG, "Temporary Car Audio Configuration without Zone mapping File Location: "
                    + mTemporaryAudioConfigurationWithoutZoneMappingFile.getPath());
        }

        mockGrantCarControlAudioSettingsPermission();

        setupAudioControlHAL();
        setupService();

    }

    @After
    public void tearDown() throws Exception {
        mTemporaryAudioConfigurationFile.close();
        mTemporaryAudioConfigurationWithoutZoneMappingFile.close();
    }

    private void setupAudioControlHAL() {
        when(mBinder.queryLocalInterface(anyString())).thenReturn(mAudioControl);
        doReturn(mBinder).when(AudioControlWrapperAidl::getService);
        mAudioControlWrapperAidl = new AudioControlWrapperAidl(mBinder);
        doReturn(mAudioControlWrapperAidl)
                .when(() -> AudioControlFactory.newAudioControl());
    }

    private void setupService() throws Exception {
        when(mMockContext.getSystemService(Context.TELECOM_SERVICE))
                .thenReturn(mMockTelephonyManager);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        doReturn(true)
                .when(() -> AudioManagerHelper
                        .setAudioDeviceGain(any(), any(), anyInt(), anyBoolean()));
        doReturn(mMockOccupantZoneService)
                .when(() ->  CarLocalServices.getService(CarOccupantZoneService.class));
        doReturn(true)
                .when(() -> SystemProperties.getBoolean(PROPERTY_RO_ENABLE_AUDIO_PATCH, false));

        setupAudioManager();

        setupResources();

        mCarAudioService =
                new CarAudioService(mMockContext,
                        mTemporaryAudioConfigurationFile.getFile().getAbsolutePath());
    }

    private void setupAudioManager() throws Exception {
        AudioDeviceInfo[] outputDevices = generateOutputDeviceInfos();
        AudioDeviceInfo[] inputDevices = generateInputDeviceInfos();
        when(mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                .thenReturn(outputDevices);
        when(mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS))
               .thenReturn(inputDevices);
        when(mMockContext.getSystemService(Context.AUDIO_SERVICE)).thenReturn(mAudioManager);

        when(mAudioManager.registerAudioPolicy(any())).thenAnswer(invocation -> {
            AudioPolicy policy = (AudioPolicy) invocation.getArguments()[0];
            policy.setRegistration(REGISTRATION_ID);
            return SUCCESS;
        });

        IBinder mockBinder = mock(IBinder.class);
        when(mockBinder.queryLocalInterface(any())).thenReturn(mMockAudioService);
        doReturn(mockBinder).when(() -> ServiceManager.getService(Context.AUDIO_SERVICE));
    }

    private void setupResources() {
        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);
        when(mMockContext.createContextAsUser(any(), anyInt())).thenReturn(mMockContext);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getBoolean(audioUseDynamicRouting)).thenReturn(mUseDynamicRouting);
        when(mMockResources.getInteger(audioVolumeKeyEventTimeoutMs))
                .thenReturn(VOLUME_KEY_EVENT_TIMEOUT_MS);
        when(mMockResources.getBoolean(audioUseHalDuckingSignals)).thenReturn(mUseHalAudioDucking);
        when(mMockResources.getBoolean(audioUseCarVolumeGroupMuting))
                .thenReturn(mUserCarVolumeGroupMuting);
        when(mMockResources.getInteger(audioVolumeAdjustmentContextsVersion))
                .thenReturn(AUDIO_CONTEXT_PRIORITY_LIST_VERSION_TWO);
        when(mMockResources.getBoolean(audioPersistMasterMuteState)).thenReturn(mPersistMasterMute);
    }

    @Test
    public void constructor_withNullContext_fails() {
        NullPointerException thrown =
                assertThrows(NullPointerException.class, () -> new CarAudioService(null));

        assertWithMessage("Car Audio Service Construction")
                .that(thrown).hasMessageThat().contains("Context");
    }

    @Test
    public void constructor_withNullContextAndNullPath_fails() {
        NullPointerException thrown =
                assertThrows(NullPointerException.class,
                        () -> new CarAudioService(null, null));

        assertWithMessage("Car Audio Service Construction")
                .that(thrown).hasMessageThat().contains("Context");
    }

    @Test
    public void constructor_withInvalidVolumeConfiguration_fails() {
        when(mMockResources.getInteger(audioVolumeAdjustmentContextsVersion))
                .thenReturn(AUDIO_CONTEXT_PRIORITY_LIST_VERSION_ONE);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new CarAudioService(mMockContext));

        assertWithMessage("Car Audio Service Construction")
                .that(thrown).hasMessageThat()
                .contains("requires audioVolumeAdjustmentContextsVersion 2");
    }

    @Test
    public void getAudioZoneIds_withBaseConfiguration_returnAllTheZones() {
        mCarAudioService.init();

        assertWithMessage("Car Audio Service Zones")
                .that(mCarAudioService.getAudioZoneIds())
                .asList().containsExactly(PRIMARY_AUDIO_ZONE, SECONDARY_ZONE_ID);
    }

    @Test
    public void getVolumeGroupCount_onPrimaryZone_returnsAllGroups() {
        mCarAudioService.init();

        assertWithMessage("Primary Zone car volume group count")
                .that(mCarAudioService.getVolumeGroupCount(PRIMARY_AUDIO_ZONE))
                .isEqualTo(PRIMARY_ZONE_VOLUME_GROUP_COUNT);
    }

    @Test
    public void getVolumeGroupCount_onSecondaryZone_returnsAllGroups() {
        mCarAudioService.init();

        assertWithMessage("Secondary Zone car volume group count")
                .that(mCarAudioService.getVolumeGroupCount(SECONDARY_ZONE_ID))
                .isEqualTo(SECONDARY_ZONE_VOLUME_GROUP_COUNT);
    }

    @Test
    public void createAudioPatch_onMediaOutputDevice_failsForConfigurationMissing() {
        mCarAudioService.init();

        doReturn(false)
                .when(() -> SystemProperties.getBoolean(PROPERTY_RO_ENABLE_AUDIO_PATCH, false));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarAudioService
                        .createAudioPatch(PRIMARY_ZONE_FM_TUNER_ADDRESS,
                                USAGE_MEDIA, DEFAULT_GAIN));

        assertWithMessage("FM and Media Audio Patch Exception")
                .that(thrown).hasMessageThat().contains("Audio Patch APIs not enabled");
    }

    @Test
    public void createAudioPatch_onMediaOutputDevice_failsForMissingPermission() {
        mCarAudioService.init();

        mockDenyCarControlAudioSettingsPermission();

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> mCarAudioService
                        .createAudioPatch(PRIMARY_ZONE_FM_TUNER_ADDRESS,
                                USAGE_MEDIA, DEFAULT_GAIN));

        assertWithMessage("FM and Media Audio Patch Permission Exception")
                .that(thrown).hasMessageThat().contains(PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void createAudioPatch_onMediaOutputDevice_succeeds() {
        mCarAudioService.init();

        mockGrantCarControlAudioSettingsPermission();
        doReturn(false)
                .when(() -> SystemProperties.getBoolean(PROPERTY_RO_ENABLE_AUDIO_PATCH, true));
        doReturn(new AudioPatchInfo(PRIMARY_ZONE_FM_TUNER_ADDRESS, MEDIA_TEST_DEVICE, 0))
                .when(() -> AudioManagerHelper
                        .createAudioPatch(mTunerDevice, mMediaOutputDevice, DEFAULT_GAIN));

        CarAudioPatchHandle audioPatch = mCarAudioService
                .createAudioPatch(PRIMARY_ZONE_FM_TUNER_ADDRESS, USAGE_MEDIA, DEFAULT_GAIN);

        assertWithMessage("Audio Patch Sink Address")
                .that(audioPatch.getSinkAddress()).isEqualTo(MEDIA_TEST_DEVICE);
        assertWithMessage("Audio Patch Source Address")
                .that(audioPatch.getSourceAddress()).isEqualTo(PRIMARY_ZONE_FM_TUNER_ADDRESS);
        assertWithMessage("Audio Patch Handle")
                .that(audioPatch.getHandleId()).isEqualTo(0);
    }

    @Test
    public void releaseAudioPatch_failsForConfigurationMissing() {
        mCarAudioService.init();

        doReturn(false)
                .when(() -> SystemProperties.getBoolean(PROPERTY_RO_ENABLE_AUDIO_PATCH, false));
        CarAudioPatchHandle carAudioPatchHandle =
                new CarAudioPatchHandle(0, PRIMARY_ZONE_FM_TUNER_ADDRESS, MEDIA_TEST_DEVICE);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarAudioService.releaseAudioPatch(carAudioPatchHandle));

        assertWithMessage("Release FM and Media Audio Patch Exception")
                .that(thrown).hasMessageThat().contains("Audio Patch APIs not enabled");
    }

    @Test
    public void releaseAudioPatch_failsForMissingPermission() {
        mCarAudioService.init();

        mockDenyCarControlAudioSettingsPermission();
        CarAudioPatchHandle carAudioPatchHandle =
                new CarAudioPatchHandle(0, PRIMARY_ZONE_FM_TUNER_ADDRESS, MEDIA_TEST_DEVICE);

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> mCarAudioService.releaseAudioPatch(carAudioPatchHandle));

        assertWithMessage("FM and Media Audio Patch Permission Exception")
                .that(thrown).hasMessageThat().contains(PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void releaseAudioPatch_failsForNullPatch() {
        mCarAudioService.init();

        assertThrows(NullPointerException.class,
                () -> mCarAudioService.releaseAudioPatch(null));
    }

    @Test
    public void setZoneIdForUid_withoutRoutingPermission_fails() {
        mCarAudioService.init();

        mockDenyCarControlAudioSettingsPermission();

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> mCarAudioService.setZoneIdForUid(OUT_OF_RANGE_ZONE, MEDIA_APP_UID));

        assertWithMessage("Set Zone for UID Permission Exception")
                .that(thrown).hasMessageThat()
                .contains(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void setZoneIdForUid_withoutDynamicRouting_fails() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath());
        nonDynamicAudioService.init();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> nonDynamicAudioService.setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID));

        assertWithMessage("Set Zone for UID Dynamic Configuration Exception")
                .that(thrown).hasMessageThat()
                .contains("Dynamic routing is required");
    }

    @Test
    public void setZoneIdForUid_withInvalidZone_fails() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioService.setZoneIdForUid(INVALID_AUDIO_ZONE, MEDIA_APP_UID));

        assertWithMessage("Set Zone for UID Invalid Zone Exception")
                .that(thrown).hasMessageThat()
                .contains("Invalid audio zone Id " + INVALID_AUDIO_ZONE);
    }

    @Test
    public void setZoneIdForUid_withOutOfRangeZone_fails() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioService.setZoneIdForUid(OUT_OF_RANGE_ZONE, MEDIA_APP_UID));

        assertWithMessage("Set Zone for UID Zone Out of Range Exception")
                .that(thrown).hasMessageThat()
                .contains("Invalid audio zone Id " + OUT_OF_RANGE_ZONE);
    }

    @Test
    public void setZoneIdForUid_withZoneAudioMapping_fails() {
        mCarAudioService.init();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarAudioService.setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID));

        assertWithMessage("Set Zone for UID With Audio Zone Mapping Exception")
                .that(thrown).hasMessageThat()
                .contains("UID based routing is not supported while using occupant zone mapping");
    }

    @Test
    public void setZoneIdForUid_withValidZone_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath());
        noZoneMappingAudioService.init();

        boolean results = noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);

        assertWithMessage("Set Zone for UID Status").that(results).isTrue();
    }

    @Test
    public void setZoneIdForUid_onDifferentZones_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath());
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);

        boolean results = noZoneMappingAudioService
                .setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID);

        assertWithMessage("Set Zone for UID For Different Zone")
                .that(results).isTrue();
    }

    @Test
    public void setZoneIdForUid_onDifferentZones_withAudioFocus_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath());
        noZoneMappingAudioService.init();
        AudioFocusInfo audioFocusInfo = createAudioFocusInfoForMedia();

        noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);

        noZoneMappingAudioService
                .requestAudioFocusForTest(audioFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        boolean results = noZoneMappingAudioService
                .setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID);

        assertWithMessage("Set Zone for UID For Different Zone with Audio Focus")
                .that(results).isTrue();
    }

    @Test
    public void getZoneIdForUid_withoutMappedUid_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath());
        noZoneMappingAudioService.init();

        int zoneId = noZoneMappingAudioService
                .getZoneIdForUid(MEDIA_APP_UID);

        assertWithMessage("Get Zone for Non Mapped UID")
                .that(zoneId).isEqualTo(PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void getZoneIdForUid_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath());
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);

        int zoneId = noZoneMappingAudioService
                .getZoneIdForUid(MEDIA_APP_UID);

        assertWithMessage("Get Zone for UID Zone Id")
                .that(zoneId).isEqualTo(SECONDARY_ZONE_ID);
    }

    @Test
    public void getZoneIdForUid_afterSwitchingZones_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath());
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);

        noZoneMappingAudioService
                .setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID);

        int zoneId = noZoneMappingAudioService
                .getZoneIdForUid(MEDIA_APP_UID);

        assertWithMessage("Get Zone for UID Zone Id")
                .that(zoneId).isEqualTo(PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void clearZoneIdForUid_withoutRoutingPermission_fails() {
        mCarAudioService.init();

        mockDenyCarControlAudioSettingsPermission();

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> mCarAudioService.clearZoneIdForUid(MEDIA_APP_UID));

        assertWithMessage("Clear Zone for UID Permission Exception")
                .that(thrown).hasMessageThat()
                .contains(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void clearZoneIdForUid_withoutDynamicRouting_fails() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath());
        nonDynamicAudioService.init();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> nonDynamicAudioService.clearZoneIdForUid(MEDIA_APP_UID));

        assertWithMessage("Clear Zone for UID Dynamic Configuration Exception")
                .that(thrown).hasMessageThat()
                .contains("Dynamic routing is required");
    }

    @Test
    public void clearZoneIdForUid_withZoneAudioMapping_fails() {
        mCarAudioService.init();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarAudioService.clearZoneIdForUid(MEDIA_APP_UID));

        assertWithMessage("Clear Zone for UID Audio Zone Mapping Exception")
                .that(thrown).hasMessageThat()
                .contains("UID based routing is not supported while using occupant zone mapping");
    }

    @Test
    public void clearZoneIdForUid_forNonMappedUid_succeeds() throws Exception {
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath());
        noZoneMappingAudioService.init();

        boolean status = noZoneMappingAudioService
                .clearZoneIdForUid(MEDIA_APP_UID);

        assertWithMessage("Clear Zone for UID Audio Zone without Mapping")
                .that(status).isTrue();
    }

    @Test
    public void clearZoneIdForUid_forMappedUid_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath());
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);

        boolean status = noZoneMappingAudioService.clearZoneIdForUid(MEDIA_APP_UID);

        assertWithMessage("Clear Zone for UID Audio Zone with Mapping")
                .that(status).isTrue();
    }

    @Test
    public void getZoneIdForUid_afterClearedUidMapping_returnsDefaultZone() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath());
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);

        noZoneMappingAudioService.clearZoneIdForUid(MEDIA_APP_UID);

        int zoneId = noZoneMappingAudioService.getZoneIdForUid(MEDIA_APP_UID);

        assertWithMessage("Get Zone for UID Audio Zone with Cleared Mapping")
                .that(zoneId).isEqualTo(PRIMARY_AUDIO_ZONE);
    }

    private void mockGrantCarControlAudioSettingsPermission() {
        mockContextCheckCallingOrSelfPermission(mMockContext,
                PERMISSION_CAR_CONTROL_AUDIO_SETTINGS, PERMISSION_GRANTED);
    }

    private void mockDenyCarControlAudioSettingsPermission() {
        mockContextCheckCallingOrSelfPermission(mMockContext,
                PERMISSION_CAR_CONTROL_AUDIO_SETTINGS, PERMISSION_DENIED);
    }

    private AudioDeviceInfo[] generateInputDeviceInfos() {
        mTunerDevice = new AudioDeviceInfoBuilder().setAddressName(PRIMARY_ZONE_FM_TUNER_ADDRESS)
                .setType(TYPE_FM_TUNER)
                .setIsSource(true)
                .build();
        return new AudioDeviceInfo[]{
                new AudioDeviceInfoBuilder().setAddressName(PRIMARY_ZONE_MICROPHONE_ADDRESS)
                        .setType(TYPE_BUILTIN_MIC)
                        .setIsSource(true)
                        .build(),
                mTunerDevice
        };
    }

    private AudioDeviceInfo[] generateOutputDeviceInfos() {
        mMediaOutputDevice = new AudioDeviceInfoBuilder()
                .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                .setAddressName(MEDIA_TEST_DEVICE)
                .build();
        return new AudioDeviceInfo[] {
                mMediaOutputDevice,
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(NAVIGATION_TEST_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(CALL_TEST_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(SYSTEM_BUS_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(NOTIFICATION_TEST_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(VOICE_TEST_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(RING_TEST_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(ALARM_TEST_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(SECONDARY_TEST_DEVICE)
                        .build(),
        };
    }

    private static AudioFocusInfo createAudioFocusInfoForMedia() {
        AudioAttributes.Builder builder = new AudioAttributes.Builder();
        builder.setUsage(USAGE_MEDIA);

        return new AudioFocusInfo(builder.build(), MEDIA_APP_UID, MEDIA_CLIENT_ID,
                MEDIA_PACKAGE_NAME, AUDIOFOCUS_GAIN, AUDIOFOCUS_LOSS, MEDIA_EMPTY_FLAG, SDK_INT);
    }
}
