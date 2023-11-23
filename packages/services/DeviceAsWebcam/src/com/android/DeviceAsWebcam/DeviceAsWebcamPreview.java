/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.DeviceAsWebcam;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class DeviceAsWebcamPreview extends Activity {
    private static final String TAG = DeviceAsWebcamPreview.class.getSimpleName();
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    private static int MAX_PREVIEW_WIDTH = 1920;
    private static int MAX_PREVIEW_HEIGHT = 1080;
    private final Executor mThreadExecutor = Executors.newFixedThreadPool(2);
    private boolean mTextureViewSetup = false;
    private Size mMaxPreviewSize = new Size(MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT);
    private Size mPreviewSize;
    private FrameLayout mParentView;
    private TextureView mTextureView;
    private DeviceAsWebcamFgService mLocalFgService;
    private boolean mPreviewSurfaceRemoved = false;
    private SurfaceTexture mPreviewTexture;
    private ConditionVariable mServiceReady = new ConditionVariable();
    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener =
            new TextureView.SurfaceTextureListener() {

                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture texture, int width,
                        int height) {

                    if (VERBOSE) {
                        Log.v(TAG, "onSurfaceTextureAvailable " + width + " x " + height);
                    }
                    mServiceReady.block();
                    mPreviewSurfaceRemoved = false;

                    if (!mTextureViewSetup) {
                        setupTextureViewLayout();
                    }
                    if (mLocalFgService != null) {
                        mLocalFgService.setOnDestroyedCallback(() -> onServiceDestroyed());
                        mLocalFgService.setPreviewSurfaceTexture(texture);
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width,
                        int height) {
                    if (VERBOSE) {
                        Log.v(TAG, "onSurfaceTextureSizeChanged " + width + " x " + height);
                    }
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    if (mLocalFgService != null) {
                        mLocalFgService.removePreviewSurfaceTexture();
                    }
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                }
            };
    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mLocalFgService = ((DeviceAsWebcamFgService.LocalBinder) service).getService();
            if (VERBOSE) {
                Log.v(TAG, "Got Fg service ");
            }
            mServiceReady.open();
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            mLocalFgService = null;
            runOnUiThread(() -> finish());
        }
    };

    private void setTextureViewRotationAndScale() {
        int pWidth = mParentView.getWidth();
        int pHeight = mParentView.getHeight();
        float scaleYToUnstretched = (float) mPreviewSize.getWidth() / mPreviewSize.getHeight();
        float scaleXToUnstretched = (float) mPreviewSize.getHeight() / mPreviewSize.getWidth();
        float additionalScaleForX = (float) pWidth / mPreviewSize.getHeight();
        float additionalScaleForY = (float) pHeight / mPreviewSize.getWidth();

        // To fit the preview
        float additionalScaleChosen = Math.min(additionalScaleForX, additionalScaleForY);

        mTextureView.setScaleX(scaleXToUnstretched * additionalScaleChosen);
        mTextureView.setScaleY(scaleYToUnstretched * additionalScaleChosen);
    }

    private void setupTextureViewLayout() {
        mPreviewSize = mLocalFgService.getSuitablePreviewSize(mMaxPreviewSize);
        FrameLayout.LayoutParams frameLayout = new FrameLayout.LayoutParams(mPreviewSize.getWidth(),
                mPreviewSize.getHeight(), Gravity.CENTER);
        mTextureView.setLayoutParams(frameLayout);
        setTextureViewRotationAndScale();
    }

    private void onServiceDestroyed() {
        ConditionVariable cv = new ConditionVariable();
        cv.close();
        runOnUiThread(() -> {
            try {
                mLocalFgService = null;
                finish();
            } finally {
                cv.open();
            }
        });
        cv.block();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.preview_layout);
        mParentView = findViewById(R.id.container_view);
        mTextureView = new TextureView(this);
        mParentView.addView(mTextureView);
        bindService(new Intent(this, DeviceAsWebcamFgService.class), 0, mThreadExecutor,
                mConnection);
    }

    @Override
    public void onResume() {
        super.onResume();
        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {
            mServiceReady.block();
            if (!mTextureViewSetup) {
                setupTextureViewLayout();
                mTextureViewSetup = true;
            }
            mPreviewSurfaceRemoved = false;
            if (mLocalFgService != null) {
                mLocalFgService.setPreviewSurfaceTexture(mTextureView.getSurfaceTexture());
            }
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        if (mLocalFgService != null) {
            mLocalFgService.removePreviewSurfaceTexture();
        }
        super.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onDestroy() {
        if (mLocalFgService != null) {
            mLocalFgService.setOnDestroyedCallback(null);
        }
        unbindService(mConnection);
        super.onDestroy();
    }
}
