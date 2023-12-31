/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.cts.verifier.audio;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.android.compatibility.common.util.CddTest;
import com.android.cts.verifier.R;

@CddTest(requirement = "7.8.2/C-1-1,C-1-2")
public class USBAudioPeripheralPlayActivity extends USBAudioPeripheralPlayerActivity {
    private static final String TAG = "USBAudioPeripheralPlayActivity";

    // Widgets
    private Button mPlayBtn;
    private LocalClickListener mButtonClickListener = new LocalClickListener();

    public USBAudioPeripheralPlayActivity() {
        super(false); // Mandated peripheral is NOT required
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.uap_play_panel);

        connectPeripheralStatusWidgets();

        // Local widgets
        mPlayBtn = (Button)findViewById(R.id.uap_playPlayBtn);
        mPlayBtn.setOnClickListener(mButtonClickListener);

        setupPlayer();

        setPassFailButtonClickListeners();
        setInfoResources(R.string.usbaudio_play_test, R.string.usbaudio_play_info, -1);

        connectUSBPeripheralUI();
    }

    //
    // USBAudioPeripheralActivity
    // Headset not publicly available, violates CTS Verifier additional equipment guidelines.
    void enableTestUI(boolean enable) {
        mPlayBtn.setEnabled(enable);
    }

    public void updateConnectStatus() {
        mPlayBtn.setEnabled(mIsPeripheralAttached);
        getPassButton().setEnabled(mIsPeripheralAttached);
    }

    public class LocalClickListener implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            if (view.getId() == R.id.uap_playPlayBtn) {
                Log.i(TAG, "Play Button Pressed");
                if (!isPlaying()) {
                    boolean result = startPlay();
                    if (result) {
                        mPlayBtn.setText(getString(R.string.audio_uap_play_stopBtn));
                    }
                } else {
                    boolean result = stopPlay();
                    if (result) {
                        mPlayBtn.setText(getString(R.string.audio_uap_play_playBtn));
                    }
                }
            }
        }
    }
}
