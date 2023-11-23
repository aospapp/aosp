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

package android.virtualdevice.cts;

import static android.Manifest.permission.ACTIVITY_EMBEDDING;
import static android.Manifest.permission.ADD_ALWAYS_UNLOCKED_DISPLAY;
import static android.Manifest.permission.CREATE_VIRTUAL_DEVICE;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_CUSTOM;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_AUDIO;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_SENSORS;
import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.content.Context.DEVICE_ID_INVALID;
import static android.hardware.Sensor.TYPE_ACCELEROMETER;
import static android.media.AudioManager.AUDIO_SESSION_ID_GENERATE;
import static android.media.AudioManager.FX_BACK;
import static android.media.AudioManager.FX_KEY_CLICK;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.util.concurrent.Uninterruptibles.tryAcquireUninterruptibly;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.annotation.Nullable;
import android.companion.virtual.VirtualDevice;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceParams;
import android.companion.virtual.sensor.VirtualSensor;
import android.companion.virtual.sensor.VirtualSensorCallback;
import android.companion.virtual.sensor.VirtualSensorConfig;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.platform.test.annotations.AppModeFull;
import android.virtualdevice.cts.common.FakeAssociationRule;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = "VirtualDeviceManager cannot be accessed by instant apps")
public class VirtualDeviceManagerBasicTest {

    private static final String VIRTUAL_DEVICE_NAME = "VirtualDeviceName";
    private static final String SENSOR_NAME = "VirtualSensorName";

    private static final VirtualDeviceParams DEFAULT_VIRTUAL_DEVICE_PARAMS =
            new VirtualDeviceParams.Builder().build();
    private static final VirtualDeviceParams NAMED_VIRTUAL_DEVICE_PARAMS =
            new VirtualDeviceParams.Builder()
                    .setName(VIRTUAL_DEVICE_NAME)
                    .build();
    private static final VirtualDisplayConfig DEFAULT_VIRTUAL_DISPLAY_CONFIG =
            new VirtualDisplayConfig.Builder("testDisplay", 100, 100, 100).build();

    @Rule
    public AdoptShellPermissionsRule mAdoptShellPermissionsRule = new AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            ACTIVITY_EMBEDDING,
            ADD_ALWAYS_UNLOCKED_DISPLAY,
            CREATE_VIRTUAL_DEVICE);

    @Mock
    private VirtualSensorCallback mVirtualSensorCallback;

    @Rule
    public FakeAssociationRule mFakeAssociationRule = new FakeAssociationRule();

    private VirtualDeviceManager mVirtualDeviceManager;
    @Nullable
    private VirtualDeviceManager.VirtualDevice mVirtualDevice;
    @Nullable
    private VirtualDeviceManager.VirtualDevice mAnotherVirtualDevice;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        Context context = getApplicationContext();
        final PackageManager packageManager = context.getPackageManager();
        assumeTrue(packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP));
        assumeTrue(packageManager.hasSystemFeature(
                PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS));
        mVirtualDeviceManager = context.getSystemService(VirtualDeviceManager.class);
    }

    @After
    public void tearDown() {
        if (mVirtualDevice != null && mVirtualDevice.getDeviceId() != DEVICE_ID_INVALID) {
            mVirtualDevice.close();
        }
        if (mAnotherVirtualDevice != null) {
            mAnotherVirtualDevice.close();
        }
    }

    @Test
    public void createVirtualDevice_shouldNotThrowException() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        assertThat(mVirtualDevice).isNotNull();
        assertThat(mVirtualDevice.getDeviceId()).isGreaterThan(DEVICE_ID_DEFAULT);
    }

    @Test
    public void createVirtualDevice_deviceIdIsUniqueAndIncremented() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        mAnotherVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        assertThat(mAnotherVirtualDevice).isNotNull();
        assertThat(mVirtualDevice.getDeviceId() + 1).isEqualTo(mAnotherVirtualDevice.getDeviceId());
    }

    @Test
    public void createVirtualDevice_noPermission_shouldThrowSecurityException() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();

        assertThrows(
                SecurityException.class,
                () -> mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS));
    }

    @Test
    public void createVirtualDevice_invalidAssociationId_shouldThrowIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mVirtualDeviceManager.createVirtualDevice(
                        /* associationId= */ -1,
                        DEFAULT_VIRTUAL_DEVICE_PARAMS));
    }

    @Test
    public void createVirtualDevice_close_isClosed() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);

        mVirtualDevice.close();

        assertThat(mVirtualDevice.getDeviceId()).isEqualTo(DEVICE_ID_INVALID);
    }

    @Test
    public void createVirtualDevice_closeMultipleTimes_isSafe() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);

        mVirtualDevice.close();
        mVirtualDevice.close();

        assertThat(mVirtualDevice.getDeviceId()).isEqualTo(DEVICE_ID_INVALID);
    }

    @Test
    public void createVirtualDevice_removeAssociation_shouldCloseVirtualDevice()
            throws InterruptedException {
        // TODO(b/282629983): Re-enable test for Automotive once CDM Listener supports more users.
        assumeFalse("Skipping test: not supported on automotive",
                getApplicationContext().getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE));

        CountDownLatch latch = new CountDownLatch(1);

        // Create device with a display and ensure it is all set up
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        VirtualDisplay display = mVirtualDevice.createVirtualDisplay(
                DEFAULT_VIRTUAL_DISPLAY_CONFIG,
                getApplicationContext().getMainExecutor(),
                null);

        DisplayManager displayManager = getApplicationContext().getSystemService(
                DisplayManager.class);
        displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                if (displayId == display.getDisplay().getDisplayId()) {
                    latch.countDown();
                }
            }

            @Override
            public void onDisplayChanged(int displayId) {
            }
        }, null);
        assertThat(display).isNotNull();
        assertThat(display.getDisplay().isValid()).isTrue();

        mFakeAssociationRule.disassociate();
        latch.await(5, TimeUnit.SECONDS);

        // Ensure device is closed properly and the display is removed
        assertThat(display.getDisplay().isValid()).isFalse();
        assertThat(mVirtualDevice.getDeviceId()).isEqualTo(DEVICE_ID_INVALID);

        // Ensure the virtual device can no longer setup new functionality
        assertThrows(SecurityException.class, () -> mVirtualDevice.createVirtualDisplay(
                DEFAULT_VIRTUAL_DISPLAY_CONFIG, null,  null));
    }

    @Test
    public void createVirtualDevice_removeAssociationAndCloseAfterwards_isSafe()
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        // Create device with a display and ensure it is all set up
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        VirtualDisplay display = mVirtualDevice.createVirtualDisplay(
                DEFAULT_VIRTUAL_DISPLAY_CONFIG,
                getApplicationContext().getMainExecutor(),
                null);

        DisplayManager displayManager = getApplicationContext().getSystemService(
                DisplayManager.class);
        displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                if (displayId == display.getDisplay().getDisplayId()) {
                    latch.countDown();
                }
            }

            @Override
            public void onDisplayChanged(int displayId) {
            }
        }, null);

        mFakeAssociationRule.disassociate();
        latch.await(5, TimeUnit.SECONDS);

        mVirtualDevice.close();

        assertThat(mVirtualDevice.getDeviceId()).isEqualTo(DEVICE_ID_INVALID);
    }

    @Test
    public void createVirtualDevice_closeAndRemoveAssociation_isSafe()
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        // Create device with a display and ensure it is all set up
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        VirtualDisplay display = mVirtualDevice.createVirtualDisplay(
                DEFAULT_VIRTUAL_DISPLAY_CONFIG,
                getApplicationContext().getMainExecutor(),
                null);

        DisplayManager displayManager = getApplicationContext().getSystemService(
                DisplayManager.class);
        displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                if (displayId == display.getDisplay().getDisplayId()) {
                    latch.countDown();
                }
            }

            @Override
            public void onDisplayChanged(int displayId) {
            }
        }, null);

        mVirtualDevice.close();
        mFakeAssociationRule.disassociate();
        latch.await(5, TimeUnit.SECONDS);

        assertThat(mVirtualDevice.getDeviceId()).isEqualTo(DEVICE_ID_INVALID);
    }

    @Test
    public void createVirtualDevice_removeAssociationAndClose_isSafe()
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        // Create device with a display and ensure it is all set up
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        VirtualDisplay display = mVirtualDevice.createVirtualDisplay(
                DEFAULT_VIRTUAL_DISPLAY_CONFIG,
                getApplicationContext().getMainExecutor(),
                null);

        DisplayManager displayManager = getApplicationContext().getSystemService(
                DisplayManager.class);
        displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                if (displayId == display.getDisplay().getDisplayId()) {
                    latch.countDown();
                }
            }

            @Override
            public void onDisplayChanged(int displayId) {
            }
        }, null);

        mFakeAssociationRule.disassociate();
        mVirtualDevice.close();
        latch.await(5, TimeUnit.SECONDS);

        assertThat(mVirtualDevice.getDeviceId()).isEqualTo(DEVICE_ID_INVALID);
    }

    /**
     * It is expected that there are zero VirtualDevices active on a new device. If this test fails
     * some application may have created a VirtualDevice before this test was run. Clear all
     * VirtualDevices (or disassociate the related CDM associations) before re-running this test.
     */
    @Test
    public void getVirtualDevices_noVirtualDevices_returnsEmptyList() {
        assertWithMessage(
                "Expected no previous VirtualDevices. Please remove all VirtualDevices before "
                        + "running this test.").that(
                mVirtualDeviceManager.getVirtualDevices()).isEmpty();
    }

    @Test
    public void getVirtualDevices_returnsAllVirtualDevices() {
        // Take into account pre-existing VirtualDevices
        List<VirtualDevice> previousVirtualDevices = mVirtualDeviceManager.getVirtualDevices();
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        mAnotherVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        NAMED_VIRTUAL_DEVICE_PARAMS);
        assertThat(mAnotherVirtualDevice).isNotNull();

        List<VirtualDevice> virtualDevices = mVirtualDeviceManager.getVirtualDevices();
        for (VirtualDevice previousVirtualDevice : previousVirtualDevices) {
            virtualDevices.remove(previousVirtualDevice);
        }
        assertThat(virtualDevices.size()).isEqualTo(2);

        VirtualDevice device = virtualDevices.get(0);
        assertThat(device.getDeviceId()).isEqualTo(mVirtualDevice.getDeviceId());
        assertThat(device.getName()).isNull();

        VirtualDevice anotherDevice = virtualDevices.get(1);
        assertThat(anotherDevice.getDeviceId()).isEqualTo(mAnotherVirtualDevice.getDeviceId());
        assertThat(anotherDevice.getName()).isEqualTo(VIRTUAL_DEVICE_NAME);
    }

    @Test
    public void createDeviceContext_invalidDeviceId_shouldThrowIllegalArgumentException() {
        Context context = getApplicationContext();

        assertThrows(
                IllegalArgumentException.class,
                () -> context.createDeviceContext(DEVICE_ID_INVALID));
    }

    @Test
    public void createDeviceContext_missingDeviceId_shouldThrowIllegalArgumentException() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        Context context = getApplicationContext();

        assertThrows(
                IllegalArgumentException.class,
                () -> context.createDeviceContext(mVirtualDevice.getDeviceId() + 1));
    }

    @Test
    public void createDeviceContext_defaultDeviceId() {
        Context context = getApplicationContext();
        Context defaultDeviceContext = context.createDeviceContext(DEVICE_ID_DEFAULT);

        assertThat(defaultDeviceContext.getDeviceId()).isEqualTo(DEVICE_ID_DEFAULT);
    }

    @Test
    public void createDeviceContext_validVirtualDeviceId() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        Context context = getApplicationContext();
        Context virtualDeviceContext =
                context.createDeviceContext(mVirtualDevice.getDeviceId());

        assertThat(virtualDeviceContext.getDeviceId()).isEqualTo(mVirtualDevice.getDeviceId());

        // The default device context should be available from the virtual device one.
        Context defaultDeviceContext = virtualDeviceContext.createDeviceContext(DEVICE_ID_DEFAULT);

        assertThat(defaultDeviceContext.getDeviceId()).isEqualTo(DEVICE_ID_DEFAULT);
    }

    @Test
    public void createContext_returnsCorrectContext() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        Context deviceContext = mVirtualDevice.createContext();
        assertThat(deviceContext.getDeviceId()).isEqualTo(mVirtualDevice.getDeviceId());
    }

    @Test
    public void createContext_multipleDevices_returnCorrectContexts() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        mAnotherVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        NAMED_VIRTUAL_DEVICE_PARAMS);
        assertThat(mVirtualDevice.createContext().getDeviceId()).isEqualTo(
                mVirtualDevice.getDeviceId());
        assertThat(mAnotherVirtualDevice.createContext().getDeviceId()).isEqualTo(
                mAnotherVirtualDevice.getDeviceId());
    }

    @Test
    public void getDevicePolicy_noPolicySpecified_shouldReturnDefault() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);

        assertThat(
                mVirtualDeviceManager.getDevicePolicy(mVirtualDevice.getDeviceId(),
                        POLICY_TYPE_SENSORS))
                .isEqualTo(DEVICE_POLICY_DEFAULT);
    }

    @Test
    public void getDevicePolicy_shouldReturnConfiguredValue() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        new VirtualDeviceParams.Builder()
                                .setDevicePolicy(POLICY_TYPE_SENSORS, DEVICE_POLICY_CUSTOM)
                                .build());

        assertThat(
                mVirtualDeviceManager.getDevicePolicy(mVirtualDevice.getDeviceId(),
                        POLICY_TYPE_SENSORS))
                .isEqualTo(DEVICE_POLICY_CUSTOM);
    }

    @Test
    public void getDevicePolicy_virtualDeviceClosed_shouldReturnDefault() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        new VirtualDeviceParams.Builder()
                                .setDevicePolicy(POLICY_TYPE_SENSORS, DEVICE_POLICY_CUSTOM)
                                .build());
        mVirtualDevice.close();

        assertThat(
                mVirtualDeviceManager.getDevicePolicy(mVirtualDevice.getDeviceId(),
                        POLICY_TYPE_SENSORS))
                .isEqualTo(DEVICE_POLICY_DEFAULT);

        mVirtualDevice = null;
    }

    @Test
    public void getVirtualSensorList_noSensorsConfigured_isEmpty() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        new VirtualDeviceParams.Builder().build());

        assertThat(mVirtualDevice.getVirtualSensorList()).isEmpty();
    }

    @Test
    public void getVirtualSensorList_withConfiguredSensor() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        new VirtualDeviceParams.Builder()
                                .setDevicePolicy(POLICY_TYPE_SENSORS, DEVICE_POLICY_CUSTOM)
                                .addVirtualSensorConfig(
                                        new VirtualSensorConfig.Builder(
                                                TYPE_ACCELEROMETER, SENSOR_NAME)
                                                .build())
                                .setVirtualSensorCallback(BackgroundThread.getExecutor(),
                                        mVirtualSensorCallback)
                                .build());

        List<VirtualSensor> sensorList = mVirtualDevice.getVirtualSensorList();
        assertThat(sensorList).hasSize(1);
        VirtualSensor sensor = sensorList.get(0);
        assertThat(sensor.getType()).isEqualTo(TYPE_ACCELEROMETER);
        assertThat(sensor.getName()).isEqualTo(SENSOR_NAME);
    }

    @Test
    public void getAudioSessionIds_noIdsSpecified_shouldReturnPlaceholderValue() {
        mVirtualDevice = mVirtualDeviceManager.createVirtualDevice(
                mFakeAssociationRule.getAssociationInfo().getId(),
                new VirtualDeviceParams.Builder().build());

        assertThat(mVirtualDeviceManager.getAudioPlaybackSessionId(
                mVirtualDevice.getDeviceId())).isEqualTo(AUDIO_SESSION_ID_GENERATE);
        assertThat(mVirtualDeviceManager.getAudioRecordingSessionId(
                mVirtualDevice.getDeviceId())).isEqualTo(AUDIO_SESSION_ID_GENERATE);
    }

    @Test
    public void getAudioSessionIds_withIdsSpecified_shouldReturnPlaceholderValue() {
        int playbackSessionId = 42;
        int recordingSessionId = 77;
        mVirtualDevice = mVirtualDeviceManager.createVirtualDevice(
                mFakeAssociationRule.getAssociationInfo().getId(),
                new VirtualDeviceParams.Builder().setDevicePolicy(POLICY_TYPE_AUDIO,
                        DEVICE_POLICY_CUSTOM).setAudioPlaybackSessionId(
                        playbackSessionId).setAudioRecordingSessionId(recordingSessionId).build());

        assertThat(mVirtualDeviceManager.getAudioPlaybackSessionId(
                mVirtualDevice.getDeviceId())).isEqualTo(playbackSessionId);
        assertThat(mVirtualDeviceManager.getAudioRecordingSessionId(
                mVirtualDevice.getDeviceId())).isEqualTo(recordingSessionId);
    }

    @Test
    public void playSoundEffect_callsListener() {
        mVirtualDevice = mVirtualDeviceManager.createVirtualDevice(
                mFakeAssociationRule.getAssociationInfo().getId(),
                new VirtualDeviceParams.Builder().setDevicePolicy(POLICY_TYPE_AUDIO,
                        DEVICE_POLICY_CUSTOM).build());
        SoundEffectListenerForTest soundEffectListener = new SoundEffectListenerForTest();
        mVirtualDevice.addSoundEffectListener(runnable -> runnable.run(), soundEffectListener);

        mVirtualDeviceManager.playSoundEffect(mVirtualDevice.getDeviceId(), FX_KEY_CLICK);
        mVirtualDeviceManager.playSoundEffect(mVirtualDevice.getDeviceId(), FX_BACK);
        soundEffectListener.waitUntilCalled(/*nrTimes=*/2);

        assertThat(soundEffectListener.getObservedSoundEffects()).isEqualTo(
                List.of(FX_KEY_CLICK, FX_BACK));
    }

    @Test
    public void playSoundEffect_callsMultipleListeners() {
        mVirtualDevice = mVirtualDeviceManager.createVirtualDevice(
                mFakeAssociationRule.getAssociationInfo().getId(),
                new VirtualDeviceParams.Builder().setDevicePolicy(POLICY_TYPE_AUDIO,
                        DEVICE_POLICY_CUSTOM).build());
        SoundEffectListenerForTest soundEffectListener1 = new SoundEffectListenerForTest();
        SoundEffectListenerForTest soundEffectListener2 = new SoundEffectListenerForTest();
        mVirtualDevice.addSoundEffectListener(runnable -> runnable.run(), soundEffectListener1);
        mVirtualDevice.addSoundEffectListener(runnable -> runnable.run(), soundEffectListener2);

        mVirtualDeviceManager.playSoundEffect(mVirtualDevice.getDeviceId(), FX_KEY_CLICK);
        mVirtualDeviceManager.playSoundEffect(mVirtualDevice.getDeviceId(), FX_BACK);
        soundEffectListener1.waitUntilCalled(/*nrTimes=*/2);
        soundEffectListener2.waitUntilCalled(/*nrTimes=*/2);

        assertThat(soundEffectListener1.getObservedSoundEffects()).isEqualTo(
                List.of(FX_KEY_CLICK, FX_BACK));
        assertThat(soundEffectListener2.getObservedSoundEffects()).isEqualTo(
                List.of(FX_KEY_CLICK, FX_BACK));
    }

    @Test
    public void playSoundEffect_unregistersListener() {
        mVirtualDevice = mVirtualDeviceManager.createVirtualDevice(
                mFakeAssociationRule.getAssociationInfo().getId(),
                new VirtualDeviceParams.Builder().setDevicePolicy(POLICY_TYPE_AUDIO,
                        DEVICE_POLICY_CUSTOM).build());
        SoundEffectListenerForTest soundEffectListener1 = new SoundEffectListenerForTest();
        SoundEffectListenerForTest soundEffectListener2 = new SoundEffectListenerForTest();
        mVirtualDevice.addSoundEffectListener(runnable -> runnable.run(), soundEffectListener1);
        mVirtualDevice.addSoundEffectListener(runnable -> runnable.run(), soundEffectListener2);

        mVirtualDeviceManager.playSoundEffect(mVirtualDevice.getDeviceId(), FX_KEY_CLICK);
        // Wait for listeners to be called before removing the second listener.
        soundEffectListener1.waitUntilCalled(/*nrTimes=*/1);
        soundEffectListener2.waitUntilCalled(/*nrTimes=*/1);
        mVirtualDevice.removeSoundEffectListener(soundEffectListener2);
        mVirtualDeviceManager.playSoundEffect(mVirtualDevice.getDeviceId(), FX_BACK);

        assertThat(soundEffectListener1.waitUntilCalled(/*nrTimes=*/1)).isTrue();
        // Second listener is not called after removal.
        assertThat(soundEffectListener2.waitUntilCalled(/*nrTimes=*/1)).isFalse();
        assertThat(soundEffectListener1.getObservedSoundEffects()).isEqualTo(
                List.of(FX_KEY_CLICK, FX_BACK));
        assertThat(soundEffectListener2.getObservedSoundEffects()).isEqualTo(
                List.of(FX_KEY_CLICK));
    }

    @Test
    public void playSoundEffect_incorrectDeviceId_doesNothing() {
        mVirtualDevice = mVirtualDeviceManager.createVirtualDevice(
                mFakeAssociationRule.getAssociationInfo().getId(),
                new VirtualDeviceParams.Builder().setDevicePolicy(POLICY_TYPE_AUDIO,
                        DEVICE_POLICY_CUSTOM).build());
        SoundEffectListenerForTest soundEffectListener = new SoundEffectListenerForTest();
        mVirtualDevice.addSoundEffectListener(runnable -> runnable.run(), soundEffectListener);

        mVirtualDeviceManager.playSoundEffect(mVirtualDevice.getDeviceId() + 1, FX_KEY_CLICK);

        assertThat(soundEffectListener.waitUntilCalled(/*nrTimes=1*/1)).isFalse();
        assertThat(soundEffectListener.getObservedSoundEffects()).isEmpty();
    }

    @Test
    public void createVirtualDevice_nullArguments_shouldThrow() {
        assertThrows(NullPointerException.class,
                () -> mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(), null));
    }

    @Test
    public void createVirtualDisplay_nullArguments_shouldThrow() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.createVirtualDisplay(null, null, null));
    }

    @Test
    public void addSoundEffectListener_nullArguments_shouldThrow() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.addSoundEffectListener(null, effectType -> {
                }));

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.addSoundEffectListener(Executors.newSingleThreadExecutor(),
                        null));
    }

    @Test
    public void removeSoundEffectListener_nullArguments_shouldThrow() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.removeSoundEffectListener(null));
    }

    @Test
    public void addActivityListener_nullArguments_shouldThrow() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.addActivityListener(null,
                        new VirtualDeviceManager.ActivityListener() {
                            @Override
                            public void onTopActivityChanged(int displayId,
                                    @NonNull ComponentName topActivity) {

                            }

                            @Override
                            public void onDisplayEmpty(int displayId) {

                            }
                        }));

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.addActivityListener(Executors.newSingleThreadExecutor(),
                        null));
    }

    @Test
    public void removeActivityListener_nullArguments_shouldThrow() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.removeActivityListener(null));
    }

    @Test
    public void createVirtualMouse_nullArguments_shouldThrow() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.createVirtualMouse(null));
    }

    @Test
    public void createVirtualTouchscreen_nullArguments_shouldThrow() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.createVirtualTouchscreen(null));
    }

    @Test
    public void createVirtualDpad_nullArguments_shouldThrow() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.createVirtualDpad(null));
    }

    @Test
    public void createVirtualKeyboard_nullArguments_shouldThrow() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.createVirtualKeyboard(null));
    }

    @Test
    public void createVirtualNavigationTouchpad_nullArguments_shouldThrow() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.createVirtualNavigationTouchpad(null));
    }

    private static class SoundEffectListenerForTest
            implements VirtualDeviceManager.SoundEffectListener {
        private final Semaphore mSemaphore = new Semaphore(0);
        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private final ArrayList<Integer> mObservedSoundEffects = new ArrayList<>();

        /**
         * Return observed sound effects.
         */
        public List<Integer> getObservedSoundEffects() {
            synchronized (mLock) {
                // Return observed sound effects regardless of whether we actually acquired the
                // semaphore permits or the attempt time out.
                return List.copyOf(mObservedSoundEffects);
            }
        }


        /**
         * Wait until listener is called specifies number of times or until the timeout expires.
         *
         * @return true if the listener was called before timeout expired.
         */
        public boolean waitUntilCalled(int nrTimes) {
            return tryAcquireUninterruptibly(mSemaphore, nrTimes, 1000, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onPlaySoundEffect(int effectType) {
            synchronized (mLock) {
                mObservedSoundEffects.add(effectType);
                mSemaphore.release();
            }
        }
    }
}

