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
import static android.car.Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME;
import static android.car.PlatformVersion.VERSION_CODES.TIRAMISU_1;
import static android.car.PlatformVersion.VERSION_CODES.UPSIDE_DOWN_CAKE_0;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_AUDIO_MIRRORING;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_DYNAMIC_ROUTING;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_OEM_AUDIO_SERVICE;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_VOLUME_GROUP_EVENTS;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_VOLUME_GROUP_MUTING;
import static android.car.media.CarAudioManager.AUDIO_MIRROR_CAN_ENABLE;
import static android.car.media.CarAudioManager.AUDIO_MIRROR_OUT_OF_OUTPUT_DEVICES;
import static android.car.media.CarAudioManager.INVALID_AUDIO_ZONE;
import static android.car.media.CarAudioManager.INVALID_REQUEST_ID;
import static android.car.media.CarAudioManager.INVALID_VOLUME_GROUP_ID;
import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;
import static android.car.test.mocks.AndroidMockitoHelper.mockCarGetPlatformVersion;
import static android.car.test.mocks.AndroidMockitoHelper.mockContextCheckCallingOrSelfPermission;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.media.AudioAttributes.USAGE_ALARM;
import static android.media.AudioAttributes.USAGE_ANNOUNCEMENT;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
import static android.media.AudioAttributes.USAGE_ASSISTANT;
import static android.media.AudioAttributes.USAGE_CALL_ASSISTANT;
import static android.media.AudioAttributes.USAGE_EMERGENCY;
import static android.media.AudioAttributes.USAGE_GAME;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
import static android.media.AudioAttributes.USAGE_SAFETY;
import static android.media.AudioAttributes.USAGE_UNKNOWN;
import static android.media.AudioAttributes.USAGE_VEHICLE_STATUS;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING;
import static android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC;
import static android.media.AudioDeviceInfo.TYPE_FM_TUNER;
import static android.media.AudioManager.AUDIOFOCUS_GAIN;
import static android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
import static android.media.AudioManager.AUDIOFOCUS_LOSS;
import static android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
import static android.media.AudioManager.AUDIOFOCUS_NONE;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
import static android.media.AudioManager.EXTRA_VOLUME_STREAM_TYPE;
import static android.media.AudioManager.FLAG_FROM_KEY;
import static android.media.AudioManager.FLAG_PLAY_SOUND;
import static android.media.AudioManager.FLAG_SHOW_UI;
import static android.media.AudioManager.MASTER_MUTE_CHANGED_ACTION;
import static android.media.AudioManager.STREAM_MUSIC;
import static android.media.AudioManager.SUCCESS;
import static android.media.AudioManager.VOLUME_CHANGED_ACTION;
import static android.media.audio.common.AudioDeviceDescription.CONNECTION_BUS;
import static android.media.audio.common.AudioDeviceType.OUT_DEVICE;
import static android.media.audio.common.AudioGainMode.JOINT;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.KeyEvent.ACTION_DOWN;
import static android.view.KeyEvent.ACTION_UP;
import static android.view.KeyEvent.KEYCODE_UNKNOWN;
import static android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
import static android.view.KeyEvent.KEYCODE_VOLUME_MUTE;
import static android.view.KeyEvent.KEYCODE_VOLUME_UP;

import static com.android.car.R.bool.audioPersistMasterMuteState;
import static com.android.car.R.bool.audioUseCarVolumeGroupEvent;
import static com.android.car.R.bool.audioUseCarVolumeGroupMuting;
import static com.android.car.R.bool.audioUseCoreRouting;
import static com.android.car.R.bool.audioUseCoreVolume;
import static com.android.car.R.bool.audioUseDynamicRouting;
import static com.android.car.R.bool.audioUseHalDuckingSignals;
import static com.android.car.R.integer.audioVolumeAdjustmentContextsVersion;
import static com.android.car.R.integer.audioVolumeKeyEventTimeoutMs;
import static com.android.car.audio.CarAudioService.CAR_DEFAULT_AUDIO_ATTRIBUTE;
import static com.android.car.audio.GainBuilder.DEFAULT_GAIN;
import static com.android.car.audio.GainBuilder.MAX_GAIN;
import static com.android.car.audio.GainBuilder.MIN_GAIN;
import static com.android.car.audio.GainBuilder.STEP_SIZE;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.car.Car;
import android.car.CarOccupantZoneManager;
import android.car.ICarOccupantZoneCallback;
import android.car.VehicleAreaSeat;
import android.car.builtin.media.AudioManagerHelper;
import android.car.builtin.media.AudioManagerHelper.AudioPatchInfo;
import android.car.builtin.os.UserManagerHelper;
import android.car.media.CarAudioManager;
import android.car.media.CarAudioPatchHandle;
import android.car.media.CarAudioZoneConfigInfo;
import android.car.media.CarVolumeGroupEvent;
import android.car.media.CarVolumeGroupInfo;
import android.car.media.IAudioZonesMirrorStatusCallback;
import android.car.media.ICarVolumeEventCallback;
import android.car.media.IMediaAudioRequestStatusCallback;
import android.car.media.IPrimaryZoneMediaAudioRequestCallback;
import android.car.media.ISwitchAudioZoneConfigCallback;
import android.car.settings.CarSettings;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.MockSettings;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.automotive.audiocontrol.AudioGainConfigInfo;
import android.hardware.automotive.audiocontrol.IAudioControl;
import android.hardware.automotive.audiocontrol.Reasons;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusInfo;
import android.media.AudioGain;
import android.media.AudioManager;
import android.media.AudioManager.AudioPlaybackCallback;
import android.media.AudioPlaybackConfiguration;
import android.media.IAudioService;
import android.media.audio.common.AudioDevice;
import android.media.audio.common.AudioDeviceAddress;
import android.media.audio.common.AudioDeviceDescription;
import android.media.audio.common.AudioPort;
import android.media.audio.common.AudioPortDeviceExt;
import android.media.audio.common.AudioPortExt;
import android.media.audiopolicy.AudioPolicy;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import com.android.car.CarInputService;
import com.android.car.CarLocalServices;
import com.android.car.CarOccupantZoneService;
import com.android.car.R;
import com.android.car.audio.hal.AudioControlFactory;
import com.android.car.audio.hal.AudioControlWrapper;
import com.android.car.audio.hal.AudioControlWrapper.AudioControlDeathRecipient;
import com.android.car.audio.hal.AudioControlWrapperAidl;
import com.android.car.audio.hal.HalAudioDeviceInfo;
import com.android.car.audio.hal.HalAudioGainCallback;
import com.android.car.audio.hal.HalAudioModuleChangeCallback;
import com.android.car.audio.hal.HalFocusListener;
import com.android.car.oem.CarOemAudioDuckingProxyService;
import com.android.car.oem.CarOemAudioFocusProxyService;
import com.android.car.oem.CarOemAudioVolumeProxyService;
import com.android.car.oem.CarOemProxyService;
import com.android.car.test.utils.TemporaryFile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class CarAudioServiceUnitTest extends AbstractExtendedMockitoTestCase {
    private static final String TAG = CarAudioServiceUnitTest.class.getSimpleName();
    private static final long TEST_CALLBACK_TIMEOUT_MS = 100;
    private static final int VOLUME_KEY_EVENT_TIMEOUT_MS = 3000;
    private static final int AUDIO_CONTEXT_PRIORITY_LIST_VERSION_ONE = 1;
    private static final int AUDIO_CONTEXT_PRIORITY_LIST_VERSION_TWO = 2;
    private static final String MEDIA_TEST_DEVICE = "media_bus_device";
    private static final String OEM_TEST_DEVICE = "oem_bus_device";
    private static final String MIRROR_TEST_DEVICE = "mirror_bus_device";
    private static final String NAVIGATION_TEST_DEVICE = "navigation_bus_device";
    private static final String CALL_TEST_DEVICE = "call_bus_device";
    private static final String NOTIFICATION_TEST_DEVICE = "notification_bus_device";
    private static final String VOICE_TEST_DEVICE = "voice_bus_device";
    private static final String RING_TEST_DEVICE = "ring_bus_device";
    private static final String ALARM_TEST_DEVICE = "alarm_bus_device";
    private static final String SYSTEM_BUS_DEVICE = "system_bus_device";
    private static final String SECONDARY_TEST_DEVICE_CONFIG_0 = "secondary_zone_bus_100";
    private static final String SECONDARY_TEST_DEVICE_CONFIG_1_0 = "secondary_zone_bus_200";
    private static final String SECONDARY_TEST_DEVICE_CONFIG_1_1 = "secondary_zone_bus_201";
    private static final String TERTIARY_TEST_DEVICE_1 = "tertiary_zone_bus_100";
    private static final String TERTIARY_TEST_DEVICE_2 = "tertiary_zone_bus_200";
    private static final String QUATERNARY_TEST_DEVICE_1 = "quaternary_zone_bus_1";
    private static final String TEST_REAR_ROW_3_DEVICE = "rear_row_three_zone_bus_1";
    private static final String PRIMARY_ZONE_MICROPHONE_ADDRESS = "Built-In Mic";
    private static final String PRIMARY_ZONE_FM_TUNER_ADDRESS = "FM Tuner";
    private static final String SECONDARY_ZONE_CONFIG_NAME_1 = "secondary zone config 1";
    private static final String SECONDARY_ZONE_CONFIG_NAME_2 = "secondary zone config 2";
    private static final String MIRROR_OFF_SIGNAL = "mirroring=off";
    // From the car audio configuration file in /res/raw/car_audio_configuration.xml
    private static final int TEST_REAR_LEFT_ZONE_ID = 1;
    private static final int TEST_REAR_RIGHT_ZONE_ID = 2;
    private static final int TEST_FRONT_ZONE_ID = 3;
    private static final int TEST_REAR_ROW_3_ZONE_ID = 4;
    public static final int[] TEST_MIRROR_AUDIO_ZONES = new int[]{TEST_REAR_LEFT_ZONE_ID,
            TEST_REAR_RIGHT_ZONE_ID};
    private static final int OUT_OF_RANGE_ZONE = TEST_REAR_ROW_3_ZONE_ID + 1;
    private static final int PRIMARY_ZONE_VOLUME_GROUP_COUNT = 4;
    private static final int SECONDARY_ZONE_VOLUME_GROUP_COUNT = 1;
    private static final int SECONDARY_ZONE_VOLUME_GROUP_ID = SECONDARY_ZONE_VOLUME_GROUP_COUNT - 1;
    private static final int TEST_PRIMARY_ZONE_GROUP_0 = 0;
    private static final int TEST_PRIMARY_ZONE_GROUP_1 = 1;
    private static final int TEST_PRIMARY_ZONE_GROUP_2 = 2;
    private static final int TEST_SECONDARY_ZONE_GROUP_0 = 0;
    private static final int TEST_SECONDARY_ZONE_GROUP_1 = 1;
    private static final int TEST_FLAGS = 0;
    private static final float TEST_VALUE = -.75f;
    private static final float INVALID_TEST_VALUE = -1.5f;
    private static final int TEST_DISPLAY_TYPE = 2;
    private static final int TEST_SEAT = 2;
    private static final int PRIMARY_OCCUPANT_ZONE = 0;
    private static final int INVALID_STATUS = 0;

    private static final int TEST_DRIVER_OCCUPANT_ZONE_ID = 1;
    private static final int TEST_REAR_LEFT_OCCUPANT_ZONE_ID = 2;
    private static final int TEST_REAR_RIGHT_OCCUPANT_ZONE_ID = 3;
    private static final int TEST_FRONT_OCCUPANT_ZONE_ID = 4;
    private static final int TEST_REAR_ROW_3_OCCUPANT_ZONE_ID = 5;
    private static final int TEST_UNASSIGNED_OCCUPANT_ZONE_ID = 6;

    private static final int TEST_MEDIA_PORT_ID = 0;
    private static final int TEST_NAV_PORT_ID = 1;
    private static final String TEST_MEDIA_PORT_NAME = "Media bus";
    private static final String TEST_NAV_PORT_NAME = "Nav bus";
    private static final int TEST_GAIN_MIN_VALUE = -3000;
    private static final int TEST_GAIN_MAX_VALUE = -1000;
    private static final int TEST_GAIN_DEFAULT_VALUE = -2000;
    private static final int TEST_GAIN_STEP_VALUE = 2;

    private static final CarOccupantZoneManager.OccupantZoneInfo TEST_DRIVER_OCCUPANT =
            getOccupantInfo(TEST_DRIVER_OCCUPANT_ZONE_ID,
                    CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER,
                    VehicleAreaSeat.SEAT_ROW_1_LEFT);
    private static final CarOccupantZoneManager.OccupantZoneInfo
            TEST_REAR_RIGHT_PASSENGER_OCCUPANT =
            getOccupantInfo(TEST_REAR_RIGHT_OCCUPANT_ZONE_ID,
                    CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                    VehicleAreaSeat.SEAT_ROW_2_RIGHT);
    private static final CarOccupantZoneManager.OccupantZoneInfo
            TEST_FRONT_PASSENGER_OCCUPANT =
            getOccupantInfo(TEST_FRONT_OCCUPANT_ZONE_ID,
                    CarOccupantZoneManager.OCCUPANT_TYPE_FRONT_PASSENGER,
                    VehicleAreaSeat.SEAT_ROW_1_RIGHT);
    private static final CarOccupantZoneManager.OccupantZoneInfo
            TEST_REAR_LEFT_PASSENGER_OCCUPANT =
            getOccupantInfo(TEST_REAR_LEFT_OCCUPANT_ZONE_ID,
                    CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                    VehicleAreaSeat.SEAT_ROW_2_LEFT);

    private static final CarOccupantZoneManager.OccupantZoneInfo
            TEST_REAR_ROW_3_PASSENGER_OCCUPANT =
            getOccupantInfo(TEST_REAR_ROW_3_OCCUPANT_ZONE_ID,
                    CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                    VehicleAreaSeat.SEAT_ROW_3_LEFT);

    private static final String PROPERTY_RO_ENABLE_AUDIO_PATCH =
            "ro.android.car.audio.enableaudiopatch";

    private static final int MEDIA_APP_UID = 1086753;
    private static final int TEST_REAR_RIGHT_UID = 1286753;
    private static final String MEDIA_CLIENT_ID = "media-client-id";
    private static final String MEDIA_PACKAGE_NAME = "com.android.car.audio";
    private static final int MEDIA_EMPTY_FLAG = 0;
    private static final String REGISTRATION_ID = "meh";
    private static final int MEDIA_VOLUME_GROUP_ID = 0;
    private static final int NAVIGATION_VOLUME_GROUP_ID = 1;
    private static final int INVALID_USAGE = -1;
    private static final int INVALID_AUDIO_FEATURE = -1;
    private static final int TEST_DRIVER_USER_ID = 10;
    private static final int TEST_REAR_LEFT_USER_ID = 11;
    private static final int TEST_REAR_RIGHT_USER_ID = 12;
    private static final int TEST_FRONT_PASSENGER_USER_ID = 13;
    private static final int TEST_REAR_ROW_3_PASSENGER_USER_ID = 14;
    private static final int TEST_GAIN_INDEX = 4;

    // TODO(b/273800524): create a utility test class for audio attributes.
    private static final AudioAttributes ATTRIBUTES_UNKNOWN =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_UNKNOWN);
    private static final AudioAttributes ATTRIBUTES_GAME =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_GAME);
    private static final AudioAttributes ATTRIBUTES_MEDIA =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA);
    private static final AudioAttributes ATTRIBUTES_NOTIFICATION =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_NOTIFICATION);
    private static final AudioAttributes ATTRIBUTES_NOTIFICATION_EVENT =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_NOTIFICATION_EVENT);
    private static final AudioAttributes ATTRIBUTES_ANNOUNCEMENT =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ANNOUNCEMENT);
    private static final AudioAttributes ATTRIBUTES_ASSISTANCE_NAVIGATION_GUIDANCE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);
    private static final AudioAttributes ATTRIBUTES_ASSISTANCE_ACCESSIBILITY =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ASSISTANCE_ACCESSIBILITY);
    private static final AudioAttributes ATTRIBUTES_ASSISTANT =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ASSISTANT);
    private static final AudioAttributes ATTRIBUTES_NOTIFICATION_RINGTONE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_NOTIFICATION_RINGTONE);
    private static final AudioAttributes ATTRIBUTES_VOICE_COMMUNICATION =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_VOICE_COMMUNICATION);
    private static final AudioAttributes ATTRIBUTES_CALL_ASSISTANT =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_CALL_ASSISTANT);
    private static final AudioAttributes ATTRIBUTES_VOICE_COMMUNICATION_SIGNALLING =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_VOICE_COMMUNICATION_SIGNALLING);
    private static final AudioAttributes ATTRIBUTES_ALARM =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ALARM);
    private static final AudioAttributes ATTRIBUTES_ASSISTANCE_SONIFICATION =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ASSISTANCE_SONIFICATION);
    private static final AudioAttributes ATTRIBUTES_EMERGENCY =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_EMERGENCY);
    private static final AudioAttributes ATTRIBUTES_SAFETY =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_SAFETY);
    private static final AudioAttributes ATTRIBUTES_VEHICLE_STATUS =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_VEHICLE_STATUS);

    private static final List<AudioAttributes> TEST_PRIMARY_ZONE_AUDIO_ATTRIBUTES_0 = List.of(
            ATTRIBUTES_UNKNOWN, ATTRIBUTES_GAME, ATTRIBUTES_MEDIA, ATTRIBUTES_NOTIFICATION,
            ATTRIBUTES_NOTIFICATION_EVENT, ATTRIBUTES_ANNOUNCEMENT);

    private static final List<AudioAttributes> TEST_PRIMARY_ZONE_AUDIO_ATTRIBUTES_1 = List.of(
            ATTRIBUTES_ASSISTANCE_NAVIGATION_GUIDANCE, ATTRIBUTES_ASSISTANCE_ACCESSIBILITY,
            ATTRIBUTES_ASSISTANT);

    private static final List<AudioAttributes> TEST_SECONDARY_ZONE_AUDIO_ATTRIBUTES_DEFAULT =
            List.of(ATTRIBUTES_UNKNOWN, ATTRIBUTES_GAME, ATTRIBUTES_MEDIA,
                    ATTRIBUTES_ASSISTANCE_NAVIGATION_GUIDANCE, ATTRIBUTES_ASSISTANCE_ACCESSIBILITY,
                    ATTRIBUTES_ASSISTANT, ATTRIBUTES_NOTIFICATION_RINGTONE,
                    ATTRIBUTES_VOICE_COMMUNICATION, ATTRIBUTES_CALL_ASSISTANT,
                    ATTRIBUTES_VOICE_COMMUNICATION_SIGNALLING, ATTRIBUTES_ALARM,
                    ATTRIBUTES_NOTIFICATION, ATTRIBUTES_NOTIFICATION_EVENT,
                    ATTRIBUTES_ASSISTANCE_SONIFICATION, ATTRIBUTES_EMERGENCY, ATTRIBUTES_SAFETY,
                    ATTRIBUTES_VEHICLE_STATUS, ATTRIBUTES_ANNOUNCEMENT);

    private static final List<AudioAttributes> TEST_SECONDARY_ZONE_AUDIO_ATTRIBUTES_0 = List.of(
            ATTRIBUTES_UNKNOWN, ATTRIBUTES_GAME, ATTRIBUTES_MEDIA,
            ATTRIBUTES_ASSISTANCE_NAVIGATION_GUIDANCE, ATTRIBUTES_ASSISTANCE_ACCESSIBILITY,
            ATTRIBUTES_ASSISTANT, ATTRIBUTES_NOTIFICATION, ATTRIBUTES_NOTIFICATION_EVENT,
            ATTRIBUTES_ANNOUNCEMENT);

    private static final List<AudioAttributes> TEST_SECONDARY_ZONE_AUDIO_ATTRIBUTES_1 = List.of(
            ATTRIBUTES_NOTIFICATION_RINGTONE, ATTRIBUTES_VOICE_COMMUNICATION,
            ATTRIBUTES_CALL_ASSISTANT, ATTRIBUTES_VOICE_COMMUNICATION_SIGNALLING, ATTRIBUTES_ALARM,
            ATTRIBUTES_ASSISTANCE_SONIFICATION, ATTRIBUTES_EMERGENCY, ATTRIBUTES_SAFETY,
            ATTRIBUTES_VEHICLE_STATUS);


    private static final CarVolumeGroupInfo TEST_PRIMARY_ZONE_VOLUME_INFO_0 =
            new CarVolumeGroupInfo.Builder("group id " + TEST_PRIMARY_ZONE_GROUP_0,
                    PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0).setMuted(true)
                    .setMinVolumeGainIndex(0).setMaxVolumeGainIndex(MAX_GAIN / STEP_SIZE)
                    .setVolumeGainIndex(DEFAULT_GAIN / STEP_SIZE)
                    .setAudioAttributes(TEST_PRIMARY_ZONE_AUDIO_ATTRIBUTES_0).build();

    private static final CarVolumeGroupInfo TEST_PRIMARY_ZONE_UNMUTED_VOLUME_INFO_0 =
            new CarVolumeGroupInfo.Builder("group id " + TEST_PRIMARY_ZONE_GROUP_0,
                    PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0).setMuted(false)
                    .setMinVolumeGainIndex(0).setMaxVolumeGainIndex(MAX_GAIN / STEP_SIZE)
                    .setVolumeGainIndex(DEFAULT_GAIN / STEP_SIZE)
                    .setAudioAttributes(TEST_PRIMARY_ZONE_AUDIO_ATTRIBUTES_0).build();

    private static final CarVolumeGroupInfo TEST_PRIMARY_ZONE_VOLUME_INFO_1 =
            new CarVolumeGroupInfo.Builder("group id " + TEST_PRIMARY_ZONE_GROUP_1,
                    PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_1).setMuted(true)
                    .setMinVolumeGainIndex(0).setMaxVolumeGainIndex(MAX_GAIN / STEP_SIZE)
                    .setAudioAttributes(TEST_PRIMARY_ZONE_AUDIO_ATTRIBUTES_1)
                    .setVolumeGainIndex(DEFAULT_GAIN / STEP_SIZE).build();

    private static final CarVolumeGroupInfo TEST_SECONDARY_ZONE_CONFIG_0_VOLUME_INFO =
            new CarVolumeGroupInfo.Builder("group id " + TEST_SECONDARY_ZONE_GROUP_0,
                    TEST_REAR_LEFT_ZONE_ID, TEST_SECONDARY_ZONE_GROUP_0)
                    .setMinVolumeGainIndex(0).setMaxVolumeGainIndex(MAX_GAIN / STEP_SIZE)
                    .setAudioAttributes(TEST_SECONDARY_ZONE_AUDIO_ATTRIBUTES_DEFAULT)
                    .setVolumeGainIndex(DEFAULT_GAIN / STEP_SIZE).build();

    private static final CarVolumeGroupInfo TEST_SECONDARY_ZONE_CONFIG_1_VOLUME_INFO_0 =
            new CarVolumeGroupInfo.Builder("group id " + TEST_SECONDARY_ZONE_GROUP_0,
                    TEST_REAR_LEFT_ZONE_ID, TEST_SECONDARY_ZONE_GROUP_0)
                    .setMinVolumeGainIndex(0).setMaxVolumeGainIndex(MAX_GAIN / STEP_SIZE)
                    .setAudioAttributes(TEST_SECONDARY_ZONE_AUDIO_ATTRIBUTES_0)
                    .setVolumeGainIndex(DEFAULT_GAIN / STEP_SIZE).build();

    private static final CarVolumeGroupInfo TEST_SECONDARY_ZONE_CONFIG_1_VOLUME_INFO_1 =
            new CarVolumeGroupInfo.Builder("group id " + TEST_SECONDARY_ZONE_GROUP_1,
                    TEST_REAR_LEFT_ZONE_ID, TEST_SECONDARY_ZONE_GROUP_1)
                    .setMinVolumeGainIndex(0).setMaxVolumeGainIndex(MAX_GAIN / STEP_SIZE)
                    .setAudioAttributes(TEST_SECONDARY_ZONE_AUDIO_ATTRIBUTES_1)
                    .setVolumeGainIndex(DEFAULT_GAIN / STEP_SIZE).build();

    private static final AudioDeviceInfo MICROPHONE_TEST_DEVICE =
            new AudioDeviceInfoBuilder().setAddressName(PRIMARY_ZONE_MICROPHONE_ADDRESS)
            .setType(TYPE_BUILTIN_MIC)
            .setIsSource(true)
            .build();
    private static final AudioDeviceInfo FM_TUNER_TEST_DEVICE =
            new AudioDeviceInfoBuilder().setAddressName(PRIMARY_ZONE_FM_TUNER_ADDRESS)
            .setType(TYPE_FM_TUNER)
            .setIsSource(true)
            .build();

    private static final AudioFocusInfo TEST_AUDIO_FOCUS_INFO =
            new AudioFocusInfo(CarAudioContext
                    .getAudioAttributeFromUsage(USAGE_VOICE_COMMUNICATION), MEDIA_APP_UID,
            MEDIA_CLIENT_ID, "com.android.car.audio",
            AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AUDIOFOCUS_NONE, /* loss= */ 0,
            Build.VERSION.SDK_INT);

    private static final AudioFocusInfo TEST_REAR_RIGHT_AUDIO_FOCUS_INFO =
            new AudioFocusInfo(CarAudioContext
            .getAudioAttributeFromUsage(USAGE_MEDIA), TEST_REAR_RIGHT_UID,
            MEDIA_CLIENT_ID, "com.android.car.audio",
            AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AUDIOFOCUS_NONE, /* loss= */ 0,
            Build.VERSION.SDK_INT);

    private static final CarVolumeGroupEvent TEST_CAR_VOLUME_GROUP_EVENT =
            new CarVolumeGroupEvent.Builder(List.of(TEST_PRIMARY_ZONE_UNMUTED_VOLUME_INFO_0),
                    CarVolumeGroupEvent.EVENT_TYPE_VOLUME_GAIN_INDEX_CHANGED,
                    List.of(CarVolumeGroupEvent.EXTRA_INFO_VOLUME_INDEX_CHANGED_BY_UI)).build();

    private static final CarVolumeGroupEvent TEST_CAR_MUTE_GROUP_EVENT =
            new CarVolumeGroupEvent.Builder(List.of(TEST_PRIMARY_ZONE_UNMUTED_VOLUME_INFO_0),
                    CarVolumeGroupEvent.EVENT_TYPE_MUTE_CHANGED,
                    List.of(CarVolumeGroupEvent.EXTRA_INFO_VOLUME_INDEX_CHANGED_BY_UI)).build();

    private static final CarVolumeGroupEvent TEST_CAR_ZONE_RECONFIGURATION_EVENT =
            new CarVolumeGroupEvent.Builder(List.of(TEST_PRIMARY_ZONE_UNMUTED_VOLUME_INFO_0),
                    CarVolumeGroupEvent.EVENT_TYPE_ZONE_CONFIGURATION_CHANGED,
                    List.of(CarVolumeGroupEvent.EXTRA_INFO_VOLUME_INDEX_CHANGED_BY_UI)).build();

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
    IBinder mVolumeCallbackBinder;
    @Mock
    IAudioControl mAudioControl;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private CarOccupantZoneService mMockOccupantZoneService;
    @Mock
    private CarOemProxyService mMockCarOemProxyService;
    @Mock
    private IAudioService mMockAudioService;
    @Mock
    private Uri mNavSettingUri;
    @Mock
    private AudioControlWrapperAidl mAudioControlWrapperAidl;
    @Mock
    private CarVolumeCallbackHandler mCarVolumeCallbackHandler;
    @Mock
    private CarInputService mMockCarInputService;

    // Not used directly, but sets proper mockStatic() expectations on Settings
    @SuppressWarnings("UnusedVariable")
    private MockSettings mMockSettings;

    private boolean mPersistMasterMute = true;
    private boolean mUseDynamicRouting = true;
    private boolean mUseHalAudioDucking = true;
    private boolean mUseCarVolumeGroupMuting = true;
    private boolean mUseCarVolumeGroupEvents = true;

    private TemporaryFile mTemporaryAudioConfigurationUsingCoreAudioFile;
    private TemporaryFile mTemporaryAudioConfigurationFile;
    private TemporaryFile mTemporaryAudioConfigurationWithoutZoneMappingFile;
    private TemporaryFile mTemporaryAudioConfigurationWithoutMirroringFile;
    private Context mContext;
    private AudioDeviceInfo mMicrophoneInputDevice;
    private AudioDeviceInfo mFmTunerInputDevice;
    private AudioDeviceInfo mMediaOutputDevice;

    @Captor
    private ArgumentCaptor<BroadcastReceiver> mVolumeReceiverCaptor;

    public CarAudioServiceUnitTest() {
        super(CarAudioService.TAG);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        mMockSettings = new MockSettings(session);
        session
                .spyStatic(AudioManager.class)
                .spyStatic(AudioManagerHelper.class)
                .spyStatic(AudioControlWrapperAidl.class)
                .spyStatic(CoreAudioHelper.class)
                .spyStatic(AudioControlFactory.class)
                .spyStatic(SystemProperties.class)
                .spyStatic(ServiceManager.class)
                .spyStatic(Car.class);
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

        try (InputStream configurationStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_core_audio_routing_and_volume)) {
            mTemporaryAudioConfigurationUsingCoreAudioFile = new TemporaryFile("xml");
            mTemporaryAudioConfigurationUsingCoreAudioFile
                    .write(new String(configurationStream.readAllBytes()));
            Log.i(TAG, "Temporary Car Audio Configuration using Core Audio File Location: "
                    + mTemporaryAudioConfigurationUsingCoreAudioFile.getPath());
        }

        try (InputStream configurationStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_without_mirroring)) {
            mTemporaryAudioConfigurationWithoutMirroringFile = new TemporaryFile("xml");
            mTemporaryAudioConfigurationWithoutMirroringFile
                    .write(new String(configurationStream.readAllBytes()));
            Log.i(TAG, "Temporary Car Audio Configuration Without Mirroring File Location: "
                    + mTemporaryAudioConfigurationWithoutMirroringFile.getPath());
        }

        mockCarGetPlatformVersion(UPSIDE_DOWN_CAKE_0);

        mockCoreAudioRoutingAndVolume();
        mockGrantCarControlAudioSettingsPermission();

        setupAudioControlHAL();
        setupService();

        when(Settings.Secure.getUriFor(
                CarSettings.Secure.KEY_AUDIO_FOCUS_NAVIGATION_REJECTED_DURING_CALL))
                .thenReturn(mNavSettingUri);
    }

    @After
    public void tearDown() throws Exception {
        mTemporaryAudioConfigurationFile.close();
        mTemporaryAudioConfigurationWithoutZoneMappingFile.close();
        CarLocalServices.removeServiceForTest(CarOemProxyService.class);
        CarLocalServices.removeServiceForTest(CarOccupantZoneService.class);
    }

    private void setupAudioControlHAL() {
        when(mBinder.queryLocalInterface(anyString())).thenReturn(mAudioControl);
        doReturn(mBinder).when(AudioControlWrapperAidl::getService);
        when(mAudioControlWrapperAidl.supportsFeature(
                AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_DUCKING)).thenReturn(true);
        when(mAudioControlWrapperAidl.supportsFeature(
                AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_FOCUS)).thenReturn(true);
        when(mAudioControlWrapperAidl.supportsFeature(
                AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_GAIN_CALLBACK)).thenReturn(true);
        when(mAudioControlWrapperAidl.supportsFeature(
                AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_GROUP_MUTING)).thenReturn(true);
        when(mAudioControlWrapperAidl.supportsFeature(
                AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_MODULE_CALLBACK)).thenReturn(true);
        doReturn(mAudioControlWrapperAidl)
                .when(() -> AudioControlFactory.newAudioControl());
    }

    private void setupService() throws Exception {
        when(mMockContext.getSystemService(Context.TELEPHONY_SERVICE))
                .thenReturn(mMockTelephonyManager);
        when(mMockContext.getSystemService(Context.AUDIO_SERVICE))
                .thenReturn(mAudioManager);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        doReturn(true)
                .when(() -> AudioManagerHelper
                        .setAudioDeviceGain(any(), any(), anyInt(), anyBoolean()));
        doReturn(true)
                .when(() -> SystemProperties.getBoolean(PROPERTY_RO_ENABLE_AUDIO_PATCH, false));

        when(mMockOccupantZoneService.getUserForOccupant(TEST_DRIVER_OCCUPANT_ZONE_ID))
                .thenReturn(TEST_DRIVER_USER_ID);
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(TEST_DRIVER_USER_ID);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(TEST_DRIVER_OCCUPANT_ZONE_ID))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        when(mMockOccupantZoneService.getOccupantZoneForUser(UserHandle.of(TEST_DRIVER_USER_ID)))
                .thenReturn(TEST_DRIVER_OCCUPANT);

        when(mMockOccupantZoneService.getUserForOccupant(TEST_REAR_LEFT_OCCUPANT_ZONE_ID))
                .thenReturn(TEST_REAR_LEFT_USER_ID);
        when(mMockOccupantZoneService.getUserForOccupant(TEST_REAR_RIGHT_OCCUPANT_ZONE_ID))
                .thenReturn(TEST_REAR_RIGHT_USER_ID);
        when(mMockOccupantZoneService.getUserForOccupant(TEST_FRONT_OCCUPANT_ZONE_ID))
                .thenReturn(TEST_FRONT_PASSENGER_USER_ID);
        when(mMockOccupantZoneService.getUserForOccupant(TEST_REAR_ROW_3_OCCUPANT_ZONE_ID))
                .thenReturn(TEST_REAR_ROW_3_PASSENGER_USER_ID);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(TEST_REAR_LEFT_OCCUPANT_ZONE_ID))
                .thenReturn(TEST_REAR_LEFT_ZONE_ID);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(TEST_REAR_RIGHT_OCCUPANT_ZONE_ID))
                .thenReturn(TEST_REAR_RIGHT_ZONE_ID);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(TEST_FRONT_OCCUPANT_ZONE_ID))
                .thenReturn(TEST_FRONT_PASSENGER_USER_ID);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(TEST_REAR_ROW_3_ZONE_ID))
                .thenReturn(TEST_REAR_ROW_3_PASSENGER_USER_ID);
        when(mMockOccupantZoneService.getOccupantZoneForUser(
                UserHandle.of(TEST_REAR_RIGHT_USER_ID))).thenReturn(
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        when(mMockOccupantZoneService.getOccupantZoneForUser(
                UserHandle.of(TEST_FRONT_PASSENGER_USER_ID))).thenReturn(
                TEST_FRONT_PASSENGER_OCCUPANT);
        when(mMockOccupantZoneService.getOccupantZoneForUser(
                UserHandle.of(TEST_REAR_LEFT_USER_ID))).thenReturn(
                TEST_REAR_LEFT_PASSENGER_OCCUPANT);
        when(mMockOccupantZoneService.getOccupantForAudioZoneId(TEST_REAR_ROW_3_ZONE_ID))
                .thenReturn(TEST_REAR_ROW_3_PASSENGER_OCCUPANT);
        when(mMockOccupantZoneService.getOccupantForAudioZoneId(TEST_REAR_RIGHT_ZONE_ID))
                .thenReturn(TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        when(mMockOccupantZoneService.getOccupantForAudioZoneId(TEST_FRONT_ZONE_ID))
                .thenReturn(TEST_FRONT_PASSENGER_OCCUPANT);
        when(mMockOccupantZoneService.getOccupantForAudioZoneId(TEST_REAR_LEFT_ZONE_ID))
                .thenReturn(TEST_REAR_LEFT_PASSENGER_OCCUPANT);
        when(mMockOccupantZoneService.getOccupantForAudioZoneId(TEST_REAR_ROW_3_ZONE_ID))
                .thenReturn(TEST_REAR_ROW_3_PASSENGER_OCCUPANT);

        CarLocalServices.removeServiceForTest(CarOccupantZoneService.class);
        CarLocalServices.addService(CarOccupantZoneService.class, mMockOccupantZoneService);
        CarLocalServices.removeServiceForTest(CarInputService.class);
        CarLocalServices.addService(CarInputService.class, mMockCarInputService);

        CarLocalServices.removeServiceForTest(CarOemProxyService.class);
        CarLocalServices.addService(CarOemProxyService.class, mMockCarOemProxyService);

        setupAudioManager();

        setupResources();

        mCarAudioService =
                new CarAudioService(mMockContext,
                        mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                        mCarVolumeCallbackHandler);
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
                .thenReturn(mUseCarVolumeGroupMuting);
        when(mMockResources.getBoolean(audioUseCarVolumeGroupEvent))
                .thenReturn(mUseCarVolumeGroupEvents);
        when(mMockResources.getInteger(audioVolumeAdjustmentContextsVersion))
                .thenReturn(AUDIO_CONTEXT_PRIORITY_LIST_VERSION_TWO);
        when(mMockResources.getBoolean(audioPersistMasterMuteState)).thenReturn(mPersistMasterMute);
    }

    @Test
    public void constructor_withNullContext_fails() {
        NullPointerException thrown =
                assertThrows(NullPointerException.class, () -> new CarAudioService(null));

        expectWithMessage("Car Audio Service Construction")
                .that(thrown).hasMessageThat().contains("Context");
    }

    @Test
    public void constructor_withNullContextAndNullPath_fails() {
        NullPointerException thrown =
                assertThrows(NullPointerException.class,
                        () -> new CarAudioService(/* context= */null,
                                /* audioConfigurationPath= */ null,
                                /* carVolumeCallbackHandler= */ null));

        expectWithMessage("Car Audio Service Construction")
                .that(thrown).hasMessageThat().contains("Context");
    }

    @Test
    public void constructor_withInvalidVolumeConfiguration_fails() {
        when(mMockResources.getInteger(audioVolumeAdjustmentContextsVersion))
                .thenReturn(AUDIO_CONTEXT_PRIORITY_LIST_VERSION_ONE);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new CarAudioService(mMockContext));

        expectWithMessage("Car Audio Service Construction")
                .that(thrown).hasMessageThat()
                .contains("requires audioVolumeAdjustmentContextsVersion 2");
    }

    @Test
    public void getAudioZoneIds_withBaseConfiguration_returnAllTheZones() {
        mCarAudioService.init();

        expectWithMessage("Car Audio Service Zones")
                .that(mCarAudioService.getAudioZoneIds()).asList()
                .containsExactly(PRIMARY_AUDIO_ZONE, TEST_REAR_LEFT_ZONE_ID,
                        TEST_REAR_RIGHT_ZONE_ID, TEST_FRONT_ZONE_ID, TEST_REAR_ROW_3_ZONE_ID);
    }

    @Test
    public void getVolumeGroupCount_onPrimaryZone_returnsAllGroups() {
        mCarAudioService.init();

        expectWithMessage("Primary zone car volume group count")
                .that(mCarAudioService.getVolumeGroupCount(PRIMARY_AUDIO_ZONE))
                .isEqualTo(PRIMARY_ZONE_VOLUME_GROUP_COUNT);
    }

    @Test
    public void getVolumeGroupCount_onPrimaryZone__withNonDynamicRouting_returnsAllGroups() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        expectWithMessage("Non dynamic routing primary zone car volume group count")
                .that(nonDynamicAudioService.getVolumeGroupCount(PRIMARY_AUDIO_ZONE))
                .isEqualTo(CarAudioDynamicRouting.STREAM_TYPES.length);
    }

    @Test
    public void getVolumeGroupIdForUsage_forMusicUsage() {
        mCarAudioService.init();

        expectWithMessage("Primary zone's media car volume group id")
                .that(mCarAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE, USAGE_MEDIA))
                .isEqualTo(MEDIA_VOLUME_GROUP_ID);
    }

    @Test
    public void getVolumeGroupIdForUsage_withNonDynamicRouting_forMusicUsage() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        expectWithMessage("Non dynamic routing primary zone's media car volume group id")
                .that(nonDynamicAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE,
                        USAGE_MEDIA)).isEqualTo(MEDIA_VOLUME_GROUP_ID);
    }

    @Test
    public void getVolumeGroupIdForUsage_forNavigationUsage() {
        mCarAudioService.init();

        expectWithMessage("Primary zone's navigation car volume group id")
                .that(mCarAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE,
                        USAGE_ASSISTANCE_NAVIGATION_GUIDANCE))
                .isEqualTo(NAVIGATION_VOLUME_GROUP_ID);
    }

    @Test
    public void getVolumeGroupIdForUsage_withNonDynamicRouting_forNavigationUsage() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        expectWithMessage("Non dynamic routing primary zone's navigation car volume group id")
                .that(nonDynamicAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE,
                        USAGE_ASSISTANCE_NAVIGATION_GUIDANCE))
                .isEqualTo(INVALID_VOLUME_GROUP_ID);
    }

    @Test
    public void getVolumeGroupIdForUsage_forInvalidUsage_returnsInvalidGroupId() {
        mCarAudioService.init();

        expectWithMessage("Primary zone's invalid car volume group id")
                .that(mCarAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE, INVALID_USAGE))
                .isEqualTo(INVALID_VOLUME_GROUP_ID);
    }

    @Test
    public void
            getVolumeGroupIdForUsage_forInvalidUsage_withNonDynamicRouting_returnsInvalidGroupId() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        expectWithMessage("Non dynamic routing primary zone's invalid car volume group id")
                .that(nonDynamicAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE,
                        INVALID_USAGE)).isEqualTo(INVALID_VOLUME_GROUP_ID);
    }

    @Test
    public void getVolumeGroupIdForUsage_forUnknownUsage_returnsMediaGroupId() {
        mCarAudioService.init();

        expectWithMessage("Primary zone's unknown car volume group id")
                .that(mCarAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE, USAGE_UNKNOWN))
                .isEqualTo(MEDIA_VOLUME_GROUP_ID);
    }

    @Test
    public void getVolumeGroupIdForUsage_forVirtualUsage_returnsInvalidGroupId() {
        mCarAudioService.init();

        expectWithMessage("Primary zone's virtual car volume group id")
                .that(mCarAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE,
                        AudioManagerHelper.getUsageVirtualSource()))
                .isEqualTo(INVALID_VOLUME_GROUP_ID);
    }

    @Test
    public void getVolumeGroupCount_onSecondaryZone_returnsAllGroups() {
        mCarAudioService.init();

        expectWithMessage("Secondary Zone car volume group count")
                .that(mCarAudioService.getVolumeGroupCount(TEST_REAR_LEFT_ZONE_ID))
                .isEqualTo(SECONDARY_ZONE_VOLUME_GROUP_COUNT);
    }

    @Test
    public void getUsagesForVolumeGroupId_forMusicContext() {
        mCarAudioService.init();


        expectWithMessage("Primary zone's music car volume group id usages")
                .that(mCarAudioService.getUsagesForVolumeGroupId(PRIMARY_AUDIO_ZONE,
                        MEDIA_VOLUME_GROUP_ID)).asList()
                .containsExactly(USAGE_UNKNOWN, USAGE_GAME, USAGE_MEDIA, USAGE_ANNOUNCEMENT,
                        USAGE_NOTIFICATION, USAGE_NOTIFICATION_EVENT);
    }

    @Test
    public void getUsagesForVolumeGroupId_forSystemContext() {
        mCarAudioService.init();
        int systemVolumeGroup =
                mCarAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE, USAGE_EMERGENCY);

        expectWithMessage("Primary zone's system car volume group id usages")
                .that(mCarAudioService.getUsagesForVolumeGroupId(PRIMARY_AUDIO_ZONE,
                        systemVolumeGroup)).asList().containsExactly(USAGE_ALARM, USAGE_EMERGENCY,
                        USAGE_SAFETY, USAGE_VEHICLE_STATUS, USAGE_ASSISTANCE_SONIFICATION);
    }

    @Test
    public void getUsagesForVolumeGroupId_onSecondaryZone_forSingleVolumeGroupId_returnAllUsages() {
        mCarAudioService.init();

        expectWithMessage("Secondary Zone's car volume group id usages")
                .that(mCarAudioService.getUsagesForVolumeGroupId(TEST_REAR_LEFT_ZONE_ID,
                        SECONDARY_ZONE_VOLUME_GROUP_ID))
                .asList().containsExactly(USAGE_UNKNOWN, USAGE_MEDIA,
                        USAGE_VOICE_COMMUNICATION, USAGE_VOICE_COMMUNICATION_SIGNALLING,
                        USAGE_ALARM, USAGE_NOTIFICATION, USAGE_NOTIFICATION_RINGTONE,
                        USAGE_NOTIFICATION_EVENT, USAGE_ASSISTANCE_ACCESSIBILITY,
                        USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, USAGE_ASSISTANCE_SONIFICATION,
                        USAGE_GAME, USAGE_ASSISTANT, USAGE_CALL_ASSISTANT, USAGE_EMERGENCY,
                        USAGE_ANNOUNCEMENT, USAGE_SAFETY, USAGE_VEHICLE_STATUS);
    }

    @Test
    public void getUsagesForVolumeGroupId_withoutDynamicRouting() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        expectWithMessage("Media car volume group id without dynamic routing").that(
                nonDynamicAudioService.getUsagesForVolumeGroupId(PRIMARY_AUDIO_ZONE,
                MEDIA_VOLUME_GROUP_ID)).asList()
                .containsExactly(CarAudioDynamicRouting.STREAM_TYPE_USAGES[MEDIA_VOLUME_GROUP_ID]);
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

        expectWithMessage("FM and Media Audio Patch Exception")
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

        expectWithMessage("FM and Media Audio Patch Permission Exception")
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
                        .createAudioPatch(mFmTunerInputDevice, mMediaOutputDevice, DEFAULT_GAIN));

        CarAudioPatchHandle audioPatch = mCarAudioService
                .createAudioPatch(PRIMARY_ZONE_FM_TUNER_ADDRESS, USAGE_MEDIA, DEFAULT_GAIN);

        expectWithMessage("Audio Patch Sink Address")
                .that(audioPatch.getSinkAddress()).isEqualTo(MEDIA_TEST_DEVICE);
        expectWithMessage("Audio Patch Source Address")
                .that(audioPatch.getSourceAddress()).isEqualTo(PRIMARY_ZONE_FM_TUNER_ADDRESS);
        expectWithMessage("Audio Patch Handle")
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

        expectWithMessage("Release FM and Media Audio Patch Exception")
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

        expectWithMessage("FM and Media Audio Patch Permission Exception")
                .that(thrown).hasMessageThat().contains(PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void releaseAudioPatch_forNullSourceAddress_throwsNullPointerException() {
        mCarAudioService.init();
        mockGrantCarControlAudioSettingsPermission();
        doReturn(new AudioPatchInfo(PRIMARY_ZONE_FM_TUNER_ADDRESS, MEDIA_TEST_DEVICE, 0))
                .when(() -> AudioManagerHelper
                        .createAudioPatch(mFmTunerInputDevice, mMediaOutputDevice, DEFAULT_GAIN));

        CarAudioPatchHandle audioPatch = mock(CarAudioPatchHandle.class);
        when(audioPatch.getSourceAddress()).thenReturn(null);

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> mCarAudioService.releaseAudioPatch(audioPatch));

        expectWithMessage("Release audio patch for null source address "
                + "and sink address Null Exception")
                .that(thrown).hasMessageThat()
                .contains("Source Address can not be null for patch id 0");
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

        expectWithMessage("Set Zone for UID Permission Exception")
                .that(thrown).hasMessageThat()
                .contains(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void setZoneIdForUid_withoutDynamicRouting_fails() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> nonDynamicAudioService.setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID));

        expectWithMessage("Set Zone for UID Dynamic Configuration Exception")
                .that(thrown).hasMessageThat()
                .contains("Dynamic routing is required");
    }

    @Test
    public void setZoneIdForUid_withInvalidZone_fails() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioService.setZoneIdForUid(INVALID_AUDIO_ZONE, MEDIA_APP_UID));

        expectWithMessage("Set Zone for UID Invalid Zone Exception")
                .that(thrown).hasMessageThat()
                .contains("Invalid audio zone Id " + INVALID_AUDIO_ZONE);
    }

    @Test
    public void setZoneIdForUid_withOutOfRangeZone_fails() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioService.setZoneIdForUid(OUT_OF_RANGE_ZONE, MEDIA_APP_UID));

        expectWithMessage("Set Zone for UID Zone Out of Range Exception")
                .that(thrown).hasMessageThat()
                .contains("Invalid audio zone Id " + OUT_OF_RANGE_ZONE);
    }

    @Test
    public void setZoneIdForUid_withZoneAudioMapping_fails() {
        mCarAudioService.init();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarAudioService.setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID));

        expectWithMessage("Set Zone for UID With Audio Zone Mapping Exception")
                .that(thrown).hasMessageThat()
                .contains("UID based routing is not supported while using occupant zone mapping");
    }

    @Test
    public void setZoneIdForUid_withValidZone_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        boolean results = noZoneMappingAudioService
                .setZoneIdForUid(TEST_REAR_LEFT_ZONE_ID, MEDIA_APP_UID);

        expectWithMessage("Set Zone for UID Status").that(results).isTrue();
    }

    @Test
    public void setZoneIdForUid_onDifferentZones_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(TEST_REAR_LEFT_ZONE_ID, MEDIA_APP_UID);

        boolean results = noZoneMappingAudioService
                .setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID);

        expectWithMessage("Set Zone for UID For Different Zone")
                .that(results).isTrue();
    }

    @Test
    public void setZoneIdForUid_onDifferentZones_withAudioFocus_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();
        AudioFocusInfo audioFocusInfo = createAudioFocusInfoForMedia();

        noZoneMappingAudioService
                .setZoneIdForUid(TEST_REAR_LEFT_ZONE_ID, MEDIA_APP_UID);

        noZoneMappingAudioService
                .requestAudioFocusForTest(audioFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        boolean results = noZoneMappingAudioService
                .setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID);

        expectWithMessage("Set Zone for UID For Different Zone with Audio Focus")
                .that(results).isTrue();
    }

    @Test
    public void getZoneIdForUid_withoutMappedUid_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        int zoneId = noZoneMappingAudioService
                .getZoneIdForUid(MEDIA_APP_UID);

        expectWithMessage("Get Zone for Non Mapped UID")
                .that(zoneId).isEqualTo(PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void getZoneIdForUid_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(TEST_REAR_LEFT_ZONE_ID, MEDIA_APP_UID);

        int zoneId = noZoneMappingAudioService
                .getZoneIdForUid(MEDIA_APP_UID);

        expectWithMessage("Get Zone for UID Zone Id")
                .that(zoneId).isEqualTo(TEST_REAR_LEFT_ZONE_ID);
    }

    @Test
    public void getZoneIdForUid_afterSwitchingZones_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(TEST_REAR_LEFT_ZONE_ID, MEDIA_APP_UID);

        noZoneMappingAudioService
                .setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID);

        int zoneId = noZoneMappingAudioService
                .getZoneIdForUid(MEDIA_APP_UID);

        expectWithMessage("Get Zone for UID Zone Id")
                .that(zoneId).isEqualTo(PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void clearZoneIdForUid_withoutRoutingPermission_fails() {
        mCarAudioService.init();

        mockDenyCarControlAudioSettingsPermission();

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> mCarAudioService.clearZoneIdForUid(MEDIA_APP_UID));

        expectWithMessage("Clear Zone for UID Permission Exception")
                .that(thrown).hasMessageThat()
                .contains(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void clearZoneIdForUid_withoutDynamicRouting_fails() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> nonDynamicAudioService.clearZoneIdForUid(MEDIA_APP_UID));

        expectWithMessage("Clear Zone for UID Dynamic Configuration Exception")
                .that(thrown).hasMessageThat()
                .contains("Dynamic routing is required");
    }

    @Test
    public void clearZoneIdForUid_withZoneAudioMapping_fails() {
        mCarAudioService.init();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarAudioService.clearZoneIdForUid(MEDIA_APP_UID));

        expectWithMessage("Clear Zone for UID Audio Zone Mapping Exception")
                .that(thrown).hasMessageThat()
                .contains("UID based routing is not supported while using occupant zone mapping");
    }

    @Test
    public void clearZoneIdForUid_forNonMappedUid_succeeds() throws Exception {
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        boolean status = noZoneMappingAudioService
                .clearZoneIdForUid(MEDIA_APP_UID);

        expectWithMessage("Clear Zone for UID Audio Zone without Mapping")
                .that(status).isTrue();
    }

    @Test
    public void clearZoneIdForUid_forMappedUid_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(TEST_REAR_LEFT_ZONE_ID, MEDIA_APP_UID);

        boolean status = noZoneMappingAudioService.clearZoneIdForUid(MEDIA_APP_UID);

        expectWithMessage("Clear Zone for UID Audio Zone with Mapping")
                .that(status).isTrue();
    }

    @Test
    public void getZoneIdForUid_afterClearedUidMapping_returnsDefaultZone() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(TEST_REAR_LEFT_ZONE_ID, MEDIA_APP_UID);

        noZoneMappingAudioService.clearZoneIdForUid(MEDIA_APP_UID);

        int zoneId = noZoneMappingAudioService.getZoneIdForUid(MEDIA_APP_UID);

        expectWithMessage("Get Zone for UID Audio Zone with Cleared Mapping")
                .that(zoneId).isEqualTo(PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void getZoneIdForAudioFocusInfo_withoutMappedUid_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        int zoneId = noZoneMappingAudioService
                .getZoneIdForAudioFocusInfo(TEST_AUDIO_FOCUS_INFO);

        expectWithMessage("Mapped audio focus info's zone")
                .that(zoneId).isEqualTo(PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void getZoneIdForAudioFocusInfo_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(TEST_REAR_LEFT_ZONE_ID, MEDIA_APP_UID);

        int zoneId = noZoneMappingAudioService
                .getZoneIdForAudioFocusInfo(TEST_AUDIO_FOCUS_INFO);

        expectWithMessage("Mapped audio focus info's zone")
                .that(zoneId).isEqualTo(TEST_REAR_LEFT_ZONE_ID);
    }

    @Test
    public void getZoneIdForAudioFocusInfo_afterSwitchingZones_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();
        noZoneMappingAudioService
                .setZoneIdForUid(TEST_REAR_LEFT_ZONE_ID, MEDIA_APP_UID);
        noZoneMappingAudioService
                .setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID);

        int zoneId = noZoneMappingAudioService
                .getZoneIdForAudioFocusInfo(TEST_AUDIO_FOCUS_INFO);

        expectWithMessage("Remapped audio focus info's zone")
                .that(zoneId).isEqualTo(PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void setGroupVolume_withoutPermission_fails() {
        mCarAudioService.init();

        mockDenyCarControlAudioVolumePermission();

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> mCarAudioService.setGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                        TEST_GAIN_INDEX, TEST_FLAGS));

        expectWithMessage("Set Volume Group Permission Exception")
                .that(thrown).hasMessageThat()
                .contains(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void setGroupVolume_withDynamicRoutingDisabled() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        nonDynamicAudioService.setGroupVolume(
                PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0, TEST_GAIN_INDEX, TEST_FLAGS);

        verify(mAudioManager).setStreamVolume(
                CarAudioDynamicRouting.STREAM_TYPES[TEST_PRIMARY_ZONE_GROUP_0],
                TEST_GAIN_INDEX,
                TEST_FLAGS);
    }

    @Test
    public void setGroupVolume_verifyNoCallbacks() {
        mCarAudioService.init();
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                /* mute= */ false, TEST_FLAGS);
        reset(mCarVolumeCallbackHandler);

        mCarAudioService.setGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                TEST_GAIN_INDEX, TEST_FLAGS);

        verify(mCarVolumeCallbackHandler, never()).onGroupMuteChange(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void setGroupVolume_afterSetVolumeGroupMute() {
        mCarAudioService.init();
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                /* mute= */ true, TEST_FLAGS);
        reset(mCarVolumeCallbackHandler);

        mCarAudioService.setGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                TEST_GAIN_INDEX, TEST_FLAGS);

        verify(mCarVolumeCallbackHandler).onGroupMuteChange(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_ZONE_GROUP_0, TEST_FLAGS);
    }

    @Test
    public void setGroupVolume_withVolumeGroupMutingDisabled_doesnotThrowException() {
        when(mMockResources.getBoolean(audioUseCarVolumeGroupMuting)).thenReturn(false);
        CarAudioService nonVolumeGroupMutingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonVolumeGroupMutingAudioService.init();
        HalAudioGainCallback callback = getHalAudioGainCallback();
        CarAudioGainConfigInfo carGain = createCarAudioGainConfigInfo(PRIMARY_AUDIO_ZONE,
                MEDIA_TEST_DEVICE, TEST_GAIN_INDEX);
        callback.onAudioDeviceGainsChanged(List.of(Reasons.TCU_MUTE), List.of(carGain));
        reset(mCarVolumeCallbackHandler);

        nonVolumeGroupMutingAudioService.setGroupVolume(
                PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0, TEST_GAIN_INDEX, TEST_FLAGS);

        // if an exception is thrown, the test automatically fails
        verify(mCarVolumeCallbackHandler).onVolumeGroupChange(eq(PRIMARY_AUDIO_ZONE),
                eq(TEST_PRIMARY_ZONE_GROUP_0), anyInt());
        verify(mCarVolumeCallbackHandler, never()).onGroupMuteChange(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void getOutputDeviceAddressForUsage_forMusicUsage() {
        mCarAudioService.init();

        String mediaDeviceAddress =
                mCarAudioService.getOutputDeviceAddressForUsage(PRIMARY_AUDIO_ZONE, USAGE_MEDIA);

        expectWithMessage("Media usage audio device address")
                .that(mediaDeviceAddress).isEqualTo(MEDIA_TEST_DEVICE);
    }

    @Test
    public void getOutputDeviceAddressForUsage_withNonDynamicRouting_forMediaUsage_fails() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> nonDynamicAudioService
                        .getOutputDeviceAddressForUsage(PRIMARY_AUDIO_ZONE, USAGE_MEDIA));

        expectWithMessage("Non dynamic routing media usage audio device address exception")
                .that(thrown).hasMessageThat().contains("Dynamic routing is required");
    }

    @Test
    public void getOutputDeviceAddressForUsage_forNavigationUsage() {
        mCarAudioService.init();

        String mediaDeviceAddress =
                mCarAudioService.getOutputDeviceAddressForUsage(PRIMARY_AUDIO_ZONE,
                        USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);

        expectWithMessage("Navigation usage audio device address")
                .that(mediaDeviceAddress).isEqualTo(NAVIGATION_TEST_DEVICE);
    }

    @Test
    public void getOutputDeviceAddressForUsage_forInvalidUsage_fails() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                mCarAudioService.getOutputDeviceAddressForUsage(PRIMARY_AUDIO_ZONE,
                        INVALID_USAGE));

        expectWithMessage("Invalid usage audio device address exception")
                .that(thrown).hasMessageThat().contains("Invalid audio attribute " + INVALID_USAGE);
    }

    @Test
    public void getOutputDeviceAddressForUsage_forVirtualUsage_fails() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                mCarAudioService.getOutputDeviceAddressForUsage(PRIMARY_AUDIO_ZONE,
                        AudioManagerHelper.getUsageVirtualSource()));

        expectWithMessage("Invalid context audio device address exception")
                .that(thrown).hasMessageThat()
                .contains("invalid");
    }

    @Test
    public void getOutputDeviceAddressForUsage_onSecondaryZone_forMusicUsage() {
        mCarAudioService.init();

        String mediaDeviceAddress = mCarAudioService.getOutputDeviceAddressForUsage(
                TEST_REAR_LEFT_ZONE_ID, USAGE_MEDIA);

        expectWithMessage("Media usage audio device address for secondary zone")
                .that(mediaDeviceAddress).isEqualTo(SECONDARY_TEST_DEVICE_CONFIG_0);
    }

    @Test
    public void getSuggestedAudioContextForZone_inPrimaryZone() {
        mCarAudioService.init();
        int defaultAudioContext = mCarAudioService.getCarAudioContext()
                .getContextForAudioAttribute(CAR_DEFAULT_AUDIO_ATTRIBUTE);

        expectWithMessage("Suggested audio context for primary zone")
                .that(mCarAudioService.getSuggestedAudioContextForZone(PRIMARY_AUDIO_ZONE))
                .isEqualTo(defaultAudioContext);
    }

    @Test
    public void getSuggestedAudioContextForZone_inSecondaryZone() {
        mCarAudioService.init();
        int defaultAudioContext = mCarAudioService.getCarAudioContext()
                .getContextForAudioAttribute(CAR_DEFAULT_AUDIO_ATTRIBUTE);

        expectWithMessage("Suggested audio context for secondary zone")
                .that(mCarAudioService.getSuggestedAudioContextForZone(TEST_REAR_LEFT_ZONE_ID))
                .isEqualTo(defaultAudioContext);
    }

    @Test
    public void getSuggestedAudioContextForZone_inInvalidZone() {
        mCarAudioService.init();

        expectWithMessage("Suggested audio context for invalid zone")
                .that(mCarAudioService.getSuggestedAudioContextForZone(INVALID_AUDIO_ZONE))
                .isEqualTo(CarAudioContext.getInvalidContext());
    }

    @Test
    public void isVolumeGroupMuted_noSetVolumeGroupMute() {
        mCarAudioService.init();

        expectWithMessage("Volume group mute for default state")
                .that(mCarAudioService.isVolumeGroupMuted(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isFalse();
    }

    @Test
    public void isVolumeGroupMuted_setVolumeGroupMuted_isFalse() {
        mCarAudioService.init();
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                /* mute= */ true, TEST_FLAGS);

        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                /* mute= */ false, TEST_FLAGS);

        expectWithMessage("Volume group muted after mute and unmute")
                .that(mCarAudioService.isVolumeGroupMuted(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isFalse();
    }

    @Test
    public void isVolumeGroupMuted_setVolumeGroupMuted_isTrue() {
        mCarAudioService.init();

        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                /* mute= */ true, TEST_FLAGS);
        expectWithMessage("Volume group muted after mute")
                .that(mCarAudioService.isVolumeGroupMuted(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isTrue();
    }

    @Test
    public void isVolumeGroupMuted_withVolumeGroupMutingDisabled() {
        when(mMockResources.getBoolean(audioUseCarVolumeGroupMuting))
                .thenReturn(false);
        CarAudioService nonVolumeGroupMutingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonVolumeGroupMutingAudioService.init();

        expectWithMessage("Volume group for disabled volume group muting")
                .that(nonVolumeGroupMutingAudioService.isVolumeGroupMuted(
                        PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0))
                .isFalse();
    }

    @Test
    public void getGroupMaxVolume_forPrimaryZone() {
        mCarAudioService.init();

        expectWithMessage("Group max volume for primary audio zone and group")
                .that(mCarAudioService.getGroupMaxVolume(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isEqualTo((MAX_GAIN - MIN_GAIN) / STEP_SIZE);
    }

    @Test
    public void getGroupMinVolume_forPrimaryZone() {
        mCarAudioService.init();

        expectWithMessage("Group Min Volume for primary audio zone and group")
                .that(mCarAudioService.getGroupMinVolume(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isEqualTo(0);
    }

    @Test
    public void getGroupCurrentVolume_forPrimaryZone() {
        mCarAudioService.init();

        expectWithMessage("Current group volume for primary audio zone and group")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0))
                .isEqualTo((DEFAULT_GAIN - MIN_GAIN) / STEP_SIZE);
    }

    @Test
    public void getGroupMaxVolume_withNoDynamicRouting() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        nonDynamicAudioService.getGroupMaxVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0);

        verify(mAudioManager).getStreamMaxVolume(
                CarAudioDynamicRouting.STREAM_TYPES[TEST_PRIMARY_ZONE_GROUP_0]);
    }

    @Test
    public void getGroupMinVolume_withNoDynamicRouting() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        nonDynamicAudioService.getGroupMinVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0);

        verify(mAudioManager).getStreamMinVolume(
                CarAudioDynamicRouting.STREAM_TYPES[TEST_PRIMARY_ZONE_GROUP_0]);
    }

    @Test
    public void getGroupCurrentVolume_withNoDynamicRouting() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        nonDynamicAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0);

        verify(mAudioManager).getStreamVolume(
                CarAudioDynamicRouting.STREAM_TYPES[TEST_PRIMARY_ZONE_GROUP_0]);
    }

    @Test
    public void setBalanceTowardRight_nonNullValue() {
        mCarAudioService.init();

        mCarAudioService.setBalanceTowardRight(TEST_VALUE);

        verify(mAudioControlWrapperAidl).setBalanceTowardRight(TEST_VALUE);
    }

    @Test
    public void setBalanceTowardRight_throws() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, ()
                -> mCarAudioService.setBalanceTowardRight(INVALID_TEST_VALUE));

        expectWithMessage("Out of bounds balance")
                .that(thrown).hasMessageThat()
                .contains(String.format("Balance is out of range of [%f, %f]", -1f, 1f));
    }

    @Test
    public void setFadeTowardFront_nonNullValue() {
        mCarAudioService.init();

        mCarAudioService.setFadeTowardFront(TEST_VALUE);

        verify(mAudioControlWrapperAidl).setFadeTowardFront(TEST_VALUE);
    }

    @Test
    public void setFadeTowardFront_throws() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, ()
                -> mCarAudioService.setFadeTowardFront(INVALID_TEST_VALUE));

        expectWithMessage("Out of bounds fade")
                .that(thrown).hasMessageThat()
                .contains(String.format("Fade is out of range of [%f, %f]", -1f, 1f));
    }

    @Test
    public void isAudioFeatureEnabled_forDynamicRouting() {
        mCarAudioService.init();

        expectWithMessage("Dynamic routing audio feature")
                .that(mCarAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_DYNAMIC_ROUTING))
                .isEqualTo(mUseDynamicRouting);
    }

    @Test
    public void isAudioFeatureEnabled_forDisabledDynamicRouting() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        expectWithMessage("Disabled dynamic routing audio feature")
                .that(nonDynamicAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_DYNAMIC_ROUTING))
                .isFalse();
    }

    @Test
    public void isAudioFeatureEnabled_forVolumeGroupMuting() {
        mCarAudioService.init();

        expectWithMessage("Group muting audio feature")
                .that(mCarAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_VOLUME_GROUP_MUTING))
                .isEqualTo(mUseCarVolumeGroupMuting);
    }

    @Test
    public void isAudioFeatureEnabled_forDisabledVolumeGroupMuting() {
        when(mMockResources.getBoolean(audioUseCarVolumeGroupMuting)).thenReturn(false);
        CarAudioService nonVolumeGroupMutingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonVolumeGroupMutingAudioService.init();

        expectWithMessage("Disabled group muting audio feature")
                .that(nonVolumeGroupMutingAudioService
                        .isAudioFeatureEnabled(AUDIO_FEATURE_VOLUME_GROUP_MUTING))
                .isFalse();
    }

    @Test
    public void isAudioFeatureEnabled_forVolumeGroupEvent() {
        mCarAudioService.init();

        expectWithMessage("Group events audio feature")
                .that(mCarAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_VOLUME_GROUP_EVENTS))
                .isEqualTo(mUseCarVolumeGroupEvents);
    }

    @Test
    public void isAudioFeatureEnabled_forDisabledVolumeGroupEvent() {
        when(mMockResources.getBoolean(audioUseCarVolumeGroupEvent)).thenReturn(false);
        CarAudioService nonVolumeGroupEventsAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonVolumeGroupEventsAudioService.init();

        expectWithMessage("Disabled group event audio feature")
                .that(nonVolumeGroupEventsAudioService
                        .isAudioFeatureEnabled(AUDIO_FEATURE_VOLUME_GROUP_EVENTS))
                .isFalse();
    }

    @Test
    public void isAudioFeatureEnabled_forUnrecognizableAudioFeature_throws() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioService.isAudioFeatureEnabled(INVALID_AUDIO_FEATURE));

        expectWithMessage("Unknown audio feature")
                .that(thrown).hasMessageThat()
                .contains("Unknown Audio Feature type: " + INVALID_AUDIO_FEATURE);
    }

    @Test
    public void isAudioFeatureEnabled_forDisabledOemService() {
        mCarAudioService.init();

        boolean isEnabled =
                mCarAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_OEM_AUDIO_SERVICE);

        expectWithMessage("Oem service enabled with disabled oem service")
                .that(isEnabled).isFalse();
    }

    @Test
    public void isAudioFeatureEnabled_withEnabledFocusService() {
        CarOemAudioFocusProxyService service = mock(CarOemAudioFocusProxyService.class);
        when(mMockCarOemProxyService.isOemServiceEnabled()).thenReturn(true);
        when(mMockCarOemProxyService.getCarOemAudioFocusService()).thenReturn(service);
        mCarAudioService.init();

        boolean isEnabled =
                mCarAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_OEM_AUDIO_SERVICE);

        expectWithMessage("Oem service enabled with enabled focus service")
                .that(isEnabled).isTrue();
    }

    @Test
    public void isAudioFeatureEnabled_withEnabledFocusServiceAndReleaseLessThanU() {
        mockCarGetPlatformVersion(TIRAMISU_1);
        CarOemAudioFocusProxyService service = mock(CarOemAudioFocusProxyService.class);
        when(mMockCarOemProxyService.isOemServiceEnabled()).thenReturn(true);
        when(mMockCarOemProxyService.getCarOemAudioFocusService()).thenReturn(service);
        mCarAudioService.init();

        boolean isEnabled =
                mCarAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_OEM_AUDIO_SERVICE);

        expectWithMessage("Oem service enabled with release less than U")
                .that(isEnabled).isFalse();
    }

    @Test
    public void isAudioFeatureEnabled_withEnabledVolumeService() {
        CarOemAudioVolumeProxyService service = mock(CarOemAudioVolumeProxyService.class);
        when(mMockCarOemProxyService.isOemServiceEnabled()).thenReturn(true);
        when(mMockCarOemProxyService.getCarOemAudioVolumeService()).thenReturn(service);
        mCarAudioService.init();

        boolean isEnabled =
                mCarAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_OEM_AUDIO_SERVICE);

        expectWithMessage("Oem service enabled with enabled volume service")
                .that(isEnabled).isTrue();
    }

    @Test
    public void isAudioFeatureEnabled_withEnabledDuckingService() {
        CarOemAudioDuckingProxyService service = mock(CarOemAudioDuckingProxyService.class);
        when(mMockCarOemProxyService.isOemServiceEnabled()).thenReturn(true);
        when(mMockCarOemProxyService.getCarOemAudioDuckingService()).thenReturn(service);
        mCarAudioService.init();

        boolean isEnabled =
                mCarAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_OEM_AUDIO_SERVICE);

        expectWithMessage("Oem service enabled with enabled ducking service")
                .that(isEnabled).isTrue();
    }

    @Test
    public void isAudioFeatureEnabled_withEnabledAudioMirror() {
        mCarAudioService.init();

        boolean isEnabled = mCarAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_AUDIO_MIRRORING);

        expectWithMessage("Audio mirror enabled status")
                .that(isEnabled).isTrue();
    }

    @Test
    public void isAudioFeatureEnabled_withDisabledAudioMirror() {
        CarAudioService carAudioService = getCarAudioServiceWithoutMirroring();
        carAudioService.init();

        boolean isEnabled = carAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_AUDIO_MIRRORING);

        expectWithMessage("Audio mirror enabled status")
                .that(isEnabled).isFalse();
    }

    @Test
    public void onOccupantZoneConfigChanged_noUserAssignedToPrimaryZone() throws Exception {
        mCarAudioService.init();
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(UserManagerHelper.USER_NULL);
        when(mMockOccupantZoneService.getUserForOccupant(anyInt()))
                .thenReturn(UserManagerHelper.USER_NULL);
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();
        int prevUserId = mCarAudioService.getUserIdForZone(PRIMARY_AUDIO_ZONE);

        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        expectWithMessage("User ID before config changed")
                .that(mCarAudioService.getUserIdForZone(PRIMARY_AUDIO_ZONE))
                .isEqualTo(prevUserId);
    }

    @Test
    public void onOccupantZoneConfigChanged_userAssignedToPrimaryZone() throws Exception {
        mCarAudioService.init();
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(TEST_DRIVER_USER_ID);
        when(mMockOccupantZoneService.getUserForOccupant(anyInt()))
                .thenReturn(TEST_REAR_LEFT_USER_ID);
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();

        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        expectWithMessage("User ID after config changed")
                .that(mCarAudioService.getUserIdForZone(PRIMARY_AUDIO_ZONE))
                .isEqualTo(TEST_REAR_LEFT_USER_ID);
    }

    @Test
    public void onOccupantZoneConfigChanged_afterResettingUser_returnNoUser() throws Exception {
        mCarAudioService.init();
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(TEST_DRIVER_USER_ID);
        when(mMockOccupantZoneService.getUserForOccupant(anyInt()))
                .thenReturn(TEST_REAR_LEFT_USER_ID);
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();
        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        when(mMockOccupantZoneService.getUserForOccupant(anyInt()))
                .thenReturn(UserManagerHelper.USER_NULL);

        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        expectWithMessage("User ID config changed to null")
                .that(mCarAudioService.getUserIdForZone(PRIMARY_AUDIO_ZONE))
                .isEqualTo(UserManagerHelper.USER_NULL);
    }

    @Test
    public void onOccupantZoneConfigChanged_noOccupantZoneMapping() throws Exception {
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();

        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        verify(mMockOccupantZoneService, never()).getUserForOccupant(anyInt());
    }

    @Test
    public void onOccupantZoneConfigChanged_noOccupantZoneMapping_alreadyAssigned()
            throws Exception {
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(TEST_DRIVER_USER_ID);
        noZoneMappingAudioService.init();
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();
        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        verify(mMockOccupantZoneService, never()).getUserForOccupant(anyInt());
        expectWithMessage("Occupant Zone for primary zone")
                .that(noZoneMappingAudioService.getUserIdForZone(PRIMARY_AUDIO_ZONE))
                .isEqualTo(TEST_DRIVER_USER_ID);
    }

    @Test
    public void onOccupantZoneConfigChanged_multipleZones() throws Exception {
        mCarAudioService.init();
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(TEST_DRIVER_USER_ID);
        when(mMockOccupantZoneService.getUserForOccupant(anyInt()))
                .thenReturn(TEST_REAR_LEFT_USER_ID, TEST_REAR_RIGHT_USER_ID);
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();

        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        expectWithMessage("User ID for primary and secondary zone after config changed")
                .that(mCarAudioService.getUserIdForZone(PRIMARY_AUDIO_ZONE))
                .isNotEqualTo(mCarAudioService.getUserIdForZone(TEST_REAR_LEFT_ZONE_ID));
        expectWithMessage("Secondary user ID config changed")
                .that(mCarAudioService.getUserIdForZone(TEST_REAR_LEFT_ZONE_ID))
                .isEqualTo(TEST_REAR_RIGHT_USER_ID);
    }

    @Test
    public void serviceDied_registersAudioGainCallback() {
        mCarAudioService.init();
        ArgumentCaptor<AudioControlDeathRecipient> captor =
                ArgumentCaptor.forClass(AudioControlDeathRecipient.class);
        verify(mAudioControlWrapperAidl).linkToDeath(captor.capture());
        AudioControlDeathRecipient runnable = captor.getValue();
        reset(mAudioControlWrapperAidl);

        runnable.serviceDied();

        verify(mAudioControlWrapperAidl).registerAudioGainCallback(any());
    }

    @Test
    public void serviceDied_registersFocusListener() {
        mCarAudioService.init();
        ArgumentCaptor<AudioControlDeathRecipient> captor =
                ArgumentCaptor.forClass(AudioControlDeathRecipient.class);
        verify(mAudioControlWrapperAidl).linkToDeath(captor.capture());
        AudioControlDeathRecipient runnable = captor.getValue();
        reset(mAudioControlWrapperAidl);

        runnable.serviceDied();

        verify(mAudioControlWrapperAidl).registerFocusListener(any());
    }

    @Test
    public void serviceDied_setsModuleChangeCallback() {
        mCarAudioService.init();
        ArgumentCaptor<AudioControlDeathRecipient> captor =
                ArgumentCaptor.forClass(AudioControlDeathRecipient.class);
        verify(mAudioControlWrapperAidl).linkToDeath(captor.capture());
        AudioControlDeathRecipient runnable = captor.getValue();
        reset(mAudioControlWrapperAidl);

        runnable.serviceDied();

        verify(mAudioControlWrapperAidl).setModuleChangeCallback(any());
    }

    private ICarOccupantZoneCallback getOccupantZoneCallback() {
        ArgumentCaptor<ICarOccupantZoneCallback> captor =
                ArgumentCaptor.forClass(ICarOccupantZoneCallback.class);
        verify(mMockOccupantZoneService).registerCallback(captor.capture());
        return captor.getValue();
    }

    @Test
    public void getVolumeGroupIdForAudioContext_forPrimaryGroup() {
        mCarAudioService.init();

        expectWithMessage("Volume group ID for primary audio zone")
                .that(mCarAudioService.getVolumeGroupIdForAudioContext(PRIMARY_AUDIO_ZONE,
                        CarAudioContext.MUSIC))
                .isEqualTo(TEST_PRIMARY_ZONE_GROUP_0);
    }

    @Test
    public void getVolumeGroupIdForAudioAttribute() {
        mCarAudioService.init();

        expectWithMessage("Volume group ID for primary audio zone")
                .that(mCarAudioService.getVolumeGroupIdForAudioAttribute(PRIMARY_AUDIO_ZONE,
                        CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA)))
                .isEqualTo(TEST_PRIMARY_ZONE_GROUP_0);
    }

    @Test
    public void getVolumeGroupIdForAudioAttribute_withNullAttribute_fails() {
        mCarAudioService.init();

        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                mCarAudioService.getVolumeGroupIdForAudioAttribute(PRIMARY_AUDIO_ZONE,
                /* attribute= */ null));

        expectWithMessage("Null audio attribute exception").that(thrown).hasMessageThat()
                .contains("Audio attributes");
    }

    @Test
    public void getVolumeGroupIdForAudioAttribute_withInvalidZoneId_fails() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                mCarAudioService.getVolumeGroupIdForAudioAttribute(INVALID_AUDIO_ZONE,
                        CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA)));

        expectWithMessage("Invalid audio zone exception").that(thrown).hasMessageThat()
                .contains("Invalid audio zone Id");
    }

    @Test
    public void getInputDevicesForZoneId_primaryZone() {
        mCarAudioService.init();

        expectWithMessage("Get input device for primary zone id")
                .that(mCarAudioService.getInputDevicesForZoneId(PRIMARY_AUDIO_ZONE))
                .containsExactly(new AudioDeviceAttributes(mMicrophoneInputDevice));
    }

    @Test
    public void getExternalSources_forSingleDevice() {
        mCarAudioService.init();
        AudioDeviceInfo[] inputDevices = generateInputDeviceInfos();

        expectWithMessage("External input device addresses")
                .that(mCarAudioService.getExternalSources())
                .asList().containsExactly(inputDevices[1].getAddress());
    }

    @Test
    public void setAudioEnabled_forEnabledVolumeGroupMuting() {
        mCarAudioService.init();

        mCarAudioService.setAudioEnabled(/* enabled= */ true);

        verify(mAudioControlWrapperAidl).onDevicesToMuteChange(any());
    }

    @Test
    public void setAudioEnabled_forDisabledVolumeGroupMuting() {
        when(mMockResources.getBoolean(audioUseCarVolumeGroupMuting)).thenReturn(false);
        CarAudioService nonVolumeGroupMutingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonVolumeGroupMutingAudioService.init();

        nonVolumeGroupMutingAudioService.setAudioEnabled(/* enabled= */ true);

        verify(mAudioControlWrapperAidl, never()).onDevicesToMuteChange(any());
    }

    @Test
    public void registerVolumeCallback_verifyCallbackHandler() {
        int uid = Binder.getCallingUid();
        mCarAudioService.init();

        mCarAudioService.registerVolumeCallback(mVolumeCallbackBinder);

        verify(mCarVolumeCallbackHandler).registerCallback(mVolumeCallbackBinder, uid, true);
    }

    @Test
    public void unregisterVolumeCallback_verifyCallbackHandler() {
        int uid = Binder.getCallingUid();
        mCarAudioService.init();

        mCarAudioService.unregisterVolumeCallback(mVolumeCallbackBinder);

        verify(mCarVolumeCallbackHandler).unregisterCallback(mVolumeCallbackBinder, uid);
    }

    @Test
    public void getMutedVolumeGroups_forInvalidZone() {
        mCarAudioService.init();

        expectWithMessage("Muted volume groups for invalid zone")
                .that(mCarAudioService.getMutedVolumeGroups(INVALID_AUDIO_ZONE))
                .isEmpty();
    }

    @Test
    public void getMutedVolumeGroups_whenVolumeGroupMuteNotSupported() {
        when(mMockResources.getBoolean(audioUseCarVolumeGroupMuting)).thenReturn(false);
        CarAudioService nonVolumeGroupMutingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonVolumeGroupMutingAudioService.init();

        expectWithMessage("Muted volume groups with disable mute feature")
                .that(nonVolumeGroupMutingAudioService.getMutedVolumeGroups(PRIMARY_AUDIO_ZONE))
                .isEmpty();
    }

    @Test
    public void getMutedVolumeGroups_withMutedGroups() {
        mCarAudioService.init();
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                /* muted= */ true, TEST_FLAGS);
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_1,
                /* muted= */ true, TEST_FLAGS);

        expectWithMessage("Muted volume groups")
                .that(mCarAudioService.getMutedVolumeGroups(PRIMARY_AUDIO_ZONE))
                .containsExactly(TEST_PRIMARY_ZONE_VOLUME_INFO_0, TEST_PRIMARY_ZONE_VOLUME_INFO_1);
    }

    @Test
    public void getMutedVolumeGroups_afterUnmuting() {
        mCarAudioService.init();
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                /* muted= */ true, TEST_FLAGS);
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_1,
                /* muted= */ true, TEST_FLAGS);
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                /* muted= */ false, TEST_FLAGS);

        expectWithMessage("Muted volume groups after unmuting one group")
                .that(mCarAudioService.getMutedVolumeGroups(PRIMARY_AUDIO_ZONE))
                .containsExactly(TEST_PRIMARY_ZONE_VOLUME_INFO_1);
    }

    @Test
    public void getMutedVolumeGroups_withMutedGroupsForDifferentZone() {
        mCarAudioService.init();
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                /* muted= */ true, TEST_FLAGS);
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_1,
                /* muted= */ true, TEST_FLAGS);

        expectWithMessage("Muted volume groups for secondary zone")
                .that(mCarAudioService.getMutedVolumeGroups(TEST_REAR_LEFT_ZONE_ID)).isEmpty();
    }

    @Test
    public void onReceive_forLegacy_noCallToOnVolumeGroupChanged() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();
        mVolumeReceiverCaptor = ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext).registerReceiver(mVolumeReceiverCaptor.capture(), any(), anyInt());
        BroadcastReceiver receiver = mVolumeReceiverCaptor.getValue();
        Intent intent = new Intent(VOLUME_CHANGED_ACTION);

        receiver.onReceive(mMockContext, intent);

        verify(mCarVolumeCallbackHandler, never())
                .onVolumeGroupChange(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onReceive_forLegacy_forStreamMusic() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();
        verify(mMockContext).registerReceiver(mVolumeReceiverCaptor.capture(), any(), anyInt());
        BroadcastReceiver receiver = mVolumeReceiverCaptor.getValue();
        Intent intent = new Intent(VOLUME_CHANGED_ACTION)
                .putExtra(EXTRA_VOLUME_STREAM_TYPE, STREAM_MUSIC);

        receiver.onReceive(mMockContext, intent);

        verify(mCarVolumeCallbackHandler).onVolumeGroupChange(
                eq(PRIMARY_AUDIO_ZONE), anyInt(), eq(FLAG_FROM_KEY | FLAG_SHOW_UI));
    }

    @Test
    public void onReceive_forLegacy_onMuteChanged() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();
        ArgumentCaptor<BroadcastReceiver> captor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext).registerReceiver(captor.capture(), any(), anyInt());
        BroadcastReceiver receiver = captor.getValue();
        Intent intent = new Intent();
        intent.setAction(MASTER_MUTE_CHANGED_ACTION);

        receiver.onReceive(mMockContext, intent);

        verify(mCarVolumeCallbackHandler)
                .onMasterMuteChanged(eq(PRIMARY_AUDIO_ZONE), eq(FLAG_FROM_KEY | FLAG_SHOW_UI));
    }

    @Test
    public void getVolumeGroupInfosForZone() {
        mCarAudioService.init();
        int groupCount = mCarAudioService.getVolumeGroupCount(PRIMARY_AUDIO_ZONE);

        List<CarVolumeGroupInfo> infos =
                mCarAudioService.getVolumeGroupInfosForZone(PRIMARY_AUDIO_ZONE);

        for (int index = 0; index < groupCount; index++) {
            CarVolumeGroupInfo info = mCarAudioService
                    .getVolumeGroupInfo(PRIMARY_AUDIO_ZONE, index);
            expectWithMessage("Car volume group infos for primary zone and info %s", info)
                    .that(infos).contains(info);
        }
    }

    @Test
    public void getVolumeGroupInfosForZone_forDynamicRoutingDisabled() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        List<CarVolumeGroupInfo> infos =
                nonDynamicAudioService.getVolumeGroupInfosForZone(PRIMARY_AUDIO_ZONE);

        expectWithMessage("Car volume group infos with dynamic routing disabled")
                .that(infos).isEmpty();
    }

    @Test
    public void getVolumeGroupInfosForZone_size() {
        mCarAudioService.init();
        int groupCount = mCarAudioService.getVolumeGroupCount(PRIMARY_AUDIO_ZONE);

        List<CarVolumeGroupInfo> infos =
                mCarAudioService.getVolumeGroupInfosForZone(PRIMARY_AUDIO_ZONE);

        expectWithMessage("Car volume group infos size for primary zone")
                .that(infos).hasSize(groupCount);
    }

    @Test
    public void getVolumeGroupInfosForZone_forInvalidZone() {
        mCarAudioService.init();

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () ->
                        mCarAudioService.getVolumeGroupInfosForZone(INVALID_AUDIO_ZONE));

        expectWithMessage("Exception for volume group infos size for invalid zone")
                .that(thrown).hasMessageThat().contains("audio zone Id");
    }

    @Test
    public void getVolumeGroupInfo() {
        CarVolumeGroupInfo testVolumeGroupInfo = new CarVolumeGroupInfo.Builder(
                TEST_PRIMARY_ZONE_VOLUME_INFO_0).setMuted(false).build();
        mCarAudioService.init();

        expectWithMessage("Car volume group info for primary zone")
                .that(mCarAudioService.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isEqualTo(testVolumeGroupInfo);
    }

    @Test
    public void getVolumeGroupInfo_forInvalidZone() {
        mCarAudioService.init();

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () ->
                        mCarAudioService.getVolumeGroupInfo(INVALID_AUDIO_ZONE,
                                TEST_PRIMARY_ZONE_GROUP_0));

        expectWithMessage("Exception for volume group info size for invalid zone")
                .that(thrown).hasMessageThat().contains("audio zone Id");
    }

    @Test
    public void getVolumeGroupInfo_forInvalidGroup() {
        mCarAudioService.init();

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () ->
                        mCarAudioService.getVolumeGroupInfo(INVALID_AUDIO_ZONE,
                                TEST_PRIMARY_ZONE_GROUP_0));

        expectWithMessage("Exception for volume groups info size for invalid group id")
                .that(thrown).hasMessageThat().contains("audio zone Id");
    }

    @Test
    public void getVolumeGroupInfo_forGroupOverRange() {
        mCarAudioService.init();
        int groupCount = mCarAudioService.getVolumeGroupCount(PRIMARY_AUDIO_ZONE);

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () ->
                        mCarAudioService.getVolumeGroupInfo(INVALID_AUDIO_ZONE,
                                groupCount));

        expectWithMessage("Exception for volume groups info size for out of range group")
                .that(thrown).hasMessageThat().contains("audio zone Id");
    }

    @Test
    public void registerPrimaryZoneMediaAudioRequestCallbackListener_withNullCallback_fails() {
        mCarAudioService.init();

        NullPointerException thrown = assertThrows(NullPointerException.class, ()
                -> mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(
                        /* callback= */ null));

        expectWithMessage("Register audio media request callback exception")
                .that(thrown).hasMessageThat()
                .contains("Media request callback");
    }

    @Test
    public void unregisterPrimaryZoneMediaAudioRequestCallback_withNullCallback_fails() {
        mCarAudioService.init();

        NullPointerException thrown = assertThrows(NullPointerException.class, ()
                -> mCarAudioService.unregisterPrimaryZoneMediaAudioRequestCallback(
                        /* callback= */ null));

        expectWithMessage("Unregister audio media request callback exception")
                .that(thrown).hasMessageThat()
                .contains("Media request callback");
    }

    @Test
    public void requestMediaAudioOnPrimaryZone_withPassengerOccupant_succeeds()
            throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);

        expectWithMessage("Audio media request id")
                .that(mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                        TEST_REAR_RIGHT_PASSENGER_OCCUPANT))
                .isNotEqualTo(INVALID_REQUEST_ID);
    }

    @Test
    public void requestMediaAudioOnPrimaryZone_withDriverOccupant_fails()
            throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, ()
                -> mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_DRIVER_OCCUPANT));

        expectWithMessage("Request media audio exception")
                .that(thrown).hasMessageThat().contains("already owns the primary audio zone");
    }

    @Test
    public void requestMediaAudioOnPrimaryZone_withNonAssignedOccupant_fails()
            throws Exception {
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(TEST_UNASSIGNED_OCCUPANT_ZONE_ID))
                .thenReturn(OUT_OF_RANGE_ZONE);
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        CarOccupantZoneManager.OccupantZoneInfo info =
                getOccupantInfo(TEST_UNASSIGNED_OCCUPANT_ZONE_ID,
                CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER,
                VehicleAreaSeat.SEAT_ROW_1_LEFT);
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);

        expectWithMessage("Invalid audio media request id")
                .that(mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback, info))
                .isEqualTo(INVALID_REQUEST_ID);
    }

    @Test
    public void requestMediaAudioOnPrimaryZone_withPassengerOccupant_callsApprover()
            throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);

        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);

        requestToken.waitForCallback();
        expectWithMessage("Called audio media request id")
                .that(requestToken.mRequestId).isEqualTo(requestId);
        expectWithMessage("Called audio media request info")
                .that(requestToken.mInfo).isEqualTo(TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
    }

    @Test
    public void requestMediaAudioOnPrimaryZone_withZoneMirroring_fails()
            throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        TestAudioZonesMirrorStatusCallbackCallback mirrorCallback =
                getAudioZonesMirrorStatusCallback();
        mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);
        mirrorCallback.waitForCallback();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                        mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                        TEST_REAR_RIGHT_PASSENGER_OCCUPANT));

        expectWithMessage("Request audio share while mirroring exception").that(thrown)
                .hasMessageThat().contains("Can not request audio share to primary zone");
    }

    @Test
    public void allowMediaAudioOnPrimaryZone_withAllowedRequest() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();

        boolean results = mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ true);

        expectWithMessage("Allowed audio playback").that(results).isTrue();
    }

    @Test
    public void allowMediaAudioOnPrimaryZone_whileMirroring_fails() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long shareId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        TestAudioZonesMirrorStatusCallbackCallback mirrorCallback =
                getAudioZonesMirrorStatusCallback();
        mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);
        mirrorCallback.waitForCallback();
        requestCallback.waitForCallback();

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> mCarAudioService
                        .allowMediaAudioOnPrimaryZone(requestToken, shareId, /* allowed= */ true));

        expectWithMessage("Allow audio share while mirroring exception").that(thrown)
                .hasMessageThat().contains("Can not allow audio share to primary zone");
        requestCallback.waitForCallback();
        expectWithMessage("Rejected status due to mirroring").that(requestCallback.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_REJECTED);
        expectWithMessage("Rejected id with rejected due to mirroring")
                .that(requestCallback.mRequestId).isEqualTo(shareId);
    }

    @Test
    public void allowMediaAudioOnPrimaryZone_withUnallowedRequest() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();

        boolean results = mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ false);

        expectWithMessage("Unallowed audio playback").that(results).isTrue();
    }

    @Test
    public void allowMediaAudioOnPrimaryZone_withAllowedRequest_callsRequester() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();

        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ true);

        requestCallback.waitForCallback();
        expectWithMessage("Media request called audio media request id")
                .that(requestCallback.mRequestId).isEqualTo(requestId);
        expectWithMessage("Media request called audio media request info")
                .that(requestCallback.mInfo).isEqualTo(TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        expectWithMessage("Media request called audio media request status")
                .that(requestCallback.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_APPROVED);
    }

    @Test
    public void allowMediaAudioOnPrimaryZone_withAllowedRequest_callsApprover() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestApprover = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestApprover);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestApprover.waitForCallback();
        requestApprover.reset();

        mCarAudioService.allowMediaAudioOnPrimaryZone(requestApprover, requestId,
                /* allowed= */ true);

        requestApprover.waitForCallback();
        expectWithMessage("Media approver called audio media request id")
                .that(requestApprover.mRequestId).isEqualTo(requestId);
        expectWithMessage("Media approver called audio media request info")
                .that(requestApprover.mInfo).isEqualTo(TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        expectWithMessage("Media approver called audio media request status")
                .that(requestApprover.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_APPROVED);
    }

    @Test
    public void allowMediaAudioOnPrimaryZone_withUnallowedRequest_callsRequester()
            throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();

        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ false);

        requestCallback.waitForCallback();
        expectWithMessage("Unallowed media request called audio media request id")
                .that(requestCallback.mRequestId).isEqualTo(requestId);
        expectWithMessage("Unallowed media request called audio media request info")
                .that(requestCallback.mInfo).isEqualTo(TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        expectWithMessage("Unallowed media request called audio media request status")
                .that(requestCallback.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_REJECTED);
    }

    @Test
    public void allowMediaAudioOnPrimaryZone_withUnallowedRequest_callsApprover() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestApprover = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestApprover);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestApprover.waitForCallback();
        requestApprover.reset();

        mCarAudioService.allowMediaAudioOnPrimaryZone(requestApprover, requestId,
                /* allowed= */ false);

        requestApprover.waitForCallback();
        expectWithMessage("Unallowed media approver called audio media request id")
                .that(requestApprover.mRequestId).isEqualTo(requestId);
        expectWithMessage("Unallowed approver token called audio media request info")
                .that(requestApprover.mInfo).isEqualTo(TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        expectWithMessage("Unallowed approver token called audio media request status")
                .that(requestApprover.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_REJECTED);
    }

    @Test
    public void isMediaAudioAllowedInPrimaryZone_witNullOccupant_fails() throws Exception {
        mCarAudioService.init();
        NullPointerException thrown = assertThrows(NullPointerException.class, ()
                -> mCarAudioService.isMediaAudioAllowedInPrimaryZone(/* occupantZoneInfo= */ null));

        expectWithMessage("Media status exception").that(thrown)
                .hasMessageThat().contains("Occupant zone info");
    }

    @Test
    public void isMediaAudioAllowedInPrimaryZone_byDefault() throws Exception {
        mCarAudioService.init();

        expectWithMessage("Media default status")
                .that(mCarAudioService.isMediaAudioAllowedInPrimaryZone(
                        TEST_REAR_RIGHT_PASSENGER_OCCUPANT))
                .isFalse();
    }

    @Test
    public void isMediaAudioAllowedInPrimaryZone_afterAllowed() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ true);
        requestToken.waitForCallback();

        expectWithMessage("Media allowed status")
                .that(mCarAudioService.isMediaAudioAllowedInPrimaryZone(
                        TEST_REAR_RIGHT_PASSENGER_OCCUPANT))
                .isTrue();
    }

    @Test
    public void isMediaAudioAllowedInPrimaryZone_afterDisallowed() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ false);
        requestToken.waitForCallback();

        expectWithMessage("Media after disallowed status")
                .that(mCarAudioService.isMediaAudioAllowedInPrimaryZone(
                        TEST_REAR_RIGHT_PASSENGER_OCCUPANT))
                .isFalse();
    }

    @Test
    public void resetMediaAudioOnPrimaryZone_afterAllowed() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ true);
        requestToken.waitForCallback();
        requestToken.reset();

        boolean reset = mCarAudioService.resetMediaAudioOnPrimaryZone(
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);

        requestToken.waitForCallback();
        expectWithMessage("Reset status").that(reset).isTrue();
        expectWithMessage("Media reset status")
                .that(mCarAudioService.isMediaAudioAllowedInPrimaryZone(
                        TEST_REAR_RIGHT_PASSENGER_OCCUPANT))
                .isFalse();
    }

    @Test
    public void cancelMediaAudioOnPrimaryZone_beforeAllowed() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();

        boolean cancel = mCarAudioService.cancelMediaAudioOnPrimaryZone(requestId);

        requestToken.waitForCallback();
        expectWithMessage("Cancel status").that(cancel).isTrue();
        expectWithMessage("Canceled media token called audio media request id")
                .that(requestToken.mRequestId).isEqualTo(requestId);
        expectWithMessage("Canceled media token called audio media request info")
                .that(requestToken.mInfo).isEqualTo(TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        expectWithMessage("Canceled media token called audio media request status")
                .that(requestToken.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_CANCELLED);
    }

    @Test
    public void cancelMediaAudioOnPrimaryZone_afterAllowed() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ true);
        requestToken.waitForCallback();
        requestToken.reset();

        boolean cancel = mCarAudioService.cancelMediaAudioOnPrimaryZone(requestId);

        requestToken.waitForCallback();
        expectWithMessage("Cancel status after allowed").that(cancel).isTrue();
        expectWithMessage("Media allowed status after canceled")
                .that(mCarAudioService.isMediaAudioAllowedInPrimaryZone(
                        TEST_REAR_RIGHT_PASSENGER_OCCUPANT))
                .isFalse();
    }

    @Test
    public void getZoneIdForAudioFocusInfo_beforeAllowedSharedAudio() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();

        expectWithMessage("Not yet shared media user zone")
                .that(mCarAudioService.getZoneIdForAudioFocusInfo(TEST_REAR_RIGHT_AUDIO_FOCUS_INFO))
                .isEqualTo(TEST_REAR_RIGHT_ZONE_ID);
    }

    @Test
    public void getZoneIdForAudioFocusInfo_afterAllowedShareAudio() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId, /* allowed= */ true);
        requestToken.waitForCallback();

        expectWithMessage("Shared media user zone")
                .that(mCarAudioService.getZoneIdForAudioFocusInfo(TEST_REAR_RIGHT_AUDIO_FOCUS_INFO))
                .isEqualTo(PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void getZoneIdForAudioFocusInfo_afterCanceled() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ true);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.cancelMediaAudioOnPrimaryZone(requestId);
        requestToken.waitForCallback();

        expectWithMessage("Canceled shared media user zone")
                .that(mCarAudioService.getZoneIdForAudioFocusInfo(TEST_REAR_RIGHT_AUDIO_FOCUS_INFO))
                .isEqualTo(TEST_REAR_RIGHT_ZONE_ID);
    }

    @Test
    public void getZoneIdForAudioFocusInfo_afterReset() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ true);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.resetMediaAudioOnPrimaryZone(TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();

        expectWithMessage("Reset shared media user zone")
                .that(mCarAudioService.getZoneIdForAudioFocusInfo(TEST_REAR_RIGHT_AUDIO_FOCUS_INFO))
                .isEqualTo(TEST_REAR_RIGHT_ZONE_ID);
    }

    private static CarOccupantZoneManager.OccupantZoneInfo getOccupantInfo(int occupantZoneId,
            int occupantType, int seat) {
        return new CarOccupantZoneManager.OccupantZoneInfo(occupantZoneId, occupantType, seat);
    }

    @Test
    public void getAudioAttributesForVolumeGroup() {
        mCarAudioService.init();
        CarVolumeGroupInfo info = mCarAudioService.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_ZONE_GROUP_0);

        List<AudioAttributes> audioAttributes =
                mCarAudioService.getAudioAttributesForVolumeGroup(info);

        expectWithMessage("Volume group audio attributes").that(audioAttributes)
                .containsExactly(
                        CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA),
                        CarAudioContext.getAudioAttributeFromUsage(USAGE_GAME),
                        CarAudioContext.getAudioAttributeFromUsage(USAGE_UNKNOWN),
                        CarAudioContext.getAudioAttributeFromUsage(USAGE_NOTIFICATION),
                        CarAudioContext.getAudioAttributeFromUsage(USAGE_NOTIFICATION_EVENT),
                        CarAudioContext.getAudioAttributeFromUsage(USAGE_ANNOUNCEMENT));
    }

    @Test
    public void getAudioAttributesForVolumeGroup_withNullInfo_fails() {
        mCarAudioService.init();

        NullPointerException thrown =
                assertThrows(NullPointerException.class, () ->
                        mCarAudioService.getAudioAttributesForVolumeGroup(/* groupInfo= */ null));

        expectWithMessage("Volume group audio attributes with null info exception")
                .that(thrown).hasMessageThat().contains("Car volume group info");
    }

    @Test
    public void getAudioAttributesForVolumeGroup_withDynamicRoutingDisabled() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        List<AudioAttributes> audioAttributes = nonDynamicAudioService
                .getAudioAttributesForVolumeGroup(TEST_PRIMARY_ZONE_VOLUME_INFO_0);

        expectWithMessage("Volume group audio attributes with dynamic routing disabled")
                .that(audioAttributes).isEmpty();
    }

    @Test
    public void onKeyEvent_forInvalidAudioZone() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_ZONE_GROUP_0);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(INVALID_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_UNKNOWN);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Volume group volume after invalid audio zone")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isEqualTo(volumeBefore);
    }

    @Test
    public void onKeyEvent_forInvalidEvent() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_ZONE_GROUP_0);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_UNKNOWN);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Volume group volume after unknown key event")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isEqualTo(volumeBefore);
    }

    @Test
    public void onKeyEvent_forActionUp() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_ZONE_GROUP_0);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_UP, KEYCODE_VOLUME_UP);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Volume group volume after volume up in primary zone in primary group "
                + "for action up")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isEqualTo(volumeBefore);
    }

    @Test
    public void onKeyEvent_forActionDownFollowedByActionUp() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_ZONE_GROUP_0);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent actionDownKeyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_UP);
        KeyEvent actionUpKeyEvent = new KeyEvent(ACTION_UP, KEYCODE_VOLUME_UP);
        listener.onKeyEvent(actionDownKeyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        listener.onKeyEvent(actionUpKeyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Volume group volume after volume up in primary zone in primary group "
                + "for action down then action up")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isEqualTo(volumeBefore + 1);
    }

    @Test
    public void onKeyEvent_forVolumeUpEvent_inPrimaryZone() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_ZONE_GROUP_0);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_UP);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Volume group volume after volume up in primary zone in primary group")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isGreaterThan(volumeBefore);
    }

    @Test
    public void onKeyEvent_forVolumeDownEvent_inPrimaryZone() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_ZONE_GROUP_0);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_DOWN);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Volume group volume after volume down in primary zone in primary group")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isLessThan(volumeBefore);
    }

    @Test
    public void onKeyEvent_forVolumeDownEvent_inPrimaryZone_forSecondaryGroup() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_ZONE_GROUP_1);
        AudioPlaybackCallback callback = getCarAudioPlaybackCallback();
        callback.onPlaybackConfigChanged(List.of(new AudioPlaybackConfigurationBuilder()
                .setUsage(USAGE_ASSISTANT)
                .setDeviceAddress(VOICE_TEST_DEVICE)
                .build())
        );
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_DOWN);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Assistant volume group volume after volume down")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_1)).isLessThan(volumeBefore);
    }

    @Test
    public void onKeyEvent_forVolumeDownEvent_inPrimaryZone_withHigherPriority() {
        mCarAudioService.init();
        int primaryGroupVolumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_ZONE_GROUP_0);
        int voiceVolumeGroupBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_ZONE_GROUP_2);
        AudioPlaybackCallback callback = getCarAudioPlaybackCallback();
        callback.onPlaybackConfigChanged(List.of(
                new AudioPlaybackConfigurationBuilder()
                        .setUsage(USAGE_VOICE_COMMUNICATION)
                        .setDeviceAddress(CALL_TEST_DEVICE)
                        .build(),
                new AudioPlaybackConfigurationBuilder()
                        .setUsage(USAGE_MEDIA)
                        .setDeviceAddress(MEDIA_TEST_DEVICE)
                        .build())
        );
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_DOWN);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Media volume group volume after volume down")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isEqualTo(primaryGroupVolumeBefore);
        expectWithMessage("Call volume group volume after volume do")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_2)).isLessThan(voiceVolumeGroupBefore);
    }

    @Test
    public void onKeyEvent_forVolumeMuteEvent_inPrimaryZone() {
        mCarAudioService.init();
        boolean muteBefore = mCarAudioService.isVolumeGroupMuted(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_ZONE_GROUP_0);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_MUTE);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Volume group volume after volume mute")
                .that(mCarAudioService.isVolumeGroupMuted(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isNotEqualTo(muteBefore);
    }

    @Test
    public void onKeyEvent_forVolumeUpEvent_inSecondaryZone() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(TEST_REAR_LEFT_ZONE_ID,
                SECONDARY_ZONE_VOLUME_GROUP_ID);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(TEST_DRIVER_OCCUPANT_ZONE_ID);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(TEST_DRIVER_OCCUPANT_ZONE_ID))
                .thenReturn(TEST_REAR_LEFT_ZONE_ID);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_UP);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Secondary zone volume group after volume up")
                .that(mCarAudioService.getGroupVolume(TEST_REAR_LEFT_ZONE_ID,
                        SECONDARY_ZONE_VOLUME_GROUP_ID))
                .isGreaterThan(volumeBefore);
    }

    @Test
    public void onKeyEvent_forVolumeDownEvent_inSecondaryZone() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(TEST_REAR_LEFT_ZONE_ID,
                SECONDARY_ZONE_VOLUME_GROUP_ID);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(TEST_DRIVER_OCCUPANT_ZONE_ID);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(TEST_DRIVER_OCCUPANT_ZONE_ID))
                .thenReturn(TEST_REAR_LEFT_ZONE_ID);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_DOWN);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Secondary zone volume group after volume down")
                .that(mCarAudioService.getGroupVolume(TEST_REAR_LEFT_ZONE_ID,
                        SECONDARY_ZONE_VOLUME_GROUP_ID))
                .isLessThan(volumeBefore);
    }

    @Test
    public void onKeyEvent_forVolumeMuteEvent_inSecondaryZone() {
        mCarAudioService.init();
        boolean muteBefore = mCarAudioService.isVolumeGroupMuted(TEST_REAR_LEFT_ZONE_ID,
                SECONDARY_ZONE_VOLUME_GROUP_ID);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(TEST_DRIVER_OCCUPANT_ZONE_ID);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(TEST_DRIVER_OCCUPANT_ZONE_ID))
                .thenReturn(TEST_REAR_LEFT_ZONE_ID);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_MUTE);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Secondary zone volume group after volume mute")
                .that(mCarAudioService.isVolumeGroupMuted(TEST_REAR_LEFT_ZONE_ID,
                        SECONDARY_ZONE_VOLUME_GROUP_ID))
                .isNotEqualTo(muteBefore);
    }

    @Test
    public void onAudioDeviceGainsChanged_forPrimaryZone_changesVolume() {
        mCarAudioService.init();
        HalAudioGainCallback callback = getHalAudioGainCallback();
        CarAudioGainConfigInfo carGain = createCarAudioGainConfigInfo(PRIMARY_AUDIO_ZONE,
                MEDIA_TEST_DEVICE, TEST_GAIN_INDEX);

        callback.onAudioDeviceGainsChanged(List.of(Reasons.THERMAL_LIMITATION), List.of(carGain));

        expectWithMessage("New audio gains for primary zone")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isEqualTo(TEST_GAIN_INDEX);
    }

    @Test
    public void onAudioDeviceGainsChanged_forSecondaryZone_changesVolume() {
        mCarAudioService.init();
        HalAudioGainCallback callback = getHalAudioGainCallback();
        CarAudioGainConfigInfo carGain = createCarAudioGainConfigInfo(TEST_REAR_LEFT_ZONE_ID,
                SECONDARY_TEST_DEVICE_CONFIG_0, TEST_GAIN_INDEX);

        callback.onAudioDeviceGainsChanged(List.of(Reasons.THERMAL_LIMITATION), List.of(carGain));

        expectWithMessage("New audio gains for secondary zone")
                .that(mCarAudioService.getGroupVolume(TEST_REAR_LEFT_ZONE_ID,
                        TEST_PRIMARY_ZONE_GROUP_0)).isEqualTo(TEST_GAIN_INDEX);
    }

    @Test
    public void onAudioDeviceGainsChanged_forIncorrectDeviceAddress_sameVolume() {
        mCarAudioService.init();
        HalAudioGainCallback callback = getHalAudioGainCallback();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_ZONE_GROUP_0);
        CarAudioGainConfigInfo carGain = createCarAudioGainConfigInfo(PRIMARY_AUDIO_ZONE,
                SECONDARY_TEST_DEVICE_CONFIG_0, TEST_GAIN_INDEX);

        callback.onAudioDeviceGainsChanged(List.of(Reasons.THERMAL_LIMITATION), List.of(carGain));

        expectWithMessage("Same audio gains for primary zone")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isEqualTo(volumeBefore);
    }

    @Test
    public void onAudioDeviceGainsChanged_forMultipleZones_changesVolume() {
        mCarAudioService.init();
        HalAudioGainCallback callback = getHalAudioGainCallback();
        CarAudioGainConfigInfo primaryAudioZoneCarGain = createCarAudioGainConfigInfo(
                PRIMARY_AUDIO_ZONE, MEDIA_TEST_DEVICE, TEST_GAIN_INDEX);
        CarAudioGainConfigInfo secondaryAudioZoneCarGain = createCarAudioGainConfigInfo(
                TEST_REAR_LEFT_ZONE_ID, SECONDARY_TEST_DEVICE_CONFIG_0, TEST_GAIN_INDEX);

        callback.onAudioDeviceGainsChanged(List.of(Reasons.THERMAL_LIMITATION),
                List.of(primaryAudioZoneCarGain, secondaryAudioZoneCarGain));

        expectWithMessage("New audio gains for primary zone")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isEqualTo(TEST_GAIN_INDEX);
        expectWithMessage("New audio gains for secondary zone")
                .that(mCarAudioService.getGroupVolume(TEST_REAR_LEFT_ZONE_ID,
                        TEST_PRIMARY_ZONE_GROUP_0)).isEqualTo(TEST_GAIN_INDEX);
    }

    @Test
    public void onAudioPortsChanged_forMediaBus_changesVolumeRanges() {
        mCarAudioService.init();
        HalAudioModuleChangeCallback callback = getHalModuleChangeCallback();
        HalAudioDeviceInfo mediaBusDeviceInfo = createHalAudioDeviceInfo(
                TEST_MEDIA_PORT_ID, TEST_MEDIA_PORT_NAME, TEST_GAIN_MIN_VALUE, TEST_GAIN_MAX_VALUE,
                TEST_GAIN_DEFAULT_VALUE, TEST_GAIN_STEP_VALUE, OUT_DEVICE, MEDIA_TEST_DEVICE);
        CarVolumeGroupInfo volumeGroupInfoBefore =
                mCarAudioService.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0);

        callback.onAudioPortsChanged(List.of(mediaBusDeviceInfo));

        expectWithMessage("update audio port for media device")
                .that(mCarAudioService.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isNotEqualTo(volumeGroupInfoBefore);
    }

    @Test
    public void onAudioPortsChanged_forNavBus_changesVolumeRanges() {
        mCarAudioService.init();
        HalAudioModuleChangeCallback callback = getHalModuleChangeCallback();
        HalAudioDeviceInfo navBusDeviceInfo = createHalAudioDeviceInfo(
                TEST_NAV_PORT_ID, TEST_NAV_PORT_NAME, TEST_GAIN_MIN_VALUE, TEST_GAIN_MAX_VALUE,
                TEST_GAIN_DEFAULT_VALUE, TEST_GAIN_STEP_VALUE, OUT_DEVICE, NAVIGATION_TEST_DEVICE);
        CarVolumeGroupInfo volumeGroupInfoBefore =
                mCarAudioService.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_1);

        callback.onAudioPortsChanged(List.of(navBusDeviceInfo));

        expectWithMessage("update audio port for nav device")
                .that(mCarAudioService.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_1)).isNotEqualTo(volumeGroupInfoBefore);
    }

    @Test
    public void onAudioPortsChanged_forMultipleBuses_changesVolumeRanges() {
        mCarAudioService.init();
        HalAudioModuleChangeCallback callback = getHalModuleChangeCallback();
        HalAudioDeviceInfo mediaBusDeviceInfo = createHalAudioDeviceInfo(
                TEST_MEDIA_PORT_ID, TEST_MEDIA_PORT_NAME, TEST_GAIN_MIN_VALUE, TEST_GAIN_MAX_VALUE,
                TEST_GAIN_DEFAULT_VALUE, TEST_GAIN_STEP_VALUE, OUT_DEVICE, MEDIA_TEST_DEVICE);
        HalAudioDeviceInfo navBusDeviceInfo = createHalAudioDeviceInfo(
                TEST_NAV_PORT_ID, TEST_NAV_PORT_NAME, TEST_GAIN_MIN_VALUE, TEST_GAIN_MAX_VALUE,
                TEST_GAIN_DEFAULT_VALUE, TEST_GAIN_STEP_VALUE, OUT_DEVICE, NAVIGATION_TEST_DEVICE);
        CarVolumeGroupInfo mediaVolumeGroupInfoBefore =
                mCarAudioService.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0);
        CarVolumeGroupInfo navVolumeGroupInfoBefore =
                mCarAudioService.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_1);

        callback.onAudioPortsChanged(List.of(mediaBusDeviceInfo, navBusDeviceInfo));

        expectWithMessage("update audio port for media device")
                .that(mCarAudioService.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_0)).isNotEqualTo(mediaVolumeGroupInfoBefore);
        expectWithMessage("update audio port for nav device")
                .that(mCarAudioService.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE,
                        TEST_PRIMARY_ZONE_GROUP_1)).isNotEqualTo(navVolumeGroupInfoBefore);
    }

    @Test
    public void getActiveAudioAttributesForZone() {
        mCarAudioService.init();

        expectWithMessage("Default active audio attributes").that(
                mCarAudioService.getActiveAudioAttributesForZone(PRIMARY_AUDIO_ZONE)).isEmpty();
    }

    @Test
    public void getActiveAudioAttributesForZone_withActiveHalFocus() {
        when(mAudioManager.requestAudioFocus(any())).thenReturn(
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        mCarAudioService.init();
        requestHalAudioFocus(USAGE_ALARM);

        expectWithMessage("HAL active audio attributes")
                .that(mCarAudioService.getActiveAudioAttributesForZone(PRIMARY_AUDIO_ZONE))
                .containsExactly(new AudioAttributes.Builder().setUsage(USAGE_ALARM).build());
    }

    @Test
    public void getActiveAudioAttributesForZone_withActivePlayback() {
        mCarAudioService.init();
        mockActivePlayback();

        expectWithMessage("Playback active audio attributes")
                .that(mCarAudioService.getActiveAudioAttributesForZone(PRIMARY_AUDIO_ZONE))
                .containsExactly(new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build());
    }

    @Test
    public void getActiveAudioAttributesForZone_withActiveHalAndPlayback() {
        mCarAudioService.init();
        mockActivePlayback();
        when(mAudioManager.requestAudioFocus(any())).thenReturn(
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        requestHalAudioFocus(USAGE_VOICE_COMMUNICATION);

        expectWithMessage("Playback active audio attributes")
                .that(mCarAudioService.getActiveAudioAttributesForZone(PRIMARY_AUDIO_ZONE))
                .containsExactly(new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build(),
                        new AudioAttributes.Builder().setUsage(USAGE_VOICE_COMMUNICATION).build());
    }

    @Test
    public void getCallStateForZone_forPrimaryZone() throws Exception {
        when(mMockTelephonyManager.getCallState()).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK);
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(TEST_DRIVER_USER_ID);
        mCarAudioService.init();
        when(mMockOccupantZoneService.getUserForOccupant(anyInt()))
                .thenReturn(TEST_DRIVER_USER_ID, TEST_REAR_RIGHT_USER_ID);
        assignOccupantToAudioZones();

        expectWithMessage("Primary zone call state").that(
                mCarAudioService.getCallStateForZone(PRIMARY_AUDIO_ZONE))
                .isEqualTo(TelephonyManager.CALL_STATE_OFFHOOK);
    }

    @Test
    public void getCallStateForZone_forNonPrimaryZone() throws Exception {
        when(mMockTelephonyManager.getCallState()).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK);
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(TEST_DRIVER_USER_ID);
        mCarAudioService.init();
        when(mMockOccupantZoneService.getUserForOccupant(anyInt()))
                .thenReturn(TEST_REAR_LEFT_USER_ID, TEST_REAR_RIGHT_USER_ID);
        assignOccupantToAudioZones();

        expectWithMessage("Secondary zone call state").that(
                        mCarAudioService.getCallStateForZone(TEST_REAR_LEFT_ZONE_ID))
                .isEqualTo(TelephonyManager.CALL_STATE_IDLE);
    }

    @Test
    public void getVolumeGroupAndContextCount() {
        CarAudioService useCoreAudioCarAudioService =
                getCarAudioServiceUsingCoreAudioRoutingAndVolume();

        verify(mAudioManager).registerVolumeGroupCallback(any(), any());
        expectWithMessage("Primary zone car volume group count")
                .that(useCoreAudioCarAudioService.getVolumeGroupCount(PRIMARY_AUDIO_ZONE))
                .isEqualTo(CoreAudioRoutingUtils.getVolumeGroups().size());
        expectWithMessage("Number of contexts")
                .that(useCoreAudioCarAudioService.getCarAudioContext().getAllContextsIds().size())
                .isEqualTo(CoreAudioRoutingUtils.getProductStrategies().size());
        expectWithMessage("Car Audio Contexts")
                .that(useCoreAudioCarAudioService.getCarAudioContext().getAllContextsIds())
                .containsExactly(CoreAudioRoutingUtils.NAV_STRATEGY_ID,
                        CoreAudioRoutingUtils.MUSIC_STRATEGY_ID,
                        CoreAudioRoutingUtils.OEM_STRATEGY_ID);
    }

    @Test
    public void registerAudioZonesMirrorStatusCallback() {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                new TestAudioZonesMirrorStatusCallbackCallback(/* count= */ 1);

        boolean registered = mCarAudioService.registerAudioZonesMirrorStatusCallback(callback);

        expectWithMessage("Audio zones mirror status callback registered status")
                .that(registered).isTrue();
    }

    @Test
    public void registerAudioZonesMirrorStatusCallback_withoutMirroringEnabled() {
        CarAudioService carAudioService = getCarAudioServiceWithoutMirroring();
        carAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                new TestAudioZonesMirrorStatusCallbackCallback(/* count= */ 1);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () ->
                        carAudioService.registerAudioZonesMirrorStatusCallback(callback));

        expectWithMessage("Disabled audio zones mirror register exception").that(thrown)
                .hasMessageThat().contains("Audio zones mirroring is required");
    }

    @Test
    public void registerAudioZonesMirrorStatusCallback_withNullCallback() {
        mCarAudioService.init();

        NullPointerException thrown =
                assertThrows(NullPointerException.class, () ->
                    mCarAudioService.registerAudioZonesMirrorStatusCallback(/* callback= */ null));

        expectWithMessage("Null audio zones mirror register exception").that(thrown)
                .hasMessageThat().contains("Audio zones mirror status callback");
    }

    @Test
    public void unregisterAudioZonesMirrorStatusCallback_withNullCallback() {
        mCarAudioService.init();

        NullPointerException thrown =
                assertThrows(NullPointerException.class, () -> mCarAudioService
                        .unregisterAudioZonesMirrorStatusCallback(/* callback= */ null));

        expectWithMessage("Null audio zones mirror unregister exception").that(thrown)
                .hasMessageThat().contains("Audio zones mirror status callback");
    }

    @Test
    public void enableMirrorForAudioZones_withNullAudioZones() {
        mCarAudioService.init();

        NullPointerException thrown =
                assertThrows(NullPointerException.class, () ->
                        mCarAudioService.enableMirrorForAudioZones(/* audioZones= */ null));

        expectWithMessage("Null mirror audio zones exception").that(thrown)
                .hasMessageThat().contains("Mirror audio zones");
    }

    @Test
    public void enableMirrorForAudioZones() throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();

        mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);

        callback.waitForCallback();
        expectWithMessage("Audio mirror approved status").that(callback.getLastStatus())
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_APPROVED);
        expectWithMessage("Audio mirror approved zones").that(callback.getLastZoneIds())
                .asList().containsExactly(TEST_REAR_LEFT_ZONE_ID, TEST_REAR_RIGHT_ZONE_ID);
    }

    @Test
    public void enableMirrorForAudioZones_sendsMirrorInfoToAudioHAL() throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();

        mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);

        callback.waitForCallback();
        String audioMirrorInfoCommand = captureAudioMirrorInfoCommand(1);
        List<String> commands = Arrays.asList(audioMirrorInfoCommand.split(";"));
        String sourceDeviceAddress = removeUpToEquals(commands.get(0));
        expectWithMessage("Audio mirror source info").that(sourceDeviceAddress)
                .isEqualTo(MIRROR_TEST_DEVICE);
        String destinationsDevices = commands.get(1);
        List<String> deviceAddresses = Arrays.asList(removeUpToEquals(destinationsDevices)
                .split(","));
        expectWithMessage("Audio mirror zone one info").that(deviceAddresses.get(0))
                .isEqualTo(SECONDARY_TEST_DEVICE_CONFIG_0);
        expectWithMessage("Audio mirror zone two info").that(deviceAddresses.get(1))
                .isEqualTo(TERTIARY_TEST_DEVICE_1);
    }

    @Test
    public void enableMirrorForAudioZones_forPrimaryZone_fails() throws Exception {
        mCarAudioService.init();
        assignOccupantToAudioZones();
        int[] audioZones = new int[]{TEST_REAR_LEFT_ZONE_ID, PRIMARY_AUDIO_ZONE};

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () ->
                        mCarAudioService.enableMirrorForAudioZones(audioZones));

        expectWithMessage("Mirror audio zones with primary zone exception").that(thrown)
                .hasMessageThat().contains("not allowed for primary audio zone");
    }

    @Test
    public void enableMirrorForAudioZones_forNonAssignedZone_fails() throws Exception {
        when(mMockOccupantZoneService.getUserForOccupant(TEST_REAR_RIGHT_OCCUPANT_ZONE_ID))
                .thenReturn(UserManagerHelper.USER_NULL);
        mCarAudioService.init();
        getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () ->
                        mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES));

        expectWithMessage("Mirror audio zones for unoccupied audio zone exception")
                .that(thrown).hasMessageThat().contains("must have an active user");
    }

    @Test
    public void enableMirrorForAudioZones_forRepeatingZones_fails() throws Exception {
        mCarAudioService.init();
        assignOccupantToAudioZones();
        int[] audioZones = new int[]{TEST_REAR_LEFT_ZONE_ID,
                TEST_REAR_LEFT_ZONE_ID};

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () ->
                        mCarAudioService.enableMirrorForAudioZones(audioZones));

        expectWithMessage("Repeated mirror audio zones exception").that(thrown)
                .hasMessageThat().contains("must be unique");
    }

    @Test
    public void enableMirrorForAudioZones_forAlreadyMirroredZones() throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);
        callback.waitForCallback();
        callback.reset(/* count= */ 1);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () ->
                        mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES));

        expectWithMessage("Audio mirror exception for repeating request")
                .that(thrown).hasMessageThat().contains("is already mirroring");

    }

    @Test
    public void enableMirrorForAudioZones_afterSharedInPrimaryZone() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_RIGHT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId, /* allowed= */ true);
        requestToken.waitForCallback();
        requestToken.reset();

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () ->
                        mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES));

        expectWithMessage("Mirror audio zones while sharing in primary zone exception")
                .that(thrown).hasMessageThat().contains("currently sharing to primary zone");
    }

    @Test
    public void enableMirrorForAudioZones_forInvertedMirrorConfiguration() throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.enableMirrorForAudioZones(new int[] {TEST_REAR_RIGHT_ZONE_ID,
                TEST_REAR_LEFT_ZONE_ID});
        callback.waitForCallback();
        callback.reset(/* count= */ 1);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () ->
                        mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES));

        expectWithMessage("Audio mirror exception for inverted zone request")
                .that(thrown).hasMessageThat().contains("is already mirroring");
    }

    @Test
    public void enableMirrorForAudioZones_withNoMoreMirrorDevices_fails() throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);
        callback.waitForCallback();
        callback.reset(/* count= */ 1);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                mCarAudioService.enableMirrorForAudioZones(
                        new int[] {TEST_FRONT_ZONE_ID, TEST_REAR_ROW_3_ZONE_ID}));

        expectWithMessage("Audio mirror for out of mirror devices exception")
                .that(thrown).hasMessageThat().contains("available mirror output devices");
    }

    @Test
    public void canEnableAudioMirror_withOutOfMirroringDevices() throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);
        callback.waitForCallback();

        expectWithMessage("Can audio mirror status").that(mCarAudioService
                        .canEnableAudioMirror())
                .isEqualTo(AUDIO_MIRROR_OUT_OF_OUTPUT_DEVICES);
    }

    @Test
    public void canEnableAudioMirror_withAudioMirrorEnabledAndNoPendingRequests() {
        mCarAudioService.init();

        expectWithMessage("Can audio mirror status before audio mirror request")
                .that(mCarAudioService.canEnableAudioMirror())
                .isEqualTo(AUDIO_MIRROR_CAN_ENABLE);
    }

    @Test
    public void canEnableAudioMirror_withMirroringDisabled() {
        CarAudioService carAudioService = getCarAudioServiceWithoutMirroring();
        carAudioService.init();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                        mCarAudioService.canEnableAudioMirror());

        expectWithMessage("Can enable audio mirror exception")
                .that(thrown).hasMessageThat().contains("Audio zones mirroring is required");
    }

    @Test
    public void extendAudioMirrorRequest() throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();
        long requestId = mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);
        callback.waitForCallback();
        callback.reset(1);

        mCarAudioService.extendAudioMirrorRequest(requestId, new int[] {TEST_FRONT_ZONE_ID});

        callback.waitForCallback();
        expectWithMessage("Audio mirror approved status").that(callback.getLastStatus())
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_APPROVED);
        expectWithMessage("Audio mirror approved zones").that(callback.getLastZoneIds())
                .asList().containsExactly(TEST_REAR_LEFT_ZONE_ID, TEST_REAR_RIGHT_ZONE_ID,
                        TEST_FRONT_ZONE_ID);
    }

    @Test
    public void extendAudioMirrorRequest_withNullAudioZones() throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();
        long requestId = mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);
        callback.waitForCallback();
        callback.reset(1);

        NullPointerException thrown =
                assertThrows(NullPointerException.class, () ->
                        mCarAudioService.extendAudioMirrorRequest(requestId,
                                /* audioZones = */ null));

        expectWithMessage("Null audio zones to extend for mirror request exception")
                .that(thrown).hasMessageThat().contains("Mirror audio zones");
    }

    @Test
    public void extendAudioMirrorRequest_withPrimaryAudioZone() throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();
        long requestId = mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);
        callback.waitForCallback();
        callback.reset(1);

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () ->
                        mCarAudioService.extendAudioMirrorRequest(requestId,
                                new int[] {PRIMARY_AUDIO_ZONE}));

        expectWithMessage("Primary audio zone to extend for mirror request exception")
                .that(thrown).hasMessageThat().contains(
                        "Audio mirroring not allowed for primary audio zone");
    }

    @Test
    public void getAudioZoneConfigInfos() {
        mCarAudioService.init();

        List<CarAudioZoneConfigInfo> zoneConfigInfos =
                mCarAudioService.getAudioZoneConfigInfos(TEST_REAR_LEFT_ZONE_ID);

        List<String> zoneConfigNames = new ArrayList(zoneConfigInfos.size());
        for (int index = 0; index < zoneConfigInfos.size(); index++) {
            zoneConfigNames.add(zoneConfigInfos.get(index).getName());
        }
        expectWithMessage("Zone configurations for secondary zone").that(zoneConfigNames)
                .containsExactly(SECONDARY_ZONE_CONFIG_NAME_1, SECONDARY_ZONE_CONFIG_NAME_2);
    }

    @Test
    public void getCurrentAudioZoneConfigInfo() {
        mCarAudioService.init();

        CarAudioZoneConfigInfo currentZoneConfigInfo =
                mCarAudioService.getCurrentAudioZoneConfigInfo(TEST_REAR_LEFT_ZONE_ID);

        expectWithMessage("Name of current zone configuration for secondary zone")
                .that(currentZoneConfigInfo.getName()).isEqualTo(SECONDARY_ZONE_CONFIG_NAME_1);
    }

    @Test
    public void switchZoneToConfig() throws Exception {
        mCarAudioService.init();
        SwitchAudioZoneConfigCallbackImpl callback = new SwitchAudioZoneConfigCallbackImpl();
        assignOccupantToAudioZones();
        CarAudioZoneConfigInfo zoneConfigSwitchTo = getZoneConfigToSwitch(TEST_REAR_LEFT_ZONE_ID);

        mCarAudioService.switchZoneToConfig(zoneConfigSwitchTo, callback);

        callback.waitForCallback();
        expectWithMessage("Updated zone configuration")
                .that(callback.getZoneConfig()).isEqualTo(zoneConfigSwitchTo);
        expectWithMessage("Zone configuration switching status")
                .that(callback.getSwitchStatus()).isTrue();
    }

    @Test
    public void switchZoneToConfig_forNonAssignedZone_fails() throws Exception {
        when(mMockOccupantZoneService.getUserForOccupant(TEST_REAR_LEFT_OCCUPANT_ZONE_ID))
                .thenReturn(UserManagerHelper.USER_NULL);
        mCarAudioService.init();
        SwitchAudioZoneConfigCallbackImpl callback = new SwitchAudioZoneConfigCallbackImpl();
        assignOccupantToAudioZones();
        CarAudioZoneConfigInfo  zoneConfigSwitchTo = getZoneConfigToSwitch(TEST_REAR_LEFT_ZONE_ID);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () ->
                        mCarAudioService.switchZoneToConfig(zoneConfigSwitchTo, callback));

        expectWithMessage("Switching zone configuration for unoccupied audio zone exception")
                .that(thrown).hasMessageThat().contains("must have an active user");
    }

    @Test
    public void switchZoneToConfig_afterSharedInPrimaryZone_fails() throws Exception {
        mCarAudioService.init();
        SwitchAudioZoneConfigCallbackImpl callback = new SwitchAudioZoneConfigCallbackImpl();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        assignOccupantToAudioZones();
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_REAR_LEFT_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId, /* allowed= */ true);
        requestToken.waitForCallback();
        requestToken.reset();
        CarAudioZoneConfigInfo zoneConfigSwitchTo = getZoneConfigToSwitch(TEST_REAR_LEFT_ZONE_ID);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () ->
                        mCarAudioService.switchZoneToConfig(zoneConfigSwitchTo, callback));

        expectWithMessage("Switching zone configuration while sharing in primary zone exception")
                .that(thrown).hasMessageThat().contains("currently sharing to primary zone");
    }

    @Test
    public void switchZoneToConfig_afterMirroring_fails() throws Exception {
        mCarAudioService.init();
        SwitchAudioZoneConfigCallbackImpl callback = new SwitchAudioZoneConfigCallbackImpl();
        TestAudioZonesMirrorStatusCallbackCallback mirrorCallback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);
        mirrorCallback.waitForCallback();
        mirrorCallback.reset(/* count= */ 1);
        CarAudioZoneConfigInfo zoneConfigSwitchTo = getZoneConfigToSwitch(TEST_REAR_LEFT_ZONE_ID);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () ->
                        mCarAudioService.switchZoneToConfig(zoneConfigSwitchTo, callback));

        expectWithMessage("Switching zone configuration while audio mirroring").that(thrown)
                .hasMessageThat().contains("currently in a mirroring configuration");
    }

    @Test
    public void switchZoneToConfig_withPendingFocus_regainsFocus() throws Exception {
        mCarAudioService.init();
        SwitchAudioZoneConfigCallbackImpl callback = new SwitchAudioZoneConfigCallbackImpl();
        assignOccupantToAudioZones();
        AudioFocusInfo audioFocusInfo = createAudioFocusInfoForMedia(TEST_REAR_RIGHT_UID);
        mCarAudioService.requestAudioFocusForTest(audioFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);
        CarAudioZoneConfigInfo zoneConfigSwitchTo = getZoneConfigToSwitch(TEST_REAR_RIGHT_ZONE_ID);

        mCarAudioService.switchZoneToConfig(zoneConfigSwitchTo, callback);

        callback.waitForCallback();
        expectWithMessage("Updated zone configuration with pending focus")
                .that(callback.getZoneConfig()).isEqualTo(zoneConfigSwitchTo);
        expectWithMessage("Zone configuration switching status with pending focus")
                .that(callback.getSwitchStatus()).isTrue();
        List<Integer> focusChanges = getFocusChanges(audioFocusInfo);
        expectWithMessage("Media audio focus changes after switching zone")
                .that(focusChanges).containsExactly(AUDIOFOCUS_LOSS_TRANSIENT, AUDIOFOCUS_GAIN);
    }

    @Test
    public void switchZoneToConfig_withPendingFocus_updatesDuckingInfo() throws Exception {
        mCarAudioService.init();
        SwitchAudioZoneConfigCallbackImpl callback = new SwitchAudioZoneConfigCallbackImpl();
        assignOccupantToAudioZones();
        AudioFocusInfo audioFocusInfo = createAudioFocusInfoForMedia(TEST_REAR_RIGHT_UID);
        mCarAudioService.requestAudioFocusForTest(audioFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);
        ArgumentCaptor<List<CarDuckingInfo>> carDuckingInfosCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mAudioControlWrapperAidl).onDevicesToDuckChange(carDuckingInfosCaptor.capture());
        verifyMediaDuckingInfoInZone(carDuckingInfosCaptor, TEST_REAR_RIGHT_ZONE_ID,
                " before switching zone");
        CarAudioZoneConfigInfo zoneConfigSwitchTo = getZoneConfigToSwitch(TEST_REAR_RIGHT_ZONE_ID);

        mCarAudioService.switchZoneToConfig(zoneConfigSwitchTo, callback);

        callback.waitForCallback();
        expectWithMessage("Updated zone configuration with pending focus")
                .that(callback.getZoneConfig()).isEqualTo(zoneConfigSwitchTo);
        expectWithMessage("Zone configuration switching status with pending focus")
                .that(callback.getSwitchStatus()).isTrue();
        verify(mAudioControlWrapperAidl, times(2))
                .onDevicesToDuckChange(carDuckingInfosCaptor.capture());
        verifyMediaDuckingInfoInZone(carDuckingInfosCaptor, TEST_REAR_RIGHT_ZONE_ID,
                " after switching zone");
    }

    @Test
    public void switchZoneToConfig_withCurrentZoneConfigAndPendingFocus_notLoseAndRegainFocus()
            throws Exception {
        mCarAudioService.init();
        SwitchAudioZoneConfigCallbackImpl callback = new SwitchAudioZoneConfigCallbackImpl();
        assignOccupantToAudioZones();
        AudioFocusInfo audioFocusInfo = createAudioFocusInfoForMedia(TEST_REAR_RIGHT_UID);
        mCarAudioService.requestAudioFocusForTest(audioFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);
        CarAudioZoneConfigInfo currentZoneConfig =
                mCarAudioService.getCurrentAudioZoneConfigInfo(TEST_REAR_RIGHT_ZONE_ID);

        mCarAudioService.switchZoneToConfig(currentZoneConfig, callback);

        callback.waitForCallback();
        expectWithMessage("Updated zone configuration with current configuration")
                .that(callback.getZoneConfig()).isEqualTo(currentZoneConfig);
        expectWithMessage("Zone configuration switching status with current configuration")
                .that(callback.getSwitchStatus()).isTrue();
        verify(mAudioManager, never()).dispatchAudioFocusChange(eq(audioFocusInfo), anyInt(),
                any(AudioPolicy.class));
    }

    @Test
    public void switchZoneToConfig_withVolumeGroupEventCallbackRegistered_invokesEvent()
            throws Exception {
        mCarAudioService.init();
        SwitchAudioZoneConfigCallbackImpl callback = new SwitchAudioZoneConfigCallbackImpl();
        CarVolumeEventCallbackImpl volumeEventCallback = new CarVolumeEventCallbackImpl();
        assignOccupantToAudioZones();
        CarAudioZoneConfigInfo zoneConfigSwitchTo = getZoneConfigToSwitch(TEST_REAR_LEFT_ZONE_ID);
        mCarAudioService.registerCarVolumeEventCallback(volumeEventCallback);

        mCarAudioService.switchZoneToConfig(zoneConfigSwitchTo, callback);

        callback.waitForCallback();
        expectWithMessage("Updated zone configuration")
                .that(callback.getZoneConfig()).isEqualTo(zoneConfigSwitchTo);
        expectWithMessage("Zone configuration switching status")
                .that(callback.getSwitchStatus()).isTrue();
        volumeEventCallback.waitForCallback();
        expectWithMessage("Volume events count after switching zone configuration")
                .that(volumeEventCallback.getVolumeGroupEvents()).hasSize(1);
        CarVolumeGroupEvent groupEvent = volumeEventCallback.getVolumeGroupEvents().get(0);
        expectWithMessage("Volume event type after switching zone configuration")
                .that(groupEvent.getEventTypes())
                .isEqualTo(CarVolumeGroupEvent.EVENT_TYPE_ZONE_CONFIGURATION_CHANGED);
        expectWithMessage("Volume group infos after switching zone configuration")
                .that(groupEvent.getCarVolumeGroupInfos())
                .containsExactly(TEST_SECONDARY_ZONE_CONFIG_1_VOLUME_INFO_0,
                        TEST_SECONDARY_ZONE_CONFIG_1_VOLUME_INFO_1);
    }

    @Test
    public void switchZoneToConfig_updatesVolumeGroupInfos()
            throws Exception {
        mCarAudioService.init();
        SwitchAudioZoneConfigCallbackImpl callback = new SwitchAudioZoneConfigCallbackImpl();
        assignOccupantToAudioZones();
        Log.e(TAG, "Current volume group " + mCarAudioService.getVolumeGroupInfosForZone(
                TEST_REAR_LEFT_ZONE_ID));
        expectWithMessage("Volume group infos before switching zone configuration")
                .that(mCarAudioService.getVolumeGroupInfosForZone(TEST_REAR_LEFT_ZONE_ID))
                .containsExactly(TEST_SECONDARY_ZONE_CONFIG_0_VOLUME_INFO);
        CarAudioZoneConfigInfo zoneConfigSwitchTo = getZoneConfigToSwitch(TEST_REAR_LEFT_ZONE_ID);

        mCarAudioService.switchZoneToConfig(zoneConfigSwitchTo, callback);

        callback.waitForCallback();
        expectWithMessage("Volume group infos after switching zone configuration")
                .that(mCarAudioService.getVolumeGroupInfosForZone(TEST_REAR_LEFT_ZONE_ID))
                .containsExactly(TEST_SECONDARY_ZONE_CONFIG_1_VOLUME_INFO_0,
                        TEST_SECONDARY_ZONE_CONFIG_1_VOLUME_INFO_1);
    }

    @Test
    public void disableAudioMirrorForZone_withInvalidZone() throws Exception {
        mCarAudioService.init();
        assignOccupantToAudioZones();

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () ->
                        mCarAudioService.disableAudioMirrorForZone(INVALID_AUDIO_ZONE));

        expectWithMessage("Disable mirror for invalid audio zone exception").that(thrown)
                        .hasMessageThat().contains("Invalid audio zone");
    }

    @Test
    public void disableAudioMirrorForZone_withMirroringDisabled() {
        CarAudioService carAudioService = getCarAudioServiceWithoutMirroring();
        carAudioService.init();

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () ->
                        mCarAudioService.disableAudioMirrorForZone(TEST_REAR_LEFT_ZONE_ID));

        expectWithMessage("Disable mirror for zone with audio mirroring disabled")
                .that(thrown).hasMessageThat().contains("Audio zones mirroring is required");
    }

    @Test
    public void disableAudioMirrorForZone_forNonMirroringZone() throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();

        mCarAudioService.disableAudioMirrorForZone(TEST_REAR_LEFT_ZONE_ID);

        callback.waitForCallback();
        expectWithMessage("Disable audio mirror for non-mirroring zone callback count")
                .that(callback.mNumberOfCalls).isEqualTo(0);
    }

    @Test
    public void disableAudioMirrorForZone_forMirroringZones() throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);
        callback.waitForCallback();
        callback.reset(/* count= */ 1);

        mCarAudioService.disableAudioMirrorForZone(TEST_REAR_LEFT_ZONE_ID);

        callback.waitForCallback();
        expectWithMessage("Callback count for disable audio mirror")
                .that(callback.mNumberOfCalls).isEqualTo(2);
        expectWithMessage("Callback status disable audio mirror for mirroring zone")
                .that(callback.getLastStatus())
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_STOPPED);
        expectWithMessage("Callback zones disable audio mirror for mirroring zone")
                .that(callback.getLastZoneIds()).asList()
                .containsExactly(TEST_REAR_RIGHT_ZONE_ID, TEST_REAR_LEFT_ZONE_ID);
    }

    @Test
    public void disableAudioMirrorForZone_forMirroringZones_forFirstMirroringConfig()
            throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);
        callback.waitForCallback();
        callback.reset(/* count= */ 1);

        mCarAudioService.disableAudioMirrorForZone(TEST_REAR_RIGHT_ZONE_ID);

        callback.waitForCallback();
        expectWithMessage("Callback count for disable audio mirror")
                .that(callback.mNumberOfCalls).isEqualTo(2);
        expectWithMessage("Callback status disable audio mirror for mirroring zone")
                .that(callback.getLastStatus())
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_STOPPED);
        expectWithMessage("Callback zones disable audio mirror for mirroring zone")
                .that(callback.getLastZoneIds()).asList()
                .containsExactly(TEST_REAR_RIGHT_ZONE_ID, TEST_REAR_LEFT_ZONE_ID);
        String audioMirrorOffCommand = captureAudioMirrorInfoCommand(2);
        expectWithMessage("Audio HAL off source for mirroring zone")
                .that(audioMirrorOffCommand).contains(MIRROR_TEST_DEVICE);
        expectWithMessage("Audio HAL off signal for mirroring zone")
                .that(audioMirrorOffCommand).contains(MIRROR_OFF_SIGNAL);
    }

    @Test
    public void disableAudioMirrorForZone_withPendingFocus()
            throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();
        mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);
        callback.waitForCallback();
        callback.reset(/* count= */ 2);
        AudioFocusInfo audioFocusInfo = createAudioFocusInfoForMedia(TEST_REAR_RIGHT_UID);
        mCarAudioService.requestAudioFocusForTest(audioFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        mCarAudioService.disableAudioMirrorForZone(TEST_REAR_LEFT_ZONE_ID);

        callback.waitForCallback();
        List<Integer> focusChanges = getFocusChanges(audioFocusInfo);
        expectWithMessage("Media audio focus changes after disable mirror for zone")
                .that(focusChanges).containsExactly(AUDIOFOCUS_LOSS_TRANSIENT, AUDIOFOCUS_GAIN);
    }

    @Test
    public void disableAudioMirror_withoutMirroringDisabled() {
        CarAudioService carAudioService = getCarAudioServiceWithoutMirroring();
        carAudioService.init();

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () ->
                        carAudioService.disableAudioMirror(INVALID_REQUEST_ID));

        expectWithMessage("Disable mirror for audio zones with audio mirroring disabled")
                .that(thrown).hasMessageThat().contains("Audio zones mirroring is required");
    }

    @Test
    public void disableAudioMirror_withInvalidRequestId() {
        mCarAudioService.init();

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () ->
                        mCarAudioService.disableAudioMirror(INVALID_REQUEST_ID));

        expectWithMessage("Disable mirror for audio zones with audio invalid request id")
                .that(thrown).hasMessageThat().contains("INVALID_REQUEST_ID");
    }

    @Test
    public void disableAudioMirror_forNonMirroringZone() throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();
        long requestId = mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);
        callback.waitForCallback();
        callback.reset(1);
        mCarAudioService.disableAudioMirror(requestId);
        callback.waitForCallback();
        callback.reset(1);

        mCarAudioService.disableAudioMirror(requestId);

        expectWithMessage("Disable audio mirror for non-mirroring zone callback count")
                .that(callback.mNumberOfCalls).isEqualTo(2);
    }

    @Test
    public void disableAudioMirror_forMirroringZones() throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();
        long requestId = mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);
        callback.waitForCallback();
        callback.reset(/* count= */ 1);

        mCarAudioService.disableAudioMirror(requestId);

        callback.waitForCallback();
        expectWithMessage("Callback count for disable mirror in audio zones")
                .that(callback.mNumberOfCalls).isEqualTo(2);
        expectWithMessage("Callback status disable audio mirror for mirroring zones")
                .that(callback.getLastStatus())
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_STOPPED);
        expectWithMessage("Callback zones disable audio mirror for mirroring zones")
                .that(callback.getLastZoneIds()).asList()
                .containsExactly(TEST_REAR_RIGHT_ZONE_ID, TEST_REAR_LEFT_ZONE_ID);
        String audioMirrorOffCommand = captureAudioMirrorInfoCommand(2);
        expectWithMessage("Audio HAL off source for mirroring zones")
                .that(audioMirrorOffCommand).contains(MIRROR_TEST_DEVICE);
        expectWithMessage("Audio HAL off signal for mirroring zones")
                .that(audioMirrorOffCommand).contains(MIRROR_OFF_SIGNAL);
    }

    @Test
    public void disableAudioMirror_withPendingFocus() throws Exception {
        mCarAudioService.init();
        TestAudioZonesMirrorStatusCallbackCallback callback =
                getAudioZonesMirrorStatusCallback();
        assignOccupantToAudioZones();
        long requestId = mCarAudioService.enableMirrorForAudioZones(TEST_MIRROR_AUDIO_ZONES);
        callback.waitForCallback();
        callback.reset(/* count= */ 2);
        AudioFocusInfo audioFocusInfo = createAudioFocusInfoForMedia(TEST_REAR_RIGHT_UID);
        mCarAudioService.requestAudioFocusForTest(audioFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        mCarAudioService.disableAudioMirror(requestId);

        callback.waitForCallback();
        List<Integer> focusChanges = getFocusChanges(audioFocusInfo);
        expectWithMessage("Media audio focus changes after disable audio"
                + "mirror for zones config").that(focusChanges)
                .containsExactly(AUDIOFOCUS_LOSS_TRANSIENT, AUDIOFOCUS_GAIN);
    }

    @Test
    public void onAudioVolumeGroupChanged_dispatchCallbackEvent() throws RemoteException {
        CarAudioService useCoreAudioCarAudioService =
                getCarAudioServiceUsingCoreAudioRoutingAndVolume();
        int musicIndex = useCoreAudioCarAudioService.getGroupVolume(
                PRIMARY_AUDIO_ZONE, CoreAudioRoutingUtils.MUSIC_CAR_GROUP_ID);
        // Report a volume change
        when(mAudioManager.getVolumeIndexForAttributes(eq(CoreAudioRoutingUtils.MUSIC_ATTRIBUTES)))
                .thenReturn(musicIndex + 1);
        when(mAudioManager.getLastAudibleVolumeForVolumeGroup(CoreAudioRoutingUtils.MUSIC_GROUP_ID))
                .thenReturn(musicIndex + 1);
        when(mAudioManager.isVolumeGroupMuted(CoreAudioRoutingUtils.MUSIC_GROUP_ID))
                .thenReturn(false);

        useCoreAudioCarAudioService.onAudioVolumeGroupChanged(PRIMARY_AUDIO_ZONE,
                CoreAudioRoutingUtils.MUSIC_GROUP_NAME, /* flags= */ 0);

        verify(mCarVolumeCallbackHandler)
                .onVolumeGroupChange(PRIMARY_AUDIO_ZONE, CoreAudioRoutingUtils.MUSIC_CAR_GROUP_ID,
                        FLAG_SHOW_UI | FLAG_PLAY_SOUND);
    }

    @Test
    public void onAudioVolumeGroupChanged_noDispatchCallbackEvent_whenAlreadySynced()
            throws RemoteException {
        CarAudioService useCoreAudioCarAudioService =
                getCarAudioServiceUsingCoreAudioRoutingAndVolume();
        useCoreAudioCarAudioService.setGroupVolume(PRIMARY_AUDIO_ZONE,
                CoreAudioRoutingUtils.MUSIC_CAR_GROUP_ID, CoreAudioRoutingUtils.MUSIC_AM_INIT_INDEX,
                /* flags= */ 0);
        reset(mCarVolumeCallbackHandler);

        useCoreAudioCarAudioService.onAudioVolumeGroupChanged(PRIMARY_AUDIO_ZONE,
                CoreAudioRoutingUtils.MUSIC_GROUP_NAME, /* flags= */ 0);

        verify(mCarVolumeCallbackHandler, never())
                .onVolumeGroupChange(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onAudioVolumeGroupChanged_dispatchCallbackEvent_whenMuted() throws RemoteException {
        CarAudioService useCoreAudioCarAudioService =
                getCarAudioServiceUsingCoreAudioRoutingAndVolume();
        // Report a mute change
        when(mAudioManager.getVolumeIndexForAttributes(eq(CoreAudioRoutingUtils.MUSIC_ATTRIBUTES)))
                .thenReturn(CoreAudioRoutingUtils.MUSIC_MIN_INDEX);
        when(mAudioManager.isVolumeGroupMuted(CoreAudioRoutingUtils.MUSIC_GROUP_ID))
                .thenReturn(true);

        useCoreAudioCarAudioService.onAudioVolumeGroupChanged(PRIMARY_AUDIO_ZONE,
                CoreAudioRoutingUtils.MUSIC_GROUP_NAME, /* flags= */ 0);

        verify(mCarVolumeCallbackHandler).onGroupMuteChange(PRIMARY_AUDIO_ZONE,
                CoreAudioRoutingUtils.MUSIC_CAR_GROUP_ID, FLAG_SHOW_UI);
    }

    @Test
    public void onVolumeGroupEvent_withVolumeEvent_triggersCallback() throws Exception {
        mCarAudioService.init();
        CarVolumeEventCallbackImpl volumeEventCallback = new CarVolumeEventCallbackImpl();
        mCarAudioService.registerCarVolumeEventCallback(volumeEventCallback);

        mCarAudioService.onVolumeGroupEvent(List.of(TEST_CAR_VOLUME_GROUP_EVENT));

        expectWithMessage("Volume event callback reception status")
                .that(volumeEventCallback.waitForCallback()).isTrue();
        verify(mCarVolumeCallbackHandler, never()).onGroupMuteChange(anyInt(), anyInt(), anyInt());
        verify(mCarVolumeCallbackHandler)
                .onVolumeGroupChange(PRIMARY_AUDIO_ZONE, CoreAudioRoutingUtils.MUSIC_CAR_GROUP_ID,
                        /* flags= */ 0);
        expectWithMessage("Volume events count after volume event")
                .that(volumeEventCallback.getVolumeGroupEvents()).hasSize(1);
        CarVolumeGroupEvent groupEvent = volumeEventCallback.getVolumeGroupEvents().get(0);
        expectWithMessage("Volume event type after volume event")
                .that(groupEvent.getEventTypes())
                .isEqualTo(CarVolumeGroupEvent.EVENT_TYPE_VOLUME_GAIN_INDEX_CHANGED);
        expectWithMessage("Volume group infos after unmute")
                .that(groupEvent.getCarVolumeGroupInfos())
                .containsExactly(TEST_PRIMARY_ZONE_UNMUTED_VOLUME_INFO_0);
    }

    @Test
    public void onVolumeGroupEvent_withMuteEvent_triggersCallback() throws Exception {
        mCarAudioService.init();
        CarVolumeEventCallbackImpl volumeEventCallback = new CarVolumeEventCallbackImpl();
        mCarAudioService.registerCarVolumeEventCallback(volumeEventCallback);

        mCarAudioService.onVolumeGroupEvent(List.of(TEST_CAR_MUTE_GROUP_EVENT));

        expectWithMessage("Volume event callback reception status")
                .that(volumeEventCallback.waitForCallback()).isTrue();
        verify(mCarVolumeCallbackHandler, never())
                .onVolumeGroupChange(anyInt(), anyInt(), anyInt());
        verify(mCarVolumeCallbackHandler)
                .onGroupMuteChange(PRIMARY_AUDIO_ZONE, CoreAudioRoutingUtils.MUSIC_CAR_GROUP_ID,
                        /* flags= */ 0);
        expectWithMessage("Volume events count after mute event")
                .that(volumeEventCallback.getVolumeGroupEvents()).hasSize(1);
        CarVolumeGroupEvent groupEvent = volumeEventCallback.getVolumeGroupEvents().get(0);
        expectWithMessage("Volume event type after mute event")
                .that(groupEvent.getEventTypes())
                .isEqualTo(CarVolumeGroupEvent.EVENT_TYPE_MUTE_CHANGED);
        expectWithMessage("Volume group infos after mute event")
                .that(groupEvent.getCarVolumeGroupInfos())
                .containsExactly(TEST_PRIMARY_ZONE_UNMUTED_VOLUME_INFO_0);
    }

    @Test
    public void onVolumeGroupEvent_withoutMuteOrVolumeEvent_doesNotTriggerCallback()
            throws Exception {
        mCarAudioService.init();
        CarVolumeEventCallbackImpl volumeEventCallback = new CarVolumeEventCallbackImpl();
        mCarAudioService.registerCarVolumeEventCallback(volumeEventCallback);

        mCarAudioService.onVolumeGroupEvent(List.of(TEST_CAR_ZONE_RECONFIGURATION_EVENT));

        expectWithMessage("Volume event callback reception status")
                .that(volumeEventCallback.waitForCallback()).isTrue();
        verify(mCarVolumeCallbackHandler, never())
                .onVolumeGroupChange(anyInt(), anyInt(), anyInt());
        verify(mCarVolumeCallbackHandler, never()).onGroupMuteChange(anyInt(), anyInt(), anyInt());
        expectWithMessage("Volume events count after reconfiguration event")
                .that(volumeEventCallback.getVolumeGroupEvents()).hasSize(1);
        CarVolumeGroupEvent groupEvent = volumeEventCallback.getVolumeGroupEvents().get(0);
        expectWithMessage("Volume event type after reconfiguration event")
                .that(groupEvent.getEventTypes())
                .isEqualTo(CarVolumeGroupEvent.EVENT_TYPE_ZONE_CONFIGURATION_CHANGED);
        expectWithMessage("Volume group infos after reconfiguration event")
                .that(groupEvent.getCarVolumeGroupInfos())
                .containsExactly(TEST_PRIMARY_ZONE_UNMUTED_VOLUME_INFO_0);
    }

    @Test
    public void setMuted_whenUnmuted_onActivation_triggersCallback() throws Exception {
        mCarAudioService.init();
        CarVolumeEventCallbackImpl volumeEventCallback = new CarVolumeEventCallbackImpl();
        mCarAudioService.registerCarVolumeEventCallback(volumeEventCallback);

        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                /* mute= */ true, TEST_FLAGS);

        verify(mCarVolumeCallbackHandler).onGroupMuteChange(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_ZONE_GROUP_0, TEST_FLAGS);
        expectWithMessage("Volume event callback reception status after mute")
                .that(volumeEventCallback.waitForCallback()).isTrue();
        expectWithMessage("Volume events count after mute")
                .that(volumeEventCallback.getVolumeGroupEvents()).hasSize(1);
        CarVolumeGroupEvent groupEvent = volumeEventCallback.getVolumeGroupEvents().get(0);
        expectWithMessage("Volume event type after mute")
                .that(groupEvent.getEventTypes())
                .isEqualTo(CarVolumeGroupEvent.EVENT_TYPE_MUTE_CHANGED);
        expectWithMessage("Volume group infos after mute")
                .that(groupEvent.getCarVolumeGroupInfos())
                .containsExactly(TEST_PRIMARY_ZONE_VOLUME_INFO_0);
    }

    @Test
    public void setMuted_whenUnmuted_onDeactivation_doesNotTriggerCallback() throws Exception {
        mCarAudioService.init();
        CarVolumeEventCallbackImpl volumeEventCallback = new CarVolumeEventCallbackImpl();
        mCarAudioService.registerCarVolumeEventCallback(volumeEventCallback);

        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                /* mute= */ false, TEST_FLAGS);

        verify(mCarVolumeCallbackHandler, never()).onGroupMuteChange(anyInt(), anyInt(), anyInt());
        expectWithMessage("Volume event callback reception status")
                .that(volumeEventCallback.waitForCallback()).isFalse();
    }

    @Test
    public void setMuted_whenMuted_onDeactivation_triggersCallback() throws Exception {
        mCarAudioService.init();
        CarVolumeEventCallbackImpl volumeEventCallback = new CarVolumeEventCallbackImpl();
        mCarAudioService.registerCarVolumeEventCallback(volumeEventCallback);
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                /* mute= */ true, TEST_FLAGS);
        reset(mCarVolumeCallbackHandler);

        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                /* mute= */ false, TEST_FLAGS);

        verify(mCarVolumeCallbackHandler).onGroupMuteChange(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_ZONE_GROUP_0, TEST_FLAGS);
        expectWithMessage("Volume event callback reception status after unmute")
                .that(volumeEventCallback.waitForCallback()).isTrue();
        expectWithMessage("Volume events count after mute")
                .that(volumeEventCallback.getVolumeGroupEvents()).hasSize(1);
        CarVolumeGroupEvent groupEvent = volumeEventCallback.getVolumeGroupEvents().get(0);
        expectWithMessage("Volume event type after unmute")
                .that(groupEvent.getEventTypes())
                .isEqualTo(CarVolumeGroupEvent.EVENT_TYPE_MUTE_CHANGED);
        expectWithMessage("Volume group infos after unmute")
                .that(groupEvent.getCarVolumeGroupInfos())
                .containsExactly(TEST_PRIMARY_ZONE_UNMUTED_VOLUME_INFO_0);
    }

    @Test
    public void setMuted_whenMuted_onActivation_doesNotTriggerCallback() throws Exception {
        mCarAudioService.init();
        CarVolumeEventCallbackImpl volumeEventCallback = new CarVolumeEventCallbackImpl();
        mCarAudioService.registerCarVolumeEventCallback(volumeEventCallback);
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                /* mute= */ true, TEST_FLAGS);
        volumeEventCallback.waitForCallback();
        volumeEventCallback.reset();
        reset(mCarVolumeCallbackHandler);

        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_ZONE_GROUP_0,
                /* mute= */ true, TEST_FLAGS);

        verify(mCarVolumeCallbackHandler, never()).onGroupMuteChange(anyInt(), anyInt(), anyInt());
        expectWithMessage("Volume event callback reception status")
                .that(volumeEventCallback.waitForCallback()).isFalse();
    }

    private String removeUpToEquals(String command) {
        return command.replaceAll("^[^=]*=", "");
    }

    private String captureAudioMirrorInfoCommand(int count) {
        ArgumentCaptor<String> capture = ArgumentCaptor.forClass(String.class);
        verify(mAudioManager, times(count)).setParameters(capture.capture());
        return capture.getValue();
    }

    private TestAudioZonesMirrorStatusCallbackCallback getAudioZonesMirrorStatusCallback() {
        TestAudioZonesMirrorStatusCallbackCallback callback =
                new TestAudioZonesMirrorStatusCallbackCallback(/* count= */ 1);
        mCarAudioService.registerAudioZonesMirrorStatusCallback(callback);
        return callback;
    }

    private void assignOccupantToAudioZones() throws RemoteException {
        ICarOccupantZoneCallback occupantZoneCallback = getOccupantZoneCallback();
        occupantZoneCallback.onOccupantZoneConfigChanged(
                CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
    }

    private CarAudioGainConfigInfo createCarAudioGainConfigInfo(int zoneId,
            String devicePortAddress, int volumeIndex) {
        AudioGainConfigInfo configInfo = new AudioGainConfigInfo();
        configInfo.zoneId = zoneId;
        configInfo.devicePortAddress = devicePortAddress;
        configInfo.volumeIndex = volumeIndex;
        return new CarAudioGainConfigInfo(configInfo);
    }

    private HalAudioGainCallback getHalAudioGainCallback() {
        ArgumentCaptor<HalAudioGainCallback> captor = ArgumentCaptor.forClass(
                HalAudioGainCallback.class);
        verify(mAudioControlWrapperAidl).registerAudioGainCallback(captor.capture());
        return captor.getValue();
    }

    private HalAudioDeviceInfo createHalAudioDeviceInfo(int id, String name, int minVal,
            int maxVal, int defaultVal, int stepVal, int type, String address) {
        AudioPortDeviceExt deviceExt = new AudioPortDeviceExt();
        deviceExt.device = new AudioDevice();
        deviceExt.device.type = new AudioDeviceDescription();
        deviceExt.device.type.type = type;
        deviceExt.device.type.connection = CONNECTION_BUS;
        deviceExt.device.address = AudioDeviceAddress.id(address);
        AudioPort audioPort = new AudioPort();
        audioPort.id = id;
        audioPort.name = name;
        audioPort.gains = new android.media.audio.common.AudioGain[] {
                new android.media.audio.common.AudioGain() {{
                    mode = JOINT;
                    minValue = minVal;
                    maxValue = maxVal;
                    defaultValue = defaultVal;
                    stepValue = stepVal;
                }}
        };
        audioPort.ext = AudioPortExt.device(deviceExt);
        return new HalAudioDeviceInfo(audioPort);
    }

    private HalAudioModuleChangeCallback getHalModuleChangeCallback() {
        ArgumentCaptor<HalAudioModuleChangeCallback> captor = ArgumentCaptor.forClass(
                HalAudioModuleChangeCallback.class);
        verify(mAudioControlWrapperAidl).setModuleChangeCallback(captor.capture());
        return captor.getValue();
    }

    private AudioPlaybackCallback getCarAudioPlaybackCallback() {
        ArgumentCaptor<AudioPlaybackCallback> captor = ArgumentCaptor.forClass(
                AudioPlaybackCallback.class);
        verify(mAudioManager).registerAudioPlaybackCallback(captor.capture(), any());
        return captor.getValue();
    }

    private CarInputService.KeyEventListener getAudioKeyEventListener() {
        ArgumentCaptor<CarInputService.KeyEventListener> captor =
                ArgumentCaptor.forClass(CarInputService.KeyEventListener.class);
        verify(mMockCarInputService).registerKeyEventListener(captor.capture(), any());
        return captor.getValue();
    }

    private void requestHalAudioFocus(int usage) {
        ArgumentCaptor<HalFocusListener> captor =
                ArgumentCaptor.forClass(HalFocusListener.class);
        verify(mAudioControlWrapperAidl).registerFocusListener(captor.capture());
        HalFocusListener halFocusListener = captor.getValue();
        halFocusListener.requestAudioFocus(usage, PRIMARY_AUDIO_ZONE,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
    }

    private void mockActivePlayback() {
        AudioPlaybackCallback callback = getCarAudioPlaybackCallback();
        callback.onPlaybackConfigChanged(List.of(getPlaybackConfig()));
    }

    private AudioPlaybackConfiguration getPlaybackConfig() {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(USAGE_MEDIA).build();
        AudioPlaybackConfiguration config = mock(AudioPlaybackConfiguration.class);
        when(config.getAudioAttributes()).thenReturn(audioAttributes);
        when(config.getAudioDeviceInfo()).thenReturn(mMediaOutputDevice);
        when(config.isActive()).thenReturn(true);

        return config;
    }

    private CarAudioService getCarAudioServiceWithoutMirroring() {
        AudioDeviceInfo[] outputDevices = generateOutputDeviceInfos();
        when(mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(outputDevices);
        CarAudioService carAudioService =
                new CarAudioService(mMockContext, mTemporaryAudioConfigurationWithoutMirroringFile
                        .getFile().getAbsolutePath(), mCarVolumeCallbackHandler);
        carAudioService.init();
        return carAudioService;
    }

    private CarAudioService getCarAudioServiceUsingCoreAudioRoutingAndVolume() {
        when(mMockResources.getBoolean(audioUseCoreVolume))
                .thenReturn(/* audioUseCoreVolume= */ true);
        when(mMockResources.getBoolean(audioUseCoreRouting))
                .thenReturn(/* audioUseCoreRouting= */ true);
        CarAudioService useCoreAudioCarAudioService =
                new CarAudioService(mMockContext,
                        mTemporaryAudioConfigurationUsingCoreAudioFile.getFile().getAbsolutePath(),
                        mCarVolumeCallbackHandler);
        useCoreAudioCarAudioService.init();
        return useCoreAudioCarAudioService;
    }

    private void mockGrantCarControlAudioSettingsPermission() {
        mockContextCheckCallingOrSelfPermission(mMockContext,
                PERMISSION_CAR_CONTROL_AUDIO_SETTINGS, PERMISSION_GRANTED);
    }

    private void mockDenyCarControlAudioSettingsPermission() {
        mockContextCheckCallingOrSelfPermission(mMockContext,
                PERMISSION_CAR_CONTROL_AUDIO_SETTINGS, PERMISSION_DENIED);
    }

    private void mockDenyCarControlAudioVolumePermission() {
        mockContextCheckCallingOrSelfPermission(mMockContext,
                PERMISSION_CAR_CONTROL_AUDIO_VOLUME, PERMISSION_DENIED);
    }

    private AudioDeviceInfo[] generateInputDeviceInfos() {
        mMicrophoneInputDevice = new AudioDeviceInfoBuilder()
                .setAddressName(PRIMARY_ZONE_MICROPHONE_ADDRESS)
                .setType(TYPE_BUILTIN_MIC)
                .setIsSource(true)
                .build();
        mFmTunerInputDevice = new AudioDeviceInfoBuilder()
                .setAddressName(PRIMARY_ZONE_FM_TUNER_ADDRESS)
                .setType(TYPE_FM_TUNER)
                .setIsSource(true)
                .build();
        return new AudioDeviceInfo[]{mMicrophoneInputDevice, mFmTunerInputDevice};
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
                        .setAddressName(SECONDARY_TEST_DEVICE_CONFIG_0)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(SECONDARY_TEST_DEVICE_CONFIG_1_0)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(SECONDARY_TEST_DEVICE_CONFIG_1_1)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(TERTIARY_TEST_DEVICE_1)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(TERTIARY_TEST_DEVICE_2)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(QUATERNARY_TEST_DEVICE_1)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(OEM_TEST_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(MIRROR_TEST_DEVICE).build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(TEST_REAR_ROW_3_DEVICE).build(),
        };
    }

    private void mockCoreAudioRoutingAndVolume() {
        doReturn(CoreAudioRoutingUtils.getProductStrategies())
                .when(() -> AudioManager.getAudioProductStrategies());
        doReturn(CoreAudioRoutingUtils.getVolumeGroups())
                .when(() -> AudioManager.getAudioVolumeGroups());

        when(mAudioManager.getVolumeGroupIdForAttributes(CoreAudioRoutingUtils.MUSIC_ATTRIBUTES))
                .thenReturn(CoreAudioRoutingUtils.MUSIC_GROUP_ID);
        when(mAudioManager.getMinVolumeIndexForAttributes(
                eq(CoreAudioRoutingUtils.MUSIC_ATTRIBUTES)))
                .thenReturn(CoreAudioRoutingUtils.MUSIC_MIN_INDEX);
        when(mAudioManager.getMaxVolumeIndexForAttributes(
                eq(CoreAudioRoutingUtils.MUSIC_ATTRIBUTES)))
                .thenReturn(CoreAudioRoutingUtils.MUSIC_MAX_INDEX);
        when(mAudioManager.getVolumeIndexForAttributes(eq(CoreAudioRoutingUtils.MUSIC_ATTRIBUTES)))
                .thenReturn(CoreAudioRoutingUtils.MUSIC_AM_INIT_INDEX);
        when(mAudioManager.getLastAudibleVolumeForVolumeGroup(CoreAudioRoutingUtils.MUSIC_GROUP_ID))
                .thenReturn(CoreAudioRoutingUtils.MUSIC_AM_INIT_INDEX);
        when(mAudioManager.isVolumeGroupMuted(CoreAudioRoutingUtils.MUSIC_GROUP_ID))
                .thenReturn(false);

        when(mAudioManager.getVolumeGroupIdForAttributes(CoreAudioRoutingUtils.NAV_ATTRIBUTES))
                .thenReturn(CoreAudioRoutingUtils.NAV_GROUP_ID);
        when(mAudioManager.getMinVolumeIndexForAttributes(eq(CoreAudioRoutingUtils.NAV_ATTRIBUTES)))
                .thenReturn(CoreAudioRoutingUtils.NAV_MIN_INDEX);
        when(mAudioManager.getMaxVolumeIndexForAttributes(eq(CoreAudioRoutingUtils.NAV_ATTRIBUTES)))
                .thenReturn(CoreAudioRoutingUtils.NAV_MAX_INDEX);
        when(mAudioManager.isVolumeGroupMuted(CoreAudioRoutingUtils.NAV_GROUP_ID))
                .thenReturn(false);

        when(mAudioManager.getVolumeGroupIdForAttributes(CoreAudioRoutingUtils.OEM_ATTRIBUTES))
                .thenReturn(CoreAudioRoutingUtils.OEM_GROUP_ID);
        when(mAudioManager.getMinVolumeIndexForAttributes(eq(CoreAudioRoutingUtils.OEM_ATTRIBUTES)))
                .thenReturn(CoreAudioRoutingUtils.OEM_MIN_INDEX);
        when(mAudioManager.getMaxVolumeIndexForAttributes(eq(CoreAudioRoutingUtils.OEM_ATTRIBUTES)))
                .thenReturn(CoreAudioRoutingUtils.OEM_MAX_INDEX);
        when(mAudioManager.isVolumeGroupMuted(CoreAudioRoutingUtils.OEM_GROUP_ID))
                .thenReturn(false);

        doReturn(CoreAudioRoutingUtils.MUSIC_GROUP_ID)
                .when(() -> CoreAudioHelper.getVolumeGroupIdForAudioAttributes(
                        CoreAudioRoutingUtils.MUSIC_ATTRIBUTES));
        doReturn(CoreAudioRoutingUtils.MUSIC_ATTRIBUTES)
                .when(() -> CoreAudioHelper.selectAttributesForVolumeGroupName(
                        CoreAudioRoutingUtils.MUSIC_GROUP_NAME));

        doReturn(CoreAudioRoutingUtils.NAV_GROUP_ID)
                .when(() -> CoreAudioHelper.getVolumeGroupIdForAudioAttributes(
                        CoreAudioRoutingUtils.NAV_ATTRIBUTES));
        doReturn(CoreAudioRoutingUtils.NAV_ATTRIBUTES)
                .when(() -> CoreAudioHelper.selectAttributesForVolumeGroupName(
                        CoreAudioRoutingUtils.NAV_GROUP_NAME));

        doReturn(CoreAudioRoutingUtils.OEM_GROUP_ID)
                .when(() -> CoreAudioHelper.getVolumeGroupIdForAudioAttributes(
                        CoreAudioRoutingUtils.OEM_ATTRIBUTES));
        doReturn(CoreAudioRoutingUtils.OEM_ATTRIBUTES)
                .when(() -> CoreAudioHelper.selectAttributesForVolumeGroupName(
                        CoreAudioRoutingUtils.OEM_GROUP_NAME));
    }

    private static AudioFocusInfo createAudioFocusInfoForMedia() {
        return createAudioFocusInfoForMedia(MEDIA_APP_UID);
    }

    private static AudioFocusInfo createAudioFocusInfoForMedia(int uid) {
        AudioAttributes.Builder builder = new AudioAttributes.Builder();
        builder.setUsage(USAGE_MEDIA);

        return new AudioFocusInfo(builder.build(), uid, MEDIA_CLIENT_ID,
                MEDIA_PACKAGE_NAME, AUDIOFOCUS_GAIN, AUDIOFOCUS_LOSS, MEDIA_EMPTY_FLAG, SDK_INT);
    }

    private List<Integer> getFocusChanges(AudioFocusInfo info) {
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(mAudioManager, atLeastOnce()).dispatchAudioFocusChange(eq(info), captor.capture(),
                any());
        return captor.getAllValues();
    }

    private void verifyMediaDuckingInfoInZone(ArgumentCaptor<List<CarDuckingInfo>>
            carDuckingInfosCaptor, int zoneId, String message) {
        expectWithMessage("Zone size of notified ducking info " + message)
                .that(carDuckingInfosCaptor.getValue().size()).isEqualTo(1);
        CarDuckingInfo duckingInfo = carDuckingInfosCaptor.getValue().get(0);
        expectWithMessage("Ducking info zone id " + message)
                .that(duckingInfo.mZoneId).isEqualTo(zoneId);
        expectWithMessage("Audio attributes holding focus " + message)
                .that(CarHalAudioUtils.metadataToAudioAttributes(duckingInfo
                        .mPlaybackMetaDataHoldingFocus))
                .containsExactly(CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA));
    }

    private CarAudioZoneConfigInfo getZoneConfigToSwitch(int zoneId) {
        CarAudioZoneConfigInfo currentZoneConfigInfo =
                mCarAudioService.getCurrentAudioZoneConfigInfo(zoneId);
        List<CarAudioZoneConfigInfo> zoneConfigInfos =
                mCarAudioService.getAudioZoneConfigInfos(zoneId);

        for (int index = 0; index < zoneConfigInfos.size(); index++) {
            if (!currentZoneConfigInfo.equals(zoneConfigInfos.get(index))) {
                return zoneConfigInfos.get(index);
            }
        }
        return null;
    }

    private static final class TestPrimaryZoneMediaAudioRequestCallback extends
            IPrimaryZoneMediaAudioRequestCallback.Stub {
        private long mRequestId = INVALID_REQUEST_ID;
        private CarOccupantZoneManager.OccupantZoneInfo mInfo;
        private CountDownLatch mStatusLatch = new CountDownLatch(1);
        private int mStatus;

        @Override
        public void onRequestMediaOnPrimaryZone(CarOccupantZoneManager.OccupantZoneInfo info,
                long requestId) {
            mInfo = info;
            mRequestId = requestId;
            mStatusLatch.countDown();
        }

        @Override
        public void onMediaAudioRequestStatusChanged(
                @NonNull CarOccupantZoneManager.OccupantZoneInfo info,
                long requestId, int status) {
            mInfo = info;
            mRequestId = requestId;
            mStatus = status;
            mStatusLatch.countDown();
        }

        private void waitForCallback() throws Exception {
            mStatusLatch.await(TEST_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        public void reset() {
            mInfo = null;
            mRequestId = INVALID_REQUEST_ID;
            mStatus = INVALID_STATUS;
            mStatusLatch = new CountDownLatch(1);
        }
    }

    private static final class TestMediaRequestStatusCallback extends
            IMediaAudioRequestStatusCallback.Stub {
        private long mRequestId = INVALID_REQUEST_ID;
        private CarOccupantZoneManager.OccupantZoneInfo mInfo;
        private int mStatus;
        private final CountDownLatch mStatusLatch = new CountDownLatch(1);

        @Override
        public void onMediaAudioRequestStatusChanged(
                @NonNull CarOccupantZoneManager.OccupantZoneInfo info,
                long requestId, @CarAudioManager.MediaAudioRequestStatus int status)
                throws RemoteException {
            mInfo = info;
            mRequestId = requestId;
            mStatus = status;
            mStatusLatch.countDown();
        }

        private void waitForCallback() throws Exception {
            mStatusLatch.await(TEST_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static final class TestAudioZonesMirrorStatusCallbackCallback extends
            IAudioZonesMirrorStatusCallback.Stub {

        private static final long TEST_CALLBACK_TIMEOUT_MS = 300;

        private List<int[]> mZoneIds = new ArrayList<>();
        private List<Integer> mStatus = new ArrayList<>();
        private int mNumberOfCalls = 0;
        private CountDownLatch mStatusLatch;

        private TestAudioZonesMirrorStatusCallbackCallback(int count) {
            mStatusLatch = new CountDownLatch(count);
        }

        @Override
        public void onAudioZonesMirrorStatusChanged(int[] zoneIds, int status) {
            mZoneIds.add(zoneIds);
            mStatus.add(status);
            mNumberOfCalls++;
            mStatusLatch.countDown();
        }

        private void waitForCallback() throws Exception {
            mStatusLatch.await(TEST_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        public void reset(int count) {
            mStatusLatch = new CountDownLatch(count);
        }

        private int[] getLastZoneIds() {
            return mZoneIds.get(mZoneIds.size() - 1);
        }

        private int[] getZoneIds(int index) {
            return mZoneIds.get(index);
        }

        public int getLastStatus() {
            return mStatus.get(mStatus.size() - 1);
        }

        public int getStatus(int index) {
            return mStatus.get(index);
        }
    }

    private static final class SwitchAudioZoneConfigCallbackImpl extends
            ISwitchAudioZoneConfigCallback.Stub {
        private CountDownLatch mStatusLatch = new CountDownLatch(1);
        private CarAudioZoneConfigInfo mZoneConfig;
        private boolean mIsSuccessful;

        @Override
        public void onAudioZoneConfigSwitched(CarAudioZoneConfigInfo zoneConfig,
                boolean isSuccessful) {
            mZoneConfig = zoneConfig;
            mIsSuccessful = isSuccessful;
            mStatusLatch.countDown();
        }

        private void waitForCallback() throws Exception {
            mStatusLatch.await(TEST_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        CarAudioZoneConfigInfo getZoneConfig() {
            return mZoneConfig;
        }

        boolean getSwitchStatus() {
            return mIsSuccessful;
        }
    }

    private static final class CarVolumeEventCallbackImpl extends ICarVolumeEventCallback.Stub {
        private CountDownLatch mStatusLatch = new CountDownLatch(1);
        private List<CarVolumeGroupEvent> mVolumeGroupEvents;

        @Override
        public void onVolumeGroupEvent(List<CarVolumeGroupEvent> volumeGroupEvents) {
            mVolumeGroupEvents = volumeGroupEvents;
            mStatusLatch.countDown();
        }

        @Override
        public void onMasterMuteChanged(int zoneId, int flags) {
            mStatusLatch.countDown();
        }

        private boolean waitForCallback() throws Exception {
            return mStatusLatch.await(TEST_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        List<CarVolumeGroupEvent> getVolumeGroupEvents() {
            return mVolumeGroupEvents;
        }

        public void reset() {
            mStatusLatch = new CountDownLatch(1);
        }
    }
}
