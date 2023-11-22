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

package android.hardware.camera2.cts;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.cts.helpers.StaticMetadata;
import android.hardware.camera2.cts.testcases.Camera2AndroidTestCase;
import android.location.Location;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import static android.hardware.camera2.cts.CameraTestUtils.configureCameraSession;
import static android.hardware.camera2.cts.helpers.AssertHelpers.*;

/**
 * Tests for the DngCreator API.
 */
public class DngCreatorTest extends Camera2AndroidTestCase {
    private static final String TAG = "DngCreatorTest";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    private static final String DEBUG_DNG_FILE = "raw16.dng";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Test basic raw capture and DNG saving functionality for each of the available cameras.
     *
     * <p>
     * For each camera, capture a single RAW16 image at the first capture size reported for
     * the raw format on that device, and save that image as a DNG file.  No further validation
     * is done.
     * </p>
     *
     * <p>
     * Note: Enabling adb shell setprop log.tag.DngCreatorTest VERBOSE will also cause the
     * raw image captured for the first reported camera device to be saved to an output file.
     * </p>
     */
    public void testSingleImageBasic() throws Exception {
        for (int i = 0; i < mCameraIds.length; i++) {
            String deviceId = mCameraIds[i];
            ImageReader captureReader = null;
            FileOutputStream fileStream = null;
            ByteArrayOutputStream outputStream = null;
            try {
                openDevice(deviceId);

                if (!mStaticInfo.isCapabilitySupported(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                    Log.i(TAG, "RAW capability is not supported in camera " + mCameraIds[i] +
                            ". Skip the test.");
                    continue;
                }

                Size[] targetCaptureSizes =
                        mStaticInfo.getAvailableSizesForFormatChecked(ImageFormat.RAW_SENSOR,
                                StaticMetadata.StreamDirection.Output);

                assertTrue("No capture sizes available for RAW format!",
                        targetCaptureSizes.length != 0);
                Rect activeArray = mStaticInfo.getActiveArraySizeChecked();
                Size activeArraySize = new Size(activeArray.width(), activeArray.height());
                assertTrue("Missing ActiveArraySize", activeArray.width() > 0 &&
                        activeArray.height() > 0);
                // TODO: Allow PixelArraySize also.
                assertArrayContains("Available sizes for RAW format must include ActiveArraySize",
                        targetCaptureSizes, activeArraySize);

                // Create capture image reader
                CameraTestUtils.SimpleImageReaderListener captureListener
                        = new CameraTestUtils.SimpleImageReaderListener();
                captureReader = createImageReader(activeArraySize, ImageFormat.RAW_SENSOR, 2,
                        captureListener);
                Pair<Image, CaptureResult> resultPair = captureSingleRawShot(activeArraySize,
                        captureReader, captureListener);
                CameraCharacteristics characteristics = mStaticInfo.getCharacteristics();

                // Test simple writeImage, no header checks
                DngCreator dngCreator = new DngCreator(characteristics, resultPair.second);
                outputStream = new ByteArrayOutputStream();
                dngCreator.writeImage(outputStream, resultPair.first);

                if (VERBOSE) {
                    String filePath = DEBUG_FILE_NAME_BASE + "camera_" + deviceId + "_" +
                            DEBUG_DNG_FILE;
                    // Write out captured DNG file for the first camera device if setprop is enabled
                    fileStream = new FileOutputStream(filePath);
                    fileStream.write(outputStream.toByteArray());
                    fileStream.flush();
                    fileStream.close();
                    Log.v(TAG, "Test DNG file for camera " + deviceId + " saved to " + filePath);
                }
            } finally {
                closeDevice(deviceId);
                closeImageReader(captureReader);

                if (outputStream != null) {
                    outputStream.close();
                }

                if (fileStream != null) {
                    fileStream.close();
                }
            }
        }
    }

    /**
     * Test basic raw capture and DNG saving with a thumbnail, rotation, usercomment, and GPS tags
     * set.
     *
     * <p>
     * For each camera, capture a single RAW16 image at the first capture size reported for
     * the raw format on that device, and save that image as a DNG file.  No further validation
     * is done.
     * </p>
     *
     * <p>
     * Note: Enabling adb shell setprop log.tag.DngCreatorTest VERBOSE will also cause the
     * raw image captured for the first reported camera device to be saved to an output file.
     * </p>
     */
    public void testSingleImageThumbnail() throws Exception {
        for (int i = 0; i < mCameraIds.length; i++) {
            String deviceId = mCameraIds[i];
            List<ImageReader> captureReaders = new ArrayList<ImageReader>();
            List<CameraTestUtils.SimpleImageReaderListener> captureListeners =
                    new ArrayList<CameraTestUtils.SimpleImageReaderListener>();
            FileOutputStream fileStream = null;
            ByteArrayOutputStream outputStream = null;
            try {
                openDevice(deviceId);

                if (!mStaticInfo.isCapabilitySupported(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                    Log.i(TAG, "RAW capability is not supported in camera " + mCameraIds[i] +
                            ". Skip the test.");
                    continue;
                }

                Size[] targetCaptureSizes =
                        mStaticInfo.getAvailableSizesForFormatChecked(ImageFormat.RAW_SENSOR,
                                StaticMetadata.StreamDirection.Output);

                assertTrue("No capture sizes available for RAW format!",
                        targetCaptureSizes.length != 0);
                Rect activeArray = mStaticInfo.getActiveArraySizeChecked();
                Size activeArraySize = new Size(activeArray.width(), activeArray.height());
                assertTrue("Missing ActiveArraySize", activeArray.width() > 0 &&
                        activeArray.height() > 0);
                // TODO: Allow PixelArraySize also.
                assertArrayContains("Available sizes for RAW format must include ActiveArraySize",
                        targetCaptureSizes, activeArraySize);

                Size[] targetPreviewSizes =
                        mStaticInfo.getAvailableSizesForFormatChecked(ImageFormat.YUV_420_888,
                                StaticMetadata.StreamDirection.Output);
                // Get smallest preview size
                Size previewSize = mOrderedPreviewSizes.get(mOrderedPreviewSizes.size() - 1);

                // Create capture image reader
                CameraTestUtils.SimpleImageReaderListener captureListener
                        = new CameraTestUtils.SimpleImageReaderListener();
                captureReaders.add(createImageReader(activeArraySize, ImageFormat.RAW_SENSOR, 2,
                        captureListener));
                captureListeners.add(captureListener);

                CameraTestUtils.SimpleImageReaderListener previewListener
                        = new CameraTestUtils.SimpleImageReaderListener();

                captureReaders.add(createImageReader(previewSize, ImageFormat.YUV_420_888, 2,
                        previewListener));
                captureListeners.add(previewListener);

                Pair<List<Image>, CaptureResult> resultPair = captureSingleRawShot(activeArraySize,
                        captureReaders, captureListeners);
                CameraCharacteristics characteristics = mStaticInfo.getCharacteristics();

                // Test simple writeImage, no header checks
                DngCreator dngCreator = new DngCreator(characteristics, resultPair.second);
                Location l = new Location("test");
                l.reset();
                l.setLatitude(37.420016);
                l.setLongitude(-122.081987);
                l.setTime(System.currentTimeMillis());
                dngCreator.setLocation(l);

                dngCreator.setDescription("helloworld");
                dngCreator.setOrientation(ExifInterface.ORIENTATION_FLIP_VERTICAL);
                dngCreator.setThumbnail(resultPair.first.get(1));
                outputStream = new ByteArrayOutputStream();
                dngCreator.writeImage(outputStream, resultPair.first.get(0));

                if (VERBOSE) {
                    String filePath = DEBUG_FILE_NAME_BASE + "camera_" + deviceId + "_" +
                            DEBUG_DNG_FILE;
                    // Write out captured DNG file for the first camera device if setprop is enabled
                    fileStream = new FileOutputStream(filePath);
                    fileStream.write(outputStream.toByteArray());
                    fileStream.flush();
                    fileStream.close();
                    Log.v(TAG, "Test DNG file for camera " + deviceId + " saved to " + filePath);
                }
            } finally {
                closeDevice(deviceId);
                for (ImageReader r : captureReaders) {
                    closeImageReader(r);
                }

                if (outputStream != null) {
                    outputStream.close();
                }

                if (fileStream != null) {
                    fileStream.close();
                }
            }
        }
    }

    private Pair<Image, CaptureResult> captureSingleRawShot(Size s, ImageReader captureReader,
            CameraTestUtils.SimpleImageReaderListener captureListener) throws Exception {
        List<ImageReader> readers = new ArrayList<ImageReader>();
        readers.add(captureReader);
        List<CameraTestUtils.SimpleImageReaderListener> listeners =
                new ArrayList<CameraTestUtils.SimpleImageReaderListener>();
        listeners.add(captureListener);
        Pair<List<Image>, CaptureResult> res = captureSingleRawShot(s, readers, listeners);
        return new Pair<Image, CaptureResult>(res.first.get(0), res.second);
    }

    /**
     * Capture a single raw image.
     *
     * <p>Capture an raw image for a given size.</p>
     *
     * @param s The size of the raw image to capture.  Must be one of the available sizes for this
     *          device.
     * @return a pair containing the {@link Image} and {@link CaptureResult} used for this capture.
     */
    private Pair<List<Image>, CaptureResult> captureSingleRawShot(Size s, List<ImageReader> captureReaders,
            List<CameraTestUtils.SimpleImageReaderListener> captureListeners) throws Exception {
        if (VERBOSE) {
            Log.v(TAG, "captureSingleRawShot - Capturing raw image.");
        }

        Size maxYuvSz = mOrderedPreviewSizes.get(0);
        Size[] targetCaptureSizes =
                mStaticInfo.getAvailableSizesForFormatChecked(ImageFormat.RAW_SENSOR,
                        StaticMetadata.StreamDirection.Output);

        // Validate size
        boolean validSize = false;
        for (int i = 0; i < targetCaptureSizes.length; ++i) {
            if (targetCaptureSizes[i].equals(s)) {
                validSize = true;
                break;
            }
        }
        assertTrue("Capture size is supported.", validSize);


        // Capture images.
        List<Surface> outputSurfaces = new ArrayList<Surface>();
        for (ImageReader captureReader : captureReaders) {
            Surface captureSurface = captureReader.getSurface();
            outputSurfaces.add(captureSurface);
        }

        CaptureRequest.Builder request = prepareCaptureRequestForSurfaces(outputSurfaces);
        request.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
        CameraTestUtils.SimpleCaptureCallback resultListener =
                new CameraTestUtils.SimpleCaptureCallback();

        startCapture(request.build(), /*repeating*/false, resultListener, mHandler);

        // Verify capture result and images
        CaptureResult result = resultListener.getCaptureResult(CAPTURE_WAIT_TIMEOUT_MS);

        List<Image> resultImages = new ArrayList<Image>();
        for (CameraTestUtils.SimpleImageReaderListener captureListener : captureListeners) {
            Image captureImage = captureListener.getImage(CAPTURE_WAIT_TIMEOUT_MS);

            /*CameraTestUtils.validateImage(captureImage, s.getWidth(), s.getHeight(),
                    ImageFormat.RAW_SENSOR, null);*/
            resultImages.add(captureImage);
        }
        // Stop capture, delete the streams.
        stopCapture(/*fast*/false);

        return new Pair<List<Image>, CaptureResult>(resultImages, result);
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
}
