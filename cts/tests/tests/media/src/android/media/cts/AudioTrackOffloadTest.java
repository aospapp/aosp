/*
 **
 ** Copyright 2018, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package android.media.cts;

import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.android.compatibility.common.util.CtsAndroidTestCase;

import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;

public class AudioTrackOffloadTest extends CtsAndroidTestCase {
    private static final String TAG = "AudioTrackOffloadTest";

    private static final int MP3_BUFF_SIZE = 192 * 1024 * 3 / 8; // 3s for 192kbps MP3

    private static final int PRESENTATION_END_TIMEOUT_MS = 8 * 1000; // 8s

    private static final AudioAttributes DEFAULT_ATTR = new AudioAttributes.Builder().build();

    private static final AudioFormat DEFAULT_FORMAT = new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_MP3)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build();

    public void testIsOffloadSupportedNullFormat() throws Exception {
        try {
            final boolean offloadableFormat = AudioManager.isOffloadedPlaybackSupported(null,
                    DEFAULT_ATTR);
            fail("Shouldn't be able to use null AudioFormat in isOffloadedPlaybackSupported()");
        } catch (NullPointerException e) {
            // ok, NPE is expected here
        }
    }

    public void testIsOffloadSupportedNullAttributes() throws Exception {
        try {
            final boolean offloadableFormat = AudioManager.isOffloadedPlaybackSupported(
                    DEFAULT_FORMAT, null);
            fail("Shouldn't be able to use null AudioAttributes in isOffloadedPlaybackSupported()");
        } catch (NullPointerException e) {
            // ok, NPE is expected here
        }
    }


    public void testExerciseIsOffloadSupported() throws Exception {
        final boolean offloadableFormat =
                AudioManager.isOffloadedPlaybackSupported(DEFAULT_FORMAT, DEFAULT_ATTR);
    }


    public void testAudioTrackOffload() throws Exception {
        AudioTrack track = null;

        try (AssetFileDescriptor mp3ToOffload = getContext().getResources()
                .openRawResourceFd(R.raw.sine1khzs40dblong);
             InputStream mp3InputStream = mp3ToOffload.createInputStream()) {

            long mp3ToOffloadLength = mp3ToOffload.getLength();
            if (!AudioManager.isOffloadedPlaybackSupported(DEFAULT_FORMAT, DEFAULT_ATTR)) {
                Log.i(TAG, "skipping test testPlayback");
                // cannot test if offloading is not supported
                return;
            }

            // format is offloadable, test playback head is progressing
            track = new AudioTrack.Builder()
                    .setAudioAttributes(DEFAULT_ATTR)
                    .setAudioFormat(DEFAULT_FORMAT)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(MP3_BUFF_SIZE)
                    .setOffloadedPlayback(true).build();
            assertNotNull("Couldn't create offloaded AudioTrack", track);
            assertEquals("Unexpected track sample rate", 44100, track.getSampleRate());
            assertEquals("Unexpected track channel config", AudioFormat.CHANNEL_OUT_STEREO,
                    track.getChannelConfiguration());

            try {
                track.registerStreamEventCallback(mExec, null);
                fail("Shouldn't be able to register null StreamEventCallback");
            } catch (Exception e) { }
            track.registerStreamEventCallback(mExec, mCallback);

            final byte[] data = new byte[MP3_BUFF_SIZE];
            final int read = mp3InputStream.read(data);

            track.play();
            int written = 0;
            while (written < read) {
                int wrote = track.write(data, written, read - written,
                        AudioTrack.WRITE_BLOCKING);
                if (wrote < 0) {
                    fail("Unable to write all read data, wrote " + written + " bytes");
                }
                written += wrote;
            }
            try {
                Thread.sleep(1 * 1000);
                synchronized(mPresEndLock) {
                    track.stop();
                    mPresEndLock.safeWait(PRESENTATION_END_TIMEOUT_MS);
                }
            } catch (InterruptedException e) { fail("Error while sleeping"); }
            synchronized (mEventCallbackLock) {
                assertTrue("onDataRequest not called", mCallback.mDataRequestCount > 0);
            }
            synchronized (mPresEndLock) {
                // we are at most PRESENTATION_END_TIMEOUT_MS + 1s after about 3s of data was
                // supplied, presentation should have ended
                assertEquals("onPresentationEnded not called one time",
                        1, mCallback.mPresentationEndedCount);
            }

        } finally {
            if (track != null) {
                Log.i(TAG, "pause");
                track.pause();
                track.unregisterStreamEventCallback(mCallback);
                track.release();
            }
        }
    }

    private Executor mExec = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private final Object mEventCallbackLock = new Object();
    private final SafeWaitObject mPresEndLock = new SafeWaitObject();

    private EventCallback mCallback = new EventCallback();

    private class EventCallback extends AudioTrack.StreamEventCallback {
        @GuardedBy("mEventCallbackLock")
        int mTearDownCount;
        @GuardedBy("mPresEndLock")
        int mPresentationEndedCount;
        @GuardedBy("mEventCallbackLock")
        int mDataRequestCount;

        @Override
        public void onTearDown(AudioTrack track) {
            synchronized (mEventCallbackLock) {
                Log.i(TAG, "onTearDown");
                mTearDownCount++;
            }
        }

        @Override
        public void onPresentationEnded(AudioTrack track) {
            synchronized (mPresEndLock) {
                Log.i(TAG, "onPresentationEnded");
                mPresentationEndedCount++;
                mPresEndLock.safeNotify();
            }
        }

        @Override
        public void onDataRequest(AudioTrack track, int size) {
            synchronized (mEventCallbackLock) {
                Log.i(TAG, "onDataRequest size:"+size);
                mDataRequestCount++;
            }
        }
    }
}
