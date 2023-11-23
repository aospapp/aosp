/*
 * Copyright 2020 The Android Open Source Project
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
package org.hyphonate.megaaudio.duplex;

import android.media.AudioDeviceInfo;
import android.util.Log;

import org.hyphonate.megaaudio.common.BuilderBase;
import org.hyphonate.megaaudio.common.StreamBase;
import org.hyphonate.megaaudio.player.AudioSource;
import org.hyphonate.megaaudio.player.AudioSourceProvider;
import org.hyphonate.megaaudio.player.Player;
import org.hyphonate.megaaudio.player.PlayerBuilder;
import org.hyphonate.megaaudio.recorder.AudioSinkProvider;
import org.hyphonate.megaaudio.recorder.Recorder;
import org.hyphonate.megaaudio.recorder.RecorderBuilder;

public class DuplexAudioManager {
    @SuppressWarnings("unused")
    private static final String TAG = DuplexAudioManager.class.getSimpleName();
    @SuppressWarnings("unused")
    private static final boolean LOG = false;

    // Player
    //TODO - explain these constants
    private int mNumPlayerChannels = 2;
    private int mPlayerSampleRate = 48000;
    private int mNumPlayerBurstFrames;

    private Player mPlayer;
    private AudioSourceProvider mSourceProvider;
    private AudioDeviceInfo mPlayerSelectedDevice;

    // Recorder
    private int mNumRecorderChannels = 2;
    private int mRecorderSampleRate = 48000;
    private int mNumRecorderBufferFrames;

    private Recorder mRecorder;
    private AudioSinkProvider mSinkProvider;
    private AudioDeviceInfo mRecorderSelectedDevice;
    private int mInputPreset = Recorder.INPUT_PRESET_NONE;

    public DuplexAudioManager(AudioSourceProvider sourceProvider, AudioSinkProvider sinkProvider) {
        setSources(sourceProvider, sinkProvider);
    }

    /**
     * Specify the source providers for the source and sink.
     * @param sourceProvider The AudioSourceProvider for the output stream
     * @param sinkProvider The AudioSinkProvider for the input stream.
     */
    public void setSources(AudioSourceProvider sourceProvider, AudioSinkProvider sinkProvider) {
        mSourceProvider = sourceProvider;
        mSinkProvider = sinkProvider;

        mPlayerSampleRate =  StreamBase.getSystemSampleRate();
        mRecorderSampleRate = StreamBase.getSystemSampleRate();
    }

    //
    // Be careful using these, they will change after setupStreams is called.
    //
    public Player getPlayer() {
        return mPlayer;
    }
    public Recorder getRecorder() {
        return mRecorder;
    }

    public void setPlayerSampleRate(int sampleRate) {
        mPlayerSampleRate = sampleRate;
    }

    public void setRecordererSampleRate(int sampleRate) {
        mPlayerSampleRate = sampleRate;
    }

    public void setPlayerRouteDevice(AudioDeviceInfo deviceInfo) {
        mPlayerSelectedDevice = deviceInfo;
    }

    public void setRecorderRouteDevice(AudioDeviceInfo deviceInfo) {
        mRecorderSelectedDevice = deviceInfo;
    }

    public void setNumPlayerChannels(int numChannels) {
        mNumPlayerChannels = numChannels;
    }

    public void setNumRecorderChannels(int numChannels) {
        mNumRecorderChannels = numChannels;
    }
    public void setRecorderSampleRate(int sampleRate) {
        mRecorderSampleRate = sampleRate;
    }

    /**
     * Specifies the input preset to use for the recorder.
     * @param preset
     */
    public void setInputPreset(int preset) {
        mInputPreset = preset;
    }

    public int setupStreams(int playerType, int recorderType) {
        // Recorder
        if ((recorderType & BuilderBase.TYPE_MASK) != BuilderBase.TYPE_NONE) {
            try {
//                mNumRecorderBufferFrames = Recorder.calcMinBufferFramesStatic(
//                        mNumRecorderChannels, mRecorderSampleRate);
                mNumRecorderBufferFrames = StreamBase.getNumBurstFrames(BuilderBase.TYPE_NONE);
                Log.i(TAG, "mNumRecorderBufferFrames: " + mNumRecorderBufferFrames);
                RecorderBuilder builder = (RecorderBuilder) new RecorderBuilder()
                        .setRecorderType(recorderType)
                        .setAudioSinkProvider(mSinkProvider)
                        .setInputPreset(mInputPreset)
                        .setRouteDevice(mRecorderSelectedDevice)
                        .setSampleRate(mRecorderSampleRate)
                        .setChannelCount(mNumRecorderChannels)
                        .setNumExchangeFrames(mNumRecorderBufferFrames);
                mRecorder = builder.build();
            } catch (RecorderBuilder.BadStateException ex) {
                Log.e(TAG, "Recorder - BadStateException" + ex);
                return StreamBase.ERROR_UNSUPPORTED;
            }
        }

        // Player
        if ((playerType & BuilderBase.TYPE_MASK) != BuilderBase.TYPE_NONE) {
            try {
                mNumPlayerBurstFrames = StreamBase.getNumBurstFrames(playerType);
                Log.i(TAG, "mNumPlayerBurstFrames:" + mNumPlayerBurstFrames);

                PlayerBuilder builder = (PlayerBuilder) new PlayerBuilder()
                        .setPlayerType(playerType)
                        .setSourceProvider(mSourceProvider)
                        .setSampleRate(mPlayerSampleRate)
                        .setChannelCount(mNumPlayerChannels)
                        .setRouteDevice(mPlayerSelectedDevice)
                        .setNumExchangeFrames(mNumPlayerBurstFrames)
                        .setPerformanceMode(BuilderBase.PERFORMANCE_MODE_LOWLATENCY);
                mPlayer = builder.build();
            } catch (PlayerBuilder.BadStateException ex) {
                Log.e(TAG, "Player - BadStateException" + ex);
                return StreamBase.ERROR_UNSUPPORTED;
            } catch (Exception ex) {
                Log.e(TAG, "Uncaught Error in Player Setup for DuplexAudioManager ex:" + ex);
            }
        }

        return StreamBase.OK;
    }

    public int start() {
        int result = StreamBase.OK;
        if (mRecorder != null && (result = mRecorder.startStream()) != StreamBase.OK) {
            return result;
        }

        if (mPlayer != null && (result = mPlayer.startStream()) != StreamBase.OK) {
            return result;
        }

        return result;
    }

    public int stop() {
        int playerResult = StreamBase.OK;
        if (mPlayer != null) {
           int result1 = mPlayer.stopStream();
           int result2 = mPlayer.teardownStream();
           playerResult = result1 != StreamBase.OK ? result1 : result2;
        }

        int recorderResult = StreamBase.OK;
        if (mRecorder != null) {
            int result1 = mRecorder.stopStream();
            int result2 = mRecorder.teardownStream();
            recorderResult = result1 != StreamBase.OK ? result1 : result2;
        }

        return playerResult != StreamBase.OK ? playerResult: recorderResult;
    }

    public int getNumPlayerBufferFrames() {
        return mPlayer != null ? mPlayer.getSystemBurstFrames() : 0;
    }

    public int getNumRecorderBufferFrames() {
        return mRecorder != null ? mRecorder.getSystemBurstFrames() : 0;
    }

    public AudioSource getAudioSource() {
        return mPlayer != null ? mPlayer.getAudioSource() : null;
    }
}
