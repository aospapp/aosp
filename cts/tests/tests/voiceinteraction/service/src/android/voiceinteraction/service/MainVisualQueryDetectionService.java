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

package android.voiceinteraction.service;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.SharedMemory;
import android.service.voice.VisualQueryDetectionService;
import android.system.ErrnoException;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.voiceinteraction.common.Utils;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.IntConsumer;

import javax.annotation.concurrent.GuardedBy;

public class MainVisualQueryDetectionService extends VisualQueryDetectionService {
    static final String TAG = "MainVisualQueryDetectionService";

    public static final String PERCEPTION_MODULE_SUCCESS = "Perception module working";
    public static final String FAKE_QUERY_FIRST = "What is ";
    public static final String FAKE_QUERY_SECOND = "the weather today?";

    public static String KEY_VQDS_TEST_SCENARIO = "test scenario";

    public static int SCENARIO_TEST_PERCEPTION_MODULES = 0;
    public static int SCENARIO_ATTENTION_LEAVE = 1;
    public static int SCENARIO_ATTENTION_QUERY_REJECTED_LEAVE = 2;
    public static int SCENARIO_ATTENTION_QUERY_FINISHED_LEAVE = 3;
    public static int SCENARIO_ATTENTION_DOUBLE_QUERY_FINISHED_LEAVE = 4;
    public static int SCENARIO_QUERY_NO_ATTENTION = 5;
    public static int SCENARIO_QUERY_NO_QUERY_FINISH = 6;

    private int mScenario = -1;

    private final Object mLock = new Object();
    private Handler mHandler;

    private ImageReader mImageReader;
    private Handler mCameraBackgroundHandler;
    private HandlerThread mCameraBackgroundThread;

    @GuardedBy("mLock")
    private boolean mStopDetectionCalled;
    @GuardedBy("mLock")
    private int mDetectionDelayMs = 0;

    @GuardedBy("mLock")
    @Nullable
    private Runnable mDetectionJob;

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = Handler.createAsync(Looper.getMainLooper());
        Log.d(TAG, "onCreate");
    }

    @Override
    public void onStartDetection() {
        Log.d(TAG, "onStartDetection");

        startCameraBackgroundThread();

        synchronized (mLock) {
            if (mDetectionJob != null) {
                throw new IllegalStateException("onStartDetection called while already detecting");
            }
            if (!mStopDetectionCalled) {
                // Delaying this allows us to test other flows, such as stopping detection. It's
                // also more realistic to schedule it onto another thread.

                // Try different combinations of attention/query permutations with different
                // detection jobs.
                mDetectionJob = createTestDetectionJob(mScenario);
                mHandler.postDelayed(mDetectionJob, 1500);
            } else {
                Log.d(TAG, "Sending detected result after stop detection");
                // We can't store and use this callback in onStopDetection (not valid anymore
                // there), so we shut down the service.
                gainedAttention();
                streamQuery(FAKE_QUERY_SECOND);
                rejectQuery();
                lostAttention();
            }
        }
    }

    @Override
    public void onStopDetection() {
        super.onStopDetection();
        stopCameraBackgroundThread();
        Log.d(TAG, "onStopDetection");
        synchronized (mLock) {
            mHandler.removeCallbacks(mDetectionJob);
            mDetectionJob = null;
            mStopDetectionCalled = true;
        }
    }

    @Override
    public void onUpdateState(
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            long callbackTimeoutMillis,
            @Nullable IntConsumer statusCallback) {
        super.onUpdateState(options, sharedMemory, callbackTimeoutMillis, statusCallback);
        Log.d(TAG, "onUpdateState");

        // Reset mDetectionJob and mStopDetectionCalled when service is initializing.
        synchronized (mLock) {
            if (statusCallback != null) {
                if (mDetectionJob != null) {
                    Log.d(TAG, "onUpdateState mDetectionJob is not null");
                    mHandler.removeCallbacks(mDetectionJob);
                    mDetectionJob = null;
                }
                mStopDetectionCalled = false;
            }

            if (options != null) {
                mDetectionDelayMs = options.getInt(Utils.KEY_DETECTION_DELAY_MS, 0);
            }
        }

        if (options != null) {
            if (options.getInt(Utils.KEY_TEST_SCENARIO, -1)
                    == Utils.EXTRA_HOTWORD_DETECTION_SERVICE_ON_UPDATE_STATE_CRASH) {
                Log.d(TAG, "Crash itself. Pid: " + Process.myPid());
                Process.killProcess(Process.myPid());
                return;
            }
            mScenario = options.getInt(KEY_VQDS_TEST_SCENARIO);
        }

        if (sharedMemory != null) {
            try {
                sharedMemory.mapReadWrite();
                Log.d(TAG, "sharedMemory : is not read-only");
                return;
            } catch (ErrnoException e) {
                // For read-only case
            } finally {
                sharedMemory.close();
            }
        }

        // Report success
        Log.d(TAG, "onUpdateState success");
        if (statusCallback != null) {
            statusCallback.accept(INITIALIZATION_STATUS_SUCCESS);
        }
    }

    private Runnable createTestDetectionJob(int scenario) {
        Runnable detectionJob;

        if (scenario == SCENARIO_TEST_PERCEPTION_MODULES) {
            detectionJob = this::openCamera;
        } else if (scenario == SCENARIO_ATTENTION_LEAVE) {
            detectionJob = () -> {
                gainedAttention();
                lostAttention();
            };
        } else if (scenario == SCENARIO_ATTENTION_QUERY_FINISHED_LEAVE) {
            detectionJob = () -> {
                gainedAttention();
                streamQuery(FAKE_QUERY_FIRST);
                streamQuery(FAKE_QUERY_SECOND);
                finishQuery();
                lostAttention();
            };
        } else if (scenario == SCENARIO_ATTENTION_QUERY_REJECTED_LEAVE) {
            detectionJob = () -> {
                gainedAttention();
                streamQuery(FAKE_QUERY_FIRST);
                rejectQuery();
                lostAttention();
            };
        } else if (scenario == SCENARIO_ATTENTION_DOUBLE_QUERY_FINISHED_LEAVE) {
            detectionJob = () -> {
                gainedAttention();
                streamQuery(FAKE_QUERY_FIRST);
                finishQuery();
                streamQuery(FAKE_QUERY_SECOND);
                finishQuery();
                lostAttention();
            };
        } else if (scenario == SCENARIO_QUERY_NO_ATTENTION) {
            detectionJob = () -> {
                streamQuery(FAKE_QUERY_FIRST);
                finishQuery();
                rejectQuery();
            };
        } else if (scenario == SCENARIO_QUERY_NO_QUERY_FINISH) {
            detectionJob = () -> {
                gainedAttention();
                finishQuery();
                lostAttention();
            };
        } else {
            Log.i(TAG, "Do nothing...");
            return null;
        }
        return detectionJob;
    }

    private void sendCameraOpenSuccessSignals() {
        gainedAttention();
        streamQuery(PERCEPTION_MODULE_SUCCESS);
        finishQuery();
        lostAttention();
    }

    private void startCameraBackgroundThread() {
        mCameraBackgroundThread = new HandlerThread("Camera Background Thread");
        mCameraBackgroundThread.start();
        mCameraBackgroundHandler = new Handler(mCameraBackgroundThread.getLooper());
    }

    private void stopCameraBackgroundThread() {
        mCameraBackgroundThread.quitSafely();
        try {
            mCameraBackgroundThread.join();
            mCameraBackgroundThread = null;
            mCameraBackgroundHandler = null;
        } catch (InterruptedException e) {
            Log.e(TAG, "Failed to stop camera thread.");
        }
    }

    private void onReceiveImage(ImageReader reader) {
        Log.i(TAG, "Image received.");
        try (Image image = reader.acquireLatestImage()) {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            assertThat(buffer.capacity()).isGreaterThan(0);
            sendCameraOpenSuccessSignals();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void openCamera() {
        CameraManager manager = getSystemService(CameraManager.class);
        assert manager != null;
        try {
            // Check camera can be seen
            String cameraId = manager.getCameraIdList()[0]; //get front facing camera
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Size imageSize = characteristics.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(ImageFormat.JPEG)[0];
            initializeImageReader(imageSize.getWidth(), imageSize.getHeight());
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    // This is called when the camera is open
                    Log.i(TAG, "onCameraOpened");
                    // The camera data will be zero on virtual device, so it would be better to skip
                    // to check the camera data.
                    if (Utils.isVirtualDevice()) {
                        sendCameraOpenSuccessSignals();
                    }
                    createCameraPreview(camera);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                }
            }, mCameraBackgroundHandler);
        } catch (CameraAccessException e) {
            throw new IllegalStateException("Missing Camera access.");
        }
    }

    private void initializeImageReader(int width, int height) {
        // Initialize image reader
        mImageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2);
        ImageReader.OnImageAvailableListener readerListener = this::onReceiveImage;
        mImageReader.setOnImageAvailableListener(readerListener, mCameraBackgroundHandler);
    }

    private void createCameraPreview(CameraDevice cameraDevice) {
        try {
            CaptureRequest.Builder captureRequestBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            Surface imageSurface = mImageReader.getSurface();
            captureRequestBuilder.addTarget(imageSurface);
            cameraDevice.createCaptureSession(List.of(imageSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            updatePreview(captureRequestBuilder, cameraCaptureSession);
                            Log.i(TAG, "Capture session configured.");
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                            //No-op
                        }
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.i(TAG, "Camera preview created.");
    }

    private void updatePreview(CaptureRequest.Builder captureRequestBuilder,
            CameraCaptureSession cameraCaptureSession) {
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            cameraCaptureSession.capture(captureRequestBuilder.build(), null,
                    mCameraBackgroundHandler);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

