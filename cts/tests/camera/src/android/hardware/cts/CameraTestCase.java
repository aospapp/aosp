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

import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.ErrorCallback;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.PreviewCallback;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import junit.framework.TestCase;

public class CameraTestCase extends TestCase {
    private static final String TAG = "CameraTestCase";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    protected static final int NO_ERROR = -1;
    protected static final long WAIT_FOR_COMMAND_TO_COMPLETE_NS = 5000000000L;
    protected static final long WAIT_FOR_FOCUS_TO_COMPLETE_NS = 5000000000L;
    protected static final long WAIT_FOR_SNAPSHOT_TO_COMPLETE_NS = 5000000000L;
    protected Looper mLooper = null;

    protected int mCameraErrorCode;
    protected Camera mCamera;

    /**
     * Initializes the message looper so that the Camera object can
     * receive the callback messages.
     */
    protected void initializeMessageLooper(final int cameraId) throws InterruptedException {
        Lock startLock = new ReentrantLock();
        Condition startDone = startLock.newCondition();
        mCameraErrorCode = NO_ERROR;
        new Thread() {
            @Override
            public void run() {
                // Set up a looper to be used by camera.
                Looper.prepare();
                // Save the looper so that we can terminate this thread
                // after we are done with it.
                mLooper = Looper.myLooper();
                try {
                    mCamera = Camera.open(cameraId);
                    mCamera.setErrorCallback(new ErrorCallback() {
                        @Override
                        public void onError(int error, Camera camera) {
                            mCameraErrorCode = error;
                        }
                    });
                } catch (RuntimeException e) {
                    Log.e(TAG, "Fail to open camera." + e);
                }
                startLock.lock();
                startDone.signal();
                startLock.unlock();
                Looper.loop(); // Blocks forever until Looper.quit() is called.
                if (VERBOSE) Log.v(TAG, "initializeMessageLooper: quit.");
            }
        }.start();

        startLock.lock();
        try {
            if (startDone.awaitNanos(WAIT_FOR_COMMAND_TO_COMPLETE_NS) <= 0L) {
                fail("initializeMessageLooper: start timeout");
            }
        } finally {
            startLock.unlock();
        }

        assertNotNull("Fail to open camera.", mCamera);
    }

    /**
     * Terminates the message looper thread, optionally allowing evict error
     */
    protected void terminateMessageLooper() throws Exception {
        mLooper.quit();
        // Looper.quit() is asynchronous. The looper may still has some
        // preview callbacks in the queue after quit is called. The preview
        // callback still uses the camera object (setHasPreviewCallback).
        // After camera is released, RuntimeException will be thrown from
        // the method. So we need to join the looper thread here.
        mLooper.getThread().join();
        mCamera.release();
        mCamera = null;
        assertEquals("Got camera error callback.", NO_ERROR, mCameraErrorCode);
    }

    /**
     * Start preview and wait for the first preview callback, which indicates the
     * preview becomes active.
     */
    protected void startPreview() throws InterruptedException {
        Lock previewLock = new ReentrantLock();
        Condition previewDone = previewLock.newCondition();

        mCamera.setPreviewCallback(new PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] data, android.hardware.Camera camera) {
                previewLock.lock();
                previewDone.signal();
                previewLock.unlock();
            }
        });
        mCamera.startPreview();

        previewLock.lock();
        try {
            if (previewDone.awaitNanos(WAIT_FOR_COMMAND_TO_COMPLETE_NS) <= 0L) {
                fail("Preview done timeout");
            }
        } finally {
            previewLock.unlock();
        }

        mCamera.setPreviewCallback(null);
    }

    /**
     * Trigger and wait for autofocus to complete.
     */
    protected void autoFocus() throws InterruptedException {
        Lock focusLock = new ReentrantLock();
        Condition focusDone = focusLock.newCondition();

        mCamera.autoFocus(new AutoFocusCallback() {
            @Override
            public void onAutoFocus(boolean success, Camera camera) {
                focusLock.lock();
                focusDone.signal();
                focusLock.unlock();
            }
        });

        focusLock.lock();
        try {
            if (focusDone.awaitNanos(WAIT_FOR_FOCUS_TO_COMPLETE_NS) <= 0L) {
                fail("Autofocus timeout");
            }
        } finally {
            focusLock.unlock();
        }
    }

    /**
     * Trigger and wait for snapshot to finish.
     */
    protected void takePicture() throws InterruptedException {
        Lock snapshotLock = new ReentrantLock();
        Condition snapshotDone = snapshotLock.newCondition();

        mCamera.takePicture(/*shutterCallback*/ null, /*rawPictureCallback*/ null,
                new PictureCallback() {
            @Override
            public void onPictureTaken(byte[] rawData, Camera camera) {
                snapshotLock.lock();
                try {
                    if (rawData == null) {
                        fail("Empty jpeg data");
                    }
                    snapshotDone.signal();
                } finally {
                    snapshotLock.unlock();
                }
            }
        });

        snapshotLock.lock();
        try {
            if (snapshotDone.awaitNanos(WAIT_FOR_SNAPSHOT_TO_COMPLETE_NS) <= 0L) {
                fail("TakePicture timeout");
            }
        } finally {
            snapshotLock.unlock();
        }
    }

}
