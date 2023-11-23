/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.media.cts;


import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.support.test.uiautomator.UiScrollable;
import android.support.test.uiautomator.UiSelector;
import android.support.test.uiautomator.Until;
import android.util.Log;
import android.view.WindowManager;

import androidx.test.InstrumentationRegistry;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


// This is a partial copy of android.view.cts.surfacevalidator.CapturedActivity.
// Common code should be move in a shared library
/** Start this activity to retrieve a MediaProjection through waitForMediaProjection() */
public class MediaProjectionActivity extends Activity {
    private static final String TAG = "MediaProjectionActivity";
    private static final int PERMISSION_CODE = 1;
    private static final int PERMISSION_DIALOG_WAIT_MS = 1000;
    private static final String ACCEPT_RESOURCE_ID = "android:id/button1";

    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private CountDownLatch mCountDownLatch;
    private boolean mProjectionServiceBound;

    private int mResultCode;
    private Intent mResultData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // UI automator need the screen ON in dismissPermissionDialog()
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mProjectionManager = getSystemService(MediaProjectionManager.class);
        mCountDownLatch = new CountDownLatch(1);
        startActivityForResult(mProjectionManager.createScreenCaptureIntent(), PERMISSION_CODE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mProjectionServiceBound) {
            mProjectionServiceBound = false;
        }
    }

    protected Intent getScreenCaptureIntent() {
        return mProjectionManager.createScreenCaptureIntent();
    }

    /**
     * Request to start a foreground service with type "mediaProjection",
     * it's free to run in either the same process or a different process in the package;
     * passing a messenger object to send signal back when the foreground service is up.
     */
    private void startMediaProjectionService() {
        ForegroundServiceUtil.requestStartForegroundService(this,
                getForegroundServiceComponentName(),
                this::createMediaProjection, null);
    }

    /**
     * @return The component name of the foreground service for this test.
     */
    public ComponentName getForegroundServiceComponentName() {
        return new ComponentName(this, LocalMediaProjectionService.class);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PERMISSION_CODE) {
            throw new IllegalStateException("Unknown request code: " + requestCode);
        }
        if (resultCode != RESULT_OK) {
            throw new IllegalStateException("User denied screen sharing permission");
        }
        Log.d(TAG, "onActivityResult");
        mResultCode = resultCode;
        mResultData = data;
        startMediaProjectionService();
    }

    private void createMediaProjection() {
        mMediaProjection = mProjectionManager.getMediaProjection(mResultCode, mResultData);
        mCountDownLatch.countDown();
    }

    public MediaProjection waitForMediaProjection() throws InterruptedException {
        final long timeOutMs = 10000;
        final int retryCount = 5;
        int count = 0;
        // Sometimes system decides to rotate the permission activity to another orientation
        // right after showing it. This results in: uiautomation thinks that accept button appears,
        // we successfully click it in terms of uiautomation, but nothing happens,
        // because permission activity is already recreated.
        // Thus, we try to click that button multiple times.
        do {
            assertTrue("Can't get the permission", count <= retryCount);
            dismissPermissionDialog();
            count++;
        } while (!mCountDownLatch.await(timeOutMs, TimeUnit.MILLISECONDS));
        return mMediaProjection;
    }

    /** The permission dialog will be auto-opened by the activity - find it and accept */
    public void dismissPermissionDialog() {
        // Ensure the device is initialized before interacting with any UI elements.
        final UiDevice uiDevice = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation());

        // Scroll down the dialog; on a device with a small screen the buttons may be below the
        // warning text.
        final boolean isWatch = getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
        if (isWatch) {
            final UiScrollable scrollable = new UiScrollable(new UiSelector().scrollable(true));
            try {
                if (!scrollable.scrollIntoView(new UiSelector().resourceId(ACCEPT_RESOURCE_ID))) {
                    Log.e(TAG, "Didn't find the accept button when scrolling");
                    return;
                }
                Log.d(TAG, "This is a watch; we finished scrolling down to the buttons");
            } catch (UiObjectNotFoundException e) {
                Log.d(TAG, "This is a watch, but there was no scrolling (the UI may not be "
                        + "scrollable");
            }
        }

        UiObject2 acceptButton = uiDevice.wait(Until.findObject(By.res(ACCEPT_RESOURCE_ID)),
                PERMISSION_DIALOG_WAIT_MS);
        if (acceptButton != null) {
            Log.d(TAG, "found permission dialog after searching all windows, clicked");
            acceptButton.click();
        }
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "onResume");
        super.onResume();
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "onPause");
        super.onPause();
    }
}
