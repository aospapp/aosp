/*
 * Copyright 2013 The Android Open Source Project
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

package android.hardware.camera2.cts;

import static android.hardware.camera2.cts.CameraTestUtils.*;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Size;
import android.hardware.camera2.cts.helpers.StaticMetadata;
import android.hardware.camera2.cts.testcases.Camera2AndroidTestCase;
import android.media.Image;
import android.media.ImageReader;
import android.os.ConditionVariable;
import android.util.Log;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Basic test for ImageReader APIs. It uses CameraDevice as producer, camera
 * sends the data to the surface provided by imageReader. Below image formats
 * are tested:</p>
 *
 * <p>YUV_420_888: flexible YUV420, it is mandatory format for camera. </p>
 * <p>JPEG: used for JPEG still capture, also mandatory format. </p>
 * <p>Some invalid access test. </p>
 * <p>TODO: Add more format tests? </p>
 */
public class ImageReaderTest extends Camera2AndroidTestCase {
    private static final String TAG = "ImageReaderTest";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    // number of frame (for streaming requests) to be verified.
    private static final int NUM_FRAME_VERIFIED = 2;
    // Max number of images can be accessed simultaneously from ImageReader.
    private static final int MAX_NUM_IMAGES = 5;

    private SimpleImageListener mListener;

    @Override
    public void setContext(Context context) {
        super.setContext(context);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testFlexibleYuv() throws Exception {
        for (String id : mCameraIds) {
            try {
                Log.i(TAG, "Testing Camera " + id);
                openDevice(id);
                bufferFormatTestByCamera(ImageFormat.YUV_420_888, /*repeating*/true);
            } finally {
                closeDevice(id);
            }
        }
    }

    public void testJpeg() throws Exception {
        for (String id : mCameraIds) {
            try {
                Log.v(TAG, "Testing jpeg capture for Camera " + id);
                openDevice(id);
                if (mStaticInfo.isHardwareLevelLegacy()) {
                    Log.i(TAG, "Skipping test on legacy devices");
                    continue;
                }
                bufferFormatTestByCamera(ImageFormat.JPEG, /*repeating*/false);
            } finally {
                closeDevice(id);
            }
        }
    }

    public void testRaw() throws Exception {
        for (String id : mCameraIds) {
            try {
                Log.v(TAG, "Testing raw capture for camera " + id);
                openDevice(id);

                bufferFormatTestByCamera(ImageFormat.RAW_SENSOR, /*repeating*/false);
            } finally {
                closeDevice(id);
            }
        }
    }

    public void testRepeatingJpeg() throws Exception {
        for (String id : mCameraIds) {
            try {
                Log.v(TAG, "Testing repeating jpeg capture for Camera " + id);
                openDevice(id);
                if (mStaticInfo.isHardwareLevelLegacy()) {
                    Log.i(TAG, "Skipping test on legacy devices");
                    continue;
                }
                bufferFormatTestByCamera(ImageFormat.JPEG, /*repeating*/true);
            } finally {
                closeDevice(id);
            }
        }
    }

    public void testRepeatingRaw() throws Exception {
        for (String id : mCameraIds) {
            try {
                Log.v(TAG, "Testing repeating raw capture for camera " + id);
                openDevice(id);

                bufferFormatTestByCamera(ImageFormat.RAW_SENSOR, /*repeating*/true);
            } finally {
                closeDevice(id);
            }
        }
    }

    public void testInvalidAccessTest() {
        // TODO: test invalid access case, see if we can receive expected
        // exceptions
    }

    /**
     * Test two image stream (YUV420_888 and JPEG) capture by using ImageReader.
     *
     * <p>Both stream formats are mandatory for Camera2 API</p>
     */
    public void testYuvAndJpeg() throws Exception {
        for (String id : mCameraIds) {
            try {
                Log.v(TAG, "YUV and JPEG testing for camera " + id);
                openDevice(id);
                if (mStaticInfo.isHardwareLevelLegacy()) {
                    Log.i(TAG, "Skipping test on legacy devices");
                    continue;
                }
                bufferFormatWithYuvTestByCamera(ImageFormat.JPEG);
            } finally {
                closeDevice(id);
            }
        }
    }

    /**
     * Test two image stream (YUV420_888 and RAW_SENSOR) capture by using ImageReader.
     *
     */
    public void testImageReaderYuvAndRaw() throws Exception {
        for (String id : mCameraIds) {
            try {
                Log.v(TAG, "YUV and RAW testing for camera " + id);
                openDevice(id);

                bufferFormatWithYuvTestByCamera(ImageFormat.RAW_SENSOR);
            } finally {
                closeDevice(id);
            }
        }
    }


    /**
     * Test capture a given format stream with yuv stream simultaneously.
     *
     * <p>Use fixed yuv size, varies targeted format capture size. Single capture is tested.</p>
     *
     * @param format The capture format to be tested along with yuv format.
     */
    private void bufferFormatWithYuvTestByCamera(int format) throws Exception {
        if (format != ImageFormat.JPEG && format != ImageFormat.RAW_SENSOR
                && format != ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException("Unsupported format: " + format);
        }

        final int NUM_SINGLE_CAPTURE_TESTED = MAX_NUM_IMAGES - 1;
        Size maxYuvSz = mOrderedPreviewSizes.get(0);
        Size[] targetCaptureSizes = mStaticInfo.getAvailableSizesForFormatChecked(format,
                StaticMetadata.StreamDirection.Output);

        for (Size captureSz : targetCaptureSizes) {
            if (VERBOSE) {
                Log.v(TAG, "Testing yuv size " + maxYuvSz.toString() + " and capture size "
                        + captureSz.toString() + " for camera " + mCamera.getId());
            }

            ImageReader captureReader = null;
            ImageReader yuvReader = null;
            try {
                // Create YUV image reader
                SimpleImageReaderListener yuvListener  = new SimpleImageReaderListener();
                yuvReader = createImageReader(maxYuvSz, ImageFormat.YUV_420_888, MAX_NUM_IMAGES,
                        yuvListener);
                Surface yuvSurface = yuvReader.getSurface();

                // Create capture image reader
                SimpleImageReaderListener captureListener = new SimpleImageReaderListener();
                captureReader = createImageReader(captureSz, format, MAX_NUM_IMAGES,
                        captureListener);
                Surface captureSurface = captureReader.getSurface();

                // Capture images.
                List<Surface> outputSurfaces = new ArrayList<Surface>();
                outputSurfaces.add(yuvSurface);
                outputSurfaces.add(captureSurface);
                CaptureRequest.Builder request = prepareCaptureRequestForSurfaces(outputSurfaces);
                SimpleCaptureCallback resultListener = new SimpleCaptureCallback();

                for (int i = 0; i < NUM_SINGLE_CAPTURE_TESTED; i++) {
                    startCapture(request.build(), /*repeating*/false, resultListener, mHandler);
                }

                // Verify capture result and images
                for (int i = 0; i < NUM_SINGLE_CAPTURE_TESTED; i++) {
                    resultListener.getCaptureResult(CAPTURE_WAIT_TIMEOUT_MS);
                    if (VERBOSE) {
                        Log.v(TAG, " Got the capture result back for " + i + "th capture");
                    }

                    Image yuvImage = yuvListener.getImage(CAPTURE_WAIT_TIMEOUT_MS);
                    if (VERBOSE) {
                        Log.v(TAG, " Got the yuv image back for " + i + "th capture");
                    }

                    Image captureImage = captureListener.getImage(CAPTURE_WAIT_TIMEOUT_MS);
                    if (VERBOSE) {
                        Log.v(TAG, " Got the capture image back for " + i + "th capture");
                    }

                    //Validate captured images.
                    CameraTestUtils.validateImage(yuvImage, maxYuvSz.getWidth(),
                            maxYuvSz.getHeight(), ImageFormat.YUV_420_888, /*filePath*/null);
                    CameraTestUtils.validateImage(captureImage, captureSz.getWidth(),
                            captureSz.getHeight(), format, /*filePath*/null);
                }

                // Stop capture, delete the streams.
                stopCapture(/*fast*/false);
            } finally {
                closeImageReader(captureReader);
                captureReader = null;
                closeImageReader(yuvReader);
                yuvReader = null;
            }
        }
    }

    private void bufferFormatTestByCamera(int format, boolean repeating) throws Exception {

        Size[] availableSizes = mStaticInfo.getAvailableSizesForFormatChecked(format,
                StaticMetadata.StreamDirection.Output);

        // for each resolution, test imageReader:
        for (Size sz : availableSizes) {
            try {
                if (VERBOSE) {
                    Log.v(TAG, "Testing size " + sz.toString() + " format " + format
                            + " for camera " + mCamera.getId());
                }

                // Create ImageReader.
                mListener  = new SimpleImageListener();
                createDefaultImageReader(sz, format, MAX_NUM_IMAGES, mListener);

                // Start capture.
                CaptureRequest request = prepareCaptureRequest();
                SimpleCaptureCallback listener = new SimpleCaptureCallback();
                startCapture(request, repeating, listener, mHandler);

                int numFrameVerified = repeating ? NUM_FRAME_VERIFIED : 1;

                // Validate images.
                validateImage(sz, format, numFrameVerified, repeating);

                // Validate capture result.
                validateCaptureResult(format, sz, listener, numFrameVerified);

                // stop capture.
                stopCapture(/*fast*/false);
            } finally {
                closeDefaultImageReader();
            }

        }
    }

    /**
     * Validate capture results.
     *
     * @param format The format of this capture.
     * @param size The capture size.
     * @param listener The capture listener to get capture result callbacks.
     */
    private void validateCaptureResult(int format, Size size, SimpleCaptureCallback listener,
            int numFrameVerified) {
        for (int i = 0; i < numFrameVerified; i++) {
            CaptureResult result = listener.getCaptureResult(CAPTURE_RESULT_TIMEOUT_MS);

            // TODO: Update this to use availableResultKeys once shim supports this.
            if (mStaticInfo.isCapabilitySupported(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
                Long exposureTime = getValueNotNull(result, CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer sensitivity = getValueNotNull(result, CaptureResult.SENSOR_SENSITIVITY);
                mCollector.expectInRange(
                        String.format(
                                "Capture for format %d, size %s exposure time is invalid.",
                                format, size.toString()),
                        exposureTime,
                        mStaticInfo.getExposureMinimumOrDefault(),
                        mStaticInfo.getExposureMaximumOrDefault()
                );
                mCollector.expectInRange(
                        String.format("Capture for format %d, size %s sensitivity is invalid.",
                                format, size.toString()),
                        sensitivity,
                        mStaticInfo.getSensitivityMinimumOrDefault(),
                        mStaticInfo.getSensitivityMaximumOrDefault()
                );
            }
            // TODO: add more key validations.
        }
    }

    private final class SimpleImageListener implements ImageReader.OnImageAvailableListener {
        private final ConditionVariable imageAvailable = new ConditionVariable();
        @Override
        public void onImageAvailable(ImageReader reader) {
            if (mReader != reader) {
                return;
            }

            if (VERBOSE) Log.v(TAG, "new image available");
            imageAvailable.open();
        }

        public void waitForAnyImageAvailable(long timeout) {
            if (imageAvailable.block(timeout)) {
                imageAvailable.close();
            } else {
                fail("wait for image available timed out after " + timeout + "ms");
            }
        }

        public void closePendingImages() {
            Image image = mReader.acquireLatestImage();
            if (image != null) {
                image.close();
            }
        }
    }

    private CaptureRequest prepareCaptureRequest() throws Exception {
        List<Surface> outputSurfaces = new ArrayList<Surface>();
        Surface surface = mReader.getSurface();
        assertNotNull("Fail to get surface from ImageReader", surface);
        outputSurfaces.add(surface);
        return prepareCaptureRequestForSurfaces(outputSurfaces).build();
    }

    private CaptureRequest.Builder prepareCaptureRequestForSurfaces(List<Surface> surfaces)
            throws Exception {
        createSession(surfaces);

        CaptureRequest.Builder captureBuilder =
                mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        assertNotNull("Fail to get captureRequest", captureBuilder);
        for (Surface surface : surfaces) {
            captureBuilder.addTarget(surface);
        }

        return captureBuilder;
    }

    private void validateImage(Size sz, int format, int captureCount,  boolean repeating)
            throws Exception {
        // TODO: Add more format here, and wrap each one as a function.
        Image img;
        final int MAX_RETRY_COUNT = 20;
        int numImageVerified = 0;
        int reTryCount = 0;
        while (numImageVerified < captureCount) {
            assertNotNull("Image listener is null", mListener);
            if (VERBOSE) Log.v(TAG, "Waiting for an Image");
            mListener.waitForAnyImageAvailable(CAPTURE_WAIT_TIMEOUT_MS);
            if (repeating) {
                /**
                 * Acquire the latest image in case the validation is slower than
                 * the image producing rate.
                 */
                img = mReader.acquireLatestImage();
                /**
                 * Sometimes if multiple onImageAvailable callbacks being queued,
                 * acquireLatestImage will clear all buffer before corresponding callback is
                 * executed. Wait for a new frame in that case.
                 */
                if (img == null && reTryCount < MAX_RETRY_COUNT) {
                    reTryCount++;
                    continue;
                }
            } else {
                img = mReader.acquireNextImage();
            }
            assertNotNull("Unable to acquire the latest image", img);
            if (VERBOSE) Log.v(TAG, "Got the latest image");
            CameraTestUtils.validateImage(img, sz.getWidth(), sz.getHeight(), format,
                    DEBUG_FILE_NAME_BASE);
            if (VERBOSE) Log.v(TAG, "finish vaildation of image " + numImageVerified);
            img.close();
            numImageVerified++;
            reTryCount = 0;
        }

        // Return all pending images to the ImageReader as the validateImage may
        // take a while to return and there could be many images pending.
        mListener.closePendingImages();
    }
}
