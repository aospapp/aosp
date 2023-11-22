/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.cts.verifier.wifi;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.cts.verifier.PassFailButtons;
import com.android.cts.verifier.R;

/**
 * Base class for Wifi tests.
 */
public abstract class BaseTestActivity extends PassFailButtons.Activity implements
        BaseTestCase.Listener {
    /*
     * Handles to GUI elements.
     */
    private TextView mWifiInfo;
    private ProgressBar mWifiProgress;

    /*
     * Test case to be executed
     */
    private BaseTestCase mTestCase;

    private Handler mHandler = new Handler();

    protected abstract BaseTestCase getTestCase(Context context);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.wifi_main);
        setPassFailButtonClickListeners();
        getPassButton().setEnabled(false);

        // Get UI component.
        mWifiInfo = (TextView) findViewById(R.id.wifi_info);
        mWifiProgress = (ProgressBar) findViewById(R.id.wifi_progress);

        // Initialize test components.
        mTestCase = getTestCase(this);

        // keep screen on while this activity is front view.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mTestCase.start(this);
        mWifiProgress.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mTestCase.stop();
        mWifiProgress.setVisibility(View.GONE);
    }

    @Override
    public void onTestStarted() {
        // nop
    }

    @Override
    public void onTestMsgReceived(String msg) {
        if (msg == null) {
            return;
        }
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mWifiInfo.append(msg);
                mWifiInfo.append("\n");
            }
        });
    }

    @Override
    public void onTestSuccess() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                getPassButton().setEnabled(true);
                mWifiInfo.append(getString(R.string.wifi_status_test_success));
                mWifiInfo.append("\n");
                mWifiProgress.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onTestFailed(String reason) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (reason != null) {
                    mWifiInfo.append(reason);
                }
                getPassButton().setEnabled(false);
                mWifiInfo.append(getString(R.string.wifi_status_test_failed));
                mWifiInfo.append("\n");
                mWifiProgress.setVisibility(View.GONE);
            }
        });
    }
}
