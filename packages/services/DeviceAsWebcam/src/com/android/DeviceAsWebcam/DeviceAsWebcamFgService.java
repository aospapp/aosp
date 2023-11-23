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

import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;

import androidx.core.app.NotificationCompat;

import com.android.DeviceAsWebcam.annotations.UsedByNative;

import java.lang.ref.WeakReference;
import java.util.Objects;

public class DeviceAsWebcamFgService extends Service {
    private static final String TAG = "DeviceAsWebcamFgService";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    static {
        System.loadLibrary("jni_deviceAsWebcam");
    }

    // Guards all methods in the service to ensure a consistent state while executing a method
    private final Object mServiceLock = new Object();
    private final IBinder mBinder = new LocalBinder();
    private Context mContext;
    private CameraController mCameraController;
    private Runnable mDestroyActivityCallback = null;
    private boolean mServiceRunning = false;



    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        synchronized (mServiceLock) {
            mContext = getApplicationContext();
            if (mContext == null) {
                Log.e(TAG, "Application context is null!, something is going to go wrong");
            }
            mCameraController = new CameraController(mContext, new WeakReference<>(this));
            int res = setupServicesAndStartListening();
            startForegroundWithNotification();
            // If `setupServiceAndStartListening` fails, we don't want to start the foreground
            // service. However, Android expects a call to `startForegroundWithNotification` in
            // `onStartCommand` and throws an exception if it isn't called. So, if the foreground
            // service should not be running, we call `startForegroundWithNotification` which starts
            // the service, and immediately call `stopSelf` which causes the service to be
            // torn down once `onStartCommand` returns.
            if (res != 0) {
                stopSelf();
            }
            mServiceRunning = true;
            return START_NOT_STICKY;
        }
    }

    private String createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel("WebcamService",
                "DeviceAsWebcamServiceFg", NotificationManager.IMPORTANCE_LOW);
        NotificationManager notMan = getSystemService(NotificationManager.class);
        Objects.requireNonNull(notMan).createNotificationChannel(channel);
        return "WebcamService";
    }

    private void startForegroundWithNotification() {
        Intent notificationIntent = new Intent(mContext, DeviceAsWebcamPreview.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, notificationIntent,
                PendingIntent.FLAG_MUTABLE);
        String channelId = createNotificationChannel();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId);
        Notification notif = builder.setOngoing(true).setPriority(
                NotificationManager.IMPORTANCE_DEFAULT).setCategory(
                Notification.CATEGORY_SERVICE).setContentIntent(pendingIntent).setSmallIcon(
                R.drawable.ic_root_webcam).build();
        startForeground(/* id= */ 1, notif);
    }

    private int setupServicesAndStartListening() {
        return setupServicesAndStartListeningNative();
    }

    @Override
    public void onDestroy() {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                return;
            }
            mServiceRunning = false;
            if (mDestroyActivityCallback != null) {
                mDestroyActivityCallback.run();
            }
            nativeOnDestroy();
            if (VERBOSE) {
                Log.v(TAG, "Destroyed fg service");
            }
        }
        super.onDestroy();
    }

    /**
     * Returns a suitable preview size <= the maxPreviewSize so there is no FoV change between
     * webcam and preview streams
     *
     * @param maxPreviewSize The upper limit of preview size
     */
    public Size getSuitablePreviewSize(Size maxPreviewSize) {
        synchronized (mServiceLock) {
            // TODO(b/267794640): Make this dynamic
            return new Size(1920, 1080);
        }
    }

    /**
     * Method to set a preview surface texture that camera will stream to. Should be of the size
     * returned by {@link #getSuitablePreviewSize}.
     *
     * @param surfaceTexture surfaceTexture to stream preview frames to
     */
    public void setPreviewSurfaceTexture(SurfaceTexture surfaceTexture) {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                Log.e(TAG, "setPreviewSurfaceTexture called after Service was destroyed.");
                return;
            }
            mCameraController.startPreviewStreaming(surfaceTexture);
        }
    }

    /**
     * Method to remove any preview SurfaceTexture set by {@link #setPreviewSurfaceTexture}.
     */
    public void removePreviewSurfaceTexture() {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                Log.e(TAG, "removePreviewSurfaceTexture was called after Service was destroyed.");
                return;
            }
            mCameraController.stopPreviewStreaming();
        }
    }

    /**
     * Method to setOnDestroyedCallback. This callback will be called when immediately before the
     * foreground service is destroyed. Intended to give and bound context a change to clean up
     * before the Service is destroyed. {@code setOnDestroyedCallback(null)} must be called to unset
     * the callback when a bound context finishes to prevent Context leak.
     * <p>
     * This callback must not call {@code setOnDestroyedCallback} from within the callback.
     *
     * @param callback callback to be called when the service is destroyed. {@code null} unsets
     *                 the callback
     */
    public void setOnDestroyedCallback(@Nullable Runnable callback) {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                Log.e(TAG, "setOnDestroyedCallback was called after Service was destroyed");
                return;
            }
            mDestroyActivityCallback = callback;
        }
    }

    @UsedByNative("DeviceAsWebcamNative.cpp")
    private void startStreaming() {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                Log.e(TAG, "startStreaming was called after Service was destroyed");
                return;
            }
            mCameraController.startWebcamStreaming();
        }
    }

    @UsedByNative("DeviceAsWebcamNative.cpp")
    private void stopService() {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                Log.e(TAG, "stopService was called after Service was destroyed");
                return;
            }
            stopSelf();
        }
    }

    @UsedByNative("DeviceAsWebcamNative.cpp")
    private void stopStreaming() {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                Log.e(TAG, "stopStreaming was called after Service was destroyed");
                return;
            }
            mCameraController.stopWebcamStreaming();
        }
    }

    @UsedByNative("DeviceAsWebcamNative.cpp")
    private void returnImage(long timestamp) {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                Log.e(TAG, "returnImage was called after Service was destroyed");
                return;
            }
            mCameraController.returnImage(timestamp);
        }
    }

    @UsedByNative("DeviceAsWebcamNative.cpp")
    private void setStreamConfig(boolean mjpeg, int width, int height, int fps) {
        synchronized (mServiceLock) {
            if (!mServiceRunning) {
                Log.e(TAG, "setStreamConfig was called after Service was destroyed");
                return;
            }
            mCameraController.setWebcamStreamConfig(mjpeg, width, height, fps);
        }
    }

    /**
     * Called by {@link DeviceAsWebcamReceiver} to check if the service should be started.
     * @return {@code true} if the foreground service should be started,
     *         {@code false} if the service is already running or should not be started
     */
    public static native boolean shouldStartServiceNative();

    /**
     * Called during {@link #onStartCommand} to initialize the native side of the service.
     * @return 0 if native side code was successfully initialized,
     *         non-0 otherwise
     */
    private native int setupServicesAndStartListeningNative();

    /**
     * Called by {@link CameraController} to queue frames for encoding. The frames are encoded
     * asynchronously. When encoding is done, the native code call {@link #returnImage} with the
     * {@code timestamp} passed here.
     * @param buffer buffer containing the frame to be encoded
     * @param timestamp timestamp associated with the buffer which uniquely identifies the buffer
     * @return 0 if buffer was successfully queued for encoding. non-0 otherwise.
     */
    public native int nativeEncodeImage(HardwareBuffer buffer, long timestamp);

    /**
     * Called by {@link #onDestroy} to give the JNI code a chance to clean up before the service
     * goes out of scope.
     */
    private native void nativeOnDestroy();


    /**
     * Simple class to hold a reference to {@link DeviceAsWebcamFgService} instance and have it be
     * accessible from {@link android.content.ServiceConnection#onServiceConnected} callback.
     */
    public class LocalBinder extends Binder {
        DeviceAsWebcamFgService getService() {
            return DeviceAsWebcamFgService.this;
        }
    }
}
