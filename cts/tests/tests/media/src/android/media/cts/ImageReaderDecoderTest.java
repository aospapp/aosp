/*
 * Copyright 2014 The Android Open Source Project
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

import com.android.cts.media.R;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.test.AndroidTestCase;
import android.util.Log;
import android.view.Surface;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Basic test for ImageReader APIs.
 * <p>
 * It uses MediaCodec to decode a short video stream, send the video frames to
 * the surface provided by ImageReader. Then compare if output buffers of the
 * ImageReader matches the output buffers of the MediaCodec. The video format
 * used here is AVC although the compression format doesn't matter for this
 * test. For decoder test, hw and sw decoders are tested,
 * </p>
 */
public class ImageReaderDecoderTest extends AndroidTestCase {
    private static final String TAG = "ImageReaderDecoderTest";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final long DEFAULT_TIMEOUT_US = 10000;
    private static final long WAIT_FOR_IMAGE_TIMEOUT_MS = 1000;
    private static final String DEBUG_FILE_NAME_BASE = "/sdcard/";
    private static final int NUM_FRAME_DECODED = 100;
    private static final int MAX_NUM_IMAGES = 3;

    private Resources mResources;
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private ByteBuffer[] mInputBuffers;
    private ByteBuffer[] mOutputBuffers;
    private ImageReader mReader;
    private Surface mReaderSurface;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private ImageListener mImageListener;

    @Override
    public void setContext(Context context) {
        super.setContext(context);
        mResources = mContext.getResources();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mImageListener = new ImageListener();
    }

    @Override
    protected void tearDown() throws Exception {
        mHandlerThread.quitSafely();
        mHandler = null;
    }

    /**
     * Test ImageReader with 480x360 hw AVC decoding for flexible yuv format, which is mandatory
     * to be supported by hw decoder.
     */
    public void testHwAVCDecode360pForFlexibleYuv() throws Exception {
        try {
            int format = ImageFormat.YUV_420_888;
            videoDecodeToSurface(
                    R.raw.video_480x360_mp4_h264_1000kbps_25fps_aac_stereo_128kbps_44100hz,
                    /* width */480, /* height */ 360, format, /* useHw */ true);
        } finally {
            closeImageReader();
        }
    }

    /**
     * Test ImageReader with 480x360 sw AVC decoding for flexible yuv format, which is mandatory
     * to be supported by sw decoder.
     */
    public void testSwAVCDecode360pForFlexibleYuv() throws Exception {
        try {
            int format = ImageFormat.YUV_420_888;
            videoDecodeToSurface(
                    R.raw.video_480x360_mp4_h264_1000kbps_25fps_aac_stereo_128kbps_44100hz,
                    /* width */ 480, /* height */ 360, format, /* useHw */ false);
        } finally {
            closeImageReader();
        }
    }

    private static class ImageListener implements ImageReader.OnImageAvailableListener {
        private final LinkedBlockingQueue<Image> mQueue =
                new LinkedBlockingQueue<Image>();

        @Override
        public void onImageAvailable(ImageReader reader) {
            try {
                mQueue.put(reader.acquireNextImage());
            } catch (InterruptedException e) {
                throw new UnsupportedOperationException(
                        "Can't handle InterruptedException in onImageAvailable");
            }
        }

        /**
         * Get an image from the image reader.
         *
         * @param timeout Timeout value for the wait.
         * @return The image from the image reader.
         */
        public Image getImage(long timeout) throws InterruptedException {
            Image image = mQueue.poll(timeout, TimeUnit.MILLISECONDS);
            assertNotNull("Wait for an image timed out in " + timeout + "ms", image);
            return image;
        }
    }

    private void videoDecodeToSurface(int video, int width,
            int height, int imageFormat, boolean useHw) throws Exception {
        MediaCodec decoder = null;
        MediaExtractor extractor;
        int outputBufferIndex;
        ByteBuffer[] decoderInputBuffers;
        ByteBuffer[] decoderOutputBuffers;

        AssetFileDescriptor vidFD = mResources.openRawResourceFd(video);

        extractor = new MediaExtractor();
        extractor.setDataSource(vidFD.getFileDescriptor(), vidFD.getStartOffset(),
                vidFD.getLength());

        MediaFormat mediaFmt = extractor.getTrackFormat(0);
        mediaFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            CodecCapabilities.COLOR_FormatYUV420Flexible);
        String mime = mediaFmt.getString(MediaFormat.KEY_MIME);
        try {
            // Create decoder
            decoder = createDecoder(mime, useHw);
            assertNotNull("couldn't find decoder", decoder);
            if (VERBOSE) Log.v(TAG, "using decoder: " + decoder.getName());

            decodeFramesToImageReader(width, height, imageFormat, decoder,
                    extractor, mediaFmt, mime);

            decoder.stop();
        } finally {
            decoder.release();
        }

    }

    /**
     * Decode video frames to image reader.
     */
    private void decodeFramesToImageReader(int width, int height, int imageFormat,
            MediaCodec decoder, MediaExtractor extractor, MediaFormat mediaFmt, String mime)
            throws Exception, InterruptedException {
        ByteBuffer[] decoderInputBuffers;
        ByteBuffer[] decoderOutputBuffers;
        if (!imageFormatSupported(decoder, imageFormat, mime)) {
            // TODO: SKIPPING TEST
            return;
        }
        createImageReader(width, height, imageFormat, MAX_NUM_IMAGES, mImageListener);

        // Configure decoder.
        if (VERBOSE) Log.v(TAG, "stream format: " + mediaFmt);
        decoder.configure(mediaFmt, mReaderSurface, /*crypto*/null, /*flags*/0);
        decoder.start();
        decoderInputBuffers = decoder.getInputBuffers();
        decoderOutputBuffers = decoder.getOutputBuffers();
        extractor.selectTrack(0);

        // Start decoding and get Image, only test the first NUM_FRAME_DECODED frames.
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        int outputFrameCount = 0;
        while (!sawOutputEOS && outputFrameCount < NUM_FRAME_DECODED) {
            if (VERBOSE) Log.v(TAG, "loop:" + outputFrameCount);
            // Feed input frame.
            if (!sawInputEOS) {
                int inputBufIndex = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US);
                if (inputBufIndex >= 0) {
                    ByteBuffer dstBuf = decoderInputBuffers[inputBufIndex];
                    int sampleSize =
                        extractor.readSampleData(dstBuf, 0 /* offset */);

                    if (VERBOSE) Log.v(TAG, "queue a input buffer, idx/size: "
                        + inputBufIndex + "/" + sampleSize);

                    long presentationTimeUs = 0;

                    if (sampleSize < 0) {
                        if (VERBOSE) Log.v(TAG, "saw input EOS.");
                        sawInputEOS = true;
                        sampleSize = 0;
                    } else {
                        presentationTimeUs = extractor.getSampleTime();
                    }

                    decoder.queueInputBuffer(
                            inputBufIndex,
                            0 /* offset */,
                            sampleSize,
                            presentationTimeUs,
                            sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);

                    if (!sawInputEOS) {
                        extractor.advance();
                    }
                }
            }

            // Get output frame
            int res = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US);
            if (VERBOSE) Log.v(TAG, "got a buffer: " + info.size + "/" + res);
            if (res == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (VERBOSE) Log.v(TAG, "no output frame available");
            } else if (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // decoder output buffers changed, need update.
                if (VERBOSE) Log.v(TAG, "decoder output buffers changed");
                decoderOutputBuffers = decoder.getOutputBuffers();
            } else if (res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // this happens before the first frame is returned.
                MediaFormat outFormat = decoder.getOutputFormat();
                if (VERBOSE) Log.v(TAG, "decoder output format changed: " + outFormat);
            } else if (res < 0) {
                // Should be decoding error.
                fail("unexpected result from deocder.dequeueOutputBuffer: " + res);
            } else {
                // res >= 0: normal decoding case, copy the output buffer.
                // Will use it as reference to valid the ImageReader output
                // Some decoders output a 0-sized buffer at the end. Ignore those.
                outputFrameCount++;
                boolean doRender = (info.size != 0);

                decoder.releaseOutputBuffer(res, doRender);
                if (doRender) {
                    // Read image and verify
                    Image image = mImageListener.getImage(WAIT_FOR_IMAGE_TIMEOUT_MS);
                    Plane[] imagePlanes = image.getPlanes();

                    //Verify
                    String fileName = DEBUG_FILE_NAME_BASE + width + "x" + height + "_"
                            + outputFrameCount + ".yuv";
                    validateImage(image, width, height, imageFormat, fileName);

                    if (VERBOSE) {
                        Log.v(TAG, "Image " + outputFrameCount + " Info:");
                        Log.v(TAG, "first plane pixelstride " + imagePlanes[0].getPixelStride());
                        Log.v(TAG, "first plane rowstride " + imagePlanes[0].getRowStride());
                        Log.v(TAG, "Image timestamp:" + image.getTimestamp());
                    }
                    image.close();
                }
            }
        }
    }

    private boolean imageFormatSupported(MediaCodec decoder, int imageFormat, String mime) {
        MediaCodecInfo codecInfo = decoder.getCodecInfo();
        if (codecInfo == null) {
            return false;
        }
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mime);
        for (int colorFormat : capabilities.colorFormats) {
            if (colorFormat == CodecCapabilities.COLOR_FormatYUV420Flexible
                    && imageFormat == ImageFormat.YUV_420_888) {
                return true;
            }
        }
        return false;
    }

    private MediaCodec createDecoder(String mime, boolean useHw) throws Exception {
        if (!useHw) {
            if (mime.contains("avc")) {
                return MediaCodec.createByCodecName("OMX.google.h264.decoder");
            } else if (mime.contains("3gpp")) {
                return MediaCodec.createByCodecName("OMX.google.h263.decoder");
            } else if (mime.contains("mp4v")) {
                return MediaCodec.createByCodecName("OMX.google.mpeg4.decoder");
            } else if (mime.contains("vp8")) {
                return MediaCodec.createByCodecName("OMX.google.vpx.decoder");
            }
        }
        return MediaCodec.createDecoderByType(mime);
    }

    /**
     * Validate image based on format and size.
     *
     * @param image The image to be validated.
     * @param width The image width.
     * @param height The image height.
     * @param format The image format.
     * @param filePath The debug dump file path, null if don't want to dump to file.
     */
    public static void validateImage(Image image, int width, int height, int format,
            String filePath) {
        assertNotNull("Input image is invalid", image);
        assertEquals("Format doesn't match", format, image.getFormat());
        assertEquals("Width doesn't match", width, image.getWidth());
        assertEquals("Height doesn't match", height, image.getHeight());

        if(VERBOSE) Log.v(TAG, "validating Image");
        byte[] data = getDataFromImage(image);
        assertTrue("Invalid image data", data != null && data.length > 0);

        validateYuvData(data, width, height, format, image.getTimestamp(), filePath);
    }

    private static void validateYuvData(byte[] yuvData, int width, int height, int format,
            long ts, String fileName) {

        assertTrue("YUV format must be one of the YUV_420_888, NV21, or YV12",
                format == ImageFormat.YUV_420_888 ||
                format == ImageFormat.NV21 ||
                format == ImageFormat.YV12);

        if (VERBOSE) Log.v(TAG, "Validating YUV data");
        int expectedSize = width * height * ImageFormat.getBitsPerPixel(format) / 8;
        assertEquals("Yuv data doesn't match", expectedSize, yuvData.length);

        if (DEBUG && fileName != null) {
            dumpFile(fileName, yuvData);
        }
    }

    private static void checkYuvFormat(int format) {
        if ((format != ImageFormat.YUV_420_888) &&
                (format != ImageFormat.NV21) &&
                (format != ImageFormat.YV12)) {
            fail("Wrong formats: " + format);
        }
    }
    /**
     * <p>Check android image format validity for an image, only support below formats:</p>
     *
     * <p>Valid formats are YUV_420_888/NV21/YV12 for video decoder</p>
     */
    private static void checkAndroidImageFormat(Image image) {
        int format = image.getFormat();
        Plane[] planes = image.getPlanes();
        switch (format) {
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
            case ImageFormat.YV12:
                assertEquals("YUV420 format Images should have 3 planes", 3, planes.length);
                break;
            default:
                fail("Unsupported Image Format: " + format);
        }
    }

    /**
     * Get a byte array image data from an Image object.
     * <p>
     * Read data from all planes of an Image into a contiguous unpadded,
     * unpacked 1-D linear byte array, such that it can be write into disk, or
     * accessed by software conveniently. It supports YUV_420_888/NV21/YV12
     * input Image format.
     * </p>
     * <p>
     * For YUV_420_888/NV21/YV12/Y8/Y16, it returns a byte array that contains
     * the Y plane data first, followed by U(Cb), V(Cr) planes if there is any
     * (xstride = width, ystride = height for chroma and luma components).
     * </p>
     */
    private static byte[] getDataFromImage(Image image) {
        assertNotNull("Invalid image:", image);
        int format = image.getFormat();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride, pixelStride;
        byte[] data = null;

        // Read image data
        Plane[] planes = image.getPlanes();
        assertTrue("Fail to get image planes", planes != null && planes.length > 0);

        // Check image validity
        checkAndroidImageFormat(image);

        ByteBuffer buffer = null;

        int offset = 0;
        data = new byte[width * height * ImageFormat.getBitsPerPixel(format) / 8];
        byte[] rowData = new byte[planes[0].getRowStride()];
        if(VERBOSE) Log.v(TAG, "get data from " + planes.length + " planes");
        for (int i = 0; i < planes.length; i++) {
            buffer = planes[i].getBuffer();
            assertNotNull("Fail to get bytebuffer from plane", buffer);
            rowStride = planes[i].getRowStride();
            pixelStride = planes[i].getPixelStride();
            assertTrue("pixel stride " + pixelStride + " is invalid", pixelStride > 0);
            if (VERBOSE) {
                Log.v(TAG, "pixelStride " + pixelStride);
                Log.v(TAG, "rowStride " + rowStride);
                Log.v(TAG, "width " + width);
                Log.v(TAG, "height " + height);
            }
            // For multi-planar yuv images, assuming yuv420 with 2x2 chroma subsampling.
            int w = (i == 0) ? width : width / 2;
            int h = (i == 0) ? height : height / 2;
            assertTrue("rowStride " + rowStride + " should be >= width " + w , rowStride >= w);
            for (int row = 0; row < h; row++) {
                int bytesPerPixel = ImageFormat.getBitsPerPixel(format) / 8;
                if (pixelStride == bytesPerPixel) {
                    // Special case: optimized read of the entire row
                    int length = w * bytesPerPixel;
                    buffer.get(data, offset, length);
                    // Advance buffer the remainder of the row stride
                    buffer.position(buffer.position() + rowStride - length);
                    offset += length;
                } else {
                    // Generic case: should work for any pixelStride but slower.
                    // Use intermediate buffer to avoid read byte-by-byte from
                    // DirectByteBuffer, which is very bad for performance
                    buffer.get(rowData, 0, rowStride);
                    for (int col = 0; col < w; col++) {
                        data[offset++] = rowData[col * pixelStride];
                    }
                }
            }
            if (VERBOSE) Log.v(TAG, "Finished reading data from plane " + i);
        }
        return data;
    }

    private static void dumpFile(String fileName, byte[] data) {
        assertNotNull("fileName must not be null", fileName);
        assertNotNull("data must not be null", data);

        FileOutputStream outStream;
        try {
            Log.v(TAG, "output will be saved as " + fileName);
            outStream = new FileOutputStream(fileName);
        } catch (IOException ioe) {
            throw new RuntimeException("Unable to create debug output file " + fileName, ioe);
        }

        try {
            outStream.write(data);
            outStream.close();
        } catch (IOException ioe) {
            throw new RuntimeException("failed writing data to file " + fileName, ioe);
        }
    }

    private void createImageReader(int width, int height, int format, int maxNumImages,
            ImageReader.OnImageAvailableListener listener) throws Exception {
        closeImageReader();

        mReader = ImageReader.newInstance(width, height, format, maxNumImages);
        mReaderSurface = mReader.getSurface();
        mReader.setOnImageAvailableListener(listener, mHandler);
        if (VERBOSE) {
            Log.v(TAG, String.format("Created ImageReader size (%dx%d), format %d", width, height,
                    format));
        }
    }

    /**
     * Close the pending images then close current active {@link ImageReader} object.
     */
    private void closeImageReader() {
        if (mReader != null) {
            try {
                // Close all possible pending images first.
                Image image = mReader.acquireLatestImage();
                if (image != null) {
                    image.close();
                }
            } finally {
                mReader.close();
                mReader = null;
            }
        }
    }
}
