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

package android.media.decoder.cts;

import static android.media.decoder.cts.DecoderSetup.createCodecFor;
import static android.media.decoder.cts.DecoderSetup.createMediaExtractor;
import static android.media.decoder.cts.DecoderSetup.getFirstVideoTrack;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.cts.MediaHeavyPresubmitTest;
import android.media.cts.MediaTestBase;
import android.media.cts.OutputSurface;
import android.os.Build;
import android.platform.test.annotations.AppModeFull;
import android.util.Log;
import android.view.Surface;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;

import com.android.compatibility.common.util.ApiTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

/**
 * This class verifies video frames are rendered and the app is notified.
 * <p>
 * This test uses MediaCodec to decode short videos, renders each video frame to the display using a
 * SurfaceView, and then verifies the {@link MediaCodec.OnFramerenderedListener#onFrameRendered)
 * callback is fired for each of these video frames in a reasonable amount of time.
 * </p>
 */
@MediaHeavyPresubmitTest
@AppModeFull(reason = "Instant apps behave the same as full apps when it comes to decoders")
@RunWith(AndroidJUnit4.class)
public class DecoderRenderTest extends MediaTestBase {
    private static final String TAG = "DecoderRenderTest";
    private static final String REPORT_LOG_NAME = "CtsMediaDecoderTestCases";

    @Before
    @Override
    public void setUp() throws Throwable {
        super.setUp();
    }

    @After
    @Override
    public void tearDown() {
        super.tearDown();
    }

    /*
     * Tests that {@link MediaCodec.OnFramerenderedListener#onFrameRendered) is called for every
     * video frame rendered to the display when playing back a full VP9 video.
     */
    @Test
    @ApiTest(apis = {"android.media.MediaCodec.OnFrameRenderedListener#onFrameRendered"})
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, codeName = "UpsideDownCake")
    public void onFrameRendered_indicatesAllFramesRendered_toDisplay_vp9() throws Exception {
        onFrameRendered_indicatesAllFramesRendered(
                "video_480x360_webm_vp9_333kbps_25fps_vorbis_stereo_128kbps_48000hz.webm",
                getActivity().getSurfaceHolder().getSurface());
    }

    /*
     * Tests that {@link MediaCodec.OnFramerenderedListener#onFrameRendered) is called for every
     * video frame rendered to the surface texture when playing back a full VP9 video.
     */
    @Test
    @ApiTest(apis = {"android.media.MediaCodec.OnFrameRenderedListener#onFrameRendered"})
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, codeName = "UpsideDownCake")
    public void onFrameRendered_indicatesAllFramesRendered_toTexture_vp9() throws Exception {
        OutputSurface outputSurface = new OutputSurface(480, 360);
        onFrameRendered_indicatesAllFramesRendered(
                "video_480x360_webm_vp9_333kbps_25fps_vorbis_stereo_128kbps_48000hz.webm",
                outputSurface.getSurface());
    }

    public class MutableData {
        public long renderTimeNs = 0;
        public long previousPresentationTimeUs = -1;
    }

    // TODO(b/234833109): Run this test against a variety of video files and codecs.
    private void onFrameRendered_indicatesAllFramesRendered(String fileName, Surface surface)
            throws Exception {
        // TODO(b/268212517): Preplay one video frame to prime the video and graphics pipeline to
        // simulate a device in its normal steady-state (less chances for dropped frames). This
        // avoids problems, for example, with GPU shaders being compiled when rendering the first
        // video frame after boot which can cause subsequent frames to be delayed and dropped.
        primeVideoPipeline(fileName);

        MediaExtractor videoExtractor = createMediaExtractor(fileName);
        int videoTrackIndex = getFirstVideoTrack(videoExtractor);
        videoExtractor.selectTrack(videoTrackIndex);
        MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTrackIndex);
        MediaCodec videoCodec = createCodecFor(videoFormat);
        assumeFalse("No video codec found for " + fileName, videoCodec == null);
        videoCodec.configure(videoFormat, surface, null, 0);

        VideoDecoderCallback videoDecoderCallback = new VideoDecoderCallback(videoExtractor);
        videoCodec.setCallback(videoDecoderCallback);

        final MutableData data = new MutableData();
        videoDecoderCallback.setOnInputBufferAvailable(
                (index, sampleSize, presentationTimeUs, flags) -> {
                    if (sampleSize == -1) {
                        flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                        sampleSize = 0;
                    }
                    videoCodec.queueInputBuffer(index, 0, sampleSize, presentationTimeUs, flags);
                });

        final Object done = new Object();
        final List<Long> releasedFrames = new LinkedList<Long>();
        videoDecoderCallback.setOnOutputBufferAvailable(
                (index, info) -> {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        synchronized (done) {
                            done.notify();
                        }
                        return;
                    }
                    if (data.renderTimeNs == 0) {
                        // the first frame should be rendered within 200 milliseconds, if it isn't,
                        // it will appear in the frame-dropped list
                        data.renderTimeNs = System.nanoTime() + 200 * 1000 * 1000;
                    } else {
                        // frames should be rendered based on the presentation time delta
                        data.renderTimeNs +=
                                (info.presentationTimeUs - data.previousPresentationTimeUs) * 1000L;
                        // well-formed streams have monotonically-increasing presentation times
                        assertTrue(info.presentationTimeUs > data.previousPresentationTimeUs);
                    }
                    Log.d(TAG, "releasing frame " + releasedFrames.size());
                    videoCodec.releaseOutputBuffer(index, data.renderTimeNs);
                    data.previousPresentationTimeUs = info.presentationTimeUs;
                    releasedFrames.add(info.presentationTimeUs);
                });

        final List<Long> renderedFrames = new LinkedList<Long>();
        videoCodec.setOnFrameRenderedListener(
                (codec, presentationTimeUs, nanoTime) -> {
                    renderedFrames.add(presentationTimeUs);
                }, null);

        videoCodec.start();
        synchronized (done) {
            done.wait();
        }

        // sleep until 200ms after the last frame's render time to verify we get a somewhat-timely
        // onFrameRendered callback
        long sleepUntilMs = 200 + (data.renderTimeNs - System.nanoTime()) / 1000 / 1000;
        if (sleepUntilMs > 0) {
            Thread.sleep(sleepUntilMs);
        }
        videoCodec.flush();
        videoCodec.stop();
        videoCodec.release();

        // Compare the presentation timestamps of the released frames with the rendered frames to
        // detect which frame numbers were skipped
        List<Integer> skippedFrames = new LinkedList<Integer>();
        int renderedFrameIndex = 0;
        int releasedFrameIndex = 0;
        for (; releasedFrameIndex < releasedFrames.size(); ++releasedFrameIndex) {
            // we have no more rendered frames, so the last few frames must have been dropped
            if (renderedFrameIndex >= renderedFrames.size()) {
                skippedFrames.add(releasedFrameIndex);
                continue;
            }
            long releasedTime = releasedFrames.get(releasedFrameIndex);
            long renderedTime = renderedFrames.get(renderedFrameIndex);
            if (releasedTime < renderedTime) {
                // we have one or more missing rendered frames in the beginning or the middle
                skippedFrames.add(releasedFrameIndex);
            } else if (releasedTime == renderedTime) {
                // the next released frame should match the next rendered frame
                renderedFrameIndex++;
            }
        }
        // add the total number of frames to the skipped frame list and the expected list, to
        // indicate to the test operator how many total frames we had, so they know where in the
        // sequence frames were dropped
        skippedFrames.add(releasedFrames.size());
        assertEquals(List.of(releasedFrames.size()), skippedFrames);
    }

    private void primeVideoPipeline(String fileName) throws Exception {
        MediaExtractor videoExtractor = createMediaExtractor(fileName);
        int videoTrackIndex = getFirstVideoTrack(videoExtractor);
        videoExtractor.selectTrack(videoTrackIndex);
        MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTrackIndex);
        MediaCodec videoCodec = createCodecFor(videoFormat);
        assumeFalse("No video codec found for " + fileName, videoCodec == null);
        videoCodec.configure(videoFormat, getActivity().getSurfaceHolder().getSurface(), null, 0);
        videoCodec.start();
        long dequeueTimeOutUs = 5000;
        boolean sawInputEos = false;
        int tries = 50;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (tries > 0) {
            int ipBufferId = -1;
            if (!sawInputEos) {
                ipBufferId = videoCodec.dequeueInputBuffer(dequeueTimeOutUs);
                if (ipBufferId != -1) {
                    ByteBuffer inputBuffer = videoCodec.getInputBuffer(ipBufferId);
                    int sampleSize = videoExtractor.readSampleData(inputBuffer, 0);
                    long presentationTime = videoExtractor.getSampleTime();
                    int extractorFlags = videoExtractor.getSampleFlags();
                    int flags = 0;
                    if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    }
                    if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                        flags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
                    }
                    boolean hasMoreSamples = videoExtractor.advance();
                    if (!hasMoreSamples) {
                        flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                        sawInputEos = true;
                    }
                    videoCodec.queueInputBuffer(ipBufferId, 0, sampleSize, presentationTime, flags);
                }
            }
            int outputBufferId = videoCodec.dequeueOutputBuffer(bufferInfo, dequeueTimeOutUs);
            if (outputBufferId >= 0) {
                videoCodec.releaseOutputBuffer(outputBufferId, true);
                break;
            }
            if (ipBufferId == -1) tries--;
        }
        videoCodec.stop();
        videoCodec.release();
        videoExtractor.release();
        assertTrue("Timed out from waiting on OutputBuffer ", tries != 0);
    }
}
