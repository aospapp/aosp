/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.mediav2.cts;

import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010;
import static android.mediav2.common.cts.CodecEncoderTestBase.ACCEPTABLE_WIRELESS_TX_QUALITY;
import static android.mediav2.common.cts.CodecEncoderTestBase.colorFormatToString;
import static android.mediav2.common.cts.CodecEncoderTestBase.getMuxerFormatForMediaType;
import static android.mediav2.common.cts.CodecEncoderTestBase.getTempFilePath;
import static android.mediav2.common.cts.CodecTestBase.PROFILE_HLG_MAP;
import static android.mediav2.common.cts.CodecTestBase.VNDK_IS_AT_LEAST_T;
import static android.mediav2.common.cts.CodecTestBase.VNDK_IS_BEFORE_U;
import static android.mediav2.common.cts.CodecTestBase.hasSupportForColorFormat;
import static android.mediav2.common.cts.CodecTestBase.isDefaultCodec;
import static android.mediav2.common.cts.CodecTestBase.isHardwareAcceleratedCodec;
import static android.mediav2.common.cts.CodecTestBase.isSoftwareCodec;
import static android.mediav2.common.cts.CodecTestBase.isVendorCodec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.mediav2.common.cts.CodecAsyncHandler;
import android.mediav2.common.cts.CodecEncoderTestBase;
import android.mediav2.common.cts.CodecTestBase;
import android.mediav2.common.cts.EncoderConfigParams;
import android.mediav2.common.cts.OutputManager;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;

import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.ApiTest;
import com.android.compatibility.common.util.CddTest;
import com.android.compatibility.common.util.Preconditions;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * Test mediacodec api, video encoders and their interactions in surface mode.
 * <p>
 * The test decodes an input clip to surface. This decoded output is fed as input to encoder.
 * Assuming no frame drops, the test expects,
 * <ul>
 *     <li>The number of encoded frames to be identical to number of frames present in input clip
 *     .</li>
 *     <li>As encoders are expected to give consistent output for a given input and configuration
 *     parameters, the test checks for consistency across runs. For now, this attribute is not
 *     strictly enforced in this test.</li>
 *     <li>The encoder output timestamps list should be identical to decoder input timestamp list
 *     .</li>
 * </ul>
 * <p>
 * The output of encoder is further verified by computing PSNR to check for obvious visual
 * artifacts.
 * <p>
 * The test runs mediacodec in synchronous and asynchronous mode.
 */
@RunWith(Parameterized.class)
public class CodecEncoderSurfaceTest {
    private static final String LOG_TAG = CodecEncoderSurfaceTest.class.getSimpleName();
    private static final String MEDIA_DIR = WorkDir.getMediaDirString();
    private static final boolean ENABLE_LOGS = false;

    private final String mEncoderName;
    private final String mEncMediaType;
    private final String mDecoderName;
    private final String mTestFileMediaType;
    private final String mTestFile;
    private final EncoderConfigParams mEncCfgParams;
    private final int mColorFormat;
    private final boolean mIsOutputToneMapped;
    private final boolean mUsePersistentSurface;
    private final String mTestArgs;

    private MediaExtractor mExtractor;
    private MediaCodec mEncoder;
    private MediaFormat mEncoderFormat;
    private final CodecAsyncHandler mAsyncHandleEncoder = new CodecAsyncHandler();
    private MediaCodec mDecoder;
    private MediaFormat mDecoderFormat;
    private final CodecAsyncHandler mAsyncHandleDecoder = new CodecAsyncHandler();
    private boolean mIsCodecInAsyncMode;
    private boolean mSignalEOSWithLastFrame;
    private boolean mSawDecInputEOS;
    private boolean mSawDecOutputEOS;
    private boolean mSawEncOutputEOS;
    private int mDecInputCount;
    private int mDecOutputCount;
    private int mEncOutputCount;
    private int mLatency;
    private boolean mReviseLatency;

    private final StringBuilder mTestConfig = new StringBuilder();
    private final StringBuilder mTestEnv = new StringBuilder();

    private boolean mSaveToMem;
    private OutputManager mOutputBuff;

    private Surface mSurface;

    private MediaMuxer mMuxer;
    private int mTrackID = -1;

    private final ArrayList<String> mTmpFiles = new ArrayList<>();

    static {
        System.loadLibrary("ctsmediav2codecencsurface_jni");

        android.os.Bundle args = InstrumentationRegistry.getArguments();
        CodecTestBase.mediaTypeSelKeys = args.getString(CodecTestBase.MEDIA_TYPE_SEL_KEY);
    }

    public CodecEncoderSurfaceTest(String encoder, String mediaType, String decoder,
            String testFileMediaType, String testFile, EncoderConfigParams encCfgParams,
            int colorFormat, boolean isOutputToneMapped, boolean usePersistentSurface,
            @SuppressWarnings("unused") String testLabel, String allTestParams) {
        mEncoderName = encoder;
        mEncMediaType = mediaType;
        mDecoderName = decoder;
        mTestFileMediaType = testFileMediaType;
        mTestFile = MEDIA_DIR + testFile;
        mEncCfgParams = encCfgParams;
        mColorFormat = colorFormat;
        mIsOutputToneMapped = isOutputToneMapped;
        mUsePersistentSurface = usePersistentSurface;
        mTestArgs = allTestParams;
        mLatency = mEncCfgParams.mMaxBFrames;
        mReviseLatency = false;
    }

    @Rule
    public TestName mTestName = new TestName();

    @Before
    public void setUp() throws IOException, CloneNotSupportedException {
        mTestConfig.setLength(0);
        mTestConfig.append("\n##################       Test Details        ####################\n");
        mTestConfig.append("Test Name :- ").append(mTestName.getMethodName()).append("\n");
        mTestConfig.append("Test Parameters :- ").append(mTestArgs).append("\n");
        if (mEncoderName.startsWith(CodecTestBase.INVALID_CODEC) || mDecoderName.startsWith(
                CodecTestBase.INVALID_CODEC)) {
            fail("no valid component available for current test. \n" + mTestConfig);
        }
        mDecoderFormat = setUpSource(mTestFile);
        ArrayList<MediaFormat> decoderFormatList = new ArrayList<>();
        decoderFormatList.add(mDecoderFormat);
        Assume.assumeTrue("Decoder: " + mDecoderName + " doesn't support format: " + mDecoderFormat,
                CodecTestBase.areFormatsSupported(mDecoderName, mTestFileMediaType,
                        decoderFormatList));
        if (CodecTestBase.doesAnyFormatHaveHDRProfile(mTestFileMediaType, decoderFormatList)
                || mTestFile.contains("10bit")) {
            // Check if encoder is capable of supporting HDR profiles.
            // Previous check doesn't verify this as profile isn't set in the format
            Assume.assumeTrue(mEncoderName + " doesn't support HDR encoding",
                    CodecTestBase.doesCodecSupportHDRProfile(mEncoderName, mEncMediaType));
        }

        if (mColorFormat == COLOR_FormatSurface) {
            // TODO(b/253492870) Remove the following assumption check once this is supported
            Assume.assumeFalse(mDecoderName + "is hardware accelerated and " + mEncoderName
                            + "is software only.",
                    isHardwareAcceleratedCodec(mDecoderName) && isSoftwareCodec(mEncoderName));
        } else {
            // findDecoderForFormat() ignores color-format and decoder returned may not be
            // supporting the color format set in mDecoderFormat. Following check will
            // skip the test if decoder doesn't support the color format that is set.
            boolean decoderSupportsColorFormat =
                    hasSupportForColorFormat(mDecoderName, mTestFileMediaType, mColorFormat);
            if (mColorFormat == COLOR_FormatYUVP010) {
                assumeTrue(mDecoderName + " doesn't support P010 output.",
                        decoderSupportsColorFormat);
            } else {
                assertTrue(mDecoderName + " doesn't support 420p 888 flexible output.",
                        decoderSupportsColorFormat);
            }
        }
        EncoderConfigParams.Builder foreman = mEncCfgParams.getBuilder()
                .setWidth(mDecoderFormat.getInteger(MediaFormat.KEY_WIDTH))
                .setHeight(mDecoderFormat.getInteger(MediaFormat.KEY_HEIGHT));
        mEncoderFormat = foreman.build().getFormat();
    }

    private static EncoderConfigParams getVideoEncoderCfgParams(String mediaType, int bitRate,
            int frameRate, int bitDepth, int maxBFrames) {
        EncoderConfigParams.Builder foreman = new EncoderConfigParams.Builder(mediaType)
                .setBitRate(bitRate)
                .setFrameRate(frameRate)
                .setColorFormat(COLOR_FormatSurface)
                .setInputBitDepth(bitDepth)
                .setMaxBFrames(maxBFrames);
        if (bitDepth == 10) {
            foreman.setProfile(Objects.requireNonNull(PROFILE_HLG_MAP.get(mediaType))[0]);
        }
        return foreman.build();
    }

    @After
    public void tearDown() {
        if (mDecoder != null) {
            mDecoder.release();
            mDecoder = null;
        }
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        if (mEncoder != null) {
            mEncoder.release();
            mEncoder = null;
        }
        if (mExtractor != null) {
            mExtractor.release();
            mExtractor = null;
        }
        if (mMuxer != null) {
            mMuxer.release();
            mMuxer = null;
        }
        for (String tmpFile : mTmpFiles) {
            File tmp = new File(tmpFile);
            if (tmp.exists()) assertTrue("unable to delete file " + tmpFile, tmp.delete());
        }
        mTmpFiles.clear();
    }

    @Parameterized.Parameters(name = "{index}_{0}_{1}_{2}_{3}_{9}")
    public static Collection<Object[]> input() throws IOException {
        final boolean isEncoder = true;
        final boolean needAudio = false;
        final boolean needVideo = true;
        final List<Object[]> exhaustiveArgsList = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>(Arrays.asList(new Object[][]{
                // mediaType, testFileMediaType, testFile, bitRate, frameRate, toneMap
                {MediaFormat.MIMETYPE_VIDEO_H263, MediaFormat.MIMETYPE_VIDEO_H263,
                        "bbb_176x144_128kbps_15fps_h263.3gp", 128000, 15, false},
                {MediaFormat.MIMETYPE_VIDEO_MPEG4, MediaFormat.MIMETYPE_VIDEO_MPEG4,
                        "bbb_128x96_64kbps_12fps_mpeg4.mp4", 64000, 12, false},
                {MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_AVC,
                        "bbb_cif_768kbps_30fps_avc.mp4", 512000, 30, false},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC,
                        "bbb_cif_768kbps_30fps_avc.mp4", 512000, 30, false},
                {MediaFormat.MIMETYPE_VIDEO_VP8, MediaFormat.MIMETYPE_VIDEO_AVC,
                        "bbb_cif_768kbps_30fps_avc.mp4", 512000, 30, false},
                {MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_VIDEO_AVC,
                        "bbb_cif_768kbps_30fps_avc.mp4", 512000, 30, false},
                {MediaFormat.MIMETYPE_VIDEO_AV1, MediaFormat.MIMETYPE_VIDEO_AVC,
                        "bbb_cif_768kbps_30fps_avc.mp4", 512000, 30, false},
        }));

        final List<Object[]> argsHighBitDepth = new ArrayList<>(Arrays.asList(new Object[][]{
                {MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_AVC,
                        "cosmat_520x390_24fps_crf22_avc_10bit.mkv", 512000, 30, false},
                {MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_AVC,
                        "cosmat_520x390_24fps_crf22_avc_10bit.mkv", 512000, 30, true},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_HEVC,
                        "cosmat_520x390_24fps_crf22_hevc_10bit.mkv", 512000, 30, false},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_HEVC,
                        "cosmat_520x390_24fps_crf22_hevc_10bit.mkv", 512000, 30, true},
                {MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_VIDEO_VP9,
                        "cosmat_520x390_24fps_crf22_vp9_10bit.mkv", 512000, 30, false},
                {MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_VIDEO_VP9,
                        "cosmat_520x390_24fps_crf22_vp9_10bit.mkv", 512000, 30, true},
                {MediaFormat.MIMETYPE_VIDEO_AV1, MediaFormat.MIMETYPE_VIDEO_AV1,
                        "cosmat_520x390_24fps_768kbps_av1_10bit.mkv", 512000, 30, false},
                {MediaFormat.MIMETYPE_VIDEO_AV1, MediaFormat.MIMETYPE_VIDEO_AV1,
                        "cosmat_520x390_24fps_768kbps_av1_10bit.mkv", 512000, 30, true},
        }));

        int[] colorFormats = {COLOR_FormatSurface, COLOR_FormatYUV420Flexible};
        int[] maxBFrames = {0, 2};
        boolean[] boolStates = {true, false};
        for (Object[] arg : args) {
            final String mediaType = (String) arg[0];
            final int br = (int) arg[3];
            final int fps = (int) arg[4];
            for (int colorFormat : colorFormats) {
                for (boolean usePersistentSurface : boolStates) {
                    for (int maxBFrame : maxBFrames) {
                        if (!mediaType.equals(MediaFormat.MIMETYPE_VIDEO_AVC)
                                && !mediaType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)
                                && maxBFrame != 0) {
                            continue;
                        }
                        Object[] testArgs = new Object[8];
                        testArgs[0] = arg[0];   // encoder mediaType
                        testArgs[1] = arg[1];   // test file mediaType
                        testArgs[2] = arg[2];   // test file
                        testArgs[3] = getVideoEncoderCfgParams(mediaType, br, fps, 8, maxBFrame);
                        testArgs[4] = colorFormat;  // color format
                        testArgs[5] = arg[5];   // tone map
                        testArgs[6] = usePersistentSurface;
                        testArgs[7] = String.format("%dkbps_%dfps_%s_%s", br / 1000, fps,
                                colorFormatToString(colorFormat, 8),
                                usePersistentSurface ? "persistentsurface" : "surface");
                        exhaustiveArgsList.add(testArgs);
                    }
                }
            }
        }
        // P010 support was added in Android T, hence limit the following tests to Android T and
        // above
        if (CodecTestBase.IS_AT_LEAST_T) {
            int[] colorFormatsHbd = {COLOR_FormatSurface, COLOR_FormatYUVP010};
            for (Object[] arg : argsHighBitDepth) {
                final String mediaType = (String) arg[0];
                final int br = (int) arg[3];
                final int fps = (int) arg[4];
                final boolean toneMap = (boolean) arg[5];
                for (int colorFormat : colorFormatsHbd) {
                    for (boolean usePersistentSurface : boolStates) {
                        for (int maxBFrame : maxBFrames) {
                            if (!mediaType.equals(MediaFormat.MIMETYPE_VIDEO_AVC)
                                    && !mediaType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)
                                    && maxBFrame != 0) {
                                continue;
                            }
                            Object[] testArgs = new Object[8];
                            testArgs[0] = arg[0];   // encoder mediaType
                            testArgs[1] = arg[1];   // test file mediaType
                            testArgs[2] = arg[2];   // test file
                            testArgs[3] =
                                    getVideoEncoderCfgParams(mediaType, br, fps, toneMap ? 8 : 10,
                                            maxBFrame);
                            if (toneMap && (colorFormat == COLOR_FormatYUVP010)) {
                                colorFormat = COLOR_FormatYUV420Flexible;
                            }
                            testArgs[4] = colorFormat;  // color format
                            testArgs[5] = arg[5];   // tone map
                            testArgs[6] = usePersistentSurface;
                            testArgs[7] = String.format("%dkbps_%dfps_%s_%s_%s", br / 1000, fps,
                                    colorFormatToString(colorFormat, toneMap ? 8 : 10),
                                    toneMap ? "tonemapyes" : "tonemapno",
                                    usePersistentSurface ? "persistentsurface" : "surface");
                            exhaustiveArgsList.add(testArgs);
                        }
                    }
                }
            }
        }
        final List<Object[]> argsList = new ArrayList<>();
        for (Object[] arg : exhaustiveArgsList) {
            ArrayList<String> decoderList =
                    CodecTestBase.selectCodecs((String) arg[1], null, null, false);
            if (decoderList.size() == 0) {
                decoderList.add(CodecTestBase.INVALID_CODEC + arg[1]);
            }
            for (String decoderName : decoderList) {
                int argLength = exhaustiveArgsList.get(0).length;
                Object[] testArg = new Object[argLength + 1];
                testArg[0] = arg[0];  // encoder mediaType
                testArg[1] = decoderName;  // decoder name
                System.arraycopy(arg, 1, testArg, 2, argLength - 1);
                argsList.add(testArg);
            }
        }

        final List<Object[]> expandedArgsList =
                CodecTestBase.prepareParamList(argsList, isEncoder, needAudio, needVideo, true);

        // Prior to Android U, this test was using the first decoder for a given mediaType.
        // In Android U, this was updated to test the encoders with all decoders for the
        // given mediaType. There are some vendor encoders in older versions of Android
        // which do not work as expected with the surface from s/w decoder.
        // If the device is has vendor partition older than Android U, limit the tests
        // to first decoder like it was being done prior to Androd U
        final List<Object[]> finalArgsList = new ArrayList<>();
        for (Object[] arg : expandedArgsList) {
            String encoderName = (String) arg[0];
            String decoderName = (String) arg[2];
            String decoderMediaType = (String) arg[3];
            if (VNDK_IS_BEFORE_U && isVendorCodec(encoderName)) {
                if (!isDefaultCodec(decoderName, decoderMediaType, /* isEncoder */false)) {
                    continue;
                }
            }
            finalArgsList.add(arg);
        }
        return finalArgsList;
    }

    private boolean hasSeenError() {
        return mAsyncHandleDecoder.hasSeenError() || mAsyncHandleEncoder.hasSeenError();
    }

    private MediaFormat setUpSource(String srcFile) throws IOException {
        Preconditions.assertTestFileExists(srcFile);
        mExtractor = new MediaExtractor();
        mExtractor.setDataSource(srcFile);
        for (int trackID = 0; trackID < mExtractor.getTrackCount(); trackID++) {
            MediaFormat format = mExtractor.getTrackFormat(trackID);
            String mediaType = format.getString(MediaFormat.KEY_MIME);
            if (mediaType.equals(mTestFileMediaType)) {
                mExtractor.selectTrack(trackID);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, mColorFormat);
                if (mIsOutputToneMapped) {
                    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                            MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
                }
                return format;
            }
        }
        mExtractor.release();
        fail("No video track found in file: " + srcFile + ". \n" + mTestConfig + mTestEnv);
        return null;
    }

    private void resetContext(boolean isAsync, boolean signalEOSWithLastFrame) {
        mAsyncHandleDecoder.resetContext();
        mAsyncHandleEncoder.resetContext();
        mIsCodecInAsyncMode = isAsync;
        mSignalEOSWithLastFrame = signalEOSWithLastFrame;
        mSawDecInputEOS = false;
        mSawDecOutputEOS = false;
        mSawEncOutputEOS = false;
        mDecInputCount = 0;
        mDecOutputCount = 0;
        mEncOutputCount = 0;
    }

    private void configureCodec(MediaFormat decFormat, MediaFormat encFormat, boolean isAsync,
            boolean signalEOSWithLastFrame) {
        resetContext(isAsync, signalEOSWithLastFrame);
        mAsyncHandleEncoder.setCallBack(mEncoder, isAsync);
        mEncoder.configure(encFormat, null, MediaCodec.CONFIGURE_FLAG_ENCODE, null);
        if (mEncoder.getInputFormat().containsKey(MediaFormat.KEY_LATENCY)) {
            mReviseLatency = true;
            mLatency = mEncoder.getInputFormat().getInteger(MediaFormat.KEY_LATENCY);
        }
        if (mUsePersistentSurface) {
            mSurface = MediaCodec.createPersistentInputSurface();
            mEncoder.setInputSurface(mSurface);
        } else {
            mSurface = mEncoder.createInputSurface();
        }
        assertTrue("Surface is not valid", mSurface.isValid());
        mAsyncHandleDecoder.setCallBack(mDecoder, isAsync);
        mDecoder.configure(decFormat, mSurface, null, 0);
        mTestEnv.setLength(0);
        mTestEnv.append("###################      Test Environment       #####################\n");
        mTestEnv.append(String.format("Encoder under test :- %s \n", mEncoderName));
        mTestEnv.append(String.format("Format under test :- %s \n", encFormat));
        mTestEnv.append(String.format("Encoder is fed with output of :- %s \n", mDecoderName));
        mTestEnv.append(String.format("Format of Decoder Input :- %s", decFormat));
        mTestEnv.append(String.format("Encoder and Decoder are operating in :- %s mode \n",
                (isAsync ? "asynchronous" : "synchronous")));
        mTestEnv.append(String.format("Components received input eos :- %s \n",
                (signalEOSWithLastFrame ? "with full buffer" : "with empty buffer")));
        if (ENABLE_LOGS) {
            Log.v(LOG_TAG, "codec configured");
        }
    }

    private void enqueueDecoderEOS(int bufferIndex) {
        if (!mSawDecInputEOS) {
            mDecoder.queueInputBuffer(bufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            mSawDecInputEOS = true;
            if (ENABLE_LOGS) {
                Log.v(LOG_TAG, "Queued End of Stream");
            }
        }
    }

    private void enqueueDecoderInput(int bufferIndex) {
        if (mExtractor.getSampleSize() < 0) {
            enqueueDecoderEOS(bufferIndex);
        } else {
            ByteBuffer inputBuffer = mDecoder.getInputBuffer(bufferIndex);
            mExtractor.readSampleData(inputBuffer, 0);
            int size = (int) mExtractor.getSampleSize();
            long pts = mExtractor.getSampleTime();
            int extractorFlags = mExtractor.getSampleFlags();
            int codecFlags = 0;
            if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                codecFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
            }
            if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                codecFlags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
            }
            if (!mExtractor.advance() && mSignalEOSWithLastFrame) {
                codecFlags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                mSawDecInputEOS = true;
            }
            if (ENABLE_LOGS) {
                Log.v(LOG_TAG, "input: id: " + bufferIndex + " size: " + size + " pts: " + pts +
                        " flags: " + codecFlags);
            }
            mDecoder.queueInputBuffer(bufferIndex, 0, size, pts, codecFlags);
            if (size > 0 && (codecFlags & (MediaCodec.BUFFER_FLAG_CODEC_CONFIG |
                    MediaCodec.BUFFER_FLAG_PARTIAL_FRAME)) == 0) {
                mOutputBuff.saveInPTS(pts);
                mDecInputCount++;
            }
        }
    }

    private void dequeueDecoderOutput(int bufferIndex, MediaCodec.BufferInfo info) {
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mSawDecOutputEOS = true;
        }
        if (ENABLE_LOGS) {
            Log.v(LOG_TAG, "output: id: " + bufferIndex + " flags: " + info.flags + " size: " +
                    info.size + " timestamp: " + info.presentationTimeUs);
        }
        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            mDecOutputCount++;
        }
        mDecoder.releaseOutputBuffer(bufferIndex, mSurface != null);
    }

    private void dequeueEncoderOutput(int bufferIndex, MediaCodec.BufferInfo info) {
        if (ENABLE_LOGS) {
            Log.v(LOG_TAG, "encoder output: id: " + bufferIndex + " flags: " + info.flags +
                    " size: " + info.size + " timestamp: " + info.presentationTimeUs);
        }
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mSawEncOutputEOS = true;
        }
        if (info.size > 0) {
            ByteBuffer buf = mEncoder.getOutputBuffer(bufferIndex);
            if (mSaveToMem) {
                mOutputBuff.saveToMemory(buf, info);
            }
            if (mMuxer != null) {
                if (mTrackID == -1) {
                    mTrackID = mMuxer.addTrack(mEncoder.getOutputFormat());
                    mMuxer.start();
                }
                mMuxer.writeSampleData(mTrackID, buf, info);
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                mOutputBuff.saveOutPTS(info.presentationTimeUs);
                mEncOutputCount++;
            }
        }
        mEncoder.releaseOutputBuffer(bufferIndex, false);
    }

    private void tryEncoderOutput(long timeOutUs) throws InterruptedException {
        if (mIsCodecInAsyncMode) {
            if (!hasSeenError() && !mSawEncOutputEOS) {
                int retry = 0;
                while (mReviseLatency) {
                    if (mAsyncHandleEncoder.hasOutputFormatChanged()) {
                        mReviseLatency = false;
                        int actualLatency = mAsyncHandleEncoder.getOutputFormat()
                                .getInteger(MediaFormat.KEY_LATENCY, mLatency);
                        if (mLatency < actualLatency) {
                            mLatency = actualLatency;
                            return;
                        }
                    } else {
                        if (retry > CodecTestBase.RETRY_LIMIT) throw new InterruptedException(
                                "did not receive output format changed for encoder after " +
                                        CodecTestBase.Q_DEQ_TIMEOUT_US * CodecTestBase.RETRY_LIMIT +
                                        " us");
                        Thread.sleep(CodecTestBase.Q_DEQ_TIMEOUT_US / 1000);
                        retry++;
                    }
                }
                Pair<Integer, MediaCodec.BufferInfo> element = mAsyncHandleEncoder.getOutput();
                if (element != null) {
                    dequeueEncoderOutput(element.first, element.second);
                }
            }
        } else {
            MediaCodec.BufferInfo outInfo = new MediaCodec.BufferInfo();
            if (!mSawEncOutputEOS) {
                int outputBufferId = mEncoder.dequeueOutputBuffer(outInfo, timeOutUs);
                if (outputBufferId >= 0) {
                    dequeueEncoderOutput(outputBufferId, outInfo);
                } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    mLatency = mEncoder.getOutputFormat()
                            .getInteger(MediaFormat.KEY_LATENCY, mLatency);
                }
            }
        }
    }

    private void waitForAllEncoderOutputs() throws InterruptedException {
        if (mIsCodecInAsyncMode) {
            while (!hasSeenError() && !mSawEncOutputEOS) {
                tryEncoderOutput(CodecTestBase.Q_DEQ_TIMEOUT_US);
            }
        } else {
            while (!mSawEncOutputEOS) {
                tryEncoderOutput(CodecTestBase.Q_DEQ_TIMEOUT_US);
            }
        }
    }

    private void queueEOS() throws InterruptedException {
        if (mIsCodecInAsyncMode) {
            while (!mAsyncHandleDecoder.hasSeenError() && !mSawDecInputEOS) {
                Pair<Integer, MediaCodec.BufferInfo> element = mAsyncHandleDecoder.getWork();
                if (element != null) {
                    int bufferID = element.first;
                    MediaCodec.BufferInfo info = element.second;
                    if (info != null) {
                        dequeueDecoderOutput(bufferID, info);
                    } else {
                        enqueueDecoderEOS(element.first);
                    }
                }
            }
        } else {
            MediaCodec.BufferInfo outInfo = new MediaCodec.BufferInfo();
            while (!mSawDecInputEOS) {
                int outputBufferId =
                        mDecoder.dequeueOutputBuffer(outInfo, CodecTestBase.Q_DEQ_TIMEOUT_US);
                if (outputBufferId >= 0) {
                    dequeueDecoderOutput(outputBufferId, outInfo);
                }
                int inputBufferId = mDecoder.dequeueInputBuffer(CodecTestBase.Q_DEQ_TIMEOUT_US);
                if (inputBufferId != -1) {
                    enqueueDecoderEOS(inputBufferId);
                }
            }
        }
        if (mIsCodecInAsyncMode) {
            while (!hasSeenError() && !mSawDecOutputEOS) {
                Pair<Integer, MediaCodec.BufferInfo> decOp = mAsyncHandleDecoder.getOutput();
                if (decOp != null) dequeueDecoderOutput(decOp.first, decOp.second);
                if (mSawDecOutputEOS) mEncoder.signalEndOfInputStream();
                if (mDecOutputCount - mEncOutputCount > mLatency) {
                    tryEncoderOutput(-1);
                }
            }
        } else {
            MediaCodec.BufferInfo outInfo = new MediaCodec.BufferInfo();
            while (!mSawDecOutputEOS) {
                int outputBufferId =
                        mDecoder.dequeueOutputBuffer(outInfo, CodecTestBase.Q_DEQ_TIMEOUT_US);
                if (outputBufferId >= 0) {
                    dequeueDecoderOutput(outputBufferId, outInfo);
                }
                if (mSawDecOutputEOS) mEncoder.signalEndOfInputStream();
                if (mDecOutputCount - mEncOutputCount > mLatency) {
                    tryEncoderOutput(-1);
                }
            }
        }
    }

    private void doWork(int frameLimit) throws InterruptedException {
        int frameCnt = 0;
        if (mIsCodecInAsyncMode) {
            // dequeue output after inputEOS is expected to be done in waitForAllOutputs()
            while (!hasSeenError() && !mSawDecInputEOS && frameCnt < frameLimit) {
                Pair<Integer, MediaCodec.BufferInfo> element = mAsyncHandleDecoder.getWork();
                if (element != null) {
                    int bufferID = element.first;
                    MediaCodec.BufferInfo info = element.second;
                    if (info != null) {
                        // <id, info> corresponds to output callback. Handle it accordingly
                        dequeueDecoderOutput(bufferID, info);
                    } else {
                        // <id, null> corresponds to input callback. Handle it accordingly
                        enqueueDecoderInput(bufferID);
                        frameCnt++;
                    }
                }
                // check decoder EOS
                if (mSawDecOutputEOS) mEncoder.signalEndOfInputStream();
                // encoder output
                if (mDecOutputCount - mEncOutputCount > mLatency) {
                    tryEncoderOutput(-1);
                }
            }
        } else {
            MediaCodec.BufferInfo outInfo = new MediaCodec.BufferInfo();
            while (!mSawDecInputEOS && frameCnt < frameLimit) {
                // decoder input
                int inputBufferId = mDecoder.dequeueInputBuffer(CodecTestBase.Q_DEQ_TIMEOUT_US);
                if (inputBufferId != -1) {
                    enqueueDecoderInput(inputBufferId);
                    frameCnt++;
                }
                // decoder output
                int outputBufferId =
                        mDecoder.dequeueOutputBuffer(outInfo, CodecTestBase.Q_DEQ_TIMEOUT_US);
                if (outputBufferId >= 0) {
                    dequeueDecoderOutput(outputBufferId, outInfo);
                }
                // check decoder EOS
                if (mSawDecOutputEOS) mEncoder.signalEndOfInputStream();
                // encoder output
                if (mDecOutputCount - mEncOutputCount > mLatency) {
                    tryEncoderOutput(-1);
                }
            }
        }
    }

    private void validateToneMappedFormat(MediaFormat format, String descriptor) {
        assertEquals("unexpected color transfer in " + descriptor + " after tone mapping",
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                format.getInteger(MediaFormat.KEY_COLOR_TRANSFER, 0));
        assertNotEquals("unexpected color standard in " + descriptor + " after tone mapping",
                MediaFormat.COLOR_STANDARD_BT2020,
                format.getInteger(MediaFormat.KEY_COLOR_STANDARD, 0));

        int profile = format.getInteger(MediaFormat.KEY_PROFILE, -1);
        int[] profileArray = CodecTestBase.PROFILE_HDR_MAP.get(mEncMediaType);
        assertFalse(descriptor + " must not contain HDR profile after tone mapping",
                IntStream.of(profileArray).anyMatch(x -> x == profile));
    }

    /**
     * Checks if the component under test can encode from surface properly. The test runs
     * mediacodec in both synchronous and asynchronous mode. The test feeds the encoder input
     * surface with output of decoder. Assuming no frame drops, the number of output frames from
     * encoder should be identical to number of input frames to decoder. Also the timestamps
     * should be identical. As encoder output is deterministic, the test expects consistent
     * output in all runs. The output is written to a file using muxer. This file is validated
     * for PSNR to check if the encoding happened successfully with out any obvious artifacts.
     */
    @CddTest(requirements = {"2.2.2", "2.3.2", "2.5.2"})
    @ApiTest(apis = {"MediaCodecInfo.CodecCapabilities#COLOR_FormatSurface"})
    @LargeTest
    @Test(timeout = CodecTestBase.PER_TEST_TIMEOUT_LARGE_TEST_MS)
    public void testSimpleEncodeFromSurface() throws IOException, InterruptedException {
        mDecoder = MediaCodec.createByCodecName(mDecoderName);
        String tmpPath = null;
        boolean muxOutput = true;
        if (mEncMediaType.equals(MediaFormat.MIMETYPE_VIDEO_AV1) && CodecTestBase.IS_BEFORE_U) {
            muxOutput = false;
        }
        {
            mEncoder = MediaCodec.createByCodecName(mEncoderName);
            /* TODO(b/149027258) */
            mSaveToMem = false;
            OutputManager ref = new OutputManager();
            OutputManager test = new OutputManager(ref.getSharedErrorLogs());
            int loopCounter = 0;
            boolean[] boolStates = {true, false};
            for (boolean isAsync : boolStates) {
                mExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                mOutputBuff = loopCounter == 0 ? ref : test;
                mOutputBuff.reset();
                if (muxOutput && loopCounter == 0) {
                    int muxerFormat = getMuxerFormatForMediaType(mEncMediaType);
                    tmpPath = getTempFilePath(mEncCfgParams.mInputBitDepth > 8 ? "10bit" : "");
                    mTmpFiles.add(tmpPath);
                    mMuxer = new MediaMuxer(tmpPath, muxerFormat);
                }
                configureCodec(mDecoderFormat, mEncoderFormat, isAsync, false);
                if (mIsOutputToneMapped) {
                    int transferRequest = mDecoder.getInputFormat().getInteger(
                            MediaFormat.KEY_COLOR_TRANSFER_REQUEST, 0);
                    assumeTrue(mDecoderName + " does not support HDR to SDR tone mapping",
                            0 != transferRequest);
                }
                mEncoder.start();
                mDecoder.start();
                doWork(Integer.MAX_VALUE);
                queueEOS();
                waitForAllEncoderOutputs();
                MediaFormat encoderOutputFormat = mEncoder.getOutputFormat();
                MediaFormat decoderOutputFormat = mDecoder.getOutputFormat();
                if (muxOutput) {
                    if (mTrackID != -1) {
                        mMuxer.stop();
                        mTrackID = -1;
                    }
                    if (mMuxer != null) {
                        mMuxer.release();
                        mMuxer = null;
                    }
                }
                mDecoder.stop();
                /* TODO(b/147348711) */
                if (false) mEncoder.stop();
                else mEncoder.reset();

                assertFalse("Decoder has encountered error in async mode. \n"
                                + mTestConfig + mTestEnv + mAsyncHandleDecoder.getErrMsg(),
                        mAsyncHandleDecoder.hasSeenError());
                assertFalse("Encoder has encountered error in async mode. \n"
                                + mTestConfig + mTestEnv + mAsyncHandleEncoder.getErrMsg(),
                        mAsyncHandleEncoder.hasSeenError());
                assertTrue("Decoder has not received any input \n" + mTestConfig + mTestEnv,
                        0 != mDecInputCount);
                assertTrue("Decoder has not sent any output \n" + mTestConfig + mTestEnv,
                        0 != mDecOutputCount);
                assertTrue("Encoder has not sent any output \n" + mTestConfig + mTestEnv,
                        0 != mEncOutputCount);
                assertEquals("Decoder output count is not equal to decoder input count \n"
                        + mTestConfig + mTestEnv, mDecInputCount, mDecOutputCount);

                /* TODO(b/153127506)
                 *  Currently disabling all encoder output checks. Added checks only for encoder
                 *  timeStamp is in increasing order or not.
                 *  Once issue is fixed remove increasing timestamp check and enable encoder checks.
                 */
                /*assertEquals("Encoder output count is not equal to Decoder input count \n"
                        + mTestConfig + mTestEnv, mDecInputCount, mEncOutputCount);
                if (loopCounter != 0 && !ref.equals(test)) {
                    fail("Encoder output is not consistent across runs \n" + mTestConfig + mTestEnv
                            + test.getErrMsg());
                }
                if (loopCounter == 0 &&
                        !ref.isOutPtsListIdenticalToInpPtsList((mEncCfgParams.mMaxBFrames != 0))) {
                    fail("Input pts list and Output pts list are not identical \n" + mTestConfig
                            + mTestEnv + ref.getErrMsg());
                }*/
                if (mEncCfgParams.mMaxBFrames == 0 && !mOutputBuff.isPtsStrictlyIncreasing(
                        Long.MIN_VALUE)) {
                    fail("Output timestamps are not strictly increasing \n" + mTestConfig + mTestEnv
                            + mOutputBuff.getErrMsg());
                }
                if (mIsOutputToneMapped) {
                    validateToneMappedFormat(decoderOutputFormat, "decoder output format");
                    validateToneMappedFormat(encoderOutputFormat, "encoder output format");

                    if (tmpPath != null) {
                        MediaExtractor extractor = new MediaExtractor();
                        extractor.setDataSource(tmpPath);
                        MediaFormat extractorFormat = extractor.getTrackFormat(0);
                        extractor.release();
                        validateToneMappedFormat(extractorFormat, "extractor format");
                    }
                }
                loopCounter++;
                mSurface.release();
                mSurface = null;
            }
            mEncoder.release();
        }
        mDecoder.release();
        mExtractor.release();
        // Skip stream validation as there is no reference for tone mapped input
        if (muxOutput && !mIsOutputToneMapped) {
            if (mEncCfgParams.mInputBitDepth > 8 && !VNDK_IS_AT_LEAST_T) return;
            CodecEncoderTestBase.validateEncodedPSNR(mTestFileMediaType, mTestFile, mEncMediaType,
                    tmpPath, false, false, ACCEPTABLE_WIRELESS_TX_QUALITY);
        }
    }

    private native boolean nativeTestSimpleEncode(String encoder, String decoder, String mediaType,
            String testFile, String muxFile, int colorFormat, boolean usePersistentSurface,
            String cfgParams, String separator, StringBuilder retMsg);

    /**
     * Test is similar to {@link #testSimpleEncodeFromSurface()} but uses ndk api
     */
    @CddTest(requirements = {"2.2.2", "2.3.2", "2.5.2"})
    @ApiTest(apis = {"MediaCodecInfo.CodecCapabilities#COLOR_FormatSurface"})
    @LargeTest
    @Test(timeout = CodecTestBase.PER_TEST_TIMEOUT_LARGE_TEST_MS)
    public void testSimpleEncodeFromSurfaceNative() throws IOException, InterruptedException {
        // TODO(b/281661171) Update native tests to encode for tone mapped output
        assumeFalse("tone mapping tests are skipped in native mode", mIsOutputToneMapped);
        String tmpPath = null;
        if (!mEncMediaType.equals(MediaFormat.MIMETYPE_VIDEO_AV1) || CodecTestBase.IS_AT_LEAST_U) {
            tmpPath = getTempFilePath(mEncCfgParams.mInputBitDepth > 8 ? "10bit" : "");
            mTmpFiles.add(tmpPath);
        }
        int colorFormat = mDecoderFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT, -1);
        boolean isPass = nativeTestSimpleEncode(mEncoderName, mDecoderName, mEncMediaType,
                mTestFile, tmpPath, colorFormat, mUsePersistentSurface,
                EncoderConfigParams.serializeMediaFormat(mEncoderFormat),
                EncoderConfigParams.TOKEN_SEPARATOR, mTestConfig);
        assertTrue(mTestConfig.toString(), isPass);
        if (tmpPath != null) {
            if (mEncCfgParams.mInputBitDepth > 8 && !VNDK_IS_AT_LEAST_T) return;
            CodecEncoderTestBase.validateEncodedPSNR(mTestFileMediaType, mTestFile, mEncMediaType,
                    tmpPath, false, false, ACCEPTABLE_WIRELESS_TX_QUALITY);
        }
    }
}
