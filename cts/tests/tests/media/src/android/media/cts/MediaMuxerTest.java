/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.AppModeFull;
import android.test.AndroidTestCase;
import android.util.Log;

import android.media.cts.R;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Vector;
import java.util.stream.IntStream;

@AppModeFull(reason = "No interaction with system server")
public class MediaMuxerTest extends AndroidTestCase {
    private static final String TAG = "MediaMuxerTest";
    private static final boolean VERBOSE = false;
    private static final int MAX_SAMPLE_SIZE = 256 * 1024;
    private static final float LATITUDE = 0.0000f;
    private static final float LONGITUDE  = -180.0f;
    private static final float BAD_LATITUDE = 91.0f;
    private static final float BAD_LONGITUDE = -181.0f;
    private static final float TOLERANCE = 0.0002f;
    private static final long OFFSET_TIME_US = 29 * 60 * 1000000L; // 29 minutes
    private Resources mResources;

    @Override
    public void setContext(Context context) {
        super.setContext(context);
        mResources = context.getResources();
    }

    /**
     * Test: make sure the muxer handles both video and audio tracks correctly.
     */
    public void testVideoAudio() throws Exception {
        int source = R.raw.video_176x144_3gp_h263_300kbps_25fps_aac_stereo_128kbps_11025hz;
        String outputFile = File.createTempFile("MediaMuxerTest_testAudioVideo", ".mp4")
                .getAbsolutePath();
        cloneAndVerify(source, outputFile, 2, 90, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    public void testDualVideoTrack() throws Exception {
        int source = R.raw.video_176x144_h264_408kbps_30fps_352x288_h264_122kbps_30fps;
        String outputFile = File.createTempFile("MediaMuxerTest_testDualVideo", ".mp4")
                .getAbsolutePath();
        cloneAndVerify(source, outputFile, 2, 90, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    public void testDualAudioTrack() throws Exception {
        int source = R.raw.audio_aac_mono_70kbs_44100hz_aac_mono_70kbs_44100hz;
        String outputFile = File.createTempFile("MediaMuxerTest_testDualAudio", ".mp4")
                .getAbsolutePath();
        cloneAndVerify(source, outputFile, 2, 90, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    public void testDualVideoAndAudioTrack() throws Exception {
        int source = R.raw.video_h264_30fps_video_h264_30fps_aac_44100hz_aac_44100hz;
        String outputFile = File.createTempFile("MediaMuxerTest_testDualVideoAudio", ".mp4")
                .getAbsolutePath();
        cloneAndVerify(source, outputFile, 4, 90, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    /**
     * Test: make sure the muxer handles video, audio and non standard compliant metadata tracks
     * that generated before API29 correctly. This test will use extractor to extract the video
     * track, audio and the non standard compliant metadata track from the source file, then
     * remuxes them into a new file with standard compliant metadata track. Finally, it will check
     * to make sure the new file's metadata track matches the source file's metadata track for the
     * mime format and data payload.
     */
    public void testVideoAudioMedatadataWithNonCompliantMetadataTrack() throws Exception {
        int source =
                R.raw.video_176x144_3gp_h263_300kbps_25fps_aac_stereo_128kbps_11025hz_metadata_gyro_non_compliant;
        String outputFile = File.createTempFile("MediaMuxerTest_testAudioVideoMetadata", ".mp4")
                .getAbsolutePath();
        cloneAndVerify(source, outputFile, 3, 90, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    /**
     * Test: make sure the muxer handles video, audio and standard compliant metadata tracks that
     * generated from API29 correctly. This test will use extractor to extract the video track,
     * audio and the standard compliant metadata track from the source file, then remuxes them
     * into a new file with standard compliant metadata track. Finally, it will check to make sure
     * the new file's metadata track matches the source file's metadata track for the mime format
     * and data payload.
     */
     public void testVideoAudioMedatadataWithCompliantMetadataTrack() throws Exception {
        int source =
                R.raw.video_176x144_3gp_h263_300kbps_25fps_aac_stereo_128kbps_11025hz_metadata_gyro_compliant;
        String outputFile = File.createTempFile("MediaMuxerTest_testAudioVideoMetadata", ".mp4")
                .getAbsolutePath();
        cloneAndVerify(source, outputFile, 3, 90, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    /**
     * Test: make sure the muxer handles audio track only file correctly.
     */
    public void testAudioOnly() throws Exception {
        int source = R.raw.sinesweepm4a;
        String outputFile = File.createTempFile("MediaMuxerTest_testAudioOnly", ".mp4")
                .getAbsolutePath();
        cloneAndVerify(source, outputFile, 1, -1, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    /**
     * Test: make sure the muxer handles video track only file correctly.
     */
    public void testVideoOnly() throws Exception {
        int source = R.raw.video_only_176x144_3gp_h263_25fps;
        String outputFile = File.createTempFile("MediaMuxerTest_videoOnly", ".mp4")
                .getAbsolutePath();
        cloneAndVerify(source, outputFile, 1, 180, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    public void testWebmOutput() throws Exception {
        int source = R.raw.video_480x360_webm_vp9_333kbps_25fps_vorbis_stereo_128kbps_48000hz;
        String outputFile = File.createTempFile("testWebmOutput", ".webm")
                .getAbsolutePath();
        cloneAndVerify(source, outputFile, 2, 90, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM);
    }

    public void testThreegppOutput() throws Exception {
        int source = R.raw.video_176x144_3gp_h263_300kbps_12fps_aac_stereo_128kbps_22050hz;
        String outputFile = File.createTempFile("testThreegppOutput", ".3gp")
                .getAbsolutePath();
        cloneAndVerify(source, outputFile, 2, 90, MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP);
    }

    /**
     * Tests: make sure the muxer handles exceptions correctly.
     * <br> Throws exception b/c start() is not called.
     * <br> Throws exception b/c 2 video tracks were added.
     * <br> Throws exception b/c 2 audio tracks were added.
     * <br> Throws exception b/c 3 tracks were added.
     * <br> Throws exception b/c no tracks was added.
     * <br> Throws exception b/c a wrong format.
     */
    public void testIllegalStateExceptions() throws IOException {
        String outputFile = File.createTempFile("MediaMuxerTest_testISEs", ".mp4")
                .getAbsolutePath();
        MediaMuxer muxer;

        // Throws exception b/c start() is not called.
        muxer = new MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxer.addTrack(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 480, 320));

        try {
            muxer.stop();
            fail("should throw IllegalStateException.");
        } catch (IllegalStateException e) {
            // expected
        } finally {
            muxer.release();
        }

        // Should not throw exception when 2 video tracks were added.
        muxer = new MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxer.addTrack(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 480, 320));

        try {
            muxer.addTrack(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 480, 320));
        } catch (IllegalStateException e) {
            fail("should not throw IllegalStateException.");
        } finally {
            muxer.release();
        }

        // Should not throw exception when 2 audio tracks were added.
        muxer = new MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxer.addTrack(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1));
        try {
            muxer.addTrack(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1));
        } catch (IllegalStateException e) {
            fail("should not throw IllegalStateException.");
        } finally {
            muxer.release();
        }

        // Should not throw exception when 3 tracks were added.
        muxer = new MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxer.addTrack(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 480, 320));
        muxer.addTrack(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1));
        try {
            muxer.addTrack(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 480, 320));
        } catch (IllegalStateException e) {
            fail("should not throw IllegalStateException.");
        } finally {
            muxer.release();
        }

        // Throws exception b/c no tracks was added.
        muxer = new MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            muxer.start();
            fail("should throw IllegalStateException.");
        } catch (IllegalStateException e) {
            // expected
        } finally {
            muxer.release();
        }

        // Throws exception b/c a wrong format.
        muxer = new MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            muxer.addTrack(MediaFormat.createVideoFormat("vidoe/mp4", 480, 320));
            fail("should throw IllegalStateException.");
        } catch (IllegalStateException e) {
            // expected
        } finally {
            muxer.release();
        }

        // Test FileDescriptor Constructor expect sucess.
        RandomAccessFile file = null;
        try {
            file = new RandomAccessFile(outputFile, "rws");
            muxer = new MediaMuxer(file.getFD(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.addTrack(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 480, 320));
        } finally {
            file.close();
            muxer.release();
        }

        // Test FileDescriptor Constructor expect exception with read only mode.
        RandomAccessFile file2 = null;
        try {
            file2 = new RandomAccessFile(outputFile, "r");
            muxer = new MediaMuxer(file2.getFD(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            fail("should throw IOException.");
        } catch (IOException e) {
            // expected
        } finally {
            file2.close();
            // No need to release the muxer.
        }

        // Test FileDescriptor Constructor expect NO exception with write only mode.
        ParcelFileDescriptor out = null;
        try {
            out = ParcelFileDescriptor.open(new File(outputFile),
                    ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE);
            muxer = new MediaMuxer(out.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IllegalArgumentException e) {
            fail("should not throw IllegalArgumentException.");
        } catch (IOException e) {
            fail("should not throw IOException.");
        } finally {
            out.close();
            muxer.release();
        }

        new File(outputFile).delete();
    }

    /**
     * Test: makes sure if audio and video muxing using MPEG4Writer works well when there are frame
     * drops as in b/63590381 and b/64949961 while B Frames encoding is enabled.
     */
    public void testSimulateAudioBVideoFramesDropIssues() throws Exception {
        int sourceId = R.raw.video_h264_main_b_frames;
        String outputFile = File.createTempFile(
            "MediaMuxerTest_testSimulateAudioBVideoFramesDropIssues", ".mp4").getAbsolutePath();
        try {
            simulateVideoFramesDropIssuesAndMux(sourceId, outputFile, 2 /* track index */,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            verifyAFewSamplesTimestamp(sourceId, outputFile);
            verifySamplesMatch(sourceId, outputFile, 66667 /* sample around 0 sec */, 0);
            verifySamplesMatch(
                    sourceId, outputFile, 8033333 /*  sample around 8 sec */, OFFSET_TIME_US);
        } finally {
            new File(outputFile).delete();
        }
    }

    /**
     * Test: makes sure if video only muxing using MPEG4Writer works well when there are B Frames.
     */
    public void testAllTimestampsBVideoOnly() throws Exception {
        int sourceId = R.raw.video_480x360_mp4_h264_bframes_495kbps_30fps_editlist;
        String outputFilePath = File.createTempFile("MediaMuxerTest_testAllTimestampsBVideoOnly",
            ".mp4").getAbsolutePath();
        try {
            // No samples to drop in this case.
            // No start offsets for any track.
            cloneMediaWithSamplesDropAndStartOffsets(sourceId, outputFilePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, null, null);
            verifyTimestampsWithSamplesDropSet(sourceId, outputFilePath, null, null);
        } finally {
            new File(outputFilePath).delete();
        }
    }

    /**
     * Test: makes sure muxing works well when video with B Frames are muxed using MPEG4Writer
     * and a few frames drop.
     */
    public void testTimestampsBVideoOnlyFramesDropOnce() throws Exception {
        int sourceId = R.raw.video_480x360_mp4_h264_bframes_495kbps_30fps_editlist;
        String outputFilePath = File.createTempFile(
            "MediaMuxerTest_testTimestampsBVideoOnlyFramesDropOnce", ".mp4").getAbsolutePath();
        try {
            HashSet<Integer> samplesDropSet = new HashSet<Integer>();
            // Drop frames from sample index 56 to 76, I frame at 56.
            IntStream.rangeClosed(56, 76).forEach(samplesDropSet::add);
            // No start offsets for any track.
            cloneMediaWithSamplesDropAndStartOffsets(sourceId, outputFilePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, samplesDropSet, null);
            verifyTimestampsWithSamplesDropSet(sourceId, outputFilePath, samplesDropSet, null);
        } finally {
            new File(outputFilePath).delete();
        }
    }

    /**
     * Test: makes sure if video muxing while framedrops occurs twice using MPEG4Writer
     * works with B Frames.
     */
    public void testTimestampsBVideoOnlyFramesDropTwice() throws Exception {
        int sourceId = R.raw.video_480x360_mp4_h264_bframes_495kbps_30fps_editlist;
        String outputFilePath = File.createTempFile(
            "MediaMuxerTest_testTimestampsBVideoOnlyFramesDropTwice", ".mp4").getAbsolutePath();
        try {
            HashSet<Integer> samplesDropSet = new HashSet<Integer>();
            // Drop frames with sample index 57 to 67, P frame at 57.
            IntStream.rangeClosed(57, 67).forEach(samplesDropSet::add);
            // Drop frames with sample index 173 to 200, B frame at 173.
            IntStream.rangeClosed(173, 200).forEach(samplesDropSet::add);
            // No start offsets for any track.
            cloneMediaWithSamplesDropAndStartOffsets(sourceId, outputFilePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, samplesDropSet, null);
            verifyTimestampsWithSamplesDropSet(sourceId, outputFilePath, samplesDropSet, null);
        } finally {
            new File(outputFilePath).delete();
        }
    }

    /**
     * Test: makes sure if audio/video muxing while framedrops once using MPEG4Writer
     * works with B Frames.
     */
    public void testTimestampsAudioBVideoFramesDropOnce() throws Exception {
        int sourceId = R.raw.video_h264_main_b_frames;
        String outputFilePath = File.createTempFile(
            "MediaMuxerTest_testTimestampsAudioBVideoFramesDropOnce", ".mp4").getAbsolutePath();
        try {
            HashSet<Integer> samplesDropSet = new HashSet<Integer>();
            // Drop frames from sample index 56 to 76, I frame at 56.
            IntStream.rangeClosed(56, 76).forEach(samplesDropSet::add);
            // No start offsets for any track.
            cloneMediaWithSamplesDropAndStartOffsets(sourceId, outputFilePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, samplesDropSet, null);
            verifyTimestampsWithSamplesDropSet(sourceId, outputFilePath, samplesDropSet, null);
        } finally {
            new File(outputFilePath).delete();
        }
    }

    /**
     * Test: makes sure if audio/video muxing while framedrops twice using MPEG4Writer
     * works with B Frames.
     */
    public void testTimestampsAudioBVideoFramesDropTwice() throws Exception {
        int sourceId = R.raw.video_h264_main_b_frames;
        String outputFilePath = File.createTempFile(
            "MediaMuxerTest_testTimestampsAudioBVideoFramesDropTwice", ".mp4").getAbsolutePath();
        try {
            HashSet<Integer> samplesDropSet = new HashSet<Integer>();
            // Drop frames with sample index 57 to 67, P frame at 57.
            IntStream.rangeClosed(57, 67).forEach(samplesDropSet::add);
            // Drop frames with sample index 173 to 200, B frame at 173.
            IntStream.rangeClosed(173, 200).forEach(samplesDropSet::add);
            // No start offsets for any track.
            cloneMediaWithSamplesDropAndStartOffsets(sourceId, outputFilePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, samplesDropSet, null);
            verifyTimestampsWithSamplesDropSet(sourceId, outputFilePath, samplesDropSet, null);
        } finally {
            new File(outputFilePath).delete();
        }
    }

    /**
     * Test: makes sure if audio/video muxing using MPEG4Writer works with B Frames
     * when video frames start later than audio.
     */
    public void testTimestampsAudioBVideoStartOffsetVideo() throws Exception {
        int sourceId = R.raw.video_h264_main_b_frames;
        String outputFilePath = File.createTempFile(
            "MediaMuxerTest_testTimestampsAudioBVideoStartOffsetVideo", ".mp4").getAbsolutePath();
        try {
            Vector<Integer> startOffsetUsVect = new Vector<Integer>();
            // Video starts at 400000us.
            startOffsetUsVect.add(400000);
            // Audio starts at 0us.
            startOffsetUsVect.add(0);
            cloneMediaWithSamplesDropAndStartOffsets(sourceId, outputFilePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, null, startOffsetUsVect);
            verifyTimestampsWithSamplesDropSet(sourceId, outputFilePath, null, startOffsetUsVect);
        } finally {
            new File(outputFilePath).delete();
        }
    }

    /**
     * Test: makes sure if audio/video muxing using MPEG4Writer works with B Frames when audio
     * samples start later than video.
     */
    public void testTimestampsAudioBVideoStartOffsetAudio() throws Exception {
        int sourceId = R.raw.video_h264_main_b_frames;
        String outputFilePath = File.createTempFile(
            "MediaMuxerTest_testTimestampsAudioBVideoStartOffsetAudio", ".mp4").getAbsolutePath();
        try {
            Vector<Integer> startOffsetUsVect = new Vector<Integer>();
            // Video starts at 0us.
            startOffsetUsVect.add(0);
            // Audio starts at 400000us.
            startOffsetUsVect.add(400000);
            cloneMediaWithSamplesDropAndStartOffsets(sourceId, outputFilePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, null, startOffsetUsVect);
            verifyTimestampsWithSamplesDropSet(sourceId, outputFilePath, null, startOffsetUsVect);
        } finally {
            new File(outputFilePath).delete();
        }
    }

    /**
     * Clones a media file and then compares against the source file to make
     * sure they match.
     */
    private void cloneAndVerify(int srcMedia, String outputMediaFile,
            int expectedTrackCount, int degrees, int fmt) throws IOException {
        try {
            cloneMediaUsingMuxer(srcMedia, outputMediaFile, expectedTrackCount,
                    degrees, fmt);
            if (fmt == MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 ||
                    fmt == MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP) {
                verifyAttributesMatch(srcMedia, outputMediaFile, degrees);
                verifyLocationInFile(outputMediaFile);
            }
            // Verify timestamp of all samples.
            verifyTimestampsWithSamplesDropSet(srcMedia, outputMediaFile, null, null);
        } finally {
            new File(outputMediaFile).delete();
        }
    }

    /**
     * Using the MediaMuxer to clone a media file.
     */
    private void cloneMediaUsingMuxer(int srcMedia, String dstMediaPath,
            int expectedTrackCount, int degrees, int fmt)
            throws IOException {
        // Set up MediaExtractor to read from the source.
        AssetFileDescriptor srcFd = mResources.openRawResourceFd(srcMedia);
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(srcFd.getFileDescriptor(), srcFd.getStartOffset(),
                srcFd.getLength());

        int trackCount = extractor.getTrackCount();
        assertEquals("wrong number of tracks", expectedTrackCount, trackCount);

        // Set up MediaMuxer for the destination.
        MediaMuxer muxer;
        muxer = new MediaMuxer(dstMediaPath, fmt);

        // Set up the tracks.
        HashMap<Integer, Integer> indexMap = new HashMap<Integer, Integer>(trackCount);
        for (int i = 0; i < trackCount; i++) {
            extractor.selectTrack(i);
            MediaFormat format = extractor.getTrackFormat(i);
            int dstIndex = muxer.addTrack(format);
            indexMap.put(i, dstIndex);
        }

        // Copy the samples from MediaExtractor to MediaMuxer.
        boolean sawEOS = false;
        int bufferSize = MAX_SAMPLE_SIZE;
        int frameCount = 0;
        int offset = 100;

        ByteBuffer dstBuf = ByteBuffer.allocate(bufferSize);
        BufferInfo bufferInfo = new BufferInfo();

        if (degrees >= 0) {
            muxer.setOrientationHint(degrees);
        }

        if (fmt == MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 ||
            fmt == MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP) {
            // Test setLocation out of bound cases
            try {
                muxer.setLocation(BAD_LATITUDE, LONGITUDE);
                fail("setLocation succeeded with bad argument: [" + BAD_LATITUDE + "," + LONGITUDE
                    + "]");
            } catch (IllegalArgumentException e) {
                // Expected
            }
            try {
                muxer.setLocation(LATITUDE, BAD_LONGITUDE);
                fail("setLocation succeeded with bad argument: [" + LATITUDE + "," + BAD_LONGITUDE
                    + "]");
            } catch (IllegalArgumentException e) {
                // Expected
            }

            muxer.setLocation(LATITUDE, LONGITUDE);
        }

        muxer.start();
        while (!sawEOS) {
            bufferInfo.offset = offset;
            bufferInfo.size = extractor.readSampleData(dstBuf, offset);

            if (bufferInfo.size < 0) {
                if (VERBOSE) {
                    Log.d(TAG, "saw input EOS.");
                }
                sawEOS = true;
                bufferInfo.size = 0;
            } else {
                bufferInfo.presentationTimeUs = extractor.getSampleTime();
                bufferInfo.flags = extractor.getSampleFlags();
                int trackIndex = extractor.getSampleTrackIndex();

                muxer.writeSampleData(indexMap.get(trackIndex), dstBuf,
                        bufferInfo);
                extractor.advance();

                frameCount++;
                if (VERBOSE) {
                    Log.d(TAG, "Frame (" + frameCount + ") " +
                            "PresentationTimeUs:" + bufferInfo.presentationTimeUs +
                            " Flags:" + bufferInfo.flags +
                            " TrackIndex:" + trackIndex +
                            " Size(KB) " + bufferInfo.size / 1024);
                }
            }
        }

        muxer.stop();
        muxer.release();
        extractor.release();
        srcFd.close();
        return;
    }

    /**
     * Compares some attributes using MediaMetadataRetriever to make sure the
     * cloned media file matches the source file.
     */
    private void verifyAttributesMatch(int srcMedia, String testMediaPath,
            int degrees) throws IOException {
        AssetFileDescriptor testFd = mResources.openRawResourceFd(srcMedia);

        MediaMetadataRetriever retrieverSrc = new MediaMetadataRetriever();
        retrieverSrc.setDataSource(testFd.getFileDescriptor(),
                testFd.getStartOffset(), testFd.getLength());

        MediaMetadataRetriever retrieverTest = new MediaMetadataRetriever();
        retrieverTest.setDataSource(testMediaPath);

        String testDegrees = retrieverTest.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        if (testDegrees != null) {
            assertEquals("Different degrees", degrees,
                    Integer.parseInt(testDegrees));
        }

        String heightSrc = retrieverSrc.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
        String heightTest = retrieverTest.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
        assertEquals("Different height", heightSrc,
                heightTest);

        String widthSrc = retrieverSrc.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
        String widthTest = retrieverTest.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
        assertEquals("Different width", widthSrc,
                widthTest);

        String durationSrc = retrieverSrc.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION);
        String durationTest = retrieverTest.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION);
        assertEquals("Different duration", durationSrc,
                durationTest);

        retrieverSrc.release();
        retrieverTest.release();
        testFd.close();
    }

    private void verifyLocationInFile(String fileName) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(fileName);
        String location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
        assertNotNull("No location information found in file " + fileName, location);


        // parsing String location and recover the location information in floats
        // Make sure the tolerance is very small - due to rounding errors.

        // Get the position of the -/+ sign in location String, which indicates
        // the beginning of the longitude.
        int minusIndex = location.lastIndexOf('-');
        int plusIndex = location.lastIndexOf('+');

        assertTrue("+ or - is not found or found only at the beginning [" + location + "]",
                (minusIndex > 0 || plusIndex > 0));
        int index = Math.max(minusIndex, plusIndex);

        float latitude = Float.parseFloat(location.substring(0, index - 1));
        int lastIndex = location.lastIndexOf('/', index);
        if (lastIndex == -1) {
            lastIndex = location.length();
        }
        float longitude = Float.parseFloat(location.substring(index, lastIndex - 1));
        assertTrue("Incorrect latitude: " + latitude + " [" + location + "]",
                Math.abs(latitude - LATITUDE) <= TOLERANCE);
        assertTrue("Incorrect longitude: " + longitude + " [" + location + "]",
                Math.abs(longitude - LONGITUDE) <= TOLERANCE);
        retriever.release();
    }

    /**
     * Uses 2 MediaExtractor, seeking to the same position, reads the sample and
     * makes sure the samples match.
     */
    private void verifySamplesMatch(int srcMedia, String testMediaPath, int seekToUs,
            long offsetTimeUs) throws IOException {
        AssetFileDescriptor testFd = mResources.openRawResourceFd(srcMedia);
        MediaExtractor extractorSrc = new MediaExtractor();
        extractorSrc.setDataSource(testFd.getFileDescriptor(),
                testFd.getStartOffset(), testFd.getLength());
        int trackCount = extractorSrc.getTrackCount();
        final int videoTrackIndex = 0;

        MediaExtractor extractorTest = new MediaExtractor();
        extractorTest.setDataSource(testMediaPath);

        assertEquals("wrong number of tracks", trackCount,
                extractorTest.getTrackCount());

        // Make sure the format is the same and select them
        for (int i = 0; i < trackCount; i++) {
            MediaFormat formatSrc = extractorSrc.getTrackFormat(i);
            MediaFormat formatTest = extractorTest.getTrackFormat(i);

            String mimeIn = formatSrc.getString(MediaFormat.KEY_MIME);
            String mimeOut = formatTest.getString(MediaFormat.KEY_MIME);
            if (!(mimeIn.equals(mimeOut))) {
                fail("format didn't match on track No." + i +
                        formatSrc.toString() + "\n" + formatTest.toString());
            }
            extractorSrc.selectTrack(videoTrackIndex);
            extractorTest.selectTrack(videoTrackIndex);

            // Pick a time and try to compare the frame.
            extractorSrc.seekTo(seekToUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            extractorTest.seekTo(seekToUs + offsetTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            int bufferSize = MAX_SAMPLE_SIZE;
            ByteBuffer byteBufSrc = ByteBuffer.allocate(bufferSize);
            ByteBuffer byteBufTest = ByteBuffer.allocate(bufferSize);

            int srcBufSize = extractorSrc.readSampleData(byteBufSrc, 0);
            int testBufSize = extractorTest.readSampleData(byteBufTest, 0);

            if (!(byteBufSrc.equals(byteBufTest))) {
                if (VERBOSE) {
                    Log.d(TAG,
                            "srcTrackIndex:" + extractorSrc.getSampleTrackIndex()
                                    + "  testTrackIndex:" + extractorTest.getSampleTrackIndex());
                    Log.d(TAG,
                            "srcTSus:" + extractorSrc.getSampleTime()
                                    + " testTSus:" + extractorTest.getSampleTime());
                    Log.d(TAG, "srcBufSize:" + srcBufSize + "testBufSize:" + testBufSize);
                }
                fail("byteBuffer didn't match");
            }
            extractorSrc.unselectTrack(i);
            extractorTest.unselectTrack(i);
        }
        extractorSrc.release();
        extractorTest.release();
        testFd.close();
    }

    /**
     * Using MediaMuxer and MediaExtractor to mux a media file from another file while skipping
     * some video frames as in the issues b/63590381 and b/64949961.
     */
    private void simulateVideoFramesDropIssuesAndMux(int srcMedia, String dstMediaPath,
            int expectedTrackCount, int fmt) throws IOException {
        // Set up MediaExtractor to read from the source.
        AssetFileDescriptor srcFd = mResources.openRawResourceFd(srcMedia);
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(srcFd.getFileDescriptor(), srcFd.getStartOffset(),
            srcFd.getLength());

        int trackCount = extractor.getTrackCount();
        assertEquals("wrong number of tracks", expectedTrackCount, trackCount);

        // Set up MediaMuxer for the destination.
        MediaMuxer muxer;
        muxer = new MediaMuxer(dstMediaPath, fmt);

        // Set up the tracks.
        HashMap<Integer, Integer> indexMap = new HashMap<Integer, Integer>(trackCount);

        for (int i = 0; i < trackCount; i++) {
            extractor.selectTrack(i);
            MediaFormat format = extractor.getTrackFormat(i);
            int dstIndex = muxer.addTrack(format);
            indexMap.put(i, dstIndex);
        }

        // Copy the samples from MediaExtractor to MediaMuxer.
        boolean sawEOS = false;
        int bufferSize = MAX_SAMPLE_SIZE;
        int sampleCount = 0;
        int offset = 0;
        int videoSampleCount = 0;
        // Counting frame index values starting from 1
        final int muxAllTypeVideoFramesUntilIndex = 136; // I/P/B frames passed as it is until this
        final int muxAllTypeVideoFramesFromIndex = 171; // I/P/B frames passed as it is from this
        final int pFrameBeforeARandomBframeIndex = 137;
        final int bFrameAfterPFrameIndex = pFrameBeforeARandomBframeIndex+1;

        ByteBuffer dstBuf = ByteBuffer.allocate(bufferSize);
        BufferInfo bufferInfo = new BufferInfo();

        muxer.start();
        while (!sawEOS) {
            bufferInfo.offset = 0;
            bufferInfo.size = extractor.readSampleData(dstBuf, offset);
            if (bufferInfo.size < 0) {
                if (VERBOSE) {
                    Log.d(TAG, "saw input EOS.");
                }
                sawEOS = true;
                bufferInfo.size = 0;
            } else {
                bufferInfo.presentationTimeUs = extractor.getSampleTime();
                bufferInfo.flags = extractor.getSampleFlags();
                int trackIndex = extractor.getSampleTrackIndex();
                // Video track at index 0, skip some video frames while muxing.
                if (trackIndex == 0) {
                    ++videoSampleCount;
                    if (VERBOSE) {
                        Log.i(TAG, "videoSampleCount : " + videoSampleCount);
                    }
                    if (videoSampleCount <= muxAllTypeVideoFramesUntilIndex
                            || videoSampleCount == bFrameAfterPFrameIndex) {
                        // Write frame as it is.
                        muxer.writeSampleData(indexMap.get(trackIndex), dstBuf, bufferInfo);
                    } else if (videoSampleCount == pFrameBeforeARandomBframeIndex
                            || videoSampleCount >= muxAllTypeVideoFramesFromIndex) {
                        // Adjust time stamp for this P frame to a few frames later, say ~5seconds
                        bufferInfo.presentationTimeUs += OFFSET_TIME_US;
                        muxer.writeSampleData(indexMap.get(trackIndex), dstBuf, bufferInfo);
                    } else {
                        // Skip frames after bFrameAfterPFrameIndex
                        // and before muxAllTypeVideoFramesFromIndex.
                        if (VERBOSE) {
                            Log.i(TAG, "skipped this frame");
                        }
                    }
                } else {
                    // write audio data as it is continuously
                    muxer.writeSampleData(indexMap.get(trackIndex), dstBuf, bufferInfo);
                }
                extractor.advance();
                sampleCount++;
                if (VERBOSE) {
                    Log.d(TAG, "Frame (" + sampleCount + ") " +
                            "PresentationTimeUs:" + bufferInfo.presentationTimeUs +
                            " Flags:" + bufferInfo.flags +
                            " TrackIndex:" + trackIndex +
                            " Size(bytes) " + bufferInfo.size );
                }
            }
        }

        muxer.stop();
        muxer.release();
        extractor.release();
        srcFd.close();

        return;
    }

    /* Uses two MediaExtractor's and checks whether timestamps of first few and another few
     *  from last sync frame matches
     */
    private void verifyAFewSamplesTimestamp(int srcMediaId, String testMediaPath)
            throws IOException {
        final int numFramesTSCheck = 10; // Num frames to be checked for its timestamps

        AssetFileDescriptor srcFd = mResources.openRawResourceFd(srcMediaId);
        MediaExtractor extractorSrc = new MediaExtractor();
        extractorSrc.setDataSource(srcFd.getFileDescriptor(),
            srcFd.getStartOffset(), srcFd.getLength());
        MediaExtractor extractorTest = new MediaExtractor();
        extractorTest.setDataSource(testMediaPath);

        int trackCount = extractorSrc.getTrackCount();
        for (int i = 0; i < trackCount; i++) {
            MediaFormat format = extractorSrc.getTrackFormat(i);
            extractorSrc.selectTrack(i);
            extractorTest.selectTrack(i);
            if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                // Check time stamps for numFramesTSCheck frames from 33333us.
                checkNumFramesTimestamp(33333, 0, numFramesTSCheck, extractorSrc, extractorTest);
                // Check time stamps for numFramesTSCheck frames from 9333333 -
                // sync frame after framedrops at index 172 of video track.
                checkNumFramesTimestamp(
                        9333333, OFFSET_TIME_US, numFramesTSCheck, extractorSrc, extractorTest);
            } else if (format.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                // Check timestamps for all audio frames. Test file has 427 audio frames.
                checkNumFramesTimestamp(0, 0, 427, extractorSrc, extractorTest);
            }
            extractorSrc.unselectTrack(i);
            extractorTest.unselectTrack(i);
        }

        extractorSrc.release();
        extractorTest.release();
        srcFd.close();
    }

    private void checkNumFramesTimestamp(long seekTimeUs, long offsetTimeUs, int numFrames,
            MediaExtractor extractorSrc, MediaExtractor extractorTest) {
        long srcSampleTimeUs = -1;
        long testSampleTimeUs = -1;
        extractorSrc.seekTo(seekTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        extractorTest.seekTo(seekTimeUs + offsetTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        while (numFrames-- > 0 ) {
            srcSampleTimeUs = extractorSrc.getSampleTime();
            testSampleTimeUs = extractorTest.getSampleTime();
            if(srcSampleTimeUs == -1 || testSampleTimeUs == -1){
                fail("either of tracks reached end of stream");
            }
            if ((srcSampleTimeUs + offsetTimeUs) != testSampleTimeUs) {
                if (VERBOSE) {
                    Log.d(TAG, "srcTrackIndex:" + extractorSrc.getSampleTrackIndex() +
                        "  testTrackIndex:" + extractorTest.getSampleTrackIndex());
                    Log.d(TAG, "srcTSus:" + srcSampleTimeUs + " testTSus:" + testSampleTimeUs);
                }
                fail("timestamps didn't match");
            }
            extractorSrc.advance();
            extractorTest.advance();
        }
    }

    /**
     * Using MediaMuxer and MediaExtractor to mux a media file from another file while skipping
     * 0 or more video frames and desired start offsets for each track.
     * startOffsetUsVect : order of tracks is the same as in the input file
     */
    private void cloneMediaWithSamplesDropAndStartOffsets(int srcMedia, String dstMediaPath,
            int fmt, HashSet<Integer> samplesDropSet, Vector<Integer> startOffsetUsVect)
            throws IOException {
        // Set up MediaExtractor to read from the source.
        AssetFileDescriptor srcFd = mResources.openRawResourceFd(srcMedia);
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(srcFd.getFileDescriptor(), srcFd.getStartOffset(),
            srcFd.getLength());

        int trackCount = extractor.getTrackCount();

        // Set up MediaMuxer for the destination.
        MediaMuxer muxer;
        muxer = new MediaMuxer(dstMediaPath, fmt);

        // Set up the tracks.
        HashMap<Integer, Integer> indexMap = new HashMap<Integer, Integer>(trackCount);

        int videoTrackIndex = 100;
        int videoStartOffsetUs = 0;
        int audioTrackIndex = 100;
        int audioStartOffsetUs = 0;
        for (int i = 0; i < trackCount; i++) {
            extractor.selectTrack(i);
            MediaFormat format = extractor.getTrackFormat(i);
            int dstIndex = muxer.addTrack(format);
            indexMap.put(i, dstIndex);
            if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                videoTrackIndex = i;
                // Make sure there's an entry for video track.
                if (startOffsetUsVect != null && (videoTrackIndex < startOffsetUsVect.size())) {
                    videoStartOffsetUs = startOffsetUsVect.get(videoTrackIndex);
                }
            }
            if (format.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                audioTrackIndex = i;
                // Make sure there's an entry for audio track.
                if (startOffsetUsVect != null && (audioTrackIndex < startOffsetUsVect.size())) {
                    audioStartOffsetUs = startOffsetUsVect.get(audioTrackIndex);
                }
            }
        }

        // Copy the samples from MediaExtractor to MediaMuxer.
        boolean sawEOS = false;
        int bufferSize = MAX_SAMPLE_SIZE;
        int sampleCount = 0;
        int offset = 0;
        int videoSampleCount = 0;

        ByteBuffer dstBuf = ByteBuffer.allocate(bufferSize);
        BufferInfo bufferInfo = new BufferInfo();

        muxer.start();
        while (!sawEOS) {
            bufferInfo.offset = 0;
            bufferInfo.size = extractor.readSampleData(dstBuf, offset);
            if (bufferInfo.size < 0) {
                if (VERBOSE) {
                    Log.d(TAG, "saw input EOS.");
                }
                sawEOS = true;
                bufferInfo.size = 0;
            } else {
                bufferInfo.presentationTimeUs = extractor.getSampleTime();
                bufferInfo.flags = extractor.getSampleFlags();
                int trackIndex = extractor.getSampleTrackIndex();
                if (trackIndex == videoTrackIndex) {
                    ++videoSampleCount;
                    if (VERBOSE) {
                        Log.i(TAG, "videoSampleCount : " + videoSampleCount);
                    }
                    if (samplesDropSet == null || (!samplesDropSet.contains(videoSampleCount))) {
                        // Write video frame with start offset adjustment.
                        bufferInfo.presentationTimeUs += videoStartOffsetUs;
                        muxer.writeSampleData(indexMap.get(trackIndex), dstBuf, bufferInfo);
                    }
                    else {
                        if (VERBOSE) {
                            Log.i(TAG, "skipped this frame");
                        }
                    }
                } else {
                    // write audio sample with start offset adjustment.
                    bufferInfo.presentationTimeUs += audioStartOffsetUs;
                    muxer.writeSampleData(indexMap.get(trackIndex), dstBuf, bufferInfo);
                }
                extractor.advance();
                sampleCount++;
                if (VERBOSE) {
                    Log.i(TAG, "Sample (" + sampleCount + ")" +
                            " TrackIndex:" + trackIndex +
                            " PresentationTimeUs:" + bufferInfo.presentationTimeUs +
                            " Flags:" + bufferInfo.flags +
                            " Size(bytes)" + bufferInfo.size );
                }
            }
        }

        muxer.stop();
        muxer.release();
        extractor.release();
        srcFd.close();

        return;
    }

    /*
     * Uses MediaExtractors and checks whether timestamps of all samples except in samplesDropSet
     *  and with start offsets adjustments for each track match.
     */
    private void verifyTimestampsWithSamplesDropSet(int srcMediaId, String testMediaPath,
            HashSet<Integer> samplesDropSet, Vector<Integer> startOffsetUsVect) throws IOException {
        AssetFileDescriptor srcFd = mResources.openRawResourceFd(srcMediaId);
        MediaExtractor extractorSrc = new MediaExtractor();
        extractorSrc.setDataSource(srcFd.getFileDescriptor(),
            srcFd.getStartOffset(), srcFd.getLength());
        MediaExtractor extractorTest = new MediaExtractor();
        extractorTest.setDataSource(testMediaPath);

        int videoTrackIndex = -1;
        int videoStartOffsetUs = 0;
        int trackCount = extractorSrc.getTrackCount();

        // Select video track.
        for (int i = 0; i < trackCount; i++) {
            MediaFormat format = extractorSrc.getTrackFormat(i);
            if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                videoTrackIndex = i;
                if (startOffsetUsVect != null && videoTrackIndex < startOffsetUsVect.size()) {
                    videoStartOffsetUs = startOffsetUsVect.get(videoTrackIndex);
                }
                extractorSrc.selectTrack(videoTrackIndex);
                extractorTest.selectTrack(videoTrackIndex);
                checkVideoSamplesTimeStamps(extractorSrc, extractorTest, samplesDropSet,
                    videoStartOffsetUs);
                extractorSrc.unselectTrack(videoTrackIndex);
                extractorTest.unselectTrack(videoTrackIndex);
            }
        }

        int audioTrackIndex = -1;
        int audioSampleCount = 0;
        int audioStartOffsetUs = 0;
        //select audio track
        for (int i = 0; i < trackCount; i++) {
            MediaFormat format = extractorSrc.getTrackFormat(i);
            if (format.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                audioTrackIndex = i;
                if (startOffsetUsVect != null && audioTrackIndex < startOffsetUsVect.size()) {
                    audioStartOffsetUs = startOffsetUsVect.get(audioTrackIndex);
                }
                extractorSrc.selectTrack(audioTrackIndex);
                extractorTest.selectTrack(audioTrackIndex);
                checkAudioSamplesTimestamps(extractorSrc, extractorTest, audioStartOffsetUs);
            }
        }

        extractorSrc.release();
        extractorTest.release();
        srcFd.close();
    }

    // Check timestamps of all video samples.
    private void checkVideoSamplesTimeStamps(MediaExtractor extractorSrc,
                MediaExtractor extractorTest, HashSet<Integer> samplesDropSet,
                int videoStartOffsetUs) {
        long srcSampleTimeUs = -1;
        long testSampleTimeUs = -1;
        boolean srcAdvance = false;
        boolean testAdvance = false;
        int videoSampleCount = 0;

        extractorSrc.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        extractorTest.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

        do {
            ++videoSampleCount;
            srcSampleTimeUs = extractorSrc.getSampleTime();
            testSampleTimeUs = extractorTest.getSampleTime();
            if (VERBOSE) {
                Log.i(TAG, "videoSampleCount:" + videoSampleCount);
                Log.i(TAG, "srcTrackIndex:" + extractorSrc.getSampleTrackIndex() +
                            "  testTrackIndex:" + extractorTest.getSampleTrackIndex());
                Log.i(TAG, "srcTSus:" + srcSampleTimeUs + " testTSus:" + testSampleTimeUs);
            }
            if (samplesDropSet == null || !samplesDropSet.contains(videoSampleCount)) {
                if (srcSampleTimeUs == -1 || testSampleTimeUs == -1) {
                  if (VERBOSE) {
                    Log.d(TAG, "videoSampleCount:" + videoSampleCount);
                    Log.d(TAG, "srcUs:" + srcSampleTimeUs + "testUs:" + testSampleTimeUs);
                  }
                  fail("either source or test track reached end of stream");
                }
                // Stts values within 0.1ms(100us) difference are fudged to save too many
                // stts entries in MPEG4Writer.
                else if (Math.abs(srcSampleTimeUs + videoStartOffsetUs - testSampleTimeUs) > 100) {
                    if (VERBOSE) {
                        Log.d(TAG, "Fail:video timestamps didn't match");
                        Log.d(TAG, "srcTrackIndex:" + extractorSrc.getSampleTrackIndex() +
                            "  testTrackIndex:" + extractorTest.getSampleTrackIndex());
                        Log.d(TAG, "srcTSus:" + srcSampleTimeUs + " testTSus:" + testSampleTimeUs);
                        Log.d(TAG, "videoSampleCount:" + videoSampleCount);
                    }
                    fail("video timestamps didn't match");
                }
                testAdvance = extractorTest.advance();
            }
            srcAdvance = extractorSrc.advance();
        } while(srcAdvance && testAdvance);
        if (srcAdvance != testAdvance) {
            if (VERBOSE) {
                Log.d(TAG, "videoSampleCount:" + videoSampleCount);
            }
            fail("either video track has not reached its last sample");
        }
    }

    private void checkAudioSamplesTimestamps(MediaExtractor extractorSrc,
                MediaExtractor extractorTest, int audioStartOffsetUs) {
        long srcSampleTimeUs = -1;
        long testSampleTimeUs = -1;
        boolean srcAdvance = false;
        boolean testAdvance = false;
        int audioSampleCount = 0;

        extractorSrc.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        extractorTest.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

        // Check timestamps of all audio samples.
        do {
            ++audioSampleCount;
            srcSampleTimeUs = extractorSrc.getSampleTime();
            testSampleTimeUs = extractorTest.getSampleTime();
            if(VERBOSE) {
                Log.d(TAG, "audioSampleCount:" + audioSampleCount);
                Log.v(TAG, "srcTrackIndex:" + extractorSrc.getSampleTrackIndex() +
                            "  testTrackIndex:" + extractorTest.getSampleTrackIndex());
                Log.v(TAG, "srcTSus:" + srcSampleTimeUs + " testTSus:" + testSampleTimeUs);
            }

            if (srcSampleTimeUs == -1 || testSampleTimeUs == -1) {
              if (VERBOSE) {
                Log.d(TAG, "audioSampleCount:" + audioSampleCount);
                Log.d(TAG, "srcTSus:" + srcSampleTimeUs + " testTSus:" + testSampleTimeUs);
              }
              fail("either source or test track reached end of stream");
            }
            // First audio sample would have zero timestamp and its start offset is implemented
            // by assigning the first audio sample's duration as the offset. Second sample onwards
            // would play after the offset.  But video offset is achieved by edit list entry for
            // video tracks with BFrames. Need to revert the conditional check for first
            // audio sample once we implement empty edit list entry for audio.
            else if ((audioSampleCount > 1 &&
                (srcSampleTimeUs + audioStartOffsetUs) != testSampleTimeUs) ||
                (audioSampleCount == 1 && srcSampleTimeUs != testSampleTimeUs)) {
                    fail("audio timestamps didn't match");
                }
            testAdvance = extractorTest.advance();
            srcAdvance = extractorSrc.advance();
        } while(srcAdvance && testAdvance);
        if (srcAdvance != testAdvance) {
            fail("either audio track has not reached its last sample");
        }
    }
}

