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

import static android.media.MediaFormat.PICTURE_TYPE_B;
import static android.media.MediaFormat.PICTURE_TYPE_I;
import static android.media.MediaFormat.PICTURE_TYPE_P;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import android.media.MediaFormat;
import android.mediav2.common.cts.BitStreamUtils;
import android.mediav2.common.cts.EncoderConfigParams;
import android.mediav2.common.cts.RawResource;

import com.android.compatibility.common.util.ApiTest;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test to verify if B frames are correctly inserted by the encoder.
 * <p></p>
 * Encode Configs:
 * <p>Input resolution = 1080p30fps</p>
 * <p>Number of frames = 600</p>
 * <p>Target bitrate = 5 Mbps</p>
 * <p>MaxBFrames = 0/1</p>
 * <p>IFrameInterval = 1 seconds</p>
 * <p></p>
 * The number of b frames in a sub-gop should not exceed MaxBFrames
 */
@RunWith(Parameterized.class)
public class VideoEncoderMaxBFrameTest extends VideoEncoderValidationTestBase {
    private static final String LOG_TAG = VideoEncoderMaxBFrameTest.class.getSimpleName();
    private static final int FRAME_LIMIT = 600;
    private static final int BIT_RATE = 5000000;
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
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

    private static EncoderConfigParams getVideoEncoderCfgParams(String mediaType, int maxBFrames) {
        return new EncoderConfigParams.Builder(mediaType)
                .setBitRate(BIT_RATE)
                .setWidth(WIDTH)
                .setHeight(HEIGHT)
                .setMaxBFrames(maxBFrames)
                .build();
    }

    @Parameterized.Parameters(name = "{index}_{0}_{1}_{4}")
    public static Collection<Object[]> input() {
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
                String label = String.format("%dkbps_%dx%d_maxb-%d", BIT_RATE / 1000, WIDTH,
                        HEIGHT, maxBFrames);
                exhaustiveArgsList.add(new Object[]{mediaType, getVideoEncoderCfgParams(mediaType,
                        maxBFrames), BIRTHDAY_FULLHD_LANDSCAPE, label});
            }
        }
        return prepareParamList(exhaustiveArgsList, true, false, true, false);
    }

    public VideoEncoderMaxBFrameTest(String encoder, String mediaType, EncoderConfigParams cfg,
            CompressedResource res, @SuppressWarnings("unused") String testLabel,
            String allTestParams) {
        super(encoder, mediaType, cfg, res, allTestParams);
    }

    @Before
    public void setUp() {
        mIsLoopBack = true;
        mParser = BitStreamUtils.getParserObject(mMediaType);
    }

    @ApiTest(apis = {"android.media.MediaFormat#KEY_MAX_B_FRAMES"})
    @Test
    public void testMaxBFrameSupport() throws IOException, InterruptedException {
        MediaFormat format = mEncCfgParams[0].getFormat();
        ArrayList<MediaFormat> formats = new ArrayList<>();
        formats.add(format);
        Assume.assumeTrue("Encoder: " + mCodecName + " doesn't support format: " + format,
                areFormatsSupported(mCodecName, mMediaType, formats));
        RawResource res = RES_YUV_MAP.getOrDefault(mCRes.uniqueLabel(), null);
        assumeNotNull("no raw resource found for testing config : " + mEncCfgParams[0] + mTestConfig
                + mTestEnv + DIAGNOSTICS, res);
        encodeToMemory(mCodecName, mEncCfgParams[0], res, FRAME_LIMIT, false, false);
        assertEquals("encoder did not encode the requested number of frames \n"
                + mTestConfig + mTestEnv, FRAME_LIMIT, mOutputCount);
        int bFramesInSubGop = 0, maxBFramesFound = -1;
        for (Map.Entry<Long, Integer> pic : mPtsPicTypeMap.entrySet()) {
            int picType = pic.getValue();
            assertTrue("Test could not gather picture types of encoded frames \n" + mTestConfig
                    + mTestEnv, picType == PICTURE_TYPE_I || picType == PICTURE_TYPE_P
                    || picType == PICTURE_TYPE_B);
            if (pic.getValue() != PICTURE_TYPE_B) {
                maxBFramesFound = Math.max(maxBFramesFound, bFramesInSubGop);
                bFramesInSubGop = 0;
            } else {
                bFramesInSubGop++;
            }
        }
        String msg = String.format("Number of BFrames in a SubGOP exceeds maximum number of"
                        + " BFrames configured.\n Configured max BFrames %d. \n Got max"
                        + " BFrames %d. \n", mEncCfgParams[0].mMaxBFrames, maxBFramesFound);
        assertTrue(msg + mTestConfig + mTestEnv, maxBFramesFound <= mEncCfgParams[0].mMaxBFrames);
        if (mEncCfgParams[0].mMaxBFrames > 0) {
            Assume.assumeTrue("maxBFrames are configured to > 0, but no B Frames are seen "
                    + "in sequence \n" + mTestConfig + mTestEnv, maxBFramesFound > 0);
        }
    }
}
