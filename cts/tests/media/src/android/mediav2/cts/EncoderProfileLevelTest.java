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

import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010;
import static android.media.MediaCodecInfo.CodecProfileLevel.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.mediav2.common.cts.EncoderConfigParams;
import android.mediav2.common.cts.EncoderProfileLevelTestBase;
import android.mediav2.common.cts.OutputManager;
import android.util.Log;

import com.android.compatibility.common.util.ApiTest;
import com.android.compatibility.common.util.CddTest;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * EncoderProfileLevelTest validates the profile, level information advertised by the component
 * in its codec capabilities. The test sets profile and level keys in media format and uses it
 * during encoder configuration. Upon successful configuration, frames are queued for encoding
 * (byte buffer mode) and the encoded output (bitstream) is expected to contain the same profile
 * that was used during configure. The level shall be at least the input configured level.
 * <p>
 * NOTE: The test configures level information basing on standard guidelines, not arbitrarily so
 * encoders are expected to maintain at least the input configured level
 * <p>
 * The test parses the bitstream (csd or frame header) and determines profile and level
 * information. This serves as reference for further validation. The test checks if the output
 * format returned by component contains same profile and level information as the bitstream. The
 * output of encoder is muxed and is extracted. The extracted format is expected to contain same
 * profile and level information as the bitstream.
 * <p>
 * As per cdd, if a device contains an encoder capable of encoding a profile/level combination
 * then it should contain a decoder capable of decoding the same profile/level combination. This
 * is verified.
 * <p>
 * If device implementations support encoding in a media type, then as per cdd they are expected to
 * handle certain profile and level configurations. This is verified.
 */
@RunWith(Parameterized.class)
public class EncoderProfileLevelTest extends EncoderProfileLevelTestBase {
    private static final String LOG_TAG = EncoderProfileLevelTest.class.getSimpleName();
    private static final HashMap<String, CddRequirements> CDD_REQUIREMENTS_MAP = new HashMap<>();

    private static class CddRequirements {
        private int[] mProfiles;
        private int mLevel;
        private int mHeight;
        private int mWidth;

        CddRequirements(int[] profiles, int level, int width, int height) {
            mProfiles = profiles;
            mLevel = level;
            mWidth = width;
            mHeight = height;
        }

        CddRequirements(int[] profiles) {
            this(profiles, -1 /* level */, -1 /* width */, -1 /* height */);
        }

        CddRequirements(int[] profiles, int level) {
            this(profiles, level, -1 /* width */, -1 /* height */);
        }

        public int[] getProfiles() {
            return mProfiles;
        }

        public int getLevel() {
            return mLevel;
        }

        public int getWidth() {
            return mWidth;
        }

        public int getHeight() {
            return mHeight;
        }
    }
    public EncoderProfileLevelTest(String encoder, String mediaType,
            EncoderConfigParams[] encCfgParams, @SuppressWarnings("unused") String testLabel,
            String allTestParams) {
        super(encoder, mediaType, encCfgParams, allTestParams);
    }

    private static List<Object[]> prepareTestArgs(Object[] arg, int[] profiles, int colorFormat) {
        List<Object[]> argsList = new ArrayList<>();
        final int[] maxBFrames = {0, 2};
        final String mediaType = (String) arg[0];
        boolean isVideo = mediaType.startsWith("video/");
        final int br = (int) arg[1];
        final int param1 = (int) arg[2];
        final int param2 = (int) arg[3];
        final int fps = (int) arg[4];
        final int level = (int) arg[5];
        if (isVideo) {
            for (int maxBframe : maxBFrames) {
                if (maxBframe != 0) {
                    if (!mediaType.equals(MediaFormat.MIMETYPE_VIDEO_AVC)
                            && !mediaType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                        continue;
                    }
                }
                Object[] testArgs = new Object[3];
                testArgs[0] = arg[0];
                testArgs[1] = getVideoEncoderCfgParams(mediaType, br, param1, param2, fps,
                        colorFormat, maxBframe, profiles, level);
                testArgs[2] = String.format("%dkbps_%dx%d_%dfps_%s_%d_%d-bframes", br / 1000,
                        param1, param2, fps, colorFormatToString(colorFormat, -1),
                        level, maxBframe);
                argsList.add(testArgs);
            }
        } else {
            Object[] testArgs = new Object[3];
            testArgs[0] = arg[0];
            testArgs[1] = getAudioEncoderCfgParams(mediaType, br, param1, param2, profiles);
            testArgs[2] = String.format("%dkbps_%dkHz_%dch", br / 1000, param1 / 1000, param2);
            argsList.add(testArgs);
        }
        return argsList;
    }

    private static EncoderConfigParams[] getVideoEncoderCfgParams(String mediaType, int bitRate,
            int width, int height, int frameRate, int colorFormat, int maxBframe, int[] profiles,
            int level) {
        ArrayList<EncoderConfigParams> cfgParams = new ArrayList<>();
        for (int profile : profiles) {
            if (maxBframe != 0) {
                if (mediaType.equals(MediaFormat.MIMETYPE_VIDEO_AVC) && (
                        profile == AVCProfileBaseline
                                || profile == AVCProfileConstrainedBaseline)) {
                    continue;
                }
            }
            cfgParams.add(new EncoderConfigParams.Builder(mediaType)
                    .setBitRate(bitRate)
                    .setWidth(width)
                    .setHeight(height)
                    .setFrameRate(frameRate)
                    .setMaxBFrames(maxBframe)
                    .setProfile(profile)
                    .setLevel(level)
                    .setColorFormat(colorFormat)
                    .build());
        }
        return cfgParams.toArray(new EncoderConfigParams[0]);
    }

    private static EncoderConfigParams[] getAudioEncoderCfgParams(String mediaType, int bitRate,
            int sampleRate, int channelCount, int[] profiles) {
        EncoderConfigParams[] cfgParams = new EncoderConfigParams[profiles.length];
        for (int i = 0; i < profiles.length; i++) {
            cfgParams[i] = new EncoderConfigParams.Builder(mediaType)
                    .setBitRate(bitRate)
                    .setSampleRate(sampleRate)
                    .setChannelCount(channelCount)
                    .setProfile(profiles[i])
                    .build();
        }
        return cfgParams;
    }

    @Parameterized.Parameters(name = "{index}_{0}_{1}_{3}")
    public static Collection<Object[]> input() {
        final boolean isEncoder = true;
        final boolean needAudio = true;
        final boolean needVideo = true;
        final Object[][] exhaustiveArgsList = new Object[][]{
                // Audio - CodecMediaType, bit-rate, sample rate, channel count, level
                {MediaFormat.MIMETYPE_AUDIO_AAC, 64000, 48000, 1, -1, -1},
                {MediaFormat.MIMETYPE_AUDIO_AAC, 128000, 48000, 2, -1, -1},
                // Video - CodecMediaType, bit-rate, height, width, frame-rate, level
                {MediaFormat.MIMETYPE_VIDEO_AVC, 64000, 128, 96, 30, AVCLevel1},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 128000, 176, 144, 15, AVCLevel1b},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 192000, 320, 240, 10, AVCLevel11},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 384000, 320, 240, 20, AVCLevel12},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 512000, 352, 240, 30, AVCLevel13},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 832000, 352, 288, 30, AVCLevel2},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 1000000, 576, 352, 25, AVCLevel21},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 1500000, 640, 480, 15, AVCLevel22},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 2000000, 720, 480, 30, AVCLevel3},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 3000000, 1280, 720, 30, AVCLevel31},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 6000000, 1280, 1024, 42, AVCLevel32},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 10000000, 1920, 1088, 30, AVCLevel4},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 25000000, 2048, 1024, 30, AVCLevel41},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 50000000, 2048, 1088, 60, AVCLevel42},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 60000000, 2560, 1920, 30, AVCLevel5},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 80000000, 4096, 2048, 30, AVCLevel51},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 120000000, 4096, 2160, 60, AVCLevel52},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 240000000, 8192, 4320, 30, AVCLevel6},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 480000000, 8192, 4320, 60, AVCLevel61},
                {MediaFormat.MIMETYPE_VIDEO_AVC, 800000000, 8192, 4320, 120, AVCLevel62},

                {MediaFormat.MIMETYPE_VIDEO_MPEG2, 4000000, 352, 288, 30, MPEG2LevelLL},
                {MediaFormat.MIMETYPE_VIDEO_MPEG2, 15000000, 720, 480, 30, MPEG2LevelML},
                {MediaFormat.MIMETYPE_VIDEO_MPEG2, 60000000, 1440, 1088, 30, MPEG2LevelH14},
                {MediaFormat.MIMETYPE_VIDEO_MPEG2, 80000000, 1920, 1088, 30, MPEG2LevelHL},
                {MediaFormat.MIMETYPE_VIDEO_MPEG2, 80000000, 1920, 1088, 60, MPEG2LevelHP},

                {MediaFormat.MIMETYPE_VIDEO_VP9, 200000, 256, 144, 15, VP9Level1},
                {MediaFormat.MIMETYPE_VIDEO_VP9, 512000, 384, 192, 30, VP9Level11},
                {MediaFormat.MIMETYPE_VIDEO_VP9, 1000000, 480, 256, 30, VP9Level2},
                {MediaFormat.MIMETYPE_VIDEO_VP9, 1500000, 640, 384, 30, VP9Level21},
                {MediaFormat.MIMETYPE_VIDEO_VP9, 1600000, 720, 480, 30, VP9Level3},
                {MediaFormat.MIMETYPE_VIDEO_VP9, 4000000, 1280, 720, 30, VP9Level31},
                {MediaFormat.MIMETYPE_VIDEO_VP9, 5000000, 1920, 1080, 30, VP9Level4},
                {MediaFormat.MIMETYPE_VIDEO_VP9, 16000000, 2048, 1088, 60, VP9Level41},
                {MediaFormat.MIMETYPE_VIDEO_VP9, 20000000, 3840, 2160, 30, VP9Level5},
                {MediaFormat.MIMETYPE_VIDEO_VP9, 80000000, 4096, 2176, 60, VP9Level51},
                {MediaFormat.MIMETYPE_VIDEO_VP9, 160000000, 4096, 2176, 120, VP9Level52},
                {MediaFormat.MIMETYPE_VIDEO_VP9, 180000000, 8192, 4352, 30, VP9Level6},
                {MediaFormat.MIMETYPE_VIDEO_VP9, 240000000, 8192, 4352, 60, VP9Level61},
                {MediaFormat.MIMETYPE_VIDEO_VP9, 480000000, 8192, 4352, 120, VP9Level62},

                {MediaFormat.MIMETYPE_VIDEO_H263, 64000, 176, 144, 15, H263Level10},
                {MediaFormat.MIMETYPE_VIDEO_H263, 128000, 176, 144, 15, H263Level45},
                {MediaFormat.MIMETYPE_VIDEO_H263, 128000, 352, 288, 15, H263Level20},
                {MediaFormat.MIMETYPE_VIDEO_H263, 384000, 352, 288, 30, H263Level30},
                {MediaFormat.MIMETYPE_VIDEO_H263, 2048000, 352, 288, 30, H263Level40},
                {MediaFormat.MIMETYPE_VIDEO_H263, 4096000, 352, 240, 60, H263Level50},
                {MediaFormat.MIMETYPE_VIDEO_H263, 8192000, 720, 240, 60, H263Level60},
                {MediaFormat.MIMETYPE_VIDEO_H263, 16384000, 720, 576, 50, H263Level70},

                {MediaFormat.MIMETYPE_VIDEO_HEVC, 128000, 176, 144, 15, HEVCMainTierLevel1},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 512000, 352, 288, 30, HEVCMainTierLevel2},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 1000000, 640, 360, 30, HEVCMainTierLevel21},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 1000000, 512, 512, 30, HEVCMainTierLevel3},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 1600000, 720, 480, 30, HEVCMainTierLevel3},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 4000000, 1280, 720, 30, HEVCMainTierLevel31},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 5000000, 1920, 1080, 30, HEVCMainTierLevel4},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 16000000, 1920, 1080, 30, HEVCHighTierLevel4},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 20000000, 1920, 1080, 60, HEVCMainTierLevel41},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 30000000, 1920, 1080, 60, HEVCHighTierLevel41},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 20000000, 3840, 2160, 30, HEVCMainTierLevel5},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 50000000, 3840, 2160, 30, HEVCHighTierLevel5},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 40000000, 3840, 2160, 60, HEVCMainTierLevel51},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 80000000, 3840, 2160, 60, HEVCHighTierLevel51},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 50000000, 3840, 2160, 120, HEVCMainTierLevel52},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 100000000, 3840, 2160, 120, HEVCHighTierLevel52},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 50000000, 7680, 4320, 30, HEVCMainTierLevel6},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 80000000, 7680, 4320, 30, HEVCHighTierLevel6},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 100000000, 7680, 4320, 60, HEVCMainTierLevel61},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 240000000, 7680, 4320, 60, HEVCHighTierLevel61},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 200000000, 7680, 4320, 120, HEVCMainTierLevel62},
                {MediaFormat.MIMETYPE_VIDEO_HEVC, 360000000, 7680, 4320, 120, HEVCHighTierLevel62},

                {MediaFormat.MIMETYPE_VIDEO_AV1, 1500000, 426, 240, 30, AV1Level2},
                {MediaFormat.MIMETYPE_VIDEO_AV1, 3000000, 640, 360, 30, AV1Level21},
                {MediaFormat.MIMETYPE_VIDEO_AV1, 6000000, 854, 480, 30, AV1Level3},
                {MediaFormat.MIMETYPE_VIDEO_AV1, 10000000, 1280, 720, 30, AV1Level31},
                {MediaFormat.MIMETYPE_VIDEO_AV1, 12000000, 1920, 1080, 30, AV1Level4},
                {MediaFormat.MIMETYPE_VIDEO_AV1, 20000000, 1920, 1080, 60, AV1Level41},
                {MediaFormat.MIMETYPE_VIDEO_AV1, 30000000, 3840, 2160, 30, AV1Level5},
                {MediaFormat.MIMETYPE_VIDEO_AV1, 40000000, 3840, 2160, 60, AV1Level51},
                {MediaFormat.MIMETYPE_VIDEO_AV1, 60000000, 3840, 2160, 120, AV1Level52},
                {MediaFormat.MIMETYPE_VIDEO_AV1, 60000000, 7680, 4320, 30, AV1Level6},
                {MediaFormat.MIMETYPE_VIDEO_AV1, 100000000, 7680, 4320, 60, AV1Level61},
                {MediaFormat.MIMETYPE_VIDEO_AV1, 160000000, 7680, 4320, 120, AV1Level62},
        };
        final List<Object[]> argsList = new ArrayList<>();
        for (Object[] arg : exhaustiveArgsList) {
            final String mediaType = (String) arg[0];
            argsList.addAll(prepareTestArgs(arg,
                    Objects.requireNonNull(PROFILE_SDR_MAP.get(mediaType)),
                    COLOR_FormatYUV420Flexible));
            // P010 support was added in Android T, hence limit the following tests to Android
            // T and above
            if (IS_AT_LEAST_T && PROFILE_HLG_MAP.get(mediaType) != null) {
                argsList.addAll(prepareTestArgs(arg,
                        Objects.requireNonNull(PROFILE_HLG_MAP.get(mediaType)),
                        COLOR_FormatYUVP010));
            }
        }
        final Object[][] mpeg4SimpleProfileArgsList = new Object[][]{
                {MediaFormat.MIMETYPE_VIDEO_MPEG4, 64000, 176, 144, 15, MPEG4Level0},
                {MediaFormat.MIMETYPE_VIDEO_MPEG4, 128000, 176, 144, 15, MPEG4Level0b},
                {MediaFormat.MIMETYPE_VIDEO_MPEG4, 64000, 128, 96, 30, MPEG4Level1},
                {MediaFormat.MIMETYPE_VIDEO_MPEG4, 128000, 352, 288, 15, MPEG4Level2},
                {MediaFormat.MIMETYPE_VIDEO_MPEG4, 384000, 352, 288, 30, MPEG4Level3},
                {MediaFormat.MIMETYPE_VIDEO_MPEG4, 4000000, 640, 480, 30, MPEG4Level4a},
                {MediaFormat.MIMETYPE_VIDEO_MPEG4, 8000000, 720, 576, 24, MPEG4Level5},
                {MediaFormat.MIMETYPE_VIDEO_MPEG4, 12000000, 1280, 720, 30, MPEG4Level6},
        };
        for (Object[] arg : mpeg4SimpleProfileArgsList) {
            argsList.addAll(prepareTestArgs(arg, new int[]{MPEG4ProfileSimple},
                    COLOR_FormatYUV420Flexible));
        }
        final Object[][] mpeg4AdvSimpleProfileArgsList = new Object[][]{
                {MediaFormat.MIMETYPE_VIDEO_MPEG4, 128000, 176, 144, 30, MPEG4Level1},
                {MediaFormat.MIMETYPE_VIDEO_MPEG4, 384000, 352, 288, 15, MPEG4Level2},
                {MediaFormat.MIMETYPE_VIDEO_MPEG4, 768000, 352, 288, 30, MPEG4Level3},
                {MediaFormat.MIMETYPE_VIDEO_MPEG4, 1500000, 352, 288, 30, MPEG4Level3b},
                {MediaFormat.MIMETYPE_VIDEO_MPEG4, 3000000, 704, 576, 15, MPEG4Level4},
                {MediaFormat.MIMETYPE_VIDEO_MPEG4, 8000000, 720, 576, 30, MPEG4Level5},
        };
        for (Object[] arg : mpeg4AdvSimpleProfileArgsList) {
            argsList.addAll(prepareTestArgs(arg, new int[]{MPEG4ProfileAdvancedSimple},
                    COLOR_FormatYUV420Flexible));
        }
        return prepareParamList(argsList, isEncoder, needAudio, needVideo, false);
    }

    static {
        // Following lists profiles, level, maxWidth and maxHeight mandated by the CDD.
        // CodecMediaType, profiles, level, maxWidth, maxHeight
        CDD_REQUIREMENTS_MAP.put(MediaFormat.MIMETYPE_AUDIO_AAC,
                new CddRequirements(new int[]{AACObjectLC, AACObjectHE, AACObjectELD}));
        CDD_REQUIREMENTS_MAP.put(MediaFormat.MIMETYPE_VIDEO_H263,
                new CddRequirements(new int[]{H263ProfileBaseline}, H263Level45));
        CDD_REQUIREMENTS_MAP.put(MediaFormat.MIMETYPE_VIDEO_AVC,
                new CddRequirements(new int[]{AVCProfileBaseline}, AVCLevel3));
        CDD_REQUIREMENTS_MAP.put(MediaFormat.MIMETYPE_VIDEO_HEVC,
                new CddRequirements(new int[]{HEVCProfileMain}, HEVCMainTierLevel3, 512, 512));
        CDD_REQUIREMENTS_MAP.put(MediaFormat.MIMETYPE_VIDEO_VP9,
                new CddRequirements(new int[]{VP9Profile0}, VP9Level3));
        if (IS_AT_LEAST_U) {
            CDD_REQUIREMENTS_MAP.put(MediaFormat.MIMETYPE_VIDEO_AV1,
                    new CddRequirements(new int[]{AV1ProfileMain8, AV1ProfileMain10}));
        }
    }

    void checkIfTrackFormatIsOk(MediaFormat trackFormat) {
        assertEquals("Input media type and extracted media type are not identical " + mTestEnv
                        + mTestConfig, mActiveEncCfg.mMediaType,
                trackFormat.getString(MediaFormat.KEY_MIME));
        if (mIsVideo) {
            assertEquals("Input width and extracted width are not same " + mTestEnv + mTestConfig,
                    mActiveEncCfg.mWidth, getWidth(trackFormat));
            assertEquals("Input height and extracted height are not same " + mTestEnv + mTestConfig,
                    mActiveEncCfg.mHeight, getHeight(trackFormat));
        } else {
            int expSampleRate = mActiveEncCfg.mProfile != AACObjectHE ? mActiveEncCfg.mSampleRate
                    : mActiveEncCfg.mSampleRate / 2;
            int expChCount = mActiveEncCfg.mProfile != AACObjectHE_PS ? mActiveEncCfg.mChannelCount
                    : mActiveEncCfg.mChannelCount / 2;
            assertEquals("Input sample rate and extracted sample rate are not same " + mTestEnv
                            + mTestConfig, expSampleRate,
                    trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE));
            assertEquals("Input channe count and extracted channel count are not same " + mTestEnv
                            + mTestConfig, expChCount,
                    trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
        }
    }

    private boolean shallSupportProfileAndLevel(EncoderConfigParams cfg) {
        CddRequirements requirement =
                Objects.requireNonNull(CDD_REQUIREMENTS_MAP.get(cfg.mMediaType));
        int[] profileCdd = requirement.getProfiles();
        int levelCdd = requirement.getLevel();
        int widthCdd = requirement.getWidth();
        int heightCdd = requirement.getHeight();

        // Check if CDD doesn't require support beyond certain resolutions.
        if (widthCdd != -1 && mActiveEncCfg.mWidth > widthCdd) {
            return false;
        }
        if (heightCdd != -1 && mActiveEncCfg.mHeight > heightCdd) {
            return false;
        }

        for (int cddProfile : profileCdd) {
            if (cfg.mProfile == cddProfile) {
                if (!cfg.mIsAudio) {
                    if (cfg.mLevel <= levelCdd) {
                        if (cfg.mMediaType.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_H263)
                                && cfg.mLevel != MediaCodecInfo.CodecProfileLevel.H263Level45
                                && cfg.mLevel > MediaCodecInfo.CodecProfileLevel.H263Level10) {
                            continue;
                        }
                    } else {
                        continue;
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Check description of class {@link EncoderProfileLevelTest}
     */
    @CddTest(requirements = {"2.2.2/5.1/H-0-3", "2.2.2/5.1/H-0-4", "2.2.2/5.1/H-0-5", "5/C-0-3",
            "5.2.1/C-1-1", "5.2.2/C-1-1", "5.2.4/C-1-2", "5.2.5/C-1-1", "5.2.6/C-1-1"})
    @ApiTest(apis = {"android.media.MediaFormat#KEY_PROFILE",
            "android.media.MediaFormat#KEY_AAC_PROFILE",
            "android.media.MediaFormat#KEY_LEVEL"})
    @Test(timeout = PER_TEST_TIMEOUT_LARGE_TEST_MS)
    public void testValidateProfileLevel() throws IOException, InterruptedException {
        int minLevel = getMinLevel(mMediaType, mEncCfgParams[0].mWidth, mEncCfgParams[0].mHeight,
                mEncCfgParams[0].mFrameRate, mEncCfgParams[0].mBitRate, mEncCfgParams[0].mProfile);
        assertEquals("Calculated minimum acceptable level does not match the entry in test table "
                + mTestConfig, mEncCfgParams[0].mLevel, minLevel);

        if (mIsVideo && mEncCfgParams[0].mInputBitDepth != 8) {
            Assume.assumeTrue(mCodecName + " doesn't support " + colorFormatToString(
                            mEncCfgParams[0].mColorFormat, mEncCfgParams[0].mInputBitDepth),
                    hasSupportForColorFormat(mCodecName, mMediaType,
                            mEncCfgParams[0].mColorFormat));
        }
        // TODO(b/280510792): Remove the following once level can be configured correctly in
        // c2.android.av1.encoder
        if (mCodecName.equals("c2.android.av1.encoder")) {
            Assume.assumeFalse("Disable frame rate > 30 for " + mCodecName,
                    mEncCfgParams[0].mFrameRate > 30);
        }
        boolean cddSupportedMediaType = CDD_REQUIREMENTS_MAP.get(mMediaType) != null;
        {
            mActiveRawRes = EncoderInput.getRawResource(mEncCfgParams[0]);
            assertNotNull("no raw resource found for testing config : "
                    + mEncCfgParams[0] + mTestConfig + mTestEnv, mActiveRawRes);
            setUpSource(mActiveRawRes.mFileName);
            mSaveToMem = true;
            mMuxOutput = true;
            mOutputBuff = new OutputManager();
            mCodec = MediaCodec.createByCodecName(mCodecName);
            MediaCodecInfo.CodecCapabilities codecCapabilities =
                    mCodec.getCodecInfo().getCapabilitiesForType(mMediaType);
            int configsTested = 0;
            for (EncoderConfigParams cfg : mEncCfgParams) {
                mActiveEncCfg = cfg;
                MediaFormat format = cfg.getFormat();
                if (!codecCapabilities.isFormatSupported(format)) {
                    if (cddSupportedMediaType) {
                        if (shallSupportProfileAndLevel(cfg)) {
                            ArrayList<MediaFormat> formats = new ArrayList<>();
                            formats.add(format);
                            assertFalse("No components present on the device supports cdd "
                                    + "required encode format:- " + format + mTestConfig + mTestEnv,
                                    selectCodecs(mMediaType, formats, null, true).isEmpty());
                        }
                        Log.d(LOG_TAG, mCodecName + " doesn't support format: " + format);
                    }
                    continue;
                }

                mOutputBuff.reset();
                configureCodec(format, false, true, true);
                mCodec.start();
                doWork(5);
                queueEOS();
                waitForAllOutputs();
                mCodec.reset();

                MediaFormat trackFormat = validateProfileAndLevel();

                deleteMuxedFile();

                // validate extracted format for mandatory keys
                if (trackFormat != null) checkIfTrackFormatIsOk(trackFormat);

                // Verify if device has an equivalent decoder for the current format
                ArrayList<MediaFormat> formatList = new ArrayList<>();
                if (mProfileLevel != null && mProfileLevel.second != -1
                        && cfg.mLevel != mProfileLevel.second) {
                    format.setInteger(MediaFormat.KEY_LEVEL, mProfileLevel.second);
                }
                formatList.add(format);
                assertTrue("Device advertises support for encoding " + format + " but cannot"
                                + " decode it. \n" + mTestConfig + mTestEnv,
                        selectCodecs(mMediaType, formatList, null, false).size() > 0);
                configsTested++;
            }
            mCodec.release();
            Assume.assumeTrue("skipping test, formats not supported by component",
                    configsTested > 0);
        }
    }
}
