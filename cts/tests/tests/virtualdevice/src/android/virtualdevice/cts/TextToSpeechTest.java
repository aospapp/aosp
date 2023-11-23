/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static android.Manifest.permission.ADD_TRUSTED_DISPLAY;
import static android.Manifest.permission.CREATE_VIRTUAL_DEVICE;
import static android.Manifest.permission.MODIFY_AUDIO_ROUTING;
import static android.Manifest.permission.READ_CLIPBOARD_IN_BACKGROUND;
import static android.Manifest.permission.WAKE_LOCK;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_CUSTOM;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_AUDIO;
import static android.media.AudioAttributes.CONTENT_TYPE_SPEECH;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceParams;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Bundle;
import android.platform.test.annotations.AppModeFull;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.virtualdevice.cts.common.FakeAssociationRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = "VirtualDeviceManager cannot be accessed by instant apps")
public class TextToSpeechTest {
    private static final String TAG = TextToSpeechTest.class.getSimpleName();
    private static final String TTS_TEXT = "My hovercraft is full of eels";
    private static final String UTTERANCE_ID = "vdmTtsTestUtteranceId";

    @Rule
    public AdoptShellPermissionsRule mAdoptShellPermissionsRule = new AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            ACTIVITY_EMBEDDING,
            ADD_TRUSTED_DISPLAY,
            CREATE_VIRTUAL_DEVICE,
            READ_CLIPBOARD_IN_BACKGROUND,
            // Modify audio routing permission is needed because without it, the audio session id
            // entry in AudioPlaybackConfiguration is redacted.
            MODIFY_AUDIO_ROUTING,
            WAKE_LOCK);

    @Rule
    public FakeAssociationRule mFakeAssociationRule = new FakeAssociationRule();

    private VirtualDeviceManager mVirtualDeviceManager;
    private AudioManager mAudioManager;

    private SpeechPlaybackObserver mSpeechPlaybackObserver;

    @Before
    public void setUp() {
        Context context = getApplicationContext();
        final PackageManager packageManager = context.getPackageManager();
        assumeTrue(packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP));
        assumeTrue(packageManager.hasSystemFeature(
                PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS));
        mVirtualDeviceManager = context.getSystemService(VirtualDeviceManager.class);
        assumeNotNull(mVirtualDeviceManager);
        mAudioManager = context.getSystemService(AudioManager.class);
        assumeNotNull(mAudioManager);
        mSpeechPlaybackObserver = new SpeechPlaybackObserver();
        mAudioManager.registerAudioPlaybackCallback(mSpeechPlaybackObserver, /*handler=*/null);

    }

    @After
    public void tearDown() {
        if (mAudioManager != null) {
            mAudioManager.unregisterAudioPlaybackCallback(mSpeechPlaybackObserver);
        }
    }

    @Test
    public void textToSpeechWithVirtualDeviceContext_hasVdmSpecificSessionId() throws Exception {
        // Create virtual device with device specific audio session id.
        int virtualDeviceAudioSessionId = mAudioManager.generateAudioSessionId();
        try (VirtualDeviceManager.VirtualDevice virtualDevice =
                     createVirtualDeviceWithPlaybackSessionId(
                             virtualDeviceAudioSessionId)) {
            Context virtualDeviceContext = virtualDevice.createContext();

            // Instantiate TTS with device-specific context.
            TextToSpeech tts = initializeTextToSpeech(virtualDeviceContext);
            assumeNotNull(tts);

            try {
                tts.speak(TTS_TEXT, TextToSpeech.QUEUE_ADD, /*params=*/null, UTTERANCE_ID);

                // Wait for audio playback with SPEECH content.
                AudioPlaybackConfiguration ttsAudioPlaybackConfig =
                        mSpeechPlaybackObserver.getSpeechAudioPlaybackConfigFuture().get();

                // Verify the SPEECH playback has audio session id corresponding to virtual device.
                assertThat(ttsAudioPlaybackConfig.getSessionId()).isEqualTo(
                        virtualDeviceAudioSessionId);
            } finally {
                tts.shutdown();
            }
        }
    }

    @Test
    public void textToSpeechWithVirtualDeviceContext_explicitSessionIdOverridesVdmSessionId()
            throws Exception {
        // Create virtual device with device specific audio session id.
        int virtualDeviceAudioSessionId = mAudioManager.generateAudioSessionId();
        try (VirtualDeviceManager.VirtualDevice virtualDevice =
                     createVirtualDeviceWithPlaybackSessionId(
                             virtualDeviceAudioSessionId)) {
            Context virtualDeviceContext = virtualDevice.createContext();

            // Instantiate TTS with device-specific context.
            TextToSpeech tts = initializeTextToSpeech(virtualDeviceContext);
            assumeNotNull(tts);

            try {
                // Issue TTS.speak request with explicitly configured audio session id.
                int explicitlyRequestedAudioSessionId = mAudioManager.generateAudioSessionId();
                tts.speak(TTS_TEXT, TextToSpeech.QUEUE_ADD,
                        createAudioSessionIdParamForTts(explicitlyRequestedAudioSessionId),
                        UTTERANCE_ID);

                // Wait for audio playback with SPEECH content.
                AudioPlaybackConfiguration ttsAudioPlaybackConfig =
                                mSpeechPlaybackObserver.getSpeechAudioPlaybackConfigFuture().get();

                // Verify that explicitly requested audio session id has overridden the virtual
                // device audio session id.
                assertThat(ttsAudioPlaybackConfig.getSessionId()).isEqualTo(
                        explicitlyRequestedAudioSessionId);
            } finally {
                tts.shutdown();
            }
        }
    }

    private static Bundle createAudioSessionIdParamForTts(int sessionId) {
        Bundle bundle = new Bundle();
        bundle.putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, sessionId);
        return bundle;
    }

    private static @Nullable TextToSpeech initializeTextToSpeech(@NonNull Context context) {
        SettableFuture<Integer> ttsInitFuture = SettableFuture.create();
        TextToSpeech tts = new TextToSpeech(context, status -> ttsInitFuture.set(status));
        int status;
        try {
            status = Uninterruptibles.getUninterruptibly(ttsInitFuture, 5, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException exception) {
            Log.w(TAG, "Failed to initialize TTS", exception);
            return null;
        }
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, String.format("TextToSpeech initialization failed with %d", status));
            return null;
        }
        tts.setLanguage(Locale.US);
        return tts;
    }

    private VirtualDeviceManager.VirtualDevice createVirtualDeviceWithPlaybackSessionId(
            int audioPlaybackSessionId) {
        return mVirtualDeviceManager.createVirtualDevice(
                mFakeAssociationRule.getAssociationInfo().getId(),
                new VirtualDeviceParams.Builder()
                        .setDevicePolicy(POLICY_TYPE_AUDIO, DEVICE_POLICY_CUSTOM)
                        .setAudioPlaybackSessionId(audioPlaybackSessionId)
                        .build());
    }

    /**
     * Helper class implementing AudioPlaybackCallback to detect playback with SPEECH content type.
     */
    private static class SpeechPlaybackObserver extends AudioManager.AudioPlaybackCallback {

        private SettableFuture<AudioPlaybackConfiguration> mAudioPlaybackConfigurationFuture =
                SettableFuture.create();

        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            super.onPlaybackConfigChanged(configs);
            configs.stream().filter(c -> c.getAudioAttributes().getContentType()
                    == CONTENT_TYPE_SPEECH).findAny().ifPresent(
                    mAudioPlaybackConfigurationFuture::set);
        }

        /**
         * Get {@ListenableFuture} with observed SPEECH playb
         *
         * @return {@code ListenableFuture} instance which will be completed with
         *    @code AudioPlaybackConfiguration} corresponding to SPEECH playback once detected.
         */
        ListenableFuture<AudioPlaybackConfiguration> getSpeechAudioPlaybackConfigFuture() {
            return mAudioPlaybackConfigurationFuture;
        }
    }
}
