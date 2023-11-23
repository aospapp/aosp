/*
 * Copyright 2019 The Android Open Source Project
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
 * limitations under the License
 */

package android.media.cts;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test media activity which has called {@link Activity#setMediaController}.
 */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class MediaActivityTest {
    private static final String TAG = "MediaActivityTest";
    private static final int WAIT_TIME_MS = 500;
    private static final List<Integer> ALL_VOLUME_STREAMS = new ArrayList();
    static {
        ALL_VOLUME_STREAMS.add(AudioManager.STREAM_ACCESSIBILITY);
        ALL_VOLUME_STREAMS.add(AudioManager.STREAM_ALARM);
        ALL_VOLUME_STREAMS.add(AudioManager.STREAM_DTMF);
        ALL_VOLUME_STREAMS.add(AudioManager.STREAM_MUSIC);
        ALL_VOLUME_STREAMS.add(AudioManager.STREAM_NOTIFICATION);
        ALL_VOLUME_STREAMS.add(AudioManager.STREAM_RING);
        ALL_VOLUME_STREAMS.add(AudioManager.STREAM_SYSTEM);
        ALL_VOLUME_STREAMS.add(AudioManager.STREAM_VOICE_CALL);
    }

    private Instrumentation mInstrumentation;
    private Context mContext;
    private boolean mUseFixedVolume;
    private AudioManager mAudioManager;
    private Map<Integer, Integer> mStreamVolumeMap = new HashMap<>();
    private MediaSession mSession;

    @Rule
    public ActivityTestRule<MediaSessionTestActivity> mActivityRule =
            new ActivityTestRule<>(MediaSessionTestActivity.class, false, false);

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getContext();
        mUseFixedVolume = mContext.getResources().getBoolean(
                Resources.getSystem().getIdentifier("config_useFixedVolume", "bool", "android"));
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);

        mStreamVolumeMap.clear();
        for (Integer stream : ALL_VOLUME_STREAMS) {
            mStreamVolumeMap.put(stream, mAudioManager.getStreamVolume(stream));
        }

        mSession = new MediaSession(mContext, TAG);
        mSession.setPlaybackToLocal(new AudioAttributes.Builder()
                .setLegacyStreamType(AudioManager.STREAM_MUSIC).build());

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(MediaSessionTestActivity.KEY_SESSION_TOKEN, mSession.getSessionToken());
        mActivityRule.launchActivity(intent);
    }

    @After
    public void cleanUp() {
        if (mSession != null) {
            mSession.release();
            mSession = null;
        }

        for (int stream : mStreamVolumeMap.keySet()) {
            int volume = mStreamVolumeMap.get(stream);
            mAudioManager.setStreamVolume(stream, volume, 0);
        }
    }

    /**
     * Tests whether volume key changes volume with the session's stream.
     */
    @Test
    public void testVolumeKey_whileSessionAlive() throws InterruptedException {
        if (mUseFixedVolume) {
            return;
        }

        final int testStream = mSession.getController().getPlaybackInfo().getAudioAttributes()
                .getVolumeControlStream();
        final int testKeyCode;
        if (mStreamVolumeMap.get(testStream) == mAudioManager.getStreamMinVolume(testStream)) {
            testKeyCode = KeyEvent.KEYCODE_VOLUME_UP;
        } else {
            testKeyCode = KeyEvent.KEYCODE_VOLUME_DOWN;
        }

        // The first event can be ignored and show volume panel instead. Use double tap.
        sendKeyEvent(testKeyCode);
        sendKeyEvent(testKeyCode);

        Thread.sleep(WAIT_TIME_MS);
        assertNotEquals((int) mStreamVolumeMap.get(testStream),
                mAudioManager.getStreamVolume(testStream));
    }

    /**
     * Tests whether volume key changes volume even after the session is released.
     */
    @Test
    public void testVolumeKey_afterSessionReleased() throws InterruptedException {
        if (mUseFixedVolume) {
            return;
        }

        mSession.release();

        // The first event can be ignored and show volume panel instead. Use double tap.
        sendKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN);
        sendKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN);

        // We cannot know which stream is changed. Need to monitor all streams.
        Thread.sleep(WAIT_TIME_MS);
        for (int stream : mStreamVolumeMap.keySet()) {
            int volume = mStreamVolumeMap.get(stream);
            if (mAudioManager.getStreamVolume(stream) != volume) {
                return;
            }
        }

        // Volume can be already zero for the target stream, so try again with the volume up.
        sendKeyEvent(KeyEvent.KEYCODE_VOLUME_UP);
        sendKeyEvent(KeyEvent.KEYCODE_VOLUME_UP);

        Thread.sleep(WAIT_TIME_MS);
        for (int stream : mStreamVolumeMap.keySet()) {
            int volume = mStreamVolumeMap.get(stream);
            if (mAudioManager.getStreamVolume(stream) != volume) {
                return;
            }
        }
        fail("Volume keys were ignored");
    }

    private void sendKeyEvent(int keyCode) {
        final long downTime = SystemClock.uptimeMillis();
        final KeyEvent down = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0);
        final long upTime = SystemClock.uptimeMillis();
        final KeyEvent up = new KeyEvent(downTime, upTime, KeyEvent.ACTION_UP, keyCode, 0);
        mInstrumentation.sendKeySync(down);
        mInstrumentation.sendKeySync(up);
    }
}
