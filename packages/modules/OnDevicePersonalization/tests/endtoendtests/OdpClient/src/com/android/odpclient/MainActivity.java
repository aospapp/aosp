/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.odpclient;

import android.app.Activity;
import android.content.Context;
import android.ondevicepersonalization.OnDevicePersonalizationManager;
import android.ondevicepersonalization.SlotResultHandle;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.PersistableBundle;
import android.util.Log;
import android.view.SurfaceControlViewHost.SurfacePackage;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity {
    private static final String TAG = "OdpClient";
    private OnDevicePersonalizationManager mOdpManager = null;

    private EditText mTextBox;
    private Button mGetAdButton;
    private SurfaceView mRenderedView;

    private Context mContext;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mContext = getApplicationContext();
        if (mOdpManager == null) {
            mOdpManager = mContext.getSystemService(OnDevicePersonalizationManager.class);
        }
        mRenderedView = findViewById(R.id.rendered_view);
        mRenderedView.setVisibility(View.INVISIBLE);
        mGetAdButton = findViewById(R.id.get_ad_button);
        mTextBox = findViewById(R.id.text_box);
        registerGetAdButton();
    }

    private void registerGetAdButton() {
        mGetAdButton.setOnClickListener(
                v -> makeRequest());
    }

    private void makeRequest() {
        try {
            if (mOdpManager == null) {
                makeToast("OnDevicePersonalizationManager is null");
                return;
            }
            CountDownLatch latch = new CountDownLatch(1);
            Log.i(TAG, "Starting execute()");
            AtomicReference<SlotResultHandle> slotResultHandle = new AtomicReference<>();
            PersistableBundle appParams = new PersistableBundle();
            appParams.putString("keyword", mTextBox.getText().toString());
            mOdpManager.execute(
                    "com.android.odpsamplenetwork",
                    appParams,
                    Executors.newSingleThreadExecutor(),
                    new OutcomeReceiver<List<SlotResultHandle>, Exception>() {
                        @Override
                        public void onResult(List<SlotResultHandle> result) {
                            makeToast("execute() success: " + result.size());
                            if (result.size() > 0) {
                                slotResultHandle.set(result.get(0));
                            } else {
                                Log.e(TAG, "No results!");
                            }
                            latch.countDown();
                        }

                        @Override
                        public void onError(Exception e) {
                            makeToast("execute() error: " + e.toString());
                            latch.countDown();
                        }
                    });
            latch.await();
            Log.d(TAG, "wait success");
            mOdpManager.requestSurfacePackage(
                    slotResultHandle.get(),
                    mRenderedView.getHostToken(),
                    getDisplay().getDisplayId(),
                    mRenderedView.getWidth(),
                    mRenderedView.getHeight(),
                    Executors.newSingleThreadExecutor(),
                    new OutcomeReceiver<SurfacePackage, Exception>() {
                        @Override
                        public void onResult(SurfacePackage surfacePackage) {
                            makeToast(
                                    "requestSurfacePackage() success: "
                                    + surfacePackage.toString());
                            new Handler(Looper.getMainLooper()).post(() -> {
                                if (surfacePackage != null) {
                                    mRenderedView.setChildSurfacePackage(
                                            surfacePackage);
                                }
                                mRenderedView.setZOrderOnTop(true);
                                mRenderedView.setVisibility(View.VISIBLE);
                            });
                        }

                        @Override
                        public void onError(Exception e) {
                            makeToast("requestSurfacePackage() error: " + e.toString());
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error", e);
        }
    }

    private void makeToast(String message) {
        Log.i(TAG, message);
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
    }
}
