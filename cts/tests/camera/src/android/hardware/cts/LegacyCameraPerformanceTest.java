/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.cts;

import android.app.Instrumentation;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.compatibility.common.util.DeviceReportLog;
import com.android.compatibility.common.util.ResultType;
import com.android.compatibility.common.util.ResultUnit;
import com.android.compatibility.common.util.Stat;

import java.util.Arrays;

/**
 * Measure and report legacy camera device performance.
 */
public class LegacyCameraPerformanceTest extends CameraTestCase {
    private static final String TAG = "CameraPerformanceTest";
    private static final String REPORT_LOG_NAME = "CtsCamera1TestCases";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    private Instrumentation mInstrumentation;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (mCamera != null) {
            mCamera.release();
            mCamera = null;
        }
    }

    public void testLegacyApiPerformance() throws Exception {
        final int NUM_TEST_LOOPS = 10;

        int nCameras = Camera.getNumberOfCameras();
        double[] avgCameraTakePictureTimes = new double[nCameras];

        for (int id = 0; id < nCameras; id++) {
            DeviceReportLog reportLog = new DeviceReportLog(REPORT_LOG_NAME,
                    "test_camera_takePicture");
            reportLog.addValue("camera_id", id, ResultType.NEUTRAL, ResultUnit.NONE);
            double[] cameraOpenTimes = new double[NUM_TEST_LOOPS];
            double[] startPreviewTimes = new double[NUM_TEST_LOOPS];
            double[] stopPreviewTimes = new double[NUM_TEST_LOOPS];
            double[] cameraCloseTimes = new double[NUM_TEST_LOOPS];
            double[] cameraTakePictureTimes = new double[NUM_TEST_LOOPS];
            double[] cameraAutoFocusTimes = new double[NUM_TEST_LOOPS];
            boolean afSupported = false;
            long openTimeMs, startPreviewTimeMs, stopPreviewTimeMs, closeTimeMs, takePictureTimeMs,
                 autofocusTimeMs;

            for (int i = 0; i < NUM_TEST_LOOPS; i++) {
                openTimeMs = SystemClock.elapsedRealtime();
                initializeMessageLooper(id);
                cameraOpenTimes[i] = SystemClock.elapsedRealtime() - openTimeMs;

                Parameters parameters = mCamera.getParameters();
                if (i == 0) {
                    for (String focusMode: parameters.getSupportedFocusModes()) {
                        if (Parameters.FOCUS_MODE_AUTO.equals(focusMode)) {
                            afSupported = true;
                            break;
                        }
                    }
                }

                if (afSupported) {
                    parameters.setFocusMode(Parameters.FOCUS_MODE_AUTO);
                    mCamera.setParameters(parameters);
                }

                SurfaceTexture previewTexture = new SurfaceTexture(/*random int*/ 1);
                mCamera.setPreviewTexture(previewTexture);
                startPreviewTimeMs = SystemClock.elapsedRealtime();
                startPreview();
                startPreviewTimes[i] = SystemClock.elapsedRealtime() - startPreviewTimeMs;

                if (afSupported) {
                    autofocusTimeMs = SystemClock.elapsedRealtime();
                    autoFocus();
                    cameraAutoFocusTimes[i] = SystemClock.elapsedRealtime() - autofocusTimeMs;
                }

                //Let preview run for a while
                Thread.sleep(1000);

                takePictureTimeMs = SystemClock.elapsedRealtime();
                takePicture();
                cameraTakePictureTimes[i] = SystemClock.elapsedRealtime() - takePictureTimeMs;

                //Resume preview after image capture
                startPreview();

                stopPreviewTimeMs = SystemClock.elapsedRealtime();
                mCamera.stopPreview();
                closeTimeMs = SystemClock.elapsedRealtime();
                stopPreviewTimes[i] = closeTimeMs - stopPreviewTimeMs;

                terminateMessageLooper();
                cameraCloseTimes[i] = SystemClock.elapsedRealtime() - closeTimeMs;
                previewTexture.release();
            }

            if (VERBOSE) {
                Log.v(TAG, "Camera " + id + " device open times(ms): "
                        + Arrays.toString(cameraOpenTimes)
                        + ". Average(ms): " + Stat.getAverage(cameraOpenTimes)
                        + ". Min(ms): " + Stat.getMin(cameraOpenTimes)
                        + ". Max(ms): " + Stat.getMax(cameraOpenTimes));
                Log.v(TAG, "Camera " + id + " start preview times(ms): "
                        + Arrays.toString(startPreviewTimes)
                        + ". Average(ms): " + Stat.getAverage(startPreviewTimes)
                        + ". Min(ms): " + Stat.getMin(startPreviewTimes)
                        + ". Max(ms): " + Stat.getMax(startPreviewTimes));
                if (afSupported) {
                    Log.v(TAG, "Camera " + id + " autofocus times(ms): "
                            + Arrays.toString(cameraAutoFocusTimes)
                            + ". Average(ms): " + Stat.getAverage(cameraAutoFocusTimes)
                            + ". Min(ms): " + Stat.getMin(cameraAutoFocusTimes)
                            + ". Max(ms): " + Stat.getMax(cameraAutoFocusTimes));
                }
                Log.v(TAG, "Camera " + id + " stop preview times(ms): "
                        + Arrays.toString(stopPreviewTimes)
                        + ". Average(ms): " + Stat.getAverage(stopPreviewTimes)
                        + ". Min(ms): " + Stat.getMin(stopPreviewTimes)
                        + ". Max(ms): " + Stat.getMax(stopPreviewTimes));
                Log.v(TAG, "Camera " + id + " device close times(ms): "
                        + Arrays.toString(cameraCloseTimes)
                        + ". Average(ms): " + Stat.getAverage(cameraCloseTimes)
                        + ". Min(ms): " + Stat.getMin(cameraCloseTimes)
                        + ". Max(ms): " + Stat.getMax(cameraCloseTimes));
                Log.v(TAG, "Camera " + id + " camera takepicture times(ms): "
                        + Arrays.toString(cameraTakePictureTimes)
                        + ". Average(ms): " + Stat.getAverage(cameraTakePictureTimes)
                        + ". Min(ms): " + Stat.getMin(cameraTakePictureTimes)
                        + ". Max(ms): " + Stat.getMax(cameraTakePictureTimes));
            }

            avgCameraTakePictureTimes[id] = Stat.getAverage(cameraTakePictureTimes);
            reportLog.addValues("camera_open_time", cameraOpenTimes, ResultType.LOWER_BETTER,
                    ResultUnit.MS);
            reportLog.addValues("camera_start_preview_time", startPreviewTimes,
                    ResultType.LOWER_BETTER, ResultUnit.MS);
            if (afSupported) {
                reportLog.addValues("camera_autofocus_time", cameraAutoFocusTimes,
                        ResultType.LOWER_BETTER, ResultUnit.MS);
            }
            reportLog.addValues("camera_stop_preview", stopPreviewTimes,
                    ResultType.LOWER_BETTER, ResultUnit.MS);
            reportLog.addValues("camera_close_time", cameraCloseTimes,
                    ResultType.LOWER_BETTER, ResultUnit.MS);
            reportLog.addValues("camera_takepicture_time", cameraTakePictureTimes,
                    ResultType.LOWER_BETTER, ResultUnit.MS);

            reportLog.submit(mInstrumentation);
        }

        if (nCameras != 0) {
            DeviceReportLog reportLog = new DeviceReportLog(REPORT_LOG_NAME,
                    "test_camera_takepicture_average");
            reportLog.setSummary("camera_takepicture_average_time_for_all_cameras",
                    Stat.getAverage(avgCameraTakePictureTimes), ResultType.LOWER_BETTER,
                    ResultUnit.MS);
            reportLog.submit(mInstrumentation);
        }
    }
}
