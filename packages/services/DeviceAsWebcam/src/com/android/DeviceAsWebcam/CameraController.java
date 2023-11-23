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

package com.android.DeviceAsWebcam;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * This class controls the operation of the camera - primarily through the public calls
 * - startPreviewStreaming
 * - startWebcamStreaming
 * - stopPreviewStreaming
 * - stopWebcamStreaming
 * These calls do what they suggest - that is start / stop preview and webcam streams. They
 * internally book-keep whether they need to start a preview stream alongside a webcam stream or
 * by itself, and vice-versa.
 * For the webcam stream, it delegates the job of interacting with the native service
 * code - used for encoding ImageReader image callbacks, to the Foreground service (it stores a weak
 * reference to the foreground service during construction).
 */
public class CameraController {
    private static final String TAG = "CameraController";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    private static final int NO_STREAMING = 0;
    private static final int WEBCAM_STREAMING = 1;
    private static final int PREVIEW_STREAMING = 2;
    private static final int PREVIEW_AND_WEBCAM_STREAMING = 3;

    private static final int MAX_BUFFERS = 4;

    private ImageReader mImgReader;
    private int mCurrentState = NO_STREAMING;
    private Context mContext;
    private WeakReference<DeviceAsWebcamFgService> mServiceWeak;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private HandlerThread mImageReaderThread;
    private Handler mImageReaderHandler;
    private ThreadPoolExecutor mThreadPoolExecutor;
    private Surface mPreviewSurface;
    private OutputConfiguration mPreviewOutputConfiguration;
    private OutputConfiguration mWebcamOutputConfiguration;
    private List<OutputConfiguration> mOutputConfigurations;
    private CameraCaptureSession mCaptureSession;
    private ConditionVariable mCameraOpened = new ConditionVariable();
    private ConditionVariable mCaptureSessionReady = new ConditionVariable();
    private AtomicBoolean mStartCaptureWebcamStream = new AtomicBoolean(false);
    private final Object mSerializationLock = new Object();
    // timestamp -> Image
    private ConcurrentHashMap<Long, ImageAndBuffer> mImageMap = new ConcurrentHashMap<>();
    private int mFps;
    // TODO(b/267794640): UI to select camera id
    private String mCameraId = "0"; // Default camera id.

    private CameraDevice.StateCallback mCameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            if (VERBOSE) {
                Log.v(TAG, "Camera device opened, creating capture session now");
            }
            mCameraDevice = cameraDevice;
            mCameraOpened.open();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
        }
    };
    private CameraCaptureSession.CaptureCallback mCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {};

    private CameraCaptureSession.StateCallback mCameraCaptureSessionCallback =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if (mCameraDevice == null) {
                        return;
                    }
                    mCaptureSession = cameraCaptureSession;
                    try {
                            mCaptureSession.setSingleRepeatingRequest(
                                    mPreviewRequestBuilder.build(), mThreadPoolExecutor,
                                    mCaptureCallback);

                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                    mCaptureSessionReady.open();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession captureSession) {
                }
            };

    private ImageReader.OnImageAvailableListener mOnImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    DeviceAsWebcamFgService service = mServiceWeak.get();
                    if (service == null) {
                        Log.e(TAG, "Service is dead, what ?");
                        return;
                    }
                    if (mImageMap.size() >= MAX_BUFFERS) {
                            Log.w(TAG, "Too many buffers acquired in onImageAvailable, returning");
                            return;
                    }
                    // Get native HardwareBuffer from the latest image and send it to
                    // the native layer for the encoder to process.
                    // Acquire latest Image and get the HardwareBuffer
                    Image image = reader.acquireLatestImage();
                    if (VERBOSE) {
                        Log.v(TAG, "Got acquired Image in onImageAvailable callback");
                    }
                    if (image == null) {
                        if (VERBOSE) {
                            Log.e(TAG, "More images than MAX acquired ?");
                        }
                        return;
                    }
                    long ts = image.getTimestamp();
                    HardwareBuffer hardwareBuffer = image.getHardwareBuffer();
                    mImageMap.put(ts, new ImageAndBuffer(image, hardwareBuffer));
                    // Callback into DeviceAsWebcamFgService to encode image
                    if ((!mStartCaptureWebcamStream.get()) ||
                            (service.nativeEncodeImage(hardwareBuffer, ts) != 0)) {
                        if (VERBOSE) {
                            Log.v(TAG,
                                    "Couldn't get buffer immediately, returning image images. "
                                            + "acquired size "
                                            + mImageMap.size());
                        }
                        returnImage(ts);
                        return;
                    }
                }
            };

    public CameraController(Context context, WeakReference<DeviceAsWebcamFgService> serviceWeak) {
        mContext = context;
        mServiceWeak = serviceWeak;
        if (mContext == null) {
            Log.e(TAG, "Application context is null!, something is going to go wrong");
            return;
        }
        startBackgroundThread();
        mCameraManager = mContext.getSystemService(CameraManager.class);
    }

    public void setWebcamStreamConfig(boolean mjpeg, int width, int height, int fps) {
        if (VERBOSE) {
            Log.v(TAG, "Set stream config service : mjpeg  ? " + mjpeg + " width" + width +
                    " height " + height + " fps " + fps);
        }
        synchronized (mSerializationLock) {
            long usage = HardwareBuffer.USAGE_CPU_READ_OFTEN;
            mFps = fps;
            mImgReader = new ImageReader.Builder(width, height)
                    .setMaxImages(MAX_BUFFERS)
                    .setDefaultHardwareBufferFormat(HardwareBuffer.YCBCR_420_888)
                    .setUsage(usage)
                    .build();
            mImgReader.setOnImageAvailableListener(mOnImageAvailableListener,
                    mImageReaderHandler);

        }
    }

    private void openCameraBlocking() {
        if (mCameraManager == null) {
            Log.e(TAG, "CameraManager is not initialized, aborting");
            return;
        }
        try {
            mCameraManager.openCamera(mCameraId, mThreadPoolExecutor, mCameraStateCallback);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        mCameraOpened.block();
        mCameraOpened.close();
        if (VERBOSE) {
            Log.v(TAG, "Camera" + mCameraId + " opened ");
        }
    }

    private void setupPreviewOnlyStreamLocked(SurfaceTexture previewSurfaceTexture) {
        mPreviewSurface = new Surface(previewSurfaceTexture);
        try {
            openCameraBlocking();
            mPreviewRequestBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            Range<Integer> fpsRange;
            if (mFps != 0) {
                fpsRange = new Range<>(mFps, mFps);
            } else {
                fpsRange = new Range<>(30, 30);
            }
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            mPreviewOutputConfiguration = new OutputConfiguration(mPreviewSurface);
            mPreviewRequestBuilder.addTarget(mPreviewSurface);
            // So that we don't have to reconfigure if / when the preview activity is turned off /
            // on again.

            mOutputConfigurations = Arrays.asList(mPreviewOutputConfiguration);
            createCaptureSessionBlocking();
            mCurrentState = PREVIEW_STREAMING;
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setupPreviewStreamAlongsideWebcamStreamLocked(
            SurfaceTexture previewSurfaceTexture) {
        if (VERBOSE) {
            Log.v(TAG, "setupPreviewAlongsideWebcam");
        }
        mPreviewSurface = new Surface(previewSurfaceTexture);
        mPreviewOutputConfiguration = new OutputConfiguration(mPreviewSurface);
        mPreviewRequestBuilder.addTarget(mPreviewSurface);
        mOutputConfigurations = Arrays.asList(mPreviewOutputConfiguration,
                mWebcamOutputConfiguration);
        createCaptureSessionBlocking();
        mCurrentState = PREVIEW_AND_WEBCAM_STREAMING;
    }

    public void startPreviewStreaming(SurfaceTexture surfaceTexture) {
        // Started on a background thread since we don't want to be blocking either the activity's
        // or the service's main thread (we call blocking camera open in these methods internally)
        mThreadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (mSerializationLock) {
                    switch (mCurrentState) {
                        case NO_STREAMING:
                            setupPreviewOnlyStreamLocked(surfaceTexture);
                            break;
                        case WEBCAM_STREAMING:
                            setupPreviewStreamAlongsideWebcamStreamLocked(surfaceTexture);
                            break;
                        case PREVIEW_STREAMING:
                        case PREVIEW_AND_WEBCAM_STREAMING:
                            Log.e(TAG, "Incorrect current state for startPreviewStreaming " +
                                    mCurrentState);
                            return;
                    }
                }
            }
        });
    }

    private void setupWebcamOnlyStreamAndOpenCameraLocked() {
        // Setup outputs
        if (VERBOSE) {
            Log.v(TAG, "setupWebcamOnly");
        }
        Surface surface = mImgReader.getSurface();
        try {
            openCameraBlocking();
            mPreviewRequestBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            Range fpsRange = new Range(mFps, mFps);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            mPreviewRequestBuilder.addTarget(surface);
            mWebcamOutputConfiguration = new OutputConfiguration(surface);
            mOutputConfigurations =
                    Arrays.asList(mWebcamOutputConfiguration);
            createCaptureSessionBlocking();
            mCurrentState = WEBCAM_STREAMING;
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setupWebcamStreamAndReconfigureSessionLocked() {
        // Setup outputs
        if (VERBOSE) {
            Log.v(TAG, "setupWebcamStreamAndReconfigureSession");
        }
        Surface surface = mImgReader.getSurface();
        mPreviewRequestBuilder.addTarget(surface);
        mWebcamOutputConfiguration = new OutputConfiguration(surface);
        mOutputConfigurations =
                Arrays.asList(mWebcamOutputConfiguration, mPreviewOutputConfiguration);
        createCaptureSessionBlocking();
        mCurrentState = PREVIEW_AND_WEBCAM_STREAMING;
    }

    public void startWebcamStreaming() {
        // Started on a background thread since we don't want to be blocking the service's main
        // thread (we call blocking camera open in these methods internally)
        mThreadPoolExecutor.execute(() -> {
            mStartCaptureWebcamStream.set(true);
            synchronized (mSerializationLock) {
                if (mImgReader == null) {
                    Log.e(TAG,
                            "Webcam streaming requested without ImageReader initialized");
                    return;
                }
                switch (mCurrentState) {
                    case NO_STREAMING:
                        setupWebcamOnlyStreamAndOpenCameraLocked();
                        break;
                    case PREVIEW_STREAMING:
                        // Its okay to recreate an already running camera session with
                        // preview since the 'glitch' that we see will not be on the webcam
                        // stream.
                        setupWebcamStreamAndReconfigureSessionLocked();
                        break;
                    case PREVIEW_AND_WEBCAM_STREAMING:
                    case WEBCAM_STREAMING:
                        Log.e(TAG, "Incorrect current state for startWebcamStreaming "
                                + mCurrentState);
                        return;
                }
            }
        });
    }

    private void stopPreviewStreamOnlyLocked() {
        mPreviewRequestBuilder.removeTarget(mPreviewSurface);
        mOutputConfigurations = Arrays.asList(mWebcamOutputConfiguration);
        createCaptureSessionBlocking();
        mPreviewSurface = null;
        mCurrentState = WEBCAM_STREAMING;
    }

    public void stopPreviewStreaming() {
        // Started on a background thread since we don't want to be blocking either the activity's
        // or the service's main thread (we call blocking camera open in these methods internally)
        mThreadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (mSerializationLock) {
                    switch (mCurrentState) {
                        case PREVIEW_AND_WEBCAM_STREAMING:
                            stopPreviewStreamOnlyLocked();
                            break;
                        case PREVIEW_STREAMING:
                            stopStreamingAltogetherLocked();
                            break;
                        case NO_STREAMING:
                        case WEBCAM_STREAMING:
                            Log.e(TAG,
                                    "Incorrect current state for stopPreviewStreaming " +
                                            mCurrentState);
                            return;
                    }
                }
            }
        });
    }

    private void stopWebcamStreamOnlyLocked() {
        // Re-configure session to have only the preview stream
        // Setup outputs
        mPreviewRequestBuilder.removeTarget(mImgReader.getSurface());
        mOutputConfigurations =
                Arrays.asList(mPreviewOutputConfiguration);
        createCaptureSessionBlocking();
        mWebcamOutputConfiguration = null;
        mCurrentState = PREVIEW_STREAMING;
    }

    private void stopStreamingAltogetherLocked() {
        if (VERBOSE) {
            Log.v(TAG, "StopStreamingAltogether");
        }
        if (mImgReader != null) {
            mImgReader.close();
        }
        mCameraDevice.close();
        mCameraDevice = null;
        mImgReader = null;
        mWebcamOutputConfiguration = null;
        mPreviewOutputConfiguration = null;
        mCurrentState = NO_STREAMING;
    }

    public void stopWebcamStreaming() {
        // Started on a background thread since we don't want to be blocking the service's main
        // thread (we call blocking camera open in these methods internally)
        mThreadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (mSerializationLock) {
                    switch (mCurrentState) {
                        case PREVIEW_AND_WEBCAM_STREAMING:
                            stopWebcamStreamOnlyLocked();
                            break;
                        case WEBCAM_STREAMING:
                            stopStreamingAltogetherLocked();
                            break;
                        case PREVIEW_STREAMING:
                        case NO_STREAMING:
                            Log.e(TAG,
                                    "Incorrect current state for stopWebcamStreaming " +
                                            mCurrentState);
                            return;
                    }
                }
                mStartCaptureWebcamStream.set(false);
            }
        });
    }

    private void startBackgroundThread() {
        mImageReaderThread = new HandlerThread("SdkCameraFrameProviderThread");
        mImageReaderThread.start();
        mImageReaderHandler = new Handler(mImageReaderThread.getLooper());
        // We need two handler threads since the surface texture add / remove calls from the fg
        // service are going to be served on the main thread. To not wait on capture session
        // creation, onCaptureSequenceCompleted we need a new thread to cater to preview surface
        // addition / removal.

        mThreadPoolExecutor =
                new ThreadPoolExecutor(/*initial pool size*/ 2, /*Max pool size*/2,
                        /*Alive time*/60, /*units*/TimeUnit.SECONDS, new LinkedBlockingQueue());
        mThreadPoolExecutor.allowCoreThreadTimeOut(true);
    }

    private void createCaptureSessionBlocking() {
        try {
            mCameraDevice.createCaptureSession(
                    new SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR, mOutputConfigurations,
                            mThreadPoolExecutor, mCameraCaptureSessionCallback));
            mCaptureSessionReady.block();
            mCaptureSessionReady.close();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void returnImage(long timestamp) {
        ImageAndBuffer imageAndBuffer = mImageMap.remove(timestamp);
        if (imageAndBuffer == null) {
            Log.e(TAG, "Image with timestamp " + timestamp +
                    " was never encoded / already returned");
            return;
        }
        imageAndBuffer.buffer.close();
        imageAndBuffer.image.close();
        if (VERBOSE) {
            Log.v(TAG, "Returned image " + timestamp);
        }
    }

    private static class ImageAndBuffer {
        public Image image;
        public HardwareBuffer buffer;
        public ImageAndBuffer(Image i, HardwareBuffer b) {
            image = i;
            buffer = b;
        }
    }
}
