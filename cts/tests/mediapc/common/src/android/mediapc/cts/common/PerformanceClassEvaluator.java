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
 * distributed under the License is distributed on an "AS IS" BASIS
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the Licnse.
 */

package android.mediapc.cts.common;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import android.media.MediaFormat;
import android.os.Build;

import com.google.common.base.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.rules.TestName;

import java.util.HashSet;
import java.util.Set;

/**
 * Logs a set of measurements and results for defined performance class requirements.
 */
public class PerformanceClassEvaluator {
    private static final String TAG = PerformanceClassEvaluator.class.getSimpleName();

    private final String mTestName;
    private Set<Requirement> mRequirements;

    public PerformanceClassEvaluator(TestName testName) {
        Preconditions.checkNotNull(testName);
        this.mTestName = testName.getMethodName();
        this.mRequirements = new HashSet<Requirement>();
    }

    // used for requirements [7.1.1.1/H-1-1], [7.1.1.1/H-2-1]
    public static class ResolutionRequirement extends Requirement {
        private static final String TAG = ResolutionRequirement.class.getSimpleName();

        private ResolutionRequirement(String id, RequiredMeasurement<?> ... reqs) {
            super(id, reqs);
        }

        public void setLongResolution(int longResolution) {
            this.<Integer>setMeasuredValue(RequirementConstants.LONG_RESOLUTION, longResolution);
        }

        public void setShortResolution(int shortResolution) {
            this.<Integer>setMeasuredValue(RequirementConstants.SHORT_RESOLUTION, shortResolution);
        }

        /**
         * [7.1.1.1/H-1-1] MUST have screen resolution of at least 1080p.
         */
        public static ResolutionRequirement createR7_1_1_1__H_1_1() {
            RequiredMeasurement<Integer> long_resolution = RequiredMeasurement
                .<Integer>builder()
                .setId(RequirementConstants.LONG_RESOLUTION)
                .setPredicate(RequirementConstants.INTEGER_GTE)
                .addRequiredValue(Build.VERSION_CODES.R, 1920)
                .build();
            RequiredMeasurement<Integer> short_resolution = RequiredMeasurement
                .<Integer>builder()
                .setId(RequirementConstants.SHORT_RESOLUTION)
                .setPredicate(RequirementConstants.INTEGER_GTE)
                .addRequiredValue(Build.VERSION_CODES.R, 1080)
                .build();

            return new ResolutionRequirement(RequirementConstants.R7_1_1_1__H_1_1, long_resolution,
                short_resolution);
        }

        /**
         * [7.1.1.1/H-2-1] MUST have screen resolution of at least 1080p.
         */
        public static ResolutionRequirement createR7_1_1_1__H_2_1() {
            RequiredMeasurement<Integer> long_resolution = RequiredMeasurement
                .<Integer>builder()
                .setId(RequirementConstants.LONG_RESOLUTION)
                .setPredicate(RequirementConstants.INTEGER_GTE)
                .addRequiredValue(Build.VERSION_CODES.S, 1920)
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 1920)
                .build();
            RequiredMeasurement<Integer> short_resolution = RequiredMeasurement
                .<Integer>builder()
                .setId(RequirementConstants.SHORT_RESOLUTION)
                .setPredicate(RequirementConstants.INTEGER_GTE)
                .addRequiredValue(Build.VERSION_CODES.S, 1080)
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 1080)
                .build();

            return new ResolutionRequirement(RequirementConstants.R7_1_1_1__H_2_1, long_resolution,
                short_resolution);
        }
    }

    // used for requirements [7.1.1.3/H-1-1], [7.1.1.3/H-2-1]
    public static class DensityRequirement extends Requirement {
        private static final String TAG = DensityRequirement.class.getSimpleName();

        private DensityRequirement(String id, RequiredMeasurement<?> ... reqs) {
            super(id, reqs);
        }

        public void setDisplayDensity(int displayDensity) {
            this.<Integer>setMeasuredValue(RequirementConstants.DISPLAY_DENSITY, displayDensity);
        }

        /**
         * [7.1.1.3/H-1-1] MUST have screen density of at least 400 dpi.
         */
        public static DensityRequirement createR7_1_1_3__H_1_1() {
            RequiredMeasurement<Integer> display_density = RequiredMeasurement
                .<Integer>builder()
                .setId(RequirementConstants.DISPLAY_DENSITY)
                .setPredicate(RequirementConstants.INTEGER_GTE)
                .addRequiredValue(Build.VERSION_CODES.R, 400)
                .build();

            return new DensityRequirement(RequirementConstants.R7_1_1_3__H_1_1, display_density);
        }

        /**
         * [7.1.1.3/H-2-1] MUST have screen density of at least 400 dpi.
         */
        public static DensityRequirement createR7_1_1_3__H_2_1() {
            RequiredMeasurement<Integer> display_density = RequiredMeasurement
                .<Integer>builder()
                .setId(RequirementConstants.DISPLAY_DENSITY)
                .setPredicate(RequirementConstants.INTEGER_GTE)
                .addRequiredValue(Build.VERSION_CODES.S, 400)
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 400)
                .build();

            return new DensityRequirement(RequirementConstants.R7_1_1_3__H_2_1, display_density);
        }
    }

    // used for requirements [7.6.1/H-1-1], [7.6.1/H-2-1]
    public static class MemoryRequirement extends Requirement {
        private static final String TAG = MemoryRequirement.class.getSimpleName();

        private MemoryRequirement(String id, RequiredMeasurement<?> ... reqs) {
            super(id, reqs);
        }

        public void setPhysicalMemory(long physicalMemory) {
            this.<Long>setMeasuredValue(RequirementConstants.PHYSICAL_MEMORY, physicalMemory);
        }

        /**
         * [7.6.1/H-1-1] MUST have at least 6 GB of physical memory.
         */
        public static MemoryRequirement createR7_6_1__H_1_1() {
            RequiredMeasurement<Long> physical_memory = RequiredMeasurement
                .<Long>builder()
                .setId(RequirementConstants.PHYSICAL_MEMORY)
                .setPredicate(RequirementConstants.LONG_GTE)
                // Media performance requires 6 GB minimum RAM, but keeping the following to 5 GB
                // as activityManager.getMemoryInfo() returns around 5.4 GB on a 6 GB device.
                .addRequiredValue(Build.VERSION_CODES.R, 5L * 1024L)
                .build();

            return new MemoryRequirement(RequirementConstants.R7_6_1__H_1_1, physical_memory);
        }

        /**
         * [7.6.1/H-2-1] MUST have at least 6/8 GB of physical memory.
         */
        public static MemoryRequirement createR7_6_1__H_2_1() {
            RequiredMeasurement<Long> physical_memory = RequiredMeasurement
                .<Long>builder()
                .setId(RequirementConstants.PHYSICAL_MEMORY)
                .setPredicate(RequirementConstants.LONG_GTE)
                // Media performance requires 6/8 GB minimum RAM, but keeping the following to
                // 5/7 GB as activityManager.getMemoryInfo() returns around 5.4 GB on a 6 GB device.
                .addRequiredValue(Build.VERSION_CODES.S, 5L * 1024L)
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 7L * 1024L)
                .build();

            return new MemoryRequirement(RequirementConstants.R7_6_1__H_2_1, physical_memory);
        }
    }

    public static class CodecInitLatencyRequirement extends Requirement {

        private static final String TAG = CodecInitLatencyRequirement.class.getSimpleName();

        private CodecInitLatencyRequirement(String id, RequiredMeasurement<?>... reqs) {
            super(id, reqs);
        }

        public void setCodecInitLatencyMs(long codecInitLatencyMs) {
            this.setMeasuredValue(RequirementConstants.CODEC_INIT_LATENCY, codecInitLatencyMs);
        }

        /**
         * [2.2.7.1/5.1/H-1-7] MUST have a codec initialization latency of 65(R) / 50(S) / 40(T)
         * ms or less for a 1080p or smaller video encoding session for all hardware video
         * encoders when under load. Load here is defined as a concurrent 1080p to 720p
         * video-only transcoding session using hardware video codecs together with the 1080p
         * audio-video recording initialization.
         */
        public static CodecInitLatencyRequirement createR5_1__H_1_7() {
            RequiredMeasurement<Long> codec_init_latency =
                RequiredMeasurement.<Long>builder().setId(RequirementConstants.CODEC_INIT_LATENCY)
                    .setPredicate(RequirementConstants.LONG_LTE)
                    .addRequiredValue(Build.VERSION_CODES.R, 65L)
                    .addRequiredValue(Build.VERSION_CODES.S, 50L)
                    .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 40L)
                    .build();

            return new CodecInitLatencyRequirement(RequirementConstants.R5_1__H_1_7,
                codec_init_latency);
        }

        /**
         * [2.2.7.1/5.1/H-1-8] MUST have a codec initialization latency of 50(R) / 40(S) / 30(T)
         * ms or less for a 128 kbps or lower bitrate audio encoding session for all audio
         * encoders when under load. Load here is defined as a concurrent 1080p to 720p
         * video-only transcoding session using hardware video codecs together with the 1080p
         * audio-video recording initialization.
         */
        public static CodecInitLatencyRequirement createR5_1__H_1_8() {
            RequiredMeasurement<Long> codec_init_latency =
                RequiredMeasurement.<Long>builder().setId(RequirementConstants.CODEC_INIT_LATENCY)
                    .setPredicate(RequirementConstants.LONG_LTE)
                    .addRequiredValue(Build.VERSION_CODES.R, 50L)
                    .addRequiredValue(Build.VERSION_CODES.S, 40L)
                    .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 30L)
                    .build();

            return new CodecInitLatencyRequirement(RequirementConstants.R5_1__H_1_8,
                codec_init_latency);
        }

        /**
         * [2.2.7.1/5.1/H-1-12] Codec initialization latency of 40ms or less for a 1080p or
         * smaller video decoding session for all hardware video encoders when under load. Load
         * here is defined as a concurrent 1080p to 720p video-only transcoding session using
         * hardware video codecs together with the 1080p audio-video recording initialization.
         */
        public static CodecInitLatencyRequirement createR5_1__H_1_12() {
            RequiredMeasurement<Long> codec_init_latency =
                RequiredMeasurement.<Long>builder().setId(RequirementConstants.CODEC_INIT_LATENCY)
                    .setPredicate(RequirementConstants.LONG_LTE)
                    .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 40L)
                    .build();

            return new CodecInitLatencyRequirement(RequirementConstants.R5_1__H_1_12,
                    codec_init_latency);
        }

        /**
         * [2.2.7.1/5.1/H-1-13] Codec initialization latency of 30ms or less for a 128kbps or
         * lower bitrate audio decoding session for all audio encoders when under load. Load here
         * is defined as a concurrent 1080p to 720p video-only transcoding session using hardware
         * video codecs together with the 1080p audio-video recording initialization.
         */
        public static CodecInitLatencyRequirement createR5_1__H_1_13() {
            RequiredMeasurement<Long> codec_init_latency =
                RequiredMeasurement.<Long>builder().setId(RequirementConstants.CODEC_INIT_LATENCY)
                    .setPredicate(RequirementConstants.LONG_LTE)
                    .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 30L)
                    .build();

            return new CodecInitLatencyRequirement(RequirementConstants.R5_1__H_1_13,
                    codec_init_latency);
        }
    }

    // used for requirements [2.2.7.1/5.3/H-1-1], [2.2.7.1/5.3/H-1-2]
    public static class FrameDropRequirement extends Requirement {
        private static final String TAG = FrameDropRequirement.class.getSimpleName();

        private FrameDropRequirement(String id, RequiredMeasurement<?>... reqs) {
            super(id, reqs);
        }

        public void setFramesDropped(int framesDropped) {
            this.setMeasuredValue(RequirementConstants.FRAMES_DROPPED, framesDropped);
        }

        public void setFrameRate(double frameRate) {
            this.setMeasuredValue(RequirementConstants.FRAME_RATE, frameRate);
        }

        /**
         * [2.2.7.1/5.3/H-1-1] MUST NOT drop more than 1 frames in 10 seconds (i.e less than 0.333
         * percent frame drop) for a 1080p 30 fps video session under load. Load is defined as a
         * concurrent 1080p to 720p video-only transcoding session using hardware video codecs,
         * as well as a 128 kbps AAC audio playback.
         */
        public static FrameDropRequirement createR5_3__H_1_1_R() {
            RequiredMeasurement<Integer> frameDropped = RequiredMeasurement
                .<Integer>builder()
                .setId(RequirementConstants.FRAMES_DROPPED)
                .setPredicate(RequirementConstants.INTEGER_LTE)
                // MUST NOT drop more than 1 frame in 10 seconds so 3 frames for 30 seconds
                .addRequiredValue(Build.VERSION_CODES.R, 3)
                .build();

            RequiredMeasurement<Double> frameRate = RequiredMeasurement
                .<Double>builder()
                .setId(RequirementConstants.FRAME_RATE)
                .setPredicate(RequirementConstants.DOUBLE_EQ)
                .addRequiredValue(Build.VERSION_CODES.R, 30.0)
                .build();

            return new FrameDropRequirement(RequirementConstants.R5_3__H_1_1, frameDropped,
                frameRate);
        }

        /**
         * [2.2.7.1/5.3/H-1-2] MUST NOT drop more than 1 frame in 10 seconds during a video
         * resolution change in a 30 fps video session under load. Load is defined as a
         * concurrent 1080p to 720p video-only transcoding session using hardware video codecs,
         * as well as a 128Kbps AAC audio playback.
         */
        public static FrameDropRequirement createR5_3__H_1_2_R() {
            RequiredMeasurement<Integer> frameDropped = RequiredMeasurement
                .<Integer>builder()
                .setId(RequirementConstants.FRAMES_DROPPED)
                .setPredicate(RequirementConstants.INTEGER_LTE)
                // MUST NOT drop more than 1 frame in 10 seconds so 3 frames for 30 seconds
                .addRequiredValue(Build.VERSION_CODES.R, 3)
                .build();

            RequiredMeasurement<Double> frameRate = RequiredMeasurement
                .<Double>builder()
                .setId(RequirementConstants.FRAME_RATE)
                .setPredicate(RequirementConstants.DOUBLE_EQ)
                .addRequiredValue(Build.VERSION_CODES.R, 30.0)
                .build();

            return new FrameDropRequirement(RequirementConstants.R5_3__H_1_2, frameDropped,
                frameRate);
        }

        /**
         * [2.2.7.1/5.3/H-1-1] MUST NOT drop more than 2(S) / 1(T) frames in 10 seconds for a
         * 1080p 60 fps video session under load. Load is defined as a concurrent 1080p to 720p
         * video-only transcoding session using hardware video codecs, as well as a 128 kbps AAC
         * audio playback.
         */
        public static FrameDropRequirement createR5_3__H_1_1_ST() {
            RequiredMeasurement<Integer> frameDropped = RequiredMeasurement
                .<Integer>builder()
                .setId(RequirementConstants.FRAMES_DROPPED)
                .setPredicate(RequirementConstants.INTEGER_LTE)
                // MUST NOT drop more than 2 frame in 10 seconds so 6 frames for 30 seconds
                .addRequiredValue(Build.VERSION_CODES.S, 6)
                // MUST NOT drop more than 1 frame in 10 seconds so 3 frames for 30 seconds
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 3)
                .build();

            RequiredMeasurement<Double> frameRate = RequiredMeasurement
                .<Double>builder()
                .setId(RequirementConstants.FRAME_RATE)
                .setPredicate(RequirementConstants.DOUBLE_EQ)
                .addRequiredValue(Build.VERSION_CODES.S, 60.0)
                .build();

            return new FrameDropRequirement(RequirementConstants.R5_3__H_1_1, frameDropped,
                frameRate);
        }

        /**
         * [2.2.7.1/5.3/H-1-2] MUST NOT drop more than 2(S) / 1(T) frames in 10 seconds during a
         * video resolution change in a 60 fps video session under load. Load is defined as a
         * concurrent 1080p to 720p video-only transcoding session using hardware video codecs,
         * as well as a 128Kbps AAC audio playback.
         */
        public static FrameDropRequirement createR5_3__H_1_2_ST() {
            RequiredMeasurement<Integer> frameDropped = RequiredMeasurement
                .<Integer>builder()
                .setId(RequirementConstants.FRAMES_DROPPED)
                .setPredicate(RequirementConstants.INTEGER_LTE)
                // MUST NOT drop more than 2 frame in 10 seconds so 6 frames for 30 seconds
                .addRequiredValue(Build.VERSION_CODES.S, 6)
                // MUST NOT drop more than 1 frame in 10 seconds so 3 frames for 30 seconds
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 3)
                .build();

            RequiredMeasurement<Double> frameRate = RequiredMeasurement
                .<Double>builder()
                .setId(RequirementConstants.FRAME_RATE)
                .setPredicate(RequirementConstants.DOUBLE_EQ)
                .addRequiredValue(Build.VERSION_CODES.S, 60.0)
                .build();

            return new FrameDropRequirement(RequirementConstants.R5_3__H_1_2, frameDropped,
                frameRate);
        }
    }

    public static class VideoCodecRequirement extends Requirement {
        private static final String TAG = VideoCodecRequirement.class.getSimpleName();

        private VideoCodecRequirement(String id, RequiredMeasurement<?> ... reqs) {
            super(id, reqs);
        }

        public void setAv1DecoderReq(boolean av1DecoderReqSatisfied) {
            this.setMeasuredValue(RequirementConstants.AV1_DEC_REQ, av1DecoderReqSatisfied);
        }

        public void set4kHwDecoders(int num4kHwDecoders) {
            this.setMeasuredValue(RequirementConstants.NUM_4k_HW_DEC, num4kHwDecoders);
        }

        public void set4kHwEncoders(int num4kHwEncoders) {
            this.setMeasuredValue(RequirementConstants.NUM_4k_HW_ENC, num4kHwEncoders);
        }

        /**
         * [2.2.7.1/5.1/H-1-15] Must have at least 1 HW video decoder supporting 4K60
         */
        public static VideoCodecRequirement createR4k60HwDecoder() {
            RequiredMeasurement<Integer> requirement = RequiredMeasurement
                .<Integer>builder()
                .setId(RequirementConstants.NUM_4k_HW_DEC)
                .setPredicate(RequirementConstants.INTEGER_GTE)
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 1)
                .build();

            return new VideoCodecRequirement(RequirementConstants.R5_1__H_1_15, requirement);
        }

        /**
         * [2.2.7.1/5.1/H-1-16] Must have at least 1 HW video encoder supporting 4K60
         */
        public static VideoCodecRequirement createR4k60HwEncoder() {
            RequiredMeasurement<Integer> requirement = RequiredMeasurement
                .<Integer>builder()
                .setId(RequirementConstants.NUM_4k_HW_ENC)
                .setPredicate(RequirementConstants.INTEGER_GTE)
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 1)
                .build();

            return new VideoCodecRequirement(RequirementConstants.R5_1__H_1_16, requirement);
        }

        /**
         * [2.2.7.1/5.1/H-1-14] AV1 Hardware decoder: Main 10, Level 4.1, Film Grain
         */
        public static VideoCodecRequirement createRAV1DecoderReq() {
            RequiredMeasurement<Boolean> requirement = RequiredMeasurement
                .<Boolean>builder()
                .setId(RequirementConstants.AV1_DEC_REQ)
                .setPredicate(RequirementConstants.BOOLEAN_EQ)
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, true)
                .build();

            return new VideoCodecRequirement(RequirementConstants.R5_1__H_1_14, requirement);
        }
    }

    // used for requirements [2.2.7.1/5.1/H-1-1], [2.2.7.1/5.1/H-1-2], [2.2.7.1/5.1/H-1-3],
    // [2.2.7.1/5.1/H-1-4], [2.2.7.1/5.1/H-1-5], [2.2.7.1/5.1/H-1-6], [2.2.7.1/5.1/H-1-9],
    // [2.2.7.1/5.1/H-1-10]
    public static class ConcurrentCodecRequirement extends Requirement {
        private static final String TAG = ConcurrentCodecRequirement.class.getSimpleName();
        // allowed tolerance in measured fps vs expected fps in percentage, i.e. codecs achieving
        // fps that is greater than (FPS_TOLERANCE_FACTOR * expectedFps) will be considered as
        // passing the test
        private static final double FPS_TOLERANCE_FACTOR = 0.95;
        private static final double FPS_30_TOLERANCE = 30.0 * FPS_TOLERANCE_FACTOR;
        static final int REQUIRED_MIN_CONCURRENT_INSTANCES = 6;
        static final int REQUIRED_MIN_CONCURRENT_INSTANCES_FOR_VP9 = 2;

        private ConcurrentCodecRequirement(String id, RequiredMeasurement<?> ... reqs) {
            super(id, reqs);
        }

        public void setConcurrentInstances(int concurrentInstances) {
            this.setMeasuredValue(RequirementConstants.CONCURRENT_SESSIONS,
                concurrentInstances);
        }

        public void setConcurrentFps(double achievedFps) {
            this.setMeasuredValue(RequirementConstants.CONCURRENT_FPS, achievedFps);
        }

        // copied from android.mediapc.cts.getReqMinConcurrentInstances due to build issues on aosp
        public static int getReqMinConcurrentInstances(int performanceClass, String mimeType1,
            String mimeType2, int resolution) {
            ArrayList<String> MEDIAPC_CONCURRENT_CODECS_R = new ArrayList<>(
                Arrays.asList(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC));
            ArrayList<String> MEDIAPC_CONCURRENT_CODECS = new ArrayList<>(Arrays
                .asList(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC,
                    MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_VIDEO_AV1));

            if (performanceClass >= Build.VERSION_CODES.TIRAMISU) {
                return resolution >= 1080 ? REQUIRED_MIN_CONCURRENT_INSTANCES : 0;
            } else if (performanceClass == Build.VERSION_CODES.S) {
                if (resolution >= 1080) {
                    return 0;
                }
                if (MEDIAPC_CONCURRENT_CODECS.contains(mimeType1) && MEDIAPC_CONCURRENT_CODECS
                    .contains(mimeType2)) {
                    if (MediaFormat.MIMETYPE_VIDEO_VP9.equalsIgnoreCase(mimeType1)
                        || MediaFormat.MIMETYPE_VIDEO_VP9.equalsIgnoreCase(mimeType2)) {
                        return REQUIRED_MIN_CONCURRENT_INSTANCES_FOR_VP9;
                    } else {
                        return REQUIRED_MIN_CONCURRENT_INSTANCES;
                    }
                } else {
                    return 0;
                }
            } else if (performanceClass == Build.VERSION_CODES.R) {
                if (resolution >= 1080) {
                    return 0;
                }
                if (MEDIAPC_CONCURRENT_CODECS_R.contains(mimeType1) && MEDIAPC_CONCURRENT_CODECS_R
                    .contains(mimeType2)) {
                    return REQUIRED_MIN_CONCURRENT_INSTANCES;
                } else {
                    return 0;
                }
            } else {
                return 0;
            }
        }

        private static double getReqMinConcurrentFps(int performanceClass, String mimeType1,
            String mimeType2, int resolution) {
            return FPS_30_TOLERANCE * getReqMinConcurrentInstances(performanceClass, mimeType1,
                mimeType2, resolution);
        }

        /**
         * [2.2.7.1/5.1/H-1-1] MUST advertise the maximum number of hardware video decoder
         * sessions that can be run concurrently in any codec combination via the
         * CodecCapabilities.getMaxSupportedInstances() and VideoCapabilities
         * .getSupportedPerformancePoints() methods.
         */
        public static ConcurrentCodecRequirement createR5_1__H_1_1_720p(String mimeType1,
            String mimeType2, int resolution) {
            RequiredMeasurement<Integer> maxInstances = RequiredMeasurement.<Integer>builder()
                .setId(RequirementConstants.CONCURRENT_SESSIONS)
                .setPredicate(RequirementConstants.INTEGER_GTE)
                .addRequiredValue(Build.VERSION_CODES.R,
                    getReqMinConcurrentInstances(Build.VERSION_CODES.R, mimeType1, mimeType2,
                        resolution))
                .addRequiredValue(Build.VERSION_CODES.S,
                    getReqMinConcurrentInstances(Build.VERSION_CODES.S, mimeType1, mimeType2,
                        resolution))
                .build();

            return new ConcurrentCodecRequirement(RequirementConstants.R5_1__H_1_1, maxInstances);
        }

        /**
         * [2.2.7.1/5.1/H-1-1] MUST advertise the maximum number of hardware video decoder
         * sessions that can be run concurrently in any codec combination via the
         * CodecCapabilities.getMaxSupportedInstances() and VideoCapabilities
         * .getSupportedPerformancePoints() methods.
         */
        public static ConcurrentCodecRequirement createR5_1__H_1_1_1080p() {
            RequiredMeasurement<Integer> maxInstances = RequiredMeasurement.<Integer>builder()
                .setId(RequirementConstants.CONCURRENT_SESSIONS)
                .setPredicate(RequirementConstants.INTEGER_GTE)
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 6)
                .build();

            return new ConcurrentCodecRequirement(RequirementConstants.R5_1__H_1_1, maxInstances);
        }

        /**
         * [2.2.7.1/5.1/H-1-2] MUST support 6 instances of hardware video decoder sessions (AVC,
         * HEVC, VP9* or later) in any codec combination running concurrently at 720p(R,S)
         * resolution@30 fps.
         */
        public static ConcurrentCodecRequirement createR5_1__H_1_2_720p(String mimeType1,
            String mimeType2, int resolution) {
            RequiredMeasurement<Double> reqConcurrentFps = RequiredMeasurement.<Double>builder()
                .setId(RequirementConstants.CONCURRENT_FPS)
                .setPredicate(RequirementConstants.DOUBLE_GTE)
                .addRequiredValue(Build.VERSION_CODES.R,
                    getReqMinConcurrentFps(Build.VERSION_CODES.R, mimeType1, mimeType2, resolution))
                .addRequiredValue(Build.VERSION_CODES.S,
                    getReqMinConcurrentFps(Build.VERSION_CODES.S, mimeType1, mimeType2, resolution))
                .build();

            return new ConcurrentCodecRequirement(RequirementConstants.R5_1__H_1_2,
                reqConcurrentFps);
        }

        /**
         * [2.2.7.1/5.1/H-1-2] MUST support 6 instances of hardware video decoder sessions (AVC,
         * HEVC, VP9* or later) in any codec combination running concurrently at 1080p(T)
         * resolution@30 fps.
         */
        public static ConcurrentCodecRequirement createR5_1__H_1_2_1080p() {
            RequiredMeasurement<Double> reqConcurrentFps = RequiredMeasurement.<Double>builder()
                .setId(RequirementConstants.CONCURRENT_FPS)
                .setPredicate(RequirementConstants.DOUBLE_GTE)
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 6 * FPS_30_TOLERANCE)
                .build();

            return new ConcurrentCodecRequirement(RequirementConstants.R5_1__H_1_2,
                reqConcurrentFps);
        }

        /**
         * [2.2.7.1/5.1/H-1-3] MUST advertise the maximum number of hardware video encoder
         * sessions that can be run concurrently in any codec combination via the
         * CodecCapabilities.getMaxSupportedInstances() and VideoCapabilities
         * .getSupportedPerformancePoints() methods.
         */
        public static ConcurrentCodecRequirement createR5_1__H_1_3_720p(String mimeType1,
            String mimeType2, int resolution) {
            RequiredMeasurement<Integer> maxInstances = RequiredMeasurement.<Integer>builder()
                .setId(RequirementConstants.CONCURRENT_SESSIONS)
                .setPredicate(RequirementConstants.INTEGER_GTE)
                .addRequiredValue(Build.VERSION_CODES.R,
                    getReqMinConcurrentInstances(Build.VERSION_CODES.R, mimeType1, mimeType2,
                        resolution))
                .addRequiredValue(Build.VERSION_CODES.S,
                    getReqMinConcurrentInstances(Build.VERSION_CODES.S, mimeType1, mimeType2,
                        resolution))
                .build();

            return new ConcurrentCodecRequirement(RequirementConstants.R5_1__H_1_3, maxInstances);
        }

        /**
         * [2.2.7.1/5.1/H-1-3] MUST advertise the maximum number of hardware video encoder
         * sessions that can be run concurrently in any codec combination via the
         * CodecCapabilities.getMaxSupportedInstances() and VideoCapabilities
         * .getSupportedPerformancePoints() methods.
         */
        public static ConcurrentCodecRequirement createR5_1__H_1_3_1080p() {
            RequiredMeasurement<Integer> maxInstances = RequiredMeasurement.<Integer>builder()
                .setId(RequirementConstants.CONCURRENT_SESSIONS)
                .setPredicate(RequirementConstants.INTEGER_GTE)
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 6)
                .build();

            return new ConcurrentCodecRequirement(RequirementConstants.R5_1__H_1_3, maxInstances);
        }

        /**
         * [2.2.7.1/5.1/H-1-4] MUST support 6 instances of hardware video encoder sessions (AVC,
         * HEVC, VP9* or later) in any codec combination running concurrently at 720p(R,S)
         * resolution@30 fps.
         */
        public static ConcurrentCodecRequirement createR5_1__H_1_4_720p() {
            RequiredMeasurement<Double> reqConcurrentFps = RequiredMeasurement.<Double>builder()
                .setId(RequirementConstants.CONCURRENT_FPS)
                .setPredicate(RequirementConstants.DOUBLE_GTE)
                // Requirement not asserted since encoder test runs in byte buffer mode
                .addRequiredValue(Build.VERSION_CODES.R, 0.0)
                .addRequiredValue(Build.VERSION_CODES.S, 0.0)
                .build();

            return new ConcurrentCodecRequirement(RequirementConstants.R5_1__H_1_4,
                reqConcurrentFps);
        }

        /**
         * [2.2.7.1/5.1/H-1-4] MUST support 6 instances of hardware video encoder sessions (AVC,
         * HEVC, VP9* or later) in any codec combination running concurrently at 1080p(T)
         * resolution@30 fps.
         */
        public static ConcurrentCodecRequirement createR5_1__H_1_4_1080p() {
            RequiredMeasurement<Double> reqConcurrentFps = RequiredMeasurement.<Double>builder()
                .setId(RequirementConstants.CONCURRENT_FPS)
                .setPredicate(RequirementConstants.DOUBLE_GTE)
                // Requirement not asserted since encoder test runs in byte buffer mode
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 0.0)
                .build();

            return new ConcurrentCodecRequirement(RequirementConstants.R5_1__H_1_4,
                reqConcurrentFps);
        }

        /**
         * [2.2.7.1/5.1/H-1-5] MUST advertise the maximum number of hardware video encoder and
         * decoder sessions that can be run concurrently in any codec combination via the
         * CodecCapabilities.getMaxSupportedInstances() and VideoCapabilities
         * .getSupportedPerformancePoints() methods.
         */
        public static ConcurrentCodecRequirement createR5_1__H_1_5_720p(String mimeType1,
            String mimeType2, int resolution) {
            RequiredMeasurement<Integer> maxInstances = RequiredMeasurement.<Integer>builder()
                .setId(RequirementConstants.CONCURRENT_SESSIONS)
                .setPredicate(RequirementConstants.INTEGER_GTE)
                .addRequiredValue(Build.VERSION_CODES.R,
                    getReqMinConcurrentInstances(Build.VERSION_CODES.R, mimeType1, mimeType2,
                        resolution))
                .addRequiredValue(Build.VERSION_CODES.S,
                    getReqMinConcurrentInstances(Build.VERSION_CODES.S, mimeType1, mimeType2,
                        resolution))
                .build();

            return new ConcurrentCodecRequirement(RequirementConstants.R5_1__H_1_5, maxInstances);
        }

        /**
         * [2.2.7.1/5.1/H-1-5] MUST advertise the maximum number of hardware video encoder and
         * decoder sessions that can be run concurrently in any codec combination via the
         * CodecCapabilities.getMaxSupportedInstances() and VideoCapabilities
         * .getSupportedPerformancePoints() methods.
         */
        public static ConcurrentCodecRequirement createR5_1__H_1_5_1080p() {
            RequiredMeasurement<Integer> maxInstances = RequiredMeasurement.<Integer>builder()
                .setId(RequirementConstants.CONCURRENT_SESSIONS)
                .setPredicate(RequirementConstants.INTEGER_GTE)
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 6)
                .build();

            return new ConcurrentCodecRequirement(RequirementConstants.R5_1__H_1_5, maxInstances);
        }

        /**
         * [2.2.7.1/5.1/H-1-6] Support 6 instances of hardware video decoder and hardware video
         * encoder sessions (AVC, HEVC, VP9 or AV1) in any codec combination running concurrently
         * at 720p(R,S) /1080p(T) @30fps resolution.
         */
        public static ConcurrentCodecRequirement createR5_1__H_1_6_720p(String mimeType1,
            String mimeType2, int resolution) {
            RequiredMeasurement<Double> reqConcurrentFps = RequiredMeasurement.<Double>builder()
                .setId(RequirementConstants.CONCURRENT_FPS)
                .setPredicate(RequirementConstants.DOUBLE_GTE)
                // Test transcoding, fps calculated for encoder and decoder combined so req / 2
                .addRequiredValue(Build.VERSION_CODES.R,
                    getReqMinConcurrentFps(Build.VERSION_CODES.R, mimeType1, mimeType2, resolution)
                        / 2)
                .addRequiredValue(Build.VERSION_CODES.S,
                    getReqMinConcurrentFps(Build.VERSION_CODES.S, mimeType1, mimeType2, resolution)
                        / 2)
                .build();

            return new ConcurrentCodecRequirement(RequirementConstants.R5_1__H_1_6,
                reqConcurrentFps);
        }

        /**
         * [2.2.7.1/5.1/H-1-6] Support 6 instances of hardware video decoder and hardware video
         * encoder sessions (AVC, HEVC, VP9 or AV1) in any codec combination running concurrently
         * at 720p(R,S) /1080p(T) @30fps resolution.
         */
        public static ConcurrentCodecRequirement createR5_1__H_1_6_1080p() {
            RequiredMeasurement<Double> reqConcurrentFps = RequiredMeasurement.<Double>builder()
                .setId(RequirementConstants.CONCURRENT_FPS)
                .setPredicate(RequirementConstants.DOUBLE_GTE)
                // Test transcoding, fps calculated for encoder and decoder combined so req / 2
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 6 * FPS_30_TOLERANCE / 2)
                .build();

            return new ConcurrentCodecRequirement(RequirementConstants.R5_1__H_1_6,
                reqConcurrentFps);
        }

        /**
         * [2.2.7.1/5.1/H-1-9] Support 2 instances of secure hardware video decoder sessions
         * (AVC, HEVC, VP9 or AV1) in any codec combination running concurrently at 1080p
         * resolution@30fps.
         */
        public static ConcurrentCodecRequirement createR5_1__H_1_9() {
            RequiredMeasurement<Double> reqConcurrentFps = RequiredMeasurement.<Double>builder()
                .setId(RequirementConstants.CONCURRENT_FPS)
                .setPredicate(RequirementConstants.DOUBLE_GTE)
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 2 * FPS_30_TOLERANCE)
                .build();

            return new ConcurrentCodecRequirement(RequirementConstants.R5_1__H_1_9,
                reqConcurrentFps);
        }

        /**
         * [2.2.7.1/5.1/H-1-10] Support 3 instances of non-secure hardware video decoder sessions
         * together with 1 instance of secure hardware video decoder session (4 instances total)
         * (AVC, HEVC, VP9 or AV1) in any codec combination running concurrently at 1080p
         * resolution@30fps.
         */
        public static ConcurrentCodecRequirement createR5_1__H_1_10() {
            RequiredMeasurement<Double> reqConcurrentFps = RequiredMeasurement.<Double>builder()
                .setId(RequirementConstants.CONCURRENT_FPS)
                .setPredicate(RequirementConstants.DOUBLE_GTE)
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 4 * FPS_30_TOLERANCE)
                .build();

            return new ConcurrentCodecRequirement(RequirementConstants.R5_1__H_1_10,
                reqConcurrentFps);
        }
    }

    // used for requirements [2.2.7.1/5.1/H-1-11], [2.2.7.1/5.7/H-1-2]
    public static class SecureCodecRequirement extends Requirement {
        private static final String TAG = SecureCodecRequirement.class.getSimpleName();

        private SecureCodecRequirement(String id, RequiredMeasurement<?> ... reqs) {
            super(id, reqs);
        }

        public void setSecureReqSatisfied(boolean secureReqSatisfied) {
            this.setMeasuredValue(RequirementConstants.SECURE_REQ_SATISFIED, secureReqSatisfied);
        }

        public void setNumCryptoHwSecureAllDec(int numCryptoHwSecureAllDec) {
            this.setMeasuredValue(RequirementConstants.NUM_CRYPTO_HW_SECURE_ALL_SUPPORT,
                numCryptoHwSecureAllDec);
        }

        /**
         * [2.2.7.1/5.7/H-1-2] MUST support MediaDrm.SECURITY_LEVEL_HW_SECURE_ALL with the below
         * content decryption capabilities.
         */
        public static SecureCodecRequirement createR5_7__H_1_2() {
            RequiredMeasurement<Integer> hw_secure_all = RequiredMeasurement.<Integer>builder()
                .setId(RequirementConstants.NUM_CRYPTO_HW_SECURE_ALL_SUPPORT)
                .setPredicate(RequirementConstants.INTEGER_GTE)
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, 1)
                .build();

            return new SecureCodecRequirement(RequirementConstants.R5_7__H_1_2, hw_secure_all);
        }

        /**
         * [2.2.7.1/5.1/H-1-11] Must support secure decoder when a corresponding AVC/VP9/HEVC or AV1
         * hardware decoder is available
         */
        public static SecureCodecRequirement createR5_1__H_1_11() {
            RequiredMeasurement<Boolean> requirement = RequiredMeasurement
                .<Boolean>builder()
                .setId(RequirementConstants.SECURE_REQ_SATISFIED)
                .setPredicate(RequirementConstants.BOOLEAN_EQ)
                .addRequiredValue(Build.VERSION_CODES.TIRAMISU, true)
                .build();

            return new SecureCodecRequirement(RequirementConstants.R5_1__H_1_11, requirement);
        }
    }

    private <R extends Requirement> R addRequirement(R req) {
        if (!this.mRequirements.add(req)) {
            throw new IllegalStateException("Requirement " + req.id() + " already added");
        }
        return req;
    }

    public ResolutionRequirement addR7_1_1_1__H_1_1() {
        return this.<ResolutionRequirement>addRequirement(
            ResolutionRequirement.createR7_1_1_1__H_1_1());
    }

    public DensityRequirement addR7_1_1_3__H_1_1() {
        return this.<DensityRequirement>addRequirement(DensityRequirement.createR7_1_1_3__H_1_1());
    }

    public MemoryRequirement addR7_6_1__H_1_1() {
        return this.<MemoryRequirement>addRequirement(MemoryRequirement.createR7_6_1__H_1_1());
    }

    public ResolutionRequirement addR7_1_1_1__H_2_1() {
        return this.<ResolutionRequirement>addRequirement(
            ResolutionRequirement.createR7_1_1_1__H_2_1());
    }

    public DensityRequirement addR7_1_1_3__H_2_1() {
        return this.<DensityRequirement>addRequirement(DensityRequirement.createR7_1_1_3__H_2_1());
    }

    public MemoryRequirement addR7_6_1__H_2_1() {
        return this.<MemoryRequirement>addRequirement(MemoryRequirement.createR7_6_1__H_2_1());
    }

    public FrameDropRequirement addR5_3__H_1_1_R() {
        return this.addRequirement(FrameDropRequirement.createR5_3__H_1_1_R());
    }

    public FrameDropRequirement addR5_3__H_1_2_R() {
        return this.addRequirement(FrameDropRequirement.createR5_3__H_1_2_R());
    }

    public FrameDropRequirement addR5_3__H_1_1_ST() {
        return this.addRequirement(FrameDropRequirement.createR5_3__H_1_1_ST());
    }

    public FrameDropRequirement addR5_3__H_1_2_ST() {
        return this.addRequirement(FrameDropRequirement.createR5_3__H_1_2_ST());
    }

    public CodecInitLatencyRequirement addR5_1__H_1_7() {
        return this.addRequirement(CodecInitLatencyRequirement.createR5_1__H_1_7());
    }

    public CodecInitLatencyRequirement addR5_1__H_1_8() {
        return this.addRequirement(CodecInitLatencyRequirement.createR5_1__H_1_8());
    }

    public CodecInitLatencyRequirement addR5_1__H_1_12() {
        return this.addRequirement(CodecInitLatencyRequirement.createR5_1__H_1_12());
    }

    public CodecInitLatencyRequirement addR5_1__H_1_13() {
        return this.addRequirement(CodecInitLatencyRequirement.createR5_1__H_1_13());
    }

    public VideoCodecRequirement addR4k60HwEncoder() {
        return this.addRequirement(VideoCodecRequirement.createR4k60HwEncoder());
    }

    public VideoCodecRequirement addR4k60HwDecoder() {
        return this.addRequirement(VideoCodecRequirement.createR4k60HwDecoder());
    }

    public VideoCodecRequirement addRAV1DecoderReq() {
        return this.addRequirement(VideoCodecRequirement.createRAV1DecoderReq());
    }

    public SecureCodecRequirement addR5_1__H_1_11() {
        return this.addRequirement(SecureCodecRequirement.createR5_1__H_1_11());
    }

    public SecureCodecRequirement addR5_7__H_1_2() {
        return this.addRequirement(SecureCodecRequirement.createR5_7__H_1_2());
    }

    public ConcurrentCodecRequirement addR5_1__H_1_1_720p(String mimeType1, String mimeType2,
        int resolution) {
        return this.addRequirement(
            ConcurrentCodecRequirement.createR5_1__H_1_1_720p(mimeType1, mimeType2, resolution));
    }

    public ConcurrentCodecRequirement addR5_1__H_1_1_1080p() {
        return this.addRequirement(ConcurrentCodecRequirement.createR5_1__H_1_1_1080p());
    }

    public ConcurrentCodecRequirement addR5_1__H_1_2_720p(String mimeType1, String mimeType2,
        int resolution) {
        return this.addRequirement(
            ConcurrentCodecRequirement.createR5_1__H_1_2_720p(mimeType1, mimeType2, resolution));
    }

    public ConcurrentCodecRequirement addR5_1__H_1_2_1080p() {
        return this.addRequirement(ConcurrentCodecRequirement.createR5_1__H_1_2_1080p());
    }

    public ConcurrentCodecRequirement addR5_1__H_1_3_720p(String mimeType1, String mimeType2,
        int resolution) {
        return this.addRequirement(
            ConcurrentCodecRequirement.createR5_1__H_1_3_720p(mimeType1, mimeType2, resolution));
    }

    public ConcurrentCodecRequirement addR5_1__H_1_3_1080p() {
        return this.addRequirement(ConcurrentCodecRequirement.createR5_1__H_1_3_1080p());
    }

    public ConcurrentCodecRequirement addR5_1__H_1_4_720p() {
        return this.addRequirement(ConcurrentCodecRequirement.createR5_1__H_1_4_720p());
    }

    public ConcurrentCodecRequirement addR5_1__H_1_4_1080p() {
        return this.addRequirement(ConcurrentCodecRequirement.createR5_1__H_1_4_1080p());
    }

    public ConcurrentCodecRequirement addR5_1__H_1_5_720p(String mimeType1, String mimeType2,
        int resolution) {
        return this.addRequirement(
            ConcurrentCodecRequirement.createR5_1__H_1_5_720p(mimeType1, mimeType2, resolution));
    }

    public ConcurrentCodecRequirement addR5_1__H_1_5_1080p() {
        return this.addRequirement(ConcurrentCodecRequirement.createR5_1__H_1_5_1080p());
    }

    public ConcurrentCodecRequirement addR5_1__H_1_6_720p(String mimeType1, String mimeType2,
        int resolution) {
        return this.addRequirement(
            ConcurrentCodecRequirement.createR5_1__H_1_6_720p(mimeType1, mimeType2, resolution));
    }

    public ConcurrentCodecRequirement addR5_1__H_1_6_1080p() {
        return this.addRequirement(ConcurrentCodecRequirement.createR5_1__H_1_6_1080p());
    }

    public ConcurrentCodecRequirement addR5_1__H_1_9() {
        return this.addRequirement(ConcurrentCodecRequirement.createR5_1__H_1_9());
    }

    public ConcurrentCodecRequirement addR5_1__H_1_10() {
        return this.addRequirement(ConcurrentCodecRequirement.createR5_1__H_1_10());
    }

    public void submitAndCheck() {
        boolean perfClassMet = true;
        for (Requirement req: this.mRequirements) {
            perfClassMet &= req.writeLogAndCheck(this.mTestName);
        }

        // check performance class
        assumeTrue("Build.VERSION.MEDIA_PERFORMANCE_CLASS is not declared", Utils.isPerfClass());
        assertThat(perfClassMet).isTrue();

        this.mRequirements.clear(); // makes sure report isn't submitted twice
    }
}
