/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.view.cts.surfacevalidator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaPlayer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.widget.FrameLayout;

import android.view.cts.R;

public class CapturedActivity extends Activity {
    public static class TestResult {
        public int passFrames;
        public int failFrames;
    }

    private static final String TAG = "CapturedActivity";
    private static final long TIME_OUT_MS = 10000;
    private static final int PERMISSION_CODE = 1;
    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;

    private SurfacePixelValidator mSurfacePixelValidator;
    private final Object mLock = new Object();

    private static final long START_CAPTURE_DELAY_MS = 1000;
    private static final long END_CAPTURE_DELAY_MS = START_CAPTURE_DELAY_MS + 4000;
    private static final long END_DELAY_MS = END_CAPTURE_DELAY_MS + 500;

    private MediaPlayer mMediaPlayer;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mOnWatch;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);

        mProjectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        startActivityForResult(mProjectionManager.createScreenCaptureIntent(), PERMISSION_CODE);

        mMediaPlayer = MediaPlayer.create(this, R.raw.colors_video);
        mMediaPlayer.setLooping(true);

        mOnWatch = getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
    }

    /**
     * MediaPlayer pre-loaded with a video with no black pixels. Be kind, rewind.
     */
    public MediaPlayer getMediaPlayer() {
        return mMediaPlayer;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PERMISSION_CODE) {
            throw new IllegalStateException("Unknown request code: " + requestCode);
        }
        if (resultCode != RESULT_OK) {
            throw new IllegalStateException("User denied screen sharing permission");
        }
        mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);
        mMediaProjection.registerCallback(new MediaProjectionCallback(), null);
    }

    public TestResult runTest(AnimationTestCase animationTestCase) {
        TestResult testResult = new TestResult();
        if (mOnWatch) {
            /**
             * Watch devices not supported, since they may not support:
             *    1) displaying unmasked windows
             *    2) RenderScript
             *    3) Video playback
             */
            Log.d(TAG, "Skipping test on watch.");
            testResult.passFrames = 1000;
            testResult.failFrames = 0;
            return testResult;
        }

        mHandler.post(() -> {
            Log.d(TAG, "Setting up test case");

            // shouldn't be necessary, since we've already done this in #create,
            // but ensure status/nav are hidden for test
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);

            animationTestCase.start(getApplicationContext(),
                    (FrameLayout) findViewById(android.R.id.content));
        });

        mHandler.postDelayed(() -> {
            Log.d(TAG, "Starting capture");

            Display display = getWindow().getDecorView().getDisplay();
            Point size = new Point();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealSize(size);
            display.getMetrics(metrics);

            mSurfacePixelValidator = new SurfacePixelValidator(CapturedActivity.this,
                    size, animationTestCase.getChecker());
            Log.d("MediaProjection", "Size is " + size.toString());
            mVirtualDisplay = mMediaProjection.createVirtualDisplay("ScreenSharingDemo",
                    size.x, size.y,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mSurfacePixelValidator.getSurface(),
                    null /*Callbacks*/,
                    null /*Handler*/);
        }, START_CAPTURE_DELAY_MS);

        mHandler.postDelayed(() -> {
            Log.d(TAG, "Stopping capture");
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }, END_CAPTURE_DELAY_MS);

        mHandler.postDelayed(() -> {
            Log.d(TAG, "Ending test case");
            animationTestCase.end();
            synchronized (mLock) {
                mSurfacePixelValidator.finish(testResult);
                mLock.notify();
            }
            mSurfacePixelValidator = null;
        }, END_DELAY_MS);

        synchronized (mLock) {
            try {
                mLock.wait(TIME_OUT_MS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        Log.d(TAG, "Test finished, passFrames " + testResult.passFrames
                + ", failFrames " + testResult.failFrames);
        return testResult;
    }

    private class MediaProjectionCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            Log.d(TAG, "MediaProjectionCallback#onStop");
            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
                mVirtualDisplay = null;
            }
        }
    }
}