/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.google.android.car.evs;

import static android.hardware.display.DisplayManager.DisplayListener;

import android.app.Activity;
import android.car.Car;
import android.car.Car.CarServiceLifecycleListener;
import android.car.CarNotConnectedException;
import android.car.evs.CarEvsBufferDescriptor;
import android.car.evs.CarEvsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CarEvsCameraPreviewActivity extends Activity {
    private static final String TAG = CarEvsCameraPreviewActivity.class.getSimpleName();

    /** Buffer queue to store references of received frames */
    private final ArrayList<CarEvsBufferDescriptor> mBufferQueue = new ArrayList<>();

    private final Object mLock = new Object();

    /** Callback executors */
    private final ExecutorService mCallbackExecutor = Executors.newFixedThreadPool(1);

    /** GL backed surface view to render the camera preview */
    private CarEvsCameraGLSurfaceView mEvsView;
    private ViewGroup mRootView;
    private LinearLayout mPreviewContainer;

    /** Display manager to monitor the display's state */
    private DisplayManager mDisplayManager;

    /** Current display state */
    private int mDisplayState = Display.STATE_OFF;

    /** Tells whether or not a video stream is running */
    private boolean mStreamRunning = false;

    private Car mCar;
    private CarEvsManager mEvsManager;

    private IBinder mSessionToken;

    private boolean mUseSystemWindow;

    /** Callback to listen to EVS stream */
    private final CarEvsManager.CarEvsStreamCallback mStreamHandler =
            new CarEvsManager.CarEvsStreamCallback() {

        @Override
        public void onStreamEvent(int event) {
            // This reference implementation only monitors a stream event without any action.
            Log.i(TAG, "Received: " + event);
            if (event == CarEvsManager.STREAM_EVENT_STREAM_STOPPED ||
                event == CarEvsManager.STREAM_EVENT_TIMEOUT) {
                finish();
            }
        }

        @Override
        public void onNewFrame(CarEvsBufferDescriptor buffer) {
            // Enqueues a new frame and posts a rendering job
            synchronized (mBufferQueue) {
                mBufferQueue.add(buffer);
            }
        }
    };

    /**
     * The Activity with showWhenLocked doesn't go to sleep even if the display sleeps.
     * So we'd like to monitor the display state and react on it manually.
     */
    private final DisplayListener mDisplayListener = new DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY) {
                return;
            }
            int state = decideViewVisibility();
            synchronized (mLock) {
                mDisplayState = state;
                handleVideoStreamLocked();
            }
        }
    };

    /** CarService status listener  */
    private final CarServiceLifecycleListener mCarServiceLifecycleListener = (car, ready) -> {
        if (!ready) {
            Log.d(TAG, "Disconnected from the Car Service");
            // Upon the CarService's accidental termination, CarEvsService gets released and
            // CarEvsManager deregisters all listeners and callbacks.  So, we simply release
            // CarEvsManager instance and update the status in handleVideoStreamLocked().
            synchronized (mLock) {
                mCar = null;
                mEvsManager = null;
                handleVideoStreamLocked();
            }
        } else {
            Log.d(TAG, "Connected to the Car Service");
            try {
                synchronized (mLock) {
                    mCar = car;
                    mEvsManager = (CarEvsManager) car.getCarManager(Car.CAR_EVS_SERVICE);
                    handleVideoStreamLocked();
                }
            } catch (CarNotConnectedException err) {
                Log.e(TAG, "Failed to connect to the Car Service");
            }
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                finish();
            } else {
                Log.e(TAG, "Unexpected intent " + intent);
            }
        }
    };

    // To close the PreviewActiivty when Home button is clicked.
    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        // Need to register the receiver for all users, because we want to receive the Intent after
        // the user is changed.
        registerReceiverForAllUsers(mBroadcastReceiver, filter, /* broadcastPermission= */ null,
                /* scheduler= */ null, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);

        registerBroadcastReceiver();
        parseExtra(getIntent());

        setShowWhenLocked(true);
        mDisplayManager = getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener, null);
        int state = decideViewVisibility();
        synchronized (mLock) {
            mDisplayState = state;
        }

        Car.createCar(getApplicationContext(), /* handler = */ null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER, mCarServiceLifecycleListener);

        mEvsView = new CarEvsCameraGLSurfaceView(getApplication(), this);
        mRootView = (ViewGroup) LayoutInflater.from(this).inflate(
                R.layout.evs_preview_activity, /* root= */ null);
        mPreviewContainer = mRootView.findViewById(R.id.evs_preview_container);
        LinearLayout.LayoutParams viewParam = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.0f
        );
        mEvsView.setLayoutParams(viewParam);
        mPreviewContainer.addView(mEvsView, 0);
        View closeButton = mRootView.findViewById(R.id.close_button);
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> finish());
        }

        int width = WindowManager.LayoutParams.MATCH_PARENT;
        int height = WindowManager.LayoutParams.MATCH_PARENT;
        if (mUseSystemWindow) {
            width = getResources().getDimensionPixelOffset(R.dimen.camera_preview_width);
            height = getResources().getDimensionPixelOffset(R.dimen.camera_preview_height);
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height,
                2020 /* WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY */,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        params.dimAmount = getResources().getFloat(R.dimen.config_cameraBackgroundScrim);

        if (mUseSystemWindow) {
            WindowManager wm = getSystemService(WindowManager.class);
            wm.addView(mRootView, params);
        } else {
            setContentView(mRootView, params);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        parseExtra(intent);
    }

    private void parseExtra(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            mSessionToken = null;
            return;
        }
        mSessionToken = extras.getBinder(CarEvsManager.EXTRA_SESSION_TOKEN);
        mUseSystemWindow = mSessionToken != null;
    }

    @Override
    protected void onStart() {
        Log.d(TAG, "onStart");
        super.onStart();
        handleVideoStreamLocked();
    }


    @Override
    protected void onStop() {
        Log.d(TAG, "onStop");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        // Request to stop current service and unregister a status listener
        synchronized (mBufferQueue) {
            mBufferQueue.clear();
        }
        synchronized (mLock) {
            if (mEvsManager != null) {
                mEvsManager.stopVideoStream();
                mEvsManager.stopActivity();
                mEvsManager.clearStatusListener();
            }
            if (mCar != null) {
                mCar.disconnect();
            }
        }
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        if (mUseSystemWindow) {
            WindowManager wm = getSystemService(WindowManager.class);
            wm.removeView(mRootView);
        }

        unregisterReceiver(mBroadcastReceiver);
    }

    private void handleVideoStreamLocked() {
        if (mEvsManager == null) {
            Log.w(TAG, "CarEvsManager is not available.");
            return;
        }

        if (mDisplayState == Display.STATE_ON) {
            // We show a camera preview only when the activity has been resumed and the display is
            // on.
            if (!mStreamRunning) {
                Log.d(TAG, "Request to start a video stream");
                mEvsManager.startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW,
                        mSessionToken, mCallbackExecutor, mStreamHandler);
                mStreamRunning = true;
            }

            return;
        }

        // Otherwise, we do not need a video stream.
        if (mStreamRunning) {
            Log.d(TAG, "Request to stop a video stream");
            mEvsManager.stopVideoStream();
            mStreamRunning = false;

            // We already stopped an active video stream so are safe to drop all buffer references.
            synchronized (mBufferQueue) {
                mBufferQueue.clear();
            }

            // Clear a buffer reference CarEvsCameraGLSurfaceView holds.
            mEvsView.clearBuffer();
        }
    }

    // Hides the view when the display is off to save the system resource, since this has
    // 'showWhenLocked' attribute, this will not go to PAUSED state even if the display turns off.
    private int decideViewVisibility() {
        Display defaultDisplay = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        int state = defaultDisplay.getState();
        Log.d(TAG, "decideShowWhenLocked: displayState=" + state);
        if (state == Display.STATE_ON) {
            getWindow().getDecorView().setVisibility(View.VISIBLE);
        } else {
            getWindow().getDecorView().setVisibility(View.INVISIBLE);
        }

        return state;
    }

    /** Get a new frame */
    public CarEvsBufferDescriptor getNewFrame() {
        synchronized (mBufferQueue) {
            if (mBufferQueue.isEmpty()) {
                return null;
            }

            // The renderer refreshes faster than 30fps so it's okay to fetch the frame from the
            // front of the buffer queue always.
            CarEvsBufferDescriptor newFrame = mBufferQueue.get(0);
            mBufferQueue.remove(0);

            return newFrame;
        }
    }

    /** Request to return a buffer we're done with */
    public void returnBuffer(CarEvsBufferDescriptor buffer) {
        mEvsManager.returnFrameBuffer(buffer);
    }
}
