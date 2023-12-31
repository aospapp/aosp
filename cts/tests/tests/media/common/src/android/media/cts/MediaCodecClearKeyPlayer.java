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
import android.content.res.Resources;
import android.media.AudioManager;
import android.media.DrmInitData;
import android.media.MediaCas;
import android.media.MediaCasException;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCrypto;
import android.media.MediaCryptoException;
import android.media.MediaDescrambler;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;

import androidx.test.InstrumentationRegistry;

import com.android.compatibility.common.util.MediaUtils;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JB(API 16) introduces {@link MediaCodec} API.  It allows apps have more control over
 * media playback, pushes individual frames to decoder and supports decryption via
 * {@link MediaCrypto} API.
 *
 * {@link MediaDrm} can be used to obtain keys for decrypting protected media streams,
 * in conjunction with MediaCrypto.
 */
public class MediaCodecClearKeyPlayer implements MediaTimeProvider {
    private static final String TAG = MediaCodecClearKeyPlayer.class.getSimpleName();

    private static final String FILE_SCHEME = "file://";

    private static final int STATE_IDLE = 1;
    private static final int STATE_PREPARING = 2;
    private static final int STATE_PLAYING = 3;
    private static final int STATE_PAUSED = 4;

    private static final UUID CLEARKEY_SCHEME_UUID =
            new UUID(0x1077efecc0b24d02L, 0xace33c1e52e2fb4bL);

    private boolean mEncryptedAudio;
    private boolean mEncryptedVideo;
    private volatile boolean mThreadStarted = false;
    private byte[] mSessionId;
    private boolean mScrambled;
    private CodecState mAudioTrackState;
    private int mMediaFormatHeight;
    private int mMediaFormatWidth;
    private int mState;
    private long mDeltaTimeUs;
    private long mDurationUs;
    private Map<Integer, CodecState> mAudioCodecStates;
    private Map<Integer, CodecState> mVideoCodecStates;
    private Map<String, String> mAudioHeaders;
    private Map<String, String> mVideoHeaders;
    private Map<UUID, byte[]> mPsshInitData;
    private MediaCrypto mCrypto;
    private MediaCas mMediaCas;
    private MediaDescrambler mAudioDescrambler;
    private MediaDescrambler mVideoDescrambler;
    private MediaExtractor mAudioExtractor;
    private MediaExtractor mVideoExtractor;
    private Deque<Surface> mSurfaces;
    private Thread mThread;
    private Uri mAudioUri;
    private Uri mVideoUri;
    private Context mContext;
    private Resources mResources;
    private Error mErrorFromThread;

    private static final byte[] PSSH = hexStringToByteArray(
            // BMFF box header (4 bytes size + 'pssh')
            "0000003470737368" +
            // Full box header (version = 1 flags = 0
            "01000000" +
            // SystemID
            "1077efecc0b24d02ace33c1e52e2fb4b" +
            // Number of key ids
            "00000001" +
            // Key id
            "30303030303030303030303030303030" +
            // size of data, must be zero
            "00000000");

    // ClearKey CAS/Descrambler test provision string
    private static final String sProvisionStr =
            "{                                                   " +
            "  \"id\": 21140844,                                 " +
            "  \"name\": \"Test Title\",                         " +
            "  \"lowercase_organization_name\": \"Android\",     " +
            "  \"asset_key\": {                                  " +
            "  \"encryption_key\": \"nezAr3CHFrmBR9R8Tedotw==\"  " +
            "  },                                                " +
            "  \"cas_type\": 1,                                  " +
            "  \"track_types\": [ ]                              " +
            "}                                                   " ;

    // ClearKey private data (0-bytes of length 4)
    private static final byte[] sCasPrivateInfo = hexStringToByteArray("00000000");

    /**
     * Convert a hex string into byte array.
     */
    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    /*
     * Media player class to stream CENC content using MediaCodec class.
     */
    public MediaCodecClearKeyPlayer(
            List<Surface> surfaces, byte[] sessionId, boolean scrambled, Context context) {
        mSessionId = sessionId;
        mScrambled = scrambled;
        mSurfaces = new ArrayDeque<>(surfaces);
        mContext = context;
        mResources = context.getResources();
        mState = STATE_IDLE;
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int n = 0;
                while (mThreadStarted == true) {
                    doSomeWork();
                    if (mAudioTrackState != null) {
                        mAudioTrackState.processAudioTrack();
                    }
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ex) {
                        Log.d(TAG, "Thread interrupted");
                    }
                    if(++n % 1000 == 0) {
                        cycleSurfaces();
                    }
                }
                if (mAudioTrackState != null) {
                    mAudioTrackState.stopAudioTrack();
                }
            }
        });
    }

    public void setAudioDataSource(Uri uri, Map<String, String> headers, boolean encrypted) {
        mAudioUri = uri;
        mAudioHeaders = headers;
        mEncryptedAudio = encrypted;
    }

    public void setVideoDataSource(Uri uri, Map<String, String> headers, boolean encrypted) {
        mVideoUri = uri;
        mVideoHeaders = headers;
        mEncryptedVideo = encrypted;
    }

    public final int getMediaFormatHeight() {
        return mMediaFormatHeight;
    }

    public final int getMediaFormatWidth() {
        return mMediaFormatWidth;
    }

    public final byte[] getDrmInitData() {
        for (MediaExtractor ex: new MediaExtractor[] {mVideoExtractor, mAudioExtractor}) {
            DrmInitData drmInitData = ex.getDrmInitData();
            if (drmInitData != null) {
                DrmInitData.SchemeInitData schemeInitData = drmInitData.get(CLEARKEY_SCHEME_UUID);
                if (schemeInitData != null && schemeInitData.data != null) {
                    // llama content still does not contain pssh data, return hard coded PSSH
                    return (schemeInitData.data.length > 1)? schemeInitData.data : PSSH;
                }
            }
        }
        // Should not happen after we get content that has the clear key system id.
        return PSSH;
    }

    private void prepareAudio() throws IOException, MediaCasException {
        boolean hasAudio = false;
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

            if (mScrambled) {
                MediaExtractor.CasInfo casInfo = mAudioExtractor.getCasInfo(i);
                if (casInfo != null && casInfo.getSession() != null) {
                    mAudioDescrambler = new MediaDescrambler(casInfo.getSystemId());
                    mAudioDescrambler.setMediaCasSession(casInfo.getSession());
                }
            }

            if (!hasAudio) {
                mAudioExtractor.selectTrack(i);
                addTrack(i, format, mEncryptedAudio);
                hasAudio = true;

                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    long durationUs = format.getLong(MediaFormat.KEY_DURATION);

                    if (durationUs > mDurationUs) {
                        mDurationUs = durationUs;
                    }
                    Log.d(TAG, "audio track format #" + i +
                            " Duration:" + mDurationUs + " microseconds");
                }

                if (hasAudio) {
                    break;
                }
            }
        }
    }

    private void prepareVideo() throws IOException, MediaCasException {
        boolean hasVideo = false;

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

            if (mScrambled) {
                MediaExtractor.CasInfo casInfo = mVideoExtractor.getCasInfo(i);
                if (casInfo != null && casInfo.getSession() != null) {
                    mVideoDescrambler = new MediaDescrambler(casInfo.getSystemId());
                    mVideoDescrambler.setMediaCasSession(casInfo.getSession());
                }
            }

            if (!hasVideo) {
                mVideoExtractor.selectTrack(i);
                addTrack(i, format, mEncryptedVideo);

                hasVideo = true;

                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    long durationUs = format.getLong(MediaFormat.KEY_DURATION);

                    if (durationUs > mDurationUs) {
                        mDurationUs = durationUs;
                    }
                    Log.d(TAG, "track format #" + i + " Duration:" +
                            mDurationUs + " microseconds");
                }

                if (hasVideo) {
                    break;
                }
            }
        }
    }

    private boolean initCasAndDescrambler(MediaExtractor extractor) throws MediaCasException {
        int trackCount = extractor.getTrackCount();
        for (int trackId = 0; trackId < trackCount; trackId++) {
            android.media.MediaFormat format = extractor.getTrackFormat(trackId);
            String mime = format.getString(android.media.MediaFormat.KEY_MIME);
            Log.d(TAG, "track "+ trackId + ": " + mime);
            if (MediaFormat.MIMETYPE_VIDEO_SCRAMBLED.equals(mime) ||
                    MediaFormat.MIMETYPE_AUDIO_SCRAMBLED.equals(mime)) {
                MediaExtractor.CasInfo casInfo = extractor.getCasInfo(trackId);
                if (casInfo != null) {
                    if (!Arrays.equals(sCasPrivateInfo, casInfo.getPrivateData())) {
                        throw new Error("Cas private data mismatch");
                    }
                    // Need MANAGE_USERS or CREATE_USERS permission to access
                    // ActivityManager#getCurrentUse in MediaCas, then adopt it from shell.
                    InstrumentationRegistry
                        .getInstrumentation().getUiAutomation().adoptShellPermissionIdentity();
                    try {
                        mMediaCas = new MediaCas(casInfo.getSystemId());
                    } finally {
                        InstrumentationRegistry
                            .getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
                    }

                    mMediaCas.provision(sProvisionStr);
                    if (mMediaCas.isAidlHal()) {
                        MediaUtils.skipTest(
                                TAG, "setMediaCas is deprecated and not supported with AIDL HAL");
                        // If AIDL CAS service is being used, then setMediaCas will not work.
                        return false;
                    }
                    extractor.setMediaCas(mMediaCas);
                    break;
                }
            }
        }
        return true;
    }

    public boolean prepare() throws IOException, MediaCryptoException, MediaCasException {
        if (null == mCrypto && (mEncryptedVideo || mEncryptedAudio)) {
            try {
                byte[] initData = new byte[0];
                mCrypto = new MediaCrypto(CLEARKEY_SCHEME_UUID, initData);
            } catch (MediaCryptoException e) {
                reset();
                Log.e(TAG, "Failed to create MediaCrypto instance.");
                throw e;
            }
            mCrypto.setMediaDrmSession(mSessionId);
        } else {
            reset();
        }

        if (null == mAudioExtractor) {
            mAudioExtractor = new MediaExtractor();
            if (null == mAudioExtractor) {
                Log.e(TAG, "Cannot create Audio extractor.");
                return false;
            }
        }
        mAudioExtractor.setDataSource(mContext, mAudioUri, mAudioHeaders);

        if (mScrambled) {
            if (!initCasAndDescrambler(mAudioExtractor)) {
                return false;
            }
            mVideoExtractor = mAudioExtractor;
        } else {
            if (null == mVideoExtractor){
                mVideoExtractor = new MediaExtractor();
                if (null == mVideoExtractor) {
                    Log.e(TAG, "Cannot create Video extractor.");
                    return false;
                }
            }
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

        prepareVideo();
        prepareAudio();

        mState = STATE_PAUSED;
        return true;
    }

    private void addTrack(int trackIndex, MediaFormat format,
            boolean encrypted) throws IOException {
        String mime = format.getString(MediaFormat.KEY_MIME);
        boolean isVideo = mime.startsWith("video/");
        boolean isAudio = mime.startsWith("audio/");

        MediaCodec codec;

        if (encrypted && mCrypto.requiresSecureDecoderComponent(mime)) {
            codec = MediaCodec.createByCodecName(
                    getSecureDecoderNameForMime(mime));
        } else {
            codec = MediaCodec.createDecoderByType(mime);
        }

        if (!mScrambled) {
            codec.configure(
                    format,
                    isVideo ? mSurfaces.getFirst() : null,
                    mCrypto,
                    0);
        } else {
            codec.configure(
                    format,
                    isVideo ? mSurfaces.getFirst() : null,
                    0,
                    isVideo ? mVideoDescrambler : mAudioDescrambler);
        }

        CodecState state;
        if (isVideo) {
            state = new CodecState((MediaTimeProvider)this, mVideoExtractor,
                            trackIndex, format, codec, true, false,
                            AudioManager.AUDIO_SESSION_ID_GENERATE);
            mVideoCodecStates.put(Integer.valueOf(trackIndex), state);
        } else {
            state = new CodecState((MediaTimeProvider)this, mAudioExtractor,
                            trackIndex, format, codec, true, false,
                            AudioManager.AUDIO_SESSION_ID_GENERATE);
            mAudioCodecStates.put(Integer.valueOf(trackIndex), state);
        }

        if (isAudio) {
            mAudioTrackState = state;
        }
    }

    protected int getMediaFormatInteger(MediaFormat format, String key) {
        return format.containsKey(key) ? format.getInteger(key) : 0;
    }

    // Find first secure decoder for media type. If none found, return
    // the name of the first regular codec with ".secure" suffix added.
    // If all else fails, return null.
    protected String getSecureDecoderNameForMime(String mime) {
        String firstDecoderName = null;
        int n = MediaCodecList.getCodecCount();
        for (int i = 0; i < n; ++i) {
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);

            if (info.isEncoder()) {
                continue;
            }

            String[] supportedTypes = info.getSupportedTypes();

            for (int j = 0; j < supportedTypes.length; ++j) {
                if (supportedTypes[j].equalsIgnoreCase(mime)) {
                    if (info.getCapabilitiesForType(mime).isFeatureSupported(
                            MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)) {
                        return info.getName();
                    } else if (firstDecoderName == null) {
                        firstDecoderName = info.getName();
                    }
                }
            }
        }
        if (firstDecoderName != null) {
            return firstDecoderName + ".secure";
        }
        return null;
    }

    public void start() {
        Log.d(TAG, "start");

        if (mState == STATE_PLAYING || mState == STATE_PREPARING) {
            return;
        } else if (mState == STATE_IDLE) {
            mState = STATE_PREPARING;
            return;
        } else if (mState != STATE_PAUSED) {
            throw new IllegalStateException();
        }

        for (CodecState state : mVideoCodecStates.values()) {
            state.startCodec();
            state.play();
        }

        for (CodecState state : mAudioCodecStates.values()) {
            state.startCodec();
            state.play();
        }

        mDeltaTimeUs = -1;
        mState = STATE_PLAYING;
    }

    public void startWork() throws IOException, MediaCryptoException, Exception {
        try {
            // Just change state from STATE_IDLE to STATE_PREPARING.
            start();
            // Extract media information from uri asset, and change state to STATE_PAUSED.
            if (!prepare()) {
                Log.d(TAG, "Could not prepare player.");
                return;
            }
            // Start CodecState, and change from STATE_PAUSED to STATE_PLAYING.
            start();
        } catch (IOException e) {
            throw e;
        } catch (MediaCryptoException e) {
            throw e;
        }

        mThreadStarted = true;
        mThread.start();
    }

    public void startThread() {
        start();
        mThreadStarted = true;
        mThread.start();
    }

    public void pause() {
        Log.d(TAG, "pause");

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

    public void reset() {
        if (mState == STATE_PLAYING) {
            mThreadStarted = false;

            try {
                mThread.join();
            } catch (InterruptedException ex) {
                Log.d(TAG, "mThread.join " + ex);
            }

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

        if (mCrypto != null) {
            mCrypto.release();
            mCrypto = null;
        }

        if (mMediaCas != null) {
            mMediaCas.close();
            mMediaCas = null;
        }

        if (mAudioDescrambler != null) {
            mAudioDescrambler.close();
            mAudioDescrambler = null;
        }

        if (mVideoDescrambler != null) {
            mVideoDescrambler.close();
            mVideoDescrambler = null;
        }

        mDurationUs = -1;
        mState = STATE_IDLE;
    }

    public boolean isEnded() {
        if (mErrorFromThread != null) {
            throw mErrorFromThread;
        }
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
        } catch (MediaCodec.CryptoException e) {
            mErrorFromThread = new Error("Video CryptoException w/ errorCode "
                    + e.getErrorCode() + ", '" + e.getMessage() + "'");
            return;
        } catch (IllegalStateException e) {
            mErrorFromThread =
                new Error("Video CodecState.feedInputBuffer IllegalStateException " + e);
            return;
        }

        try {
            for (CodecState state : mAudioCodecStates.values()) {
                state.doSomeWork();
            }
        } catch (MediaCodec.CryptoException e) {
            mErrorFromThread = new Error("Audio CryptoException w/ errorCode "
                    + e.getErrorCode() + ", '" + e.getMessage() + "'");
            return;
        } catch (IllegalStateException e) {
            mErrorFromThread =
                new Error("Audio CodecState.feedInputBuffer IllegalStateException " + e);
            return;
        }
    }

    private void cycleSurfaces() {
        if (mSurfaces.size() > 1) {
            final Surface s = mSurfaces.removeFirst();
            mSurfaces.addLast(s);
            for (CodecState c : mVideoCodecStates.values()) {
                c.setOutputSurface(mSurfaces.getFirst());
                /*
                 * Calling InputSurface.clearSurface on an old `output` surface because after
                 * MediaCodec has rendered to the old output surface, we need `edit`
                 * (i.e. draw black on) the old output surface.
                 */
                InputSurface.clearSurface(s);
                break;
            }
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

    public int getCurrentPosition() {
        if (mVideoCodecStates == null) {
                return 0;
        }

        long positionUs = 0;

        for (CodecState state : mVideoCodecStates.values()) {
            long trackPositionUs = state.getCurrentPositionUs();

            if (trackPositionUs > positionUs) {
                positionUs = trackPositionUs;
            }
        }
        return (int)((positionUs + 500) / 1000);
    }

}
