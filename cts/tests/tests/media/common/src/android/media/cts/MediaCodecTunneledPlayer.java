/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.Context;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;
import android.view.SurfaceHolder;

import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * JB(API 21) introduces {@link MediaCodec} tunneled mode API.  It allows apps
 * to use MediaCodec to delegate their Audio/Video rendering to a vendor provided
 * Codec component.
 */
public class MediaCodecTunneledPlayer implements MediaTimeProvider {
    private static final String TAG = MediaCodecTunneledPlayer.class.getSimpleName();

    /** State the player starts in, before configuration. */
    private static final int STATE_IDLE = 1;
    /** State of the player during initial configuration. */
    private static final int STATE_PREPARING = 2;
    /** State of the player during playback. */
    private static final int STATE_PLAYING = 3;
    /** State of the player when configured but not playing. */
    private static final int STATE_PAUSED = 4;

    private Boolean mThreadStarted = false;
    private byte[] mSessionId;
    private CodecState mAudioTrackState;
    private int mMediaFormatHeight;
    private int mMediaFormatWidth;
    private Integer mState;
    private long mDeltaTimeUs;
    private long mDurationUs;
    private Map<Integer, CodecState> mAudioCodecStates;
    private Map<Integer, CodecState> mVideoCodecStates;
    private Map<String, String> mAudioHeaders;
    private Map<String, String> mVideoHeaders;
    private MediaExtractor mAudioExtractor;
    private MediaExtractor mVideoExtractor;
    private SurfaceHolder mSurfaceHolder;
    private Thread mThread;
    private Uri mAudioUri;
    private Uri mVideoUri;
    private boolean mIsTunneled;
    private int mAudioSessionId;
    private Context mContext;

    /*
     * Media player class to playback video using tunneled MediaCodec.
     */
    public MediaCodecTunneledPlayer(Context context, SurfaceHolder holder, boolean tunneled, int AudioSessionId) {
        mContext = context;
        mSurfaceHolder = holder;
        mIsTunneled = tunneled;
        mAudioTrackState = null;
        mState = STATE_IDLE;
        mAudioSessionId = AudioSessionId;
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    synchronized (mThreadStarted) {
                        if (mThreadStarted == false) {
                            break;
                        }
                    }
                    synchronized (mState) {
                        if (mState == STATE_PLAYING) {
                            doSomeWork();
                            if (mAudioTrackState != null) {
                                mAudioTrackState.processAudioTrack();
                            }
                        }
                    }
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ex) {
                        Log.d(TAG, "Thread interrupted");
                    }
                }
            }
        });
    }

    public void setAudioDataSource(Uri uri, Map<String, String> headers) {
        mAudioUri = uri;
        mAudioHeaders = headers;
    }

    public void setVideoDataSource(Uri uri, Map<String, String> headers) {
        mVideoUri = uri;
        mVideoHeaders = headers;
    }

    public final int getMediaFormatHeight() {
        return mMediaFormatHeight;
    }

    public final int getMediaFormatWidth() {
        return mMediaFormatWidth;
    }

    private boolean prepareAudio() throws IOException {
        for (int i = mAudioExtractor.getTrackCount(); i-- > 0;) {
            MediaFormat format = mAudioExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);

            if (!mime.startsWith("audio/")) {
                continue;
            }

            Log.d(TAG, "audio track #" + i + " " + format + " " + mime +
                  " Is ADTS:" + getMediaFormatInteger(format, MediaFormat.KEY_IS_ADTS) +
                  " Sample rate:" + getMediaFormatInteger(format, MediaFormat.KEY_SAMPLE_RATE) +
                  " Channel count:" +
                  getMediaFormatInteger(format, MediaFormat.KEY_CHANNEL_COUNT));

            mAudioExtractor.selectTrack(i);
            if (!addTrack(i, format)) {
                Log.e(TAG, "prepareAudio - addTrack() failed!");
                return false;
            }

            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                long durationUs = format.getLong(MediaFormat.KEY_DURATION);

                if (durationUs > mDurationUs) {
                    mDurationUs = durationUs;
                }
                Log.d(TAG, "audio track format #" + i +
                        " Duration:" + mDurationUs + " microseconds");
            }
        }
        return true;
    }

    private boolean prepareVideo() throws IOException {
        for (int i = mVideoExtractor.getTrackCount(); i-- > 0;) {
            MediaFormat format = mVideoExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);

            if (!mime.startsWith("video/")) {
                continue;
            }

            mMediaFormatHeight = getMediaFormatInteger(format, MediaFormat.KEY_HEIGHT);
            mMediaFormatWidth = getMediaFormatInteger(format, MediaFormat.KEY_WIDTH);
            Log.d(TAG, "video track #" + i + " " + format + " " + mime +
                  " Width:" + mMediaFormatWidth + ", Height:" + mMediaFormatHeight);

            mVideoExtractor.selectTrack(i);
            if (!addTrack(i, format)) {
                Log.e(TAG, "prepareVideo - addTrack() failed!");
                return false;
            }

            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                long durationUs = format.getLong(MediaFormat.KEY_DURATION);

                if (durationUs > mDurationUs) {
                    mDurationUs = durationUs;
                }
                Log.d(TAG, "track format #" + i + " Duration:" +
                        mDurationUs + " microseconds");
            }
        }
        return true;
    }

    public boolean prepare() throws IOException {
        if (null == mAudioExtractor) {
            mAudioExtractor = new MediaExtractor();
            if (null == mAudioExtractor) {
                Log.e(TAG, "prepare - Cannot create Audio extractor.");
                return false;
            }
        }

        if (null == mVideoExtractor){
            mVideoExtractor = new MediaExtractor();
            if (null == mVideoExtractor) {
                Log.e(TAG, "prepare - Cannot create Video extractor.");
                return false;
            }
        }

        mAudioExtractor.setDataSource(mContext, mAudioUri, mAudioHeaders);
        if (mVideoUri != null) {
            mVideoExtractor.setDataSource(mContext, mVideoUri, mVideoHeaders);
        }

        if (null == mVideoCodecStates) {
            mVideoCodecStates = new HashMap<Integer, CodecState>();
        } else {
            mVideoCodecStates.clear();
        }

        if (null == mAudioCodecStates) {
            mAudioCodecStates = new HashMap<Integer, CodecState>();
        } else {
            mAudioCodecStates.clear();
        }

        if (!prepareAudio()) {
            Log.e(TAG,"prepare - prepareAudio() failed!");
            return false;
        }
        if (!prepareVideo()) {
            Log.e(TAG,"prepare - prepareVideo() failed!");
            return false;
        }

        synchronized (mState) {
            mState = STATE_PAUSED;
        }
        return true;
    }

    private boolean addTrack(int trackIndex, MediaFormat format) throws IOException {
        String mime = format.getString(MediaFormat.KEY_MIME);
        boolean isVideo = mime.startsWith("video/");
        boolean isAudio = mime.startsWith("audio/");
        MediaCodec codec;

        // setup tunneled video codec if needed
        if (isVideo && mIsTunneled) {
            format.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback,
                        true);
            MediaCodecList mcl = new MediaCodecList(MediaCodecList.ALL_CODECS);
            String codecName = mcl.findDecoderForFormat(format);
            if (codecName == null) {
                Log.e(TAG,"addTrack - Could not find Tunneled playback codec for "+mime+
                        " format!");
                return false;
            }

            codec = MediaCodec.createByCodecName(codecName);
            if (codec == null) {
                Log.e(TAG, "addTrack - Could not create Tunneled playback codec "+
                        codecName+"!");
                return false;
            }

            if (mAudioTrackState != null) {
                format.setInteger(MediaFormat.KEY_AUDIO_SESSION_ID, mAudioSessionId);
            }
        }
        else {
            codec = MediaCodec.createDecoderByType(mime);
            if (codec == null) {
                Log.e(TAG, "addTrack - Could not create regular playback codec for mime "+
                        mime+"!");
                return false;
            }
        }
        codec.configure(
                format,
                isVideo ? mSurfaceHolder.getSurface() : null, null, 0);

        CodecState state;
        if (isVideo) {
            state = new CodecState((MediaTimeProvider)this, mVideoExtractor,
                            trackIndex, format, codec, true, mIsTunneled, mAudioSessionId);
            mVideoCodecStates.put(Integer.valueOf(trackIndex), state);
        } else {
            state = new CodecState((MediaTimeProvider)this, mAudioExtractor,
                            trackIndex, format, codec, true, mIsTunneled, mAudioSessionId);
            mAudioCodecStates.put(Integer.valueOf(trackIndex), state);
        }

        if (isAudio) {
            mAudioTrackState = state;
        }

        return true;
    }

    protected int getMediaFormatInteger(MediaFormat format, String key) {
        return format.containsKey(key) ? format.getInteger(key) : 0;
    }

    public boolean start() {
        Log.d(TAG, "start");

        synchronized (mState) {
            if (mState == STATE_PLAYING || mState == STATE_PREPARING) {
                return true;
            } else if (mState == STATE_IDLE) {
                mState = STATE_PREPARING;
                return true;
            } else if (mState != STATE_PAUSED) {
                throw new IllegalStateException("Expected STATE_PAUSED, got " + mState);
            }

            for (CodecState state : mVideoCodecStates.values()) {
                state.start();
            }

            for (CodecState state : mAudioCodecStates.values()) {
                state.start();
            }

            mDeltaTimeUs = -1;
            mState = STATE_PLAYING;
        }
        return false;
    }

    public void startWork() throws IOException, Exception {
        try {
            // Just change state from STATE_IDLE to STATE_PREPARING.
            start();
            // Extract media information from uri asset, and change state to STATE_PAUSED.
            prepare();
            // Start CodecState, and change from STATE_PAUSED to STATE_PLAYING.
            start();
        } catch (IOException e) {
            throw e;
        }

        synchronized (mThreadStarted) {
            mThreadStarted = true;
            mThread.start();
        }
    }

    public void startThread() {
        start();
        synchronized (mThreadStarted) {
            mThreadStarted = true;
            mThread.start();
        }
    }

    // Pauses the audio track
    public void pause() {
        Log.d(TAG, "pause");

        synchronized (mState) {
            if (mState == STATE_PAUSED) {
                return;
            } else if (mState != STATE_PLAYING) {
                throw new IllegalStateException();
            }

            for (CodecState state : mVideoCodecStates.values()) {
                state.pause();
            }

            for (CodecState state : mAudioCodecStates.values()) {
                state.pause();
            }

            mState = STATE_PAUSED;
        }
    }

    public void flush() {
        Log.d(TAG, "flush");

        synchronized (mState) {
            if (mState == STATE_PLAYING || mState == STATE_PREPARING) {
                return;
            }

            for (CodecState state : mAudioCodecStates.values()) {
                state.flush();
            }

            for (CodecState state : mVideoCodecStates.values()) {
                state.flush();
            }
        }
    }

    /** Seek all tracks to their very beginning.
     *
     * @param  presentationTimeOffsetUs The offset for the presentation time to start at.
     * @throws IllegalStateException  if the player is not paused
     */
    public void seekToBeginning(long presentationTimeOffsetUs) {
        Log.d(TAG, "seekToBeginning");
        synchronized (mState) {
            if (mState != STATE_PAUSED) {
                throw new IllegalStateException("Expected STATE_PAUSED, got " + mState);
            }

            for (CodecState state : mVideoCodecStates.values()) {
                state.seekToBeginning(presentationTimeOffsetUs);
            }

            for (CodecState state : mAudioCodecStates.values()) {
                state.seekToBeginning(presentationTimeOffsetUs);
            }
        }
    }

    /**
     * Enables or disables looping. Should be called after {@link #prepare()}.
     */
    public void setLoopEnabled(boolean enabled) {
        synchronized (mState) {
            if (mVideoCodecStates != null) {
                for (CodecState state : mVideoCodecStates.values()) {
                    state.setLoopEnabled(enabled);
                }
            }

            if (mAudioCodecStates != null) {
                for (CodecState state : mAudioCodecStates.values()) {
                    state.setLoopEnabled(enabled);
                }
            }
        }
    }

    public void reset() {
        synchronized (mState) {
            if (mState == STATE_PLAYING) {
                pause();
            }
            if (mVideoCodecStates != null) {
                for (CodecState state : mVideoCodecStates.values()) {
                    state.release();
                }
                mVideoCodecStates = null;
            }

            if (mAudioCodecStates != null) {
                for (CodecState state : mAudioCodecStates.values()) {
                    state.release();
                }
                mAudioCodecStates = null;
            }

            if (mAudioExtractor != null) {
                mAudioExtractor.release();
                mAudioExtractor = null;
            }

            if (mVideoExtractor != null) {
                mVideoExtractor.release();
                mVideoExtractor = null;
            }

            mDurationUs = -1;
            mState = STATE_IDLE;
        }
        synchronized (mThreadStarted) {
            mThreadStarted = false;
        }
        try {
            mThread.join();
        } catch (InterruptedException ex) {
            Log.d(TAG, "mThread.join ", ex);
        }
    }

    public boolean isEnded() {
        for (CodecState state : mVideoCodecStates.values()) {
          if (!state.isEnded()) {
            return false;
          }
        }

        for (CodecState state : mAudioCodecStates.values()) {
            if (!state.isEnded()) {
              return false;
            }
        }

        return true;
    }

    private void doSomeWork() {
        try {
            for (CodecState state : mVideoCodecStates.values()) {
                state.doSomeWork();
            }
        } catch (IllegalStateException e) {
            throw new Error("Video CodecState.doSomeWork", e);
        }

        try {
            for (CodecState state : mAudioCodecStates.values()) {
                state.doSomeWork();
            }
        } catch (IllegalStateException e) {
            throw new Error("Audio CodecState.doSomeWork", e);
        }

    }

    public long getNowUs() {
        if (mAudioTrackState == null) {
            return System.currentTimeMillis() * 1000;
        }

        return mAudioTrackState.getAudioTimeUs();
    }

    public long getRealTimeUsForMediaTime(long mediaTimeUs) {
        if (mDeltaTimeUs == -1) {
            long nowUs = getNowUs();
            mDeltaTimeUs = nowUs - mediaTimeUs;
        }

        return mDeltaTimeUs + mediaTimeUs;
    }

    public int getDuration() {
        return (int)((mDurationUs + 500) / 1000);
    }

    /**
     * Retrieve the presentation timestamp of the latest queued output sample.
     * In tunnel mode, retrieves the presentation timestamp of the latest rendered video frame.
     * @return presentation timestamp in microseconds, or {@code CodecState.UNINITIALIZED_TIMESTAMP}
     * if playback has not started.
    */
    public int getCurrentPosition() {
        if (mVideoCodecStates == null) {
            return CodecState.UNINITIALIZED_TIMESTAMP;
        }

        long positionUs = CodecState.UNINITIALIZED_TIMESTAMP;

        for (CodecState state : mVideoCodecStates.values()) {
            long trackPositionUs = state.getCurrentPositionUs();
            if (trackPositionUs > positionUs) {
                positionUs = trackPositionUs;
            }
        }

        if (positionUs == CodecState.UNINITIALIZED_TIMESTAMP) {
            return CodecState.UNINITIALIZED_TIMESTAMP;
        }
        return (int) (positionUs + 500) / 1000;
    }

    /**
     * Returns the system time of the latest rendered frame in any of the video codecs.
     */
    public long getCurrentRenderedSystemTimeNano() {
        if (mVideoCodecStates == null) {
            return 0;
        }

        long position = 0;

        for (CodecState state : mVideoCodecStates.values()) {
            long trackPosition = state.getRenderedVideoSystemTimeNano();

            if (trackPosition > position) {
                position = trackPosition;
            }
        }
        return position;
    }

    /**
     * Returns the timestamp of the last written audio sample, in microseconds.
     */
    public long getAudioTrackPositionUs() {
        if (mAudioTrackState == null) {
            return 0;
        }
        return mAudioTrackState.getCurrentPositionUs();
    }

    /**
     * Returns the presentation timestamp of the last rendered video frame.
     *
     * Note: This assumes there is exactly one video codec running in the player.
     */
    public long getVideoTimeUs() {
        if (mVideoCodecStates == null || mVideoCodecStates.get(0) == null) {
            return CodecState.UNINITIALIZED_TIMESTAMP;
        }
        return mVideoCodecStates.get(0).getVideoTimeUs();
    }

    /**
     * Returns the ordered list of video frame timestamps rendered in tunnel mode.
     *
     * Note: This assumes there is exactly one video codec running in the player.
     */
    public ImmutableList<Long> getRenderedVideoFrameTimestampList() {
        return mVideoCodecStates.get(0).getRenderedVideoFrameTimestampList();
    }

    /**
     * Returns the ordered list of system times of rendered video frames in tunnel-mode.
     *
     * Note: This assumes there is at most one tunneled mode video codec running in the player.
     */
    public ImmutableList<Long> getRenderedVideoFrameSystemTimeList() {
        if (mVideoCodecStates == null) {
            return ImmutableList.<Long>of();
        }

        for (CodecState state : mVideoCodecStates.values()) {
            ImmutableList<Long> timestamps = state.getRenderedVideoFrameSystemTimeList();
            if (!timestamps.isEmpty())
                return timestamps;
        }
        return ImmutableList.<Long>of();
    }

    /**
     * When the player is on stand-by, tries to queue one frame worth of video per video codec.
     *
     * Returns arbitrarily the timestamp of any frame queued this way by one of the video codecs.
     * Returns null if no video frame were queued.
     */
    public Long queueOneVideoFrame() {
        Log.d(TAG, "queueOneVideoFrame");

        if (mVideoCodecStates == null || !(mState == STATE_PLAYING || mState == STATE_PAUSED)) {
            return null;
        }

        Long result = null;
        for (CodecState state : mVideoCodecStates.values()) {
            Long timestamp = state.doSomeWork(true /* mustWait */);
            if (timestamp != null) {
                result = timestamp;
            }
        }
        return result;
    }

    /**
     * Resume playback when paused.
     *
     * @throws IllegalStateException if playback is not paused or if there is no configured audio
     *                               track.
     */
    public void resume() {
        Log.d(TAG, "resume");
        if (mAudioTrackState == null) {
            throw new IllegalStateException("Resuming playback with no audio track");
        }
        if (mState != STATE_PAUSED) {
            throw new IllegalStateException("Expected STATE_PAUSED, got " + mState);
        }
        mAudioTrackState.playAudioTrack();
        mState = STATE_PLAYING;
    }

    /**
     * Configure video peek for the video codecs attached to the player.
     */
    public void setVideoPeek(boolean enable) {
        Log.d(TAG, "setVideoPeek");
        if (mVideoCodecStates == null) {
            return;
        }

        for (CodecState state: mVideoCodecStates.values()) {
            state.setVideoPeek(enable);
        }
    }

    public AudioTimestamp getTimestamp() {
        if (mAudioCodecStates == null) {
            return null;
        }

        AudioTimestamp timestamp = new AudioTimestamp();
        if (mAudioCodecStates.size() != 0) {
            timestamp =
                    mAudioCodecStates.entrySet().iterator().next().getValue().getTimestamp();
        }
        return timestamp;
    }

    /** Queries the attached video codecs for video peek ready signals.
     *
     * Returns true if any of the video codecs have video peek ready.
     * Returns false otherwise.
     */
    public boolean isFirstTunnelFrameReady() {
        Log.d(TAG, "firstTunnelFrameReady");
        if (mVideoCodecStates == null) {
            return false;
        }

        for (CodecState state : mVideoCodecStates.values()) {
            if (state.isFirstTunnelFrameReady()) {
                return true;
            }
        }
        return false;
    }

    /** Returns the number of frames that have been sent down to the HAL. */
    public int getAudioFramesWritten() {
        if (mAudioCodecStates == null) {
            return -1;
        }
        return mAudioCodecStates.entrySet().iterator().next().getValue().getFramesWritten();
    }

    public void stopWritingToAudioTrack(boolean stopWriting) {
        for (CodecState state : mAudioCodecStates.values()) {
            state.stopWritingToAudioTrack(stopWriting);
        }
    }

    /** Configure underrun simulation on audio codecs. */
    public void simulateAudioUnderrun(boolean enabled) {
        for (CodecState state: mAudioCodecStates.values()) {
            state.simulateUnderrun(enabled);
        }
    }

    /** Configure an offset (in ms) to audio content to simulate track desynchronization. */
    public void setAudioTrackOffsetMs(int audioOffsetMs) {
        if (mAudioTrackState != null) {
            mAudioTrackState.setAudioOffsetMs(audioOffsetMs);
        }
    }

    /** Returns the underlying {@code AudioTrack}, if any. */
    public AudioTrack getAudioTrack() {
        if (mAudioTrackState != null) {
            return mAudioTrackState.getAudioTrack();
        }
        return null;
    }
}
