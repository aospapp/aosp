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

package android.videocodec.cts;

import static android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;
import static android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
import static android.mediav2.common.cts.CodecTestBase.ComponentClass.HARDWARE;
import static android.videocodec.cts.VideoEncoderValidationTestBase.BIRTHDAY_FULLHD_LANDSCAPE;
import static android.videocodec.cts.VideoEncoderValidationTestBase.DIAGNOSTICS;
import static android.videocodec.cts.VideoEncoderValidationTestBase.logAllFilesInCacheDir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import android.media.MediaFormat;
import android.mediav2.common.cts.CodecEncoderTestBase;
import android.mediav2.common.cts.CodecTestBase;
import android.mediav2.common.cts.CompareStreams;
import android.mediav2.common.cts.DecodeStreamToYuv;
import android.mediav2.common.cts.EncoderConfigParams;
import android.mediav2.common.cts.RawResource;
import android.util.Log;

import com.android.compatibility.common.util.ApiTest;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * This test is to ensure no quality regression is seen from avc to hevc. Also no quality
 * regression is seen from '0' b frames to '1' b frame
 * <p></p>
 * Global Encode Config:
 * <p>Input resolution = 1080p30fps</p>
 * <p>Bitrate mode = VBR/CBR</p>
 * <p>Codec type = AVC/HEVC</p>
 * <p>IFrameInterval = 1 seconds</p>
 * <p></p>
 */
@RunWith(Parameterized.class)
public class VideoEncoderQualityRegressionTest {
    private static final String LOG_TAG = VideoEncoderQualityRegressionTest.class.getSimpleName();
    private static final String[] MEDIA_TYPES =
            {MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC};
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int[] BIT_RATES = {2000000, 4000000, 6000000, 8000000, 10000000, 12000000};
    private static final int[] BIT_RATE_MODES = {BITRATE_MODE_CBR, BITRATE_MODE_VBR};
    private static final int[] B_FRAMES = {0, 1};
    private static final int FRAME_RATE = 30;
    private static final int KEY_FRAME_INTERVAL = 1;
    private static final int FRAME_LIMIT = 300;
    private static final List<Object[]> exhaustiveArgsList = new ArrayList<>();
    private static final VideoEncoderValidationTestBase.CompressedResource RES =
            BIRTHDAY_FULLHD_LANDSCAPE;
    private static RawResource sActiveRawRes = null;

    private final int mBitRateMode;
    private final ArrayList<String> mTmpFiles = new ArrayList<>();

    static {
        System.loadLibrary("ctsvideoqualityutils_jni");
    }

    @Parameterized.Parameters(name = "{index}_{1}")
    public static Collection<Object[]> input() {
        for (int bitRateMode : BIT_RATE_MODES) {
            exhaustiveArgsList.add(new Object[]{bitRateMode,
                    CodecEncoderTestBase.bitRateModeToString(bitRateMode)});
        }
        return exhaustiveArgsList;
    }

    public VideoEncoderQualityRegressionTest(int bitRateMode,
            @SuppressWarnings("unused") String testLabel) {
        mBitRateMode = bitRateMode;
    }

    @BeforeClass
    public static void decodeResourceToYuv() throws IOException, InterruptedException {
        logAllFilesInCacheDir(true);
        try {
            DecodeStreamToYuv yuv = new DecodeStreamToYuv(RES.mMediaType, RES.mResFile,
                    FRAME_LIMIT, LOG_TAG);
            sActiveRawRes = yuv.getDecodedYuv();
        } catch (Exception e) {
            DIAGNOSTICS.append(String.format("\nWhile decoding the resource : %s,"
                    + " encountered exception :  %s was thrown", RES, e));
            logAllFilesInCacheDir(false);
        }
    }

    @AfterClass
    public static void cleanUpResources() {
        new File(sActiveRawRes.mFileName).delete();
        sActiveRawRes = null;
    }

    @Before
    public void setUp() {
        assumeNotNull("no raw resource found for testing : ", sActiveRawRes);
    }

    @After
    public void tearDown() {
        for (String tmpFile : mTmpFiles) {
            File tmp = new File(tmpFile);
            if (tmp.exists()) assertTrue("unable to delete file " + tmpFile, tmp.delete());
        }
        mTmpFiles.clear();
    }

    private static EncoderConfigParams getVideoEncoderCfgParams(String mediaType, int bitRate,
            int bitRateMode, int maxBFrames) {
        return new EncoderConfigParams.Builder(mediaType)
                .setWidth(VideoEncoderQualityRegressionTest.WIDTH)
                .setHeight(VideoEncoderQualityRegressionTest.HEIGHT)
                .setBitRate(bitRate)
                .setBitRateMode(bitRateMode)
                .setKeyFrameInterval(KEY_FRAME_INTERVAL)
                .setFrameRate(FRAME_RATE)
                .setMaxBFrames(maxBFrames)
                .build();
    }

    private native double nativeGetBDRate(double[] qualitiesA, double[] ratesA, double[] qualitiesB,
            double[] ratesB, boolean selBdSnr, StringBuilder retMsg);

    void getQualityRegressionForCfgs(List<EncoderConfigParams[]> cfgsUnion, String[] encoderNames,
            double minGain) throws IOException, InterruptedException {
        double[][] psnrs = new double[cfgsUnion.size()][cfgsUnion.get(0).length];
        double[][] rates = new double[cfgsUnion.size()][cfgsUnion.get(0).length];
        for (int i = 0; i < cfgsUnion.size(); i++) {
            EncoderConfigParams[] cfgs = cfgsUnion.get(i);
            String mediaType = cfgs[0].mMediaType;
            VideoEncoderValidationTestBase vevtb = new VideoEncoderValidationTestBase(null,
                    mediaType, null, null, "");
            vevtb.setLoopBack(true);
            for (int j = 0; j < cfgs.length; j++) {
                vevtb.encodeToMemory(encoderNames[i], cfgs[j], sActiveRawRes, FRAME_LIMIT, true,
                        true);
                mTmpFiles.add(vevtb.getMuxedOutputFilePath());
                assertEquals("encoder did not encode the requested number of frames \n",
                        FRAME_LIMIT, vevtb.getOutputCount());
                int outSize = vevtb.getOutputManager().getOutStreamSize();
                double achievedBitRate = ((double) outSize * 8 * FRAME_RATE) / (1000 * FRAME_LIMIT);
                CompareStreams cs = null;
                try {
                    cs = new CompareStreams(sActiveRawRes, mediaType,
                            vevtb.getMuxedOutputFilePath(), true, true);
                    final double[] globalPSNR = cs.getGlobalPSNR();
                    double weightedPSNR = (6 * globalPSNR[0] + globalPSNR[1] + globalPSNR[2]) / 8;
                    psnrs[i][j] = weightedPSNR;
                    rates[i][j] = achievedBitRate;
                } finally {
                    if (cs != null) cs.cleanUp();
                }
                vevtb.deleteMuxedFile();
            }
        }
        StringBuilder retMsg = new StringBuilder();
        double bdRate = nativeGetBDRate(psnrs[0], rates[0], psnrs[1], rates[1], false, retMsg);
        if (retMsg.length() != 0) fail(retMsg.toString());
        for (int i = 0; i < psnrs.length; i++) {
            retMsg.append(String.format("\nBitrate GlbPsnr Set %d\n", i));
            for (int j = 0; j < psnrs[i].length; j++) {
                retMsg.append(String.format("{%f, %f},\n", rates[i][j], psnrs[i][j]));
            }
        }
        retMsg.append(String.format("bd rate %f not < %f", bdRate, minGain));
        Log.d(LOG_TAG, retMsg.toString());
        // assuming set B encoding is superior to set A,
        assumeTrue(retMsg.toString(), bdRate < minGain);
    }

    @ApiTest(apis = {"android.media.MediaFormat#KEY_BITRATE",
            "android.media.MediaFormat#KEY_BITRATE_MODE"})
    @Test
    public void testQualityRegressionOverCodecs() throws IOException, InterruptedException {
        String[] encoderNames = new String[MEDIA_TYPES.length];
        List<EncoderConfigParams[]> cfgsUnion = new ArrayList<>();
        for (int i = 0; i < MEDIA_TYPES.length; i++) {
            EncoderConfigParams[] cfgsOfMediaType = new EncoderConfigParams[BIT_RATES.length];
            cfgsUnion.add(cfgsOfMediaType);
            ArrayList<MediaFormat> fmts = new ArrayList<>();
            for (int j = 0; j < cfgsOfMediaType.length; j++) {
                cfgsOfMediaType[j] = getVideoEncoderCfgParams(MEDIA_TYPES[i], BIT_RATES[j],
                        mBitRateMode, 0);
                fmts.add(cfgsOfMediaType[j].getFormat());
            }
            ArrayList<String> encoders = CodecTestBase.selectCodecs(MEDIA_TYPES[i], fmts, null,
                    true, HARDWARE);
            Assume.assumeTrue("no encoders present on device that support encoding fmts: " + fmts,
                    encoders.size() > 0);
            encoderNames[i] = encoders.get(0);
        }
        getQualityRegressionForCfgs(cfgsUnion, encoderNames, 0);
    }

    void testQualityRegressionOverBFrames(String mediaType)
            throws IOException, InterruptedException {
        String[] encoderNames = new String[B_FRAMES.length];
        List<EncoderConfigParams[]> cfgsUnion = new ArrayList<>();
        for (int i = 0; i < B_FRAMES.length; i++) {
            EncoderConfigParams[] cfgs = new EncoderConfigParams[BIT_RATES.length];
            cfgsUnion.add(cfgs);
            ArrayList<MediaFormat> fmts = new ArrayList<>();
            for (int j = 0; j < cfgs.length; j++) {
                cfgs[j] = getVideoEncoderCfgParams(mediaType, BIT_RATES[j], mBitRateMode,
                        B_FRAMES[i]);
                fmts.add(cfgs[j].getFormat());
            }
            ArrayList<String> encoders = CodecTestBase.selectCodecs(mediaType, fmts, null, true,
                    HARDWARE);
            Assume.assumeTrue("no encoders present on device that support encoding fmts: " + fmts,
                    encoders.size() > 0);
            encoderNames[i] = encoders.get(0);
        }
        getQualityRegressionForCfgs(cfgsUnion, encoderNames, 0.000001d);
    }

    @ApiTest(apis = {"android.media.MediaFormat#KEY_BITRATE",
            "android.media.MediaFormat#KEY_BITRATE_MODE",
            "android.media.MediaFormat#KEY_MAX_B_FRAMES"})
    @Test
    public void testQualityRegressionOverBFramesAvc() throws IOException, InterruptedException {
        testQualityRegressionOverBFrames(MediaFormat.MIMETYPE_VIDEO_AVC);
    }

    @ApiTest(apis = {"android.media.MediaFormat#KEY_BITRATE",
            "android.media.MediaFormat#KEY_BITRATE_MODE",
            "android.media.MediaFormat#KEY_MAX_B_FRAMES"})
    @Test
    public void testQualityRegressionOverBFramesHevc() throws IOException, InterruptedException {
        testQualityRegressionOverBFrames(MediaFormat.MIMETYPE_VIDEO_HEVC);
    }
}
