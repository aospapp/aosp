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

package android.media.cts;

import static org.junit.Assert.assertThrows;

import android.annotation.NonNull;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.Spatializer;
import android.util.Log;

import com.android.compatibility.common.util.CtsAndroidTestCase;
import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@NonMediaMainlineTest
public class SpatializerTest extends CtsAndroidTestCase {

    private AudioManager mAudioManager;
    private static final String TAG = "SpatializerTest";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mAudioManager = (AudioManager) getContext().getSystemService(AudioManager.class);
    }

    public void testGetSpatializer() {
        Spatializer spat = mAudioManager.getSpatializer();
        assertNotNull("Spatializer shouldn't be null", spat);
    }

    public void testUnsupported() {
        Spatializer spat = mAudioManager.getSpatializer();
        if (spat.getImmersiveAudioLevel() != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE) {
            Log.i(TAG, "skipping testUnsupported, functionality supported");
            return;
        }
        assertFalse(spat.isEnabled());
        assertFalse(spat.isAvailable());
    }

    public void testSpatializerStateListenerManagement() {
        final Spatializer spat = mAudioManager.getSpatializer();
        final MySpatStateListener stateListener = new MySpatStateListener();

        // add listener:
        // verify null arg checks
        assertThrows("null Executor allowed in addOnSpatializerStateChangedListener",
                NullPointerException.class,
                () -> spat.addOnSpatializerStateChangedListener(null, stateListener));
        assertThrows("null listener allowed in addOnSpatializerStateChangedListener",
                NullPointerException.class,
                () -> spat.addOnSpatializerStateChangedListener(
                        Executors.newSingleThreadExecutor(),null));

        spat.addOnSpatializerStateChangedListener(Executors.newSingleThreadExecutor(),
                stateListener);
        // verify double add
        assertThrows("duplicate listener allowed in addOnSpatializerStateChangedListener",
                IllegalArgumentException.class,
                () -> spat.addOnSpatializerStateChangedListener(Executors.newSingleThreadExecutor(),
                        stateListener));

        // remove listener:
        // verify null arg check
        assertThrows("null listener allowed in removeOnSpatializerStateChangedListener",
                NullPointerException.class,
                () -> spat.removeOnSpatializerStateChangedListener(null));

        // verify unregistered listener
        assertThrows("unregistered listener allowed in removeOnSpatializerStateChangedListener",
                IllegalArgumentException.class,
                () -> spat.removeOnSpatializerStateChangedListener(new MySpatStateListener()));

        spat.removeOnSpatializerStateChangedListener(stateListener);
        // verify double remove
        assertThrows("double listener removal allowed in removeOnSpatializerStateChangedListener",
                IllegalArgumentException.class,
                () -> spat.removeOnSpatializerStateChangedListener(stateListener));
    }

    public void testMinSpatializationCapabilities() {
        Spatializer spat = mAudioManager.getSpatializer();
        if (spat.getImmersiveAudioLevel() == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE) {
            Log.i(TAG, "skipping testMinSpatializationCapabilities, no Spatializer");
            return;
        }
        if (!spat.isAvailable()) {
            Log.i(TAG, "skipping testMinSpatializationCapabilities, Spatializer not available");
            return;
        }
        for (int sampleRate : new int[] { 44100, 4800 }) {
            AudioFormat minFormat = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build();
            for (int usage : new int[] { AudioAttributes.USAGE_MEDIA,
                                         AudioAttributes.USAGE_GAME}) {
                AudioAttributes defAttr = new AudioAttributes.Builder()
                        .setUsage(usage)
                        .build();
                assertTrue("AudioAttributes usage:" + usage + " at " + sampleRate
                        + " should be virtualizeable", spat.canBeSpatialized(defAttr, minFormat));
            }
        }
    }

    public void testVirtualizerEnabled() throws Exception {
        Spatializer spat = mAudioManager.getSpatializer();
        if (spat.getImmersiveAudioLevel() == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE) {
            Log.i(TAG, "skipping testVirtualizerEnabled, no Spatializer");
            return;
        }
        boolean spatEnabled = spat.isEnabled();
        final MySpatStateListener stateListener = new MySpatStateListener();

        spat.addOnSpatializerStateChangedListener(Executors.newSingleThreadExecutor(),
                stateListener);
        getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity("android.permission.MODIFY_DEFAULT_AUDIO_EFFECTS");

        spat.setEnabled(!spatEnabled);
        getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
        assertEquals("VirtualizerStage enabled state differ",
                !spatEnabled, spat.isEnabled());
        Boolean enabled = stateListener.getEnabled();
        assertNotNull("VirtualizerStage state listener wasn't called", enabled);
        assertEquals("VirtualizerStage state listener didn't get expected value",
                !spatEnabled, enabled.booleanValue());
    }

    static class MySpatStateListener
            implements Spatializer.OnSpatializerStateChangedListener {

        private final Object mCbEnaLock = new Object();
        private final Object mCbAvailLock = new Object();
        @GuardedBy("mCbEnaLock")
        private Boolean mEnabled = null;
        @GuardedBy("mCbEnaLock")
        private final LinkedBlockingQueue<Boolean> mEnabledQueue =
                new LinkedBlockingQueue<Boolean>();
        @GuardedBy("mCbAvailLock")
        private Boolean mAvailable = null;
        @GuardedBy("mCbAvailLock")
        private final LinkedBlockingQueue<Boolean> mAvailableQueue =
                new LinkedBlockingQueue<Boolean>();

        private static final int LISTENER_WAIT_TIMEOUT_MS = 3000;
        void reset() {
            synchronized (mCbEnaLock) {
                synchronized (mCbAvailLock) {
                    mEnabled = null;
                    mEnabledQueue.clear();
                    mAvailable = null;
                    mAvailableQueue.clear();
                }
            }
        }

        Boolean getEnabled() {
            synchronized (mCbEnaLock) {
                while (mEnabled == null) {
                    try {
                        mEnabled = mEnabledQueue.poll(
                                LISTENER_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                        if (mEnabled == null) { // timeout
                            break;
                        }
                    } catch (InterruptedException e) {
                    }
                }
            }
            return mEnabled;
        }

        Boolean getAvailable() {
            synchronized (mCbAvailLock) {
                while (mAvailable == null) {
                    try {
                        mAvailable = mAvailableQueue.poll(
                                LISTENER_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                        if (mAvailable == null) { // timeout
                            break;
                        }
                    } catch (InterruptedException e) {
                    }
                }
            }
            return mAvailable;
        }

        MySpatStateListener() {
            reset();
        }

        @Override
        public void onSpatializerEnabledChanged(Spatializer spat, boolean enabled) {
            synchronized (mCbEnaLock) {
                try {
                    mEnabledQueue.put(enabled);
                } catch (InterruptedException e) {
                    fail("Failed to put enabled event in queue");
                }
            }
        }

        @Override
        public void onSpatializerAvailableChanged(@NonNull Spatializer spat, boolean available) {
            synchronized (mCbAvailLock) {
                try {
                    mAvailableQueue.put(available);
                } catch (InterruptedException e) {
                    fail("Failed to put available event in queue");
                }
            }
        }
    }
}
