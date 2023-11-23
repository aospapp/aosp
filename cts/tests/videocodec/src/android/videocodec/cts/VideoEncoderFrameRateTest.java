/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.mediav2.common.cts.CodecTestBase.ComponentClass.HARDWARE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import android.media.MediaFormat;
import android.mediav2.common.cts.EncoderConfigParams;
import android.mediav2.common.cts.RawResource;

import com.android.compatibility.common.util.ApiTest;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

/**
 * This test is to verify whether the rate control process of the tested encoder generates a
 * bitstream whose size matches the frame rate set by the media format key of KEY_FRAME_RATE.
 * <p></p>
 * Test Params:
 * <p>Input resolution = 1920x1080</p>
 * <p>Number of frames = 300</p>
 * <p>Target bitrate = 5 Mbps</p>
 * <p>MaxBFrames = 0/1</p>
 * <p>FPS = 15/30</p>
 * <p>IFrameInterval = 1 second</p>
 * <p></p>
 * Let's denote the encoded file size for fps=15 as A, and the encoded file size for fps=30
 * as B. It is expected that 0.8 <= A/(2*B) <= 1.2.
 */
@RunWith(Parameterized.class)
public class VideoEncoderFrameRateTest extends VideoEncoderValidationTestBase {
    private static final String LOG_TAG = VideoEncoderFrameRateTest.class.getSimpleName();
    private static final int KEY_FRAME_INTERVAL = 1;
    private static final int FRAME_LIMIT = 300;
    private static final int BASE_FRAME_RATE = 30;
    private static final int BIT_RATE = 5000000;
    private static final List<Object[]> exhaustiveArgsList = new ArrayList<>();
    private static final HashMap<String, RawResource> RES_YUV_MAP = new HashMap<>();

    @BeforeClass
    public static void decodeResourcesToYuv() {
        ArrayList<CompressedResource> resources = new ArrayList<>();
        for (Object[] arg : exhaustiveArgsList) {
            resources.add((CompressedResource) arg[2]);
        }
        decodeStreamsToYuv(resources, RES_YUV_MAP, LOG_TAG);
    }

    @AfterClass
    public static void cleanUpResources() {
        for (RawResource res : RES_YUV_MAP.values()) {
            new File(res.mFileName).delete();
        }
    }

    private static EncoderConfigParams getVideoEncoderCfgParams(String mediaType, int width,
            int height, int maxBFrames) {
        return new EncoderConfigParams.Builder(mediaType)
                .setBitRate(BIT_RATE)
                .setKeyFrameInterval(KEY_FRAME_INTERVAL)
                .setFrameRate(BASE_FRAME_RATE)
                .setWidth(width)
                .setHeight(height)
                .setMaxBFrames(maxBFrames)
                .build();
    }

    private static void addParams(int width, int height, CompressedResource res) {
        final String[] mediaTypes = new String[]{MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AV1};
        final int[] maxBFramesPerSubGop = new int[]{0, 1};
        for (String mediaType : mediaTypes) {
            for (int maxBFrames : maxBFramesPerSubGop) {
                // mediaType, cfg, resource file, test label
                if (!mediaType.equals(MediaFormat.MIMETYPE_VIDEO_AVC)
                        && !mediaType.equals((MediaFormat.MIMETYPE_VIDEO_HEVC))
                        && maxBFrames != 0) {
                    continue;
                }
                String label = String.format("%dkbps_%dx%d_maxb-%d", BIT_RATE / 1000, width,
                        height, maxBFrames);
                exhaustiveArgsList.add(new Object[]{mediaType, getVideoEncoderCfgParams(mediaType,
                        width, height, maxBFrames), res, label});
            }
        }
    }

    @Parameterized.Parameters(name = "{index}_{0}_{1}_{4}")
    public static Collection<Object[]> input() {
        addParams(1920, 1080, BIRTHDAY_FULLHD_LANDSCAPE);
        addParams(1080, 1920, SELFIEGROUP_FULLHD_PORTRAIT);
        return prepareParamList(exhaustiveArgsList, true, false, true, false, HARDWARE);
    }

    public VideoEncoderFrameRateTest(String encoder, String mediaType,
            EncoderConfigParams cfgParams, CompressedResource res,
            @SuppressWarnings("unused") String testLabel, String allTestParams) {
        super(encoder, mediaType, cfgParams, res, allTestParams);
    }

    @Before
    public void setUp() {
        mIsLoopBack = true;
    }

    @ApiTest(apis = {"android.media.MediaFormat#KEY_FRAME_RATE"})
    @Test
    public void testEncoderFrameRateSupport() throws IOException, InterruptedException,
            CloneNotSupportedException {
        float[] scaleFactors = new float[]{1.0f, 0.5f};
        float refFactor = -1.0f;
        int refSize = -1;
        boolean testSkipped = false;
        RawResource res = RES_YUV_MAP.getOrDefault(mCRes.uniqueLabel(), null);
        assumeNotNull("no raw resource found for testing config : " + mEncCfgParams[0] + mTestConfig
                + mTestEnv + DIAGNOSTICS, res);
        for (float scaleFactor : scaleFactors) {
            EncoderConfigParams cfg = mEncCfgParams[0].getBuilder()
                    .setFrameRate((int) (BASE_FRAME_RATE * scaleFactor)).build();
            MediaFormat format = cfg.getFormat();
            ArrayList<MediaFormat> formats = new ArrayList<>();
            formats.add(format);
            if (!areFormatsSupported(mCodecName, mMediaType, formats)) {
                continue;
            }
            encodeToMemory(mCodecName, cfg, res, FRAME_LIMIT, true, false);
            assertEquals("encoder did not encode the requested number of frames \n" + mTestConfig
                    + mTestEnv, FRAME_LIMIT, mOutputCount);
            if (refFactor == -1.0) {
                refFactor = scaleFactor;
                refSize = mOutputBuff.getOutStreamSize();
            } else {
                testSkipped = true;
                float scaledStreamSize = (scaleFactor / refFactor) * mOutputBuff.getOutStreamSize();
                float sizeRatio = scaledStreamSize / refSize;
                String msg = String.format(
                        "Output bitstream size does not scale as per configured frame rate. \n"
                                + "For %d frame rate, encoder generates %d bytes of data. \n"
                                + "for %d frame rate, encoder generates %d bytes of data. \n",
                        (int) (BASE_FRAME_RATE * refFactor), refSize,
                        (int) (BASE_FRAME_RATE * scaleFactor), mOutputBuff.getOutStreamSize());
                assertTrue(msg + mTestConfig + mTestEnv, sizeRatio >= 0.8 && sizeRatio <= 1.2);
            }
        }
        assumeTrue("did not find at least 2 formats that are supported by the component",
                testSkipped);
    }
}
